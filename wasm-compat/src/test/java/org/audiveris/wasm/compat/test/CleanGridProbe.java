package org.audiveris.wasm.compat.test;

import java.util.Map;
import org.audiveris.omr.sheet.*;
import org.audiveris.wasm.compat.platform.*;
import org.teavm.jso.JSExport;
import org.teavm.jso.typedarrays.Int8Array;

/** Complete the same recognition calls as GridStep, exposing failures rather than swallowing them. */
public final class CleanGridProbe {
    public static void main(String[] args) { }
    @JSExport public static void configure() { HeadlessConfiguration.configure(Map.of(), Map.of()); }
    @JSExport public static String suppliers() { return GridProbe.suppliers(); }
    @JSExport public static void configureSerifs(String family, int interline, double uw, double uh, double lw, double lh) {
        GridProbe.configureSerifs(family, interline, uw, uh, lw, lh);
    }
    @JSExport public static String brackets(String family, int interline) { return GridProbe.brackets(family, interline); }
    @JSExport public static String fixture() {
        return GridProbe.analyze(PixelSheetProbe.create(1024, 300, GridProbe.staffPixels(), "clean-staff"), true);
    }
    @JSExport public static String gridPage(int width, int height, Int8Array pixels, String id) {
        return GridProbe.analyze(PixelSheetProbe.create(width, height, pixels.copyToJavaArray(), id), true);
    }
    @JSExport public static String gridGrayPage(int width, int height, Int8Array pixels, String id, double mean, double deviation) {
        if (!Double.isFinite(mean) || !Double.isFinite(deviation)) { throw new IllegalArgumentException("Adaptive coefficients must be finite"); }
        var gray = GrayInput.takeOwnership(width, height, pixels.copyToJavaArray()).takeProcessor();
        var binary = new org.audiveris.omr.image.VerticalFilter(gray, mean, deviation).filteredImage();
        return GridProbe.analyze(PixelSheetProbe.create(gray, binary, id), true);
    }
    @JSExport public static boolean verifyCache() { return GridProbe.verifyCleanCache(); }
    @JSExport public static Int8Array noStaffPixels() { return Int8Array.copyFromJavaArray(GridProbe.cleanedPixels()); }
}
