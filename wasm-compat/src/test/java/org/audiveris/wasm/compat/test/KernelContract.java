package org.audiveris.wasm.compat.test;

import java.awt.image.Kernel;

/** Original API names exercise the compiler substitution in Wasm, including subclass cloning. */
public final class KernelContract {
    public static boolean run() {
        float[] data = {1, -0.0f, Float.NaN, 4, 5, 6, 99}; Kernel kernel = new Kernel(3, 2, data);
        data[0] = 33;
        if (kernel.getWidth() != 3 || kernel.getHeight() != 2 || kernel.getXOrigin() != 1 || kernel.getYOrigin() != 0) { return false; }
        float[] values = kernel.getKernelData(null);
        if (values.length != 6 || values[0] != 1 || Float.floatToRawIntBits(values[1]) != Float.floatToRawIntBits(-0.0f) || !Float.isNaN(values[2])) { return false; }
        values[0] = 55; if (kernel.getKernelData(null)[0] != 1) { return false; }
        Tagged original = new Tagged(); Object value = original.clone();
        if (!(value instanceof Tagged)) { return false; } Tagged copy = (Tagged) value;
        if (copy == original || copy.token != original.token || copy.number != 39) { return false; }
        Kernel empty = new Kernel(0, 0, new float[0]);
        if (empty.getXOrigin() != -1 || empty.getYOrigin() != -1) { return false; }
        int errors = 0;
        try { kernel.getKernelData(new float[2]); } catch (IllegalArgumentException expected) { errors++; }
        try { new Kernel(2, 3, new float[5]); } catch (IllegalArgumentException expected) { errors++; }
        try { new Kernel(1, 1, null); } catch (NullPointerException expected) { errors++; }
        return errors == 3;
    }
    private static final class Tagged extends Kernel {
        final Object token = new Object(); final int number = 39;
        Tagged() { super(1, 1, new float[]{1}); }
    }
}
