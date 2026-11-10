package org.audiveris.wasm.compat.test;

import org.audiveris.omr.sheet.*;
import org.audiveris.omr.sheet.stem.StemsRetriever;
import org.audiveris.omr.sig.inter.BeamGroupInter;
import org.audiveris.wasm.compat.platform.HeadlessSheetContext;
import org.teavm.jso.JSExport;
import org.teavm.jso.typedarrays.Int8Array;

/** Next-step dependency probe; recognition parity is not yet established for STEMS. */
public final class ConnectedStemsProbe {
    public static void main(String[] args) { }
    @JSExport public static void configure(Int8Array bytes) {HeadsProbe.configure(bytes);}
    @JSExport public static void installTemplates(Int8Array bytes) throws Exception {HeadsProbe.installTemplates(bytes);}
    @JSExport public static void configureSerifs(String f,int i,double a,double b,double c,double d) {HeadsProbe.configureSerifs(f,i,a,b,c,d);}
    @JSExport public static void configureGeometry(int k,String f,String s,int i,int n,int d,double a,double b,double c,double e) {HeadsProbe.configureGeometry(k,f,s,i,n,d,a,b,c,e);}
    @JSExport public static void configurePointWidth(String f,String s,int p,double w) {HeadsProbe.configurePointWidth(f,s,p,w);}
    @JSExport public static String fixture() throws Exception {return analyze(PixelSheetProbe.create(1024,300,GridProbe.staffPixels(),"connected-stems"));}
    @SuppressWarnings("deprecation")
    public static String analyze(Sheet sheet) throws Exception {
        String prefix=HeadsProbe.analyze(sheet);if(prefix.contains("\"rejected\":true")){return prefix;}
        Sheet previous=HeadlessSheetContext.current();HeadlessSheetContext.bind(sheet);
        try {
            for(var step:org.audiveris.omr.step.OmrStep.values()) {
                if(step.compareTo(org.audiveris.omr.step.OmrStep.HEADS)<=0) {sheet.getStub().done(step);}
            }
            for(SystemInfo system:sheet.getSystems()){BeamGroupInter.checkSystemForOldBeamGroup(system);}
            for(SystemInfo system:sheet.getSystems()){new StemsRetriever(system).process();}
            int stems=0;
            for(SystemInfo system:sheet.getSystems()){
                new StemsRetriever(system).finalizeBeams();system.getSig().contextualize();
                stems+=system.getSig().inters(org.audiveris.omr.sig.inter.StemInter.class).size();
            }
            BeamProbe.checkErrors();
            return prefix.substring(0,prefix.length()-1)+",\"connectedStemsCount\":"+stems+"}";
        }finally{HeadlessSheetContext.bind(previous);}
    }
}
