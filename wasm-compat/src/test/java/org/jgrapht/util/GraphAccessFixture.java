package org.jgrapht.util;

import java.lang.reflect.Constructor;

/** JVM-only caller with SupplierUtil's package access, outside the target classes' nests. */
public final class GraphAccessFixture {
    public static boolean query(Constructor<?> constructor, Object receiver) { return constructor.canAccess(receiver); }
    private static final class OwnPrivate { private OwnPrivate() { } }
    public static Class<?> ownType() { return OwnPrivate.class; }
}
