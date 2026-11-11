// SPDX-License-Identifier: AGPL-3.0-or-later
package org.audiveris.wasm.compat.platform;

import org.audiveris.omr.image.*;
import org.audiveris.omr.glyph.Shape;
import org.audiveris.omr.ui.symbol.MusicFamily;
import org.teavm.jso.*;
import org.teavm.jso.core.JSArray;

/** Font-derived coverage only; the original factory builds all matching data locally. */
public final class WorkerTemplateRaster implements TemplateFactory.RasterSource {
    @JSFunctor public interface Backend extends JSObject {
        Result render(String family,String shape,int size,boolean small,double padding);
    }
    public interface Result extends JSObject {
        @JSProperty double getWidth(); @JSProperty double getHeight();
        @JSProperty double getFatX(); @JSProperty double getFatY();
        @JSProperty double getFatWidth(); @JSProperty double getFatHeight();
        @JSProperty JSArray<WorkerMusicSymbols.Mask> getMasks();
    }
    private final Backend backend;
    private static int rendered;
    private WorkerTemplateRaster(Backend backend){this.backend=backend;}
    public static void install(Backend backend){java.util.Objects.requireNonNull(backend);WorkerTemplates.installRasterSource(new WorkerTemplateRaster(backend));}
    public static int renders(){return rendered;}
    private static int integer(double value){if(!Double.isFinite(value)||value!=Math.rint(value)||value<0||value>16384)throw new IllegalArgumentException("Invalid template raster dimension");return (int)value;}
    public TemplateFactory.Raster render(Shape shape,MusicFamily family,int pointSize){
        if(pointSize<=0||pointSize>4096)throw new IllegalArgumentException("Unsupported template point size");
        Result result=backend.render(family.name(),shape.name(),pointSize,shape.isSmallHead(),TemplateFactory.maxDistanceFromSymbol(pointSize));
        if(result==null)return null;
        int width=integer(result.getWidth()),height=integer(result.getHeight());
        if(width==0||height==0||(long)width*height>16*1024*1024)throw new IllegalArgumentException("Template raster pixel budget");
        var fat=new java.awt.Rectangle(integer(result.getFatX()),integer(result.getFatY()),integer(result.getFatWidth()),integer(result.getFatHeight()));
        if((long)fat.x+fat.width>width||(long)fat.y+fat.height>height)throw new IllegalArgumentException("Template bounds outside raster");
        byte[] coverage=new byte[width*height];WorkerMusicSymbols.blend(coverage,width,height,result.getMasks());
        int[] argb=new int[coverage.length];for(int i=0;i<argb.length;i++)argb[i]=(coverage[i]&255)<<24;
        rendered++;return new TemplateFactory.Raster(new PackedColorImage(width,height,argb),fat);
    }
}
