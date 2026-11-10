// SPDX-License-Identifier: AGPL-3.0-or-later
package org.audiveris.wasm.compat;

import java.util.*;
import org.teavm.model.*;
import org.teavm.model.instructions.*;
import org.teavm.model.optimization.ConstantConditionElimination;
import org.audiveris.wasm.compat.platform.*;

/** Explicit compiler-model substitutions; does not select roots or edit source files. */
final class HeadlessPatches {
    private static ValueType obj(String name) { return ValueType.object(name); }
    private static final ValueType STRING = obj("java.lang.String");
    static void apply(ClassHolder cls, ClassHolderTransformerContext context) {
        ImageJPatches.apply(cls);
        GraphApiPatches.apply(cls);
        if (!cls.getName().startsWith("org.audiveris.omr.")
                && !cls.getName().equals("org.slf4j.LoggerFactory") && !cls.getName().equals("org.slf4j.MDC")) { return; }
        Map<MethodDescriptor, Program> original = new HashMap<>();
        for (MethodHolder method : cls.getMethods()) { original.put(method.getDescriptor(), method.getProgram()); }
        switch (cls.getName()) {
            case "org.audiveris.omr.sheet.ledger.LedgersBuilder": ledgerFailure(cls); break;
            case "org.audiveris.omr.sheet.beam.SpotsBuilder": spotImages(cls); break;
            case "org.audiveris.omr.sheet.stem.StemScaler": stemBuffer(cls, context); break;
            case "org.audiveris.omr.math.HiLoPeakFinder": prefixCopies(cls, 1); break;
            case "org.audiveris.omr.sheet.clef.ClefBuilder": grayCopies(cls, 1); prefixCopies(cls, 1); break;
            case "org.audiveris.omr.sheet.key.KeyExtractor": prefixCopies(cls, 1); break;
            case "org.audiveris.omr.sheet.key.KeyBuilder$ShapeBuilder": prefixCopies(cls, 5); break;
            case "org.audiveris.omr.sheet.key.KeyRoi": grayCopies(cls, 2); break;
            case "org.audiveris.omr.sheet.time.HeaderTimeBuilder": grayCopies(cls, 1); break;
            case "org.audiveris.omr.sig.inter.AbstractPitchedInter": pitchOffsets(cls); break;
            case "org.audiveris.omr.ui.symbol.SymbolGeometry":
                symbolGeometryDefault(cls); break;
            case "org.audiveris.omr.classifier.BasicClassifier":
                if (cls.getMethod(new MethodDescriptor("load", ValueType.arrayOf(STRING), obj("org.audiveris.omr.classifier.AbstractClassifier$Network"))) == null) {
                    throw new IllegalStateException("Review BasicClassifier model-loading boundary");
                }
                StandardApiPatches.delegate(cls, "load", "load", "org.audiveris.omr.classifier.WorkerClassifierBridge", false, true,
                        ValueType.arrayOf(STRING), obj("org.audiveris.omr.classifier.AbstractClassifier$Network")); break;
            case "org.audiveris.omr.sig.inter.BracketInter":
                if (cls.getMethod(new MethodDescriptor("computeArea", ValueType.VOID)) == null) { throw new IllegalStateException("Review BracketInter geometry boundary"); }
                StandardApiPatches.delegate(cls, "computeArea", "computeArea", "org.audiveris.omr.sig.inter.WorkerBracketBridge", false, true, ValueType.VOID); break;
            case "org.audiveris.omr.WellKnowns": environment(cls); break;
            case "org.audiveris.omr.util.OmrExecutors": executors(cls); break;
            case "org.audiveris.omr.sheet.Picture": archivedTables(cls); break;
            case "org.audiveris.omr.sheet.Book": modelLocks(cls); break;
            case "org.audiveris.omr.sheet.SheetStub":
                modelLocks(cls); installedField(cls, "sheet", "getSheet", "requireInstalledSheet", obj("org.audiveris.omr.sheet.Sheet")); break;
            case "org.audiveris.omr.sheet.Sheet":
                installedField(cls, "picture", "getPicture", "requireInstalledPicture", obj("org.audiveris.omr.sheet.Picture")); break;
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
        ConfigurationPatches.apply(cls, context);
        PresentationPatches.apply(cls);
        desktopCallbacks(cls);
        for (MethodHolder method : cls.getMethods()) {
            Program before = original.get(method.getDescriptor());
            if (before != method.getProgram()) { report(method, "platform-method", before == null ? 0 : before.basicBlockCount(), instructions(before)); }
        }
        if (!cls.getName().startsWith("org.audiveris.omr.")) { return; }
        for (MethodHolder method : cls.getMethods()) {
            Program program = method.getProgram(); if (program == null) { continue; }
            int oldBlocks = program.basicBlockCount(), oldInstructions = instructions(program);
            boolean templateOutput = method.getReference().toString().equals("org.audiveris.omr.image.TemplateFactory.buildDecoratedImage(Lorg/audiveris/omr/image/Template;)Ljava/awt/image/BufferedImage;");
            List<Instruction> preserved = new ArrayList<>(); Map<Instruction, MethodReference> originalCalls = new IdentityHashMap<>();
            if (templateOutput) {
                for (BasicBlock block : program.getBasicBlocks()) { for (Instruction instruction : block) {
                    preserved.add(instruction); if (instruction instanceof InvokeInstruction) { originalCalls.put(instruction, ((InvokeInstruction) instruction).getMethod()); }
                } }
            }
            boolean changed = false;
            for (int i = 0; i < program.basicBlockCount(); i++) {
                for (Instruction instruction : program.basicBlockAt(i)) {
                    if (instruction instanceof GetFieldInstruction) {
                        GetFieldInstruction get = (GetFieldInstruction) instruction;
                        if (get.getField().equals(new FieldReference("org.audiveris.omr.ui.util.UIUtil", "closeWindow"))) {
                            InvokeInstruction reject = new InvokeInstruction(); reject.setType(InvocationType.SPECIAL);
                            reject.setMethod(new MethodReference(HeadlessEvents.class.getName(), "unsupportedDesktop", ValueType.VOID));
                            reject.setLocation(get.getLocation()); get.insertPrevious(reject);
                            NullConstantInstruction nil = new NullConstantInstruction(); nil.setReceiver(get.getReceiver());
                            nil.setLocation(get.getLocation()); get.replace(nil); changed = true; continue;
                        }
                        if (get.getField().getClassName().equals("org.audiveris.omr.sheet.ui.TemplateView") && get.getField().getFieldName().equals("templateComposite")) {
                            InvokeInstruction data = new InvokeInstruction(); data.setType(InvocationType.SPECIAL);
                            data.setMethod(new MethodReference("org.audiveris.wasm.compat.platform.HeadlessRenderData", "templateComposite", obj("java.awt.AlphaComposite")));
                            data.setReceiver(get.getReceiver()); data.setLocation(get.getLocation()); get.replace(data); changed = true; continue;
                        }
                        if (gui(get.getField()) || presentationService(get.getField(), cls, context)) {
                            if (get.getInstance() != null) {
                                NullCheckInstruction check = new NullCheckInstruction(); check.setValue(get.getInstance()); check.setReceiver(program.createVariable()); get.insertPrevious(check);
                            }
                            NullConstantInstruction constant = new NullConstantInstruction(); constant.setReceiver(get.getReceiver());
                            constant.setLocation(get.getLocation()); get.replace(constant); changed = true;
                        }
                    } else if (instruction instanceof PutFieldInstruction) {
                        PutFieldInstruction put = (PutFieldInstruction) instruction;
                        if (gui(put.getField()) || presentationService(put.getField(), cls, context)) {
                            InvokeInstruction reject = new InvokeInstruction(); reject.setType(InvocationType.SPECIAL);
                            reject.setMethod(new MethodReference(HeadlessEvents.class.getName(), "rejectGui", obj("java.lang.Object"), ValueType.VOID));
                            reject.setArguments(put.getValue()); reject.setLocation(put.getLocation()); put.replace(reject); changed = true;
                        }
                    } else if (instruction instanceof InvokeInstruction) {
                        InvokeInstruction call = (InvokeInstruction) instruction;
                        if (call.getMethod().getClassName().equals("org.audiveris.omr.ui.util.UIUtil")
                                && Set.of("setAbsoluteStroke", "setAbsoluteDashedStroke").contains(call.getMethod().getName())) {
                            call.setMethod(new MethodReference("org.audiveris.wasm.compat.platform.HeadlessRenderOps", call.getMethod().getDescriptor())); changed = true; continue;
                        }
                        if (call.getMethod().getClassName().equals("org.audiveris.omr.ui.action.AdvancedTopics")
                                && call.getMethod().getName().equals("processSystemsInParallel")) {
                            IntegerConstantInstruction constant = new IntegerConstantInstruction(); constant.setConstant(0); constant.setReceiver(call.getReceiver());
                            constant.setLocation(call.getLocation()); call.replace(constant); changed = true; continue;
                        }
                        if (call.getMethod().getClassName().equals("javax.swing.SwingUtilities")
                                && call.getMethod().getName().equals("isEventDispatchThread")) {
                            IntegerConstantInstruction constant = new IntegerConstantInstruction(); constant.setConstant(0); constant.setReceiver(call.getReceiver());
                            constant.setLocation(call.getLocation()); call.replace(constant); changed = true;
                        }
                    }
                }
            }
            if (changed) {
                if (templateOutput) { verifyTemplateOutput(program, preserved, originalCalls); }
                else { while (new ConstantConditionElimination().optimize(method.getDescriptor(), program)) { } }
                report(method, templateOutput ? "detached-render-data-preserve-builder-instructions" : "headless-gui-guard", oldBlocks, oldInstructions);
            }
        }
    }
    private static void verifyTemplateOutput(Program program, List<Instruction> before, Map<Instruction, MethodReference> calls) {
        List<Instruction> after = new ArrayList<>();
        for (BasicBlock block : program.getBasicBlocks()) { for (Instruction instruction : block) { after.add(instruction); } }
        if (before.size() != after.size()) { throw new IllegalStateException("Template output instructions were removed"); }
        int opacity = 0, strokes = 0;
        for (int i = 0; i < before.size(); i++) {
            Instruction old = before.get(i), current = after.get(i); MethodReference target = calls.get(old);
            if (old instanceof GetFieldInstruction && ((GetFieldInstruction) old).getField().equals(new FieldReference("org.audiveris.omr.sheet.ui.TemplateView", "templateComposite"))) {
                if (!(current instanceof InvokeInstruction) || !((InvokeInstruction) current).getMethod().getClassName().equals("org.audiveris.wasm.compat.platform.HeadlessRenderData")) { throw new IllegalStateException("Template opacity adaptation changed"); }
                opacity++; continue;
            }
            if (old != current) { throw new IllegalStateException("Template output computation changed"); }
            if (target != null && target.getClassName().equals("org.audiveris.omr.ui.util.UIUtil") && Set.of("setAbsoluteStroke", "setAbsoluteDashedStroke").contains(target.getName())) {
                MethodReference replacement = ((InvokeInstruction) current).getMethod();
                if (!replacement.getClassName().equals("org.audiveris.wasm.compat.platform.HeadlessRenderOps") || !replacement.getDescriptor().equals(target.getDescriptor())) { throw new IllegalStateException("Template stroke adaptation changed"); }
                strokes++;
            } else if (target != null && !target.equals(((InvokeInstruction) current).getMethod())) { throw new IllegalStateException("Template output call changed"); }
        }
        if (opacity != 1 || strokes == 0) { throw new IllegalStateException("Review template rendering boundaries"); }
    }
    private static int instructions(Program program) {
        if (program == null) { return 0; } int count = 0;
        for (BasicBlock block : program.getBasicBlocks()) { for (Instruction instruction : block) { count++; } } return count;
    }
    private static boolean reportStarted;
    static synchronized void report(MethodHolder method, String kind, int beforeBlocks, int beforeInstructions) {
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
    private static boolean presentationService(FieldReference field, ClassHolder current, ClassHolderTransformerContext context) {
        if (field.getClassName().equals("org.audiveris.omr.sheet.Book")
                && Set.of("parameterDialog", "bookBrowser").contains(field.getFieldName())) { return true; }
        if (field.getClassName().equals("org.audiveris.omr.glyph.GlyphIndex") && field.getFieldName().equals("glyphService")
                || field.getClassName().equals("org.audiveris.omr.score.Score") && field.getFieldName().equals("logicalsEditor")) { return true; }
        if (!field.getFieldName().equals("entityService")) { return false; }
        // Bytecode may name the subclass when accessing an inherited protected field.
        ClassReader owner = field.getClassName().equals(current.getName()) ? current : context.getHierarchy().getClassSource().get(field.getClassName());
        while (owner != null) {
            FieldReader declared = owner.getField(field.getFieldName());
            if (declared != null) { return owner.getName().equals("org.audiveris.omr.util.BasicIndex") && declared.getType().equals(obj("org.audiveris.omr.ui.selection.EntityService")); }
            owner = owner.getParent() == null ? null : context.getHierarchy().getClassSource().get(owner.getParent());
        }
        return false;
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
    static void rejectBody(MethodHolder method) {
        Program program = new Program(); for (int i = 0; i <= method.parameterCount(); i++) { program.createVariable(); }
        BasicBlock block = program.createBasicBlock(); InvokeInstruction reject = new InvokeInstruction(); reject.setType(InvocationType.SPECIAL);
        reject.setMethod(new MethodReference(HeadlessEvents.class.getName(), "unsupportedDesktop", ValueType.VOID)); block.add(reject);
        ExitInstruction exit = new ExitInstruction();
        ValueType resultType = method.getDescriptor().getResultType();
        if (!resultType.equals(ValueType.VOID)) {
            Variable result = program.createVariable();
            if (resultType.equals(ValueType.BOOLEAN)) {
                IntegerConstantInstruction zero = new IntegerConstantInstruction(); zero.setConstant(0); zero.setReceiver(result); block.add(zero);
            } else if (resultType instanceof ValueType.Object || resultType instanceof ValueType.Array) {
                NullConstantInstruction nil = new NullConstantInstruction(); nil.setReceiver(result); block.add(nil);
            } else { throw new IllegalStateException("Review desktop return type " + method.getReference()); }
            exit.setValueToReturn(result);
        }
        block.add(exit); method.setProgram(program);
    }
    private static void ledgerFailure(ClassHolder cls) {
        MethodHolder method = cls.getMethod(new MethodDescriptor("buildLedgers", obj("java.util.List"), ValueType.VOID));
        if (method == null || method.getProgram() == null) { throw new IllegalStateException("Review ledger failure boundary"); }
        Program program = method.getProgram(); int count = 0, before = instructions(program);
        for (BasicBlock block : program.getBasicBlocks()) { for (Instruction instruction : block) {
            if (!(instruction instanceof InvokeInstruction)) { continue; }
            var call = (InvokeInstruction) instruction;
            if (call.getMethod().equals(new MethodReference("org.slf4j.Logger", "warn", STRING, obj("java.lang.Throwable"), ValueType.VOID))) {
                call.setInstance(null); call.setType(InvocationType.SPECIAL);
                call.setMethod(new MethodReference(HeadlessSpotImages.class.getName(), "failed", STRING, obj("java.lang.Throwable"), ValueType.VOID)); count++;
            }
        } }
        if (count != 1) { throw new IllegalStateException("Review ledger catch sites: " + count); }
        report(method, "ledger-failure-propagation", program.basicBlockCount(), before);
    }
    private static void spotImages(ClassHolder cls) {
        int gray = 0, runs = 0, snapshots = 0, failures = 0, saves = 0;
        for (MethodHolder method : cls.getMethods()) {
            Program program = method.getProgram(); if (program == null) { continue; }
            int before = instructions(program), changed = 0;
            for (BasicBlock block : program.getBasicBlocks()) { for (Instruction instruction : block) {
                if (!(instruction instanceof InvokeInstruction)) { continue; }
                var call = (InvokeInstruction) instruction; var target = call.getMethod();
                String helper = null; ValueType parameter = null, result = null;
                if (target.equals(new MethodReference("ij.process.ByteProcessor", "getBufferedImage", obj("java.awt.image.BufferedImage")))) {
                    helper = "gray"; parameter = obj("ij.process.ByteProcessor"); result = obj("java.awt.image.BufferedImage"); gray++;
                } else if (target.equals(new MethodReference("org.audiveris.omr.run.RunTable", "getBufferedImage", obj("java.awt.image.BufferedImage")))) {
                    helper = "runs"; parameter = obj("org.audiveris.omr.run.RunTable"); result = obj("java.awt.image.BufferedImage"); runs++;
                } else if (target.equals(new MethodReference("ij.process.ByteProcessor", "duplicate", obj("ij.process.ImageProcessor")))) {
                    helper = "duplicate"; parameter = obj("ij.process.ByteProcessor"); result = obj("ij.process.ByteProcessor"); snapshots++;
                } else if (target.equals(new MethodReference("org.audiveris.omr.image.ImageUtil", "saveOnDisk",
                        obj("java.awt.image.BufferedImage"), STRING, STRING, ValueType.VOID))) {
                    call.setMethod(new MethodReference(HeadlessSpotImages.class.getName(), "save", obj("java.awt.image.BufferedImage"), STRING, STRING, ValueType.VOID));
                    saves++; changed++; continue;
                } else if (method.getName().equals("buildSheetSpots") && target.equals(new MethodReference("org.slf4j.Logger", "warn", STRING,
                        obj("java.lang.Throwable"), ValueType.VOID))) {
                    call.setInstance(null); call.setType(InvocationType.SPECIAL);
                    call.setMethod(new MethodReference(HeadlessSpotImages.class.getName(), "failed", STRING, obj("java.lang.Throwable"), ValueType.VOID));
                    failures++; changed++; continue;
                }
                if (helper != null) {
                    Variable input = call.getInstance(); call.setInstance(null); call.setType(InvocationType.SPECIAL);
                    call.setMethod(new MethodReference(HeadlessSpotImages.class.getName(), helper, parameter, result));
                    call.setArguments(input); changed++;
                }
            } }
            if (changed > 0) { report(method, "spot-gray-snapshot-debug-output-and-failure-boundaries", program.basicBlockCount(), before); }
        }
        if (gray != 3 || runs != 1 || snapshots != 1 || failures != 1 || saves != 3) {
            throw new IllegalStateException("Review spot image sites: " + gray + "/" + runs + "/" + snapshots + "/" + failures + "/" + saves);
        }
    }
    private static void stemBuffer(ClassHolder cls, ClassHolderTransformerContext context) {
        ValueType sheetType = obj("org.audiveris.omr.sheet.Sheet"), pixels = obj("ij.process.ByteProcessor");
        ValueType flagType = obj("org.audiveris.omr.constant.Constant$Boolean");
        FieldHolder sheet = cls.getField("sheet"), constants = cls.getField("constants");
        MethodHolder method = cls.getMethod(new MethodDescriptor("getBuffer", pixels));
        ClassReader options = context.getHierarchy().getClassSource().get(cls.getName() + "$Constants");
        FieldReader save = options == null ? null : options.getField("saveStemImage");
        if (sheet == null || !sheet.getType().equals(sheetType) || constants == null || method == null
                || method.getProgram() == null || save == null || !save.getType().equals(flagType)) {
            throw new IllegalStateException("Review StemScaler detached debug image boundary");
        }
        int copies = 0, graphics = 0, sources = 0, returns = 0;
        Variable returnedBuffer = null, sourceKey = null;
        Map<Variable, Variable> aliases = new IdentityHashMap<>();
        for (BasicBlock block : method.getProgram().getBasicBlocks()) { for (Instruction instruction : block) {
            if (instruction instanceof AssignInstruction) {
                var copy = (AssignInstruction) instruction; aliases.put(copy.getReceiver(), copy.getAssignee());
            }
            if (instruction instanceof GetFieldInstruction) {
                var get = (GetFieldInstruction) instruction;
                if (get.getField().equals(new FieldReference("org.audiveris.omr.sheet.Picture$SourceKey", "NO_STAFF"))) { sourceKey = get.getReceiver(); }
            }
            if (instruction instanceof InvokeInstruction) {
                var invoke = (InvokeInstruction) instruction; MethodReference target = invoke.getMethod();
                if (target.equals(new MethodReference("ij.process.ByteProcessor", "getBufferedImage", obj("java.awt.image.BufferedImage")))) { copies++; }
                if (target.equals(new MethodReference("java.awt.image.BufferedImage", "createGraphics", obj("java.awt.Graphics2D")))) { graphics++; }
                if (target.equals(new MethodReference("org.audiveris.omr.sheet.Picture", "getSource", obj("org.audiveris.omr.sheet.Picture$SourceKey"), pixels))) {
                    if (invoke.getArguments().get(0) != sourceKey) { throw new IllegalStateException("Review StemScaler source key"); }
                    returnedBuffer = invoke.getReceiver(); sources++;
                }
            }
        } }
        for (BasicBlock block : method.getProgram().getBasicBlocks()) { for (Instruction instruction : block) {
            if (instruction instanceof ExitInstruction) {
                Variable value = ((ExitInstruction) instruction).getValueToReturn(); Set<Variable> seen = new HashSet<>();
                while (aliases.containsKey(value) && seen.add(value)) { value = aliases.get(value); }
                if (value != returnedBuffer) { throw new IllegalStateException("Review StemScaler returned buffer: " + value + " / " + returnedBuffer); }
                returns++;
            }
        } }
        if (copies != 1 || graphics != 1 || sources != 1 || returns != 1) { throw new IllegalStateException("Review StemScaler image-copy behavior"); }
        Program program = new Program(); Variable self = program.createVariable(), owner = program.createVariable();
        Variable values = program.createVariable(), flag = program.createVariable(), enabled = program.createVariable(), result = program.createVariable();
        BasicBlock block = program.createBasicBlock();
        GetFieldInstruction ownerGet = new GetFieldInstruction(); ownerGet.setField(sheet.getReference()); ownerGet.setFieldType(sheetType);
        ownerGet.setInstance(self); ownerGet.setReceiver(owner); block.add(ownerGet);
        GetFieldInstruction valuesGet = new GetFieldInstruction(); valuesGet.setField(constants.getReference()); valuesGet.setFieldType(constants.getType());
        valuesGet.setReceiver(values); block.add(valuesGet);
        GetFieldInstruction flagGet = new GetFieldInstruction(); flagGet.setField(new FieldReference(options.getName(), "saveStemImage"));
        flagGet.setFieldType(flagType); flagGet.setInstance(values); flagGet.setReceiver(flag); block.add(flagGet);
        InvokeInstruction check = new InvokeInstruction(); check.setType(InvocationType.VIRTUAL);
        check.setMethod(new MethodReference("org.audiveris.omr.constant.Constant$Boolean", "isSet", ValueType.BOOLEAN));
        check.setInstance(flag); check.setReceiver(enabled); block.add(check);
        InvokeInstruction call = new InvokeInstruction(); call.setType(InvocationType.SPECIAL);
        call.setMethod(new MethodReference(HeadlessStemBuffer.class.getName(), "buffer", sheetType, ValueType.BOOLEAN, pixels));
        call.setArguments(owner, enabled); call.setReceiver(result); block.add(call);
        ExitInstruction exit = new ExitInstruction(); exit.setValueToReturn(result); block.add(exit); method.setProgram(program);
    }
    private static void prefixCopies(ClassHolder cls, int expected) {
        int replaced = 0;
        for (MethodHolder method : cls.getMethods()) {
            Program program = method.getProgram(); if (program == null) { continue; }
            Map<Variable, Integer> constants = new HashMap<>();
            Map<Variable, InvokeInstruction> views = new HashMap<>();
            int before = instructions(program), changed = 0;
            for (BasicBlock block : program.getBasicBlocks()) { for (Instruction instruction : block) {
                if (instruction instanceof IntegerConstantInstruction) {
                    var value = (IntegerConstantInstruction) instruction; constants.put(value.getReceiver(), value.getConstant());
                } else if (instruction instanceof InvokeInstruction) {
                    var call = (InvokeInstruction) instruction;
                    if (call.getMethod().getName().equals("subList") && call.getArguments().size() == 2
                            && call.getMethod().getClassName().startsWith("java.util.")) { views.put(call.getReceiver(), call); }
                }
            } }
            for (BasicBlock block : program.getBasicBlocks()) { for (Instruction instruction : block) {
                if (!(instruction instanceof InvokeInstruction)) { continue; }
                var call = (InvokeInstruction) instruction;
                if (!call.getMethod().getName().equals("retainAll") || call.getArguments().size() != 1) { continue; }
                var view = views.get(call.getArguments().get(0));
                if (view == null || !Integer.valueOf(0).equals(constants.get(view.getArguments().get(0)))) { continue; }
                Variable values = view.getInstance(), from = view.getArguments().get(0), to = view.getArguments().get(1);
                view.setInstance(null); view.setType(InvocationType.SPECIAL);
                view.setMethod(new MethodReference(HeaderListCopies.class.getName(), "prefix", obj("java.util.List"),
                        ValueType.INTEGER, ValueType.INTEGER, obj("java.util.List")));
                view.setArguments(values, from, to); replaced++; changed++;
            } }
            if (changed > 0) { report(method, "header-retain-prefix-snapshot", program.basicBlockCount(), before); }
        }
        if (replaced != expected) { throw new IllegalStateException("Review header prefix sites: " + cls.getName() + " / " + replaced); }
    }
    private static void grayCopies(ClassHolder cls, int expected) {
        int replaced = 0;
        for (MethodHolder method : cls.getMethods()) {
            Program program = method.getProgram(); if (program == null) { continue; }
            Map<Variable, Integer> constants = new HashMap<>(); int before = instructions(program), changed = 0;
            for (BasicBlock block : program.getBasicBlocks()) { for (Instruction instruction : block) {
                if (instruction instanceof IntegerConstantInstruction) {
                    IntegerConstantInstruction value = (IntegerConstantInstruction) instruction; constants.put(value.getReceiver(), value.getConstant());
                }
            } }
            for (BasicBlock block : program.getBasicBlocks()) { for (Instruction instruction : block) {
                if (!(instruction instanceof InvokeInstruction)) { continue; }
                InvokeInstruction call = (InvokeInstruction) instruction;
                if (!call.getMethod().getClassName().equals("ij.process.ByteProcessor") || !call.getMethod().getName().equals("copyBits")) { continue; }
                if (call.getArguments().size() != 4 || !Integer.valueOf(ij.process.Blitter.COPY).equals(constants.get(call.getArguments().get(3)))) {
                    throw new IllegalStateException("Review raw header copy mode");
                }
                Variable target = call.getInstance(); var arguments = new java.util.ArrayList<Variable>(); arguments.add(target); arguments.addAll(call.getArguments());
                call.setInstance(null); call.setType(InvocationType.SPECIAL);
                call.setMethod(new MethodReference(HeadlessGrayCopy.class.getName(), "copyBits", obj("ij.process.ByteProcessor"), obj("ij.process.ImageProcessor"),
                        ValueType.INTEGER, ValueType.INTEGER, ValueType.INTEGER, ValueType.VOID));
                call.setArguments(arguments.toArray(new Variable[0])); replaced++; changed++;
            } }
            if (changed > 0) { report(method, "raw-gray-header-copy", program.basicBlockCount(), before); }
        }
        if (replaced != expected) { throw new IllegalStateException("Review header raw-copy sites: " + cls.getName() + " / " + replaced); }
    }
    private static void pitchOffsets(ClassHolder cls) {
        MethodHolder method = cls.getMethod(new MethodDescriptor("populatePitchOffsets", ValueType.VOID));
        FieldHolder field = cls.getField("pitchOffsets"); ValueType map = obj("java.util.Map");
        if (method == null || field == null || !field.getType().equals(map)) { throw new IllegalStateException("Review pitched-inter font measurements"); }
        Program program = new Program(); program.createVariable(); Variable values = program.createVariable(); BasicBlock block = program.createBasicBlock();
        GetFieldInstruction get = new GetFieldInstruction(); get.setField(new FieldReference(cls.getName(), "pitchOffsets"));
        get.setFieldType(map); get.setReceiver(values); block.add(get);
        InvokeInstruction call = new InvokeInstruction(); call.setType(InvocationType.SPECIAL);
        call.setMethod(new MethodReference(HeadlessSymbolGeometry.class.getName(), "fillPitchOffsets", map, ValueType.VOID));
        call.setArguments(values); block.add(call); block.add(new ExitInstruction()); method.setProgram(program);
    }
    private static void symbolGeometryDefault(ClassHolder cls) {
        MethodHolder method = cls.getMethod(new MethodDescriptor("provider", obj("org.audiveris.omr.ui.symbol.SymbolGeometry$Provider")));
        if (method == null || method.getProgram() == null) { throw new IllegalStateException("Review SymbolGeometry provider boundary"); }
        Program program = method.getProgram(); int before = instructions(program), replaced = 0;
        for (BasicBlock block : program.getBasicBlocks()) {
            java.util.List<Instruction> original = new java.util.ArrayList<>(); for (Instruction instruction : block) { original.add(instruction); }
            for (Instruction instruction : original) {
                if (!(instruction instanceof GetFieldInstruction)) { continue; }
                GetFieldInstruction get = (GetFieldInstruction) instruction;
                if (!get.getField().equals(new FieldReference("org.audiveris.omr.ui.symbol.SymbolGeometry$DefaultHolder", "INSTANCE"))) { continue; }
                if (get.getInstance() != null) { throw new IllegalStateException("Expected static default provider"); }
                InvokeInstruction call = new InvokeInstruction(); call.setType(InvocationType.SPECIAL);
                call.setMethod(new MethodReference(HeadlessSymbolGeometry.class.getName(), "provider", obj("org.audiveris.omr.ui.symbol.SymbolGeometry$Provider")));
                call.setReceiver(get.getReceiver()); call.setLocation(get.getLocation()); get.replace(call); replaced++;
            }
        }
        if (replaced != 1) { throw new IllegalStateException("Review SymbolGeometry default initialization: " + replaced); }
        report(method, "symbol-geometry-default-provider", program.basicBlockCount(), before);
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
    private static void archivedTables(ClassHolder cls) {
        MethodHolder method = cls.getMethod(new MethodDescriptor("convertOldTables", ValueType.VOID));
        FieldHolder field = cls.getField("oldTables"); ValueType type = obj("java.util.EnumMap");
        if (method == null || method.getProgram() == null || field == null || !field.getType().equals(type)) {
            throw new IllegalStateException("Review legacy picture-table migration");
        }
        Program program = new Program(); Variable self = program.createVariable(), tables = program.createVariable();
        BasicBlock block = program.createBasicBlock();
        GetFieldInstruction get = new GetFieldInstruction(); get.setField(field.getReference());
        get.setFieldType(type); get.setInstance(self); get.setReceiver(tables); block.add(get);
        InvokeInstruction check = new InvokeInstruction(); check.setType(InvocationType.SPECIAL);
        check.setMethod(new MethodReference(HeadlessArchives.class.getName(), "requireNoLegacyTables", obj("java.util.Map"), ValueType.VOID));
        check.setArguments(tables); block.add(check);
        Variable nilValue = program.createVariable(); NullConstantInstruction nil = new NullConstantInstruction();
        nil.setReceiver(nilValue); block.add(nil);
        PutFieldInstruction clear = new PutFieldInstruction(); clear.setField(field.getReference());
        clear.setFieldType(type); clear.setInstance(self); clear.setValue(nilValue); block.add(clear);
        block.add(new ExitInstruction()); method.setProgram(program);
    }
    private static void modelLocks(ClassHolder cls) {
        String original = "java.util.concurrent.locks.ReentrantLock", replacement = SerialLock.class.getName();
        int constructions = 0, constructors = 0;
        for (MethodHolder method : cls.getMethods()) {
            Program program = method.getProgram(); if (program == null) { continue; }
            int before = instructions(program); boolean changed = false;
            for (BasicBlock block : program.getBasicBlocks()) { for (Instruction instruction : block) {
                if (instruction instanceof ConstructInstruction && ((ConstructInstruction) instruction).getType().equals(original)) {
                    ((ConstructInstruction) instruction).setType(replacement); constructions++; changed = true;
                } else if (instruction instanceof InvokeInstruction && ((InvokeInstruction) instruction).getMethod().getClassName().equals(original)) {
                    InvokeInstruction call = (InvokeInstruction) instruction;
                    if (!call.getMethod().getDescriptor().equals(new MethodDescriptor("<init>", ValueType.VOID))) {
                        throw new IllegalStateException("Review model lock operation " + call.getMethod());
                    }
                    call.setMethod(new MethodReference(replacement, call.getMethod().getDescriptor())); constructors++; changed = true;
                }
            } }
            if (changed) { report(method, "single-lane-model-lock-ownership", program.basicBlockCount(), before); }
        }
        // Java field initializers occur in each constructor that does not delegate via this(...).
        if (constructions == 0 || constructions != constructors) { throw new IllegalStateException("Review model lock construction"); }
    }
    private static void installedField(ClassHolder cls, String fieldName, String getter, String helper, ValueType type) {
        FieldHolder field = cls.getField(fieldName);
        MethodHolder method = cls.getMethod(new MethodDescriptor(getter, type));
        if (field == null || !field.getType().equals(type) || method == null) { throw new IllegalStateException("Review loaded sheet access"); }
        Program program = new Program(); Variable self = program.createVariable(), sheet = program.createVariable(), result = program.createVariable();
        BasicBlock block = program.createBasicBlock(); GetFieldInstruction get = new GetFieldInstruction();
        get.setField(field.getReference()); get.setFieldType(type); get.setInstance(self); get.setReceiver(sheet); block.add(get);
        InvokeInstruction require = new InvokeInstruction(); require.setType(InvocationType.SPECIAL);
        require.setMethod(new MethodReference(HeadlessModels.class.getName(), helper, type, type));
        require.setArguments(sheet); require.setReceiver(result); block.add(require);
        ExitInstruction exit = new ExitInstruction(); exit.setValueToReturn(result); block.add(exit); method.setProgram(program);
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
