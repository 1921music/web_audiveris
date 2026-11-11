// SPDX-License-Identifier: AGPL-3.0-or-later
package org.audiveris.wasm.compat.test;
import org.audiveris.wasm.compat.platform.*;
import org.audiveris.omr.ui.symbol.MusicFamily;
import org.audiveris.omr.glyph.Shape;
import org.audiveris.omr.image.TemplateFactory;
import org.teavm.jso.JSExport;
import org.teavm.jso.typedarrays.*;

/** Resource generation from fonts, with no page-dependent catalog or measurement installation. */
public final class EngineResourcesProbe {
    public static void main(String[] args){ }
    @JSExport public static void configure(HeadlessSymbolGeometry.Backend geometry,WorkerTemplateRaster.Backend raster){
        HeadlessConfiguration.configure(java.util.Map.of(),java.util.Map.of());
        HeadlessSymbolGeometry.install(geometry,256);WorkerTemplateRaster.install(raster);
    }
    @JSExport public static Float64Array measure(int kind,String family,String shape,int scale,int num,int den){
        var provider=HeadlessSymbolGeometry.provider();var f=MusicFamily.valueOf(family);var s=Shape.valueOf(shape);double[] values;
        if(kind==0){var b=provider.bounds(f,s,scale);values=new double[]{b.getX(),b.getY(),b.getWidth(),b.getHeight()};}
        else if(kind==1){var p=provider.centroidOffset(f,s,scale);values=new double[]{p.getX(),p.getY(),0,0};}
        else if(kind==2){var d=provider.timeDimension(f,s,scale,num,den);values=new double[]{d.width,d.height,0,0};}
        else if(kind==4)values=new double[]{provider.widthAtPointSize(f,s,scale),0,0,0};
        else throw new IllegalArgumentException("Unknown measurement kind");
        return Float64Array.copyFromJavaArray(values);
    }
    @JSExport public static Int8Array catalog(String family,int size)throws Exception{return Int8Array.copyFromJavaArray(TemplateCodec.catalog(MusicFamily.valueOf(family),size,TemplateFactory.resourceProfile()));}
    @JSExport public static Int8Array matching(String family,int size)throws Exception{return Int8Array.copyFromJavaArray(TemplateCodec.match(MusicFamily.valueOf(family),size));}
    @JSExport public static int generatedRasters(){return WorkerTemplateRaster.renders();}
    @JSExport public static int measurements(){return HeadlessSymbolGeometry.measurements();}
}
