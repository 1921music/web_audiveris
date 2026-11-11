// SPDX-License-Identifier: AGPL-3.0-or-later
package org.audiveris.wasm.compat.test;

import java.awt.Rectangle;
import java.awt.geom.Point2D;
import ij.process.ByteProcessor;
import org.audiveris.omr.glyph.Shape;
import org.audiveris.omr.sig.inter.AbstractInter;
import org.audiveris.omr.ui.symbol.MusicFamily;
import org.audiveris.wasm.compat.platform.*;
import org.teavm.jso.*;
import org.teavm.jso.core.JSArray;
import org.teavm.jso.typedarrays.Int8Array;

public final class MusicSymbolProbe {
    public static void main(String[] args){ }
    @JSExport public static void install(WorkerMusicSymbols.Backend backend){WorkerMusicSymbols.install(backend);}
    @JSExport public static int calls(){return WorkerMusicSymbols.calls();}
    @JSExport public static double rasterMemoryBytes(){return MarlinMemory.usedBytes();}
    @JSExport public static Int8Array paint(String family,String shape,int size,double x,double y,int background){
        byte[] pixels=new byte[160*160];for(int i=0;i<pixels.length;i++)pixels[i]=(byte)(i*37+background*53);
        var raster=new PackedPageRaster(new ByteProcessor(160,160,pixels,null));
        raster.symbol(new Fixture(Shape.valueOf(shape),x,y),MusicFamily.valueOf(family),size);
        return Int8Array.copyFromJavaArray(pixels);
    }
    @JSExport public static boolean invalidMaskIsAtomic(JSArray<WorkerMusicSymbols.Mask> masks){
        byte[] pixels={13};
        try{WorkerMusicSymbols.blend(pixels,1,1,masks);}catch(IllegalArgumentException expected){return pixels[0]==13;}
        return false;
    }
    @JSExport public static Int8Array paintOperations(JSArray<WorkerMusicSymbols.Mask> masks,int background){
        byte[] pixels=new byte[160*160];for(int i=0;i<pixels.length;i++)pixels[i]=(byte)(i*37+background*53);
        WorkerMusicSymbols.blend(pixels,160,160,masks);return Int8Array.copyFromJavaArray(pixels);
    }
    private static final class Fixture extends AbstractInter {
        private final double x,y;
        Fixture(Shape shape,double x,double y){super(null,new Rectangle(0,0,1,1),shape,1.0);this.x=x;this.y=y;}
        @Override public Point2D getCenter2D(){return new Point2D.Double(x,y);}
    }
}
