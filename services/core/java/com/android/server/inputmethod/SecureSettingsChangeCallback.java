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
import android.content.ContentResolver;
import android.database.ContentObserver;
import android.net.Uri;
import android.os.Handler;
import android.os.UserHandle;
import android.provider.Settings;
import android.util.ArrayMap;

import java.util.Collection;

/**
 * A wrapper interface to monitor the given set of {@link Settings.Secure}.
 */
@FunctionalInterface
interface SecureSettingsChangeCallback {
    /**
     * Called back when the value associated with {@code key} is updated.
     *
     * @param key a key defined in {@link Settings.Secure}
     * @param flags flags defined in {@link ContentResolver.NotifyFlags}
     * @param userId the user ID with which the value is associated
     */
    void onChange(@NonNull String key, @ContentResolver.NotifyFlags int flags,
            @UserIdInt int userId);

    /**
     * Registers {@link SecureSettingsChangeCallback} to the given set of {@link Settings.Secure}.
     *
     * @param handler  {@link Handler} to be used to call back {@link #onChange(String, int, int)}
     * @param resolver {@link ContentResolver} with which {@link Settings.Secure} will be retrieved
     * @param keys     A set of {@link Settings.Secure} to be monitored
     * @param callback {@link SecureSettingsChangeCallback} to be called back
     */
    @NonNull
    static void register(@NonNull Handler handler, @NonNull ContentResolver resolver,
            @NonNull String[] keys, @NonNull SecureSettingsChangeCallback callback) {
        final ArrayMap<Uri, String> uriMapper = new ArrayMap<>();
        for (String key : keys) {
            uriMapper.put(Settings.Secure.getUriFor(key), key);
        }
        final ContentObserver observer = new ContentObserver(handler) {
            @Override
            public void onChange(boolean selfChange, @NonNull Collection<Uri> uris, int flags,
                    @UserIdInt int userId) {
                uris.forEach(uri -> {
                    final String key = uriMapper.get(uri);
                    if (key != null) {
                        callback.onChange(key, flags, userId);
                    }
                });
            }
        };
        for (Uri uri : uriMapper.keySet()) {
            resolver.registerContentObserverAsUser(uri, false /* notifyForDescendants */, observer,
                    UserHandle.ALL);
        }
    }
}
