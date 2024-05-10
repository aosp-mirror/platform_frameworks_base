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

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Environment;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.annotations.VisibleForTesting;

import java.io.File;

public class MediaProjectionSessionIdGenerator {

    private static final String PREFERENCES_FILE_NAME = "media_projection_session_id";
    private static final String SESSION_ID_PREF_KEY = "media_projection_session_id_key";
    private static final int SESSION_ID_DEFAULT_VALUE = 0;

    private static final Object sInstanceLock = new Object();

    @GuardedBy("sInstanceLock")
    private static MediaProjectionSessionIdGenerator sInstance;

    private final Object mSessionIdLock = new Object();

    @GuardedBy("mSessionIdLock")
    private final SharedPreferences mSharedPreferences;

    /** Creates or returns an existing instance of {@link MediaProjectionSessionIdGenerator}. */
    public static MediaProjectionSessionIdGenerator getInstance(Context context) {
        synchronized (sInstanceLock) {
            if (sInstance == null) {
                File preferencesFile =
                        new File(Environment.getDataSystemDirectory(), PREFERENCES_FILE_NAME);
                // Needed as this class is instantiated before the device is unlocked.
                Context directBootContext = context.createDeviceProtectedStorageContext();
                SharedPreferences preferences =
                        directBootContext.getSharedPreferences(
                                preferencesFile, Context.MODE_PRIVATE);
                sInstance = new MediaProjectionSessionIdGenerator(preferences);
            }
            return sInstance;
        }
    }

    @VisibleForTesting
    public MediaProjectionSessionIdGenerator(SharedPreferences sharedPreferences) {
        this.mSharedPreferences = sharedPreferences;
    }

    /** Returns the current session ID. This value is persisted across reboots. */
    public int getCurrentSessionId() {
        synchronized (mSessionIdLock) {
            return getCurrentSessionIdInternal();
        }
    }

    /**
     * Creates and returns a new session ID. This value will be persisted as the new current session
     * ID, and will be persisted across reboots.
     */
    public int createAndGetNewSessionId() {
        synchronized (mSessionIdLock) {
            int newSessionId = getCurrentSessionId() + 1;
            setSessionIdInternal(newSessionId);
            return newSessionId;
        }
    }

    @GuardedBy("mSessionIdLock")
    private void setSessionIdInternal(int value) {
        mSharedPreferences.edit().putInt(SESSION_ID_PREF_KEY, value).apply();
    }

    @GuardedBy("mSessionIdLock")
    private int getCurrentSessionIdInternal() {
        return mSharedPreferences.getInt(SESSION_ID_PREF_KEY, SESSION_ID_DEFAULT_VALUE);
    }
}
