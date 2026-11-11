package org.audiveris.wasm.compat.test;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.awt.Dimension;
import java.awt.Font;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.*;
import org.apache.fontbox.ttf.TTFParser;
import org.apache.fontbox.ttf.TrueTypeFont;
import org.apache.pdfbox.io.RandomAccessReadBuffer;
import org.audiveris.omr.text.FontAttributes;
import org.audiveris.omr.ui.symbol.MusicFont;
import org.audiveris.omr.ui.symbol.OmrFont;
import org.audiveris.omr.ui.symbol.TextFont;

/** JVM-only physical-font resolution for recorded TEXTS queries; no font discovery enters Wasm. */
public final class RecordedTextFontsOracle {
    public static void main(String[] args) throws Exception {
        Path input = Path.of(args[0]), output = Path.of(args[1]); Files.createDirectories(output.resolve("fonts"));
        var json = new ObjectMapper();
        MusicFont.populateAllSymbols();
        Class<?> utilities = Class.forName("sun.font.FontUtilities"), composite = Class.forName("sun.font.CompositeFont"),
                fileFont = Class.forName("sun.font.FileFont");
        var getFont = utilities.getMethod("getFont2D", Font.class);
        var getFile = fileFont.getDeclaredMethod("getPublicFileName"); getFile.setAccessible(true);
        var slotFont = composite.getMethod("getSlotFont", int.class);
        var physicalStyle = Class.forName("sun.font.Font2D").getMethod("getStyle");
        var faces = new LinkedHashMap<Path, TextFontMetricsOracle.Face>();
        var parsed = new LinkedHashMap<Path, TrueTypeFont>();
        var measurements = new ArrayList<TextFontMetricsOracle.Measurement>();
        var syntheticFlags = new ArrayList<Boolean>();
        var bindings = new ArrayList<Map<String, Object>>();
        var unsupported = new ArrayList<Map<String, Object>>();
        var differences = new ArrayList<Map<String, Object>>();
        int eligible = 0, checked = 0, syntheticItalicCount = 0; double maxDifference = 0;
        try {
            for (var entry : json.readTree(input.toFile())) {
                int kind = entry.get("kind").asInt(); if (kind > 1) { continue; } eligible++;
                int flags = entry.get("flags").asInt(), size = entry.get("size").asInt();
                String value = entry.get("value").asText();
                var attrs = new FontAttributes((flags & 1) != 0, (flags & 2) != 0, (flags & 4) != 0,
                        (flags & 8) != 0, (flags & 16) != 0, (flags & 32) != 0);
                TextFont font = TextFont.getBestFont(attrs, size);
                Object font2d = getFont.invoke(null, font), physical = font2d;
                var glyphs = font.createGlyphVector(OmrFont.frc, value);
                var slots = new TreeSet<Integer>();
                if (composite.isInstance(font2d)) {
                    for (int code : glyphs.getGlyphCodes(0, glyphs.getNumGlyphs(), null)) { slots.add(code >>> 24); }
                    if (slots.isEmpty()) { slots.add(0); }
                    if (slots.size() != 1) {
                        unsupported.add(Map.of("query", entry, "reason", "Multiple physical fallback fonts", "slots", slots)); continue;
                    }
                    physical = slotFont.invoke(font2d, slots.first());
                }
                if (!fileFont.isInstance(physical)) {
                    unsupported.add(Map.of("query", entry, "reason", "Font has no physical file")); continue;
                }
                int actualStyle = (int) physicalStyle.invoke(physical);
                boolean syntheticItalic = font.isItalic() && (actualStyle & Font.ITALIC) == 0;
                if (font.isBold() && (actualStyle & Font.BOLD) == 0) {
                    unsupported.add(Map.of("query", entry, "reason", "Synthetic bold is outside this candidate")); continue;
                }
                Path path = Path.of((String) getFile.invoke(physical)).toRealPath();
                if (!faces.containsKey(path)) {
                    byte[] bytes = Files.readAllBytes(path);
                    String digest = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
                    String filename = digest.substring(0, 12) + "-" + path.getFileName(); Files.write(output.resolve("fonts").resolve(filename), bytes);
                    faces.put(path, new TextFontMetricsOracle.Face("Physical" + faces.size(), filename, path.getFileName().toString(), digest));
                    var ttf = new TTFParser().parse(new RandomAccessReadBuffer(bytes)); ttf.setEnableGsub(false); parsed.put(path, ttf);
                }
                double[] actual;
                try { actual = FontBoxMetricsExperiment.measure(parsed.get(path), size, value, false, 2, syntheticItalic); }
                catch (UnsupportedOperationException error) { unsupported.add(Map.of("query", entry, "reason", error.getMessage())); continue; }
                double[] expected = {entry.get("x").asDouble(), entry.get("y").asDouble(), entry.get("width").asDouble(), entry.get("height").asDouble(),
                        kind == 1 ? entry.get("advance").asDouble() : glyphs.getGlyphPosition(glyphs.getNumGlyphs()).getX()};
                double error = 0;
                for (int i = 0; i < 5; i++) { error = Math.max(error, Math.abs(actual[i] - expected[i])); }
                checked++; maxDifference = Math.max(maxDifference, error);
                if (syntheticItalic) { syntheticItalicCount++; }
                if (error != 0) { differences.add(Map.of("query", entry, "expected", expected, "actual", actual)); }
                var face = faces.get(path);
                bindings.add(Map.of("kind", kind, "flags", flags, "size", size, "value", value, "logicalName", font.getFontName(),
                        "face", face.id(), "sourceFile", path.toString(), "requestedStyle", font.getStyle(), "physicalStyle", actualStyle, "syntheticItalic", syntheticItalic));
                var bounds = new TextFontMetricsOracle.Bounds(expected[0], expected[1], expected[2], expected[3]);
                int width = Math.max(1, (int) Math.ceil(expected[2] * 1.15)), height = Math.max(1, (int) Math.ceil(expected[3] * 1.15));
                measurements.add(new TextFontMetricsOracle.Measurement(face.id(), size, value, bounds, expected[4], width, height, font.computeSize(value, new Dimension(width, height))));
                syntheticFlags.add(syntheticItalic);
            }
        } finally { for (var font : parsed.values()) { font.close(); } }
        if (checked == 0) { throw new AssertionError("No recorded text queries checked"); }
        var fixture = json.createObjectNode(); fixture.set("faces", json.valueToTree(faces.values()));
        var ordered = fixture.putArray("measurements");
        for (var face : faces.values()) { for (int i = 0; i < measurements.size(); i++) {
            var item = measurements.get(i); if (item.face().equals(face.id())) {
                com.fasterxml.jackson.databind.node.ObjectNode row = json.valueToTree(item); row.put("syntheticItalic", syntheticFlags.get(i)); ordered.add(row);
            }
        } }
        json.writeValue(output.resolve("jvm-reference.json").toFile(), fixture);
        json.writeValue(output.resolve("bindings.json").toFile(), bindings);
        var styles = new TreeMap<Integer, Map<String, Object>>();
        for (var binding : bindings) {
            int flags = (int) binding.get("flags");
            int key = (flags & 3) | ((flags & 16) != 0 ? 16 : flags & 8);
            Map<String, Object> style = Map.of("flags", key, "face", binding.get("face"),
                    "name", binding.get("logicalName"), "syntheticItalic", binding.get("syntheticItalic"));
            var previous = styles.putIfAbsent(key, style);
            if (previous != null && !previous.equals(style)) { throw new AssertionError("Physical fallback differs within a text style: " + key); }
        }
        json.writeValue(output.resolve("font-resources.json").toFile(), Map.of("faces", faces.values(), "styles", styles.values()));
        var music = json.createArrayNode();
        for (var entry : json.readTree(input.toFile())) { if (entry.get("kind").asInt() >= 2) { music.add(entry); } }
        json.writeValue(output.resolve("music-measurements.json").toFile(), music);
        json.writeValue(output.resolve("comparison.json").toFile(), Map.of("eligibleQueries", eligible, "checkedQueries", checked,
                "faces", faces.size(), "differentQueries", differences.size(), "maxDifference", maxDifference,
                "differences", differences, "unsupported", unsupported, "javaRuntime", System.getProperty("java.runtime.version"), "syntheticItalicQueries", syntheticItalicCount));
        System.out.println("Recorded TEXTS font queries: " + checked + "/" + eligible + ", faces " + faces.size()
                + ", different " + differences.size() + ", unsupported " + unsupported.size() + ", max difference " + maxDifference);
    }
}
