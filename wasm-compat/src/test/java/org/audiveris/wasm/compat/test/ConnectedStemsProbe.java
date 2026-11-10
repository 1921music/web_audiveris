package org.audiveris.wasm.compat.test;

import org.audiveris.omr.sheet.*;
import org.audiveris.omr.sheet.stem.StemsRetriever;
import org.audiveris.omr.sig.inter.BeamGroupInter;
import org.audiveris.wasm.compat.platform.HeadlessSheetContext;
import org.teavm.jso.JSExport;
import org.teavm.jso.typedarrays.Int8Array;
import java.util.*;
import org.audiveris.omr.sig.inter.*;

/** Original STEMS prolog, per-system processing and epilog with complete graph snapshots. */
public final class ConnectedStemsProbe {
    private static java.awt.Rectangle traceArea;
    @JSExport public static void traceRectangle(int x,int y,int w,int h){traceArea=new java.awt.Rectangle(x,y,w,h);}
    public static void main(String[] args) { }
    @JSExport public static String sortContract() {return StemSortContract.run(true);}
    @JSExport public static void configure(Int8Array bytes) {HeadsProbe.configure(bytes);}
    @JSExport public static void installTemplates(Int8Array bytes) throws Exception {HeadsProbe.installTemplates(bytes);}
    @JSExport public static void configureSerifs(String f,int i,double a,double b,double c,double d) {HeadsProbe.configureSerifs(f,i,a,b,c,d);}
    @JSExport public static void configureGeometry(int k,String f,String s,int i,int n,int d,double a,double b,double c,double e) {HeadsProbe.configureGeometry(k,f,s,i,n,d,a,b,c,e);}
    @JSExport public static void configurePointWidth(String f,String s,int p,double w) {HeadsProbe.configurePointWidth(f,s,p,w);}
    @JSExport public static boolean resourceContract() {return HeadsProbe.resourceContract();}
    @JSExport public static boolean stemDebugRejected() {
        try {org.audiveris.omr.sheet.stem.StemBuilder.saveConnection(null,null,null);return false;}
        catch(UnsupportedOperationException expected){return true;}
    }
    @JSExport public static Int8Array headPixels() {return HeadsProbe.headPixels();}
    @JSExport public static Int8Array distancePixels() {return HeadsProbe.distancePixels();}
    @JSExport public static void configureDebug(Int8Array bytes) {HeadsProbe.configureDebug(bytes);}
    @JSExport public static boolean debugImageRejected() throws Exception {return HeadsProbe.debugImageRejected();}
    @JSExport public static void configurePreview(Int8Array bytes) {HeadsProbe.configurePreview(bytes);}
    @JSExport public static boolean previewRejected() throws Exception {return HeadsProbe.previewRejected();}
    @JSExport public static String page(int w,int h,Int8Array pixels,String id) throws Exception {return analyze(PixelSheetProbe.create(w,h,pixels.copyToJavaArray(),id));}
    @JSExport public static String grayPage(int w,int h,Int8Array pixels,String id,double mean,double deviation) throws Exception {
        if(!Double.isFinite(mean)||!Double.isFinite(deviation)) {throw new IllegalArgumentException("Adaptive coefficients must be finite");}
        var gray=org.audiveris.wasm.compat.platform.GrayInput.takeOwnership(w,h,pixels.copyToJavaArray()).takeProcessor();
        return analyze(PixelSheetProbe.create(gray,new org.audiveris.omr.image.VerticalFilter(gray,mean,deviation).filteredImage(),id));
    }
    @JSExport public static String fixture() throws Exception {return analyze(PixelSheetProbe.create(1024,300,GridProbe.staffPixels(),"heads-staff"));}
    @SuppressWarnings("deprecation")
    public static String analyze(Sheet sheet) throws Exception {
        String prefix=HeadsProbe.analyze(sheet);if(prefix.contains("\"rejected\":true")){return prefix;}
        Sheet previous=HeadlessSheetContext.current();HeadlessSheetContext.bind(sheet);
        try {
            for(var step:org.audiveris.omr.step.OmrStep.values()) {
                if(step.compareTo(org.audiveris.omr.step.OmrStep.HEADS)<=0) {sheet.getStub().done(step);}
            }
            if(traceArea!=null){for(SystemInfo system:sheet.getSystems()){for(Inter inter:system.getSig().vertexSet()){
                if(inter.getBounds()!=null&&traceArea.intersects(inter.getBounds())){inter.setVip(true);}
            }}}
            for(SystemInfo system:sheet.getSystems()){BeamGroupInter.checkSystemForOldBeamGroup(system);}
            for(SystemInfo system:sheet.getSystems()){new StemsRetriever(system).process();}
            int stems=0;
            for(SystemInfo system:sheet.getSystems()){
                new StemsRetriever(system).finalizeBeams();system.getSig().contextualize();
                stems+=system.getSig().inters(org.audiveris.omr.sig.inter.StemInter.class).size();
            }
            BeamProbe.checkErrors();
            var result=new StringBuilder(prefix.substring(0,prefix.length()-1)).append(",\"connectedStemsCount\":").append(stems);
            result.append(",\"connectedStems\":[");boolean first=true;
            for(SystemInfo system:sheet.getSystems()) {
                var inters=new ArrayList<>(system.getSig().inters(StemInter.class));
                inters.sort(Comparator.comparing(ConnectedStemsProbe::key));
                for(Inter inter:inters) {
                    StemInter stem=(StemInter)inter;var line=stem.getMedian();var glyph=stem.getGlyph();var runs=glyph.getRunTable();
                    if(!first){result.append(',');}first=false;
                    result.append("{\"system\":").append(system.getId()).append(",\"key\":\"").append(key(stem))
                        .append("\",\"grade\":").append(stem.getGrade()).append(",\"contextualGrade\":").append(stem.getContextualGrade())
                        .append(",\"width\":").append(stem.getWidth()).append(",\"median\":[").append(line.getX1()).append(',').append(line.getY1())
                        .append(',').append(line.getX2()).append(',').append(line.getY2()).append("],\"glyph\":[")
                        .append(glyph.getLeft()).append(',').append(glyph.getTop()).append(',').append(glyph.getWeight())
                        .append(',').append(runs.getWidth()).append(',').append(runs.getHeight()).append("],\"runs\":[");boolean firstRun=true;
                    for(int sequence=0;sequence<runs.getSize();sequence++){for(var iterator=runs.iterator(sequence);iterator.hasNext();){
                        var run=iterator.next();if(!firstRun){result.append(',');}firstRun=false;
                        result.append('[').append(sequence).append(',').append(run.getStart()).append(',').append(run.getLength()).append(']');
                    }}result.append("]}");
                }
            }
            result.append("],\"stemRelations\":[");BeamProbe.appendRelations(result,sheet);
            result.append("],\"stemConnections\":[");first=true;
            for(SystemInfo system:sheet.getSystems()) {
                var graph=system.getSig();var edges=new ArrayList<>(graph.edgeSet());
                edges.sort(Comparator.comparing((org.audiveris.omr.sig.relation.Relation e)->e.getClass().getSimpleName())
                    .thenComparing(e->key(graph.getEdgeSource(e))).thenComparing(e->key(graph.getEdgeTarget(e))));
                for(var edge:edges) {
                    if(!(edge instanceof org.audiveris.omr.sig.relation.AbstractStemConnection connection)){continue;}
                    if(!first){result.append(',');}first=false;var point=connection.getExtensionPoint();
                    result.append("{\"system\":").append(system.getId()).append(",\"type\":\"").append(edge.getClass().getSimpleName())
                        .append("\",\"source\":\"").append(key(graph.getEdgeSource(edge))).append("\",\"target\":\"").append(key(graph.getEdgeTarget(edge)))
                        .append("\",\"dx\":").append(connection.getDx()).append(",\"dy\":").append(connection.getDy()).append(",\"extension\":");
                    if(point==null){result.append("null");}else{result.append('[').append(point.getX()).append(',').append(point.getY()).append(']');}
                    if(edge instanceof org.audiveris.omr.sig.relation.HeadStemRelation rel){result.append(",\"headSide\":\"").append(rel.getHeadSide()).append("\",\"consistency\":").append(rel.getConsistency());}
                    if(edge instanceof org.audiveris.omr.sig.relation.BeamStemRelation rel){result.append(",\"beamPortion\":\"").append(rel.getBeamPortion()).append('"');}
                    result.append('}');
                }
            }
            result.append("],\"contextualVertices\":[");first=true;
            for(SystemInfo system:sheet.getSystems()) {
                var vertices=new ArrayList<>(system.getSig().vertexSet());vertices.sort(Comparator.comparing(ConnectedStemsProbe::key));
                for(Inter inter:vertices){if(!first){result.append(',');}first=false;
                    result.append("{\"system\":").append(system.getId()).append(",\"key\":\"").append(key(inter)).append("\",\"grade\":").append(inter.getGrade())
                        .append(",\"contextualGrade\":").append(inter.getContextualGrade()).append('}');
                }
            }
            BeamProbe.checkErrors();return result.append("]}").toString();
        }finally{HeadlessSheetContext.bind(previous);}
    }
    private static String key(Inter inter){var box=inter.getBounds();return inter.getClass().getSimpleName()+":"+inter.getShape()+":"+box.x+":"+box.y+":"+box.width+":"+box.height;}
}
