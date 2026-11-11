// SPDX-License-Identifier: AGPL-3.0-or-later
package org.audiveris.wasm.compat.test;

import org.audiveris.wasm.compat.platform.PortableTextGeometry;
import org.audiveris.wasm.compat.platform.WorkerMusicFontGeometry;
import org.teavm.jso.*;
import org.teavm.jso.typedarrays.Int8Array;

/** Cleanup and actual OCR continuation with physical ordinary and external music font metrics. */
public final class MusicBackedTextsProbe {
    public static void main(String[] args){ }
    @JSExport public static void configure(Int8Array bytes){CleanupTextsProbe.configure(bytes);}
    @JSExport public static void installTemplates(Int8Array bytes)throws Exception{CleanupTextsProbe.installTemplates(bytes);}
    @JSExport public static void configureSerifs(String f,int i,double a,double b,double c,double d){CleanupTextsProbe.configureSerifs(f,i,a,b,c,d);}
    @JSExport public static void configureGeometry(int k,String f,String s,int i,int n,int d,double a,double b,double c,double e){CleanupTextsProbe.configureGeometry(k,f,s,i,n,d,a,b,c,e);}
    @JSExport public static void configurePointWidth(String f,String s,int p,double w){CleanupTextsProbe.configurePointWidth(f,s,p,w);}
    @JSExport public static void configureTextFont(int k,int flags,int size,String value,String family,String name,double x,double y,double width,double height,double advance){throw new IllegalArgumentException("Font measurements must come from the configured physical backends");}
    @JSExport public static void defaultTextFont(String name){CleanupTextsProbe.defaultTextFont(name);}
    @JSExport public static void installTextFace(String id,Int8Array bytes)throws Exception{CleanupTextsProbe.installTextFace(id,bytes);}
    @JSExport public static void bindTextFace(int flags,String id,String name,boolean syntheticItalic){CleanupTextsProbe.bindTextFace(flags,id,name,syntheticItalic);}
    @JSExport public static int textMeasurements(){return CleanupTextsProbe.textMeasurements();}
    @JSExport public static void installMusicMetrics(WorkerMusicFontGeometry.Backend backend,int cacheLimit){WorkerMusicFontGeometry.install(backend,cacheLimit);}
    @JSExport public static int musicMeasurements(){return WorkerMusicFontGeometry.measurements();}
    @JSExport public static JSObject beginPage(int width,int height,Int8Array pixels,String id,int margin,String languages,int generation)throws Exception{
        PortableTextGeometry.activate();WorkerMusicFontGeometry.activate();
        return TextsContinuationProbe.beginPageWithCleanup(width,height,pixels,id,margin,languages,generation);
    }
    @JSExport public static boolean receive(JSObject envelope){return CleanupTextsProbe.receive(envelope);}
    @JSExport public static void cancel(){CleanupTextsProbe.cancel();}
    @JSExport public static String resume()throws Exception{return CleanupTextsProbe.resume();}
}
