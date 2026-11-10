// SPDX-License-Identifier: AGPL-3.0-or-later
package org.audiveris.wasm.compat.platform;

import org.audiveris.omr.sheet.Sheet;

/** Bind the actual job sheet before invoking model methods that previously queried the desktop. */
public final class HeadlessSheetContext {
    private static Sheet sheet;
    private HeadlessSheetContext() { }
    public static Sheet current() { return sheet; }
    public static void bind(Sheet value) { sheet = value; }
    public static Sheet requireSheet() {
        if (sheet == null) { throw new IllegalStateException("Bind the Worker job sheet before computing glyph geometry"); }
        return sheet;
    }
}
