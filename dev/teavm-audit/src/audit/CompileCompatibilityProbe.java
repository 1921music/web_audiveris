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
        if (Boolean.getBoolean("audiveris.compat.debug")) { tool.setDebugInformationGenerated(true); }
        tool.generate();
        java.nio.file.Files.createDirectories(new File(args[0]).toPath());
        var diagnostics = new StringBuilder("severity\tmethod\tmessage\n");
        int printedProblems = 0;
        for (var problem : tool.getProblemProvider().getProblems()) {
            var text = new DefaultProblemTextConsumer(); problem.render(text);
            if (printedProblems++ < 20) { System.out.println(problem.getSeverity() + ": " + text.getText() + " @ " + problem.getLocation()); }
            var method = problem.getLocation() == null ? null : problem.getLocation().getMethod();
            diagnostics.append(problem.getSeverity()).append('\t').append(method).append('\t')
                    .append(text.getText().replace('\t', ' ').replace('\r', ' ').replace('\n', ' ')).append('\n');
        }
        java.nio.file.Files.writeString(new File(args[0], "diagnostics.tsv").toPath(), diagnostics);
        if (printedProblems > 20) { System.out.println(printedProblems + " diagnostics; full report: " + new File(args[0], "diagnostics.tsv")); }
        if (!tool.getProblemProvider().getSevereProblems().isEmpty()) {
            java.util.Map<org.teavm.model.MethodReference, java.util.Set<org.teavm.model.MethodReference>> reverse = new java.util.HashMap<>();
            var graph = tool.getDependencyInfo().getCallGraph();
            for (var method : tool.getDependencyInfo().getReachableMethods()) {
                var node = graph.getNode(method); if (node == null) { continue; }
                for (var site : node.getCallSites()) { for (var callee : site.getCalledMethods()) {
                    reverse.computeIfAbsent(callee.getMethod(), key -> new java.util.LinkedHashSet<>()).add(method);
                } }
            }
            var paths = new StringBuilder("method\tdependencyPath\n");
            if (Boolean.getBoolean("audiveris.compat.semanticPaths")) {
                var edges = new StringBuilder("caller\tcallee\n");
                for (var entry : reverse.entrySet()) for (var caller : entry.getValue()) {
                    edges.append(caller).append('\t').append(entry.getKey()).append('\n');
                }
                java.nio.file.Files.writeString(new File(args[0], "call-edges.tsv").toPath(), edges);
            }
            var explained = new java.util.HashSet<org.teavm.model.MethodReference>(); int printedPaths = 0;
            for (var problem : tool.getProblemProvider().getSevereProblems()) {
                if (problem.getLocation() == null || problem.getLocation().getMethod() == null) { continue; }
                if (!explained.add(problem.getLocation().getMethod())) { continue; }
                var queue = new java.util.ArrayDeque<org.teavm.model.MethodReference>();
                var seen = new java.util.HashSet<org.teavm.model.MethodReference>();
                var child = new java.util.HashMap<org.teavm.model.MethodReference, org.teavm.model.MethodReference>();
                var start = problem.getLocation().getMethod(); queue.add(start); seen.add(start);
                while (!queue.isEmpty()) {
                    var last = queue.removeFirst();
                    if (last.getClassName().equals(args.length > 1 ? args[1] : "org.audiveris.wasm.compat.test.CompatibilityProbe")) {
                        var path = new java.util.ArrayList<org.teavm.model.MethodReference>();
                        for (var current = last; current != null; current = child.get(current)) { path.add(current); }
                        paths.append(start).append('\t').append(path).append('\n');
                        if (printedPaths++ < 12) { System.out.println("Dependency path: " + path); } break;
                    }
                    for (var parent : reverse.getOrDefault(last, java.util.Set.of())) {
                        if (Boolean.getBoolean("audiveris.compat.semanticPaths") && (parent.getName().equals("toString")
                                || (parent.getClassName().equals("java.lang.String") && parent.getName().equals("valueOf")))) { continue; }
                        if (seen.add(parent)) { child.put(parent, last); queue.addLast(parent); }
                    }
                }
            }
            java.nio.file.Files.writeString(new File(args[0], "dependency-paths.tsv").toPath(), paths);
            throw new IllegalStateException("Compatibility probe compilation failed");
        }
        try (var runtime = CompileCompatibilityProbe.class.getResourceAsStream("/org/teavm/backend/wasm/wasm-gc-runtime.min.js")) {
            if (runtime == null) { throw new IllegalStateException("TeaVM Wasm GC runtime resource missing"); }
            java.nio.file.Files.copy(runtime, new File(args[0], name + ".wasm-runtime.js").toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        }
        System.out.println("Compiled compatibility probe to Wasm GC");
    }
}
