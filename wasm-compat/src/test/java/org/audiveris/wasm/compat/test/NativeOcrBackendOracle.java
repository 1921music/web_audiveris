package org.audiveris.wasm.compat.test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.GZIPInputStream;
import org.audiveris.wasm.compat.platform.OcrExchange;
import org.bytedeco.javacpp.BoolPointer;
import org.bytedeco.javacpp.BytePointer;
import org.bytedeco.javacpp.IntPointer;
import org.bytedeco.tesseract.ResultIterator;
import org.bytedeco.tesseract.TessBaseAPI;
import static org.bytedeco.leptonica.global.leptonica.*;
import static org.bytedeco.tesseract.global.tesseract.*;

/** JVM-only raw OCR differential, before TextWord's font fitting or TEXTS page cleanup. */
public final class NativeOcrBackendOracle {
    public record Run(String engineVersion, int oem, int psm, int sourceDpi, OcrExchange.Result result) { }
    private static final class Word {
        String text; OcrExchange.Box bounds; double confidence;
        OcrExchange.Baseline baseline; OcrExchange.Font font;
        final List<OcrExchange.Character> characters = new ArrayList<>();
        OcrExchange.Word finish() { return new OcrExchange.Word(text, bounds, confidence, baseline, font, characters); }
    }
    public static void main(String[] args) throws Exception {
        Path fixture = Path.of(args[0]), model = Path.of(args[1]), output = Path.of(args[2]);
        Files.createDirectories(output);
        Path data = output.resolve("tessdata"); Files.createDirectories(data);
        try (var input = new GZIPInputStream(Files.newInputStream(model))) {
            Files.copy(input, data.resolve("eng.traineddata"), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        }
        for (int psm : new int[]{PSM_SINGLE_BLOCK, PSM_AUTO}) {
            try (var api = new TessBaseAPI()) {
                var image = pixRead(fixture.resolve("native.png").toString());
                if (image == null || image.isNull()) { throw new AssertionError("Cannot decode native OCR fixture"); }
                try {
                    if (api.Init(data.toString(), "eng", OEM_TESSERACT_ONLY) != 0) { throw new AssertionError("Native OCR initialization"); }
                    api.SetImage(image); api.SetSourceResolution(70); api.SetPageSegMode(psm);
                    var layout = api.AnalyseLayout(); if (layout != null) { layout.deallocate(); }
                    if (api.Recognize(null) != 0) { throw new AssertionError("Native OCR recognition"); }
                    var result = read(api);
                    String words = result.lines().stream().flatMap(line -> line.words().stream()).map(OcrExchange.Word::text).collect(java.util.stream.Collectors.joining(" "));
                    if (!words.equals("Allegro moderato Music 123")) { throw new AssertionError(words); }
                    Files.writeString(output.resolve("psm-" + psm + ".json"), OcrTransportOracle.json(new Run(
                            TessBaseAPI.Version().getString(), OEM_TESSERACT_ONLY, psm, 70, result)) + "\n");
                    System.out.println("Native OCR " + TessBaseAPI.Version().getString() + " PSM " + psm + ": " + words);
                } finally { api.End(); pixDestroy(image); }
            }
        }
    }
    static OcrExchange.Result read(TessBaseAPI api) {
        var lines = new ArrayList<OcrExchange.Line>();
        var words = new ArrayList<OcrExchange.Word>(); Word word = null;
        OcrExchange.Baseline lineBaseline = null; boolean fonts = false;
        try (ResultIterator iterator = api.GetIterator()) {
            iterator.Begin();
            do {
                if (iterator.Empty(RIL_SYMBOL)) { continue; }
                if (iterator.IsAtBeginningOf(RIL_WORD)) {
                    if (word != null) { words.add(word.finish()); }
                    if (iterator.IsAtBeginningOf(RIL_TEXTLINE)) {
                        if (!words.isEmpty()) { lines.add(new OcrExchange.Line(words, lineBaseline)); words = new ArrayList<>(); }
                        lineBaseline = baseline(iterator, RIL_TEXTLINE);
                    }
                    word = new Word(); word.text = text(iterator, RIL_WORD); word.bounds = box(iterator, RIL_WORD);
                    word.confidence = iterator.Confidence(RIL_WORD) / 100.0; word.baseline = baseline(iterator, RIL_WORD);
                    word.font = font(iterator); fonts |= word.font != null;
                }
                word.characters.add(new OcrExchange.Character(text(iterator, RIL_SYMBOL), box(iterator, RIL_SYMBOL)));
            } while (iterator.Next(RIL_SYMBOL));
        }
        if (word != null) { words.add(word.finish()); }
        if (!words.isEmpty()) { lines.add(new OcrExchange.Line(words, lineBaseline)); }
        return new OcrExchange.Result(new OcrExchange.Capabilities(true, true, fonts), lines);
    }
    private static String text(ResultIterator iterator, int level) {
        try (BytePointer text = iterator.GetUTF8Text(level)) { return text.getString(java.nio.charset.StandardCharsets.UTF_8); }
    }
    private static OcrExchange.Box box(ResultIterator iterator, int level) {
        try (var x = new IntPointer(1L); var y = new IntPointer(1L); var right = new IntPointer(1L); var bottom = new IntPointer(1L)) {
            if (!iterator.BoundingBox(level, x, y, right, bottom)) { throw new AssertionError("Missing native OCR bounds"); }
            return new OcrExchange.Box(x.get(), y.get(), right.get() - x.get(), bottom.get() - y.get());
        }
    }
    private static OcrExchange.Baseline baseline(ResultIterator iterator, int level) {
        try (var x = new IntPointer(1L); var y = new IntPointer(1L); var right = new IntPointer(1L); var bottom = new IntPointer(1L)) {
            return iterator.Baseline(level, x, y, right, bottom) ? new OcrExchange.Baseline(x.get(), y.get(), right.get(), bottom.get()) : null;
        }
    }
    private static OcrExchange.Font font(ResultIterator iterator) {
        try (var flags = new BoolPointer(6L); var size = new IntPointer(1L); var id = new IntPointer(1L)) {
            // Separate views keep the native bool outputs owned by one allocation.
            var name = iterator.WordFontAttributes(new BoolPointer(flags).position(0), new BoolPointer(flags).position(1),
                    new BoolPointer(flags).position(2), new BoolPointer(flags).position(3), new BoolPointer(flags).position(4),
                    new BoolPointer(flags).position(5), size, id);
            return name == null || name.isNull() ? null : new OcrExchange.Font(flags.get(0), flags.get(1), flags.get(2),
                    flags.get(3), flags.get(4), flags.get(5), size.get(), null);
        }
    }
}
