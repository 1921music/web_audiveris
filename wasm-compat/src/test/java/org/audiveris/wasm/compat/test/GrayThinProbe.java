// SPDX-License-Identifier: AGPL-3.0-or-later
package org.audiveris.wasm.compat.test;

import java.awt.Shape;
import java.awt.geom.Path2D;
import java.util.Arrays;
import org.audiveris.wasm.compat.platform.GrayThinRaster;
import org.teavm.jso.JSExport;
import org.teavm.jso.typedarrays.Int8Array;

/** Curves, all small directed line vectors and large clipped coordinates in both stroke modes. */
public final class GrayThinProbe {
    static final int SHAPES = 512 + 65 * 65 + 16, CASES = SHAPES * 2;
    public static void main(String[] args) { }
    static Shape shape(int index) {
        if (index < 512) { return GrayPathProbe.shape(index); }
        var path = new Path2D.Float(); int line = index - 512;
        if (line < 65 * 65) {
            path.moveTo(23.25, 18.5); path.lineTo(23.25 + line % 65 - 32, 18.5 + line / 65 - 32);
        } else {
            int bits = line - 65 * 65;
            path.moveTo((bits & 1) == 0 ? -4000000 : 4000000, (bits & 2) == 0 ? -4000000 : 4000000);
            path.lineTo(23.25 + ((bits & 4) == 0 ? -4000000 : 4000000), 18.5 + ((bits & 8) == 0 ? -4000000 : 4000000));
        }
        return path;
    }
    static byte[] portable(int test) {
        byte[] pixels = GrayPathProbe.input(test / 2);
        GrayThinRaster.draw(pixels, GrayPathProbe.WIDTH, GrayPathProbe.HEIGHT, new Path2D.Float(shape(test / 2)), (test & 1) != 0);
        return pixels;
    }
    @JSExport public static int cases() { return CASES; }
    @JSExport public static String hashes() {
        var result = new StringBuilder(); for (int i = 0; i < CASES; i++) { result.append(Arrays.hashCode(portable(i))).append(','); } return result.toString();
    }
    @JSExport public static Int8Array pixels() {
        int size = GrayPathProbe.WIDTH * GrayPathProbe.HEIGHT; byte[] result = new byte[CASES * size];
        for (int i = 0; i < CASES; i++) { System.arraycopy(portable(i), 0, result, i * size, size); }
        return Int8Array.copyFromJavaArray(result);
    }
    @JSExport public static int guards() {
        int checked = 0;
        try { GrayThinRaster.draw(new byte[1], 2, 1, new Path2D.Float(), false); }
        catch (IllegalArgumentException expected) { checked++; }
        for (float value : new float[]{Float.NaN, Float.NEGATIVE_INFINITY, 0x800000}) {
            var path = new Path2D.Float(); path.moveTo(value, 0); path.lineTo(1, 1);
            try { GrayThinRaster.draw(new byte[1], 1, 1, path, false); }
            catch (UnsupportedOperationException expected) { checked++; }
        }
        if (checked != 4) { throw new AssertionError("Thin path input guards"); } return checked;
    }
}
