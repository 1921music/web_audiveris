package org.audiveris.wasm.compat.test;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import org.audiveris.omr.WellKnowns;
import org.audiveris.omr.util.OmrExecutors;
import org.audiveris.omr.step.ui.StepMonitoring;
import org.audiveris.omr.log.LogUtil;
import org.audiveris.omr.wasmtest.GuiBoundaryFixture;
import org.audiveris.wasm.compat.platform.*;
import org.slf4j.*;
import org.teavm.jso.JSExport;

public final class HeadlessProbe {
    public static void main(String[] args) { }
    @JSExport public static void configure(String marker) { HeadlessConfigurationTest.prepare(marker); }
    @JSExport public static boolean rejectInvalidConfiguration() { return org.audiveris.omr.constant.WorkerConstantContract.rejectInvalidConfiguration(); }
    private static void check(boolean value) { if (!value) { throw new AssertionError("Headless platform contract"); } }
    @JSExport public static String platform() throws Exception {
        try { return platformCore(); }
        catch (Throwable error) { error.printStackTrace(); return error.getClass().getName() + ": " + error.getMessage(); }
    }
    private static String platformCore() throws Exception {
        HeadlessConfigurationTest.run(); org.audiveris.omr.constant.WorkerConstantContract.run();
        check(org.audiveris.omr.ui.util.UIUtil.getGlobalFontRatio() == 1.5);
        check(org.audiveris.omr.ui.util.UIUtil.GLOBAL_FONT_RATIO == 1.5f);
        if (!org.audiveris.omr.sheet.BookManager.getBaseFolder().equals(HeadlessConfiguration.outputFolder())) {
            throw new AssertionError("Book output override: " + org.audiveris.omr.sheet.BookManager.getBaseFolder() + " expected " + HeadlessConfiguration.outputFolder());
        }
        org.audiveris.omr.sheet.BookManager.setBaseFolder(java.nio.file.Paths.get("/audiveris/changed"));
        check(HeadlessConfiguration.outputFolder() == null);
        if (!org.audiveris.omr.sheet.BookManager.getBaseFolder().toString().equals("/audiveris/changed")) {
            throw new AssertionError("Book output update: " + org.audiveris.omr.sheet.BookManager.getBaseFolder());
        }
        SerialExecutorTest.run(); HeadlessMusicDataTest.run();
        HeadlessRenderTest.data();
        check(HeadlessSheetContext.current() == null);
        try { HeadlessSheetContext.requireSheet(); throw new AssertionError(); } catch (IllegalStateException expected) { }
        WellKnowns.ensureLoaded();
        check(!WellKnowns.WINDOWS && !WellKnowns.MAC_OS_X && !WellKnowns.LINUX && !WellKnowns.FLATPAK && !WellKnowns.RUNNING_FROM_JAR);
        check(WellKnowns.FILE_ENCODING.equals("UTF-8") && WellKnowns.OS_ARCH.equals("wasm"));
        check(WellKnowns.CONFIG_FOLDER.toString().equals("/audiveris/config"));
        check(WellKnowns.TEMP_FOLDER.toString().equals("/audiveris/log/temp"));
        check(WellKnowns.COMPANY_ID.length() > 0 && WellKnowns.TOOL_NAME.length() > 0);
        check(WellKnowns.CLASS_CONTAINER.getScheme().equals("resource"));
        try (InputStream input = WellKnowns.RES_URI.resolve("ISO639-3.xml").toURL().openStream()) {
            check(new String(input.readAllBytes(), StandardCharsets.UTF_8).contains("English"));
        }
        check(GuiBoundaryFixture.engineWork() == 21);
        check(OmrExecutors.getNumberOfCpus() == 1 && !OmrExecutors.defaultParallelism.getValue());
        OmrExecutors.restart();
        for (ExecutorService executor : new ExecutorService[] {OmrExecutors.getHighExecutor(), OmrExecutors.getLowExecutor(), OmrExecutors.getCachedLowExecutor()}) {
            check(executor instanceof SerialExecutor); check(executor.submit(() -> 17).get() == 17);
        }
        check(OmrExecutors.shutdown());
        try { OmrExecutors.getHighExecutor(); throw new AssertionError(); }
        catch (org.audiveris.omr.step.ProcessingCancellationException expected) { }
        OmrExecutors.restart(); check(OmrExecutors.getHighExecutor().submit(() -> 18).get() == 18);
        List<String> events = new ArrayList<>(); HeadlessEvents.setSink((kind, subject, detail) -> events.add(kind + ":" + subject + ":" + detail));
        LogUtil.initialize(WellKnowns.CONFIG_FOLDER, WellKnowns.RES_URI);
        HeadlessEvents.setSheetId("sheet-1");
        StepMonitoring.notifyStart(); StepMonitoring.animate(); StepMonitoring.notifyMsg("recognizing"); StepMonitoring.notifyStep(null, null); StepMonitoring.notifyStop();
        check(events.contains("progress:start:") && events.contains("step:sheet-1:") && events.contains("message::recognizing"));
        try { StepMonitoring.createMonitor(); throw new AssertionError(); } catch (UnsupportedOperationException expected) { }
        Logger logger = LoggerFactory.getLogger(HeadlessProbe.class); check(logger == LoggerFactory.getLogger(HeadlessProbe.class.getName()));
        check(LoggerFactory.getILoggerFactory().getLogger(logger.getName()) == logger);
        logger.info("Result {}", 42); check(events.contains("log:" + logger.getName() + ":INFO Result 42"));
        MDC.put("BOOK", "at-submit");
        Future<String> context = OmrExecutors.getHighExecutor().submit(() -> {
            check(MDC.get("BOOK").equals("at-submit"));
            MDC.put("BOOK", "nested");
            check(OmrExecutors.getLowExecutor().submit(() -> MDC.get("BOOK")).get().equals("nested"));
            return MDC.get("BOOK");
        });
        MDC.put("BOOK", "caller"); check(context.get().equals("nested") && MDC.get("BOOK").equals("caller"));
        MDC.put("BOOK", "test"); MDC.put("SHEET", "1"); LogUtil.stopStub(); check(MDC.get("SHEET") == null && MDC.get("BOOK").equals("test"));
        LogUtil.stopBook(); check(MDC.get("BOOK") == null);
        HeadlessEvents.setSink((kind, subject, detail) -> { });
        HeadlessEvents.setSheetId("");
        return "platform-ok";
    }
    @JSExport public static void close() { OmrExecutors.shutdown(); }
    @JSExport public static boolean open() {
        try { return OmrExecutors.getHighExecutor().submit(() -> true).get(); } catch (Exception closed) { return false; }
    }
}
