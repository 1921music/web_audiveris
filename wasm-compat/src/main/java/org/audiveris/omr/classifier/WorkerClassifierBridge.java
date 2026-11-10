// SPDX-License-Identifier: AGPL-3.0-or-later
package org.audiveris.omr.classifier;

import org.audiveris.omr.math.NeuralNetwork;
import org.audiveris.wasm.compat.platform.HeadlessClassifierModels;

/** Host-provided archive replaces filesystem acquisition; feature extraction and inference remain. */
public final class WorkerClassifierBridge {
    private WorkerClassifierBridge() { }
    public static AbstractClassifier<NeuralNetwork, BasicNorms>.Network load(BasicClassifier classifier, String[] names) {
        if (names == null || names.length != 1 || !BasicClassifier.MODEL_FILE_NAME.equals(names[0])) {
            throw new UnsupportedOperationException("Unsupported classifier model request");
        }
        var data = HeadlessClassifierModels.require();
        if (!classifier.isCompatible(data.model(), data.norms())) { throw new IllegalArgumentException("Classifier model does not match engine feature/shape labels"); }
        return classifier.new Network(data.model(), data.norms());
    }
}
