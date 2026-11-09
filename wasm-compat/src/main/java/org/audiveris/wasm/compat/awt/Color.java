// SPDX-License-Identifier: AGPL-3.0-or-later
package org.audiveris.wasm.compat.awt;

import java.awt.Paint;
import java.awt.PaintContext;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.geom.AffineTransform;
import java.awt.geom.Rectangle2D;
import java.awt.image.ColorModel;
import java.awt.image.Raster;
import java.io.Serializable;

/** sRGB color values and solid paint. No silent fallback for unsupported color-space conversions. */
public class Color implements Paint, Serializable {
    private static final long serialVersionUID = 118526816881161077L;
    public static final Color white = new Color(255, 255, 255), WHITE = white;
    public static final Color lightGray = new Color(192, 192, 192), LIGHT_GRAY = lightGray;
    public static final Color gray = new Color(128, 128, 128), GRAY = gray;
    public static final Color darkGray = new Color(64, 64, 64), DARK_GRAY = darkGray;
    public static final Color black = new Color(0, 0, 0), BLACK = black;
    public static final Color red = new Color(255, 0, 0), RED = red;
    public static final Color pink = new Color(255, 175, 175), PINK = pink;
    public static final Color orange = new Color(255, 200, 0), ORANGE = orange;
    public static final Color yellow = new Color(255, 255, 0), YELLOW = yellow;
    public static final Color green = new Color(0, 255, 0), GREEN = green;
    public static final Color magenta = new Color(255, 0, 255), MAGENTA = magenta;
    public static final Color cyan = new Color(0, 255, 255), CYAN = cyan;
    public static final Color blue = new Color(0, 0, 255), BLUE = blue;
    private final int value;
    private float[] precise;
    private float preciseAlpha;

