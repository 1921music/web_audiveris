// SPDX-License-Identifier: AGPL-3.0-or-later
package org.audiveris.wasm.compat.test;

import com.fasterxml.jackson.databind.ObjectMapper;
import ij.process.ByteProcessor;
import java.nio.file.*;
import java.util.*;
import org.audiveris.omr.sheet.Sheet;
import org.audiveris.omr.sheet.rhythm.MeasuresStep;
import org.audiveris.omr.sheet.note.ChordsStep;
import org.audiveris.omr.step.OmrStep;
import org.audiveris.omr.text.*;
import org.audiveris.omr.ui.symbol.*;
import org.audiveris.wasm.compat.platform.*;

/** Independent JVM reference: original step.doit calls, with the same actual external OCR replies. */
public final class PostTextsOracle {
    public static void main(String[] args)throws Exception{
        var resources=org.audiveris.omr.WellKnowns.RES_URI;
        ((ch.qos.logback.classic.Logger)org.slf4j.LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME)).setLevel(ch.qos.logback.classic.Level.WARN);
        Path root=Path.of(args[0]),samples=Path.of(args[1]),output=Path.of(args[2]),nativeTexts=root.resolve("live-ocr/jvm");Files.createDirectories(output);
        var json=new ObjectMapper();var cli=new org.audiveris.omr.CLI("Audiveris");cli.parseParameters(new String[0]);
        var field=org.audiveris.omr.Main.class.getDeclaredField("cli");field.setAccessible(true);field.set(null,cli);
        if(org.audiveris.omr.ui.action.AdvancedTopics.processSystemsInParallel())throw new IllegalStateException("Reference requires serial system processing");
        MusicFont.populateAllSymbols();var geometry=new RecordingSymbolGeometry(SymbolGeometry.provider());SymbolGeometry.setProvider(geometry);geometry.recordPitchOffsets();
        var results=new ArrayList<Object>();
        for(var row:json.readTree(nativeTexts.resolve("jvm-reference.json").toFile())){
            String id=row.get("id").asText();var meta=json.readTree(nativeTexts.resolve(id+"/metadata.json").toFile());int w=meta.get("width").asInt(),h=meta.get("height").asInt();
            Sheet sheet=PixelSheetProbe.create(w,h,Files.readAllBytes(samples.resolve(id+".binary")),id);String prefix=CueBeamsProbe.analyze(sheet);
            if(prefix.contains("\"rejected\":true"))throw new AssertionError("Previously accepted page rejected: "+id);sheet.getStub().done(OmrStep.CUE_BEAMS);
            var raw=json.readValue(nativeTexts.resolve(id+"/ocr-result.json").toFile(),OcrExchange.Result.class);
            byte[] cleaned=Files.readAllBytes(nativeTexts.resolve(id+"/cleaned.gray"));var exchange=new OcrExchange("post-texts-oracle",id,0);
            var frame=OcrImageFrame.begin(exchange,w,h,cleaned,meta.get("margin").asInt(),meta.get("languages").asText(),OcrExchange.Layout.MULTI_BLOCK);
            if(!frame.accept(new OcrExchange.Reply(frame.request().token(),raw,null)))throw new AssertionError("Native OCR token");
            new DirectTextsSystems().run(sheet,new ByteProcessor(w,h,cleaned,null),WorkerTextsBridge.textLines(sheet,exchange.takeReply().result()));
            var texts=json.readTree(TextsContinuationProbe.summary(prefix,sheet));
            if(!texts.equals(row.get("result"))){json.writeValue(output.resolve("failed-texts.json").toFile(),Map.of("actual",texts,"expected",row.get("result")));throw new AssertionError("Native TEXTS prefix changed: "+id);}
            var phases=new ArrayList<Object>();new MeasuresStep().doit(sheet);sheet.getStub().done(OmrStep.MEASURES);
            phases.add(json.readTree(PostTextsSnapshot.summary(sheet,"MEASURES")));
            new ChordsStep().doit(sheet);sheet.getStub().done(OmrStep.CHORDS);
            phases.add(json.readTree(PostTextsSnapshot.summary(sheet,"CHORDS")));
            results.add(Map.of("id",id,"phases",phases));System.out.println("Original post-TEXTS recognition: "+id+"; stacks "+sheet.getSystems().stream().mapToInt(s->s.getStacks().size()).sum());
        }
        json.writeValue(output.resolve("jvm-reference.json").toFile(),results);
        json.writeValue(output.resolve("symbol-geometry.json").toFile(),geometry.records());json.writeValue(output.resolve("point-widths.json").toFile(),geometry.pointWidths());
    }
}
