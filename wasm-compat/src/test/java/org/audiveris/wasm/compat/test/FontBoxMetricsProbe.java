package org.audiveris.wasm.compat.test;

import org.apache.fontbox.ttf.TTFParser;
import org.apache.fontbox.ttf.TrueTypeFont;
import org.apache.pdfbox.io.RandomAccessReadBuffer;
import org.teavm.jso.JSExport;
import org.teavm.jso.typedarrays.Int8Array;

/** Opt-in research entry; never selected by the recognition engine. */
public final class FontBoxMetricsProbe {
    private static TrueTypeFont font;
    public static void main(String[] args) { }
    @JSExport public static void face(Int8Array bytes) throws Exception {
        if (font != null) { font.close(); font = null; }
        font = new TTFParser().parse(new RandomAccessReadBuffer(bytes.copyToJavaArray()));
        font.setEnableGsub(false);
    }
    @JSExport public static double measure(int size, String text, int field) throws Exception {
        if (font == null || field < 0 || field > 4 || size <= 0) { throw new IllegalArgumentException("Font/measurement required"); }
        return FontBoxMetricsExperiment.measure(font, size, text, false, 2)[field];
    }
    @JSExport public static double measureOblique(int size, String text, int field) throws Exception {
        if (font == null || field < 0 || field > 4 || size <= 0) { throw new IllegalArgumentException("Font/measurement required"); }
        return FontBoxMetricsExperiment.measure(font, size, text, false, 2, true)[field];
    }
}
