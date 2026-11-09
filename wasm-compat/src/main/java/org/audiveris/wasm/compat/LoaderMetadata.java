// SPDX-License-Identifier: AGPL-3.0-or-later
package org.audiveris.wasm.compat;

public final class LoaderMetadata {
    private LoaderMetadata() { }
    public static String validateName(String name) {
        if (name != null && name.isEmpty()) { throw new IllegalArgumentException("ClassLoader name must not be empty"); }
        return name;
    }
    public static ClassLoader platformLoader() { return ClassLoader.getSystemClassLoader(); }
    // Class definition/loading is not parallel in the monolithic Wasm class space.
    public static boolean registerParallel() { return false; }
    public static boolean isParallel(ClassLoader loader) { return false; }
}
