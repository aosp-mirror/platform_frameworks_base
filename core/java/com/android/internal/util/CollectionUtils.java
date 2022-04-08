/*
 * Copyright (C) 2017 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.internal.util;

import static java.util.Collections.emptySet;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.util.ArrayMap;
import android.util.ArraySet;
import android.util.ExceptionUtils;

import com.android.internal.util.FunctionalUtils.ThrowingConsumer;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.stream.Stream;

/**
 * Utility methods for dealing with (typically {@code Nullable}) {@link Collection}s
 *
 * Unless a method specifies otherwise, a null value for a collection is treated as an empty
 * collection of that type.
 */
public class CollectionUtils {
    private CollectionUtils() { /* cannot be instantiated */ }

    /**
     * Returns a list of items from the provided list that match the given condition.
     *
     * This is similar to {@link Stream#filter} but without the overhead of creating an intermediate
     * {@link Stream} instance
     */
    public static @NonNull <T> List<T> filter(@Nullable List<T> list,
            java.util.function.Predicate<? super T> predicate) {
        ArrayList<T> result = null;
        for (int i = 0; i < size(list); i++) {
            final T item = list.get(i);
            if (predicate.test(item)) {
                result = ArrayUtils.add(result, item);
            }
        }
        return emptyIfNull(result);
    }

    /**
     * @see #filter(List, java.util.function.Predicate)
     */
    public static @NonNull <T> Set<T> filter(@Nullable Set<T> set,
            java.util.function.Predicate<? super T> predicate) {
        if (set == null || set.size() == 0) return emptySet();
        ArraySet<T> result = null;
        if (set instanceof ArraySet) {
            ArraySet<T> arraySet = (ArraySet<T>) set;
            int size = arraySet.size();
            for (int i = 0; i < size; i++) {
                final T item = arraySet.valueAt(i);
                if (predicate.test(item)) {
                    result = ArrayUtils.add(result, item);
                }
            }
        } else {
            for (T item : set) {
                if (predicate.test(item)) {
                    result = ArrayUtils.add(result, item);
                }
            }
        }
        return emptyIfNull(result);
    }

    /** Add all elements matching {@code predicate} in {@code source} to {@code dest}. */
    public static <T> void addIf(@Nullable List<T> source, @NonNull Collection<? super T> dest,
            @Nullable Predicate<? super T> predicate) {
        for (int i = 0; i < size(source); i++) {
            final T item = source.get(i);
            if (predicate.test(item)) {
                dest.add(item);
            }
        }
    }

    /**
     * Returns a list of items resulting from applying the given function to each element of the
     * provided list.
     *
     * The resulting list will have the same {@link #size} as the input one.
     *
     * This is similar to {@link Stream#map} but without the overhead of creating an intermediate
     * {@link Stream} instance
     */
    public static @NonNull <I, O> List<O> map(@Nullable List<I> cur,
            Function<? super I, ? extends O> f) {
        if (isEmpty(cur)) return Collections.emptyList();
        final ArrayList<O> result = new ArrayList<>();
        for (int i = 0; i < cur.size(); i++) {
            result.add(f.apply(cur.get(i)));
        }
        return result;
    }

    /**
     * @see #map(List, Function)
     */
    public static @NonNull <I, O> Set<O> map(@Nullable Set<I> cur,
            Function<? super I, ? extends O> f) {
        if (isEmpty(cur)) return emptySet();
        ArraySet<O> result = new ArraySet<>();
        if (cur instanceof ArraySet) {
            ArraySet<I> arraySet = (ArraySet<I>) cur;
            int size = arraySet.size();
            for (int i = 0; i < size; i++) {
                result.add(f.apply(arraySet.valueAt(i)));
            }
        } else {
            for (I item : cur) {
                result.add(f.apply(item));
            }
        }
        return result;
    }

    /**
     * {@link #map(List, Function)} + {@link #filter(List, java.util.function.Predicate)}
     *
     * Calling this is equivalent (but more memory efficient) to:
     *
     * {@code
     *      filter(
     *          map(cur, f),
     *          i -> { i != null })
     * }
     */
    public static @NonNull <I, O> List<O> mapNotNull(@Nullable List<I> cur,
            Function<? super I, ? extends O> f) {
        if (isEmpty(cur)) return Collections.emptyList();
        List<O> result = null;
        for (int i = 0; i < cur.size(); i++) {
            O transformed = f.apply(cur.get(i));
            if (transformed != null) {
                result = add(result, transformed);
            }
        }
        return emptyIfNull(result);
    }

