// SPDX-License-Identifier: AGPL-3.0-or-later
package org.audiveris.wasm.compat.platform;

import org.audiveris.omr.sheet.Sheet;
import org.audiveris.omr.sheet.Picture;

/** The host supplies page pixels; a Worker never falls back to desktop file loading. */
public final class HeadlessModels {
    private HeadlessModels() { }
    public static Sheet requireInstalledSheet(Sheet sheet) {
        if (sheet == null) { throw new IllegalStateException("Page pixels have not been supplied to this Worker"); }
        return sheet;
    }
    public static Picture requireInstalledPicture(Picture picture) {
        if (picture == null) { throw new IllegalStateException("Page pixels have not been supplied to this Worker"); }
        return picture;
    }
}
