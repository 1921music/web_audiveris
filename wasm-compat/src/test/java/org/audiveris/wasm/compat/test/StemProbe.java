package org.audiveris.wasm.compat.test;

import java.util.*;
import org.audiveris.omr.glyph.GlyphGroup;
import org.audiveris.omr.sheet.*;
import org.audiveris.omr.sheet.stem.*;
import org.audiveris.wasm.compat.platform.HeadlessSheetContext;
import org.teavm.jso.JSExport;
import org.teavm.jso.typedarrays.Int8Array;

/** Integration probe for the original stem-scaling and vertical-seed algorithms. */
public final class StemProbe {
    static java.util.function.Consumer<Sheet> beforeStem;
    public static void main(String[] args) { }
    @JSExport public static void configure(Int8Array model) { HeaderProbe.configure(model); }
    @JSExport public static void configureDebug(Int8Array model) {
        org.audiveris.wasm.compat.platform.HeadlessConfiguration.configure(
            Map.of("org.audiveris.omr.sheet.stem.StemScaler.saveStemImage", "true"), Map.of());
        org.audiveris.wasm.compat.platform.HeadlessClassifierModels.install(model.copyToJavaArray());
    }
    @JSExport public static boolean debugImageRejected() throws Exception {
        try { new StemScaler(PixelSheetProbe.create(2, 2, new byte[]{-1, -1, -1, -1}, "debug")).retrieveStemWidth(); }
        catch (UnsupportedOperationException expected) { return expected.getMessage().contains("host raster backend"); }
        return false;
    }
    @JSExport public static boolean resourceContract() { return HeaderProbe.resourceContract(); }
    @JSExport public static Int8Array stemPixels() { return Int8Array.copyFromJavaArray(GridProbe.cleanedPixels()); }
    @JSExport public static String fixture() throws Exception {
        return analyze(PixelSheetProbe.create(1024, 300, GridProbe.staffPixels(), "stem-staff"));
    }
    @JSExport public static String grayPage(int width, int height, Int8Array pixels, String id, double mean, double deviation) throws Exception {
        if (!Double.isFinite(mean) || !Double.isFinite(deviation)) { throw new IllegalArgumentException("Adaptive coefficients must be finite"); }
        var gray = org.audiveris.wasm.compat.platform.GrayInput.takeOwnership(width, height, pixels.copyToJavaArray()).takeProcessor();
        var binary = new org.audiveris.omr.image.VerticalFilter(gray, mean, deviation).filteredImage();
        return analyze(PixelSheetProbe.create(gray, binary, id));
    }
    @JSExport public static void configureSerifs(String family, int interline, double uw, double uh, double lw, double lh) {
        HeaderProbe.configureSerifs(family, interline, uw, uh, lw, lh);
    }
    @JSExport public static void configureGeometry(int kind, String family, String shape, int interline, int num, int den,
            double a, double b, double c, double d) {
        HeaderProbe.configureGeometry(kind, family, shape, interline, num, den, a, b, c, d);
    }
    @JSExport public static String page(int width, int height, Int8Array pixels, String id) throws Exception {
        return analyze(PixelSheetProbe.create(width, height, pixels.copyToJavaArray(), id));
    }
    public static String analyze(Sheet sheet) throws Exception {
        String header = HeaderProbe.analyze(sheet);
        if (header.contains("\"rejected\":true")) { return header; }
        Sheet previous = HeadlessSheetContext.current(); HeadlessSheetContext.bind(sheet);
        try {
            if (beforeStem != null) { beforeStem.accept(sheet); }
            byte[] original = ((byte[]) sheet.getPicture().getSource(Picture.SourceKey.NO_STAFF).getPixels()).clone();
            if (sheet.getScale().getStemScale() == null) { sheet.getScale().setStemScale(new StemScaler(sheet).retrieveStemWidth()); }
            for (SystemInfo system : sheet.getSystems()) { new VerticalsBuilder(system).buildVerticals(); }
            if (!Arrays.equals(original, (byte[]) sheet.getPicture().getSource(Picture.SourceKey.NO_STAFF).getPixels())) {
                throw new AssertionError("Pinned StemScaler unexpectedly modifies recognition pixels");
            }
            var scale = sheet.getScale().getStemScale();
            StringBuilder result = new StringBuilder(header.substring(0, header.length() - 1));
            result.append(",\"stemScale\":[").append(scale.getMain()).append(',').append(scale.getMax()).append("],\"seeds\":[");
            boolean first = true;
            for (SystemInfo system : sheet.getSystems()) {
                var seeds = system.getGroupedGlyphs(GlyphGroup.VERTICAL_SEED);
                seeds.sort(Comparator.comparingInt(org.audiveris.omr.glyph.Glyph::getLeft)
                        .thenComparingInt(org.audiveris.omr.glyph.Glyph::getTop));
                for (var glyph : seeds) {
                    if (!first) { result.append(','); } first = false;
                    var bounds = glyph.getBounds(); int hash = 1;
                    var table = glyph.getRunTable();
                    for (int i = 0; i < table.getSize(); i++) {
                        hash = 31 * hash + i;
                        for (var iterator = table.iterator(i); iterator.hasNext();) {
                            var run = iterator.next(); hash = 31 * hash + run.getStart(); hash = 31 * hash + run.getLength();
                        }
                    }
                    result.append('[').append(system.getId()).append(',').append(bounds.x).append(',').append(bounds.y)
                        .append(',').append(bounds.width).append(',').append(bounds.height).append(',').append(glyph.getWeight())
                        .append(',').append(hash).append(']');
                }
            }
            return result.append("]}").toString();
        } finally { HeadlessSheetContext.bind(previous); }
    }
}
