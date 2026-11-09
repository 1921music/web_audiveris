import ij.process.ByteProcessor;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import javax.imageio.ImageIO;
import org.audiveris.omr.run.Orientation;
import org.audiveris.omr.run.Run;
import org.audiveris.omr.run.RunTable;
import org.audiveris.omr.run.RunTableFactory;
import org.audiveris.omr.util.OmrExecutors;

/** Independent reference: actual upstream RunTableFactory and run iterator. */
public final class UpstreamRunReference {
    public static void main(String[] args) throws Exception {
        Path fixtures = Path.of(args[0]);
        try (var files = Files.list(fixtures)) {
            for (Path binary : files.filter(p -> p.toString().endsWith(".binary")).sorted().toList()) {
                String id = binary.getFileName().toString().replace(".binary", "");
                var image = ImageIO.read(fixtures.resolve(id + ".input.png").toFile());
                ByteProcessor pixels = new ByteProcessor(image.getWidth(), image.getHeight(), Files.readAllBytes(binary), null);
                for (Orientation orientation : new Orientation[] {Orientation.HORIZONTAL, Orientation.VERTICAL}) {
                    RunTable table = new RunTableFactory(orientation).createTable(pixels);
                    int[] offsets = new int[table.getSize() + 1];
                    List<Integer> pairs = new ArrayList<>();
                    for (int lane = 0; lane < table.getSize(); lane++) {
                        offsets[lane] = pairs.size() / 2;
                        for (var iterator = table.iterator(lane); iterator.hasNext();) {
                            Run run = iterator.next();
                            pairs.add(run.getStart());
                            pairs.add(run.getLength());
                        }
                    }
                    offsets[table.getSize()] = pairs.size() / 2;
                    ByteBuffer output = ByteBuffer.allocate(4 * (1 + offsets.length + pairs.size())).order(ByteOrder.LITTLE_ENDIAN);
                    output.putInt(table.getSize());
                    for (int offset : offsets) { output.putInt(offset); }
                    for (int value : pairs) { output.putInt(value); }
                    Files.write(fixtures.resolve(id + (orientation == Orientation.HORIZONTAL ? ".horizontal.runs" : ".vertical.runs")), output.array());
                    System.out.println(id + " " + orientation + ": " + pairs.size() / 2 + " runs");
                }
            }
        } finally { OmrExecutors.shutdown(); }
    }
}
