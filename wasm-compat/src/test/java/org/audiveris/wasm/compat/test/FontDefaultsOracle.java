package org.audiveris.wasm.compat.test;

import java.nio.file.*;
import org.audiveris.omr.ui.symbol.*;

public final class FontDefaultsOracle {
    public static void main(String[] args) throws Exception {
        String initial = FontDefaultsProbe.families();
        if (!initial.equals("Bravura:SansSerif")) { throw new AssertionError("Changed source defaults: " + initial); }
        if (MusicFont.defaultMusicParam != FontFamilyDefaults.defaultMusicParam
                || TextFont.defaultTextParam != FontFamilyDefaults.defaultTextParam) {
            throw new AssertionError("Font API aliases use different parameter objects");
        }
        String result = FontDefaultsProbe.mutate();
        if (!result.equals("Bravura:SansSerif|FinaleJazz:FinaleJazzText|Bravura:SansSerif")) {
            throw new AssertionError("Parameter mutation/reset: " + result);
        }
        Path output = Path.of(args[0]); Files.createDirectories(output.getParent()); Files.writeString(output, result);
        System.out.println("Original font constant sets and shared parameter aliases verified");
    }
}
