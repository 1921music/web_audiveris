// SPDX-License-Identifier: AGPL-3.0-or-later
package org.audiveris.wasm.compat;

import org.teavm.extension.spi.substitution.SimpleSubstitutionPolicy;
import org.teavm.extension.spi.substitution.SubstitutionSink;

/** Compiler-side selection only; callers continue to reference standard Java APIs. */
public final class CompilerSubstitutions extends SimpleSubstitutionPolicy {
    @Override public void contribute(SubstitutionSink sink) {
        if (!Boolean.getBoolean("audiveris.compat.enabled")) { return; }
        sink.selectClasses(named("java.awt.Point").or(named("java.awt.Dimension")).or(named("java.awt.Color")))
                .replacePackage("java.awt", "org.audiveris.wasm.compat.awt");
        sink.selectClasses(named("jdk.internal.util.ByteArrayLittleEndian").or(named("jdk.internal.util.ByteArray")))
                .replacePackage("jdk.internal.util", "org.audiveris.wasm.compat.bytes");
        sink.selectClasses(named("java.util.UUID")).replacePackage("java.util", "org.audiveris.wasm.compat.util");
        sink.selectClasses(named("java.lang.Module")).replacePackage("java.lang", "org.audiveris.wasm.compat.lang");
        if (Boolean.getBoolean("audiveris.compat.headless")) {
            sink.selectClasses(named("java.util.concurrent.ConcurrentSkipListMap")
                    .or(named("java.util.concurrent.ConcurrentSkipListMap$1"))
                    .or(named("java.util.concurrent.ConcurrentSkipListMap$1$1"))
                    .or(named("java.util.concurrent.ConcurrentSkipListMap$Keys"))
                    .or(named("java.util.concurrent.ConcurrentSkipListMap$Keys$1")))
                    .replacePackage("java.util.concurrent", "org.audiveris.wasm.compat.concurrent");
            sink.selectClasses(named("java.util.concurrent.ConcurrentSkipListSet"))
                    .replacePackage("java.util.concurrent", "org.audiveris.wasm.compat.concurrent");
        }
    }
}
