// SPDX-License-Identifier: AGPL-3.0-or-later
package org.audiveris.wasm.compat;

import java.util.AbstractSet;
import java.util.Collection;
import java.util.Comparator;
import java.util.Iterator;
import java.util.Objects;
import java.util.SortedSet;
import java.util.Spliterator;
import java.util.TreeSet;
import java.util.function.Predicate;

/** The two missing Collections APIs, keeping sorted, live, unmodifiable views. */
public final class SortedSets {
    private static final SortedSet<?> EMPTY = new ReadOnly<>(new TreeSet<>());
    private SortedSets() { }
    public static <E> SortedSet<E> unmodifiableSortedSet(SortedSet<E> set) { return new ReadOnly<>(Objects.requireNonNull(set)); }
    @SuppressWarnings("unchecked") public static <E> SortedSet<E> emptySortedSet() { return (SortedSet<E>) EMPTY; }

    private static final class ReadOnly<E> extends AbstractSet<E> implements SortedSet<E> {
        private final SortedSet<E> backing;
        ReadOnly(SortedSet<E> backing) { this.backing = backing; }
        @Override public Comparator<? super E> comparator() { return backing.comparator(); }
        @Override public E first() { return backing.first(); }
        @Override public E last() { return backing.last(); }
        @Override public int size() { return backing.size(); }
        @Override public boolean contains(Object object) { return backing.contains(object); }
        @Override public SortedSet<E> subSet(E from, E to) { return unmodifiableSortedSet(backing.subSet(from, to)); }
        @Override public SortedSet<E> headSet(E to) { return unmodifiableSortedSet(backing.headSet(to)); }
        @Override public SortedSet<E> tailSet(E from) { return unmodifiableSortedSet(backing.tailSet(from)); }
        @Override public Spliterator<E> spliterator() { return backing.spliterator(); }
        @Override public Iterator<E> iterator() {
            Iterator<E> iterator = backing.iterator();
            return new Iterator<>() {
                @Override public boolean hasNext() { return iterator.hasNext(); }
                @Override public E next() { return iterator.next(); }
                @Override public void remove() { throw new UnsupportedOperationException(); }
            };
        }
        @Override public boolean add(E value) { throw new UnsupportedOperationException(); }
        @Override public boolean addAll(Collection<? extends E> values) { throw new UnsupportedOperationException(); }
        @Override public boolean remove(Object value) { throw new UnsupportedOperationException(); }
        @Override public boolean removeAll(Collection<?> values) { throw new UnsupportedOperationException(); }
        @Override public boolean retainAll(Collection<?> values) { throw new UnsupportedOperationException(); }
        @Override public boolean removeIf(Predicate<? super E> predicate) { throw new UnsupportedOperationException(); }
        @Override public void clear() { throw new UnsupportedOperationException(); }
    }
}
