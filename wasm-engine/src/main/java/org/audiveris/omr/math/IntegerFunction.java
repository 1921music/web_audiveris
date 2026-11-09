//------------------------------------------------------------------------------------------------//
//                                                                                                //
//                                  I n t e g e r F u n c t i o n                                 //
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

import java.io.PrintStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class IntegerFunction
{

    protected final int xMin;

    protected final int xMax;

    private final int[] values;

    public IntegerFunction (int xMin,
                            int xMax)
    {
        this.xMin = xMin;
        this.xMax = xMax;
        values = new int[xMax - xMin + 1];
    }

    public void addValue (int x,
                          int delta)
    {
        if (x >= xMin && x <= xMax) {
            values[x - xMin] += delta;
        }
    }

    public int argMax (int x1,
                       int x2)
    {
        int main = x1;
        int maxY = getValue(main);

        for (int x = x1; x <= x2; x++) {
            int y = getValue(x);

            if (maxY < y) {
                maxY = y;
                main = x;
            }
        }

        return main;
    }

    public int getArea ()
    {
        return getArea(xMin, xMax, 0);
    }

    public int getArea (int x1,
                        int x2,
                        int y)
    {
        int area = 0;

        for (int x = x1; x <= x2; x++) {
            area += Math.max(0, getValue(x) - y);
        }

        return area;
    }

    public int getDerivative (int x)
    {
        return getValue(x) - getValue(x - 1);
    }

    public List<Integer> getLocalMaxima (int x1,
                                         int x2)
    {

        x1 = Math.max(x1, xMin);
        x2 = Math.min(x2, xMax);

        final List<Integer> maxima = new ArrayList<>();
        Integer prevX = null;
        int prevY = 0;
        boolean growing = false;

        for (int x = x1; x <= x2; x++) {
            int y = getValue(x);

            if (prevX != null) {
                if (y >= prevY) {
                    growing = true;
                } else {
                    if (growing) {
                        maxima.add(prevX); // End of a local max
                    }

                    growing = false;
                }
            }

            prevX = x;
            prevY = y;
        }

        Collections.sort(
                maxima,
                (x3,
                 x4) -> Integer.compare(getValue(x4), getValue(x3)));

        return maxima;
    }

    public int getValue (int x)
    {
        return values[x - xMin];
    }

    public int getXMax ()
    {
        return xMax;
    }

    public int getXMin ()
    {
        return xMin;
    }

    public void print (String title,
                       Integer xMaxPrint,
                       PrintStream stream)
    {
        if (title != null) {
            stream.println(title);
        }

        final int x2 = (xMaxPrint != null) ? Math.min(xMax, xMaxPrint) : xMax;

        stream.println("[");
        for (int x = xMin + 1; x <= x2; x++) {
            stream.format("%6d:%d/%+d%n", x, getValue(x), getDerivative(x));
        }
        stream.println("]");
    }

    public void setValue (int x,
                          int y)
    {
        if (x >= xMin && x <= xMax) {
            values[x - xMin] = y;
        }
    }
}
