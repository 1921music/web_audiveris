// SPDX-License-Identifier: AGPL-3.0-or-later
package org.audiveris.wasm.compat;

import org.teavm.backend.wasm.TeaVMWasmGCHost;
import org.teavm.backend.wasm.model.*;
import org.teavm.backend.wasm.model.instruction.*;
import org.teavm.model.*;
import org.teavm.vm.spi.TeaVMHost;

/** Correct ties-to-even, signed zero and infinities in one Wasm instruction. */
final class RoundingIntrinsic {
    private static final MethodReference RINT = new MethodReference(RoundingMath.class.getName(), "rint", ValueType.DOUBLE, ValueType.DOUBLE);
    static void install(TeaVMHost host) {
        TeaVMWasmGCHost backend = host.getExtension(TeaVMWasmGCHost.class);
        if (backend == null) { throw new IllegalStateException("Rounding compatibility requires Wasm GC"); }
        backend.contributeToCodeGen((context, registry) -> registry.bodyIntrinsics().registerIntrinsic(RINT, (method, function) -> {
            if (function.getType().getParameterTypes().size() != 1) { throw new IllegalStateException("Review rint calling convention"); }
            if (function.getLocalVariables().isEmpty()) {
                for (WasmType type : function.getType().getParameterTypes()) { function.add(new WasmLocal(type)); }
            }
            function.getBody().add(new WasmGetLocal(function.getLocalVariables().get(0)));
            function.getBody().add(new WasmFloatUnary(WasmFloatType.FLOAT64, WasmFloatUnaryOperation.NEAREST));
        }));
        host.add((cls, context) -> {
            if (!cls.getName().equals(RINT.getClassName())) { return; }
            MethodHolder method = cls.getMethod(RINT.getDescriptor());
            if (method == null) { throw new IllegalStateException("Missing portable rint"); }
            method.setProgram(null); method.getModifiers().add(ElementModifier.NATIVE);
        });
    }
}
