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

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.app.timezonedetector.PhoneTimeZoneSuggestion;
import android.content.Context;
import android.util.ArrayMap;
import android.util.LocalLog;
import android.util.Slog;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.util.IndentingPrintWriter;

import java.io.PrintWriter;
import java.util.LinkedList;
import java.util.Map;
import java.util.Objects;

/**
 * A singleton, stateful time zone detection strategy that is aware of multiple phone devices. It
 * keeps track of the most recent suggestion from each phone and it uses the best based on a scoring
 * algorithm. If several phones provide the same score then the phone with the lowest numeric ID
 * "wins". If the situation changes and it is no longer possible to be confident about the time
 * zone, phones must submit an empty suggestion in order to "withdraw" their previous suggestion.
 */
public class TimeZoneDetectorStrategy {

    /**
     * Used by {@link TimeZoneDetectorStrategy} to interact with the surrounding service. It can be
     * faked for tests.
     */
    @VisibleForTesting
    public interface Callback {

        /**
         * Returns true if automatic time zone detection is enabled in settings.
         */
        boolean isTimeZoneDetectionEnabled();

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

    static final String LOG_TAG = "TimeZoneDetectorStrategy";
    static final boolean DBG = false;

    /**
     * The abstract score for an empty or invalid suggestion.
     *
     * Used to score suggestions where there is no zone.
     */
    @VisibleForTesting
    public static final int SCORE_NONE = 0;

    /**
     * The abstract score for a low quality suggestion.
     *
     * Used to score suggestions where:
     * The suggested zone ID is one of several possibilities, and the possibilities have different
     * offsets.
     *
     * You would have to be quite desperate to want to use this choice.
     */
    @VisibleForTesting
    public static final int SCORE_LOW = 1;

    /**
     * The abstract score for a medium quality suggestion.
     *
     * Used for:
     * The suggested zone ID is one of several possibilities but at least the possibilities have the
     * same offset. Users would get the correct time but for the wrong reason. i.e. their device may
     * switch to DST at the wrong time and (for example) their calendar events.
     */
    @VisibleForTesting
    public static final int SCORE_MEDIUM = 2;

    /**
     * The abstract score for a high quality suggestion.
     *
     * Used for:
     * The suggestion was for one zone ID and the answer was unambiguous and likely correct given
     * the info available.
     */
    @VisibleForTesting
    public static final int SCORE_HIGH = 3;

    /**
     * The abstract score for a highest quality suggestion.
     *
     * Used for:
     * Suggestions that must "win" because they constitute test or emulator zone ID.
     */
    @VisibleForTesting
    public static final int SCORE_HIGHEST = 4;

    /** The threshold at which suggestions are good enough to use to set the device's time zone. */
    @VisibleForTesting
    public static final int SCORE_USAGE_THRESHOLD = SCORE_MEDIUM;

    /** The number of previous phone suggestions to keep for each ID (for use during debugging). */
    private static final int KEEP_SUGGESTION_HISTORY_SIZE = 30;

    @NonNull
    private final Callback mCallback;

    /**
     * A log that records the decisions / decision metadata that affected the device's time zone
     * (for use during debugging).
     */
    @NonNull
    private final LocalLog mTimeZoneChangesLog = new LocalLog(30);

    /**
     * A mapping from phoneId to a linked list of time zone suggestions (the head being the latest).
     * We typically expect one or two entries in this Map: devices will have a small number
     * of telephony devices and phoneIds are assumed to be stable. The LinkedList associated with
     * the ID will not exceed {@link #KEEP_SUGGESTION_HISTORY_SIZE} in size.
     */
    @GuardedBy("this")
    private ArrayMap<Integer, LinkedList<QualifiedPhoneTimeZoneSuggestion>> mSuggestionByPhoneId =
            new ArrayMap<>();

    /**
     * The most recent best guess of time zone from all phones. Can be {@code null} to indicate
     * there would be no current suggestion.
     */
    @GuardedBy("this")
    @Nullable
    private QualifiedPhoneTimeZoneSuggestion mCurrentSuggestion;

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

