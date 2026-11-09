// SPDX-License-Identifier: AGPL-3.0-or-later
package org.audiveris.wasm;

import org.teavm.jso.JSExport;
import org.teavm.jso.JSBody;
import org.teavm.jso.JSObject;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/** One active page per instance/worker. JS owns scheduling and job identifiers. */
public final class WasmApi {
    private static Page active;
    private static ByteBuffer transfer;

    public static void main(String[] args) { }

    @JSExport
    public static void start(int width, int height, int stride, JSObject pixels,
                             int radius, double mean, double deviation, boolean estimateScale, boolean buildRuns, boolean buildSections) {
        if (active != null) { throw new IllegalStateException("Worker already has an active page"); }
        int length = byteLength(pixels);
        if (width <= 0 || height <= 0 || stride < width || (long) stride * height > length
                || (long) width * height > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("Invalid GRAY8 dimensions or buffer");
        }
        byte[] input = new byte[length];
        ByteBuffer buffer = transferBuffer(length);
        copyInput(pixels, buffer);
        buffer.get(input);
        active = new Page(new GrayImage(width, height, stride, input), radius, mean, deviation, estimateScale, buildRuns, buildSections);
    }

    @JSExport
    public static boolean advance(int columns) { return requireActive().advance(columns); }

    @JSExport
    public static int completedColumns() {
        Page page = requireActive();
        return page.binarizer != null ? page.binarizer.completedColumns()
                : page.horizontalSections != null ? page.currentSections().completedLanes()
                : page.buildRuns ? page.currentRuns().completedLanes()
                : page.scale != null ? page.scale.completedColumns() : page.width;
    }

    @JSExport
    public static String stage() {
        Page page = requireActive();
        return page.binarizer != null ? "BINARY" : page.horizontalSections != null ? "SECTIONS"
                : page.buildRuns ? "RUNS" : page.scale != null ? "SCALE" : "BINARY";
    }

    @JSExport
    public static int totalUnits() {
        Page page = requireActive();
        return page.horizontalSections != null ? page.currentSections().laneCount()
                : page.binarizer == null && page.buildRuns ? page.currentRuns().laneCount() : page.width;
    }

    @JSExport
    public static JSObject runTable(boolean horizontal) {
        Page page = requireActive();
        CompactRunTable table = horizontal ? page.horizontal : page.vertical;
        if (table == null) { throw new IllegalStateException("Run table has not been requested or reached"); }
        return intOutput(table.packed());
    }

    @JSExport
    public static JSObject sections(boolean horizontal) {
        Page page = requireActive();
        CompactSections sections = horizontal ? page.horizontalSections : page.verticalSections;
        if (sections == null) { throw new IllegalStateException("Sections have not been requested or reached"); }
        return intOutput(sections.packed());
    }

    @JSExport
    public static JSObject sectionGeometry(boolean horizontal) {
        Page page = requireActive();
        CompactSections sections = horizontal ? page.horizontalSections : page.verticalSections;
        if (sections == null) { throw new IllegalStateException("Sections have not been requested or reached"); }
        return intOutput(SectionGeometry.compute(sections.packed(), horizontal));
    }

    private static JSObject intOutput(int[] data) {
        ByteBuffer buffer = transferBuffer(Math.multiplyExact(data.length, Integer.BYTES));
        buffer.order(ByteOrder.LITTLE_ENDIAN).asIntBuffer().put(data);
        return copyIntOutput(buffer, data.length);
    }

    @JSExport
    public static JSObject binaryImage() {
        Page page = requireActive();
        if (page.output == null) { throw new IllegalStateException("Binary image is incomplete"); }
        ByteBuffer buffer = transferBuffer(page.output.length);
        buffer.put(page.output);
        buffer.rewind();
        return copyOutput(buffer, page.output.length);
    }

    @JSExport
    public static int[] scale() {
        Page page = requireActive();
        if (page.scale == null) { throw new IllegalStateException("Scale has not been requested or reached"); }
        return page.scale.result();
    }

    @JSExport
    public static void dispose() { active = null; }

    private static ByteBuffer transferBuffer(int size) {
        if (transfer == null || transfer.capacity() < size) { transfer = ByteBuffer.allocateDirect(size); }
        transfer.clear();
        transfer.limit(size);
        return transfer;
    }

    @JSBody(params = "input", script = "return input.byteLength;")
    private static native int byteLength(JSObject input);

    @JSBody(params = {"input", "destination"}, script = "destination.set(input);")
    private static native void copyInput(JSObject input, ByteBuffer destination);

    // Return independent storage: transferring this buffer must never detach Wasm memory.
    @JSBody(params = {"buffer", "length"}, script = "return new Uint8Array(buffer.buffer, buffer.byteOffset, length).slice();")
    private static native JSObject copyOutput(ByteBuffer buffer, int length);

    @JSBody(params = {"buffer", "length"}, script = "return new Int32Array(buffer.buffer, buffer.byteOffset, length).slice();")
    private static native JSObject copyIntOutput(ByteBuffer buffer, int length);

    private static Page requireActive() {
        if (active == null) { throw new IllegalStateException("No active page"); }
        return active;
    }

    private static final class Page {
        final int width, height;
        final boolean estimateScale;
        final boolean buildRuns;
        final boolean buildSections;
        AdaptiveBinarizer binarizer;
        ScaleEstimator scale;
        byte[] output;
        CompactRunTable horizontal, vertical;
        CompactSections horizontalSections, verticalSections;

        Page(GrayImage image, int radius, double mean, double deviation, boolean estimateScale, boolean buildRuns, boolean buildSections) {
            width = image.width;
            height = image.height;
            this.estimateScale = estimateScale;
            this.buildRuns = buildRuns;
            this.buildSections = buildSections;
            binarizer = new AdaptiveBinarizer(image, radius, mean, deviation);
        }

        boolean advance(int columns) {
            if (binarizer != null) {
                if (binarizer.advance(columns)) {
                    output = binarizer.result();
                    binarizer = null; // Release grayscale pixels and integral tables at the boundary.
                    if (buildRuns) {
                        horizontal = new CompactRunTable(width, height, output, true);
                        return false;
                    }
                    if (!estimateScale) { return true; }
                    scale = new ScaleEstimator(width, height, output);
                }
                return false;
            }
            if (horizontalSections != null) {
                if (verticalSections == null) {
                    if (horizontalSections.advance(columns)) { verticalSections = new CompactSections(vertical.packed()); }
                    return false;
                }
                return verticalSections.advance(columns);
            }
            if (buildRuns) {
                if (vertical == null) {
                    if (horizontal.advance(columns)) { vertical = new CompactRunTable(width, height, output, false); }
                    return false;
                }
                if (!vertical.advance(columns)) { return false; }
                if (buildSections) {
                    horizontalSections = new CompactSections(horizontal.packed());
                    return false;
                }
                return true;
            }
            return scale == null || scale.advance(columns);
        }

        CompactRunTable currentRuns() { return vertical != null ? vertical : horizontal; }
        CompactSections currentSections() { return verticalSections != null ? verticalSections : horizontalSections; }
    }
}
