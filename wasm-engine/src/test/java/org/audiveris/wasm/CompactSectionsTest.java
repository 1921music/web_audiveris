package org.audiveris.wasm;

import java.util.Arrays;
import org.junit.Test;
import static org.junit.Assert.*;

public class CompactSectionsTest {
    private int[] sections(String... rows) {
        int width = rows[0].length();
        byte[] binary = new byte[width * rows.length];
        for (int y = 0; y < rows.length; y++) {
            for (int x = 0; x < width; x++) { binary[y * width + x] = (byte) (rows[y].charAt(x) == '#' ? 0 : 255); }
        }
        CompactRunTable runs = new CompactRunTable(width, rows.length, binary, true);
        runs.advance(Integer.MAX_VALUE);
        int[] reference = null;
        for (int budget : new int[] {1, 8, Integer.MAX_VALUE}) {
            CompactSections sections = new CompactSections(runs.packed());
            while (!sections.advance(budget)) { }
            if (reference != null) { assertArrayEquals(reference, sections.packed()); }
            reference = sections.packed();
            byte[] reconstructed = new byte[binary.length];
            Arrays.fill(reconstructed, (byte) 255);
            int base = reference[0] + 2;
            for (int section = 0; section < reference[0]; section++) {
                int cursor = base + reference[section + 1];
                int first = reference[cursor++], count = reference[cursor++];
                for (int row = 0; row < count; row++) {
                    int start = reference[cursor++], length = reference[cursor++];
                    for (int x = start; x < start + length; x++) { reconstructed[(first + row) * width + x] = 0; }
                }
            }
            assertArrayEquals(binary, reconstructed);
        }
        return reference;
    }

    @Test public void splitsAtDivergenceAndConvergence() {
        assertEquals(4, sections(".###.", ".###.", ".#.#.", ".###.")[0]);
    }

    @Test public void preservesIntegerMeanAndSeparatesEmptyLanes() {
        assertEquals(2, sections("#######..", "######...", "########.")[0]);
        assertEquals(2, sections(".###.", ".....", ".###.")[0]);
        assertEquals(0, sections(".....", ".....")[0]);
    }
}
