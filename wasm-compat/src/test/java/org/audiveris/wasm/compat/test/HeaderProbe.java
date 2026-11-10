package org.audiveris.wasm.compat.test;

import java.util.Map;
import org.audiveris.omr.sheet.*;
import org.audiveris.omr.sheet.header.HeaderBuilder;
import org.audiveris.omr.sig.inter.*;
import org.audiveris.wasm.compat.platform.*;
import org.teavm.jso.JSExport;
import org.teavm.jso.typedarrays.Int8Array;

/** Preserve each recognition call and surface failures during incremental HEADERS integration. */
public final class HeaderProbe {
    public static void main(String[] args) { }
    @JSExport public static void configure(Int8Array model) {
        HeadlessConfiguration.configure(Map.of(), Map.of()); HeadlessClassifierModels.install(model.copyToJavaArray());
    }
    @JSExport public static void configureSerifs(String family, int interline, double uw, double uh, double lw, double lh) {
        GridProbe.configureSerifs(family, interline, uw, uh, lw, lh);
    }
    @JSExport public static void configureGeometry(int kind, String family, String shape, int interline, int num, int den,
            double a, double b, double c, double d) {
        HeadlessSymbolGeometry.register(kind, family, shape, interline, num, den, a, b, c, d);
    }
    @JSExport public static String page(int width, int height, Int8Array pixels, String id) {
        try { return analyze(PixelSheetProbe.create(width, height, pixels.copyToJavaArray(), id)); }
        catch (Throwable failure) { failure.printStackTrace(); throw failure; }
    }
    @JSExport public static String fixture() { return analyze(PixelSheetProbe.create(1024, 300, GridProbe.staffPixels(), "header-staff")); }
    @JSExport public static String grayPage(int width, int height, Int8Array pixels, String id, double mean, double deviation) {
        if (!Double.isFinite(mean) || !Double.isFinite(deviation)) { throw new IllegalArgumentException("Adaptive coefficients must be finite"); }
        var gray = GrayInput.takeOwnership(width, height, pixels.copyToJavaArray()).takeProcessor();
        var binary = new org.audiveris.omr.image.VerticalFilter(gray, mean, deviation).filteredImage();
        return analyze(PixelSheetProbe.create(gray, binary, id));
    }
    @JSExport public static boolean resourceContract() {
        if (HeaderPrefixCases.verify(false) != 528) { throw new AssertionError("Header prefix cases"); }
        var family = org.audiveris.omr.ui.symbol.MusicFamily.Bravura; var shape = org.audiveris.omr.glyph.Shape.NOISE;
        var original = org.audiveris.omr.ui.symbol.SymbolGeometry.provider();
        HeadlessSymbolGeometry.register(0, "Bravura", "NOISE", 9999, 0, 0, 1, 2, 3, 4);
        var bounds = original.bounds(family, shape, 9999); bounds.setRect(9, 9, 9, 9);
        if (original.bounds(family, shape, 9999).getWidth() != 3) { throw new AssertionError("Mutable measurement leaked"); }
        HeadlessSymbolGeometry.register(0, "Bravura", "NOISE", 10000, 0, 0, 5, 6, 7, 8);
        if (original.bounds(family, shape, 9999).getWidth() != 3) { throw new AssertionError("Resource extension changed previous data"); }
        int rejected = 0;
        try { HeadlessSymbolGeometry.register(0, "Bravura", "NOISE", 9999, 0, 0, 1, 2, 3, 4); }
        catch (IllegalArgumentException expected) { rejected++; }
        try { original.bounds(family, shape, 10001); }
        catch (UnsupportedOperationException expected) { rejected++; }
        try { HeadlessSymbolGeometry.register(0, "Bravura", "NOISE", 10002, 0, 0, 0, 0, Double.NaN, 1); }
        catch (IllegalArgumentException expected) { rejected++; }
        var custom = new org.audiveris.omr.ui.symbol.SymbolGeometry.Provider() {
            public java.awt.geom.Rectangle2D bounds(org.audiveris.omr.ui.symbol.MusicFamily f, org.audiveris.omr.glyph.Shape s, int i) {
                return new java.awt.geom.Rectangle2D.Double(0, 0, 11, 12);
            }
            public java.awt.geom.Point2D centroidOffset(org.audiveris.omr.ui.symbol.MusicFamily f, org.audiveris.omr.glyph.Shape s, int i) {
                return new java.awt.geom.Point2D.Double(0, 0);
            }
            public java.awt.Dimension timeDimension(org.audiveris.omr.ui.symbol.MusicFamily f, org.audiveris.omr.glyph.Shape s, int i, int n, int d) {
                return new java.awt.Dimension(13, 14);
            }
        };
        try {
            org.audiveris.omr.ui.symbol.SymbolGeometry.setProvider(custom);
            if (org.audiveris.omr.ui.symbol.SymbolGeometry.provider() != custom) { throw new AssertionError("Provider injection ignored"); }
        } finally { org.audiveris.omr.ui.symbol.SymbolGeometry.setProvider(null); }
        if (org.audiveris.omr.ui.symbol.SymbolGeometry.provider() != original || rejected != 3) { throw new AssertionError("Resource contract"); }
        return true;
    }
    public static String analyze(Sheet sheet) {
        String grid = GridProbe.analyze(sheet, true);
        if (grid.contains("\"rejected\":true")) { return grid; }
        Sheet previous = HeadlessSheetContext.current(); HeadlessSheetContext.bind(sheet);
        try {
            for (var system : sheet.getSystems()) { new HeaderBuilder(system).processHeader(); }
            StringBuilder result = new StringBuilder("{\"grid\":").append(grid).append(",\"headers\":["); boolean first = true;
            for (var system : sheet.getSystems()) { for (Staff staff : system.getStaves()) {
                if (!first) { result.append(','); } first = false; var header = staff.getHeader();
                result.append("{\"staff\":").append(staff.getId()).append(",\"start\":").append(header.start)
                    .append(",\"stop\":").append(header.stop).append(",\"clef\":").append(inter(header.clef))
                    .append(",\"key\":").append(inter(header.key)).append(",\"time\":").append(inter(header.time));
                if (header.key != null) { result.append(",\"fifths\":").append(header.key.getFifths()); }
                if (header.time != null) { result.append(",\"numerator\":").append(header.time.getNumerator())
                    .append(",\"denominator\":").append(header.time.getDenominator()); }
                result.append('}');
            } }
            return result.append("]}").toString();
        } finally { HeadlessSheetContext.bind(previous); }
    }
    private static String inter(Inter inter) {
        if (inter == null) { return "null"; }
        var bounds = inter.getBounds();
        return "{\"type\":\"" + inter.getClass().getSimpleName() + "\",\"shape\":"
            + (inter.getShape() == null ? "null" : "\"" + inter.getShape().name() + "\"") + ",\"grade\":" + inter.getGrade() + ",\"bounds\":"
            + (bounds == null ? "null" : "[" + bounds.x + "," + bounds.y + "," + bounds.width + "," + bounds.height + "]") + "}";
    }
}
