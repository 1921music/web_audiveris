// SPDX-License-Identifier: AGPL-3.0-or-later
package org.audiveris.wasm.compat.platform;

import java.util.*;
import java.util.function.Consumer;

/** Per-module configuration; supply values before the first engine constant is registered. */
public final class HeadlessConfiguration {
    private static Map<String, String> overrides = Map.of(), saved = Map.of();
    private static boolean frozen;
    private static boolean saveSheets;
    private static java.nio.file.Path outputFolder;
    private static Consumer<Map<String, String>> persistence;
    private HeadlessConfiguration() { }

    public static void configure(Map<String, String> runOverrides, Map<String, String> persistedValues) {
        configure(runOverrides, persistedValues, false);
    }
    public static void configure(Map<String, String> runOverrides, Map<String, String> persistedValues, boolean saveEachSheetStep) {
        if (frozen) { throw new IllegalStateException("Configure a fresh Worker before engine initialization"); }
        Map<String, String> nextOverrides = copy(runOverrides), nextSaved = copy(persistedValues);
        overrides = nextOverrides; saved = nextSaved; saveSheets = saveEachSheetStep; outputFolder = null;
    }
    private static Map<String, String> copy(Map<String, String> values) {
        Objects.requireNonNull(values, "configuration");
        Map<String, String> result = new HashMap<>();
        for (var entry : values.entrySet()) {
            String key = Objects.requireNonNull(entry.getKey(), "configuration key");
            if (key.isEmpty()) { throw new IllegalArgumentException("Empty configuration key"); }
            result.put(key, Objects.requireNonNull(entry.getValue(), "configuration value"));
        }
        return Collections.unmodifiableMap(result);
    }
    public static String value(String key) {
        frozen = true;
        String value = overrides.get(key);
        return value != null ? value : saved.get(key);
    }
    public static String savedValue(String key) { frozen = true; return saved.get(key); }
    public static Map<String, String> savedValues() { frozen = true; return saved; }
    public static boolean isFrozen() { return frozen; }
    public static boolean saveSheets() { frozen = true; return saveSheets; }
    public static void setOutputFolder(String folder) {
        if (frozen) { throw new IllegalStateException("Set the output folder before engine initialization"); }
        java.nio.file.Path path = folder == null ? null : java.nio.file.Paths.get(folder).normalize();
        if (path != null && !path.isAbsolute()) { throw new IllegalArgumentException("Output folder must be an absolute logical path"); }
        outputFolder = path;
    }
    public static java.nio.file.Path outputFolder() { frozen = true; return outputFolder; }
    /** Preserve BookManager.setBaseFolder's explicit removal of the run-specific override. */
    public static void clearOutputFolder() { outputFolder = null; }
    public static int stepTimeout() {
        String value = value("org.audiveris.omr.Main.sheetStepTimeOut");
        int seconds = value == null ? HeadlessDefaults.defaultStepTimeout() : Integer.parseInt(value);
        if (seconds <= 0) { throw new IllegalArgumentException("Sheet step timeout must be positive"); }
        return seconds;
    }
    public static void setPersistence(Consumer<Map<String, String>> sink) { persistence = sink; }
    public static void persist(Map<String, String> values) {
        if (persistence == null) { throw new IllegalStateException("Host configuration persistence is not installed"); }
        Map<String, String> snapshot = copy(values);
        persistence.accept(snapshot);
        saved = snapshot;
    }
}
