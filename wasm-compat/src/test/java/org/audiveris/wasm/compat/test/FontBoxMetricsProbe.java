package org.audiveris.wasm.compat.test;

import org.audiveris.wasm.compat.platform.TrueTypeFaceMetrics;
import org.teavm.jso.JSExport;
import org.teavm.jso.typedarrays.Int8Array;

/** Opt-in research entry; never selected by the recognition engine. */
public final class FontBoxMetricsProbe {
    private static TrueTypeFaceMetrics font;
    public static void main(String[] args) { }
    @JSExport public static void face(Int8Array bytes) throws Exception {
        if (font != null) { font.close(); font = null; }
        font = new TrueTypeFaceMetrics(bytes.copyToJavaArray());
    }
    @JSExport public static double measure(int size, String text, int field) throws Exception {
        if (font == null || field < 0 || field > 4 || size <= 0) { throw new IllegalArgumentException("Font/measurement required"); }
        return font.measure(size, text, false)[field];
    }
    @JSExport public static double measureOblique(int size, String text, int field) throws Exception {
        if (font == null || field < 0 || field > 4 || size <= 0) { throw new IllegalArgumentException("Font/measurement required"); }
        return font.measure(size, text, true)[field];
    }
}
