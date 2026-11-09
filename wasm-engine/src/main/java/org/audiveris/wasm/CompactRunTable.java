// SPDX-License-Identifier: AGPL-3.0-or-later
package org.audiveris.wasm;

import java.util.Arrays;

/** Foreground runs without AWT, ImageJ, per-run objects, or implicit parallel execution. */
public final class CompactRunTable {
    private final byte[] binary;
    private final int width, laneCount, laneLength;
    private final boolean horizontal;
    private final int[] offsets;
    private int[] data = new int[1024];
    private int used;
    private int nextLane;
    private int[] packed;

    public CompactRunTable(int width, int height, byte[] binary, boolean horizontal) {
        if (width <= 0 || height <= 0 || binary == null || (long) width * height != binary.length) {
            throw new IllegalArgumentException("Invalid binary image");
        }
        this.binary = binary;
        this.width = width;
        this.horizontal = horizontal;
        laneCount = horizontal ? height : width;
        laneLength = horizontal ? width : height;
        offsets = new int[laneCount + 1];
    }

    public boolean advance(int laneBudget) {
        if (laneBudget <= 0) { throw new IllegalArgumentException("Positive lane budget required"); }
        int end = nextLane + Math.min(laneBudget, laneCount - nextLane);
        for (; nextLane < end; nextLane++) {
            offsets[nextLane] = used / 2;
            int coordinate = 0;
            while (coordinate < laneLength) {
                while (coordinate < laneLength && !foreground(nextLane, coordinate)) { coordinate++; }
                if (coordinate == laneLength) { break; }
                int start = coordinate;
                while (coordinate < laneLength && foreground(nextLane, coordinate)) { coordinate++; }
                if (used + 2 > data.length) { data = Arrays.copyOf(data, Math.addExact(data.length, data.length / 2)); }
                data[used++] = start;
                data[used++] = coordinate - start;
            }
        }
        offsets[nextLane] = used / 2;
        return nextLane == laneCount;
    }

    private boolean foreground(int lane, int coordinate) {
        return binary[horizontal ? lane * width + coordinate : coordinate * width + lane] == 0;
    }

    public int completedLanes() { return nextLane; }
    public int laneCount() { return laneCount; }

    /** [lane count, laneCount+1 run offsets, interleaved (start,length) pairs]. */
    public int[] packed() {
        if (nextLane != laneCount) { throw new IllegalStateException("Run table is incomplete"); }
        if (packed == null) {
            packed = new int[Math.addExact(laneCount + 2, used)];
            packed[0] = laneCount;
            System.arraycopy(offsets, 0, packed, 1, offsets.length);
            System.arraycopy(data, 0, packed, laneCount + 2, used);
            data = null;
        }
        return packed;
    }
}
