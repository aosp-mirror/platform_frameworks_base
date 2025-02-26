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

import android.annotation.IntDef;
import android.annotation.NonNull;
import android.os.SystemProperties;
import android.text.TextUtils;
import android.util.LocalLog;
import android.util.Slog;

import com.android.i18n.timezone.ZoneInfoDb;

import java.io.PrintWriter;
import java.lang.annotation.Retention;
import java.lang.annotation.Target;

/**
 * A set of constants and static methods that encapsulate knowledge of how time zone and associated
 * metadata are stored on Android.
 */
public final class SystemTimeZone {

    private static final String TAG = "SystemTimeZone";
    private static final boolean DEBUG = false;
    private static final String TIME_ZONE_SYSTEM_PROPERTY = "persist.sys.timezone";
    private static final String TIME_ZONE_CONFIDENCE_SYSTEM_PROPERTY =
            "persist.sys.timezone_confidence";

    /**
     * The "special" time zone ID used as a low-confidence default when the device's time zone
     * is empty or invalid during boot.
     */
    private static final String DEFAULT_TIME_ZONE_ID = "GMT";

    /**
     * An annotation that indicates a "time zone confidence" value is expected.
     *
     * <p>The confidence indicates whether the time zone is expected to be correct. The confidence
     * can be upgraded or downgraded over time. It can be used to decide whether a user could /
     * should be asked to confirm the time zone. For example, during device set up low confidence
     * would describe a time zone that has been initialized by default or by using low quality
     * or ambiguous signals. The user may then be asked to confirm the time zone, moving it to a
     * high confidence.
     */
    @Retention(SOURCE)
    @Target(TYPE_USE)
    @IntDef(prefix = "TIME_ZONE_CONFIDENCE_",
            value = { TIME_ZONE_CONFIDENCE_LOW, TIME_ZONE_CONFIDENCE_HIGH })
    public @interface TimeZoneConfidence {
    }

    /** Used when confidence is low and would (ideally) be confirmed by a user. */
    public static final @TimeZoneConfidence int TIME_ZONE_CONFIDENCE_LOW = 0;
    /**
     * Used when confidence in the time zone is high and does not need to be confirmed by a user.
     */
    public static final @TimeZoneConfidence int TIME_ZONE_CONFIDENCE_HIGH = 100;

    /**
     * An in-memory log that records the debug info related to the device's time zone setting.
     * This is logged in bug reports to assist with debugging time zone detection issues.
     */
    @NonNull
    private static final LocalLog sTimeZoneDebugLog =
            new LocalLog(30, false /* useLocalTimestamps */);

    private SystemTimeZone() {}

    /**
     * Called during device boot to validate and set the time zone ID to a low-confidence default.
     */
    public static void initializeTimeZoneSettingsIfRequired() {
        String timezoneProperty = SystemProperties.get(TIME_ZONE_SYSTEM_PROPERTY);
        if (!isValidTimeZoneId(timezoneProperty)) {
            String logInfo = "initializeTimeZoneSettingsIfRequired():" + TIME_ZONE_SYSTEM_PROPERTY
                    + " is not valid (" + timezoneProperty + "); setting to "
                    + DEFAULT_TIME_ZONE_ID;
            Slog.w(TAG, logInfo);
            setTimeZoneId(DEFAULT_TIME_ZONE_ID, TIME_ZONE_CONFIDENCE_LOW, logInfo);
        }
    }

    /**
     * Adds an entry to the system time zone debug log that is included in bug reports. This method
     * is intended to be used to record event that may lead to a time zone change, e.g. config or
     * mode changes.
     */
    public static void addDebugLogEntry(@NonNull String logMsg) {
        sTimeZoneDebugLog.log(logMsg);
    }

