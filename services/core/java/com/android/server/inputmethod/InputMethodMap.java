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
import android.annotation.IntRange;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.util.ArrayMap;
import android.view.inputmethod.InputMethodInfo;

import java.util.Arrays;
import java.util.List;

/**
 * A map from IME ID to {@link InputMethodInfo}, which is guaranteed to be immutable thus
 * thread-safe.
 */
final class InputMethodMap {
    private static final ArrayMap<String, InputMethodInfo> EMPTY_MAP =
            new ArrayMap<>();

    private final ArrayMap<String, InputMethodInfo> mMap;

    static InputMethodMap emptyMap() {
        return new InputMethodMap(EMPTY_MAP);
    }

    static InputMethodMap of(@NonNull ArrayMap<String, InputMethodInfo> map) {
        return new InputMethodMap(map);
    }

    private InputMethodMap(@NonNull ArrayMap<String, InputMethodInfo> map) {
        mMap = map.isEmpty() ? EMPTY_MAP : new ArrayMap<>(map);
    }

    @AnyThread
    @Nullable
    InputMethodInfo get(@Nullable String imeId) {
        return mMap.get(imeId);
    }

    @AnyThread
    @NonNull
    List<InputMethodInfo> values() {
        return List.copyOf(mMap.values());
    }

    @AnyThread
    @Nullable
    InputMethodInfo valueAt(int index) {
        return mMap.valueAt(index);
    }

    @AnyThread
    boolean containsKey(@Nullable String imeId) {
        return mMap.containsKey(imeId);
    }

    @AnyThread
    @IntRange(from = 0)
    int size() {
        return mMap.size();
    }

    @AnyThread
    @NonNull
    public InputMethodMap applyAdditionalSubtypes(
            @NonNull AdditionalSubtypeMap additionalSubtypeMap) {
        if (additionalSubtypeMap.isEmpty()) {
            return this;
        }
        final int size = size();
        final ArrayMap<String, InputMethodInfo> newMethodMap = new ArrayMap<>(size);
        boolean updated = false;
        for (int i = 0; i < size; ++i) {
            final var imi = valueAt(i);
            final var imeId = imi.getId();
            final var newAdditionalSubtypes = additionalSubtypeMap.get(imeId);
            if (newAdditionalSubtypes == null || newAdditionalSubtypes.isEmpty()) {
                newMethodMap.put(imi.getId(), imi);
            } else {
                newMethodMap.put(imi.getId(), new InputMethodInfo(imi, newAdditionalSubtypes));
                updated = true;
            }
        }
        return updated ? InputMethodMap.of(newMethodMap) : this;
    }

    /**
     * Compares the given two {@link InputMethodMap} instances to see if they contain the same data
     * or not.
     *
     * @param map1 {@link InputMethodMap} to be compared with
     * @param map2 {@link InputMethodMap} to be compared with
     * @return {@code true} if both {@link InputMethodMap} instances contain exactly the same data
     */
    @AnyThread
    static boolean areSame(@NonNull InputMethodMap map1, @NonNull InputMethodMap map2) {
        if (map1 == map2) {
            return true;
        }
        final int size = map1.size();
        if (size != map2.size()) {
            return false;
        }
        for (int i = 0; i < size; ++i) {
            final var imi1 = map1.valueAt(i);
            final var imeId = imi1.getId();
            final var imi2 = map2.get(imeId);
            if (imi2 == null) {
                return false;
            }
            final var marshaled1 = InputMethodInfoUtils.marshal(imi1);
            final var marshaled2 = InputMethodInfoUtils.marshal(imi2);
            if (!Arrays.equals(marshaled1, marshaled2)) {
                return false;
            }
        }
        return true;
    }
}
