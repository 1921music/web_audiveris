// SPDX-License-Identifier: AGPL-3.0-or-later
package org.audiveris.wasm.compat.test;

import java.awt.geom.Path2D;
import java.util.Arrays;
import org.audiveris.wasm.compat.platform.GraySpanRaster;
import org.teavm.jso.JSExport;
import org.teavm.jso.typedarrays.Int8Array;

/** Both native span normalization modes over the shared geometry fixtures. */
public final class GraySpanProbe {
    static final int CASES = GrayPathProbe.CASES * 2;
    public static void main(String[] args) { }
    static byte[] portable(int test) {
        byte[] pixels = GrayPathProbe.input(test / 2);
        GraySpanRaster.fill(pixels, GrayPathProbe.WIDTH, GrayPathProbe.HEIGHT, GrayPathProbe.shape(test / 2), (test & 1) != 0);
        return pixels;
    }
    @JSExport public static String hashes() {
        var result = new StringBuilder();
        for (int i = 0; i < CASES; i++) { result.append(Arrays.hashCode(portable(i))).append(','); }
        return result.toString();
    }
    @JSExport public static Int8Array pixels() {
        int size = GrayPathProbe.WIDTH * GrayPathProbe.HEIGHT; byte[] result = new byte[CASES * size];
        for (int i = 0; i < CASES; i++) { System.arraycopy(portable(i), 0, result, i * size, size); }
        return Int8Array.copyFromJavaArray(result);
    }
    @JSExport public static int guards() {
        int checked = 0;
        try { GraySpanRaster.fill(new byte[1], 2, 1, new Path2D.Float(), false); }
        catch (IllegalArgumentException expected) { checked++; }
        for (float value : new float[]{Float.NaN, Float.POSITIVE_INFINITY, 0x800000}) {
            var path = new Path2D.Float(); path.moveTo(value, 0); path.lineTo(1, 1);
            try { GraySpanRaster.fill(new byte[1], 1, 1, path, false); }
            catch (UnsupportedOperationException expected) { checked++; }
        }
        if (checked != 4) { throw new AssertionError("Span input guards"); } return checked;
    }
}
