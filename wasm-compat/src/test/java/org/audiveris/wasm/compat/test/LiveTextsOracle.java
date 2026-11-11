package org.audiveris.wasm.compat.test;

import com.fasterxml.jackson.databind.ObjectMapper;
import ij.process.ByteProcessor;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Map;
import org.audiveris.omr.sheet.Sheet;
import org.audiveris.omr.step.OmrStep;
import org.audiveris.omr.text.*;
import org.audiveris.omr.ui.symbol.*;
import org.audiveris.wasm.compat.platform.*;

/** Original JVM TEXTS processing with actual Tesseract.js replies, independently of native OCR parity. */
public final class LiveTextsOracle {
    public static void main(String[] args) throws Exception {
        var json = new ObjectMapper();
        Path root = Path.of(args[0]), samples = Path.of(args[1]), source = root.resolve("native"),
                output = root.resolve("live-ocr/jvm");
        Files.createDirectories(output);
        var cli = new org.audiveris.omr.CLI("Audiveris"); cli.parseParameters(new String[0]);
        var cliField = org.audiveris.omr.Main.class.getDeclaredField("cli"); cliField.setAccessible(true); cliField.set(null, cli);
        MusicFont.populateAllSymbols();
        var music = new RecordingSymbolGeometry(SymbolGeometry.provider()); SymbolGeometry.setProvider(music); music.recordPitchOffsets();
        var fonts = new RecordingTextFontGeometry(TextFontGeometry.provider()); TextFontGeometry.setProvider(fonts);
        var results = new ArrayList<Map<String, Object>>();
        var replies = json.readTree(root.resolve("live-ocr/node-results.json").toFile());
        for (var row : replies) {
            String id = row.get("id").asText();
            var metadata = json.readTree(source.resolve(id + "/metadata.json").toFile());
            int width = metadata.get("width").asInt(), height = metadata.get("height").asInt(), margin = metadata.get("margin").asInt();
            String languages = metadata.get("languages").asText();
            byte[] cleaned = Files.readAllBytes(source.resolve(id + "/cleaned.gray"));
            byte[] pixels = Files.readAllBytes(samples.resolve(id + ".binary"));
            Sheet sheet = PixelSheetProbe.create(width, height, pixels, id);
            String prefix = CueBeamsProbe.analyze(sheet);
            if (prefix.contains("\"rejected\":true")) { throw new AssertionError("Previously accepted page rejected: " + id); }
            sheet.getStub().done(OmrStep.CUE_BEAMS);
            var raw = json.treeToValue(row.get("result"), OcrExchange.Result.class);
            var exchange = new OcrExchange("live-oracle", id, 0);
            var frame = OcrImageFrame.begin(exchange, width, height, cleaned, margin, languages, OcrExchange.Layout.MULTI_BLOCK);
            if (!frame.accept(new OcrExchange.Reply(frame.request().token(), raw, null))) { throw new AssertionError("Oracle token"); }
            var lines = WorkerTextsBridge.textLines(sheet, exchange.takeReply().result());
            new DirectTextsSystems().run(sheet, new ByteProcessor(width, height, cleaned, null), lines);
            var summary = json.readTree(TextsContinuationProbe.summary(prefix, sheet));
            results.add(Map.of("id", id, "result", summary));
            Path folder = output.resolve(id); Files.createDirectories(folder);
            for (String name : new String[]{"metadata.json", "cleaned.gray"}) {
                Files.copy(source.resolve(id).resolve(name), folder.resolve(name), StandardCopyOption.REPLACE_EXISTING);
            }
            json.writeValue(folder.resolve("ocr-result.json").toFile(), raw);
            json.writeValue(folder.resolve("expected.json").toFile(), summary);
            System.out.println("Live Tesseract.js -> original JVM TEXTS: " + id + "; words " + summary.get("words"));
        }
        if (results.size() != json.readTree(source.resolve("coverage.json").toFile()).get("acceptedPages").asInt()) {
            throw new AssertionError("Live OCR page coverage differs");
        }
        json.writeValue(output.resolve("jvm-reference.json").toFile(), results);
        json.writeValue(output.resolve("font-measurements.json").toFile(), fonts.records());
        Files.writeString(output.resolve("default-font.txt"), fonts.defaultFontName());
        json.writeValue(output.resolve("symbol-geometry.json").toFile(), music.records());
        json.writeValue(output.resolve("point-widths.json").toFile(), music.pointWidths());
        for (String name : new String[]{"coverage.json", "jvm-rejections.json"}) {
            Files.copy(source.resolve(name), output.resolve(name), StandardCopyOption.REPLACE_EXISTING);
        }
    }
}
