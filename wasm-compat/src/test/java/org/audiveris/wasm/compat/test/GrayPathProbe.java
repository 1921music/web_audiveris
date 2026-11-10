package org.audiveris.wasm.compat.test;

import java.awt.Shape;
import java.awt.geom.*;
import java.util.*;
import org.audiveris.wasm.compat.platform.GrayPathRaster;
import org.teavm.jso.JSExport;
import org.teavm.jso.typedarrays.Int8Array;

/** Path-fill fixtures shared by native Java2D and the portable scan converter. */
public final class GrayPathProbe {
    static final int WIDTH = 47, HEIGHT = 37, CASES = 512;
    public static void main(String[] args) { }
    static byte[] input(int test) {
        byte[] pixels = new byte[WIDTH * HEIGHT];
        for (int i = 0; i < pixels.length; i++) { pixels[i] = (byte)((i * 31 + test * 17) & 127); }
        return pixels;
    }
    static Shape shape(int test) {
        Random random = new Random(31079 + test);
        double x = (test % 11) - 5.25, y = (test % 7) - 3.5;
        switch (test % 8) {
            case 0: return new Rectangle2D.Double(x, y, test % 53, test % 41);
            case 1: return new Ellipse2D.Double(x, y, test % 53, test % 41);
            case 2: return new RoundRectangle2D.Double(x, y, 38.5, 30.5, 13, 9);
            case 3: {
                Area area = new Area(new Rectangle2D.Double(x, y, 50, 40));
                area.subtract(new Area(new Ellipse2D.Double(9.25, 7.5, 29, 22)));
                return area;
            }
            default: {
                Path2D.Float path = new Path2D.Float(test % 2 == 0 ? Path2D.WIND_EVEN_ODD : Path2D.WIND_NON_ZERO);
                for (int sub = 0; sub < 3; sub++) {
                    path.moveTo(x, y);
                    for (int i = 0; i < 7; i++) {
                        double a = random.nextDouble() * 85 - 20, b = random.nextDouble() * 65 - 15;
                        if (test % 8 == 4) { path.lineTo(a, b); }
                        else if (test % 8 == 5) { path.quadTo(a, b, b, a); }
                        else { path.curveTo(a, b, b, a, a - b, b - a); }
                    }
                    if (sub % 2 == 0) { path.closePath(); }
                }
                return path;
            }
        }
    }
    static byte[] portable(int test) {
        byte[] pixels = input(test); GrayPathRaster.fill(pixels, WIDTH, HEIGHT, new Path2D.Float(shape(test))); return pixels;
    }
    @JSExport public static String hashes() {
        StringBuilder result = new StringBuilder();
        for (int test = 0; test < CASES; test++) { result.append(Arrays.hashCode(portable(test))).append(','); }
        return result.toString();
    }
    @JSExport public static Int8Array pixels() {
        byte[] all = new byte[CASES * WIDTH * HEIGHT];
        for (int test = 0; test < CASES; test++) {
            System.arraycopy(portable(test), 0, all, test * WIDTH * HEIGHT, WIDTH * HEIGHT);
        }
        return Int8Array.copyFromJavaArray(all);
    }
}
