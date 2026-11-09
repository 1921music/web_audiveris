// SPDX-License-Identifier: AGPL-3.0-or-later
package org.audiveris.wasm.compat;

import java.util.HashSet;
import java.util.Set;
import org.teavm.model.*;
import org.teavm.model.instructions.*;

/** TeaVM 0.16 has a locale setter, but does not call it when materializing a bundle provider. */
final class ResourceBundlePatches {
    static void apply(ClassHolder cls) {
        if (!cls.getName().equals("java.util.ResourceBundle")) { return; }
        ValueType string = ValueType.object("java.lang.String"), bundle = ValueType.object(cls.getName());
        MethodHolder method = cls.getMethod(new MethodDescriptor("handleGetBundle", string, string, ValueType.BOOLEAN, bundle));
        if (method == null) { throw new IllegalStateException("Review ResourceBundle locale repair for this TeaVM version"); }
        Program program = method.getProgram(); Set<Variable> provided = new HashSet<>();
        int repaired = 0;
        for (int i = 0; i < program.basicBlockCount(); i++) {
            for (Instruction instruction : program.basicBlockAt(i)) {
                if (instruction instanceof InvokeInstruction) {
                    InvokeInstruction call = (InvokeInstruction) instruction;
                    if (call.getMethod().getClassName().equals("java.util.function.Supplier") && call.getMethod().getName().equals("get")) {
                        provided.add(call.getReceiver());
                    }
                }
            }
        }
        for (int i = 0; i < program.basicBlockCount(); i++) {
            for (Instruction instruction : program.basicBlockAt(i)) {
                if (instruction instanceof CastInstruction) {
                    CastInstruction cast = (CastInstruction) instruction;
                    if (bundle.equals(cast.getTargetType()) && provided.contains(cast.getValue())) {
                        InvokeInstruction locale = new InvokeInstruction(); locale.setType(InvocationType.SPECIAL);
                        locale.setMethod(new MethodReference(cls.getName(), "setLocale", string, ValueType.VOID));
                        locale.setInstance(cast.getReceiver()); locale.setArguments(program.variableAt(2)); cast.insertNext(locale);
                        repaired++;
                    }
                }
            }
        }
        if (repaired != 1) { throw new IllegalStateException("Review ResourceBundle provider/locale repair: matched " + repaired + " sites"); }
    }
}
