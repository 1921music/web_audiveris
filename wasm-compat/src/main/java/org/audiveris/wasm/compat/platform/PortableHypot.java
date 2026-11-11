// SPDX-License-Identifier: AGPL-3.0-or-later
package org.audiveris.wasm.compat.platform;

/** Native JVM uses Math.hypot; TeaVM links the pinned JDK's pure Java FdLibm implementation. */
public final class PortableHypot {
    private PortableHypot(){ }
    public static double hypot(double x,double y){return Math.hypot(x,y);}
    /** JDK Math's package-private helper; FdLibm uses only normal powers of two. */
    public static double powerOfTwoD(int exponent){
        if(exponent< -1022||exponent>1023)throw new IllegalArgumentException("Normal binary exponent required");
        return Double.longBitsToDouble(((long)exponent+1023)<<52);
    }
}
