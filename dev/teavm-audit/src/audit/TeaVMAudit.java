package audit;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import org.teavm.backend.wasm.WasmGCTarget;
import org.teavm.dependency.AbstractDependencyListener;
import org.teavm.dependency.DependencyAgent;
import org.teavm.dependency.MethodDependency;
import org.teavm.dependency.FastDependencyAnalyzer;
import org.teavm.dependency.PreciseDependencyAnalyzer;
import org.teavm.diagnostics.DefaultProblemTextConsumer;
import org.teavm.model.ClassReader;
import org.teavm.model.ElementModifier;
import org.teavm.model.MethodReader;
import org.teavm.model.MethodReference;
import org.teavm.model.ValueType;
import org.teavm.vm.TeaVM;
import org.teavm.vm.TeaVMBuilder;
import org.teavm.vm.TeaVMOptimizationLevel;
import org.teavm.vm.TeaVMPhase;
import org.teavm.vm.TeaVMProgressFeedback;
import org.teavm.vm.TeaVMProgressListener;

/** Force every declared application method into TeaVM dependency analysis, including private/synthetic methods. */
public final class TeaVMAudit {
    record Root(String category, MethodReference reference, boolean body, boolean nativeMethod) { }
    private static final List<Root> roots = new ArrayList<>();
    private static final Set<String> rootClasses = new TreeSet<>();
    private static final Set<MethodReference> analyzedMethods = new java.util.HashSet<>();
    private static final Map<MethodReference, String> rootCategories = new java.util.HashMap<>();

