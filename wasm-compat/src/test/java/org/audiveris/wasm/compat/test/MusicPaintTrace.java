// SPDX-License-Identifier: AGPL-3.0-or-later
package org.audiveris.wasm.compat.test;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.awt.*;
import java.awt.font.GlyphVector;
import java.awt.geom.Point2D;
import java.awt.image.*;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.List;
import org.audiveris.omr.glyph.Shape;
import org.audiveris.omr.ui.symbol.*;

/** JVM-only experiment: does filling actual native glyph outlines reproduce symbol painting? */
public final class MusicPaintTrace {
    private static final int SIDE = 160;

    public static void main(String[] args) throws Exception {
        MusicFont.populateAllSymbols();
        var nativeImage = new BufferedImage(SIDE,SIDE,BufferedImage.TYPE_BYTE_GRAY);
        var outlineImage = new BufferedImage(SIDE,SIDE,BufferedImage.TYPE_BYTE_GRAY);
        byte[] nativePixels = ((DataBufferByte)nativeImage.getRaster().getDataBuffer()).getData();
        byte[] outlinePixels = ((DataBufferByte)outlineImage.getRaster().getDataBuffer()).getData();
        var original = nativeImage.createGraphics(); var stats = new Stats();
        var outlined = new OutlineGraphics(outlineImage.createGraphics(),stats);
        for (var g : new Graphics2D[]{original,outlined}) {
            g.setColor(Color.WHITE);
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING,RenderingHints.VALUE_ANTIALIAS_OFF);
            g.setStroke(new BasicStroke(1));
        }
        var families = new TreeMap<String,Object>(); var examples = new ArrayList<Object>();
        var fontFiles = new TreeMap<String,String>();
        for (MusicFamily family : MusicFamily.values()) {
            Path path = Path.of("res",family.getFileName());
            fontFiles.put(family.getFileName(),HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path))));
        }
        int cases = 0, differentCases = 0; long differentPixels = 0;
        Shape[] shapes = {Shape.NOTEHEAD_BLACK,Shape.NOTEHEAD_VOID,Shape.NOTEHEAD_BLACK_SMALL,
            Shape.WHOLE_REST,Shape.HALF_REST,Shape.QUARTER_REST,Shape.EIGHTH_REST,Shape.ONE_16TH_REST,
            Shape.FERMATA,Shape.FERMATA_BELOW,Shape.DYNAMICS_P,Shape.DYNAMICS_PP,Shape.DYNAMICS_MP,
            Shape.DYNAMICS_F,Shape.DYNAMICS_FF,Shape.DYNAMICS_MF,Shape.DYNAMICS_SFZ,
            Shape.PEDAL_MARK,Shape.PEDAL_UP_MARK};
        try {
            for (MusicFamily family : MusicFamily.values()) {
                int familyCases = 0, familyDifferent = 0;
                for (Shape shape : shapes) for (int size : new int[]{12,19,24,37,48}) {
                    FontSymbol fs = shape.getFontSymbolBySize(family,size);
                    if (fs.symbol == null) { throw new AssertionError("Missing selected symbol: " + family + "/" + shape); }
                    for (double fraction : new double[]{0,.25,.5,.75}) {
                        Arrays.fill(nativePixels,(byte)0); Arrays.fill(outlinePixels,(byte)0);
                        var center = new Point2D.Double(SIDE/2.0+fraction,SIDE/2.0+fraction);
                        int before = stats.calls;
                        fs.symbol.paintSymbol(original,fs.font,center,Alignment.AREA_CENTER);
                        fs.symbol.paintSymbol(outlined,fs.font,center,Alignment.AREA_CENTER);
                        if (stats.calls == before) { throw new AssertionError("No glyph drawing: " + family + "/" + shape); }
                        long difference = 0;
                        for (int i=0;i<nativePixels.length;i++) if (nativePixels[i]!=outlinePixels[i]) difference++;
                        cases++; familyCases++; differentPixels+=difference;
                        if (difference!=0) {
                            differentCases++; familyDifferent++;
                            if (examples.size()<12) examples.add(Map.of("family",family.name(),"shape",shape.name(),
                                "size",size,"fraction",fraction,"differentPixels",difference));
                        }
                    }
                }
                families.put(family.name(),Map.of("cases",familyCases,"differentCases",familyDifferent));
            }
        } finally { original.dispose(); outlined.dispose(); }
        if (cases!=2280) throw new AssertionError("Incomplete fixture grid: " + cases);
        Path output = Path.of(args[0]); Files.createDirectories(output);
        var report = new LinkedHashMap<String,Object>();
        report.put("javaRuntime",System.getProperty("java.runtime.version"));
        report.put("cases",cases); report.put("shapes",shapes.length); report.put("pointSizes",List.of(12,19,24,37,48));
        report.put("fractionalCenters",List.of(0,.25,.5,.75)); report.put("pixelsPerCase",nativePixels.length);
        report.put("differentCases",differentCases); report.put("differentPixels",differentPixels);
        report.put("nativeGlyphVectorCalls",stats.calls); report.put("families",families); report.put("examples",examples);
        report.put("fontSha256",fontFiles);
        report.put("limitation","Diagnostic only: both paths use actual JVM fonts and glyph positioning. Outline filling is not a portable music-font implementation or an accepted approximation.");
        new ObjectMapper().writerWithDefaultPrettyPrinter().writeValue(output.resolve("verification.json").toFile(),report);
        System.out.println("Native music glyph painting vs native outline filling: " + differentCases + "/" + cases + " cases differ, " + differentPixels + " pixels");
    }

    private static final class Stats { int calls; }
    private static final class OutlineGraphics extends GraphicsDelegate {
        private final Stats stats;
        OutlineGraphics(Graphics2D delegate,Stats stats) { super(delegate);this.stats=stats; }
        @Override public Graphics create() { return new OutlineGraphics((Graphics2D)delegate.create(),stats); }
        @Override public void drawGlyphVector(GlyphVector glyphs,float x,float y) {
            stats.calls++; delegate.fill(glyphs.getOutline(x,y));
        }
        @Override public void drawString(String text,float x,float y) { throw new AssertionError("Unexpected drawString"); }
        @Override public void drawString(String text,int x,int y) { throw new AssertionError("Unexpected drawString"); }
    }
}
