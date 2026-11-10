// SPDX-License-Identifier: AGPL-3.0-or-later
package org.audiveris.wasm.compat.platform;

import java.util.concurrent.ExecutorService;
import org.audiveris.omr.step.ProcessingCancellationException;
import org.audiveris.omr.util.param.Param;

/** Per-module execution state. Multiple browser Workers each have their own instance. */
public final class HeadlessExecution {
    private static SerialExecutor high, low, cached;
    private static boolean allowed = true;
    private HeadlessExecution() { }
    public static Param<Boolean> parallelism() {
        return new Param<Boolean>(Param.GLOBAL_SCOPE) {
            @Override public Boolean getValue() { return false; }
            @Override public Boolean getSourceValue() { return false; }
            @Override public Boolean getSpecific() { return null; }
            @Override public boolean isSpecific() { return false; }
            @Override public boolean setSpecific(Boolean value) {
                if (Boolean.TRUE.equals(value)) { throw new IllegalArgumentException("Parallelism is controlled by host Workers"); } return false;
            }
        };
    }
    private static void check() { if (!allowed) { throw new ProcessingCancellationException("Executor closed"); } }
    private static final class Snapshot {
        final java.util.Map<String, String> log = HeadlessLogging.mdc().getCopyOfContextMap();
        final String sheet = HeadlessEvents.sheetId();
        final org.audiveris.omr.sheet.Sheet model = HeadlessSheetContext.current();
    }
    private static SerialExecutor create() {
        return new SerialExecutor(new SerialExecutor.Context() {
            @Override public Object capture() { return new Snapshot(); }
            @Override public void restore(Object value) {
                Snapshot snapshot = (Snapshot) value;
                HeadlessLogging.mdc().setContextMap(snapshot.log); HeadlessEvents.setSheetId(snapshot.sheet);
                HeadlessSheetContext.bind(snapshot.model);
            }
        });
    }
    public static ExecutorService high() { check(); if (high == null || high.isShutdown()) { high = create(); } return high; }
    public static ExecutorService low() { check(); if (low == null || low.isShutdown()) { low = create(); } return low; }
    public static ExecutorService cached() { check(); if (cached == null || cached.isShutdown()) { cached = create(); } return cached; }
    public static int cpus() { return 1; }
    public static void restart() { allowed = true; }
    public static boolean shutdown() {
        allowed = false; boolean terminated = true;
        for (SerialExecutor executor : new SerialExecutor[] {cached, low, high}) {
            if (executor != null) { executor.shutdown(); executor.drain(); terminated &= executor.isTerminated(); }
        }
        return terminated;
    }
}
