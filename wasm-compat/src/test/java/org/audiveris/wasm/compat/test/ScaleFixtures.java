package org.audiveris.wasm.compat.test;

import java.util.Arrays;

final class ScaleFixtures {
    static byte[] staff() {
        byte[] pixels = new byte[160 * 150]; Arrays.fill(pixels, (byte) 255);
        for (int line = 0; line < 5; line++) {
            for (int y = 45 + line * 12; y < 47 + line * 12; y++) {
                for (int x = 8; x < 152; x++) { pixels[y * 160 + x] = 0; }
            }
        }
        return pixels;
    }
    static byte[] blank() { byte[] pixels = new byte[160 * 150]; Arrays.fill(pixels, (byte) 255); return pixels; }
}
