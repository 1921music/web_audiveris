// SPDX-License-Identifier: AGPL-3.0-or-later
package org.audiveris.wasm.compat;

import org.teavm.extension.spi.reflection.SimpleReflectionPolicy;

/** Supply only ConstantSet declared-field metadata; no classpath scanning or reflective methods. */
public final class ConstantSetReflectionPolicy extends SimpleReflectionPolicy {
    @Override protected void setup() {
        if (!Boolean.getBoolean("audiveris.compat.enabled") || !Boolean.getBoolean("audiveris.compat.headless")) { return; }
        var base = environment().findClass("org.audiveris.omr.constant.ConstantSet");
        selectClasses(cls -> cls != null && base != null && !cls.equals(base) && base.isAssignableFrom(cls))
                .reflectableFields(field -> true);
    }
}
