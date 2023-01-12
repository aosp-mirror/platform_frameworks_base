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

import android.annotation.IntDef;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.UserIdInt;
import android.app.time.ExternalTimeSuggestion;
import android.app.time.TimeState;
import android.app.time.UnixEpochTime;
import android.app.timedetector.ManualTimeSuggestion;
import android.app.timedetector.TelephonyTimeSuggestion;
import android.util.IndentingPrintWriter;

import com.android.internal.util.Preconditions;
import com.android.server.timezonedetector.Dumpable;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * The interface for the class that implements the time detection algorithm used by the
 * {@link TimeDetectorService}.
 *
 * <p>Most calls will be handled by a single thread but that is not true for all calls. For example
 * {@link #dump(IndentingPrintWriter, String[])}) may be called on a different thread so
 * implementations must handle thread safety.
 *
 * @hide
 */
public interface TimeDetectorStrategy extends Dumpable {

    @IntDef({ ORIGIN_TELEPHONY, ORIGIN_MANUAL, ORIGIN_NETWORK, ORIGIN_GNSS, ORIGIN_EXTERNAL })
    @Retention(RetentionPolicy.SOURCE)
    @Target({ ElementType.TYPE_USE, ElementType.TYPE_PARAMETER })
    @interface Origin {}

    /** Used when a time value originated from a telephony signal. */
    @Origin int ORIGIN_TELEPHONY = 1;

    /** Used when a time value originated from a user / manual settings. */
    @Origin int ORIGIN_MANUAL = 2;

    /** Used when a time value originated from a network signal. */
    @Origin int ORIGIN_NETWORK = 3;

    /** Used when a time value originated from a gnss signal. */
    @Origin int ORIGIN_GNSS = 4;

    /** Used when a time value originated from an externally specified signal. */
    @Origin int ORIGIN_EXTERNAL = 5;

    /** Returns a snapshot of the system clock's state. See {@link TimeState} for details. */
    @NonNull
    TimeState getTimeState();

    /**
     * Sets the system time state. See {@link TimeState} for details. Intended for use during
     * testing to force the device's state, this bypasses the time detection logic.
     */
    void setTimeState(@NonNull TimeState timeState);

    /**
     * Signals that a user has confirmed the supplied time. If the {@code confirmationTime},
     * adjusted for elapsed time since it was created (expected to be with {@link
     * #getTimeState()}), is very close to the clock's current state, then this can be used to
     * raise the system's confidence in that time. Returns {@code true} if confirmation was
     * successful (i.e. the time matched), {@code false} otherwise.
     */
    boolean confirmTime(@NonNull UnixEpochTime confirmationTime);

    /** Processes the suggested time from telephony sources. */
    void suggestTelephonyTime(@NonNull TelephonyTimeSuggestion timeSuggestion);

    /**
     * Processes the suggested manually entered time. Returns {@code false} if the suggestion was
     * invalid, or the device configuration prevented the suggestion being used, {@code true} if the
     * suggestion was accepted. A suggestion that is valid but does not change the time because it
     * matches the current device time is considered accepted.
     *
     * @param bypassUserPolicyChecks {@code true} for device policy manager use cases where device
     *   policy restrictions that should apply to actual users can be ignored
     */
    boolean suggestManualTime(@UserIdInt int userId, @NonNull ManualTimeSuggestion timeSuggestion,
            boolean bypassUserPolicyChecks);

    /** Processes the suggested time from network sources. */
    void suggestNetworkTime(@NonNull NetworkTimeSuggestion timeSuggestion);

    /**
     * Returns the latest (accepted) network time suggestion. Returns {@code null} if there isn't
     * one.
     */
    @Nullable
    NetworkTimeSuggestion getLatestNetworkSuggestion();

    /**
     * Clears the latest network time suggestion, leaving none. The remaining time signals from
     * other sources will be reassessed causing the device's time to be updated if config and
     * settings allow.
     */
    void clearLatestNetworkSuggestion();

    /** Processes the suggested time from gnss sources. */
    void suggestGnssTime(@NonNull GnssTimeSuggestion timeSuggestion);

    /** Processes the suggested time from external sources. */
    void suggestExternalTime(@NonNull ExternalTimeSuggestion timeSuggestion);

    // Utility methods below are to be moved to a better home when one becomes more obvious.

    /**
     * Converts one of the {@code ORIGIN_} constants to a human readable string suitable for config
     * and debug usage. Throws an {@link IllegalArgumentException} if the value is unrecognized.
     */
    @NonNull
    static String originToString(@Origin int origin) {
        switch (origin) {
            case ORIGIN_MANUAL:
                return "manual";
            case ORIGIN_NETWORK:
                return "network";
            case ORIGIN_TELEPHONY:
                return "telephony";
            case ORIGIN_GNSS:
                return "gnss";
            case ORIGIN_EXTERNAL:
                return "external";
            default:
                throw new IllegalArgumentException("origin=" + origin);
        }
    }

    /**
     * Converts a human readable config string to one of the {@code ORIGIN_} constants.
     * Throws an {@link IllegalArgumentException} if the value is unrecognized or {@code null}.
     */
    static @Origin int stringToOrigin(String originString) {
        Preconditions.checkArgument(originString != null);

        switch (originString) {
            case "manual":
                return ORIGIN_MANUAL;
            case "network":
                return ORIGIN_NETWORK;
            case "telephony":
                return ORIGIN_TELEPHONY;
            case "gnss":
                return ORIGIN_GNSS;
            case "external":
                return ORIGIN_EXTERNAL;
            default:
                throw new IllegalArgumentException("originString=" + originString);
        }
    }
}
