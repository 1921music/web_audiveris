// SPDX-License-Identifier: AGPL-3.0-or-later
package org.audiveris.wasm.compat;

import java.util.Set;
import org.teavm.model.*;
import org.teavm.model.instructions.*;

/** Opt-in AA fill profile: retain JDK Marlin algorithms, replace only their buffer storage. */
final class MarlinAlphaPatches {
    private static final String MEMORY = "org.audiveris.wasm.compat.platform.MarlinMemory";
    private static final String OFF_HEAP = "sun.java2d.marlin.OffHeapArray";
    static void apply(ClassHolder cls) {
        if (cls.getName().equals("sun.java2d.pipe.Region")) {
            MethodHolder ids = cls.getMethod(new MethodDescriptor("initIDs",ValueType.VOID));
            if (ids == null) throw new IllegalStateException("Review Region native initialization");
            Program p = new Program(); p.createVariable(); p.createBasicBlock().add(new ExitInstruction());
            ids.getModifiers().remove(ElementModifier.NATIVE); ids.setProgram(p);
        }
        if (!cls.getName().startsWith("sun.java2d.marlin.")) return;
        if (cls.getName().equals(OFF_HEAP)) {
            MethodHolder constructor = cls.getMethod(new MethodDescriptor("<init>",ValueType.object("java.lang.Object"),ValueType.LONG,ValueType.VOID));
            if (constructor == null) throw new IllegalStateException("Review Marlin buffer constructor");
            Program p = new Program(); Variable self = p.createVariable(), parent = p.createVariable(), length = p.createVariable(), address = p.createVariable();
            BasicBlock b = p.createBasicBlock();
            InvokeInstruction allocate = new InvokeInstruction(); allocate.setType(InvocationType.SPECIAL);
            allocate.setMethod(new MethodReference(MEMORY,"allocate",ValueType.object("java.lang.Object"),ValueType.LONG,ValueType.LONG));
            allocate.setArguments(parent,length); allocate.setReceiver(address); b.add(allocate);
            put(b,self,"address",ValueType.LONG,address); put(b,self,"length",ValueType.LONG,length);
            b.add(new ExitInstruction()); constructor.setProgram(p);
            MethodHolder initializer = cls.getMethod(new MethodDescriptor("<clinit>",ValueType.VOID));
            p = new Program(); p.createVariable(); Variable four = p.createVariable(); b = p.createBasicBlock();
            IntegerConstantInstruction constant = new IntegerConstantInstruction(); constant.setConstant(4); constant.setReceiver(four); b.add(constant);
            put(b,null,"SIZE_INT",ValueType.INTEGER,four); b.add(new ExitInstruction()); initializer.setProgram(p);
        }
        for (MethodHolder method : cls.getMethods()) {
            if (method.getProgram() == null) continue;
            for (BasicBlock block : method.getProgram().getBasicBlocks()) for (Instruction instruction : block) {
                if (instruction instanceof InvokeInstruction call && call.getMethod().getClassName().equals("jdk.internal.misc.Unsafe")) {
                    if (!Set.of("getByte","putByte","getInt","putInt","reallocateMemory","freeMemory","setMemory").contains(call.getMethod().getName()))
                        throw new IllegalStateException("Review Marlin Unsafe operation: " + call.getMethod());
                    call.setInstance(null); call.setType(InvocationType.SPECIAL);
                    call.setMethod(new MethodReference(MEMORY,call.getMethod().getDescriptor()));
                } else if (instruction instanceof GetFieldInstruction get && get.getField().getFieldName().equals("UNSAFE")) {
                    NullConstantInstruction nil = new NullConstantInstruction(); nil.setReceiver(get.getReceiver()); get.replace(nil);
                }
            }
        }
    }
    private static void put(BasicBlock block,Variable instance,String name,ValueType type,Variable value) {
        PutFieldInstruction put = new PutFieldInstruction(); put.setInstance(instance); put.setField(new FieldReference(OFF_HEAP,name));
        put.setFieldType(type); put.setValue(value); block.add(put);
    }
}
