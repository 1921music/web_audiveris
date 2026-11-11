// SPDX-License-Identifier: AGPL-3.0-or-later
package org.audiveris.wasm.compat.test;

import org.audiveris.wasm.compat.platform.PortableTextGeometry;
import org.teavm.jso.JSExport;
import org.teavm.jso.JSObject;
import org.teavm.jso.typedarrays.Int8Array;

/** TEXTS cleanup, external OCR suspension and recognition, with physical text fonts. */
public final class CleanupTextsProbe {
    public static void main(String[] args) { }
    @JSExport public static void configure(Int8Array bytes) { FontBackedTextsProbe.configure(bytes); }
    @JSExport public static void installTemplates(Int8Array bytes) throws Exception { FontBackedTextsProbe.installTemplates(bytes); }
    @JSExport public static void configureSerifs(String f,int i,double a,double b,double c,double d) { FontBackedTextsProbe.configureSerifs(f,i,a,b,c,d); }
    @JSExport public static void configureGeometry(int k,String f,String s,int i,int n,int d,double a,double b,double c,double e) { FontBackedTextsProbe.configureGeometry(k,f,s,i,n,d,a,b,c,e); }
    @JSExport public static void configurePointWidth(String f,String s,int p,double w) { FontBackedTextsProbe.configurePointWidth(f,s,p,w); }
    @JSExport public static void configureTextFont(int k,int flags,int size,String value,String family,String name,double x,double y,double width,double height,double advance) {
        FontBackedTextsProbe.configureTextFont(k,flags,size,value,family,name,x,y,width,height,advance);
    }
    @JSExport public static void defaultTextFont(String name) { FontBackedTextsProbe.defaultTextFont(name); }
    @JSExport public static void installTextFace(String id,Int8Array bytes) throws Exception { FontBackedTextsProbe.installTextFace(id,bytes); }
    @JSExport public static void bindTextFace(int flags,String id,String name,boolean syntheticItalic) { FontBackedTextsProbe.bindTextFace(flags,id,name,syntheticItalic); }
    @JSExport public static int textMeasurements() { return FontBackedTextsProbe.textMeasurements(); }
    @JSExport public static JSObject beginPage(int width,int height,Int8Array pixels,String id,int margin,String languages,int generation) throws Exception {
        PortableTextGeometry.activate();
        return TextsContinuationProbe.beginPageWithCleanup(width,height,pixels,id,margin,languages,generation);
    }
    @JSExport public static boolean receive(JSObject envelope) { return FontBackedTextsProbe.receive(envelope); }
    @JSExport public static void cancel() { FontBackedTextsProbe.cancel(); }
    @JSExport public static String resume() throws Exception { return FontBackedTextsProbe.resume(); }
}
