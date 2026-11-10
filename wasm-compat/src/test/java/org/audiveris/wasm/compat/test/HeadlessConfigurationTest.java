package org.audiveris.wasm.compat.test;

import java.util.*;
import org.audiveris.wasm.compat.platform.HeadlessConfiguration;

/** Shared JVM/Wasm contract, initialized before any engine class that reads constants. */
public final class HeadlessConfigurationTest {
    private static String marker;
    public static void prepare(String value) {
        marker = value;
        Map<String, String> overrides = new HashMap<>(); overrides.put("fixture.override", "11"); overrides.put("fixture.marker", value);
        overrides.put("org.audiveris.omr.ui.util.UIUtil.globalFontRatio", "1.5");
        overrides.put("org.audiveris.omr.Main.sheetStepTimeOut", value.endsWith("two") ? "61" : "47");
        overrides.put("org.audiveris.omr.constant.WorkerConstantContract.invalid", "not-a-number");
        Map<String, String> saved = new HashMap<>(); saved.put("fixture.override", "19"); saved.put("fixture.saved", "13");
        saved.put("fixture.same", "1"); saved.put("fixture.unused", "kept");
        HeadlessConfiguration.configure(overrides, saved, value.endsWith("two"));
        HeadlessConfiguration.setOutputFolder("/audiveris/output/" + value);
        overrides.put("fixture.override", "999"); saved.clear(); // Caller mutations must not change a module's snapshot.
    }
    public static void run() {
        var pending = new org.audiveris.wasm.compat.platform.WorkerPendingSet<String>();
        pending.add("b"); pending.add("a"); var iterator = pending.iterator();
        check(iterator.next().equals("a")); pending.add("c"); iterator.remove();
        check(iterator.next().equals("b")); iterator.remove(); check(!iterator.hasNext());
        check(new ArrayList<>(pending).equals(List.of("c")));
        try { iterator.remove(); throw new AssertionError("Repeated removal accepted"); } catch (IllegalStateException expected) { }
        check("11".equals(HeadlessConfiguration.value("fixture.override")));
        check("13".equals(HeadlessConfiguration.value("fixture.saved")));
        check(HeadlessConfiguration.value("fixture.absent") == null);
        check(marker.equals(HeadlessConfiguration.value("fixture.marker")));
        check(HeadlessConfiguration.stepTimeout() == (marker.endsWith("two") ? 61 : 47));
        check(HeadlessConfiguration.saveSheets() == marker.endsWith("two"));
        check(HeadlessConfiguration.outputFolder().toString().equals("/audiveris/output/" + marker));
        check("19".equals(HeadlessConfiguration.savedValue("fixture.override")));
        check(HeadlessConfiguration.isFrozen());
        try { HeadlessConfiguration.configure(Map.of(), Map.of()); throw new AssertionError("Late reconfiguration allowed"); }
        catch (IllegalStateException expected) { }
        try { HeadlessConfiguration.savedValues().put("bad", "write"); throw new AssertionError("Mutable snapshot"); }
        catch (UnsupportedOperationException expected) { }
    }
    private static void check(boolean value) { if (!value) { throw new AssertionError("Worker configuration contract"); } }
}
