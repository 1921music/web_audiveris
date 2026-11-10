package org.audiveris.wasm.compat.test;

import java.util.List;
import org.audiveris.wasm.compat.platform.*;
import org.audiveris.wasm.compat.platform.OcrExchange.*;
import org.teavm.jso.JSExport;
import org.teavm.jso.JSObject;
import org.teavm.jso.typedarrays.Int8Array;

public final class OcrTransportProbe {
    private static OcrExchange session;
    private static Request pending;
    private static OcrImageFrame frame;
    private static Result retained;
    public static void main(String[] args) { }
    public static Result fixture() {
        var baseline = new Baseline(0, 9, 54, 10.25);
        var font = new Font(true, true, false, false, true, false, 12, "Serif \"test\"\n");
        var first = new Word("A𝄞é", new Box(0, 0, 27, 10), 0.9375, new Baseline(0, 9, 27, 9.5), font,
            List.of(new OcrExchange.Character("A", new Box(0, 0, 8, 10)),
                new OcrExchange.Character("𝄞", new Box(8, 0, 12, 10)),
                new OcrExchange.Character("é", new Box(20, 0, 7, 10))));
        var second = new Word("日本語", new Box(30, 0, 24, 10), 0.75, null, null,
            List.of(new OcrExchange.Character("日", new Box(30, 0, 8, 10)),
                new OcrExchange.Character("本", new Box(38, 0, 8, 10)),
                new OcrExchange.Character("語", new Box(46, 0, 8, 10))));
        return new Result(new Capabilities(true, true, true), List.of(new Line(List.of(first, second), baseline), new Line(List.of())));
    }
    @JSExport public static boolean importFixture(JSObject value) { return OcrTransport.result(value).equals(fixture()); }
    @JSExport public static void retain(JSObject value) { retained = OcrTransport.result(value); }
    @JSExport public static boolean retainedMatches() { return retained.equals(fixture()); }
    @JSExport public static boolean rejects(JSObject value) {
        try { OcrTransport.result(value); return false; } catch (IllegalArgumentException expected) { return true; }
    }
    @JSExport public static JSObject begin(int generation) {
        frame = null;
        session = new OcrExchange("transport-job", "page-1", generation);
        pending = session.request(3, 2, new byte[]{0, 10, 20, (byte)128, (byte)200, (byte)255}, "eng+deu", Layout.MULTI_BLOCK);
        return OcrTransport.request(pending);
    }
    @JSExport public static JSObject imageRequest(int width, int height, Int8Array pixels, String languages, String layout, int generation) {
        frame = null;
        session = new OcrExchange("transport-job", "page-1", generation);
        pending = session.request(width, height, pixels.copyToJavaArray(), languages, Layout.valueOf(layout));
        return OcrTransport.request(pending);
    }
    @JSExport public static JSObject paddedImageRequest(int width, int height, Int8Array pixels, int margin, String languages, String layout, int generation) {
        session = new OcrExchange("transport-job", "page-1", generation);
        frame = OcrImageFrame.begin(session, width, height, pixels.copyToJavaArray(), margin, languages, Layout.valueOf(layout));
        pending = frame.request(); return OcrTransport.request(pending);
    }
    @JSExport public static boolean resumeMatches(JSObject expected) {
        return session.takeReply().result().equals(OcrTransport.result(expected));
    }
    @JSExport public static String resumeSummary() {
        Reply reply = session.takeReply();
        if (reply.failure() != null) { return "failure:" + reply.failure().code().name(); }
        int words = 0, characters = 0;
        for (Line line : reply.result().lines()) {
            words += line.words().size();
            for (Word word : line.words()) { characters += word.characters().size(); }
        }
        return reply.result().lines().size() + "/" + words + "/" + characters;
    }
    @JSExport public static boolean originalPixelsOwned() {
        return java.util.Arrays.equals(pending.pixels(), new byte[]{0, 10, 20, (byte)128, (byte)200, (byte)255});
    }
    @JSExport public static boolean receive(JSObject envelope) {
        Token token = OcrTransport.token(envelope);
        if (!session.matches(token)) { return false; }
        Reply reply = OcrTransport.reply(envelope);
        return frame == null ? session.accept(reply) : frame.accept(reply);
    }
    @JSExport public static String resume() {
        Reply reply = session.takeReply();
        if (reply.failure() != null) { return reply.failure().code().name() + ":" + reply.failure().message(); }
        if (reply.result().equals(fixture())) { return "fixture"; }
        if (reply.result().lines().isEmpty()) { return "empty"; }
        throw new AssertionError("Unexpected OCR fixture");
    }
    @JSExport public static void cancel() { session.cancel(); }
}
