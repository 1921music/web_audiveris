// SPDX-License-Identifier: AGPL-3.0-or-later
package org.audiveris.wasm.compat;

import org.teavm.model.*;
import org.audiveris.wasm.compat.resources.EmbeddedResources;

final class ResourceApiPatches {
    static void apply(ClassHolder cls) {
        ValueType string = ValueType.object("java.lang.String"), url = ValueType.object("java.net.URL");
        ValueType stream = ValueType.object("java.io.InputStream"), enumeration = ValueType.object("java.util.Enumeration");
        if (cls.getName().equals("java.lang.ClassLoader")) {
            add(cls, "getResourceAsStream", "getStream", false, true, string, stream);
            add(cls, "getSystemResourceAsStream", "systemStream", true, true, string, stream);
            add(cls, "getResource", "getResource", false, false, string, url);
            add(cls, "getSystemResource", "systemResource", true, false, string, url);
            add(cls, "getResources", "getResources", false, false, string, enumeration);
            add(cls, "getSystemResources", "systemResources", true, false, string, enumeration);
            add(cls, "loadClass", "loadClass", false, false, string, ValueType.object("java.lang.Class"));
        } else if (cls.getName().equals("java.lang.Class")) {
            add(cls, "getResource", "classResource", false, false, string, url);
            add(cls, "getResourceAsStream", "classStream", false, true, string, stream);
        }
    }
    private static void add(ClassHolder cls, String name, String helper, boolean isStatic, boolean replace, ValueType... signature) {
        StandardApiPatches.delegate(cls, name, helper, EmbeddedResources.class.getName(), isStatic, replace, signature);
    }
}
