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

package com.android.server.tare;

import android.util.Log;

import com.android.internal.annotations.GuardedBy;

/**
 * Maintains the current TARE state and handles writing it to disk and reading it back from disk.
 */
public class Scribe {
    private static final String TAG = "TARE-" + Scribe.class.getSimpleName();
    private static final boolean DEBUG = InternalResourceService.DEBUG
            || Log.isLoggable(TAG, Log.DEBUG);

    private final InternalResourceService mIrs;

    @GuardedBy("mIrs.mLock")
    private long mLastReclamationTime;

    Scribe(InternalResourceService irs) {
        mIrs = irs;
    }

    @GuardedBy("mIrs.mLock")
    long getLastReclamationTimeLocked() {
        return mLastReclamationTime;
    }

    @GuardedBy("InternalResourceService.mLock")
    void setLastReclamationTimeLocked(long time) {
        mLastReclamationTime = time;
    }

    @GuardedBy("mIrs.mLock")
    void tearDownLocked() {
        mLastReclamationTime = 0;
    }
}
