package org.audiveris.wasm.compat.test;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.*;
import org.audiveris.wasm.compat.platform.SerialLock;

public final class SerialLockTest {
    public static void main(String[] args) throws Exception {
        List<String> expected = cases(new ReentrantLock()), actual = cases(new SerialLock());
        if (!expected.equals(actual)) { throw new AssertionError(expected + " != " + actual); }
        SerialLock lock = new SerialLock(); CountDownLatch ready = new CountDownLatch(1), release = new CountDownLatch(1);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread other = new Thread(() -> {
            try { lock.lock(); ready.countDown(); if (!release.await(5, TimeUnit.SECONDS)) { throw new AssertionError("Test release timeout"); } lock.unlock(); }
            catch (Throwable error) { failure.set(error); }
        });
        other.setDaemon(true); other.start();
        try {
            if (!ready.await(5, TimeUnit.SECONDS)) { throw new AssertionError("Test ownership timeout"); }
            if (lock.tryLock() || lock.tryLock(0, TimeUnit.SECONDS)) { throw new AssertionError("Wrong owner acquired lock"); }
            try { lock.lock(); throw new AssertionError("Blocking accepted"); } catch (UnsupportedOperationException expectedFailure) { }
            try { lock.tryLock(1, TimeUnit.MILLISECONDS); throw new AssertionError("Timed blocking accepted"); } catch (UnsupportedOperationException expectedFailure) { }
            try { lock.unlock(); throw new AssertionError("Wrong owner released lock"); } catch (IllegalMonitorStateException expectedFailure) { }
            try { lock.newCondition(); throw new AssertionError("Condition accepted"); } catch (UnsupportedOperationException expectedFailure) { }
        } finally { release.countDown(); other.join(5000); }
        if (other.isAlive() || failure.get() != null) { throw new AssertionError("Ownership fixture failure", failure.get()); }
        if (!lock.tryLock()) { throw new AssertionError("Released lock unavailable"); } lock.unlock();
        System.out.println("Serial lock: JDK reentry/interruption parity, wrong-owner rejection and explicit blocking boundary passed");
    }
    private static List<String> cases(Lock lock) throws Exception {
        List<String> result = new ArrayList<>();
        lock.lock(); lock.lock(); result.add("" + lock.tryLock()); result.add("" + lock.tryLock(0, TimeUnit.NANOSECONDS));
        lock.unlock(); lock.unlock(); lock.unlock(); lock.unlock();
        try { lock.unlock(); throw new AssertionError("Unbalanced unlock accepted"); }
        catch (IllegalMonitorStateException expected) { result.add("unbalanced"); }
        Thread.currentThread().interrupt();
        try { lock.lockInterruptibly(); throw new AssertionError("Interrupted lock accepted"); }
        catch (InterruptedException expected) { result.add("interrupted:" + Thread.currentThread().isInterrupted()); }
        Thread.currentThread().interrupt();
        try { lock.tryLock(1, TimeUnit.SECONDS); throw new AssertionError("Interrupted timed lock accepted"); }
        catch (InterruptedException expected) { result.add("timed-interrupted:" + Thread.currentThread().isInterrupted()); }
        try { lock.tryLock(1, null); throw new AssertionError("Null time unit accepted"); }
        catch (NullPointerException expected) { result.add("null-unit"); }
        result.add("" + lock.tryLock(-1, TimeUnit.SECONDS)); lock.unlock(); return result;
    }
}
