// SPDX-License-Identifier: AGPL-3.0-or-later
package org.audiveris.omr.sheet.curve;

import ij.process.ByteProcessor;
import java.awt.Rectangle;
import java.util.List;
import org.audiveris.wasm.compat.platform.PackedPageRaster;

/** Owned rehearsal crops and integer rectangle erasure, without desktop image allocation. */
public final class WorkerRehearsalsBridge {
    private WorkerRehearsalsBridge(){ }
    public static ByteProcessor crop(ByteProcessor source){
        Rectangle roi=source.getRoi();int width=roi.width,height=roi.height;
        byte[] input=(byte[])source.getPixels(),output=new byte[Math.multiplyExact(width,height)];
        for(int y=0;y<height;y++)System.arraycopy(input,(roi.y+y)*source.getWidth()+roi.x,output,y*width,width);
        return new ByteProcessor(width,height,output,null);
    }
    public static ByteProcessor cleanScene(RehearsalsBuilder owner,ByteProcessor buffer,Rectangle scene,List<Rectangle> rectangles,int id){
        var raster=new PackedPageRaster(buffer);
        for(Rectangle rect:rectangles)raster.fillRect(rect.x-scene.x,rect.y-scene.y,rect.width,rect.height);
        return buffer;
    }
}
