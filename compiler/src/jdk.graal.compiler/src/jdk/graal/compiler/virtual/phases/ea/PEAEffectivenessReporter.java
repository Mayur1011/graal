package jdk.graal.compiler.virtual.phases.ea;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import jdk.graal.compiler.debug.PathUtilities;
import jdk.graal.compiler.graph.Node;
import jdk.graal.compiler.graph.NodeSourcePosition;
import jdk.graal.compiler.nodes.FixedNode;
import jdk.graal.compiler.nodes.StructuredGraph;
import jdk.graal.compiler.nodes.debug.DynamicCounterNode;
import jdk.graal.compiler.nodes.extended.BoxNode;
import jdk.graal.compiler.nodes.spi.VirtualizableAllocation;
import jdk.graal.compiler.nodes.virtual.CommitAllocationNode;
import jdk.graal.compiler.options.OptionValues;
import jdk.vm.ci.meta.ResolvedJavaMethod;

/**
 * Retains the compilation id and complete inlining chain, so a scalar-replaced allocation in one compilation is not accidentally merged with a materialized allocation in another compilation.
 */
public final class PEAEffectivenessReporter {

    private static final ConcurrentHashMap<Context, ContextRecord> contexts = new ConcurrentHashMap<>();
    private static final Map<StructuredGraph, IdentityHashMap<Node, Boolean>> instrumentedCandidates = new IdentityHashMap<>();

    private PEAEffectivenessReporter() {
    }

    public static boolean enabled(OptionValues options) {
        return PartialEscapePhase.effectivenessReportEnabled(options) && PartialEscapePhase.effectivenessReportFile(options) != null;
    }

    /** True if the node originates in one of the configured application class prefixes. */
    public static boolean isInScope(Node node, OptionValues options) {
        return siteOf(node, options) != null;
    }

    /**
     * Records candidates before PEA can remove them.
     The runtime counter is installed once per graph node even when the PEA phase runs more than once.
     look for all the nodes with VirtualizableAllocation, these are the nodes that can be replaced with a virtual allocation.
     */
    public static void recordCandidates(StructuredGraph graph) {
        OptionValues options = graph.getOptions();
        if (!enabled(options) && !PartialEscapePhase.runtimeCountersEnabled(options)) {
            return;
        }
        for (Node node : graph.getNodes()) {
            if (node instanceof FixedNode fixed && node instanceof VirtualizableAllocation && !(node instanceof CommitAllocationNode) && !(node instanceof BoxNode)) {
                Context context = contextOf(graph, fixed, options);
                if (context == null) {
                    continue;
                }
                if (enabled(options)) {
                    contexts.computeIfAbsent(context, ContextRecord::new).candidate = true;
                }
                if (PartialEscapePhase.runtimeCountersEnabled(options) && markCandidateInstrumented(graph, fixed)) {
                    DynamicCounterNode.addCounterBefore("PEA outcomes", "candidate executions", 1, false, fixed);
                }
            }
        }
    }

    /** Called only when the virtualization effect is applied to the graph. */
    public static void recordVirtualized(StructuredGraph graph, Node allocation) {
        Context context = contextOf(graph, allocation, graph.getOptions());
        if (context != null && enabled(graph.getOptions())) {
            ContextRecord record = contexts.computeIfAbsent(context, ContextRecord::new);
            record.candidate = true;
            record.virtualized = true;
        }
    }

    /** Called when a tracked virtual object survives to commit lowering. */
    public static void recordFinalHeap(StructuredGraph graph, Node virtual) {
        Context context = contextOf(graph, virtual, graph.getOptions());
        if (context != null && enabled(graph.getOptions())) {
            ContextRecord record = contexts.computeIfAbsent(context, ContextRecord::new);
            record.candidate = true;
            record.virtualized = true;
            record.finalHeap = true;
        }
    }

