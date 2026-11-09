package org.audiveris.wasm;

import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.zip.ZipFile;
import javax.imageio.ImageIO;
import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.Element;

/** Compare against .omr files produced by the complete upstream JVM application. */
public final class UpstreamComparison {
    public static void main(String[] args) throws Exception {
        Path fixtures = Path.of(args[0]), upstream = Path.of(args[1]);
        int compared = 0;
        try (var files = Files.list(fixtures)) {
            for (Path scaleFile : files.filter(p -> p.toString().endsWith(".scale")).sorted().toList()) {
                String id = scaleFile.getFileName().toString().replace(".scale", "");
                Path book;
                try (var books = Files.walk(upstream)) {
                    book = books.filter(p -> p.getFileName().toString().equals(id + ".input.omr")).findFirst().orElseThrow();
                }
                try (ZipFile zip = new ZipFile(book.toFile())) {
                    var xml = zip.getEntry("sheet#1/sheet#1.xml");
                    var factory = DocumentBuilderFactory.newInstance();
                    factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
                    Element scale = (Element) factory.newDocumentBuilder().parse(zip.getInputStream(xml)).getElementsByTagName("scale").item(0);
                    if (scale == null) { throw new AssertionError(id + ": upstream scale missing"); }
                    int[] actual = new int[12];
                    String[] tags = {"line", "interline", "small-interline"};
                    for (int i = 0; i < tags.length; i++) {
                        Element range = (Element) scale.getElementsByTagName(tags[i]).item(0);
                        if (range != null) {
                            actual[3 * i] = Integer.parseInt(range.getAttribute("min"));
                            actual[3 * i + 1] = Integer.parseInt(range.getAttribute("main"));
                            actual[3 * i + 2] = Integer.parseInt(range.getAttribute("max"));
                        }
                    }
                    Element beam = (Element) scale.getElementsByTagName("beam").item(0);
                    Element smallBeam = (Element) scale.getElementsByTagName("small-beam").item(0);
                    if (beam != null) {
                        actual[9] = Integer.parseInt(beam.getAttribute("main-thickness"));
                        actual[11] = Boolean.parseBoolean(beam.getAttribute("extra")) ? 1 : 0;
                    }
                    if (smallBeam != null) { actual[10] = Integer.parseInt(smallBeam.getAttribute("main-thickness")); }
                    int[] expected = Arrays.stream(Files.readString(scaleFile).replaceAll("[\\[\\]\\s]", "").split(",")).mapToInt(Integer::parseInt).toArray();
                    if (!Arrays.equals(expected, actual)) {
                        throw new AssertionError(id + ": scale differs: portable=" + Arrays.toString(expected) + ", upstream=" + Arrays.toString(actual));
                    }
                    BufferedImage image = ImageIO.read(zip.getInputStream(zip.getEntry("sheet#1/BINARY.png")));
                    byte[] binary = new byte[image.getWidth() * image.getHeight()];
                    for (int y = 0; y < image.getHeight(); y++) {
                        for (int x = 0; x < image.getWidth(); x++) { binary[y * image.getWidth() + x] = (byte) image.getRGB(x, y); }
                    }
                    if (!Arrays.equals(binary, Files.readAllBytes(fixtures.resolve(id + ".binary")))) {
                        throw new AssertionError(id + ": full upstream binary differs");
                    }
                    System.out.println(id + ": full upstream BINARY and line/interline/beam scales match " + Arrays.toString(actual));
                    compared++;
                }
            }
        }
        if (compared == 0) { throw new AssertionError("No full upstream reference results compared"); }
        Files.writeString(fixtures.getParent().resolve("upstream-comparison.json"), "{\"matchedPages\":" + compared + "}\n");
    }
}
