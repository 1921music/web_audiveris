package org.audiveris.wasm.compat.test;

import java.io.*;
import java.util.*;
import org.audiveris.wasm.compat.PropertyApis;

final class PropertyApiTest {
    static int run() throws Exception {
        int checks = 0; Random random = new Random(982);
        String original = PropertyProbe.values(), kxml = PropertyProbe.kxmlValues();
        if (!original.equals(kxml)) { throw new AssertionError("kxml2 XML comparison:\n" + kxml + "\nJDK:\n" + original); }
        checks++;
        for (int i = 0; i < 100; i++) {
            Properties properties = new Properties();
            for (int k = 0; k < 20; k++) {
                StringBuilder key = new StringBuilder(), value = new StringBuilder();
                for (int j = 0; j < 20; j++) { key.append((char) random.nextInt(65536)); value.append((char) random.nextInt(65536)); }
                properties.setProperty(key.toString(), value.toString());
            }
            StringWriter text = new StringWriter(); PropertyApis.storeWriter(properties, text, "comment\u697d\u8b5c\nnext");
            Properties parsed = new Properties(); parsed.load(new StringReader(text.toString()));
            if (!parsed.equals(properties)) { throw new AssertionError("Writer roundtrip"); }
            checks++;
            Properties xml = new Properties();
            for (int k = 0; k < 10; k++) {
                xml.setProperty("key" + k + "\ud83d\ude00", "\u697d\u8b5c<&>\"'\n\t " + random.nextLong() + new String(Character.toChars(0x10000 + random.nextInt(0x100000))));
            }
            for (var charset : new java.nio.charset.Charset[] {java.nio.charset.StandardCharsets.UTF_8,
                    java.nio.charset.StandardCharsets.UTF_16, java.nio.charset.StandardCharsets.ISO_8859_1}) {
                ByteArrayOutputStream bytes = new ByteArrayOutputStream(); xml.storeToXML(bytes, "comment", charset);
                Properties reference = new Properties(); reference.loadFromXML(new ByteArrayInputStream(bytes.toByteArray()));
                Properties actual = new Properties(); org.audiveris.wasm.compat.KxmlProperties.loadXml(actual, new ByteArrayInputStream(bytes.toByteArray()));
                if (!actual.equals(reference)) { throw new AssertionError("kxml2 encoding/attribute/entity roundtrip " + charset); }
                checks++;
            }
        }
        class WriterProbe extends StringWriter {
            boolean flushed, closed;
            @Override public void flush() { flushed = true; }
            @Override public void close() { closed = true; }
        }
        WriterProbe writer = new WriterProbe(); PropertyApis.storeWriter(new Properties(), writer, null);
        if (!writer.flushed || writer.closed) { throw new AssertionError("Writer ownership"); } checks++;
        return checks;
    }
}
