package org.audiveris.wasm.compat.test;

import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import com.fasterxml.jackson.databind.*;
import org.audiveris.omr.classifier.*;
import org.audiveris.omr.math.NeuralNetwork;
import org.audiveris.wasm.compat.platform.HeadlessClassifierModels;

/** Pinned original classifier/JAXB reference, or a separate JVM check of the portable loader. */
public final class ClassifierOracle {
    public static void main(String[] args) throws Exception {
        boolean portable = args[3].equals("portable"); Path grid = Path.of(args[0]), output = Path.of(args[1]);
        if (!portable) {
            Path origin = Path.of(NeuralNetwork.class.getProtectionDomain().getCodeSource().getLocation().toURI()).toRealPath();
            if (!origin.equals(Path.of(args[2]).toRealPath())) { throw new AssertionError("Wrong original neural class"); }
        }
        var cli = new org.audiveris.omr.CLI("Audiveris"); cli.parseParameters(new String[0]);
        var cliField = org.audiveris.omr.Main.class.getDeclaredField("cli"); cliField.setAccessible(true); cliField.set(null, cli);
        BasicClassifier classifier = BasicClassifier.getInstance();
        var networkField = BasicClassifier.class.getDeclaredField("network"); networkField.setAccessible(true);
        if (portable) {
            if (!ClassifierProbe.factoryContract()) { throw new AssertionError("Factory contract"); }
            malformedModels(Path.of(args[4]), output.getParent());
            HeadlessClassifierModels.install(Files.readAllBytes(Path.of(args[4])));
            networkField.set(classifier, WorkerClassifierBridge.load(classifier, new String[]{BasicClassifier.MODEL_FILE_NAME}));
        }
        Object network = networkField.get(classifier); var modelField = network.getClass().getField("model"); modelField.setAccessible(true);
        NeuralNetwork model = (NeuralNetwork) modelField.get(network);
        var normsField = network.getClass().getField("norms"); normsField.setAccessible(true);
        BasicNorms norms = (BasicNorms) normsField.get(network);
        ObjectMapper mapper = new ObjectMapper(); List<Map<String, Object>> pages = new ArrayList<>();
        try {
            String metadata = model.getInputSize() + ":" + model.getHiddenSize() + ":" + model.getOutputSize() + ":" + model.getEpochsTotal()
                    + ":" + model.getLearningRate() + ":" + model.getMomentum() + ":" + model.getLambda();
            String weights = weightHash(model);
            JsonNode gridReference = mapper.readTree(grid.resolve("jvm-reference.json").toFile());
            pages.add(page(grid, "staff", 1024, 300, 12, mapper));
            for (JsonNode page : gridReference.get("pages")) {
                JsonNode result = page.get("result"); if (result.path("rejected").asBoolean()) { continue; }
                // Accepted real samples have dimensions recorded by the original pixel oracle.
                JsonNode manifest = mapper.readTree(grid.resolve("../pixel-probe/samples/manifest.json").normalize().toFile());
                String id = page.get("id").asText();
                for (JsonNode input : manifest.get("pages")) { if (input.get("id").asText().equals(id)) {
                    pages.add(page(grid, id, input.get("width").asInt(), input.get("height").asInt(), result.get("interline").asInt(), mapper));
                } }
            }
            MessageDigest normDigest = MessageDigest.getInstance("SHA-256");
            for (var vector : List.of(norms.means, norms.stds)) { for (int i = 0; i < model.getInputSize(); i++) {
                long bits = Double.doubleToLongBits(vector.getDouble(i));
                for (int b = 7; b >= 0; b--) { normDigest.update((byte) (bits >>> (b * 8))); }
            } }
            Map<String, Object> result = Map.of("metadata", metadata, "weightSha256", weights,
                "normSha256", HexFormat.of().formatHex(normDigest.digest()),
                "kernel", mapper.readTree(ClassifierProbe.kernel(model)), "pages", pages);
            Files.createDirectories(output.getParent());
            mapper.writerWithDefaultPrettyPrinter().writeValue(output.toFile(), result);
            System.out.println((portable ? "Portable" : "Pinned original") + " classifier: " + metadata + "; " + pages.size() + " pixel inputs");
        } finally { org.audiveris.omr.util.OmrExecutors.shutdown(); }
    }
    private static Map<String, Object> page(Path grid, String id, int width, int height, int interline, ObjectMapper mapper) throws Exception {
        byte[] pixels = Files.readAllBytes(grid.resolve("no-staff/" + id + ".binary"));
        var result = mapper.readTree(ClassifierProbe.classify(width, height, pixels, interline, 32));
        if (result.isEmpty()) { throw new AssertionError("No classifier fixtures: " + id); }
        return Map.of("id", id, "width", width, "height", height, "interline", interline, "glyphLimit", 32,
            "sha256", HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(pixels)), "result", result);
    }
    private static String weightHash(NeuralNetwork model) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        for (String name : new String[]{"hiddenWeights", "outputWeights"}) {
            var field = NeuralNetwork.class.getDeclaredField(name); field.setAccessible(true);
            for (double[] row : (double[][]) field.get(model)) { for (double value : row) {
                long bits = Double.doubleToLongBits(value); for (int i = 7; i >= 0; i--) { digest.update((byte) (bits >>> (i * 8))); }
            } }
        }
        return HexFormat.of().formatHex(digest.digest());
    }
    private static void malformedModels(Path source, Path output) throws Exception {
        Files.createDirectories(output);
        for (String variant : new String[]{"stds", "dimensions"}) {
            try (var zip = new java.util.zip.ZipFile(source.toFile());
                    var target = new java.util.zip.ZipOutputStream(Files.newOutputStream(output.resolve("bad-" + variant + ".zip")))) {
                for (var entry : Collections.list(zip.entries())) {
                    byte[] bytes;
                    try (var input = zip.getInputStream(entry)) { bytes = input.readAllBytes(); }
                    if (variant.equals("stds") && entry.getName().equals("stds.xml")) {
                        bytes = new String(bytes, java.nio.charset.StandardCharsets.UTF_8)
                            .replaceFirst("<value>[^<]+</value>", "<value>0</value>").getBytes(java.nio.charset.StandardCharsets.UTF_8);
                    }
                    if (variant.equals("dimensions") && entry.getName().equals("model.xml")) {
                        bytes = new String(bytes, java.nio.charset.StandardCharsets.UTF_8)
                            .replaceFirst("input-size=\"110\"", "input-size=\"111\"").getBytes(java.nio.charset.StandardCharsets.UTF_8);
                    }
                    target.putNextEntry(new java.util.zip.ZipEntry(entry.getName())); target.write(bytes); target.closeEntry();
                }
            }
        }
    }
}
