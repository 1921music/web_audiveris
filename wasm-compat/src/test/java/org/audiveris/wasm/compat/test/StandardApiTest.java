package org.audiveris.wasm.compat.test;

import java.util.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;
import org.audiveris.wasm.compat.StandardApis;

final class StandardApiTest {
    private static int checks;
    private static void check(boolean value) { checks++; if (!value) { throw new AssertionError("Standard API oracle check " + checks); } }
    private static String outcome(Supplier<?> call) {
        try { return String.valueOf(call.get()); } catch (RuntimeException e) { return e.getClass().getName(); }
    }
    static int run() throws Exception {
        Random random = new Random(361);
        for (int i = 0; i < 5000; i++) {
            byte[] a = new byte[random.nextInt(64)], b = new byte[random.nextInt(64)];
            random.nextBytes(a); random.nextBytes(b);
            check(StandardApis.compareBytes(a, b) == Arrays.compare(a, b));
            check(StandardApis.compareBytes(a, a) == 0);
            check(StandardApis.compareBytes(null, b) == -1);
            int number = random.nextInt(); String text = Integer.toUnsignedString(number);
            check(StandardApis.parseUnsignedInt(text) == Integer.parseUnsignedInt(text));
            AtomicLong a1 = new AtomicLong(random.nextLong()), a2 = new AtomicLong(a1.get());
            long expected = (i & 1) == 0 ? a1.get() : random.nextLong(), update = random.nextLong();
            check(StandardApis.compareAndExchange(a1, expected, update) == a2.compareAndExchange(expected, update));
            check(a1.get() == a2.get());
        }
        for (String text : new String[] {null, "", "+0", "-0", "-1", "4294967295", "4294967296", "+4294967295", "0000000000000000000000001", "123x", " 1", "\u0661\u0662"}) {
            check(outcome(() -> StandardApis.parseUnsignedInt(text)).equals(outcome(() -> Integer.parseUnsignedInt(text))));
        }
        for (String text : new String[] {null, "UTF-8", "utf8", "US-ASCII", "UTF-16LE", "NO-SUCH-CHARSET", "", "a b", "-foo"}) {
            check(outcome(() -> StandardApis.charsetSupported(text)).equals(outcome(() -> java.nio.charset.Charset.isSupported(text))));
        }
        for (Class<?> type : new Class<?>[] {void.class, boolean.class, byte.class, char.class, short.class, int.class, long.class, float.class, double.class, String.class, StandardApiTest.class, int[][].class, String[].class}) {
            check(StandardApis.packageName(type).equals(type.getPackageName()));
            check(StandardApis.descriptorString(type).equals(type.descriptorString()));
            check(StandardApis.componentType(type) == type.componentType());
            check(outcome(() -> StandardApis.arrayType(type)).equals(outcome(type::arrayType)));
        }
        Class<?> deep = int.class;
        for (int i = 0; i < 255; i++) { deep = deep.arrayType(); }
        Class<?> limit = deep;
        check(outcome(() -> StandardApis.arrayType(limit)).equals(outcome(limit::arrayType)));
        for (String name : new String[] {"void", "boolean", "byte", "char", "short", "int", "long", "float", "double", "unknown"}) {
            Class<?> type = StandardApis.forPrimitiveName(name);
            check(type == null ? name.equals("unknown") : type.isPrimitive() && type.getName().equals(name));
        }
        check(outcome(() -> StandardApis.forPrimitiveName(null)).equals("java.lang.NullPointerException"));
        for (long millis : new long[] {Long.MIN_VALUE, -1001, -1, 0, 1234, Long.MAX_VALUE}) {
            java.time.Instant instant = java.time.Instant.ofEpochMilli(millis);
            check(StandardApis.dateFrom(instant).equals(Date.from(instant)));
        }
        for (java.time.Instant instant : new java.time.Instant[] {null, java.time.Instant.MAX, java.time.Instant.MIN}) {
            check(outcome(() -> StandardApis.dateFrom(instant)).equals(outcome(() -> Date.from(instant))));
        }
        for (String header : new String[] {null, "", "-1", "42", "4294967296", "9223372036854775807", "9223372036854775808", " 1"}) {
            java.net.URLConnection connection = connection(header);
            check(StandardApis.contentLengthLong(connection) == connection.getContentLengthLong());
        }
        Iterator<Integer> iterator = StandardApis.asIterator(Collections.enumeration(Arrays.asList(1, 2, 3)));
        check(iterator.next() == 1);
        check(outcome(() -> { iterator.remove(); return "ok"; }).equals("java.lang.UnsupportedOperationException"));
        List<Integer> tail = new ArrayList<>(); iterator.forEachRemaining(tail::add);
        check(tail.equals(Arrays.asList(2, 3)));
        check(outcome(iterator::next).equals("java.util.NoSuchElementException"));
        check(outcome(() -> { iterator.forEachRemaining(null); return "ok"; }).equals("java.lang.NullPointerException"));
        for (Integer value : new Integer[] {null, 42}) {
            Spliterator<Integer> single = StandardApis.singletonSpliterator(value);
            check(single.trySplit() == null && single.estimateSize() == 1);
            check((single.characteristics() & Spliterator.NONNULL) != 0 == (value != null));
            check(outcome(() -> single.tryAdvance(null)).equals("java.lang.NullPointerException"));
            check(single.estimateSize() == 1);
            List<Integer> output = new ArrayList<>();
            check(single.tryAdvance(output::add) && output.equals(Collections.singletonList(value)));
            check(single.estimateSize() == 0 && !single.tryAdvance(output::add));
            check(outcome(() -> single.tryAdvance(null)).equals("java.lang.NullPointerException"));
        }
        Spliterator<Object> empty = StandardApis.emptySpliterator();
        check(empty.characteristics() == Spliterators.emptySpliterator().characteristics());
        check(empty.trySplit() == null && empty.estimateSize() == 0);
        check(outcome(() -> empty.tryAdvance(null)).equals("java.lang.NullPointerException"));
        check(!StandardApis.findFirst(ServiceLoader.load(CompatibilityProbe.EmptyService.class)).isPresent());
        for (String value : new String[] {null, "", "music", "\u697d\u8b5c"}) {
            check(StandardApis.nullableStream(value).isParallel() == java.util.stream.Stream.ofNullable(value).isParallel());
            check(StandardApis.nullableStream(value).collect(java.util.stream.Collectors.toList())
                    .equals(java.util.stream.Stream.ofNullable(value).collect(java.util.stream.Collectors.toList())));
        }
        check(!StandardApis.isVirtual(Thread.currentThread()));
        StandardApis.onSpinWait();
        java.io.PrintStream original = System.err;
        java.io.ByteArrayOutputStream trace = new java.io.ByteArrayOutputStream();
        try {
            System.setErr(new java.io.PrintStream(trace, true, java.nio.charset.StandardCharsets.UTF_8));
            StandardApis.dumpStack();
        } finally { System.setErr(original); }
        check(trace.toString(java.nio.charset.StandardCharsets.UTF_8).contains("java.lang.Exception: Stack trace"));
        check(trace.toString(java.nio.charset.StandardCharsets.UTF_8).contains("StandardApiTest.run"));
        return checks;
    }
    static java.net.URLConnection connection(String header) throws Exception {
        return new java.net.URLConnection(new java.net.URL("http://example.invalid")) {
            @Override public void connect() { }
            @Override public String getHeaderField(String key) { return header; }
        };
    }
}
