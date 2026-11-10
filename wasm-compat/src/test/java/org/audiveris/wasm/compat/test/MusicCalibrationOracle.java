package org.audiveris.wasm.compat.test;

import java.nio.file.*;
import org.audiveris.omr.sheet.Scale;
import org.audiveris.omr.ui.symbol.*;

/** Keep the upstream interpolation and all native font measurements, including edge widths. */
public final class MusicCalibrationOracle {
    public static void main(String[] args) throws Exception {
        var cli = new org.audiveris.omr.CLI("Audiveris"); cli.parseParameters(new String[0]);
        var field = org.audiveris.omr.Main.class.getDeclaredField("cli"); field.setAccessible(true); field.set(null, cli);
        var scale = new Scale(new Scale.InterlineScale(20, 21, 22), new Scale.LineScale(4, 4, 4), new Scale.BeamScale(10, false), null, null);
        int checks = 0;
        for (MusicFamily family : MusicFamily.values()) {
            for (int i = 0; i <= 128; i++) {
                double width = i * 0.5 + 0.03125;
                int expected = MusicFont.getHeadFont(family, scale, 0).buildMusicFontScale(width).getPointSize();
                int actual = SymbolGeometry.musicFontScale(family, width).getPointSize();
                if (expected != actual) { throw new AssertionError("Music calibration " + family + " / " + width + ": " + expected + " / " + actual); }
                checks++;
            }
        }
        Path output = Path.of(args[0]); Files.createDirectories(output.getParent());
        Files.writeString(output, "{\"checks\":" + checks + ",\"families\":" + MusicFamily.values().length + ",\"samePointSizes\":true}\n");
        System.out.println("Original music-font interpolation matches: " + checks + " widths");
    }
}
