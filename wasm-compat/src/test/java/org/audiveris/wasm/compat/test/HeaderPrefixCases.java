package org.audiveris.wasm.compat.test;

import java.util.*;
import org.audiveris.wasm.compat.platform.HeaderListCopies;

/** Duplicates in the tail must survive when also present in the retained prefix. */
public final class HeaderPrefixCases {
    public static int verify(boolean originalJvm) {
        Random random = new Random(917531); int cases = 0;
        for (int size = 0; size < 32; size++) {
            List<Integer> input = new ArrayList<>();
            for (int i = 0; i < size; i++) { int value = random.nextInt(8); input.add(value == 0 ? null : value); }
            for (int cut = 0; cut <= size; cut++) {
                List<Integer> actual = new ArrayList<>(input), expected = new ArrayList<>(), prefix = new ArrayList<>();
                for (int i = 0; i < cut; i++) { prefix.add(input.get(i)); }
                for (Integer value : input) { if (prefix.contains(value)) { expected.add(value); } }
                boolean changed = actual.retainAll(HeaderListCopies.prefix(actual, 0, cut));
                if (!actual.equals(expected) || changed != (expected.size() != size)) { throw new AssertionError("Header prefix " + size + "/" + cut); }
                if (originalJvm) {
                    List<Integer> original = new ArrayList<>(input);
                    boolean originalChanged = original.retainAll(original.subList(0, cut));
                    if (!original.equals(actual) || originalChanged != changed) { throw new AssertionError("JVM prefix " + size + "/" + cut); }
                }
                cases++;
            }
        }
        return cases;
    }
}
