package org.audiveris.wasm.compat.test;

import org.teavm.jso.JSExport;

/** Standard-name callers; raw bits distinguish signed zero and preserve finite results exactly. */
public final class MathRoundingProbe {
    public static void main(String[] args) { }
    public static double[] cases() {
        return new double[]{-3.5,-2.5,-1.5,-0.5,-0.0,0.0,0.5,1.5,2.5,3.5,
                -0.49999999999999994,0.49999999999999994,1.4999999999999998,-1.4999999999999998,
                Double.MIN_VALUE,-Double.MIN_VALUE,Double.MAX_VALUE,-Double.MAX_VALUE,
                0x1.0p52, -0x1.0p52,0x1.0p63,-0x1.0p63,Double.NaN,Double.POSITIVE_INFINITY,Double.NEGATIVE_INFINITY};
    }
    @JSExport public static String values() {
        StringBuilder result = new StringBuilder();
        for (double value : cases()) {
            result.append(Long.toHexString(Double.doubleToLongBits(Math.rint(value)))).append(':')
                    .append(Math.round(value)).append(':').append(Math.round((float) value)).append(';');
        }
        return result.toString();
    }
    @JSExport public static String checksum() {
        long state = 0x68a4fb25139dL, hash = 13;
        for (int i = 0; i < 8192; i++) {
            state = state * 6364136223846793005L + 1442695040888963407L;
            double value = Double.longBitsToDouble(state);
            hash = hash * 31 + Double.doubleToLongBits(Math.rint(value));
            hash = hash * 31 + Math.round(value); hash = hash * 31 + Math.round((float) value);
        }
        return Long.toHexString(hash);
    }
}
