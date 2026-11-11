// SPDX-License-Identifier: AGPL-3.0-or-later
package org.audiveris.omr.text;

import ij.process.ByteProcessor;
import java.awt.Rectangle;
import java.awt.geom.Line2D;
import java.awt.geom.Rectangle2D;
import java.util.ArrayList;
import java.util.List;
import org.audiveris.omr.sheet.Sheet;
import org.audiveris.omr.sheet.SystemInfo;
import org.audiveris.omr.step.StepException;
import org.audiveris.wasm.compat.platform.OcrExchange;
import org.audiveris.wasm.compat.platform.OcrImageFrame;

/** Explicit external-OCR continuation. Prepared rasters must include the original TEXTS cleanup. */
public final class WorkerTextsBridge {
    private final Sheet sheet;
    private final ByteProcessor buffer;
    private final OcrExchange exchange;
    private final OcrImageFrame frame;
    private boolean resumed;
    public WorkerTextsBridge(Sheet sheet, ByteProcessor cleanedBuffer, String job, int generation, int margin, String languages) {
        this.sheet = java.util.Objects.requireNonNull(sheet);
        if (cleanedBuffer.getWidth() != sheet.getWidth() || cleanedBuffer.getHeight() != sheet.getHeight()) {
            throw new IllegalArgumentException("TEXTS raster dimensions differ from sheet");
        }
        buffer = new ByteProcessor(cleanedBuffer.getWidth(), cleanedBuffer.getHeight(),
                ((byte[])cleanedBuffer.getPixels()).clone(), null);
        exchange = new OcrExchange(job, sheet.getId(), generation);
        frame = OcrImageFrame.begin(exchange, buffer.getWidth(), buffer.getHeight(), (byte[])buffer.getPixels(),
                margin, languages, OcrExchange.Layout.MULTI_BLOCK);
    }
    public OcrExchange.Request request() { return frame.request(); }
    public boolean accept(OcrExchange.Reply reply) { return frame.accept(reply); }
    public void cancel() { exchange.cancel(); }
    public OcrExchange.State state() { return exchange.state(); }
    public List<TextLine> resume() throws StepException {
        if (resumed) { throw new IllegalStateException("TEXTS already resumed"); }
        OcrExchange.Reply reply = exchange.takeReply(); resumed = true;
        if (reply.failure() != null) { throw new StepException("External OCR " + reply.failure().code() + ": " + reply.failure().message()); }
        List<TextLine> lines = textLines(sheet, reply.result());
        new Continuation().run(sheet, new TextsStep.Context(buffer, lines));
        sheet.getStub().done(org.audiveris.omr.step.OmrStep.TEXTS);
        return lines;
    }
    /** Preserve original word construction, character splitting, ordinate sorting and font adjustment. */
    public static List<TextLine> textLines(Sheet sheet, OcrExchange.Result result) {
        if (!result.capabilities().fontAttributes() && result.lines().stream().anyMatch(line -> !line.words().isEmpty())) {
            throw new UnsupportedOperationException("TEXTS requires OCR font metadata or an explicit fallback");
        }
        var lines = new ArrayList<TextLine>();
        for (OcrExchange.Line input : result.lines()) {
            var line = new TextLine(sheet);
            for (OcrExchange.Word source : input.words()) {
                var font = source.font(); var baseline = source.baseline();
                if (font == null) { continue; } // Original TesseractOrder skips individual words without font info.
                var info = new FontInfo(font.bold(), font.italic(), font.underlined(), font.monospace(), font.serif(), font.smallcaps(), font.pointSize(), font.name());
                var word = new TextWord(sheet, box(source.bounds()), source.text(),
                        baseline == null ? null : new Line2D.Double(baseline.x1(), baseline.y1(), baseline.x2(), baseline.y2()), source.confidence(), info, line);
                for (OcrExchange.Character character : source.characters()) {
                    Rectangle bounds = box(character.bounds()); String value = character.text();
                    if (value.length() == 1) { word.addChar(new TextChar(bounds, value)); }
                    else { // Same UTF-16 splitting as TesseractOrder.wordAddChars.
                        double width = (double)bounds.width / value.length();
                        for (int i = 0; i < value.length(); i++) {
                            word.addChar(new TextChar(new Rectangle2D.Double(bounds.x + i * width, bounds.y, width, bounds.height).getBounds(), value.substring(i, i + 1)));
                        }
                    }
                }
                line.appendWord(word);
            }
            if (!line.getValue().isBlank()) { lines.add(line); }
        }
        lines.sort(TextLine.byOrdinate(sheet.getSkew()));
        for (TextLine line : lines) { for (TextWord word : line.getWords()) { word.adjustFont(); } }
        return lines;
    }
    private static Rectangle box(OcrExchange.Box box) { return new Rectangle(box.x(), box.y(), box.width(), box.height()); }
    private static final class Continuation extends TextsStep {
        void run(Sheet sheet, Context context) throws StepException {
            for (SystemInfo system : sheet.getSystems()) { super.doSystem(system, context); }
            super.doEpilog(sheet, context);
        }
    }
}
