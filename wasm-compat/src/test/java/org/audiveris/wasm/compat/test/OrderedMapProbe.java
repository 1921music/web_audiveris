package org.audiveris.wasm.compat.test;

import java.util.*;
import java.util.concurrent.ConcurrentSkipListMap;
import org.teavm.jso.JSExport;

public final class OrderedMapProbe {
    public static void main(String[] args) { }
    @JSExport public static String index() { return IndexCases.exercise(); }
    @JSExport public static String sets() {
        return OrderedSetCases.exercise(new java.util.concurrent.ConcurrentSkipListSet<>()) + "|"
                + OrderedSetCases.exercise(new java.util.concurrent.ConcurrentSkipListSet<>(Comparator.reverseOrder()));
    }
    @JSExport public static String exercise() {
        return OrderedMapCases.exercise(new ConcurrentSkipListMap<>()) + "|"
                + OrderedMapCases.exercise(new ConcurrentSkipListMap<>(Comparator.reverseOrder()));
    }
}
