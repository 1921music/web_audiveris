// SPDX-License-Identifier: AGPL-3.0-or-later
package org.audiveris.wasm.compat.bytes;

/** API-compatible replacement of the JDK little-endian byte-array utility. */
public final class ByteArrayLittleEndian {
    private ByteArrayLittleEndian() { }
    public static boolean getBoolean(byte[] a, int p) { return a[p] != 0; }
    public static char getChar(byte[] a, int p) { return (char) ByteArrayAccess.getShort(a, p, true); }
    public static short getShort(byte[] a, int p) { return (short) ByteArrayAccess.getShort(a, p, true); }
    public static int getUnsignedShort(byte[] a, int p) { return ByteArrayAccess.getShort(a, p, true); }
    public static int getInt(byte[] a, int p) { return ByteArrayAccess.getInt(a, p, true); }
    public static long getLong(byte[] a, int p) { return ByteArrayAccess.getLong(a, p, true); }
    public static float getFloat(byte[] a, int p) { return Float.intBitsToFloat(getInt(a, p)); }
    public static float getFloatRaw(byte[] a, int p) { return Float.intBitsToFloat(getInt(a, p)); }
    public static double getDouble(byte[] a, int p) { return Double.longBitsToDouble(getLong(a, p)); }
    public static double getDoubleRaw(byte[] a, int p) { return Double.longBitsToDouble(getLong(a, p)); }
    public static void setBoolean(byte[] a, int p, boolean value) { a[p] = (byte) (value ? 1 : 0); }
    public static void setChar(byte[] a, int p, char value) { ByteArrayAccess.put(a, p, value, 2, true); }
    public static void setShort(byte[] a, int p, short value) { ByteArrayAccess.put(a, p, value, 2, true); }
    public static void setUnsignedShort(byte[] a, int p, int value) { ByteArrayAccess.put(a, p, value, 2, true); }
    public static void setInt(byte[] a, int p, int value) { ByteArrayAccess.put(a, p, value, 4, true); }
    public static void setLong(byte[] a, int p, long value) { ByteArrayAccess.put(a, p, value, 8, true); }
    public static void setFloat(byte[] a, int p, float value) { setInt(a, p, Float.floatToIntBits(value)); }
    public static void setFloatRaw(byte[] a, int p, float value) { setInt(a, p, Float.floatToRawIntBits(value)); }
    public static void setDouble(byte[] a, int p, double value) { setLong(a, p, Double.doubleToLongBits(value)); }
    public static void setDoubleRaw(byte[] a, int p, double value) { setLong(a, p, Double.doubleToRawLongBits(value)); }
}

