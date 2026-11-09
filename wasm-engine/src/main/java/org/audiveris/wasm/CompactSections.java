// SPDX-License-Identifier: AGPL-3.0-or-later
// Adapted from Audiveris SectionFactory and JunctionRatioPolicy (Audiveris contributors).
package org.audiveris.wasm;

import java.util.Arrays;

/** Upstream section junction rules with primitive storage and per-lane yield points. */
public final class CompactSections {
    private final int[] table;
    private final int lanes, base;
    private final int[] head, last, next, first, count, weight;
    private final boolean[] processed;
    private int[] active = new int[64];
    private int activeCount;
    private int nextLane;
    private int sectionCount;
    private int[] packed;

    public CompactSections(int[] table) {
        this.table = table;
        lanes = table[0];
        base = lanes + 2;
        int runs = table[lanes + 1];
        head = new int[runs]; last = new int[runs]; first = new int[runs];
        count = new int[runs]; weight = new int[runs]; processed = new boolean[runs];
        next = new int[runs];
        Arrays.fill(next, -1);
    }

    public boolean advance(int budget) {
        if (budget <= 0) { throw new IllegalArgumentException("Positive lane budget required"); }
        int end = nextLane + Math.min(budget, lanes - nextLane);
        for (; nextLane < end; nextLane++) {
            int start = table[nextLane + 1], stop = table[nextLane + 2];
            if (start == stop) { activeCount = 0; continue; }
            int[] previous = active;
            int previousCount = activeCount;
            active = new int[Math.max(64, stop - start)];
            activeCount = 0;
            // Mark sections that diverge or whose only successor violates the length ratio.
            for (int i = 0; i < previousCount; i++) {
                int section = previous[i], overlaps = 0, successor = -1;
                for (int run = start; run < stop; run++) {
                    if (runStart(run) > runStop(last[section])) { break; }
                    if (runStop(run) >= runStart(last[section])) { overlaps++; successor = run; }
                }
                if (overlaps > 1) { processed[section] = true; }
                else if (overlaps == 1) {
                    // Upstream getMeanRunLength deliberately uses integer division.
                    double ratio = (double) runLength(successor) / (weight[section] / count[section]);
                    if (ratio < .8 || ratio > 1.25) { processed[section] = true; }
                }
            }
            for (int run = start; run < stop; run++) {
                int overlaps = 0, predecessor = -1;
                for (int i = 0; i < previousCount; i++) {
                    int section = previous[i];
                    if (runStart(last[section]) > runStop(run)) { break; }
                    if (runStop(last[section]) >= runStart(run)) { overlaps++; predecessor = section; }
                }
                int section;
                if (overlaps == 1 && !processed[predecessor]) {
                    section = predecessor;
                    next[last[section]] = run;
                    last[section] = run;
                    count[section]++;
                    weight[section] += runLength(run);
                } else {
                    section = sectionCount++;
                    head[section] = last[section] = run;
                    first[section] = nextLane;
                    count[section] = 1;
                    weight[section] = runLength(run);
                }
                active[activeCount++] = section;
            }
        }
        return nextLane == lanes;
    }

    private int runStart(int run) { return table[base + 2 * run]; }
    private int runLength(int run) { return table[base + 2 * run + 1]; }
    private int runStop(int run) { return runStart(run) + runLength(run) - 1; }

    public int completedLanes() { return nextLane; }
    public int laneCount() { return lanes; }

    /** [section count, sectionCount+1 data offsets, (first lane,run count,(start,length)...)...]. */
    public int[] packed() {
        if (nextLane != lanes) { throw new IllegalStateException("Sections are incomplete"); }
        if (packed == null) {
            int runs = table[lanes + 1];
            packed = new int[Math.addExact(sectionCount + 2, Math.addExact(2 * sectionCount, 2 * runs))];
            packed[0] = sectionCount;
            int dataBase = sectionCount + 2, cursor = dataBase;
            for (int section = 0; section < sectionCount; section++) {
                packed[section + 1] = cursor - dataBase;
                packed[cursor++] = first[section];
                packed[cursor++] = count[section];
                for (int run = head[section]; run != -1; run = next[run]) {
                    packed[cursor++] = runStart(run);
                    packed[cursor++] = runLength(run);
                }
            }
            packed[sectionCount + 1] = cursor - dataBase;
        }
        return packed;
    }
}
