// SPDX-License-Identifier: AGPL-3.0-or-later
package org.audiveris.wasm.compat;

import java.util.Set;
import org.teavm.model.*;
import org.teavm.model.instructions.*;

/** Opt-in stroke geometry profile. Raster allocation and native diagnostics are outside this API. */
final class StrokePathPatches {
    private static final String ENGINE = "sun.java2d.marlin.DMarlinRenderingEngine";
    static void apply(ClassHolder cls) {
        if (cls.getName().startsWith("sun.java2d.marlin.") || cls.getName().equals("org.audiveris.wasm.compat.platform.StrokeRuntime")) {
            for (MethodHolder method : cls.getMethods()) {
                if (method.getProgram() == null) { continue; }
                for (BasicBlock block : method.getProgram().getBasicBlocks()) {
                    for (Instruction instruction : block) {
                        if (instruction instanceof InvokeInstruction call && call.getMethod().equals(
                                new MethodReference("java.lang.Math", "ulp", ValueType.DOUBLE, ValueType.DOUBLE))) {
                            call.setMethod(new MethodReference("org.audiveris.wasm.compat.platform.StrokeRuntime", "ulp", ValueType.DOUBLE, ValueType.DOUBLE));
                        }
                        if (instruction instanceof InvokeInstruction call && call.getMethod().getClassName().equals("java.lang.Math")
                                && Set.of("cbrt", "acos").contains(call.getMethod().getName())) {
                            if (!call.getMethod().getDescriptor().equals(new MethodDescriptor(call.getMethod().getName(), ValueType.DOUBLE, ValueType.DOUBLE))) {
                                throw new IllegalStateException("Review stroke numeric signature");
                            }
                            String algorithm = call.getMethod().getName().equals("cbrt") ? "Cbrt" : "Acos";
                            call.setMethod(new MethodReference("java.lang.FdLibm$" + algorithm, "compute", ValueType.DOUBLE, ValueType.DOUBLE));
                        }
                    }
                }
            }
        }
        if (cls.getName().equals("sun.java2d.ReentrantContextProviderCLQ")) {
            String queue = "java.util.concurrent.ConcurrentLinkedQueue", local = "java.util.ArrayDeque";
            FieldHolder field = cls.getField("ctxQueue");
            if (field == null || !field.getType().equals(ValueType.object(queue))) { throw new IllegalStateException("Review Marlin context queue"); }
            field.setType(ValueType.object(local));
            int constructions = 0, operations = 0;
            for (MethodHolder method : cls.getMethods()) {
                if (method.getProgram() == null) { continue; }
                for (BasicBlock block : method.getProgram().getBasicBlocks()) {
                    for (Instruction instruction : block) {
                        if (instruction instanceof ConstructInstruction create && create.getType().equals(queue)) {
                            create.setType(local); constructions++;
                        } else if (instruction instanceof InvokeInstruction call && call.getMethod().getClassName().equals(queue)) {
                            if (!Set.of("<init>", "offer", "poll").contains(call.getMethod().getName())) { throw new IllegalStateException("Review Marlin queue operation"); }
                            call.setMethod(new MethodReference(local, call.getMethod().getDescriptor())); operations++;
                        } else if (instruction instanceof GetFieldInstruction get && get.getField().equals(field.getReference())) {
                            get.setFieldType(ValueType.object(local));
                        } else if (instruction instanceof PutFieldInstruction put && put.getField().equals(field.getReference())) {
                            put.setFieldType(ValueType.object(local));
                        }
                    }
                }
            }
            if (constructions != 1 || operations != 3) { throw new IllegalStateException("Review Marlin queue sites: " + constructions + "/" + operations); }
        } else if (cls.getName().equals("sun.java2d.marlin.RendererContext") && !Boolean.getBoolean("audiveris.compat.alphaRaster")) {
            MethodHolder constructor = cls.getMethod(new MethodDescriptor("<init>", ValueType.object("java.lang.String"), ValueType.VOID));
            if (constructor == null) { throw new IllegalStateException("Review stroke renderer context"); }
            Set<String> rasterOnly = Set.of("sun.java2d.marlin.MarlinCache", "sun.java2d.marlin.Renderer", "sun.java2d.marlin.MarlinTileGenerator");
            int allocations = 0, constructors = 0;
            for (BasicBlock block : constructor.getProgram().getBasicBlocks()) {
                for (Instruction instruction = block.getFirstInstruction(); instruction != null;) {
                    Instruction next = instruction.getNext();
                    if (instruction instanceof ConstructInstruction create && rasterOnly.contains(create.getType())) {
                        NullConstantInstruction nil = new NullConstantInstruction(); nil.setReceiver(create.getReceiver()); create.replace(nil); allocations++;
                    } else if (instruction instanceof InvokeInstruction call && call.getMethod().getName().equals("<init>") && rasterOnly.contains(call.getMethod().getClassName())) {
                        call.delete(); constructors++;
                    }
                    instruction = next;
                }
            }
            if (allocations != 3 || constructors != 3) { throw new IllegalStateException("Review raster-only context allocations"); }
        } else if (cls.getName().equals(ENGINE)) {
            for (MethodHolder method : cls.getMethods()) {
                if (method.getName().equals("logSettings")) { log(method, "settings"); }
                if (method.getName().equals("getAATileGenerator") && !Boolean.getBoolean("audiveris.compat.alphaRaster")) { reject(method); }
                if (method.getName().equals("strokeTo") && method.getProgram() != null) {
                    for (BasicBlock block : method.getProgram().getBasicBlocks()) {
                        for (Instruction instruction : block) {
                            if (instruction instanceof InvokeInstruction call && call.getMethod().getClassName().equals("sun.java2d.pipe.Region")) {
                                if (!Set.of("getLoX", "getLoY", "getWidth", "getHeight").contains(call.getMethod().getName())
                                        || !call.getArguments().isEmpty() || call.getReceiver() == null) {
                                    throw new IllegalStateException("Review stroke clip operation");
                                }
                                Variable region = call.getInstance(); call.setInstance(null); call.setArguments(region); call.setType(InvocationType.SPECIAL);
                                call.setMethod(new MethodReference("org.audiveris.wasm.compat.platform.StrokeRuntime", "rejectClip", ValueType.object("java.lang.Object"), ValueType.INTEGER));
                            }
                        }
                    }
                }
            }
        } else if (cls.getName().equals("sun.java2d.marlin.MarlinUtils")) {
            for (MethodHolder method : cls.getMethods()) {
                if (method.getName().equals("<clinit>")) {
                    Program program = new Program(); program.createVariable(); program.createBasicBlock().add(new ExitInstruction()); method.setProgram(program);
                } else if (method.getName().equals("logInfo")) { log(method, "info"); }
                else if (method.getName().equals("logException")) { log(method, "exception"); }
            }
        } else if (cls.getName().equals("sun.java2d.marlin.RendererStats")) {
            for (MethodHolder method : cls.getMethods()) {
                if (Set.of("createInstance", "dumpStats").contains(method.getName())) { reject(method); }
            }
        }
    }
    private static void log(MethodHolder method, String helper) {
        Program program = new Program(); program.createVariable();
        Variable[] arguments = new Variable[method.getDescriptor().parameterCount() + 1];
        for (int i = 1; i < arguments.length; i++) { arguments[i] = program.createVariable(); }
        arguments[0] = program.createVariable(); BasicBlock block = program.createBasicBlock();
        GetFieldInstruction enabled = new GetFieldInstruction(); enabled.setField(new FieldReference("sun.java2d.marlin.MarlinConst", "ENABLE_LOGS"));
        enabled.setFieldType(ValueType.BOOLEAN); enabled.setReceiver(arguments[0]); block.add(enabled);
        ValueType[] signature = new ValueType[arguments.length + 1]; signature[0] = ValueType.BOOLEAN;
        for (int i = 1; i < arguments.length; i++) { signature[i] = method.getDescriptor().parameterType(i - 1); }
        signature[arguments.length] = ValueType.VOID;
        InvokeInstruction call = new InvokeInstruction(); call.setType(InvocationType.SPECIAL);
        call.setMethod(new MethodReference("org.audiveris.wasm.compat.platform.StrokeRuntime", helper, signature));
        call.setArguments(arguments); block.add(call); block.add(new ExitInstruction()); method.setProgram(program);
    }
    private static void reject(MethodHolder method) {
        Program program = new Program(); program.createVariable();
        for (int i = 0; i < method.getDescriptor().parameterCount(); i++) { program.createVariable(); }
        Variable error = program.createVariable(), message = program.createVariable(); BasicBlock block = program.createBasicBlock();
        ConstructInstruction create = new ConstructInstruction(); create.setType("java.lang.UnsupportedOperationException"); create.setReceiver(error); block.add(create);
        StringConstantInstruction text = new StringConstantInstruction(); text.setConstant("Worker stroke geometry excludes native raster/statistics: " + method.getName()); text.setReceiver(message); block.add(text);
        InvokeInstruction init = new InvokeInstruction(); init.setType(InvocationType.SPECIAL); init.setInstance(error);
        init.setMethod(new MethodReference("java.lang.UnsupportedOperationException", "<init>", ValueType.object("java.lang.String"), ValueType.VOID)); init.setArguments(message); block.add(init);
        RaiseInstruction raise = new RaiseInstruction(); raise.setException(error); block.add(raise); method.setProgram(program);
    }
}
