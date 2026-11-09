// SPDX-License-Identifier: AGPL-3.0-or-later
// Adapted from Audiveris AdaptiveFilter/VerticalFilter, copyright Audiveris contributors.
package org.audiveris.wasm;

/** Upstream threshold formula and rolling integral columns, with explicit yield points. */
public final class AdaptiveBinarizer {
    private final GrayImage image;
    private final int radius;
    private final double meanCoefficient;
    private final double deviationCoefficient;
    private final long[][] sums;
    private final long[][] squares;
    private final byte[] output;
    private int integralRight = -1;
    private int nextColumn;

    public AdaptiveBinarizer(GrayImage image, int radius, double mean, double deviation) {
        if (radius < 0 || radius > 1024 || !Double.isFinite(mean) || !Double.isFinite(deviation)) {
            throw new IllegalArgumentException("Invalid adaptive threshold parameters");
        }
        this.image = image;
        this.radius = radius;
        this.meanCoefficient = mean;
        this.deviationCoefficient = deviation;
        int columns = 2 + 2 * radius;
        sums = new long[columns][image.height];
        squares = new long[columns][image.height];
        output = new byte[image.width * image.height];
    }

    /** Returns true when the complete binary image is available. */
    public boolean advance(int columnBudget) {
        if (columnBudget <= 0) { throw new IllegalArgumentException("Positive column budget required"); }
        int end = nextColumn + Math.min(columnBudget, image.width - nextColumn);
        for (; nextColumn < end; nextColumn++) {
            int x = nextColumn;
            int x1 = Math.max(-1, x - radius - 1);
            int x2 = Math.min(image.width - 1, x + radius);
            while (integralRight < x2) { populate(++integralRight); }
            for (int y = 0; y < image.height; y++) {
                int y1 = Math.max(-1, y - radius - 1);
                int y2 = Math.min(image.height - 1, y + radius);
                int area = (x2 - x1) * (y2 - y1);
                double mean = rectangle(sums, x1, y1, x2, y2) / area;
                double squareMean = rectangle(squares, x1, y1, x2, y2) / area;
                double deviation = Math.sqrt(Math.abs(squareMean - mean * mean));
                double threshold = meanCoefficient * mean + deviationCoefficient * deviation;
                output[y * image.width + x] = (byte) (image.get(x, y) <= threshold ? 0 : 255);
            }
        }
        return nextColumn == image.width;
    }

    private double rectangle(long[][] table, int x1, int y1, int x2, int y2) {
        long a = x1 >= 0 && y1 >= 0 ? table[x1 % table.length][y1] : 0;
        long b = y1 >= 0 ? table[x2 % table.length][y1] : 0;
        long c = x1 >= 0 ? table[x1 % table.length][y2] : 0;
        long d = table[x2 % table.length][y2];
        return (a + d) - b - c;
    }

    private void populate(int x) {
        int column = x % sums.length;
        int previous = (x + sums.length - 1) % sums.length;
        long top = 0, topLeft = 0, squareTop = 0, squareTopLeft = 0;
        for (int y = 0; y < image.height; y++) {
            long pixel = image.get(x, y);
            long left = sums[previous][y];
            long squareLeft = squares[previous][y];
            long value = pixel + left + top - topLeft;
            long squareValue = pixel * pixel + squareLeft + squareTop - squareTopLeft;
            sums[column][y] = value;
            squares[column][y] = squareValue;
            top = value;
            topLeft = left;
            squareTop = squareValue;
            squareTopLeft = squareLeft;
        }
    }

    public int completedColumns() { return nextColumn; }

    /** The caller takes ownership only after completion. */
    public byte[] result() {
        if (nextColumn != image.width) { throw new IllegalStateException("Binarization is incomplete"); }
        return output;
    }
}
