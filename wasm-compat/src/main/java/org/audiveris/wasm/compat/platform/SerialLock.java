// SPDX-License-Identifier: AGPL-3.0-or-later
package org.audiveris.wasm.compat.platform;

import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.Lock;

/** Reentrant ownership for one execution lane; contention never blocks a Worker. */
public final class SerialLock implements Lock {
    private static final Object MODULE_LANE = new Object();
    private Object owner;
    private int holds;
    private static Object lane() { Thread thread = Thread.currentThread(); return thread == null ? MODULE_LANE : thread; }
    @Override public boolean tryLock() {
        Object thread = lane();
        if (owner != null && owner != thread) { return false; }
        if (holds == Integer.MAX_VALUE) { throw new Error("Maximum lock count exceeded"); }
        owner = thread; holds++; return true;
    }
    @Override public void lock() {
        if (!tryLock()) { throw new UnsupportedOperationException("Blocking across Java execution lanes is unsupported in this Worker"); }
    }
    @Override public void lockInterruptibly() throws InterruptedException {
        if (Thread.interrupted()) { throw new InterruptedException(); } lock();
    }
    @Override public boolean tryLock(long time, TimeUnit unit) throws InterruptedException {
        Objects.requireNonNull(unit);
        if (Thread.interrupted()) { throw new InterruptedException(); }
        if (tryLock()) { return true; }
        if (time <= 0) { return false; }
        throw new UnsupportedOperationException("Timed blocking across Java execution lanes is unsupported in this Worker");
    }
    @Override public void unlock() {
        if (holds == 0 || owner != lane()) { throw new IllegalMonitorStateException(); }
        if (--holds == 0) { owner = null; }
    }
    @Override public Condition newCondition() {
        throw new UnsupportedOperationException("Condition waits require an explicit asynchronous boundary");
    }
}
