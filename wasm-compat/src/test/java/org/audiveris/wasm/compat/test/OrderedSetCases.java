package org.audiveris.wasm.compat.test;

import java.util.*;

public final class OrderedSetCases {
    public static String exercise(NavigableSet<Integer> set) {
        long hash = 19; int state = 911;
        boolean reverse = set.comparator() != null && set.comparator().compare(0, 1) > 0;
        for (int i = 0; i < 5000; i++) {
            state = state * 1664525 + 1013904223; int key = (state >>> 16) % 97;
            String result;
            switch ((state >>> 8) % 6) {
                case 0: result = "" + set.add(key); break;
                case 1: result = "" + set.remove(key); break;
                case 2: result = "" + set.lower(key) + set.floor(key) + set.ceiling(key) + set.higher(key); break;
                case 3: result = "" + set.descendingSet().subSet(reverse ? 25 : 75, true, reverse ? 75 : 25, false); break;
                case 4: result = "" + set.pollFirst(); break;
                default: result = "" + set.pollLast(); break;
            }
            hash = hash * 31 + result.hashCode();
        }
        set.clear(); set.addAll(List.of(1, 2, 3, 4, 5, 6, 7));
        NavigableSet<Integer> view = set.subSet(reverse ? 6 : 2, true, reverse ? 2 : 6, false);
        Iterator<Integer> iterator = view.descendingIterator();
        while (iterator.hasNext()) { if ((iterator.next() & 1) == 0) { iterator.remove(); } }
        hash = hash * 31 + set.hashCode(); view.clear(); hash = hash * 31 + set.hashCode();
        set.add(20); set.removeAll(List.of(1, 7));
        if (set.stream().count() != set.size()) { throw new AssertionError("Set stream traversal"); }
        return hash + ":" + set;
    }
}
