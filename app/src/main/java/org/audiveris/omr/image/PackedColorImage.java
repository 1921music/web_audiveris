// SPDX-License-Identifier: AGPL-3.0-or-later
package org.audiveris.omr.image;

/** Owned ARGB pixels for the original template algorithms. */
public final class PackedColorImage implements ColorImage {
    private final int width,height;
    private final int[] pixels;
    public PackedColorImage(int width,int height,int[] owned){
        if(width<=0||height<=0||owned==null||(long)width*height!=owned.length)throw new IllegalArgumentException("Color image dimensions");
        this.width=width;this.height=height;this.pixels=owned;
    }
    public int getWidth(){return width;}
    public int getHeight(){return height;}
    private int index(int x,int y){if(x<0||y<0||x>=width||y>=height)throw new IndexOutOfBoundsException();return y*width+x;}
    public int getRGB(int x,int y){return pixels[index(x,y)];}
    public void setRGB(int x,int y,int argb){pixels[index(x,y)]=argb;}
}
