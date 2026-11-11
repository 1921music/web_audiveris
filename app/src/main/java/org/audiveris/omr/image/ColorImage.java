// SPDX-License-Identifier: AGPL-3.0-or-later
package org.audiveris.omr.image;

/** Pixel access used by template processing, independent of a desktop color model. */
public interface ColorImage {
    int getWidth();
    int getHeight();
    int getRGB(int x,int y);
    void setRGB(int x,int y,int argb);
    static ColorImage of(java.awt.image.BufferedImage image){return new Desktop(image);}
    final class Desktop implements ColorImage {
        private final java.awt.image.BufferedImage image;
        Desktop(java.awt.image.BufferedImage image){this.image=java.util.Objects.requireNonNull(image);}
        public int getWidth(){return image.getWidth();}
        public int getHeight(){return image.getHeight();}
        public int getRGB(int x,int y){return image.getRGB(x,y);}
        public void setRGB(int x,int y,int argb){image.setRGB(x,y,argb);}
    }
}
