package org.audiveris.wasm.compat.test;

import java.util.*;
import java.util.concurrent.ConcurrentNavigableMap;

/** Same operation stream for the JDK oracle, portable implementation and standard-name Wasm caller. */
public final class OrderedMapCases {
    public static String exercise(ConcurrentNavigableMap<Integer, String> map) {
        long hash = 17; int state = 73591;
        boolean reverse = map.comparator() != null && map.comparator().compare(0, 1) > 0;
        for (int i = 0; i < 12000; i++) {
            state = state * 1664525 + 1013904223;
            int key = (state >>> 16) % 97, operation = (state >>> 8) % 12;
            String value = "v" + ((state >>> 1) % 23), result;
            switch (operation) {
                case 0: result = map.put(key, value); break;
                case 1: result = map.putIfAbsent(key, value); break;
                case 2: result = map.remove(key); break;
                case 3: result = map.replace(key, value); break;
                case 4: result = "" + map.replace(key, "v4", value); break;
                case 5: result = "" + map.remove(key, "v5"); break;
                case 6: result = "" + map.floorEntry(key); break;
                case 7: result = "" + map.ceilingEntry(key); break;
                case 8: result = "" + map.lowerKey(key) + ":" + map.higherKey(key); break;
                case 9: result = map.compute(key, (k, old) -> old == null ? value : old + "x"); break;
                case 10: result = map.merge(key, value, (old, extra) -> old.length() > 9 ? null : old + extra); break;
                default: result = "" + map.descendingMap().subMap(reverse ? 25 : 75, true, reverse ? 75 : 25, false); break;
            }
            hash = hash * 31 + Objects.hashCode(result);
            if (i % 53 == 0) {
                Iterator<String> values = map.values().iterator(); int n = 0;
                while (values.hasNext()) { values.next(); if (n++ % 3 == 0) { values.remove(); } }
                hash = hash * 31 + map.hashCode();
            }
        }
        // Exhaust small bounds, including empty views and navigation from outside a view.
        for (int low = 0; low < 10; low++) { for (int high = low; high < 10; high++) { for (int flags = 0; flags < 4; flags++) {
            map.clear(); for (int key = 0; key < 10; key++) { map.put(key, "b" + key); }
            int from = reverse ? high : low, to = reverse ? low : high;
            ConcurrentNavigableMap<Integer, String> view = map.subMap(from, (flags & 1) != 0, to, (flags & 2) != 0);
            hash = hash * 31 + view.toString().hashCode();
            for (int key = -1; key <= 10; key++) {
                String navigation = "" + view.lowerEntry(key) + view.floorEntry(key) + view.ceilingEntry(key) + view.higherEntry(key);
                hash = hash * 31 + navigation.hashCode();
            }
            hash = hash * 31 + view.descendingMap().toString().hashCode();
            Iterator<?> entries = view.descendingMap().entrySet().iterator(); int n = 0;
            while (entries.hasNext()) { entries.next(); if (n++ % 2 == 0) { entries.remove(); } }
            hash = hash * 31 + map.hashCode(); view.clear(); hash = hash * 31 + map.hashCode();
        } } }
        map.clear(); for (int i = 0; i < 10; i++) { map.put(i, "n" + i); }
        ConcurrentNavigableMap<Integer, String> middle = map.subMap(reverse ? 8 : 2, true, reverse ? 2 : 8, false);
        middle.put(5, "changed"); middle.navigableKeySet().remove(3);
        hash = hash * 31 + Objects.hashCode(middle.pollFirstEntry());
        hash = hash * 31 + Objects.hashCode(middle.descendingMap().pollFirstEntry());
        middle.headMap(6).values().remove("changed");
        hash = hash * 31 + map.hashCode();
        middle.clear(); hash = hash * 31 + map.hashCode();
        Iterator<Integer> iterator = map.navigableKeySet().iterator();
        int first = iterator.next(); map.put(20, "new"); map.remove(8); iterator.remove();
        int count = 0; while (iterator.hasNext()) { iterator.next(); if (++count > 30) { throw new AssertionError("Unbounded iteration"); } }
        if (map.containsKey(first) || !map.containsKey(20)) { throw new AssertionError("Live iterator removal"); }
        // Weakly consistent iterators need not observe exactly the same racing edits.
        // Verify their contract, but exclude permitted observation differences from the oracle hash.
        return hash + ":" + map + ":" + exceptions(map);
    }
    private static String exceptions(ConcurrentNavigableMap<Integer, String> map) {
        StringBuilder result = new StringBuilder();
        boolean reverse = map.comparator() != null && map.comparator().compare(0, 1) > 0;
        Runnable[] cases = {
            () -> map.put(null, "x"), () -> map.put(1, null), () -> map.get(null),
            () -> map.containsValue(null), () -> map.replace(1, null),
            () -> map.replace(1, null, "x"), () -> map.subMap(reverse ? 2 : 9, reverse ? 9 : 2),
            () -> map.subMap(reverse ? 6 : 2, reverse ? 2 : 6).put(9, "outside"),
            () -> map.firstEntry().setValue("mutable"),
            () -> { Iterator<?> it = map.values().iterator(); it.remove(); }
        };
        for (Runnable action : cases) {
            try { action.run(); result.append("NONE"); }
            catch (RuntimeException expected) { result.append(expected.getClass().getSimpleName()); }
            result.append(',');
        }
        if (map.remove(1, null)) { throw new AssertionError("Null removal matched"); }
        return result.toString();
    }
}