    /**
     * Updates the device's time zone system property, associated metadata and adds an entry to the
     * debug log. Returns {@code true} if the device's time zone changed, {@code false} if the ID is
     * invalid or the device is already set to the supplied ID.
     *
     * <p>This method ensures the confidence metadata is set to the supplied value if the supplied
     * time zone ID is considered valid.
     *
     * <p>This method is intended only for use by the AlarmManager. When changing the device's time
     * zone other system service components must use {@link
     * AlarmManagerInternal#setTimeZone(String, int, String)} to ensure that important
     * system-wide side effects occur.
     */
    public static boolean setTimeZoneId(
            @NonNull String timeZoneId, @TimeZoneConfidence int confidence,
            @NonNull String logInfo) {
        if (TextUtils.isEmpty(timeZoneId) || !isValidTimeZoneId(timeZoneId)) {
            addDebugLogEntry("setTimeZoneId: Invalid time zone ID."
                    + " timeZoneId=" + timeZoneId
                    + ", confidence=" + confidence
                    + ", logInfo=" + logInfo);
            return false;
        }

        boolean timeZoneChanged = false;
        synchronized (SystemTimeZone.class) {
            String currentTimeZoneId = getTimeZoneId();
            @TimeZoneConfidence int currentConfidence = getTimeZoneConfidence();
            if (currentTimeZoneId == null || !currentTimeZoneId.equals(timeZoneId)) {
                SystemProperties.set(TIME_ZONE_SYSTEM_PROPERTY, timeZoneId);
                if (DEBUG) {
                    Slog.v(TAG, "Time zone changed: " + currentTimeZoneId + ", new=" + timeZoneId);
                }
                timeZoneChanged = true;
            }
            boolean timeZoneConfidenceChanged = setTimeZoneConfidence(confidence);
            if (timeZoneChanged || timeZoneConfidenceChanged) {
                String logMsg = "Time zone or confidence set: "
                        + " (new) timeZoneId=" + timeZoneId
                        + ", (new) confidence=" + confidence
                        + ", (old) timeZoneId=" + currentTimeZoneId
                        + ", (old) confidence=" + currentConfidence
                        + ", logInfo=" + logInfo;
                addDebugLogEntry(logMsg);
            }
        }

        return timeZoneChanged;
    }

    /**
     * Sets the time zone confidence value if required. See {@link TimeZoneConfidence} for details.
     */
    private static boolean setTimeZoneConfidence(@TimeZoneConfidence int newConfidence) {
        int currentConfidence = getTimeZoneConfidence();
        if (currentConfidence != newConfidence) {
            SystemProperties.set(
                    TIME_ZONE_CONFIDENCE_SYSTEM_PROPERTY, Integer.toString(newConfidence));
            if (DEBUG) {
                Slog.v(TAG, "Time zone confidence changed: old=" + currentConfidence
                        + ", newConfidence=" + newConfidence);
            }
            return true;
        }
        return false;
    }

    /** Returns the time zone confidence value. See {@link TimeZoneConfidence} for details. */
    public static @TimeZoneConfidence int getTimeZoneConfidence() {
        int confidence = SystemProperties.getInt(
                TIME_ZONE_CONFIDENCE_SYSTEM_PROPERTY, TIME_ZONE_CONFIDENCE_LOW);
        if (!isValidTimeZoneConfidence(confidence)) {
            confidence = TIME_ZONE_CONFIDENCE_LOW;
        }
        return confidence;
    }

    /** Returns the device's time zone ID setting. */
    public static String getTimeZoneId() {
        return SystemProperties.get(TIME_ZONE_SYSTEM_PROPERTY);
    }

    /**
     * Dumps information about recent time zone decisions / changes to the supplied writer.
     */
    public static void dump(PrintWriter writer) {
        sTimeZoneDebugLog.dump(writer);
    }

    private static boolean isValidTimeZoneConfidence(@TimeZoneConfidence int confidence) {
        return confidence >= TIME_ZONE_CONFIDENCE_LOW && confidence <= TIME_ZONE_CONFIDENCE_HIGH;
    }

    private static boolean isValidTimeZoneId(String timeZoneId) {
        return timeZoneId != null
                && !timeZoneId.isEmpty()
                && ZoneInfoDb.getInstance().hasTimeZone(timeZoneId);
    }
}
