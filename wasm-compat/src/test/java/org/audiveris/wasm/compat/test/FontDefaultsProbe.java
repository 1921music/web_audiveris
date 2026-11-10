package org.audiveris.wasm.compat.test;

import java.util.Map;
import org.audiveris.omr.sheet.Params;
import org.audiveris.omr.ui.symbol.FontFamilyDefaults;
import org.audiveris.omr.ui.symbol.MusicFamily;
import org.audiveris.omr.ui.symbol.TextFamily;
import org.audiveris.wasm.compat.platform.HeadlessConfiguration;
import org.teavm.jso.JSExport;

public final class FontDefaultsProbe {
    public static void main(String[] args) { }
    @JSExport public static void configure(boolean overrides) {
        HeadlessConfiguration.configure(overrides ? Map.of(
                "org.audiveris.omr.ui.symbol.MusicFont.defaultMusicFamily", "Leland",
                "org.audiveris.omr.ui.symbol.TextFont.defaultTextFamily", "Serif") : Map.of(), Map.of());
    }
    @JSExport public static String families() {
        Params.BookParams params = new Params.BookParams(); params.completeParams(); params.setParents(null);
        if (!"English".equals(org.audiveris.omr.text.Language.DEFINED_LANGUAGES.fullNameOf("eng"))) {
            throw new AssertionError("Missing language configuration resource");
        }
        return params.musicFamily.getValue().name() + ":" + params.textFamily.getValue().name();
    }
    @JSExport public static String mutate() {
        String before = families();
        FontFamilyDefaults.defaultMusicParam.setSpecific(MusicFamily.FinaleJazz);
        FontFamilyDefaults.defaultTextParam.setSpecific(TextFamily.FinaleJazzText);
        String changed = families();
        FontFamilyDefaults.defaultMusicParam.setSpecific(null); FontFamilyDefaults.defaultTextParam.setSpecific(null);
        return before + "|" + changed + "|" + families();
    }
}
