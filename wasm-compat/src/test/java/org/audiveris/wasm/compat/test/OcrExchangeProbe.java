package org.audiveris.wasm.compat.test;

import java.util.*;
import org.audiveris.wasm.compat.platform.OcrExchange;
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
        return "OCR exchange contracts: " + checks;
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
