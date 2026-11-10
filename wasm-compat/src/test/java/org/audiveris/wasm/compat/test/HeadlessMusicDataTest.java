package org.audiveris.wasm.compat.test;

import java.util.*;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.audiveris.wasm.compat.platform.HeadlessMusicData;

final class HeadlessMusicDataTest {
    static void run() {
        Set<Integer> expected = new HashSet<>(Arrays.asList(0xE0A2, 0xE1D3, 0xE1D5, 0xE1D7, 0xE1D9, 0xE1E7));
        if (!expected.equals(HeadlessMusicData.musicCodes())) { throw new AssertionError("Review upstream metronome glyph set"); }
        // TeaVM rejects Java's x{...} syntax. The private fragment is adapted to equivalent BMP escapes.
        String expression = HeadlessMusicData.beatUnitRegexp();
        Pattern pattern = Pattern.compile("[" + expression + "]");
        for (int code : expected) {
            if (!pattern.matcher(new String(Character.toChars(code))).matches()) { throw new AssertionError("Lost beat unit"); }
        }
        for (String other : Arrays.asList("A", " ", "", "\uE000", "\uE0A2\uE1D3")) {
            if (pattern.matcher(other).matches()) { throw new AssertionError("Invalid beat unit matched"); }
        }
        Pattern tempo = Pattern.compile("[^\\(" + expression + "]");
        for (int code : expected) { if (tempo.matcher(Character.toString((char) code)).matches()) { throw new AssertionError("Tempo prefix includes beat glyph"); } }
        if (tempo.matcher("(").matches() || !tempo.matcher("A").matches()) { throw new AssertionError("Tempo prefix changed"); }
    }
    static void jvmReference() {
        String original = HeadlessMusicData.musicCodes().stream().map(code -> "\\x{" + Integer.toHexString(code) + "}").collect(Collectors.joining());
        for (String prefix : Arrays.asList("[", "[^\\(")) {
            Pattern reference = Pattern.compile(prefix + original + "]"), adapted = Pattern.compile(prefix + HeadlessMusicData.beatUnitRegexp() + "]");
            for (int code = 0; code <= 0xFFFF; code++) {
                String value = Character.toString((char) code);
                if (reference.matcher(value).matches() != adapted.matcher(value).matches()) { throw new AssertionError("Metronome fragment changed " + code); }
            }
        }
    }
}
