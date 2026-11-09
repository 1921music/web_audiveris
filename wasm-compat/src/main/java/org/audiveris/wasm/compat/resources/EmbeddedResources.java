// SPDX-License-Identifier: AGPL-3.0-or-later
package org.audiveris.wasm.compat.resources;

import java.io.*;
import java.net.*;
import java.util.*;

/** A per-Wasm-instance resource bundle, exposed through the standard classpath APIs. */
public final class EmbeddedResources {
    private static final Map<String, byte[]> DATA = new HashMap<>();
    private static final List<ClassLoader> OWNERS = new ArrayList<>();
    private static boolean initialized;
    private EmbeddedResources() { }
    private static synchronized void initialize() {
        if (initialized) { return; }
        BuiltinResources.load(DATA);
        URL.setURLStreamHandlerFactory(protocol -> protocol.equals("resource") ? new Handler() : null);
        initialized = true;
    }
    public static synchronized void register(String name, byte[] bytes) {
        Objects.requireNonNull(name); Objects.requireNonNull(bytes); initialize();
        DATA.put(name, bytes.clone());
    }
    private static boolean directory(String name) {
        String prefix = name.endsWith("/") ? name : name + "/";
        for (String key : DATA.keySet()) { if (key.startsWith(prefix)) { return true; } }
        return false;
    }
    public static InputStream getStream(ClassLoader loader, String name) {
        Objects.requireNonNull(name); initialize();
        byte[] data = DATA.get(name);
        if (data == null && directory(name)) { data = new byte[0]; }
        return data == null ? null : new ByteArrayInputStream(data);
    }
    public static InputStream systemStream(String name) { return getStream(ClassLoader.getSystemClassLoader(), name); }
    public static URL getResource(ClassLoader loader, String name) {
        Objects.requireNonNull(name); initialize();
        try (InputStream input = loader.getResourceAsStream(name)) {
            if (input == null && !directory(name)) { return null; }
            int owner;
            synchronized (OWNERS) {
                owner = OWNERS.indexOf(loader);
                if (owner < 0) { owner = OWNERS.size(); OWNERS.add(loader); }
            }
            String path = "/c" + owner + "/" + name;
            if (directory(name) && !path.endsWith("/")) { path += "/"; }
            return new URL(null, new URI("resource", null, path, null, null).toASCIIString(), new Handler());
        } catch (IOException | URISyntaxException failure) { throw new IllegalStateException(failure); }
    }
    public static URL systemResource(String name) { return getResource(ClassLoader.getSystemClassLoader(), name); }
    public static Enumeration<URL> getResources(ClassLoader loader, String name) {
        URL resource = loader.getResource(name);
        return Collections.enumeration(resource == null ? Collections.emptyList() : Collections.singletonList(resource));
    }
    public static Enumeration<URL> systemResources(String name) { return getResources(ClassLoader.getSystemClassLoader(), name); }
    private static String resolve(Class<?> owner, String name) {
        Objects.requireNonNull(name);
        if (name.startsWith("/")) { return name.substring(1); }
        while (owner.isArray()) { owner = owner.getComponentType(); }
        String className = owner.getName(); int dot = className.lastIndexOf('.');
        return dot < 0 ? name : className.substring(0, dot).replace('.', '/') + "/" + name;
    }
    public static URL classResource(Class<?> owner, String name) {
        ClassLoader loader = owner.getClassLoader();
        return getResource(loader == null ? ClassLoader.getSystemClassLoader() : loader, resolve(owner, name));
    }
    public static InputStream classStream(Class<?> owner, String name) {
        ClassLoader loader = owner.getClassLoader();
        return (loader == null ? ClassLoader.getSystemClassLoader() : loader).getResourceAsStream(resolve(owner, name));
    }
    public static Class<?> loadClass(ClassLoader loader, String name) throws ClassNotFoundException {
        return Class.forName(name, false, loader);
    }
    private static final class Handler extends URLStreamHandler {
        @Override protected URLConnection openConnection(URL url) {
            return new URLConnection(url) {
                byte[] content;
                @Override public void connect() throws IOException {
                    if (connected) { return; }
                    try {
                        String path = url.toURI().getPath(); int separator = path.indexOf('/', 2);
                        if (!path.startsWith("/c") || separator < 0) { throw new FileNotFoundException(url.toString()); }
                        int id = Integer.parseInt(path.substring(2, separator));
                        ClassLoader loader;
                        synchronized (OWNERS) { loader = OWNERS.get(id); }
                        String name = path.substring(separator + 1);
                        try (InputStream input = loader.getResourceAsStream(name)) {
                            if (input == null) {
                                if (!directory(name)) { throw new FileNotFoundException(name); }
                                content = new byte[0];
                            } else { content = input.readAllBytes(); }
                        }
                        connected = true;
                    } catch (URISyntaxException | IndexOutOfBoundsException | NumberFormatException malformed) {
                        throw new IOException("Invalid resource URL", malformed);
                    }
                }
                @Override public InputStream getInputStream() throws IOException { connect(); return new ByteArrayInputStream(content); }
                @Override public int getContentLength() { try { connect(); return content.length; } catch (IOException failure) { return -1; } }
                @Override public long getContentLengthLong() { return getContentLength(); }
                @Override public String getHeaderField(String name) {
                    return name != null && name.equalsIgnoreCase("Content-Length") && getContentLength() >= 0 ? Integer.toString(content.length) : null;
                }
            };
        }
    }
}
