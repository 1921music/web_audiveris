// SPDX-License-Identifier: AGPL-3.0-or-later
package org.audiveris.wasm;

/** Borrowed, read-only GRAY8 input. Padding at the end of each row is permitted. */
public final class GrayImage {
    public final int width;
    public final int height;
    public final int stride;
    final byte[] pixels;

    public GrayImage(int width, int height, int stride, byte[] pixels) {
        if (width <= 0 || height <= 0 || stride < width || pixels == null
                || (long) stride * height > pixels.length
                || (long) width * height > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("Invalid GRAY8 dimensions or buffer");
        }
        this.width = width;
        this.height = height;
        this.stride = stride;
        this.pixels = pixels;
    }

    int get(int x, int y) { return pixels[y * stride + x] & 255; }
}
