// SPDX-License-Identifier: AGPL-3.0-or-later
package org.audiveris.wasm.compat.platform;

import org.audiveris.omr.sheet.Sheet;
import org.audiveris.omr.sheet.SystemInfo;
import org.audiveris.omr.sheet.rhythm.MeasuresStep;
import org.audiveris.omr.sheet.note.ChordsStep;
import org.audiveris.omr.step.OmrStep;
import org.audiveris.omr.step.StepException;

/** Serial post-OCR continuation on the existing sheet; failures require a fresh session. */
public final class PostTextsRecognition {
    private final Sheet sheet;
    private int completed;
    private boolean failed;
    public PostTextsRecognition(Sheet sheet) {
        this.sheet=java.util.Objects.requireNonNull(sheet);
        if(!sheet.getStub().isDone(OmrStep.TEXTS))throw new IllegalStateException("Complete TEXTS first");
    }
    public Sheet sheet(){return sheet;}
    public void advance(String name)throws StepException {
        if(failed)throw new IllegalStateException("Post-TEXTS processing failed; start a new page");
        OmrStep step=switch(name){case "MEASURES"->OmrStep.MEASURES;case "CHORDS"->OmrStep.CHORDS;default->throw new IllegalArgumentException("Unsupported post-TEXTS step: "+name);};
        if(completed>=2||step!=(completed==0?OmrStep.MEASURES:OmrStep.CHORDS)||sheet.getStub().isDone(step))throw new IllegalStateException("Post-TEXTS steps must run once, in order");
        Sheet previous=HeadlessSheetContext.current();HeadlessSheetContext.bind(sheet);
        long errors=HeadlessLogging.errorCount();
        try {
            if(step==OmrStep.MEASURES)new Measures().run(sheet);else new Chords().run(sheet);
            if(HeadlessLogging.errorCount()!=errors)throw new IllegalStateException("Recognition logged errors during "+name);
            sheet.getStub().done(step);completed++;
        }catch(StepException|RuntimeException error){failed=true;throw error;}
        catch(Error error){failed=true;throw error;}
        finally{HeadlessSheetContext.bind(previous);}
    }
    private static final class Measures extends MeasuresStep {
        void run(Sheet sheet)throws StepException{Void context=super.doProlog(sheet);for(SystemInfo system:sheet.getSystems())super.doSystem(system,context);super.doEpilog(sheet,context);}
    }
    private static final class Chords extends ChordsStep {
        void run(Sheet sheet)throws StepException{Void context=super.doProlog(sheet);for(SystemInfo system:sheet.getSystems())super.doSystem(system,context);super.doEpilog(sheet,context);}
    }
}
