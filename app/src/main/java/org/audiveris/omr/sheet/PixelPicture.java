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
 * Staff-line removal uses integer run rectangles; other derived sources, replacing tables and image encoding require a later backend.
 */
public final class PixelPicture extends Picture {
    private final Sheet owner;
    private ByteProcessor noStaff;
    private final ByteProcessor gray;
    private final ByteProcessor binary;
    private final RunTable binaryTable;
    private RunTable headSpots;

    public PixelPicture(Sheet sheet, ByteProcessor gray, ByteProcessor binary) {
        super(sheet, Objects.requireNonNull(binary, "Missing binary pixels").getWidth(), binary.getHeight());
        if (gray != null && (gray.getWidth() != binary.getWidth() || gray.getHeight() != binary.getHeight())) {
            throw new IllegalArgumentException("Gray and binary dimensions differ");
        }
        for (byte pixel : (byte[]) binary.getPixels()) {
            if (pixel != 0 && pixel != (byte) 255) { throw new IllegalArgumentException("Expected binary pixels (0 or 255)"); }
        }
        this.owner = sheet; this.gray = gray; this.binary = binary;
        binaryTable = new RunTableFactory(Orientation.VERTICAL).createTable(binary);
    }
    @Override public ByteProcessor getSource(SourceKey key) {
        switch (Objects.requireNonNull(key)) {
            case GRAY: return gray;
            case BINARY: return binary;
            case NO_STAFF: if (noStaff == null) { noStaff = withoutStaffLines(); } return noStaff;
            default: throw new UnsupportedOperationException("Derived pixel source is not implemented yet: " + key);
        }
    }
    @Override public void disposeSource(SourceKey key) {
        if (key == SourceKey.NO_STAFF) { noStaff = null; }
        super.disposeSource(key);
    }
    private ByteProcessor withoutStaffLines() {
        byte[] pixels = ((byte[]) binary.getPixels()).clone();
        ByteProcessor output = new ByteProcessor(binary.getWidth(), binary.getHeight(), pixels, null);
        for (SystemInfo system : owner.getSystems()) { for (Staff staff : system.getStaves()) {
            for (var info : staff.getLines()) {
                StaffLine line = (StaffLine) info; var glyph = line.getGlyph();
                if (glyph == null) {
                    org.slf4j.LoggerFactory.getLogger(Picture.class).warn("Staff #{} no glyph for line #{}", staff.getId(), 1 + staff.getLines().indexOf(line));
                } else {
                    var offset = glyph.getTopLeft(); eraseRuns(output, glyph.getRunTable(), offset.x, offset.y);
                }
            }
        } }
        return output;
    }
    /** Same integer run rectangles as RunTable.render(Graphics2D, Point), clipped to the image. */
    private static void eraseRuns(ByteProcessor output, RunTable table, int dx, int dy) {
        org.audiveris.omr.image.RunTableRaster.erase(output, table, dx, dy);
    }
    @Override public RunTable getVerticalTable(TableKey key) {
        return Objects.requireNonNull(key) == TableKey.BINARY ? binaryTable : headSpots;
    }
    @Override public BufferedImage getImage(ImageKey key) {
        throw new UnsupportedOperationException("Raw pixels require an image-encoding backend");
    }
    @Override public void setTable(TableKey key, RunTable table, boolean modified) {
        if (table == null) { return; }
        if (Objects.requireNonNull(key) != TableKey.HEAD_SPOTS) {
            throw new UnsupportedOperationException("Replacing the owned binary table requires a new pixel input");
        }
        if (table.getWidth() != getWidth() || table.getHeight() != getHeight()) {
            throw new UnsupportedOperationException("Raw table dimensions must preserve the input geometry");
        }
        headSpots = table;
    }
}
