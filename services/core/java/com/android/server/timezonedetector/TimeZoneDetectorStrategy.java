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

import static android.app.timezonedetector.PhoneTimeZoneSuggestion.MATCH_TYPE_EMULATOR_ZONE_ID;
import static android.app.timezonedetector.PhoneTimeZoneSuggestion.MATCH_TYPE_TEST_NETWORK_OFFSET_ONLY;
import static android.app.timezonedetector.PhoneTimeZoneSuggestion.QUALITY_MULTIPLE_ZONES_WITH_DIFFERENT_OFFSETS;
import static android.app.timezonedetector.PhoneTimeZoneSuggestion.QUALITY_MULTIPLE_ZONES_WITH_SAME_OFFSET;
import static android.app.timezonedetector.PhoneTimeZoneSuggestion.QUALITY_SINGLE_ZONE;

import android.annotation.IntDef;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.app.timezonedetector.ManualTimeZoneSuggestion;
import android.app.timezonedetector.PhoneTimeZoneSuggestion;
import android.content.Context;
import android.util.ArrayMap;
import android.util.LocalLog;
import android.util.Slog;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.util.IndentingPrintWriter;

import java.io.PrintWriter;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.LinkedList;
import java.util.Map;
import java.util.Objects;

/**
 * A singleton, stateful time zone detection strategy that is aware of user (manual) suggestions and
 * suggestions from multiple phone devices. Suggestions are acted on or ignored as needed, dependent
 * on the current "auto time zone detection" setting.
 *
 * <p>For automatic detection it keeps track of the most recent suggestion from each phone it uses
 * the best suggestion based on a scoring algorithm. If several phones provide the same score then
 * the phone with the lowest numeric ID "wins". If the situation changes and it is no longer
 * possible to be confident about the time zone, phones must submit an empty suggestion in order to
 * "withdraw" their previous suggestion.
 */
public class TimeZoneDetectorStrategy {

    /**
     * Used by {@link TimeZoneDetectorStrategy} to interact with the surrounding service. It can be
     * faked for tests.
     *
     * <p>Note: Because the system properties-derived values like
     * {@link #isAutoTimeZoneDetectionEnabled()}, {@link #isAutoTimeZoneDetectionEnabled()},
     * {@link #getDeviceTimeZone()} can be modified independently and from different threads (and
     * processes!), their use are prone to race conditions. That will be true until the
     * responsibility for setting their values is moved to {@link TimeZoneDetectorStrategy}.
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
        void setDeviceTimeZone(@NonNull String zoneId, boolean sendNetworkBroadcast);
    }

    private static final String LOG_TAG = "TimeZoneDetectorStrategy";
    private static final boolean DBG = false;

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
     * The abstract score for an empty or invalid phone suggestion.
     *
     * Used to score phone suggestions where there is no zone.
     */
    @VisibleForTesting
    public static final int PHONE_SCORE_NONE = 0;

    /**
     * The abstract score for a low quality phone suggestion.
     *
     * Used to score suggestions where:
     * The suggested zone ID is one of several possibilities, and the possibilities have different
     * offsets.
     *
     * You would have to be quite desperate to want to use this choice.
     */
    @VisibleForTesting
    public static final int PHONE_SCORE_LOW = 1;

    /**
     * The abstract score for a medium quality phone suggestion.
     *
     * Used for:
     * The suggested zone ID is one of several possibilities but at least the possibilities have the
     * same offset. Users would get the correct time but for the wrong reason. i.e. their device may
     * switch to DST at the wrong time and (for example) their calendar events.
     */
    @VisibleForTesting
    public static final int PHONE_SCORE_MEDIUM = 2;

    /**
     * The abstract score for a high quality phone suggestion.
     *
     * Used for:
     * The suggestion was for one zone ID and the answer was unambiguous and likely correct given
     * the info available.
     */
    @VisibleForTesting
    public static final int PHONE_SCORE_HIGH = 3;

    /**
     * The abstract score for a highest quality phone suggestion.
     *
     * Used for:
     * Suggestions that must "win" because they constitute test or emulator zone ID.
     */
    @VisibleForTesting
    public static final int PHONE_SCORE_HIGHEST = 4;

    /**
     * The threshold at which phone suggestions are good enough to use to set the device's time
     * zone.
     */
    @VisibleForTesting
    public static final int PHONE_SCORE_USAGE_THRESHOLD = PHONE_SCORE_MEDIUM;

    /** The number of previous phone suggestions to keep for each ID (for use during debugging). */
    private static final int KEEP_PHONE_SUGGESTION_HISTORY_SIZE = 30;

