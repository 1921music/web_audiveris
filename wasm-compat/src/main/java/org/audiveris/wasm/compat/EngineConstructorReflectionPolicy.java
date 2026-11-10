// SPDX-License-Identifier: AGPL-3.0-or-later
package org.audiveris.wasm.compat;

import org.teavm.extension.spi.reflection.SimpleReflectionPolicy;

/** Only constructors required by the original filament/support factories; no classpath scan or method roots. */
public final class EngineConstructorReflectionPolicy extends SimpleReflectionPolicy {
    @Override protected void setup() {
        if (!Boolean.getBoolean("audiveris.compat.enabled") || !Boolean.getBoolean("audiveris.compat.headless")) { return; }
        for (String name : new String[]{"org.audiveris.omr.sheet.grid.StaffFilament", "org.audiveris.omr.glyph.dynamic.StraightFilament"}) {
            selectClasses(type -> type != null && name.equals(type.name()))
                    .reflectableMethods(withSignature(int.class).and(named("<init>")));
        }
        selectClasses(type -> type != null && "org.audiveris.omr.sig.relation.BeamBeamRelation".equals(type.name()))
                .reflectableMethods(withSignature(new Class<?>[0]).and(named("<init>")));
    }
}
