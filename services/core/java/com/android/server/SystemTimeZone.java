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
import android.os.SystemProperties;
import android.text.TextUtils;
import android.util.Slog;

import com.android.i18n.timezone.ZoneInfoDb;

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

    private SystemTimeZone() {}

    /**
     * Called during device boot to validate and set the time zone ID to a low-confidence default.
     */
    public static void initializeTimeZoneSettingsIfRequired() {
        String timezoneProperty = SystemProperties.get(TIME_ZONE_SYSTEM_PROPERTY);
        if (!isValidTimeZoneId(timezoneProperty)) {
            Slog.w(TAG, TIME_ZONE_SYSTEM_PROPERTY + " is not valid (" + timezoneProperty
                    + "); setting to " + DEFAULT_TIME_ZONE_ID);
            setTimeZoneId(DEFAULT_TIME_ZONE_ID, TIME_ZONE_CONFIDENCE_LOW);
        }
    }

    /**
     * Updates the device's time zone system property and associated metadata. Returns {@code true}
     * if the device's time zone changed, {@code false} if the ID is invalid or the device is
     * already set to the supplied ID.
     *
     * <p>This method ensures the confidence metadata is set to the supplied value if the supplied
     * time zone ID is considered valid.
     *
     * <p>This method is intended only for use by the AlarmManager. When changing the device's time
     * zone other system service components must use {@link
     * com.android.server.AlarmManagerInternal#setTimeZone(String, int)} to ensure that important
     * system-wide side effects occur.
     */
    public static boolean setTimeZoneId(String timeZoneId, @TimeZoneConfidence int confidence) {
        if (TextUtils.isEmpty(timeZoneId) || !isValidTimeZoneId(timeZoneId)) {
            return false;
        }

        boolean timeZoneChanged = false;
        synchronized (SystemTimeZone.class) {
            String currentTimeZoneId = getTimeZoneId();
            if (currentTimeZoneId == null || !currentTimeZoneId.equals(timeZoneId)) {
                SystemProperties.set(TIME_ZONE_SYSTEM_PROPERTY, timeZoneId);
                if (DEBUG) {
                    Slog.v(TAG, "Time zone changed: " + currentTimeZoneId + ", new=" + timeZoneId);
                }
                timeZoneChanged = true;
            }
            setTimeZoneConfidence(confidence);
        }

        return timeZoneChanged;
    }

    /**
     * Sets the time zone confidence value if required. See {@link TimeZoneConfidence} for details.
     */
    private static void setTimeZoneConfidence(@TimeZoneConfidence int confidence) {
        int currentConfidence = getTimeZoneConfidence();
        if (currentConfidence != confidence) {
            SystemProperties.set(
                    TIME_ZONE_CONFIDENCE_SYSTEM_PROPERTY, Integer.toString(confidence));
            if (DEBUG) {
                Slog.v(TAG, "Time zone confidence changed: old=" + currentConfidence
                        + ", new=" + confidence);
            }
        }
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

    private static boolean isValidTimeZoneConfidence(@TimeZoneConfidence int confidence) {
        return confidence >= TIME_ZONE_CONFIDENCE_LOW && confidence <= TIME_ZONE_CONFIDENCE_HIGH;
    }

    private static boolean isValidTimeZoneId(String timeZoneId) {
        return timeZoneId != null
                && !timeZoneId.isEmpty()
                && ZoneInfoDb.getInstance().hasTimeZone(timeZoneId);
    }
}