    @NonNull
    private final Callback mCallback;

    /**
     * A log that records the decisions / decision metadata that affected the device's time zone
     * (for use during debugging).
     */
    @NonNull
    private final LocalLog mTimeZoneChangesLog = new LocalLog(30, false /* useLocalTimestamps */);

    /**
     * A mapping from phoneId to a linked list of phone time zone suggestions (the head being the
     * latest). We typically expect one or two entries in this Map: devices will have a small number
     * of telephony devices and phoneIds are assumed to be stable. The LinkedList associated with
     * the ID will not exceed {@link #KEEP_PHONE_SUGGESTION_HISTORY_SIZE} in size.
     */
    @GuardedBy("this")
    private ArrayMap<Integer, LinkedList<QualifiedPhoneTimeZoneSuggestion>> mSuggestionByPhoneId =
            new ArrayMap<>();

    /**
     * Creates a new instance of {@link TimeZoneDetectorStrategy}.
     */
    public static TimeZoneDetectorStrategy create(Context context) {
        Callback timeZoneDetectionServiceHelper = new TimeZoneDetectorCallbackImpl(context);
        return new TimeZoneDetectorStrategy(timeZoneDetectionServiceHelper);
    }

    @VisibleForTesting
    public TimeZoneDetectorStrategy(Callback callback) {
        mCallback = Objects.requireNonNull(callback);
    }

    /** Process the suggested manually- / user-entered time zone. */
    public synchronized void suggestManualTimeZone(@NonNull ManualTimeZoneSuggestion suggestion) {
        Objects.requireNonNull(suggestion);

        String timeZoneId = suggestion.getZoneId();
        String cause = "Manual time suggestion received: suggestion=" + suggestion;
        setDeviceTimeZoneIfRequired(ORIGIN_MANUAL, timeZoneId, cause);
    }

    /**
     * Suggests a time zone for the device, or withdraws a previous suggestion if
     * {@link PhoneTimeZoneSuggestion#getZoneId()} is {@code null}. The suggestion is scoped to a
     * specific {@link PhoneTimeZoneSuggestion#getPhoneId() phone}.
     * See {@link PhoneTimeZoneSuggestion} for an explanation of the metadata associated with a
     * suggestion. The strategy uses suggestions to decide whether to modify the device's time zone
     * setting and what to set it to.
     */
    public synchronized void suggestPhoneTimeZone(@NonNull PhoneTimeZoneSuggestion suggestion) {
        if (DBG) {
            Slog.d(LOG_TAG, "Phone suggestion received. newSuggestion=" + suggestion);
        }
        Objects.requireNonNull(suggestion);

        // Score the suggestion.
        int score = scorePhoneSuggestion(suggestion);
        QualifiedPhoneTimeZoneSuggestion scoredSuggestion =
                new QualifiedPhoneTimeZoneSuggestion(suggestion, score);

        // Store the suggestion against the correct phoneId.
        LinkedList<QualifiedPhoneTimeZoneSuggestion> suggestions =
                mSuggestionByPhoneId.get(suggestion.getPhoneId());
        if (suggestions == null) {
            suggestions = new LinkedList<>();
            mSuggestionByPhoneId.put(suggestion.getPhoneId(), suggestions);
        }
        suggestions.addFirst(scoredSuggestion);
        if (suggestions.size() > KEEP_PHONE_SUGGESTION_HISTORY_SIZE) {
            suggestions.removeLast();
        }

        // Now perform auto time zone detection. The new suggestion may be used to modify the time
        // zone setting.
        String reason = "New phone time suggested. suggestion=" + suggestion;
        doAutoTimeZoneDetection(reason);
    }

    private static int scorePhoneSuggestion(@NonNull PhoneTimeZoneSuggestion suggestion) {
        int score;
        if (suggestion.getZoneId() == null) {
            score = PHONE_SCORE_NONE;
        } else if (suggestion.getMatchType() == MATCH_TYPE_TEST_NETWORK_OFFSET_ONLY
                || suggestion.getMatchType() == MATCH_TYPE_EMULATOR_ZONE_ID) {
            // Handle emulator / test cases : These suggestions should always just be used.
            score = PHONE_SCORE_HIGHEST;
        } else if (suggestion.getQuality() == QUALITY_SINGLE_ZONE) {
            score = PHONE_SCORE_HIGH;
        } else if (suggestion.getQuality() == QUALITY_MULTIPLE_ZONES_WITH_SAME_OFFSET) {
            // The suggestion may be wrong, but at least the offset should be correct.
            score = PHONE_SCORE_MEDIUM;
        } else if (suggestion.getQuality() == QUALITY_MULTIPLE_ZONES_WITH_DIFFERENT_OFFSETS) {
            // The suggestion has a good chance of being wrong.
            score = PHONE_SCORE_LOW;
        } else {
            throw new AssertionError();
        }
        return score;
    }

