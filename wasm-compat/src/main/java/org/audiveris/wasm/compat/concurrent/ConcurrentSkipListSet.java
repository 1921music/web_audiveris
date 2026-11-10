// SPDX-License-Identifier: AGPL-3.0-or-later
package org.audiveris.wasm.compat.concurrent;

import java.util.*;
import java.util.concurrent.ConcurrentNavigableMap;

/** Single-lane companion: the JDK set otherwise calls private skip-list map internals. */
public class ConcurrentSkipListSet<E> extends AbstractSet<E>
        implements NavigableSet<E>, Cloneable, java.io.Serializable {
    private static final long serialVersionUID = 1L;
    private final ConcurrentNavigableMap<E, Boolean> map;
    public ConcurrentSkipListSet() { map = new ConcurrentSkipListMap<>(); }
    public ConcurrentSkipListSet(Comparator<? super E> comparator) { map = new ConcurrentSkipListMap<>(comparator); }
    public ConcurrentSkipListSet(Collection<? extends E> values) { this(); addAll(values); }
    public ConcurrentSkipListSet(SortedSet<E> values) { this(values.comparator()); addAll(values); }
    private ConcurrentSkipListSet(ConcurrentNavigableMap<E, Boolean> map) { this.map = map; }
    @Override public int size() { return map.size(); }
    @Override public boolean isEmpty() { return map.isEmpty(); }
    @Override public boolean contains(Object value) { return map.containsKey(value); }
    @Override public boolean add(E value) { return map.putIfAbsent(value, Boolean.TRUE) == null; }
    @Override public boolean remove(Object value) { return map.remove(value) != null; }
    @Override public boolean removeAll(Collection<?> values) {
        Objects.requireNonNull(values); boolean changed = false;
        for (Object value : values) { changed |= remove(value); } return changed;
    }
    @Override public void clear() { map.clear(); }
    @Override public Iterator<E> iterator() { return map.navigableKeySet().iterator(); }
    @Override public Iterator<E> descendingIterator() { return map.descendingKeySet().iterator(); }
    @Override public Comparator<? super E> comparator() { return map.comparator(); }
    @Override public E first() { return map.firstKey(); }
    @Override public E last() { return map.lastKey(); }
    @Override public E lower(E value) { return map.lowerKey(value); }
    @Override public E floor(E value) { return map.floorKey(value); }
    @Override public E ceiling(E value) { return map.ceilingKey(value); }
    @Override public E higher(E value) { return map.higherKey(value); }
    private E key(Map.Entry<E, Boolean> entry) { return entry == null ? null : entry.getKey(); }
    @Override public E pollFirst() { return key(map.pollFirstEntry()); }
    @Override public E pollLast() { return key(map.pollLastEntry()); }
    @Override public NavigableSet<E> descendingSet() { return new ConcurrentSkipListSet<>(map.descendingMap()); }
    @Override public NavigableSet<E> subSet(E from, boolean fromInclusive, E to, boolean toInclusive) {
        return new ConcurrentSkipListSet<>(map.subMap(from, fromInclusive, to, toInclusive));
    }
    @Override public NavigableSet<E> headSet(E to, boolean inclusive) { return new ConcurrentSkipListSet<>(map.headMap(to, inclusive)); }
    @Override public NavigableSet<E> tailSet(E from, boolean inclusive) { return new ConcurrentSkipListSet<>(map.tailMap(from, inclusive)); }
    @Override public NavigableSet<E> subSet(E from, E to) { return subSet(from, true, to, false); }
    @Override public NavigableSet<E> headSet(E to) { return headSet(to, false); }
    @Override public NavigableSet<E> tailSet(E from) { return tailSet(from, true); }
    @Override public ConcurrentSkipListSet<E> clone() {
        ConcurrentSkipListSet<E> copy = new ConcurrentSkipListSet<>(comparator()); copy.addAll(this); return copy;
    }
}
