// SPDX-License-Identifier: AGPL-3.0-or-later
package org.audiveris.wasm.compat.platform;

import ij.process.Blitter;
import ij.process.ByteProcessor;
import ij.process.ImageProcessor;
import java.util.Objects;

/** Raw GRAY8 COPY for the four fresh-buffer header crops, without float/display-LUT branches. */
public final class HeadlessGrayCopy {
    private HeadlessGrayCopy() { }
    public static void copyBits(ByteProcessor target, ImageProcessor source, int dx, int dy, int mode) {
        Objects.requireNonNull(target); Objects.requireNonNull(source);
        if (!(source instanceof ByteProcessor) || mode != Blitter.COPY) {
            throw new UnsupportedOperationException("Header crops require raw GRAY8 COPY");
        }
        byte[] input = (byte[]) source.getPixels(), output = (byte[]) target.getPixels();
        if (input == output) { throw new UnsupportedOperationException("Header crops require separate pixel buffers"); }
        var roi = target.getRoi();
        if (roi.x != 0 || roi.y != 0 || roi.width != target.getWidth() || roi.height != target.getHeight()) {
            throw new UnsupportedOperationException("Header crops require a fresh full-image destination");
        }
        int width = target.getWidth(), height = target.getHeight(), sourceWidth = source.getWidth();
        long left = Math.max(0L, dx), top = Math.max(0L, dy);
        long right = Math.min((long) width, (long) dx + sourceWidth), bottom = Math.min((long) height, (long) dy + source.getHeight());
        if (right <= left || bottom <= top) { return; }
        int count = (int) (right - left);
        for (int y = (int) top; y < bottom; y++) {
            int from = (int) ((long) (y - (long) dy) * sourceWidth + left - dx);
            System.arraycopy(input, from, output, y * width + (int) left, count);
        }
    }
}
