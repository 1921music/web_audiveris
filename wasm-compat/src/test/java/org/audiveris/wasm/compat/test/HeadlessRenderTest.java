package org.audiveris.wasm.compat.test;

import java.awt.*;
import java.awt.geom.AffineTransform;
import java.awt.image.BufferedImage;
import org.audiveris.wasm.compat.platform.HeadlessRenderData;
import org.audiveris.wasm.compat.platform.HeadlessRenderOps;

final class HeadlessRenderTest {
    static void data() {
        AlphaComposite value = HeadlessRenderData.templateComposite();
        if (value != HeadlessRenderData.templateComposite() || value.getRule() != AlphaComposite.SRC_OVER || value.getAlpha() != 0.25f) { throw new AssertionError("Template opacity changed"); }
    }
    static void jvmDrawing() {
        for (double zoom : new double[] {0.5, 1, 2, 4}) {
            Graphics2D graphics = new BufferedImage(32, 32, BufferedImage.TYPE_INT_RGB).createGraphics();
            try {
                graphics.setTransform(AffineTransform.getScaleInstance(zoom, zoom));
                Stroke original = graphics.getStroke();
                if (HeadlessRenderOps.setAbsoluteStroke(graphics, 3.5f) != original) { throw new AssertionError("Lost saved stroke"); }
                BasicStroke stroke = (BasicStroke) graphics.getStroke();
                if (stroke.getLineWidth() != 3.5f / (float) zoom) { throw new AssertionError("Scaled stroke width changed"); }
                Stroke previous = stroke;
                if (HeadlessRenderOps.setAbsoluteDashedStroke(graphics, 2) != previous) { throw new AssertionError("Lost saved dashed stroke"); }
                stroke = (BasicStroke) graphics.getStroke();
                if (stroke.getLineWidth() != 2f / (float) zoom || stroke.getDashArray()[0] != 6f / (float) zoom
                        || stroke.getEndCap() != BasicStroke.CAP_SQUARE || stroke.getLineJoin() != BasicStroke.JOIN_MITER) {
                    throw new AssertionError("Dashed stroke changed");
                }
            } finally { graphics.dispose(); }
        }
    }
}
