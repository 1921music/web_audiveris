package org.audiveris.wasm.compat.test;

import java.nio.file.*;
import java.util.*;
import org.audiveris.wasm.compat.concurrent.ConcurrentSkipListMap;

public final class OrderedMapOracle {
    public static void main(String[] args) throws Exception {
        String expected = OrderedMapProbe.exercise();
        String actual = OrderedMapCases.exercise(new ConcurrentSkipListMap<>()) + "|"
                + OrderedMapCases.exercise(new ConcurrentSkipListMap<>(Comparator.reverseOrder()));
        if (!expected.equals(actual)) { throw new AssertionError(expected + " != " + actual); }
        ConcurrentSkipListMap<Integer, String> map = new ConcurrentSkipListMap<>(); map.put(1, "one");
        var clone = map.clone(); clone.put(2, "two");
        if (map.containsKey(2) || !clone.equals(Map.of(1, "one", 2, "two"))) { throw new AssertionError("clone"); }
        TreeMap<Integer, String> sorted = new TreeMap<>(Comparator.reverseOrder()); sorted.putAll(clone);
        if (new ConcurrentSkipListMap<>(sorted).firstKey() != 2) { throw new AssertionError("Sorted constructor comparator"); }
        Path output = Path.of(args[0]); Files.createDirectories(output.getParent()); Files.writeString(output, expected);
        Files.writeString(output.resolveSibling("index-reference.txt"), IndexCases.exercise());
        String setExpected = OrderedMapProbe.sets();
        String setActual = OrderedSetCases.exercise(new org.audiveris.wasm.compat.concurrent.ConcurrentSkipListSet<Integer>()) + "|"
                + OrderedSetCases.exercise(new org.audiveris.wasm.compat.concurrent.ConcurrentSkipListSet<>(Comparator.reverseOrder()));
        if (!setExpected.equals(setActual)) { throw new AssertionError(setExpected + " != " + setActual); }
        Files.writeString(output.resolveSibling("set-reference.txt"), setExpected);
        System.out.println("Ordered-map oracle: 24,000 randomized operations plus live-view, iterator and exception checks");
    }
}
