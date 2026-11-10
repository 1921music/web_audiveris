// SPDX-License-Identifier: AGPL-3.0-or-later
package org.audiveris.wasm.compat.platform;

import java.util.*;

/** One execution lane: sorted snapshot traversal permits registration during constant initialization. */
public final class WorkerPendingSet<E> extends AbstractSet<E> {
    private final Set<E> values;
    public WorkerPendingSet() { values = new TreeSet<>(); }
    public WorkerPendingSet(Comparator<? super E> comparator) { values = new TreeSet<>(comparator); }
    @Override public boolean add(E value) { return values.add(Objects.requireNonNull(value)); }
    @Override public boolean remove(Object value) { return values.remove(value); }
    @Override public int size() { return values.size(); }
    @Override public Iterator<E> iterator() {
        Iterator<E> snapshot = new ArrayList<>(values).iterator();
        return new Iterator<>() {
            private E last; private boolean removable;
            public boolean hasNext() { return snapshot.hasNext(); }
            public E next() { last = snapshot.next(); removable = true; return last; }
            public void remove() {
                if (!removable) { throw new IllegalStateException(); }
                values.remove(last); removable = false;
            }
        };
    }
}
