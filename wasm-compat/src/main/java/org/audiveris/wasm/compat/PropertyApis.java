// SPDX-License-Identifier: AGPL-3.0-or-later
package org.audiveris.wasm.compat;

import java.io.*;
import java.util.*;

public final class PropertyApis {
    private PropertyApis() { }
    /** On Wasm the compiler binds this to the unchanged JDK's pure-Java XML properties handler. */
    public static void loadXml(Properties properties, InputStream input) throws IOException { properties.loadFromXML(input); }

    public static void storeWriter(Properties properties, Writer destination, String comments) throws IOException {
        BufferedWriter writer = destination instanceof BufferedWriter ? (BufferedWriter) destination : new BufferedWriter(destination);
        if (comments != null) {
            writer.write('#');
            for (int i = 0; i < comments.length(); i++) {
                char c = comments.charAt(i);
                if (c > 255) { unicode(writer, c); }
                else if (c == '\n' || c == '\r') {
                    if (c == '\r' && i + 1 < comments.length() && comments.charAt(i + 1) == '\n') { i++; }
                    writer.newLine();
                    if (i + 1 == comments.length() || comments.charAt(i + 1) != '#' && comments.charAt(i + 1) != '!') { writer.write('#'); }
                } else { writer.write(c); }
            }
            writer.newLine();
        }
        writer.write('#'); writer.write(new Date().toString()); writer.newLine();
        synchronized (properties) {
            for (Map.Entry<Object, Object> entry : properties.entrySet()) {
                escaped(writer, (String) entry.getKey(), true);
                writer.write('='); escaped(writer, (String) entry.getValue(), false); writer.newLine();
            }
        }
        writer.flush();
    }
    private static void unicode(Writer writer, char value) throws IOException {
        writer.write("\\u");
        for (int shift = 12; shift >= 0; shift -= 4) { writer.write("0123456789ABCDEF".charAt(value >>> shift & 15)); }
    }
    private static void escaped(Writer writer, String value, boolean key) throws IOException {
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '\t': writer.write("\\t"); break;
                case '\n': writer.write("\\n"); break;
                case '\r': writer.write("\\r"); break;
                case '\f': writer.write("\\f"); break;
                case ' ': if (key || i == 0) { writer.write('\\'); } writer.write(c); break;
                case '\\': case '=': case ':': case '#': case '!': writer.write('\\'); writer.write(c); break;
                default: writer.write(c); break;
            }
        }
    }
}
