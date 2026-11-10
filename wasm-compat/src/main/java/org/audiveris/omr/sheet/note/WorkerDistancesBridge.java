// SPDX-License-Identifier: AGPL-3.0-or-later
package org.audiveris.omr.sheet.note;

import ij.process.ByteProcessor;

/** Only raster preparation changes; distance transformation and neutralization stay original. */
public final class WorkerDistancesBridge {
    private WorkerDistancesBridge() { }
    public static ByteProcessor erasedBuffer(DistancesBuilder builder, ByteProcessor input) {
        return builder.erasedRawBuffer(input);
    }
}
