// SPDX-License-Identifier: AGPL-3.0-or-later
package org.audiveris.wasm.compat.lang;

public final class Modules {
    private Modules() { }
    public static Module forLoader(ClassLoader loader) { return Module.forLoader(loader); }
    public static Module forClass(Class<?> type) { return Module.forClass(type); }
}
