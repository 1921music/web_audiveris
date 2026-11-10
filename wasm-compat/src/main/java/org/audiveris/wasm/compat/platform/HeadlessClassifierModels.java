// SPDX-License-Identifier: AGPL-3.0-or-later
package org.audiveris.wasm.compat.platform;

import java.io.*;
import java.util.*;
import java.util.zip.*;
import org.kxml2.io.KXmlParser;
import org.xmlpull.v1.XmlPullParser;
import org.audiveris.omr.classifier.BasicNorms;
import org.audiveris.omr.math.NeuralNetwork;
import org.audiveris.omr.math.PoorManAlgebra.Nd4j;

/** Explicit host model asset: parse once per Worker; no filesystem or reflective XML binding. */
public final class HeadlessClassifierModels {
    public record Data(NeuralNetwork model, BasicNorms norms) { }
    private static Data installed;
    private static boolean frozen;
    private HeadlessClassifierModels() { }

    public static void install(byte[] archive) {
        if (frozen) { throw new IllegalStateException("Classifier model is frozen"); }
        Objects.requireNonNull(archive);
        NeuralNetwork model = null; double[] means = null, stds = null; Set<String> seen = new HashSet<>();
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(archive))) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                String name = entry.getName();
                if (Set.of("model.xml", "means.xml", "stds.xml").contains(name)) {
                    if (!seen.add(name)) { throw new IllegalArgumentException("Duplicate classifier entry: " + name); }
                    KXmlParser parser = parser(zip);
                    if (name.equals("model.xml")) { model = model(parser); }
                    else if (name.equals("means.xml")) { means = vector(parser); }
                    else { stds = vector(parser); }
                    finish(parser);
                }
                zip.closeEntry();
            }
        } catch (IOException | org.xmlpull.v1.XmlPullParserException failure) {
            throw new IllegalArgumentException("Invalid classifier archive", failure);
        }
        if (model == null || means == null || stds == null || means.length != model.getInputSize()
                || stds.length != means.length) { throw new IllegalArgumentException("Missing or inconsistent classifier model/norms"); }
        for (int i = 0; i < means.length; i++) {
            if (!Double.isFinite(means[i]) || !Double.isFinite(stds[i]) || stds[i] <= 0) {
                throw new IllegalArgumentException("Invalid classifier normalization");
            }
        }
        // Publish only a complete model. Parsing retains no reference to the caller's bytes.
        installed = new Data(model, new BasicNorms(Nd4j.create(means), Nd4j.create(stds)));
    }

    public static Data require() {
        if (installed == null) { throw new IllegalStateException("Host must install a classifier model before recognition"); }
        frozen = true; return installed;
    }

    private static KXmlParser parser(InputStream input) throws IOException, org.xmlpull.v1.XmlPullParserException {
        KXmlParser parser = new KXmlParser(); parser.setInput(input, null); parser.nextTag(); return parser;
    }
    private static void tag(KXmlParser parser, String name) throws IOException, org.xmlpull.v1.XmlPullParserException {
        parser.require(XmlPullParser.START_TAG, "", name);
    }
    private static int integer(KXmlParser parser, String attribute) {
        String value = parser.getAttributeValue(null, attribute);
        if (value == null) { throw new IllegalArgumentException("Missing model attribute: " + attribute); }
        return Integer.parseInt(value);
    }
    private static double decimal(KXmlParser parser, String attribute) {
        String value = parser.getAttributeValue(null, attribute);
        return value == null ? 0 : Double.parseDouble(value);
    }
    private static NeuralNetwork model(KXmlParser parser) throws IOException, org.xmlpull.v1.XmlPullParserException {
        tag(parser, "neural-network");
        int inputs = integer(parser, "input-size"), hiddens = integer(parser, "hidden-size"), outputs = integer(parser, "output-size");
        if (inputs <= 0 || hiddens <= 0 || outputs <= 0 || inputs == Integer.MAX_VALUE || hiddens == Integer.MAX_VALUE) {
            throw new IllegalArgumentException("Invalid model dimensions");
        }
        double rate = decimal(parser, "learning-rate"), momentum = decimal(parser, "momentum"), lambda = decimal(parser, "lambda");
        int epochs = parser.getAttributeValue(null, "epochs-total") == null ? 0 : integer(parser, "epochs-total");
        String[] inputLabels = null, outputLabels = null; double[][] hidden = null, output = null; Set<String> seen = new HashSet<>();
        while (parser.nextTag() == XmlPullParser.START_TAG) {
            String name = parser.getName();
            if (!seen.add(name)) { throw new IllegalArgumentException("Duplicate network element: " + name); }
            switch (name) {
                case "input-labels": inputLabels = parser.nextText().trim().split("\\s+"); break;
                case "output-labels": outputLabels = parser.nextText().trim().split("\\s+"); break;
                case "hidden-weights": hidden = matrix(parser, hiddens, inputs + 1); break;
                case "output-weights": output = matrix(parser, outputs, hiddens + 1); break;
                default: throw new IllegalArgumentException("Unsupported network element: " + name);
            }
        }
        parser.require(XmlPullParser.END_TAG, "", "neural-network");
        if (inputLabels == null || outputLabels == null || hidden == null || output == null
                || inputLabels.length != inputs || outputLabels.length != outputs) { throw new IllegalArgumentException("Inconsistent model labels or weights"); }
        return new NeuralNetwork(inputLabels, outputLabels, hidden, output, rate, momentum, lambda, epochs);
    }
    private static double[][] matrix(KXmlParser parser, int rows, int columns) throws IOException, org.xmlpull.v1.XmlPullParserException {
        String parent = parser.getName(); double[][] matrix = new double[rows][]; int row = 0;
        while (parser.nextTag() == XmlPullParser.START_TAG) {
            tag(parser, "row"); if (row == rows) { throw new IllegalArgumentException("Too many model rows"); }
            double[] values = new double[columns]; int column = 0;
            while (parser.nextTag() == XmlPullParser.START_TAG) {
                tag(parser, "item"); if (column == columns) { throw new IllegalArgumentException("Too many model columns"); }
                values[column++] = Double.parseDouble(parser.nextText().trim());
            }
            parser.require(XmlPullParser.END_TAG, "", "row");
            if (column != columns) { throw new IllegalArgumentException("Missing model columns"); }
            matrix[row++] = values;
        }
        parser.require(XmlPullParser.END_TAG, "", parent);
        if (row != rows) { throw new IllegalArgumentException("Missing model rows"); }
        return matrix;
    }
    private static double[] vector(KXmlParser parser) throws IOException, org.xmlpull.v1.XmlPullParserException {
        tag(parser, "vector"); List<Double> values = new ArrayList<>();
        while (parser.nextTag() == XmlPullParser.START_TAG) { tag(parser, "value"); values.add(Double.parseDouble(parser.nextText().trim())); }
        parser.require(XmlPullParser.END_TAG, "", "vector");
        double[] vector = new double[values.size()]; for (int i = 0; i < vector.length; i++) { vector[i] = values.get(i); }
        return vector;
    }
    private static void finish(KXmlParser parser) throws IOException, org.xmlpull.v1.XmlPullParserException {
        while (parser.next() != XmlPullParser.END_DOCUMENT) {
            if (parser.getEventType() != XmlPullParser.TEXT || !parser.isWhitespace()) { throw new IllegalArgumentException("Trailing classifier XML content"); }
        }
    }
}
