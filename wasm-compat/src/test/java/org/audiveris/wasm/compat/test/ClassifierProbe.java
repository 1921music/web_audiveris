package org.audiveris.wasm.compat.test;

import java.awt.Point;
import java.util.*;
import org.audiveris.omr.classifier.*;
import org.audiveris.omr.glyph.*;
import org.audiveris.omr.run.*;
import org.audiveris.wasm.compat.platform.*;
import org.teavm.jso.JSExport;
import org.teavm.jso.typedarrays.Int8Array;

/** Original connected components, glyph features, normalization and classifier inference. */
public final class ClassifierProbe {
    public static void main(String[] args) { }
    @JSExport public static void configure(Int8Array archive) {
        HeadlessConfiguration.configure(Map.of(), Map.of()); HeadlessClassifierModels.install(archive.copyToJavaArray());
    }
    @JSExport public static String metadata() {
        var model = HeadlessClassifierModels.require().model();
        return model.getInputSize() + ":" + model.getHiddenSize() + ":" + model.getOutputSize() + ":" + model.getEpochsTotal()
                + ":" + model.getLearningRate() + ":" + model.getMomentum() + ":" + model.getLambda();
    }
    @JSExport public static void reinstall(Int8Array archive) { HeadlessClassifierModels.install(archive.copyToJavaArray()); }
    @JSExport public static boolean factoryContract() {
        String[] inputs = {"a", "b"}, outputs = {"x", "y"};
        double[][] hidden = {{0.1, 0.2, 0.3}, {-0.1, -0.2, 0.7}}, out = {{0.4, 0.5, -0.6}, {0.7, -0.8, 0.9}};
        var model = new org.audiveris.omr.math.NeuralNetwork(inputs, outputs, hidden, out, 0.1, 0.9, 0.001, 3);
        double[] before = model.run(new double[]{0.25, -0.75}, null, null);
        inputs[0] = "changed"; outputs[0] = "changed"; hidden[0][0] = 100; out[0][0] = -100;
        if (!Arrays.equals(before, model.run(new double[]{0.25, -0.75}, null, null))
                || !model.getInputLabels()[0].equals("a") || !model.getOutputLabels()[0].equals("x")) {
            throw new AssertionError("Model input arrays were not copied");
        }
        int rejected = 0;
        try { new org.audiveris.omr.math.NeuralNetwork(inputs, outputs, new double[][]{{1}}, out, 0.1, 0.9, 0, 0); }
        catch (IllegalArgumentException expected) { rejected++; }
        hidden[0][0] = Double.NaN;
        try { new org.audiveris.omr.math.NeuralNetwork(inputs, outputs, hidden, out, 0.1, 0.9, 0, 0); }
        catch (IllegalArgumentException expected) { rejected++; }
        if (rejected != 2) { throw new AssertionError("Model dimensions/non-finite weights accepted"); }
        return true;
    }
    @JSExport public static String kernel() { return kernel(HeadlessClassifierModels.require().model()); }
    public static String kernel(org.audiveris.omr.math.NeuralNetwork model) {
        int state = 1741; StringBuilder result = new StringBuilder("[");
        for (int sample = 0; sample < 128; sample++) {
            double[] input = new double[model.getInputSize()];
            for (int i = 0; i < input.length; i++) { state = state * 1664525 + 1013904223; input[i] = (state >>> 1) / 1073741824.0 - 1; }
            double[] output = model.run(input, null, null); if (sample != 0) { result.append(','); } result.append('[');
            for (int i = 0; i < output.length; i++) { if (i != 0) { result.append(','); } result.append(output[i]); }
            result.append(']');
        }
        return result.append(']').toString();
    }
    @JSExport public static String page(int width, int height, Int8Array pixels, int interline, int limit) {
        return classify(width, height, pixels.copyToJavaArray(), interline, limit);
    }
    public static String classify(int width, int height, byte[] pixels, int interline, int limit) {
        if (interline <= 0 || limit <= 0) { throw new IllegalArgumentException("Positive interline and glyph limit required"); }
        var input = GrayInput.takeOwnership(width, height, pixels).takeProcessor();
        var table = new RunTableFactory(Orientation.VERTICAL).createTable(input);
        var glyphs = GlyphFactory.buildGlyphs(table, new Point());
        glyphs.sort(Comparator.comparingInt((Glyph g) -> g.getTop()).thenComparingInt(g -> g.getLeft())
                .thenComparingInt(g -> g.getWidth()).thenComparingInt(g -> g.getHeight()).thenComparingInt(g -> g.getWeight()));
        var classifier = BasicClassifier.getInstance(); var descriptor = new MixGlyphDescriptor();
        StringBuilder json = new StringBuilder("["); int count = 0;
        for (Glyph glyph : glyphs) {
            if (glyph.getWeight() < 20 || glyph.getWidth() < 2 || glyph.getHeight() < 2) { continue; }
            if (count++ != 0) { json.append(','); }
            json.append("{\"bounds\":[").append(glyph.getLeft()).append(',').append(glyph.getTop()).append(',')
                    .append(glyph.getWidth()).append(',').append(glyph.getHeight()).append("],\"weight\":").append(glyph.getWeight());
            json.append(",\"features\":["); double[] features = descriptor.getFeatures(glyph, interline);
            for (int i = 0; i < features.length; i++) { if (i != 0) { json.append(','); } json.append(features[i]); }
            json.append("],\"grades\":["); var evaluations = classifier.getNaturalEvaluations(glyph, interline); int best = 0;
            for (int i = 0; i < evaluations.length; i++) {
                if (i != 0) { json.append(','); } json.append(evaluations[i].grade);
                if (evaluations[i].grade > evaluations[best].grade) { best = i; }
            }
            var sorted = classifier.evaluate(glyph, interline, 3, 0, null);
            json.append("],\"best\":\"").append(evaluations[best].shape.name()).append("\",\"top\":[");
            for (int i = 0; i < sorted.length; i++) {
                if (i != 0) { json.append(','); } json.append('"').append(sorted[i].shape.name()).append('"');
            }
            json.append("]}"); if (count == limit) { break; }
        }
        return json.append(']').toString();
    }
}
