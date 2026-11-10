package org.audiveris.wasm.compat.test;

public final class OcrExchangeOracle {
    public static void main(String[] args) throws Exception {
        String result = OcrExchangeProbe.contracts();
        var output = java.nio.file.Path.of(args[0]);
        java.nio.file.Files.createDirectories(output.getParent());
        java.nio.file.Files.writeString(output, result);
        System.out.println(result);
    }
}
