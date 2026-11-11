// SPDX-License-Identifier: AGPL-3.0-or-later
package org.audiveris.wasm.compat;

import org.teavm.model.*;
import org.teavm.model.instructions.ExitInstruction;

/** Opt-in, memory-only font component: no native mapped-file cleaner is available. */
final class FontMetricsPatches {
    static void apply(ClassHolder cls) {
        if (cls.getName().equals("org.apache.commons.logging.LogFactory")) {
            var initializer = cls.getMethod(new MethodDescriptor("<clinit>", ValueType.VOID));
            if (initializer == null) { throw new IllegalStateException("Review pinned Commons Logging initialization"); }
            Program program = new Program(); program.createVariable(); program.createBasicBlock().add(new ExitInstruction()); initializer.setProgram(program);
            for (String parameter : new String[]{"java.lang.String", "java.lang.Class"}) {
                ValueType type = ValueType.object(parameter), log = ValueType.object("org.apache.commons.logging.Log");
                if (cls.getMethod(new MethodDescriptor("getLog", type, log)) == null) {
                    throw new IllegalStateException("Review pinned Commons Logging factory");
                }
                StandardApiPatches.delegate(cls, "getLog", "logger",
                        "org.audiveris.wasm.compat.platform.FontMetricsRuntime", true, true, type, log);
            }
            return;
        }
        if (!cls.getName().equals("org.apache.pdfbox.io.IOUtils")) { return; }
        ValueType consumer = ValueType.object("java.util.function.Consumer");
        if (cls.getMethod(new MethodDescriptor("unmapper", consumer)) == null) {
            throw new IllegalStateException("Review pinned PDFBox mapped-file cleaner");
        }
        StandardApiPatches.delegate(cls, "unmapper", "unmapper",
                "org.audiveris.wasm.compat.platform.FontMetricsRuntime", true, true, consumer);
    }
}
