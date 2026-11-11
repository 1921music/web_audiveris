// SPDX-License-Identifier: AGPL-3.0-or-later
package org.audiveris.omr.sheet.symbol;

import ij.process.ByteProcessor;
import java.util.List;
import java.util.Map;
import org.audiveris.omr.glyph.Glyph;
import org.audiveris.omr.sheet.Picture;
import org.audiveris.omr.sheet.SystemInfo;
import org.audiveris.wasm.compat.platform.PackedPageRaster;

/** Original strong/weak erasure and glyph extraction into an owned staff-free buffer. */
public final class WorkerSymbolsBridge {
    @FunctionalInterface public interface Observer{void accept(byte[] pixels);}
    private static Observer observer;
    private WorkerSymbolsBridge(){ }
    public static void setObserver(Observer value){observer=value;}
    public static void process(SymbolsFilter filter,Map<SystemInfo,List<Glyph>> optionals){
        var sheet=filter.getSheet();var source=sheet.getPicture().getSource(Picture.SourceKey.NO_STAFF);
        var buffer=new ByteProcessor(source.getWidth(),source.getHeight(),((byte[])source.getPixels()).clone(),null);
        filter.processBuffer(buffer,new PackedPageRaster(buffer),optionals);
        if(observer!=null)observer.accept(((byte[])buffer.getPixels()).clone());
    }
}
