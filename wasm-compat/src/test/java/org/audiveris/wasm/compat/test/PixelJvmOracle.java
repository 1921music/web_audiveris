package org.audiveris.wasm.compat.test;

import java.nio.file.*;

/** JVM-only fixture I/O stays outside the Wasm entrypoint and production pixel API. */
public final class PixelJvmOracle {
    public static void main(String[] args) throws Exception {
        PixelProbe.ownership();
        String simple = PixelProbe.threshold(), global = PixelProbe.globalCases(), adaptive = PixelProbe.adaptiveCases(), runs;
        String scaleFixtures;
        try { runs = PixelProbe.runCases(); scaleFixtures = PixelProbe.scaleFixtures(); } finally { org.audiveris.omr.util.OmrExecutors.shutdown(); }
        Files.writeString(Path.of(args[0]), "{\"threshold\":\"" + simple + "\",\"globalCases\":\"" + global + "\",\"adaptiveCases\":\"" + adaptive + "\",\"runCases\":\"" + runs + "\",\"bulkOracle\":\"" + PixelProbe.bulkOracle() + "\",\"scaleFixtures\":\"" + scaleFixtures + "\"}\n");
        if (args.length > 1) { PixelSamples.prepare(Path.of(args[1]), Path.of(args[0]).getParent().resolve("samples")); }
        System.out.println("Gray input, threshold/adaptive filters and run-table JVM oracle passed");
    }
}