    /**
     * Suggests a time zone for the device, or withdraws a previous suggestion if
     * {@link PhoneTimeZoneSuggestion#getZoneId()} is {@code null}. The suggestion is scoped to a
     * specific {@link PhoneTimeZoneSuggestion#getPhoneId() phone}.
     * See {@link PhoneTimeZoneSuggestion} for an explanation of the metadata associated with a
     * suggestion. The service uses suggestions to decide whether to modify the device's time zone
     * setting and what to set it to.
     */
    public synchronized void suggestPhoneTimeZone(@NonNull PhoneTimeZoneSuggestion newSuggestion) {
        if (DBG) {
            Slog.d(LOG_TAG, "suggestPhoneTimeZone: newSuggestion=" + newSuggestion);
        }
        Objects.requireNonNull(newSuggestion);

        int score = scoreSuggestion(newSuggestion);
        QualifiedPhoneTimeZoneSuggestion scoredSuggestion =
                new QualifiedPhoneTimeZoneSuggestion(newSuggestion, score);

        // Record the suggestion against the correct phoneId.
        LinkedList<QualifiedPhoneTimeZoneSuggestion> suggestions =
                mSuggestionByPhoneId.get(newSuggestion.getPhoneId());
        if (suggestions == null) {
            suggestions = new LinkedList<>();
            mSuggestionByPhoneId.put(newSuggestion.getPhoneId(), suggestions);
        }
        suggestions.addFirst(scoredSuggestion);
        if (suggestions.size() > KEEP_SUGGESTION_HISTORY_SIZE) {
            suggestions.removeLast();
        }

        // Now run the competition between the phones' suggestions.
        doTimeZoneDetection();
    }

    private static int scoreSuggestion(@NonNull PhoneTimeZoneSuggestion suggestion) {
        int score;
        if (suggestion.getZoneId() == null) {
            score = SCORE_NONE;
        } else if (suggestion.getMatchType() == MATCH_TYPE_TEST_NETWORK_OFFSET_ONLY
                || suggestion.getMatchType() == MATCH_TYPE_EMULATOR_ZONE_ID) {
            // Handle emulator / test cases : These suggestions should always just be used.
            score = SCORE_HIGHEST;
        } else if (suggestion.getQuality() == QUALITY_SINGLE_ZONE) {
            score = SCORE_HIGH;
        } else if (suggestion.getQuality() == QUALITY_MULTIPLE_ZONES_WITH_SAME_OFFSET) {
            // The suggestion may be wrong, but at least the offset should be correct.
            score = SCORE_MEDIUM;
        } else if (suggestion.getQuality() == QUALITY_MULTIPLE_ZONES_WITH_DIFFERENT_OFFSETS) {
            // The suggestion has a good chance of being wrong.
            score = SCORE_LOW;
        } else {
            throw new AssertionError();
        }
        return score;
    }

    /**
     * Finds the best available time zone suggestion from all phones. If it is high-enough quality
     * and automatic time zone detection is enabled then it will be set on the device. The outcome
     * can be that this service becomes / remains un-opinionated and nothing is set.
     */
    @GuardedBy("this")
    private void doTimeZoneDetection() {
        QualifiedPhoneTimeZoneSuggestion bestSuggestion = findBestSuggestion();
        boolean timeZoneDetectionEnabled = mCallback.isTimeZoneDetectionEnabled();

        // Work out what to do with the best suggestion.
        if (bestSuggestion == null) {
            // There is no suggestion. Become un-opinionated.
            if (DBG) {
                Slog.d(LOG_TAG, "doTimeZoneDetection: No good suggestion."
                        + " bestSuggestion=null"
                        + ", timeZoneDetectionEnabled=" + timeZoneDetectionEnabled);
            }
            mCurrentSuggestion = null;
            return;
        }

        // Special case handling for uninitialized devices. This should only happen once.
        String newZoneId = bestSuggestion.suggestion.getZoneId();
        if (newZoneId != null && !mCallback.isDeviceTimeZoneInitialized()) {
            Slog.i(LOG_TAG, "doTimeZoneDetection: Device has no time zone set so might set the"
                    + " device to the best available suggestion."
                    + " bestSuggestion=" + bestSuggestion
                    + ", timeZoneDetectionEnabled=" + timeZoneDetectionEnabled);

            mCurrentSuggestion = bestSuggestion;
            if (timeZoneDetectionEnabled) {
                setDeviceTimeZone(bestSuggestion.suggestion);
            }
            return;
        }

        boolean suggestionGoodEnough = bestSuggestion.score >= SCORE_USAGE_THRESHOLD;
        if (!suggestionGoodEnough) {
            if (DBG) {
                Slog.d(LOG_TAG, "doTimeZoneDetection: Suggestion not good enough."
                        + " bestSuggestion=" + bestSuggestion);
            }
            mCurrentSuggestion = null;
            return;
        }

        // Paranoia: Every suggestion above the SCORE_USAGE_THRESHOLD should have a non-null time
        // zone ID.
        if (newZoneId == null) {
            Slog.w(LOG_TAG, "Empty zone suggestion scored higher than expected. This is an error:"
                    + " bestSuggestion=" + bestSuggestion);
            mCurrentSuggestion = null;
            return;
        }

        // There is a good suggestion. Store the suggestion and set the device time zone if
        // settings allow.
        mCurrentSuggestion = bestSuggestion;

        // Only set the device time zone if time zone detection is enabled.
        if (!timeZoneDetectionEnabled) {
            if (DBG) {
                Slog.d(LOG_TAG, "doTimeZoneDetection: Not setting the time zone because time zone"
                        + " detection is disabled."
                        + " bestSuggestion=" + bestSuggestion);
            }
            return;
        }
        PhoneTimeZoneSuggestion suggestion = bestSuggestion.suggestion;
        setDeviceTimeZone(suggestion);
    }

