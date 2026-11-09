// SPDX-License-Identifier: AGPL-3.0-or-later
package org.audiveris.wasm.compat;

import org.teavm.extension.spi.reflection.SimpleReflectionPolicy;

/** Explicit metadata opt-in, separate from the original audit method roots. */
public final class CompilerReflectionPolicy extends SimpleReflectionPolicy {
    @Override protected void setup() {
        if (!Boolean.getBoolean("audiveris.compat.enabled")) { return; }
        for (String name : System.getProperty("audiveris.compat.reflectionClasses", "").split(",")) {
            if (!name.isBlank()) {
                selectClass(name.trim()).foundByName().reflectableMethods(method -> true);
                System.out.println("Compatibility reflection metadata: " + name.trim());
            }
        }
    }
}
