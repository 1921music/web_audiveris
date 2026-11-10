// SPDX-License-Identifier: AGPL-3.0-or-later
package org.audiveris.wasm.compat.platform;

import java.util.ArrayList;
import java.util.List;

/** Snapshot only the header callers' inline retainAll(prefix) arguments. */
public final class HeaderListCopies {
    private HeaderListCopies() { }
    public static List<?> prefix(List<?> values, int from, int to) {
        return new ArrayList<>(values.subList(from, to));
    }
}
