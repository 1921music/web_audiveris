//------------------------------------------------------------------------------------------------//
//                                                                                                //
//                                   H i L o P e a k F i n d e r                                  //
//                                                                                                //
//------------------------------------------------------------------------------------------------//
// <editor-fold defaultstate="collapsed" desc="hdr">
//
//  Copyright © Audiveris 2026. All rights reserved.
//
//  This program is free software: you can redistribute it and/or modify it under the terms of the
//  GNU Affero General Public License as published by the Free Software Foundation, either version
//  3 of the License, or (at your option) any later version.
//
//  This program is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY;
//  without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
//  See the GNU Affero General Public License for more details.
//
//  You should have received a copy of the GNU Affero General Public License along with this
//  program.  If not, see <http://www.gnu.org/licenses/>.
//------------------------------------------------------------------------------------------------//
// </editor-fold>
// Portable adaptation: plotting, XML annotations and logging removed.
package org.audiveris.omr.math;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

public class HiLoPeakFinder
{

    public final String name;

    public final IntegerFunction function;

    public final int xMin;

    public final int xMax;

    private double minGainRatio;

    private int minValue;

    private Quorum quorum;

    private int minDerivative;

    private List<Range> hilos;

    private List<Range> peaks;

    private final Comparator<Range> byReverseMainValue = new Comparator<Range>()
    {
        @Override
        public int compare (Range e1,
                            Range e2)
        {
            return Double.compare(function.getValue(e2.main), function.getValue(e1.main));
        }
    };

    public HiLoPeakFinder (String name,
                           IntegerFunction function)
    {
        Objects.requireNonNull(name, "PeakFinder needs non-null name");
        Objects.requireNonNull(function, "PeakFinder needs non-null function");
        this.name = name;
        this.function = function;

        xMin = function.getXMin();
        xMax = function.getXMax();
    }

    public HiLoPeakFinder (String name,
                           IntegerFunction function,
                           int xMin,
                           int xMax)
    {
        Objects.requireNonNull(name, "PeakFinder needs non-null name");
        Objects.requireNonNull(function, "PeakFinder needs non-null function");
        this.name = name;
        this.function = function;

        if ((xMin < function.getXMin()) || (xMax > function.getXMax())) {
            throw new IllegalArgumentException("PeakFinder domain not included in function domain");
        }

        this.xMin = xMin;
        this.xMax = xMax;
    }

    private Range createPeak (int pMin,
                              int main,
                              int pMax)
    {
        int total = function.getValue(main);
        int lower = main;
        int upper = main;
        boolean modified;

        do {
            modified = false;

            final int before = (lower == pMin) ? 0 : function.getValue(lower - 1);
            final int after = (upper == pMax) ? 0 : function.getValue(upper + 1);
            final int gain = Math.max(before, after);
            final double gainRatio = (double) gain / (total + gain);

            if (gainRatio >= minGainRatio) {
                if (before > after) {
                    lower--;

                } else {
                    upper++;

                }

                total += gain;
                modified = true;
            } else {

            }
        } while (modified);

        final Range peak = new Range(lower, main, upper);

        return peak;
    }

    public List<Range> findPeaks (int minValue,
                                  int minDerivative,
                                  double minGainRatio)
    {
        this.minValue = minValue;
        this.minDerivative = minDerivative;
        this.minGainRatio = minGainRatio;

        peaks = new ArrayList<>();
        retrieveHiLos();

        final Map<Range, Range> hiloToPeak = new HashMap<>();

        final List<Range> decreasing = new ArrayList<>(hilos);
        Collections.sort(decreasing, byReverseMainValue);

        for (Range hilo : decreasing) {
            final int i = hilos.indexOf(hilo);
            int pMin = Math.max(hilo.min - 1, 1);

            if (i > 0) {
                final Range prevHiLo = hilos.get(i - 1);
                final Range prevPeak = hiloToPeak.get(prevHiLo);
                pMin = Math.max(pMin, (prevPeak != null) ? (prevPeak.max + 1) : (prevHiLo.max + 1));
            }

            int pMax = hilo.max;

            final Range peak = createPeak(pMin, hilo.main, pMax);
            hiloToPeak.put(hilo, peak);
            peaks.add(peak);
        }

        if (!peaks.isEmpty()) {
            Collections.sort(peaks, byReverseMainValue);

            if (quorum != null) {
                for (int i = 0; i < peaks.size(); i++) {
                    final Range peak = peaks.get(i);

                    if (function.getValue(peak.main) < quorum.yMin) {
                        peaks.retainAll(peaks.subList(0, i));
                    }
                }
            }
        }

        return peaks;
    }

    public List<Range> getPeaks ()
    {
        return peaks;
    }

    private void retrieveHiLos ()
    {

        hilos = new ArrayList<>();

        DerPeak hiPeak = null;
        DerPeak loPeak = null;

        for (int x = xMin + 1; x <= xMax; x++) {
            final int y = function.getValue(x);
            final int der = function.getDerivative(x);

            if (der >= minDerivative) {

                if (loPeak != null) {

                    final Range hilo = new Range(
                            hiPeak.min,
                            function.argMax(hiPeak.min, loPeak.max),
                            loPeak.max);

                    hilos.add(hilo);
                    loPeak = hiPeak = null;
                }

                if ((hiPeak == null) || hiPeak.finished) {
                    hiPeak = new DerPeak(x, x); // Start a Hi
                } else {
                    hiPeak.max = x; // Extend Hi
                }
            } else if (der <= -minDerivative) {

                if (loPeak == null) {
                    if (hiPeak != null) {
                        loPeak = new DerPeak(x, x); // Start a Lo
                    }
                } else {
                    loPeak.max = x; // Extend Lo
                }
            } else if (loPeak != null) {

                final Range hilo = new Range(
                        hiPeak.min,
                        function.argMax(hiPeak.min, loPeak.max),
                        loPeak.max);

                hilos.add(hilo);
                loPeak = hiPeak = null;
            } else if (hiPeak != null) {

                if (y < minValue) {
                    hiPeak = null;
                } else {
                    hiPeak.finished = true; // Terminate Hi peak
                }
            }
        }
    }

    public void setQuorum (Quorum quorum)
    {
        this.quorum = quorum;
    }

    @Override
    public String toString ()
    {
        return new StringBuilder(getClass().getSimpleName()) //
                .append('{').append(name).append('}').toString();
    }

    private static class DerPeak
    {

        public final int min;

        public int max;

        private boolean finished;

        DerPeak (int min,
                 int max)
        {
            this.min = min;
            this.max = max;
        }

        @Override
        public String toString ()
        {
            return String.format("(%d,%d)", min, max);
        }
    }

    public static class Quorum
    {

        public final int yMin;

        public final Integer xMin;

        public final Integer xMax;

        public Quorum (int yMin)
        {
            this(yMin, null, null);
        }

        public Quorum (int yMin,
                       Integer xMin,
                       Integer xMax)
        {
            this.yMin = yMin;
            this.xMin = xMin;
            this.xMax = xMax;
        }
    }
}
