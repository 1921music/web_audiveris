package org.audiveris.wasm.compat.test;

import java.util.*;

public final class LoggerProbe {
    public static final class Capture implements System.Logger {
        String output = "";
        @Override public String getName() { return "capture"; }
        @Override public boolean isLoggable(Level level) { return level != Level.OFF; }
        @Override public void log(Level level, ResourceBundle bundle, String message, Throwable error) { output = "throwable"; }
        @Override public void log(Level level, ResourceBundle bundle, String format, Object... parameters) {
            Objects.requireNonNull(level);
            output = level.getName() + "|" + bundle + "|" + format + "|" + Arrays.toString(parameters);
        }
    }
    public static String values() {
        Capture logger = new Capture(); StringBuilder result = new StringBuilder();
        for (System.Logger.Level level : System.Logger.Level.values()) {
            logger.log(level, "format {0}", new Object[] {42, "value", null});
            result.append(logger.output).append('\n');
        }
        logger.log(System.Logger.Level.INFO, null, (Object[]) null); result.append(logger.output).append('\n');
        return result.toString();
    }
    static int compare() {
        Capture actual = new Capture(), expected = new Capture(); int checks = 0;
        for (System.Logger.Level level : System.Logger.Level.values()) {
            Object[] values = {42, "value", null};
            org.audiveris.wasm.compat.StandardApis.systemLog(actual, level, "format {0}", values);
            expected.log(level, "format {0}", values);
            if (!actual.output.equals(expected.output)) { throw new AssertionError("System.Logger forwarding"); }
            checks++;
        }
        return checks;
    }
}
