// SPDX-License-Identifier: AGPL-3.0-or-later
package org.audiveris.omr.sheet;

import ij.process.ByteProcessor;
import java.awt.image.BufferedImage;
import java.util.Objects;
import org.audiveris.omr.run.Orientation;
import org.audiveris.omr.run.RunTable;
import org.audiveris.omr.run.RunTableFactory;

/**
 * Owned raw pixels for the initial binary/scale/grid pipeline. No encoded image or
 * desktop raster is created. The caller relinquishes the supplied buffers.
 * Derived sources, replacing tables and image encoding require a later backend.
 */
public final class PixelPicture extends Picture {
    private final ByteProcessor gray;
    private final ByteProcessor binary;
    private final RunTable binaryTable;

    public PixelPicture(Sheet sheet, ByteProcessor gray, ByteProcessor binary) {
        super(sheet, Objects.requireNonNull(binary, "Missing binary pixels").getWidth(), binary.getHeight());
        if (gray != null && (gray.getWidth() != binary.getWidth() || gray.getHeight() != binary.getHeight())) {
            throw new IllegalArgumentException("Gray and binary dimensions differ");
        }
        for (byte pixel : (byte[]) binary.getPixels()) {
            if (pixel != 0 && pixel != (byte) 255) { throw new IllegalArgumentException("Expected binary pixels (0 or 255)"); }
        }
        this.gray = gray; this.binary = binary;
        binaryTable = new RunTableFactory(Orientation.VERTICAL).createTable(binary);
    }
    @Override public ByteProcessor getSource(SourceKey key) {
        switch (Objects.requireNonNull(key)) {
            case GRAY: return gray;
            case BINARY: return binary;
            default: throw new UnsupportedOperationException("Derived pixel source is not implemented yet: " + key);
        }
    }
    @Override public RunTable getVerticalTable(TableKey key) {
        return Objects.requireNonNull(key) == TableKey.BINARY ? binaryTable : null;
    }
    @Override public BufferedImage getImage(ImageKey key) {
        throw new UnsupportedOperationException("Raw pixels require an image-encoding backend");
    }
    @Override public void setTable(TableKey key, RunTable table, boolean modified) {
        throw new UnsupportedOperationException("Replacing raw-pixel tables is not implemented yet");
    }
}
