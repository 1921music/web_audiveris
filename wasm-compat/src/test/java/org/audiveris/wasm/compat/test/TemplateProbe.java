package org.audiveris.wasm.compat.test;

import org.audiveris.omr.image.*;
import org.audiveris.omr.glyph.Shape;
import org.audiveris.omr.ui.symbol.MusicFamily;
import org.audiveris.wasm.compat.platform.*;
import org.teavm.jso.JSExport;
import org.teavm.jso.typedarrays.Int8Array;

public final class TemplateProbe {
    public static void main(String[] args) { }
    @JSExport public static void configure(String key,String value) {HeadlessConfiguration.configure(java.util.Map.of(key,value),java.util.Map.of());}
    @JSExport public static void install(Int8Array bytes) throws Exception {WorkerTemplates.install(bytes.copyToJavaArray());}
    @JSExport public static void explicitSource() {TemplateFactory.setSource(WorkerTemplates.source());}
    @JSExport public static Int8Array catalog(String family,int size) throws Exception {
        return Int8Array.copyFromJavaArray(TemplateCodec.catalog(MusicFamily.valueOf(family),size,TemplateFactory.resourceProfile()));
    }
    @JSExport public static Int8Array match(String family,int size) throws Exception {
        return Int8Array.copyFromJavaArray(TemplateCodec.match(MusicFamily.valueOf(family),size));
    }
    @JSExport public static boolean frozen() {
        try {TemplateFactory.setSource(WorkerTemplates.source());return false;}catch(IllegalStateException expected){return true;}
    }
    @JSExport public static boolean missing() {
        try {WorkerTemplates.source().build(Shape.NOTEHEAD_BLACK,MusicFamily.Bravura,9999);return false;}catch(UnsupportedOperationException expected){return true;}
    }
    @JSExport public static boolean rejects(Int8Array bytes) {
        try {install(bytes);return false;}catch(Exception expected){return true;}
    }
    @JSExport public static boolean copyContract(String family,int size) {
        var f=MusicFamily.valueOf(family);var source=WorkerTemplates.source();
        var first=source.build(Shape.NOTEHEAD_BLACK,f,size);
        var second=source.build(Shape.NOTEHEAD_BLACK,f,size);
        if(first==null||second==null) {return false;}
        int count=second.getKeyPoints().size();double x=second.getOffset2D(Anchored.Anchor.CENTER).getX();
        first.getKeyPoints().clear();first.getOffset2D(Anchored.Anchor.CENTER).setLocation(-999,-999);
        var third=source.build(Shape.NOTEHEAD_BLACK,f,size);
        return second.getKeyPoints().size()==count&&third.getKeyPoints().size()==count
                &&second.getOffset2D(Anchored.Anchor.CENTER).getX()==x&&third.getOffset2D(Anchored.Anchor.CENTER).getX()==x;
    }
}
