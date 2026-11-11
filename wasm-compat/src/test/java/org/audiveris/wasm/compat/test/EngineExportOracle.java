// SPDX-License-Identifier: AGPL-3.0-or-later
package org.audiveris.wasm.compat.test;

import com.fasterxml.jackson.databind.ObjectMapper;
import ij.process.ByteProcessor;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import org.audiveris.omr.sheet.*;
import org.audiveris.omr.sheet.rhythm.*;
import org.audiveris.omr.sheet.note.ChordsStep;
import org.audiveris.omr.sheet.curve.CurvesStep;
import org.audiveris.omr.sheet.symbol.*;
import org.audiveris.omr.step.*;
import org.audiveris.omr.text.*;
import org.audiveris.omr.score.PartwiseBuilder;
import org.audiveris.wasm.compat.platform.*;
import org.audiveris.proxymusic.PortableMusicXmlWriter;

/** Original native steps and JAXB serialization: independent reference for standalone export. */
public final class EngineExportOracle {
    public static void main(String[] args)throws Exception{
        var resources=org.audiveris.omr.WellKnowns.RES_URI;
        var logger=(ch.qos.logback.classic.Logger)org.slf4j.LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
        logger.setLevel(ch.qos.logback.classic.Level.WARN);
        var cli=new org.audiveris.omr.CLI("Audiveris");cli.parseParameters(new String[0]);
        var field=org.audiveris.omr.Main.class.getDeclaredField("cli");field.setAccessible(true);field.set(null,cli);
        Path root=Path.of(args[0]),output=root.resolve("build/wasm-engine/native-export"),
            texts=root.resolve("build/wasm-compat/texts-continuation-probe/live-ocr/jvm"),
            samples=root.resolve("build/wasm-compat/pixel-probe/samples");
        Files.createDirectories(output);var json=new ObjectMapper();
        org.audiveris.omr.ui.symbol.MusicFont.populateAllSymbols();
        var report=new ArrayList<Object>();
        for(var row:json.readTree(texts.resolve("jvm-reference.json").toFile())){
            String id=row.get("id").asText();var meta=json.readTree(texts.resolve(id+"/metadata.json").toFile());
            int width=meta.get("width").asInt(),height=meta.get("height").asInt();
            Sheet sheet=PixelSheetProbe.create(width,height,Files.readAllBytes(samples.resolve(id+".binary")),id);
            String prefix=CueBeamsProbe.analyze(sheet);if(prefix.contains("\"rejected\":true"))throw new AssertionError("Rejected "+id);
            sheet.getStub().done(OmrStep.CUE_BEAMS);
            var raw=json.readValue(texts.resolve(id+"/ocr-result.json").toFile(),OcrExchange.Result.class);
            var exchange=new OcrExchange("native-export",id,0);
            var frame=OcrImageFrame.begin(exchange,width,height,Files.readAllBytes(texts.resolve(id+"/cleaned.gray")),meta.get("margin").asInt(),meta.get("languages").asText(),OcrExchange.Layout.MULTI_BLOCK);
            if(!frame.accept(new OcrExchange.Reply(frame.request().token(),raw,null)))throw new AssertionError("OCR token");
            new DirectTextsSystems().run(sheet,new ByteProcessor(width,height,Files.readAllBytes(texts.resolve(id+"/cleaned.gray")),null),WorkerTextsBridge.textLines(sheet,exchange.takeReply().result()));
            AbstractStep[] steps={new MeasuresStep(),new ChordsStep(),new CurvesStep(),new SymbolsStep(),new LinksStep(),new RhythmsStep(),new PageStep()};
            OmrStep[] names={OmrStep.MEASURES,OmrStep.CHORDS,OmrStep.CURVES,OmrStep.SYMBOLS,OmrStep.LINKS,OmrStep.RHYTHMS,OmrStep.PAGE};
            for(int i=0;i<steps.length;i++){steps[i].doit(sheet);sheet.getStub().done(names[i]);}
            int index=0;
            for(var score:sheet.getStub().getBook().getScores()){
                var model=PartwiseBuilder.build(score);model.setVersion("4.0");
                if(model.getPart().isEmpty())throw new AssertionError("Empty native score");
                // Direct JAXB marshalling excludes the utility's timestamp/signature mutation.
                var context=org.audiveris.proxymusic.util.Marshalling.getContext(model.getClass());
                var stream=new ByteArrayOutputStream();context.createMarshaller().marshal(model,stream);
                String original=stream.toString(java.nio.charset.StandardCharsets.UTF_8),portable=PortableMusicXmlWriter.write(model);
                String file=id+"-"+(index++);
                Files.writeString(output.resolve(file+".jaxb.musicxml"),original);
                Files.writeString(output.resolve(file+".portable.musicxml"),portable);
                Files.writeString(output.resolve(id+".page.json"),PageRecognitionSnapshot.summary(sheet,"PAGE"));
                report.add(Map.of("id",id,"file",file,"parts",model.getPart().size()));
                System.out.println("Native MusicXML exported "+file);
            }
        }
        json.writeValue(output.resolve("reference.json").toFile(),report);
        org.audiveris.omr.util.OmrExecutors.shutdown();
    }
}
