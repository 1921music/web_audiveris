package org.audiveris.wasm.compat.test;

import java.util.*;
import org.audiveris.wasm.compat.platform.OcrExchange;
import org.audiveris.wasm.compat.platform.OcrImageFrame;
import org.audiveris.wasm.compat.platform.OcrExchange.*;
import org.teavm.jso.JSExport;

/** State transition and ownership contracts; no synthetic OCR accuracy claim. */
public final class OcrExchangeProbe {
    public static void main(String[] args) { }
    private static int checks;
    private static OcrExchange live;
    private static Token token;
    private static final Capabilities REQUIRED = new Capabilities(true, false, false);
    private static Result empty() { return new Result(REQUIRED, List.of()); }
    private static void check(boolean condition) { checks++; if (!condition) { throw new AssertionError("OCR contract " + checks); } }
    private static void rejects(Runnable action, boolean stateError) {
        boolean rejected = false;
        try { action.run(); }
        catch (IllegalStateException e) { if (!stateError) { throw e; } rejected = true; }
        catch (IllegalArgumentException e) { if (stateError) { throw e; } rejected = true; }
        check(rejected);
    }
    @JSExport public static String contracts() {
        checks = 0;
        rejects(() -> new OcrExchange("job", "page", -1), false);
        rejects(() -> new Box(Integer.MAX_VALUE, 0, 1, 1), false);
        rejects(() -> new Baseline(0, 0, Double.NaN, 1), false);
        rejects(() -> new Result(new Capabilities(false, false, false), List.of()), false);
        Box box = new Box(1, 2, 3, 4);
        var characters = new ArrayList<OcrExchange.Character>();
        characters.add(new OcrExchange.Character("A", box));
        Word word = new Word("A", box, 0.8, null, null, characters);
        characters.clear(); check(word.characters().size() == 1);
        rejects(() -> new Word("A", box, Double.NaN, null, null, word.characters()), false);
        rejects(() -> new Word("A", box, 1.01, null, null, word.characters()), false);
        rejects(() -> new Word("A", box, 0.5, null, null, List.of()), false);
        var words = new ArrayList<Word>(); words.add(word);
        Line line = new Line(words); words.clear(); check(line.words().size() == 1);
        var lines = new ArrayList<Line>(); lines.add(line);
        Result result = new Result(REQUIRED, lines); lines.clear(); check(result.lines().size() == 1);
        Word detailed = new Word("A", box, 0.8, new Baseline(1, 5, 4, 5),
            new Font(true, false, false, false, true, false, 12, null), word.characters());
        rejects(() -> new Result(REQUIRED, List.of(new Line(List.of(detailed)))), false);
        check(new Result(new Capabilities(true, true, true), List.of(line, new Line(List.of(detailed)))).lines().size() == 2);
        for (int generation = 0; generation < 24; generation++) {
            OcrExchange session = new OcrExchange("job", "page", generation);
            OcrExchange other = new OcrExchange("other", "page", generation);
            byte[] image = new byte[]{0, 20, (byte)255, 100};
            rejects(() -> session.request(3, 2, image, "eng", Layout.MULTI_BLOCK), false);
            check(session.state() == State.IDLE);
            Request request = session.request(2, 2, image, "eng+deu", Layout.MULTI_BLOCK);
            other.request(2, 2, image, "eng", Layout.MULTI_BLOCK);
            image[0] = 42; byte[] copy = request.pixels(); copy[1] = 99;
            check(request.pixels()[0] == 0 && request.pixels()[1] == 20);
            check(request.width() == 2 && request.height() == 2 && request.languages().equals("eng+deu")
                && request.layout() == Layout.MULTI_BLOCK);
            rejects(() -> session.request(2, 2, image, "eng", Layout.SINGLE_BLOCK), true);
            rejects(session::takeReply, true);
            check(!session.accept(new Reply(new Token("job", "page", generation + 1, 1), result, null)));
            check(!session.accept(new Reply(new Token("job", "other", generation, 1), result, null)));
            check(!other.accept(new Reply(request.token(), result, null)));
            check(session.state() == State.WAITING);
            rejects(() -> new Reply(request.token(), null, null), false);
            rejects(() -> new Reply(request.token(), result, new Failure(FailureCode.EXECUTION_FAILED, "failed")), false);
            check(session.accept(new Reply(request.token(), result, null)));
            check(!session.accept(new Reply(request.token(), result, null)));
            check(session.takeReply().result() == result && session.state() == State.IDLE);
            Request second = session.request(2, 2, image, "eng", Layout.SINGLE_BLOCK);
            check(second.token().request() == 2 && !session.accept(new Reply(request.token(), empty(), null)));
            check(session.accept(new Reply(second.token(), empty(), null)));
            Reply blank = session.takeReply(); check(blank.failure() == null && blank.result().lines().isEmpty());
            for (FailureCode code : FailureCode.values()) {
                Request failed = session.request(2, 2, image, "eng", Layout.SINGLE_BLOCK);
                Failure error = new Failure(code, "backend failure");
                check(session.accept(new Reply(failed.token(), null, error)));
                check(session.takeReply().failure() == error);
            }
            Request cancelled = session.request(2, 2, image, "eng", Layout.SINGLE_BLOCK);
            session.cancel(); session.cancel();
            check(!session.accept(new Reply(cancelled.token(), empty(), null)));
            check(session.state() == State.CANCELLED);
            rejects(session::takeReply, true);
            rejects(() -> session.request(2, 2, image, "eng", Layout.SINGLE_BLOCK), true);
            OcrExchange ready = new OcrExchange("job", "page", generation);
            Request r = ready.request(2, 2, image, "eng", Layout.MULTI_BLOCK);
            check(ready.accept(new Reply(r.token(), empty(), null))); ready.cancel();
            rejects(ready::takeReply, true);
        }
        long imageHash = frameContracts();
        return "OCR exchange contracts: " + checks + "; GRAY8 frames: " + imageHash;
    }
    private static long frameContracts() {
        long hash = 1;
        for (int seed = 0; seed < 128; seed++) {
            int width = 1 + seed % 31, height = 1 + seed % 17;
            byte[] image = new byte[width * height];
            for (int i = 0; i < image.length; i++) { image[i] = (byte)(i * 37 + seed * 11); }
            for (int configured : new int[]{-3, 0, 1, 10, 31}) {
                int margin = Math.max(0, configured);
                OcrExchange session = new OcrExchange("frame", "page", seed);
                var frame = OcrImageFrame.begin(session, width, height, image, configured, "eng", Layout.MULTI_BLOCK);
                Request request = frame.request(); byte[] pixels = request.pixels();
                check(request.width() == width + 2 * margin && request.height() == height + 2 * margin);
                check(frame.originX() == -margin && frame.originY() == -margin);
                for (byte pixel : pixels) { hash = hash * 31 + (pixel & 255); }
                pixels[0] ^= 127; check(!Arrays.equals(pixels, request.pixels()));
                var box = new Box(3 + margin, 5 + margin, 10, 8);
                var baseline = new Baseline(3 + margin, 13 + margin, 13 + margin, 13 + margin);
                var font = new Font(true, false, false, false, true, false, 12, null);
                var word = new Word("A", box, 0.912345678, baseline, font, List.of(new OcrExchange.Character("A", box)));
                Result input = new Result(new Capabilities(true, true, true), List.of(new Line(List.of(word), baseline)));
                Reply reply = new Reply(request.token(), input, null);
                check(!frame.accept(new Reply(new Token("frame", "page", seed + 1, request.token().request()), input, null)));
                check(frame.accept(reply)); check(!frame.accept(reply));
                Result output = session.takeReply().result(); Word restored = output.lines().get(0).words().get(0);
                check(restored.bounds().equals(new Box(3, 5, 10, 8)) && restored.characters().get(0).bounds().equals(restored.bounds()));
                check(restored.baseline().equals(new Baseline(3, 13, 13, 13)) && output.lines().get(0).baseline().equals(restored.baseline()));
                check(restored.font() == font && restored.confidence() == word.confidence() && output.capabilities().equals(input.capabilities()));
                check(word.bounds().x() == 3 + margin && !frame.accept(reply));
                var failureFrame = OcrImageFrame.begin(session, width, height, image, configured, "eng", Layout.SINGLE_BLOCK);
                Failure failure = new Failure(FailureCode.MODEL_MISSING, "missing");
                check(failureFrame.accept(new Reply(failureFrame.request().token(), null, failure)));
                check(session.takeReply().failure() == failure);
                var cancelled = OcrImageFrame.begin(session, width, height, image, configured, "eng", Layout.SINGLE_BLOCK);
                session.cancel(); check(!cancelled.accept(new Reply(cancelled.request().token(), empty(), null)));
            }
        }
        OcrExchange session = new OcrExchange("invalid-frame", "page", 0);
        rejects(() -> OcrImageFrame.begin(session, 1, 1, new byte[1], Integer.MAX_VALUE, "eng", Layout.MULTI_BLOCK), false);
        rejects(() -> OcrImageFrame.begin(session, 2, 1, new byte[1], 10, "eng", Layout.MULTI_BLOCK), false);
        check(session.state() == State.IDLE);
        var frame = OcrImageFrame.begin(session, 1, 1, new byte[1], 10, "eng", Layout.MULTI_BLOCK);
        var outside = new Box(8, 9, 1, 1);
        Word unstyled = new Word("x", outside, 0.5, null, null, List.of(new OcrExchange.Character("x", outside)));
        Result unstyledResult = new Result(REQUIRED, List.of(new Line(List.of(unstyled))));
        check(frame.accept(new Reply(frame.request().token(), unstyledResult, null)));
        Word restored = session.takeReply().result().lines().get(0).words().get(0);
        check(restored.bounds().equals(new Box(-2, -1, 1, 1)) && restored.font() == null && restored.baseline() == null);
        var blank = OcrImageFrame.begin(session, 1, 1, new byte[1], 10, "eng", Layout.MULTI_BLOCK);
        check(blank.accept(new Reply(blank.request().token(), empty(), null)));
        check(session.takeReply().result().lines().isEmpty());
        var overflow = OcrImageFrame.begin(session, 1, 1, new byte[1], 10, "eng", Layout.MULTI_BLOCK);
        var edge = new Box(Integer.MIN_VALUE, 0, 0, 0);
        var edgeWord = new Word("x", edge, 0.5, null, null, List.of(new OcrExchange.Character("x", edge)));
        boolean rejected = false;
        try { overflow.accept(new Reply(overflow.request().token(), new Result(REQUIRED, List.of(new Line(List.of(edgeWord)))), null)); }
        catch (ArithmeticException expected) { rejected = true; }
        check(rejected && session.state() == State.WAITING);
        check(overflow.accept(new Reply(overflow.request().token(), empty(), null)));
        check(session.takeReply().result().lines().isEmpty());
        return hash;
    }
    /** Separate JS calls exercise an actual asynchronous event-loop gap while waiting. */
    @JSExport public static int begin(int generation) {
        live = new OcrExchange("live-job", "page", generation);
        token = live.request(1, 1, new byte[]{0}, "eng", Layout.MULTI_BLOCK).token();
        return token.request();
    }
    @JSExport public static boolean respond(int generation) {
        return live.accept(new Reply(new Token("live-job", "page", generation, token.request()), empty(), null));
    }
    @JSExport public static String state() { return live.state().name(); }
    @JSExport public static void cancel() { live.cancel(); }
    @JSExport public static int resume() { return live.takeReply().result().lines().size(); }
}
