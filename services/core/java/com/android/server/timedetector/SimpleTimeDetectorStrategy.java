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
import android.app.AlarmManager;
import android.app.timedetector.ManualTimeSuggestion;
import android.app.timedetector.PhoneTimeSuggestion;
import android.content.Intent;
import android.util.LocalLog;
import android.util.Slog;
import android.util.TimestampedValue;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.telephony.TelephonyIntents;
import com.android.internal.util.IndentingPrintWriter;

import java.io.PrintWriter;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

/**
 * An implementation of TimeDetectorStrategy that passes only NITZ suggestions to
 * {@link AlarmManager}.
 *
 * <p>Most public methods are marked synchronized to ensure thread safety around internal state.
 */
public final class SimpleTimeDetectorStrategy implements TimeDetectorStrategy {

    private static final boolean DBG = false;
    private static final String LOG_TAG = "SimpleTimeDetectorStrategy";

    @IntDef({ ORIGIN_PHONE, ORIGIN_MANUAL })
    @Retention(RetentionPolicy.SOURCE)
    public @interface Origin {}

    /** Used when a time value originated from a telephony signal. */
    @Origin
    private static final int ORIGIN_PHONE = 1;

    /** Used when a time value originated from a user / manual settings. */
    @Origin
    private static final int ORIGIN_MANUAL = 2;

    /**
     * CLOCK_PARANOIA: The maximum difference allowed between the expected system clock time and the
     * actual system clock time before a warning is logged. Used to help identify situations where
     * there is something other than this class setting the system clock automatically.
     */
    private static final long SYSTEM_CLOCK_PARANOIA_THRESHOLD_MILLIS = 2 * 1000;

    // A log for changes made to the system clock and why.
    @NonNull
    private final LocalLog mTimeChangesLog = new LocalLog(30, false /* useLocalTimestamps */);

    // @NonNull after initialize()
    private Callback mCallback;

    // Last phone suggestion.
    @Nullable private PhoneTimeSuggestion mLastPhoneSuggestion;

    // Information about the last time signal received: Used when toggling auto-time.
    @Nullable private TimestampedValue<Long> mLastAutoSystemClockTime;
    private boolean mLastAutoSystemClockTimeSendNetworkBroadcast;

    // System clock state.
    @Nullable private TimestampedValue<Long> mLastAutoSystemClockTimeSet;

    @Override
    public void initialize(@NonNull Callback callback) {
        mCallback = callback;
    }

    @Override
    public synchronized void suggestPhoneTime(@NonNull PhoneTimeSuggestion timeSuggestion) {
        // NITZ logic

        // Empty suggestions are just ignored as we don't currently keep track of suggestion origin.
        if (timeSuggestion.getUtcTime() == null) {
            return;
        }

        boolean timeSuggestionIsValid =
                validateNewPhoneSuggestion(timeSuggestion, mLastPhoneSuggestion);
        if (!timeSuggestionIsValid) {
            return;
        }
        // Always store the last NITZ value received, regardless of whether we go on to use it to
        // update the system clock. This is so that we can validate future phone suggestions.
        mLastPhoneSuggestion = timeSuggestion;

        // System clock update logic.
        final TimestampedValue<Long> newUtcTime = timeSuggestion.getUtcTime();
        setSystemClockIfRequired(ORIGIN_PHONE, newUtcTime, timeSuggestion);
    }

    @Override
    public synchronized void suggestManualTime(ManualTimeSuggestion timeSuggestion) {
        final TimestampedValue<Long> newUtcTime = timeSuggestion.getUtcTime();
        setSystemClockIfRequired(ORIGIN_MANUAL, newUtcTime, timeSuggestion);
    }

    private static boolean validateNewPhoneSuggestion(@NonNull PhoneTimeSuggestion newSuggestion,
            @Nullable PhoneTimeSuggestion lastSuggestion) {

        if (lastSuggestion != null) {
            long referenceTimeDifference = TimestampedValue.referenceTimeDifference(
                    newSuggestion.getUtcTime(), lastSuggestion.getUtcTime());
            if (referenceTimeDifference < 0 || referenceTimeDifference > Integer.MAX_VALUE) {
                // Out of order or bogus.
                Slog.w(LOG_TAG, "Bad NITZ signal received."
                        + " referenceTimeDifference=" + referenceTimeDifference
                        + " lastSuggestion=" + lastSuggestion
                        + " newSuggestion=" + newSuggestion);
                return false;
            }
        }
        return true;
    }

