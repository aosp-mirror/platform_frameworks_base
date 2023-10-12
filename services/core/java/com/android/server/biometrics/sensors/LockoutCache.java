/*
 * Copyright (C) 2020 The Android Open Source Project
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

package com.android.server.biometrics.sensors;

import android.util.Slog;
import android.util.SparseIntArray;

/**
 * For a single sensor, caches lockout states for all users.
 */
public class LockoutCache implements LockoutTracker {
    private static final String TAG = "LockoutCache";

    // Map of userId to LockoutMode
    private final SparseIntArray mUserLockoutStates;

    public LockoutCache() {
        mUserLockoutStates = new SparseIntArray();
    }

    @Override
    public void setLockoutModeForUser(int userId, @LockoutMode int mode) {
        Slog.d(TAG, "Lockout for user: " + userId +  " is " + mode);
        synchronized (this) {
            mUserLockoutStates.put(userId, mode);
        }
    }

    @Override
    public int getLockoutModeForUser(int userId) {
        synchronized (this) {
            return mUserLockoutStates.get(userId, LOCKOUT_NONE);
        }
    }
}
