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

`GrayAlphaRaster.java` calls the installed JDK 25 Marlin AA tile generator. The opt-in
`MarlinAlphaPatches` compiler profile retains its Java edge, coverage and tile algorithms,
replacing Marlin's `OffHeapArray`/`Unsafe` buffer operations with checked, module-local
`MarlinMemory` byte arrays. Rectangular `Region` construction omits JNI ID registration.
No OpenJDK source file or JAR is rewritten. The JDK code included by TeaVM remains under
GPL version 2 with the Classpath Exception; see the accompanying `LICENSE`.

Reference: https://github.com/openjdk/jdk/tree/jdk-25%2B36/src/java.desktop/share/classes/sun/java2d/marlin

The CURVES compiler profile links the installed JDK 25 pure Java
`java.lang.FdLibm$Hypot.compute` implementation for `Math.hypot`. This retains the JDK's
handling of mixed infinity/NaN, overflow, underflow and rounding; no reflection or JVM call
occurs in the Worker. OpenJDK bytecode is compiled into Wasm without rewriting its source or
JAR. Its GPL version 2 with Classpath Exception applies to the included code. The independent
native reference records the exact JDK runtime and Hypot class SHA-256 alongside test vectors.

`PortableTrig` similarly links the JDK `FdLibm.Sin`, `Cos`, `Atan` and `Atan2` methods,
including the original argument reduction helpers. These supply deterministic StrictMath
semantics instead of browser-dependent host math imports. Their original OpenJDK bytecode
remains unchanged; the same GPL version 2 with Classpath Exception applies. The native
reference records the six implementation class hashes and independent StrictMath vectors.

Reference: https://github.com/openjdk/jdk/blob/jdk-25%2B36/src/java.base/share/classes/java/lang/FdLibm.java
