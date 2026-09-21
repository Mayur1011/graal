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
package jdk.graal.compiler.nodes.virtual;

/**
 * A bounded classification of the operation or compiler constraint that caused PEA to materialize a
 * virtual object. The lower-case name is stable because it is used in experiment CSV files and
 * dynamic-counter names.
 */
public enum PEAMaterializationReason {
    CALL_ARGUMENT("program_escape"),
    METHOD_RETURN("program_escape"),
    EXCEPTION_UNWIND("program_escape"),
    STATIC_FIELD_STORE("program_escape"),
    INSTANCE_FIELD_STORE("program_escape"),
    ARRAY_STORE("program_escape"),
    UNSUPPORTED_USE("pea_limitation"),
    MERGE_INCOMPATIBLE("control_flow"),
    PHI_INCOMPATIBLE("control_flow"),
    LOOP_EXIT_STATE("loop_handling"),
    LOOP_ANALYSIS_LIMIT("loop_handling"),
    LOCK_CONSTRAINT("locking"),
    VIRTUALIZER_REQUESTED("pea_internal"),
    OBJECT_GRAPH_DEPENDENCY("dependency"),
    LOCK_ORDER_DEPENDENCY("dependency"),
    OTHER_UNKNOWN("unknown");

    private final String family;

    PEAMaterializationReason(String family) {
        this.family = family;
    }

    public String family() {
        return family;
    }

    public String id() {
        return name().toLowerCase(java.util.Locale.ROOT);
    }
}
