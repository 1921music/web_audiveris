// SPDX-License-Identifier: AGPL-3.0-or-later
package org.audiveris.wasm.compat;

import java.util.*;
import org.teavm.model.*;
import org.teavm.model.instructions.*;
import org.audiveris.wasm.compat.platform.HeadlessSheetContext;

/** Reviewed presentation entrypoints, never a blanket removal of UI packages or image output. */
final class PresentationPatches {
    private static final String PREFIX = "org.audiveris.omr.";
    private static final Map<String, List<String>> DESKTOP = desktopMethods();
    private static Map<String, List<String>> desktopMethods() {
        Map<String, List<String>> result = new LinkedHashMap<>();
        for (String owner : List.of("step.AbstractStep", "step.BinaryStep", "sheet.grid.GridStep", "sheet.ledger.LedgersStep", "sheet.stem.StemSeedsStep", "sheet.symbol.SymbolsStep")) {
            result.put(PREFIX + owner, List.of("displayUI(Lorg/audiveris/omr/step/OmrStep;Lorg/audiveris/omr/sheet/Sheet;)V"));
        }
        String impact = "impact(Lorg/audiveris/omr/sig/ui/UITaskList;Lorg/audiveris/omr/sig/ui/UITask$OpKind;)V";
        result.put(PREFIX + "step.OmrStep", List.of("displayUI(Lorg/audiveris/omr/sheet/Sheet;)V", impact));
        result.put(PREFIX + "step.AbstractStep", List.of("displayUI(Lorg/audiveris/omr/step/OmrStep;Lorg/audiveris/omr/sheet/Sheet;)V", impact));
        // Only the UI-task overload: RhythmsStep.impact(Collection<Inter>) is recognition work.
        for (String owner : List.of("sheet.rhythm.RhythmsStep", "sheet.symbol.LinksStep", "step.PageStep", "text.TextsStep")) {
            result.put(PREFIX + owner, List.of(impact));
        }
        result.put(PREFIX + "sheet.rhythm.MeasuresStep", List.of(impact, "isAddition(Lorg/audiveris/omr/sig/ui/UITaskList;)Z"));
        for (String owner : List.of("sheet.ledger.LedgersBuilder", "sheet.stem.StemChecker")) { result.put(PREFIX + owner, List.of("addCheckBoard()V")); }
        for (String owner : List.of("sig.inter.AbstractInter", "sig.inter.HeadInter", "sig.inter.CompoundNoteInter")) {
            result.put(PREFIX + owner, List.of("getTracker(Lorg/audiveris/omr/sheet/Sheet;)Lorg/audiveris/omr/sig/ui/InterTracker;"));
        }
        result.put(PREFIX + "sig.inter.HeadInter", List.of("getTracker(Lorg/audiveris/omr/sheet/Sheet;)Lorg/audiveris/omr/sig/ui/InterTracker;",
                "getNeededLedgerAdditions(Ljava/awt/geom/Point2D;Lorg/audiveris/omr/sheet/Staff;)Ljava/util/List;"));
        for (String owner : List.of("sig.relation.Relation", "sig.relation.ChordSyllableRelation", "sig.relation.HeadStemRelation")) {
            result.put(PREFIX + owner, List.of("preLink(Lorg/audiveris/omr/sig/inter/InterPair;)Ljava/util/List;"));
        }
        result.put(PREFIX + "sig.inter.StaffBarlineInter", List.of("cancelAdd(Lorg/audiveris/omr/util/WrappedBoolean;Lorg/audiveris/omr/sheet/Sheet;)Ljava/util/List;",
                "getClosureToRemove(Lorg/audiveris/omr/util/WrappedBoolean;)Ljava/util/List;"));
        result.put(PREFIX + "sheet.ScaleBuilder$HistoKeeper", List.of("writePlots()V"));
        result.put(PREFIX + "sheet.stem.StemScaler$HistoKeeper", List.of("writePlot(Lorg/audiveris/omr/sheet/Scale$StemScale;)V"));
        result.put(PREFIX + "sig.relation.HeadStemRelation", List.of("preLink(Lorg/audiveris/omr/sig/inter/InterPair;)Ljava/util/List;",
                "buildStemChord(Ljava/util/List;Lorg/audiveris/omr/sig/inter/StemInter;)Lorg/audiveris/omr/sig/inter/HeadChordInter;"));
        result.put(PREFIX + "sheet.Sheet", List.of("getSheetEditor()Lorg/audiveris/omr/sheet/ui/SheetEditor;",
                "getGlyphsController()Lorg/audiveris/omr/glyph/ui/GlyphsController;", "getInterController()Lorg/audiveris/omr/sig/ui/InterController;",
                "getErrorsEditor()Lorg/audiveris/omr/ui/ErrorsEditor;", "createGlyphsController()V"));
        result.put(PREFIX + "sheet.Picture", List.of("onEvent(Lorg/audiveris/omr/ui/selection/LocationEvent;)V", "onEvent(Ljava/lang/Object;)V"));
        return Collections.unmodifiableMap(result);
    }
    static void apply(ClassHolder cls) {
        List<String> entries = DESKTOP.get(cls.getName());
        if (entries != null) {
            Set<String> missing = new LinkedHashSet<>(entries);
            for (MethodHolder method : cls.getMethods()) {
                if (!missing.remove(method.getDescriptor().toString())) { continue; }
                if (method.getProgram() == null) { throw new IllegalStateException("Review desktop body " + method.getReference()); }
                HeadlessPatches.rejectBody(method);
            }
            if (!missing.isEmpty()) { throw new IllegalStateException("Review desktop entrypoints " + cls.getName() + ": " + missing); }
        }
        if (cls.getName().equals(PREFIX + "sig.inter.OctaveShiftInter")) { sheetContext(cls); }
        if (cls.getName().equals(PREFIX + "util.BasicIndex") || cls.getName().equals(PREFIX + "glyph.GlyphIndex")) {
            MethodHolder setter = cls.getMethod(new MethodDescriptor("setEntityService", ValueType.object(PREFIX + "ui.selection.EntityService"), ValueType.VOID));
            if (setter == null) { throw new IllegalStateException("Review index UI service boundary"); }
            Program program = new Program(); program.createVariable(); Variable service = program.createVariable(); BasicBlock block = program.createBasicBlock();
            InvokeInstruction reject = new InvokeInstruction(); reject.setType(InvocationType.SPECIAL);
            reject.setMethod(new MethodReference("org.audiveris.wasm.compat.platform.HeadlessEvents", "rejectGui", ValueType.object("java.lang.Object"), ValueType.VOID));
            reject.setArguments(service); block.add(reject); block.add(new ExitInstruction()); setter.setProgram(program);
        }
        if (cls.getName().equals(PREFIX + "sig.inter.MetronomeInter")) {
            MethodDescriptor method = new MethodDescriptor("beatUnitRegexp", ValueType.object("java.lang.String"));
            if (cls.getMethod(method) == null) { throw new IllegalStateException("Review metronome regexp boundary"); }
            StandardApiPatches.delegate(cls, "beatUnitRegexp", "beatUnitRegexp", "org.audiveris.wasm.compat.platform.HeadlessMusicData", true, true, ValueType.object("java.lang.String"));
        }
    }
    private static void sheetContext(ClassHolder cls) {
        MethodHolder method = cls.getMethod(new MethodDescriptor("setGlyph", ValueType.object(PREFIX + "glyph.Glyph"), ValueType.VOID));
        Program program = method.getProgram(); List<Instruction> instructions = new ArrayList<>();
        for (BasicBlock block : program.getBasicBlocks()) { for (Instruction instruction : block) { instructions.add(instruction); } }
        InvokeInstruction controller = null, selected = null, sheet = null;
        int setters = 0, dimensions = 0;
        for (Instruction instruction : instructions) {
            if (!(instruction instanceof InvokeInstruction)) { continue; }
            InvokeInstruction call = (InvokeInstruction) instruction; MethodReference target = call.getMethod();
            if (target.getName().equals("setGlyph") && target.getClassName().equals(PREFIX + "sig.inter.AbstractInter")) { setters++; }
            if (target.getName().equals("computeValueDimensions")) { dimensions++; }
            if (target.getClassName().equals(PREFIX + "sheet.ui.StubsController") && target.getName().equals("getInstance")) {
                if (controller != null) { throw new IllegalStateException("Ambiguous selected-sheet chain"); } controller = call;
            } else if (controller != null && call.getInstance() == controller.getReceiver() && target.getName().equals("getSelectedStub")) {
                selected = call;
            } else if (selected != null && call.getInstance() == selected.getReceiver() && target.getName().equals("getSheet")) { sheet = call; }
        }
        if (controller == null || selected == null || sheet == null || setters != 1 || dimensions != 1) {
            throw new IllegalStateException("Review OctaveShift glyph/context computation before substituting it");
        }
        // Change only sheet acquisition: retain super.setGlyph, all conditions, dimensions and line geometry.
        InvokeInstruction current = new InvokeInstruction(); current.setType(InvocationType.SPECIAL);
        current.setMethod(new MethodReference(HeadlessSheetContext.class.getName(), "requireSheet", ValueType.object(PREFIX + "sheet.Sheet")));
        current.setReceiver(sheet.getReceiver()); current.setLocation(sheet.getLocation()); sheet.replace(current);
        controller.delete(); selected.delete();
        HeadlessPatches.report(method, "worker-sheet-context-preserve-glyph-and-geometry", program.basicBlockCount(), instructions.size());
    }
}
