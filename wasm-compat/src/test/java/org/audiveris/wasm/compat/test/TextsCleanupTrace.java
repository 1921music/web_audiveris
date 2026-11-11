// SPDX-License-Identifier: AGPL-3.0-or-later
package org.audiveris.wasm.compat.test;

import com.fasterxml.jackson.databind.*;
import ij.process.ByteProcessor;
import java.awt.*;
import java.awt.font.GlyphVector;
import java.awt.geom.*;
import java.awt.image.BufferedImage;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.List;
import java.util.function.Consumer;
import org.audiveris.omr.sheet.*;
import org.audiveris.omr.step.OmrStep;
import org.audiveris.omr.ui.symbol.MusicFont;
import org.audiveris.wasm.compat.platform.GrayPathRaster;
import org.audiveris.wasm.compat.platform.GraySpanRaster;
import org.audiveris.wasm.compat.platform.StrokePaths;

/** JVM-only observation of the unchanged cleaner, checked against saved native pixels. */
public final class TextsCleanupTrace {
    public static void main(String[] args) throws Exception {
        Path samples = Path.of(args[0]), reference = Path.of(args[1]), output = Path.of(args[2]);
        Files.createDirectories(output);
        var json = new ObjectMapper(); var expected = new HashMap<String, JsonNode>();
        var inputs = new TreeMap<String, String>();
        for (String name : List.of("jvm-reference.json", "jvm-rejections.json")) {
            for (JsonNode row : json.readTree(read(reference.resolve(name), "native/" + name, inputs))) {
                expected.put(row.get("id").asText(), row.get("result"));
            }
        }
        var cli = new org.audiveris.omr.CLI("Audiveris"); cli.parseParameters(new String[0]);
        var field = org.audiveris.omr.Main.class.getDeclaredField("cli"); field.setAccessible(true); field.set(null, cli);
        MusicFont.populateAllSymbols();
        Class<?> cleaner = Class.forName("org.audiveris.omr.text.SheetScanner$TextsCleaner");
        var constructor = cleaner.getDeclaredConstructor(ByteProcessor.class, Graphics2D.class, Sheet.class);
        var erase = cleaner.getDeclaredMethod("eraseInters"); constructor.setAccessible(true); erase.setAccessible(true);
        var pages = new ArrayList<Object>(); var rejected = new ArrayList<String>(); long comparedPixels = 0;
        for (JsonNode page : json.readTree(read(samples.resolve("manifest.json"), "samples/manifest.json", inputs)).get("pages")) {
            String id = page.get("id").asText(); int width = page.get("width").asInt(), height = page.get("height").asInt();
            Sheet sheet = PixelSheetProbe.create(width, height, read(samples.resolve(id + ".binary"), "samples/" + id + ".binary", inputs), id);
            JsonNode prefix = json.readTree(CueBeamsProbe.analyze(sheet)), original = expected.get(id);
            if (original == null) { throw new AssertionError("Missing native reference: " + id); }
            for (var fields = prefix.fields(); fields.hasNext();) {
                var entry = fields.next();
                if (!entry.getValue().equals(original.get(entry.getKey()))) { throw new AssertionError("Changed CUE prefix: " + id + "/" + entry.getKey()); }
            }
            if (prefix.path("rejected").asBoolean()) { rejected.add(id); continue; }
            sheet.getStub().done(OmrStep.CUE_BEAMS);
            BufferedImage image = sheet.getPicture().getSource(Picture.SourceKey.NO_STAFF).getBufferedImage();
            Trace[] holder = new Trace[1];
            ByteProcessor buffer = new ByteProcessor(image) {
                @Override public void threshold(int level) {
                    super.threshold(level);
                    Trace trace = holder[0];
                    for (int i = 0; i < trace.raster.length; i++) { trace.raster[i] = (byte) ((trace.raster[i] & 255) <= level ? 0 : 255); }
                    for (int i = 0; i < trace.normalizedRaster.length; i++) { trace.normalizedRaster[i] = (byte) ((trace.normalizedRaster[i] & 255) <= level ? 0 : 255); }
                    for (int i = 0; i < trace.spanRaster.length; i++) { trace.spanRaster[i] = (byte) ((trace.spanRaster[i] & 255) <= level ? 0 : 255); }
                    trace.thresholdDifferences = differences((byte[]) getPixels(), trace.raster);
                    trace.normalizedThresholdDifferences = differences((byte[]) getPixels(), trace.normalizedRaster);
                    trace.spanThresholdDifferences = differences((byte[]) getPixels(), trace.spanRaster);
                }
            };
            BufferedImage replay = new ByteProcessor(width, height, ((byte[]) buffer.getPixels()).clone(), null).getBufferedImage();
            var trace = new Trace(replay.createGraphics(), width, height, ((byte[]) buffer.getPixels()).clone()); holder[0] = trace;
            var graphics = new Recorder(image.createGraphics(), trace);
            try { erase.invoke(constructor.newInstance(buffer, graphics, sheet)); }
            finally { graphics.dispose(); trace.replay.dispose(); }
            if (trace.thresholdDifferences < 0) { throw new AssertionError("Cleaner threshold was not observed"); }
            byte[] actual = (byte[]) buffer.getPixels(), saved = read(reference.resolve(id).resolve("cleaned.gray"), "native/" + id + "/cleaned.gray", inputs);
            if (!Arrays.equals(actual, saved)) { throw new AssertionError("Original cleanup changed: " + id); }
            // The original cleaner thresholds before its final glyph pass; replay the same threshold once here.
            ByteProcessor replayed = new ByteProcessor(replay); replayed.threshold(127);
            if (!Arrays.equals(actual, (byte[]) replayed.getPixels())) { throw new AssertionError("Unrecorded cleanup drawing: " + id); }
            comparedPixels += actual.length;
            pages.add(Map.of("id", id, "width", width, "height", height, "pixels", actual.length,
                    "cleanedSha256", HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(actual)),
                    "operations", trace.counts, "strokeStates", trace.strokes, "fonts", trace.fonts,
                    "examples", trace.examples, "rasterCandidates", Map.of("thinFill", Map.of("beforeGlyphPassDifferentPixels", trace.thresholdDifferences,
                            "finalDifferentPixels", differences(actual, trace.raster)), "quarterSnappedFill", Map.of(
                            "beforeGlyphPassDifferentPixels", trace.normalizedThresholdDifferences, "finalDifferentPixels", differences(actual, trace.normalizedRaster)),
                            "spanFill", Map.of("beforeGlyphPassDifferentPixels", trace.spanThresholdDifferences, "finalDifferentPixels", differences(actual, trace.spanRaster)),
                            "unsupportedOperations", trace.unsupported)));
            System.out.println("Original TEXTS cleanup and recorded operations match native pixels: " + id + "; " + trace.counts
                    + "; thin-fill candidate differences before glyph pass/final: " + trace.thresholdDifferences + "/" + differences(actual, trace.raster)
                    + "; quarter-snapped fill: " + trace.normalizedThresholdDifferences + "/" + differences(actual, trace.normalizedRaster)
                    + "; span fill: " + trace.spanThresholdDifferences + "/" + differences(actual, trace.spanRaster));
        }
        if (pages.isEmpty()) { throw new AssertionError("No cleanup page tested"); }
        json.writerWithDefaultPrettyPrinter().writeValue(output.resolve("verification.json").toFile(), Map.of(
                "pages", pages, "rejectedBeforeTexts", rejected, "comparedPixels", comparedPixels,
                "originalCleanupPixelsExact", true, "recordedOperationsReplayExact", true,
                "inputSha256", inputs,
                "javaRuntime", System.getProperty("java.runtime.version"),
                "limitation", "JVM observation and native replay; the thin-fill candidate replays native drawing decisions and is diagnostic, not a portable cleaner"));
    }

    private static byte[] read(Path path, String key, Map<String, String> inputs) throws Exception {
        byte[] bytes = Files.readAllBytes(path); inputs.put(key, HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes))); return bytes;
    }

    private static int differences(byte[] a, byte[] b) {
        int count = 0; for (int i = 0; i < a.length; i++) { if (a[i] != b[i]) { count++; } } return count;
    }

    /** Diagnostic endpoint snapping, including control-point adjustment; not a span rasterizer. */
    private static Path2D.Float quarterSnapped(Shape shape) {
        var iterator = shape.getPathIterator(null); var path = new Path2D.Float(iterator.getWindingRule());
        float[] coords = new float[6]; float dx = 0, dy = 0, moveDx = 0, moveDy = 0;
        while (!iterator.isDone()) {
            int type = iterator.currentSegment(coords);
            if (type == PathIterator.SEG_CLOSE) { path.closePath(); dx = moveDx; dy = moveDy; iterator.next(); continue; }
            int end = switch (type) { case PathIterator.SEG_QUADTO -> 2; case PathIterator.SEG_CUBICTO -> 4; default -> 0; };
            float x = (float) Math.floor(coords[end] + 0.25f) + 0.25f, y = (float) Math.floor(coords[end + 1] + 0.25f) + 0.25f;
            float nx = x - coords[end], ny = y - coords[end + 1]; coords[end] = x; coords[end + 1] = y;
            switch (type) {
                case PathIterator.SEG_MOVETO -> { path.moveTo(x, y); moveDx = nx; moveDy = ny; }
                case PathIterator.SEG_LINETO -> path.lineTo(x, y);
                case PathIterator.SEG_QUADTO -> path.quadTo(coords[0] + (dx + nx) / 2, coords[1] + (dy + ny) / 2, x, y);
                case PathIterator.SEG_CUBICTO -> path.curveTo(coords[0] + dx, coords[1] + dy, coords[2] + nx, coords[3] + ny, x, y);
                default -> throw new AssertionError("Unexpected path segment");
            }
            dx = nx; dy = ny; iterator.next();
        }
        return path;
    }

    private static final class Trace {
        final Graphics2D replay;
        final int width, height; final byte[] raster, normalizedRaster, spanRaster;
        int thresholdDifferences = -1, normalizedThresholdDifferences = -1, spanThresholdDifferences = -1, unsupported;
        final Map<String, Integer> counts = new TreeMap<>(), strokes = new TreeMap<>(), fonts = new TreeMap<>();
        final Map<String, Object> examples = new TreeMap<>();
        Trace(Graphics2D replay, int width, int height, byte[] raster) {
            this.replay = replay; this.width = width; this.height = height; this.raster = raster; normalizedRaster = raster.clone(); spanRaster = raster.clone();
        }
    }
    private static final class Recorder extends GraphicsDelegate {
        private final Trace trace;
        Recorder(Graphics2D delegate, Trace trace) { super(delegate); this.trace = trace; }
        @Override public Graphics create() { return new Recorder((Graphics2D) delegate.create(), trace); }
        private void paint(String kind, Shape shape, Consumer<Graphics2D> action) {
            if (!Color.WHITE.equals(delegate.getColor())) { throw new AssertionError("Unexpected cleanup color"); }
            trace.counts.merge(kind, 1, Integer::sum);
            BasicStroke stroke = (BasicStroke) delegate.getStroke();
            trace.strokes.merge(kind + ":width=" + stroke.getLineWidth() + ":cap=" + stroke.getEndCap()
                    + ":join=" + stroke.getLineJoin() + ":normalize=" + delegate.getRenderingHint(RenderingHints.KEY_STROKE_CONTROL), 1, Integer::sum);
            if (!delegate.getTransform().isIdentity() || delegate.getClip() != null || shape == null || kind.equals("drawGlyphVector")) {
                trace.unsupported++;
            } else if (kind.equals("fillRect")) {
                Rectangle box = (Rectangle) shape;
                int x0 = (int) Math.max(0, Math.min(trace.width, (long) box.x)), y0 = (int) Math.max(0, Math.min(trace.height, (long) box.y));
                int x1 = (int) Math.max(x0, Math.min(trace.width, (long) box.x + Math.max(0, box.width)));
                int y1 = (int) Math.max(y0, Math.min(trace.height, (long) box.y + Math.max(0, box.height)));
                for (int y = y0; y < y1; y++) {
                    Arrays.fill(trace.raster, y * trace.width + x0, y * trace.width + x1, (byte) 255);
                    Arrays.fill(trace.normalizedRaster, y * trace.width + x0, y * trace.width + x1, (byte) 255);
                    Arrays.fill(trace.spanRaster, y * trace.width + x0, y * trace.width + x1, (byte) 255);
                }
            } else {
                Path2D.Float path = kind.startsWith("draw") ? StrokePaths.normalized(shape, stroke) : new Path2D.Float(shape);
                GrayPathRaster.fill(trace.raster, trace.width, trace.height, path);
                GrayPathRaster.fill(trace.normalizedRaster, trace.width, trace.height,
                        kind.startsWith("fill") && stroke.getLineWidth() > 1 ? quarterSnapped(shape) : path);
                if (kind.startsWith("draw")) { GraySpanRaster.fill(trace.spanRaster, trace.width, trace.height, path, false); }
                else if (stroke.getLineWidth() > 1) { GraySpanRaster.fill(trace.spanRaster, trace.width, trace.height, shape, true); }
                else { GrayPathRaster.fill(trace.spanRaster, trace.width, trace.height, path); }
            }
            if (shape != null && !trace.examples.containsKey(kind)) {
                var segments = new ArrayList<double[]>(); var iterator = shape.getPathIterator(null); double[] coords = new double[6];
                while (!iterator.isDone()) {
                    int type = iterator.currentSegment(coords), count = switch (type) { case 0, 1 -> 2; case 2 -> 4; case 3 -> 6; default -> 0; };
                    double[] row = new double[count + 1]; row[0] = type; System.arraycopy(coords, 0, row, 1, count); segments.add(row); iterator.next();
                }
                double[] matrix = new double[6]; delegate.getTransform().getMatrix(matrix);
                trace.examples.put(kind, Map.of("winding", iterator.getWindingRule(), "segments", segments, "transform", matrix));
            }
            Graphics2D target = trace.replay;
            target.setTransform(delegate.getTransform()); target.setClip(delegate.getClip());
            target.setRenderingHints(delegate.getRenderingHints()); target.setComposite(delegate.getComposite());
            target.setPaint(delegate.getPaint()); target.setStroke(stroke); target.setFont(delegate.getFont());
            action.accept(target);
        }
        @Override public void fill(Shape shape) {
            paint("fill:" + shape.getClass().getName(), shape, g -> g.fill(shape)); delegate.fill(shape);
        }
        @Override public void draw(Shape shape) {
            paint("draw:" + shape.getClass().getName(), shape, g -> g.draw(shape)); delegate.draw(shape);
        }
        @Override public void fillRect(int x, int y, int width, int height) {
            paint("fillRect", new Rectangle(x, y, width, height), g -> g.fillRect(x, y, width, height)); delegate.fillRect(x, y, width, height);
        }
        @Override public void drawLine(int x1, int y1, int x2, int y2) {
            paint("drawLine", new Line2D.Double(x1, y1, x2, y2), g -> g.drawLine(x1, y1, x2, y2)); delegate.drawLine(x1, y1, x2, y2);
        }
        @Override public void drawGlyphVector(GlyphVector glyphs, float x, float y) {
            trace.fonts.merge(glyphs.getFont().getFontName(), glyphs.getNumGlyphs(), Integer::sum);
            paint("drawGlyphVector", glyphs.getOutline(x, y), g -> g.drawGlyphVector(glyphs, x, y)); delegate.drawGlyphVector(glyphs, x, y);
        }
        @Override public void drawString(String value, float x, float y) {
            paint("drawString", null, g -> g.drawString(value, x, y)); delegate.drawString(value, x, y);
        }
        @Override public void drawString(String value, int x, int y) {
            paint("drawStringInt", null, g -> g.drawString(value, x, y)); delegate.drawString(value, x, y);
        }
    }
}