    /**
     * Finds the best available time zone suggestion from all phones. If it is high-enough quality
     * and automatic time zone detection is enabled then it will be set on the device. The outcome
     * can be that this strategy becomes / remains un-opinionated and nothing is set.
     */
    @GuardedBy("this")
    private void doAutoTimeZoneDetection(@NonNull String detectionReason) {
        if (!mCallback.isAutoTimeZoneDetectionEnabled()) {
            // Avoid doing unnecessary work with this (race-prone) check.
            return;
        }

        QualifiedPhoneTimeZoneSuggestion bestPhoneSuggestion = findBestPhoneSuggestion();

        // Work out what to do with the best suggestion.
        if (bestPhoneSuggestion == null) {
            // There is no phone suggestion available at all. Become un-opinionated.
            if (DBG) {
                Slog.d(LOG_TAG, "Could not determine time zone: No best phone suggestion."
                        + " detectionReason=" + detectionReason);
            }
            return;
        }

        // Special case handling for uninitialized devices. This should only happen once.
        String newZoneId = bestPhoneSuggestion.suggestion.getZoneId();
        if (newZoneId != null && !mCallback.isDeviceTimeZoneInitialized()) {
            String cause = "Device has no time zone set. Attempting to set the device to the best"
                    + " available suggestion."
                    + " bestPhoneSuggestion=" + bestPhoneSuggestion
                    + ", detectionReason=" + detectionReason;
            Slog.i(LOG_TAG, cause);
            setDeviceTimeZoneIfRequired(ORIGIN_PHONE, newZoneId, cause);
            return;
        }

        boolean suggestionGoodEnough = bestPhoneSuggestion.score >= PHONE_SCORE_USAGE_THRESHOLD;
        if (!suggestionGoodEnough) {
            if (DBG) {
                Slog.d(LOG_TAG, "Best suggestion not good enough."
                        + " bestPhoneSuggestion=" + bestPhoneSuggestion
                        + ", detectionReason=" + detectionReason);
            }
            return;
        }

        // Paranoia: Every suggestion above the SCORE_USAGE_THRESHOLD should have a non-null time
        // zone ID.
        if (newZoneId == null) {
            Slog.w(LOG_TAG, "Empty zone suggestion scored higher than expected. This is an error:"
                    + " bestPhoneSuggestion=" + bestPhoneSuggestion
                    + " detectionReason=" + detectionReason);
            return;
        }

        String zoneId = bestPhoneSuggestion.suggestion.getZoneId();
        String cause = "Found good suggestion."
                + ", bestPhoneSuggestion=" + bestPhoneSuggestion
                + ", detectionReason=" + detectionReason;
        setDeviceTimeZoneIfRequired(ORIGIN_PHONE, zoneId, cause);
    }

    @GuardedBy("this")
    private void setDeviceTimeZoneIfRequired(
            @Origin int origin, @NonNull String newZoneId, @NonNull String cause) {
        Objects.requireNonNull(newZoneId);
        Objects.requireNonNull(cause);

        boolean sendNetworkBroadcast = (origin == ORIGIN_PHONE);
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

        mCallback.setDeviceTimeZone(newZoneId, sendNetworkBroadcast);
        String msg = "Set device time zone."
                + " origin=" + origin
                + ", currentZoneId=" + currentZoneId
                + ", newZoneId=" + newZoneId
                + ", sendNetworkBroadcast" + sendNetworkBroadcast
                + ", cause=" + cause;
        if (DBG) {
            Slog.d(LOG_TAG, msg);
        }
        mTimeZoneChangesLog.log(msg);
    }

    private static boolean isOriginAutomatic(@Origin int origin) {
        return origin == ORIGIN_PHONE;
    }

