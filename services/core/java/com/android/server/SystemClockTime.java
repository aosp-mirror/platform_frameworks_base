/*
 * Copyright 2022 The Android Open Source Project
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
package com.android.server;

import static java.lang.annotation.ElementType.TYPE_USE;
import static java.lang.annotation.RetentionPolicy.SOURCE;

import android.annotation.CurrentTimeMillisLong;
import android.annotation.IntDef;
import android.annotation.NonNull;
import android.os.Build;
import android.os.Environment;
import android.os.SystemProperties;
import android.util.LocalLog;
import android.util.Slog;

import java.io.PrintWriter;
import java.lang.annotation.Retention;
import java.lang.annotation.Target;

/**
 * A set of static methods that encapsulate knowledge of how the system clock time and associated
 * metadata are stored on Android.
 */
public final class SystemClockTime {

    private static final String TAG = "SystemClockTime";

    /**
     * A log that records the decisions / decision metadata that affected the device's system clock
     * time. This is logged in bug reports to assist with debugging issues with time.
     */
    @NonNull
    private static final LocalLog sTimeDebugLog =
            new LocalLog(30, false /* useLocalTimestamps */);


    /**
     * An annotation that indicates a "time confidence" value is expected.
     *
     * <p>The confidence indicates whether the time is expected to be correct. The confidence can be
     * upgraded or downgraded over time. It can be used to decide whether a user could / should be
     * asked to confirm the time. For example, during device set up low confidence would describe a
     * time that has been initialized by default. The user may then be asked to confirm the time,
     * moving it to a high confidence.
     */
    @Retention(SOURCE)
    @Target(TYPE_USE)
    @IntDef(prefix = "TIME_CONFIDENCE_",
            value = { TIME_CONFIDENCE_LOW, TIME_CONFIDENCE_HIGH })
    public @interface TimeConfidence {
    }

    /** Used when confidence is low and would (ideally) be confirmed by a user. */
    public static final @TimeConfidence int TIME_CONFIDENCE_LOW = 0;

    /**
     * Used when confidence in the time is high and does not need to be confirmed by a user.
     */
    public static final @TimeConfidence int TIME_CONFIDENCE_HIGH = 100;

    /**
     * The confidence in the current time. Android's time confidence is held in memory because RTC
     * hardware can forget / corrupt the time while the device is powered off. Therefore, on boot
     * we can't assume the time is good, and so default it to "low" confidence until it is confirmed
     * or explicitly set.
     */
    private static @TimeConfidence int sTimeConfidence = TIME_CONFIDENCE_LOW;

    private static final long sNativeData = init();

    private SystemClockTime() {
    }

    /**
     * Sets the system clock time to a reasonable lower bound. Used during boot-up to ensure the
     * device has a time that is better than a default like 1970-01-01.
     */
    public static void initializeIfRequired() {
        // Use the most recent of Build.TIME, the root file system's timestamp, and the
        // value of the ro.build.date.utc system property (which is in seconds).
        final long systemBuildTime = Long.max(
                1000L * SystemProperties.getLong("ro.build.date.utc", -1L),
                Long.max(Environment.getRootDirectory().lastModified(), Build.TIME));
        long currentTimeMillis = getCurrentTimeMillis();
        if (currentTimeMillis < systemBuildTime) {
            String logMsg = "Current time only " + currentTimeMillis
                    + ", advancing to build time " + systemBuildTime;
            Slog.i(TAG, logMsg);
            setTimeAndConfidence(systemBuildTime, TIME_CONFIDENCE_LOW, logMsg);
        }
    }

    /**
     * Sets the system clock time and confidence. See also {@link #setConfidence(int, String)} for
     * an alternative that only sets the confidence.
     *
     * @param unixEpochMillis the time to set
     * @param confidence the confidence in {@code unixEpochMillis}. See {@link TimeConfidence} for
     *     details.
     * @param logMsg a log message that can be included in bug reports that explains the update
     */
    public static void setTimeAndConfidence(
            @CurrentTimeMillisLong long unixEpochMillis, int confidence, @NonNull String logMsg) {
        synchronized (SystemClockTime.class) {
            setTime(sNativeData, unixEpochMillis);
            sTimeConfidence = confidence;
            sTimeDebugLog.log(logMsg);
        }
    }

    /**
     * Sets the system clock confidence. See also {@link #setTimeAndConfidence(long, int, String)}
     * for an alternative that sets the time and confidence.
     *
     * @param confidence the confidence in the system clock time. See {@link TimeConfidence} for
     *     details.
     * @param logMsg a log message that can be included in bug reports that explains the update
     */
    public static void setConfidence(@TimeConfidence int confidence, @NonNull String logMsg) {
        synchronized (SystemClockTime.class) {
            sTimeConfidence = confidence;
            sTimeDebugLog.log(logMsg);
        }
    }

    /**
     * Returns the system clock time. The same as {@link System#currentTimeMillis()}.
     */
    private static @CurrentTimeMillisLong long getCurrentTimeMillis() {
        return System.currentTimeMillis();
    }

    /**
     * Returns the system clock confidence. See {@link TimeConfidence} for details.
     */
    public static @TimeConfidence int getTimeConfidence() {
        synchronized (SystemClockTime.class) {
            return sTimeConfidence;
        }
    }

    /**
     * Adds an entry to the system time debug log that is included in bug reports. This method is
     * intended to be used to record event that may lead to a time change, e.g. config or mode
     * changes.
     */
    public static void addDebugLogEntry(@NonNull String logMsg) {
        sTimeDebugLog.log(logMsg);
    }

    /**
     * Dumps information about recent time / confidence changes to the supplied writer.
     */
    public static void dump(PrintWriter writer) {
        sTimeDebugLog.dump(writer);
    }

    private static native long init();
    private static native int setTime(long nativeData, @CurrentTimeMillisLong long millis);
}
