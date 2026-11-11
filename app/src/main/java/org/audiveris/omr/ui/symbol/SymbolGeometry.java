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
        default double widthAtPointSize(MusicFamily family, Shape shape, int pointSize) {
            throw new UnsupportedOperationException("Point-size font measurements are not installed");
        }
    }
    private static Provider configured;
    private SymbolGeometry() { }
    public static Provider provider() { return configured != null ? configured : DefaultHolder.INSTANCE; }
    public static void setProvider(Provider provider) { configured = provider; }

    /** FontSymbol's dimension rounding for symbols whose rectangle is their layout bounds. */
    public static Dimension roundedLayoutDimension(MusicFamily family, Shape shape, int interline) {
        Rectangle2D bounds = provider().bounds(family, shape, interline);
        return new Dimension((int)Math.rint(bounds.getWidth()), (int)Math.rint(bounds.getHeight()));
    }

    /** The original MusicFont.computePointSize interpolation, with only the font measurements delegated. */
    public static org.audiveris.omr.sheet.Scale.MusicFontScale musicFontScale(MusicFamily family, double width) {
        Provider metrics = provider(); Shape shape = Shape.NOTEHEAD_BLACK;
        int v1 = (int) Math.rint(width * 3.3);
        double w1 = metrics.widthAtPointSize(family, shape, v1);
        int dv = (int) Math.rint(width * 0.25);
        int v2 = w1 < width ? v1 + dv : v1 - dv;
        double w2 = metrics.widthAtPointSize(family, shape, v2);
        double dw = w2 - w1;
        int value = Math.abs(dw) < 0.01 ? v1 : (int) Math.rint(v1 + ((v2 - v1) * ((width - w1) / (w2 - w1))));
        // Retain the original final measurement used for verification logging.
        metrics.widthAtPointSize(family, shape, value);
        return new org.audiveris.omr.sheet.Scale.MusicFontScale(family.name(), value);
    }

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
        @Override public double widthAtPointSize(MusicFamily family, Shape shape, int pointSize) {
            return new MusicFont(family, pointSize).layoutShapeByCode(shape).getBounds().getWidth();
        }
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
