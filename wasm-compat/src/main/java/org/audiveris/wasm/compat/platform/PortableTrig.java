// SPDX-License-Identifier: AGPL-3.0-or-later
package org.audiveris.wasm.compat.platform;

/** Deterministic JDK StrictMath semantics; TeaVM links the original FdLibm Java methods. */
public final class PortableTrig {
    private PortableTrig(){ }
    public static double sin(double x){return StrictMath.sin(x);}
    public static double cos(double x){return StrictMath.cos(x);}
    public static double atan(double x){return StrictMath.atan(x);}
    public static double atan2(double y,double x){
        double angle=atan2Core(y,x);
        // Preserve the sign when an extreme ratio underflows to zero. TeaVM's
        // optimized negation of the FdLibm result otherwise loses negative zero.
        return angle==0.0?Double.longBitsToDouble(Double.doubleToRawLongBits(y)&Long.MIN_VALUE):angle;
    }
    public static double atan2Core(double y,double x){return StrictMath.atan2(y,x);}
}
