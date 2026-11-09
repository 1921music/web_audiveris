package org.audiveris.wasm.compat.test;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.Properties;
import org.teavm.jso.JSExport;

/** XML component measurement only; this is not a recognition-engine throughput benchmark. */
public final class XmlBenchmark {
    private static byte[][] documents;
    public static void main(String[] args) { }
    private static byte[][] documents() throws IOException {
        if (documents == null) {
            byte[] language;
            try (InputStream input = XmlBenchmark.class.getClassLoader().getResourceAsStream("res/ISO639-3.xml")) { language = input.readAllBytes(); }
            StringBuilder large = new StringBuilder("<?xml version=\"1.0\" encoding=\"UTF-8\"?><!DOCTYPE properties SYSTEM \"http://java.sun.com/dtd/properties.dtd\"><properties>");
            for (int i = 0; i < 2000; i++) { large.append("<entry key=\"k").append(i).append("\">value &amp; \u697d\u8b5c &#x1F600; ").append(i).append("</entry>"); }
            large.append("</properties>");
            documents = new byte[][] {language, large.toString().getBytes(StandardCharsets.UTF_8)};
        }
        return documents;
    }
    @JSExport public static int documentBytes(int document) {
        try { return documents()[document].length; } catch (IOException failure) { throw new AssertionError(failure); }
    }
    @JSExport public static int parse(int iterations, int document) {
        try {
            byte[] bytes = documents()[document]; int checksum = 0;
            for (int i = 0; i < iterations; i++) {
                Properties properties = new Properties(); properties.loadFromXML(new ByteArrayInputStream(bytes));
                checksum += properties.size();
                String value = properties.getProperty(document == 0 ? "eng" : "k1999");
                checksum += value.hashCode();
            }
            return checksum;
        } catch (IOException failure) { throw new AssertionError(failure); }
    }
}
