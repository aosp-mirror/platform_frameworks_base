/*
 * Copyright (C) 2024 The Android Open Source Project
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

package com.android.server.inputmethod;

import android.annotation.AnyThread;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.util.ArrayMap;
import android.view.inputmethod.InputMethodSubtype;

import java.util.Collection;
import java.util.List;

/**
 * An on-memory immutable data representation of subtype.xml, which contains so-called additional
 * {@link InputMethodSubtype}.
 *
 * <p>While the data structure could be also used for general purpose map from IME ID to
 * a list of {@link InputMethodSubtype}, unlike {@link InputMethodMap} this particular data
 * structure is currently used only around additional {@link InputMethodSubtype}, which is why this
 * class is (still) called {@code AdditionalSubtypeMap} rather than {@code InputMethodSubtypeMap}.
 * </p>
 */
final class AdditionalSubtypeMap {
    /**
     * An empty {@link AdditionalSubtypeMap}.
     */
    static final AdditionalSubtypeMap EMPTY_MAP = new AdditionalSubtypeMap(new ArrayMap<>());

    @NonNull
    private final ArrayMap<String, List<InputMethodSubtype>> mMap;

    @AnyThread
    @NonNull
    private static AdditionalSubtypeMap createOrEmpty(
            @NonNull ArrayMap<String, List<InputMethodSubtype>> map) {
        return map.isEmpty() ? EMPTY_MAP : new AdditionalSubtypeMap(map);
    }

    /**
     * Create a new instance from the given {@link ArrayMap}.
     *
     * <p>This method effectively creates a new copy of map.</p>
     *
     * @param map An {@link ArrayMap} from which {@link AdditionalSubtypeMap} is to be created.
     * @return A {@link AdditionalSubtypeMap} that contains a new copy of {@code map}.
     */
    @AnyThread
    @NonNull
    static AdditionalSubtypeMap of(@NonNull ArrayMap<String, List<InputMethodSubtype>> map) {
        return createOrEmpty(map);
    }

    /**
     * Create a new instance of {@link AdditionalSubtypeMap} from an existing
     * {@link AdditionalSubtypeMap} by removing {@code key}, or return {@code map} itself if it does
     * not contain an entry of {@code key}.
     *
     * @param key The key to be removed from {@code map}.
     * @return A new instance of {@link AdditionalSubtypeMap}, which is guaranteed to not contain
     *         {@code key}, or {@code map} itself if it does not contain an entry of {@code key}.
     */
    @AnyThread
    @NonNull
    AdditionalSubtypeMap cloneWithRemoveOrSelf(@NonNull String key) {
        if (isEmpty() || !containsKey(key)) {
            return this;
        }
        final ArrayMap<String, List<InputMethodSubtype>> newMap = new ArrayMap<>(mMap);
        newMap.remove(key);
        return createOrEmpty(newMap);
    }

    /**
     * Create a new instance of {@link AdditionalSubtypeMap} from an existing
     * {@link AdditionalSubtypeMap} by removing {@code keys} or return {@code map} itself if it does
     * not contain any entry for {@code keys}.
     *
     * @param keys Keys to be removed from {@code map}.
     * @return A new instance of {@link AdditionalSubtypeMap}, which is guaranteed to not contain
     *         {@code keys}, or {@code map} itself if it does not contain any entry of {@code keys}.
     */
    @AnyThread
    @NonNull
    AdditionalSubtypeMap cloneWithRemoveOrSelf(@NonNull Collection<String> keys) {
        if (isEmpty()) {
            return this;
        }
        final ArrayMap<String, List<InputMethodSubtype>> newMap = new ArrayMap<>(mMap);
        return newMap.removeAll(keys) ? createOrEmpty(newMap) : this;
    }

    /**
     * Create a new instance of {@link AdditionalSubtypeMap} from an existing
     * {@link AdditionalSubtypeMap} by putting {@code key} and {@code value}.
     *
     * @param key Key to be put into {@code map}.
     * @param value Value to be put into {@code map}.
     * @return A new instance of {@link AdditionalSubtypeMap}, which is guaranteed to contain the
     *         pair of {@code key} and {@code value}.
     */
    @AnyThread
    @NonNull
    AdditionalSubtypeMap cloneWithPut(
            @Nullable String key, @NonNull List<InputMethodSubtype> value) {
        final ArrayMap<String, List<InputMethodSubtype>> newMap = new ArrayMap<>(mMap);
        newMap.put(key, value);
        return new AdditionalSubtypeMap(newMap);
    }

    private AdditionalSubtypeMap(@NonNull ArrayMap<String, List<InputMethodSubtype>> map) {
        mMap = map;
    }

    @AnyThread
    @Nullable
    List<InputMethodSubtype> get(@Nullable String key) {
        return mMap.get(key);
    }

    @AnyThread
    boolean containsKey(@Nullable String key) {
        return mMap.containsKey(key);
    }

    @AnyThread
    boolean isEmpty() {
        return mMap.isEmpty();
    }

    @AnyThread
    @NonNull
    Collection<String> keySet() {
        return mMap.keySet();
    }

    @AnyThread
    int size() {
        return mMap.size();
    }
}
