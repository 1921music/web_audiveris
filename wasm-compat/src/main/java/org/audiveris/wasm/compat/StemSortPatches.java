// SPDX-License-Identifier: AGPL-3.0-or-later
package org.audiveris.wasm.compat;

import org.teavm.model.*;
import org.teavm.model.instructions.*;
import org.audiveris.wasm.compat.platform.StemItemSort;

/** Use the unchanged JDK TimSort class at one algorithm-sensitive call site. */
final class StemSortPatches {
    private static final ValueType ARRAY = ValueType.arrayOf(ValueType.object("java.lang.Object"));
    private static final ValueType COMPARATOR = ValueType.object("java.util.Comparator");
    static void apply(ClassHolder cls) {
        if (cls.getName().equals(StemItemSort.class.getName())) {
            MethodHolder method = cls.getMethod(new MethodDescriptor("sortArray", ARRAY, ValueType.INTEGER, COMPARATOR, ValueType.VOID));
            if (method == null) { throw new IllegalStateException("Review pinned stem sort bridge"); }
            Program program = new Program(); program.createVariable();
            Variable values = program.createVariable(), length = program.createVariable(), comparator = program.createVariable();
            Variable zero = program.createVariable(), nil = program.createVariable();
            BasicBlock block = program.createBasicBlock();
            IntegerConstantInstruction number = new IntegerConstantInstruction(); number.setConstant(0); number.setReceiver(zero); block.add(number);
            NullConstantInstruction nothing = new NullConstantInstruction(); nothing.setReceiver(nil); block.add(nothing);
            InvokeInstruction sort = new InvokeInstruction(); sort.setType(InvocationType.SPECIAL);
            sort.setMethod(new MethodReference("java.util.TimSort", "sort", ARRAY, ValueType.INTEGER, ValueType.INTEGER,
                    COMPARATOR, ARRAY, ValueType.INTEGER, ValueType.INTEGER, ValueType.VOID));
            sort.setArguments(values, zero, length, comparator, nil, zero, zero); block.add(sort);
            block.add(new ExitInstruction()); method.setProgram(program);
        } else if (cls.getName().equals("org.audiveris.omr.sheet.stem.StemBuilder")) {
            MethodHolder method = cls.getMethod(new MethodDescriptor("sortItems", ValueType.object("java.util.List"), ValueType.VOID));
            if (method == null || method.getProgram() == null) { throw new IllegalStateException("Review stem item sorting"); }
            MethodReference original = new MethodReference("java.util.Collections", "sort", ValueType.object("java.util.List"), COMPARATOR, ValueType.VOID);
            int calls = 0, before = 0;
            for (BasicBlock block : method.getProgram().getBasicBlocks()) { for (Instruction instruction : block) {
                before++;
                if (instruction instanceof InvokeInstruction call && call.getMethod().equals(original)) {
                    call.setMethod(new MethodReference(StemItemSort.class.getName(), original.getDescriptor())); calls++;
                }
            } }
            if (calls != 1) { throw new IllegalStateException("Review mixed-comparator sort sites: " + calls); }
            HeadlessPatches.report(method, "pinned-jdk-timsort-for-mixed-stem-item-comparator", method.getProgram().basicBlockCount(), before);
        }
    }
}
