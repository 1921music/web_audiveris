package org.audiveris.wasm.compat.test;

import java.util.*;
import org.audiveris.omr.sheet.*;
import org.audiveris.omr.sheet.grid.*;
import org.audiveris.omr.step.StepException;
import org.audiveris.omr.util.HorizontalSide;
import org.audiveris.wasm.compat.platform.*;
import org.teavm.jso.JSExport;
import org.teavm.jso.typedarrays.Int8Array;

/** Exercise the original horizontal staff retrieval, before barline/system completion. */
public final class StaffProbe {
    public static void main(String[] args) { }
    @JSExport public static String suppliers() { return GraphSupplierCases.run(); }
    @JSExport public static void configure() { HeadlessConfiguration.configure(Map.of(), Map.of()); }
    @JSExport public static String fixture() { return staffJava(1024, 300, staffPixels(), "staff"); }
    @JSExport public static String staffPage(int width, int height, Int8Array binary, String id) {
        return staffJava(width, height, binary.copyToJavaArray(), id);
    }
    @JSExport public static String staffGrayPage(int width, int height, Int8Array pixels, String id, double mean, double deviation) {
        if (!Double.isFinite(mean) || !Double.isFinite(deviation)) { throw new IllegalArgumentException("Adaptive coefficients must be finite"); }
        var gray = GrayInput.takeOwnership(width, height, pixels.copyToJavaArray()).takeProcessor();
        var binary = new org.audiveris.omr.image.VerticalFilter(gray, mean, deviation).filteredImage();
        Sheet sheet = PixelSheetProbe.create(gray, binary, id);
        if (sheet.getPicture().getSource(Picture.SourceKey.GRAY) != gray) { throw new AssertionError("Gray source ownership"); }
        return analyze(sheet);
    }
    public static byte[] staffPixels() {
        byte[] pixels = new byte[1024 * 300]; Arrays.fill(pixels, (byte) 255);
        for (int top : new int[]{60, 180}) { for (int line = 0; line < 5; line++) {
            for (int y = top + line * 12; y < top + line * 12 + 2; y++) {
                for (int x = 20; x < 1004; x++) { pixels[y * 1024 + x] = 0; }
            }
        } }
        return pixels;
    }
    public static String staffJava(int width, int height, byte[] pixels, String id) {
        return analyze(PixelSheetProbe.create(width, height, pixels, id));
    }
    public static String analyze(Sheet sheet) {
        Sheet previous = HeadlessSheetContext.current(); HeadlessSheetContext.bind(sheet);
        try {
            sheet.setScale(new ScaleBuilder(sheet).retrieveScale());
            GridBuilder grid = new GridBuilder(sheet);
            grid.linesRetriever.createBothLags(); grid.linesRetriever.retrieveLines();
            StringBuilder result = new StringBuilder("{\"slope\":").append(sheet.getSkew().getSlope()).append(",\"staves\":[");
            boolean first = true;
            for (Staff staff : sheet.getStaffManager().getStaves()) {
                if (!first) { result.append(','); } first = false;
                result.append("{\"id\":").append(staff.getId()).append(",\"lines\":["); boolean firstLine = true;
                for (LineInfo line : staff.getLines()) {
                    if (!firstLine) { result.append(','); } firstLine = false;
                    var left = line.getEndPoint(HorizontalSide.LEFT); var right = line.getEndPoint(HorizontalSide.RIGHT);
                    result.append('[').append(left.getX()).append(',').append(left.getY()).append(',')
                            .append(right.getX()).append(',').append(right.getY()).append(']');
                }
                result.append("]}");
            }
            return result.append("]}").toString();
        } catch (StepException rejected) { return "{\"rejected\":true,\"invalid\":" + !sheet.getStub().isValid() + "}"; }
        finally { HeadlessSheetContext.bind(previous); }
    }
}
