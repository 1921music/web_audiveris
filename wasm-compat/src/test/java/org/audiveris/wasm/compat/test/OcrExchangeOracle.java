package org.audiveris.wasm.compat.test;

public final class OcrExchangeOracle {
    public static void main(String[] args) throws Exception {
        String result = OcrExchangeProbe.contracts();
        long frameBytes = compareNativeMargins();
        var output = java.nio.file.Path.of(args[0]);
        java.nio.file.Files.createDirectories(output.getParent());
        java.nio.file.Files.writeString(output, result);
        java.nio.file.Files.writeString(output.resolveSibling("jvm-frame-reference.json"),
                "{\"cases\":640,\"gray8Bytes\":" + frameBytes + ",\"nativePixelsExact\":true}\n");
        System.out.println(result);
    }
    private static long compareNativeMargins() {
        long bytes = 0;
        for (int seed = 0; seed < 128; seed++) {
            int width = 1 + seed % 31, height = 1 + seed % 17;
            var image = new java.awt.image.BufferedImage(width, height, java.awt.image.BufferedImage.TYPE_BYTE_GRAY);
            byte[] pixels = ((java.awt.image.DataBufferByte)image.getRaster().getDataBuffer()).getData();
            for (int i = 0; i < pixels.length; i++) { pixels[i] = (byte)(i * 37 + seed * 11); }
            for (int configured : new int[]{-3, 0, 1, 10, 31}) {
                int margin = Math.max(0, configured);
                var expected = image;
                if (margin > 0) {
                    expected = new java.awt.image.BufferedImage(width + 2 * margin, height + 2 * margin, java.awt.image.BufferedImage.TYPE_BYTE_GRAY);
                    var graphics = expected.createGraphics();
                    try { graphics.setColor(java.awt.Color.WHITE); graphics.fillRect(0, 0, expected.getWidth(), expected.getHeight()); graphics.drawImage(image, margin, margin, null); }
                    finally { graphics.dispose(); }
                }
                var session = new org.audiveris.wasm.compat.platform.OcrExchange("native", "page", seed);
                var frame = org.audiveris.wasm.compat.platform.OcrImageFrame.begin(session, width, height, pixels, configured,
                        "eng", org.audiveris.wasm.compat.platform.OcrExchange.Layout.MULTI_BLOCK);
                byte[] actual = frame.request().pixels();
                byte[] nativePixels = ((java.awt.image.DataBufferByte)expected.getRaster().getDataBuffer()).getData();
                if (!java.util.Arrays.equals(actual, nativePixels)) { throw new AssertionError("Native OCR border: " + seed + "/" + configured); }
                pixels[0] ^= 127;
                if (!java.util.Arrays.equals(actual, frame.request().pixels())) { throw new AssertionError("OCR input ownership"); }
                pixels[0] ^= 127; bytes += actual.length;
            }
        }
        System.out.println("640 native Java2D OCR borders: " + bytes + " identical GRAY8 bytes");
        return bytes;
    }
}
