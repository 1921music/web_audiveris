// SPDX-License-Identifier: AGPL-3.0-or-later
package org.audiveris.wasm.compat;

import java.lang.reflect.Method;
import java.util.*;

/** Preserve original method flags for the explicitly registered reflection classes. */
public final class ReflectionMetadata {
    private ReflectionMetadata() { }
    private static final Map<String, Integer> ORIGINAL = readOriginal();
    static String encodedOriginal() { return ""; }
    static int backendModifiers(Method method) { return method.getModifiers(); }
    private static Map<String, Integer> readOriginal() {
        Map<String, Integer> result = new HashMap<>();
        for (String line : encodedOriginal().split("\n")) {
            int separator = line.lastIndexOf('|');
            if (separator >= 0) { result.put(line.substring(0, separator), Integer.parseInt(line.substring(separator + 1))); }
        }
        return result;
    }
    public static String key(Method method) {
        StringBuilder result = new StringBuilder(method.getDeclaringClass().getName()).append('#').append(method.getName()).append('(');
        for (Class<?> parameter : method.getParameterTypes()) { result.append(parameter.getName()).append(';'); }
        return result.append(')').append(method.getReturnType().getName()).toString();
    }
    public static int modifiers(Method method) {
        Integer original = ORIGINAL.get(key(method));
        return original == null ? backendModifiers(method) : original;
    }
}
