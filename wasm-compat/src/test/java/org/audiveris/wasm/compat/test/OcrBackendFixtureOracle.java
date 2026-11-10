package org.audiveris.wasm.compat.test;

/** Native image fixture for the external OCR adapter; not a TEXTS recognition oracle. */
public final class OcrBackendFixtureOracle {
    public static void main(String[] args) throws Exception {
        var root = java.nio.file.Path.of(args[0]); java.nio.file.Files.createDirectories(root);
        int width = 700, height = 180;
        var image = new java.awt.image.BufferedImage(width, height, java.awt.image.BufferedImage.TYPE_BYTE_GRAY);
        var g = image.createGraphics();
        try {
            g.setColor(java.awt.Color.WHITE); g.fillRect(0, 0, width, height);
            g.setRenderingHint(java.awt.RenderingHints.KEY_TEXT_ANTIALIASING, java.awt.RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            g.setColor(java.awt.Color.BLACK);
            g.setFont(new java.awt.Font(java.awt.Font.SANS_SERIF, java.awt.Font.BOLD, 44)); g.drawString("Allegro moderato", 30, 65);
            g.setFont(new java.awt.Font(java.awt.Font.SERIF, java.awt.Font.PLAIN, 38)); g.drawString("Music 123", 30, 135);
        } finally { g.dispose(); }
        byte[] pixels = ((java.awt.image.DataBufferByte)image.getRaster().getDataBuffer()).getData();
        java.nio.file.Files.write(root.resolve("input.gray"), pixels);
        javax.imageio.ImageIO.write(image, "png", root.resolve("native.png").toFile());
        java.nio.file.Files.writeString(root.resolve("fixture.json"), "{\"width\":700,\"height\":180,\"expected\":[\"Allegro\",\"moderato\",\"Music\",\"123\"]}\n");
    }
}
