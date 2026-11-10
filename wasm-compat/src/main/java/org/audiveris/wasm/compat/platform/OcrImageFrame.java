// SPDX-License-Identifier: AGPL-3.0-or-later
package org.audiveris.wasm.compat.platform;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Objects;
import org.audiveris.wasm.compat.platform.OcrExchange.*;

/** GRAY8 equivalent of OcrUtil's white border and TesseractOCR's origin translation.
 * The caller supplies the configured white margin; this boundary does not choose defaults,
 * sort lines, fit fonts, or suspend a Java stack.
 */
public final class OcrImageFrame {
    private final OcrExchange session;
    private final Request request;
    private final int margin;
    private OcrImageFrame(OcrExchange session, Request request, int margin) {
        this.session = session; this.request = request; this.margin = margin;
    }
    public static OcrImageFrame begin(OcrExchange session, int width, int height, byte[] pixels,
                                      int configuredMargin, String languages, Layout layout) {
        Objects.requireNonNull(session); Objects.requireNonNull(pixels);
        if (session.state() != State.IDLE) { throw new IllegalStateException("OCR request already pending"); }
        if (width <= 0 || height <= 0 || (long)width * height != pixels.length) {
            throw new IllegalArgumentException("Invalid packed GRAY8 image");
        }
        int margin = Math.max(0, configuredMargin); // Original OcrUtil disables nonpositive margins.
        long paddedWidth = width + 2L * margin, paddedHeight = height + 2L * margin;
        if (paddedWidth > Integer.MAX_VALUE || paddedHeight > Integer.MAX_VALUE
                || paddedWidth * paddedHeight > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("Padded OCR image exceeds array dimensions");
        }
        byte[] input = pixels;
        if (margin > 0) {
            input = new byte[(int)(paddedWidth * paddedHeight)]; Arrays.fill(input, (byte)255);
            for (int y = 0; y < height; y++) {
                System.arraycopy(pixels, y * width, input, (y + margin) * (int)paddedWidth + margin, width);
            }
        }
        Request request = session.request((int)paddedWidth, (int)paddedHeight, input, languages, layout);
        return new OcrImageFrame(session, request, margin);
    }
    public Request request() { return request; }
    public int originX() { return -margin; }
    public int originY() { return -margin; }
    /** Admit a matching reply once, restoring image coordinates before the caller resumes.
     * Stale/duplicate/cancelled replies are rejected before walking their result.
     */
    public boolean accept(Reply reply) {
        Objects.requireNonNull(reply);
        if (!request.token().equals(reply.token()) || !session.matches(reply.token())) { return false; }
        if (reply.result() == null || margin == 0) { return session.accept(reply); }
        Result result = reply.result(); var lines = new ArrayList<Line>();
        for (Line line : result.lines()) {
            var words = new ArrayList<Word>();
            for (Word word : line.words()) {
                var characters = new ArrayList<OcrExchange.Character>();
                for (OcrExchange.Character character : word.characters()) {
                    characters.add(new OcrExchange.Character(character.text(), translate(character.bounds())));
                }
                words.add(new Word(word.text(), translate(word.bounds()), word.confidence(),
                        translate(word.baseline()), word.font(), characters));
            }
            lines.add(new Line(words, translate(line.baseline())));
        }
        return session.accept(new Reply(reply.token(), new Result(result.capabilities(), lines), null));
    }
    private Box translate(Box box) {
        return new Box(Math.subtractExact(box.x(), margin), Math.subtractExact(box.y(), margin), box.width(), box.height());
    }
    private Baseline translate(Baseline line) {
        return line == null ? null : new Baseline(line.x1() - margin, line.y1() - margin, line.x2() - margin, line.y2() - margin);
    }
}
