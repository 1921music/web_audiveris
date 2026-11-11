// SPDX-License-Identifier: AGPL-3.0-or-later
package org.audiveris.wasm.compat.platform;

import java.awt.Shape;
import sun.java2d.marlin.DMarlinRenderingEngine;
import sun.java2d.pipe.AATileGenerator;
import sun.java2d.pipe.Region;

/** JDK AA fill coverage with rectangular clipping and white SrcOver onto byte gray. */
public final class GrayAlphaRaster {
    private static final DMarlinRenderingEngine ENGINE = new DMarlinRenderingEngine();
    private GrayAlphaRaster() { }
    public static void fill(byte[] pixels,int width,int height,Shape shape) {
        if (width <= 0 || height <= 0 || (long)width*height != pixels.length) throw new IllegalArgumentException("AA raster dimensions");
        int[] box = new int[4];
        AATileGenerator generator = ENGINE.getAATileGenerator(shape,null,Region.getInstanceXYWH(0,0,width,height),null,false,false,box);
        if (generator == null) return;
        try {
            int tw = generator.getTileWidth(), th = generator.getTileHeight(); byte[] tile = new byte[tw*th];
            for (int y=box[1];y<box[3];y+=th) for (int x=box[0];x<box[2];x+=tw) {
                int typical = generator.getTypicalAlpha();
                if (typical == 0 || typical == 255) generator.nextTile(); else generator.getAlpha(tile,0,tw);
                if (typical == 0) continue;
                int w = Math.min(tw,box[2]-x), h = Math.min(th,box[3]-y);
                for (int dy=0;dy<h;dy++) for (int dx=0;dx<w;dx++) {
                    int alpha = typical == 255 ? 255 : tile[dy*tw+dx]&255, index=(y+dy)*width+x+dx, old=pixels[index]&255;
                    pixels[index]=(byte)(alpha+(old*(255-alpha)+127)/255);
                }
            }
        } finally { generator.dispose(); }
    }
}
