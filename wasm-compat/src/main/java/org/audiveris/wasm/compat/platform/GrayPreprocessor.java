// SPDX-License-Identifier: AGPL-3.0-or-later
package org.audiveris.wasm.compat.platform;

import ij.process.ByteProcessor;
import org.audiveris.omr.image.GlobalFilter;
import org.audiveris.omr.image.VerticalFilter;

/** File/DOM-independent preprocessing using the original Audiveris algorithms. Consumes the input. */
public final class GrayPreprocessor {
    private GrayPreprocessor() { }
    public static ByteProcessor global(GrayInput input, int threshold) {
        if (threshold < 0 || threshold > 255) { throw new IllegalArgumentException("Threshold must be in [0,255]"); }
        return new GlobalFilter(input.takeProcessor(), threshold).filteredImage();
    }
    public static ByteProcessor adaptive(GrayInput input, double meanCoefficient, double deviationCoefficient) {
        if (!Double.isFinite(meanCoefficient) || !Double.isFinite(deviationCoefficient)) {
            throw new IllegalArgumentException("Adaptive coefficients must be finite");
        }
        return new VerticalFilter(input.takeProcessor(), meanCoefficient, deviationCoefficient).filteredImage();
    }
}