    /**
     * Returns the given list, or an immutable empty list if the provided list is null
     *
     * This can be used to guarantee null-safety without paying the price of extra allocations
     *
     * @see Collections#emptyList
     */
    public static @NonNull <T> List<T> emptyIfNull(@Nullable List<T> cur) {
        return cur == null ? Collections.emptyList() : cur;
    }

    /**
     * Returns the given set, or an immutable empty set if the provided set is null
     *
     * This can be used to guarantee null-safety without paying the price of extra allocations
     *
     * @see Collections#emptySet
     */
    public static @NonNull <T> Set<T> emptyIfNull(@Nullable Set<T> cur) {
        return cur == null ? emptySet() : cur;
    }

    /**
     * Returns the given map, or an immutable empty map if the provided map is null
     *
     * This can be used to guarantee null-safety without paying the price of extra allocations
     *
     * @see Collections#emptyMap
     */
    public static @NonNull <K, V> Map<K, V> emptyIfNull(@Nullable Map<K, V> cur) {
        return cur == null ? Collections.emptyMap() : cur;
    }

    /**
     * Returns the size of the given collection, or 0 if null
     */
    public static int size(@Nullable Collection<?> cur) {
        return cur != null ? cur.size() : 0;
    }

    /**
     * Returns the size of the given map, or 0 if null
     */
    public static int size(@Nullable Map<?, ?> cur) {
        return cur != null ? cur.size() : 0;
    }

    /**
     * Returns whether the given collection {@link Collection#isEmpty is empty} or {@code null}
     */
    public static boolean isEmpty(@Nullable Collection<?> cur) {
        return size(cur) == 0;
    }

    /**
     * Returns the elements of the given list that are of type {@code c}
     */
    public static @NonNull <T> List<T> filter(@Nullable List<?> list, Class<T> c) {
        if (isEmpty(list)) return Collections.emptyList();
        ArrayList<T> result = null;
        for (int i = 0; i < list.size(); i++) {
            final Object item = list.get(i);
            if (c.isInstance(item)) {
                result = ArrayUtils.add(result, (T) item);
            }
        }
        return emptyIfNull(result);
    }

    /**
     * Returns whether there exists at least one element in the list for which
     * condition {@code predicate} is true
     */
    public static <T> boolean any(@Nullable List<T> items,
            java.util.function.Predicate<T> predicate) {
        return find(items, predicate) != null;
    }

    /**
     * Returns whether there exists at least one element in the set for which
     * condition {@code predicate} is true
     */
    public static <T> boolean any(@Nullable Set<T> items,
            java.util.function.Predicate<T> predicate) {
        return find(items, predicate) != null;
    }

    /**
     * Returns the first element from the list for which
     * condition {@code predicate} is true, or null if there is no such element
     */
    public static @Nullable <T> T find(@Nullable List<T> items,
            java.util.function.Predicate<T> predicate) {
        if (isEmpty(items)) return null;
        for (int i = 0; i < items.size(); i++) {
            final T item = items.get(i);
            if (predicate.test(item)) return item;
        }
        return null;
    }

    /**
     * Returns the first element from the set for which
     * condition {@code predicate} is true, or null if there is no such element
     */
    public static @Nullable <T> T find(@Nullable Set<T> cur,
            java.util.function.Predicate<T> predicate) {
        if (cur == null || predicate == null) return null;
        int size = cur.size();
        if (size == 0) return null;
        try {
            if (cur instanceof ArraySet) {
                ArraySet<T> arraySet = (ArraySet<T>) cur;
                for (int i = 0; i < size; i++) {
                    T item = arraySet.valueAt(i);
                    if (predicate.test(item)) {
                        return item;
                    }
                }
            } else {
                for (T t : cur) {
                    if (predicate.test(t)) {
                        return t;
                    }
                }
            }
        } catch (Exception e) {
            throw ExceptionUtils.propagate(e);
        }
        return null;
    }

    /**
     * Similar to {@link List#add}, but with support for list values of {@code null} and
     * {@link Collections#emptyList}
     */
    public static @NonNull <T> List<T> add(@Nullable List<T> cur, T val) {
        if (cur == null || cur == Collections.emptyList()) {
            cur = new ArrayList<>();
        }
        cur.add(val);
        return cur;
    }

    /**
     * Similar to {@link List#add(int, Object)}, but with support for list values of {@code null}
     * and {@link Collections#emptyList}
     */
    public static @NonNull <T> List<T> add(@Nullable List<T> cur, int index, T val) {
        if (cur == null || cur == Collections.emptyList()) {
            cur = new ArrayList<>();
        }
        cur.add(index, val);
        return cur;
    }

