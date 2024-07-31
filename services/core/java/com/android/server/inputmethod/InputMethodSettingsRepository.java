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
import android.annotation.UserIdInt;

final class InputMethodSettingsRepository {
    private static final Object sMutationLock = new Object();

    @NonNull
    private static volatile ImmutableSparseArray<InputMethodSettings> sPerUserMap =
            ImmutableSparseArray.empty();

    /**
     * Not intended to be instantiated.
     */
    private InputMethodSettingsRepository() {
    }

    @NonNull
    @AnyThread
    static InputMethodSettings get(@UserIdInt int userId) {
        final InputMethodSettings obj = sPerUserMap.get(userId);
        if (obj != null) {
            return obj;
        }
        return InputMethodSettings.createEmptyMap(userId);
    }

    @AnyThread
    static void put(@UserIdInt int userId, @NonNull InputMethodSettings obj) {
        synchronized (sMutationLock) {
            sPerUserMap = sPerUserMap.cloneWithPutOrSelf(userId, obj);
        }
    }

    @AnyThread
    static void remove(@UserIdInt int userId) {
        synchronized (sMutationLock) {
            sPerUserMap = sPerUserMap.cloneWithRemoveOrSelf(userId);
        }
    }
}
