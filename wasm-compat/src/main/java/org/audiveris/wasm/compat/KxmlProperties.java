// SPDX-License-Identifier: AGPL-3.0-or-later
package org.audiveris.wasm.compat;

import java.io.*;
import java.util.*;
import org.kxml2.io.KXmlParser;
import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;

/** Small synchronous XML properties adapter; no factory lookup, reflection or browser RPC. */
public final class KxmlProperties {
    private static final String DTD = "http://java.sun.com/dtd/properties.dtd";
    private KxmlProperties() { }
    public static void loadXml(Properties properties, InputStream input) throws IOException {
        Objects.requireNonNull(properties); Objects.requireNonNull(input);
        try {
            KXmlParser parser = new KXmlParser(); parser.setInput(input, null);
            boolean doctype = false, root = false, comment = false, entry = false;
            String key = null; StringBuilder value = new StringBuilder();
            for (int token = parser.nextToken(); token != XmlPullParser.END_DOCUMENT; token = parser.nextToken()) {
                switch (token) {
                    case XmlPullParser.DOCDECL:
                        validateDoctype(parser.getText()); doctype = true; break;
                    case XmlPullParser.START_TAG:
                        String name = parser.getName();
                        if (!root) {
                            if (!doctype || !name.equals("properties")) { throw invalid("Missing properties DOCTYPE/root"); }
                            root = true;
                        } else if (!name.equals("entry") && !name.equals("comment")) { throw invalid("Unexpected element: " + name); }
                        if (name.equals("entry")) {
                            key = parser.getAttributeValue(null, "key");
                            if (key == null) { throw invalid("Entry key is required"); }
                            entry = true;
                        } else if (name.equals("comment")) {
                            if (comment) { throw invalid("Only one comment element is allowed"); }
                            comment = true;
                        }
                        break;
                    case XmlPullParser.TEXT:
                    case XmlPullParser.CDSECT:
                    case XmlPullParser.ENTITY_REF:
                        // Like the reference JDK handler, unresolved entities contribute no characters.
                        if (entry && parser.getText() != null) { value.append(parser.getText()); }
                        break;
                    case XmlPullParser.END_TAG:
                        if (entry) { properties.setProperty(key, value.toString()); value.setLength(0); entry = false; }
                        break;
                    default: break;
                }
            }
            if (!root) { throw invalid("Missing properties root"); }
        } catch (XmlPullParserException malformed) { throw new InvalidPropertiesFormatException(malformed); }
        finally { input.close(); }
    }
    private static InvalidPropertiesFormatException invalid(String message) { return new InvalidPropertiesFormatException(message); }
    private static void validateDoctype(String text) throws InvalidPropertiesFormatException {
        List<String> tokens = new ArrayList<>();
        for (int i = 0; i < text.length();) {
            char c = text.charAt(i);
            if (c <= ' ') { i++; continue; }
            if (c == '[') { throw invalid("Internal DTD subsets are not allowed"); }
            if (c == '\'' || c == '"') {
                int end = text.indexOf(c, i + 1);
                if (end < 0) { throw invalid("Unclosed DTD string"); }
                tokens.add(text.substring(i + 1, end)); i = end + 1;
            } else {
                int end = i + 1;
                while (end < text.length() && text.charAt(end) > ' ' && text.charAt(end) != '[') { end++; }
                tokens.add(text.substring(i, end)); i = end;
            }
        }
        boolean system = tokens.size() == 3 && tokens.get(1).equals("SYSTEM") && tokens.get(2).equals(DTD);
        boolean publicId = tokens.size() == 4 && tokens.get(1).equals("PUBLIC") && tokens.get(3).equals(DTD);
        if (tokens.isEmpty() || !tokens.get(0).equals("properties") || !system && !publicId) { throw invalid("Unexpected properties DTD"); }
    }
}
