// SPDX-License-Identifier: AGPL-3.0-or-later
package org.audiveris.wasm.compat.platform;

import java.awt.geom.Rectangle2D;
import java.io.IOException;
import org.apache.fontbox.ttf.*;
import org.apache.pdfbox.io.RandomAccessReadBuffer;

/** Owned, unshaped physical TTF metrics. Font selection/fallback belongs to the caller. */
public final class TrueTypeFaceMetrics implements AutoCloseable {
    private final TrueTypeFont font;
    private final CmapSubtable cmap;
    private final int units;
    public TrueTypeFaceMetrics(byte[] bytes) throws IOException {
        TrueTypeFont parsed = new TTFParser().parse(new RandomAccessReadBuffer(bytes.clone()));
        try {
            units = parsed.getUnitsPerEm();
            if (units <= 0 || (units & (units - 1)) != 0) {
                throw new UnsupportedOperationException("Exact metrics currently require power-of-two TTF units per em");
            }
            var table = parsed.getCmap(); CmapSubtable found = null;
            if (table != null) {
                for (int[] code : new int[][]{{0, 4}, {3, 10}, {0, 3}, {3, 1}, {3, 0}, {0, 1}}) {
                    found = table.getSubtable(code[0], code[1]); if (found != null) { break; }
                }
            }
            if (found == null) { throw new IOException("Missing Unicode cmap"); }
            cmap = found; font = parsed;
        } catch (IOException | RuntimeException | Error error) {
            try { parsed.close(); } catch (IOException cleanup) { error.addSuppressed(cleanup); } throw error;
        }
    }
    /** x/y/width/height/advance, matching the original unhinted, fractional metrics context. */
    public double[] measure(int size, String value, boolean syntheticItalic) throws IOException {
        if (size <= 0 || size > 16384) { throw new UnsupportedOperationException("Font size outside the verified TTF range"); }
        double scale = (double) size / units; float advance = 0; Rectangle2D result = null;
        for (int i = 0; i < value.length();) {
            int codepoint = value.codePointAt(i); i += Character.charCount(codepoint);
            int gid = cmap.getGlyphId(codepoint);
            if (gid == 0 && codepoint != 0) { throw new UnsupportedOperationException("Physical font fallback required for U+" + Integer.toHexString(codepoint)); }
            Rectangle2D box = TrueTypeGlyphBounds.measure(font, gid, scale, syntheticItalic);
            if (!box.isEmpty()) {
                var placed = new Rectangle2D.Double(advance + box.getMinX(), -box.getMaxY(), box.getWidth(), box.getHeight());
                if (result == null) { result = placed; } else { result.add(placed); }
            }
            advance += (float) (font.getAdvanceWidth(gid) * scale);
        }
        if (result == null) { result = new Rectangle2D.Double(); }
        return new double[]{result.getX(), result.getY(), result.getWidth(), result.getHeight(), advance};
    }
    @Override public void close() throws IOException { font.close(); }
}
