// SPDX-License-Identifier: AGPL-3.0-or-later
package org.audiveris.wasm.compat.platform;

import java.util.ArrayList;
import org.audiveris.wasm.compat.platform.OcrExchange.*;
import org.teavm.jso.JSBody;
import org.teavm.jso.JSObject;
import org.teavm.jso.typedarrays.Int8Array;

/** Plain structured-clone data at the host boundary, with no Java object handles or JSON parser.
 * All inbound values are checked and copied into the immutable engine model.
 */
public final class OcrTransport {
    private OcrTransport() { }
    public static JSObject request(Request request) {
        Token token = request.token();
        return requestData(token.job(), token.page(), token.generation(), token.request(), request.width(),
            request.height(), Int8Array.copyFromJavaArray(request.pixels()), request.languages(), request.layout().name());
    }
    public static Token token(JSObject envelope) {
        JSObject value = object(field(object(envelope), "token"));
        return new Token(string(value, "job"), string(value, "page"), integer(value, "generation"), integer(value, "request"));
    }
    public static Reply reply(JSObject envelope) {
        Token token = token(envelope);
        JSObject data = field(envelope, "result"), failure = field(envelope, "failure");
        return new Reply(token, nullish(data) ? null : result(data), nullish(failure) ? null : failure(failure));
    }
    public static Result result(JSObject data) {
        object(data); JSObject caps = object(field(data, "capabilities"));
        Capabilities capabilities = new Capabilities(bool(caps, "characters"), bool(caps, "baselines"), bool(caps, "fontAttributes"));
        JSObject lines = array(field(data, "lines")); ArrayList<Line> copiedLines = new ArrayList<>();
        for (int i = 0, n = length(lines); i < n; i++) {
            JSObject line = object(element(lines, i)), words = array(field(line, "words"));
            ArrayList<Word> copiedWords = new ArrayList<>();
            for (int j = 0, m = length(words); j < m; j++) {
                JSObject word = object(element(words, j)), chars = array(field(word, "characters"));
                ArrayList<OcrExchange.Character> copiedChars = new ArrayList<>();
                for (int k = 0, count = length(chars); k < count; k++) {
                    JSObject character = object(element(chars, k));
                    copiedChars.add(new OcrExchange.Character(string(character, "text"), box(field(character, "bounds"))));
                }
                copiedWords.add(new Word(string(word, "text"), box(field(word, "bounds")), number(word, "confidence"),
                    baseline(field(word, "baseline")), font(field(word, "font")), copiedChars));
            }
            copiedLines.add(new Line(copiedWords, baseline(field(line, "baseline"))));
        }
        return new Result(capabilities, copiedLines);
    }
    private static Failure failure(JSObject data) {
        object(data); return new Failure(FailureCode.valueOf(string(data, "code")), string(data, "message"));
    }
    private static Box box(JSObject data) {
        object(data); return new Box(integer(data, "x"), integer(data, "y"), integer(data, "width"), integer(data, "height"));
    }
    private static Baseline baseline(JSObject data) {
        if (nullish(data)) { return null; }
        object(data); return new Baseline(number(data, "x1"), number(data, "y1"), number(data, "x2"), number(data, "y2"));
    }
    private static Font font(JSObject data) {
        if (nullish(data)) { return null; }
        object(data); JSObject name = field(data, "name");
        return new Font(bool(data, "bold"), bool(data, "italic"), bool(data, "underlined"), bool(data, "monospace"),
            bool(data, "serif"), bool(data, "smallcaps"), integer(data, "pointSize"), nullish(name) ? null : string(data, "name"));
    }
    private static JSObject object(JSObject value) {
        if (nullish(value) || !kind(value).equals("object") || isArray(value)) { throw new IllegalArgumentException("Expected OCR object"); }
        return value;
    }
    private static JSObject array(JSObject value) {
        if (nullish(value) || !isArray(value) || length(value) < 0) { throw new IllegalArgumentException("Expected OCR array"); }
        return value;
    }
    private static String string(JSObject owner, String key) {
        JSObject value = field(owner, key);
        if (!kind(value).equals("string")) { throw new IllegalArgumentException("Expected OCR string: " + key); }
        return stringValue(value);
    }
    private static boolean bool(JSObject owner, String key) {
        JSObject value = field(owner, key);
        if (!kind(value).equals("boolean")) { throw new IllegalArgumentException("Expected OCR boolean: " + key); }
        return booleanValue(value);
    }
    private static double number(JSObject owner, String key) {
        JSObject value = field(owner, key);
        if (!kind(value).equals("number")) { throw new IllegalArgumentException("Expected OCR number: " + key); }
        double number = numberValue(value);
        if (!Double.isFinite(number)) { throw new IllegalArgumentException("Non-finite OCR number: " + key); }
        return number;
    }
    private static int integer(JSObject owner, String key) {
        double value = number(owner, key);
        if (value < Integer.MIN_VALUE || value > Integer.MAX_VALUE || value != Math.rint(value)) {
            throw new IllegalArgumentException("Expected OCR int32: " + key);
        }
        return (int)value;
    }
    @JSBody(params={"job","page","generation","request","width","height","pixels","languages","layout"}, script=
        "return {token:{job:job,page:page,generation:generation,request:request},width:width,height:height,pixels:new Uint8Array(pixels.buffer,pixels.byteOffset,pixels.byteLength),languages:languages,layout:layout};")
    private static native JSObject requestData(String job,String page,int generation,int request,int width,int height,Int8Array pixels,String languages,String layout);
    @JSBody(params={"value","key"}, script="return value[key];") private static native JSObject field(JSObject value,String key);
    @JSBody(params={"value","index"}, script="return value[index];") private static native JSObject element(JSObject value,int index);
    @JSBody(params="value", script="return value == null;") private static native boolean nullish(JSObject value);
    @JSBody(params="value", script="return typeof value;") private static native String kind(JSObject value);
    @JSBody(params="value", script="return Array.isArray(value);") private static native boolean isArray(JSObject value);
    @JSBody(params="value", script="return value.length;") private static native int length(JSObject value);
    @JSBody(params="value", script="return value;") private static native String stringValue(JSObject value);
    @JSBody(params="value", script="return value;") private static native double numberValue(JSObject value);
    @JSBody(params="value", script="return value;") private static native boolean booleanValue(JSObject value);
}
