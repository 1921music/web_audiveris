//------------------------------------------------------------------------------------------------//
//                                                                                                //
//                                            R a n g e                                           //
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

import java.util.Comparator;

public class Range
{

    public static final Comparator<Range> byMain = (e1,
                                                    e2) -> Double.compare(e1.main, e2.main);

    public final int min;

    public final int main;

    public final int max;

    private Range ()
    {
        this.min = 0;
        this.main = 0;
        this.max = 0;
    }

    public Range (int min,
                  int main,
                  int max)
    {
        this.min = min;
        this.main = main;
        this.max = max;
    }

    public int getWidth ()
    {
        return max - min + 1;
    }

    @Override
    public String toString ()
    {
        return String.format("(%d,%d,%d)", min, main, max);
    }
}
