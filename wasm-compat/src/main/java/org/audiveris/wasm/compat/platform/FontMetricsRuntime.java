// SPDX-License-Identifier: AGPL-3.0-or-later
package org.audiveris.wasm.compat.platform;

import java.nio.ByteBuffer;
import java.util.function.Consumer;
import org.apache.commons.logging.Log;
import org.slf4j.Logger;

/** Font parsing uses owned heap bytes; mapped file entry points must fail explicitly. */
public final class FontMetricsRuntime {
    private FontMetricsRuntime() { }
    public static Consumer<ByteBuffer> unmapper() {
        return buffer -> { throw new UnsupportedOperationException("Mapped font I/O is unavailable in the memory-only font profile"); };
    }
    public static Log logger(Class<?> type) { return logger(type.getName()); }
    public static Log logger(String name) { return new ConsoleLog(HeadlessLogging.logger(name)); }
    private record ConsoleLog(Logger logger) implements Log {
        @Override public boolean isTraceEnabled() { return logger.isTraceEnabled(); }
        @Override public boolean isDebugEnabled() { return logger.isDebugEnabled(); }
        @Override public boolean isInfoEnabled() { return logger.isInfoEnabled(); }
        @Override public boolean isWarnEnabled() { return logger.isWarnEnabled(); }
        @Override public boolean isErrorEnabled() { return logger.isErrorEnabled(); }
        @Override public boolean isFatalEnabled() { return logger.isErrorEnabled(); }
        @Override public void trace(Object value) { logger.trace(String.valueOf(value)); }
        @Override public void trace(Object value, Throwable error) { logger.trace(String.valueOf(value), error); }
        @Override public void debug(Object value) { logger.debug(String.valueOf(value)); }
        @Override public void debug(Object value, Throwable error) { logger.debug(String.valueOf(value), error); }
        @Override public void info(Object value) { logger.info(String.valueOf(value)); }
        @Override public void info(Object value, Throwable error) { logger.info(String.valueOf(value), error); }
        @Override public void warn(Object value) { logger.warn(String.valueOf(value)); }
        @Override public void warn(Object value, Throwable error) { logger.warn(String.valueOf(value), error); }
        @Override public void error(Object value) { logger.error(String.valueOf(value)); }
        @Override public void error(Object value, Throwable error) { logger.error(String.valueOf(value), error); }
        @Override public void fatal(Object value) { logger.error(String.valueOf(value)); }
        @Override public void fatal(Object value, Throwable error) { logger.error(String.valueOf(value), error); }
    }
}
