// SPDX-License-Identifier: AGPL-3.0-or-later
package org.audiveris.wasm.compat.platform;

import org.teavm.jso.*;
import org.teavm.jso.typedarrays.Int8Array;

/** Production entry point. One Wasm instance owns one active page and its resources. */
public final class WorkerEngine {
    private static RecognitionSession session;
    private static boolean configured;
    private static int lastGeneration=-1;
    private static final int MAX_PIXELS=32*1024*1024;
    private WorkerEngine(){ }
    public static void main(String[] args){ }
    @JSExport public static void configure(Int8Array model,String defaultFont,
            HeadlessSymbolGeometry.Backend geometry,WorkerTemplateRaster.Backend raster,
            WorkerMusicFontGeometry.Backend metrics,WorkerMusicSymbols.Backend symbols){
        if(configured)throw new IllegalStateException("Engine already configured");
        HeadlessConfiguration.configure(java.util.Map.of(),java.util.Map.of());
        HeadlessClassifierModels.install(model.copyToJavaArray());
        HeadlessTextFontGeometry.defaultName(defaultFont);
        HeadlessSymbolGeometry.install(geometry,4096);
        WorkerTemplateRaster.install(raster);
        WorkerMusicFontGeometry.install(metrics,4096);
        WorkerMusicSymbols.install(symbols);
        configured=true;
    }
    @JSExport public static void installTextFace(String id,Int8Array bytes)throws Exception{
        PortableTextGeometry.install(id,bytes.copyToJavaArray());
    }
    @JSExport public static void bindTextFace(int flags,String id,String name,boolean italic){
        PortableTextGeometry.bind(flags,id,name,italic);
    }
    @JSExport public static void beginGray(int width,int height,Int8Array pixels,String job,int generation,
            String family,boolean smallHeads,int margin,String languages,double mean,double deviation){
        if(!configured)throw new IllegalStateException("Configure engine resources first");
        if(session!=null&&!session.terminal())throw new IllegalStateException("Finish or cancel the active page first");
        if(generation<=lastGeneration)throw new IllegalArgumentException("Generation must increase for each page");
        if(width<=0||height<=0||width>32768||height>32768||(long)width*height>MAX_PIXELS
                ||pixels.getLength()!=(long)width*height)throw new IllegalArgumentException("Invalid or oversized page");
        PortableTextGeometry.activate();WorkerMusicFontGeometry.activate();
        lastGeneration=generation;session=null;
        org.audiveris.omr.image.TemplateFactory.getInstance().clearCache();
        session=new RecognitionSession(GrayInput.takeOwnership(width,height,pixels.copyToJavaArray()),
                job,generation,family,smallHeads,margin,languages,mean,deviation);
    }
    private static RecognitionSession current(){
        if(session==null)throw new IllegalStateException("No page started");return session;
    }
    @JSExport public static String stage(){return current().stage();}
    @JSExport public static boolean complete(){return session!=null&&session.complete();}
    @JSExport public static void advance()throws Exception{current().advance();}
    @JSExport public static JSObject ocrRequest(){
        var request=current().request();return request==null?null:OcrTransport.request(request);
    }
    @JSExport public static boolean receive(JSObject envelope){
        if(session==null)return false;
        var request=session.request();
        if(request==null||!request.token().equals(OcrTransport.token(envelope)))return false;
        return session.accept(OcrTransport.reply(envelope));
    }
    @JSExport public static void resume()throws Exception{current().resume();}
    @JSExport public static void cancel(){if(session!=null)session.cancel();}
    @JSExport public static int scoreCount(){
        if(!current().complete())throw new IllegalStateException("Complete recognition before exporting");
        return current().sheet().getStub().getBook().getScores().size();
    }
    @JSExport public static String musicXml(int index)throws Exception{
        int count=scoreCount();
        if(index<0||index>=count)throw new IllegalArgumentException("Score index out of range");
        var sheet=current().sheet();var previous=HeadlessSheetContext.current();HeadlessSheetContext.bind(sheet);
        long errors=HeadlessLogging.errorCount();
        try{
            var model=org.audiveris.omr.score.PartwiseBuilder.buildModel(sheet.getStub().getBook().getScores().get(index));
            if(model.getPartList()==null||model.getPart().isEmpty()||HeadlessLogging.errorCount()!=errors)
                throw new IllegalStateException("MusicXML model construction failed");
            model.setVersion("4.0");
            return org.audiveris.proxymusic.PortableMusicXmlWriter.write(model);
        }finally{HeadlessSheetContext.bind(previous);}
    }
    /** Java-side inspection for reference verification; no host object handle is exposed. */
    public static RecognitionSession session(){return current();}
}
