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
import android.app.AlarmManager;
import android.app.timedetector.TimeSignal;
import android.content.Intent;
import android.util.Slog;
import android.util.TimestampedValue;

import com.android.internal.telephony.TelephonyIntents;

import java.io.PrintWriter;

/**
 * An implementation of TimeDetectorStrategy that passes only NITZ suggestions to
 * {@link AlarmManager}. The TimeDetectorService handles thread safety: all calls to
 * this class can be assumed to be single threaded (though the thread used may vary).
 */
// @NotThreadSafe
public final class SimpleTimeDetectorStrategy implements TimeDetectorStrategy {

    private final static String TAG = "timedetector.SimpleTimeDetectorStrategy";

    /**
     * CLOCK_PARANOIA: The maximum difference allowed between the expected system clock time and the
     * actual system clock time before a warning is logged. Used to help identify situations where
     * there is something other than this class setting the system clock.
     */
    private static final long SYSTEM_CLOCK_PARANOIA_THRESHOLD_MILLIS = 2 * 1000;

    // @NonNull after initialize()
    private Callback mCallback;

    // NITZ state.
    @Nullable private TimestampedValue<Long> mLastNitzTime;


    // Information about the last time signal received: Used when toggling auto-time.
    @Nullable private TimestampedValue<Long> mLastSystemClockTime;
    private boolean mLastSystemClockTimeSendNetworkBroadcast;

    // System clock state.
    @Nullable private TimestampedValue<Long> mLastSystemClockTimeSet;

    @Override
    public void initialize(@NonNull Callback callback) {
        mCallback = callback;
    }

    @Override
    public void suggestTime(@NonNull TimeSignal timeSignal) {
        if (!TimeSignal.SOURCE_ID_NITZ.equals(timeSignal.getSourceId())) {
            Slog.w(TAG, "Ignoring signal from unsupported source: " + timeSignal);
            return;
        }

        // NITZ logic

        TimestampedValue<Long> newNitzUtcTime = timeSignal.getUtcTime();
        boolean nitzTimeIsValid = validateNewNitzTime(newNitzUtcTime, mLastNitzTime);
        if (!nitzTimeIsValid) {
            return;
        }
        // Always store the last NITZ value received, regardless of whether we go on to use it to
        // update the system clock. This is so that we can validate future NITZ signals.
        mLastNitzTime = newNitzUtcTime;

        // System clock update logic.

        // Historically, Android has sent a telephony broadcast only when setting the time using
        // NITZ.
        final boolean sendNetworkBroadcast =
                TimeSignal.SOURCE_ID_NITZ.equals(timeSignal.getSourceId());

        final TimestampedValue<Long> newUtcTime = newNitzUtcTime;
        setSystemClockIfRequired(newUtcTime, sendNetworkBroadcast);
    }

    private static boolean validateNewNitzTime(TimestampedValue<Long> newNitzUtcTime,
            TimestampedValue<Long> lastNitzTime) {

        if (lastNitzTime != null) {
            long referenceTimeDifference =
                    TimestampedValue.referenceTimeDifference(newNitzUtcTime, lastNitzTime);
            if (referenceTimeDifference < 0 || referenceTimeDifference > Integer.MAX_VALUE) {
                // Out of order or bogus.
                Slog.w(TAG, "validateNewNitzTime: Bad NITZ signal received."
                        + " referenceTimeDifference=" + referenceTimeDifference
                        + " lastNitzTime=" + lastNitzTime
                        + " newNitzUtcTime=" + newNitzUtcTime);
                return false;
            }
        }
        return true;
    }

    private void setSystemClockIfRequired(
            TimestampedValue<Long> time, boolean sendNetworkBroadcast) {

        // Store the last candidate we've seen in all cases so we can set the system clock
        // when/if time detection is enabled.
        mLastSystemClockTime = time;
        mLastSystemClockTimeSendNetworkBroadcast = sendNetworkBroadcast;

        if (!mCallback.isTimeDetectionEnabled()) {
            Slog.d(TAG, "setSystemClockIfRequired: Time detection is not enabled. time=" + time);
            return;
        }

        mCallback.acquireWakeLock();
        try {
            long elapsedRealtimeMillis = mCallback.elapsedRealtimeMillis();
            long actualTimeMillis = mCallback.systemClockMillis();

            // CLOCK_PARANOIA : Check to see if this class owns the clock or if something else
            // may be setting the clock.
            if (mLastSystemClockTimeSet != null) {
                long expectedTimeMillis = TimeDetectorStrategy.getTimeAt(
                        mLastSystemClockTimeSet, elapsedRealtimeMillis);
                long absSystemClockDifference = Math.abs(expectedTimeMillis - actualTimeMillis);
                if (absSystemClockDifference > SYSTEM_CLOCK_PARANOIA_THRESHOLD_MILLIS) {
                    Slog.w(TAG, "System clock has not tracked elapsed real time clock. A clock may"
                            + " be inaccurate or something unexpectedly set the system clock."
                            + " elapsedRealtimeMillis=" + elapsedRealtimeMillis
                            + " expectedTimeMillis=" + expectedTimeMillis
                            + " actualTimeMillis=" + actualTimeMillis);
                }
            }

            final String reason = "New time signal";
            adjustAndSetDeviceSystemClock(
                    time, sendNetworkBroadcast, elapsedRealtimeMillis, actualTimeMillis, reason);
        } finally {
            mCallback.releaseWakeLock();
        }
    }

