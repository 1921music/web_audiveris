// SPDX-License-Identifier: AGPL-3.0-or-later
package org.audiveris.wasm.compat;

import org.teavm.model.*;
import org.teavm.model.instructions.*;

final class ReflectionApiPatches {
    static void apply(ClassHolder cls) {
        ValueType reflected = ValueType.object("java.lang.reflect.Method");
        if (cls.getName().equals("java.lang.reflect.Method")) {
            StandardApiPatches.delegate(cls, "getModifiers", "modifiers", ReflectionMetadata.class.getName(), false, true, ValueType.INTEGER);
        }
        if (!cls.getName().equals(ReflectionMetadata.class.getName())) { return; }
        Program encoded = new Program(); encoded.createVariable(); Variable text = encoded.createVariable();
        BasicBlock block = encoded.createBasicBlock(); StringConstantInstruction constant = new StringConstantInstruction();
        StringBuilder original = new StringBuilder();
        for (String name : System.getProperty("audiveris.compat.reflectionClasses", "").split(",")) {
            if (name.isBlank()) { continue; }
            try {
                Class<?> type = Class.forName(name.trim(), false, ReflectionApiPatches.class.getClassLoader());
                for (java.lang.reflect.Method method : type.getDeclaredMethods()) {
                    original.append(ReflectionMetadata.key(method)).append('|').append(method.getModifiers()).append('\n');
                }
            } catch (ClassNotFoundException failure) { throw new IllegalStateException("Reflection class missing: " + name, failure); }
        }
        constant.setConstant(original.toString()); constant.setReceiver(text); block.add(constant);
        ExitInstruction exit = new ExitInstruction(); exit.setValueToReturn(text); block.add(exit);
        cls.getMethod(new MethodDescriptor("encodedOriginal", ValueType.object("java.lang.String"))).setProgram(encoded);
        Program backend = new Program(); backend.createVariable(); Variable method = backend.createVariable(), result = backend.createVariable();
        BasicBlock body = backend.createBasicBlock(); InvokeInstruction call = new InvokeInstruction(); call.setType(InvocationType.SPECIAL);
        call.setMethod(new MethodReference("java.lang.reflect.Executable", "getModifiers", ValueType.INTEGER));
        call.setInstance(method); call.setReceiver(result); body.add(call);
        ExitInstruction done = new ExitInstruction(); done.setValueToReturn(result); body.add(done);
        cls.getMethod(new MethodDescriptor("backendModifiers", reflected, ValueType.INTEGER)).setProgram(backend);
    }
}
