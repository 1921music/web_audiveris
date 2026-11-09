package org.audiveris.wasm.compat.test;

import java.awt.Color;
import java.awt.Dimension;
import java.awt.Point;
import java.awt.geom.Point2D;
import java.util.Random;
import org.audiveris.wasm.compat.bytes.ByteArrayLittleEndian;
import org.audiveris.wasm.compat.bytes.ByteArray;
import org.teavm.jso.JSExport;

/** Exactly the same standard-API bytecode runs against the JVM and the Wasm substitutions. */
public final class CompatibilityProbe {
    public static void main(String[] args) { }
    @JSExport public static void dumpStack() { Thread.dumpStack(); }
    @JSExport public static String numbers() {
        StringBuilder result = new StringBuilder();
        String[] inputs = {"0", "-0", "1", "-123456789.987654321", "1e308", "1e-308", "4.9e-324", "1.7976931348623157e308",
            "2.2250738585072012e-308", "9007199254740993", "0x1.fffffffffffffp1023", "NaN", "Infinity", "bad"};
        for (String input : inputs) {
            result.append(outcome(() -> Long.toHexString(Double.doubleToLongBits(Double.parseDouble(input))))).append('\n');
            result.append(outcome(() -> Integer.toHexString(Float.floatToIntBits(Float.parseFloat(input))))).append('\n');
            result.append(outcome(() -> Long.toHexString(Double.doubleToLongBits(com.fasterxml.jackson.core.io.NumberInput.parseDouble(input, true))))).append('\n');
            result.append(outcome(() -> Integer.toHexString(Float.floatToIntBits(com.fasterxml.jackson.core.io.NumberInput.parseFloat(input, true))))).append('\n');
        }
        for (String input : new String[] {"123456789012345678901234567890.0123456789", "1e-100", "-0.000", "bad", "9".repeat(1024)}) {
            result.append(outcome(() -> com.fasterxml.jackson.core.io.NumberInput.parseBigDecimal(input, true).toString())).append('\n');
            result.append(outcome(() -> com.fasterxml.jackson.core.io.NumberInput.parseBigInteger(input, true).toString())).append('\n');
        }
        Random random = new Random(71);
        for (int i = 0; i < 256; i++) {
            double value = Double.longBitsToDouble(random.nextLong());
            String input = Double.toString(value);
            result.append(Long.toHexString(Double.doubleToLongBits(com.fasterxml.jackson.core.io.NumberInput.parseDouble(input, true)))).append('\n');
        }
        return result.toString();
    }
    @JSExport public static String bytes() {
        StringBuilder result = new StringBuilder();
        byte[] a = new byte[20], b = new byte[20]; Random random = new Random(47);
        for (int i = 0; i < 128; i++) {
            random.nextBytes(a); System.arraycopy(a, 0, b, 0, a.length); int p = i % 13;
            result.append(ByteArrayLittleEndian.getBoolean(a, p)).append('|').append(ByteArray.getBoolean(b, p)).append('|');
            result.append((int) ByteArrayLittleEndian.getChar(a, p)).append('|').append((int) ByteArray.getChar(b, p)).append('|');
            result.append(ByteArrayLittleEndian.getShort(a, p)).append('|').append(ByteArray.getShort(b, p)).append('|');
            result.append(ByteArrayLittleEndian.getUnsignedShort(a, p)).append('|').append(ByteArray.getUnsignedShort(b, p)).append('|');
            result.append(ByteArrayLittleEndian.getInt(a, p)).append('|').append(ByteArray.getInt(b, p)).append('|');
            result.append(ByteArrayLittleEndian.getLong(a, p)).append('|').append(ByteArray.getLong(b, p)).append('\n');
            long value = random.nextLong();
            ByteArrayLittleEndian.setLong(a, p, value); ByteArray.setLong(b, p, value);
            result.append(ByteArrayLittleEndian.getLong(a, p)).append('|').append(ByteArray.getLong(b, p)).append('\n');
            ByteArrayLittleEndian.setInt(a, p, (int) value); ByteArray.setInt(b, p, (int) value);
            result.append(ByteArrayLittleEndian.getInt(a, p)).append('|').append(ByteArray.getInt(b, p)).append('\n');
            float f = i == 0 ? Float.NaN : i == 1 ? -0f : (float) (value * .001);
            ByteArrayLittleEndian.setFloat(a, p, f); ByteArray.setFloat(b, p, f);
            result.append(Float.floatToIntBits(ByteArrayLittleEndian.getFloat(a, p))).append('|').append(Float.floatToIntBits(ByteArray.getFloat(b, p))).append('\n');
            double d = i == 0 ? Double.NaN : i == 1 ? -0d : value * .001;
            ByteArrayLittleEndian.setDoubleRaw(a, p, d); ByteArray.setDoubleRaw(b, p, d);
            result.append(Double.doubleToLongBits(ByteArrayLittleEndian.getDoubleRaw(a, p))).append('|').append(Double.doubleToLongBits(ByteArray.getDoubleRaw(b, p))).append('\n');
        }
        return result.toString();
    }
    public interface EmptyService { }
    private static String outcome(java.util.function.Supplier<?> operation) {
        try { return String.valueOf(operation.get()); }
        catch (RuntimeException failure) { return failure.getClass().getName(); }
    }
    @JSExport public static String standard() {
        StringBuilder values = new StringBuilder();
        for (Class<?> type : new Class<?>[] {void.class, boolean.class, byte.class, char.class, short.class,
                int.class, long.class, float.class, double.class, String.class, int[][].class, String[].class}) {
            values.append(type.getPackageName()).append('|').append(type.descriptorString()).append('|');
            values.append(type.componentType() == null ? "null" : type.componentType().getName()).append('|');
            values.append(outcome(type::arrayType)).append('\n');
        }
        for (String input : new String[] {null, "", "-1", "+0", "4294967295", "4294967296", "123x", "\u0661\u0662"}) {
            values.append(outcome(() -> Integer.parseUnsignedInt(input))).append('\n');
        }
        for (String name : new String[] {null, "UTF-8", "US-ASCII", "UTF-16LE", "NO-SUCH-CHARSET", "", "a b"}) {
            values.append(outcome(() -> java.nio.charset.Charset.isSupported(name))).append('\n');
        }
        // Aliases/optional encodings vary with the provider: isSupported must agree with forName.
        for (String name : new String[] {"utf8", "UTF-32", "x-no-such-charset"}) {
            boolean available;
            try { java.nio.charset.Charset.forName(name); available = true; }
            catch (java.nio.charset.UnsupportedCharsetException unsupported) { available = false; }
            values.append(available == java.nio.charset.Charset.isSupported(name)).append('\n');
        }
        for (byte[] a : new byte[][] {null, {}, {-128, 0}, {0, 1}, {127}}) {
            for (byte[] b : new byte[][] {null, {}, {-128, 1}, {0, 1}, {127}}) {
                values.append(java.util.Arrays.compare(a, b)).append('\n');
            }
        }
        java.util.Iterator<Integer> iterator = java.util.Collections.enumeration(java.util.Arrays.asList(1, 2)).asIterator();
        values.append(iterator.next()).append('|').append(iterator.next()).append('|').append(iterator.hasNext()).append('\n');
        values.append(outcome(iterator::next)).append('\n');
        java.util.Spliterator<Object> empty = java.util.Spliterators.emptySpliterator();
        values.append(empty.estimateSize()).append('|').append(empty.characteristics()).append('|').append(empty.trySplit()).append('\n');
        values.append(outcome(() -> empty.tryAdvance(null))).append('\n');
        values.append(java.util.ServiceLoader.load(EmptyService.class).findFirst().isPresent()).append('\n');
        for (long millis : new long[] {-1001, -1, 0, 1234}) {
            values.append(java.util.Date.from(java.time.Instant.ofEpochMilli(millis)).getTime()).append('\n');
        }
        java.util.concurrent.atomic.AtomicLong atomic = new java.util.concurrent.atomic.AtomicLong(42);
        values.append(atomic.compareAndExchange(41, 99)).append('|').append(atomic.get()).append('\n');
        values.append(atomic.compareAndExchange(42, Long.MIN_VALUE)).append('|').append(atomic.get()).append('\n');
        Thread thread = Thread.currentThread();
        Thread.onSpinWait();
        values.append(thread.threadId() == thread.getId()).append('|').append(thread.isVirtual()).append('\n');
        Thread subclass = new Thread() { @Override public long getId() { return -7; } };
        values.append(subclass.getId() == -7 && subclass.threadId() > 0).append('\n');
        try {
            for (String header : new String[] {null, "-1", "42", "4294967296", "9223372036854775807", "9223372036854775808", " 1"}) {
                values.append(StandardApiTest.connection(header).getContentLengthLong()).append('\n');
            }
        } catch (Exception failure) { throw new AssertionError(failure); }
        return values.toString();
    }
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