    @Override
    public void handleAutoTimeDetectionToggle(boolean enabled) {
        // If automatic time detection is enabled we update the system clock instantly if we can.
        // Conversely, if automatic time detection is disabled we leave the clock as it is.
        if (enabled) {
            if (mLastSystemClockTime != null) {
                // Only send the network broadcast if the last candidate would have caused one.
                final boolean sendNetworkBroadcast = mLastSystemClockTimeSendNetworkBroadcast;

                mCallback.acquireWakeLock();
                try {
                    long elapsedRealtimeMillis = mCallback.elapsedRealtimeMillis();
                    long actualTimeMillis = mCallback.systemClockMillis();

                    final String reason = "Automatic time detection enabled.";
                    adjustAndSetDeviceSystemClock(mLastSystemClockTime, sendNetworkBroadcast,
                            elapsedRealtimeMillis, actualTimeMillis, reason);
                } finally {
                    mCallback.releaseWakeLock();
                }
            }
        } else {
            // CLOCK_PARANOIA: We are losing "control" of the system clock so we cannot predict what
            // it should be in future.
            mLastSystemClockTimeSet = null;
        }
    }

    @Override
    public void dump(@NonNull PrintWriter pw, @Nullable String[] args) {
        pw.println("mLastNitzTime=" + mLastNitzTime);
        pw.println("mLastSystemClockTimeSet=" + mLastSystemClockTimeSet);
        pw.println("mLastSystemClockTime=" + mLastSystemClockTime);
        pw.println("mLastSystemClockTimeSendNetworkBroadcast="
                + mLastSystemClockTimeSendNetworkBroadcast);
    }

    private void adjustAndSetDeviceSystemClock(
            TimestampedValue<Long> newTime, boolean sendNetworkBroadcast,
            long elapsedRealtimeMillis, long actualSystemClockMillis, String reason) {

        // Adjust for the time that has elapsed since the signal was received.
        long newSystemClockMillis = TimeDetectorStrategy.getTimeAt(newTime, elapsedRealtimeMillis);

        // Check if the new signal would make sufficient difference to the system clock. If it's
        // below the threshold then ignore it.
        long absTimeDifference = Math.abs(newSystemClockMillis - actualSystemClockMillis);
        long systemClockUpdateThreshold = mCallback.systemClockUpdateThresholdMillis();
        if (absTimeDifference < systemClockUpdateThreshold) {
            Slog.d(TAG, "adjustAndSetDeviceSystemClock: Not setting system clock. New time and"
                    + " system clock are close enough."
                    + " elapsedRealtimeMillis=" + elapsedRealtimeMillis
                    + " newTime=" + newTime
                    + " reason=" + reason
                    + " systemClockUpdateThreshold=" + systemClockUpdateThreshold
                    + " absTimeDifference=" + absTimeDifference);
            return;
        }

        Slog.d(TAG, "Setting system clock using time=" + newTime
                + " reason=" + reason
                + " elapsedRealtimeMillis=" + elapsedRealtimeMillis
                + " newTimeMillis=" + newSystemClockMillis);
        mCallback.setSystemClock(newSystemClockMillis);

        // CLOCK_PARANOIA : Record the last time this class set the system clock.
        mLastSystemClockTimeSet = newTime;

        if (sendNetworkBroadcast) {
            // Send a broadcast that telephony code used to send after setting the clock.
            // TODO Remove this broadcast as soon as there are no remaining listeners.
            Intent intent = new Intent(TelephonyIntents.ACTION_NETWORK_SET_TIME);
            intent.addFlags(Intent.FLAG_RECEIVER_REPLACE_PENDING);
            intent.putExtra("time", newSystemClockMillis);
            mCallback.sendStickyBroadcast(intent);
        }
    }
}
