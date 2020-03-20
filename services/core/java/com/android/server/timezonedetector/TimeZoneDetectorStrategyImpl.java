/*
 * Copyright 2019 The Android Open Source Project
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

import static android.app.timezonedetector.TelephonyTimeZoneSuggestion.MATCH_TYPE_EMULATOR_ZONE_ID;
import static android.app.timezonedetector.TelephonyTimeZoneSuggestion.MATCH_TYPE_TEST_NETWORK_OFFSET_ONLY;
import static android.app.timezonedetector.TelephonyTimeZoneSuggestion.QUALITY_MULTIPLE_ZONES_WITH_DIFFERENT_OFFSETS;
import static android.app.timezonedetector.TelephonyTimeZoneSuggestion.QUALITY_MULTIPLE_ZONES_WITH_SAME_OFFSET;
import static android.app.timezonedetector.TelephonyTimeZoneSuggestion.QUALITY_SINGLE_ZONE;

import android.annotation.IntDef;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.app.timezonedetector.ManualTimeZoneSuggestion;
import android.app.timezonedetector.TelephonyTimeZoneSuggestion;
import android.content.Context;
import android.util.LocalLog;
import android.util.Slog;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.util.IndentingPrintWriter;

import java.io.PrintWriter;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.Objects;

/**
 * An implementation of {@link TimeZoneDetectorStrategy} that handle telephony and manual
 * suggestions. Suggestions are acted on or ignored as needed, dependent on the current "auto time
 * zone detection" setting.
 *
 * <p>For automatic detection, it keeps track of the most recent telephony suggestion from each
 * slotIndex and it uses the best suggestion based on a scoring algorithm. If several slotIndexes
 * provide the same score then the slotIndex with the lowest numeric value "wins". If the situation
 * changes and it is no longer possible to be confident about the time zone, slotIndexes must have
 * an empty suggestion submitted in order to "withdraw" their previous suggestion.
 *
 * <p>Most public methods are marked synchronized to ensure thread safety around internal state.
 */
public final class TimeZoneDetectorStrategyImpl implements TimeZoneDetectorStrategy {

    /**
     * Used by {@link TimeZoneDetectorStrategyImpl} to interact with the surrounding service. It can
     * be faked for tests.
     *
     * <p>Note: Because the system properties-derived values like
     * {@link #isAutoTimeZoneDetectionEnabled()}, {@link #isAutoTimeZoneDetectionEnabled()},
     * {@link #getDeviceTimeZone()} can be modified independently and from different threads (and
     * processes!), their use are prone to race conditions. That will be true until the
     * responsibility for setting their values is moved to {@link TimeZoneDetectorStrategyImpl}.
     */
    @VisibleForTesting
    public interface Callback {

        /**
         * Returns true if automatic time zone detection is enabled in settings.
         */
        boolean isAutoTimeZoneDetectionEnabled();

        /**
         * Returns true if the device has had an explicit time zone set.
         */
        boolean isDeviceTimeZoneInitialized();

        /**
         * Returns the device's currently configured time zone.
         */
        String getDeviceTimeZone();

        /**
         * Sets the device's time zone.
         */
        void setDeviceTimeZone(@NonNull String zoneId);
    }

    private static final String LOG_TAG = "TimeZoneDetectorStrategy";
    private static final boolean DBG = false;

    @IntDef({ ORIGIN_TELEPHONY, ORIGIN_MANUAL })
    @Retention(RetentionPolicy.SOURCE)
    public @interface Origin {}

    /** Used when a time value originated from a telephony signal. */
    @Origin
    private static final int ORIGIN_TELEPHONY = 1;

    /** Used when a time value originated from a user / manual settings. */
    @Origin
    private static final int ORIGIN_MANUAL = 2;

    /**
     * The abstract score for an empty or invalid telephony suggestion.
     *
     * Used to score telephony suggestions where there is no zone.
     */
    @VisibleForTesting
    public static final int TELEPHONY_SCORE_NONE = 0;

    /**
     * The abstract score for a low quality telephony suggestion.
     *
     * Used to score suggestions where:
     * The suggested zone ID is one of several possibilities, and the possibilities have different
     * offsets.
     *
     * You would have to be quite desperate to want to use this choice.
     */
    @VisibleForTesting
    public static final int TELEPHONY_SCORE_LOW = 1;

    /**
     * The abstract score for a medium quality telephony suggestion.
     *
     * Used for:
     * The suggested zone ID is one of several possibilities but at least the possibilities have the
     * same offset. Users would get the correct time but for the wrong reason. i.e. their device may
     * switch to DST at the wrong time and (for example) their calendar events.
     */
    @VisibleForTesting
    public static final int TELEPHONY_SCORE_MEDIUM = 2;

