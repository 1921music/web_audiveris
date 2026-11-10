package org.audiveris.wasm.compat.test;

import java.util.Map;
import org.audiveris.omr.sheet.Book;
import org.audiveris.omr.sheet.Sheet;
import org.audiveris.omr.sheet.SheetStub;
import org.audiveris.wasm.compat.platform.HeadlessConfiguration;
import org.teavm.jso.JSExport;

/** Diagnose model construction before adding raster storage or recognition steps. */
public final class SheetModelProbe {
    public static void main(String[] args) { }
    @JSExport public static String construct() {
        HeadlessConfiguration.configure(Map.of(), Map.of());
        Book book = Book.createMemoryBook("model");
        return model(book);
    }
    public static String model(Book book) {
        SheetStub stub = new SheetStub(book, 1);
        book.addStub(stub);
        lock(book.getLock()); lock(stub.getLock());
        Sheet sheet = new Sheet(stub);
        if (sheet.getStaffManager() == null || sheet.getLagManager() == null
                || sheet.getGlyphIndex() == null || sheet.getInterIndex() == null) {
            throw new AssertionError("Incomplete sheet model");
        }
        return sheet.getId() + ";valid=" + stub.isValid();
    }
    private static void lock(java.util.concurrent.locks.Lock lock) {
        lock.lock(); lock.lock();
        if (!lock.tryLock()) { throw new AssertionError("Reentrant model lock"); }
        lock.unlock(); lock.unlock(); lock.unlock();
        try { lock.unlock(); throw new AssertionError("Unlocked model lock accepted release"); }
        catch (IllegalMonitorStateException expected) { }
    }
    @JSExport public static boolean invalidBooks() {
        org.audiveris.wasm.compat.platform.HeadlessArchives.requireNoLegacyTables(null);
        org.audiveris.wasm.compat.platform.HeadlessArchives.requireNoLegacyTables(Map.of());
        try {
            org.audiveris.wasm.compat.platform.HeadlessArchives.requireNoLegacyTables(Map.of("binary", new Object()));
            return false;
        } catch (UnsupportedOperationException expected) { }
        try { Book.createMemoryBook(null); return false; } catch (NullPointerException expected) { }
        try { Book.createMemoryBook("  "); return false; } catch (IllegalArgumentException expected) { }
        return Book.createMemoryBook(" trimmed ").getRadix().equals("trimmed");
    }
}