    private void setDeviceTimeZone(@NonNull PhoneTimeZoneSuggestion suggestion) {
        String currentZoneId = mCallback.getDeviceTimeZone();
        String newZoneId = suggestion.getZoneId();

        // Paranoia: This should never happen.
        if (newZoneId == null) {
            Slog.w(LOG_TAG, "setDeviceTimeZone: Suggested zone is null."
                    + " timeZoneSuggestion=" + suggestion);
            return;
        }

        // Avoid unnecessary changes / intents.
        if (newZoneId.equals(currentZoneId)) {
            // No need to set the device time zone - the setting is already what we would be
            // suggesting.
            if (DBG) {
                Slog.d(LOG_TAG, "setDeviceTimeZone: No need to change the time zone;"
                        + " device is already set to the suggested zone."
                        + " timeZoneSuggestion=" + suggestion);
            }
            return;
        }

        String msg = "Changing device time zone. currentZoneId=" + currentZoneId
                + ", timeZoneSuggestion=" + suggestion;
        if (DBG) {
            Slog.d(LOG_TAG, msg);
        }
        mTimeZoneChangesLog.log(msg);
        mCallback.setDeviceTimeZone(newZoneId);
    }

    @GuardedBy("this")
    @Nullable
    private QualifiedPhoneTimeZoneSuggestion findBestSuggestion() {
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
     * Returns the current best suggestion. Not intended for general use: it is used during tests
     * to check service behavior.
     */
    @VisibleForTesting
    @Nullable
    public synchronized QualifiedPhoneTimeZoneSuggestion findBestSuggestionForTests() {
        return findBestSuggestion();
    }

    /**
     * Called when the has been a change to the automatic time zone detection setting.
     */
    @VisibleForTesting
    public synchronized void handleTimeZoneDetectionChange() {
        if (DBG) {
            Slog.d(LOG_TAG, "handleTimeZoneDetectionChange() called");
        }
        if (mCallback.isTimeZoneDetectionEnabled()) {
            // When the user enabled time zone detection, run the time zone detection and change the
            // device time zone if possible.
            doTimeZoneDetection();
        }
    }

    /**
     * Dumps any logs held to the supplied writer.
     */
    public synchronized void dumpLogs(IndentingPrintWriter ipw) {
        ipw.println("TimeZoneDetectorStrategy:");

        ipw.increaseIndent(); // level 1

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
    }

    /**
     * Dumps internal state such as field values.
     */
    public synchronized void dumpState(PrintWriter pw) {
        pw.println("mCurrentSuggestion=" + mCurrentSuggestion);
        pw.println("mCallback.isTimeZoneDetectionEnabled()="
                + mCallback.isTimeZoneDetectionEnabled());
        pw.println("mCallback.isDeviceTimeZoneInitialized()="
                + mCallback.isDeviceTimeZoneInitialized());
        pw.println("mCallback.getDeviceTimeZone()="
                + mCallback.getDeviceTimeZone());
        pw.flush();
    }

    /**
     * A method used to inspect service state during tests. Not intended for general use.
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
