package org.audiveris.wasm.compat.test;

import java.util.Random;
import org.audiveris.wasm.compat.FloatingPointParser;

final class FloatingPointTest {
    private static int checks;
    private static String outcome(String input, boolean compat, boolean single) {
        try {
            return single ? Integer.toHexString(Float.floatToIntBits(compat ? FloatingPointParser.parseFloat(input) : Float.parseFloat(input)))
                    : Long.toHexString(Double.doubleToLongBits(compat ? FloatingPointParser.parseDouble(input) : Double.parseDouble(input)));
        } catch (RuntimeException failure) { return failure.getClass().getName(); }
    }
    private static void compare(String input) {
        for (boolean single : new boolean[] {false, true}) {
            String actual = outcome(input, true, single), expected = outcome(input, false, single);
            if (!actual.equals(expected)) { throw new AssertionError("parse " + input + ": " + actual + " != " + expected); }
            checks++;
        }
    }
    static int run() {
        for (String input : new String[] {null, "", " ", ".", "+", "-", "1_0", "1e", "1e+", "1e0bad", "NaN", "-NaN", "+NaN", "NaNd", "InfinityF", "Infinity", "-Infinity", "\u0661", "0x1", "0xp1", "0x.p1", "0x1.p1", "0x1.2p+2f", "0x1p-2147483648", "0x1p2147483647", "-0e9999999999999", "1e-99999999999", "1e99999999999", "  1.25d\r\n", "1.000000059604644775390625000000000001", "4.9e-324", "2.2250738585072012e-308", "0x1.fffffffffffffp1023", "0x1p-1075", "0x1.0000000000001p-1075", "0x1.ffffffp127", "0x1.fffffep127", "0x1p-150"}) { compare(input); }
        compare("0".repeat(1000) + "1e0");
        compare("1" + "0".repeat(1000) + "e-1000");
        Random random = new Random(427);
        for (int i = 0; i < 5000; i++) {
            double d = Double.longBitsToDouble(random.nextLong());
            float f = Float.intBitsToFloat(random.nextInt());
            compare(Double.toString(d)); compare(Double.toHexString(d));
            compare(Float.toString(f)); compare(Float.toHexString(f));
        }
        return checks;
    }
}
