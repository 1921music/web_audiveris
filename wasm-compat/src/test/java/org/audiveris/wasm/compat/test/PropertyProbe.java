package org.audiveris.wasm.compat.test;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

public final class PropertyProbe {
    private static final String HEADER = "<?xml version=\"1.0\" encoding=\"UTF-8\"?><!DOCTYPE properties SYSTEM \"http://java.sun.com/dtd/properties.dtd\">";
    @FunctionalInterface interface Loader { void load(Properties properties, InputStream input) throws IOException; }
    private static String xmlResult(String xml, java.nio.charset.Charset charset, Loader loader) {
        final boolean[] closed = {false};
        InputStream input = new ByteArrayInputStream(xml.getBytes(charset)) {
            @Override public void close() throws IOException { closed[0] = true; super.close(); }
        };
        Properties properties = new Properties(); properties.setProperty("existing", "preserved");
        String outcome;
        try {
            loader.load(properties, input);
            outcome = new TreeMap<>(properties).toString();
        } catch (Exception failure) { outcome = failure.getClass().getName(); }
        return outcome + "|closed=" + closed[0];
    }
    public static String values() {
        return values(Properties::loadFromXML);
    }
    public static String kxmlValues() { return values(org.audiveris.wasm.compat.KxmlProperties::loadXml); }
    private static String values(Loader loader) {
        StringBuilder result = new StringBuilder();
        for (String body : new String[] {
            "<properties/>",
            "<properties><comment>text</comment><entry key=\"eng\">English</entry><entry key=\"jpn\">\u65e5\u672c\u8a9e</entry></properties>",
            "<properties><!-- comment --><entry key=\"a&amp;b\">&lt;value&gt;&#x1F600;&#65;</entry></properties>",
            "<properties><entry key=\"key\">first</entry><entry key=\"key\"><![CDATA[second&<>]]></entry></properties>",
            "<properties><entry key=\"spaces\">  a\r\nb  </entry></properties>",
            "<properties><entry>missing key</entry></properties>",
            "<wrong/>", "<properties><entry key=\"x\">&unknown;</entry></properties>",
            "<properties><entry key=\"x\">unclosed</properties>", "<properties><bad/></properties>"
        }) { result.append(xmlResult(HEADER + body, StandardCharsets.UTF_8, loader)).append('\n'); }
        result.append(xmlResult("<properties/>", StandardCharsets.UTF_8, loader)).append('\n');
        result.append(xmlResult(HEADER.replace("http://java.sun.com/dtd/properties.dtd", "http://example.invalid/other.dtd") + "<properties/>", StandardCharsets.UTF_8, loader)).append('\n');
        result.append(xmlResult(HEADER.replace("UTF-8", "UTF-16") + "<properties><entry key=\"unicode\">\u697d\u8b5c</entry></properties>", StandardCharsets.UTF_16, loader)).append('\n');
        Properties properties = new Properties();
        properties.setProperty(" key:=#!\\\n", " \t\n\r\f\\:=#!\u697d\u8b5c\ud83d\ude00"); properties.setProperty("normal", "two spaces  remain");
        StringWriter text = new StringWriter();
        try {
            properties.store(text, "comment\u697d\u8b5c\r\n!next\nlast");
            String output = text.toString();
            Properties parsed = new Properties(); parsed.load(new StringReader(output));
            result.append(parsed.equals(properties)).append('|').append(output.contains("\u697d\u8b5c")).append('|');
            result.append(output.startsWith("#comment\\u697D\\u8B5C\n!next\n#last\n")).append('\n');
        } catch (IOException failure) { throw new AssertionError(failure); }
        return result.toString();
    }
}
