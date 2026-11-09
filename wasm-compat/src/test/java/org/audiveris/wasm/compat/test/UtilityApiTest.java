package org.audiveris.wasm.compat.test;

import java.util.*;
import java.util.function.Supplier;
import org.audiveris.wasm.compat.SortedMaps;

final class UtilityApiTest {
    private static int checks;
    private static void check(boolean ok, String label) { checks++; if (!ok) { throw new AssertionError(label); } }
    private static String outcome(Supplier<?> call) {
        try { return String.valueOf(call.get()); } catch (RuntimeException e) { return e.getClass().getName(); }
    }
    static int run() throws Exception {
        Random random = new Random(712);
        for (int i = 0; i < 10000; i++) {
            long hi = random.nextLong(), lo = random.nextLong();
            var a = new org.audiveris.wasm.compat.util.UUID(hi, lo);
            var b = new java.util.UUID(hi, lo);
            check(a.toString().equals(b.toString()), "UUID format");
            check(a.hashCode() == b.hashCode() && a.version() == b.version() && a.variant() == b.variant(), "UUID metadata");
            check(a.equals(org.audiveris.wasm.compat.util.UUID.fromString(b.toString())), "UUID roundtrip");
            check(outcome(a::timestamp).equals(outcome(b::timestamp)), "UUID timestamp");
            check(outcome(a::clockSequence).equals(outcome(b::clockSequence)), "UUID clock sequence");
            check(outcome(a::node).equals(outcome(b::node)), "UUID node");
            long next = random.nextLong();
            check(Integer.signum(a.compareTo(new org.audiveris.wasm.compat.util.UUID(next, lo))) == Integer.signum(b.compareTo(new java.util.UUID(next, lo))), "UUID ordering");
        }
        for (String input : UtilityProbe.UUID_INPUTS) {
            check(outcome(() -> org.audiveris.wasm.compat.util.UUID.fromString(input)).equals(outcome(() -> java.util.UUID.fromString(input))), "UUID parsing: " + input);
        }
        Set<String> ids = new HashSet<>();
        for (int i = 0; i < 64; i++) {
            var uuid = org.audiveris.wasm.compat.util.UUID.randomUUID();
            check(uuid.version() == 4 && uuid.variant() == 2 && ids.add(uuid.toString()), "UUID secure random shape/uniqueness");
        }
        TreeMap<Integer, String> source = new TreeMap<>(); source.put(1, "one"); source.put(3, "three");
        SortedMap<Integer, String> actual = SortedMaps.unmodifiableSortedMap(source), expected = Collections.unmodifiableSortedMap(source);
        source.put(2, "two");
        check(actual.equals(expected) && actual.subMap(1, 3).equals(expected.subMap(1, 3)), "sorted map live ranges");
        for (var map : Arrays.asList(actual, SortedMaps.<Integer, String>emptySortedMap())) {
            check(outcome(() -> map.put(1, "value")).equals("java.lang.UnsupportedOperationException"), "map put");
            check(outcome(() -> map.remove(999)).equals("java.lang.UnsupportedOperationException"), "map remove");
            check(outcome(() -> { map.putAll(Map.of()); return null; }).equals("java.lang.UnsupportedOperationException"), "empty putAll");
            check(outcome(() -> map.computeIfAbsent(999, k -> "x")).equals("java.lang.UnsupportedOperationException"), "map compute");
            check(outcome(() -> map.remove(999, "missing")).equals("java.lang.UnsupportedOperationException"), "map conditional remove");
        }
        check(outcome(() -> actual.entrySet().iterator().next().setValue("bad")).equals("java.lang.UnsupportedOperationException"), "entry immutability");
        SortedMap<Integer, String> empty = SortedMaps.emptySortedMap(), jdkEmpty = Collections.emptySortedMap();
        check(outcome(() -> empty.get(null)).equals(outcome(() -> jdkEmpty.get(null))), "empty map null lookup");
        check(outcome(() -> empty.subMap(2, 1)).equals(outcome(() -> jdkEmpty.subMap(2, 1))), "empty map range");
        for (var method : UtilityProbe.DefaultMethods.class.getDeclaredMethods()) {
            check(org.audiveris.wasm.compat.StandardApis.methodIsDefault(method) == method.isDefault(), "default method flags");
        }
        return checks;
    }
}
