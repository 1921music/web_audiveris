// SPDX-License-Identifier: AGPL-3.0-or-later
package org.audiveris.wasm.compat.platform;

import org.audiveris.omr.sheet.*;
import org.audiveris.omr.sheet.beam.*;
import org.audiveris.omr.sheet.grid.GridStep;
import org.audiveris.omr.sheet.header.HeadersStep;
import org.audiveris.omr.sheet.ledger.LedgersStep;
import org.audiveris.omr.sheet.note.HeadsStep;
import org.audiveris.omr.sheet.stem.*;
import org.audiveris.omr.step.*;
import org.audiveris.omr.text.WorkerTextsBridge;
import org.audiveris.omr.ui.symbol.MusicFamily;

/** One owned page; each call executes at most one recognition stage or OCR resume.
 * The host owns scheduling and may discard this session at any boundary.
 */
public final class RecognitionSession {
    private static final String[] PREFIX={"SCALE","GRID","HEADERS","STEM_SEEDS","BEAMS","LEDGERS","HEADS","STEMS","REDUCTION","CUE_BEAMS"};
    private static final String[] TAIL={"MEASURES","CHORDS","CURVES","SYMBOLS","LINKS","RHYTHMS","PAGE"};
    private final Sheet sheet;
    private final String job,languages;
    private final int generation,margin;
    private int prefixIndex,tailIndex;
    private String stage="BINARY";
    private boolean failed;
    private WorkerTextsBridge texts;
    private PageRecognitionContinuation tail;

    public RecognitionSession(GrayInput input,String job,int generation,String family,boolean smallHeads,
                              int margin,String languages,double mean,double deviation) {
        if(job==null||job.isBlank()||generation<0||margin<0||margin>1024||languages==null||languages.isBlank())
            throw new IllegalArgumentException("Invalid recognition options");
        if(!Double.isFinite(mean)||!Double.isFinite(deviation)||mean<0||deviation<0)
            throw new IllegalArgumentException("Invalid adaptive coefficients");
        this.job=job;this.generation=generation;this.margin=margin;this.languages=languages;
        var musicFamily=MusicFamily.valueOf(family);
        var gray=input.takeProcessor();
        var binary=new org.audiveris.omr.image.VerticalFilter(gray,mean,deviation).filteredImage();
        var book=Book.createMemoryBook(job);var stub=new SheetStub(book,1);book.addStub(stub);
        stub.getMusicFamilyParam().setSpecific(musicFamily);
        stub.getProcessingSwitches().getParam(ProcessingSwitch.smallHeads).setSpecific(smallHeads);
        sheet=stub.createMemorySheet(gray,binary);
    }
    public Sheet sheet(){return sheet;}
    public String stage(){return stage;}
    public boolean complete(){return !failed&&tailIndex==TAIL.length;}
    public boolean terminal(){return failed||complete();}
    private void requireActive(){if(terminal())throw new IllegalStateException("Recognition session is terminal");}
    public OcrExchange.Request request(){
        if(terminal())return null;
        if(tail!=null)return tail.request();
        return texts==null?null:texts.request();
    }
    public boolean accept(OcrExchange.Reply reply){
        if(terminal())return false;
        return tail!=null?tail.accept(reply):texts!=null&&texts.accept(reply);
    }
    public void cancel(){failed=true;if(texts!=null)texts.cancel();if(tail!=null)tail.cancel();}
    public void advance()throws StepException{
        requireActive();
        if(request()!=null)throw new IllegalStateException("Resume or cancel pending OCR before advancing");
        run(()->{
            if(prefixIndex<PREFIX.length){
                String next=PREFIX[prefixIndex];
                switch(next){
                    case "SCALE"->new ScaleStep().doit(sheet);
                    case "GRID"->{new GridStep().doit(sheet);if(sheet.getSystems().isEmpty())throw new StepException("No music systems detected");}
                    case "HEADERS"->new HeadersStep().doitSerial(sheet);
                    case "STEM_SEEDS"->new StemSeedsStep().doitSerial(sheet);
                    case "BEAMS"->new BeamsStep().doitSerial(sheet);
                    case "LEDGERS"->new LedgersStep().doitSerial(sheet);
                    case "HEADS"->new HeadsStep().doitSerial(sheet);
                    case "STEMS"->new StemsStep().doitSerial(sheet);
                    case "REDUCTION"->new ReductionStep().doitSerial(sheet);
                    case "CUE_BEAMS"->new CueBeamsStep().doitSerial(sheet);
                    default->throw new AssertionError(next);
                }
                sheet.getStub().done(OmrStep.valueOf(next));stage=next;prefixIndex++;
            }else if(texts==null){
                texts=new WorkerTextsBridge(sheet,PortableTextsCleanup.clean(sheet),job,generation,margin,languages);
            }else{
                tail.advance(TAIL[tailIndex]);
                if(!tail.hasPending()){stage=TAIL[tailIndex++];}
            }
        });
    }
    public void resume()throws StepException{
        requireActive();
        run(()->{
            if(tail!=null){tail.resume();if(!tail.hasPending())stage=TAIL[tailIndex++];}
            else{
                if(texts==null)throw new IllegalStateException("No pending OCR");
                texts.resume();stage="TEXTS";
                tail=new PageRecognitionContinuation(sheet,job,generation,margin,languages);
            }
        });
    }
    @FunctionalInterface private interface Action{void run()throws StepException;}
    private void run(Action action)throws StepException{
        Sheet previous=HeadlessSheetContext.current();HeadlessSheetContext.bind(sheet);
        long errors=HeadlessLogging.errorCount();
        try{action.run();if(HeadlessLogging.errorCount()!=errors)throw new IllegalStateException("Recognition logged errors after "+stage);}
        catch(StepException|RuntimeException|Error error){cancel();throw error;}
        finally{HeadlessSheetContext.bind(previous);}
    }
}
