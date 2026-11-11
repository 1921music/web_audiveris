// SPDX-License-Identifier: AGPL-3.0-or-later
package org.audiveris.wasm.compat;

import org.teavm.model.BasicBlock;
import org.teavm.model.ElementModifier;
import org.teavm.model.MethodDescriptor;
import org.teavm.model.MethodHolder;
import org.teavm.model.MethodReference;
import org.teavm.model.Program;
import org.teavm.model.ValueType;
import org.teavm.model.instructions.ExitInstruction;
import org.teavm.model.instructions.InvocationType;
import org.teavm.model.instructions.InvokeInstruction;
import org.teavm.vm.spi.TeaVMHost;
import org.teavm.vm.spi.TeaVMPlugin;

/** Supply missing APIs and repair default-path normalization in TeaVM's model, not source/JAR files. */
public final class CompilerPatches implements TeaVMPlugin {
    @Override public void install(TeaVMHost host) {
        if (!Boolean.getBoolean("audiveris.compat.enabled")) { return; }
        ReachabilityFenceIntrinsic.install(host);
        RoundingIntrinsic.install(host);
        host.add((cls, context) -> {
            StandardApiPatches.apply(cls);
            PropertyApiPatches.apply(cls);
            ResourceApiPatches.apply(cls);
            ResourceBundlePatches.apply(cls);
            ReflectionApiPatches.apply(cls);
            ThreadApiPatches.apply(cls);
            ClassLoaderPatches.apply(cls);
            if (Boolean.getBoolean("audiveris.compat.strokePaths")) { StrokePathPatches.apply(cls); }
            if (Boolean.getBoolean("audiveris.compat.fontMetrics")) { FontMetricsPatches.apply(cls); }
            if (Boolean.getBoolean("audiveris.compat.headless")) { HeadlessPatches.apply(cls, context); }
            if (cls.getName().equals("java.nio.file.impl.DefaultPath")
                    || cls.getName().equals("org.teavm.classlib.java.nio.file.impl.TDefaultPath")) {
                for (MethodHolder method : cls.getMethods()) {
                    if (!method.getName().equals("normalize") || method.getDescriptor().parameterCount() != 0) { continue; }
                    Program program = new Program(); var self = program.createVariable(); var result = program.createVariable();
                    BasicBlock block = program.createBasicBlock();
                    InvokeInstruction invoke = new InvokeInstruction(); invoke.setType(InvocationType.SPECIAL);
                    ValueType path = ValueType.object("java.nio.file.Path");
                    invoke.setMethod(new MethodReference(RuntimeApis.class.getName(), "normalizeDefaultPath", path, path));
                    invoke.setArguments(self); invoke.setReceiver(result); block.add(invoke);
                    var cast = new org.teavm.model.instructions.CastInstruction();
                    cast.setValue(result); cast.setTargetType(method.getDescriptor().getResultType());
                    var typed = program.createVariable(); cast.setReceiver(typed); block.add(cast);
                    ExitInstruction exit = new ExitInstruction(); exit.setValueToReturn(typed); block.add(exit);
                    method.setProgram(program);
                }
                return;
            }
            if (cls.getName().equals("java.io.File")) { addFileToPath(cls); return; }
            if (cls.getName().equals("java.lang.String")) {
                ValueType bytes = ValueType.arrayOf(ValueType.BYTE);
                addStringMethod(cls, new MethodDescriptor("lines", ValueType.object("java.util.stream.Stream")), "stringLines", false);
                addStringMethod(cls, new MethodDescriptor("getBytes", ValueType.INTEGER, ValueType.INTEGER, bytes, ValueType.INTEGER, ValueType.VOID), "stringGetBytes", false);
                addStringMethod(cls, new MethodDescriptor("<init>", bytes, ValueType.INTEGER, ValueType.VOID), "highByteChars", true);
                addStringMethod(cls, new MethodDescriptor("<init>", bytes, ValueType.INTEGER, ValueType.INTEGER, ValueType.INTEGER, ValueType.VOID), "highByteChars", true);
                return;
            }
            if (!cls.getName().equals("java.util.Collections")) { return; }
            ValueType set = ValueType.object("java.util.SortedSet");
            for (MethodDescriptor descriptor : new MethodDescriptor[] {
                    new MethodDescriptor("unmodifiableSortedSet", set, set),
                    new MethodDescriptor("emptySortedSet", set)}) {
                if (cls.getMethod(descriptor) != null) { continue; }
                MethodHolder method = new MethodHolder(descriptor);
                method.setLevel(org.teavm.model.AccessLevel.PUBLIC);
                method.getModifiers().add(ElementModifier.STATIC);
                Program program = new Program();
                program.createVariable(); // Reserved receiver slot, even for static methods.
                var arguments = new org.teavm.model.Variable[descriptor.parameterCount()];
                for (int i = 0; i < arguments.length; i++) { arguments[i] = program.createVariable(); }
                var result = program.createVariable();
                BasicBlock block = program.createBasicBlock();
                InvokeInstruction invoke = new InvokeInstruction();
                invoke.setType(InvocationType.SPECIAL);
                invoke.setMethod(new MethodReference(SortedSets.class.getName(), descriptor));
                invoke.setArguments(arguments);
                invoke.setReceiver(result);
                block.add(invoke);
                ExitInstruction exit = new ExitInstruction(); exit.setValueToReturn(result); block.add(exit);
                method.setProgram(program);
                cls.addMethod(method);
            }
        });
    }

