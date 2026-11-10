package org.audiveris.wasm.compat.test;

import java.util.*;
import org.audiveris.omr.glyph.GlyphGroup;
import org.audiveris.omr.lag.*;
import org.audiveris.omr.sheet.*;
import org.audiveris.omr.sheet.beam.*;
import org.audiveris.omr.sig.inter.*;
import org.audiveris.wasm.compat.platform.HeadlessSheetContext;
import org.teavm.jso.JSExport;
import org.teavm.jso.typedarrays.Int8Array;

/** Execute the original BEAMS recognition calls and expose intermediate tables and geometry. */
public final class BeamProbe {
    public static void main(String[] args) { }
    private static final List<String> errors = new ArrayList<>();
    public static void checkErrors() { if (!errors.isEmpty()) { throw new IllegalStateException("Recognition logged errors: " + errors); } }
    private static void monitor() {
        org.audiveris.wasm.compat.platform.HeadlessEvents.setSink((kind, subject, detail) -> {
            if (kind.equals("log") && detail.startsWith("ERROR ")) { errors.add(subject + ": " + detail); }
        });
    }
    @JSExport public static void configure(Int8Array model) { StemProbe.configure(model); monitor(); }
    @JSExport public static void configureDebug(Int8Array model) { StemProbe.configureDebug(model); }
    @JSExport public static boolean debugImageRejected() throws Exception { return StemProbe.debugImageRejected(); }
    @JSExport public static void configurePreview(Int8Array model) {
        org.audiveris.wasm.compat.platform.HeadlessConfiguration.configure(
            Map.of("org.audiveris.omr.sheet.beam.SpotsBuilder.saveBeamSpots", "true"), Map.of());
        org.audiveris.wasm.compat.platform.HeadlessClassifierModels.install(model.copyToJavaArray());
    }
    @JSExport public static boolean previewRejected() throws Exception {
        try { fixture(); }
        catch (IllegalStateException expected) {
            return expected.getCause() instanceof UnsupportedOperationException && expected.getCause().getMessage().contains("host image output backend");
        }
        return false;
    }
    @JSExport public static void configureSerifs(String family, int interline, double uw, double uh, double lw, double lh) {
        StemProbe.configureSerifs(family, interline, uw, uh, lw, lh);
    }
    @JSExport public static void configureGeometry(int kind, String family, String shape, int interline, int num, int den,
            double a, double b, double c, double d) { StemProbe.configureGeometry(kind, family, shape, interline, num, den, a, b, c, d); }
    @JSExport public static void configurePointWidth(String family, String shape, int pointSize, double width) {
        org.audiveris.wasm.compat.platform.HeadlessSymbolGeometry.registerPointWidth(family, shape, pointSize, width);
    }
    @JSExport public static boolean resourceContract() {
        var metrics = org.audiveris.wasm.compat.platform.HeadlessSymbolGeometry.provider();
        org.audiveris.wasm.compat.platform.HeadlessSymbolGeometry.registerPointWidth("Bravura", "NOISE", 9999, 2.5);
        if (metrics.widthAtPointSize(org.audiveris.omr.ui.symbol.MusicFamily.Bravura, org.audiveris.omr.glyph.Shape.NOISE, 9999) != 2.5) { return false; }
        int rejected = 0;
        try { org.audiveris.wasm.compat.platform.HeadlessSymbolGeometry.registerPointWidth("Bravura", "NOISE", 9999, 3); }
        catch (IllegalArgumentException expected) { rejected++; }
        try { org.audiveris.wasm.compat.platform.HeadlessSymbolGeometry.registerPointWidth("Bravura", "NOISE", 10000, Double.NaN); }
        catch (IllegalArgumentException expected) { rejected++; }
        return rejected == 2 && StemProbe.resourceContract() && KernelContract.run();
    }
    @JSExport public static Int8Array headPixels() { return Int8Array.copyFromJavaArray(headPixelsJava()); }
    @JSExport public static String fixture() throws Exception { return analyze(PixelSheetProbe.create(1024, 300, GridProbe.staffPixels(), "beam-staff")); }
    @JSExport public static String grayPage(int width, int height, Int8Array pixels, String id, double mean, double deviation) throws Exception {
        if (!Double.isFinite(mean) || !Double.isFinite(deviation)) { throw new IllegalArgumentException("Adaptive coefficients must be finite"); }
        var gray = org.audiveris.wasm.compat.platform.GrayInput.takeOwnership(width, height, pixels.copyToJavaArray()).takeProcessor();
        var binary = new org.audiveris.omr.image.VerticalFilter(gray, mean, deviation).filteredImage();
        return analyze(PixelSheetProbe.create(gray, binary, id));
    }
    private static Sheet current;
    public static byte[] headPixelsJava() { return (byte[]) current.getPicture().getVerticalTable(Picture.TableKey.HEAD_SPOTS).getBuffer().getPixels(); }
    @JSExport public static String page(int width, int height, Int8Array pixels, String id) throws Exception {
        return analyze(PixelSheetProbe.create(width, height, pixels.copyToJavaArray(), id));
    }
    public static String analyze(Sheet sheet) throws Exception {
        current = null;
        errors.clear();
        String stems = StemProbe.analyze(sheet);
        if (stems.contains("\"rejected\":true")) { return stems; }
        Sheet previous = HeadlessSheetContext.current(); HeadlessSheetContext.bind(sheet);
        try {
            Lag lag = new BasicLag(Lags.SPOT_LAG, SpotsBuilder.SPOT_ORIENTATION);
            new SpotsBuilder(sheet).buildSheetSpots(lag);
            if (sheet.getPicture().getVerticalTable(Picture.TableKey.HEAD_SPOTS) == null) {
                throw new IllegalStateException("SpotsBuilder did not complete head-spot preparation");
            }
            for (SystemInfo system : sheet.getSystems()) {
                new BeamsBuilder(system, lag).buildBeams(); new MultipleRestsBuilder(system).process();
            }
            current = sheet;
            var font = sheet.getScale().getMusicFontScale(); var heads = sheet.getScale().getBlackHeadScale();
            StringBuilder result = new StringBuilder(stems.substring(0, stems.length() - 1));
            result.append(",\"musicPointSize\":").append(font == null ? "null" : font.getPointSize());
            result.append(",\"blackHeadScale\":").append(heads == null ? "null" : "[" + heads.getWidthMean() + "," + heads.getWidthStd()
                + "," + heads.getHeightMean() + "," + heads.getHeightStd() + "]");
            result.append(",\"beams\":["); boolean first = true;
            for (SystemInfo system : sheet.getSystems()) {
                List<Inter> inters = system.getSig().inters(new Class[]{AbstractBeamInter.class, MultipleRestInter.class});
                inters.sort(Comparator.comparingInt((Inter inter) -> inter.getBounds().x)
                    .thenComparingInt(inter -> inter.getBounds().y).thenComparing(inter -> inter.getClass().getSimpleName()));
                for (Inter inter : inters) {
                    var item = (AbstractHorizontalInter) inter; var box = item.getBounds(); var line = item.getMedian();
                    if (!first) { result.append(','); } first = false;
                    result.append("{\"system\":").append(system.getId()).append(",\"type\":\"").append(item.getClass().getSimpleName())
                        .append("\",\"grade\":").append(item.getGrade()).append(",\"bounds\":[").append(box.x).append(',').append(box.y)
                        .append(',').append(box.width).append(',').append(box.height).append("],\"median\":[")
                        .append(line.getX1()).append(',').append(line.getY1()).append(',').append(line.getX2()).append(',').append(line.getY2())
                        .append("],\"height\":").append(item.getHeight()).append('}');
                }
                system.removeGroupedGlyphs(GlyphGroup.BEAM_SPOT);
                for (var glyph : system.getGroupedGlyphs(GlyphGroup.BEAM_SPOT)) {
                    if (glyph.getGroups().size() == 1) { throw new AssertionError("Beam-only spot survived cleanup"); }
                }
            }
            result.append("],\"relations\":["); first = true;
            appendRelations(result, sheet);
            checkErrors();
            return result.append("]}").toString();
        } finally { HeadlessSheetContext.bind(previous); }
    }
    static void appendRelations(StringBuilder result, Sheet sheet) {
        boolean first = true;
            for (SystemInfo system : sheet.getSystems()) {
                var graph = system.getSig();
                var edges = new ArrayList<>(graph.edgeSet());
                edges.sort(Comparator.comparing((org.audiveris.omr.sig.relation.Relation edge) -> edge.getClass().getSimpleName())
                    .thenComparing(edge -> interKey(graph.getEdgeSource(edge))).thenComparing(edge -> interKey(graph.getEdgeTarget(edge))));
                for (var edge : edges) {
                    if (!first) { result.append(','); } first = false;
                    result.append("{\"system\":").append(system.getId()).append(",\"type\":\"").append(edge.getClass().getSimpleName())
                        .append("\",\"source\":\"").append(interKey(graph.getEdgeSource(edge))).append("\",\"target\":\"")
                        .append(interKey(graph.getEdgeTarget(edge))).append('"');
                    if (edge instanceof org.audiveris.omr.sig.relation.Support support) {
                        result.append(",\"grade\":").append(support.getGrade()).append(",\"sourceRatio\":").append(support.getSourceRatio())
                            .append(",\"targetRatio\":").append(support.getTargetRatio());
                    }
                    result.append('}');
                }
            }
    }
    private static String interKey(Inter inter) {
        var box = inter.getBounds();
        return inter.getClass().getSimpleName() + ":" + inter.getShape() + ":" + box.x + ":" + box.y + ":" + box.width + ":" + box.height;
    }
}
