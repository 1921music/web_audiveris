package org.audiveris.wasm.compat.test;

/** JVM-only serializer for the native immutable model. Reflection is not included in the Wasm entry. */
public final class OcrTransportOracle {
    public static void main(String[] args) throws Exception {
        String json = json(OcrTransportProbe.fixture());
        var output = java.nio.file.Path.of(args[0]); java.nio.file.Files.createDirectories(output.getParent());
        java.nio.file.Files.writeString(output, json);
        System.out.println("Native OCR model fixture: Unicode characters, optional metadata and line baseline");
    }
    static String json(Object value) throws Exception {
        if (value == null) { return "null"; }
        if (value instanceof String text) {
            StringBuilder out = new StringBuilder("\"");
            for (int i = 0; i < text.length(); i++) {
                char c = text.charAt(i);
                if (c == '"' || c == '\\') { out.append('\\').append(c); }
                else if (c < 32) { String hex = Integer.toHexString(c); out.append("\\u").append("0000", 0, 4 - hex.length()).append(hex); }
                else { out.append(c); }
            }
            return out.append('"').toString();
        }
        if (value instanceof Number || value instanceof Boolean) { return value.toString(); }
        if (value instanceof java.util.List<?> values) {
            var out = new java.util.StringJoiner(",", "[", "]"); for (Object element : values) { out.add(json(element)); } return out.toString();
        }
        if (value.getClass().isRecord()) {
            var out = new java.util.StringJoiner(",", "{", "}");
            for (var component : value.getClass().getRecordComponents()) { out.add(json(component.getName()) + ":" + json(component.getAccessor().invoke(value))); }
            return out.toString();
        }
        throw new IllegalArgumentException("Unsupported fixture value");
    }
}
