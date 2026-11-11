// SPDX-License-Identifier: AGPL-3.0-or-later
package org.audiveris.wasm.compat.test;

import com.fasterxml.jackson.databind.ObjectMapper;
import ij.process.ByteProcessor;
import java.awt.Graphics2D;
import java.awt.Shape;
import java.awt.Stroke;
import java.awt.image.DataBufferByte;
import java.awt.image.BufferedImage;
import java.awt.image.Raster;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import org.audiveris.omr.glyph.Glyph;
import org.audiveris.omr.image.PageRaster;
import org.audiveris.omr.sheet.*;
import org.audiveris.omr.sheet.curve.Skeleton;
import org.audiveris.omr.sig.inter.Inter;
import org.audiveris.omr.ui.symbol.MusicFamily;
import org.audiveris.wasm.compat.platform.PackedPageRaster;

/** Independent native skeleton, then packed erasure with native glyph painting as a diagnostic. */
public final class CurveSkeletonOracle {
    private static ByteProcessor buffer(Skeleton skeleton)throws Exception{
        var field=Skeleton.class.getDeclaredField("buf");field.setAccessible(true);return (ByteProcessor)field.get(skeleton);
    }
    public static void compare(Sheet sheet,Path output)throws Exception{
        Files.createDirectories(output);var original=new Skeleton(sheet);original.buildSkeleton();byte[] expected=((byte[])buffer(original).getPixels()).clone();
        var source=sheet.getPicture().getSource(Picture.SourceKey.BINARY);
        var pixels=new ByteProcessor(source.getWidth(),source.getHeight(),((byte[])source.getPixels()).clone(),null);pixels.skeletonize();
        var model=pixels.getBufferedImage().getColorModel();
        var rasterData=Raster.createInterleavedRaster(new DataBufferByte((byte[])pixels.getPixels(),pixels.getWidth()*pixels.getHeight()),pixels.getWidth(),pixels.getHeight(),pixels.getWidth(),1,new int[]{0},null);
        var image=new BufferedImage(model,rasterData,model.isAlphaPremultiplied(),null);
        var graphics=image.createGraphics();var raster=new Hybrid(pixels,graphics,sheet);var candidate=new Skeleton(sheet);
        try{candidate.cleanBuffer(pixels,raster);}finally{graphics.dispose();}
        byte[] actual=(byte[])buffer(candidate).getPixels();long differences=0;for(int i=0;i<actual.length;i++)if(actual[i]!=expected[i])differences++;
        var erased=Skeleton.class.getDeclaredMethod("getErasedInters",boolean.class);erased.setAccessible(true);
        if(!erased.invoke(original,false).equals(erased.invoke(candidate,false))||!erased.invoke(original,true).equals(erased.invoke(candidate,true)))throw new AssertionError("Skeleton erasure decisions changed");
        var digest=MessageDigest.getInstance("SHA-256");var report=Map.of("pixels",actual.length,"differentPixels",differences,"nativeSha256",HexFormat.of().formatHex(digest.digest(expected)),"packedSha256",HexFormat.of().formatHex(digest.digest(actual)),"nativeFontPaintCalls",raster.symbols,"erasureDecisionsExact",true);
        new ObjectMapper().writerWithDefaultPrettyPrinter().writeValue(output.resolve("skeleton-comparison.json").toFile(),report);
        Files.write(output.resolve("skeleton.gray"),expected);
        if(differences!=0)throw new AssertionError("Native vs packed skeleton pixels differ: "+differences);
    }
    private static final class Native extends PageCleaner {
        Native(ByteProcessor pixels,Graphics2D graphics,Sheet sheet){super(pixels,graphics,sheet);}
        PageRaster raster(){return raster;}
    }
    private static final class Hybrid implements PageRaster {
        private final PageRaster packed,nativeRaster;private int symbols;
        Hybrid(ByteProcessor pixels,Graphics2D graphics,Sheet sheet){packed=new PackedPageRaster(pixels);nativeRaster=new Native(pixels,graphics,sheet).raster();}
        public void initialize(){packed.initialize();nativeRaster.initialize();}
        public Stroke getStroke(){return packed.getStroke();}
        public void setStroke(Stroke stroke){packed.setStroke(stroke);nativeRaster.setStroke(stroke);}
        public void fill(Shape shape){packed.fill(shape);}
        public void draw(Shape shape){packed.draw(shape);}
        public void fillRect(int x,int y,int width,int height){packed.fillRect(x,y,width,height);}
        public void glyph(Glyph glyph){packed.glyph(glyph);}
        public void symbol(Inter inter,MusicFamily family,int size){symbols++;nativeRaster.symbol(inter,family,size);}
    }
}
