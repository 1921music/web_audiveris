// SPDX-License-Identifier: AGPL-3.0-or-later
package org.audiveris.wasm.compat;

import org.teavm.model.*;
import org.teavm.model.instructions.*;

/** Separate ImageJ's default desktop text font from otherwise pure pixel processors. */
final class ImageJPatches {
    static void apply(ClassHolder cls) {
        if (!cls.getName().equals("ij.process.ImageProcessor")) { return; }
        int defaults = 0;
        for (MethodHolder method : cls.getMethods()) {
            Program program = method.getProgram(); if (program == null) { continue; }
            int before = 0; boolean changed = false;
            java.util.List<GetFieldInstruction> reads = new java.util.ArrayList<>();
            for (BasicBlock block : program.getBasicBlocks()) { for (Instruction instruction : block) {
                before++; if (instruction instanceof GetFieldInstruction) { reads.add((GetFieldInstruction) instruction); }
            } }
            for (GetFieldInstruction read : reads) {
                if (read.getField().equals(new FieldReference("ij.IJ", "font12")) && method.getName().equals("<init>")) {
                    NullConstantInstruction nil = new NullConstantInstruction(); nil.setReceiver(read.getReceiver()); nil.setLocation(read.getLocation());
                    read.replace(nil); defaults++; changed = true;
                } else if (read.getField().equals(new FieldReference(cls.getName(), "font"))) {
                    Variable result = read.getReceiver(); read.setReceiver(program.createVariable());
                    InvokeInstruction check = new InvokeInstruction(); check.setType(InvocationType.SPECIAL);
                    check.setMethod(new MethodReference("org.audiveris.wasm.compat.platform.HeadlessImageJ", "requireFont", ValueType.object("java.awt.Font"), ValueType.object("java.awt.Font")));
                    check.setArguments(read.getReceiver()); check.setReceiver(result); check.setLocation(read.getLocation()); read.insertNext(check); changed = true;
                }
            }
            if (changed) { HeadlessPatches.report(method, "imagej-pixel-constructor-without-desktop-font", program.basicBlockCount(), before); }
        }
        if (defaults != 1) { throw new IllegalStateException("Review pinned ImageJ default-font initialization"); }
    }
}
