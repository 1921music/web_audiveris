package org.audiveris.wasm.compat.test;

import org.audiveris.wasm.compat.lang.Modules;
import java.util.function.Supplier;

final class ModuleApiTest {
    private static String outcome(Supplier<?> operation) {
        try { return String.valueOf(operation.get()); } catch (RuntimeException error) { return error.getClass().getName(); }
    }
    static int run() throws Exception {
        int checks = 0;
        var actual = Modules.forClass(ModuleApiTest.class);
        var expected = ModuleApiTest.class.getModule();
        if (actual.isNamed() != expected.isNamed() || actual.getName() != expected.getName()
                || actual.getDescriptor() != expected.getDescriptor() || actual.getLayer() != expected.getLayer()
                || actual.getClassLoader() != expected.getClassLoader()) { throw new AssertionError("unnamed module identity/metadata"); }
        checks++;
        if (actual != Modules.forLoader(ModuleApiTest.class.getClassLoader())) { throw new AssertionError("module identity cache"); } checks++;
        for (String name : new String[] {"", "missing.package", "java.lang", null}) {
            if (!outcome(() -> actual.isOpen(name)).equals(outcome(() -> expected.isOpen(name)))
                    || !outcome(() -> actual.isExported(name)).equals(outcome(() -> expected.isExported(name)))) { throw new AssertionError("unnamed module openness"); }
            checks++;
            if (!outcome(() -> actual.isOpen(name, actual)).equals(outcome(() -> expected.isOpen(name, expected)))) { throw new AssertionError("qualified openness"); } checks++;
        }
        for (Class<?> service : new Class<?>[] {Runnable.class, String.class, int.class, null}) {
            if (!outcome(() -> actual.canUse(service)).equals(outcome(() -> expected.canUse(service)))) { throw new AssertionError("unnamed module services"); }
            checks++;
        }
        if (!actual.canRead(actual) || actual.addReads(actual) != actual || actual.addUses(Runnable.class) != actual
                || actual.addOpens("missing", actual) != actual || actual.addExports("missing", actual) != actual) { throw new AssertionError("unnamed module updates"); }
        checks++;
        if (!outcome(() -> actual.canRead(null)).equals(outcome(() -> expected.canRead(null)))) { throw new AssertionError("null module"); } checks++;
        if (actual.getAnnotation(Deprecated.class) != null || actual.getDeclaredAnnotations().length != 0) { throw new AssertionError("unnamed module annotations"); } checks++;
        java.lang.Module base = String.class.getModule();
        for (String name : new String[] {"java.lang.String", "java.lang.Integer", "no.such.Class", "[Ljava.lang.String;", "int"}) {
            if (org.audiveris.wasm.compat.StandardApis.moduleClass(base, name) != Class.forName(base, name)) { throw new AssertionError("module class lookup " + name); }
            checks++;
        }
        java.util.Random random = new java.util.Random(852);
        for (int i = 0; i < 1000; i++) {
            Integer[] a = new Integer[random.nextInt(10)], b = new Integer[random.nextInt(10)];
            for (int j = 0; j < a.length; j++) { a[j] = random.nextBoolean() ? null : random.nextInt(); }
            for (int j = 0; j < b.length; j++) { b[j] = random.nextBoolean() ? null : random.nextInt(); }
            if (org.audiveris.wasm.compat.StandardApis.compareComparables(a, b) != java.util.Arrays.compare(a, b)) { throw new AssertionError("Comparable array order"); }
            checks++;
        }
        return checks;
    }
}
