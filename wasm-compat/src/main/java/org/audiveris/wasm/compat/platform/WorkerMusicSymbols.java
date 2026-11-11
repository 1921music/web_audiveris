// SPDX-License-Identifier: AGPL-3.0-or-later
package org.audiveris.wasm.compat.platform;

import java.util.ArrayList;
import org.teavm.jso.*;
import org.teavm.jso.core.JSArray;
import org.teavm.jso.typedarrays.Int8Array;
import org.teavm.jso.typedarrays.Float64Array;
import java.awt.geom.Path2D;

/** Module-local synchronous font masks; the host resolves logical symbols, never Java handles. */
public final class WorkerMusicSymbols {
    @JSFunctor public interface Backend extends JSObject {
        JSArray<Mask> paint(String family,String shape,int pointSize,double centerX,double centerY);
    }
    public interface Mask extends JSObject {
        @JSProperty double getX(); @JSProperty double getY();
        @JSProperty double getWidth(); @JSProperty double getHeight();
        @JSProperty Int8Array getPixels();
        @JSProperty Float64Array getOutline(); @JSProperty double getWindingRule();
    }
    private record Tile(int x,int y,int width,int height,byte[] pixels) { }
    private record Paint(Tile tile,Path2D.Float outline) { }
    private static Backend backend;
    private static int calls;
    @JSBody(params="value",script="return Array.isArray(value);")
    private static native boolean array(JSObject value);
    @JSBody(params="value",script="return value.outline != null;")
    private static native boolean hasOutline(JSObject value);
    @JSBody(params="value",script="return value instanceof Float64Array;")
    private static native boolean doubles(JSObject value);
    @JSBody(params="value",script="return value instanceof Int8Array;")
    private static native boolean bytes(JSObject value);
    private WorkerMusicSymbols(){ }
    public static void install(Backend value){
        java.util.Objects.requireNonNull(value);
        if(backend!=null)throw new IllegalStateException("Music symbol backend is sealed");
        backend=value;
    }
    public static int calls(){return calls;}
    public static void paint(byte[] pixels,int width,int height,String family,String shape,int pointSize,double x,double y){
        if(backend==null)throw new UnsupportedOperationException("Music symbol backend is not configured: "+shape);
        if(pointSize<=0||pointSize>128||!Double.isFinite(x)||!Double.isFinite(y)||Math.abs(x)>4194304||Math.abs(y)>4194304)throw new IllegalArgumentException("Invalid symbol request");
        JSArray<Mask> masks=backend.paint(family,shape,pointSize,x,y);
        blend(pixels,width,height,masks);calls++;
    }
    private static int integer(double value){
        if(!Double.isFinite(value)||value!=Math.rint(value)||Math.abs(value)>4194304)throw new IllegalArgumentException("Invalid symbol mask coordinate");
        return (int)value;
    }
    public static void blend(byte[] pixels,int width,int height,JSArray<Mask> masks){
        if(width<=0||height<=0||(long)width*height!=pixels.length||masks==null||!array(masks)||masks.getLength()<0||masks.getLength()>64)throw new IllegalArgumentException("Invalid symbol masks");
        var copied=new ArrayList<Paint>();long total=0;
        // Validate and own every mask before changing the destination.
        for(int i=0;i<masks.getLength();i++){
            Mask mask=masks.get(i);if(mask==null)throw new IllegalArgumentException("Missing symbol mask");
            if(hasOutline(mask)){
                Float64Array outline=mask.getOutline();if(!doubles(outline))throw new IllegalArgumentException("Invalid outline buffer");
                int rule=integer(mask.getWindingRule());
                if((rule!=0&&rule!=1)||outline.getLength()>65536||(total+=outline.getLength())>1048576)throw new IllegalArgumentException("Invalid symbol outline");
                double[] values=outline.copyToJavaArray();Path2D.Float path=new Path2D.Float(rule);boolean started=false;
                for(int at=0;at<values.length;){
                    int type=integer(values[at++]),count=switch(type){case 0,1->2;case 2->4;case 3->6;case 4->0;default->throw new IllegalArgumentException("Invalid symbol segment");};
                    if(at+count>values.length||(!started&&type!=0))throw new IllegalArgumentException("Incomplete symbol outline");
                    for(int j=0;j<count;j++)if(!Double.isFinite(values[at+j])||Math.abs(values[at+j])>4194304)throw new IllegalArgumentException("Invalid symbol point");
                    switch(type){
                        case 0-> {path.moveTo(values[at],values[at+1]);started=true;}
                        case 1->path.lineTo(values[at],values[at+1]);
                        case 2->path.quadTo(values[at],values[at+1],values[at+2],values[at+3]);
                        case 3->path.curveTo(values[at],values[at+1],values[at+2],values[at+3],values[at+4],values[at+5]);
                        case 4->path.closePath();
                    }
                    at+=count;
                }
                copied.add(new Paint(null,path));continue;
            }
            int x=integer(mask.getX()),y=integer(mask.getY()),w=integer(mask.getWidth()),h=integer(mask.getHeight());
            Int8Array source=mask.getPixels();
            if(w<0||h<0||w>512||h>512||source==null||!bytes(source)||(long)w*h!=source.getLength()||(total+=(long)w*h)>1048576)throw new IllegalArgumentException("Invalid symbol mask dimensions");
            copied.add(new Paint(new Tile(x,y,w,h,source.copyToJavaArray()),null));
        }
        for(Paint paint:copied){
            if(paint.outline!=null){GrayAlphaRaster.fill(pixels,width,height,paint.outline);continue;}
            Tile tile=paint.tile;
            int x0=Math.max(0,tile.x),y0=Math.max(0,tile.y),x1=Math.min(width,tile.x+tile.width),y1=Math.min(height,tile.y+tile.height);
            for(int y=y0;y<y1;y++)for(int x=x0;x<x1;x++){
                int alpha=tile.pixels[(y-tile.y)*tile.width+x-tile.x]&255,index=y*width+x,old=pixels[index]&255;
                pixels[index]=(byte)(alpha+(old*(255-alpha)+127)/255);
            }
        }
    }
}