    private static void addFileToPath(org.teavm.model.ClassHolder cls) {
        ValueType path = ValueType.object("java.nio.file.Path");
        MethodDescriptor descriptor = new MethodDescriptor("toPath", path);
        if (cls.getMethod(descriptor) != null) { return; }
        var field = new org.teavm.model.FieldHolder("__audiverisCompatPath"); field.setType(path);
        field.setLevel(org.teavm.model.AccessLevel.PRIVATE);
        field.getModifiers().add(ElementModifier.VOLATILE); field.getModifiers().add(ElementModifier.TRANSIENT);
        cls.addField(field);
        var reference = new org.teavm.model.FieldReference("java.io.File", field.getName());
        MethodHolder method = new MethodHolder(descriptor); method.setLevel(org.teavm.model.AccessLevel.PUBLIC);
        Program program = new Program(); var self = program.createVariable(); var cached = program.createVariable(); var result = program.createVariable();
        BasicBlock entry = program.createBasicBlock(), create = program.createBasicBlock(), reuse = program.createBasicBlock();
        var get = new org.teavm.model.instructions.GetFieldInstruction();
        get.setInstance(self); get.setField(reference); get.setFieldType(path); get.setReceiver(cached); entry.add(get);
        var branch = new org.teavm.model.instructions.BranchingInstruction(org.teavm.model.instructions.BranchingCondition.NULL);
        branch.setOperand(cached); branch.setConsequent(create); branch.setAlternative(reuse); entry.add(branch);
        ExitInstruction existing = new ExitInstruction(); existing.setValueToReturn(cached); reuse.add(existing);
        InvokeInstruction call = new InvokeInstruction(); call.setType(InvocationType.SPECIAL);
        call.setMethod(new MethodReference(RuntimeApis.class.getName(), "fileToPath", ValueType.object("java.io.File"), path));
        call.setArguments(self); call.setReceiver(result); create.add(call);
        var put = new org.teavm.model.instructions.PutFieldInstruction();
        put.setInstance(self); put.setField(reference); put.setFieldType(path); put.setValue(result); create.add(put);
        ExitInstruction exit = new ExitInstruction(); exit.setValueToReturn(result); create.add(exit);
        method.setProgram(program); cls.addMethod(method);
    }

    private static void addStringMethod(org.teavm.model.ClassHolder cls, MethodDescriptor descriptor, String helper, boolean constructor) {
        if (cls.getMethod(descriptor) != null) { return; }
        MethodHolder method = new MethodHolder(descriptor);
        method.setLevel(org.teavm.model.AccessLevel.PUBLIC);
        Program program = new Program();
        var self = program.createVariable();
        var params = new org.teavm.model.Variable[descriptor.parameterCount()];
        for (int i = 0; i < params.length; i++) { params[i] = program.createVariable(); }
        ValueType resultType = constructor ? ValueType.arrayOf(ValueType.CHARACTER) : descriptor.getResultType();
        ValueType[] signature = new ValueType[params.length + (constructor ? 1 : 2)];
        int cursor = 0;
        if (!constructor) { signature[cursor++] = ValueType.object("java.lang.String"); }
        for (int i = 0; i < params.length; i++) { signature[cursor++] = descriptor.parameterType(i); }
        signature[cursor] = resultType;
        BasicBlock block = program.createBasicBlock();
        var args = new org.teavm.model.Variable[params.length + (constructor ? 0 : 1)];
        if (!constructor) { args[0] = self; }
        System.arraycopy(params, 0, args, constructor ? 0 : 1, params.length);
        var result = resultType == ValueType.VOID ? null : program.createVariable();
        InvokeInstruction call = new InvokeInstruction(); call.setType(InvocationType.SPECIAL);
        call.setMethod(new MethodReference(RuntimeApis.class.getName(), new MethodDescriptor(helper, signature)));
        call.setArguments(args); call.setReceiver(result); block.add(call);
        if (constructor) {
            InvokeInstruction init = new InvokeInstruction(); init.setType(InvocationType.SPECIAL);
            init.setMethod(new MethodReference("java.lang.String", "<init>", ValueType.arrayOf(ValueType.CHARACTER), ValueType.VOID));
            init.setInstance(self); init.setArguments(result); block.add(init);
        }
        ExitInstruction exit = new ExitInstruction();
        if (!constructor) { exit.setValueToReturn(result); }
        block.add(exit); method.setProgram(program); cls.addMethod(method);
    }
}
