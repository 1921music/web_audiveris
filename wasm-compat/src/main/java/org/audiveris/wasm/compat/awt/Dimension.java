// SPDX-License-Identifier: AGPL-3.0-or-later
package org.audiveris.wasm.compat.awt;

import java.awt.geom.Dimension2D;
import java.io.Serializable;

/** Integer dimensions; fractional sizes round upward, as in AWT. */
public class Dimension extends Dimension2D implements Serializable {
    private static final long serialVersionUID = 4723952579491349524L;
    public int width, height;
    public Dimension() { }
    public Dimension(int width, int height) { this.width = width; this.height = height; }
    public Dimension(Dimension size) { this(size.width, size.height); }
    @Override public double getWidth() { return width; }
    @Override public double getHeight() { return height; }
    public Dimension getSize() { return new Dimension(width, height); }
    public void setSize(Dimension size) { setSize(size.width, size.height); }
    public void setSize(int width, int height) { this.width = width; this.height = height; }
    @Override public void setSize(double width, double height) {
        this.width = (int) Math.ceil(width);
        this.height = (int) Math.ceil(height);
    }
    @Override public boolean equals(Object object) {
        return object instanceof Dimension size && width == size.width && height == size.height;
    }
    @Override public int hashCode() { int sum = width + height; return sum * (sum + 1) / 2 + width; }
    @Override public String toString() { return getClass().getName() + "[width=" + width + ",height=" + height + "]"; }
}
