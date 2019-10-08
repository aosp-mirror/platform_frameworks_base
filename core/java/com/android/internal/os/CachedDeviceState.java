/*
 * Copyright (C) 2018 The Android Open Source Project
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


import android.os.SystemClock;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.annotations.VisibleForTesting;

import java.util.ArrayList;

/**
 * Stores the device state (e.g. charging/on battery, screen on/off) to be shared with
 * the System Server telemetry services.
 *
 * @hide
 */
public class CachedDeviceState {
    private volatile boolean mScreenInteractive;
    private volatile boolean mCharging;
    private final Object mStopwatchesLock = new Object();
    @GuardedBy("mStopwatchLock")
    private final ArrayList<TimeInStateStopwatch> mOnBatteryStopwatches = new ArrayList<>();

    public CachedDeviceState() {
        mCharging = true;
        mScreenInteractive = false;
    }

    @VisibleForTesting
    public CachedDeviceState(boolean isCharging, boolean isScreenInteractive) {
        mCharging = isCharging;
        mScreenInteractive = isScreenInteractive;
    }

    public void setScreenInteractive(boolean screenInteractive) {
        mScreenInteractive = screenInteractive;
    }

    public void setCharging(boolean charging) {
        if (mCharging != charging) {
            mCharging = charging;
            updateStopwatches(/* shouldStart= */ !charging);
        }
    }

    private void updateStopwatches(boolean shouldStart) {
        synchronized (mStopwatchesLock) {
            final int size = mOnBatteryStopwatches.size();
            for (int i = 0; i < size; i++) {
                if (shouldStart) {
                    mOnBatteryStopwatches.get(i).start();
                } else {
                    mOnBatteryStopwatches.get(i).stop();
                }
            }
        }
    }

    public Readonly getReadonlyClient() {
        return new CachedDeviceState.Readonly();
    }

    /**
     * Allows for only a readonly access to the device state.
     */
    public class Readonly {
        public boolean isCharging() {
            return mCharging;
        }

        public boolean isScreenInteractive() {
            return mScreenInteractive;
        }

        /** Creates a {@link TimeInStateStopwatch stopwatch} that tracks the time on battery. */
        public TimeInStateStopwatch createTimeOnBatteryStopwatch() {
            synchronized (mStopwatchesLock) {
                final TimeInStateStopwatch stopwatch = new TimeInStateStopwatch();
                mOnBatteryStopwatches.add(stopwatch);
                if (!mCharging) {
                    stopwatch.start();
                }
                return stopwatch;
            }
        }
    }

    /** Tracks the time the device spent in a given state. */
    public class TimeInStateStopwatch implements AutoCloseable {
        private final Object mLock = new Object();
        @GuardedBy("mLock")
        private long mStartTimeMillis;
        @GuardedBy("mLock")
        private long mTotalTimeMillis;

        /** Returns the time in state since the last call to {@link TimeInStateStopwatch#reset}. */
        public long getMillis() {
            synchronized (mLock) {
                return mTotalTimeMillis + elapsedTime();
            }
        }

        /** Resets the time in state to 0 without stopping the timer if it's started. */
        public void reset() {
            synchronized (mLock) {
                mTotalTimeMillis = 0;
                mStartTimeMillis = isRunning() ? SystemClock.elapsedRealtime() : 0;
            }
        }

        private void start() {
            synchronized (mLock) {
                if (!isRunning()) {
                    mStartTimeMillis = SystemClock.elapsedRealtime();
                }
            }
        }

        private void stop() {
            synchronized (mLock) {
                if (isRunning()) {
                    mTotalTimeMillis += elapsedTime();
                    mStartTimeMillis = 0;
                }
            }
        }

        private long elapsedTime() {
            return isRunning() ? SystemClock.elapsedRealtime() - mStartTimeMillis : 0;
        }

        @VisibleForTesting
        public boolean isRunning() {
            return mStartTimeMillis > 0;
        }

        @Override
        public void close() {
            synchronized (mStopwatchesLock) {
                mOnBatteryStopwatches.remove(this);
            }
        }
    }
}
