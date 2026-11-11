package org.audiveris.omr.text;

import ij.process.ByteProcessor;
import java.util.List;
import org.audiveris.omr.sheet.Sheet;
import org.audiveris.omr.sheet.SystemInfo;
import org.audiveris.omr.step.OmrStep;
import org.audiveris.omr.step.StepException;

/** JVM reference: original system/epilog processing with a caller-supplied OCR backend. */
public final class DirectTextsSystems extends TextsStep {
    public void run(Sheet sheet, ByteProcessor buffer, List<TextLine> lines) throws StepException {
        Context context = new Context(buffer, lines);
        for (SystemInfo system : sheet.getSystems()) { super.doSystem(system, context); }
        super.doEpilog(sheet, context);
        sheet.getStub().done(OmrStep.TEXTS);
    }
}
