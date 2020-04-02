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
import android.annotation.Nullable;
import android.app.timedetector.ManualTimeSuggestion;
import android.app.timedetector.NetworkTimeSuggestion;
import android.app.timedetector.TelephonyTimeSuggestion;
import android.os.TimestampedValue;

import java.io.PrintWriter;

/**
 * The interface for the class that implements the time detection algorithm used by the
 * {@link TimeDetectorService}.
 *
 * <p>Most calls will be handled by a single thread but that is not true for all calls. For example
 * {@link #dump(PrintWriter, String[])}) may be called on a different thread so implementations must
 * handle thread safety.
 *
 * @hide
 */
public interface TimeDetectorStrategy {

    /**
     * The interface used by the strategy to interact with the surrounding service.
     *
     * <p>Note: Because the system properties-derived value {@link #isAutoTimeDetectionEnabled()}
     * can be modified independently and from different threads (and processes!). its use is prone
     * to race conditions. That will be true until the responsibility for setting their values is
     * moved to {@link TimeDetectorStrategy}. There are similar issues with
     * {@link #systemClockMillis()} while any process can modify the system clock.
     */
    interface Callback {

        /**
         * The absolute threshold below which the system clock need not be updated. i.e. if setting
         * the system clock would adjust it by less than this (either backwards or forwards) then it
         * need not be set.
         */
        int systemClockUpdateThresholdMillis();

        /** Returns true if automatic time detection is enabled. */
        boolean isAutoTimeDetectionEnabled();

        /** Acquire a suitable wake lock. Must be followed by {@link #releaseWakeLock()} */
        void acquireWakeLock();

        /** Returns the elapsedRealtimeMillis clock value. */
        long elapsedRealtimeMillis();

        /** Returns the system clock value. */
        long systemClockMillis();

        /** Sets the device system clock. The WakeLock must be held. */
        void setSystemClock(long newTimeMillis);

        /** Release the wake lock acquired by a call to {@link #acquireWakeLock()}. */
        void releaseWakeLock();
    }

    /** Initialize the strategy. */
    void initialize(@NonNull Callback callback);

    /** Process the suggested time from telephony sources. */
    void suggestTelephonyTime(@NonNull TelephonyTimeSuggestion timeSuggestion);

    /**
     * Process the suggested manually entered time. Returns {@code false} if the suggestion was
     * invalid, or the device configuration prevented the suggestion being used, {@code true} if the
     * suggestion was accepted. A suggestion that is valid but does not change the time because it
     * matches the current device time is considered accepted.
     */
    boolean suggestManualTime(@NonNull ManualTimeSuggestion timeSuggestion);

    /** Process the suggested time from network sources. */
    void suggestNetworkTime(@NonNull NetworkTimeSuggestion timeSuggestion);

    /** Handle the auto-time setting being toggled on or off. */
    void handleAutoTimeDetectionChanged();

    /** Dump debug information. */
    void dump(@NonNull PrintWriter pw, @Nullable String[] args);

    // Utility methods below are to be moved to a better home when one becomes more obvious.

    /**
     * Adjusts the supplied time value by applying the difference between the reference time
     * supplied and the reference time associated with the time.
     */
    static long getTimeAt(@NonNull TimestampedValue<Long> timeValue, long referenceClockMillisNow) {
        return (referenceClockMillisNow - timeValue.getReferenceTimeMillis())
                + timeValue.getValue();
    }
}
