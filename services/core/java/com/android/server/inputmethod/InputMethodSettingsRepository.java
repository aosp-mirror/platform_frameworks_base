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

import android.annotation.NonNull;
import android.annotation.UserIdInt;
import android.content.Context;
import android.content.pm.UserInfo;
import android.os.Handler;
import android.util.SparseArray;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.inputmethod.DirectBootAwareness;
import com.android.server.LocalServices;
import com.android.server.pm.UserManagerInternal;

final class InputMethodSettingsRepository {
    @GuardedBy("ImfLock.class")
    @NonNull
    private static final SparseArray<InputMethodSettings> sPerUserMap = new SparseArray<>();

    /**
     * Not intended to be instantiated.
     */
    private InputMethodSettingsRepository() {
    }

    @NonNull
    @GuardedBy("ImfLock.class")
    static InputMethodSettings get(@UserIdInt int userId) {
        final InputMethodSettings obj = sPerUserMap.get(userId);
        if (obj != null) {
            return obj;
        }
        return InputMethodSettings.createEmptyMap(userId);
    }

    @GuardedBy("ImfLock.class")
    static void put(@UserIdInt int userId, @NonNull InputMethodSettings obj) {
        sPerUserMap.put(userId, obj);
    }

    static void initialize(@NonNull Handler handler, @NonNull Context context) {
        final UserManagerInternal userManagerInternal =
                LocalServices.getService(UserManagerInternal.class);
        handler.post(() -> {
            userManagerInternal.addUserLifecycleListener(
                    new UserManagerInternal.UserLifecycleListener() {
                        @Override
                        public void onUserRemoved(UserInfo user) {
                            final int userId = user.id;
                            handler.post(() -> {
                                synchronized (ImfLock.class) {
                                    sPerUserMap.remove(userId);
                                }
                            });
                        }
                    });
            synchronized (ImfLock.class) {
                for (int userId : userManagerInternal.getUserIds()) {
                    final InputMethodSettings settings =
                            InputMethodManagerService.queryInputMethodServicesInternal(
                                    context,
                                    userId,
                                    AdditionalSubtypeMapRepository.get(userId),
                                    DirectBootAwareness.AUTO);
                    sPerUserMap.put(userId, settings);
                }
            }
        });
    }
}
