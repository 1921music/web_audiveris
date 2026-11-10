package org.audiveris.wasm.compat.test;

import java.nio.file.*;
import java.util.*;
import ij.process.ByteProcessor;
import com.fasterxml.jackson.databind.*;
import org.audiveris.omr.run.*;
import org.audiveris.omr.sheet.*;

/** Unchanged staff algorithms with the pinned upstream scale class first on the JVM classpath. */
public final class StaffOracle {
    public static void main(String[] args) throws Exception {
        Path scaleOrigin = Path.of(ScaleBuilder.class.getProtectionDomain().getCodeSource().getLocation().toURI()).toRealPath();
        if (!scaleOrigin.equals(Path.of(args[2]).toRealPath())) { throw new AssertionError("Wrong scale reference classpath"); }
        var cli = new org.audiveris.omr.CLI("Audiveris"); cli.parseParameters(new String[0]);
        var field = org.audiveris.omr.Main.class.getDeclaredField("cli"); field.setAccessible(true); field.set(null, cli);
        Path samples = Path.of(args[0]), output = Path.of(args[1]); ObjectMapper mapper = new ObjectMapper();
        JsonNode manifest = mapper.readTree(samples.resolve("manifest.json").toFile());
        List<Map<String, Object>> results = new ArrayList<>();
        try {
            JsonNode fixture = compare(samples, "staff", 1024, 300, StaffProbe.staffPixels(), mapper);
            if (fixture.path("staves").size() != 2) { throw new AssertionError("Expected two fixture staves: " + fixture); }
            for (JsonNode page : manifest.get("pages")) {
                String id = page.get("id").asText();
                JsonNode result = compare(samples, id, page.get("width").asInt(), page.get("height").asInt(),
                        Files.readAllBytes(samples.resolve(id + ".binary")), mapper);
                results.add(Map.of("id", id, "result", result));
                System.out.println("Staff oracle " + id + " staves=" + result.path("staves").size() + " rejected=" + result.path("rejected").asBoolean());
            }
            Files.createDirectories(output.getParent());
            mapper.writerWithDefaultPrettyPrinter().writeValue(output.toFile(), Map.of("fixture", fixture, "pages", results));
        } finally { org.audiveris.omr.util.OmrExecutors.shutdown(); }
    }
    private static JsonNode compare(Path folder, String id, int width, int height, byte[] binary, ObjectMapper mapper) throws Exception {
        RunTable table = new RunTableFactory(Orientation.VERTICAL).createTable(new ByteProcessor(width, height, binary.clone(), null));
        Book book = new Book(folder.resolve("staff-" + id + ".png")); SheetStub stub = new SheetStub(book, 1); book.addStub(stub);
        JsonNode expected = mapper.readTree(StaffProbe.analyze(new Sheet(stub, table)));
        JsonNode actual = mapper.readTree(StaffProbe.staffJava(width, height, binary, id));
        if (!expected.equals(actual)) { throw new AssertionError("Raw picture changes staff geometry: " + id + "\n" + expected + "\n" + actual); }
        return expected;
    }
}
