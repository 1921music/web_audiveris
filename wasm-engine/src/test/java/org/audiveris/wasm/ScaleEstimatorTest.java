package org.audiveris.wasm;

import java.util.Arrays;
import org.junit.Test;
import static org.junit.Assert.*;

public class ScaleEstimatorTest {
    @Test public void measuresKnownStaffSpacingAndThicknessAcrossYields() {
        int width = 640, height = 400;
        byte[] pixels = new byte[width * height];
        Arrays.fill(pixels, (byte) 255);
        for (int top : new int[] {40, 220}) {
            for (int line = 0; line < 5; line++) {
                for (int thickness = 0; thickness < 2; thickness++) {
                    for (int x = 20; x < width - 20; x++) { pixels[(top + line * 16 + thickness) * width + x] = 0; }
                }
            }
        }
        for (int budget : new int[] {1, 64, Integer.MAX_VALUE}) {
            ScaleEstimator estimator = new ScaleEstimator(width, height, pixels);
            while (!estimator.advance(budget)) { }
            assertEquals(2, estimator.result()[1]);
            assertEquals(16, estimator.result()[4]);
            assertEquals(9, estimator.result()[9]);
            assertEquals(1, estimator.result()[11]);
        }
        // Add a known eight-pixel beam, below the significance cutoff for staff-line peaks.
        for (int y = 150; y < 158; y++) {
            for (int x = 40; x < 240; x++) { pixels[y * width + x] = 0; }
        }
        ScaleEstimator measured = new ScaleEstimator(width, height, pixels);
        while (!measured.advance(32)) { }
        assertEquals(8, measured.result()[9]);
        assertEquals(0, measured.result()[11]);
    }

    @Test public void rejectsBlankPageInsteadOfInventingScale() {
        byte[] pixels = new byte[640 * 400];
        Arrays.fill(pixels, (byte) 255);
        ScaleEstimator estimator = new ScaleEstimator(640, 400, pixels);
        assertThrows(IllegalStateException.class, () -> estimator.advance(640));
    }
}
