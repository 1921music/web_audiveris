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
    }
}
