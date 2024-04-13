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
import android.annotation.Nullable;
import android.annotation.UserIdInt;
import android.content.Context;
import android.content.pm.UserInfo;
import android.os.Handler;
import android.util.SparseArray;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.inputmethod.DirectBootAwareness;
import com.android.server.LocalServices;
import com.android.server.pm.UserManagerInternal;

/**
 * Provides accesses to per-user additional {@link android.view.inputmethod.InputMethodSubtype}
 * persistent storages.
 */
final class AdditionalSubtypeMapRepository {
    @GuardedBy("ImfLock.class")
    @NonNull
    private static final SparseArray<AdditionalSubtypeMap> sPerUserMap = new SparseArray<>();

    /**
     * Not intended to be instantiated.
     */
    private AdditionalSubtypeMapRepository() {
    }

    @NonNull
    @GuardedBy("ImfLock.class")
    static AdditionalSubtypeMap get(@UserIdInt int userId) {
        final AdditionalSubtypeMap map = sPerUserMap.get(userId);
        if (map != null) {
            return map;
        }
        final AdditionalSubtypeMap newMap = AdditionalSubtypeUtils.load(userId);
        sPerUserMap.put(userId, newMap);
        return newMap;
    }

    @GuardedBy("ImfLock.class")
    static void putAndSave(@UserIdInt int userId, @NonNull AdditionalSubtypeMap map,
            @NonNull InputMethodMap inputMethodMap) {
        final AdditionalSubtypeMap previous = sPerUserMap.get(userId);
        if (previous == map) {
            return;
        }
        sPerUserMap.put(userId, map);
        // TODO: Offload this to a background thread.
        // TODO: Skip if the previous data is exactly the same as new one.
        AdditionalSubtypeUtils.save(map, inputMethodMap, userId);
    }

    static void initialize(@NonNull Handler handler, @NonNull Context context) {
        final UserManagerInternal userManagerInternal =
                LocalServices.getService(UserManagerInternal.class);
        handler.post(() -> {
            userManagerInternal.addUserLifecycleListener(
                    new UserManagerInternal.UserLifecycleListener() {
                        @Override
                        public void onUserCreated(UserInfo user, @Nullable Object token) {
                            final int userId = user.id;
                            handler.post(() -> {
                                synchronized (ImfLock.class) {
                                    if (!sPerUserMap.contains(userId)) {
                                        final AdditionalSubtypeMap additionalSubtypeMap =
                                                AdditionalSubtypeUtils.load(userId);
                                        sPerUserMap.put(userId, additionalSubtypeMap);
                                        final InputMethodSettings settings =
                                                InputMethodManagerService
                                                        .queryInputMethodServicesInternal(context,
                                                                userId,
                                                                additionalSubtypeMap,
                                                                DirectBootAwareness.AUTO);
                                        InputMethodSettingsRepository.put(userId, settings);
                                    }
                                }
                            });
                        }

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
                    sPerUserMap.put(userId, AdditionalSubtypeUtils.load(userId));
                }
            }
        });
    }
}
