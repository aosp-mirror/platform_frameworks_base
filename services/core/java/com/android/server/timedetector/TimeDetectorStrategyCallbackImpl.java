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
import android.content.ContentResolver;
import android.content.Context;
import android.os.PowerManager;
import android.os.SystemClock;
import android.os.SystemProperties;
import android.provider.Settings;
import android.util.Slog;

import java.util.Objects;

/**
 * The real implementation of {@link TimeDetectorStrategy.Callback} used on device.
 */
public final class TimeDetectorStrategyCallbackImpl implements TimeDetectorStrategy.Callback {

    private final static String TAG = "timedetector.TimeDetectorStrategyCallbackImpl";

    private static final int SYSTEM_CLOCK_UPDATE_THRESHOLD_MILLIS_DEFAULT = 2 * 1000;

    /**
     * If a newly calculated system clock time and the current system clock time differs by this or
     * more the system clock will actually be updated. Used to prevent the system clock being set
     * for only minor differences.
     */
    private final int mSystemClockUpdateThresholdMillis;

    @NonNull private final Context mContext;
    @NonNull private final ContentResolver mContentResolver;
    @NonNull private final PowerManager.WakeLock mWakeLock;
    @NonNull private final AlarmManager mAlarmManager;

    public TimeDetectorStrategyCallbackImpl(@NonNull Context context) {
        mContext = Objects.requireNonNull(context);
        mContentResolver = Objects.requireNonNull(context.getContentResolver());

        PowerManager powerManager = context.getSystemService(PowerManager.class);
        mWakeLock = Objects.requireNonNull(
                powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, TAG));

        mAlarmManager = Objects.requireNonNull(context.getSystemService(AlarmManager.class));

        mSystemClockUpdateThresholdMillis =
                SystemProperties.getInt("ro.sys.time_detector_update_diff",
                        SYSTEM_CLOCK_UPDATE_THRESHOLD_MILLIS_DEFAULT);
    }

    @Override
    public int systemClockUpdateThresholdMillis() {
        return mSystemClockUpdateThresholdMillis;
    }

    @Override
    public boolean isAutoTimeDetectionEnabled() {
        try {
            return Settings.Global.getInt(mContentResolver, Settings.Global.AUTO_TIME) != 0;
        } catch (Settings.SettingNotFoundException snfe) {
            return true;
        }
    }

    @Override
    public void acquireWakeLock() {
        if (mWakeLock.isHeld()) {
            Slog.wtf(TAG, "WakeLock " + mWakeLock + " already held");
        }
        mWakeLock.acquire();
    }

    @Override
    public long elapsedRealtimeMillis() {
        return SystemClock.elapsedRealtime();
    }

    @Override
    public long systemClockMillis() {
        return System.currentTimeMillis();
    }

    @Override
    public void setSystemClock(long newTimeMillis) {
        checkWakeLockHeld();
        mAlarmManager.setTime(newTimeMillis);
    }

    @Override
    public void releaseWakeLock() {
        checkWakeLockHeld();
        mWakeLock.release();
    }

    private void checkWakeLockHeld() {
        if (!mWakeLock.isHeld()) {
            Slog.wtf(TAG, "WakeLock " + mWakeLock + " not held");
        }
    }
}
