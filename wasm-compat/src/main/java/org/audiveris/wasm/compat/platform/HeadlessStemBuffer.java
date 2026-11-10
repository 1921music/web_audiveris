// SPDX-License-Identifier: AGPL-3.0-or-later
package org.audiveris.wasm.compat.platform;

import ij.process.ByteProcessor;
import java.util.Objects;
import org.audiveris.omr.sheet.Picture;
import org.audiveris.omr.sheet.Sheet;

/** Preserve the pinned ImageJ/StemScaler behavior: drawing affects a detached debug image only. */
public final class HeadlessStemBuffer {
    private HeadlessStemBuffer() { }
    public static ByteProcessor buffer(Sheet sheet, boolean saveImage) {
        if (saveImage) { throw new UnsupportedOperationException("Stem debug image rendering requires a host raster backend"); }
        // TODO: reconsider if upstream starts copying the rendered image back into the processor.
        return Objects.requireNonNull(sheet.getPicture().getSource(Picture.SourceKey.NO_STAFF));
    }
}
