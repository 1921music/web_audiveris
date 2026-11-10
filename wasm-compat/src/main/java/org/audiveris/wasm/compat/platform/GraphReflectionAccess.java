// SPDX-License-Identifier: AGPL-3.0-or-later
package org.audiveris.wasm.compat.platform;

import java.lang.reflect.Constructor;
import java.lang.reflect.Modifier;
import java.util.Objects;

/** Access check specialized to JGraphT SupplierUtil, not a general caller-sensitive JDK API. */
public final class GraphReflectionAccess {
    private GraphReflectionAccess() { }
    @SuppressWarnings("deprecation")
    public static boolean canAccess(Constructor<?> constructor, Object receiver) {
        Objects.requireNonNull(constructor);
        if (receiver != null) { throw new IllegalArgumentException("Constructor receiver must be null"); }
        if (constructor.isAccessible()) { return true; }
        Class<?> declaring = constructor.getDeclaringClass();
        if (Modifier.isPublic(constructor.getModifiers()) && Modifier.isPublic(declaring.getModifiers())) { return true; }
        if (declaring.getPackageName().equals("org.jgrapht.util")) {
            throw new UnsupportedOperationException("Non-public constructors in SupplierUtil's own package need explicit access metadata");
        }
        // SupplierUtil is not a subclass or nestmate of the application's target classes.
        return false;
    }
}