    /**
     * The abstract score for a high quality telephony suggestion.
     *
     * Used for:
     * The suggestion was for one zone ID and the answer was unambiguous and likely correct given
     * the info available.
     */
    @VisibleForTesting
    public static final int TELEPHONY_SCORE_HIGH = 3;

    /**
     * The abstract score for a highest quality telephony suggestion.
     *
     * Used for:
     * Suggestions that must "win" because they constitute test or emulator zone ID.
     */
    @VisibleForTesting
    public static final int TELEPHONY_SCORE_HIGHEST = 4;

    /**
     * The threshold at which telephony suggestions are good enough to use to set the device's time
     * zone.
     */
    @VisibleForTesting
    public static final int TELEPHONY_SCORE_USAGE_THRESHOLD = TELEPHONY_SCORE_MEDIUM;

    /**
     * The number of suggestions to keep. These are logged in bug reports to assist when debugging
     * issues with detection.
     */
    private static final int KEEP_SUGGESTION_HISTORY_SIZE = 10;

    @NonNull
    private final Callback mCallback;

    /**
     * A log that records the decisions / decision metadata that affected the device's time zone.
     * This is logged in bug reports to assist with debugging issues with detection.
     */
    @NonNull
    private final LocalLog mTimeZoneChangesLog = new LocalLog(30, false /* useLocalTimestamps */);

    /**
     * A mapping from slotIndex to a telephony time zone suggestion. We typically expect one or two
     * mappings: devices will have a small number of telephony devices and slotIndexes are assumed
     * to be stable.
     */
    @GuardedBy("this")
    private ArrayMapWithHistory<Integer, QualifiedTelephonyTimeZoneSuggestion>
            mSuggestionBySlotIndex = new ArrayMapWithHistory<>(KEEP_SUGGESTION_HISTORY_SIZE);

    /**
     * Creates a new instance of {@link TimeZoneDetectorStrategyImpl}.
     */
    public static TimeZoneDetectorStrategyImpl create(Context context) {
        Callback timeZoneDetectionServiceHelper = new TimeZoneDetectorCallbackImpl(context);
        return new TimeZoneDetectorStrategyImpl(timeZoneDetectionServiceHelper);
    }

    @VisibleForTesting
    public TimeZoneDetectorStrategyImpl(Callback callback) {
        mCallback = Objects.requireNonNull(callback);
    }

    @Override
    public synchronized void suggestManualTimeZone(@NonNull ManualTimeZoneSuggestion suggestion) {
        Objects.requireNonNull(suggestion);

        String timeZoneId = suggestion.getZoneId();
        String cause = "Manual time suggestion received: suggestion=" + suggestion;
        setDeviceTimeZoneIfRequired(ORIGIN_MANUAL, timeZoneId, cause);
    }

    @Override
    public synchronized void suggestTelephonyTimeZone(
            @NonNull TelephonyTimeZoneSuggestion suggestion) {
        if (DBG) {
            Slog.d(LOG_TAG, "Telephony suggestion received. newSuggestion=" + suggestion);
        }
        Objects.requireNonNull(suggestion);

        // Score the suggestion.
        int score = scoreTelephonySuggestion(suggestion);
        QualifiedTelephonyTimeZoneSuggestion scoredSuggestion =
                new QualifiedTelephonyTimeZoneSuggestion(suggestion, score);

        // Store the suggestion against the correct slotIndex.
        mSuggestionBySlotIndex.put(suggestion.getSlotIndex(), scoredSuggestion);

        // Now perform auto time zone detection. The new suggestion may be used to modify the time
        // zone setting.
        String reason = "New telephony time suggested. suggestion=" + suggestion;
        doAutoTimeZoneDetection(reason);
    }

    private static int scoreTelephonySuggestion(@NonNull TelephonyTimeZoneSuggestion suggestion) {
        int score;
        if (suggestion.getZoneId() == null) {
            score = TELEPHONY_SCORE_NONE;
        } else if (suggestion.getMatchType() == MATCH_TYPE_TEST_NETWORK_OFFSET_ONLY
                || suggestion.getMatchType() == MATCH_TYPE_EMULATOR_ZONE_ID) {
            // Handle emulator / test cases : These suggestions should always just be used.
            score = TELEPHONY_SCORE_HIGHEST;
        } else if (suggestion.getQuality() == QUALITY_SINGLE_ZONE) {
            score = TELEPHONY_SCORE_HIGH;
        } else if (suggestion.getQuality() == QUALITY_MULTIPLE_ZONES_WITH_SAME_OFFSET) {
            // The suggestion may be wrong, but at least the offset should be correct.
            score = TELEPHONY_SCORE_MEDIUM;
        } else if (suggestion.getQuality() == QUALITY_MULTIPLE_ZONES_WITH_DIFFERENT_OFFSETS) {
            // The suggestion has a good chance of being wrong.
            score = TELEPHONY_SCORE_LOW;
        } else {
            throw new AssertionError();
        }
        return score;
    }