    /** Writes a complete replacement snapshot. */
    public static synchronized void writeSnapshot(OptionValues options) {
        if (!enabled(options)) {
            return;
        }
        ArrayList<ContextRecord> rows = new ArrayList<>(contexts.values());
        rows.sort(Comparator.comparing(record -> record.context.id()));

        Map<Site, SiteSummary> sites = new ConcurrentHashMap<>();
        for (ContextRecord row : rows) {
            if (row.candidate) {
                sites.computeIfAbsent(row.context.site, SiteSummary::new).add(row);
            }
        }
        ArrayList<SiteSummary> summaries = new ArrayList<>(sites.values());
        summaries.sort(Comparator.comparing(summary -> summary.site.id()));

        int notVirtualized = 0;
        int scalarReplaced = 0;
        int finallyMaterialized = 0;
        int mixed = 0;
        for (SiteSummary summary : summaries) {
            switch (summary.classification()) {
                case "always_not_virtualized" -> notVirtualized++;
                case "always_scalar_replaced" -> scalarReplaced++;
                case "always_materialized" -> finallyMaterialized++;
                default -> mixed++;
            }
        }

        String file = PartialEscapePhase.effectivenessReportFile(options);
        String temporaryFile = file + ".tmp";
        try (PrintStream out = new PrintStream(PathUtilities.openOutputStream(temporaryFile))) {
            out.println("record;class;method;descriptor;bci;contexts;observed_outcomes;classification");
            for (SiteSummary summary : summaries) {
                out.printf("site;%s;%s;%s;%d;%d;%s;%s%n", field(summary.site.className), field(summary.site.method),
                                field(summary.site.descriptor), summary.site.bci, summary.contexts,
                                summary.outcomes(), summary.classification());
            }
            out.printf("summary;total_candidates;%d%n", summaries.size());
            out.printf("summary;always_not_virtualized;%d%n", notVirtualized);
            out.printf("summary;always_scalar_replaced;%d%n", scalarReplaced);
            out.printf("summary;always_materialized;%d%n", finallyMaterialized);
            out.printf("summary;mixed;%d%n", mixed);
        } catch (IOException e) {
            throw new RuntimeException("Could not write PEA effectiveness snapshot to " + temporaryFile, e);
        }

        /*
         * A HotSpot shutdown can stop a background compiler thread at any point. Never truncate
         * the last complete public report while constructing its replacement. On file systems
         * without atomic rename support, the completed temporary file is still moved only after
         * its stream has been closed.
         */
        Path temporaryPath = Path.of(temporaryFile);
        Path outputPath = Path.of(file);
        try {
            try {
                Files.move(temporaryPath, outputPath, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temporaryPath, outputPath, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            throw new RuntimeException("Could not publish PEA effectiveness snapshot to " + file, e);
        }
    }

    private static String field(String value) {
        return value.replace("%", "%25").replace(";", "%3B").replace("\r", "%0D").replace("\n", "%0A");
    }

    private static synchronized boolean markCandidateInstrumented(StructuredGraph graph, Node node) {
        IdentityHashMap<Node, Boolean> nodes = instrumentedCandidates.computeIfAbsent(graph, unused -> new IdentityHashMap<>());
        return nodes.put(node, Boolean.TRUE) == null;
    }

    private static Context contextOf(StructuredGraph graph, Node node, OptionValues options) {
        Site site = siteOf(node, options);
        if (site == null) {
            return null;
        }
        return new Context(site, String.valueOf(graph.compilationId()), inlineContext(node.getNodeSourcePosition()));
    }

    private static Site siteOf(Node node, OptionValues options) {
        NodeSourcePosition position = node.getNodeSourcePosition();
        if (position == null || position.getMethod() == null) {
            return null;
        }
        ResolvedJavaMethod method = position.getMethod();
        String className = method.getDeclaringClass().toJavaName();
        if (!matchesScope(className, PartialEscapePhase.effectivenessReportFilter(options))) {
            return null;
        }
        return new Site(className, method.getName(), method.getSignature().toMethodDescriptor(), position.getBCI());
    }

    private static boolean matchesScope(String className, String scope) {
        if (scope == null || scope.isBlank()) {
            return false;
        }
        for (String prefix : scope.split(",")) {
            if (className.startsWith(prefix.trim())) {
                return true;
            }
        }
        return false;
    }

    private static String inlineContext(NodeSourcePosition position) {
        StringBuilder builder = new StringBuilder();
        for (NodeSourcePosition current = position; current != null; current = current.getCaller()) {
            if (!builder.isEmpty()) {
                builder.append(" <- ");
            }
            ResolvedJavaMethod method = current.getMethod();
            builder.append(method.getDeclaringClass().toJavaName()).append('.').append(method.getName())
                            .append(method.getSignature().toMethodDescriptor()).append('@').append(current.getBCI());
        }
        return builder.toString();
    }


    // learnt a new concept (record in used to define a class who will store immutable values. best to uses are keys in hashmap)
    private record Site(String className, String method, String descriptor, int bci) {
        String id() {
            return className + '.' + method + descriptor + '@' + bci;
        }
    }

    /*
     a method can be compiled multiple times with different inline contexts.
     The same allocation bytecode may appear in:
       - A normal compilation.
       - An OSR compilation.
       - A recompilation.
       - An inlined copy under one caller.
       - Another inlined copy under a different caller.
       These are separate contexts because PEA may produce different results in each.
     */
    private record Context(Site site, String compilation, String inlineContext) {
        String id() {
            return compilation + ':' + inlineContext;
        }
    }

    private static final class ContextRecord {
        final Context context;
        volatile boolean candidate;
        volatile boolean virtualized;
        volatile boolean finalHeap;

        ContextRecord(Context context) {
            this.context = context;
        }

        String classification() {
            if (!virtualized) {
                return "not_virtualized";
            }
            return finalHeap ? "finally_materialized" : "scalar_replaced";
        }
    }

    private static final class SiteSummary {
        final Site site;
        final LinkedHashSet<String> outcomes = new LinkedHashSet<>();
        int contexts;

        SiteSummary(Site site) {
            this.site = site;
        }

        void add(ContextRecord row) {
            contexts++;
            outcomes.add(row.classification());
        }

        String outcomes() {
            return String.join("|", outcomes);
        }

        String classification() {
            if (outcomes.size() != 1) {
                return "mixed";
            }
            return switch (outcomes.iterator().next()) {
                case "not_virtualized" -> "always_not_virtualized";
                case "scalar_replaced" -> "always_scalar_replaced";
                case "finally_materialized" -> "always_materialized";
                default -> "mixed";
            };
        }
    }
}
