// SPDX-License-Identifier: AGPL-3.0-or-later
package org.audiveris.wasm.compat;

import org.teavm.model.*;
import org.teavm.model.instructions.*;

/** Missing-only delegation; the original class library remains responsible for existing APIs. */
final class StandardApiPatches {
    private static ValueType object(String name) { return ValueType.object(name); }
    static void apply(ClassHolder cls) {
        ValueType string = object("java.lang.String"), type = object("java.lang.Class");
        switch (cls.getName()) {
            case "java.lang.Math":
                delegate(cls, "rint", "rint", RoundingMath.class.getName(), true, true, ValueType.DOUBLE, ValueType.DOUBLE);
                delegate(cls, "round", "round", RoundingMath.class.getName(), true, true, ValueType.DOUBLE, ValueType.LONG);
                delegate(cls, "round", "round", RoundingMath.class.getName(), true, true, ValueType.FLOAT, ValueType.INTEGER); break;
            case "java.lang.Double":
                delegate(cls, "parseDouble", "parseDouble", FloatingPointParser.class.getName(), true, true, string, ValueType.DOUBLE); break;
            case "java.lang.Float":
                delegate(cls, "parseFloat", "parseFloat", FloatingPointParser.class.getName(), true, true, string, ValueType.FLOAT); break;
            case "java.util.Arrays":
                add(cls, "compare", "compareBytes", true, ValueType.arrayOf(ValueType.BYTE), ValueType.arrayOf(ValueType.BYTE), ValueType.INTEGER);
                add(cls, "compare", "compareComparables", true, ValueType.arrayOf(object("java.lang.Comparable")), ValueType.arrayOf(object("java.lang.Comparable")), ValueType.INTEGER); break;
            case "java.lang.Integer":
                add(cls, "parseUnsignedInt", "parseUnsignedInt", true, string, ValueType.INTEGER); break;
            case "java.nio.charset.Charset":
                add(cls, "isSupported", "charsetSupported", true, string, ValueType.BOOLEAN); break;
            case "java.lang.Class":
                add(cls, "forName", "moduleClass", true, object("java.lang.Module"), string, type);
                delegate(cls, "getModule", "forClass", "java.lang.Module", false, false, object("java.lang.Module"));
                add(cls, "getPackageName", "packageName", false, string);
                add(cls, "descriptorString", "descriptorString", false, string);
                add(cls, "componentType", "componentType", false, type);
                add(cls, "arrayType", "arrayType", false, type);
                add(cls, "forPrimitiveName", "forPrimitiveName", true, string, type); break;
            case "java.lang.ClassLoader":
                delegate(cls, "getUnnamedModule", "forLoader", "java.lang.Module", false, false, object("java.lang.Module")); break;
            case "java.lang.System$Logger":
                add(cls, "log", "systemLog", false, object("java.lang.System$Logger$Level"), string,
                        ValueType.arrayOf(object("java.lang.Object")), ValueType.VOID); break;
            case "java.lang.Package":
                add(cls, "getImplementationVersion", "unknownPackageMetadata", false, string);
                add(cls, "getImplementationVendor", "unknownPackageMetadata", false, string);
                add(cls, "getSpecificationTitle", "unknownPackageMetadata", false, string); break;
            case "java.util.ResourceBundle":
                add(cls, "getBundle", "moduleBundle", true, string, object("java.util.Locale"), object("java.lang.Module"), object("java.util.ResourceBundle")); break;
            case "java.util.Enumeration":
                add(cls, "asIterator", "asIterator", false, object("java.util.Iterator")); break;
            case "java.util.ServiceLoader":
                add(cls, "findFirst", "findFirst", false, object("java.util.Optional")); break;
            case "java.util.stream.Stream":
                add(cls, "ofNullable", "nullableStream", true, object("java.lang.Object"), object("java.util.stream.Stream")); break;
            case "java.util.Spliterators":
                add(cls, "emptySpliterator", "emptySpliterator", true, object("java.util.Spliterator")); break;
            case "java.util.Collections":
                delegate(cls, "emptySortedMap", "emptySortedMap", SortedMaps.class.getName(), true, false, object("java.util.SortedMap"));
                delegate(cls, "unmodifiableSortedMap", "unmodifiableSortedMap", SortedMaps.class.getName(), true, false, object("java.util.SortedMap"), object("java.util.SortedMap"));
                add(cls, "singletonSpliterator", "singletonSpliterator", true, object("java.lang.Object"), object("java.util.Spliterator")); break;
            case "java.lang.reflect.Method":
                add(cls, "isDefault", "methodIsDefault", false, ValueType.BOOLEAN); break;
            case "java.util.Date":
                add(cls, "from", "dateFrom", true, object("java.time.Instant"), object("java.util.Date")); break;
            case "java.net.URLConnection":
                add(cls, "getContentLengthLong", "contentLengthLong", false, ValueType.LONG); break;
            case "java.util.concurrent.atomic.AtomicLong":
                add(cls, "compareAndExchange", "compareAndExchange", false, ValueType.LONG, ValueType.LONG, ValueType.LONG); break;
            case "java.lang.Thread":
                addThreadId(cls);
                add(cls, "isVirtual", "isVirtual", false, ValueType.BOOLEAN);
                cls.getMethod(new MethodDescriptor("isVirtual", ValueType.BOOLEAN)).getModifiers().add(ElementModifier.FINAL);
                add(cls, "dumpStack", "dumpStack", true, ValueType.VOID);
                add(cls, "onSpinWait", "onSpinWait", true, ValueType.VOID); break;
            default: break;
        }
    }

