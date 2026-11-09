// SPDX-License-Identifier: AGPL-3.0-or-later
// Adapted from Audiveris ScaleBuilder (copyright Audiveris contributors).
package org.audiveris.wasm;

import java.util.Comparator;
import java.util.Iterator;
import java.util.List;
import org.audiveris.omr.math.HiLoPeakFinder;
import org.audiveris.omr.math.IntegerFunction;
import org.audiveris.omr.math.Range;

/** The upstream vertical-run histograms and line/interline peak selection, without Sheet or UI. */
public final class ScaleEstimator {
    private final byte[] binary;
    private final int width, height, maxWhite;
    private final IntegerFunction black;
    private final IntegerFunction combo;
    private int nextColumn;
    private int[] result;

    public ScaleEstimator(int width, int height, byte[] binary) {
        if (width <= 0 || height <= 0 || binary == null || (long) width * height != binary.length) {
            throw new IllegalArgumentException("Invalid binary image");
        }
        this.binary = binary;
        this.width = width;
        this.height = height;
        int maxBlack = (int) Math.rint(height * .08);
        maxWhite = (int) Math.rint(height * .25);
        black = new IntegerFunction(0, maxBlack);
        combo = new IntegerFunction(0, maxBlack + maxWhite);
    }

    public boolean advance(int columnBudget) {
        if (columnBudget <= 0) { throw new IllegalArgumentException("Positive column budget required"); }
        int end = nextColumn + Math.min(columnBudget, width - nextColumn);
        for (; nextColumn < end; nextColumn++) {
            int previousEnd = 0, previousLength = 0;
            int y = 0;
            while (y < height) {
                while (y < height && binary[y * width + nextColumn] != 0) { y++; }
                if (y == height) { break; }
                int start = y;
                while (y < height && binary[y * width + nextColumn] == 0) { y++; }
                int length = y - start;
                black.addValue(length, 1);
                int white = start - previousEnd;
                if (white > 0 && white <= maxWhite && previousLength != 0) {
                    combo.addValue(previousLength + white, 1);
                    combo.addValue(white + length, 1);
                }
                previousLength = length;
                previousEnd = y;
            }
        }
        if (nextColumn == width && result == null) { result = selectPeaks(); }
        return result != null;
    }

    private List<Range> peaks(String name, IntegerFunction histogram) {
        return new HiLoPeakFinder(name, histogram).findPeaks(1, (int) Math.rint(histogram.getArea() * .025), .03);
    }

    private int[] selectPeaks() {
        List<Range> combos = peaks("combo", combo);
        if (combos.isEmpty()) { throw new IllegalStateException("No regularly spaced lines found"); }
        Range primary = combos.get(0), secondary = null;
        List<Range> others = combos.subList(1, combos.size());
        for (Iterator<Range> iterator = others.iterator(); iterator.hasNext();) {
            Range candidate = iterator.next();
            double ratio = (double) Math.max(candidate.main, primary.main) / Math.min(candidate.main, primary.main);
            if (ratio > 1.9) { iterator.remove(); }
            else if (ratio < 1.2) {
                primary = new Range(Math.min(candidate.min, primary.min), (candidate.main + primary.main) / 2,
                        Math.max(candidate.max, primary.max));
                iterator.remove();
            }
        }
        if (!others.isEmpty()) { secondary = others.get(0); }

        long blackPixels = 0;
        for (int length = 0; length <= black.getXMax(); length++) { blackPixels += (long) length * black.getValue(length); }
        if ((double) blackPixels / ((long) width * height) < .001) {
            throw new IllegalStateException("Too few black pixels");
        }
        List<Range> blacks = peaks("black", black);
        if (blacks.isEmpty()) { throw new IllegalStateException("No significant black lines found"); }
        int minCount = (int) Math.rint(black.getValue(blacks.get(0).main) * .4);
        for (int i = 1; i < blacks.size(); i++) {
            if (black.getValue(blacks.get(i).main) < minCount) { blacks.subList(i, blacks.size()).clear(); break; }
        }
        Range line;
        Integer significantBeam = null;
        if (blacks.size() > 1) {
            blacks.sort(Comparator.comparingInt(range -> range.main));
            Range first = blacks.get(0), second = blacks.get(1);
            line = second.main <= (int) Math.rint(primary.main * .5)
                    ? new Range(first.min, second.main, second.max) : first;
            if (second.main > (int) Math.rint(primary.main * .5)) { significantBeam = second.main; }
        } else { line = blacks.get(0); }
        if (primary.main < 11 || primary.main > 100) {
            throw new IllegalStateException("Unreliable interline resolution: " + primary.main);
        }
        Range large = secondary != null && secondary.main > primary.main ? secondary : primary;
        Range small = secondary == null ? null : (secondary.main < primary.main ? secondary : primary);
        int[] beams = measureBeams(line, large.main, secondary != null, significantBeam);
        return new int[] { line.min, line.main, line.max, large.min, large.main, large.max,
                small == null ? 0 : small.min, small == null ? 0 : small.main, small == null ? 0 : small.max,
                beams[0], beams[1], beams[2] };
    }

    /** Upstream ScaleBuilder.computeBeamKeys: measurements take precedence over guesses. */
    private int[] measureBeams(Range line, int interline, boolean twoInterlines, Integer significant) {
        if (significant != null) { return new int[] { significant, 0, 0 }; }
        int min = Math.max(line.max, (int) Math.rint(.45 * interline));
        int max = Math.min(interline - line.min / 2, (int) Math.rint(.95 * interline));
        int guess = min + (int) Math.rint(.25 * (max - min));
        int quorum = (int) Math.rint(black.getArea() * .01);
        List<Integer> peaks = black.getLocalMaxima(min, max);
        Integer measured = null, second = null;
        if (!peaks.isEmpty()) {
            int peak = peaks.get(0);
            if ((double) black.getValue(peak) / quorum >= 1 || Math.abs(peak - guess) <= 2) { measured = peak; }
            if (twoInterlines && peaks.size() > 1 && (double) black.getValue(peaks.get(1)) / quorum >= 1) {
                second = peaks.get(1);
            }
        }
        if (second != null && measured != null) { return new int[] { Math.max(measured, second), Math.min(measured, second), 0 }; }
        return measured == null ? new int[] { guess, 0, 1 } : new int[] { measured, 0, 0 };
    }

    public int completedColumns() { return nextColumn; }
    public int[] result() {
        if (result == null) { throw new IllegalStateException("Scale estimation is incomplete"); }
        return result;
    }
}
