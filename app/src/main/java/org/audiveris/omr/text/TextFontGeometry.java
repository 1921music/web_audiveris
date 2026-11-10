// SPDX-License-Identifier: AGPL-3.0-or-later
package org.audiveris.omr.text;

import java.awt.Rectangle;
import java.awt.font.TextLayout;
import java.awt.geom.Rectangle2D;
import org.audiveris.omr.ui.symbol.OmrFont;
import org.audiveris.omr.ui.symbol.TextFont;
import org.audiveris.omr.ui.symbol.MusicFont;

/** Text measurements used by recognition, separately from desktop font loading. */
public final class TextFontGeometry {
    public record Measurement(String fontName, double x, double y, double width, double height) { }
    public interface Provider {
        Measurement visualBounds(FontAttributes attributes, int pointSize, String value);
        Rectangle2D layoutBounds(FontInfo font, String value);
        double advance(FontInfo font, String value);
        String defaultFontName();
        Measurement musicVisualBounds(FontInfo font, String value);
        Rectangle2D musicLayoutBounds(FontInfo font, String value);
        double musicAdvance(FontInfo font, String value);
    }
    private static Provider configured;
    private TextFontGeometry() { }
    public static Provider provider() { return configured != null ? configured : DefaultHolder.INSTANCE; }
    public static void setProvider(Provider provider) { configured = provider; }
    /** Original OmrFont.computeSize float arithmetic and rounding. */
    public static int computeSize(Measurement measurement, int pointSize, Rectangle bounds) {
        float ratio = bounds.width >= bounds.height ? bounds.width / (float)measurement.width()
                : bounds.height / (float)measurement.height();
        return (int)Math.rint(ratio * (float)pointSize);
    }
    public static FontInfo adjustedFont(FontAttributes attributes, int pointSize, String value, Rectangle bounds) {
        var measurement = provider().visualBounds(attributes, pointSize, value);
        return new FontInfo(attributes, computeSize(measurement, pointSize, bounds), measurement.fontName());
    }
    public static FontInfo adjustedMusicFont(FontInfo font, String value, Rectangle bounds) {
        var measurement = provider().musicVisualBounds(font, value);
        return new FontInfo(computeSize(measurement, font.pointSize, bounds), measurement.fontName());
    }
    /** Original OmrFont.computeLocation, using the same unadjusted music font measurement. */
    public static java.awt.Point musicLocation(FontInfo font, String value, Rectangle bounds) {
        var measurement = provider().musicVisualBounds(font, value);
        double rectDy = measurement.y() / measurement.height();
        double ratio = bounds.width >= bounds.height ? bounds.width / measurement.width() : bounds.height / measurement.height();
        return new java.awt.Point(bounds.x, bounds.y + (int)Math.rint(rectDy * ratio));
    }
    private static final class DefaultHolder { static final Provider INSTANCE = new AwtProvider(); }
    private static final class AwtProvider implements Provider {
        @Override public Measurement visualBounds(FontAttributes attributes, int pointSize, String value) {
            TextFont font = TextFont.getBestFont(attributes, pointSize);
            Rectangle2D bounds = font.createGlyphVector(OmrFont.frc, value).getVisualBounds();
            return new Measurement(font.getFontName(), bounds.getX(), bounds.getY(), bounds.getWidth(), bounds.getHeight());
        }
        @Override public Rectangle2D layoutBounds(FontInfo info, String value) {
            return new TextLayout(value, new TextFont(info), OmrFont.frc).getBounds();
        }
        @Override public double advance(FontInfo info, String value) { return new TextFont(info).layout(value).getAdvance(); }
        @Override public String defaultFontName() { return TextFont.TEXT_FONT_NAME; }
        @Override public Measurement musicVisualBounds(FontInfo info, String value) {
            var font = new MusicFont(info); var bounds = font.createGlyphVector(OmrFont.frc, value).getVisualBounds();
            return new Measurement(font.getFontName(), bounds.getX(), bounds.getY(), bounds.getWidth(), bounds.getHeight());
        }
        @Override public Rectangle2D musicLayoutBounds(FontInfo info, String value) { return new MusicFont(info).layout(value).getBounds(); }
        @Override public double musicAdvance(FontInfo info, String value) { return new MusicFont(info).layout(value).getAdvance(); }
    }
}
