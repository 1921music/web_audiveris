package org.audiveris.wasm.compat.test;

import java.nio.file.*;
import java.util.*;
import com.fasterxml.jackson.databind.*;

/** Compare the actual Sheet path with the previously compiled unmodified scale oracle. */
public final class PixelSheetOracle {
    public static void main(String[] args) throws Exception {
        var cli = new org.audiveris.omr.CLI("Audiveris"); cli.parseParameters(new String[0]);
        var field = org.audiveris.omr.Main.class.getDeclaredField("cli"); field.setAccessible(true); field.set(null, cli);
        if (!PixelSheetProbe.validation()) { throw new AssertionError("Pixel sheet validation"); }
        String fixture = PixelSheetProbe.scaleJava(160, 150, ScaleFixtures.staff(), "staff");
        Path samples = Path.of(args[0]), output = Path.of(args[1]); ObjectMapper mapper = new ObjectMapper();
        JsonNode manifest = mapper.readTree(samples.resolve("manifest.json").toFile());
        JsonNode upstream = mapper.readTree(samples.resolve("scale-upstream.json").toFile());
        List<Map<String, String>> results = new ArrayList<>();
        for (JsonNode page : manifest.get("pages")) {
            String id = page.get("id").asText();
            String actual = PixelSheetProbe.scaleJava(page.get("width").asInt(), page.get("height").asInt(),
                    Files.readAllBytes(samples.resolve(id + ".binary")), id);
            JsonNode reference = null;
            for (JsonNode row : upstream) { if (row.get("id").asText().equals(id)) { reference = row; break; } }
            if (reference == null || !actual.equals(reference.get("result").asText() + ";invalid=" + reference.get("invalid").asBoolean())) {
                throw new AssertionError("Pixel Sheet scale differs from upstream: " + id + " " + actual);
            }
            results.add(Map.of("id", id, "result", actual));
        }
        Files.createDirectories(output.getParent());
        mapper.writerWithDefaultPrettyPrinter().writeValue(output.toFile(), Map.of("fixture", fixture, "pages", results));
        System.out.println("Raw-pixel Sheet scale matches unmodified upstream for " + results.size() + " real pages");
    }
}
