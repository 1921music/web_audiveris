// SPDX-License-Identifier: AGPL-3.0-or-later
package org.audiveris.wasm.compat.platform;

import java.util.Map;

/** Legacy .omr migration is outside the raw-pixel Worker entrypoint. */
public final class HeadlessArchives {
    private HeadlessArchives() { }
    public static void requireNoLegacyTables(Map<?, ?> tables) {
        if (tables != null && !tables.isEmpty()) {
            throw new UnsupportedOperationException("Legacy .omr table migration is not supported in this Worker");
        }
    }
}
