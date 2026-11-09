// SPDX-License-Identifier: AGPL-3.0-or-later
package org.audiveris.wasm.compat;

import org.teavm.model.*;
import org.teavm.model.instructions.*;

final class PropertyApiPatches {
    static void apply(ClassHolder cls) {
        ValueType properties = ValueType.object("java.util.Properties"), input = ValueType.object("java.io.InputStream");
        if (cls.getName().equals("java.util.Properties")) {
            String xmlHelper = System.getProperty("audiveris.compat.xmlBackend", "kxml2").equals("jdk")
                    ? PropertyApis.class.getName() : KxmlProperties.class.getName();
            StandardApiPatches.delegate(cls, "loadFromXML", "loadXml", xmlHelper, false, false, input, ValueType.VOID);
            StandardApiPatches.delegate(cls, "store", "storeWriter", PropertyApis.class.getName(), false, false,
                    ValueType.object("java.io.Writer"), ValueType.object("java.lang.String"), ValueType.VOID);
            // TeaVM 0.16 masks Reader characters to 8 bits in its shared text loader.
            // InputStream characters already fit in 8 bits; widening this mask preserves both APIs.
            for (MethodHolder candidate : cls.getMethods()) {
                if (!candidate.getName().equals("load") || candidate.getDescriptor().parameterCount() != 1
                        || !candidate.getDescriptor().parameterType(0).toString().contains("StreamOrReader")) { continue; }
                Program body = candidate.getProgram();
                for (int i = 0; i < body.basicBlockCount(); i++) {
                    for (Instruction instruction : body.basicBlockAt(i)) {
                        if (instruction instanceof IntegerConstantInstruction) {
                            IntegerConstantInstruction constant = (IntegerConstantInstruction) instruction;
                            if (constant.getConstant() == 255) { constant.setConstant(65535); }
                        }
                    }
                }
            }
        }
        if (!cls.getName().equals(PropertyApis.class.getName())) { return; }
        MethodHolder method = cls.getMethod(new MethodDescriptor("loadXml", properties, input, ValueType.VOID));
        Program program = new Program(); program.createVariable();
        Variable props = program.createVariable(), stream = program.createVariable(), handler = program.createVariable();
        BasicBlock block = program.createBasicBlock();
        InvokeInstruction require = new InvokeInstruction(); require.setType(InvocationType.SPECIAL);
        require.setMethod(new MethodReference("java.util.Objects", "requireNonNull", ValueType.object("java.lang.Object"), ValueType.object("java.lang.Object")));
        require.setArguments(stream); block.add(require);
        String name = "jdk.internal.util.xml.PropertiesDefaultHandler";
        ConstructInstruction construct = new ConstructInstruction(); construct.setType(name); construct.setReceiver(handler); block.add(construct);
        InvokeInstruction init = new InvokeInstruction(); init.setType(InvocationType.SPECIAL);
        init.setMethod(new MethodReference(name, "<init>", ValueType.VOID)); init.setInstance(handler); block.add(init);
        InvokeInstruction load = new InvokeInstruction(); load.setType(InvocationType.SPECIAL);
        load.setMethod(new MethodReference(name, "load", properties, input, ValueType.VOID)); load.setInstance(handler);
        load.setArguments(props, stream); block.add(load);
        InvokeInstruction close = new InvokeInstruction(); close.setType(InvocationType.VIRTUAL);
        close.setMethod(new MethodReference("java.io.InputStream", "close", ValueType.VOID)); close.setInstance(stream); block.add(close);
        block.add(new ExitInstruction()); method.setProgram(program);
    }
}
