// SPDX-License-Identifier: AGPL-3.0-or-later
package org.audiveris.wasm.compat;

public final class ThreadContexts {
    private ThreadContexts() { }
    /** The compiler uses the stored parent field on Wasm, avoiding overrides of the public getter. */
    public static ClassLoader inheritedContext() {
        Thread parent = Thread.currentThread();
        return parent == null ? ClassLoader.getSystemClassLoader() : parent.getContextClassLoader();
    }
}
