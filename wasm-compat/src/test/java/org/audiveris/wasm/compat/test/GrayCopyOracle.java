package org.audiveris.wasm.compat.test;

import ij.process.*;
import java.nio.file.*;
import java.util.*;
import org.audiveris.wasm.compat.platform.HeadlessGrayCopy;

/** Compare the scoped raw-copy adapter with unmodified ImageJ, including clipping and nonbinary bytes. */
public final class GrayCopyOracle {
    public static void main(String[] args) throws Exception {
        if (HeaderPrefixCases.verify(true) != 528) { throw new AssertionError("Original JVM prefix cases"); }
        var image = new java.awt.image.BufferedImage(3, 3, java.awt.image.BufferedImage.TYPE_BYTE_GRAY);
        var shared = new ByteProcessor(image);
        shared.set(1, 1, 123);
        if (image.getRaster().getSample(1, 1, 0) != 123) { throw new AssertionError("Original ImageJ image alias"); }
        image.getRaster().setSample(1, 1, 0, 77);
        if (shared.get(1, 1) != 77) { throw new AssertionError("Original ImageJ processor alias"); }
        Random random = new Random(61937); long pixels = 0;
        for (int i = 0; i < 512; i++) {
            int width = 5 + random.nextInt(45), height = 5 + random.nextInt(37);
            int sourceWidth = 3 + random.nextInt(43), sourceHeight = 3 + random.nextInt(39);
            byte[] initial = new byte[width * height], source = new byte[sourceWidth * sourceHeight];
            random.nextBytes(initial); random.nextBytes(source);
            ByteProcessor input = new ByteProcessor(sourceWidth, sourceHeight, source, null);
            ByteProcessor expected = new ByteProcessor(width, height, initial.clone(), null), actual = new ByteProcessor(width, height, initial.clone(), null);
            int dx = i < 4 ? (i < 2 ? Integer.MIN_VALUE : Integer.MAX_VALUE) : random.nextInt(101) - 50;
            int dy = i < 4 ? (i % 2 == 0 ? Integer.MIN_VALUE : Integer.MAX_VALUE) : random.nextInt(101) - 50;
            expected.copyBits(input, dx, dy, Blitter.COPY); HeadlessGrayCopy.copyBits(actual, input, dx, dy, Blitter.COPY);
            if (!Arrays.equals((byte[]) expected.getPixels(), (byte[]) actual.getPixels())) { throw new AssertionError("Gray copy " + i); }
            pixels += initial.length;
        }
        Path output = Path.of(args[0]); Files.createDirectories(output.getParent());
        Files.writeString(output, "{\"cases\":512,\"pixels\":" + pixels + ",\"nonbinaryBytes\":true,\"extremeOffsets\":true}\n");
        System.out.println("Raw gray COPY matches original ImageJ: 512 cases / " + pixels + " pixels");
    }
}
