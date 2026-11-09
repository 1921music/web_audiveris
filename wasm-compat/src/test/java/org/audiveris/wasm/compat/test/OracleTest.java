package org.audiveris.wasm.compat.test;

import java.awt.geom.Point2D;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Random;
import org.audiveris.wasm.compat.awt.Color;
import org.audiveris.wasm.compat.awt.Dimension;
import org.audiveris.wasm.compat.awt.Point;

/** JVM behavioral oracle, deliberately independent of the compatibility implementations. */
public final class OracleTest {
    private static int checks;
    private static void equal(double actual, double expected, String label) {
        checks++;
        if (Double.doubleToLongBits(actual) != Double.doubleToLongBits(expected)) {
            throw new AssertionError(label + ": " + actual + " != " + expected);
        }
    }
    private static void check(boolean condition, String label) { checks++; if (!condition) { throw new AssertionError(label); } }
    private static void colors(Color actual, java.awt.Color expected) {
        equal(actual.getRGB(), expected.getRGB(), "RGB");
        equal(actual.getAlpha(), expected.getAlpha(), "alpha");
        equal(actual.hashCode(), expected.hashCode(), "hash");
        equal(actual.getTransparency(), expected.getTransparency(), "transparency");
        float[] a = actual.getRGBComponents(null), e = expected.getRGBComponents(null);
        for (int i = 0; i < 4; i++) { equal(a[i], e[i], "float RGB component"); }
        equal(actual.brighter().getRGB(), expected.brighter().getRGB(), "brighter");
        equal(actual.darker().getRGB(), expected.darker().getRGB(), "darker");
        float[] hsb = Color.RGBtoHSB(actual.getRed(), actual.getGreen(), actual.getBlue(), null);
        float[] oracle = java.awt.Color.RGBtoHSB(expected.getRed(), expected.getGreen(), expected.getBlue(), null);
        for (int i = 0; i < 3; i++) { equal(hsb[i], oracle[i], "HSB component"); }
        equal(Color.HSBtoRGB(hsb[0], hsb[1], hsb[2]), java.awt.Color.HSBtoRGB(oracle[0], oracle[1], oracle[2]), "HSB roundtrip");
    }
    public static void main(String[] args) throws Exception {
        checks += StandardApiTest.run();
        checks += ByteArrayTest.run();
        checks += FloatingPointTest.run();
        compareSortedSets();
        compareStrings();
        comparePaths();
        double[] edges = {Double.NaN, Double.NEGATIVE_INFINITY, Double.POSITIVE_INFINITY, -2147483649d,
            Integer.MIN_VALUE, Integer.MAX_VALUE, -1.5, -.5, -0d, 0d, .499999999, .5, 1.5};
        for (double x : edges) {
            Point point = new Point(); java.awt.Point oracle = new java.awt.Point();
            point.setLocation(x, -x); oracle.setLocation(x, -x);
            equal(point.x, oracle.x, "point edge x"); equal(point.y, oracle.y, "point edge y");
            Dimension size = new Dimension(); java.awt.Dimension other = new java.awt.Dimension();
            size.setSize(x, -x); other.setSize(x, -x);
            equal(size.width, other.width, "dimension edge width"); equal(size.height, other.height, "dimension edge height");
        }
        Random random = new Random(1949);
        for (int i = 0; i < 10000; i++) {
            int x = random.nextInt(), y = random.nextInt();
            Point point = new Point(x, y); java.awt.Point oracle = new java.awt.Point(x, y);
            point.translate(x, y); oracle.translate(x, y);
            equal(point.x, oracle.x, "overflow x"); equal(point.y, oracle.y, "overflow y");
            equal(point.hashCode(), oracle.hashCode(), "point hash");
            equal(point.distanceSq(new Point2D.Double(2, 3)), oracle.distanceSq(new Point2D.Double(2, 3)), "point distance");
            check(point.equals(new Point2D.Double(point.x, point.y)), "Point2D equality");
            check(point.clone() instanceof Point, "point clone type");
            Dimension size = new Dimension(x, y); java.awt.Dimension original = new java.awt.Dimension(x, y);
            equal(size.hashCode(), original.hashCode(), "dimension hash");
            check(size.equals(new Dimension(size)), "dimension copy");
            colors(new Color(x, true), new java.awt.Color(x, true));
            colors(new Color(x), new java.awt.Color(x));
            float r = random.nextFloat(), g = random.nextFloat(), b = random.nextFloat(), a = random.nextFloat();
            colors(new Color(r, g, b, a), new java.awt.Color(r, g, b, a));
        }
        colors(new Color(Float.NaN, .5f, 1f, Float.NaN), new java.awt.Color(Float.NaN, .5f, 1f, Float.NaN));
        for (int bad : new int[] {-1, 256}) {
            boolean rejected = false;
            try { new Color(bad, 0, 0); } catch (IllegalArgumentException expected) { rejected = true; }
            check(rejected, "invalid integer component");
        }
        for (float bad : new float[] {-.00001f, 1.00001f, Float.POSITIVE_INFINITY}) {
            boolean rejected = false;
            try { new Color(bad, 0, 0); } catch (IllegalArgumentException expected) { rejected = true; }
            check(rejected, "invalid float component");
        }
        for (int rgba : new int[] {0, 0x12345678, 0xffabcdef, 0x80aabbcc}) {
            var actual = new Color(rgba, true).createContext(null, null, null, null, null);
            var expected = new java.awt.Color(rgba, true).createContext(null, null, null, null, null);
            var raster = actual.getRaster(-2, 3, 7, 5); var other = expected.getRaster(-2, 3, 7, 5);
            for (int y = 0; y < 5; y++) { for (int x = 0; x < 7; x++) {
                equal(actual.getColorModel().getRGB(raster.getDataElements(x, y, null)),
                      expected.getColorModel().getRGB(other.getDataElements(x, y, null)), "solid paint pixel");
            } }
            actual.dispose(); expected.dispose();
        }
        double[] fixture = CompatibilityProbe.values();
        StringBuilder json = new StringBuilder("[");
        for (int i = 0; i < fixture.length; i++) { if (i > 0) { json.append(','); } json.append(fixture[i]); }
        Files.writeString(Path.of(args[0]), json.append("]\n").toString());
        Files.writeString(Path.of(args[0]).resolveSibling("collections.json"), java.util.Arrays.toString(CompatibilityProbe.collections()));
        Files.writeString(Path.of(args[0]).resolveSibling("strings.json"), java.util.Arrays.toString(CompatibilityProbe.strings()));
        Files.writeString(Path.of(args[0]).resolveSibling("paths.json"), java.util.Arrays.toString(CompatibilityProbe.paths()));
        Files.writeString(Path.of(args[0]).resolveSibling("standard.txt"), CompatibilityProbe.standard());
        Files.writeString(Path.of(args[0]).resolveSibling("bytes.txt"), CompatibilityProbe.bytes());
        Files.writeString(Path.of(args[0]).resolveSibling("numbers.txt"), CompatibilityProbe.numbers());
        Files.writeString(Path.of(args[0]).resolveSibling("jvm-verification.json"), "{\"checks\":" + checks + ",\"probeValues\":" + fixture.length + ",\"passed\":true}\n");
        System.out.println("JVM oracle passed " + checks + " checks; exported " + fixture.length + " Wasm reference values");
    }