    @GuardedBy("this")
    @Nullable
    private QualifiedPhoneTimeZoneSuggestion findBestPhoneSuggestion() {
        QualifiedPhoneTimeZoneSuggestion bestSuggestion = null;

        // Iterate over the latest QualifiedPhoneTimeZoneSuggestion objects received for each phone
        // and find the best. Note that we deliberately do not look at age: the caller can
        // rate-limit so age is not a strong indicator of confidence. Instead, the callers are
        // expected to withdraw suggestions they no longer have confidence in.
        for (int i = 0; i < mSuggestionByPhoneId.size(); i++) {
            LinkedList<QualifiedPhoneTimeZoneSuggestion> phoneSuggestions =
                    mSuggestionByPhoneId.valueAt(i);
            if (phoneSuggestions == null) {
                // Unexpected
                continue;
            }
            QualifiedPhoneTimeZoneSuggestion candidateSuggestion = phoneSuggestions.getFirst();
            if (candidateSuggestion == null) {
                // Unexpected
                continue;
            }

            if (bestSuggestion == null) {
                bestSuggestion = candidateSuggestion;
            } else if (candidateSuggestion.score > bestSuggestion.score) {
                bestSuggestion = candidateSuggestion;
            } else if (candidateSuggestion.score == bestSuggestion.score) {
                // Tie! Use the suggestion with the lowest phoneId.
                int candidatePhoneId = candidateSuggestion.suggestion.getPhoneId();
                int bestPhoneId = bestSuggestion.suggestion.getPhoneId();
                if (candidatePhoneId < bestPhoneId) {
                    bestSuggestion = candidateSuggestion;
                }
            }
        }
        return bestSuggestion;
    }

    /**
     * Returns the current best phone suggestion. Not intended for general use: it is used during
     * tests to check strategy behavior.
     */
    @VisibleForTesting
    @Nullable
    public synchronized QualifiedPhoneTimeZoneSuggestion findBestPhoneSuggestionForTests() {
        return findBestPhoneSuggestion();
    }

    /**
     * Called when there has been a change to the automatic time zone detection setting.
     */
    @VisibleForTesting
    public synchronized void handleAutoTimeZoneDetectionChange() {
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
    public synchronized void dumpState(PrintWriter pw, String[] args) {
        pw.println("TimeZoneDetectorStrategy:");
        pw.println("mCallback.isTimeZoneDetectionEnabled()="
                + mCallback.isAutoTimeZoneDetectionEnabled());
        pw.println("mCallback.isDeviceTimeZoneInitialized()="
                + mCallback.isDeviceTimeZoneInitialized());
        pw.println("mCallback.getDeviceTimeZone()="
                + mCallback.getDeviceTimeZone());

        IndentingPrintWriter ipw = new IndentingPrintWriter(pw, " ");
        ipw.println("Time zone change log:");
        ipw.increaseIndent(); // level 2
        mTimeZoneChangesLog.dump(ipw);
        ipw.decreaseIndent(); // level 2

        ipw.println("Phone suggestion history:");
        ipw.increaseIndent(); // level 2
        for (Map.Entry<Integer, LinkedList<QualifiedPhoneTimeZoneSuggestion>> entry
                : mSuggestionByPhoneId.entrySet()) {
            ipw.println("Phone " + entry.getKey());

            ipw.increaseIndent(); // level 3
            for (QualifiedPhoneTimeZoneSuggestion suggestion : entry.getValue()) {
                ipw.println(suggestion);
            }
            ipw.decreaseIndent(); // level 3
        }
        ipw.decreaseIndent(); // level 2
        ipw.decreaseIndent(); // level 1
        ipw.flush();

        pw.flush();
    }

    /**
     * A method used to inspect strategy state during tests. Not intended for general use.
     */
    @VisibleForTesting
    public synchronized QualifiedPhoneTimeZoneSuggestion getLatestPhoneSuggestion(int phoneId) {
        LinkedList<QualifiedPhoneTimeZoneSuggestion> suggestions =
                mSuggestionByPhoneId.get(phoneId);
        if (suggestions == null) {
            return null;
        }
        return suggestions.getFirst();
    }

    /**
     * A {@link PhoneTimeZoneSuggestion} with additional qualifying metadata.
     */
    @VisibleForTesting
    public static class QualifiedPhoneTimeZoneSuggestion {

        @VisibleForTesting
        public final PhoneTimeZoneSuggestion suggestion;

        /**
         * The score the suggestion has been given. This can be used to rank against other
         * suggestions of the same type.
         */
        @VisibleForTesting
        public final int score;

        @VisibleForTesting
        public QualifiedPhoneTimeZoneSuggestion(PhoneTimeZoneSuggestion suggestion, int score) {
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
            QualifiedPhoneTimeZoneSuggestion that = (QualifiedPhoneTimeZoneSuggestion) o;
            return score == that.score
                    && suggestion.equals(that.suggestion);
        }

        @Override
        public int hashCode() {
            return Objects.hash(score, suggestion);
        }

        @Override
        public String toString() {
            return "QualifiedPhoneTimeZoneSuggestion{"
                    + "suggestion=" + suggestion
                    + ", score=" + score
                    + '}';
        }
    }
}
