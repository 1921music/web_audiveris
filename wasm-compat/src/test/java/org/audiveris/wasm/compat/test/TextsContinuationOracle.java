package org.audiveris.wasm.compat.test;

import java.nio.file.*;
import java.util.*;
import java.awt.image.BufferedImage;
import com.fasterxml.jackson.databind.*;
import org.audiveris.omr.sheet.*;
import org.audiveris.omr.step.*;
import org.audiveris.omr.text.*;
import org.audiveris.omr.sig.inter.*;
import org.audiveris.omr.ui.symbol.*;
import org.audiveris.wasm.compat.platform.*;
import org.bytedeco.tesseract.TessBaseAPI;
import static org.bytedeco.leptonica.global.leptonica.*;
import static org.bytedeco.tesseract.global.tesseract.*;

/** Native original cleanup/OCR/TEXTS versus explicit OCR suspension and original-system continuation. */
public final class TextsContinuationOracle {
    private static final ObjectMapper JSON = new ObjectMapper();
    public static void main(String[] args) throws Exception {
        Path samples=Path.of(args[0]),root=Path.of(args[1]),model=Path.of(args[2]);Files.createDirectories(root);
        var cli=new org.audiveris.omr.CLI("Audiveris");cli.parseParameters(new String[0]);
        var cliField=org.audiveris.omr.Main.class.getDeclaredField("cli");cliField.setAccessible(true);cliField.set(null,cli);
        MusicFont.populateAllSymbols();
        var music=new RecordingSymbolGeometry(SymbolGeometry.provider());SymbolGeometry.setProvider(music);music.recordPitchOffsets();
        var fonts=new RecordingTextFontGeometry(TextFontGeometry.provider());TextFontGeometry.setProvider(fonts);
        int limit=Integer.getInteger("audiveris.probe.textsPages",1),count=0;
        var results=new ArrayList<Map<String,Object>>();
        for(JsonNode page:JSON.readTree(samples.resolve("manifest.json").toFile()).get("pages")){
            if(limit>0&&count>=limit){break;}
            String id=page.get("id").asText();int width=page.get("width").asInt(),height=page.get("height").asInt();
            byte[] pixels=Files.readAllBytes(samples.resolve(id+".binary"));
            Sheet original=PixelSheetProbe.create(width,height,pixels.clone(),id);String prefix=CueBeamsProbe.analyze(original);
            if(prefix.contains("\"rejected\":true")){continue;}
            original.getStub().done(OmrStep.CUE_BEAMS);Path folder=root.resolve(id);Files.createDirectories(folder);
            Original step=new Original();step.prepare(original);
            byte[] cleaned=((byte[])step.buffer.getPixels()).clone();Files.write(folder.resolve("cleaned.gray"),cleaned);
            var raw=recognize(step.buffer,model,folder,step.margin,original.getStub().getOcrLanguages());
            JSON.writeValue(folder.resolve("ocr-result.json").toFile(),raw);
            OcrExchange exchange=new OcrExchange("oracle",id,0);
            OcrImageFrame frame=OcrImageFrame.begin(exchange,width,height,cleaned,step.margin,"eng",OcrExchange.Layout.MULTI_BLOCK);
            frame.accept(new OcrExchange.Reply(frame.request().token(),raw,null));
            var imported=WorkerTextsBridge.textLines(original,exchange.takeReply().result());
            JSON.writeValue(folder.resolve("original-lines.json").toFile(),lines(step.lines));
            JSON.writeValue(folder.resolve("imported-lines.json").toFile(),lines(imported));
            if(!lines(step.lines).equals(lines(imported))){throw new AssertionError("Raw OCR import differs from TesseractOrder/OcrUtil: "+id);}
            step.finish(original);String expected=summary(prefix,original);JSON.writeValue(folder.resolve("expected.json").toFile(),JSON.readTree(expected));
            int margin=step.margin;String languages=original.getStub().getOcrLanguages();
            original=null;step=null;imported=null;
            Sheet continued=PixelSheetProbe.create(width,height,pixels.clone(),id);String nextPrefix=CueBeamsProbe.analyze(continued);
            continued.getStub().done(OmrStep.CUE_BEAMS);
            var continuation=new WorkerTextsBridge(continued,new ij.process.ByteProcessor(width,height,cleaned,null),"oracle",1,margin,languages);
            if(!continuation.accept(new OcrExchange.Reply(continuation.request().token(),raw,null))){throw new AssertionError("Native continuation token");}
            Sheet previous=HeadlessSheetContext.current();HeadlessSheetContext.bind(continued);
            try{continuation.resume();}finally{HeadlessSheetContext.bind(previous);}
            String actual=summary(nextPrefix,continued);
            if(!JSON.readTree(expected).equals(JSON.readTree(actual))){Files.writeString(folder.resolve("failed-continued.json"),actual);throw new AssertionError("TEXTS continuation changed recognition: "+id);}
            JSON.writeValue(folder.resolve("metadata.json").toFile(),Map.of("id",id,"width",width,"height",height,"margin",margin,"languages",languages));
            results.add(Map.of("id",id,"result",JSON.readTree(expected)));count++;
            System.out.println("Original TEXTS and continuation match: "+id+"; OCR lines "+raw.lines().size());
        }
        if(count==0){throw new AssertionError("No accepted score page tested");}
        JSON.writeValue(root.resolve("font-measurements.json").toFile(),fonts.records());Files.writeString(root.resolve("default-font.txt"),fonts.defaultFontName());
        JSON.writeValue(root.resolve("symbol-geometry.json").toFile(),music.records());JSON.writeValue(root.resolve("point-widths.json").toFile(),music.pointWidths());
        JSON.writeValue(root.resolve("jvm-reference.json").toFile(),results);
    }
    private static OcrExchange.Result recognize(ij.process.ByteProcessor buffer,Path model,Path output,int margin,String languages) throws Exception {
        OcrExchange exchange=new OcrExchange("native", "page",0);
        var frame=OcrImageFrame.begin(exchange,buffer.getWidth(),buffer.getHeight(),(byte[])buffer.getPixels(),margin,languages,OcrExchange.Layout.MULTI_BLOCK);
        var request=frame.request();var image=new BufferedImage(request.width(),request.height(),BufferedImage.TYPE_BYTE_GRAY);
        byte[] bytes=((java.awt.image.DataBufferByte)image.getRaster().getDataBuffer()).getData();System.arraycopy(request.pixels(),0,bytes,0,bytes.length);
        Path png=output.resolve("padded.png");javax.imageio.ImageIO.write(image,"png",png.toFile());
        try(var api=new TessBaseAPI()){
            var pix=pixRead(png.toString());
            try{if(api.Init(model.toString(),languages,OEM_TESSERACT_ONLY)!=0){throw new AssertionError("Native OCR initialization");}
                api.SetImage(pix);api.SetSourceResolution(70);api.SetPageSegMode(PSM_AUTO);
                var layout=api.AnalyseLayout();if(layout!=null){layout.deallocate();}
                if(api.Recognize(null)!=0){throw new AssertionError("Native OCR recognition");}return NativeOcrBackendOracle.read(api);
            }finally{api.End();pixDestroy(pix);}
        }
    }
    private static List<Object> lines(List<TextLine> lines){
        var result=new ArrayList<Object>();
        for(TextLine line:lines){var words=new ArrayList<Object>();for(TextWord word:line.getWords()){
            var chars=new ArrayList<Object>();for(TextChar character:word.getChars()){chars.add(List.of(character.getValue(),box(character.getBounds())));}
            var baseline=word.getBaseline();var font=word.getFontInfo();
            words.add(List.of(word.getValue(),box(word.getBounds()),word.getConfidence(),List.of(baseline.getX1(),baseline.getY1(),baseline.getX2(),baseline.getY2()),
                    HeadlessTextFontGeometry.flags(font),font.pointSize,font.fontName,chars));
        }result.add(words);}return result;
    }
    private static List<Integer> box(java.awt.Rectangle box){return List.of(box.x,box.y,box.width,box.height);}
    public static String summary(String prefix,Sheet sheet) throws Exception {
        return TextsContinuationProbe.summary(prefix,sheet);
    }
    private static final class Original extends TextsStep {
        ij.process.ByteProcessor buffer;List<TextLine> lines;Context context;int margin;
        void prepare(Sheet sheet)throws Exception{
            context=super.doProlog(sheet);buffer=context.buffer;lines=context.textLines;
            var constants=OcrUtil.class.getDeclaredField("constants");constants.setAccessible(true);Object set=constants.get(null);
            var field=set.getClass().getDeclaredField("whiteMarginAdded");field.setAccessible(true);margin=((org.audiveris.omr.constant.Constant.Integer)field.get(set)).getValue();
            if(margin!=10){throw new AssertionError("Fixture expects default margin; actual "+margin);}
        }
        void finish(Sheet sheet)throws StepException{for(SystemInfo system:sheet.getSystems()){super.doSystem(system,context);}super.doEpilog(sheet,context);}
    }
}
