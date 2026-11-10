// SPDX-License-Identifier: AGPL-3.0-or-later
package org.audiveris.wasm.compat.awt.image;

/** Data-only convolution kernel, without desktop raster initialization. */
public class Kernel implements Cloneable {
    private final int width, height, xOrigin, yOrigin;
    private final float[] coefficients;
    public Kernel(int width, int height, float[] values) {
        this.width = width; this.height = height;
        xOrigin = (width - 1) >> 1; yOrigin = (height - 1) >> 1;
        int count = width * height;
        if (values.length < count) { throw new IllegalArgumentException("Kernel data array too small"); }
        coefficients = new float[count]; System.arraycopy(values, 0, coefficients, 0, count);
    }
    public final int getWidth() { return width; }
    public final int getHeight() { return height; }
    public final int getXOrigin() { return xOrigin; }
    public final int getYOrigin() { return yOrigin; }
    public final float[] getKernelData(float[] destination) {
        if (destination == null) { destination = new float[coefficients.length]; }
        if (destination.length < coefficients.length) { throw new IllegalArgumentException("Kernel data array too small"); }
        System.arraycopy(coefficients, 0, destination, 0, coefficients.length); return destination;
    }
    @Override public Object clone() {
        try { return super.clone(); } catch (CloneNotSupportedException impossible) { throw new InternalError(impossible); }
    }
}
