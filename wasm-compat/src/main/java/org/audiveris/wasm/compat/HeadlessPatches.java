// SPDX-License-Identifier: AGPL-3.0-or-later
package org.audiveris.wasm.compat;

import java.util.*;
import org.teavm.model.*;
import org.teavm.model.instructions.*;
import org.teavm.model.optimization.ConstantConditionElimination;
import org.audiveris.wasm.compat.platform.*;

/** Explicit application/platform substitutions. No root selection or original source is changed. */
final class HeadlessPatches {
    private static ValueType obj(String name) { return ValueType.object(name); }
    private static final ValueType STRING = obj("java.lang.String");
    static void apply(ClassHolder cls) {
        if (!cls.getName().startsWith("org.audiveris.omr.")
                && !cls.getName().equals("org.slf4j.LoggerFactory") && !cls.getName().equals("org.slf4j.MDC")) { return; }
        Map<MethodDescriptor, Program> original = new HashMap<>();
        for (MethodHolder method : cls.getMethods()) { original.put(method.getDescriptor(), method.getProgram()); }
        switch (cls.getName()) {
            case "org.audiveris.omr.WellKnowns": environment(cls); break;
            case "org.audiveris.omr.util.OmrExecutors": executors(cls); break;
            case "org.slf4j.LoggerFactory":
                emptyInitializer(cls);
                delegate(cls, "getLogger", "logger", HeadlessLogging.class, STRING, obj("org.slf4j.Logger"));
                delegate(cls, "getLogger", "logger", HeadlessLogging.class, obj("java.lang.Class"), obj("org.slf4j.Logger"));
                delegate(cls, "getILoggerFactory", "factory", HeadlessLogging.class, obj("org.slf4j.ILoggerFactory")); break;
            case "org.slf4j.MDC":
                if (cls.getMethod(new MethodDescriptor("<clinit>", ValueType.VOID)) != null) { emptyInitializer(cls); }
                delegate(cls, "getMDCAdapter", "mdc", HeadlessLogging.class, obj("org.slf4j.spi.MDCAdapter")); break;
            case "org.audiveris.omr.log.LogUtil": logging(cls); break;
            case "org.audiveris.omr.step.ui.StepMonitoring": progress(cls); break;
            default: break;
        }
        desktopCallbacks(cls);
        for (MethodHolder method : cls.getMethods()) {
            Program before = original.get(method.getDescriptor());
            if (before != method.getProgram()) { report(method, "platform-method", before == null ? 0 : before.basicBlockCount(), instructions(before)); }
        }
        if (!cls.getName().startsWith("org.audiveris.omr.")) { return; }
        for (MethodHolder method : cls.getMethods()) {
            Program program = method.getProgram(); if (program == null) { continue; }
            int oldBlocks = program.basicBlockCount(), oldInstructions = instructions(program);
            boolean changed = false;
            for (int i = 0; i < program.basicBlockCount(); i++) {
                for (Instruction instruction : program.basicBlockAt(i)) {
                    if (instruction instanceof GetFieldInstruction) {
                        GetFieldInstruction get = (GetFieldInstruction) instruction;
                        if (gui(get.getField()) || presentationService(get.getField())) {
                            if (get.getInstance() != null) {
                                NullCheckInstruction check = new NullCheckInstruction(); check.setValue(get.getInstance()); check.setReceiver(program.createVariable()); get.insertPrevious(check);
                            }
                            NullConstantInstruction constant = new NullConstantInstruction(); constant.setReceiver(get.getReceiver());
                            constant.setLocation(get.getLocation()); get.replace(constant); changed = true;
                        }
                    } else if (instruction instanceof PutFieldInstruction) {
                        PutFieldInstruction put = (PutFieldInstruction) instruction;
                        if (gui(put.getField()) || presentationService(put.getField())) {
                            InvokeInstruction reject = new InvokeInstruction(); reject.setType(InvocationType.SPECIAL);
                            reject.setMethod(new MethodReference(HeadlessEvents.class.getName(), "rejectGui", obj("java.lang.Object"), ValueType.VOID));
                            reject.setArguments(put.getValue()); reject.setLocation(put.getLocation()); put.replace(reject); changed = true;
                        }
                    } else if (instruction instanceof InvokeInstruction) {
                        InvokeInstruction call = (InvokeInstruction) instruction;
                        if (call.getMethod().getClassName().equals("javax.swing.SwingUtilities")
                                && call.getMethod().getName().equals("isEventDispatchThread")) {
                            IntegerConstantInstruction constant = new IntegerConstantInstruction(); constant.setConstant(0); constant.setReceiver(call.getReceiver());
                            constant.setLocation(call.getLocation()); call.replace(constant); changed = true;
                        }
                    }
                }
            }
            if (changed) {
                while (new ConstantConditionElimination().optimize(method.getDescriptor(), program)) { }
                report(method, "headless-gui-guard", oldBlocks, oldInstructions);
            }
        }
    }
    private static int instructions(Program program) {
        if (program == null) { return 0; } int count = 0;
        for (BasicBlock block : program.getBasicBlocks()) { for (Instruction instruction : block) { count++; } } return count;
    }
    private static boolean reportStarted;
    private static synchronized void report(MethodHolder method, String kind, int beforeBlocks, int beforeInstructions) {
        String destination = System.getProperty("audiveris.compat.headlessReport"); if (destination == null) { return; }
        try {
            java.nio.file.Path path = java.nio.file.Path.of(destination);
            if (!reportStarted) {
                java.nio.file.Files.createDirectories(path.getParent());
                java.nio.file.Files.writeString(path, "method\tkind\tbeforeBlocks\tafterBlocks\tbeforeInstructions\tafterInstructions\n"); reportStarted = true;
            }
            Program program = method.getProgram();
            java.nio.file.Files.writeString(path, method.getReference() + "\t" + kind + "\t" + beforeBlocks + "\t" + program.basicBlockCount()
                    + "\t" + beforeInstructions + "\t" + instructions(program) + "\n", java.nio.file.StandardOpenOption.APPEND);
        } catch (java.io.IOException error) { throw new IllegalStateException("Cannot record headless substitutions", error); }
    }
    private static boolean gui(FieldReference field) { return field.getClassName().equals("org.audiveris.omr.OMR") && field.getFieldName().equals("gui"); }
    private static boolean presentationService(FieldReference field) {
        return field.getClassName().equals("org.audiveris.omr.util.BasicIndex") && field.getFieldName().equals("entityService")
                || field.getClassName().equals("org.audiveris.omr.glyph.GlyphIndex") && field.getFieldName().equals("glyphService");
    }
    private static void desktopCallbacks(ClassHolder cls) {
        String name;
        switch (cls.getName()) {
            case "org.audiveris.omr.sheet.Book": name = "lambda$setModified$0"; break;
            case "org.audiveris.omr.sheet.Sheet": name = "lambda$reset$0"; break;
            case "org.audiveris.omr.sheet.SheetStub": name = "lambda$setUpgraded$0"; break;
            case "org.audiveris.omr.util.BasicIndex": case "org.audiveris.omr.glyph.GlyphIndex":
            case "org.audiveris.omr.glyph.dynamic.FilamentIndex": name = "lambda$publish$0"; break;
            default: return;
        }
        boolean found = false;
        for (MethodHolder method : cls.getMethods()) {
            if (!method.getName().equals(name)) { continue; }
            if (!method.getDescriptor().getResultType().equals(ValueType.VOID)) { throw new IllegalStateException("Review desktop callback " + method.getReference()); }
            found = true; rejectBody(method);
        }
        if (!found) { throw new IllegalStateException("Review desktop callback " + cls.getName() + "." + name); }
    }
    private static void rejectBody(MethodHolder method) {
        Program program = new Program(); for (int i = 0; i <= method.parameterCount(); i++) { program.createVariable(); }
        BasicBlock block = program.createBasicBlock(); InvokeInstruction reject = new InvokeInstruction(); reject.setType(InvocationType.SPECIAL);
        reject.setMethod(new MethodReference(HeadlessEvents.class.getName(), "unsupportedDesktop", ValueType.VOID)); block.add(reject); block.add(new ExitInstruction()); method.setProgram(program);
    }
    private static void delegate(ClassHolder cls, String method, String helper, Class<?> owner, ValueType... signature) {
        MethodDescriptor descriptor = new MethodDescriptor(method, signature);
        if (cls.getMethod(descriptor) == null) { throw new IllegalStateException("Review platform method " + cls.getName() + "." + descriptor); }
        StandardApiPatches.delegate(cls, method, helper, owner.getName(), true, true, signature);
    }
    private static MethodHolder initializer(ClassHolder cls) {
        MethodHolder method = cls.getMethod(new MethodDescriptor("<clinit>", ValueType.VOID));
        if (method == null) { throw new IllegalStateException("Missing initializer " + cls.getName()); } return method;
    }
    private static void emptyInitializer(ClassHolder cls) {
        Program program = new Program(); program.createVariable(); program.createBasicBlock().add(new ExitInstruction()); initializer(cls).setProgram(program);
    }
    private static void initializeField(ClassHolder cls, String name, Class<?> owner, String helper, ValueType type) {
        if (cls.getField(name) == null || !cls.getField(name).getType().equals(type)) { throw new IllegalStateException("Review platform field " + cls.getName() + "." + name); }
        Program program = new Program(); program.createVariable(); BasicBlock block = program.createBasicBlock(); Variable result = program.createVariable();
        InvokeInstruction call = new InvokeInstruction(); call.setType(InvocationType.SPECIAL); call.setMethod(new MethodReference(owner.getName(), helper, type)); call.setReceiver(result); block.add(call);
        PutFieldInstruction put = new PutFieldInstruction(); put.setField(new FieldReference(cls.getName(), name)); put.setFieldType(type); put.setValue(result); block.add(put);
        block.add(new ExitInstruction()); initializer(cls).setProgram(program);
    }
    private static void environment(ClassHolder cls) {
        Program program = new Program(); program.createVariable(); BasicBlock block = program.createBasicBlock();
        InvokeInstruction init = new InvokeInstruction(); init.setType(InvocationType.SPECIAL); init.setMethod(new MethodReference(HeadlessEnvironment.class.getName(), "initialize", ValueType.VOID)); block.add(init);
        for (FieldHolder field : cls.getFields()) {
            if (!field.hasModifier(ElementModifier.STATIC) || field.getInitialValue() != null) { continue; }
            ValueType type = field.getType(); Variable result = program.createVariable();
            if (type.equals(ValueType.BOOLEAN)) {
                IntegerConstantInstruction constant = new IntegerConstantInstruction(); constant.setConstant(0); constant.setReceiver(result); block.add(constant);
            } else {
                String helper = type.equals(STRING) ? "string" : type.equals(obj("java.net.URI")) ? "uri" : type.equals(obj("java.nio.file.Path")) ? "path" : null;
                if (helper == null) { throw new IllegalStateException("Review WellKnowns field " + field.getName()); }
                Variable key = program.createVariable(); StringConstantInstruction constant = new StringConstantInstruction(); constant.setConstant(field.getName()); constant.setReceiver(key); block.add(constant);
                InvokeInstruction call = new InvokeInstruction(); call.setType(InvocationType.SPECIAL); call.setMethod(new MethodReference(HeadlessEnvironment.class.getName(), helper, STRING, type)); call.setArguments(key); call.setReceiver(result); block.add(call);
            }
            PutFieldInstruction put = new PutFieldInstruction(); put.setField(new FieldReference(cls.getName(), field.getName())); put.setFieldType(type); put.setValue(result); block.add(put);
        }
        block.add(new ExitInstruction()); initializer(cls).setProgram(program);
    }
    private static void executors(ClassHolder cls) {
        initializeField(cls, "defaultParallelism", HeadlessExecution.class, "parallelism", obj("org.audiveris.omr.util.param.Param"));
        delegate(cls, "getHighExecutor", "high", HeadlessExecution.class, obj("java.util.concurrent.ExecutorService"));
        delegate(cls, "getLowExecutor", "low", HeadlessExecution.class, obj("java.util.concurrent.ExecutorService"));
        delegate(cls, "getCachedLowExecutor", "cached", HeadlessExecution.class, obj("java.util.concurrent.ExecutorService"));
        delegate(cls, "getNumberOfCpus", "cpus", HeadlessExecution.class, ValueType.INTEGER);
        delegate(cls, "restart", "restart", HeadlessExecution.class, ValueType.VOID);
        delegate(cls, "shutdown", "shutdown", HeadlessExecution.class, ValueType.BOOLEAN);
    }
    private static void logging(ClassHolder cls) {
        delegate(cls, "initialize", "initialize", HeadlessLogging.class, obj("java.nio.file.Path"), obj("java.net.URI"), ValueType.VOID);
        delegate(cls, "start", "book", HeadlessEvents.class, obj("org.audiveris.omr.sheet.Book"), ValueType.VOID);
        delegate(cls, "start", "stub", HeadlessEvents.class, obj("org.audiveris.omr.sheet.SheetStub"), ValueType.VOID);
        delegate(cls, "stopBook", "stopBook", HeadlessEvents.class, ValueType.VOID);
        delegate(cls, "stopStub", "stopStub", HeadlessEvents.class, ValueType.VOID);
        for (MethodHolder method : cls.getMethods()) {
            if (!Set.of("addAppender", "addFileAppender", "addGuiAppender", "removeAppender").contains(method.getName())) { continue; }
            rejectBody(method);
        }
    }
    private static void progress(ClassHolder cls) {
        delegate(cls, "animate", "animate", HeadlessEvents.class, ValueType.VOID);
        delegate(cls, "notifyStart", "start", HeadlessEvents.class, ValueType.VOID);
        delegate(cls, "notifyStop", "stop", HeadlessEvents.class, ValueType.VOID);
        delegate(cls, "notifyMsg", "message", HeadlessEvents.class, STRING, ValueType.VOID);
        delegate(cls, "notifyStep", "step", HeadlessEvents.class, obj("org.audiveris.omr.sheet.SheetStub"), obj("org.audiveris.omr.step.OmrStep"), ValueType.VOID);
        MethodHolder create = cls.getMethod(new MethodDescriptor("createMonitor", obj("org.audiveris.omr.step.ui.StepMonitor")));
        Program program = new Program(); program.createVariable(); BasicBlock block = program.createBasicBlock();
        InvokeInstruction reject = new InvokeInstruction(); reject.setType(InvocationType.SPECIAL); reject.setMethod(new MethodReference(HeadlessEvents.class.getName(), "unsupportedDesktop", ValueType.VOID)); block.add(reject);
        Variable value = program.createVariable(); NullConstantInstruction nil = new NullConstantInstruction(); nil.setReceiver(value); block.add(nil); ExitInstruction exit = new ExitInstruction(); exit.setValueToReturn(value); block.add(exit); create.setProgram(program);
    }
}
