package org.audiveris.omr.constant;

import java.util.*;
import org.audiveris.wasm.compat.platform.HeadlessConfiguration;

/** Wasm tests exercise original Constant decoding, mutation, reset and the substituted manager. */
public final class WorkerConstantContract {
    public static void run() {
        Fixture fixture;
        try { fixture = new Fixture(); } catch (RuntimeException error) { throw new IllegalStateException("ConstantSet construction", error); }
        check(fixture.initialize() && fixture.size() == 1 && fixture.value.getQualifiedName() != null);
        check(fixture.value.getValue() == 23);
        check(fixture.value.getQualifiedName().equals("org.audiveris.omr.constant.WorkerConstantContract.value"));
        ConstantManager manager = ConstantManager.getInstance();
        Constant.Integer override = constant("override", 7), saved = constant("saved", 5), source = constant("source", 17);
        constant("same", 1);
        check(override.getValue() == 11 && saved.getValue() == 13 && source.getValue() == 17);
        check(override.getSourceValue() == 7 && override.getQualifiedName().equals("fixture.override"));
        check(manager.addConstant("fixture.override", override).equals("11"));
        try { manager.addConstant("fixture.override", source); throw new AssertionError("Duplicate accepted"); }
        catch (IllegalArgumentException expected) { }
        try { manager.addConstant(null, source); throw new AssertionError("Null name accepted"); }
        catch (IllegalArgumentException expected) { }
        check(manager.getConstantUserValue("fixture.override").equals("19"));
        check(new ArrayList<>(manager.getAllProperties()).equals(List.of("fixture.override", "fixture.same", "fixture.saved", "fixture.unused")));
        check(manager.getUnusedUserProperties().equals(new TreeSet<>(List.of("fixture.unused"))));
        saved.setValue(29); check(saved.getValue() == 29); source.setValue(99); source.resetToSource(); check(source.getValue() == 17);
        Map<String, String> snapshot = WorkerConstantManagerBridge.export(manager);
        check(snapshot.equals(Map.of("fixture.override", "11", "fixture.saved", "29", "fixture.unused", "kept")));
        try { manager.storeResource(); throw new AssertionError("Missing persistence silently succeeded"); }
        catch (IllegalStateException expected) { }
        List<Map<String, String>> stored = new ArrayList<>(); HeadlessConfiguration.setPersistence(stored::add);
        manager.storeResource(); check(stored.size() == 1 && stored.get(0).equals(snapshot));
        check(manager.getConstantUserValue("fixture.saved").equals("29"));
        check(manager.removeConstant(source) == source);
        HeadlessConfiguration.setPersistence(null);
    }
    private static final class Fixture extends ConstantSet {
        private final Constant.Integer value = new Constant.Integer("units", 23, "ConstantSet fixture");
    }
    public static boolean rejectInvalidConfiguration() {
        try { new InvalidFixture().initialize(); return false; }
        catch (IllegalStateException expected) {
            Throwable cause = expected;
            while (cause != null && !(cause instanceof NumberFormatException)) { cause = cause.getCause(); }
            if (cause == null) { throw new AssertionError("Unexpected initialization failure", expected); }
            return true;
        }
    }
    private static final class InvalidFixture extends ConstantSet {
        private final Constant.Integer invalid = new Constant.Integer("units", 5, "Invalid input fixture");
    }
    private static Constant.Integer constant(String name, int fallback) {
        Constant.Integer value = new Constant.Integer("units", fallback, "Worker configuration fixture");
        value.setUnitAndName("fixture", name); return value;
    }
    private static void check(boolean value) { if (!value) { throw new AssertionError("Original Constant contract"); } }
}
