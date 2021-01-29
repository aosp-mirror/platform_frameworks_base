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

import static com.android.server.timedetector.TimeDetectorStrategy.ORIGIN_NETWORK;
import static com.android.server.timedetector.TimeDetectorStrategy.ORIGIN_TELEPHONY;
import static com.android.server.timedetector.TimeDetectorStrategy.stringToOrigin;

import android.annotation.NonNull;
import android.app.AlarmManager;
import android.content.ContentResolver;
import android.content.Context;
import android.os.Build;
import android.os.PowerManager;
import android.os.SystemClock;
import android.os.SystemProperties;
import android.provider.Settings;
import android.util.Slog;

import com.android.internal.R;
import com.android.server.timedetector.TimeDetectorStrategy.Origin;

import java.time.Instant;
import java.util.Objects;

/**
 * The real implementation of {@link TimeDetectorStrategyImpl.Environment} used on device.
 */
public final class EnvironmentImpl implements TimeDetectorStrategyImpl.Environment {

    private static final String TAG = TimeDetectorService.TAG;

    private static final int SYSTEM_CLOCK_UPDATE_THRESHOLD_MILLIS_DEFAULT = 2 * 1000;

    /**
     * Time in the past. If automatic time suggestion is before this point, it's
     * incorrect for sure.
     */
    private static final Instant TIME_LOWER_BOUND = Instant.ofEpochMilli(
            Long.max(android.os.Environment.getRootDirectory().lastModified(), Build.TIME));

    /**
     * By default telephony and network only suggestions are accepted and telephony takes
     * precedence over network.
     */
    private static final @Origin int[] DEFAULT_AUTOMATIC_TIME_ORIGIN_PRIORITIES =
            { ORIGIN_TELEPHONY, ORIGIN_NETWORK };

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
    @NonNull private final int[] mOriginPriorities;

    public EnvironmentImpl(@NonNull Context context) {
        mContext = Objects.requireNonNull(context);
        mContentResolver = Objects.requireNonNull(context.getContentResolver());

        PowerManager powerManager = context.getSystemService(PowerManager.class);
        mWakeLock = Objects.requireNonNull(
                powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, TAG));

        mAlarmManager = Objects.requireNonNull(context.getSystemService(AlarmManager.class));

        mSystemClockUpdateThresholdMillis =
                SystemProperties.getInt("ro.sys.time_detector_update_diff",
                        SYSTEM_CLOCK_UPDATE_THRESHOLD_MILLIS_DEFAULT);

        mOriginPriorities = getOriginPriorities(context);
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
    public Instant autoTimeLowerBound() {
        return TIME_LOWER_BOUND;
    }

    @Override
    public int[] autoOriginPriorities() {
        return mOriginPriorities;
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

    private static int[] getOriginPriorities(@NonNull Context context) {
        String[] originStrings =
                context.getResources().getStringArray(R.array.config_autoTimeSourcesPriority);
        if (originStrings.length == 0) {
            return DEFAULT_AUTOMATIC_TIME_ORIGIN_PRIORITIES;
        } else {
            int[] origins = new int[originStrings.length];
            for (int i = 0; i < originStrings.length; i++) {
                int origin = stringToOrigin(originStrings[i]);
                origins[i] = origin;
            }

            return origins;
        }
    }
}
