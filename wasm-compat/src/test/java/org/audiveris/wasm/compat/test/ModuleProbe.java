package org.audiveris.wasm.compat.test;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;

public final class ModuleProbe {
    private static final class NamedLoader extends ClassLoader { NamedLoader(String name, ClassLoader parent) { super(name, parent); } }
    public static String values() {
        Module module = ModuleProbe.class.getModule();
        StringBuilder value = new StringBuilder();
        value.append(module.isNamed()).append('|').append(module.getName()).append('|').append(module.getDescriptor()).append('|').append(module.getLayer()).append('\n');
        value.append(module == ModuleProbe.class.getClassLoader().getUnnamedModule()).append('|');
        value.append(module.canRead(String.class.getModule())).append('|').append(module.canUse(Runnable.class)).append('\n');
        value.append(module.isOpen("missing.package")).append('|').append(module.isExported("missing.package", module)).append('\n');
        value.append(module.addReads(String.class.getModule()) == module).append('|');
        value.append(module.addOpens("missing", module) == module).append('|').append(module.addUses(Runnable.class) == module).append('\n');
        value.append(module.getAnnotations().length).append('|').append(module.getAnnotation(Deprecated.class)).append('\n');
        try (InputStream input = module.getResourceAsStream("/org/audiveris/wasm/compat/test/resource.txt")) {
            value.append(new String(input.readAllBytes(), StandardCharsets.UTF_8).trim()).append('\n');
        } catch (Exception failure) { throw new AssertionError(failure); }
        Package pkg = ModuleProbe.class.getPackage();
        value.append(pkg.getImplementationVersion()).append('|').append(pkg.getImplementationVendor()).append('\n');
        value.append(pkg.getSpecificationTitle()).append('\n');
        String bundle = "org.audiveris.wasm.compat.test.ModuleMessages";
        var root = java.util.ResourceBundle.getBundle(bundle, java.util.Locale.ROOT, module);
        var french = java.util.ResourceBundle.getBundle(bundle, java.util.Locale.FRENCH, module);
        value.append(root.getString("message")).append('|').append(french.getString("message")).append('|').append(french.getString("baseOnly")).append('\n');
        value.append(root.getLocale()).append('|').append(french.getLocale()).append('|').append(french.getBaseBundleName()).append('\n');
        value.append(Class.forName(module, ResourceProbe.LookupFixture.class.getName()) == ResourceProbe.LookupFixture.class).append('|');
        value.append(Class.forName(module, "no.such.Class") == null).append('\n');
        for (Integer[] a : new Integer[][] {null, {}, {null, 1}, {1, 2}, {3}}) {
            for (Integer[] b : new Integer[][] {null, {}, {null, 2}, {1, 2}, {4}}) { value.append(java.util.Arrays.compare(a, b)).append(','); }
        }
        value.append('\n');
        Thread thread = Thread.currentThread(); ClassLoader original = thread.getContextClassLoader();
        ClassLoader selected = new ClassLoader(original) { };
        try {
            thread.setContextClassLoader(selected); Thread first = new Thread();
            value.append(thread.getContextClassLoader() == selected).append('|').append(first.getContextClassLoader() == selected).append('\n');
            thread.setContextClassLoader(null); Thread second = new Thread();
            value.append(thread.getContextClassLoader() == null).append('|').append(second.getContextClassLoader() == null).append('|')
                    .append(first.getContextClassLoader() == selected).append('\n');
        } finally { thread.setContextClassLoader(original); }
        ClassLoader parent = ModuleProbe.class.getClassLoader();
        ClassLoader named = new NamedLoader("wasm-loader-\u697d\u8b5c", parent);
        value.append(named.getName()).append('|').append(named.getParent() == parent).append('|').append(named.isRegisteredAsParallelCapable()).append('\n');
        value.append(new NamedLoader(null, parent).getName()).append('\n');
        try { new NamedLoader("", parent); value.append("wrong"); }
        catch (IllegalArgumentException expected) { value.append("empty name rejected"); }
        value.append('\n');
        return value.toString();
    }
}
