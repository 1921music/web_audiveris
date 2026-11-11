// SPDX-License-Identifier: AGPL-3.0-or-later
package org.audiveris.wasm.compat.test;

import ij.process.ByteProcessor;
import java.awt.BasicStroke;
import java.util.Arrays;
import org.audiveris.wasm.compat.platform.PackedPageRaster;
import org.teavm.jso.JSExport;
import org.teavm.jso.typedarrays.Int8Array;

/** Exercise the actual PageRaster thin branch in Wasm, independently of whole-score fixtures. */
public final class PackedThinProbe {
    public static void main(String[] args) { }
    private static byte[] portable(int index) {
        var buffer = new ByteProcessor(GrayPathProbe.WIDTH,GrayPathProbe.HEIGHT,GrayPathProbe.input(index),null);
        var backend = new PackedPageRaster(buffer); backend.initialize();
        backend.setStroke(new BasicStroke(new float[]{0,.25f,.5f,1}[index % 4],index % 3,(index / 3) % 3));
        backend.draw(GrayThinProbe.shape(index)); return (byte[])buffer.getPixels();
    }
    @JSExport public static int cases() { return GrayThinProbe.SHAPES; }
    @JSExport public static String hashes() {
        var result = new StringBuilder(); for (int i=0;i<cases();i++) { result.append(Arrays.hashCode(portable(i))).append(','); } return result.toString();
    }
    @JSExport public static Int8Array pixels() {
        int size=GrayPathProbe.WIDTH*GrayPathProbe.HEIGHT; byte[] result=new byte[cases()*size];
        for (int i=0;i<cases();i++) { System.arraycopy(portable(i),0,result,i*size,size); } return Int8Array.copyFromJavaArray(result);
    }
    @JSExport public static int guards() {
        var backend = new PackedPageRaster(new ByteProcessor(1,1)); int checked=0;
        try { backend.setStroke(shape -> shape); } catch (UnsupportedOperationException expected) { checked++; }
        backend.setStroke(new BasicStroke(.5f,BasicStroke.CAP_SQUARE,BasicStroke.JOIN_MITER,10,new float[]{1,1},0));
        try { backend.draw(new java.awt.geom.Line2D.Double(0,0,1,1)); } catch (UnsupportedOperationException expected) { checked++; }
        if (checked!=2) { throw new AssertionError("Packed thin guards"); } return checked;
    }
}
