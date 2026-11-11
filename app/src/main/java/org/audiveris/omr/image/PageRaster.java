// SPDX-License-Identifier: AGPL-3.0-or-later
package org.audiveris.omr.image;

import java.awt.Shape;
import java.awt.Stroke;
import org.audiveris.omr.glyph.Glyph;
import org.audiveris.omr.sig.inter.Inter;
import org.audiveris.omr.ui.symbol.MusicFamily;

/** White, non-antialiased drawing used by page cleaners, independent of a desktop image. */
public interface PageRaster {
    void initialize();
    Stroke getStroke();
    void setStroke(Stroke stroke);
    void fill(Shape shape);
    void draw(Shape shape);
    void fillRect(int x, int y, int width, int height);
    void glyph(Glyph glyph);
    void symbol(Inter inter, MusicFamily family, int pointSize);
}
