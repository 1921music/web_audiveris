// SPDX-License-Identifier: AGPL-3.0-or-later
package org.audiveris.wasm.compat.platform;

import ij.process.ByteProcessor;
import org.audiveris.omr.sheet.Picture;
import org.audiveris.omr.sheet.Sheet;
import org.audiveris.omr.text.SheetScanner;

/** Original TEXTS traversal and glyph decisions, with an owned raster and explicit drawing. */
public final class PortableTextsCleanup {
    private PortableTextsCleanup() { }
    public static ByteProcessor clean(Sheet sheet) {
        ByteProcessor source = sheet.getPicture().getSource(Picture.SourceKey.NO_STAFF);
        var buffer = new ByteProcessor(source.getWidth(), source.getHeight(), ((byte[]) source.getPixels()).clone(), null);
        Sheet previous = HeadlessSheetContext.current(); HeadlessSheetContext.bind(sheet);
        try { SheetScanner.cleanBuffer(buffer, new PackedPageRaster(buffer), sheet); return buffer; }
        finally { HeadlessSheetContext.bind(previous); }
    }
}