    /**
     * Finds the best available time zone suggestion from all slotIndexes. If it is high-enough
     * quality and automatic time zone detection is enabled then it will be set on the device. The
     * outcome can be that this strategy becomes / remains un-opinionated and nothing is set.
     */
    @GuardedBy("this")
    private void doAutoTimeZoneDetection(@NonNull String detectionReason) {
        if (!mCallback.isAutoTimeZoneDetectionEnabled()) {
            // Avoid doing unnecessary work with this (race-prone) check.
            return;
        }

        QualifiedTelephonyTimeZoneSuggestion bestTelephonySuggestion =
                findBestTelephonySuggestion();

        // Work out what to do with the best suggestion.
        if (bestTelephonySuggestion == null) {
            // There is no telephony suggestion available at all. Become un-opinionated.
            if (DBG) {
                Slog.d(LOG_TAG, "Could not determine time zone: No best telephony suggestion."
                        + " detectionReason=" + detectionReason);
            }
            return;
        }

        boolean suggestionGoodEnough =
                bestTelephonySuggestion.score >= TELEPHONY_SCORE_USAGE_THRESHOLD;
        if (!suggestionGoodEnough) {
            if (DBG) {
                Slog.d(LOG_TAG, "Best suggestion not good enough."
                        + " bestTelephonySuggestion=" + bestTelephonySuggestion
                        + ", detectionReason=" + detectionReason);
            }
            return;
        }

        // Paranoia: Every suggestion above the SCORE_USAGE_THRESHOLD should have a non-null time
        // zone ID.
        String newZoneId = bestTelephonySuggestion.suggestion.getZoneId();
        if (newZoneId == null) {
            Slog.w(LOG_TAG, "Empty zone suggestion scored higher than expected. This is an error:"
                    + " bestTelephonySuggestion=" + bestTelephonySuggestion
                    + " detectionReason=" + detectionReason);
            return;
        }

        String zoneId = bestTelephonySuggestion.suggestion.getZoneId();
        String cause = "Found good suggestion."
                + ", bestTelephonySuggestion=" + bestTelephonySuggestion
                + ", detectionReason=" + detectionReason;
        setDeviceTimeZoneIfRequired(ORIGIN_TELEPHONY, zoneId, cause);
    }

    @GuardedBy("this")
    private void setDeviceTimeZoneIfRequired(
            @Origin int origin, @NonNull String newZoneId, @NonNull String cause) {
        Objects.requireNonNull(newZoneId);
        Objects.requireNonNull(cause);

        boolean isOriginAutomatic = isOriginAutomatic(origin);
        if (isOriginAutomatic) {
            if (!mCallback.isAutoTimeZoneDetectionEnabled()) {
                if (DBG) {
                    Slog.d(LOG_TAG, "Auto time zone detection is not enabled."
                            + " origin=" + origin
                            + ", newZoneId=" + newZoneId
                            + ", cause=" + cause);
                }
                return;
            }
        } else {
            if (mCallback.isAutoTimeZoneDetectionEnabled()) {
                if (DBG) {
                    Slog.d(LOG_TAG, "Auto time zone detection is enabled."
                            + " origin=" + origin
                            + ", newZoneId=" + newZoneId
                            + ", cause=" + cause);
                }
                return;
            }
        }

        String currentZoneId = mCallback.getDeviceTimeZone();

        // Avoid unnecessary changes / intents.
        if (newZoneId.equals(currentZoneId)) {
            // No need to set the device time zone - the setting is already what we would be
            // suggesting.
            if (DBG) {
                Slog.d(LOG_TAG, "No need to change the time zone;"
                        + " device is already set to the suggested zone."
                        + " origin=" + origin
                        + ", newZoneId=" + newZoneId
                        + ", cause=" + cause);
            }
            return;
        }

        mCallback.setDeviceTimeZone(newZoneId);
        String msg = "Set device time zone."
                + " origin=" + origin
                + ", currentZoneId=" + currentZoneId
                + ", newZoneId=" + newZoneId
                + ", cause=" + cause;
        if (DBG) {
            Slog.d(LOG_TAG, msg);
        }
        mTimeZoneChangesLog.log(msg);
    }

    private static boolean isOriginAutomatic(@Origin int origin) {
        return origin != ORIGIN_MANUAL;
    }

