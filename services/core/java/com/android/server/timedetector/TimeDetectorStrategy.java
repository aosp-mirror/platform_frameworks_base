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
import android.app.timedetector.TimeSignal;
import android.content.Intent;
import android.util.TimestampedValue;

import java.io.PrintWriter;

/**
 * The interface for classes that implement the time detection algorithm used by the
 * TimeDetectorService. The TimeDetectorService handles thread safety: all calls to implementations
 * of this interface can be assumed to be single threaded (though the thread used may vary).
 *
 * @hide
 */
// @NotThreadSafe
public interface TimeDetectorStrategy {

    /**
     * The interface used by the strategy to interact with the surrounding service.
     */
    interface Callback {

        /**
         * The absolute threshold below which the system clock need not be updated. i.e. if setting
         * the system clock would adjust it by less than this (either backwards or forwards) then it
         * need not be set.
         */
        int systemClockUpdateThresholdMillis();

        /** Returns true if automatic time detection is enabled. */
        boolean isTimeDetectionEnabled();

        /** Acquire a suitable wake lock. Must be followed by {@link #releaseWakeLock()} */
        void acquireWakeLock();

        /** Returns the elapsedRealtimeMillis clock value. The WakeLock must be held. */
        long elapsedRealtimeMillis();

        /** Returns the system clock value. The WakeLock must be held. */
        long systemClockMillis();

        /** Sets the device system clock. The WakeLock must be held. */
        void setSystemClock(long newTimeMillis);

        /** Release the wake lock acquired by a call to {@link #acquireWakeLock()}. */
        void releaseWakeLock();

        /** Send the supplied intent as a stick broadcast. */
        void sendStickyBroadcast(@NonNull Intent intent);
    }

    /** Initialize the strategy. */
    void initialize(@NonNull Callback callback);

    /** Process the suggested time. */
    void suggestTime(@NonNull TimeSignal timeSignal);

    /** Handle the auto-time setting being toggled on or off. */
    void handleAutoTimeDetectionToggle(boolean enabled);

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
