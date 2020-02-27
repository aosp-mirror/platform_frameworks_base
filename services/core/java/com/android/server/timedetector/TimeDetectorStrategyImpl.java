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
import android.app.timedetector.NetworkTimeSuggestion;
import android.app.timedetector.TelephonyTimeSuggestion;
import android.os.TimestampedValue;
import android.util.LocalLog;
import android.util.Slog;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.util.IndentingPrintWriter;
import com.android.server.timezonedetector.ArrayMapWithHistory;
import com.android.server.timezonedetector.ReferenceWithHistory;

import java.io.PrintWriter;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

/**
 * An implementation of {@link TimeDetectorStrategy} that passes telephony and manual suggestions to
 * {@link AlarmManager}. When there are multiple telephony sources, the one with the lowest ID is
 * used unless the data becomes too stale.
 *
 * <p>Most public methods are marked synchronized to ensure thread safety around internal state.
 */
public final class TimeDetectorStrategyImpl implements TimeDetectorStrategy {

    private static final boolean DBG = false;
    private static final String LOG_TAG = "SimpleTimeDetectorStrategy";

    /** A score value used to indicate "no score", either due to validation failure or age. */
    private static final int TELEPHONY_INVALID_SCORE = -1;
    /** The number of buckets telephony suggestions can be put in by age. */
    private static final int TELEPHONY_BUCKET_COUNT = 24;
    /** Each bucket is this size. All buckets are equally sized. */
    @VisibleForTesting
    static final int TELEPHONY_BUCKET_SIZE_MILLIS = 60 * 60 * 1000;
    /**
     * Telephony and network suggestions older than this value are considered too old to be used.
     */
    @VisibleForTesting
    static final long MAX_UTC_TIME_AGE_MILLIS =
            TELEPHONY_BUCKET_COUNT * TELEPHONY_BUCKET_SIZE_MILLIS;

    @IntDef({ ORIGIN_TELEPHONY, ORIGIN_MANUAL, ORIGIN_NETWORK })
    @Retention(RetentionPolicy.SOURCE)
    public @interface Origin {}

    /** Used when a time value originated from a telephony signal. */
    @Origin
    private static final int ORIGIN_TELEPHONY = 1;

    /** Used when a time value originated from a user / manual settings. */
    @Origin
    private static final int ORIGIN_MANUAL = 2;

    /** Used when a time value originated from a network signal. */
    @Origin
    private static final int ORIGIN_NETWORK = 3;

    /**
     * CLOCK_PARANOIA: The maximum difference allowed between the expected system clock time and the
     * actual system clock time before a warning is logged. Used to help identify situations where
     * there is something other than this class setting the system clock.
     */
    private static final long SYSTEM_CLOCK_PARANOIA_THRESHOLD_MILLIS = 2 * 1000;

    /**
     * The number of suggestions to keep. These are logged in bug reports to assist when debugging
     * issues with detection.
     */
    private static final int KEEP_SUGGESTION_HISTORY_SIZE = 10;

    /**
     * A log that records the decisions / decision metadata that affected the device's system clock
     * time. This is logged in bug reports to assist with debugging issues with detection.
     */
    @NonNull
    private final LocalLog mTimeChangesLog = new LocalLog(30, false /* useLocalTimestamps */);

    // @NonNull after initialize()
    private Callback mCallback;

    // Used to store the last time the system clock state was set automatically. It is used to
    // detect (and log) issues with the realtime clock or whether the clock is being set without
    // going through this strategy code.
    @GuardedBy("this")
    @Nullable
    private TimestampedValue<Long> mLastAutoSystemClockTimeSet;

    /**
     * A mapping from slotIndex to a time suggestion. We typically expect one or two mappings:
     * devices will have a small number of telephony devices and slotIndexs are assumed to be
     * stable.
     */
    @GuardedBy("this")
    private final ArrayMapWithHistory<Integer, TelephonyTimeSuggestion> mSuggestionBySlotIndex =
            new ArrayMapWithHistory<>(KEEP_SUGGESTION_HISTORY_SIZE);

    @GuardedBy("this")
    private final ReferenceWithHistory<NetworkTimeSuggestion> mLastNetworkSuggestion =
            new ReferenceWithHistory<>(KEEP_SUGGESTION_HISTORY_SIZE);

    @Override
    public void initialize(@NonNull Callback callback) {
        mCallback = callback;
    }

    @Override
    public synchronized void suggestManualTime(@NonNull ManualTimeSuggestion suggestion) {
        final TimestampedValue<Long> newUtcTime = suggestion.getUtcTime();

        if (!validateSuggestionTime(newUtcTime, suggestion)) {
            return;
        }

        String cause = "Manual time suggestion received: suggestion=" + suggestion;
        setSystemClockIfRequired(ORIGIN_MANUAL, newUtcTime, cause);
    }

