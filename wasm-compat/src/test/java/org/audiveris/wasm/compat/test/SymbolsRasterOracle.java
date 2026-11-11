// SPDX-License-Identifier: AGPL-3.0-or-later
package org.audiveris.wasm.compat.test;

import com.fasterxml.jackson.databind.ObjectMapper;
import ij.process.ByteProcessor;
import java.awt.Graphics2D;
import java.awt.Shape;
import java.awt.Stroke;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferByte;
import java.awt.image.Raster;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import org.audiveris.omr.glyph.Glyph;
import org.audiveris.omr.image.PageRaster;
import org.audiveris.omr.sheet.*;
import org.audiveris.omr.sheet.symbol.SymbolsFilter;
import org.audiveris.omr.sig.inter.Inter;
import org.audiveris.omr.ui.symbol.MusicFamily;
import org.audiveris.wasm.compat.platform.PackedPageRaster;

/** Original AWT cleaner invocation, independent packed erasure and weak-glyph comparison. */
public final class SymbolsRasterOracle {
    private SymbolsRasterOracle(){ }
    public static void compare(Sheet sheet,Path output)throws Exception{
        Files.createDirectories(output);
        var source=sheet.getPicture().getSource(Picture.SourceKey.NO_STAFF);
        BufferedImage originalImage=((ByteProcessor)source.duplicate()).getBufferedImage();var original=new ByteProcessor(originalImage);
        var originalOptionals=new TreeMap<SystemInfo,List<Glyph>>();Graphics2D graphics=originalImage.createGraphics();
        try{
            var type=Class.forName("org.audiveris.omr.sheet.symbol.SymbolsFilter$SymbolsCleaner");
            var constructor=type.getDeclaredConstructor(ByteProcessor.class,Graphics2D.class,Sheet.class);constructor.setAccessible(true);
            var cleaner=constructor.newInstance(original,graphics,sheet);var erase=type.getDeclaredMethod("eraseInters",Map.class);erase.setAccessible(true);erase.invoke(cleaner,originalOptionals);
        }finally{graphics.dispose();}
        original.threshold(127);byte[] expected=((byte[])original.getPixels()).clone();

        var candidate=new ByteProcessor(source.getWidth(),source.getHeight(),((byte[])source.getPixels()).clone(),null);
        var model=originalImage.getColorModel();
        var rasterData=Raster.createInterleavedRaster(new DataBufferByte((byte[])candidate.getPixels(),candidate.getWidth()*candidate.getHeight()),candidate.getWidth(),candidate.getHeight(),candidate.getWidth(),1,new int[]{0},null);
        var sharedImage=new BufferedImage(model,rasterData,model.isAlphaPremultiplied(),null);
        var candidateOptionals=new TreeMap<SystemInfo,List<Glyph>>();var g=sharedImage.createGraphics();var raster=new Hybrid(candidate,g,sheet);
        try{new SymbolsFilter(sheet).eraseBuffer(candidate,raster,candidateOptionals);}finally{g.dispose();}
        byte[] actual=(byte[])candidate.getPixels();long differences=0;for(int i=0;i<expected.length;i++)if(expected[i]!=actual[i])differences++;
        var originalKeys=keys(originalOptionals);var candidateKeys=keys(candidateOptionals);
        if(!originalKeys.equals(candidateKeys))throw new AssertionError("Symbols optional glyph selection changed");
        var report=new LinkedHashMap<String,Object>();report.put("pixels",actual.length);report.put("differentPixels",differences);
        report.put("nativeSha256",sha(expected));report.put("packedSha256",sha(actual));report.put("optionalGlyphs",originalKeys);
        report.put("optionalGlyphSelectionExact",true);report.put("nativeFontPaintCalls",raster.symbols);
        report.put("originalImageSharesBuffer",originalImage.getRaster().getDataBuffer() instanceof DataBufferByte bytes&&bytes.getData()==original.getPixels());
        new ObjectMapper().writerWithDefaultPrettyPrinter().writeValue(output.resolve("symbols-comparison.json").toFile(),report);
        Files.write(output.resolve("symbols.gray"),expected);
        if(differences!=0)throw new AssertionError("Native vs packed symbols pixels differ: "+differences);
    }
    private static List<String> keys(Map<SystemInfo,List<Glyph>> optionals)throws Exception{
        var keys=new ArrayList<String>();
        for(var entry:optionals.entrySet())for(var glyph:entry.getValue()){
            var b=glyph.getBounds();keys.add(entry.getKey().getId()+":"+b.x+":"+b.y+":"+b.width+":"+b.height+":"+sha((byte[])glyph.getRunTable().getBuffer().getPixels()));
        }
        keys.sort(String::compareTo);return keys;
    }
    private static String sha(byte[] pixels)throws Exception{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(pixels));}
    private static final class Native extends PageCleaner {
        Native(ByteProcessor pixels,Graphics2D graphics,Sheet sheet){super(pixels,graphics,sheet);}
        PageRaster raster(){return raster;}
    }
    private static final class Hybrid implements PageRaster {
        private final PageRaster packed,nativeRaster;private int symbols;
        Hybrid(ByteProcessor pixels,Graphics2D graphics,Sheet sheet){packed=new PackedPageRaster(pixels);nativeRaster=new Native(pixels,graphics,sheet).raster();}
        public void initialize(){packed.initialize();nativeRaster.initialize();}
        public Stroke getStroke(){return packed.getStroke();}
        public void setStroke(Stroke value){packed.setStroke(value);nativeRaster.setStroke(value);}
        public void fill(Shape shape){packed.fill(shape);}
        public void draw(Shape shape){packed.draw(shape);}
        public void fillRect(int x,int y,int w,int h){packed.fillRect(x,y,w,h);}
        public void glyph(Glyph glyph){packed.glyph(glyph);}
        public void symbol(Inter inter,MusicFamily family,int size){symbols++;nativeRaster.symbol(inter,family,size);}
    }
}
