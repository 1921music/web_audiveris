package org.audiveris.wasm.compat.test;

import java.util.*;
import org.audiveris.omr.sheet.*;
import org.audiveris.omr.sheet.ledger.*;
import org.audiveris.wasm.compat.platform.HeadlessSheetContext;
import org.teavm.jso.JSExport;
import org.teavm.jso.typedarrays.Int8Array;

/** Original ledger filtering, construction and post-analysis, following the beam pipeline. */
public final class LedgerProbe {
    public static void main(String[] args) { }
    @JSExport public static void configure(Int8Array model) { BeamProbe.configure(model); }
    @JSExport public static void configureSerifs(String f, int i, double a, double b, double c, double d) { BeamProbe.configureSerifs(f,i,a,b,c,d); }
    @JSExport public static void configureGeometry(int k, String f, String s, int i, int n, int d, double a, double b, double c, double e) { BeamProbe.configureGeometry(k,f,s,i,n,d,a,b,c,e); }
    @JSExport public static void configurePointWidth(String f, String s, int p, double w) { BeamProbe.configurePointWidth(f,s,p,w); }
    @JSExport public static boolean resourceContract() { return BeamProbe.resourceContract(); }
    @JSExport public static void configureDebug(Int8Array model) { BeamProbe.configureDebug(model); }
    @JSExport public static boolean debugImageRejected() throws Exception { return BeamProbe.debugImageRejected(); }
    @JSExport public static void configurePreview(Int8Array model) { BeamProbe.configurePreview(model); }
    @JSExport public static boolean previewRejected() throws Exception { return BeamProbe.previewRejected(); }
    @JSExport public static Int8Array headPixels() { return BeamProbe.headPixels(); }
    @JSExport public static String fixture() throws Exception { return analyze(PixelSheetProbe.create(1024,300,GridProbe.staffPixels(),"ledger-staff")); }
    @JSExport public static String page(int w, int h, Int8Array pixels, String id) throws Exception { return analyze(PixelSheetProbe.create(w,h,pixels.copyToJavaArray(),id)); }
    @JSExport public static String grayPage(int w, int h, Int8Array pixels, String id, double mean, double deviation) throws Exception {
        if (!Double.isFinite(mean) || !Double.isFinite(deviation)) { throw new IllegalArgumentException("Adaptive coefficients must be finite"); }
        var gray = org.audiveris.wasm.compat.platform.GrayInput.takeOwnership(w,h,pixels.copyToJavaArray()).takeProcessor();
        return analyze(PixelSheetProbe.create(gray,new org.audiveris.omr.image.VerticalFilter(gray,mean,deviation).filteredImage(),id));
    }
    public static String analyze(Sheet sheet) throws Exception {
        String beams = BeamProbe.analyze(sheet); if (beams.contains("\"rejected\":true")) { return beams; }
        Sheet previous = HeadlessSheetContext.current(); HeadlessSheetContext.bind(sheet);
        try {
            var context = new LedgersStep.Context(new LedgersFilter(sheet).process());
            for (SystemInfo system : sheet.getSystems()) {
                var builder = new LedgersBuilder(system); context.builders.put(system,builder);
                builder.buildLedgers(context.sectionMap.get(system));
            }
            new LedgersPostAnalysis(sheet,context).process();
            BeamProbe.checkErrors();
            StringBuilder result = new StringBuilder(beams.substring(0,beams.length()-1)).append(",\"ledgers\":["); boolean first = true;
            for (SystemInfo system : sheet.getSystems()) { for (Staff staff : system.getStaves()) {
                for (var entry : staff.getLedgerMap().entrySet()) {
                    var values = new ArrayList<>(entry.getValue()); values.sort(Comparator.comparingInt(value -> value.getBounds().x));
                    for (var ledger : values) {
                        if (ledger.isRemoved() || !system.getSig().containsVertex(ledger) || ledger.getStaff() != staff) { throw new AssertionError("Ledger map and graph disagree"); }
                        if (!first) { result.append(','); } first=false; var box=ledger.getBounds();
                        result.append("{\"system\":").append(system.getId()).append(",\"staff\":").append(staff.getId())
                            .append(",\"index\":").append(entry.getKey()).append(",\"grade\":").append(ledger.getGrade())
                            .append(",\"bounds\":[").append(box.x).append(',').append(box.y).append(',').append(box.width).append(',').append(box.height)
                            .append("],\"weight\":").append(ledger.getGlyph()==null?0:ledger.getGlyph().getWeight()).append('}');
                    }
                }
            } }
            result.append("],\"ledgerPitches\":["); first = true;
            for (SystemInfo system : sheet.getSystems()) { for (Staff staff : system.getStaves()) {
                for (int i=1;i<=9;i++) { double x=sheet.getWidth()*i/10.0;
                    for (int offset : new int[]{-12,-8,-6,6,8,12}) {
                        double y=staff.getFirstLine().yAt(x)+offset*staff.getSpecificInterline()/2.0;
                        if (!first) { result.append(','); } first=false;
                        result.append(staff.pitchPositionOf(new java.awt.geom.Point2D.Double(x,y)));
                    }
                }
            } }
            return result.append("]}").toString();
        } finally { HeadlessSheetContext.bind(previous); }
    }
}