    /**
     * Similar to {@link Set#addAll(Collection)}}, but with support for list values of {@code null}
     * and {@link Collections#emptySet}
     */
    public static @NonNull <T> Set<T> addAll(@Nullable Set<T> cur, @Nullable Collection<T> val) {
        if (isEmpty(val)) {
            return cur != null ? cur : emptySet();
        }
        if (cur == null || cur == emptySet()) {
            cur = new ArraySet<>();
        }
        cur.addAll(val);
        return cur;
    }

    /**
     * @see #add(List, Object)
     */
    public static @NonNull <T> Set<T> add(@Nullable Set<T> cur, T val) {
        if (cur == null || cur == emptySet()) {
            cur = new ArraySet<>();
        }
        cur.add(val);
        return cur;
    }

    /**
     * @see #add(List, Object)
     */
    public static @NonNull <K, V> Map<K, V> add(@Nullable Map<K, V> map, K key, V value) {
        if (map == null || map == Collections.emptyMap()) {
            map = new ArrayMap<>();
        }
        map.put(key, value);
        return map;
    }

    /**
     * Similar to {@link List#remove}, but with support for list values of {@code null} and
     * {@link Collections#emptyList}
     */
    public static @NonNull <T> List<T> remove(@Nullable List<T> cur, T val) {
        if (isEmpty(cur)) {
            return emptyIfNull(cur);
        }
        cur.remove(val);
        return cur;
    }

    /**
     * @see #remove(List, Object)
     */
    public static @NonNull <T> Set<T> remove(@Nullable Set<T> cur, T val) {
        if (isEmpty(cur)) {
            return emptyIfNull(cur);
        }
        cur.remove(val);
        return cur;
    }

    /**
     * @return a list that will not be affected by mutations to the given original list.
     */
    public static @NonNull <T> List<T> copyOf(@Nullable List<T> cur) {
        return isEmpty(cur) ? Collections.emptyList() : new ArrayList<>(cur);
    }

    /**
     * @return a list that will not be affected by mutations to the given original list.
     */
    public static @NonNull <T> Set<T> copyOf(@Nullable Set<T> cur) {
        return isEmpty(cur) ? emptySet() : new ArraySet<>(cur);
    }

    /**
     * @return a {@link Set} representing the given collection.
     */
    public static @NonNull <T> Set<T> toSet(@Nullable Collection<T> cur) {
        return isEmpty(cur) ? emptySet() : new ArraySet<>(cur);
    }

    /**
     * Applies {@code action} to each element in {@code cur}
     *
     * This avoids creating an iterator if the given set is an {@link ArraySet}
     */
    public static <T> void forEach(@Nullable Set<T> cur, @Nullable ThrowingConsumer<T> action) {
        if (cur == null || action == null) return;
        int size = cur.size();
        if (size == 0) return;
        try {
            if (cur instanceof ArraySet) {
                ArraySet<T> arraySet = (ArraySet<T>) cur;
                for (int i = 0; i < size; i++) {
                    action.acceptOrThrow(arraySet.valueAt(i));
                }
            } else {
                for (T t : cur) {
                    action.acceptOrThrow(t);
                }
            }
        } catch (Exception e) {
            throw ExceptionUtils.propagate(e);
        }
    }

    /**
     * Applies {@code action} to each element in {@code cur}
     *
     * This avoids creating an iterator if the given map is an {@link ArrayMap}
     * For non-{@link ArrayMap}s it avoids creating {@link Map.Entry} instances
     */
    public static <K, V> void forEach(@Nullable Map<K, V> cur, @Nullable BiConsumer<K, V> action) {
        if (cur == null || action == null) {
            return;
        }
        int size = cur.size();
        if (size == 0) {
            return;
        }

        if (cur instanceof ArrayMap) {
            ArrayMap<K, V> arrayMap = (ArrayMap<K, V>) cur;
            for (int i = 0; i < size; i++) {
                action.accept(arrayMap.keyAt(i), arrayMap.valueAt(i));
            }
        } else {
            for (K key : cur.keySet()) {
                action.accept(key, cur.get(key));
            }
        }
    }

    /**
     * @return the first element if not empty/null, null otherwise
     */
    public static @Nullable <T> T firstOrNull(@Nullable List<T> cur) {
        return isEmpty(cur) ? null : cur.get(0);
    }

    /**
     * @return the first element if not empty/null, null otherwise
     */
    public static @Nullable <T> T firstOrNull(@Nullable Collection<T> cur) {
        return isEmpty(cur) ? null : cur.iterator().next();
    }

    /**
     * @return list of single given element if it's not null, empty list otherwise
     */
    public static @NonNull <T> List<T> singletonOrEmpty(@Nullable T item) {
        return item == null ? Collections.emptyList() : Collections.singletonList(item);
    }
}
