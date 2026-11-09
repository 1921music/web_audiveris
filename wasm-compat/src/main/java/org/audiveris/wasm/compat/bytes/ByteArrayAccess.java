// SPDX-License-Identifier: AGPL-3.0-or-later
package org.audiveris.wasm.compat.bytes;

/** Unaligned byte-array reads/writes without native memory or VarHandles. */
final class ByteArrayAccess {
    private ByteArrayAccess() { }
    private static void check(int offset, int width, int length) {
        if (offset < 0 || offset > length - width) { throw new ArrayIndexOutOfBoundsException(offset); }
    }
    static int getShort(byte[] a, int p, boolean little) {
        check(p, 2, a.length);
        return little ? (a[p] & 255) | (a[p + 1] & 255) << 8 : (a[p] & 255) << 8 | (a[p + 1] & 255);
    }
    static int getInt(byte[] a, int p, boolean little) {
        check(p, 4, a.length);
        return little ? (a[p] & 255) | (a[p + 1] & 255) << 8 | (a[p + 2] & 255) << 16 | a[p + 3] << 24
                : a[p] << 24 | (a[p + 1] & 255) << 16 | (a[p + 2] & 255) << 8 | (a[p + 3] & 255);
    }
    static long getLong(byte[] a, int p, boolean little) {
        check(p, 8, a.length);
        long first = getInt(a, p, little) & 0xffff_ffffL, second = getInt(a, p + 4, little) & 0xffff_ffffL;
        return little ? first | second << 32 : first << 32 | second;
    }
    static void put(byte[] a, int p, long value, int width, boolean little) {
        check(p, width, a.length);
        for (int i = 0; i < width; i++) { a[p + i] = (byte) (value >>> ((little ? i : width - 1 - i) * 8)); }
    }
}

