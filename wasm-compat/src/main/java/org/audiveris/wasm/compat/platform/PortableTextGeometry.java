// SPDX-License-Identifier: AGPL-3.0-or-later
package org.audiveris.wasm.compat.platform;

import java.awt.geom.Rectangle2D;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.*;
import org.audiveris.omr.text.*;

/** Opt-in text metrics from explicit physical fonts; music metrics retain their separate provider. */
public final class PortableTextGeometry implements TextFontGeometry.Provider {
    private record Binding(TrueTypeFaceMetrics face, String name, boolean syntheticItalic) { }
    private static final Map<String, TrueTypeFaceMetrics> faces = new LinkedHashMap<>();
    private static final Map<Integer, Binding> bindings = new LinkedHashMap<>();
    private static PortableTextGeometry active;
    private final Map<Integer, Binding> configured;
    private final TextFontGeometry.Provider music;
    private int requests;
    private PortableTextGeometry(Map<Integer, Binding> configured, TextFontGeometry.Provider music) {
        this.configured = Map.copyOf(configured); this.music = music;
    }
    private static void mutable() { if (active != null) { throw new IllegalStateException("Text font resources are sealed"); } }
    public static void install(String id, byte[] bytes) throws IOException {
        mutable(); if (id == null || id.isBlank() || faces.containsKey(id)) { throw new IllegalArgumentException("Invalid/duplicate font face"); }
        faces.put(id, new TrueTypeFaceMetrics(bytes));
    }
    public static void bind(int flags, String id, String name, boolean syntheticItalic) {
        mutable(); if (flags < 0 || flags > 63 || name == null || name.isBlank() || !faces.containsKey(id)) {
            throw new IllegalArgumentException("Invalid text font binding");
        }
        int key = canonical(flags); var value = new Binding(faces.get(id), name, syntheticItalic);
        Binding old = bindings.putIfAbsent(key, value);
        if (old != null && !old.equals(value)) { throw new IllegalArgumentException("Conflicting text font binding"); }
    }
    public static void activate() {
        if (active == null) {
            if (bindings.isEmpty()) { throw new IllegalStateException("Configure physical text fonts before recognition"); }
            active = new PortableTextGeometry(bindings, HeadlessTextFontGeometry.provider());
        }
        TextFontGeometry.setProvider(active);
    }
    public static int measurements() { return active == null ? 0 : active.requests; }
    private static int canonical(int flags) { return (flags & 3) | ((flags & 16) != 0 ? 16 : flags & 8); }
    private Binding binding(FontAttributes attrs) {
        int key = canonical(HeadlessTextFontGeometry.flags(attrs)); Binding binding = configured.get(key);
        if (binding == null) { throw new UnsupportedOperationException("Text font style is not configured: " + key); }
        return binding;
    }
    private double[] measure(Binding binding, int size, String value) {
        requests++;
        try { return binding.face().measure(size, value, binding.syntheticItalic()); }
        catch (IOException error) { throw new UncheckedIOException("Physical font measurement failed", error); }
    }
    @Override public TextFontGeometry.Measurement visualBounds(FontAttributes attrs, int size, String value) {
        Binding binding = binding(attrs); double[] bounds = measure(binding, size, value);
        return new TextFontGeometry.Measurement(binding.name(), bounds[0], bounds[1], bounds[2], bounds[3]);
    }
    private double[] layout(FontInfo font, String value) {
        if (value.isEmpty()) { throw new IllegalArgumentException("Empty text layout"); }
        for (int i = 0; i < value.length(); i++) {
            if (value.charAt(i) < 32 || value.charAt(i) > 126) {
                throw new UnsupportedOperationException("Complex text layout needs an explicit shaping backend");
            }
        }
        return measure(binding(font.getAttributes()), font.pointSize, value);
    }
    @Override public Rectangle2D layoutBounds(FontInfo font, String value) {
        double[] bounds = layout(font, value); return new Rectangle2D.Double(bounds[0], bounds[1], bounds[2], bounds[3]);
    }
    @Override public double advance(FontInfo font, String value) { return layout(font, value)[4]; }
    @Override public String defaultFontName() { return music.defaultFontName(); }
    @Override public TextFontGeometry.Measurement musicVisualBounds(FontInfo font, String value) { return music.musicVisualBounds(font, value); }
    @Override public Rectangle2D musicLayoutBounds(FontInfo font, String value) { return music.musicLayoutBounds(font, value); }
    @Override public double musicAdvance(FontInfo font, String value) { return music.musicAdvance(font, value); }
}
