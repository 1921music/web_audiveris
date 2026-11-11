package org.audiveris.wasm.compat.test;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.awt.geom.Rectangle2D;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.apache.fontbox.ttf.TTFParser;
import org.apache.fontbox.ttf.TrueTypeFont;
import org.apache.fontbox.ttf.GlyfCompositeDescript;
import org.apache.fontbox.ttf.CmapSubtable;
import org.apache.pdfbox.io.RandomAccessReadBuffer;

/** Diagnostic only: determine whether unhinted TTF bounds reproduce Java2D font fitting. */
public final class FontBoxMetricsExperiment {
    public record Difference(String face, int size, String text, double expected,
                             double actual, String field) { }
    public record Candidate(String method, int cases, int boundsDifferent, int advancesDifferent, int fittedSizesDifferent,
                            int sweptTargets, int sweptSizesDifferent, int maxSweptSizeDifference,
                            double maxBoundsDifference, double maxAdvanceDifference,
                            List<Difference> examples) { }

    public static void main(String[] args) throws Exception {
        Path directory = Path.of(args[0]);
        var fixture = new ObjectMapper().readValue(directory.resolve("jvm-reference.json").toFile(),
                TextFontMetricsOracle.Fixture.class);
        var reports = new ArrayList<Candidate>();
        for (boolean pathBounds : new boolean[]{false, true}) {
            for (int roundingMode : new int[]{0, 1, 2}) {
                int cases = 0, different = 0, advances = 0, fittedSizes = 0, sweptTargets = 0, sweptDifferences = 0, maxSweptDifference = 0;
                double maxBounds = 0, maxAdvance = 0;
                var examples = new ArrayList<Difference>();
                for (var face : fixture.faces()) {
                    try (var font = new TTFParser().parse(new RandomAccessReadBuffer(
                            Files.readAllBytes(directory.resolve("fonts").resolve(face.file()))))) {
                        font.setEnableGsub(false);
                        for (var item : fixture.measurements()) {
                            if (!item.face().equals(face.id())) { continue; }
                            double[] actual = measure(font, item.size(), item.text(), pathBounds, roundingMode);
                            double[] expected = {item.bounds().x(), item.bounds().y(), item.bounds().width(),
                                    item.bounds().height(), item.advance()};
                            float ratio = item.targetWidth() >= item.targetHeight()
                                    ? item.targetWidth() / (float) actual[2] : item.targetHeight() / (float) actual[3];
                            if ((int) Math.rint(ratio * item.size()) != item.fittedSize()) { fittedSizes++; }
                            // A single 1.15x target misses rounding thresholds. Sweep both
                            // fitting axes to expose small geometric errors that change size.
                            for (int axis : new int[]{2, 3}) {
                                for (int target = 1; target <= 256; target++) {
                                    int expectedSize = (int) Math.rint((target / (float) expected[axis]) * item.size());
                                    int actualSize = (int) Math.rint((target / (float) actual[axis]) * item.size());
                                    int error = Math.abs(expectedSize - actualSize); sweptTargets++;
                                    if (error != 0) { sweptDifferences++; maxSweptDifference = Math.max(maxSweptDifference, error); }
                                }
                            }
                            boolean differs = false;
                            for (int i = 0; i < 5; i++) {
                                double delta = Math.abs(actual[i] - expected[i]);
                                if (i < 4) { maxBounds = Math.max(maxBounds, delta); differs |= delta != 0; }
                                else { maxAdvance = Math.max(maxAdvance, delta); if (delta != 0) { advances++; } }
                                if (delta != 0 && examples.size() < 12) {
                                    examples.add(new Difference(face.file(), item.size(), item.text(),
                                            expected[i], actual[i], new String[]{"x", "y", "width", "height", "advance"}[i]));
                                }
                            }
                            cases++; if (differs) { different++; }
                        }
                    }
                }
                reports.add(new Candidate((pathBounds ? "path" : "header") + new String[]{"-raw", "-1/64", "-components-1/64"}[roundingMode],
                        cases, different, advances, fittedSizes, sweptTargets, sweptDifferences, maxSweptDifference,
                        maxBounds, maxAdvance, examples));
            }
        }
        String json = OcrTransportOracle.json(reports);
        Files.writeString(directory.resolve("fontbox-experiment.json"), json + "\n");
        System.out.println(json);
    }

