// SPDX-License-Identifier: AGPL-3.0-or-later
package org.audiveris.wasm.compat.platform;

import org.audiveris.omr.sheet.Sheet;
import org.audiveris.omr.sheet.SystemInfo;
import org.audiveris.omr.sheet.symbol.SymbolsStep;
import org.audiveris.omr.sheet.symbol.LinksStep;
import org.audiveris.omr.sheet.rhythm.RhythmsStep;
import org.audiveris.omr.step.PageStep;
import org.audiveris.omr.step.OmrStep;
import org.audiveris.omr.step.StepException;

/** One owned page, explicit step boundaries and at most one pending local OCR request. */
public final class PageRecognitionContinuation {
    private static final OmrStep[] TAIL={OmrStep.SYMBOLS,OmrStep.LINKS,OmrStep.RHYTHMS,OmrStep.PAGE};
    private final Sheet sheet;
    private final PostCurvesRecognition prefix;
    private int completed;
    private boolean failed;
    public PageRecognitionContinuation(Sheet sheet,String job,int generation,int margin,String languages){
        this.sheet=sheet;prefix=new PostCurvesRecognition(sheet,job,generation,margin,languages);
    }
    public Sheet sheet(){return sheet;}
    public boolean hasPending(){return !failed&&prefix.hasPending();}
    private void requireUsable(){if(failed)throw new IllegalStateException("Page continuation failed; start a new page");}
    public OcrExchange.Request request(){requireUsable();return prefix.request();}
    public boolean accept(OcrExchange.Reply reply){return !failed&&prefix.accept(reply);}
    public void resume()throws StepException{requireUsable();prefix.resume();}
    public void cancel(){failed=true;prefix.cancel();}
    public void advance(String name)throws StepException{
        requireUsable();
        OmrStep step=switch(name){case "SYMBOLS"->OmrStep.SYMBOLS;case "LINKS"->OmrStep.LINKS;case "RHYTHMS"->OmrStep.RHYTHMS;case "PAGE"->OmrStep.PAGE;default->null;};
        if(step==null){prefix.advance(name);return;}
        if(!sheet.getStub().isDone(OmrStep.CURVES)||completed>=TAIL.length||step!=TAIL[completed]||sheet.getStub().isDone(step))throw new IllegalStateException("Complete CURVES, then SYMBOLS, LINKS, RHYTHMS and PAGE once in order");
        Sheet previous=HeadlessSheetContext.current();HeadlessSheetContext.bind(sheet);long errors=HeadlessLogging.errorCount();
        try{
            switch(step){
                case SYMBOLS->new Symbols().run(sheet);
                case LINKS->new Links().run(sheet);
                case RHYTHMS->new RhythmsStep().doit(sheet);
                case PAGE->new PageStep().doit(sheet);
                default->throw new AssertionError(step);
            }
            if(HeadlessLogging.errorCount()!=errors)throw new IllegalStateException("Recognition logged errors during "+name);
            sheet.getStub().done(step);completed++;
        }catch(StepException|RuntimeException error){failed=true;throw error;}
        catch(Error error){failed=true;throw error;}
        finally{HeadlessSheetContext.bind(previous);}
    }
    private static final class Symbols extends SymbolsStep {
        void run(Sheet sheet)throws StepException{var context=super.doProlog(sheet);for(SystemInfo system:sheet.getSystems())super.doSystem(system,context);super.doEpilog(sheet,context);}
    }
    private static final class Links extends LinksStep {
        void run(Sheet sheet)throws StepException{Void context=super.doProlog(sheet);for(SystemInfo system:sheet.getSystems())super.doSystem(system,context);super.doEpilog(sheet,context);}
    }
}