    @GuardedBy("this")
    @Nullable
    private QualifiedTelephonyTimeZoneSuggestion findBestTelephonySuggestion() {
        QualifiedTelephonyTimeZoneSuggestion bestSuggestion = null;

        // Iterate over the latest QualifiedTelephonyTimeZoneSuggestion objects received for each
        // slotIndex and find the best. Note that we deliberately do not look at age: the caller can
        // rate-limit so age is not a strong indicator of confidence. Instead, the callers are
        // expected to withdraw suggestions they no longer have confidence in.
        for (int i = 0; i < mSuggestionBySlotIndex.size(); i++) {
            QualifiedTelephonyTimeZoneSuggestion candidateSuggestion =
                    mSuggestionBySlotIndex.valueAt(i);
            if (candidateSuggestion == null) {
                // Unexpected
                continue;
            }

            if (bestSuggestion == null) {
                bestSuggestion = candidateSuggestion;
            } else if (candidateSuggestion.score > bestSuggestion.score) {
                bestSuggestion = candidateSuggestion;
            } else if (candidateSuggestion.score == bestSuggestion.score) {
                // Tie! Use the suggestion with the lowest slotIndex.
                int candidateSlotIndex = candidateSuggestion.suggestion.getSlotIndex();
                int bestSlotIndex = bestSuggestion.suggestion.getSlotIndex();
                if (candidateSlotIndex < bestSlotIndex) {
                    bestSuggestion = candidateSuggestion;
                }
            }
        }
        return bestSuggestion;
    }

    /**
     * Returns the current best telephony suggestion. Not intended for general use: it is used
     * during tests to check strategy behavior.
     */
    @VisibleForTesting
    @Nullable
    public synchronized QualifiedTelephonyTimeZoneSuggestion findBestTelephonySuggestionForTests() {
        return findBestTelephonySuggestion();
    }

    @Override
    public synchronized void handleAutoTimeZoneDetectionChanged() {
        if (DBG) {
            Slog.d(LOG_TAG, "handleTimeZoneDetectionChange() called");
        }
        if (mCallback.isAutoTimeZoneDetectionEnabled()) {
            // When the user enabled time zone detection, run the time zone detection and change the
            // device time zone if possible.
            String reason = "Auto time zone detection setting enabled.";
            doAutoTimeZoneDetection(reason);
        }
    }

    /**
     * Dumps internal state such as field values.
     */
    @Override
    public synchronized void dump(PrintWriter pw, String[] args) {
        IndentingPrintWriter ipw = new IndentingPrintWriter(pw, " ");
        ipw.println("TimeZoneDetectorStrategy:");

        ipw.increaseIndent(); // level 1
        ipw.println("mCallback.isTimeZoneDetectionEnabled()="
                + mCallback.isAutoTimeZoneDetectionEnabled());
        ipw.println("mCallback.isDeviceTimeZoneInitialized()="
                + mCallback.isDeviceTimeZoneInitialized());
        ipw.println("mCallback.getDeviceTimeZone()="
                + mCallback.getDeviceTimeZone());

        ipw.println("Time zone change log:");
        ipw.increaseIndent(); // level 2
        mTimeZoneChangesLog.dump(ipw);
        ipw.decreaseIndent(); // level 2

        ipw.println("Telephony suggestion history:");
        ipw.increaseIndent(); // level 2
        mSuggestionBySlotIndex.dump(ipw);
        ipw.decreaseIndent(); // level 2
        ipw.decreaseIndent(); // level 1
        ipw.flush();
    }

    /**
     * A method used to inspect strategy state during tests. Not intended for general use.
     */
    @VisibleForTesting
    public synchronized QualifiedTelephonyTimeZoneSuggestion getLatestTelephonySuggestion(
            int slotIndex) {
        return mSuggestionBySlotIndex.get(slotIndex);
    }

    /**
     * A {@link TelephonyTimeZoneSuggestion} with additional qualifying metadata.
     */
    @VisibleForTesting
    public static class QualifiedTelephonyTimeZoneSuggestion {

        @VisibleForTesting
        public final TelephonyTimeZoneSuggestion suggestion;

        /**
         * The score the suggestion has been given. This can be used to rank against other
         * suggestions of the same type.
         */
        @VisibleForTesting
        public final int score;

        @VisibleForTesting
        public QualifiedTelephonyTimeZoneSuggestion(
                TelephonyTimeZoneSuggestion suggestion, int score) {
            this.suggestion = suggestion;
            this.score = score;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) {
                return true;
            }
            if (o == null || getClass() != o.getClass()) {
                return false;
            }
            QualifiedTelephonyTimeZoneSuggestion that = (QualifiedTelephonyTimeZoneSuggestion) o;
            return score == that.score
                    && suggestion.equals(that.suggestion);
        }

        @Override
        public int hashCode() {
            return Objects.hash(score, suggestion);
        }

        @Override
        public String toString() {
            return "QualifiedTelephonyTimeZoneSuggestion{"
                    + "suggestion=" + suggestion
                    + ", score=" + score
                    + '}';
        }
    }
}
