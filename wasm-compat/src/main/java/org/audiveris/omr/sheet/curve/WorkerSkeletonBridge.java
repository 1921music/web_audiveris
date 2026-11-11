// SPDX-License-Identifier: AGPL-3.0-or-later
package org.audiveris.omr.sheet.curve;

import ij.process.BinaryProcessor;
import ij.process.ByteProcessor;
import java.awt.image.BufferedImage;
import org.audiveris.omr.sheet.Picture;
import org.audiveris.wasm.compat.platform.PackedPageRaster;

/** Owned black-foreground skeleton and original erasure decisions; no desktop image is created. */
public final class WorkerSkeletonBridge {
    @FunctionalInterface public interface Observer {void accept(byte[] pixels);}
    private static Observer observer;
    private WorkerSkeletonBridge(){ }
    /** Optional diagnostic receives owned pre-retrieval pixels, never the mutable working raster. */
    public static void setObserver(Observer value){observer=value;}
    public static ij.process.ImageProcessor fresh(int width,int height){
        return new ByteProcessor(width,height,new byte[Math.multiplyExact(width,height)],null);
    }
    public static void insert(BinaryProcessor target,ij.process.ImageProcessor source,int x,int y){
        org.audiveris.wasm.compat.platform.HeadlessGrayCopy.copyBits(target,source,x,y,ij.process.Blitter.COPY);
    }
    /** Four inclusive one-pixel boundary lines used by the pinned thinning implementation. */
    public static void clearBorder(BinaryProcessor target,int background){
        if(background!=255)throw new UnsupportedOperationException("Skeleton requires black foreground");
        int width=target.getWidth(),height=target.getHeight();byte[] pixels=(byte[])target.getPixels();
        java.util.Arrays.fill(pixels,0,width,(byte)255);
        java.util.Arrays.fill(pixels,(height-1)*width,height*width,(byte)255);
        for(int y=0;y<height;y++){pixels[y*width]=(byte)255;pixels[y*width+width-1]=(byte)255;}
    }
    public static BufferedImage build(Skeleton skeleton){
        var sheet=skeleton.getSheet();ByteProcessor source=sheet.getPicture().getSource(Picture.SourceKey.BINARY);
        var buffer=new ByteProcessor(source.getWidth(),source.getHeight(),((byte[])source.getPixels()).clone(),null);
        new BinaryProcessor(buffer).skeletonize(0);
        skeleton.cleanBuffer(buffer,new PackedPageRaster(buffer));
        if(observer!=null)observer.accept(((byte[])buffer.getPixels()).clone());
        // Curves uses the returned image only inside its unavailable desktop-view branch.
        return null;
    }
}
