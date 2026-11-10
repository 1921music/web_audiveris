// SPDX-License-Identifier: AGPL-3.0-or-later
package org.audiveris.wasm.compat.platform;

import ij.process.ByteProcessor;
import java.util.Objects;

/** Packed unsigned 8-bit rows (0 black, 255 white). Owns copied/transferred pixels, consumed once. */
public final class GrayInput {
    private final int width, height;
    private byte[] pixels;
    private GrayInput(int width, int height, byte[] pixels, boolean copy) {
        Objects.requireNonNull(pixels, "gray pixels");
        if (width <= 0 || height <= 0 || (long) width * height != pixels.length) {
            throw new IllegalArgumentException("Gray dimensions must match the packed pixel count");
        }
        this.width = width; this.height = height; this.pixels = copy ? pixels.clone() : pixels;
    }
    public static GrayInput copyOf(int width, int height, byte[] pixels) { return new GrayInput(width, height, pixels, true); }
    /** Caller relinquishes every reference to this buffer; use copyOf when ownership is shared. */
    public static GrayInput takeOwnership(int width, int height, byte[] pixels) { return new GrayInput(width, height, pixels, false); }
    public int width() { return width; }
    public int height() { return height; }
    public boolean isConsumed() { return pixels == null; }
    public ByteProcessor takeProcessor() {
        if (pixels == null) { throw new IllegalStateException("Gray input has already been consumed"); }
        ByteProcessor processor = new ByteProcessor(width, height, pixels, null);
        pixels = null; // Transfer the owned copy, without a second pixel copy.
        return processor;
    }
}
