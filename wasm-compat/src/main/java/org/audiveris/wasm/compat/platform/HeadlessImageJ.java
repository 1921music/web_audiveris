// SPDX-License-Identifier: AGPL-3.0-or-later
package org.audiveris.wasm.compat.platform;

public final class HeadlessImageJ {
    private HeadlessImageJ() { }
    public static java.awt.Font requireFont(java.awt.Font font) {
        if (font == null) { throw new UnsupportedOperationException("ImageJ text rendering requires an explicit font backend"); }
        return font;
    }
}
