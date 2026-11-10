// SPDX-License-Identifier: AGPL-3.0-or-later
package org.audiveris.wasm.compat.platform;

import java.awt.geom.Rectangle2D;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import org.audiveris.omr.text.*;

/** Exact host measurements, owned by one engine module; missing entries never use Canvas approximations. */
public final class HeadlessTextFontGeometry implements TextFontGeometry.Provider {
    private record Key(int kind, int flags, int size, String value, String family) { }
    private record Entry(String name, double x, double y, double width, double height, double advance) { }
    private static final HeadlessTextFontGeometry INSTANCE = new HeadlessTextFontGeometry();
    private static final Map<Key, Entry> entries = new HashMap<>();
    private static String defaultName;
    public static TextFontGeometry.Provider provider() { return INSTANCE; }
    public static int flags(FontAttributes attrs) {
        return (attrs.isBold ? 1 : 0) | (attrs.isItalic ? 2 : 0) | (attrs.isUnderlined ? 4 : 0)
                | (attrs.isMonospaced ? 8 : 0) | (attrs.isSerif ? 16 : 0) | (attrs.isSmallcaps ? 32 : 0);
    }
    public static void defaultName(String name) {
        if (name == null || name.isBlank()) { throw new IllegalArgumentException("Missing default text font name"); }
        if (defaultName != null && !defaultName.equals(name)) { throw new IllegalStateException("Default text font already installed"); }
        defaultName = name;
    }
    public static void register(int kind, int flags, int size, String value, String family, String name, double x, double y, double width, double height, double advance) {
        Objects.requireNonNull(value); Objects.requireNonNull(name); Objects.requireNonNull(family);
        if (kind < 0 || kind > 3 || flags < 0 || flags > 63 || size < 0 || name.isBlank()
                || !Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(width) || !Double.isFinite(height)
                || width < 0 || height < 0 || !Double.isFinite(advance)) { throw new IllegalArgumentException("Invalid text font measurement"); }
        Key key = new Key(kind, flags, size, value, kind >= 2 ? family : ""); Entry entry = new Entry(name, x, y, width, height, advance);
        Entry old = entries.putIfAbsent(key, entry);
        if (old != null && !old.equals(entry)) { throw new IllegalArgumentException("Conflicting text font measurement"); }
    }
    private Entry require(int kind, FontAttributes attrs, int size, String value) {
        Key key = new Key(kind, flags(attrs), size, value, kind >= 2 ? ((FontInfo)attrs).fontName : ""); Entry entry = entries.get(key);
        if (entry == null) { throw new UnsupportedOperationException("Host text font measurement required: " + key); }
        return entry;
    }
    @Override public TextFontGeometry.Measurement visualBounds(FontAttributes attrs, int size, String value) {
        Entry entry = require(0, attrs, size, value);
        return new TextFontGeometry.Measurement(entry.name(), entry.x(), entry.y(), entry.width(), entry.height());
    }
    @Override public Rectangle2D layoutBounds(FontInfo font, String value) {
        Entry entry = require(1, font.getAttributes(), font.pointSize, value);
        return new Rectangle2D.Double(entry.x(), entry.y(), entry.width(), entry.height());
    }
    @Override public double advance(FontInfo font, String value) { return require(1, font.getAttributes(), font.pointSize, value).advance(); }
    @Override public TextFontGeometry.Measurement musicVisualBounds(FontInfo font, String value) {
        Entry entry = require(3, font, font.pointSize, value);
        return new TextFontGeometry.Measurement(entry.name(), entry.x(), entry.y(), entry.width(), entry.height());
    }
    @Override public Rectangle2D musicLayoutBounds(FontInfo font, String value) {
        Entry entry = require(2, font, font.pointSize, value); return new Rectangle2D.Double(entry.x(), entry.y(), entry.width(), entry.height());
    }
    @Override public double musicAdvance(FontInfo font, String value) { return require(2, font, font.pointSize, value).advance(); }
    @Override public String defaultFontName() {
        if (defaultName == null) { throw new UnsupportedOperationException("Host default text font name required"); }
        return defaultName;
    }
}