    public static void main(String[] args) throws Exception {
        Path input = Path.of(args[0]), output = Path.of(args[1]);
        String scope = args[2];
        String mode = args.length > 3 ? args[3] : "precise";
        if (!Set.of("all", "ui", "engine", "inventory", "selected").contains(scope)) {
            throw new IllegalArgumentException("Unknown scope: " + scope);
        }
        Map<String, String> plan = scope.equals("selected") ? readPlan(Path.of(args[4])) : Map.of();
        Set<String> seenPlan = new java.util.HashSet<>();
        Files.createDirectories(output);
        long start = System.nanoTime();
        TeaVMBuilder builder = new TeaVMBuilder(new WasmGCTarget()).setStrict(true);
        if (mode.equals("fast")) {
            builder.setDependencyAnalyzerFactory((source, resources, loader, services, diagnostics, cache, platforms, properties) ->
                    new FastDependencyAnalyzer(source, resources, loader, services, diagnostics, cache, platforms, properties) {
                        @Override protected void processMethod(MethodDependency method) {
                            super.processMethod(method);
                            analyzedMethods.add(method.getReference());
                        }
                    });
        } else {
            builder.setDependencyAnalyzerFactory((source, resources, loader, services, diagnostics, cache, platforms, properties) ->
                    new PreciseDependencyAnalyzer(source, resources, loader, services, diagnostics, cache, platforms, properties) {
                        @Override protected void processMethod(MethodDependency method) {
                            super.processMethod(method);
                            analyzedMethods.add(method.getReference());
                        }
                    });
        }
        TeaVM vm = builder.build();
        vm.installPlugins();
        vm.setEntryPoint(EmptyEntry.class.getName());
        vm.setOptimizationLevel(TeaVMOptimizationLevel.SIMPLE);
        List<String> classNames;
        try (var files = Files.walk(input)) {
            classNames = files.filter(p -> p.toString().endsWith(".class"))
                    .map(p -> input.relativize(p).toString().replace('/', '.').replace('\\', '.').replaceAll("\\.class$", ""))
                    .sorted().toList();
        }
        for (String name : classNames) {
            if (scope.equals("all") || scope.equals("inventory")) { rootClasses.add(name); }
            ClassReader cls = vm.getDependencyClassSource().get(name);
            if (cls == null) { throw new IllegalStateException("Application class not found: " + name); }
            for (MethodReader method : cls.getMethods()) {
                String signature = method.getReference().toString().replace('/', '.');
                String category = category(name, method.getName());
                if (isComponent(vm.getDependencyClassSource(), name, new java.util.HashSet<>())
                        || name.contains("$ChartListener") || name.endsWith(".ChartPlotter")
                        || name.endsWith(".SpotsController") || signature.contains("Ljavax.swing.")
                        || signature.contains("Ljava.awt.Graphics") || signature.contains("Ljava.awt.event.")) {
                    category = "ui";
                }
                if (scope.equals("selected")) {
                    String reference = method.getReference().toString();
                    if (!plan.containsKey(reference)) { throw new IllegalStateException("Method missing from root plan: " + reference); }
                    seenPlan.add(reference);
                    if (!plan.get(reference).equals("root")) { continue; }
                } else if (!scope.equals("all") && !scope.equals("inventory") && !category.equals(scope)) { continue; }
                rootCategories.put(method.getReference(), category);
                roots.add(new Root(category, method.getReference(), method.getProgram() != null,
                        method.hasModifier(ElementModifier.NATIVE)));
                rootClasses.add(name);
            }
        }
        if (scope.equals("selected") && seenPlan.size() != plan.size()) {
            throw new IllegalStateException("Root plan contains stale methods; rebuild the inventory");
        }
        System.out.println("TeaVM 0.16.0 Wasm GC audit: " + rootClasses.size() + " classes, " + roots.size() + " method roots (" + scope + ")");
        writeRoots(output.resolve("roots.tsv"), null);
        if (scope.equals("inventory")) { writeSourceReferences(vm, output); return; }
        vm.add(new AbstractDependencyListener() {
            @Override public void started(DependencyAgent agent) {
                for (String name : rootClasses) { agent.linkClass(name).initClass(null); }
                for (Root root : roots) {
                    MethodDependency method = agent.linkMethod(root.reference());
                    ClassReader cls = agent.getClassSource().get(root.reference().getClassName());
                    MethodReader reader = cls.getMethod(root.reference().getDescriptor());
                    if (!reader.hasModifier(ElementModifier.STATIC)) {
                        method.getVariable(0).propagate(agent.getType(ValueType.object(cls.getName())));
                    }
                    // Seed reference-typed arguments so virtual calls do not disappear for lack of inputs.
                    for (int i = 0; i < root.reference().parameterCount(); i++) {
                        seed(agent, method.getVariable(i + 1), root.reference().parameterType(i));
                    }
                    method.use();
                }
            }
        });
        vm.setProgressListener(new TeaVMProgressListener() {
            long last;
            @Override public TeaVMProgressFeedback phaseStarted(TeaVMPhase phase, int count) {
                System.out.println("Phase " + phase + ", estimated work " + count);
                return TeaVMProgressFeedback.CONTINUE;
            }
            @Override public TeaVMProgressFeedback progressReached(int progress) {
                long now = System.nanoTime();
                if (now - last > 15_000_000_000L) {
                    System.out.println("Analysis progress " + progress + "; heap "
                            + (Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory()) / (1024 * 1024) + " MiB");
                    last = now;
                }
                return TeaVMProgressFeedback.CONTINUE;
            }
        });
        Throwable failure = null;
        try { vm.build(output.toFile(), "audit.wasm"); }
        catch (Throwable exception) {
            failure = exception;
            StringWriter trace = new StringWriter();
            exception.printStackTrace(new PrintWriter(trace));
            Files.writeString(output.resolve("compiler-exception.txt"), trace.toString());
            System.out.println("Compiler exception: " + exception);
        }
        writeRoots(output.resolve("roots.tsv"), vm);
        try (var writer = Files.newBufferedWriter(output.resolve("diagnostics.tsv"), StandardCharsets.UTF_8)) {
            writer.write("severity\tcategory\tcaller\tlocation\tmessage\tparameters\n");
            for (var problem : vm.getProblemProvider().getProblems()) {
                var text = new DefaultProblemTextConsumer();
                problem.render(text);
                var location = problem.getLocation();
                var caller = location == null ? null : location.getMethod();
                String category = caller == null ? "unattributed" : rootCategories.getOrDefault(caller,
                        rootClasses.contains(caller.getClassName()) ? category(caller.getClassName(), caller.getName()) : "dependency");
                writer.write(tsv(problem.getSeverity(), category, caller, location == null ? null : location.getSourceLocation(),
                        text.getText(), java.util.Arrays.toString(problem.getParams())));
            }
        }
        // Reverse edges support attribution of errors inside third-party code to application roots.
        try (var writer = Files.newBufferedWriter(output.resolve("calls.tsv"), StandardCharsets.UTF_8)) {
            writer.write("caller\tcallee\n");
            var graph = vm.getDependencyInfo().getCallGraph();
            for (var method : vm.getDependencyInfo().getReachableMethods()) {
                var node = graph.getNode(method);
                if (node == null) { continue; }
                for (var site : node.getCallSites()) {
                    for (var callee : site.getCalledMethods()) { writer.write(tsv(method, callee.getMethod())); }
                }
            }
        }
        try (var writer = Files.newBufferedWriter(output.resolve("reached-methods.tsv"), StandardCharsets.UTF_8)) {
            writer.write("class\tmethod\tused\tanalyzed\n");
            for (var method : vm.getDependencyInfo().getReachableMethods()) {
                var dependency = vm.getDependencyInfo().getMethod(method);
                writer.write(tsv(method.getClassName(), method, dependency != null && dependency.isUsed(), analyzedMethods.contains(method)));
            }
        }
        Files.write(output.resolve("reached-classes.txt"), new TreeSet<>(vm.getDependencyInfo().getReachableClasses()), StandardCharsets.UTF_8);
        int used = 0;
        for (Root root : roots) {
            var dependency = vm.getDependencyInfo().getMethod(root.reference());
            if (dependency != null && dependency.isUsed()) { used++; }
        }
        String summary = "scope=" + scope + "\nmode=" + mode + "\nclasses=" + rootClasses.size() + "\nmethodRoots=" + roots.size()
                + "\nusedRoots=" + used + "\nproblems=" + vm.getProblemProvider().getProblems().size()
                + "\nanalyzedRootBodies=" + roots.stream().filter(r -> r.body() && analyzedMethods.contains(r.reference())).count()
                + "\nrootBodies=" + roots.stream().filter(Root::body).count()
                + "\nsevereProblems=" + vm.getProblemProvider().getSevereProblems().size()
                + "\ncompilerException=" + (failure == null ? "none" : failure.getClass().getName())
                + "\nelapsedSeconds=" + (System.nanoTime() - start) / 1e9 + "\n";
        Files.writeString(output.resolve("summary.properties"), summary);
        System.out.println(summary);
        if (used != roots.size()) { throw new IllegalStateException("Not all root methods were included; see roots.tsv"); }
        if (failure == null && roots.stream().anyMatch(r -> r.body() && !analyzedMethods.contains(r.reference()))) {
            throw new IllegalStateException("Not all root method bodies were analyzed; see roots.tsv");
        }
        if (failure != null) { throw new IllegalStateException("Compiler analysis did not complete", failure); }
    }

