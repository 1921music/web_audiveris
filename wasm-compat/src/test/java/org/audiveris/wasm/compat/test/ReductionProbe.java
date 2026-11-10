package org.audiveris.wasm.compat.test;

import org.audiveris.omr.sheet.*;
import org.audiveris.omr.step.*;
import org.audiveris.wasm.compat.platform.HeadlessSheetContext;
import org.teavm.jso.JSExport;
import org.teavm.jso.typedarrays.Int8Array;
import java.util.*;
import org.audiveris.omr.sig.inter.*;

/** Original REDUCTION processing; runtime parity is checked separately from compilation. */
public final class ReductionProbe {
    public static void main(String[] args) { }
    @JSExport public static void configure(Int8Array bytes){ConnectedStemsProbe.configure(bytes);}
    @JSExport public static void installTemplates(Int8Array bytes) throws Exception {ConnectedStemsProbe.installTemplates(bytes);}
    @JSExport public static void configureSerifs(String f,int i,double a,double b,double c,double d){ConnectedStemsProbe.configureSerifs(f,i,a,b,c,d);}
    @JSExport public static void configureGeometry(int k,String f,String s,int i,int n,int d,double a,double b,double c,double e){ConnectedStemsProbe.configureGeometry(k,f,s,i,n,d,a,b,c,e);}
    @JSExport public static void configurePointWidth(String f,String s,int p,double w){ConnectedStemsProbe.configurePointWidth(f,s,p,w);}
    @JSExport public static boolean resourceContract(){return ConnectedStemsProbe.resourceContract();}
    @JSExport public static String sortContract(){return ConnectedStemsProbe.sortContract();}
    @JSExport public static boolean stemDebugRejected(){return ConnectedStemsProbe.stemDebugRejected();}
    @JSExport public static Int8Array headPixels(){return ConnectedStemsProbe.headPixels();}
    @JSExport public static Int8Array distancePixels(){return ConnectedStemsProbe.distancePixels();}
    @JSExport public static void configureDebug(Int8Array bytes){ConnectedStemsProbe.configureDebug(bytes);}
    @JSExport public static boolean debugImageRejected() throws Exception {return ConnectedStemsProbe.debugImageRejected();}
    @JSExport public static void configurePreview(Int8Array bytes){ConnectedStemsProbe.configurePreview(bytes);}
    @JSExport public static boolean previewRejected() throws Exception {return ConnectedStemsProbe.previewRejected();}
    @JSExport public static String page(int w,int h,Int8Array pixels,String id) throws Exception {return analyze(PixelSheetProbe.create(w,h,pixels.copyToJavaArray(),id));}
    @JSExport public static String grayPage(int w,int h,Int8Array pixels,String id,double mean,double deviation) throws Exception {
        if(!Double.isFinite(mean)||!Double.isFinite(deviation)){throw new IllegalArgumentException("Adaptive coefficients must be finite");}
        var gray=org.audiveris.wasm.compat.platform.GrayInput.takeOwnership(w,h,pixels.copyToJavaArray()).takeProcessor();
        return analyze(PixelSheetProbe.create(gray,new org.audiveris.omr.image.VerticalFilter(gray,mean,deviation).filteredImage(),id));
    }
    @JSExport public static String fixture() throws Exception {return analyze(PixelSheetProbe.create(1024,300,GridProbe.staffPixels(),"heads-staff"));}
    public static String analyze(Sheet sheet) throws Exception {
        String prefix=ConnectedStemsProbe.analyze(sheet);if(prefix.contains("\"rejected\":true")){return prefix;}
        Sheet previous=HeadlessSheetContext.current();HeadlessSheetContext.bind(sheet);
        try {
            sheet.getStub().done(OmrStep.STEMS);
            ExposedStep step=new ExposedStep();
            for(SystemInfo system:sheet.getSystems()){step.doSystem(system,null);}
            step.finish(sheet);BeamProbe.checkErrors();
            int vertices=0,relations=0;
            for(SystemInfo system:sheet.getSystems()){vertices+=system.getSig().vertexSet().size();relations+=system.getSig().edgeSet().size();}
            var result=new StringBuilder(prefix.substring(0,prefix.length()-1)).append(",\"reducedVertices\":").append(vertices)
                .append(",\"reducedRelations\":").append(relations).append(",\"reducedInters\":[");boolean first=true;
            Set<org.audiveris.omr.glyph.Glyph> retained=new HashSet<>();
            for(SystemInfo system:sheet.getSystems()) {
                for(Staff staff:system.getStaves()){for(var staffLine:staff.getLines()){
                    var glyph=((org.audiveris.omr.sheet.StaffLine)staffLine).getGlyph();if(glyph!=null){retained.add(glyph);}
                }}
                var inters=new ArrayList<>(system.getSig().vertexSet());inters.sort(Comparator.comparing(ReductionProbe::key));
                for(Inter inter:inters) {
                    if(!first){result.append(',');}first=false;
                    result.append("{\"system\":").append(system.getId()).append(",\"key\":\"").append(key(inter))
                        .append("\",\"staff\":").append(inter.getStaff()==null?"null":inter.getStaff().getId())
                        .append(",\"grade\":").append(inter.getGrade()).append(",\"contextualGrade\":").append(inter.getContextualGrade())
                        .append(",\"abnormal\":").append(inter.isAbnormal()).append(",\"frozen\":").append(inter.isFrozen())
                        .append(",\"implicit\":").append(inter.isImplicit()).append(",\"manual\":").append(inter.isManual());
                    java.awt.geom.Line2D line=inter instanceof AbstractVerticalInter v?v.getMedian():inter instanceof AbstractHorizontalInter h?h.getMedian():null;
                    if(line!=null){result.append(",\"median\":[").append(line.getX1()).append(',').append(line.getY1()).append(',').append(line.getX2()).append(',').append(line.getY2()).append(']');}
                    if(inter instanceof HeadInter head){result.append(",\"pitch\":").append(head.getPitch());}
                    if(inter instanceof StemInter stem){result.append(",\"width\":").append(stem.getWidth()).append(",\"freeLength\":").append(stem.getFreeLength());}
                    var glyph=inter.getGlyph();result.append(",\"glyph\":");
                    if(glyph==null){result.append("null");}else{
                        retained.add(glyph);var runs=glyph.getRunTable();
                        result.append("{\"left\":").append(glyph.getLeft()).append(",\"top\":").append(glyph.getTop())
                            .append(",\"weight\":").append(glyph.getWeight()).append(",\"width\":").append(runs.getWidth()).append(",\"height\":").append(runs.getHeight())
                            .append(",\"orientation\":\"").append(runs.getOrientation().name()).append("\",\"runs\":[");boolean firstRun=true;
                        for(int sequence=0;sequence<runs.getSize();sequence++){for(var iterator=runs.iterator(sequence);iterator.hasNext();){
                            var run=iterator.next();if(!firstRun){result.append(',');}firstRun=false;
                            result.append('[').append(sequence).append(',').append(run.getStart()).append(',').append(run.getLength()).append(']');
                        }}result.append("]}");
                    }result.append('}');
                }
            }
            for(var glyph:sheet.getGlyphIndex().getEntities()){if(!retained.contains(glyph)){throw new AssertionError("Unreferenced glyph survived reduction cleanup");}}
            result.append("],\"reducedEdges\":[");BeamProbe.appendRelations(result,sheet);BeamProbe.checkErrors();
            return result.append("],\"unreferencedGlyphsRemoved\":true}").toString();
        }finally{HeadlessSheetContext.bind(previous);}
    }
    private static String key(Inter inter){var box=inter.getBounds();return inter.getClass().getSimpleName()+":"+inter.getShape()+":"+(box==null?"null":box.x+":"+box.y+":"+box.width+":"+box.height);}
    private static final class ExposedStep extends ReductionStep {
        void finish(Sheet sheet) throws StepException {super.doEpilog(sheet,null);}
    }
}
