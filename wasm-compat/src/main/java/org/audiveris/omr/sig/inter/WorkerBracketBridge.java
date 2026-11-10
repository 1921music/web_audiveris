// SPDX-License-Identifier: AGPL-3.0-or-later
package org.audiveris.omr.sig.inter;

import org.audiveris.wasm.compat.platform.HeadlessBracketMetrics;

/** Replace font acquisition only; retain the original bracket area construction. */
public final class WorkerBracketBridge {
    private WorkerBracketBridge() { }
    public static void computeArea(BracketInter bracket) {
        if (bracket.sig == null) { return; }
        var sheet = bracket.sig.getSystem().getSheet();
        var bounds = HeadlessBracketMetrics.require(sheet.getStub().getMusicFamily().name(), sheet.getScale().getInterline());
        bracket.computeArea(bounds[0], bounds[1]);
    }
}
