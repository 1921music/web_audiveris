// SPDX-License-Identifier: AGPL-3.0-or-later
package org.audiveris.wasm.compat;

import org.teavm.model.*;

/** Bind the original symbol filter's erasure/glyph decisions to the packed raster entry. */
final class SymbolsRasterPatches {
    static void apply(ClassHolder cls){
        if(!cls.getName().equals("org.audiveris.omr.sheet.symbol.SymbolsFilter"))return;
        var map=ValueType.object("java.util.Map");
        if(cls.getMethod(new MethodDescriptor("process",map,ValueType.VOID))==null)throw new IllegalStateException("Review symbols filter entry");
        StandardApiPatches.delegate(cls,"process","process","org.audiveris.omr.sheet.symbol.WorkerSymbolsBridge",false,true,map,ValueType.VOID);
    }
}
