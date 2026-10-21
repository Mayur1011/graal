/*
 * Copyright (c) 2026, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.  Oracle designates this
 * particular file as subject to the "Classpath" exception as provided
 * by Oracle in the LICENSE file that accompanied this code.
 *
 * This code is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 * version 2 for more details (a copy is included in the LICENSE file that
 * accompanied this code).
 *
 * You should have received a copy of the GNU General Public License version
 * 2 along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 *
 * Please contact Oracle, 500 Oracle Parkway, Redwood Shores, CA 94065 USA
 * or visit www.oracle.com if you need additional information or have any
 * questions.
 */
package jdk.graal.compiler.phases.common.priorityinline.data;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import jdk.graal.compiler.debug.TTY;
import jdk.graal.compiler.graph.NodeSourcePosition;
import jdk.graal.compiler.nodes.Invoke;
import jdk.graal.compiler.options.OptionValues;
import jdk.graal.compiler.phases.common.priorityinline.PriorityInliningPhase;
import jdk.vm.ci.meta.ResolvedJavaMethod;

/**
 * Loads an offline escape-analysis summary (a {@code .res} fact file) that names call sites whose
 * inlining lets PEA scalar-replace an allocation. See
 * {@link PriorityInliningPhase.Options#OfflineEscapeSummaryFile}.
 *
 * File format: one fact per line, {@code class;method;descriptor;callBci}. A trailing
 * {@code ;allocationBci} field is accepted but ignored (kept only for a human reading the file).
 * Blank lines and lines starting with {@code #} are ignored. The descriptor is the JVM method
 * descriptor and may itself contain {@code ;} (for example {@code (Lfoo/Bar;)V}), so the numeric
 * fields are read from the end of the line. A malformed line is reported and skipped; it never
 * fails a compilation.
 */
public final class OfflineEscapeHints {

    private static final ConcurrentHashMap<String, Set<String>> CACHE = new ConcurrentHashMap<>();
    private static final Set<String> REPORTED = ConcurrentHashMap.newKeySet();

    private OfflineEscapeHints() {
    }

    public static boolean hasOfflineBenefit(OptionValues options, ResolvedJavaMethod method, int callBci) {
        String file = PriorityInliningPhase.Options.OfflineEscapeSummaryFile.getValue(options);
        if (file == null) {
            return false;
        }
        String key = key(method.getDeclaringClass().toJavaName(), method.getName(), method.getSignature().toMethodDescriptor(), callBci);
        boolean matched = load(file).contains(key);
        if (matched && REPORTED.add("matched " + key)) {
            TTY.println("[OfflineEscapeHints] matched " + key);
        }
        return matched;
    }

    /**
     * Logs (once per call site) that a call carrying the offline hint was really inlined. A
     * {@code matched} line without a following {@code inlined} line means the hint raised the
     * call's priority but the inliner still refused it (budget, recursion, not inlinable).
     */
    public static void reportInlined(Invoke invoke) {
        NodeSourcePosition position = invoke.asNode().getNodeSourcePosition();
        if (position == null || position.getMethod() == null) {
            return;
        }
        ResolvedJavaMethod caller = position.getMethod();
        String key = key(caller.getDeclaringClass().toJavaName(), caller.getName(), caller.getSignature().toMethodDescriptor(), position.getBCI());
        String target = invoke.callTarget().targetMethod().format("%H.%n");
        // One line per (call site, target): a polymorphic call site can inline several targets.
        if (REPORTED.add("inlined " + key + " -> " + target)) {
            TTY.println("[OfflineEscapeHints] inlined " + key + " -> " + target);
        }
    }

    /**
     * Whether a call site carrying the offline hint must be inlined regardless of the inlining
     * budget ({@link PriorityInliningPhase.Options#OfflineEscapeForceInline}). Recursive call sites
     * are never forced, so the expansion always terminates.
     */
    public static boolean forcesInlining(OptionValues options, EnumSet<BenefitKind> benefits, int recursionDepth) {
        return recursionDepth == 0 && benefits.contains(BenefitKind.OfflineNonEscaping) &&
                        PriorityInliningPhase.Options.OfflineEscapeForceInline.getValue(options);
    }

    private static Set<String> load(String file) {
        return CACHE.computeIfAbsent(file, OfflineEscapeHints::parse);
    }

    private static Set<String> parse(String file) {
        Set<String> keys = ConcurrentHashMap.newKeySet();
        List<String> lines;
        try {
            lines = Files.readAllLines(Path.of(file));
        } catch (IOException e) {
            TTY.println("[OfflineEscapeHints] cannot read " + file + ": " + e);
            return keys;
        }
        for (String line : lines) {
            String trimmed = line.trim();
            if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                continue;
            }
            String key = parseKey(trimmed);
            if (key == null) {
                TTY.println("[OfflineEscapeHints] ignoring malformed line (expected class;method;descriptor;callBci[;allocationBci]): " + line);
            } else {
                keys.add(key);
            }
        }
        TTY.println("[OfflineEscapeHints] loaded " + keys.size() + " facts from " + file);
        return keys;
    }

    /**
     * Parses {@code class;method;descriptor;callBci[;allocationBci]}. Splitting on {@code ;} alone
     * is ambiguous because object types in the descriptor end in {@code ;}, so the one or two
     * trailing numeric fields are peeled off first and everything between the method name and
     * them is the descriptor.
     */
    private static String parseKey(String line) {
        String[] fields = line.split(";", -1);
        int end = fields.length;
        if (end >= 5 && isNumber(fields[end - 1]) && isNumber(fields[end - 2])) {
            end--; // drop the optional allocationBci
        }
        if (end < 4 || !isNumber(fields[end - 1])) {
            return null;
        }
        String descriptor = String.join(";", Arrays.copyOfRange(fields, 2, end - 1));
        return key(fields[0], fields[1], descriptor, Integer.parseInt(fields[end - 1]));
    }

    private static boolean isNumber(String field) {
        return !field.isEmpty() && field.length() <= 9 && field.chars().allMatch(Character::isDigit);
    }

    private static String key(String className, String method, String descriptor, int callBci) {
        return className + '.' + method + descriptor + '@' + callBci;
    }
}
