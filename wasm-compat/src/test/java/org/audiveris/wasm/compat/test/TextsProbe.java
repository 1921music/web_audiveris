package org.audiveris.wasm.compat.test;

import org.audiveris.omr.sheet.*;
import org.audiveris.omr.text.TextsStep;
import org.audiveris.omr.step.*;
import org.audiveris.wasm.compat.platform.HeadlessSheetContext;
import org.teavm.jso.JSExport;
import org.teavm.jso.typedarrays.Int8Array;

/** Unmodified TEXTS entry for dependency discovery; OCR is not replaced by an empty result. */
public final class TextsProbe {
    public static void main(String[] args) { }
    @JSExport public static void configure(Int8Array bytes){CueBeamsProbe.configure(bytes);}
    @JSExport public static void installTemplates(Int8Array bytes) throws Exception {CueBeamsProbe.installTemplates(bytes);}
    @JSExport public static void configureSerifs(String f,int i,double a,double b,double c,double d){CueBeamsProbe.configureSerifs(f,i,a,b,c,d);}
    @JSExport public static void configureGeometry(int k,String f,String s,int i,int n,int d,double a,double b,double c,double e){CueBeamsProbe.configureGeometry(k,f,s,i,n,d,a,b,c,e);}
    @JSExport public static void configurePointWidth(String f,String s,int p,double w){CueBeamsProbe.configurePointWidth(f,s,p,w);}
    @JSExport public static String fixture() throws Exception {return analyze(PixelSheetProbe.create(1024,300,GridProbe.staffPixels(),"heads-staff"));}
    public static String analyze(Sheet sheet) throws Exception {
        String prefix=CueBeamsProbe.analyze(sheet);if(prefix.contains("\"rejected\":true")){return prefix;}
        Sheet previous=HeadlessSheetContext.current();HeadlessSheetContext.bind(sheet);
        try {
            sheet.getStub().done(OmrStep.CUE_BEAMS);new ExposedStep().run(sheet);BeamProbe.checkErrors();
            int vertices=0;for(SystemInfo system:sheet.getSystems()){vertices+=system.getSig().vertexSet().size();}
            return prefix.substring(0,prefix.length()-1)+",\"textVertices\":"+vertices+"}";
        }finally{HeadlessSheetContext.bind(previous);}
    }
    private static final class ExposedStep extends TextsStep {
        void run(Sheet sheet) throws StepException {
            Context context=super.doProlog(sheet);
            for(SystemInfo system:sheet.getSystems()){super.doSystem(system,context);}
            super.doEpilog(sheet,context);
        }
    }
}
