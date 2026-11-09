package org.audiveris.wasm;

import org.junit.Test;
import static org.junit.Assert.*;

public class LineFitTest {
    @Test public void fitsAProjectedLineAndSupportsMergingMeasurements() {
        LineFit all = new LineFit(), first = new LineFit(), second = new LineFit();
        for (int x = 0; x < 100; x++) {
            all.includePoint(x, 2 * x + 3);
            (x < 50 ? first : second).includePoint(x, 2 * x + 3);
        }
        first.include(second);
        assertEquals(0, all.distanceOf(123, 249), 1e-10);
        assertEquals(2, all.getSlope(), 1e-10);
        assertEquals(249, all.yAtX(123), 1e-10);
        assertEquals(123, all.xAtY(249), 1e-10);
        assertEquals(all.distanceOf(0, 0), first.distanceOf(0, 0), 1e-10);
        assertEquals(0, all.meanDistance(), 1e-5);
    }

    @Test public void measuresRectangleThicknessAndHandlesVerticalLines() {
        LineFit rectangle = new LineFit(), vertical = new LineFit();
        for (int x = 0; x < 4; x++) {
            rectangle.includePoint(x, 0); rectangle.includePoint(x, 1);
        }
        assertEquals(.5, rectangle.meanDistance(), 1e-10);
        for (int y = 0; y < 10; y++) { vertical.includePoint(10, y); }
        assertEquals(0, vertical.distanceOf(10, 50), 1e-10);
        assertEquals(10, vertical.xAtY(50), 1e-10);
        assertThrows(IllegalStateException.class, () -> vertical.yAtX(10));
        assertThrows(IllegalStateException.class, () -> new LineFit().meanDistance());
    }
}
