package org.audiveris.wasm;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.zip.ZipFile;
import javax.xml.parsers.DocumentBuilderFactory;
import org.audiveris.omr.math.NaturalSpline;
import org.w3c.dom.Element;

/** Verify real upstream staff-line knots and generate numerical fixtures for Wasm/browser tests. */
public final class CurveReference {
    private static final StringBuilder fixtures = new StringBuilder("[\n");
    private static int count;

    public static void main(String[] args) throws Exception {
        add("synthetic-line", new double[] {0, 10}, new double[] {2, 4});
        add("synthetic-quadratic", new double[] {0, 10, 30}, new double[] {2, 4, 3});
        add("synthetic-cubic", new double[] {0, 10, 25, 50}, new double[] {2, 4, 3, 5});
        try (var files = Files.walk(Path.of(args[0]))) {
            for (Path file : files.filter(p -> p.toString().endsWith(".omr")).sorted().toList()) {
                try (ZipFile zip = new ZipFile(file.toFile())) {
                    var factory = DocumentBuilderFactory.newInstance();
                    factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
                    var document = factory.newDocumentBuilder().parse(zip.getInputStream(zip.getEntry("sheet#1/sheet#1.xml")));
                    var staves = document.getElementsByTagName("staff");
                    for (int i = 0; i < staves.getLength(); i++) {
                        var lines = ((Element) staves.item(i)).getElementsByTagName("line");
                        for (int j = 0; j < lines.getLength(); j++) {
                            var points = ((Element) lines.item(j)).getElementsByTagName("point");
                            if (points.getLength() < 2) { continue; }
                            double[] x = new double[points.getLength()], y = new double[points.getLength()];
                            for (int k = 0; k < points.getLength(); k++) {
                                Element point = (Element) points.item(k);
                                x[k] = Double.parseDouble(point.getAttribute("x"));
                                y[k] = Double.parseDouble(point.getAttribute("y"));
                            }
                            add("staff-" + count, x, y);
                        }
                    }
                }
            }
        }
        if (count <= 3) { throw new AssertionError("No upstream staff-line curves were found; run GRID reference first"); }
        Files.writeString(Path.of(args[1]), fixtures.append("\n]\n").toString());
        System.out.println(count + " upstream curves match positions and derivatives");
    }

    private static void add(String id, double[] axis, double[] values) {
        NaturalSpline upstream = NaturalSpline.interpolate(axis, values);
        NaturalCurve portable = new NaturalCurve(axis, values);
        double[] queries = new double[17], expected = new double[34];
        for (int i = 0; i < queries.length; i++) {
            queries[i] = i == 16 ? axis[axis.length - 1] : axis[0] + (axis[axis.length - 1] - axis[0]) * i / 16;
            expected[2 * i] = upstream.yAtX(queries[i]);
            expected[2 * i + 1] = upstream.yDerivativeAtX(queries[i]);
            if (Math.abs(expected[2 * i] - portable.valueAt(queries[i])) > 1e-10
                    || Math.abs(expected[2 * i + 1] - portable.slopeAt(queries[i])) > 1e-10) {
                throw new AssertionError(id + ": portable curve differs from upstream");
            }
        }
        if (count++ > 0) { fixtures.append(",\n"); }
        fixtures.append("{\"id\":\"").append(id).append("\",\"axis\":").append(Arrays.toString(axis))
                .append(",\"values\":").append(Arrays.toString(values)).append(",\"queries\":").append(Arrays.toString(queries))
                .append(",\"expected\":").append(Arrays.toString(expected)).append('}');
    }
}