    private static String outcome(java.util.function.Supplier<?> operation) {
        try { return String.valueOf(operation.get()); } catch (RuntimeException exception) { return exception.getClass().getName(); }
    }
    private static void comparePaths() {
        String[] parts = {"", ".", "..", "abc", "\u697d\u8b5c", "file.txt"};
        for (String a : parts) { for (String b : parts) { for (String c : parts) {
            for (String root : new String[] {"", "/"}) {
                Path path = Path.of(root + a + "/" + b + "/" + c);
                check(org.audiveris.wasm.compat.RuntimeApis.normalizeDefaultPath(path).equals(path.normalize()), "lexical normalization: " + path);
                java.io.File file = new java.io.File(path.toString());
                check(org.audiveris.wasm.compat.RuntimeApis.fileToPath(file).equals(file.toPath()), "file path conversion");
            }
        } } }
    }
    @SuppressWarnings("deprecation") private static void compareStrings() {
        Random random = new Random(23);
        for (int i = 0; i < 300; i++) {
            byte[] input = new byte[random.nextInt(100)]; random.nextBytes(input);
            int high = random.nextInt();
            check(new String(org.audiveris.wasm.compat.RuntimeApis.highByteChars(input, high)).equals(new String(input, high)), "high-byte constructor");
            int start = random.nextInt(input.length + 1), length = random.nextInt(input.length - start + 1);
            check(new String(org.audiveris.wasm.compat.RuntimeApis.highByteChars(input, high, start, length))
                    .equals(new String(input, high, start, length)), "high-byte constructor slice");
            String text = "\u1234\ud800\u00ff" + new String(input, 0);
            byte[] actual = new byte[text.length() + 6], expected = new byte[actual.length];
            org.audiveris.wasm.compat.RuntimeApis.stringGetBytes(text, 1, text.length(), actual, 3);
            text.getBytes(1, text.length(), expected, 3);
            check(java.util.Arrays.equals(actual, expected), "low-byte copy and destination boundaries");
        }
        for (String text : new String[] {"", "a", "\n", "\r", "\r\n", "a\r\nb\nc\rd\r", "a\u0085b\u2028c", "\n\n\n", "a\u0000b"}) {
            check(org.audiveris.wasm.compat.RuntimeApis.stringLines(text).toList().equals(text.lines().toList()), "line delimiters");
            check(org.audiveris.wasm.compat.RuntimeApis.stringLines(text).parallel().toList().equals(text.lines().parallel().toList()), "parallel line ordering");
        }
    }
    private static void compareSortedSets() {
        java.util.TreeSet<Integer> source = new java.util.TreeSet<>(java.util.List.of(1, 3, 5));
        var actual = org.audiveris.wasm.compat.SortedSets.unmodifiableSortedSet(source);
        var expected = java.util.Collections.unmodifiableSortedSet(source);
        source.add(4);
        check(actual.equals(expected) && actual.subSet(2, 5).equals(expected.subSet(2, 5)), "live sorted subset");
        equal(actual.spliterator().characteristics(), expected.spliterator().characteristics(), "spliterator flags");
        java.util.TreeSet<Integer> reverse = new java.util.TreeSet<>(java.util.Comparator.reverseOrder());
        reverse.addAll(source);
        var reverseView = org.audiveris.wasm.compat.SortedSets.unmodifiableSortedSet(reverse);
        check(reverseView.comparator() == reverse.comparator(), "comparator identity");
        check(reverseView.first().equals(reverse.first()), "reverse order");
        for (int mode = 0; mode < 2; mode++) {
            java.util.SortedSet<Integer> a = mode == 0 ? actual : org.audiveris.wasm.compat.SortedSets.emptySortedSet();
            java.util.SortedSet<Integer> e = mode == 0 ? expected : java.util.Collections.emptySortedSet();
            java.util.List<java.util.function.Function<java.util.SortedSet<Integer>, ?>> operations = java.util.List.of(
                s -> s.contains(null), s -> s.first(), s -> s.last(), s -> s.subSet(5, 3), s -> s.headSet(null),
                s -> s.remove(999), s -> s.removeAll(java.util.List.of()), s -> s.retainAll(java.util.List.of()),
                s -> s.removeIf(x -> false), s -> s.addAll(java.util.List.of()),
                s -> { s.clear(); return null; }, s -> { s.iterator().remove(); return null; });
            for (var operation : operations) {
                String aResult = outcome(() -> operation.apply(a)), eResult = outcome(() -> operation.apply(e));
                check(aResult.equals(eResult), "sorted set outcome: " + aResult + " != " + eResult);
            }
        }
    }
}
