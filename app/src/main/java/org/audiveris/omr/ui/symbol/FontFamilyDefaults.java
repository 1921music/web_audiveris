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
    /** Original OmrFont staff-interline to point-size conversion, without loading a font. */
    public static int pointSize(int staffInterline) { return 4 * staffInterline; }
    static MusicFont.Constants musicConstants() { return musicConstants; }
    static TextFont.Constants textConstants() { return textConstants; }
    /** Base text fonts are created with plain style; export needs their metadata, not an AWT font. */
    public static int defaultTextPointSize() { return textConstants.defaultTextFontSize.getValue(); }

    /** Same point-size calculation as MusicFont, without initializing desktop fonts. */
    public static int headPointSize(org.audiveris.omr.sheet.Scale scale, double staffInterline) {
        final var musicFontScale = scale.getMusicFontScale();
        if (musicFontScale != null) {
            final double ratio = staffInterline / scale.getInterline();
            return (int) Math.rint(ratio * musicFontScale.getPointSize());
        }
        return 4 * (int) Math.rint(staffInterline * musicConstants.headRatio.getValue());
    }
}
