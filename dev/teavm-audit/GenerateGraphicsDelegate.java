import java.awt.Graphics2D;
import java.lang.reflect.*;
import java.nio.file.*;
import java.util.*;

/** Generate the uninteresting delegation methods of the JVM-only graphics recorder. */
class GenerateGraphicsDelegate {
    public static void main(String[] args) throws Exception {
        StringBuilder code = new StringBuilder("package org.audiveris.wasm.compat.test;\n\n"
            + "/** Generated JVM-only delegation; see dev/teavm-audit/GenerateGraphicsDelegate.java. */\n"
            + "abstract class GraphicsDelegate extends java.awt.Graphics2D {\n"
            + "    protected final java.awt.Graphics2D delegate;\n"
            + "    GraphicsDelegate(java.awt.Graphics2D delegate) { this.delegate = delegate; }\n");
        var methods = Arrays.stream(Graphics2D.class.getMethods()).filter(m -> Modifier.isAbstract(m.getModifiers()))
                .sorted(Comparator.comparing(Method::toString)).toList();
        for (Method method : methods) {
            code.append("    @Override public ").append(method.getReturnType().getCanonicalName()).append(' ').append(method.getName()).append('(');
            var types = method.getParameterTypes();
            for (int i = 0; i < types.length; i++) {
                if (i > 0) { code.append(", "); } code.append(types[i].getCanonicalName()).append(" p").append(i);
            }
            code.append(") { "); if (method.getReturnType() != void.class) { code.append("return "); }
            code.append("delegate.").append(method.getName()).append('(');
            for (int i = 0; i < types.length; i++) { if (i > 0) { code.append(", "); } code.append('p').append(i); }
            code.append("); }\n");
        }
        code.append("}\n"); Files.writeString(Path.of(args[0]), code);
    }
}
