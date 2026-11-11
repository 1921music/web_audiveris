// SPDX-License-Identifier: AGPL-3.0-or-later
package org.audiveris.wasm.compat.test;

import com.fasterxml.jackson.databind.ObjectMapper;
import ij.process.ByteProcessor;
import java.nio.file.*;
import java.util.*;
import org.audiveris.omr.sheet.Sheet;
import org.audiveris.omr.sheet.rhythm.MeasuresStep;
import org.audiveris.omr.sheet.note.ChordsStep;
import org.audiveris.omr.sheet.curve.CurvesStep;
import org.audiveris.omr.sheet.symbol.SymbolsStep;
import org.audiveris.omr.sheet.symbol.LinksStep;
import org.audiveris.omr.sheet.rhythm.RhythmsStep;
import org.audiveris.omr.step.*;
import org.audiveris.omr.text.*;
import org.audiveris.omr.ui.symbol.*;
import org.audiveris.wasm.compat.platform.*;

/** Original JVM step.doit calls through PAGE, independent of the Worker continuation. */
public final class PageRecognitionOracle {
    public static void main(String[] args)throws Exception{
        var resources=org.audiveris.omr.WellKnowns.RES_URI;
        var logger=(ch.qos.logback.classic.Logger)org.slf4j.LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
        logger.setLevel(ch.qos.logback.classic.Level.WARN);
        var errors=new ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent>();
        errors.start();logger.addAppender(errors);
        Path root=Path.of(args[0]),samples=Path.of(args[1]),output=Path.of(args[2]),nativeTexts=root.resolve("live-ocr/jvm");Files.createDirectories(output);
        var json=new ObjectMapper();var cli=new org.audiveris.omr.CLI("Audiveris");cli.parseParameters(new String[0]);
        var field=org.audiveris.omr.Main.class.getDeclaredField("cli");field.setAccessible(true);field.set(null,cli);
        if(org.audiveris.omr.ui.action.AdvancedTopics.processSystemsInParallel())throw new IllegalStateException("Reference requires serial system processing");
        MusicFont.populateAllSymbols();
        int headDimensionCases=0;
        for(var family:MusicFamily.values())for(int interline=1;interline<=128;interline++)for(var shape:new org.audiveris.omr.glyph.Shape[]{org.audiveris.omr.glyph.Shape.NOTEHEAD_BLACK_SMALL,org.audiveris.omr.glyph.Shape.TUPLET_THREE,org.audiveris.omr.glyph.Shape.TUPLET_SIX}){
            var original=shape.getFontSymbolByInterline(family,interline).getDimension();
            var candidate=SymbolGeometry.roundedLayoutDimension(family,shape,interline);
            if(!candidate.equals(original))throw new AssertionError("Grace head dimension changed: "+family+"/"+interline);
            headDimensionCases++;
        }
        json.writeValue(output.resolve("symbol-layout-dimensions.json").toFile(),Map.of("cases",headDimensionCases,"exact",true));
        var geometry=new RecordingSymbolGeometry(SymbolGeometry.provider());SymbolGeometry.setProvider(geometry);geometry.recordPitchOffsets();
        HypotOracle.write(output.resolve("hypot-reference.json"));var results=new ArrayList<Object>();
        for(var row:json.readTree(nativeTexts.resolve("jvm-reference.json").toFile())){
            String id=row.get("id").asText();var meta=json.readTree(nativeTexts.resolve(id+"/metadata.json").toFile());int w=meta.get("width").asInt(),h=meta.get("height").asInt();
            Sheet sheet=PixelSheetProbe.create(w,h,Files.readAllBytes(samples.resolve(id+".binary")),id);String prefix=CueBeamsProbe.analyze(sheet);
            if(prefix.contains("\"rejected\":true"))throw new AssertionError("Previously accepted page rejected: "+id);sheet.getStub().done(OmrStep.CUE_BEAMS);
            var raw=json.readValue(nativeTexts.resolve(id+"/ocr-result.json").toFile(),OcrExchange.Result.class);
            byte[] cleaned=Files.readAllBytes(nativeTexts.resolve(id+"/cleaned.gray"));var exchange=new OcrExchange("page-oracle",id,0);
            var frame=OcrImageFrame.begin(exchange,w,h,cleaned,meta.get("margin").asInt(),meta.get("languages").asText(),OcrExchange.Layout.MULTI_BLOCK);
            if(!frame.accept(new OcrExchange.Reply(frame.request().token(),raw,null)))throw new AssertionError("Native OCR token");
            new DirectTextsSystems().run(sheet,new ByteProcessor(w,h,cleaned,null),WorkerTextsBridge.textLines(sheet,exchange.takeReply().result()));
            var texts=json.readTree(TextsContinuationProbe.summary(prefix,sheet));
            if(!texts.equals(row.get("result"))){json.writeValue(output.resolve("failed-texts.json").toFile(),Map.of("actual",texts,"expected",row.get("result")));throw new AssertionError("Native TEXTS prefix changed: "+id);}
            var phases=new ArrayList<Object>();
            AbstractStep[] steps={new MeasuresStep(),new ChordsStep(),new CurvesStep(),new SymbolsStep(),new LinksStep(),new RhythmsStep(),new PageStep()};
            OmrStep[] names={OmrStep.MEASURES,OmrStep.CHORDS,OmrStep.CURVES,OmrStep.SYMBOLS,OmrStep.LINKS,OmrStep.RHYTHMS,OmrStep.PAGE};
            for(int i=0;i<steps.length;i++){
                if(names[i]==OmrStep.CURVES)CurveSkeletonOracle.compare(sheet,output.resolve(id));
                if(names[i]==OmrStep.SYMBOLS)SymbolsRasterOracle.compare(sheet,output.resolve(id));
                errors.list.clear();steps[i].doit(sheet);
                for(var event:errors.list)if(event.getLevel().isGreaterOrEqual(ch.qos.logback.classic.Level.ERROR))throw new AssertionError("Native recognition logged error at "+id+" "+names[i]+": "+event.getFormattedMessage());
                sheet.getStub().done(names[i]);phases.add(json.readTree(PageRecognitionSnapshot.summary(sheet,names[i].name())));
                System.out.println("Original page recognition: "+id+" "+names[i]);
            }
            results.add(Map.of("id",id,"phases",phases));
        }
        json.writeValue(output.resolve("jvm-reference.json").toFile(),results);
        json.writeValue(output.resolve("symbol-geometry.json").toFile(),geometry.records());json.writeValue(output.resolve("point-widths.json").toFile(),geometry.pointWidths());
    }
}
