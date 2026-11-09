// SPDX-License-Identifier: AGPL-3.0-or-later
package org.audiveris.wasm.compat.awt;

import java.awt.geom.Point2D;
import java.io.Serializable;

/** Integer point with AWT rounding and its Point2D inheritance, without a window system. */
public class Point extends Point2D implements Serializable {
    private static final long serialVersionUID = -5276940640259749850L;
    public int x, y;
    public Point() { }
    public Point(int x, int y) { this.x = x; this.y = y; }
    public Point(Point point) { this(point.x, point.y); }
    @Override public double getX() { return x; }
    @Override public double getY() { return y; }
    public Point getLocation() { return new Point(x, y); }
    public void setLocation(Point point) { setLocation(point.x, point.y); }
    public void setLocation(int x, int y) { this.x = x; this.y = y; }
    @Override public void setLocation(double x, double y) {
        this.x = (int) Math.floor(x + .5);
        this.y = (int) Math.floor(y + .5);
    }
    public void move(int x, int y) { setLocation(x, y); }
    public void translate(int dx, int dy) { x += dx; y += dy; }
    @Override public boolean equals(Object object) {
        if (object instanceof Point point) { return x == point.x && y == point.y; }
        return super.equals(object);
    }
    @Override public String toString() { return getClass().getName() + "[x=" + x + ",y=" + y + "]"; }
}
