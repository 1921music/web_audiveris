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
            System.out.println(problem.getSeverity() + ": " + text.getText());
        }
        if (!tool.getProblemProvider().getSevereProblems().isEmpty()) { throw new IllegalStateException("Compatibility probe compilation failed"); }
        try (var runtime = CompileCompatibilityProbe.class.getResourceAsStream("/org/teavm/backend/wasm/wasm-gc-runtime.min.js")) {
            if (runtime == null) { throw new IllegalStateException("TeaVM Wasm GC runtime resource missing"); }
            java.nio.file.Files.copy(runtime, new File(args[0], name + ".wasm-runtime.js").toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        }
        System.out.println("Compiled compatibility probe to Wasm GC");
    }
}
