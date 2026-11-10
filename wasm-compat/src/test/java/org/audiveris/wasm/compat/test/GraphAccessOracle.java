package org.audiveris.wasm.compat.test;

import java.nio.file.*;
import org.audiveris.wasm.compat.platform.GraphReflectionAccess;
import org.jgrapht.util.GraphAccessFixture;

public final class GraphAccessOracle {
    public static void main(String[] args) throws Exception {
        for (Class<?> type : new Class<?>[]{GraphSupplierCases.PublicNode.class, GraphSupplierCases.HiddenNode.class, GraphSupplierCases.hiddenType()}) {
            var constructor = type.getDeclaredConstructor();
            if (GraphReflectionAccess.canAccess(constructor, null) != GraphAccessFixture.query(constructor, null)) { throw new AssertionError("Access parity: " + type); }
            try { GraphReflectionAccess.canAccess(constructor, new Object()); throw new AssertionError("Constructor receiver accepted"); }
            catch (IllegalArgumentException expected) { }
            constructor.setAccessible(true);
            if (!GraphReflectionAccess.canAccess(constructor, null) || !GraphAccessFixture.query(constructor, null)) { throw new AssertionError("Explicit access override"); }
        }
        try { GraphReflectionAccess.canAccess(GraphAccessFixture.ownType().getDeclaredConstructor(), null); throw new AssertionError("Unmodelled package/nest accepted"); }
        catch (UnsupportedOperationException expected) { }
        Path output = Path.of(args[0]); Files.createDirectories(output.getParent()); Files.writeString(output, GraphSupplierCases.run());
        System.out.println("JGraphT supplier constructor access, explicit override and missing-constructor behavior verified");
    }
}
