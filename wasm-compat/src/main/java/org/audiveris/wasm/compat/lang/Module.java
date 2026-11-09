// SPDX-License-Identifier: AGPL-3.0-or-later
package org.audiveris.wasm.compat.lang;

import java.io.*;
import java.lang.annotation.Annotation;
import java.lang.module.ModuleDescriptor;
import java.lang.reflect.AnnotatedElement;
import java.util.Objects;
import java.util.IdentityHashMap;
import java.util.Map;

/** TeaVM's flat class space has no JPMS named modules: represent its unnamed-module contract. */
public final class Module implements AnnotatedElement {
    private static final Map<ClassLoader, Module> MODULES = new IdentityHashMap<>();
    private final ClassLoader loader;
    Module(ClassLoader loader) { this.loader = loader; }
    public static synchronized Module forLoader(ClassLoader loader) {
        Module module = MODULES.get(loader);
        if (module == null) { module = new Module(loader); MODULES.put(loader, module); }
        return module;
    }
    public static Module forClass(Class<?> type) { return forLoader(type.getClassLoader()); }
    public boolean isNamed() { return false; }
    public String getName() { return null; }
    public ClassLoader getClassLoader() { return loader; }
    public ModuleDescriptor getDescriptor() { return null; }
    public ModuleLayer getLayer() { return null; }
    public boolean canRead(Module other) { Objects.requireNonNull(other); return true; }
    public Module addReads(Module other) { Objects.requireNonNull(other); return this; }
    public boolean isExported(String name) { Objects.requireNonNull(name); return true; }
    public boolean isExported(String name, Module other) { Objects.requireNonNull(other); return isExported(name); }
    public boolean isOpen(String name) { Objects.requireNonNull(name); return true; }
    public boolean isOpen(String name, Module other) { Objects.requireNonNull(other); return isOpen(name); }
    public Module addExports(String name, Module other) { Objects.requireNonNull(name); Objects.requireNonNull(other); return this; }
    public Module addOpens(String name, Module other) { Objects.requireNonNull(name); Objects.requireNonNull(other); return this; }
    public Module addUses(Class<?> service) { Objects.requireNonNull(service); return this; }
    public boolean canUse(Class<?> service) { Objects.requireNonNull(service); return true; }
    public InputStream getResourceAsStream(String name) throws IOException {
        if (name.startsWith("/")) { name = name.substring(1); }
        return (loader == null ? ClassLoader.getSystemClassLoader() : loader).getResourceAsStream(name);
    }
    @Override public <T extends Annotation> T getAnnotation(Class<T> type) { Objects.requireNonNull(type); return null; }
    @Override public Annotation[] getAnnotations() { return new Annotation[0]; }
    @Override public Annotation[] getDeclaredAnnotations() { return new Annotation[0]; }
    @Override public String toString() { return "unnamed module @" + Integer.toHexString(System.identityHashCode(this)); }
}
