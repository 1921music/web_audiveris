package org.audiveris.wasm;

import org.junit.Test;
import static org.junit.Assert.*;

public class SectionGeometryTest {
    @Test public void computesBoundsWeightAndUpstreamCentroidInBothOrientations() {
        int[] section = {1, 0, 6, 0, 2, 1, 4, 2, 5};
        assertArrayEquals(new int[] {1, 1, 0, 6, 2, 3, 0, 9, 2, 5, 4}, SectionGeometry.compute(section, true));
        assertArrayEquals(new int[] {1, 0, 1, 2, 6, 0, 3, 9, 2, 5, 4}, SectionGeometry.compute(section, false));
        assertArrayEquals(new int[] {0}, SectionGeometry.compute(new int[] {0, 0}, true));
    }
}
