/*
 * Copyright (C) 2019 The Android Open Source Project
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

package com.android.internal.infra;

import android.annotation.NonNull;
import android.os.Handler;
import android.os.SystemClock;

import com.android.internal.annotations.GuardedBy;

/**
 * A throttled runnable that can wrap around a runnable and throttle calls to its run().
 *
 * The throttling logic makes sure that the original runnable will be called only after the
 * specified interval passes since the last actual call. The first call in a while (after the
 * specified interval passes since the last actual call) will always result in the original runnable
 * being called immediately, and then subsequent calls will start to be throttled. It is guaranteed
 * that any call to this throttled runnable will always result in the original runnable being called
 * afterwards, within the specified interval.
 */
public class ThrottledRunnable implements Runnable {

    @NonNull
    private final Handler mHandler;
    private final long mIntervalMillis;
    @NonNull
    private final Runnable mRunnable;

    @NonNull
    private final Object mLock = new Object();

    @GuardedBy("mLock")
    private long mScheduledUptimeMillis;

    public ThrottledRunnable(@NonNull Handler handler, long intervalMillis,
            @NonNull Runnable runnable) {
        mHandler = handler;
        mIntervalMillis = intervalMillis;
        mRunnable = runnable;
    }

    @Override
    public void run() {
        synchronized (mLock) {
            if (mHandler.hasCallbacks(mRunnable)) {
                // We have a scheduled runnable.
                return;
            }
            long currentUptimeMillis = SystemClock.uptimeMillis();
            if (mScheduledUptimeMillis == 0
                    || currentUptimeMillis > mScheduledUptimeMillis + mIntervalMillis) {
                // First time in a while, schedule immediately.
                mScheduledUptimeMillis = currentUptimeMillis;
            } else {
                // We were scheduled not long ago, so schedule with delay for throttling.
                mScheduledUptimeMillis = mScheduledUptimeMillis + mIntervalMillis;
            }
            mHandler.postAtTime(mRunnable, mScheduledUptimeMillis);
        }
    }
}
