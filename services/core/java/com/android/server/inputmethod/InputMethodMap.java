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
}
