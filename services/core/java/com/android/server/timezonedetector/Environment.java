/*
 * Copyright 2025 The Android Open Source Project
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

import android.annotation.CurrentTimeMillisLong;
import android.annotation.ElapsedRealtimeLong;
import android.annotation.NonNull;

import com.android.server.SystemTimeZone;

import java.io.PrintWriter;

/**
 * Used by the time zone detector code to interact with device state besides that available from
 * {@link ServiceConfigAccessor}. It can be faked for testing.
 */
public interface Environment {

    /**
     * Returns the device's currently configured time zone. May return an empty string.
     */
    @NonNull
    String getDeviceTimeZone();

    /**
     * Returns the confidence of the device's current time zone.
     */
    @SystemTimeZone.TimeZoneConfidence
    int getDeviceTimeZoneConfidence();

    /**
     * Sets the device's time zone, associated confidence, and records a debug log entry.
     */
    void setDeviceTimeZoneAndConfidence(
            @NonNull String zoneId, @SystemTimeZone.TimeZoneConfidence int confidence,
            @NonNull String logInfo);

    /**
     * Returns the time according to the elapsed realtime clock, the same as {@link
     * android.os.SystemClock#elapsedRealtime()}.
     */
    @ElapsedRealtimeLong
    long elapsedRealtimeMillis();

    /**
     * Returns the current time in milliseconds, the same as
     * {@link java.lang.System#currentTimeMillis()}.
     */
    @CurrentTimeMillisLong
    long currentTimeMillis();

    /**
     * Adds a standalone entry to the time zone debug log.
     */
    void addDebugLogEntry(@NonNull String logMsg);

    /**
     * Dumps the time zone debug log to the supplied {@link PrintWriter}.
     */
    void dumpDebugLog(PrintWriter printWriter);

    /**
     * Requests that the supplied runnable be invoked asynchronously.
     */
    void runAsync(@NonNull Runnable runnable);
}
