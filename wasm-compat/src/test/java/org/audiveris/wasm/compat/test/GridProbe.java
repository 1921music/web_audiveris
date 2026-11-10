package org.audiveris.wasm.compat.test;

import java.util.*;
import org.audiveris.omr.sheet.*;
import org.audiveris.omr.sheet.grid.*;
import org.audiveris.omr.step.StepException;
import org.audiveris.omr.util.HorizontalSide;
import org.audiveris.wasm.compat.platform.*;
import org.teavm.jso.JSExport;
import org.teavm.jso.typedarrays.Int8Array;

/** Exercise the complete original GridBuilder pipeline, surfacing unexpected failures. */
public final class GridProbe {
    public static void main(String[] args) { }
    @JSExport public static String suppliers() { return GraphSupplierCases.run(); }
    @JSExport public static void configure() { HeadlessConfiguration.configure(Map.of(), Map.of()); }
    @JSExport public static String fixture() { return gridJava(1024, 300, staffPixels(), "staff"); }
    @JSExport public static String gridPage(int width, int height, Int8Array binary, String id) {
        return gridJava(width, height, binary.copyToJavaArray(), id);
    }
    @JSExport public static String gridGrayPage(int width, int height, Int8Array pixels, String id, double mean, double deviation) {
        if (!Double.isFinite(mean) || !Double.isFinite(deviation)) { throw new IllegalArgumentException("Adaptive coefficients must be finite"); }
        var gray = GrayInput.takeOwnership(width, height, pixels.copyToJavaArray()).takeProcessor();
        var binary = new org.audiveris.omr.image.VerticalFilter(gray, mean, deviation).filteredImage();
        Sheet sheet = PixelSheetProbe.create(gray, binary, id);
        if (sheet.getPicture().getSource(Picture.SourceKey.GRAY) != gray) { throw new AssertionError("Gray source ownership"); }
        return analyze(sheet);
    }
    @JSExport public static void configureSerifs(String family, int interline, double upperWidth, double upperHeight, double lowerWidth, double lowerHeight) {
        HeadlessBracketMetrics.register(family, interline, upperWidth, upperHeight, lowerWidth, lowerHeight);
    }
    @JSExport public static String brackets(String family, int interline) {
        Sheet sheet = PixelSheetProbe.create(2, 2, new byte[]{-1, -1, -1, -1}, "bracket");
        sheet.getStub().getMusicFamilyParam().setSpecific(org.audiveris.omr.ui.symbol.MusicFamily.valueOf(family));
        sheet.setScale(new Scale(new Scale.InterlineScale(interline, interline, interline), new Scale.LineScale(2, 2, 2), new Scale.BeamScale(7, true), null, null));
        var system = new SystemInfo(1, sheet, null);
        StringBuilder result = new StringBuilder("["); boolean first = true;
        for (var kind : org.audiveris.omr.sig.inter.BracketInter.BracketKind.values()) {
            for (double tilt : new double[]{-3.25, 0, 4.75}) {
                var bracket = new org.audiveris.omr.sig.inter.BracketInter(null, null,
                    new java.awt.geom.Line2D.Double(30.5, 20.25, 30.5 + tilt, 150.75), 3.25, kind);
                bracket.setSig(system.getSig()); var area = bracket.getArea(); var bounds = area.getBounds();
                if (!first) { result.append(','); } first = false;
                result.append('[').append(bounds.x).append(',').append(bounds.y).append(',').append(bounds.width).append(',').append(bounds.height);
                for (int y = 0; y < 7; y++) { for (int x = 0; x < 7; x++) {
                    result.append(',').append(area.contains(bounds.x + bounds.width * (x + 0.25) / 7,
                        bounds.y + bounds.height * (y + 0.25) / 7));
                } }
                result.append(']');
            }
        }
        return result.append(']').toString();
    }
    public static byte[] staffPixels() {
        byte[] pixels = new byte[1024 * 300]; Arrays.fill(pixels, (byte) 255);
        for (int top : new int[]{60, 180}) { for (int line = 0; line < 5; line++) {
            for (int y = top + line * 12; y < top + line * 12 + 2; y++) {
                for (int x = 20; x < 1004; x++) { pixels[y * 1024 + x] = 0; }
            }
        } }
        for (int x : new int[]{20, 350, 700, 1001}) {
            for (int y = 60; y < 230; y++) { pixels[y * 1024 + x] = 0; pixels[y * 1024 + x + 1] = 0; }
        }
        return pixels;
    }
    public static String gridJava(int width, int height, byte[] pixels, String id) {
        return analyze(PixelSheetProbe.create(width, height, pixels, id));
    }
    public static String analyze(Sheet sheet) {
        Sheet previous = HeadlessSheetContext.current(); HeadlessSheetContext.bind(sheet);
        try {
            sheet.setScale(new ScaleBuilder(sheet).retrieveScale());
            GridBuilder grid = new GridBuilder(sheet);
            grid.linesRetriever.createBothLags(); grid.linesRetriever.retrieveLines();
            grid.linesRetriever.addShortSections(); grid.barsRetriever.process(); grid.linesRetriever.completeLines();
            StringBuilder result = new StringBuilder("{\"slope\":").append(sheet.getSkew().getSlope()).append(",\"interline\":").append(sheet.getScale().getInterline()).append(",\"staves\":[");
            boolean first = true;
            for (Staff staff : sheet.getStaffManager().getStaves()) {
                if (!first) { result.append(','); } first = false;
                result.append("{\"id\":").append(staff.getId()).append(",\"lines\":["); boolean firstLine = true;
                for (LineInfo line : staff.getLines()) {
                    if (!firstLine) { result.append(','); } firstLine = false;
                    var left = line.getEndPoint(HorizontalSide.LEFT); var right = line.getEndPoint(HorizontalSide.RIGHT);
                    result.append('[').append(left.getX()).append(',').append(left.getY()).append(',')
                                                        .append(right.getX()).append(',').append(right.getY()).append(',').append(line.getThickness());
                    for (int sample = 1; sample <= 9; sample++) {
                        result.append(',').append(line.yAt(left.getX() + (right.getX() - left.getX()) * sample / 10));
                    }
                    result.append(']');
                }
                result.append("]}");
            }
            result.append("],\"systems\":["); boolean firstSystem = true;
            for (SystemInfo system : sheet.getSystems()) {
                if (!firstSystem) { result.append(','); } firstSystem = false;
                result.append("{\"id\":").append(system.getId()).append(",\"staves\":[");
                boolean firstStaff = true;
                for (Staff staff : system.getStaves()) {
                    if (!firstStaff) { result.append(','); } firstStaff = false; result.append(staff.getId());
                }
                var bounds = system.getBounds();
                result.append("],\"bounds\":[").append(bounds.x).append(',').append(bounds.y).append(',')
                    .append(bounds.width).append(',').append(bounds.height).append("],\"parts\":").append(system.getParts().size())
                    .append(",\"bars\":["); boolean firstBar = true;
                for (Staff staff : system.getStaves()) { for (var bar : staff.getBarlines()) {
                    if (!firstBar) { result.append(','); } firstBar = false;
                    var line = bar.getMedian();
                    result.append('[').append(staff.getId()).append(',').append(bar.getGrade()).append(',')
                        .append(line.getX1()).append(',').append(line.getY1()).append(',')
                        .append(line.getX2()).append(',').append(line.getY2()).append(']');
                } }
                result.append("]}");
            }
            return result.append("]}").toString();
        } catch (StepException rejected) { return "{\"rejected\":true,\"invalid\":" + !sheet.getStub().isValid() + "}"; }
        finally { HeadlessSheetContext.bind(previous); }
    }
}
