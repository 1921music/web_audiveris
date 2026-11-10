package org.audiveris.wasm.compat.test;

import ij.process.ByteProcessor;
import java.util.Map;
import org.audiveris.omr.sheet.*;
import org.audiveris.omr.step.*;
import org.audiveris.wasm.compat.platform.HeadlessConfiguration;
import org.teavm.jso.JSExport;
import org.teavm.jso.typedarrays.Int8Array;

public final class PixelSheetProbe {
    public static void main(String[] args) { }
    @JSExport public static void configure() { HeadlessConfiguration.configure(Map.of(), Map.of()); }
    @JSExport public static String scalePage(int width, int height, Int8Array binary, String id) {
        return scaleJava(width, height, binary.copyToJavaArray(), id);
    }
    public static String scaleJava(int width, int height, byte[] binary, String id) {
        Sheet sheet = create(width, height, binary, id);
        try { return "ok:" + new ScaleBuilder(sheet).retrieveScale() + ";invalid=" + !sheet.getStub().isValid(); }
        catch (StepException rejected) { return "rejected:" + rejected.getMessage() + ";invalid=" + !sheet.getStub().isValid(); }
    }
    static Sheet create(int width, int height, byte[] binary, String id) {
        ByteProcessor pixels = org.audiveris.wasm.compat.platform.GrayInput.takeOwnership(width, height, binary).takeProcessor();
        return create(null, pixels, id);
    }
    static Sheet create(ByteProcessor gray, ByteProcessor pixels, String id) {
        Book book = Book.createMemoryBook(id); SheetStub stub = new SheetStub(book, 1); book.addStub(stub);
        int width = pixels.getWidth(), height = pixels.getHeight();
        Sheet sheet = stub.createMemorySheet(gray, pixels);
        if (sheet.getWidth() != width || sheet.getHeight() != height || !stub.isDone(OmrStep.LOAD) || !stub.isDone(OmrStep.BINARY)) {
            throw new AssertionError("Pixel sheet initialization");
        }
        if (sheet.getPicture().getSource(Picture.SourceKey.BINARY) != pixels) { throw new AssertionError("Unexpected binary copy"); }
        if (stub.getSheet() != sheet) { throw new AssertionError("Sheet not installed in stub"); }
        return sheet;
    }
    @JSExport public static String fixture() { return scaleJava(160, 150, ScaleFixtures.staff(), "staff"); }
    @JSExport public static boolean validation() {
        Book book = Book.createMemoryBook("validation"); SheetStub stub = new SheetStub(book, 1); book.addStub(stub);
        try { stub.createMemorySheet(new ByteProcessor(2, 2), new ByteProcessor(1, 3)); return false; }
        catch (IllegalArgumentException expected) { }
        try { stub.createMemorySheet(null, new ByteProcessor(1, 1, new byte[]{127}, null)); return false; }
        catch (IllegalArgumentException expected) { }
        if (stub.hasSheet() || stub.isDone(OmrStep.LOAD)) { return false; }
        Sheet sheet = stub.createMemorySheet(null, new ByteProcessor(1, 1, new byte[]{0}, null));
        try { stub.createMemorySheet(null, new ByteProcessor(1, 1)); return false; }
        catch (IllegalStateException expected) { }
        try { sheet.getPicture().getSource(Picture.SourceKey.NO_STAFF); return false; }
        catch (UnsupportedOperationException expected) { }
        return stub.getSheet() == sheet && sheet.getPicture().getVerticalTable(Picture.TableKey.BINARY).getBuffer().get(0) == 0;
    }
}
