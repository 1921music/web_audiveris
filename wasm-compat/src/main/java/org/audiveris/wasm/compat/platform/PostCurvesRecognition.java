// SPDX-License-Identifier: AGPL-3.0-or-later
package org.audiveris.wasm.compat.platform;

import org.audiveris.omr.sheet.Sheet;
import org.audiveris.omr.sheet.curve.Curves;
import org.audiveris.omr.sheet.curve.RehearsalsBuilder;
import org.audiveris.omr.step.OmrStep;
import org.audiveris.omr.step.StepException;
import org.audiveris.omr.text.WorkerTextsBridge;

/** Original geometric recognition, with explicit local rehearsal OCR suspension points. */
public final class PostCurvesRecognition {
    private final PostTextsRecognition prefix;
    private final Sheet sheet;
    private final OcrExchange exchange;
    private final int margin;
    private final String languages;
    private RehearsalsBuilder.Continuation rehearsals;
    private OcrImageFrame frame;
    private boolean failed,started;
    public PostCurvesRecognition(Sheet sheet,String job,int generation,int margin,String languages){
        this.sheet=sheet;prefix=new PostTextsRecognition(sheet);this.margin=margin;this.languages=languages;
        exchange=new OcrExchange(job+"/curves",sheet.getId(),generation);
    }
    public Sheet sheet(){return sheet;}
    public boolean hasPending(){return !failed&&frame!=null;}
    private void requireUsable(){if(failed)throw new IllegalStateException("Curve continuation failed; start a new page");}
    public OcrExchange.Request request(){requireUsable();return frame==null?null:frame.request();}
    public boolean accept(OcrExchange.Reply reply){return !failed&&frame!=null&&frame.accept(reply);}
    public void cancel(){exchange.cancel();failed=true;frame=null;rehearsals=null;}
    public void advance(String name)throws StepException{
        requireUsable();
        if(started)throw new IllegalStateException("CURVES already started; resume pending OCR or start a new page");
        if(!name.equals("CURVES")){prefix.advance(name);return;}
        if(!sheet.getStub().isDone(OmrStep.CHORDS)||sheet.getStub().isDone(OmrStep.CURVES))throw new IllegalStateException("Complete CHORDS before running CURVES once");
        run(()->{started=true;rehearsals=new Curves(sheet).buildCurvesUntilRehearsals().beginContinuation();next();});
    }
    public void resume()throws StepException{
        requireUsable();
        if(frame==null||exchange.state()!=OcrExchange.State.READY)throw new IllegalStateException("No ready rehearsal OCR reply");
        run(()->{
            var reply=exchange.takeReply();frame=null;
            if(reply.failure()!=null)throw new StepException("External OCR "+reply.failure().code()+": "+reply.failure().message());
            rehearsals.accept(WorkerTextsBridge.textLines(sheet,reply.result()));next();
        });
    }
    private void next(){
        var scan=rehearsals.next();
        if(scan==null){rehearsals=null;return;}
        frame=OcrImageFrame.begin(exchange,scan.buffer.getWidth(),scan.buffer.getHeight(),(byte[])scan.buffer.getPixels(),margin,languages,OcrExchange.Layout.SINGLE_BLOCK);
    }
    @FunctionalInterface private interface Action{void run()throws StepException;}
    private void run(Action action)throws StepException{
        Sheet previous=HeadlessSheetContext.current();HeadlessSheetContext.bind(sheet);long errors=HeadlessLogging.errorCount();
        try{action.run();if(HeadlessLogging.errorCount()!=errors)throw new IllegalStateException("Recognition logged errors during CURVES");if(started&&rehearsals==null)sheet.getStub().done(OmrStep.CURVES);}
        catch(StepException|RuntimeException error){failed=true;frame=null;rehearsals=null;throw error;}
        catch(Error error){failed=true;frame=null;rehearsals=null;throw error;}
        finally{HeadlessSheetContext.bind(previous);}
    }
}
