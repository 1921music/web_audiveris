package org.audiveris.wasm.compat.test;

import ij.process.ByteProcessor;
import java.util.Arrays;
import org.audiveris.omr.sheet.*;
import org.audiveris.omr.sheet.note.DistancesBuilder;
import org.teavm.jso.JSExport;
import org.teavm.jso.typedarrays.Int8Array;

/** Full distance-table comparison after the original ledger pipeline. */
public final class DistanceProbe {
    public static boolean portableJvm;
    private static byte[] distances;
    public static void main(String[] args) { }
    @JSExport public static void configure(Int8Array model) { LedgerProbe.configure(model); }
    @JSExport public static void configureSerifs(String f,int i,double a,double b,double c,double d) { LedgerProbe.configureSerifs(f,i,a,b,c,d); }
    @JSExport public static void configureGeometry(int k,String f,String s,int i,int n,int d,double a,double b,double c,double e) { LedgerProbe.configureGeometry(k,f,s,i,n,d,a,b,c,e); }
    @JSExport public static void configurePointWidth(String f,String s,int p,double w) { LedgerProbe.configurePointWidth(f,s,p,w); }
    @JSExport public static boolean resourceContract() { return LedgerProbe.resourceContract(); }
    @JSExport public static void configureDebug(Int8Array model) { LedgerProbe.configureDebug(model); }
    @JSExport public static boolean debugImageRejected() throws Exception { return LedgerProbe.debugImageRejected(); }
    @JSExport public static void configurePreview(Int8Array model) { LedgerProbe.configurePreview(model); }
    @JSExport public static boolean previewRejected() throws Exception { return LedgerProbe.previewRejected(); }
    @JSExport public static Int8Array headPixels() { return LedgerProbe.headPixels(); }
    @JSExport public static Int8Array distancePixels() { return Int8Array.copyFromJavaArray(distancePixelsJava()); }
    public static byte[] distancePixelsJava() { return distances; }
    @JSExport public static String fixture() throws Exception { return analyze(PixelSheetProbe.create(1024,300,GridProbe.staffPixels(),"distance-staff")); }
    @JSExport public static String page(int w,int h,Int8Array pixels,String id) throws Exception { return analyze(PixelSheetProbe.create(w,h,pixels.copyToJavaArray(),id)); }
    @JSExport public static String grayPage(int w,int h,Int8Array pixels,String id,double mean,double deviation) throws Exception {
        if (!Double.isFinite(mean)||!Double.isFinite(deviation)) { throw new IllegalArgumentException("Adaptive coefficients must be finite"); }
        var gray=org.audiveris.wasm.compat.platform.GrayInput.takeOwnership(w,h,pixels.copyToJavaArray()).takeProcessor();
        return analyze(PixelSheetProbe.create(gray,new org.audiveris.omr.image.VerticalFilter(gray,mean,deviation).filteredImage(),id));
    }
    public static String analyze(Sheet sheet) throws Exception {
        distances=null; String ledger=LedgerProbe.analyze(sheet);
        if (ledger.contains("\"rejected\":true")) { return ledger; }
        var previous=org.audiveris.wasm.compat.platform.HeadlessSheetContext.current();
        org.audiveris.wasm.compat.platform.HeadlessSheetContext.bind(sheet);
        try {
        var input=sheet.getPicture().getSource(Picture.SourceKey.BINARY);
        byte[] before=((byte[])input.getPixels()).clone();
        var builder=portableJvm?new Portable(sheet):new DistancesBuilder(sheet);
        var table=builder.buildDistances();
        if (portableJvm) {
            var nativeTable=new DistancesBuilder(sheet).buildDistances();
            if (table.getNormalizer()!=nativeTable.getNormalizer()) { throw new AssertionError("Normalizer differs"); }
            for (int i=0;i<sheet.getWidth()*sheet.getHeight();i++) {
                if (table.getValue(i)!=nativeTable.getValue(i)) { throw new AssertionError("Current native/raw distance differs at "+i); }
            }
        }
        if (!Arrays.equals(before,(byte[])input.getPixels())) { throw new AssertionError("Distance preparation mutated binary input"); }
        distances=new byte[2*sheet.getWidth()*sheet.getHeight()];
        int min=Integer.MAX_VALUE,max=Integer.MIN_VALUE,unknown=0,zero=0;
        for(int i=0;i<distances.length/2;i++) {
            int v=table.getValue(i); min=Math.min(min,v);max=Math.max(max,v);
            if(v==org.audiveris.omr.image.ChamferDistance.VALUE_UNKNOWN) {unknown++;} if(v==0) {zero++;}
            distances[2*i]=(byte)v;distances[2*i+1]=(byte)(v>>>8);
        }
        BeamProbe.checkErrors();
        return ledger.substring(0,ledger.length()-1)+",\"distances\":["+table.getWidth()+","+table.getHeight()+","+table.getNormalizer()+","+min+","+max+","+unknown+","+zero+"]}";
        } finally { org.audiveris.wasm.compat.platform.HeadlessSheetContext.bind(previous); }
    }
    private static final class Portable extends DistancesBuilder {
        Portable(Sheet sheet) { super(sheet); }
        @Override protected ByteProcessor erasedBuffer(ByteProcessor input) { return erasedRawBuffer(input); }
    }
}
