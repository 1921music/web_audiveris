package org.audiveris.wasm.compat.test;

import java.util.*;
import org.audiveris.omr.sheet.*;
import org.audiveris.omr.sheet.note.*;
import org.audiveris.wasm.compat.platform.HeadlessSheetContext;
import org.teavm.jso.JSExport;
import org.teavm.jso.typedarrays.Int8Array;

/** Original HEADS algorithms with explicit host template catalogs and complete recognition snapshots. */
public final class HeadsProbe {
    private static byte[] capturedSpots;
    public static void main(String[] args) { }
    @JSExport public static void configure(Int8Array model) { DistanceProbe.configure(model); }
    @JSExport public static void configureDebug(Int8Array model) {DistanceProbe.configureDebug(model);}
    @JSExport public static boolean debugImageRejected() throws Exception {return DistanceProbe.debugImageRejected();}
    @JSExport public static void configurePreview(Int8Array model) {DistanceProbe.configurePreview(model);}
    @JSExport public static boolean previewRejected() throws Exception {return DistanceProbe.previewRejected();}
    @JSExport public static void configureSerifs(String f,int i,double a,double b,double c,double d) { DistanceProbe.configureSerifs(f,i,a,b,c,d); }
    @JSExport public static void configureGeometry(int k,String f,String s,int i,int n,int d,double a,double b,double c,double e) { DistanceProbe.configureGeometry(k,f,s,i,n,d,a,b,c,e); }
    @JSExport public static void configurePointWidth(String f,String s,int p,double w) { DistanceProbe.configurePointWidth(f,s,p,w); }
    @JSExport public static void installTemplates(Int8Array bytes) throws Exception {org.audiveris.wasm.compat.platform.WorkerTemplates.install(bytes.copyToJavaArray());}
    @JSExport public static boolean resourceContract() {return DistanceProbe.resourceContract();}
    public static byte[] headPixelsJava() {return capturedSpots;}
    @JSExport public static Int8Array headPixels() {return Int8Array.copyFromJavaArray(capturedSpots);}
    @JSExport public static Int8Array distancePixels() {return DistanceProbe.distancePixels();}
    @JSExport public static String page(int w,int h,Int8Array pixels,String id) throws Exception {return analyze(PixelSheetProbe.create(w,h,pixels.copyToJavaArray(),id));}
    @JSExport public static String grayPage(int w,int h,Int8Array pixels,String id,double mean,double deviation) throws Exception {
        if(!Double.isFinite(mean)||!Double.isFinite(deviation)) {throw new IllegalArgumentException("Adaptive coefficients must be finite");}
        var gray=org.audiveris.wasm.compat.platform.GrayInput.takeOwnership(w,h,pixels.copyToJavaArray()).takeProcessor();
        return analyze(PixelSheetProbe.create(gray,new org.audiveris.omr.image.VerticalFilter(gray,mean,deviation).filteredImage(),id));
    }
    @JSExport public static String fixture() throws Exception {
        return analyze(PixelSheetProbe.create(1024,300,GridProbe.staffPixels(),"heads-staff"));
    }
    public static String analyze(Sheet sheet) throws Exception {
        capturedSpots=null;
        String prefix=DistanceProbe.analyze(sheet);
        if(prefix.contains("\"rejected\":true")) {return prefix;}
        Sheet previous=HeadlessSheetContext.current(); HeadlessSheetContext.bind(sheet);
        try {
            DistancesBuilder builder=DistanceProbe.portableJvm?new PortableDistances(sheet):new DistancesBuilder(sheet);
            var table=builder.buildDistances();
            var spots=new HeadSpotsBuilder(sheet).getSpots();
            var tallies=new TreeMap<SystemInfo,HeadSeedTally>();
            for(SystemInfo system:sheet.getSystems()) {tallies.put(system,new HeadSeedTally());}
            var drumDumped=new java.util.TreeMap<Integer,java.util.Map<String,Boolean>>();
            for(SystemInfo system:sheet.getSystems()) {
                var tally=tallies.get(system);
                new NoteHeadsBuilder(system,table,spots.get(system),tally,false,drumDumped).buildHeads();
            }
            var cachedSpots=sheet.getPicture().getVerticalTable(Picture.TableKey.HEAD_SPOTS);
            capturedSpots=((byte[])cachedSpots.getBuffer().getPixels()).clone();
            sheet.getPicture().discardImage(Picture.ImageKey.HEAD_SPOTS);
            var stillLive=sheet.getPicture().getVerticalTable(Picture.TableKey.HEAD_SPOTS);
            java.lang.ref.Reference.reachabilityFence(cachedSpots);
            if(stillLive!=cachedSpots) {throw new AssertionError("Discarding HEAD_SPOTS invalidated a live weak cache");}
            HeadSeedTally.analyze(sheet,tallies.values()); BeamProbe.checkErrors();
            var result=new StringBuilder(prefix.substring(0,prefix.length()-1)).append(",\"heads\":[");boolean first=true;
            Set<String> catalogs=new TreeSet<>();
            for(SystemInfo system:sheet.getSystems()) {
                for(Staff staff:system.getStaves()) {if(!staff.isTablature()) {catalogs.add(sheet.getStub().getMusicFamily().name()+":"+staff.getHeadPointSize());}}
                var heads=new ArrayList<>(system.getSig().inters(org.audiveris.omr.sig.inter.HeadInter.class));
                heads.sort(Comparator.comparingInt((org.audiveris.omr.sig.inter.Inter h)->h.getStaff().getId())
                        .thenComparingInt(h->h.getBounds().y).thenComparingInt(h->h.getBounds().x).thenComparing(h->h.getShape().name()));
                for(var inter:heads) {
                    var head=(org.audiveris.omr.sig.inter.HeadInter)inter;var box=head.getBounds();
                    if(!first){result.append(',');}first=false;
                    result.append("{\"system\":").append(system.getId()).append(",\"staff\":").append(head.getStaff().getId())
                        .append(",\"shape\":\"").append(head.getShape().name()).append("\",\"pitch\":").append(head.getPitch())
                        .append(",\"grade\":").append(head.getGrade()).append(",\"bounds\":[").append(box.x).append(',').append(box.y).append(',').append(box.width).append(',').append(box.height)
                        .append("],\"glyph\":");
                    var glyph=head.getGlyph();
                    if(glyph==null){result.append("null");}else{
                        var origin=glyph.getTopLeft();var runs=glyph.getRunTable();
                        result.append("{\"left\":").append(origin.x).append(",\"top\":").append(origin.y).append(",\"weight\":").append(glyph.getWeight())
                            .append(",\"width\":").append(runs.getWidth()).append(",\"height\":").append(runs.getHeight())
                            .append(",\"orientation\":\"").append(runs.getOrientation().name()).append("\",\"runs\":[");boolean firstRun=true;
                        for(int sequence=0;sequence<runs.getSize();sequence++){for(var iterator=runs.iterator(sequence);iterator.hasNext();){
                            var run=iterator.next();if(!firstRun){result.append(',');}firstRun=false;
                            result.append('[').append(sequence).append(',').append(run.getStart()).append(',').append(run.getLength()).append(']');
                        }}result.append("]}");
                    }result.append('}');
                }
            }
            result.append("],\"headRelations\":[");BeamProbe.appendRelations(result,sheet);
            result.append("],\"templatesUsed\":[");first=true;
            for(String key:catalogs){if(!first){result.append(',');}first=false;result.append('"').append(key).append('"');}
            result.append("],\"headSeedScale\":[");first=true;var seedScale=sheet.getScale().getHeadSeedScale();
            if(seedScale!=null){for(var shape:org.audiveris.omr.glyph.ShapeSet.Heads){for(var side:org.audiveris.omr.util.HorizontalSide.values()){
                Double dx=seedScale.getDx(shape,side);if(dx==null){continue;}if(!first){result.append(',');}first=false;
                result.append("[\"").append(shape.name()).append("\",\"").append(side.name()).append("\",").append(dx).append(']');
            }}}
            return result.append("],\"headSpotsCacheRetainedWhileLive\":true}").toString();
        } finally {HeadlessSheetContext.bind(previous);}
    }
    private static final class PortableDistances extends DistancesBuilder {
        PortableDistances(Sheet sheet){super(sheet);}
        @Override protected ij.process.ByteProcessor erasedBuffer(ij.process.ByteProcessor input){return erasedRawBuffer(input);}
    }
}
