package org.audiveris.wasm.compat.test;

import org.jgrapht.util.SupplierUtil;

public final class GraphSupplierCases {
    public static final class PublicNode { public PublicNode() { } public int marker = 11; }
    public static final class HiddenNode { private HiddenNode() { } public int marker = 17; }
    public static final class MissingNoArg { public MissingNoArg(int value) { } }
    private static final class HiddenType { public HiddenType() { } public int marker = 23; }
    public static Class<?> hiddenType() { return HiddenType.class; }
    public static String run() {
        var supplier = SupplierUtil.createSupplier(PublicNode.class);
        PublicNode first = supplier.get(), second = supplier.get();
        if (first == second || first.marker != 11) { throw new AssertionError("Fresh constructor supplier"); }
        int hidden = SupplierUtil.createSupplier(HiddenNode.class).get().marker;
        int hiddenType = SupplierUtil.createSupplier(HiddenType.class).get().marker;
        String failure;
        try { SupplierUtil.createSupplier(MissingNoArg.class).get(); throw new AssertionError("Missing constructor accepted"); }
        catch (RuntimeException expected) { failure = expected.getClass().getName(); }
        return first.marker + ":" + hidden + ":" + hiddenType + ":" + failure;
    }
}
