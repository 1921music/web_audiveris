package org.audiveris.wasm.compat.test;

import java.nio.file.*;
import org.audiveris.wasm.compat.RoundingMath;

public final class MathRoundingOracle {
    public static void main(String[] args) throws Exception {
        int checks = 0;
        for (double value : MathRoundingProbe.cases()) { checks += check(value); }
        for (int i = -2048; i <= 2048; i++) {
            double tie = i * 0.5; checks += check(Math.nextDown(tie)) + check(tie) + check(Math.nextUp(tie));
        }
        long state = 0x145e19bc34L;
        for (int i = 0; i < 65536; i++) {
            state = state * 6364136223846793005L + 1442695040888963407L; checks += check(Double.longBitsToDouble(state));
        }
        Path output = Path.of(args[0]); Files.createDirectories(output.getParent());
        Files.writeString(output, MathRoundingProbe.values());
        Files.writeString(output.resolveSibling("jvm-checksum.txt"), MathRoundingProbe.checksum());
        Files.writeString(output.resolveSibling("jvm-verification.json"), "{\"checks\":" + checks + "}\n");
        System.out.println("Rounding matches JDK in " + checks + " checks");
    }
    private static int check(double value) {
        if (Double.doubleToLongBits(Math.rint(value)) != Double.doubleToLongBits(RoundingMath.rint(value))
                || Math.round(value) != RoundingMath.round(value) || Math.round((float) value) != RoundingMath.round((float) value)) {
            throw new AssertionError("Rounding mismatch at " + Double.toHexString(value));
        }
        return 3;
    }
}
