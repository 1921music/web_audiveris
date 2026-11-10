package org.audiveris.wasm.compat.test;

import java.nio.file.*;
import java.util.*;
import ij.process.ByteProcessor;
import com.fasterxml.jackson.databind.*;
import org.audiveris.omr.run.*;
import org.audiveris.omr.sheet.*;

/** Original GridBuilder algorithms with the pinned upstream scale class first on the JVM classpath. */
public final class GridOracle {
    private static boolean headers, stems;
    public static void main(String[] args) throws Exception {
        Path scaleOrigin = Path.of(ScaleBuilder.class.getProtectionDomain().getCodeSource().getLocation().toURI()).toRealPath();
        if (!scaleOrigin.equals(Path.of(args[2]).toRealPath())) { throw new AssertionError("Wrong scale reference classpath"); }
        Path bracketOrigin = Path.of(org.audiveris.omr.sig.inter.BracketInter.class.getProtectionDomain().getCodeSource().getLocation().toURI()).toRealPath();
        if (!bracketOrigin.equals(Path.of(args[3]).toRealPath())) { throw new AssertionError("Wrong bracket reference classpath"); }
        var cli = new org.audiveris.omr.CLI("Audiveris"); cli.parseParameters(new String[0]);
        var field = org.audiveris.omr.Main.class.getDeclaredField("cli"); field.setAccessible(true); field.set(null, cli);
        Path samples = Path.of(args[0]), output = Path.of(args[1]); ObjectMapper mapper = new ObjectMapper();
        JsonNode manifest = mapper.readTree(samples.resolve("manifest.json").toFile());
        List<Map<String, Object>> results = new ArrayList<>();
        RecordingSymbolGeometry geometry = null;
        stems = args.length > 4 && args[4].equals("stems");
        headers = stems || (args.length > 4 && args[4].equals("headers"));
        boolean complete = headers || (args.length > 4 && args[4].equals("complete"));
        if (headers && args.length > 5 && !args[5].equals("current")) {
            for (Class<?> type : List.of(org.audiveris.omr.sig.inter.AbstractInter.class, org.audiveris.omr.sig.inter.TimeWholeInter.class,
                    org.audiveris.omr.sig.inter.TimeCustomInter.class, org.audiveris.omr.sheet.clef.ClefBuilder.class, org.audiveris.omr.sheet.key.KeyRoi.class)) {
                Path origin = Path.of(type.getProtectionDomain().getCodeSource().getLocation().toURI()).toRealPath();
                if (!origin.equals(Path.of(args[5]).toRealPath())) { throw new AssertionError("Wrong original header class: " + type); }
            }
        }
        if (stems && args.length > 6) {
            for (Class<?> type : List.of(org.audiveris.omr.sheet.stem.StemScaler.class,
                    org.audiveris.omr.sheet.stem.StemChecker.class, org.audiveris.omr.sheet.stem.VerticalsBuilder.class)) {
                Path origin = Path.of(type.getProtectionDomain().getCodeSource().getLocation().toURI()).toRealPath();
                if (!origin.equals(Path.of(args[6]).toRealPath())) { throw new AssertionError("Wrong original stem class: " + type); }
            }
        }
        if (headers) {
            geometry = new RecordingSymbolGeometry(org.audiveris.omr.ui.symbol.SymbolGeometry.provider());
            org.audiveris.omr.ui.symbol.SymbolGeometry.setProvider(geometry);
            geometry.recordPitchOffsets();
        }
        Path rasters = output.resolveSibling(stems ? "stem-buffers" : "no-staff"); if (complete) { Files.createDirectories(rasters); }
        try {
            JsonNode fixture = compare(samples, "staff", 1024, 300, GridProbe.staffPixels(), mapper, complete, rasters);
            if ((headers ? fixture.path("grid") : fixture).path("staves").size() != 2) { throw new AssertionError("Expected two fixture staves: " + fixture); }
            for (JsonNode page : manifest.get("pages")) {
                String id = page.get("id").asText();
                JsonNode result = compare(samples, id, page.get("width").asInt(), page.get("height").asInt(),
                        Files.readAllBytes(samples.resolve(id + ".binary")), mapper, complete, rasters);
                results.add(Map.of("id", id, "result", result));
                JsonNode logResult = headers && result.has("grid") ? result.get("grid") : result;
                System.out.println("Grid oracle " + id + " staves=" + logResult.path("staves").size() + " rejected=" + result.path("rejected").asBoolean());
            }
            Files.createDirectories(output.getParent());
            mapper.writerWithDefaultPrettyPrinter().writeValue(output.toFile(), Map.of("fixture", fixture, "pages", results));
            Set<Integer> interlines = new TreeSet<>(); interlines.add((headers ? fixture.get("grid") : fixture).get("interline").asInt());
            for (var row : results) {
                JsonNode result = (JsonNode) row.get("result"); if (headers && result.has("grid")) { result = result.get("grid"); } if (result.has("interline")) { interlines.add(result.get("interline").asInt()); }
            }
            List<Map<String, Object>> measurements = new ArrayList<>();
            for (var family : org.audiveris.omr.ui.symbol.MusicFamily.values()) { for (int interline : interlines) {
                var font = org.audiveris.omr.ui.symbol.MusicFont.getBaseFont(family, interline);
                var upper = font.layoutShapeByCode(org.audiveris.omr.glyph.Shape.BRACKET_UPPER_SERIF).getBounds();
                var lower = font.layoutShapeByCode(org.audiveris.omr.glyph.Shape.BRACKET_LOWER_SERIF).getBounds();
                measurements.add(Map.of("family", family.name(), "interline", interline, "dimensions",
                    List.of(upper.getWidth(), upper.getHeight(), lower.getWidth(), lower.getHeight()),
                    "brackets", mapper.readTree(GridProbe.brackets(family.name(), interline))));
            } }
            mapper.writerWithDefaultPrettyPrinter().writeValue(output.resolveSibling("font-measurements.json").toFile(), measurements);
            if (geometry != null) { mapper.writerWithDefaultPrettyPrinter().writeValue(output.resolveSibling("symbol-geometry.json").toFile(), geometry.records()); }
        } finally { org.audiveris.omr.util.OmrExecutors.shutdown(); }
    }
    private static JsonNode compare(Path folder, String id, int width, int height, byte[] binary, ObjectMapper mapper, boolean complete, Path rasters) throws Exception {
        RunTable table = new RunTableFactory(Orientation.VERTICAL).createTable(new ByteProcessor(width, height, binary.clone(), null));
        Book book = new Book(folder.resolve("staff-" + id + ".png")); SheetStub stub = new SheetStub(book, 1); book.addStub(stub);
        JsonNode expected = mapper.readTree(stems ? StemProbe.analyze(new Sheet(stub, table)) : headers ? HeaderProbe.analyze(new Sheet(stub, table)) : GridProbe.analyze(new Sheet(stub, table), complete));
        byte[] expectedPixels = complete && !expected.path("rejected").asBoolean() ? GridProbe.cleanedPixels().clone() : null;
        JsonNode actual = mapper.readTree(stems ? StemProbe.analyze(PixelSheetProbe.create(width, height, binary, id)) : headers ? HeaderProbe.analyze(PixelSheetProbe.create(width, height, binary, id)) : GridProbe.analyze(PixelSheetProbe.create(width, height, binary, id), complete));
        if (expectedPixels != null) {
            if (!Arrays.equals(expectedPixels, GridProbe.cleanedPixels())) { throw new AssertionError("Staff-free pixels differ: " + id); }
            if (!stems && !GridProbe.verifyCleanCache()) { throw new AssertionError("Cache lifecycle"); }
            Files.write(rasters.resolve(id + ".binary"), expectedPixels);
        }
        if (!expected.equals(actual)) { throw new AssertionError("Raw picture changes grid geometry: " + id + "\n" + expected + "\n" + actual); }
        return expected;
    }
}
