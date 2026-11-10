package org.audiveris.wasm.compat.test;

import java.io.*;
import java.util.*;
import ij.process.ByteProcessor;
import org.audiveris.omr.glyph.ShapeSet;
import org.audiveris.omr.image.*;
import org.audiveris.omr.image.Anchored.Anchor;
import org.audiveris.omr.ui.symbol.MusicFamily;

/** Test asset encoding from the public template APIs; no font/raster algorithm is reimplemented. */
final class TemplateCodec {
    static byte[] catalog(MusicFamily family,int size,double[] profile) throws IOException {
        var bytes=new ByteArrayOutputStream();var out=new DataOutputStream(bytes);
        out.writeInt(0x4154504c);out.writeInt(1);out.writeUTF(family.name());out.writeInt(size);
        out.writeInt(profile.length);for(double value:profile) {out.writeDouble(value);}
        var catalog=TemplateFactory.getInstance().getCatalog(family,size);out.writeInt(ShapeSet.Heads.size());
        for(var shape:ShapeSet.Heads) {
            out.writeUTF(shape.name());var t=catalog.getTemplate(shape);out.writeBoolean(t!=null);if(t==null){continue;}
            var slim=t.getSlimBounds();out.writeInt(t.getWidth());out.writeInt(t.getHeight());
            out.writeInt(slim.x);out.writeInt(slim.y);out.writeInt(slim.width);out.writeInt(slim.height);
            var points=t.getKeyPoints();out.writeInt(points.size());
            for(var p:points) {out.writeInt(p.x);out.writeInt(p.y);out.writeDouble(p.d);}
            out.writeInt(t.getOffsets().size());
            for(var e:t.getOffsets().entrySet()) {out.writeUTF(e.getKey().name());out.writeDouble(e.getValue().getX());out.writeDouble(e.getValue().getY());}
        }
        return bytes.toByteArray();
    }
    static byte[] match(MusicFamily family,int size) throws IOException {
        var bytes=new ByteArrayOutputStream();var out=new DataOutputStream(bytes);
        var distances=new DistanceTable.Short(128,96,3);byte[] pixels=new byte[128*96];
        for(int i=0;i<pixels.length;i++) {
            distances.setValue(i,i%17==0?ChamferDistance.VALUE_UNKNOWN:i%7==0?0:i*13%31);
            pixels[i]=i%5==0?0:(byte)255;
        }
        var image=new ByteProcessor(128,96,pixels,null);
        var catalog=TemplateFactory.getInstance().getCatalog(family,size);
        for(var shape:ShapeSet.Heads) {
            var t=catalog.getTemplate(shape);out.writeBoolean(t!=null);if(t==null){continue;}
            List<Anchor> anchors=new ArrayList<>();anchors.add(null);anchors.addAll(t.getOffsets().keySet());
            for(Anchor anchor:anchors) {for(int[] position:new int[][]{{-10,-5},{0,0},{37,43},{127,95}}) {
                int x=position[0],y=position[1];out.writeDouble(t.evaluate(x,y,anchor,distances));out.writeDouble(t.evaluateHole(x,y,anchor,distances));
                for(boolean dilated:new boolean[]{false,true}) {
                    var box=anchor==null?new java.awt.Rectangle(x,y,t.getWidth(),t.getHeight()):t.getBoundsAt(x,y,anchor);
                    var points=t.getForegroundPixels(box,image,dilated);out.writeInt(points.size());
                    for(var point:points) {out.writeInt(point.x);out.writeInt(point.y);}
                }
            }}
        }
        return bytes.toByteArray();
    }
}
