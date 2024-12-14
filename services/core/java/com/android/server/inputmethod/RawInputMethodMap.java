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
import android.util.ArrayMap;
import android.util.Slog;
import android.view.inputmethod.InputMethodInfo;

import com.android.internal.inputmethod.DirectBootAwareness;

import java.util.List;

/**
 * This is quite similar to {@link InputMethodMap} with two major differences.
 *
 * <ul>
 *     <li>Additional {@link android.view.inputmethod.InputMethodSubtype} is not included.</li>
 *     <li>Always include direct-boot unaware {@link android.inputmethodservice.InputMethodService}.
 *     </li>
 * </ul>
 *
 * <p>As seen in {@link #toInputMethodMap(AdditionalSubtypeMap, int, boolean)}, you can consider
 * this is a prototype data where you can always derive {@link InputMethodMap} with
 * {@link AdditionalSubtypeMap} and a boolean information whether
 * {@link com.android.server.pm.UserManagerInternal#isUserUnlockingOrUnlocked(int)} returns
 * {@code true} or not.</p>
 */
final class RawInputMethodMap {
    static final String TAG = "RawInputMethodMap";

    private static final ArrayMap<String, InputMethodInfo> EMPTY_MAP = new ArrayMap<>();

    private final ArrayMap<String, InputMethodInfo> mMap;

    static RawInputMethodMap emptyMap() {
        return new RawInputMethodMap(EMPTY_MAP);
    }

    static RawInputMethodMap of(@NonNull ArrayMap<String, InputMethodInfo> map) {
        return new RawInputMethodMap(map);
    }

    private RawInputMethodMap(@NonNull ArrayMap<String, InputMethodInfo> map) {
        mMap = map.isEmpty() ? EMPTY_MAP : new ArrayMap<>(map);
    }

    @AnyThread
    @NonNull
    List<InputMethodInfo> values() {
        return List.copyOf(mMap.values());
    }

    @NonNull
    InputMethodMap toInputMethodMap(@NonNull AdditionalSubtypeMap additionalSubtypeMap,
            @DirectBootAwareness int directBootAwareness, boolean userUnlocked) {
        final int size = mMap.size();
        final var newMap = new ArrayMap<String, InputMethodInfo>(size);

        final boolean requireDirectBootAwareFlag;
        switch (directBootAwareness) {
            case DirectBootAwareness.ANY -> requireDirectBootAwareFlag = false;
            case DirectBootAwareness.AUTO -> requireDirectBootAwareFlag = !userUnlocked;
            default -> {
                requireDirectBootAwareFlag = !userUnlocked;
                Slog.e(TAG, "Unknown directBootAwareness=" + directBootAwareness
                        + ". Falling back to DirectBootAwareness.AUTO");
            }
        }

        boolean updated = false;
        for (int i = 0; i < size; ++i) {
            final var imeId = mMap.keyAt(i);
            final var imi = mMap.valueAt(i);
            if (requireDirectBootAwareFlag && !imi.getServiceInfo().directBootAware) {
                updated = true;
                continue;
            }
            final var newAdditionalSubtypes = additionalSubtypeMap.get(imeId);
            if (newAdditionalSubtypes == null || newAdditionalSubtypes.isEmpty()) {
                newMap.put(imi.getId(), imi);
            } else {
                updated = true;
                newMap.put(imi.getId(), new InputMethodInfo(imi, newAdditionalSubtypes));
            }
        }
        // If newMap is semantically the same as mMap, we can reuse mMap and discard newMap.
        return InputMethodMap.of(updated ? newMap : mMap);
    }
}
