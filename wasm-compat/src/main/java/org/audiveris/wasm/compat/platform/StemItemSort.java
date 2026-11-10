// SPDX-License-Identifier: AGPL-3.0-or-later
package org.audiveris.wasm.compat.platform;

import java.util.*;

/** Only the mixed stem-item comparator needs the pinned JVM's comparison schedule. */
public final class StemItemSort {
    private StemItemSort() { }
    @SuppressWarnings({"unchecked", "rawtypes"})
    public static <T> void sort(List<T> list, Comparator<? super T> comparator) {
        Object[] values = list.toArray();
        sortArray(values, values.length, (Comparator) comparator);
        var iterator = list.listIterator();
        for (Object value : values) { iterator.next(); iterator.set((T) value); }
    }
    private static void sortArray(Object[] values, int length, Comparator<Object> comparator) {
        Arrays.sort(values, 0, length, comparator);
    }
}
