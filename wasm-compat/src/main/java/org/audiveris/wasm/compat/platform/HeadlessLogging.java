// SPDX-License-Identifier: AGPL-3.0-or-later
package org.audiveris.wasm.compat.platform;

import java.net.URI;
import java.nio.file.Path;
import java.util.*;
import org.slf4j.*;
import org.slf4j.event.Level;
import org.slf4j.helpers.LegacyAbstractLogger;
import org.slf4j.helpers.MessageFormatter;
import org.slf4j.helpers.BasicMDCAdapter;
import org.slf4j.spi.MDCAdapter;

/** Console and event logging, without Logback service discovery, GUI appenders or file setup. */
public final class HeadlessLogging {
    private static final Map<String, Logger> LOGGERS = new HashMap<>();
    private static final ILoggerFactory FACTORY = HeadlessLogging::logger;
    private static final MDCAdapter CONTEXT = new BasicMDCAdapter();
    private static long errors;
    private HeadlessLogging() { }
    public static ILoggerFactory factory() { return FACTORY; }
    public static Logger logger(Class<?> type) { return logger(Objects.requireNonNull(type).getName()); }
    public static Logger logger(String name) { return LOGGERS.computeIfAbsent(Objects.requireNonNull(name), ConsoleLogger::new); }
    public static MDCAdapter mdc() { return CONTEXT; }
    public static long errorCount() { return errors; }
    public static void initialize(Path config, URI resources) { HeadlessEvents.emit("logging", "initialize", "console"); }
    private static final class ConsoleLogger extends LegacyAbstractLogger {
        ConsoleLogger(String name) { this.name = name; }
        @Override public boolean isTraceEnabled() { return false; }
        @Override public boolean isDebugEnabled() { return false; }
        @Override public boolean isInfoEnabled() { return true; }
        @Override public boolean isWarnEnabled() { return true; }
        @Override public boolean isErrorEnabled() { return true; }
        @Override protected String getFullyQualifiedCallerName() { return ConsoleLogger.class.getName(); }
        @Override protected void handleNormalizedLoggingCall(Level level, Marker marker, String pattern, Object[] arguments, Throwable error) {
            if (level == Level.ERROR) { errors++; }
            String message = MessageFormatter.arrayFormat(pattern, arguments, error).getMessage();
            HeadlessEvents.emit("log", name, level + " " + message);
            java.io.PrintStream output = level.toInt() >= Level.WARN.toInt() ? System.err : System.out;
            output.println(level + " " + name + " " + message);
            if (error != null) { error.printStackTrace(output); }
        }
    }
}
