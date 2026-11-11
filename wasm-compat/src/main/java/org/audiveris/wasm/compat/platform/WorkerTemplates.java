// SPDX-License-Identifier: AGPL-3.0-or-later
package org.audiveris.wasm.compat.platform;

import java.awt.Rectangle;
import java.io.*;
import java.util.*;
import org.audiveris.omr.glyph.Shape;
import org.audiveris.omr.glyph.ShapeSet;
import org.audiveris.omr.image.*;
import org.audiveris.omr.image.Anchored.Anchor;
import org.audiveris.omr.ui.symbol.MusicFamily;

/** Immutable host catalogs. Native matching algorithms consume ordinary Template instances. */
public final class WorkerTemplates implements TemplateFactory.Source {
    public static final int MAGIC=0x4154504c, VERSION=1;
    private static final WorkerTemplates INSTANCE=new WorkerTemplates();
    private static final Map<String,Catalog> catalogs=new HashMap<>();
    private static TemplateFactory.RasterSource raster;
    private record Offset(Anchor anchor,double x,double y) { }
    private record Data(int width,int height,int x,int y,int slimWidth,int slimHeight,
                        List<PixelDistance> points,List<Offset> offsets) { }
    private record Catalog(double[] profile,Map<Shape,Data> entries) { }
    private WorkerTemplates() { }
    public static TemplateFactory.Source source() {return INSTANCE;}
    public static void installRasterSource(TemplateFactory.RasterSource source){
        Objects.requireNonNull(source);if(raster!=null)throw new IllegalStateException("Template raster backend is sealed");raster=source;
    }
    private static String key(MusicFamily family,int pointSize) {return family.name()+":"+pointSize;}
    public static void install(byte[] bytes) throws IOException {
        Objects.requireNonNull(bytes);
        if(bytes.length>64*1024*1024) {throw new IOException("Template catalog exceeds resource size limit");}
        DataInputStream in=new DataInputStream(new ByteArrayInputStream(bytes));
        if(in.readInt()!=MAGIC||in.readInt()!=VERSION) {throw new IOException("Unsupported template catalog format");}
        try {
            MusicFamily family=MusicFamily.valueOf(in.readUTF());int size=in.readInt();
            if(size<=0) {throw new IOException("Invalid template point size");}
            String key=key(family,size);
            if(catalogs.containsKey(key)) {throw new IllegalStateException("Template catalog already installed: "+key);}
            int count=in.readInt();if(count!=7) {throw new IOException("Invalid template parameter profile");}
            double[] profile=new double[count];for(int i=0;i<count;i++) {profile[i]=finite(in.readDouble());}
            count=in.readInt();if(count!=ShapeSet.Heads.size()) {throw new IOException("Incomplete template shape catalog");}
            Map<Shape,Data> entries=new EnumMap<>(Shape.class);
            for(int i=0;i<count;i++) {
                Shape shape=Shape.valueOf(in.readUTF());
                if(!ShapeSet.Heads.contains(shape)||entries.containsKey(shape)) {throw new IOException("Invalid/duplicate template shape: "+shape);}
                if(!in.readBoolean()) {entries.put(shape,null);continue;}
                int w=in.readInt(),h=in.readInt(),x=in.readInt(),y=in.readInt(),sw=in.readInt(),sh=in.readInt();
                if(w<=0||h<=0||sw<0||sh<0) {throw new IOException("Invalid template bounds");}
                int n=in.readInt();if(n<0||n>in.available()/16) {throw new IOException("Invalid template key-point count");}
                List<PixelDistance> points=new ArrayList<>(n);
                for(int j=0;j<n;j++) {
                    int px=in.readInt(),py=in.readInt();double d=finite(in.readDouble());
                    if(px<0||py<0) {throw new IOException("Negative template key-point position");}
                    points.add(new PixelDistance(px,py,d));
                }
                n=in.readInt();if(n<0||n>Anchor.values().length) {throw new IOException("Invalid template anchor count");}
                List<Offset> offsets=new ArrayList<>(n);EnumSet<Anchor> seen=EnumSet.noneOf(Anchor.class);
                for(int j=0;j<n;j++) {
                    Anchor anchor=Anchor.valueOf(in.readUTF());
                    if(!seen.add(anchor)) {throw new IOException("Duplicate template anchor");}
                    offsets.add(new Offset(anchor,finite(in.readDouble()),finite(in.readDouble())));
                }
                entries.put(shape,new Data(w,h,x,y,sw,sh,List.copyOf(points),List.copyOf(offsets)));
            }
            if(in.available()!=0) {throw new IOException("Trailing template resource bytes");}
            // Publish only a fully decoded catalog. Shapes unsupported by the font retain null entries.
            catalogs.put(key,new Catalog(profile,Collections.unmodifiableMap(entries)));
        }catch(IllegalArgumentException error) {throw new IOException("Invalid template resource key",error);}
    }
    private static double finite(double value) throws IOException {
        if(!Double.isFinite(value)) {throw new IOException("Non-finite template resource value");}return value;
    }
    private static Data require(Shape shape,MusicFamily family,int pointSize) {
        Catalog catalog=catalogs.get(key(family,pointSize));
        if(catalog==null) {throw new UnsupportedOperationException("Host must supply template catalog: "+key(family,pointSize));}
        double[] current=TemplateFactory.resourceProfile();
        if(current.length!=catalog.profile.length) {throw new IllegalStateException("Template profile version differs");}
        for(int i=0;i<current.length;i++) {
            if(Double.doubleToLongBits(current[i])!=Double.doubleToLongBits(catalog.profile[i])) {
                throw new IllegalStateException("Template resource parameters differ at index "+i);
            }
        }
        if(!catalog.entries.containsKey(shape)) {throw new UnsupportedOperationException("Shape is outside the installed head catalog: "+shape);}
        return catalog.entries.get(shape);
    }
    @Override public Template build(Shape shape,MusicFamily family,int pointSize) {
        if(TemplateFactory.templateImagesRequested()) {throw new UnsupportedOperationException("Template preview requires a host image backend");}
        if(raster!=null&&!catalogs.containsKey(key(family,pointSize)))return TemplateFactory.buildFromRaster(shape,family,pointSize,raster);
        Data data=require(shape,family,pointSize);if(data==null) {return null;}
        Template result=new Template(shape,family,pointSize,data.width,data.height,data.points,
                new Rectangle(data.x,data.y,data.slimWidth,data.slimHeight));
        for(Offset offset:data.offsets) {result.putOffset(offset.anchor,offset.x,offset.y);}
        return result;
    }
    @Override public List<PixelDistance> keyPoints(Shape shape,MusicFamily family,int pointSize) {
        if(raster!=null&&!catalogs.containsKey(key(family,pointSize)))return TemplateFactory.keyPointsFromRaster(shape,family,pointSize,raster);
        Data data=require(shape,family,pointSize);
        if(data==null) {throw new UnsupportedOperationException("Font has no template for "+shape);}
        return new ArrayList<>(data.points);
    }
}
