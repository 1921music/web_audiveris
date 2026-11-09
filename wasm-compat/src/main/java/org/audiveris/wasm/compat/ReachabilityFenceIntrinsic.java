// SPDX-License-Identifier: AGPL-3.0-or-later
package org.audiveris.wasm.compat;

import org.teavm.backend.wasm.TeaVMWasmGCHost;
import org.teavm.backend.wasm.model.*;
import org.teavm.backend.wasm.model.instruction.*;
import org.teavm.model.*;
import org.teavm.vm.spi.TeaVMHost;

/** A GC fence must keep the argument live at the call, not become an empty Java method. */
final class ReachabilityFenceIntrinsic {
    private static final MethodReference FENCE = new MethodReference("java.lang.ref.Reference", "reachabilityFence",
            ValueType.object("java.lang.Object"), ValueType.VOID);
    static void install(TeaVMHost host) {
        TeaVMWasmGCHost backend = host.getExtension(TeaVMWasmGCHost.class);
        if (backend == null) { throw new IllegalStateException("Compatibility GC fence requires Wasm GC"); }
        backend.contributeToCodeGen((context, registry) -> {
            WasmGlobal[] root = {null};
            registry.bodyIntrinsics().registerIntrinsic(FENCE, (method, function) -> {
                if (root[0] == null) {
                    root[0] = new WasmGlobal("audiveris_reachability_fence", WasmType.ANY.asNullable());
                    root[0].getInitialValue().add(new WasmNullConstant(WasmType.ANY.asNullable()));
                    if (Boolean.getBoolean("audiveris.compat.probeGC")) { root[0].setExportName("audiveris_probe_fence_root"); }
                    context.module().globals.add(root[0]);
                }
                if (function.getLocalVariables().isEmpty()) {
                    for (WasmType type : function.getType().getParameterTypes()) { function.add(new WasmLocal(type)); }
                }
                function.getBody().add(new WasmGetLocal(function.getLocalVariables().get(0)));
                function.getBody().add(new WasmSetGlobal(root[0]));
                function.getBody().add(new WasmNullConstant(WasmType.ANY.asNullable()));
                function.getBody().add(new WasmSetGlobal(root[0]));
            });
        });
        host.add((cls, context) -> {
            if (!cls.getName().equals(FENCE.getClassName()) || cls.getMethod(FENCE.getDescriptor()) != null) { return; }
            MethodHolder method = new MethodHolder(FENCE.getDescriptor()); method.setLevel(AccessLevel.PUBLIC);
            method.getModifiers().add(ElementModifier.STATIC); method.getModifiers().add(ElementModifier.NATIVE);
            cls.addMethod(method);
        });
    }
}
