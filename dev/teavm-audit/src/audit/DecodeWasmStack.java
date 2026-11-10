package audit;

import java.nio.file.*;
import java.util.*;
import org.teavm.backend.wasm.debug.ExternalDebugFile;
import org.teavm.backend.wasm.debug.parser.LinesDeobfuscationParser;

/** Decode numeric Wasm stack addresses using an optional probe .teadbg file. */
public final class DecodeWasmStack {
    public static void main(String[] args) throws Exception {
        var parser = new LinesDeobfuscationParser();
        var sections = new HashMap<String, byte[]>();
        if (!ExternalDebugFile.read(Files.readAllBytes(Path.of(args[0])), name ->
                (data, from, to) -> sections.put(name, Arrays.copyOfRange(data, from, to)))) {
            throw new IllegalArgumentException("Not a TeaVM external debug file");
        }
        parser.pullSections(sections::get);
        for (int i = 1; i < args.length; i++) {
            int address = Integer.decode(args[i]);
            var locations = parser.getLineInfo().deobfuscateSingle(address);
            System.out.println(args[i] + " " + Arrays.toString(locations));
        }
    }
}
