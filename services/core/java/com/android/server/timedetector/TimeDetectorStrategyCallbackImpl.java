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

package com.android.server.timedetector;

import android.annotation.NonNull;
import android.app.AlarmManager;
import android.content.Context;
import android.os.PowerManager;
import android.os.SystemClock;
import android.util.Slog;
import android.util.TimestampedValue;

import java.time.Clock;

/**
 * The real implementation of {@link TimeDetectorStrategy.Callback} used on device.
 */
public class TimeDetectorStrategyCallbackImpl implements TimeDetectorStrategy.Callback {

    private final static String TAG = "timedetector.TimeDetectorStrategyCallbackImpl";

    @NonNull private PowerManager.WakeLock mWakeLock;
    @NonNull private AlarmManager mAlarmManager;
    @NonNull private Clock mElapsedRealtimeClock;

    public TimeDetectorStrategyCallbackImpl(Context context) {
        PowerManager powerManager = context.getSystemService(PowerManager.class);

        mWakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, TAG);

        mAlarmManager = context.getSystemService(AlarmManager.class);
        mElapsedRealtimeClock = SystemClock.elapsedRealtimeClock();
    }

    @Override
    public void setTime(TimestampedValue<Long> time) {
        mWakeLock.acquire();
        try {
            long elapsedRealtimeMillis = mElapsedRealtimeClock.millis();
            long currentTimeMillis = TimeDetectorStrategy.getTimeAt(time, elapsedRealtimeMillis);
            Slog.d(TAG, "Setting system clock using time=" + time
                    + ", elapsedRealtimeMillis=" + elapsedRealtimeMillis);
            mAlarmManager.setTime(currentTimeMillis);
        } finally {
            mWakeLock.release();
        }
    }
}