    @GuardedBy("this")
    private void setSystemClockIfRequired(
            @Origin int origin, TimestampedValue<Long> time, Object cause) {
        // Historically, Android has sent a TelephonyIntents.ACTION_NETWORK_SET_TIME broadcast only
        // when setting the time using NITZ.
        boolean sendNetworkBroadcast = origin == ORIGIN_PHONE;

        boolean isOriginAutomatic = isOriginAutomatic(origin);
        if (isOriginAutomatic) {
            // Store the last auto time candidate we've seen in all cases so we can set the system
            // clock when/if time detection is off but later enabled.
            mLastAutoSystemClockTime = time;
            mLastAutoSystemClockTimeSendNetworkBroadcast = sendNetworkBroadcast;

            if (!mCallback.isAutoTimeDetectionEnabled()) {
                if (DBG) {
                    Slog.d(LOG_TAG, "Auto time detection is not enabled."
                            + " origin=" + origin
                            + ", time=" + time
                            + ", cause=" + cause);
                }
                return;
            }
        } else {
            if (mCallback.isAutoTimeDetectionEnabled()) {
                if (DBG) {
                    Slog.d(LOG_TAG, "Auto time detection is enabled."
                            + " origin=" + origin
                            + ", time=" + time
                            + ", cause=" + cause);
                }
                return;
            }
        }

        mCallback.acquireWakeLock();
        try {
            long elapsedRealtimeMillis = mCallback.elapsedRealtimeMillis();
            long actualTimeMillis = mCallback.systemClockMillis();

            if (isOriginAutomatic) {
                // CLOCK_PARANOIA : Check to see if this class owns the clock or if something else
                // may be setting the clock.
                if (mLastAutoSystemClockTimeSet != null) {
                    long expectedTimeMillis = TimeDetectorStrategy.getTimeAt(
                            mLastAutoSystemClockTimeSet, elapsedRealtimeMillis);
                    long absSystemClockDifference = Math.abs(expectedTimeMillis - actualTimeMillis);
                    if (absSystemClockDifference > SYSTEM_CLOCK_PARANOIA_THRESHOLD_MILLIS) {
                        Slog.w(LOG_TAG,
                                "System clock has not tracked elapsed real time clock. A clock may"
                                        + " be inaccurate or something unexpectedly set the system"
                                        + " clock."
                                        + " elapsedRealtimeMillis=" + elapsedRealtimeMillis
                                        + " expectedTimeMillis=" + expectedTimeMillis
                                        + " actualTimeMillis=" + actualTimeMillis);
                    }
                }
            }

            adjustAndSetDeviceSystemClock(
                    time, sendNetworkBroadcast, elapsedRealtimeMillis, actualTimeMillis, cause);
        } finally {
            mCallback.releaseWakeLock();
        }
    }

    private static boolean isOriginAutomatic(@Origin int origin) {
        return origin == ORIGIN_PHONE;
    }

    @Override
    public synchronized void handleAutoTimeDetectionChanged() {
        // If automatic time detection is enabled we update the system clock instantly if we can.
        // Conversely, if automatic time detection is disabled we leave the clock as it is.
        boolean enabled = mCallback.isAutoTimeDetectionEnabled();
        if (enabled) {
            if (mLastAutoSystemClockTime != null) {
                // Only send the network broadcast if the last candidate would have caused one.
                final boolean sendNetworkBroadcast = mLastAutoSystemClockTimeSendNetworkBroadcast;

                mCallback.acquireWakeLock();
                try {
                    long elapsedRealtimeMillis = mCallback.elapsedRealtimeMillis();
                    long actualTimeMillis = mCallback.systemClockMillis();

                    final String reason = "Automatic time detection enabled.";
                    adjustAndSetDeviceSystemClock(mLastAutoSystemClockTime, sendNetworkBroadcast,
                            elapsedRealtimeMillis, actualTimeMillis, reason);
                } finally {
                    mCallback.releaseWakeLock();
                }
            }
        } else {
            // CLOCK_PARANOIA: We are losing "control" of the system clock so we cannot predict what
            // it should be in future.
            mLastAutoSystemClockTimeSet = null;
        }
    }

    @Override
    public synchronized void dump(@NonNull PrintWriter pw, @Nullable String[] args) {
        IndentingPrintWriter ipw = new IndentingPrintWriter(pw, " ");
        ipw.println("TimeDetectorStrategy:");
        ipw.increaseIndent(); // level 1

        ipw.println("mLastPhoneSuggestion=" + mLastPhoneSuggestion);
        ipw.println("mLastAutoSystemClockTimeSet=" + mLastAutoSystemClockTimeSet);
        ipw.println("mLastAutoSystemClockTime=" + mLastAutoSystemClockTime);
        ipw.println("mLastAutoSystemClockTimeSendNetworkBroadcast="
                + mLastAutoSystemClockTimeSendNetworkBroadcast);


        ipw.println("Time change log:");
        ipw.increaseIndent(); // level 2
        mTimeChangesLog.dump(ipw);
        ipw.decreaseIndent(); // level 2

        ipw.decreaseIndent(); // level 1
        ipw.flush();
    }

    @GuardedBy("this")
    private void adjustAndSetDeviceSystemClock(
            TimestampedValue<Long> newTime, boolean sendNetworkBroadcast,
            long elapsedRealtimeMillis, long actualSystemClockMillis, Object cause) {

        // Adjust for the time that has elapsed since the signal was received.
        long newSystemClockMillis = TimeDetectorStrategy.getTimeAt(newTime, elapsedRealtimeMillis);

        // Check if the new signal would make sufficient difference to the system clock. If it's
        // below the threshold then ignore it.
        long absTimeDifference = Math.abs(newSystemClockMillis - actualSystemClockMillis);
        long systemClockUpdateThreshold = mCallback.systemClockUpdateThresholdMillis();
        if (absTimeDifference < systemClockUpdateThreshold) {
            if (DBG) {
                Slog.d(LOG_TAG, "Not setting system clock. New time and"
                        + " system clock are close enough."
                        + " elapsedRealtimeMillis=" + elapsedRealtimeMillis
                        + " newTime=" + newTime
                        + " cause=" + cause
                        + " systemClockUpdateThreshold=" + systemClockUpdateThreshold
                        + " absTimeDifference=" + absTimeDifference);
            }
            return;
        }

        mCallback.setSystemClock(newSystemClockMillis);
        String logMsg = "Set system clock using time=" + newTime
                + " cause=" + cause
                + " elapsedRealtimeMillis=" + elapsedRealtimeMillis
                + " newSystemClockMillis=" + newSystemClockMillis;
        if (DBG) {
            Slog.d(LOG_TAG, logMsg);
        }
        mTimeChangesLog.log(logMsg);

        // CLOCK_PARANOIA : Record the last time this class set the system clock.
        mLastAutoSystemClockTimeSet = newTime;

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
