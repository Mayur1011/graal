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
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

import jdk.graal.compiler.debug.PathUtilities;
import jdk.graal.compiler.graph.Node;
import jdk.graal.compiler.graph.NodeSourcePosition;
import jdk.graal.compiler.nodes.FixedNode;
import jdk.graal.compiler.nodes.StructuredGraph;
import jdk.graal.compiler.nodes.debug.DynamicCounterNode;
import jdk.graal.compiler.nodes.extended.BoxNode;
import jdk.graal.compiler.nodes.spi.VirtualizableAllocation;
import jdk.graal.compiler.nodes.virtual.AllocatedObjectNode;
import jdk.graal.compiler.nodes.virtual.CommitAllocationNode;
import jdk.graal.compiler.nodes.virtual.PEAMaterializationReason;
import jdk.graal.compiler.options.OptionValues;
import jdk.vm.ci.meta.ResolvedJavaMethod;

/**
 * Retains the compilation id and complete inlining chain, so an allocation scalar-replaced in one
 * compilation is not accidentally merged with a materialized allocation in another compilation.
 */
public final class PEAEffectivenessReporter {

    private static final ConcurrentHashMap<Context, ContextRecord> contexts = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<MaterializationOccurrence, Boolean> materializations = new ConcurrentHashMap<>();
    private static final Map<StructuredGraph, IdentityHashMap<Node, Boolean>> instrumentedCandidates = new IdentityHashMap<>();
    private static final AtomicBoolean snapshotDirty = new AtomicBoolean();

    private PEAEffectivenessReporter() {
    }

    public static boolean enabled(OptionValues options) {
        return PartialEscapePhase.effectivenessReportEnabled(options) && PartialEscapePhase.effectivenessReportFile(options) != null;
    }

    /**
     * Returns whether the node originates in one of the configured application class prefixes.
     */
    public static boolean isInScope(Node node, OptionValues options) {
        return siteOf(node, options) != null;
    }

    /**
     * Records candidates before PEA can remove them. The runtime counter is installed once per graph
     * node even when the PEA phase runs more than once.
     */
    public static void recordCandidates(StructuredGraph graph) {
        OptionValues options = graph.getOptions();
        if (!enabled(options) && !PartialEscapePhase.runtimeCountersEnabled(options)) {
            return;
        }
        for (Node node : graph.getNodes()) {
            if (node instanceof FixedNode fixed && node instanceof VirtualizableAllocation &&
                            !(node instanceof CommitAllocationNode) && !(node instanceof BoxNode)) {
                Context context = contextOf(graph, fixed, options);
                if (context == null) {
                    continue;
                }
                if (enabled(options)) {
                    contexts.computeIfAbsent(context, ContextRecord::new).candidate = true;
                    snapshotDirty.set(true);
                }
                if (PartialEscapePhase.runtimeCountersEnabled(options) && markCandidateInstrumented(graph, fixed)) {
                    DynamicCounterNode.addCounterBefore("PEA outcomes", "candidate executions", 1, false, fixed);
                }
            }
        }
    }

    /**
     * Records a virtualization only when its graph effect is applied.
     */
    public static void recordVirtualized(StructuredGraph graph, Node allocation) {
        Context context = contextOf(graph, allocation, graph.getOptions());
        if (context != null && enabled(graph.getOptions())) {
            ContextRecord record = contexts.computeIfAbsent(context, ContextRecord::new);
            record.candidate = true;
            record.virtualized = true;
            snapshotDirty.set(true);
        }
    }

    /**
     * Records a tracked virtual object and its occurrence-specific reason when they survive to
     * commit lowering.
     */
    public static void recordFinalHeap(StructuredGraph graph, Node virtual, AllocatedObjectNode allocation) {
        Context context = contextOf(graph, virtual, graph.getOptions());
        if (context != null && enabled(graph.getOptions())) {
            ContextRecord record = contexts.computeIfAbsent(context, ContextRecord::new);
            record.candidate = true;
            record.virtualized = true;
            record.finalHeap = true;
            PEAMaterializationReason reason = allocation.getPEAMaterializationReason();
            record.materializationReasons.add(reason.id());
            SourceLocation trigger = sourceLocation(allocation.getPEAMaterializationTriggerPosition());
            MaterializationOccurrence occurrence = new MaterializationOccurrence(context, allocation.getId(), reason,
                            allocation.getPEARootMaterializationReason(), allocation.getPEAMaterializationTriggerNode(), trigger,
                            allocation.getPEAMaterializationDetail());
            materializations.put(occurrence, Boolean.TRUE);
            snapshotDirty.set(true);
        }
    }

    /**
     * Atomically publishes a complete replacement snapshot when tracked state has changed.
     */
    public static synchronized void writeSnapshot(OptionValues options) {
        if (!enabled(options) || !snapshotDirty.compareAndSet(true, false)) {
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
            out.println("record;class;method;descriptor;bci;contexts;observed_outcomes;materialization_reasons;reason_classification;classification");
            for (SiteSummary summary : summaries) {
                out.printf("site;%s;%s;%s;%d;%d;%s;%s;%s;%s%n", field(summary.site.className), field(summary.site.method),
                                field(summary.site.descriptor), summary.site.bci, summary.contexts,
                                summary.outcomes(), summary.materializationReasons(), summary.reasonClassification(), summary.classification());
            }
            out.printf("summary;total_candidates;%d%n", summaries.size());
            out.printf("summary;always_not_virtualized;%d%n", notVirtualized);
            out.printf("summary;always_scalar_replaced;%d%n", scalarReplaced);
            out.printf("summary;always_materialized;%d%n", finallyMaterialized);
            out.printf("summary;mixed;%d%n", mixed);
        } catch (IOException e) {
            snapshotDirty.set(true);
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
            snapshotDirty.set(true);
            throw new RuntimeException("Could not publish PEA effectiveness snapshot to " + file, e);
        }

        writeMaterializationSnapshot(options);
    }

