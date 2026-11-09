// SPDX-License-Identifier: AGPL-3.0-or-later
package org.audiveris.wasm.compat.util;

import java.io.Serializable;
import java.security.SecureRandom;

/** UUID value semantics independent of the host JDK or JavaScript string UUID implementation. */
public final class UUID implements Serializable, Comparable<UUID> {
    private static final long serialVersionUID = -4856846361193249489L;
    private final long mostSigBits, leastSigBits;
    public UUID(long mostSigBits, long leastSigBits) { this.mostSigBits = mostSigBits; this.leastSigBits = leastSigBits; }
    public long getMostSignificantBits() { return mostSigBits; }
    public long getLeastSignificantBits() { return leastSigBits; }
    public int version() { return (int) (mostSigBits >>> 12 & 15); }
    public int variant() {
        if (leastSigBits >= 0) { return 0; }
        return (leastSigBits & 0x4000_0000_0000_0000L) == 0 ? 2 : (int) (leastSigBits >>> 61);
    }
    private void requireTimeBased() { if (version() != 1) { throw new UnsupportedOperationException("Not a time-based UUID"); } }
    public long timestamp() {
        requireTimeBased();
        return (mostSigBits & 0xfff) << 48 | (mostSigBits >>> 16 & 0xffff) << 32 | mostSigBits >>> 32;
    }
    public int clockSequence() { requireTimeBased(); return (int) (leastSigBits >>> 48 & 0x3fff); }
    public long node() { requireTimeBased(); return leastSigBits & 0xffff_ffff_ffffL; }
    @Override public int compareTo(UUID other) {
        int first = Long.compare(mostSigBits, other.mostSigBits);
        return first != 0 ? first : Long.compare(leastSigBits, other.leastSigBits);
    }
    @Override public boolean equals(Object other) {
        return other instanceof UUID && ((UUID) other).mostSigBits == mostSigBits && ((UUID) other).leastSigBits == leastSigBits;
    }
    @Override public int hashCode() { long bits = mostSigBits ^ leastSigBits; return (int) (bits ^ bits >>> 32); }
    private static String hex(long value, int width) {
        String digits = Long.toHexString(value);
        return "0".repeat(Math.max(0, width - digits.length())) + digits;
    }
    @Override public String toString() {
        return hex(mostSigBits >>> 32, 8) + "-" + hex(mostSigBits >>> 16 & 0xffff, 4) + "-" + hex(mostSigBits & 0xffff, 4)
                + "-" + hex(leastSigBits >>> 48, 4) + "-" + hex(leastSigBits & 0xffff_ffff_ffffL, 12);
    }
    public static UUID fromString(String value) {
        if (value.length() > 36) { throw new IllegalArgumentException("UUID too long"); }
        String[] groups = value.split("-", -1);
        if (groups.length != 5) { throw new IllegalArgumentException("Invalid UUID"); }
        long a = component(groups[0]), b = component(groups[1]), c = component(groups[2]);
        long d = component(groups[3]), e = component(groups[4]);
        return new UUID((a & 0xffff_ffffL) << 32 | (b & 0xffff) << 16 | c & 0xffff,
                (d & 0xffff) << 48 | e & 0xffff_ffff_ffffL);
    }
    private static long component(String value) {
        if (value.isEmpty()) { throw new NumberFormatException(); }
        // The JDK fallback parser accepts a leading plus and noncanonical group widths.
        int start = value.charAt(0) == '+' ? 1 : 0;
        if (start == value.length()) { throw new NumberFormatException(); }
        long parsed = 0;
        for (int i = start; i < value.length(); i++) {
            int digit = Character.digit(value.charAt(i), 16);
            if (digit < 0 || parsed > (Long.MAX_VALUE - digit) / 16) { throw new NumberFormatException(); }
            parsed = parsed * 16 + digit;
        }
        return parsed;
    }
    private static final class RandomHolder { static final SecureRandom RANDOM = new SecureRandom(); }
    public static UUID randomUUID() {
        byte[] bytes = new byte[16]; RandomHolder.RANDOM.nextBytes(bytes);
        bytes[6] = (byte) (bytes[6] & 15 | 0x40); bytes[8] = (byte) (bytes[8] & 63 | 0x80);
        return fromBytes(bytes);
    }
    private static UUID fromBytes(byte[] bytes) {
        long first = 0, second = 0;
        for (int i = 0; i < 8; i++) { first = first << 8 | bytes[i] & 255; second = second << 8 | bytes[i + 8] & 255; }
        return new UUID(first, second);
    }
}
