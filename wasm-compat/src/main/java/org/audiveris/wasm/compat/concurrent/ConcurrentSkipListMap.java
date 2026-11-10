// SPDX-License-Identifier: AGPL-3.0-or-later
package org.audiveris.wasm.compat.concurrent;

import java.util.*;
import java.util.concurrent.ConcurrentNavigableMap;

/**
 * Ordered map for a single Java execution lane in each Wasm module. This is not a
 * shared-memory concurrent implementation. Views are live; iterators navigate by
 * key rather than holding a fail-fast TreeMap iterator. No O(n) snapshot per scan.
 */
public class ConcurrentSkipListMap<K, V> extends AbstractMap<K, V>
        implements ConcurrentNavigableMap<K, V>, Cloneable, java.io.Serializable {
    private static final long serialVersionUID = 1L;
    private final NavigableMap<K, V> map;
    private final K low, high;
    private final boolean lowInclusive, highInclusive, reverse;

    public ConcurrentSkipListMap() { this(new TreeMap<K, V>(), null, false, null, false, false); }
    public ConcurrentSkipListMap(Comparator<? super K> comparator) { this(new TreeMap<K, V>(comparator), null, false, null, false, false); }
    public ConcurrentSkipListMap(Map<? extends K, ? extends V> values) { this(); putAll(values); }
    public ConcurrentSkipListMap(SortedMap<K, ? extends V> values) { this(values.comparator()); putAll(values); }
    private ConcurrentSkipListMap(NavigableMap<K, V> map, K low, boolean lowInclusive, K high, boolean highInclusive, boolean reverse) {
        this.map = map; this.low = low; this.lowInclusive = lowInclusive;
        this.high = high; this.highInclusive = highInclusive; this.reverse = reverse;
    }
    @SuppressWarnings("unchecked") private int compare(Object first, Object second) {
        Comparator<? super K> comparator = map.comparator();
        return comparator == null ? ((Comparable<? super K>) first).compareTo((K) second) : comparator.compare((K) first, (K) second);
    }
    private boolean below(Object key) { return low != null && (compare(key, low) < 0 || (compare(key, low) == 0 && !lowInclusive)); }
    private boolean above(Object key) { return high != null && (compare(key, high) > 0 || (compare(key, high) == 0 && !highInclusive)); }
    private boolean inRange(Object key) { return !below(key) && !above(key); }
    private Entry<K, V> firstAscending() {
        Entry<K, V> entry = low == null ? map.firstEntry() : lowInclusive ? map.ceilingEntry(low) : map.higherEntry(low);
        return entry == null || above(entry.getKey()) ? null : immutable(entry);
    }
    private Entry<K, V> lastAscending() {
        Entry<K, V> entry = high == null ? map.lastEntry() : highInclusive ? map.floorEntry(high) : map.lowerEntry(high);
        return entry == null || below(entry.getKey()) ? null : immutable(entry);
    }
    private Entry<K, V> neighbor(K key, boolean forward, boolean inclusive) {
        Objects.requireNonNull(key); boolean ascending = forward != reverse;
        Entry<K, V> entry = ascending ? (inclusive ? map.ceilingEntry(key) : map.higherEntry(key))
                : (inclusive ? map.floorEntry(key) : map.lowerEntry(key));
        if (entry == null) { return null; }
        if (ascending && below(entry.getKey())) { return firstAscending(); }
        if (!ascending && above(entry.getKey())) { return lastAscending(); }
        return inRange(entry.getKey()) ? immutable(entry) : null;
    }
    private void endpoint(K key, boolean inclusive) {
        Objects.requireNonNull(key);
        if ((low != null && (compare(key, low) < 0 || (compare(key, low) == 0 && inclusive && !lowInclusive)))
                || (high != null && (compare(key, high) > 0 || (compare(key, high) == 0 && inclusive && !highInclusive)))) {
            throw new IllegalArgumentException("Endpoint outside view");
        }
    }
    private K key(Entry<K, V> entry) { return entry == null ? null : entry.getKey(); }

    @Override public Comparator<? super K> comparator() { return reverse ? Collections.reverseOrder(map.comparator()) : map.comparator(); }
    @Override public int size() {
        if (low == null && high == null) { return map.size(); }
        int count = 0; for (Entry<K, V> ignored : entrySet()) { count++; } return count;
    }
    @Override public boolean isEmpty() { return firstEntry() == null; }
    @Override public boolean containsKey(Object key) { Objects.requireNonNull(key); return inRange(key) && map.containsKey(key); }
    @Override public boolean containsValue(Object value) {
        Objects.requireNonNull(value); for (Entry<K, V> entry : entrySet()) { if (value.equals(entry.getValue())) { return true; } } return false;
    }
    @Override public V get(Object key) { Objects.requireNonNull(key); return inRange(key) ? map.get(key) : null; }
    @Override public V put(K key, V value) {
        Objects.requireNonNull(key); Objects.requireNonNull(value);
        if (!inRange(key)) { throw new IllegalArgumentException("Key outside view"); } return map.put(key, value);
    }
    @Override public V remove(Object key) { Objects.requireNonNull(key); return inRange(key) ? map.remove(key) : null; }
    @Override public void clear() {
        if (low == null && high == null) { map.clear(); return; }
        Iterator<?> iterator = entrySet().iterator(); while (iterator.hasNext()) { iterator.next(); iterator.remove(); }
    }
    @Override public V putIfAbsent(K key, V value) {
        Objects.requireNonNull(value); V old = get(key); if (old == null) { put(key, value); } return old;
    }
    @Override public boolean remove(Object key, Object value) {
        Objects.requireNonNull(key); if (value == null || !value.equals(get(key))) { return false; }
        remove(key); return true;
    }
    @Override public boolean replace(K key, V oldValue, V newValue) {
        Objects.requireNonNull(oldValue); Objects.requireNonNull(newValue);
        if (!oldValue.equals(get(key))) { return false; } put(key, newValue); return true;
    }
    @Override public V replace(K key, V value) {
        Objects.requireNonNull(value); V old = get(key); if (old != null) { put(key, value); } return old;
    }
    private Entry<K, V> immutable(Entry<K, V> entry) { return entry == null ? null : new SimpleImmutableEntry<>(entry); }
    @Override public Entry<K, V> lowerEntry(K key) { return neighbor(key, false, false); }
    @Override public K lowerKey(K key) { return key(lowerEntry(key)); }
    @Override public Entry<K, V> floorEntry(K key) { return neighbor(key, false, true); }
    @Override public K floorKey(K key) { return key(floorEntry(key)); }
    @Override public Entry<K, V> ceilingEntry(K key) { return neighbor(key, true, true); }
    @Override public K ceilingKey(K key) { return key(ceilingEntry(key)); }
    @Override public Entry<K, V> higherEntry(K key) { return neighbor(key, true, false); }
    @Override public K higherKey(K key) { return key(higherEntry(key)); }
    @Override public Entry<K, V> firstEntry() { return reverse ? lastAscending() : firstAscending(); }
    @Override public Entry<K, V> lastEntry() { return reverse ? firstAscending() : lastAscending(); }
    @Override public K firstKey() { Entry<K, V> entry = firstEntry(); if (entry == null) { throw new NoSuchElementException(); } return entry.getKey(); }
    @Override public K lastKey() { Entry<K, V> entry = lastEntry(); if (entry == null) { throw new NoSuchElementException(); } return entry.getKey(); }
    @Override public Entry<K, V> pollFirstEntry() { Entry<K, V> entry = firstEntry(); if (entry != null) { remove(entry.getKey()); } return entry; }
    @Override public Entry<K, V> pollLastEntry() { Entry<K, V> entry = lastEntry(); if (entry != null) { remove(entry.getKey()); } return entry; }
    @Override public ConcurrentNavigableMap<K, V> descendingMap() { return new ConcurrentSkipListMap<>(map, low, lowInclusive, high, highInclusive, !reverse); }
    @Override public ConcurrentNavigableMap<K, V> subMap(K from, boolean fromInclusive, K to, boolean toInclusive) {
        endpoint(from, fromInclusive); endpoint(to, toInclusive);
        if ((reverse ? compare(to, from) : compare(from, to)) > 0) { throw new IllegalArgumentException("Reversed range"); }
        return reverse ? new ConcurrentSkipListMap<>(map, to, toInclusive, from, fromInclusive, true)
                : new ConcurrentSkipListMap<>(map, from, fromInclusive, to, toInclusive, false);
    }
    @Override public ConcurrentNavigableMap<K, V> headMap(K to, boolean inclusive) {
        endpoint(to, inclusive);
        return reverse ? new ConcurrentSkipListMap<>(map, to, inclusive, high, highInclusive, true)
                : new ConcurrentSkipListMap<>(map, low, lowInclusive, to, inclusive, false);
    }
    @Override public ConcurrentNavigableMap<K, V> tailMap(K from, boolean inclusive) {
        endpoint(from, inclusive);
        return reverse ? new ConcurrentSkipListMap<>(map, low, lowInclusive, from, inclusive, true)
                : new ConcurrentSkipListMap<>(map, from, inclusive, high, highInclusive, false);
    }
    @Override public ConcurrentNavigableMap<K, V> subMap(K from, K to) { return subMap(from, true, to, false); }
    @Override public ConcurrentNavigableMap<K, V> headMap(K to) { return headMap(to, false); }
    @Override public ConcurrentNavigableMap<K, V> tailMap(K from) { return tailMap(from, true); }
    @Override public NavigableSet<K> keySet() { return navigableKeySet(); }
    @Override public NavigableSet<K> navigableKeySet() { return new Keys<>(this); }
    @Override public NavigableSet<K> descendingKeySet() { return descendingMap().navigableKeySet(); }

    @Override public Set<Entry<K, V>> entrySet() {
        return new AbstractSet<>() {
            @Override public int size() { return ConcurrentSkipListMap.this.size(); }
            @Override public void clear() { ConcurrentSkipListMap.this.clear(); }
            @Override public boolean contains(Object value) {
                if (!(value instanceof Entry)) { return false; }
                Entry<?, ?> entry = (Entry<?, ?>) value;
                return entry.getKey() != null && entry.getValue() != null && entry.getValue().equals(get(entry.getKey()));
            }
            @Override public boolean remove(Object value) {
                if (!(value instanceof Entry)) { return false; }
                Entry<?, ?> entry = (Entry<?, ?>) value;
                return entry.getKey() != null && ConcurrentSkipListMap.this.remove(entry.getKey(), entry.getValue());
            }
            @Override public Iterator<Entry<K, V>> iterator() {
                return new Iterator<>() {
                    Entry<K, V> next = firstEntry(); K last; boolean removable;
                    @Override public boolean hasNext() { return next != null; }
                    @Override public Entry<K, V> next() {
                        if (next == null) { throw new NoSuchElementException(); }
                        Entry<K, V> result = next; last = result.getKey(); removable = true;
                        next = higherEntry(last); return result;
                    }
                    @Override public void remove() {
                        if (!removable) { throw new IllegalStateException(); }
                        ConcurrentSkipListMap.this.remove(last); removable = false;
                    }
                };
            }
        };
    }
    @SuppressWarnings("MethodDoesntCallSuperMethod")
    @Override public ConcurrentSkipListMap<K, V> clone() {
        ConcurrentSkipListMap<K, V> copy = new ConcurrentSkipListMap<>(comparator()); copy.putAll(this); return copy;
    }

    private static final class Keys<K, V> extends AbstractSet<K> implements NavigableSet<K> {
        private final ConcurrentNavigableMap<K, V> map;
        Keys(ConcurrentNavigableMap<K, V> map) { this.map = map; }
        @Override public int size() { return map.size(); }
        @Override public boolean isEmpty() { return map.isEmpty(); }
        @Override public boolean contains(Object key) { return map.containsKey(key); }
        @Override public boolean remove(Object key) { return map.remove(key) != null; }
        @Override public void clear() { map.clear(); }
        @Override public Iterator<K> iterator() {
            Iterator<Entry<K, V>> iterator = map.entrySet().iterator();
            return new Iterator<>() {
                @Override public boolean hasNext() { return iterator.hasNext(); }
                @Override public K next() { return iterator.next().getKey(); }
                @Override public void remove() { iterator.remove(); }
            };
        }
        @Override public Iterator<K> descendingIterator() { return descendingSet().iterator(); }
        @Override public Comparator<? super K> comparator() { return map.comparator(); }
        @Override public K first() { return map.firstKey(); }
        @Override public K last() { return map.lastKey(); }
        @Override public K lower(K key) { return map.lowerKey(key); }
        @Override public K floor(K key) { return map.floorKey(key); }
        @Override public K ceiling(K key) { return map.ceilingKey(key); }
        @Override public K higher(K key) { return map.higherKey(key); }
        private K key(Entry<K, V> entry) { return entry == null ? null : entry.getKey(); }
        @Override public K pollFirst() { return key(map.pollFirstEntry()); }
        @Override public K pollLast() { return key(map.pollLastEntry()); }
        @Override public NavigableSet<K> descendingSet() { return map.descendingMap().navigableKeySet(); }
        @Override public NavigableSet<K> subSet(K from, boolean fromInclusive, K to, boolean toInclusive) {
            return map.subMap(from, fromInclusive, to, toInclusive).navigableKeySet();
        }
        @Override public NavigableSet<K> headSet(K to, boolean inclusive) { return map.headMap(to, inclusive).navigableKeySet(); }
        @Override public NavigableSet<K> tailSet(K from, boolean inclusive) { return map.tailMap(from, inclusive).navigableKeySet(); }
        @Override public SortedSet<K> subSet(K from, K to) { return subSet(from, true, to, false); }
        @Override public SortedSet<K> headSet(K to) { return headSet(to, false); }
        @Override public SortedSet<K> tailSet(K from) { return tailSet(from, true); }
    }
}
