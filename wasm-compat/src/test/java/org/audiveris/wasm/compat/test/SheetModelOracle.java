package org.audiveris.wasm.compat.test;

import java.nio.file.*;
import org.audiveris.omr.sheet.Book;

public final class SheetModelOracle {
    public static void main(String[] args) throws Exception {
        org.audiveris.omr.CLI cli = new org.audiveris.omr.CLI("Audiveris"); cli.parseParameters(new String[0]);
        var cliField = org.audiveris.omr.Main.class.getDeclaredField("cli"); cliField.setAccessible(true); cliField.set(null, cli);
        Path output = Path.of(args[0]); Files.createDirectories(output.getParent());
        Path input = output.getParent().resolve("input"); Files.createDirectories(input);
        String current = SheetModelProbe.construct();
        Book original = new Book(input.resolve("model.png"));
        String expected = SheetModelProbe.model(original);
        if (!expected.equals(current) || !SheetModelProbe.invalidBooks()) { throw new AssertionError("Sheet model parity"); }
        Book memory = Book.createMemoryBook("model");
        if (memory.getInputPath() != null || !memory.getStubsToUpgrade().isEmpty()) { throw new AssertionError("File-backed memory book"); }
        Files.writeString(output, expected);
        System.out.println("Memory-book and original input-book model parity: " + expected);
    }
}
