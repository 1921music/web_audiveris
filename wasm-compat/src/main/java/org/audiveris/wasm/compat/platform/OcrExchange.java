// SPDX-License-Identifier: AGPL-3.0-or-later
package org.audiveris.wasm.compat.platform;

import java.util.List;
import java.util.Objects;

/** One pending external OCR operation per engine session, owned by its Worker.
 * No Java stack is suspended. The caller keeps its recognition context until takeReply().
 * Coordinates are relative to the submitted (possibly padded) image; page offsets stay engine-side.
 */
public final class OcrExchange {
    public enum Layout { MULTI_BLOCK, SINGLE_BLOCK }
    public enum State { IDLE, WAITING, READY, CANCELLED }
    public enum FailureCode { MODEL_MISSING, UNSUPPORTED, EXECUTION_FAILED }
    public record Capabilities(boolean characters, boolean baselines, boolean fontAttributes) { }
    public record Token(String job, String page, int generation, int request) {
        public Token {
            requireName(job); requireName(page);
            if (generation < 0 || request <= 0) { throw new IllegalArgumentException("Invalid OCR token"); }
        }
    }
    public record Box(int x, int y, int width, int height) {
        public Box {
            if (width < 0 || height < 0 || (long)x + width > Integer.MAX_VALUE
                    || (long)y + height > Integer.MAX_VALUE) { throw new IllegalArgumentException("Invalid OCR box"); }
        }
    }
    public record Baseline(double x1, double y1, double x2, double y2) {
        public Baseline { finite(x1); finite(y1); finite(x2); finite(y2); }
    }
    public record Font(boolean bold, boolean italic, boolean underlined, boolean monospace,
                       boolean serif, boolean smallcaps, int pointSize, String name) {
        public Font { if (pointSize <= 0) { throw new IllegalArgumentException("Invalid OCR font size"); } }
    }
    public record Character(String text, Box bounds) {
        public Character { requireName(text); Objects.requireNonNull(bounds); }
    }
    public record Word(String text, Box bounds, double confidence, Baseline baseline,
                       Font font, List<Character> characters) {
        public Word {
            requireName(text); Objects.requireNonNull(bounds); OcrExchange.confidence(confidence);
            characters = List.copyOf(characters);
            if (characters.isEmpty()) { throw new IllegalArgumentException("Missing OCR character boxes"); }
        }
    }
    public record Line(List<Word> words, Baseline baseline) {
        public Line { words = List.copyOf(words); }
        public Line(List<Word> words) { this(words, null); }
    }
    /** Confidence is normalized to [0,1]; optional metadata remains null if unavailable. */
    public record Result(Capabilities capabilities, List<Line> lines) {
        public Result {
            Objects.requireNonNull(capabilities); lines = List.copyOf(lines);
            if (!capabilities.characters) { throw new IllegalArgumentException("OCR requires character boxes"); }
            for (Line line : lines) {
                if (!capabilities.baselines && line.baseline != null) { throw new IllegalArgumentException("Unexpected OCR line baseline"); }
                for (Word word : line.words) {
                    if ((!capabilities.baselines && word.baseline != null)
                            || (!capabilities.fontAttributes && word.font != null)) {
                        throw new IllegalArgumentException("OCR metadata does not match capabilities");
                    }
                }
            }
        }
    }
    public record Failure(FailureCode code, String message) {
        public Failure { Objects.requireNonNull(code); requireName(message); }
    }
    public record Reply(Token token, Result result, Failure failure) {
        public Reply {
            Objects.requireNonNull(token);
            if ((result == null) == (failure == null)) { throw new IllegalArgumentException("OCR reply needs one outcome"); }
        }
    }
    /** pixels() returns a transfer snapshot, never the engine's working buffer. */
    public static final class Request {
        private final Token token;
        private final int width, height;
        private final byte[] pixels;
        private final String languages;
        private final Layout layout;
        private Request(Token token, int width, int height, byte[] pixels, String languages, Layout layout) {
            this.token = token; this.width = width; this.height = height;
            this.pixels = pixels.clone(); this.languages = languages; this.layout = layout;
        }
        public Token token() { return token; }
        public int width() { return width; }
        public int height() { return height; }
        public byte[] pixels() { return pixels.clone(); }
        public String languages() { return languages; }
        public Layout layout() { return layout; }
    }
    private final String job, page;
    private final int generation;
    private int sequence;
    private State state = State.IDLE;
    private Request pending;
    private Reply reply;
    public OcrExchange(String job, String page, int generation) {
        requireName(job); requireName(page);
        if (generation < 0) { throw new IllegalArgumentException("Invalid OCR generation"); }
        this.job = job; this.page = page; this.generation = generation;
    }
    public State state() { return state; }
    public boolean matches(Token candidate) {
        return state == State.WAITING && pending.token.equals(Objects.requireNonNull(candidate));
    }
    public Request request(int width, int height, byte[] pixels, String languages, Layout layout) {
        if (state != State.IDLE) { throw new IllegalStateException("OCR session is not idle"); }
        Objects.requireNonNull(pixels); requireName(languages); Objects.requireNonNull(layout);
        if (width <= 0 || height <= 0 || (long)width * height != pixels.length) {
            throw new IllegalArgumentException("Invalid packed GRAY8 OCR image");
        }
        if (sequence == Integer.MAX_VALUE) { throw new IllegalStateException("OCR request sequence exhausted"); }
        Request next = new Request(new Token(job, page, generation, sequence + 1), width, height, pixels, languages, layout);
        pending = next; sequence++; state = State.WAITING; return next;
    }
    /** False means stale, duplicate, cancelled, or belonging to another job/page/generation. */
    public boolean accept(Reply candidate) {
        Objects.requireNonNull(candidate);
        if (!matches(candidate.token)) { return false; }
        reply = candidate; pending = null; state = State.READY; return true;
    }
    public Reply takeReply() {
        if (state != State.READY) { throw new IllegalStateException("OCR reply is not ready"); }
        Reply value = reply; reply = null; state = State.IDLE; return value;
    }
    /** Terminal for this execution generation. A restarted job creates a new exchange. */
    public void cancel() { pending = null; reply = null; state = State.CANCELLED; }
    private static void requireName(String value) {
        if (Objects.requireNonNull(value).isEmpty()) { throw new IllegalArgumentException("Empty OCR field"); }
    }
    private static void finite(double value) {
        if (!Double.isFinite(value)) { throw new IllegalArgumentException("Non-finite OCR coordinate"); }
    }
    private static void confidence(double value) {
        if (!Double.isFinite(value) || value < 0 || value > 1) { throw new IllegalArgumentException("Invalid OCR confidence"); }
    }
}
