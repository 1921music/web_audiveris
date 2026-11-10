// SPDX-License-Identifier: AGPL-3.0-or-later
package org.audiveris.wasm.compat;

import org.teavm.model.*;
import org.teavm.model.instructions.*;

/** Small platform boundary; leave Constant decoding and the registry's removal semantics intact. */
final class ConfigurationPatches {
    static void apply(ClassHolder cls, ClassHolderTransformerContext context) {
        constantUnit(cls, context);
        if (cls.getName().equals("org.audiveris.omr.sheet.SheetStub")) { runOptions(cls); }
        if (cls.getName().equals("org.audiveris.omr.sheet.BookManager")) { outputFolders(cls); }
        if (cls.getName().equals("org.audiveris.omr.constant.UnitManager")) { pendingSets(cls, "dirtySets"); return; }
        if (cls.getName().equals("org.audiveris.omr.constant.PackageNode")) { pendingSets(cls, "children"); return; }
        if (!cls.getName().equals("org.audiveris.omr.constant.ConstantManager")) { return; }
        String bridge = "org.audiveris.omr.constant.WorkerConstantManagerBridge";
        ValueType string = ValueType.object("java.lang.String"), collection = ValueType.object("java.util.Collection");
        delegate(cls, bridge, "addConstant", string, ValueType.object("org.audiveris.omr.constant.Constant"), string);
        delegate(cls, bridge, "getConstantUserValue", string, string);
        delegate(cls, bridge, "getAllProperties", collection);
        delegate(cls, bridge, "getUnusedUserProperties", collection);
        delegate(cls, bridge, "storeResource", ValueType.VOID);
        MethodHolder constructor = cls.getMethod(new MethodDescriptor("<init>", ValueType.VOID));
        FieldHolder registry = cls.getField("constants");
        ValueType map = ValueType.object("java.util.concurrent.ConcurrentHashMap");
        if (constructor == null || registry == null || !registry.getType().equals(map)) {
            throw new IllegalStateException("Review ConstantManager registry initialization");
        }
        Program program = new Program(); Variable self = program.createVariable(), values = program.createVariable();
        BasicBlock block = program.createBasicBlock();
        InvokeInstruction object = new InvokeInstruction(); object.setType(InvocationType.SPECIAL);
        object.setMethod(new MethodReference("java.lang.Object", "<init>", ValueType.VOID)); object.setInstance(self); block.add(object);
        ConstructInstruction create = new ConstructInstruction(); create.setType("java.util.concurrent.ConcurrentHashMap"); create.setReceiver(values); block.add(create);
        InvokeInstruction initialize = new InvokeInstruction(); initialize.setType(InvocationType.SPECIAL);
        initialize.setMethod(new MethodReference("java.util.concurrent.ConcurrentHashMap", "<init>", ValueType.VOID)); initialize.setInstance(values); block.add(initialize);
        PutFieldInstruction put = new PutFieldInstruction(); put.setField(new FieldReference(cls.getName(), "constants"));
        put.setFieldType(map); put.setInstance(self); put.setValue(values); block.add(put);
        block.add(new ExitInstruction()); constructor.setProgram(program);
    }
    private static void outputFolders(ClassHolder cls) {
        int queries = 0, clears = 0;
        for (MethodHolder method : cls.getMethods()) {
            Program program = method.getProgram(); if (program == null) { continue; }
            java.util.List<Instruction> before = new java.util.ArrayList<>();
            for (BasicBlock block : program.getBasicBlocks()) { for (Instruction instruction : block) { before.add(instruction); } }
            java.util.Set<Instruction> removed = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
            for (Instruction instruction : before) {
                if (!(instruction instanceof InvokeInstruction)) { continue; }
                InvokeInstruction cli = (InvokeInstruction) instruction;
                if (!cli.getMethod().getClassName().equals("org.audiveris.omr.Main") || !cli.getMethod().getName().equals("getCli")) { continue; }
                InvokeInstruction consumer = null;
                for (Instruction candidate : before) {
                    if (!(candidate instanceof InvokeInstruction)) { continue; }
                    InvokeInstruction call = (InvokeInstruction) candidate;
                    if (call.getInstance() == cli.getReceiver() && call.getMethod().getClassName().equals("org.audiveris.omr.CLI")) {
                        if (consumer != null) { throw new IllegalStateException("Ambiguous output-folder query"); } consumer = call;
                    }
                }
                if (consumer == null) { throw new IllegalStateException("Review BookManager CLI use"); }
                String configuration = "org.audiveris.wasm.compat.platform.HeadlessConfiguration";
                if (consumer.getMethod().getName().equals("getOutputFolder")) {
                    consumer.setMethod(new MethodReference(configuration, "outputFolder", ValueType.object("java.nio.file.Path"))); queries++;
                } else if (consumer.getMethod().getName().equals("nullifyOutputFolder")) {
                    consumer.setMethod(new MethodReference(configuration, "clearOutputFolder", ValueType.VOID)); clears++;
                } else { throw new IllegalStateException("Unexpected BookManager CLI use"); }
                consumer.setInstance(null); consumer.setType(InvocationType.SPECIAL); cli.delete(); removed.add(cli);
            }
            if (!removed.isEmpty()) {
                java.util.List<Instruction> expected = new java.util.ArrayList<>(before); expected.removeIf(removed::contains);
                java.util.List<Instruction> after = new java.util.ArrayList<>();
                for (BasicBlock block : program.getBasicBlocks()) { for (Instruction instruction : block) { after.add(instruction); } }
                if (!expected.equals(after)) { throw new IllegalStateException("Output-folder resolution/storage instructions changed"); }
                HeadlessPatches.report(method, "host-output-path-preserve-storage-body", program.basicBlockCount(), before.size());
            }
        }
        if (queries != 3 || clears != 1) { throw new IllegalStateException("Review BookManager output-folder boundary"); }
    }
    private static void runOptions(ClassHolder cls) {
        MethodHolder method = cls.getMethod(new MethodDescriptor("doOneStep", ValueType.object("org.audiveris.omr.step.OmrStep"), ValueType.VOID));
        Program program = method.getProgram(); java.util.List<Instruction> before = new java.util.ArrayList<>();
        InvokeInstruction cli = null, save = null, timeout = null;
        for (BasicBlock block : program.getBasicBlocks()) { for (Instruction instruction : block) {
            before.add(instruction);
            if (!(instruction instanceof InvokeInstruction)) { continue; }
            InvokeInstruction call = (InvokeInstruction) instruction;
            if (call.getMethod().getClassName().equals("org.audiveris.omr.Main")) {
                if (call.getMethod().getName().equals("getCli")) { if (cli != null) { throw new IllegalStateException("Ambiguous CLI query"); } cli = call; }
                if (call.getMethod().getName().equals("getSheetStepTimeOut")) { if (timeout != null) { throw new IllegalStateException("Ambiguous timeout"); } timeout = call; }
            }
            if (call.getMethod().getClassName().equals("org.audiveris.omr.CLI") && call.getMethod().getName().equals("isSave")) { save = call; }
        } }
        if (cli == null || save == null || timeout == null || save.getInstance() != cli.getReceiver()) { throw new IllegalStateException("Review SheetStub run options"); }
        String configuration = "org.audiveris.wasm.compat.platform.HeadlessConfiguration";
        timeout.setMethod(new MethodReference(configuration, "stepTimeout", ValueType.INTEGER));
        save.setMethod(new MethodReference(configuration, "saveSheets", ValueType.BOOLEAN)); save.setType(InvocationType.SPECIAL); save.setInstance(null);
        cli.delete();
        java.util.List<Instruction> expected = new java.util.ArrayList<>(before); expected.remove(cli);
        java.util.List<Instruction> after = new java.util.ArrayList<>();
        for (BasicBlock block : program.getBasicBlocks()) { for (Instruction instruction : block) { after.add(instruction); } }
        if (!expected.equals(after)) { throw new IllegalStateException("Sheet-step execution instructions changed"); }
        HeadlessPatches.report(method, "worker-run-options-preserve-step-execution-and-save", program.basicBlockCount(), before.size());
    }
    private static void constantUnit(ClassHolder cls, ClassHolderTransformerContext context) {
        String base = "org.audiveris.omr.constant.ConstantSet";
        MethodDescriptor descriptor = new MethodDescriptor("getUnitName", ValueType.object("java.lang.String"));
        if (cls.getName().equals(base)) {
            MethodHolder hook = cls.getMethod(descriptor);
            if (hook == null) { throw new IllegalStateException("ConstantSet namespace hook missing"); }
            HeadlessPatches.rejectBody(hook); return;
        }
        String parent = cls.getParent(); boolean isSet = false;
        while (parent != null) {
            if (parent.equals(base)) { isSet = true; break; }
            ClassReader type = context.getHierarchy().getClassSource().get(parent);
            parent = type == null ? null : type.getParent();
        }
        if (!isSet) { return; }
        if (cls.getMethod(descriptor) != null) { throw new IllegalStateException("Review explicit ConstantSet namespace " + cls.getName()); }
        MethodHolder method = new MethodHolder(descriptor); method.setLevel(AccessLevel.PROTECTED); cls.addMethod(method);
        String declaring = cls.getDeclaringClassName();
        if (declaring == null) { HeadlessPatches.rejectBody(method); return; }
        Program program = new Program(); program.createVariable(); Variable value = program.createVariable(); BasicBlock block = program.createBasicBlock();
        StringConstantInstruction literal = new StringConstantInstruction(); literal.setConstant(declaring); literal.setReceiver(value); block.add(literal);
        ExitInstruction exit = new ExitInstruction(); exit.setValueToReturn(value); block.add(exit); method.setProgram(program);
    }
    private static void pendingSets(ClassHolder cls, String fieldName) {
        String original = "java.util.concurrent.ConcurrentSkipListSet", portable = "org.audiveris.wasm.compat.platform.WorkerPendingSet";
        FieldHolder field = cls.getField(fieldName);
        if (field == null || !field.getType().equals(ValueType.object(original))) { throw new IllegalStateException("Review pending ConstantSets"); }
        field.setType(ValueType.object(portable));
        for (MethodHolder method : cls.getMethods()) {
            Program program = method.getProgram(); if (program == null) { continue; }
            int count = 0; boolean changed = false;
            for (BasicBlock block : program.getBasicBlocks()) { for (Instruction instruction : block) {
                count++;
                if (instruction instanceof ConstructInstruction && ((ConstructInstruction) instruction).getType().equals(original)) {
                    ((ConstructInstruction) instruction).setType(portable);
                    changed = true;
                } else if (instruction instanceof InvokeInstruction) {
                    InvokeInstruction call = (InvokeInstruction) instruction;
                    if (call.getMethod().getClassName().equals(original)) { call.setMethod(new MethodReference(portable, call.getMethod().getDescriptor())); changed = true; }
                } else if (instruction instanceof GetFieldInstruction && ((GetFieldInstruction) instruction).getField().getFieldName().equals(fieldName)) {
                    ((GetFieldInstruction) instruction).setFieldType(ValueType.object(portable));
                    changed = true;
                } else if (instruction instanceof PutFieldInstruction && ((PutFieldInstruction) instruction).getField().getFieldName().equals(fieldName)) {
                    ((PutFieldInstruction) instruction).setFieldType(ValueType.object(portable));
                    changed = true;
                }
            } }
            if (changed) {
                HeadlessPatches.report(method, "worker-pending-sets-preserve-registration-and-traversal", program.basicBlockCount(), count);
            }
        }
    }
    private static void delegate(ClassHolder cls, String bridge, String name, ValueType... signature) {
        if (cls.getMethod(new MethodDescriptor(name, signature)) == null) { throw new IllegalStateException("Review configuration method " + name); }
        StandardApiPatches.delegate(cls, name, name, bridge, false, true, signature);
    }
}
