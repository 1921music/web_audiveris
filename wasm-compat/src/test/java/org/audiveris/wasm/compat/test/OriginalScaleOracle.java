package org.audiveris.wasm.compat.test;

import java.nio.file.*;
import java.util.*;
import ij.process.ByteProcessor;
import org.audiveris.omr.run.*;
import org.audiveris.omr.sheet.*;
import org.audiveris.omr.step.StepException;
import com.fasterxml.jackson.databind.*;

/** Run with upstream ScaleBuilder first on the classpath, in its own JVM/configuration registry. */
public final class OriginalScaleOracle {
    public static void main(String[] args) throws Exception {
        Path origin = Path.of(ScaleBuilder.class.getProtectionDomain().getCodeSource().getLocation().toURI()).toRealPath();
        if (!origin.equals(Path.of(args[1]).toRealPath())) { throw new AssertionError("Wrong scale oracle classpath: " + origin); }
        org.audiveris.omr.CLI cli = new org.audiveris.omr.CLI("Audiveris"); cli.parseParameters(new String[0]);
        var cliField = org.audiveris.omr.Main.class.getDeclaredField("cli"); cliField.setAccessible(true); cliField.set(null, cli);
        Path folder = Path.of(args[0]); ObjectMapper mapper = new ObjectMapper();
        JsonNode manifest = mapper.readTree(folder.resolve("manifest.json").toFile());
        List<Map<String, Object>> results = new ArrayList<>();
        try {
            for (JsonNode page : manifest.get("pages")) {
                String id = page.get("id").asText(); int width = page.get("width").asInt(), height = page.get("height").asInt();
                byte[] pixels = Files.readAllBytes(folder.resolve(id + ".binary"));
                results.add(measure(folder, id, width, height, pixels, 0, 0, null));
            }
            results.add(measure(folder, "staff", 160, 150, ScaleFixtures.staff(), 0, 0, null));
            results.add(measure(folder, "specified", 160, 150, ScaleFixtures.staff(), 18, 8, null));
            results.add(measure(folder, "blank", 160, 150, ScaleFixtures.blank(), 0, 0, null));
            Scale supplied = new Scale(new Scale.InterlineScale(23, 24, 25), new Scale.LineScale(2, 3, 4), new Scale.BeamScale(11, false), null, null);
            results.add(measure(folder, "supplied", 160, 150, ScaleFixtures.blank(), 0, 0, supplied));
        } finally { org.audiveris.omr.util.OmrExecutors.shutdown(); }
        String name = args.length > 2 ? args[2] : "scale-upstream.json";
        mapper.writerWithDefaultPrettyPrinter().writeValue(folder.resolve(name).toFile(), results);
        if (name.equals("scale-current.json") && !mapper.readTree(folder.resolve("scale-upstream.json").toFile()).equals(mapper.valueToTree(results))) {
            throw new AssertionError("Refactored Sheet path differs from upstream");
        }
    }
    private static Map<String, Object> measure(Path folder, String id, int width, int height, byte[] pixels, int interline, int beam, Scale existing) throws Exception {
        RunTable table = new RunTableFactory(Orientation.VERTICAL).createTable(new ByteProcessor(width, height, pixels, null));
        Book book = new Book(folder.resolve("sample-" + id + ".png").toAbsolutePath());
        SheetStub stub = new SheetStub(book, 1); book.addStub(stub);
        if (interline != 0) { stub.getInterlineSpecificationParam().setSpecific(interline); }
        if (beam != 0) { stub.getBeamSpecificationParam().setSpecific(beam); }
        Sheet sheet = new Sheet(stub, table); if (existing != null) { sheet.setScale(existing); }
        Map<String, Object> result = new LinkedHashMap<>(); result.put("id", id); result.put("sheetId", sheet.getId());
        try {
            Scale scale = new ScaleBuilder(sheet).retrieveScale();
            if (existing != null && scale != existing) { throw new AssertionError("Existing scale replaced"); }
            result.put("result", "ok:" + scale);
        } catch (StepException rejected) { result.put("result", "rejected:" + rejected.getMessage()); }
        result.put("invalid", !stub.isValid()); System.out.println("Scale oracle " + id + " " + result.get("result"));
        return result;
    }
}
