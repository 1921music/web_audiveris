// SPDX-License-Identifier: AGPL-3.0-or-later
// Adapted from Audiveris BasicSection/DynamicSection (Audiveris contributors).
package org.audiveris.wasm;

/** Geometry needed by filament construction, computed from runs instead of AWT polygons. */
public final class SectionGeometry {
    private SectionGeometry() { }

    /** [section count, (x,y,width,height,centroidX,centroidY,weight,runCount,maxRun,meanRun)...]. */
    public static int[] compute(int[] sections, boolean horizontal) {
        int total = sections[0], base = total + 2;
        int[] geometry = new int[Math.addExact(1, Math.multiplyExact(total, 10))];
        geometry[0] = total;
        int output = 1;
        for (int section = 0; section < total; section++) {
            int cursor = base + sections[section + 1];
            int first = sections[cursor++], count = sections[cursor++];
            int min = Integer.MAX_VALUE, end = 0, weight = 0, max = 0;
            int centroidCoordinate = 0, centroidPosition = 0;
            for (int row = 0; row < count; row++) {
                int start = sections[cursor++], length = sections[cursor++];
                min = Math.min(min, start);
                end = Math.max(end, start + length);
                weight += length;
                max = Math.max(max, length);
                // Preserve upstream integer centroid semantics, including the half-pixel convention.
                centroidCoordinate += length * (2 * start + length);
                centroidPosition += length * (2 * (first + row));
            }
            centroidCoordinate /= 2 * weight;
            centroidPosition /= 2 * weight;
            geometry[output++] = horizontal ? min : first;
            geometry[output++] = horizontal ? first : min;
            geometry[output++] = horizontal ? end - min : count;
            geometry[output++] = horizontal ? count : end - min;
            geometry[output++] = horizontal ? centroidCoordinate : centroidPosition;
            geometry[output++] = horizontal ? centroidPosition : centroidCoordinate;
            geometry[output++] = weight;
            geometry[output++] = count;
            geometry[output++] = max;
            geometry[output++] = weight / count;
        }
        return geometry;
    }
}