    public Color(int rgb) { value = rgb | 0xff000000; }
    public Color(int rgba, boolean hasAlpha) { value = hasAlpha ? rgba : rgba | 0xff000000; }
    public Color(int red, int green, int blue) { this(red, green, blue, 255); }
    public Color(int red, int green, int blue, int alpha) {
        if (red < 0 || red > 255 || green < 0 || green > 255 || blue < 0 || blue > 255 || alpha < 0 || alpha > 255) {
            throw new IllegalArgumentException("Color component outside 0..255");
        }
        value = alpha << 24 | red << 16 | green << 8 | blue;
    }
    public Color(float red, float green, float blue) { this(red, green, blue, 1f); }
    public Color(float red, float green, float blue, float alpha) {
        this(Math.round(red * 255), Math.round(green * 255), Math.round(blue * 255), Math.round(alpha * 255));
        if (red < 0 || red > 1 || green < 0 || green > 1 || blue < 0 || blue > 1 || alpha < 0 || alpha > 1) {
            throw new IllegalArgumentException("Color component outside 0..1");
        }
        precise = new float[] {red, green, blue};
        preciseAlpha = alpha;
    }
    public int getRed() { return value >> 16 & 255; }
    public int getGreen() { return value >> 8 & 255; }
    public int getBlue() { return value & 255; }
    public int getAlpha() { return value >>> 24; }
    public int getRGB() { return value; }
    @Override public int getTransparency() { return getAlpha() == 255 ? OPAQUE : getAlpha() == 0 ? BITMASK : TRANSLUCENT; }
    public float[] getRGBComponents(float[] components) {
        float[] result = components == null ? new float[4] : components;
        getRGBColorComponents(result);
        result[3] = precise == null ? getAlpha() / 255f : preciseAlpha;
        return result;
    }
    public float[] getRGBColorComponents(float[] components) {
        float[] result = components == null ? new float[3] : components;
        result[0] = precise == null ? getRed() / 255f : precise[0];
        result[1] = precise == null ? getGreen() / 255f : precise[1];
        result[2] = precise == null ? getBlue() / 255f : precise[2];
        return result;
    }
    public float[] getComponents(float[] components) { return getRGBComponents(components); }
    public float[] getColorComponents(float[] components) { return getRGBColorComponents(components); }
    public Color brighter() {
        int red = getRed(), green = getGreen(), blue = getBlue();
        if (red == 0 && green == 0 && blue == 0) { return new Color(3, 3, 3, getAlpha()); }
        if (red > 0 && red < 3) { red = 3; }
        if (green > 0 && green < 3) { green = 3; }
        if (blue > 0 && blue < 3) { blue = 3; }
        return new Color(Math.min((int) (red / .7), 255), Math.min((int) (green / .7), 255), Math.min((int) (blue / .7), 255), getAlpha());
    }
    public Color darker() { return new Color((int) (getRed() * .7), (int) (getGreen() * .7), (int) (getBlue() * .7), getAlpha()); }
    public static Color decode(String value) { return new Color(Integer.decode(value)); }
    public static Color getColor(String name) { return getColor(name, (Color) null); }
    public static Color getColor(String name, Color fallback) {
        Integer value = Integer.getInteger(name);
        return value == null ? fallback : new Color(value);
    }
    public static Color getColor(String name, int fallback) { return getColor(name, new Color(fallback)); }
    public static float[] RGBtoHSB(int red, int green, int blue, float[] components) {
        float[] result = components == null ? new float[3] : components;
        int max = Math.max(red, Math.max(green, blue)), min = Math.min(red, Math.min(green, blue));
        float brightness = max / 255f;
        float saturation = max == 0 ? 0 : (float) (max - min) / max;
        float hue = 0;
        if (saturation != 0) {
            float r = (float) (max - red) / (max - min), g = (float) (max - green) / (max - min), b = (float) (max - blue) / (max - min);
            hue = red == max ? b - g : green == max ? 2 + r - b : 4 + g - r;
            hue /= 6;
            if (hue < 0) { hue += 1; }
        }
        result[0] = hue; result[1] = saturation; result[2] = brightness;
        return result;
    }
    public static int HSBtoRGB(float hue, float saturation, float brightness) {
        int red, green, blue;
        if (saturation == 0) { red = green = blue = (int) (brightness * 255 + .5f); }
        else {
            float h = (hue - (float) Math.floor(hue)) * 6, f = h - (float) Math.floor(h);
            float p = brightness * (1 - saturation), q = brightness * (1 - saturation * f), t = brightness * (1 - saturation * (1 - f));
            float r, g, b;
            switch ((int) h) {
                case 0 -> { r = brightness; g = t; b = p; }
                case 1 -> { r = q; g = brightness; b = p; }
                case 2 -> { r = p; g = brightness; b = t; }
                case 3 -> { r = p; g = q; b = brightness; }
                case 4 -> { r = t; g = p; b = brightness; }
                default -> { r = brightness; g = p; b = q; }
            }
            red = (int) (r * 255 + .5f); green = (int) (g * 255 + .5f); blue = (int) (b * 255 + .5f);
        }
        return 0xff000000 | red << 16 | green << 8 | blue;
    }
    public static Color getHSBColor(float hue, float saturation, float brightness) { return new Color(HSBtoRGB(hue, saturation, brightness)); }
    @Override public boolean equals(Object other) { return other instanceof Color color && value == color.value; }
    @Override public int hashCode() { return value; }
    @Override public String toString() { return getClass().getName() + "[r=" + getRed() + ",g=" + getGreen() + ",b=" + getBlue() + "]"; }

    @Override public PaintContext createContext(ColorModel model, Rectangle device, Rectangle2D user,
                                               AffineTransform transform, RenderingHints hints) {
        return new SolidContext(value);
    }
    private static final class SolidContext implements PaintContext {
        private final int rgb;
        SolidContext(int rgb) { this.rgb = rgb; }
        @Override public void dispose() { }
        @Override public ColorModel getColorModel() { return ColorModel.getRGBdefault(); }
        @Override public Raster getRaster(int x, int y, int width, int height) {
            var raster = getColorModel().createCompatibleWritableRaster(width, height);
            int[] values = new int[Math.multiplyExact(width, height)];
            java.util.Arrays.fill(values, rgb);
            raster.setDataElements(0, 0, width, height, values);
            return raster;
        }
    }
}
