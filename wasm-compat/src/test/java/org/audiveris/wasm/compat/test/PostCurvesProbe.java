// SPDX-License-Identifier: AGPL-3.0-or-later
package org.audiveris.wasm.compat.test;

import org.audiveris.wasm.compat.platform.*;
import org.teavm.jso.*;
import org.teavm.jso.typedarrays.Int8Array;

/** Full physical-font TEXTS entry plus guarded original post-TEXTS recognition. */
public final class PostCurvesProbe {
    private static PostCurvesRecognition continuation;
    private static byte[] skeleton;
    private static int generation,margin;
    private static String languages;
    public static void main(String[] args){ }
    @JSExport public static double hypot(double x,double y){return PortableHypot.hypot(x,y);}
    @JSExport public static double sin(double x){return PortableTrig.sin(x);}
    @JSExport public static double cos(double x){return PortableTrig.cos(x);}
    @JSExport public static double atan(double x){return PortableTrig.atan(x);}
    @JSExport public static double atan2(double y,double x){return PortableTrig.atan2(y,x);}
    @JSExport public static void configure(Int8Array b){MusicBackedTextsProbe.configure(b);org.audiveris.omr.sheet.curve.WorkerSkeletonBridge.setObserver(pixels->skeleton=pixels);}
    @JSExport public static Int8Array skeletonPixels(){if(skeleton==null)throw new IllegalStateException("No new skeleton snapshot");byte[] value=skeleton;skeleton=null;return Int8Array.copyFromJavaArray(value);}
    @JSExport public static void installTemplates(Int8Array b)throws Exception{MusicBackedTextsProbe.installTemplates(b);}
    @JSExport public static void configureSerifs(String f,int i,double a,double b,double c,double d){MusicBackedTextsProbe.configureSerifs(f,i,a,b,c,d);}
    @JSExport public static void configureGeometry(int k,String f,String s,int i,int n,int d,double a,double b,double c,double e){MusicBackedTextsProbe.configureGeometry(k,f,s,i,n,d,a,b,c,e);}
    @JSExport public static void configurePointWidth(String f,String s,int p,double w){MusicBackedTextsProbe.configurePointWidth(f,s,p,w);}
    @JSExport public static void configureTextFont(int k,int flags,int size,String v,String f,String n,double x,double y,double w,double h,double a){MusicBackedTextsProbe.configureTextFont(k,flags,size,v,f,n,x,y,w,h,a);}
    @JSExport public static void defaultTextFont(String n){MusicBackedTextsProbe.defaultTextFont(n);}
    @JSExport public static void installTextFace(String id,Int8Array b)throws Exception{MusicBackedTextsProbe.installTextFace(id,b);}
    @JSExport public static void bindTextFace(int flags,String id,String n,boolean italic){MusicBackedTextsProbe.bindTextFace(flags,id,n,italic);}
    @JSExport public static int textMeasurements(){return MusicBackedTextsProbe.textMeasurements();}
    @JSExport public static void installMusicMetrics(WorkerMusicFontGeometry.Backend b,int limit){MusicBackedTextsProbe.installMusicMetrics(b,limit);}
    @JSExport public static int musicMeasurements(){return MusicBackedTextsProbe.musicMeasurements();}
    @JSExport public static void installMusicSymbols(WorkerMusicSymbols.Backend b){MusicBackedTextsProbe.installMusicSymbols(b);}
    @JSExport public static int musicSymbolPaints(){return MusicBackedTextsProbe.musicSymbolPaints();}
    @JSExport public static Int8Array verifyMusicSymbol(String f,String s,int p,double x,double y,int b){return MusicBackedTextsProbe.verifyMusicSymbol(f,s,p,x,y,b);}
    @JSExport public static JSObject beginPage(int w,int h,Int8Array p,String id,int m,String l,int g)throws Exception{if(continuation!=null&&continuation.hasPending())throw new IllegalStateException("Finish or cancel CURVES first");JSObject request=MusicBackedTextsProbe.beginPage(w,h,p,id,m,l,g);continuation=null;skeleton=null;generation=g;margin=m;languages=l;return request;}
    @JSExport public static boolean receive(JSObject e){return MusicBackedTextsProbe.receive(e);}
    @JSExport public static void cancel(){if(continuation!=null){continuation.cancel();continuation=null;}skeleton=null;MusicBackedTextsProbe.cancel();}
    @JSExport public static String resume()throws Exception{return MusicBackedTextsProbe.resume();}
    @JSExport public static String advance(String stage,JSObject envelope)throws Exception{
        var sheet=TextsContinuationProbe.completedSheet(envelope);if(continuation==null)continuation=new PostCurvesRecognition(sheet,"texts-job",generation,margin,languages);
        if(continuation.sheet()!=sheet)throw new IllegalStateException("Continuation belongs to a different page");
        continuation.advance(stage);BeamProbe.checkErrors();return PostCurvesSnapshot.summary(sheet,stage);
    }
    @JSExport public static JSObject curveRequest(){if(continuation==null)return null;var request=continuation.request();return request==null?null:OcrTransport.request(request);}
    @JSExport public static boolean receiveCurves(JSObject envelope){if(continuation==null)return false;var request=continuation.request();if(request==null||!request.token().equals(OcrTransport.token(envelope)))return false;return continuation.accept(OcrTransport.reply(envelope));}
    @JSExport public static String resumeCurves()throws Exception{if(continuation==null)throw new IllegalStateException("No CURVES continuation");continuation.resume();BeamProbe.checkErrors();return PostCurvesSnapshot.summary(continuation.sheet(),"CURVES");}
}
