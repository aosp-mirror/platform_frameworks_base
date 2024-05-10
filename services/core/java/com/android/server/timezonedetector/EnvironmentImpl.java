/*
 * Copyright 2019 The Android Open Source Project
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

package com.android.server.timezonedetector;

import android.annotation.ElapsedRealtimeLong;
import android.annotation.NonNull;
import android.os.Handler;
import android.os.SystemClock;
import android.os.SystemProperties;

import com.android.server.AlarmManagerInternal;
import com.android.server.LocalServices;
import com.android.server.SystemTimeZone;
import com.android.server.SystemTimeZone.TimeZoneConfidence;

import java.io.PrintWriter;
import java.util.Objects;

/**
 * The real implementation of {@link TimeZoneDetectorStrategyImpl.Environment}.
 */
final class EnvironmentImpl implements TimeZoneDetectorStrategyImpl.Environment {

    private static final String TIMEZONE_PROPERTY = "persist.sys.timezone";

    @NonNull private final Handler mHandler;

    EnvironmentImpl(@NonNull Handler handler) {
        mHandler = Objects.requireNonNull(handler);
    }

    @Override
    @NonNull
    public String getDeviceTimeZone() {
        return SystemProperties.get(TIMEZONE_PROPERTY);
    }

    @Override
    public @TimeZoneConfidence int getDeviceTimeZoneConfidence() {
        return SystemTimeZone.getTimeZoneConfidence();
    }

    @Override
    public void setDeviceTimeZoneAndConfidence(
            @NonNull String zoneId, @TimeZoneConfidence int confidence,
            @NonNull String logInfo) {
        AlarmManagerInternal alarmManagerInternal =
                LocalServices.getService(AlarmManagerInternal.class);
        alarmManagerInternal.setTimeZone(zoneId, confidence, logInfo);
    }

    @Override
    public @ElapsedRealtimeLong long elapsedRealtimeMillis() {
        return SystemClock.elapsedRealtime();
    }

    @Override
    public void addDebugLogEntry(@NonNull String logMsg) {
        SystemTimeZone.addDebugLogEntry(logMsg);
    }

    @Override
    public void dumpDebugLog(@NonNull PrintWriter printWriter) {
        SystemTimeZone.dump(printWriter);
    }

    @Override
    public void runAsync(@NonNull Runnable runnable) {
        mHandler.post(runnable);
    }
}
