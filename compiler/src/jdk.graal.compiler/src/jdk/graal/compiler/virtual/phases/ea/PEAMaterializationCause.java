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
package jdk.graal.compiler.virtual.phases.ea;

import jdk.graal.compiler.graph.Node;
import jdk.graal.compiler.graph.NodeSourcePosition;
import jdk.graal.compiler.nodes.virtual.PEAMaterializationReason;

/** Metadata captured at the PEA decision that changes a virtual object into a real object. */
record PEAMaterializationCause(PEAMaterializationReason reason, PEAMaterializationReason rootReason,
                String triggerNode, NodeSourcePosition triggerPosition, String detail) {

    static PEAMaterializationCause direct(PEAMaterializationReason reason, Node trigger, String detail) {
        String nodeName = trigger == null ? "<none>" : trigger.getClass().getSimpleName();
        NodeSourcePosition position = trigger == null ? null : trigger.getNodeSourcePosition();
        return new PEAMaterializationCause(reason, reason, nodeName, position, detail == null ? "" : detail);
    }

    PEAMaterializationCause propagated(PEAMaterializationReason propagatedReason) {
        return new PEAMaterializationCause(propagatedReason, rootReason, triggerNode, triggerPosition, detail);
    }
}
