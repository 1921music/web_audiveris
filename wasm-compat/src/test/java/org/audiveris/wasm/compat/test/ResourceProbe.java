package org.audiveris.wasm.compat.test;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

public final class ResourceProbe {
    public static final class LookupFixture {
        public static int value() { return 42; }
        public int ordinary() { return 42; }
    }
    private static String read(URL url) throws Exception {
        try (InputStream input = url.openStream()) { return new String(input.readAllBytes(), StandardCharsets.UTF_8).trim(); }
    }
    public static String values() {
        try {
            StringBuilder result = new StringBuilder();
            ClassLoader loader = ResourceProbe.class.getClassLoader();
            String name = "org/audiveris/wasm/compat/test/resource.txt";
            result.append(read(loader.getResource(name))).append('\n');
            result.append(read(ResourceProbe.class.getResource("resource.txt"))).append('\n');
            result.append(read(ResourceProbe.class.getResource("/" + name))).append('\n');
            URL url = loader.getResource(name);
            result.append(read(url.toURI().toURL())).append('\n');
            try (InputStream input = loader.getResourceAsStream(name)) { result.append(input.read()).append('\n'); }
            result.append(loader.getResource("no-such-resource") == null).append('|');
            result.append(ResourceProbe.class.getResourceAsStream("missing") == null).append('\n');
            Enumeration<URL> resources = loader.getResources(name);
            int count = 0;
            while (resources.hasMoreElements()) { result.append(read(resources.nextElement())).append('\n'); count++; }
            result.append(count).append('\n');
            result.append(ClassLoader.getSystemResources("missing").hasMoreElements()).append('\n');
            result.append(loader.loadClass(LookupFixture.class.getName()).getName()).append('\n');
            try { loader.loadClass("no.such.Class"); result.append("wrong"); }
            catch (ClassNotFoundException expected) { result.append("missing class"); }
            result.append('\n');
            Properties languages = new Properties();
            try (InputStream input = loader.getResource("res/ISO639-3.xml").toURI().toURL().openStream()) { languages.loadFromXML(input); }
            result.append(languages.size()).append('|').append(languages.getProperty("eng")).append('|').append(languages.getProperty("jpn")).append('\n');
            return result.toString();
        } catch (Exception failure) { throw new AssertionError(failure); }
    }
}
