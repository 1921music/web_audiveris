package org.audiveris.wasm.compat.test;

import java.awt.Dimension;
import java.awt.Font;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import org.audiveris.omr.ui.symbol.OmrFont;

/** Research oracle for the synchronous font-fitting boundary, using explicit physical fonts. */
public final class TextFontMetricsOracle {
    public record Bounds(double x, double y, double width, double height) { }
    public record Face(String id, String file, String name, String sha256) { }
    public record Measurement(String face, int size, String text, Bounds bounds, double advance,
                              int targetWidth, int targetHeight, int fittedSize) { }
    public record Fixture(List<Face> faces, List<Measurement> measurements) { }
    private static final class PhysicalFont extends OmrFont { PhysicalFont(Font font) { super(font); } }
    public static void main(String[] args) throws Exception {
        Path directory = Path.of(args[0]), output = Path.of(args[1]); Files.createDirectories(output.resolve("fonts"));
        var faces = new ArrayList<Face>(); var measurements = new ArrayList<Measurement>();
        String[] files = {"LiberationSans-Regular.ttf", "LiberationSans-Bold.ttf", "LiberationSans-Italic.ttf", "LiberationSans-BoldItalic.ttf",
                "LiberationSerif-Regular.ttf", "LiberationSerif-Bold.ttf", "LiberationSerif-Italic.ttf", "LiberationSerif-BoldItalic.ttf",
                "LiberationMono-Regular.ttf", "LiberationMono-Bold.ttf", "LiberationMono-Italic.ttf", "LiberationMono-BoldItalic.ttf"};
        for (String file : files) {
            byte[] bytes = Files.readAllBytes(directory.resolve(file));
            Files.write(output.resolve("fonts").resolve(file), bytes);
            Font base = Font.createFont(Font.TRUETYPE_FONT, new java.io.ByteArrayInputStream(bytes));
            String id = "Physical" + faces.size();
            faces.add(new Face(id, file, base.getFontName(), HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes))));
            for (int size : new int[]{10, 18, 38, 44}) {
                var font = new PhysicalFont(base.deriveFont((float)size));
                for (String text : new String[]{"Allegro", "moderato", "Music", "123", "AVATAR", "ffi", "poco a poco", "Été più"}) {
                    var glyphs = font.createGlyphVector(OmrFont.frc, text); var bounds = glyphs.getVisualBounds();
                    int width = Math.max(1, (int)Math.ceil(bounds.getWidth() * 1.15));
                    int height = Math.max(1, (int)Math.ceil(bounds.getHeight() * 1.15));
                    measurements.add(new Measurement(id, size, text, new Bounds(bounds.getX(), bounds.getY(), bounds.getWidth(), bounds.getHeight()),
                            glyphs.getGlyphPosition(glyphs.getNumGlyphs()).getX(), width, height, font.computeSize(text, new Dimension(width, height))));
                }
            }
        }
        Files.writeString(output.resolve("jvm-reference.json"), OcrTransportOracle.json(new Fixture(faces, measurements)) + "\n");
        System.out.println("Original OmrFont.computeSize: " + measurements.size() + " cases, " + faces.size() + " physical fonts");
    }
}