    private static void addThreadId(ClassHolder cls) {
        MethodDescriptor descriptor = new MethodDescriptor("threadId", ValueType.LONG);
        if (cls.getMethod(descriptor) != null) { return; }
        if (cls.getField("id") == null || !cls.getField("id").getType().equals(ValueType.LONG)) {
            throw new IllegalStateException("Review threadId patch for this TeaVM version: expected Thread.id");
        }
        MethodHolder method = new MethodHolder(descriptor); method.setLevel(AccessLevel.PUBLIC);
        method.getModifiers().add(ElementModifier.FINAL);
        Program program = new Program(); Variable self = program.createVariable(), result = program.createVariable();
        BasicBlock block = program.createBasicBlock();
        GetFieldInstruction get = new GetFieldInstruction(); get.setInstance(self); get.setReceiver(result);
        get.setField(new FieldReference(cls.getName(), "id")); get.setFieldType(ValueType.LONG); block.add(get);
        ExitInstruction exit = new ExitInstruction(); exit.setValueToReturn(result); block.add(exit);
        method.setProgram(program); cls.addMethod(method);
    }

    private static void add(ClassHolder cls, String name, String helper, boolean isStatic, ValueType... signature) {
        delegate(cls, name, helper, StandardApis.class.getName(), isStatic, false, signature);
    }
    static void delegate(ClassHolder cls, String name, String helper, String helperClass, boolean isStatic, boolean replace, ValueType... signature) {
        MethodDescriptor descriptor = new MethodDescriptor(name, signature);
        MethodHolder method = cls.getMethod(descriptor);
        if (method != null && !replace) { return; }
        boolean added = method == null;
        if (added) { method = new MethodHolder(descriptor); method.setLevel(AccessLevel.PUBLIC); }
        if (isStatic) { method.getModifiers().add(ElementModifier.STATIC); }
        // Interface methods with a body are Java default methods; deliberately omit ABSTRACT.
        Program program = new Program();
        var self = program.createVariable();
        var parameters = new Variable[descriptor.parameterCount() + (isStatic ? 0 : 1)];
        ValueType[] helperSignature = new ValueType[parameters.length + 1];
        int start = 0;
        if (!isStatic) { parameters[0] = self; helperSignature[0] = object(cls.getName()); start = 1; }
        for (int i = 0; i < descriptor.parameterCount(); i++) {
            parameters[i + start] = program.createVariable();
            helperSignature[i + start] = descriptor.parameterType(i);
        }
        helperSignature[parameters.length] = descriptor.getResultType();
        BasicBlock block = program.createBasicBlock();
        InvokeInstruction invoke = new InvokeInstruction(); invoke.setType(InvocationType.SPECIAL);
        invoke.setMethod(new MethodReference(helperClass, new MethodDescriptor(helper, helperSignature)));
        invoke.setArguments(parameters);
        Variable result = descriptor.getResultType() == ValueType.VOID ? null : program.createVariable();
        invoke.setReceiver(result); block.add(invoke);
        ExitInstruction exit = new ExitInstruction(); exit.setValueToReturn(result); block.add(exit);
        method.setProgram(program);
        if (added) { cls.addMethod(method); }
    }
}
