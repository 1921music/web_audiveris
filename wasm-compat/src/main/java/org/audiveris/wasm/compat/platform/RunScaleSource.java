// SPDX-License-Identifier: AGPL-3.0-or-later
package org.audiveris.wasm.compat.platform;

import java.util.Objects;
import org.audiveris.omr.run.RunTable;
import org.audiveris.omr.sheet.Scale;
import org.audiveris.omr.sheet.ScaleBuilder;
import org.audiveris.omr.step.StepException;

/** One page's scale input/state; no Sheet, file loader or GUI object is required. */
public final class RunScaleSource implements ScaleBuilder.Source {
    private final RunTable binary;
    private final String id;
    private final Integer interline, beam;
    private final Scale existing;
    private boolean invalid;
    private String failureMessage;
    public RunScaleSource(RunTable binary, String id, Integer interline, Integer beam, Scale existing) {
        this.binary = Objects.requireNonNull(binary, "binary runs"); this.id = Objects.requireNonNull(id, "page id");
        if (!binary.getOrientation().isVertical() || binary.getWidth() <= 0 || binary.getHeight() <= 0) { throw new IllegalArgumentException("Scale requires nonempty vertical runs"); }
        if (interline != null && interline < 0 || beam != null && beam < 0) { throw new IllegalArgumentException("Scale specifications must be nonnegative"); }
        this.interline = interline; this.beam = beam; this.existing = existing;
    }
    @Override public RunTable getBinaryTable() { return binary; }
    @Override public Scale getScale() { return existing; }
    @Override public Integer getInterlineSpecification() { return interline; }
    @Override public Integer getBeamSpecification() { return beam; }
    @Override public String getId() { return id; }
    @Override public int getWidth() { return binary.getWidth(); }
    @Override public void invalidate() { invalid = true; }
    @Override public void decideOnRemoval(String message) throws StepException {
        invalid = true; failureMessage = message;
        throw new StepException("Sheet ignored"); // Same outcome as a one-sheet, noninteractive upstream job.
    }
    public boolean isInvalid() { return invalid; }
    public String failureMessage() { return failureMessage; }
}
