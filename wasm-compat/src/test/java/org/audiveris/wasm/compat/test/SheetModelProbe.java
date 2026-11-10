package org.audiveris.wasm.compat.test;

import java.nio.file.Path;
import java.util.Map;
import org.audiveris.omr.sheet.Book;
import org.audiveris.omr.sheet.Sheet;
import org.audiveris.omr.sheet.SheetStub;
import org.audiveris.omr.run.RunTable;
import org.audiveris.wasm.compat.platform.HeadlessConfiguration;
import org.teavm.jso.JSExport;

/** Diagnose model construction before adding raster storage or recognition steps. */
public final class SheetModelProbe {
    public static void main(String[] args) { }
    @JSExport public static String construct() {
        HeadlessConfiguration.configure(Map.of(), Map.of());
        Book book = new Book(Path.of("/input/model.png"));
        SheetStub stub = new SheetStub(book, 1);
        book.addStub(stub);
        Sheet sheet = new Sheet(stub, (RunTable) null);
        if (sheet.getStaffManager() == null || sheet.getLagManager() == null
                || sheet.getGlyphIndex() == null || sheet.getInterIndex() == null) {
            throw new AssertionError("Incomplete sheet model");
        }
        return sheet.getId() + ";valid=" + stub.isValid();
    }
}
