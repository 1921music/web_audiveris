package org.audiveris.wasm.compat;

import java.nio.file.*;
import java.util.*;
import org.teavm.model.*;
import org.teavm.model.instructions.*;

/** Regression: javac can reference a protected field through the subclass's name. */
public final class InheritedUiFieldTest {
    public static void main(String[] args) throws Exception {
        check(false); check(true); realFilamentIndex(false); realFilamentIndex(true);
        if (args.length > 0) { Files.writeString(Path.of(args[0]), "{\"inheritedUiFieldCut\":true,\"shadowFieldPreserved\":true,\"engineBranchPreserved\":true}\n"); }
        System.out.println("Inherited UI field and preserved engine branch checks passed");
    }
    private static void realFilamentIndex(boolean fast) {
        System.setProperty("audiveris.compat.enabled", "true"); System.setProperty("audiveris.compat.headless", "true");
        var builder = new org.teavm.vm.TeaVMBuilder(new org.teavm.backend.wasm.WasmGCTarget());
        if (fast) { builder.setDependencyAnalyzerFactory((source, resources, loader, services, diagnostics, cache, platforms, properties) ->
                new org.teavm.dependency.FastDependencyAnalyzer(source, resources, loader, services, diagnostics, cache, platforms, properties)); }
        var vm = builder.build(); vm.installPlugins();
        ClassReader util = vm.getDependencyClassSource().get("org.audiveris.omr.ui.util.UIUtil");
        MethodReader initializer = util.getMethod(new MethodDescriptor("<clinit>", ValueType.VOID));
        Set<String> retained = new HashSet<>();
        var initialization = new AbstractInstructionReader() {
            @Override public void create(VariableReader receiver, String type) {
                if (type.equals("org.audiveris.omr.ui.util.UIUtil$1")) { throw new AssertionError("Desktop listener initialized"); }
                if (type.equals("org.audiveris.omr.ui.util.UIUtil$Constants")) { retained.add("configuration"); }
            }
            @Override public void putField(VariableReader instance, FieldReference field, VariableReader value, ValueType type) {
                if (field.getFieldName().equals("GLOBAL_FONT_RATIO")) { retained.add("font-ratio"); }
                if (field.getFieldName().equals("closeWindow")) { throw new AssertionError("Desktop listener assigned"); }
            }
        };
        for (var block : initializer.getProgram().getBasicBlocks()) { block.readAllInstructions(initialization); }
        if (!retained.equals(Set.of("configuration", "font-ratio"))) { throw new AssertionError("Font configuration lost: " + retained); }
        ClassReader cls = vm.getDependencyClassSource().get("org.audiveris.omr.glyph.dynamic.FilamentIndex");
        MethodReader method = cls.getMethod(new MethodDescriptor("publish", ValueType.object("org.audiveris.omr.glyph.dynamic.Filament"), ValueType.VOID));
        List<String> remaining = new ArrayList<>();
        var reader = new AbstractInstructionReader() {
            @Override public void getField(VariableReader receiver, VariableReader instance, FieldReference field, ValueType type) {
                if (field.getFieldName().equals("entityService")) { remaining.add(field + " type=" + type); }
            }
            @Override public void create(VariableReader receiver, String type) {
                if (type.equals("org.audiveris.omr.ui.selection.EntityListEvent")) { remaining.add("event construction"); }
            }
        };
        for (var block : method.getProgram().getBasicBlocks()) { block.readAllInstructions(reader); }
        if (!remaining.isEmpty()) { throw new AssertionError("Real inherited UI boundary remains (fast=" + fast + "): " + remaining); }
    }
    private static void check(boolean shadow) {
        ValueType serviceType = ValueType.object("org.audiveris.omr.ui.selection.EntityService");
        ClassHolder base = new ClassHolder("org.audiveris.omr.util.BasicIndex");
        FieldHolder field = new FieldHolder("entityService"); field.setType(serviceType); field.setLevel(AccessLevel.PROTECTED); base.addField(field);
        ClassHolder child = new ClassHolder("org.audiveris.omr.wasmtest.InheritedField"); child.setParent(base.getName());
        if (shadow) { FieldHolder own = new FieldHolder("entityService"); own.setType(serviceType); child.addField(own); }
        MethodHolder method = new MethodHolder(new MethodDescriptor("engine", ValueType.INTEGER)); method.setLevel(AccessLevel.PUBLIC); child.addMethod(method);
        Program program = new Program(); Variable self = program.createVariable(), service = program.createVariable();
        BasicBlock entry = program.createBasicBlock(), ui = program.createBasicBlock(), engine = program.createBasicBlock();
        GetFieldInstruction get = new GetFieldInstruction(); get.setField(new FieldReference(child.getName(), "entityService"));
        get.setFieldType(serviceType); get.setInstance(self); get.setReceiver(service); entry.add(get);
        BranchingInstruction branch = new BranchingInstruction(BranchingCondition.NOT_NULL); branch.setOperand(service); branch.setConsequent(ui); branch.setAlternative(engine); entry.add(branch);
        InvokeInstruction desktop = new InvokeInstruction(); desktop.setType(InvocationType.SPECIAL); desktop.setMethod(new MethodReference("org.audiveris.omr.wasmtest.UnavailableDesktop", "run", ValueType.VOID)); ui.add(desktop);
        returned(program, ui, 999); returned(program, engine, 21); method.setProgram(program);
        Map<String, ClassHolder> classes = Map.of(base.getName(), base, child.getName(), child);
        ClassHierarchy hierarchy = new ClassHierarchy(classes::get);
        ClassHolderTransformerContext context = new ClassHolderTransformerContext() {
            public ClassHierarchy getHierarchy() { return hierarchy; }
            public org.teavm.diagnostics.Diagnostics getDiagnostics() { return null; }
            public org.teavm.cache.IncrementalDependencyRegistration getIncrementalCache() { return null; }
            public boolean isObfuscated() { return false; }
            public boolean isStrict() { return true; }
            public String getEntryPoint() { return null; }
            public void submit(ClassHolder value) { throw new AssertionError(); }
        };
        HeadlessPatches.apply(child, context);
        boolean desktopRemaining = false, engineRemaining = false, fieldRemaining = false;
        for (BasicBlock block : program.getBasicBlocks()) { for (Instruction instruction : block) {
            if (instruction instanceof InvokeInstruction && ((InvokeInstruction) instruction).getMethod().getName().equals("run")) { desktopRemaining = true; }
            if (instruction instanceof IntegerConstantInstruction && ((IntegerConstantInstruction) instruction).getConstant() == 21) { engineRemaining = true; }
            fieldRemaining |= instruction instanceof GetFieldInstruction;
        } }
        if (!engineRemaining || desktopRemaining != shadow || fieldRemaining != shadow) { throw new AssertionError("Inherited UI boundary corrupted engine or shadow field"); }
    }
    private static void returned(Program program, BasicBlock block, int value) {
        Variable result = program.createVariable(); IntegerConstantInstruction number = new IntegerConstantInstruction(); number.setConstant(value); number.setReceiver(result); block.add(number);
        ExitInstruction exit = new ExitInstruction(); exit.setValueToReturn(result); block.add(exit);
    }
}
