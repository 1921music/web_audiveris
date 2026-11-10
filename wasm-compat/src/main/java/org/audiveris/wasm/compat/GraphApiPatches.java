// SPDX-License-Identifier: AGPL-3.0-or-later
package org.audiveris.wasm.compat;

import org.teavm.model.*;
import org.teavm.model.instructions.*;

/** Keep JGraphT's supplier implementation; adapt only its constructor access query. */
final class GraphApiPatches {
    static void apply(ClassHolder cls) {
        if (!cls.getName().equals("org.jgrapht.util.SupplierUtil")) { return; }
        int replaced = 0;
        MethodReference query = new MethodReference("java.lang.reflect.Constructor", "canAccess", ValueType.object("java.lang.Object"), ValueType.BOOLEAN);
        for (MethodHolder method : cls.getMethods()) {
            Program program = method.getProgram(); if (program == null) { continue; }
            int instructions = 0; boolean changed = false;
            for (BasicBlock block : program.getBasicBlocks()) { for (Instruction instruction : block) {
                instructions++;
                if (!(instruction instanceof InvokeInstruction)) { continue; }
                InvokeInstruction call = (InvokeInstruction) instruction;
                if (!call.getMethod().equals(query)) { continue; }
                Variable owner = call.getInstance(), receiver = call.getArguments().get(0);
                call.setType(InvocationType.SPECIAL); call.setInstance(null);
                call.setMethod(new MethodReference("org.audiveris.wasm.compat.platform.GraphReflectionAccess", "canAccess",
                        ValueType.object("java.lang.reflect.Constructor"), ValueType.object("java.lang.Object"), ValueType.BOOLEAN));
                call.setArguments(owner, receiver); replaced++; changed = true;
            } }
            if (changed) { HeadlessPatches.report(method, "jgrapht-constructor-access-query", program.basicBlockCount(), instructions); }
        }
        if (replaced != 1) { throw new IllegalStateException("Review pinned JGraphT constructor access check: " + replaced); }
    }
}
