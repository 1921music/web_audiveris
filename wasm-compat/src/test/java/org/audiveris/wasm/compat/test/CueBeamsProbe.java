package org.audiveris.wasm.compat.test;

import org.audiveris.omr.sheet.*;
import org.audiveris.omr.sheet.beam.CueBeamsStep;
import org.audiveris.omr.step.*;
import org.audiveris.wasm.compat.platform.HeadlessSheetContext;
import org.teavm.jso.JSExport;
import org.teavm.jso.typedarrays.Int8Array;

/** Next-step dependency probe; active cue-beam recognition is not yet verified. */
public final class CueBeamsProbe {
    private static Boolean smallHeads;
    @JSExport public static void configureSmallHeads(boolean value){smallHeads=value;}
    public static void main(String[] args) { }
    @JSExport public static void configure(Int8Array bytes){ReductionProbe.configure(bytes);}
    @JSExport public static void installTemplates(Int8Array bytes) throws Exception {ReductionProbe.installTemplates(bytes);}
    @JSExport public static void configureSerifs(String f,int i,double a,double b,double c,double d){ReductionProbe.configureSerifs(f,i,a,b,c,d);}
    @JSExport public static void configureGeometry(int k,String f,String s,int i,int n,int d,double a,double b,double c,double e){ReductionProbe.configureGeometry(k,f,s,i,n,d,a,b,c,e);}
    @JSExport public static void configurePointWidth(String f,String s,int p,double w){ReductionProbe.configurePointWidth(f,s,p,w);}
    @JSExport public static boolean resourceContract(){return ReductionProbe.resourceContract();}
    @JSExport public static String sortContract(){return ReductionProbe.sortContract();}
    @JSExport public static boolean stemDebugRejected(){return ReductionProbe.stemDebugRejected();}
    @JSExport public static Int8Array headPixels(){return ReductionProbe.headPixels();}
    @JSExport public static Int8Array distancePixels(){return ReductionProbe.distancePixels();}
    @JSExport public static void configureDebug(Int8Array bytes){ReductionProbe.configureDebug(bytes);}
    @JSExport public static boolean debugImageRejected() throws Exception {return ReductionProbe.debugImageRejected();}
    @JSExport public static void configurePreview(Int8Array bytes){ReductionProbe.configurePreview(bytes);}
    @JSExport public static boolean previewRejected() throws Exception {return ReductionProbe.previewRejected();}
    @JSExport public static String page(int w,int h,Int8Array pixels,String id) throws Exception {return analyze(PixelSheetProbe.create(w,h,pixels.copyToJavaArray(),id));}
    @JSExport public static String grayPage(int w,int h,Int8Array pixels,String id,double mean,double deviation) throws Exception {
        if(!Double.isFinite(mean)||!Double.isFinite(deviation)){throw new IllegalArgumentException("Adaptive coefficients must be finite");}
        var gray=org.audiveris.wasm.compat.platform.GrayInput.takeOwnership(w,h,pixels.copyToJavaArray()).takeProcessor();
        return analyze(PixelSheetProbe.create(gray,new org.audiveris.omr.image.VerticalFilter(gray,mean,deviation).filteredImage(),id));
    }
    @JSExport public static String fixture() throws Exception {return analyze(PixelSheetProbe.create(1024,300,GridProbe.staffPixels(),"heads-staff"));}
    public static String analyze(Sheet sheet) throws Exception {
        if(smallHeads!=null){sheet.getStub().getProcessingSwitches().getParam(ProcessingSwitch.smallHeads).setSpecific(smallHeads);}
        String prefix=ReductionProbe.analyze(sheet);if(prefix.contains("\"rejected\":true")){return prefix;}
        Sheet previous=HeadlessSheetContext.current();HeadlessSheetContext.bind(sheet);
        try {
            sheet.getStub().done(OmrStep.REDUCTION);boolean active=new ExposedStep().run(sheet);BeamProbe.checkErrors();
            int vertices=0,relations=0;
            for(SystemInfo system:sheet.getSystems()){vertices+=system.getSig().vertexSet().size();relations+=system.getSig().edgeSet().size();}
            var result=new StringBuilder(prefix.substring(0,prefix.length()-1)).append(",\"cueBeamsActive\":").append(active)
                .append(",\"cueVertices\":").append(vertices).append(",\"cueRelations\":").append(relations).append(",\"cueBeams\":[");boolean first=true;
            for(SystemInfo system:sheet.getSystems()){
                var beams=new java.util.ArrayList<>(system.getSig().inters(org.audiveris.omr.sig.inter.SmallBeamInter.class));
                beams.sort(java.util.Comparator.comparingInt((org.audiveris.omr.sig.inter.Inter inter)->inter.getBounds().x).thenComparingInt(inter->inter.getBounds().y));
                for(var inter:beams){var beam=(org.audiveris.omr.sig.inter.SmallBeamInter)inter;var box=beam.getBounds();var line=beam.getMedian();
                    if(!first){result.append(',');}first=false;
                    result.append("{\"system\":").append(system.getId()).append(",\"grade\":").append(beam.getGrade()).append(",\"bounds\":[")
                        .append(box.x).append(',').append(box.y).append(',').append(box.width).append(',').append(box.height).append("],\"median\":[")
                        .append(line.getX1()).append(',').append(line.getY1()).append(',').append(line.getX2()).append(',').append(line.getY2())
                        .append("],\"height\":").append(beam.getHeight()).append('}');
                }
            }
            result.append("],\"cueEdges\":[");BeamProbe.appendRelations(result,sheet);BeamProbe.checkErrors();return result.append("]}").toString();
        }finally{HeadlessSheetContext.bind(previous);}
    }
    private static final class ExposedStep extends CueBeamsStep {
        boolean run(Sheet sheet) throws StepException {
            Context context=super.doProlog(sheet);
            for(SystemInfo system:sheet.getSystems()){super.doSystem(system,context);}
            super.doEpilog(sheet,context);return context!=null;
        }
    }
}