    private static Map<String, String> readPlan(Path path) throws Exception {
        Map<String, String> plan = new java.util.HashMap<>();
        var lines = Files.readAllLines(path, StandardCharsets.UTF_8);
        var header = List.of(lines.get(0).split("\t", -1));
        int method = header.indexOf("method"), action = header.indexOf("action"), decision = header.indexOf("classDecision"), category = header.indexOf("category");
        if (method < 0 || action < 0 || decision < 0 || category < 0) { throw new IllegalArgumentException("Invalid root plan header"); }
        for (String line : lines.subList(1, lines.size())) {
            String[] fields = line.split("\t", -1);
            if (!Set.of("root", "excluded", "dependency-only").contains(fields[action])) {
                throw new IllegalArgumentException("Invalid root plan action");
            }
            if (fields[action].equals("root") && (!Set.of("keep", "mixed").contains(fields[decision]) || fields[category].equals("ui"))) {
                throw new IllegalArgumentException("Forbidden class/UI method was requested as a root");
            }
            if (plan.put(fields[method], fields[action]) != null) { throw new IllegalArgumentException("Duplicate planned method"); }
        }
        return plan;
    }

    private static void writeSourceReferences(TeaVM vm, Path output) throws Exception {
        try (var writer = Files.newBufferedWriter(output.resolve("source-references.tsv"), StandardCharsets.UTF_8)) {
            writer.write("caller\ttargetClass\ttargetMember\tkind\tlocation\n");
            for (Root root : roots) {
                var method = vm.getDependencyClassSource().get(root.reference().getClassName()).getMethod(root.reference().getDescriptor());
                if (method.getProgram() == null) { continue; }
                var reader = new org.teavm.model.instructions.AbstractInstructionReader() {
                    org.teavm.model.TextLocation location;
                    void emit(String cls, Object member, String kind) {
                        try { writer.write(tsv(root.reference(), cls, member, kind, location)); }
                        catch (java.io.IOException e) { throw new java.io.UncheckedIOException(e); }
                    }
                    void fieldType(ValueType type, Object field) {
                        if (type instanceof ValueType.Object object) { emit(object.getClassName(), field, "field-type"); }
                        else if (type instanceof ValueType.Array array) { fieldType(array.getItemType(), field); }
                    }
                    @Override public void location(org.teavm.model.TextLocation value) { location = value; }
                    @Override public void create(org.teavm.model.VariableReader receiver, String type) { emit(type, "", "construct"); }
                    @Override public void initClass(String cls) { emit(cls, cls + ".<clinit>()V", "initialize"); }
                    @Override public void getField(org.teavm.model.VariableReader receiver, org.teavm.model.VariableReader instance,
                            org.teavm.model.FieldReference field, ValueType type) { emit(field.getClassName(), field, "field-read"); fieldType(type, field); }
                    @Override public void putField(org.teavm.model.VariableReader instance, org.teavm.model.FieldReference field,
                            org.teavm.model.VariableReader value, ValueType type) { emit(field.getClassName(), field, "field-write"); fieldType(type, field); }
                    @Override public void invoke(org.teavm.model.VariableReader receiver, org.teavm.model.VariableReader instance,
                            MethodReference target, java.util.List<? extends org.teavm.model.VariableReader> arguments,
                            org.teavm.model.instructions.InvocationType type) { emit(target.getClassName(), target, "invoke-" + type); }
                };
                for (var block : method.getProgram().getBasicBlocks()) { reader.location(null); block.readAllInstructions(reader); }
            }
        }
    }

