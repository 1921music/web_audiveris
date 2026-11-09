package org.audiveris.omr.wasmtest;

import org.audiveris.omr.OMR;

/** A native-only call must disappear before dependency analysis; engine work must remain. */
public final class GuiBoundaryFixture {
    private static native int unavailableDesktop();
    public static int engineWork() {
        int result = 7;
        if (OMR.gui != null) { result += unavailableDesktop(); }
        return result * 3;
    }
}
