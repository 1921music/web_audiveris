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

`GrayThinRaster.java` calls the original Java `sun.java2d.loops.ProcessPath` and adapts
the white line-stepping loop and endpoint clipping from the same tag's native sources:

- `src/java.desktop/share/native/libawt/java2d/loops/DrawLine.c`
- `src/java.desktop/share/native/libawt/java2d/loops/LoopMacros.h`
- `src/java.desktop/share/native/libawt/java2d/loops/ProcessPath.c`

Copyright (c) 2000, 2001, Oracle and/or its affiliates. All rights reserved.
Copyright (c) 2000, 2016, Oracle and/or its affiliates. All rights reserved.
Copyright (c) 2005, 2018, Oracle and/or its affiliates. All rights reserved.
Their license/copyright notices are retained in the adaptation. Native endpoint
clipping is performed before integer line stepping; the packed sink writes only
opaque white pixels. Native memory and general color/composite handling are absent.