    private static void seed(DependencyAgent agent, org.teavm.dependency.DependencyNode node, ValueType type) {
        if (type instanceof ValueType.Object || type instanceof ValueType.Array) {
            node.propagate(agent.getType(type));
            if (type instanceof ValueType.Array array) { seed(agent, node.getArrayItem(), array.getItemType()); }
        }
    }

    static String category(String cls, String method) {
        if (!cls.startsWith("org.audiveris.") && !cls.equals("Audiveris")) { return "dependency"; }
        if (cls.contains(".ui.") || cls.endsWith(".ui") || cls.startsWith("org.audiveris.omr.sheet.ui.")
                || cls.contains(".log.LogPane")) { return "ui"; }
        if (method.startsWith("render") || method.startsWith("paint") || method.equals("getBoard")
                || method.equals("getComponent") || method.equals("displayFrame") || method.equals("getEditor")
                || method.startsWith("displayChart") || method.equals("getChart") || method.equals("plot")) { return "ui"; }
        return "engine";
    }

    private static boolean isComponent(org.teavm.model.ClassReaderSource source, String name, Set<String> seen) {
        if (name == null || !seen.add(name)) { return false; }
        if (name.equals("java.awt.Component")) { return true; }
        var cls = source.get(name);
        return cls != null && isComponent(source, cls.getParent(), seen);
    }

    private static void writeRoots(Path file, TeaVM vm) throws Exception {
        try (var writer = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
            writer.write("category\tclass\tmethod\thasBody\tnative\tused\tanalyzed\n");
            for (Root root : roots) {
                var info = vm == null ? null : vm.getDependencyInfo().getMethod(root.reference());
                writer.write(tsv(root.category(), root.reference().getClassName(), root.reference(), root.body(), root.nativeMethod(),
                        info != null && info.isUsed(), analyzedMethods.contains(root.reference())));
            }
        }
    }

    static String tsv(Object... values) {
        List<String> result = new ArrayList<>();
        for (Object value : values) { result.add(value == null ? "" : value.toString().replace("\t", "\\t").replace("\r", "\\r").replace("\n", "\\n")); }
        return String.join("\t", result) + "\n";
    }
}
