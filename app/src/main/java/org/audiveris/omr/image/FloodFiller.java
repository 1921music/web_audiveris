//------------------------------------------------------------------------------------------------//
//                                                                                                //
//                                     F l o o d F i l l e r                                      //
//                                                                                                //
//------------------------------------------------------------------------------------------------//
// <editor-fold defaultstate="collapsed" desc="hdr">
//
//  Copyright © Audiveris 2026. All rights reserved.
//
//  This program is free software: you can redistribute it and/or modify it under the terms of the
//  GNU Affero General Public License as published by the Free Software Foundation, either version
//  3 of the License, or (at your option) any later version.
//
//  This program is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY;
//  without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
//  See the GNU Affero General Public License for more details.
//
//  You should have received a copy of the GNU Affero General Public License along with this
//  program.  If not, see <http://www.gnu.org/licenses/>.
//------------------------------------------------------------------------------------------------//
// </editor-fold>
package org.audiveris.omr.image;

import java.awt.image.BufferedImage;

/**
 * Class <code>FloodFiller</code>
 *
 * @author Hervé Bitteur
 */
public class FloodFiller
{
    //~ Instance fields ----------------------------------------------------------------------------

    private final ColorImage image;

    private final int width;

    private final int height;

    //~ Constructors -------------------------------------------------------------------------------

    /**
     * Creates a new FloodFiller object.
     *
     * @param image the image to fill
     */
    public FloodFiller (BufferedImage image)
    {
        this(ColorImage.of(image));
    }

    public FloodFiller (ColorImage image)
    {
        this.image = image;
        width = image.getWidth();
        height = image.getHeight();
    }

    //~ Methods ------------------------------------------------------------------------------------

    /**
     * Flood fill the area from provided (x,y) location by iteratively converting
     * pixels from oldColor to newColor.
     * <p>
     * This implementation flood fills only vertically and horizontally, not in diagonal.
     *
     * @param x        the abscissa of the pixel to process
     * @param y        the ordinate of the pixel to process
     * @param oldColor the color to be replaced
     * @param newColor the color to replace with
     */
    public void fill (int x,
                      int y,
                      int oldColor,
                      int newColor)
    {
        if(x<0||y<0||x>=width||y>=height||oldColor==newColor||image.getRGB(x,y)!=oldColor)return;
        // Mark when enqueuing: each pixel occupies the bounded stack at most once.
        // This preserves four-connected fill without using the Java call stack for large holes.
        int[] pending=new int[Math.multiplyExact(width,height)];int size=0;
        image.setRGB(x,y,newColor);pending[size++]=y*width+x;
        while(size>0){
            int index=pending[--size],px=index%width,py=index/width;
            for(int side=0;side<4;side++){
                int nx=px+(side==0?-1:side==3?1:0),ny=py+(side==1?-1:side==2?1:0);
                if(nx>=0&&ny>=0&&nx<width&&ny<height&&image.getRGB(nx,ny)==oldColor){image.setRGB(nx,ny,newColor);pending[size++]=ny*width+nx;}
            }
        }
    }

    /**
     * Check if the pixel at provided (x,y) location is an isolated pixel, and if so,
     * replace it by newColor.
     *
     * @param x        the abscissa of the pixel to process
     * @param y        the ordinate of the pixel to process
     * @param newColor the color to replace with
     */
    public void adjust (int x,
                        int y,
                        int newColor)
    {
        // Check if pixel is isolated (no vertical/horizontal neighbor w/ same color)
        final int pix = image.getRGB(x, y);

        if (image.getRGB(x - 1, y) == pix) {
            return;
        }

        if (image.getRGB(x, y - 1) == pix) {
            return;
        }

        if (image.getRGB(x, y + 1) == pix) {
            return;
        }

        if (image.getRGB(x + 1, y) == pix) {
            return;
        }

        image.setRGB(x, y, newColor);
    }
}
