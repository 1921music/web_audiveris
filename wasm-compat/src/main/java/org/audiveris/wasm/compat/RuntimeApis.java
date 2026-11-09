// SPDX-License-Identifier: AGPL-3.0-or-later
package org.audiveris.wasm.compat;

import java.util.Objects;
import java.util.Spliterator;
import java.util.Spliterators;
import java.util.function.Consumer;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

/** Small standard-API gaps; external I/O and environment policy are not emulated here. */
public final class RuntimeApis {
    private RuntimeApis() { }
    public static java.nio.file.Path fileToPath(java.io.File file) { return java.nio.file.Paths.get(file.getPath()); }
    /** Lexical normalization for TeaVM's default (slash-separated) filesystem, with no I/O. */
    public static java.nio.file.Path normalizeDefaultPath(java.nio.file.Path path) {
        java.util.ArrayList<String> segments = new java.util.ArrayList<>();
        boolean absolute = path.isAbsolute();
        for (java.nio.file.Path part : path) {
            String name = part.toString();
            if (name.isEmpty() || name.equals(".")) { continue; }
            if (name.equals("..")) {
                if (!segments.isEmpty() && !segments.get(segments.size() - 1).equals("..")) {
                    segments.remove(segments.size() - 1);
                } else if (!absolute) { segments.add(name); }
            } else { segments.add(name); }
        }
        StringBuilder normalized = new StringBuilder();
        if (path.getRoot() != null) { normalized.append(path.getRoot()); }
        String separator = path.getFileSystem().getSeparator();
        boolean needsSeparator = normalized.length() > 0 && !normalized.toString().endsWith(separator);
        for (String segment : segments) {
            if (needsSeparator) { normalized.append(separator); }
            normalized.append(segment);
            needsSeparator = true;
        }
        String result = normalized.toString();
        return result.equals(path.toString()) ? path : path.getFileSystem().getPath(result);
    }
    public static void stringGetBytes(String value, int begin, int end, byte[] destination, int offset) {
        if (begin < 0 || end > value.length() || begin > end) { throw new StringIndexOutOfBoundsException(); }
        int length = end - begin;
        Objects.checkFromIndexSize(offset, length, destination.length);
        for (int i = 0; i < length; i++) { destination[offset + i] = (byte) value.charAt(begin + i); }
    }
    public static char[] highByteChars(byte[] bytes, int highByte) { return highByteChars(bytes, highByte, 0, bytes.length); }
    public static char[] highByteChars(byte[] bytes, int highByte, int offset, int length) {
        Objects.checkFromIndexSize(offset, length, bytes.length);
        char[] chars = new char[length];
        for (int i = 0; i < length; i++) { chars[i] = (char) (highByte << 8 | bytes[offset + i] & 255); }
        return chars;
    }
    public static Stream<String> stringLines(String value) {
        return StreamSupport.stream(new Spliterators.AbstractSpliterator<String>((long) value.length() + 1,
                Spliterator.ORDERED | Spliterator.IMMUTABLE | Spliterator.NONNULL) {
            int offset;
            @Override public boolean tryAdvance(Consumer<? super String> action) {
                Objects.requireNonNull(action);
                if (offset == value.length()) { return false; }
                int start = offset;
                while (offset < value.length() && value.charAt(offset) != '\n' && value.charAt(offset) != '\r') { offset++; }
                String line = value.substring(start, offset);
                if (offset < value.length()) {
                    char delimiter = value.charAt(offset++);
                    if (delimiter == '\r' && offset < value.length() && value.charAt(offset) == '\n') { offset++; }
                }
                action.accept(line);
                return true;
            }
        }, false);
    }
}