    @Override
    public synchronized void suggestNetworkTime(@NonNull NetworkTimeSuggestion timeSuggestion) {
        if (!validateSuggestionTime(timeSuggestion.getUtcTime(), timeSuggestion)) {
            return;
        }

        // The caller submits suggestions with the best available information when there are network
        // changes. The best available information may have been cached and if they were all stored
        // this would lead to duplicates showing up in the suggestion history. The suggestions may
        // be made for different reasons but there is not a significant benefit to storing the same
        // suggestion information again. doAutoTimeDetection() should still be called: this ensures
        // the suggestion and device state are always re-evaluated, which might produce a different
        // detected time if, for example, the age of all suggestions are considered.
        NetworkTimeSuggestion lastNetworkSuggestion = mLastNetworkSuggestion.get();
        if (lastNetworkSuggestion == null || !lastNetworkSuggestion.equals(timeSuggestion)) {
            mLastNetworkSuggestion.set(timeSuggestion);
        }

        // Now perform auto time detection. The new suggestion may be used to modify the system
        // clock.
        String reason = "New network time suggested. timeSuggestion=" + timeSuggestion;
        doAutoTimeDetection(reason);
    }

    @Override
    public synchronized void suggestTelephonyTime(@NonNull TelephonyTimeSuggestion timeSuggestion) {
        // Empty time suggestion means that telephony network connectivity has been lost.
        // The passage of time is relentless, and we don't expect our users to use a time machine,
        // so we can continue relying on previous suggestions when we lose connectivity. This is
        // unlike time zone, where a user may lose connectivity when boarding a flight and where we
        // do want to "forget" old signals. Suggestions that are too old are discarded later in the
        // detection algorithm.
        if (timeSuggestion.getUtcTime() == null) {
            return;
        }

        // Perform validation / input filtering and record the validated suggestion against the
        // slotIndex.
        if (!validateAndStoreTelephonySuggestion(timeSuggestion)) {
            return;
        }

        // Now perform auto time detection. The new suggestion may be used to modify the system
        // clock.
        String reason = "New telephony time suggested. timeSuggestion=" + timeSuggestion;
        doAutoTimeDetection(reason);
    }