    static double[] measure(TrueTypeFont font, int size, String text,
                                    boolean pathBounds, int roundingMode) throws Exception {
        var cmap = unicodeCmap(font);
        double scale = (double) size / font.getUnitsPerEm();
        float advance = 0;
        Rectangle2D result = null;
        for (int i = 0; i < text.length();) {
            int codepoint = text.codePointAt(i); i += Character.charCount(codepoint);
            int gid = cmap.getGlyphId(codepoint);
            var glyph = font.getGlyph().getGlyph(gid);
            if (glyph != null && glyph.getNumberOfContours() != 0) {
                Rectangle2D box = glyphBounds(font, gid, scale, pathBounds, roundingMode, 0);
                double x1 = box.getMinX(), x2 = box.getMaxX();
                double y1 = -box.getMaxY(), y2 = -box.getMinY();
                var placed = new Rectangle2D.Double(advance + x1, y1, x2 - x1, y2 - y1);
                if (result == null) { result = placed; } else { result.add(placed); }
            }
            advance += (float) (font.getAdvanceWidth(gid) * scale);
        }
        if (result == null) { result = new Rectangle2D.Double(); }
        return new double[]{result.getX(), result.getY(), result.getWidth(), result.getHeight(), advance};
    }

    /** Same strict Unicode subtable preference as FontBox, without the unused GSUB wrapper. */
    private static CmapSubtable unicodeCmap(TrueTypeFont font) throws Exception {
        var table = font.getCmap();
        if (table == null) { throw new java.io.IOException("Missing Unicode cmap"); }
        for (int[] code : new int[][]{{0, 4}, {3, 10}, {0, 3}, {3, 1}, {3, 0}, {0, 1}}) {
            var subtable = table.getSubtable(code[0], code[1]);
            if (subtable != null) { return subtable; }
        }
        throw new java.io.IOException("Missing Unicode cmap");
    }

    private static Rectangle2D glyphBounds(TrueTypeFont font, int gid, double scale,
                                          boolean pathBounds, int mode, int depth) throws Exception {
        if (depth > 16) { throw new IllegalArgumentException("Composite font recursion"); }
        var glyph = font.getGlyph().getGlyph(gid);
        if (mode == 2 && glyph.getDescription() instanceof GlyfCompositeDescript composite) {
            Rectangle2D result = null;
            for (var component : composite.getComponents()) {
                if (component.getXScale() != 1 || component.getYScale() != 1
                        || component.getScale01() != 0 || component.getScale10() != 0
                        || (component.getFlags() & 2) == 0) {
                    throw new UnsupportedOperationException("Research candidate supports translated components only");
                }
                var bounds = glyphBounds(font, component.getGlyphIndex(), scale, pathBounds, mode, depth + 1);
                var placed = new Rectangle2D.Double(bounds.getX() + round(component.getXTranslate() * scale),
                        bounds.getY() + round(component.getYTranslate() * scale), bounds.getWidth(), bounds.getHeight());
                if (result == null) { result = placed; } else { result.add(placed); }
            }
            return result == null ? new Rectangle2D.Double() : result;
        }
        Rectangle2D bounds = pathBounds ? glyph.getPath().getBounds2D()
                : new Rectangle2D.Double(glyph.getXMinimum(), glyph.getYMinimum(),
                        glyph.getXMaximum() - glyph.getXMinimum(), glyph.getYMaximum() - glyph.getYMinimum());
        double x1 = bounds.getMinX() * scale, y1 = bounds.getMinY() * scale,
                x2 = bounds.getMaxX() * scale, y2 = bounds.getMaxY() * scale;
        if (mode != 0) { x1 = round(x1); y1 = round(y1); x2 = round(x2); y2 = round(y2); }
        return new Rectangle2D.Double(x1, y1, x2 - x1, y2 - y1);
    }

    private static double round(double value) {
        return Math.copySign(Math.floor(Math.abs(value) * 64 + 0.5) / 64, value);
    }
}
