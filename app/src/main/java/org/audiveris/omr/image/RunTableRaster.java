// SPDX-License-Identifier: AGPL-3.0-or-later
package org.audiveris.omr.image;

import ij.process.ByteProcessor;
import org.audiveris.omr.run.Orientation;
import org.audiveris.omr.run.RunTable;

/** Integer glyph-run erasure with image clipping, shared by raw image backends. */
public final class RunTableRaster {
    private RunTableRaster() { }
    public static void erase(ByteProcessor output, RunTable table, int dx, int dy) {
        boolean horizontal = table.getOrientation() == Orientation.HORIZONTAL;
        int width = output.getWidth(), height = output.getHeight(); byte[] pixels = (byte[]) output.getPixels();
        for (int sequence = 0; sequence < table.getSize(); sequence++) {
            long fixed = (long) sequence + (horizontal ? dy : dx);
            if (fixed < 0 || fixed >= (horizontal ? height : width)) { continue; }
            for (var iterator = table.iterator(sequence); iterator.hasNext();) {
                var run = iterator.next(); long start = (long) run.getStart() + (horizontal ? dx : dy);
                int from = (int) Math.max(0, Math.min(horizontal ? width : height, start));
                int to = (int) Math.max(0, Math.min(horizontal ? width : height, start + run.getLength()));
                if (horizontal) { java.util.Arrays.fill(pixels, (int) fixed * width + from, (int) fixed * width + to, (byte) 255); }
                else { for (int y = from; y < to; y++) { pixels[y * width + (int) fixed] = (byte) 255; } }
            }
        }
    }
}