    @Override
    public synchronized void handleAutoTimeDetectionChanged() {
        boolean enabled = mCallback.isAutoTimeDetectionEnabled();
        // When automatic time detection is enabled we update the system clock instantly if we can.
        // Conversely, when automatic time detection is disabled we leave the clock as it is.
        if (enabled) {
            String reason = "Auto time zone detection setting enabled.";
            doAutoTimeDetection(reason);
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

        ipw.println("mLastAutoSystemClockTimeSet=" + mLastAutoSystemClockTimeSet);
        ipw.println("mCallback.isAutoTimeDetectionEnabled()="
                + mCallback.isAutoTimeDetectionEnabled());
        ipw.println("mCallback.elapsedRealtimeMillis()=" + mCallback.elapsedRealtimeMillis());
        ipw.println("mCallback.systemClockMillis()=" + mCallback.systemClockMillis());
        ipw.println("mCallback.systemClockUpdateThresholdMillis()="
                + mCallback.systemClockUpdateThresholdMillis());

        ipw.println("Time change log:");
        ipw.increaseIndent(); // level 2
        mTimeChangesLog.dump(ipw);
        ipw.decreaseIndent(); // level 2

        ipw.println("Telephony suggestion history:");
        ipw.increaseIndent(); // level 2
        mSuggestionBySlotIndex.dump(ipw);
        ipw.decreaseIndent(); // level 2

        ipw.println("Network suggestion history:");
        ipw.increaseIndent(); // level 2
        mLastNetworkSuggestion.dump(ipw);
        ipw.decreaseIndent(); // level 2

        ipw.decreaseIndent(); // level 1
        ipw.flush();
    }

    @GuardedBy("this")
    private boolean validateAndStoreTelephonySuggestion(
            @NonNull TelephonyTimeSuggestion suggestion) {
        TimestampedValue<Long> newUtcTime = suggestion.getUtcTime();
        if (!validateSuggestionTime(newUtcTime, suggestion)) {
            // There's probably nothing useful we can do: elsewhere we assume that reference
            // times are in the past so just stop here.
            return false;
        }

        int slotIndex = suggestion.getSlotIndex();
        TelephonyTimeSuggestion previousSuggestion = mSuggestionBySlotIndex.get(slotIndex);
        if (previousSuggestion != null) {
            // We can log / discard suggestions with obvious issues with the reference time clock.
            if (previousSuggestion.getUtcTime() == null
                    || previousSuggestion.getUtcTime().getValue() == null) {
                // This should be impossible given we only store validated suggestions.
                Slog.w(LOG_TAG, "Previous suggestion is null or has a null time."
                        + " previousSuggestion=" + previousSuggestion
                        + ", suggestion=" + suggestion);
                return false;
            }

            long referenceTimeDifference = TimestampedValue.referenceTimeDifference(
                    newUtcTime, previousSuggestion.getUtcTime());
            if (referenceTimeDifference < 0) {
                // The reference time is before the previously received suggestion. Ignore it.
                Slog.w(LOG_TAG, "Out of order telephony suggestion received."
                        + " referenceTimeDifference=" + referenceTimeDifference
                        + " previousSuggestion=" + previousSuggestion
                        + " suggestion=" + suggestion);
                return false;
            }
        }

        // Store the latest suggestion.
        mSuggestionBySlotIndex.put(slotIndex, suggestion);
        return true;
    }

    private boolean validateSuggestionTime(
            @NonNull TimestampedValue<Long> newUtcTime, @NonNull Object suggestion) {
        if (newUtcTime.getValue() == null) {
            Slog.w(LOG_TAG, "Suggested time value is null. suggestion=" + suggestion);
            return false;
        }

        // We can validate the suggestion against the reference time clock.
        long elapsedRealtimeMillis = mCallback.elapsedRealtimeMillis();
        if (elapsedRealtimeMillis < newUtcTime.getReferenceTimeMillis()) {
            // elapsedRealtime clock went backwards?
            Slog.w(LOG_TAG, "New reference time is in the future? Ignoring."
                    + " elapsedRealtimeMillis=" + elapsedRealtimeMillis
                    + ", suggestion=" + suggestion);
            return false;
        }
        return true;
    }

    @GuardedBy("this")
    private void doAutoTimeDetection(@NonNull String detectionReason) {
        if (!mCallback.isAutoTimeDetectionEnabled()) {
            // Avoid doing unnecessary work with this (race-prone) check.
            return;
        }

        // Android devices currently prioritize any telephony over network signals. There are
        // carrier compliance tests that would need to be changed before we could ignore NITZ or
        // prefer NTP generally. This check is cheap on devices without telephony hardware.
        TelephonyTimeSuggestion bestTelephonySuggestion = findBestTelephonySuggestion();
        if (bestTelephonySuggestion != null) {
            final TimestampedValue<Long> newUtcTime = bestTelephonySuggestion.getUtcTime();
            String cause = "Found good telephony suggestion."
                    + ", bestTelephonySuggestion=" + bestTelephonySuggestion
                    + ", detectionReason=" + detectionReason;
            setSystemClockIfRequired(ORIGIN_TELEPHONY, newUtcTime, cause);
            return;
        }

        // There is no good telephony suggestion, try network.
        NetworkTimeSuggestion networkSuggestion = findLatestValidNetworkSuggestion();
        if (networkSuggestion != null) {
            final TimestampedValue<Long> newUtcTime = networkSuggestion.getUtcTime();
            String cause = "Found good network suggestion."
                    + ", networkSuggestion=" + networkSuggestion
                    + ", detectionReason=" + detectionReason;
            setSystemClockIfRequired(ORIGIN_NETWORK, newUtcTime, cause);
            return;
        }

        if (DBG) {
            Slog.d(LOG_TAG, "Could not determine time: No best telephony or network suggestion."
                    + " detectionReason=" + detectionReason);
        }
    }

    @GuardedBy("this")
    @Nullable
    private TelephonyTimeSuggestion findBestTelephonySuggestion() {
        long elapsedRealtimeMillis = mCallback.elapsedRealtimeMillis();

        // Telephony time suggestions are assumed to be derived from NITZ or NITZ-like signals.
        // These have a number of limitations:
        // 1) No guarantee of accuracy ("accuracy of the time information is in the order of
        // minutes") [1]
        // 2) No guarantee of regular signals ("dependent on the handset crossing radio network
        // boundaries") [1]
        //
        // [1] https://en.wikipedia.org/wiki/NITZ
        //
        // Generally, when there are suggestions from multiple slotIndexs they should usually
        // approximately agree. In cases where signals *are* inaccurate we don't want to vacillate
        // between signals from two slotIndexs. However, it is known for NITZ signals to be
        // incorrect occasionally, which means we also don't want to stick forever with one
        // slotIndex. Without cross-referencing across sources (e.g. the current device time, NTP),
        // or doing some kind of statistical analysis of consistency within and across slotIndexs,
        // we can't know which suggestions are more correct.
        //
        // For simplicity, we try to value recency, then consistency of slotIndex.
        //
        // The heuristic works as follows:
        // Recency: The most recent suggestion from each slotIndex is scored. The score is based on
        // a discrete age bucket, i.e. so signals received around the same time will be in the same
        // bucket, thus applying a loose reference time ordering. The suggestion with the highest
        // score is used.
        // Consistency: If there a multiple suggestions with the same score, the suggestion with the
        // lowest slotIndex is always taken.
        //
        // In the trivial case with a single ID this will just mean that the latest received
        // suggestion is used.

        TelephonyTimeSuggestion bestSuggestion = null;
        int bestScore = TELEPHONY_INVALID_SCORE;
        for (int i = 0; i < mSuggestionBySlotIndex.size(); i++) {
            Integer slotIndex = mSuggestionBySlotIndex.keyAt(i);
            TelephonyTimeSuggestion candidateSuggestion = mSuggestionBySlotIndex.valueAt(i);
            if (candidateSuggestion == null) {
                // Unexpected - null suggestions should never be stored.
                Slog.w(LOG_TAG, "Latest suggestion unexpectedly null for slotIndex."
                        + " slotIndex=" + slotIndex);
                continue;
            } else if (candidateSuggestion.getUtcTime() == null) {
                // Unexpected - we do not store empty suggestions.
                Slog.w(LOG_TAG, "Latest suggestion unexpectedly empty. "
                        + " candidateSuggestion=" + candidateSuggestion);
                continue;
            }

            int candidateScore =
                    scoreTelephonySuggestion(elapsedRealtimeMillis, candidateSuggestion);
            if (candidateScore == TELEPHONY_INVALID_SCORE) {
                // Expected: This means the suggestion is obviously invalid or just too old.
                continue;
            }

            // Higher scores are better.
            if (bestSuggestion == null || bestScore < candidateScore) {
                bestSuggestion = candidateSuggestion;
                bestScore = candidateScore;
            } else if (bestScore == candidateScore) {
                // Tie! Use the suggestion with the lowest slotIndex.
                int candidateSlotIndex = candidateSuggestion.getSlotIndex();
                int bestSlotIndex = bestSuggestion.getSlotIndex();
                if (candidateSlotIndex < bestSlotIndex) {
                    bestSuggestion = candidateSuggestion;
                }
            }
        }
        return bestSuggestion;
    }

    private static int scoreTelephonySuggestion(
            long elapsedRealtimeMillis, @NonNull TelephonyTimeSuggestion timeSuggestion) {

        // Validate first.
        TimestampedValue<Long> utcTime = timeSuggestion.getUtcTime();
        if (!validateSuggestionUtcTime(elapsedRealtimeMillis, utcTime)) {
            Slog.w(LOG_TAG, "Existing suggestion found to be invalid "
                    + " elapsedRealtimeMillis=" + elapsedRealtimeMillis
                    + ", timeSuggestion=" + timeSuggestion);
            return TELEPHONY_INVALID_SCORE;
        }

        // The score is based on the age since receipt. Suggestions are bucketed so two
        // suggestions in the same bucket from different slotIndexs are scored the same.
        long ageMillis = elapsedRealtimeMillis - utcTime.getReferenceTimeMillis();

        // Turn the age into a discrete value: 0 <= bucketIndex < TELEPHONY_BUCKET_COUNT.
        int bucketIndex = (int) (ageMillis / TELEPHONY_BUCKET_SIZE_MILLIS);
        if (bucketIndex >= TELEPHONY_BUCKET_COUNT) {
            return TELEPHONY_INVALID_SCORE;
        }

        // We want the lowest bucket index to have the highest score. 0 > score >= BUCKET_COUNT.
        return TELEPHONY_BUCKET_COUNT - bucketIndex;
    }

    /** Returns the latest, valid, network suggestion. Returns {@code null} if there isn't one. */
    @GuardedBy("this")
    @Nullable
    private NetworkTimeSuggestion findLatestValidNetworkSuggestion() {
        NetworkTimeSuggestion networkSuggestion = mLastNetworkSuggestion.get();
        if (networkSuggestion == null) {
            // No network suggestions received. This is normal if there's no connectivity.
            return null;
        }

        TimestampedValue<Long> utcTime = networkSuggestion.getUtcTime();
        long elapsedRealTimeMillis = mCallback.elapsedRealtimeMillis();
        if (!validateSuggestionUtcTime(elapsedRealTimeMillis, utcTime)) {
            // The latest suggestion is not valid, usually due to its age.
            return null;
        }

        return networkSuggestion;
    }

    @GuardedBy("this")
    private void setSystemClockIfRequired(
            @Origin int origin, @NonNull TimestampedValue<Long> time, @NonNull String cause) {

        boolean isOriginAutomatic = isOriginAutomatic(origin);
        if (isOriginAutomatic) {
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
            setSystemClockUnderWakeLock(origin, time, cause);
        } finally {
            mCallback.releaseWakeLock();
        }
    }

    private static boolean isOriginAutomatic(@Origin int origin) {
        return origin != ORIGIN_MANUAL;
    }

    @GuardedBy("this")
    private void setSystemClockUnderWakeLock(
            int origin, @NonNull TimestampedValue<Long> newTime, @NonNull Object cause) {

        long elapsedRealtimeMillis = mCallback.elapsedRealtimeMillis();
        boolean isOriginAutomatic = isOriginAutomatic(origin);
        long actualSystemClockMillis = mCallback.systemClockMillis();
        if (isOriginAutomatic) {
            // CLOCK_PARANOIA : Check to see if this class owns the clock or if something else
            // may be setting the clock.
            if (mLastAutoSystemClockTimeSet != null) {
                long expectedTimeMillis = TimeDetectorStrategy.getTimeAt(
                        mLastAutoSystemClockTimeSet, elapsedRealtimeMillis);
                long absSystemClockDifference =
                        Math.abs(expectedTimeMillis - actualSystemClockMillis);
                if (absSystemClockDifference > SYSTEM_CLOCK_PARANOIA_THRESHOLD_MILLIS) {
                    Slog.w(LOG_TAG,
                            "System clock has not tracked elapsed real time clock. A clock may"
                                    + " be inaccurate or something unexpectedly set the system"
                                    + " clock."
                                    + " elapsedRealtimeMillis=" + elapsedRealtimeMillis
                                    + " expectedTimeMillis=" + expectedTimeMillis
                                    + " actualTimeMillis=" + actualSystemClockMillis
                                    + " cause=" + cause);
                }
            }
        }

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

        // CLOCK_PARANOIA : Record the last time this class set the system clock due to an auto-time
        // signal, or clear the record it is being done manually.
        if (isOriginAutomatic(origin)) {
            mLastAutoSystemClockTimeSet = newTime;
        } else {
            mLastAutoSystemClockTimeSet = null;
        }
    }

    /**
     * Returns the current best telephony suggestion. Not intended for general use: it is used
     * during tests to check strategy behavior.
     */
    @VisibleForTesting
    @Nullable
    public synchronized TelephonyTimeSuggestion findBestTelephonySuggestionForTests() {
        return findBestTelephonySuggestion();
    }

    /**
     * Returns the latest valid network suggestion. Not intended for general use: it is used during
     * tests to check strategy behavior.
     */
    @VisibleForTesting
    @Nullable
    public NetworkTimeSuggestion findLatestValidNetworkSuggestionForTests() {
        return findLatestValidNetworkSuggestion();
    }

    /**
     * A method used to inspect state during tests. Not intended for general use.
     */
    @VisibleForTesting
    @Nullable
    public synchronized TelephonyTimeSuggestion getLatestTelephonySuggestion(int slotIndex) {
        return mSuggestionBySlotIndex.get(slotIndex);
    }

    /**
     * A method used to inspect state during tests. Not intended for general use.
     */
    @VisibleForTesting
    @Nullable
    public NetworkTimeSuggestion getLatestNetworkSuggestion() {
        return mLastNetworkSuggestion.get();
    }

    private static boolean validateSuggestionUtcTime(
            long elapsedRealtimeMillis, TimestampedValue<Long> utcTime) {
        long referenceTimeMillis = utcTime.getReferenceTimeMillis();
        if (referenceTimeMillis > elapsedRealtimeMillis) {
            // Future reference times are ignored. They imply the reference time was wrong, or the
            // elapsed realtime clock used to derive it has gone backwards, neither of which are
            // supportable situations.
            return false;
        }

        // Any suggestion > MAX_AGE_MILLIS is treated as too old. Although time is relentless and
        // predictable, the accuracy of the reference time clock may be poor over long periods which
        // would lead to errors creeping in. Also, in edge cases where a bad suggestion has been
        // made and never replaced, it could also mean that the time detection code remains
        // opinionated using a bad invalid suggestion. This caps that edge case at MAX_AGE_MILLIS.
        long ageMillis = elapsedRealtimeMillis - referenceTimeMillis;
        return ageMillis <= MAX_UTC_TIME_AGE_MILLIS;
    }
}
