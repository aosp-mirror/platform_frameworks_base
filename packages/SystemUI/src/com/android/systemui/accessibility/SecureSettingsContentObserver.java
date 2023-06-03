/*
 * Copyright (C) 2021 The Android Open Source Project
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

package com.android.systemui.accessibility;

import android.content.ContentResolver;
import android.content.Context;
import android.database.ContentObserver;
import android.os.Handler;
import android.os.Looper;
import android.os.UserHandle;
import android.provider.Settings;

import androidx.annotation.NonNull;

import com.android.internal.annotations.VisibleForTesting;
import com.android.systemui.settings.UserTracker;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Provides basic methods for adding, removing arbitrary listeners and inquiry given {@code
 * secureSettingsKey} value; it must comes from {@link Settings.Secure}.
 *
 * This abstract class is intended to be subclassed and specialized to maintain
 * a registry of listeners of specific types and dispatch changes to them.
 *
 * @param <T> The listener type
 */
public abstract class SecureSettingsContentObserver<T> {

    private final ContentResolver mContentResolver;
    private final UserTracker mUserTracker;
    @VisibleForTesting
    final ContentObserver mContentObserver;

    private final String mKey;

    @VisibleForTesting
    final List<T> mListeners = new ArrayList<>();

    protected SecureSettingsContentObserver(Context context, UserTracker userTracker,
            String secureSettingsKey) {
        mKey = secureSettingsKey;
        mContentResolver = context.getContentResolver();
        mUserTracker = userTracker;
        mContentObserver = new ContentObserver(new Handler(Looper.getMainLooper())) {
            @Override
            public void onChange(boolean selfChange) {
                updateValueChanged();
            }
        };
    }

    /**
     * Registers a listener to receive updates from given settings key {@code secureSettingsKey}.
     *
     * @param listener A listener to be added to receive the changes
     */
    public void addListener(@NonNull T listener) {
        Objects.requireNonNull(listener, "listener must be non-null");

        if (!mListeners.contains(listener)) {
            mListeners.add(listener);
        }

        if (mListeners.size() == 1) {
            mContentResolver.registerContentObserver(
                    Settings.Secure.getUriFor(mKey), /* notifyForDescendants= */
                    false, mContentObserver, UserHandle.USER_ALL);
        }
    }

    /**
     * Unregisters a listener previously registered with {@link #addListener(T listener)}.
     *
     * @param listener A listener to be removed from receiving the changes
     */
    public void removeListener(@NonNull T listener) {
        Objects.requireNonNull(listener, "listener must be non-null");

        mListeners.remove(listener);

        if (mListeners.isEmpty()) {
            mContentResolver.unregisterContentObserver(mContentObserver);
        }
    }

    /**
     * Gets the value from the current user's secure settings.
     *
     * See {@link Settings.Secure}.
     */
    public final String getSettingsValue() {
        return Settings.Secure.getStringForUser(mContentResolver, mKey, mUserTracker.getUserId());
    }

    private void updateValueChanged() {
        final String value = getSettingsValue();
        final int listenerSize = mListeners.size();
        for (int i = 0; i < listenerSize; i++) {
            onValueChanged(mListeners.get(i), value);
        }
    }

    /**
     * Called when the registered value from {@code secureSettingsKey} changes.
     *
     * @param listener A listener could be used to receive the updates
     * @param value Content changed value from {@code secureSettingsKey}
     */
    abstract void onValueChanged(T listener, String value);
}
