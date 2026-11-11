package org.audiveris.wasm.compat.test;

import org.audiveris.omr.sheet.*;
import org.audiveris.omr.step.OmrStep;
import org.audiveris.omr.text.WorkerTextsBridge;
import org.audiveris.wasm.compat.platform.*;
import org.teavm.jso.JSExport;
import org.teavm.jso.JSObject;
import org.teavm.jso.typedarrays.Int8Array;

/** Real TextsStep system processing, after an explicitly supplied original-cleanup raster. */
public final class TextsContinuationProbe {
    private static WorkerTextsBridge pending;
    private static Sheet sheet;
    private static String prefix;
    public static void main(String[] args) { }
    @JSExport public static void configure(Int8Array bytes) { CueBeamsProbe.configure(bytes); }
    @JSExport public static void installTemplates(Int8Array bytes) throws Exception { CueBeamsProbe.installTemplates(bytes); }
    @JSExport public static void configureSerifs(String f,int i,double a,double b,double c,double d) { CueBeamsProbe.configureSerifs(f,i,a,b,c,d); }
    @JSExport public static void configureGeometry(int k,String f,String s,int i,int n,int d,double a,double b,double c,double e) { CueBeamsProbe.configureGeometry(k,f,s,i,n,d,a,b,c,e); }
    @JSExport public static void configurePointWidth(String f,String s,int p,double w) { CueBeamsProbe.configurePointWidth(f,s,p,w); }
    @JSExport public static void configureTextFont(int k,int flags,int size,String value,String family,String name,double x,double y,double width,double height,double advance) {
        HeadlessTextFontGeometry.register(k,flags,size,value,family,name,x,y,width,height,advance);
    }
    @JSExport public static void defaultTextFont(String name) { HeadlessTextFontGeometry.defaultName(name); }
    @JSExport public static JSObject beginPage(int width,int height,Int8Array pixels,Int8Array cleaned,String id,int margin,String languages,int generation) throws Exception {
        return begin(width,height,pixels,cleaned,id,margin,languages,generation);
    }
    public static JSObject beginPageWithCleanup(int width,int height,Int8Array pixels,String id,int margin,String languages,int generation) throws Exception {
        return begin(width,height,pixels,null,id,margin,languages,generation);
    }
    private static JSObject begin(int width,int height,Int8Array pixels,Int8Array cleaned,String id,int margin,String languages,int generation) throws Exception {
        if(pending!=null){
            if(pending.state()==OcrExchange.State.WAITING||pending.state()==OcrExchange.State.READY){throw new IllegalStateException("Finish or cancel the pending TEXTS operation");}
            if(generation<=pending.request().token().generation()){throw new IllegalArgumentException("Use a new TEXTS generation");}
        }
        sheet = PixelSheetProbe.create(width,height,pixels.copyToJavaArray(),id);
        prefix = CueBeamsProbe.analyze(sheet);
        if (prefix.contains("\"rejected\":true")) { throw new IllegalArgumentException("Page rejected before TEXTS"); }
        sheet.getStub().done(OmrStep.CUE_BEAMS);
        var buffer = cleaned == null ? PortableTextsCleanup.clean(sheet) : new ij.process.ByteProcessor(width,height,cleaned.copyToJavaArray(),null);
        pending = new WorkerTextsBridge(sheet, buffer,
                "texts-job",generation,margin,languages);
        return OcrTransport.request(pending.request());
    }
    @JSExport public static boolean receive(JSObject envelope) {
        if (pending.state() != OcrExchange.State.WAITING || !pending.request().token().equals(OcrTransport.token(envelope))) { return false; }
        return pending.accept(OcrTransport.reply(envelope));
    }
    @JSExport public static void cancel() { pending.cancel(); }
    @JSExport public static String resume() throws Exception {
        Sheet previous = HeadlessSheetContext.current(); HeadlessSheetContext.bind(sheet);
        try { pending.resume(); BeamProbe.checkErrors(); return summary(prefix,sheet); }
        finally { HeadlessSheetContext.bind(previous); }
    }
    public static String summary(String prefix, Sheet sheet) {
        int vertices=0,relations=0,sentences=0,words=0;
        for (SystemInfo system : sheet.getSystems()) {
            vertices+=system.getSig().vertexSet().size(); relations+=system.getSig().edgeSet().size();
            sentences+=system.getSig().inters(org.audiveris.omr.sig.inter.SentenceInter.class).size();
            words+=system.getSig().inters(org.audiveris.omr.sig.inter.WordInter.class).size();
        }
        var out=new StringBuilder(prefix.substring(0,prefix.length()-1)).append(",\"textVertices\":").append(vertices)
                .append(",\"textRelations\":").append(relations).append(",\"sentences\":").append(sentences).append(",\"words\":").append(words).append(",\"textWords\":[");
        boolean first=true;
        for(SystemInfo system:sheet.getSystems()){
            var items=new java.util.ArrayList<>(system.getSig().inters(org.audiveris.omr.sig.inter.WordInter.class));
            items.sort(java.util.Comparator.comparingInt((org.audiveris.omr.sig.inter.Inter inter)->inter.getBounds().x)
                    .thenComparingInt(inter->inter.getBounds().y).thenComparing(inter->((org.audiveris.omr.sig.inter.WordInter)inter).getValue()));
            for(var inter:items){var word=(org.audiveris.omr.sig.inter.WordInter)inter;var font=word.getFontInfo();var box=word.getBounds();
                if(!first){out.append(',');}first=false;
                out.append("{\"system\":").append(system.getId()).append(",\"value\":");quote(out,word.getValue());
                out.append(",\"bounds\":[").append(box.x).append(',').append(box.y).append(',').append(box.width).append(',').append(box.height)
                        .append("],\"grade\":").append(word.getGrade()).append(",\"flags\":").append(HeadlessTextFontGeometry.flags(font))
                        .append(",\"size\":").append(font.pointSize).append(",\"font\":");quote(out,font.fontName);out.append('}');
            }
        }
        out.append("],\"textSentences\":[");first=true;
        for(SystemInfo system:sheet.getSystems()){
            var items=new java.util.ArrayList<>(system.getSig().inters(org.audiveris.omr.sig.inter.SentenceInter.class));
            items.sort(java.util.Comparator.comparingInt((org.audiveris.omr.sig.inter.Inter inter)->inter.getBounds().x)
                    .thenComparingInt(inter->inter.getBounds().y).thenComparing(inter->((org.audiveris.omr.sig.inter.SentenceInter)inter).getValue()));
            for(var inter:items){var sentence=(org.audiveris.omr.sig.inter.SentenceInter)inter;var box=sentence.getBounds();
                if(!first){out.append(',');}first=false;out.append("{\"system\":").append(system.getId()).append(",\"role\":");
                quote(out,sentence.getRole()==null?null:sentence.getRole().name());out.append(",\"value\":");quote(out,sentence.getValue());
                out.append(",\"bounds\":[").append(box.x).append(',').append(box.y).append(',').append(box.width).append(',').append(box.height)
                        .append("],\"grade\":").append(sentence.getGrade()).append('}');
            }
        }
        out.append("],\"textsDone\":").append(sheet.getStub().isDone(OmrStep.TEXTS));
        out.append(",\"textGraphEdges\":[");BeamProbe.appendRelations(out,sheet);return out.append("]}").toString();
    }
    private static void quote(StringBuilder out,String value){
        if(value==null){out.append("null");return;}out.append('"');
        for(int i=0;i<value.length();i++){char c=value.charAt(i);if(c=='"'||c=='\\'){out.append('\\').append(c);}
            else if(c<32){String hex=Integer.toHexString(c);out.append("\\u").append("0000",0,4-hex.length()).append(hex);}else{out.append(c);}}
        out.append('"');
    }
}
