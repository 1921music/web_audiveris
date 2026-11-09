// SPDX-License-Identifier: AGPL-3.0-or-later
package org.audiveris.wasm.compat.platform;

import java.util.*;
import java.util.concurrent.*;

/** One Worker, no Java threads. Awaiting a future pumps this executor's FIFO queue.
 * Deadlines/cancellation are checked at task boundaries; the host must terminate a stuck Worker.
 * Fire-and-forget work needs a host drain call. This is not shared-memory parallel execution.
 */
public final class SerialExecutor implements ExecutorService {
    public interface Context { Object capture(); void restore(Object value); }
    private static final Context EMPTY_CONTEXT = new Context() {
        @Override public Object capture() { return null; }
        @Override public void restore(Object value) { }
    };
    private final Context context;
    private final Deque<Task<?>> queue = new ArrayDeque<>();
    private boolean shutdown;
    private int running;
    public SerialExecutor() { this(EMPTY_CONTEXT); }
    public SerialExecutor(Context context) { this.context = Objects.requireNonNull(context); }
    private static long budget(long timeout, TimeUnit unit) { return Objects.requireNonNull(unit).toNanos(timeout); }
    private static boolean expired(long start, long limit) { return limit <= 0 || System.nanoTime() - start >= limit; }
    private <T> Task<T> enqueue(Callable<T> callable, boolean report) {
        Objects.requireNonNull(callable);
        if (shutdown) { throw new RejectedExecutionException("Serial Worker executor is closed"); }
        Task<T> task = new Task<>(callable, report); queue.addLast(task); return task;
    }
    private boolean pump() {
        Task<?> task = queue.pollFirst();
        if (task == null) { return false; }
        task.run(); return true;
    }
    public void drain() { while (pump()) { } }
    @Override public void execute(Runnable command) {
        Objects.requireNonNull(command); enqueue(() -> { command.run(); return null; }, true);
    }
    @Override public <T> Future<T> submit(Callable<T> task) { return enqueue(task, false); }
    @Override public <T> Future<T> submit(Runnable task, T result) {
        Objects.requireNonNull(task); return enqueue(() -> { task.run(); return result; }, false);
    }
    @Override public Future<?> submit(Runnable task) { return submit(task, null); }
    @Override public void shutdown() { shutdown = true; }
    @Override public List<Runnable> shutdownNow() {
        shutdown = true; List<Runnable> pending = new ArrayList<>();
        while (!queue.isEmpty()) { Task<?> task = queue.removeFirst(); if (!task.done) { pending.add(task); task.cancel(false); } }
        return pending;
    }
    @Override public boolean isShutdown() { return shutdown; }
    @Override public boolean isTerminated() { return shutdown && running == 0 && queue.isEmpty(); }
    @Override public boolean awaitTermination(long timeout, TimeUnit unit) throws InterruptedException {
        long limit = budget(timeout, unit), start = System.nanoTime();
        while (!isTerminated()) {
            interrupted();
            if (expired(start, limit) || running != 0 || !pump()) { return false; }
        }
        return true;
    }
    private static void interrupted() throws InterruptedException { if (Thread.interrupted()) { throw new InterruptedException(); } }
    private <T> List<Task<T>> schedule(Collection<? extends Callable<T>> tasks) {
        Objects.requireNonNull(tasks);
        List<Callable<T>> copy = new ArrayList<>(); for (Callable<T> task : tasks) { copy.add(Objects.requireNonNull(task)); }
        List<Task<T>> result = new ArrayList<>(); for (Callable<T> task : copy) { result.add(enqueue(task, false)); } return result;
    }
    private static void cancelAll(List<? extends Future<?>> tasks) { for (Future<?> task : tasks) { task.cancel(true); } }
    @Override public <T> List<Future<T>> invokeAll(Collection<? extends Callable<T>> tasks) throws InterruptedException {
        List<Task<T>> scheduled = schedule(tasks); boolean complete = false;
        try {
            for (Task<T> task : scheduled) { try { task.get(); } catch (ExecutionException | CancellationException ignored) { } }
            complete = true; return new ArrayList<>(scheduled);
        } finally { if (!complete) { cancelAll(scheduled); } }
    }
    @Override public <T> List<Future<T>> invokeAll(Collection<? extends Callable<T>> tasks, long timeout, TimeUnit unit) throws InterruptedException {
        long limit = budget(timeout, unit), start = System.nanoTime(); List<Task<T>> scheduled = schedule(tasks);
        try {
            for (Task<T> task : scheduled) {
                try { task.get(limit - (System.nanoTime() - start), TimeUnit.NANOSECONDS); }
                catch (ExecutionException | CancellationException ignored) { }
                catch (TimeoutException expired) { break; }
            }
            return new ArrayList<>(scheduled);
        } finally { cancelAll(scheduled); }
    }
    @Override public <T> T invokeAny(Collection<? extends Callable<T>> tasks) throws InterruptedException, ExecutionException {
        try { return any(tasks, false, 0); } catch (TimeoutException impossible) { throw new AssertionError(impossible); }
    }
    @Override public <T> T invokeAny(Collection<? extends Callable<T>> tasks, long timeout, TimeUnit unit)
            throws InterruptedException, ExecutionException, TimeoutException { return any(tasks, true, budget(timeout, unit)); }
    private <T> T any(Collection<? extends Callable<T>> tasks, boolean timed, long limit)
            throws InterruptedException, ExecutionException, TimeoutException {
        long start = System.nanoTime(); List<Task<T>> scheduled = schedule(tasks);
        if (scheduled.isEmpty()) { throw new IllegalArgumentException("No tasks"); }
        try {
            ExecutionException failure = null;
            for (Task<T> task : scheduled) {
                try { return timed ? task.get(limit - (System.nanoTime() - start), TimeUnit.NANOSECONDS) : task.get(); }
                catch (ExecutionException error) { failure = error; }
                catch (CancellationException cancelled) { failure = new ExecutionException(cancelled); }
            }
            throw failure;
        } finally { cancelAll(scheduled); }
    }
    private final class Task<T> implements Future<T>, Runnable {
        private Callable<T> callable;
        private final boolean report;
        private boolean done, cancelled, executing;
        private T value;
        private Throwable failure;
        private Object captured;
        Task(Callable<T> callable, boolean report) { this.callable = callable; this.report = report; captured = context.capture(); }
        @Override public void run() {
            if (done || executing) { return; }
            Object previous = context.capture(); executing = true; running++;
            try { context.restore(captured); T result = callable.call(); if (!done) { value = result; } }
            catch (Throwable error) { if (!done) { failure = error; } if (report) { error.printStackTrace(); } }
            finally { done = true; executing = false; running--; callable = null; captured = null; context.restore(previous); }
        }
        @Override public boolean cancel(boolean mayInterruptIfRunning) {
            if (done) { return false; } cancelled = true; done = true;
            if (!executing) { callable = null; captured = null; } return true;
        }
        @Override public boolean isCancelled() { return cancelled; }
        @Override public boolean isDone() { return done; }
        private T result() throws ExecutionException {
            if (cancelled) { throw new CancellationException(); }
            if (failure != null) { throw new ExecutionException(failure); } return value;
        }
        @Override public T get() throws InterruptedException, ExecutionException {
            while (!done) { interrupted(); if (executing || !pump()) { throw new IllegalStateException("Cyclic future wait in a single Worker"); } }
            return result();
        }
        @Override public T get(long timeout, TimeUnit unit) throws InterruptedException, ExecutionException, TimeoutException {
            long limit = budget(timeout, unit), start = System.nanoTime();
            if (done) { return result(); }
            do {
                interrupted(); if (expired(start, limit)) { throw new TimeoutException(); }
                if (executing || !pump()) { throw new IllegalStateException("Cyclic future wait in a single Worker"); }
                if (expired(start, limit)) { throw new TimeoutException(); }
            } while (!done);
            return result();
        }
    }
}
