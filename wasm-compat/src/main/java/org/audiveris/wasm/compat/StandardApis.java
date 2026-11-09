// SPDX-License-Identifier: AGPL-3.0-or-later
package org.audiveris.wasm.compat;

import java.util.*;
import java.util.function.Consumer;

/** Small API additions expressed only in APIs already implemented by TeaVM. */
public final class StandardApis {
    private StandardApis() { }

    public static int compareBytes(byte[] first, byte[] second) {
        if (first == second) { return 0; }
        if (first == null) { return -1; }
        if (second == null) { return 1; }
        int length = Math.min(first.length, second.length);
        for (int i = 0; i < length; i++) {
            int difference = first[i] - second[i];
            if (difference != 0) { return difference; }
        }
        return first.length - second.length;
    }

    public static int parseUnsignedInt(String value) {
        if (value == null || value.isEmpty() || value.charAt(0) == '-') { throw new NumberFormatException(); }
        int start = value.charAt(0) == '+' ? 1 : 0;
        if (start == value.length()) { throw new NumberFormatException(); }
        long parsed = 0;
        for (int i = start; i < value.length(); i++) {
            int digit = Character.digit(value.charAt(i), 10);
            if (digit < 0) { throw new NumberFormatException(); }
            parsed = parsed * 10 + digit;
            if (parsed > 0xffff_ffffL) { throw new NumberFormatException(); }
        }
        return (int) parsed;
    }

    public static boolean charsetSupported(String name) {
        try { java.nio.charset.Charset.forName(name); return true; }
        catch (java.nio.charset.UnsupportedCharsetException unsupported) { return false; }
    }

    public static String packageName(Class<?> type) {
        while (type.isArray()) { type = type.getComponentType(); }
        if (type.isPrimitive()) { return "java.lang"; }
        String name = type.getName();
        int dot = name.lastIndexOf('.');
        return dot < 0 ? "" : name.substring(0, dot);
    }

    public static String descriptorString(Class<?> type) {
        if (type.isArray()) { return type.getName().replace('.', '/'); }
        if (!type.isPrimitive()) { return "L" + type.getName().replace('.', '/') + ";"; }
        if (type == void.class) { return "V"; }
        if (type == boolean.class) { return "Z"; }
        if (type == byte.class) { return "B"; }
        if (type == char.class) { return "C"; }
        if (type == short.class) { return "S"; }
        if (type == int.class) { return "I"; }
        if (type == long.class) { return "J"; }
        if (type == float.class) { return "F"; }
        return "D";
    }

    public static Class<?> componentType(Class<?> type) { return type.getComponentType(); }
    public static Class<?> arrayType(Class<?> type) {
        try { return java.lang.reflect.Array.newInstance(type, 0).getClass(); }
        catch (IllegalArgumentException invalid) { throw new UnsupportedOperationException(invalid); }
    }
    public static Class<?> forPrimitiveName(String name) {
        switch (name) {
            case "void": return void.class;
            case "boolean": return boolean.class;
            case "byte": return byte.class;
            case "char": return char.class;
            case "short": return short.class;
            case "int": return int.class;
            case "long": return long.class;
            case "float": return float.class;
            case "double": return double.class;
            default: return null;
        }
    }

    public static <E> Iterator<E> asIterator(Enumeration<E> values) {
        return new Iterator<E>() {
            @Override public boolean hasNext() { return values.hasMoreElements(); }
            @Override public E next() {
                if (!values.hasMoreElements()) { throw new NoSuchElementException(); }
                return values.nextElement();
            }
        };
    }

    public static <E> Optional<E> findFirst(ServiceLoader<E> loader) {
        Iterator<E> iterator = loader.iterator();
        return iterator.hasNext() ? Optional.of(iterator.next()) : Optional.empty();
    }

    public static <E> Spliterator<E> emptySpliterator() {
        return new Spliterator<E>() {
            @Override public boolean tryAdvance(Consumer<? super E> action) { Objects.requireNonNull(action); return false; }
            @Override public Spliterator<E> trySplit() { return null; }
            @Override public long estimateSize() { return 0; }
            @Override public int characteristics() { return SIZED | SUBSIZED; }
        };
    }

    public static <E> Spliterator<E> singletonSpliterator(E value) {
        return new Spliterator<E>() {
            boolean remaining = true;
            @Override public boolean tryAdvance(Consumer<? super E> action) {
                Objects.requireNonNull(action);
                if (!remaining) { return false; }
                remaining = false;
                action.accept(value);
                return true;
            }
            @Override public Spliterator<E> trySplit() { return null; }
            @Override public long estimateSize() { return remaining ? 1 : 0; }
            @Override public int characteristics() {
                return ORDERED | DISTINCT | SIZED | SUBSIZED | IMMUTABLE | (value == null ? 0 : NONNULL);
            }
        };
    }

    public static java.util.Date dateFrom(java.time.Instant instant) {
        try { return new java.util.Date(instant.toEpochMilli()); }
        catch (ArithmeticException overflow) { throw new IllegalArgumentException(overflow); }
    }

    public static long contentLengthLong(java.net.URLConnection connection) {
        try { return Long.parseLong(connection.getHeaderField("Content-Length")); }
        catch (NumberFormatException invalid) { return -1; }
    }

    public static long compareAndExchange(java.util.concurrent.atomic.AtomicLong atomic, long expected, long update) {
        long observed;
        do {
            observed = atomic.get();
            if (observed != expected) { return observed; }
        } while (!atomic.compareAndSet(expected, update));
        return observed;
    }

    // TeaVM's TThread models cooperative platform threads, not Java virtual threads.
    public static boolean isVirtual(Thread thread) { return false; }
    // The Java contract permits no action: this is a processor hint, never a scheduling/yield point.
    public static void onSpinWait() { }
    public static void dumpStack() { new Exception("Stack trace").printStackTrace(); }
}
