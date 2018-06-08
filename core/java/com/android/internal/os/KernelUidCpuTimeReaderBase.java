/*
 * Copyright (C) 2017 The Android Open Source Project
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

package com.android.internal.os;

import android.annotation.Nullable;
import android.os.SystemClock;
import android.util.Slog;

/**
 * The base class of all KernelUidCpuTimeReaders.
 *
 * This class is NOT designed to be thread-safe or accessed by more than one caller (due to
 * the nature of {@link #readDelta(Callback)}).
 */
public abstract class KernelUidCpuTimeReaderBase<T extends KernelUidCpuTimeReaderBase.Callback> {
    protected static final boolean DEBUG = false;
    // Throttle interval in milliseconds
    private static final long DEFAULT_THROTTLE_INTERVAL = 10_000L;

    private final String TAG = this.getClass().getSimpleName();
    private long mLastTimeReadMs = Long.MIN_VALUE;
    private long mThrottleInterval = DEFAULT_THROTTLE_INTERVAL;

    // A generic Callback interface (used by readDelta) to be extended by subclasses.
    public interface Callback {
    }

    public void readDelta(@Nullable T cb) {
        if (SystemClock.elapsedRealtime() < mLastTimeReadMs + mThrottleInterval) {
            if (DEBUG) {
                Slog.d(TAG, "Throttle");
            }
            return;
        }
        readDeltaImpl(cb);
        mLastTimeReadMs = SystemClock.elapsedRealtime();
    }

    protected abstract void readDeltaImpl(@Nullable T cb);

    public void setThrottleInterval(long throttleInterval) {
        if (throttleInterval >= 0) {
            mThrottleInterval = throttleInterval;
        }
    }
}
