// SPDX-License-Identifier: AGPL-3.0-or-later
package org.audiveris.wasm.compat.platform;

import ij.process.ByteProcessor;
import java.awt.image.BufferedImage;
import org.audiveris.omr.run.RunTable;

/** Explicitly unsupported spot-preview output, separate from the recognition pixel operations. */
public final class HeadlessSpotImages {
    private HeadlessSpotImages() { }
    public static BufferedImage gray(ByteProcessor pixels) {
        throw new UnsupportedOperationException("Spot debug images require a host image output backend");
    }
    public static BufferedImage runs(RunTable pixels) {
        throw new UnsupportedOperationException("Spot debug images require a host image output backend");
    }
    public static void save(BufferedImage pixels, String sheet, String suffix) {
        throw new UnsupportedOperationException("Spot debug images require a host image output backend");
    }
    public static void failed(String message, Throwable cause) {
        throw new IllegalStateException(message, cause);
    }
    /** Only used for the fresh, full-image beam-filter result before head-spot thresholding. */
    public static ByteProcessor duplicate(ByteProcessor source) {
        var roi = source.getRoi();
        if (roi.x != 0 || roi.y != 0 || roi.width != source.getWidth() || roi.height != source.getHeight()
                || source.getMin() != 0 || source.getMax() != 255) {
            throw new UnsupportedOperationException("Spot snapshot requires full raw gray pixels with the default display range");
        }
        return new ByteProcessor(source.getWidth(), source.getHeight(), ((byte[]) source.getPixels()).clone(), null);
    }
}
