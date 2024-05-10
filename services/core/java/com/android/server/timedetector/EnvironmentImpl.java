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

import android.annotation.CurrentTimeMillisLong;
import android.annotation.NonNull;
import android.content.Context;
import android.os.Handler;
import android.os.PowerManager;
import android.os.SystemClock;
import android.util.IndentingPrintWriter;
import android.util.Slog;

import com.android.server.AlarmManagerInternal;
import com.android.server.LocalServices;
import com.android.server.SystemClockTime;
import com.android.server.SystemClockTime.TimeConfidence;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

/**
 * The real implementation of {@link TimeDetectorStrategyImpl.Environment} used on device.
 */
final class EnvironmentImpl implements TimeDetectorStrategyImpl.Environment {

    private static final String LOG_TAG = TimeDetectorService.TAG;

    @NonNull private final Handler mHandler;
    @NonNull private final PowerManager.WakeLock mWakeLock;
    @NonNull private final AlarmManagerInternal mAlarmManagerInternal;

    EnvironmentImpl(@NonNull Context context, @NonNull Handler handler) {
        mHandler = Objects.requireNonNull(handler);

        PowerManager powerManager = context.getSystemService(PowerManager.class);
        mWakeLock = Objects.requireNonNull(
                powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, LOG_TAG));

        mAlarmManagerInternal = Objects.requireNonNull(
                LocalServices.getService(AlarmManagerInternal.class));
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
    public @TimeConfidence int systemClockConfidence() {
        return SystemClockTime.getTimeConfidence();
    }

    @Override
    public void setSystemClock(
            @CurrentTimeMillisLong long newTimeMillis, @TimeConfidence int confidence,
            @NonNull String logMsg) {
        checkWakeLockHeld();
        mAlarmManagerInternal.setTime(newTimeMillis, confidence, logMsg);
    }

    @Override
    public void setSystemClockConfidence(@TimeConfidence int confidence, @NonNull String logMsg) {
        checkWakeLockHeld();
        SystemClockTime.setConfidence(confidence, logMsg);
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

    @Override
    public void addDebugLogEntry(@NonNull String logMsg) {
        SystemClockTime.addDebugLogEntry(logMsg);
    }

    @Override
    public void dumpDebugLog(@NonNull IndentingPrintWriter pw) {
        long elapsedRealtimeMillis = elapsedRealtimeMillis();
        pw.printf("elapsedRealtimeMillis()=%s (%s)\n",
                Duration.ofMillis(elapsedRealtimeMillis), elapsedRealtimeMillis);
        long systemClockMillis = systemClockMillis();
        pw.printf("systemClockMillis()=%s (%s)\n",
                Instant.ofEpochMilli(systemClockMillis), systemClockMillis);
        pw.println("systemClockConfidence()=" + systemClockConfidence());

        pw.println("SystemClockTime debug log:");
        pw.increaseIndent();
        SystemClockTime.dump(pw);
        pw.decreaseIndent();
    }

    @Override
    public void runAsync(@NonNull Runnable runnable) {
        mHandler.post(runnable);
    }
}