    private static void writeMaterializationSnapshot(OptionValues options) {
        String file = PartialEscapePhase.materializationReasonReportFile(options);
        if (file == null) {
            return;
        }
        ArrayList<MaterializationOccurrence> rows = new ArrayList<>(materializations.keySet());
        rows.sort(Comparator.comparing(MaterializationOccurrence::id));
        int[] counts = new int[PEAMaterializationReason.values().length];
        for (MaterializationOccurrence row : rows) {
            counts[row.reason.ordinal()]++;
        }

        String temporaryFile = file + ".tmp";
        try (PrintStream out = new PrintStream(PathUtilities.openOutputStream(temporaryFile))) {
            out.println("record;allocation_class;allocation_method;allocation_descriptor;allocation_bci;" +
                            "compilation;inline_context;materialization_node_id;reason_family;reason;root_reason;" +
                            "trigger_node;trigger_class;trigger_method;trigger_descriptor;trigger_bci;detail");
            for (MaterializationOccurrence row : rows) {
                SourceLocation trigger = row.trigger;
                out.printf("materialization;%s;%s;%s;%d;%s;%s;%d;%s;%s;%s;%s;%s;%s;%s;%d;%s%n",
                                field(row.context.site.className), field(row.context.site.method), field(row.context.site.descriptor),
                                row.context.site.bci, field(row.context.compilation), field(row.context.inlineContext), row.nodeId,
                                row.reason.family(), row.reason.id(), row.rootReason.id(), field(row.triggerNode), field(trigger.className),
                                field(trigger.method), field(trigger.descriptor), trigger.bci, field(row.detail));
            }
            out.printf("summary;total_final_materialization_occurrences;%d%n", rows.size());
            for (PEAMaterializationReason reason : PEAMaterializationReason.values()) {
                out.printf("summary;reason_%s;%d%n", reason.id(), counts[reason.ordinal()]);
            }
        } catch (IOException e) {
            snapshotDirty.set(true);
            throw new RuntimeException("Could not write PEA materialization-reason snapshot to " + temporaryFile, e);
        }
        publishSnapshot(temporaryFile, file, "PEA materialization-reason");
    }

    private static void publishSnapshot(String temporaryFile, String file, String description) {
        Path temporaryPath = Path.of(temporaryFile);
        Path outputPath = Path.of(file);
        try {
            try {
                Files.move(temporaryPath, outputPath, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temporaryPath, outputPath, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            snapshotDirty.set(true);
            throw new RuntimeException("Could not publish " + description + " snapshot to " + file, e);
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

    private static SourceLocation sourceLocation(NodeSourcePosition position) {
        if (position == null || position.getMethod() == null) {
            return new SourceLocation("", "", "", -1);
        }
        ResolvedJavaMethod method = position.getMethod();
        return new SourceLocation(method.getDeclaringClass().toJavaName(), method.getName(), method.getSignature().toMethodDescriptor(), position.getBCI());
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


    private record Site(String className, String method, String descriptor, int bci) {
        String id() {
            return className + '.' + method + descriptor + '@' + bci;
        }
    }

    private record SourceLocation(String className, String method, String descriptor, int bci) {
    }

    /**
     * A source allocation can occur in normal, OSR, recompiled, and differently inlined graphs.
     * These are separate contexts because PEA can produce a different result in each one.
     */
    private record Context(Site site, String compilation, String inlineContext) {
        String id() {
            return compilation + ':' + inlineContext;
        }
    }

    private record MaterializationOccurrence(Context context, int nodeId, PEAMaterializationReason reason,
                    PEAMaterializationReason rootReason, String triggerNode, SourceLocation trigger, String detail) {
        String id() {
            return context.id() + ':' + nodeId + ':' + reason.id();
        }
    }

    private static final class ContextRecord {
        final Context context;
        volatile boolean candidate;
        volatile boolean virtualized;
        volatile boolean finalHeap;
        final Set<String> materializationReasons = ConcurrentHashMap.newKeySet();

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
        final LinkedHashSet<String> materializationReasons = new LinkedHashSet<>();
        int contexts;

        SiteSummary(Site site) {
            this.site = site;
        }

        void add(ContextRecord row) {
            contexts++;
            outcomes.add(row.classification());
            materializationReasons.addAll(row.materializationReasons);
        }

        String outcomes() {
            return String.join("|", outcomes);
        }

        String materializationReasons() {
            ArrayList<String> sorted = new ArrayList<>(materializationReasons);
            sorted.sort(String::compareTo);
            return String.join("|", sorted);
        }

        String reasonClassification() {
            if (materializationReasons.isEmpty()) {
                return "not_materialized";
            }
            if (materializationReasons.size() == 1) {
                return materializationReasons.iterator().next();
            }
            return "multiple_reasons";
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
