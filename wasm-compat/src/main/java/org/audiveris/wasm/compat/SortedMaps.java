// SPDX-License-Identifier: AGPL-3.0-or-later
package org.audiveris.wasm.compat;

import java.util.*;
import java.util.function.*;

public final class SortedMaps {
    private SortedMaps() { }
    private static final SortedMap<?, ?> EMPTY = new ReadOnly<>(new TreeMap<>());
    @SuppressWarnings("unchecked") public static <K, V> SortedMap<K, V> emptySortedMap() { return (SortedMap<K, V>) EMPTY; }
    public static <K, V> SortedMap<K, V> unmodifiableSortedMap(SortedMap<K, V> map) { return new ReadOnly<>(map); }
    private static final class ReadOnly<K, V> extends AbstractMap<K, V> implements SortedMap<K, V>, java.io.Serializable {
        private static final long serialVersionUID = 1L;
        private final SortedMap<K, V> backing;
        private final Map<K, V> view;
        ReadOnly(SortedMap<K, V> map) { backing = Objects.requireNonNull(map); view = Collections.unmodifiableMap(map); }
        @Override public Comparator<? super K> comparator() { return backing.comparator(); }
        @Override public K firstKey() { return backing.firstKey(); }
        @Override public K lastKey() { return backing.lastKey(); }
        @Override public SortedMap<K, V> subMap(K from, K to) { return new ReadOnly<>(backing.subMap(from, to)); }
        @Override public SortedMap<K, V> headMap(K to) { return new ReadOnly<>(backing.headMap(to)); }
        @Override public SortedMap<K, V> tailMap(K from) { return new ReadOnly<>(backing.tailMap(from)); }
        @Override public int size() { return backing.size(); }
        @Override public boolean containsKey(Object key) { return backing.containsKey(key); }
        @Override public boolean containsValue(Object value) { return backing.containsValue(value); }
        @Override public V get(Object key) { return backing.get(key); }
        @Override public Set<Entry<K, V>> entrySet() { return view.entrySet(); }
        @Override public Set<K> keySet() { return view.keySet(); }
        @Override public Collection<V> values() { return view.values(); }
        @Override public V put(K key, V value) { throw new UnsupportedOperationException(); }
        @Override public V remove(Object key) { throw new UnsupportedOperationException(); }
        @Override public void putAll(Map<? extends K, ? extends V> values) { throw new UnsupportedOperationException(); }
        @Override public void clear() { throw new UnsupportedOperationException(); }
        @Override public V putIfAbsent(K key, V value) { throw new UnsupportedOperationException(); }
        @Override public boolean remove(Object key, Object value) { throw new UnsupportedOperationException(); }
        @Override public boolean replace(K key, V old, V value) { throw new UnsupportedOperationException(); }
        @Override public V replace(K key, V value) { throw new UnsupportedOperationException(); }
        @Override public void replaceAll(BiFunction<? super K, ? super V, ? extends V> function) { throw new UnsupportedOperationException(); }
        @Override public V computeIfAbsent(K key, Function<? super K, ? extends V> function) { throw new UnsupportedOperationException(); }
        @Override public V computeIfPresent(K key, BiFunction<? super K, ? super V, ? extends V> function) { throw new UnsupportedOperationException(); }
        @Override public V compute(K key, BiFunction<? super K, ? super V, ? extends V> function) { throw new UnsupportedOperationException(); }
        @Override public V merge(K key, V value, BiFunction<? super V, ? super V, ? extends V> function) { throw new UnsupportedOperationException(); }
    }
}
