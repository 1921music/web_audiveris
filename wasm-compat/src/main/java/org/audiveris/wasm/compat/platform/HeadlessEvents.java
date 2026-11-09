// SPDX-License-Identifier: AGPL-3.0-or-later
package org.audiveris.wasm.compat.platform;

import java.util.Objects;
import org.audiveris.omr.sheet.Book;
import org.audiveris.omr.sheet.SheetStub;
import org.audiveris.omr.step.OmrStep;
import org.slf4j.MDC;

/** Synchronous event sink at the Worker boundary; a host adapter can forward these to postMessage. */
public final class HeadlessEvents {
    public interface Sink { void accept(String kind, String subject, String detail); }
    private static Sink sink = (kind, subject, detail) -> { };
    private static String sheetId = "";
    private HeadlessEvents() { }
    public static void setSink(Sink value) { sink = Objects.requireNonNull(value); }
    public static void setSheetId(String value) { sheetId = Objects.requireNonNull(value); }
    static String sheetId() { return sheetId; }
    public static void emit(String kind, String subject, String detail) { sink.accept(kind, subject, detail); }
    public static void animate() { emit("progress", "animate", ""); }
    public static void start() { emit("progress", "start", ""); }
    public static void stop() { emit("progress", "stop", ""); }
    public static void message(String text) { emit("message", "", text); HeadlessLogging.logger("progress").info(text); }
    public static void step(SheetStub stub, OmrStep step) {
        // Job identity comes from the host, not desktop-oriented SheetStub/Book formatting.
        emit("step", sheetId, step == null ? "" : ((Enum<?>) step).name());
    }
    public static void book(Book book) {
        String alias = book.getAlias(); MDC.put("BOOK", alias == null ? book.getRadix() : alias);
    }
    public static void stub(SheetStub stub) { book(stub.getBook()); MDC.put("SHEET", stub.getNum()); }
    public static void stopBook() { MDC.remove("SHEET"); MDC.remove("BOOK"); }
    public static void stopStub() { MDC.remove("SHEET"); }
    public static boolean notEventThread() { return false; }
    public static void rejectGui(Object value) {
        if (value != null) { throw new UnsupportedOperationException("Desktop GUI is unavailable in the Worker engine"); }
    }
    public static void unsupportedDesktop() { throw new UnsupportedOperationException("Desktop UI/log-file setup is unavailable; use the Worker event/log sink"); }
}
