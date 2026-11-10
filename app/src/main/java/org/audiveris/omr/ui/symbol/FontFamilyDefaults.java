// SPDX-License-Identifier: AGPL-3.0-or-later
package org.audiveris.omr.ui.symbol;

import org.audiveris.omr.util.param.ConstantBasedParam;
import org.audiveris.omr.util.param.Param;

/**
 * Font-family configuration without initializing AWT fonts. The original constant
 * sets, names and parameter objects are shared with MusicFont and TextFont.
 * Initializing a nested constant-set class does not initialize its enclosing font.
 */
public final class FontFamilyDefaults {
    private static final MusicFont.Constants musicConstants = new MusicFont.Constants();
    private static final TextFont.Constants textConstants = new TextFont.Constants();

    public static final Param<MusicFamily> defaultMusicParam = new ConstantBasedParam<>(
            musicConstants.defaultMusicFamily, Param.GLOBAL_SCOPE);
    public static final Param<TextFamily> defaultTextParam = new ConstantBasedParam<>(
            textConstants.defaultTextFamily, Param.GLOBAL_SCOPE);

    private FontFamilyDefaults() { }
    static MusicFont.Constants musicConstants() { return musicConstants; }
    static TextFont.Constants textConstants() { return textConstants; }
}
