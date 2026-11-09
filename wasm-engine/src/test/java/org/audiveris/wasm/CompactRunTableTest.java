package org.audiveris.wasm;

import java.util.Arrays;
import java.util.Random;
import org.junit.Test;
import static org.junit.Assert.*;

public class CompactRunTableTest {
    @Test public void reconstructsBothOrientationsAcrossYields() {
        Random random = new Random(1921);
        for (int width : new int[] {1, 2, 37}) {
            for (int height : new int[] {1, 3, 43}) {
                byte[] binary = new byte[width * height];
                for (int i = 0; i < binary.length; i++) { binary[i] = (byte) (random.nextBoolean() ? 0 : 255); }
                for (boolean horizontal : new boolean[] {true, false}) {
                    int[] previous = null;
                    for (int budget : new int[] {1, 8, Integer.MAX_VALUE}) {
                        CompactRunTable table = new CompactRunTable(width, height, binary, horizontal);
                        while (!table.advance(budget)) { }
                        int[] packed = table.packed();
                        if (previous != null) { assertArrayEquals(previous, packed); }
                        previous = packed;
                        byte[] reconstructed = new byte[binary.length];
                        Arrays.fill(reconstructed, (byte) 255);
                        int lanes = packed[0], base = lanes + 2;
                        for (int lane = 0; lane < lanes; lane++) {
                            for (int run = packed[lane + 1]; run < packed[lane + 2]; run++) {
                                int start = packed[base + 2 * run], length = packed[base + 2 * run + 1];
                                assertTrue(length > 0);
                                for (int p = start; p < start + length; p++) {
                                    reconstructed[horizontal ? lane * width + p : p * width + lane] = 0;
                                }
                            }
                        }
                        assertArrayEquals(binary, reconstructed);
                        assertTrue(table.advance(1));
                    }
                }
            }
        }
    }
}
