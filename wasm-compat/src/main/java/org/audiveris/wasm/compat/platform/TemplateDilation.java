// SPDX-License-Identifier: AGPL-3.0-or-later
package org.audiveris.wasm.compat.platform;

import ij.process.ByteProcessor;

/** 3x3 minimum for Template's fresh full-frame binary buffer with its default non-inverted LUT. */
public final class TemplateDilation {
    private TemplateDilation() { }
    public static void dilate(ByteProcessor image) {
        int width=image.getWidth(),height=image.getHeight();var roi=image.getRoi();
        if(width<3||height<3||roi.x!=0||roi.y!=0||roi.width!=width||roi.height!=height) {
            throw new IllegalArgumentException("Expected a full template dilation buffer");
        }
        byte[] pixels=(byte[])image.getPixels(),before=pixels.clone();
        for(byte value:before) {if(value!=0&&value!=(byte)255) {throw new IllegalArgumentException("Expected binary template pixels");}}
        for(int y=0;y<height;y++) {for(int x=0;x<width;x++) {
            byte value=(byte)255;
            for(int j=Math.max(0,y-1);j<=Math.min(height-1,y+1)&&value!=0;j++) {
                for(int i=Math.max(0,x-1);i<=Math.min(width-1,x+1);i++) {
                    if(before[j*width+i]==0) {value=0;break;}
                }
            }
            pixels[y*width+x]=value;
        }}
    }
}
