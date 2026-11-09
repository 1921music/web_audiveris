// SPDX-License-Identifier: AGPL-3.0-or-later
package org.audiveris.wasm.compat;

import org.teavm.model.*;
import org.teavm.model.instructions.*;

/** Per-logical-thread context metadata; this adds no native threads or shared Worker heaps. */
final class ThreadApiPatches {
    private static final ValueType LOADER = ValueType.object("java.lang.ClassLoader");
    private static final FieldReference CONTEXT = new FieldReference("java.lang.Thread", "__audiverisContextLoader");
    static void apply(ClassHolder cls) {
        if (cls.getName().equals("java.lang.Thread")) {
            MethodDescriptor setter = new MethodDescriptor("setContextClassLoader", LOADER, ValueType.VOID);
            if (cls.getMethod(setter) != null) { return; }
            FieldHolder field = new FieldHolder(CONTEXT.getFieldName()); field.setType(LOADER); field.setLevel(AccessLevel.PRIVATE);
            field.getModifiers().add(ElementModifier.VOLATILE); cls.addField(field);
            MethodHolder set = new MethodHolder(setter); set.setLevel(AccessLevel.PUBLIC);
            Program write = new Program(); Variable self = write.createVariable(), value = write.createVariable();
            BasicBlock body = write.createBasicBlock(); body.add(put(self, value)); body.add(new ExitInstruction()); set.setProgram(write); cls.addMethod(set);
            MethodHolder get = cls.getMethod(new MethodDescriptor("getContextClassLoader", LOADER));
            if (get == null) { throw new IllegalStateException("Review Thread context patch for this TeaVM version"); }
            Program read = new Program(); Variable owner = read.createVariable(), result = read.createVariable();
            BasicBlock block = read.createBasicBlock(); block.add(get(owner, result));
            ExitInstruction exit = new ExitInstruction(); exit.setValueToReturn(result); block.add(exit); get.setProgram(read);
            for (MethodHolder constructor : cls.getMethods()) {
                if (!constructor.getName().equals("<init>")) { continue; }
                Program program = constructor.getProgram();
                for (int i = 0; i < program.basicBlockCount(); i++) {
                    for (Instruction instruction : program.basicBlockAt(i)) {
                        if (instruction instanceof ExitInstruction) {
                            Variable context = program.createVariable(); InvokeInstruction inherit = new InvokeInstruction(); inherit.setType(InvocationType.SPECIAL);
                            inherit.setMethod(new MethodReference(ThreadContexts.class.getName(), "inheritedContext", LOADER));
                            inherit.setReceiver(context); instruction.insertPrevious(inherit); instruction.insertPrevious(put(program.variableAt(0), context));
                        }
                    }
                }
            }
        } else if (cls.getName().equals(ThreadContexts.class.getName())) {
            Program program = new Program(); program.createVariable();
            Variable parent = program.createVariable(), result = program.createVariable(), fallback = program.createVariable();
            BasicBlock entry = program.createBasicBlock(), present = program.createBasicBlock(), missing = program.createBasicBlock();
            InvokeInstruction current = new InvokeInstruction(); current.setType(InvocationType.SPECIAL);
            current.setMethod(new MethodReference("java.lang.Thread", "currentThread", ValueType.object("java.lang.Thread")));
            current.setReceiver(parent); entry.add(current);
            BranchingInstruction branch = new BranchingInstruction(BranchingCondition.NULL); branch.setOperand(parent);
            branch.setConsequent(missing); branch.setAlternative(present); entry.add(branch);
            present.add(get(parent, result)); ExitInstruction known = new ExitInstruction(); known.setValueToReturn(result); present.add(known);
            InvokeInstruction system = new InvokeInstruction(); system.setType(InvocationType.SPECIAL);
            system.setMethod(new MethodReference("java.lang.ClassLoader", "getSystemClassLoader", LOADER)); system.setReceiver(fallback); missing.add(system);
            ExitInstruction initial = new ExitInstruction(); initial.setValueToReturn(fallback); missing.add(initial);
            cls.getMethod(new MethodDescriptor("inheritedContext", LOADER)).setProgram(program);
        }
    }
    private static GetFieldInstruction get(Variable owner, Variable result) {
        GetFieldInstruction instruction = new GetFieldInstruction(); instruction.setField(CONTEXT); instruction.setFieldType(LOADER);
        instruction.setInstance(owner); instruction.setReceiver(result); return instruction;
    }
    private static PutFieldInstruction put(Variable owner, Variable value) {
        PutFieldInstruction instruction = new PutFieldInstruction(); instruction.setField(CONTEXT); instruction.setFieldType(LOADER);
        instruction.setInstance(owner); instruction.setValue(value); return instruction;
    }
}
