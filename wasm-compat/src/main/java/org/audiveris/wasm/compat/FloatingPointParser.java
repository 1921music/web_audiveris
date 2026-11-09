// SPDX-License-Identifier: AGPL-3.0-or-later
package org.audiveris.wasm.compat;

import java.math.BigInteger;

/** Exact decimal/hexadecimal fallback parsing, with one round-to-nearest-even step. */
public final class FloatingPointParser {
    private FloatingPointParser() { }
    public static double parseDouble(String text) { return Double.longBitsToDouble(bits(text, 52, -1022, 1023)); }
    public static float parseFloat(String text) { return Float.intBitsToFloat((int) bits(text, 23, -126, 127)); }

    private static long bits(String input, int fractionBits, int minimumExponent, int maximumExponent) {
        String text = input.trim(); // Deliberately matches Java's ASCII trim rule, not strip().
        int end = text.length(), start = 0;
        if (end == 0) { throw new NumberFormatException(); }
        boolean negative = text.charAt(0) == '-';
        if (negative || text.charAt(0) == '+') { start++; }
        long sign = negative ? 1L << (fractionBits == 52 ? 63 : 31) : 0;
        long infinity = ((long) maximumExponent * 2 + 1) << fractionBits;
        String named = text.substring(start);
        if (named.equals("NaN")) { return infinity | 1L << (fractionBits - 1); }
        if (named.equals("Infinity")) { return sign | infinity; }
        char last = text.charAt(end - 1);
        if (last == 'f' || last == 'F' || last == 'd' || last == 'D') { end--; }
        if (start >= end) { throw new NumberFormatException(); }
        boolean hex = start + 2 <= end && text.charAt(start) == '0'
                && (text.charAt(start + 1) == 'x' || text.charAt(start + 1) == 'X');
        if (hex) { start += 2; }
        StringBuilder digits = new StringBuilder();
        boolean dot = false;
        int fractionalDigits = 0, cursor = start;
        for (; cursor < end; cursor++) {
            char c = text.charAt(cursor);
            if (c == '.' && !dot) { dot = true; continue; }
            boolean digit = c >= '0' && c <= '9' || hex && (c >= 'a' && c <= 'f' || c >= 'A' && c <= 'F');
            if (!digit) { break; }
            digits.append(c);
            if (dot) { fractionalDigits++; }
        }
        if (digits.length() == 0) { throw new NumberFormatException(); }
        long exponent = 0;
        if (cursor < end) {
            char marker = text.charAt(cursor++);
            if (hex ? marker != 'p' && marker != 'P' : marker != 'e' && marker != 'E') { throw new NumberFormatException(); }
            boolean minus = cursor < end && text.charAt(cursor) == '-';
            if (cursor < end && (minus || text.charAt(cursor) == '+')) { cursor++; }
            int exponentStart = cursor;
            // Saturation still permits cancellation with every mantissa digit in this input.
            long limit = 4L * text.length() + 4096;
            for (; cursor < end; cursor++) {
                char c = text.charAt(cursor);
                if (c < '0' || c > '9') { throw new NumberFormatException(); }
                exponent = Math.min(limit, exponent * 10 + c - '0');
            }
            if (cursor == exponentStart) { throw new NumberFormatException(); }
            if (minus) { exponent = -exponent; }
        } else if (hex) { throw new NumberFormatException(); }
        int first = 0, trailing = digits.length();
        while (first < trailing && digits.charAt(first) == '0') { first++; }
        if (first == trailing) { return sign; }
        while (digits.charAt(trailing - 1) == '0') { trailing--; }
        long scale = exponent - (hex ? 4L : 1L) * fractionalDigits
                + (hex ? 4L : 1L) * (digits.length() - trailing);
        String significant = digits.substring(first, trailing);
        if (!hex) {
            long magnitude = scale + significant.length() - 1;
            if (magnitude > (fractionBits == 52 ? 309 : 39)) { return sign | infinity; }
            if (magnitude < (fractionBits == 52 ? -325 : -47)) { return sign; }
        }
        BigInteger numerator = new BigInteger(significant, hex ? 16 : 10), denominator = BigInteger.ONE;
        if (hex) {
            long magnitude = numerator.bitLength() - 1L + scale;
            if (magnitude > maximumExponent + 1L) { return sign | infinity; }
            if (magnitude < minimumExponent - fractionBits - 2L) { return sign; }
            if (scale >= 0) { numerator = numerator.shiftLeft(Math.toIntExact(scale)); }
            else { denominator = denominator.shiftLeft(Math.toIntExact(-scale)); }
        } else if (scale >= 0) { numerator = numerator.multiply(BigInteger.TEN.pow(Math.toIntExact(scale))); }
        else { denominator = BigInteger.TEN.pow(Math.toIntExact(-scale)); }
        int binaryExponent = numerator.bitLength() - denominator.bitLength();
        int comparison = binaryExponent >= 0 ? numerator.compareTo(denominator.shiftLeft(binaryExponent))
                : numerator.shiftLeft(-binaryExponent).compareTo(denominator);
        if (comparison < 0) { binaryExponent--; }
        if (binaryExponent > maximumExponent) { return sign | infinity; }
        binaryExponent = Math.max(binaryExponent, minimumExponent);
        int shift = fractionBits - binaryExponent;
        if (shift >= 0) { numerator = numerator.shiftLeft(shift); }
        else { denominator = denominator.shiftLeft(-shift); }
        BigInteger[] division = numerator.divideAndRemainder(denominator);
        BigInteger rounded = division[0];
        int half = division[1].shiftLeft(1).compareTo(denominator);
        if (half > 0 || half == 0 && rounded.testBit(0)) { rounded = rounded.add(BigInteger.ONE); }
        if (rounded.bitLength() > fractionBits + 1) { rounded = rounded.shiftRight(1); binaryExponent++; }
        if (binaryExponent > maximumExponent) { return sign | infinity; }
        if (rounded.bitLength() <= fractionBits) { return sign | rounded.longValue(); }
        return sign | ((long) binaryExponent + maximumExponent) << fractionBits
                | rounded.longValue() & ((1L << fractionBits) - 1);
    }
}
