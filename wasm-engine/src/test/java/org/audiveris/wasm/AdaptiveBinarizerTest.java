package org.audiveris.wasm;

import ij.process.ByteProcessor;
import java.util.Arrays;
import java.util.Random;
import org.audiveris.omr.image.VerticalFilter;
import org.junit.Test;
import static org.junit.Assert.*;

public class AdaptiveBinarizerTest {
    @Test public void matchesUnchangedUpstreamAtBordersAndAcrossYields() {
        Random random = new Random(1921);
        for (int width : new int[] {1, 2, 18, 37, 82}) {
            for (int height : new int[] {1, 3, 37, 91}) {
                byte[] pixels = new byte[width * height];
                random.nextBytes(pixels);
                byte[] expected = (byte[]) new VerticalFilter(
                        new ByteProcessor(width, height, pixels, null), .7, .9).filteredImage().getPixels();
                for (int budget : new int[] {1, 7, Integer.MAX_VALUE}) {
                    AdaptiveBinarizer filter = new AdaptiveBinarizer(new GrayImage(width, height, width, pixels), 18, .7, .9);
                    while (!filter.advance(budget)) { }
                    assertArrayEquals("width=" + width + ", height=" + height + ", budget=" + budget, expected, filter.result());
                }
            }
        }
    }

    @Test public void respectsStrideAndUnsignedPixels() {
        byte[] padded = {(byte) 255, 0, 99, 99, 80, (byte) 190, 99, 99};
        AdaptiveBinarizer paddedFilter = new AdaptiveBinarizer(new GrayImage(2, 2, 4, padded), 18, .7, .9);
        AdaptiveBinarizer packedFilter = new AdaptiveBinarizer(new GrayImage(2, 2, 2,
                new byte[] {(byte) 255, 0, 80, (byte) 190}), 18, .7, .9);
        paddedFilter.advance(2);
        packedFilter.advance(2);
        assertArrayEquals(packedFilter.result(), paddedFilter.result());
    }

    @Test public void rejectsInvalidInputsAndIncompleteResults() {
        assertThrows(IllegalArgumentException.class, () -> new GrayImage(Integer.MAX_VALUE, 2, Integer.MAX_VALUE, new byte[1]));
        assertThrows(IllegalArgumentException.class, () -> new GrayImage(2, 2, 1, new byte[4]));
        GrayImage image = new GrayImage(2, 2, 2, new byte[4]);
        assertThrows(IllegalArgumentException.class, () -> new AdaptiveBinarizer(image, 18, Double.NaN, .9));
        AdaptiveBinarizer filter = new AdaptiveBinarizer(image, 18, .7, .9);
        assertThrows(IllegalStateException.class, filter::result);
        assertThrows(IllegalArgumentException.class, () -> filter.advance(0));
    }

    @Test public void uniformWhiteAndBlackPagesStayUniform() {
        for (int value : new int[] {0, 255}) {
            byte[] pixels = new byte[20 * 30];
            Arrays.fill(pixels, (byte) value);
            AdaptiveBinarizer filter = new AdaptiveBinarizer(new GrayImage(20, 30, 20, pixels), 18, .7, .9);
            filter.advance(20);
            assertArrayEquals(pixels, filter.result());
        }
    }
}
