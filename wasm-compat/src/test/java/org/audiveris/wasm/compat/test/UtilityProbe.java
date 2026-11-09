package org.audiveris.wasm.compat.test;

import java.util.*;

public final class UtilityProbe {
    public interface DefaultMethods {
        default void normal() { }
        static void staticMethod() { }
        void abstractMethod();
        private void privateMethod() { }
    }
    static final String[] UUID_INPUTS = {null, "", "0-0-0-0-0", "1-1-1-1-1", "+1-1-1-1-1", "123456789-0-0-0-0", "ffffffff-ffff-ffff-ffff-ffffffffffff", "00000000-0000-0000-0000-000000000000", "0-0-0-0-", "0-0-0-0-0-0", "g-0-0-0-0", "00000000-0000-0000-0000-0000000000000", "\u0661-1-1-1-1"};
    private static String outcome(java.util.function.Supplier<?> call) {
        try { return String.valueOf(call.get()); } catch (RuntimeException e) { return e.getClass().getName(); }
    }
    public static String values() {
        StringBuilder result = new StringBuilder(); Random random = new Random(391);
        for (int i = 0; i < 128; i++) {
            long hi = random.nextLong(), lo = random.nextLong();
            java.util.UUID id = new java.util.UUID(hi, lo);
            result.append(id).append('|').append(id.hashCode()).append('|').append(id.version()).append('|').append(id.variant()).append('|');
            result.append(outcome(id::timestamp)).append('|').append(outcome(id::clockSequence)).append('|').append(outcome(id::node)).append('|');
            result.append(java.util.UUID.fromString(id.toString()).equals(id)).append('|').append(id.getMostSignificantBits()).append('|').append(id.getLeastSignificantBits()).append('\n');
        }
        for (String input : UUID_INPUTS) { result.append(outcome(() -> java.util.UUID.fromString(input))).append('\n'); }
        Set<java.util.UUID> ids = new HashSet<>(); boolean correct = true;
        for (int i = 0; i < 64; i++) {
            java.util.UUID id = java.util.UUID.randomUUID(); correct &= id.version() == 4 && id.variant() == 2 && ids.add(id);
        }
        result.append(correct).append('\n');
        SortedMap<Integer, String> map = Collections.emptySortedMap();
        result.append(map.size()).append('|').append(map.comparator()).append('|').append(outcome(map::firstKey)).append('\n');
        result.append(outcome(() -> map.get(null))).append('|').append(outcome(() -> map.subMap(2, 1))).append('\n');
        result.append(outcome(() -> map.computeIfAbsent(3, k -> "x"))).append('\n');
        // Static methods are not currently exposed by TeaVM's getDeclaredMethods metadata.
        // That separate reflection limitation is documented rather than simulated here.
        for (String method : new String[] {"normal", "ordinary", "abstractMethod", "privateMethod"}) {
            try {
                var reflected = (method.equals("ordinary") ? ResourceProbe.LookupFixture.class : DefaultMethods.class).getDeclaredMethod(method);
                result.append(reflected.isDefault()).append('|').append(reflected.getModifiers()).append('|')
                        .append(reflected.getDeclaringClass().getName()).append('|').append(reflected.getDeclaringClass().isInterface()).append('\n');
            }
            catch (Exception failure) { result.append("reflection failure: ").append(method).append(':').append(failure.getClass().getName()).append('\n'); }
        }
        return result.toString();
    }
}
