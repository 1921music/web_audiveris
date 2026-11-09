package org.audiveris.wasm.compat.test;

import java.util.*;
import java.util.concurrent.*;
import org.audiveris.wasm.compat.platform.SerialExecutor;

/** Shared JVM/Wasm checks of the single-Worker scheduling contract, not a JVM thread-pool oracle. */
public final class SerialExecutorTest {
    private static void check(boolean condition) { if (!condition) { throw new AssertionError("Serial executor contract"); } }
    public static void main(String[] args) throws Exception {
        run();
        if (args.length > 0) { java.nio.file.Files.writeString(java.nio.file.Path.of(args[0]), "{\"serialSchedulingContract\":true}\n"); }
        System.out.println("Serial Worker scheduling checks passed");
    }
    static void run() throws Exception {
        SerialExecutor executor = new SerialExecutor(); List<Integer> order = new ArrayList<>();
        Future<Integer> first = executor.submit(() -> { order.add(1); return 41; });
        Future<Integer> second = executor.submit(() -> { order.add(2); return 42; });
        check(!first.isDone() && order.isEmpty()); check(second.get() == 42); check(first.get() == 41); check(order.equals(Arrays.asList(1, 2)));
        Future<Integer> nested = executor.submit(() -> executor.submit(() -> 7).get() * 3); check(nested.get() == 21);
        Future<?> failure = executor.submit(() -> { throw new IllegalArgumentException("retained"); });
        try { failure.get(); throw new AssertionError(); } catch (ExecutionException expected) { check(expected.getCause() instanceof IllegalArgumentException); }
        Future<?> cancelled = executor.submit(() -> { throw new AssertionError("Cancelled body executed"); });
        check(cancelled.cancel(true) && cancelled.isDone() && cancelled.isCancelled()); check(!cancelled.cancel(false));
        try { cancelled.get(); throw new AssertionError(); } catch (CancellationException expected) { }
        Future<Integer> pending = executor.submit(() -> 9);
        try { pending.get(0, TimeUnit.NANOSECONDS); throw new AssertionError(); } catch (TimeoutException expected) { check(!pending.isDone()); }
        check(pending.get() == 9 && pending.get(0, TimeUnit.NANOSECONDS) == 9);
        List<Future<Integer>> all = executor.invokeAll(Arrays.asList(() -> 1, () -> { throw new IllegalStateException(); }, () -> 3));
        check(all.get(0).get() == 1 && all.get(2).get() == 3);
        try { all.get(1).get(); throw new AssertionError(); } catch (ExecutionException expected) { check(expected.getCause() instanceof IllegalStateException); }
        check(executor.<Integer>invokeAny(Arrays.asList(() -> { throw new IllegalStateException(); }, () -> 5)) == 5);
        SerialExecutor cancellingAny = new SerialExecutor();
        try {
            cancellingAny.<Integer>invokeAny(Arrays.asList(() -> { cancellingAny.shutdownNow(); throw new IllegalStateException(); }, () -> 2));
            throw new AssertionError();
        } catch (ExecutionException expected) { check(expected.getCause() instanceof CancellationException); }
        List<Future<Integer>> expired = executor.invokeAll(Arrays.asList(() -> 1, () -> 2), 0, TimeUnit.NANOSECONDS);
        check(expired.stream().allMatch(Future::isCancelled));
        check(executor.invokeAll(Collections.<Callable<Integer>>emptyList()).isEmpty());
        try { executor.invokeAny(Collections.<Callable<Integer>>emptyList()); throw new AssertionError(); } catch (IllegalArgumentException expected) { }
        try { executor.submit((Runnable) null); throw new AssertionError(); } catch (NullPointerException expected) { }
        try { executor.submit((Callable<Object>) null); throw new AssertionError(); } catch (NullPointerException expected) { }
        check(executor.submit(() -> order.add(3), "result").get().equals("result"));
        executor.execute(() -> order.add(4)); executor.shutdown(); check(!executor.isTerminated());
        check(executor.awaitTermination(1, TimeUnit.SECONDS) && order.equals(Arrays.asList(1, 2, 3, 4)));
        try { executor.submit(() -> 1); throw new AssertionError(); } catch (RejectedExecutionException expected) { }
        SerialExecutor aborted = new SerialExecutor(); Future<Integer> never = aborted.submit(() -> 0);
        check(aborted.shutdownNow().size() == 1 && never.isCancelled() && aborted.isTerminated());
        SerialExecutor interrupted = new SerialExecutor(); Future<Integer> waiting = interrupted.submit(() -> 1);
        Thread.currentThread().interrupt();
        try { waiting.get(); throw new AssertionError(); } catch (InterruptedException expected) { check(!waiting.isDone() && !Thread.currentThread().isInterrupted()); }
        check(waiting.get() == 1);
        SerialExecutor cyclic = new SerialExecutor();
        @SuppressWarnings("unchecked") Future<Integer>[] self = new Future[1]; self[0] = cyclic.submit(() -> self[0].get());
        try { self[0].get(); throw new AssertionError(); } catch (ExecutionException expected) { check(expected.getCause() instanceof IllegalStateException); }
    }
}
