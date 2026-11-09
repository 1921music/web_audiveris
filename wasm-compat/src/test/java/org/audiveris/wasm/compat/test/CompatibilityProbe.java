package org.audiveris.wasm.compat.test;

import java.awt.Color;
import java.awt.Dimension;
import java.awt.Point;
import java.awt.geom.Point2D;
import java.util.Random;
import org.teavm.jso.JSExport;

/** Exactly the same standard-API bytecode runs against the JVM and the Wasm substitutions. */
public final class CompatibilityProbe {
    public static void main(String[] args) { }
    @JSExport public static String pathDiagnostic() {
        try { paths(); return "ok"; }
        catch (Throwable failure) { return failure.getClass().getName() + ": " + failure.getMessage(); }
    }

    @JSExport public static int[] paths() {
        String[] inputs = {"", ".", "./model.bin", "models/../data.bin", "/tmp/data.bin", "unicode-\u697d\u8b5c.bin"};
        int[] result = new int[inputs.length * 4]; int cursor = 0;
        for (String input : inputs) {
            java.io.File file = new java.io.File(input);
            java.nio.file.Path path = file.toPath();
            result[cursor++] = path.toString().hashCode();
            result[cursor++] = path == file.toPath() ? 1 : 0;
            result[cursor++] = path.equals(java.nio.file.Paths.get(file.getPath())) ? 1 : 0;
            result[cursor++] = path.normalize().toString().hashCode();
        }
        return result;
    }

    @SuppressWarnings("deprecation") @JSExport public static int[] strings() {
        byte[] bytes = {(byte) 128, (byte) 255, 0, 65};
        String first = new String(bytes, 0x12), second = new String(bytes, 0x123, 1, 3);
        String source = "A\u00ff\u1234\ud800\r\nB";
        byte[] low = new byte[9]; source.getBytes(0, source.length(), low, 1);
        String[] cases = {"", "a", "a\n", "a\r\nb\r\n", "\n\r\r\n", "a\u0085b\u2028c", "\ud83d\ude00\r\nx"};
        int[] result = new int[4 + 3 + 9 + cases.length * 2];
        int cursor = 0;
        for (int i = 0; i < first.length(); i++) { result[cursor++] = first.charAt(i); }
        for (int i = 0; i < second.length(); i++) { result[cursor++] = second.charAt(i); }
        for (byte value : low) { result[cursor++] = value; }
        for (String value : cases) {
            result[cursor++] = (int) value.lines().count();
            result[cursor++] = value.lines().mapToInt(String::hashCode).sum();
        }
        return result;
    }

    @JSExport public static int[] collections() {
        java.util.TreeSet<Integer> backing = new java.util.TreeSet<>();
        backing.add(1); backing.add(3); backing.add(5);
        java.util.SortedSet<Integer> view = java.util.Collections.unmodifiableSortedSet(backing);
        backing.add(4);
        int rejected = 0;
        try { view.add(9); } catch (UnsupportedOperationException expected) { rejected++; }
        try { view.remove(999); } catch (UnsupportedOperationException expected) { rejected++; }
        try { view.iterator().remove(); } catch (UnsupportedOperationException expected) { rejected++; }
        try { view.subSet(2, 5).clear(); } catch (UnsupportedOperationException expected) { rejected++; }
        java.util.SortedSet<Integer> empty = java.util.Collections.emptySortedSet();
        try { empty.first(); } catch (java.util.NoSuchElementException expected) { rejected++; }
        return new int[] {view.size(), view.first(), view.last(), view.subSet(2, 5).size(),
            view.subSet(2, 5).first(), view.subSet(2, 5).last(), empty.size(), rejected};
    }

    @JSExport public static double[] values() {
        double[] values = new double[128 * 32];
        Random random = new Random(1921);
        int cursor = 0;
        for (int i = 0; i < 128; i++) {
            double x = random.nextDouble() * 2000 - 1000, y = random.nextDouble() * 2000 - 1000;
            Point point = new Point(); point.setLocation(x, y);
            Point copy = new Point(point); copy.translate(i, -i);
            Point2D base = copy;
            Dimension size = new Dimension(); size.setSize(x, y);
            Dimension sizeCopy = new Dimension(size);
            Color color = new Color(random.nextInt(), (i & 1) == 0);
            Color precise = new Color(random.nextFloat(), random.nextFloat(), random.nextFloat(), random.nextFloat());
            float[] rgb = precise.getRGBComponents(null);
            float[] hsb = Color.RGBtoHSB(color.getRed(), color.getGreen(), color.getBlue(), null);
            double[] record = {point.x, point.y, base.getX(), base.getY(), point.distanceSq(copy), point.hashCode(),
                ((Point) copy.clone()).x, sizeCopy.getWidth(), sizeCopy.getHeight(), size.hashCode(),
                ((Dimension) size.clone()).height, color.getRGB(), color.getAlpha(), color.getTransparency(),
                color.brighter().getRGB(), color.darker().getRGB(), precise.getRGB(), rgb[0], rgb[1], rgb[2], rgb[3],
                hsb[0], hsb[1], hsb[2], Color.HSBtoRGB(hsb[0], hsb[1], hsb[2]),
                Color.getHSBColor(hsb[0], hsb[1], hsb[2]).getRGB(), Color.decode("#123456").getRGB(),
                Color.RED == Color.red ? 1 : 0, Color.white.getRGB(),
                color.equals(new Color(color.getRGB(), true)) ? 1 : 0, color.hashCode(), point.getLocation().equals(point) ? 1 : 0};
            System.arraycopy(record, 0, values, cursor, record.length);
            cursor += record.length;
        }
        return values;
    }
}
