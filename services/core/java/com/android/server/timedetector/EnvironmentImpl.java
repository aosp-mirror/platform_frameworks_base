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
import android.annotation.UserIdInt;
import android.app.AlarmManager;
import android.content.ContentResolver;
import android.content.Context;
import android.database.ContentObserver;
import android.os.Handler;
import android.os.PowerManager;
import android.os.SystemClock;
import android.os.UserHandle;
import android.os.UserManager;
import android.provider.Settings;
import android.util.Slog;

import com.android.internal.annotations.GuardedBy;
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

    // @NonNull after setConfigChangeListener() is called.
    @GuardedBy("this")
    private ConfigurationChangeListener mConfigChangeListener;

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

        // Wire up the config change listeners. All invocations are performed on the mHandler
        // thread.

        ContentResolver contentResolver = context.getContentResolver();
        contentResolver.registerContentObserver(
                Settings.Global.getUriFor(Settings.Global.AUTO_TIME), true,
                new ContentObserver(mHandler) {
                    @Override
                    public void onChange(boolean selfChange) {
                        handleAutoTimeDetectionChangedOnHandlerThread();
                    }
                });
        mServiceConfigAccessor.addListener(
                () -> mHandler.post(
                        EnvironmentImpl.this::handleAutoTimeDetectionChangedOnHandlerThread));
    }

    /** Internal method for handling the auto time setting being changed. */
    private void handleAutoTimeDetectionChangedOnHandlerThread() {
        synchronized (this) {
            if (mConfigChangeListener == null) {
                Slog.wtf(LOG_TAG, "mConfigChangeListener is unexpectedly null");
            }
            mConfigChangeListener.onChange();
        }
    }

    @Override
    public void setConfigChangeListener(@NonNull ConfigurationChangeListener listener) {
        synchronized (this) {
            mConfigChangeListener = Objects.requireNonNull(listener);
        }
    }

    @Override
    public int systemClockUpdateThresholdMillis() {
        return mServiceConfigAccessor.systemClockUpdateThresholdMillis();
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
        return mServiceConfigAccessor.autoTimeLowerBound();
    }

    @Override
    public int[] autoOriginPriorities() {
        return mServiceConfigAccessor.getOriginPriorities();
    }

    @Override
    public ConfigurationInternal configurationInternal(@UserIdInt int userId) {
        return new ConfigurationInternal.Builder(userId)
                .setUserConfigAllowed(isUserConfigAllowed(userId))
                .setAutoDetectionEnabled(isAutoTimeDetectionEnabled())
                .build();
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

    private void checkWakeLockHeld() {
        if (!mWakeLock.isHeld()) {
            Slog.wtf(LOG_TAG, "WakeLock " + mWakeLock + " not held");
        }
    }

    private boolean isUserConfigAllowed(@UserIdInt int userId) {
        UserHandle userHandle = UserHandle.of(userId);
        return !mUserManager.hasUserRestriction(UserManager.DISALLOW_CONFIG_DATE_TIME, userHandle);
    }
}
