package org.audiveris.wasm.compat.test;

import java.awt.Color;
import java.awt.Point;
import java.nio.file.*;
import java.util.*;
import ij.process.ByteProcessor;
import org.audiveris.omr.run.*;
import org.audiveris.omr.sheet.PixelPicture;

/** Compare integer raster replacement with the original Java2D run renderer, including clipping. */
public final class CleanRasterOracle {
    public static void main(String[] args) throws Exception {
        var paint = PixelPicture.class.getDeclaredMethod("eraseRuns", ByteProcessor.class, RunTable.class, int.class, int.class);
        paint.setAccessible(true); Random random = new Random(95341); long pixels = 0;
        for (int index = 0; index < 256; index++) {
            int width = 37, height = 29; byte[] input = new byte[width * height], glyph = new byte[19 * 17];
            for (int i = 0; i < input.length; i++) { input[i] = random.nextBoolean() ? 0 : (byte) 255; }
            for (int i = 0; i < glyph.length; i++) { glyph[i] = random.nextInt(4) == 0 ? 0 : (byte) 255; }
            var table = new RunTableFactory(index % 2 == 0 ? Orientation.HORIZONTAL : Orientation.VERTICAL)
                    .createTable(new ByteProcessor(19, 17, glyph, null));
            int dx = index < 4 ? (index < 2 ? Integer.MIN_VALUE : Integer.MAX_VALUE) : random.nextInt(77) - 30;
            int dy = index < 4 ? (index % 2 == 0 ? Integer.MIN_VALUE : Integer.MAX_VALUE) : random.nextInt(65) - 25;
            var image = new ByteProcessor(width, height, input.clone(), null).getBufferedImage();
            var graphics = image.createGraphics(); graphics.setColor(Color.WHITE);
            try { table.render(graphics, new Point(dx, dy)); } finally { graphics.dispose(); }
            byte[] expected = (byte[]) new ByteProcessor(image).getPixels();
            var actual = new ByteProcessor(width, height, input.clone(), null);
            paint.invoke(null, actual, table, dx, dy);
            if (!Arrays.equals(expected, (byte[]) actual.getPixels())) { throw new AssertionError("Raster clipping case " + index); }
            pixels += input.length;
        }
        Path output = Path.of(args[0]); Files.createDirectories(output.getParent());
        Files.writeString(output, "{\"cases\":256,\"pixels\":" + pixels + ",\"orientations\":2,\"extremeOffsets\":true}\n");
        System.out.println("Run raster clipping agrees with original Java2D: 256 cases / " + pixels + " pixels");
    }
}
