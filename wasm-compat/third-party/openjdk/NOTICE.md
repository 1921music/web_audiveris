`GraySpanRaster.java` adapts the span-conversion algorithm from OpenJDK's
`src/java.desktop/share/native/libawt/java2d/pipe/ShapeSpanIterator.c`, tag `jdk-25+36`:

https://github.com/openjdk/jdk/blob/jdk-25%2B36/src/java.desktop/share/native/libawt/java2d/pipe/ShapeSpanIterator.c

Copyright (c) 1998, 2025, Oracle and/or its affiliates. All rights reserved.
The original copyright and license header is retained in the Java adaptation.
The accompanying `LICENSE` contains GPL version 2 and the Classpath Exception.

Changes: JNI entry points and native memory management are replaced by Java-owned
segments and arrays; a packed grayscale image is the output. The adaptation keeps
the original float calculations, curve subdivision, winding rules, normalization
and integer overflow in span stepping. It explicitly rejects non-finite and
out-of-range input coordinates. It does not expose JNI or native iterator pointers.
