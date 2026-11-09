package audit;

import java.io.File;
import org.teavm.tooling.TeaVMTool;
import org.teavm.tooling.TeaVMTargetType;
import org.teavm.diagnostics.DefaultProblemTextConsumer;

public final class CompileCompatibilityProbe {
    public static void main(String[] args) throws Exception {
        TeaVMTool tool = new TeaVMTool();
        tool.setTargetType(TeaVMTargetType.WEBASSEMBLY_GC);
        tool.setMainClass(args.length > 1 ? args[1] : "org.audiveris.wasm.compat.test.CompatibilityProbe");
        tool.setTargetDirectory(new File(args[0]));
        String name = args.length > 2 ? args[2] : "compat";
        tool.setTargetFileName(name + ".wasm");
        tool.setStrict(true);
        tool.generate();
        for (var problem : tool.getProblemProvider().getProblems()) {
            var text = new DefaultProblemTextConsumer(); problem.render(text);
            System.out.println(problem.getSeverity() + ": " + text.getText() + " @ " + problem.getLocation());
        }
        if (!tool.getProblemProvider().getSevereProblems().isEmpty()) {
            java.util.Map<org.teavm.model.MethodReference, java.util.Set<org.teavm.model.MethodReference>> reverse = new java.util.HashMap<>();
            var graph = tool.getDependencyInfo().getCallGraph();
            for (var method : tool.getDependencyInfo().getReachableMethods()) {
                var node = graph.getNode(method); if (node == null) { continue; }
                for (var site : node.getCallSites()) { for (var callee : site.getCalledMethods()) {
                    reverse.computeIfAbsent(callee.getMethod(), key -> new java.util.LinkedHashSet<>()).add(method);
                } }
            }
            for (var problem : tool.getProblemProvider().getSevereProblems()) {
                if (problem.getLocation() == null || problem.getLocation().getMethod() == null) { continue; }
                var queue = new java.util.ArrayDeque<java.util.List<org.teavm.model.MethodReference>>();
                var seen = new java.util.HashSet<org.teavm.model.MethodReference>();
                queue.add(java.util.List.of(problem.getLocation().getMethod()));
                while (!queue.isEmpty()) {
                    var chain = queue.removeFirst(); var last = chain.get(chain.size() - 1); if (!seen.add(last)) { continue; }
                    if (last.getClassName().equals(args.length > 1 ? args[1] : "org.audiveris.wasm.compat.test.CompatibilityProbe")) {
                        var path = new java.util.ArrayList<>(chain); java.util.Collections.reverse(path); System.out.println("Dependency path: " + path); break;
                    }
                    for (var parent : reverse.getOrDefault(last, java.util.Set.of())) {
                        var path = new java.util.ArrayList<>(chain); path.add(parent); queue.addLast(path);
                    }
                }
            }
            throw new IllegalStateException("Compatibility probe compilation failed");
        }
        try (var runtime = CompileCompatibilityProbe.class.getResourceAsStream("/org/teavm/backend/wasm/wasm-gc-runtime.min.js")) {
            if (runtime == null) { throw new IllegalStateException("TeaVM Wasm GC runtime resource missing"); }
            java.nio.file.Files.copy(runtime, new File(args[0], name + ".wasm-runtime.js").toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        }
        System.out.println("Compiled compatibility probe to Wasm GC");
    }
}
