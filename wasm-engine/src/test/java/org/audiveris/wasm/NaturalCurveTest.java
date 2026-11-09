package org.audiveris.wasm;

import java.util.Random;
import org.audiveris.omr.math.NaturalSpline;
import org.junit.Test;
import static org.junit.Assert.*;

public class NaturalCurveTest {
    @Test public void matchesUnchangedUpstreamForAllDegreesAndBothOrientations() {
        Random random = new Random(1921);
        for (int knots : new int[] {2, 3, 4, 9, 30}) {
            for (int sample = 0; sample < 20; sample++) {
                double[] axis = new double[knots], values = new double[knots];
                for (int i = 0; i < knots; i++) {
                    axis[i] = i == 0 ? 10 : axis[i - 1] + 10 + random.nextDouble() * 30;
                    values[i] = 1000 + random.nextDouble() * 15;
                }
                NaturalCurve portable = new NaturalCurve(axis, values);
                NaturalSpline horizontal = NaturalSpline.interpolate(axis, values);
                NaturalSpline vertical = NaturalSpline.interpolate(values, axis);
                for (int step = 0; step <= 100; step++) {
                    double coordinate = step == 100 ? axis[knots - 1] : axis[0] + (axis[knots - 1] - axis[0]) * step / 100;
                    assertEquals(horizontal.yAtX(coordinate), portable.valueAt(coordinate), 1e-10);
                    assertEquals(vertical.xAtY(coordinate), portable.valueAt(coordinate), 1e-10);
                    assertEquals(horizontal.yDerivativeAtX(coordinate), portable.slopeAt(coordinate), 1e-10);
                    assertEquals(vertical.xDerivativeAtY(coordinate), portable.slopeAt(coordinate), 1e-10);
                }
                assertEquals(horizontal.yAtXExt(-10), portable.valueAtExt(-10), 1e-10);
                assertEquals(horizontal.yAtXExt(axis[knots - 1] + 10), portable.valueAtExt(axis[knots - 1] + 10), 1e-10);
            }
        }
    }

    @Test public void rejectsMalformedCurvesAndOutsideDerivativeQueries() {
        assertThrows(IllegalArgumentException.class, () -> new NaturalCurve(new double[] {1, 1}, new double[] {1, 2}));
        NaturalCurve curve = new NaturalCurve(new double[] {1, 2}, new double[] {3, 5});
        assertEquals(4, curve.valueAt(1.5), 1e-10);
        assertThrows(IllegalArgumentException.class, () -> curve.slopeAt(0));
    }
}
