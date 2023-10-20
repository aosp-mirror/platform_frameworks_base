/*
 * Copyright (C) 2023 The Android Open Source Project
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

package com.android.server.media.projection;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Environment;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.annotations.VisibleForTesting;

import java.io.File;
import java.time.Duration;
import java.time.Instant;
import java.time.InstantSource;

/** Stores timestamps of media projection sessions. */
public class MediaProjectionTimestampStore {
    private static final String PREFERENCES_FILE_NAME = "media_projection_timestamp";
    private static final String TIMESTAMP_PREF_KEY = "media_projection_timestamp_key";
    private static final Object sInstanceLock = new Object();

    @GuardedBy("sInstanceLock")
    private static MediaProjectionTimestampStore sInstance;

    private final Object mTimestampLock = new Object();

    @GuardedBy("mTimestampLock")
    private final SharedPreferences mSharedPreferences;

    private final InstantSource mInstantSource;

    @VisibleForTesting
    public MediaProjectionTimestampStore(
            SharedPreferences sharedPreferences, InstantSource instantSource) {
        this.mSharedPreferences = sharedPreferences;
        this.mInstantSource = instantSource;
    }

    /** Creates or returns an existing instance of {@link MediaProjectionTimestampStore}. */
    public static MediaProjectionTimestampStore getInstance(Context context) {
        synchronized (sInstanceLock) {
            if (sInstance == null) {
                File preferencesFile =
                        new File(Environment.getDataSystemDirectory(), PREFERENCES_FILE_NAME);
                // Needed as this class is instantiated before the device is unlocked.
                Context directBootContext = context.createDeviceProtectedStorageContext();
                SharedPreferences preferences =
                        directBootContext.getSharedPreferences(
                                preferencesFile, Context.MODE_PRIVATE);
                sInstance = new MediaProjectionTimestampStore(preferences, InstantSource.system());
            }
            return sInstance;
        }
    }

    /**
     * Returns the time that has passed since the last active session, or {@code null} if there was
     * no last active session.
     */
    @Nullable
    public Duration timeSinceLastActiveSession() {
        synchronized (mTimestampLock) {
            Instant lastActiveSessionTimestamp = getLastActiveSessionTimestamp();
            if (lastActiveSessionTimestamp == null) {
                return null;
            }
            Instant now = mInstantSource.instant();
            return Duration.between(lastActiveSessionTimestamp, now);
        }
    }

    /** Registers that the current active session ended now. */
    public void registerActiveSessionEnded() {
        synchronized (mTimestampLock) {
            Instant now = mInstantSource.instant();
            setLastActiveSessionTimestamp(now);
        }
    }

    @GuardedBy("mTimestampLock")
    @Nullable
    private Instant getLastActiveSessionTimestamp() {
        long lastActiveSessionEpochMilli =
                mSharedPreferences.getLong(TIMESTAMP_PREF_KEY, /* defValue= */ -1);
        if (lastActiveSessionEpochMilli == -1) {
            return null;
        }
        return Instant.ofEpochMilli(lastActiveSessionEpochMilli);
    }

    @GuardedBy("mTimestampLock")
    private void setLastActiveSessionTimestamp(@NonNull Instant timestamp) {
        mSharedPreferences.edit().putLong(TIMESTAMP_PREF_KEY, timestamp.toEpochMilli()).apply();
    }
}
