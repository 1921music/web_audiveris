// SPDX-License-Identifier: AGPL-3.0-or-later
package org.audiveris.wasm.compat.platform;

import java.awt.geom.Rectangle2D;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/** Host-supplied font measurements, isolated per module and frozen on first use. */
public final class HeadlessBracketMetrics {
    private record Serifs(double upperWidth, double upperHeight, double lowerWidth, double lowerHeight) { }
    private static final Map<String, Serifs> values = new HashMap<>();
    private static boolean frozen;
    private HeadlessBracketMetrics() { }
    public static void register(String family, int interline, double upperWidth, double upperHeight,
            double lowerWidth, double lowerHeight) {
        if (frozen) { throw new IllegalStateException("Font measurements are frozen"); }
        Objects.requireNonNull(family);
        if (family.isBlank() || interline <= 0) { throw new IllegalArgumentException("Invalid font measurement key"); }
        for (double value : new double[]{upperWidth, upperHeight, lowerWidth, lowerHeight}) {
            if (!Double.isFinite(value) || value < 0) { throw new IllegalArgumentException("Invalid font measurement"); }
        }
        String key = family + "@" + interline;
        if (values.containsKey(key)) { throw new IllegalArgumentException("Duplicate font measurement: " + key); }
        values.put(key, new Serifs(upperWidth, upperHeight, lowerWidth, lowerHeight));
    }
    public static Rectangle2D[] require(String family, int interline) {
        frozen = true;
        String key = Objects.requireNonNull(family) + "@" + interline;
        Serifs serifs = values.get(key);
        if (serifs == null) {
            var provider=HeadlessSymbolGeometry.provider();
            var musicFamily=org.audiveris.omr.ui.symbol.MusicFamily.valueOf(family);
            var upper=provider.bounds(musicFamily,org.audiveris.omr.glyph.Shape.BRACKET_UPPER_SERIF,interline);
            var lower=provider.bounds(musicFamily,org.audiveris.omr.glyph.Shape.BRACKET_LOWER_SERIF,interline);
            serifs=new Serifs(upper.getWidth(),upper.getHeight(),lower.getWidth(),lower.getHeight());
        }
        return new Rectangle2D[]{new Rectangle2D.Double(0, 0, serifs.upperWidth, serifs.upperHeight),
                new Rectangle2D.Double(0, 0, serifs.lowerWidth, serifs.lowerHeight)};
    }
}
