package org.audiveris.wasm.compat.test;

import org.audiveris.wasm.compat.platform.PortableTextGeometry;
import org.teavm.jso.JSExport;
import org.teavm.jso.JSObject;
import org.teavm.jso.typedarrays.Int8Array;

/** Separate opt-in entry: ordinary text from physical fonts, with explicit music resources. */
public final class FontBackedTextsProbe {
    public static void main(String[] args) { }
    @JSExport public static void configure(Int8Array bytes) { TextsContinuationProbe.configure(bytes); }
    @JSExport public static void installTemplates(Int8Array bytes) throws Exception { TextsContinuationProbe.installTemplates(bytes); }
    @JSExport public static void configureSerifs(String f,int i,double a,double b,double c,double d) { TextsContinuationProbe.configureSerifs(f,i,a,b,c,d); }
    @JSExport public static void configureGeometry(int k,String f,String s,int i,int n,int d,double a,double b,double c,double e) { TextsContinuationProbe.configureGeometry(k,f,s,i,n,d,a,b,c,e); }
    @JSExport public static void configurePointWidth(String f,String s,int p,double w) { TextsContinuationProbe.configurePointWidth(f,s,p,w); }
    @JSExport public static void configureTextFont(int k,int flags,int size,String value,String family,String name,double x,double y,double width,double height,double advance) {
        if (k < 2) { throw new IllegalArgumentException("Ordinary text metrics must come from physical fonts"); }
        TextsContinuationProbe.configureTextFont(k,flags,size,value,family,name,x,y,width,height,advance);
    }
    @JSExport public static void defaultTextFont(String name) { TextsContinuationProbe.defaultTextFont(name); }
    @JSExport public static void installTextFace(String id,Int8Array bytes) throws Exception { PortableTextGeometry.install(id,bytes.copyToJavaArray()); }
    @JSExport public static void bindTextFace(int flags,String id,String name,boolean syntheticItalic) { PortableTextGeometry.bind(flags,id,name,syntheticItalic); }
    @JSExport public static int textMeasurements() { return PortableTextGeometry.measurements(); }
    @JSExport public static JSObject beginPage(int width,int height,Int8Array pixels,Int8Array cleaned,String id,int margin,String languages,int generation) throws Exception {
        PortableTextGeometry.activate();
        return TextsContinuationProbe.beginPage(width,height,pixels,cleaned,id,margin,languages,generation);
    }
    @JSExport public static boolean receive(JSObject envelope) { return TextsContinuationProbe.receive(envelope); }
    @JSExport public static void cancel() { TextsContinuationProbe.cancel(); }
    @JSExport public static String resume() throws Exception { return TextsContinuationProbe.resume(); }
}
