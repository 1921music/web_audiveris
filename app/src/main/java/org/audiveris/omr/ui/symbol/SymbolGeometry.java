// SPDX-License-Identifier: AGPL-3.0-or-later
package org.audiveris.omr.ui.symbol;

import java.awt.Dimension;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.geom.Point2D;
import java.awt.geom.Rectangle2D;
import org.audiveris.omr.glyph.Shape;

/** Font measurements used by recognition, independently of drawing or desktop font loading. */
public final class SymbolGeometry {
    public interface Provider {
        Rectangle2D bounds(MusicFamily family, Shape shape, int interline);
        Point2D centroidOffset(MusicFamily family, Shape shape, int interline);
        Dimension timeDimension(MusicFamily family, Shape shape, int interline, int numerator, int denominator);
    }
    private static Provider configured;
    private SymbolGeometry() { }
    public static Provider provider() { return configured != null ? configured : DefaultHolder.INSTANCE; }
    public static void setProvider(Provider provider) { configured = provider; }

    /** Same normalized-centroid positioning as ShapeSymbol.getCentroid(Rectangle). */
    public static Point centroid(MusicFamily family, Shape shape, int interline, Rectangle box) {
        Point2D offset = provider().centroidOffset(family, shape, interline);
        return new Point((int) Math.rint(box.getCenterX() + box.getWidth() * offset.getX()),
                (int) Math.rint(box.getCenterY() + box.getHeight() * offset.getY()));
    }
    private static final class DefaultHolder {
        static final Provider INSTANCE = new AwtProvider();
    }
    private static final class AwtProvider implements Provider {
        @Override public Rectangle2D bounds(MusicFamily family, Shape shape, int interline) {
            return shape.getFontSymbolByInterline(family, interline).getLayout().getBounds();
        }
        @Override public Point2D centroidOffset(MusicFamily family, Shape shape, int interline) {
            // Preserve ClefBuilder's original font/symbol selection, including its point-size query.
            FontSymbol fs = shape.getFontSymbolByInterline(family, MusicFont.getPointSize(interline));
            if (fs.symbol.centroidOffset == null) { fs.symbol.getCentroid(new Rectangle(0, 0, 1, 1)); }
            Point2D offset = fs.symbol.centroidOffset;
            return new Point2D.Double(offset.getX(), offset.getY());
        }
        @Override public Dimension timeDimension(MusicFamily family, Shape shape, int interline, int numerator, int denominator) {
            MusicFont font = MusicFont.getBaseFont(family, interline);
            return new NumDenSymbol(shape, family, numerator, denominator).getDimension(font);
        }
    }
}
