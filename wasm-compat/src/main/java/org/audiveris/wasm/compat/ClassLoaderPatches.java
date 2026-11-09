// SPDX-License-Identifier: AGPL-3.0-or-later
package org.audiveris.wasm.compat;

import org.teavm.model.*;
import org.teavm.model.instructions.*;

final class ClassLoaderPatches {
    static void apply(ClassHolder cls) {
        if (!cls.getName().equals("java.lang.ClassLoader")) { return; }
        ValueType string = ValueType.object("java.lang.String"), loader = ValueType.object(cls.getName());
        StandardApiPatches.delegate(cls, "getPlatformClassLoader", "platformLoader", LoaderMetadata.class.getName(), true, false, loader);
        StandardApiPatches.delegate(cls, "registerAsParallelCapable", "registerParallel", LoaderMetadata.class.getName(), true, false, ValueType.BOOLEAN);
        StandardApiPatches.delegate(cls, "isRegisteredAsParallelCapable", "isParallel", LoaderMetadata.class.getName(), false, false, ValueType.BOOLEAN);
        MethodDescriptor named = new MethodDescriptor("<init>", string, loader, ValueType.VOID);
        if (cls.getMethod(named) != null) { return; }
        FieldHolder name = new FieldHolder("__audiverisLoaderName"); name.setType(string); name.setLevel(AccessLevel.PRIVATE); cls.addField(name);
        FieldReference field = new FieldReference(cls.getName(), name.getName());
        MethodHolder constructor = new MethodHolder(named); constructor.setLevel(AccessLevel.PROTECTED);
        Program program = new Program(); Variable self = program.createVariable(), supplied = program.createVariable(), parent = program.createVariable(), checked = program.createVariable();
        BasicBlock entry = program.createBasicBlock();
        InvokeInstruction init = new InvokeInstruction(); init.setType(InvocationType.SPECIAL);
        init.setMethod(new MethodReference(cls.getName(), "<init>", loader, ValueType.VOID)); init.setInstance(self); init.setArguments(parent); entry.add(init);
        InvokeInstruction validate = new InvokeInstruction(); validate.setType(InvocationType.SPECIAL);
        validate.setMethod(new MethodReference(LoaderMetadata.class.getName(), "validateName", string, string)); validate.setArguments(supplied); validate.setReceiver(checked); entry.add(validate);
        PutFieldInstruction put = new PutFieldInstruction(); put.setField(field); put.setFieldType(string); put.setInstance(self); put.setValue(checked); entry.add(put);
        entry.add(new ExitInstruction()); constructor.setProgram(program); cls.addMethod(constructor);
        MethodDescriptor getter = new MethodDescriptor("getName", string);
        if (cls.getMethod(getter) == null) {
            MethodHolder method = new MethodHolder(getter); method.setLevel(AccessLevel.PUBLIC); method.getModifiers().add(ElementModifier.FINAL);
            Program read = new Program(); Variable owner = read.createVariable(), result = read.createVariable(); BasicBlock block = read.createBasicBlock();
            GetFieldInstruction get = new GetFieldInstruction(); get.setField(field); get.setFieldType(string); get.setInstance(owner); get.setReceiver(result); block.add(get);
            ExitInstruction exit = new ExitInstruction(); exit.setValueToReturn(result); block.add(exit); method.setProgram(read); cls.addMethod(method);
        }
    }
}
