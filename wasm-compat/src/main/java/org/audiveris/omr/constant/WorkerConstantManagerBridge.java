// SPDX-License-Identifier: AGPL-3.0-or-later
package org.audiveris.omr.constant;

import java.util.*;
import org.audiveris.wasm.compat.platform.HeadlessConfiguration;

/** Package access keeps the original constant registry, duplicate guards and decoding behavior. */
public final class WorkerConstantManagerBridge {
    private WorkerConstantManagerBridge() { }
    public static String addConstant(ConstantManager manager, String key, Constant constant) {
        if (key == null) { throw new IllegalArgumentException("Attempt to add a constant with no qualified name"); }
        Constant old = manager.constants.putIfAbsent(key, constant);
        if (old != null && old != constant) { throw new IllegalArgumentException("Attempt to duplicate constant " + key); }
        return HeadlessConfiguration.value(key);
    }
    public static String getConstantUserValue(ConstantManager manager, String key) {
        return HeadlessConfiguration.savedValue(key);
    }
    public static Collection<String> getAllProperties(ConstantManager manager) {
        return new TreeSet<>(HeadlessConfiguration.savedValues().keySet());
    }
    public static Collection<String> getUnusedUserProperties(ConstantManager manager) {
        SortedSet<String> keys = new TreeSet<>(HeadlessConfiguration.savedValues().keySet());
        keys.removeAll(((Map<String, Constant>) manager.constants).keySet()); return keys;
    }
    public static Map<String, String> export(ConstantManager manager) {
        // Same cleanup as UserHolder: retain unknown keys, remove source-valued entries.
        Map<String, String> values = new HashMap<>(HeadlessConfiguration.savedValues());
        for (var entry : manager.constants.entrySet()) {
            String current = entry.getValue().getStringValue();
            if (current.equals(entry.getValue().getSourceString())) { values.remove(entry.getKey()); }
            else { values.put(entry.getKey(), current); }
        }
        return Collections.unmodifiableMap(values);
    }
    public static void storeResource(ConstantManager manager) { HeadlessConfiguration.persist(export(manager)); }
}
