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
import android.os.Build;
import android.os.Handler;
import android.os.PowerManager;
import android.os.SystemClock;
import android.os.UserManager;
import android.util.Slog;

import com.android.server.timezonedetector.ConfigurationChangeListener;

import java.time.Instant;
import java.util.Objects;

/**
 * The real implementation of {@link TimeDetectorStrategyImpl.Environment} used on device.
 */
final class EnvironmentImpl implements TimeDetectorStrategyImpl.Environment {

    private static final String LOG_TAG = TimeDetectorService.TAG;

    @NonNull private final Context mContext;
    @NonNull private final Handler mHandler;
    @NonNull private final ServiceConfigAccessor mServiceConfigAccessor;
    @NonNull private final ContentResolver mContentResolver;
    @NonNull private final PowerManager.WakeLock mWakeLock;
    @NonNull private final AlarmManager mAlarmManager;
    @NonNull private final UserManager mUserManager;

    EnvironmentImpl(@NonNull Context context, @NonNull Handler handler,
            @NonNull ServiceConfigAccessor serviceConfigAccessor) {
        mContext = Objects.requireNonNull(context);
        mContentResolver = Objects.requireNonNull(context.getContentResolver());
        mHandler = Objects.requireNonNull(handler);
        mServiceConfigAccessor = Objects.requireNonNull(serviceConfigAccessor);

        PowerManager powerManager = context.getSystemService(PowerManager.class);
        mWakeLock = Objects.requireNonNull(
                powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, LOG_TAG));

        mAlarmManager = Objects.requireNonNull(context.getSystemService(AlarmManager.class));

        mUserManager = Objects.requireNonNull(context.getSystemService(UserManager.class));
    }

    @Override
    public void setConfigurationInternalChangeListener(
            @NonNull ConfigurationChangeListener listener) {
        ConfigurationChangeListener configurationChangeListener =
                () -> mHandler.post(listener::onChange);
        mServiceConfigAccessor.addConfigurationInternalChangeListener(configurationChangeListener);
    }

    @Override
    public int systemClockUpdateThresholdMillis() {
        return mServiceConfigAccessor.systemClockUpdateThresholdMillis();
    }

    @Override
    public Instant autoTimeLowerBound() {
        return mServiceConfigAccessor.autoTimeLowerBound();
    }

    @Override
    public int[] autoOriginPriorities() {
        return mServiceConfigAccessor.getOriginPriorities();
    }

    @Override
    public ConfigurationInternal getCurrentUserConfigurationInternal() {
        return mServiceConfigAccessor.getCurrentUserConfigurationInternal();
    }

    @Override
    public void acquireWakeLock() {
        if (mWakeLock.isHeld()) {
            Slog.wtf(LOG_TAG, "WakeLock " + mWakeLock + " already held");
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

    @Override
    public boolean deviceHasY2038Issue() {
        return Build.SUPPORTED_32_BIT_ABIS.length > 0;
    }

    private void checkWakeLockHeld() {
        if (!mWakeLock.isHeld()) {
            Slog.wtf(LOG_TAG, "WakeLock " + mWakeLock + " not held");
        }
    }
}
