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

import static android.app.time.Capabilities.CAPABILITY_POSSESSED;
import static android.app.timezonedetector.TelephonyTimeZoneSuggestion.MATCH_TYPE_EMULATOR_ZONE_ID;
import static android.app.timezonedetector.TelephonyTimeZoneSuggestion.MATCH_TYPE_TEST_NETWORK_OFFSET_ONLY;
import static android.app.timezonedetector.TelephonyTimeZoneSuggestion.QUALITY_MULTIPLE_ZONES_WITH_DIFFERENT_OFFSETS;
import static android.app.timezonedetector.TelephonyTimeZoneSuggestion.QUALITY_MULTIPLE_ZONES_WITH_SAME_OFFSET;
import static android.app.timezonedetector.TelephonyTimeZoneSuggestion.QUALITY_SINGLE_ZONE;

import android.annotation.ElapsedRealtimeLong;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.UserIdInt;
import android.app.time.TimeZoneCapabilities;
import android.app.time.TimeZoneCapabilitiesAndConfig;
import android.app.timezonedetector.ManualTimeZoneSuggestion;
import android.app.timezonedetector.TelephonyTimeZoneSuggestion;
import android.content.Context;
import android.os.Handler;
import android.os.TimestampedValue;
import android.util.IndentingPrintWriter;
import android.util.LocalLog;
import android.util.Slog;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.annotations.VisibleForTesting;

import java.time.Duration;
import java.util.List;
import java.util.Objects;

/**
 * The real implementation of {@link TimeZoneDetectorStrategy}.
 *
 * <p>Most public methods are marked synchronized to ensure thread safety around internal state.
 */
public final class TimeZoneDetectorStrategyImpl implements TimeZoneDetectorStrategy {

    /**
     * Used by {@link TimeZoneDetectorStrategyImpl} to interact with device configuration / settings
     * / system properties. It can be faked for testing.
     *
     * <p>Note: Because the settings / system properties-derived values can currently be modified
     * independently and from different threads (and processes!), their use is prone to race
     * conditions.
     */
    @VisibleForTesting
    public interface Environment {

        /**
         * Sets a {@link ConfigurationChangeListener} that will be invoked when there are any
         * changes that could affect the content of {@link ConfigurationInternal}.
         * This is invoked during system server setup.
         */
        void setConfigurationInternalChangeListener(@NonNull ConfigurationChangeListener listener);

        /** Returns the {@link ConfigurationInternal} for the current user. */
        @NonNull ConfigurationInternal getCurrentUserConfigurationInternal();

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

        /**
         * Returns the time according to the elapsed realtime clock, the same as {@link
         * android.os.SystemClock#elapsedRealtime()}.
         */
        @ElapsedRealtimeLong
        long elapsedRealtimeMillis();
    }

    private static final String LOG_TAG = TimeZoneDetectorService.TAG;
    private static final boolean DBG = TimeZoneDetectorService.DBG;

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
    private final Environment mEnvironment;

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
    private final ArrayMapWithHistory<Integer, QualifiedTelephonyTimeZoneSuggestion>
            mTelephonySuggestionsBySlotIndex =
            new ArrayMapWithHistory<>(KEEP_SUGGESTION_HISTORY_SIZE);

    /**
     * The latest geolocation suggestion received. If the user disabled geolocation time zone
     * detection then the latest suggestion is cleared.
     */
    @GuardedBy("this")
    private final ReferenceWithHistory<GeolocationTimeZoneSuggestion> mLatestGeoLocationSuggestion =
            new ReferenceWithHistory<>(KEEP_SUGGESTION_HISTORY_SIZE);

    /**
     * The latest manual suggestion received.
     */
    @GuardedBy("this")
    private final ReferenceWithHistory<ManualTimeZoneSuggestion> mLatestManualSuggestion =
            new ReferenceWithHistory<>(KEEP_SUGGESTION_HISTORY_SIZE);

    @GuardedBy("this")
    @NonNull
    private ConfigurationInternal mCurrentConfigurationInternal;

    /**
     * Whether telephony time zone detection fallback is currently enabled (when device config also
     * allows).
     *
     * <p>This field is only actually used when telephony time zone fallback is supported, but the
     * value is maintained even when it isn't supported as it can be turned on at any time via
     * server flags. The reference time is the elapsed realtime when the mode last changed to help
     * ordering between fallback mode switches and suggestions.
     *
     * <p>See {@link TimeZoneDetectorStrategy} for more information.
     */
    @GuardedBy("this")
    @NonNull
    private TimestampedValue<Boolean> mTelephonyTimeZoneFallbackEnabled;

    /**
     * Creates a new instance of {@link TimeZoneDetectorStrategyImpl}.
     */
    public static TimeZoneDetectorStrategyImpl create(
            @NonNull Context context, @NonNull Handler handler,
            @NonNull ServiceConfigAccessor serviceConfigAccessor) {

        Environment environment = new EnvironmentImpl(context, handler, serviceConfigAccessor);
        return new TimeZoneDetectorStrategyImpl(environment);
    }

    @VisibleForTesting
    public TimeZoneDetectorStrategyImpl(@NonNull Environment environment) {
        mEnvironment = Objects.requireNonNull(environment);

        // Start with telephony fallback enabled.
        mTelephonyTimeZoneFallbackEnabled =
                new TimestampedValue<>(mEnvironment.elapsedRealtimeMillis(), true);

        synchronized (this) {
            mEnvironment.setConfigurationInternalChangeListener(
                    this::handleConfigurationInternalChanged);
            mCurrentConfigurationInternal = mEnvironment.getCurrentUserConfigurationInternal();
        }
    }

    @Override
    public synchronized void suggestGeolocationTimeZone(
            @NonNull GeolocationTimeZoneSuggestion suggestion) {

        ConfigurationInternal currentUserConfig = mCurrentConfigurationInternal;
        if (DBG) {
            Slog.d(LOG_TAG, "Geolocation suggestion received."
                    + " currentUserConfig=" + currentUserConfig
                    + " newSuggestion=" + suggestion);
        }
        Objects.requireNonNull(suggestion);

        // Geolocation suggestions may be stored but not used during time zone detection if the
        // configuration doesn't have geo time zone detection enabled. The caller is expected to
        // withdraw a previous suggestion (i.e. submit an "uncertain" suggestion, when geo time zone
        // detection is disabled.

        // The suggestion's "effective from" time is ignored: we currently assume suggestions
        // are made in a sensible order and the most recent is always the best one to use.
        mLatestGeoLocationSuggestion.set(suggestion);

        // Update the mTelephonyTimeZoneFallbackEnabled state if needed: a certain suggestion
        // will usually disable telephony fallback mode if it is currently enabled.
        disableTelephonyFallbackIfNeeded();

        // Now perform auto time zone detection. The new suggestion may be used to modify the
        // time zone setting.
        String reason = "New geolocation time zone suggested. suggestion=" + suggestion;
        doAutoTimeZoneDetection(currentUserConfig, reason);
    }

    @Override
    public synchronized boolean suggestManualTimeZone(
            @UserIdInt int userId, @NonNull ManualTimeZoneSuggestion suggestion) {

        ConfigurationInternal currentUserConfig = mCurrentConfigurationInternal;
        if (currentUserConfig.getUserId() != userId) {
            Slog.w(LOG_TAG, "Manual suggestion received but user != current user, userId=" + userId
                    + " suggestion=" + suggestion);

            // Only listen to changes from the current user.
            return false;
        }

        Objects.requireNonNull(suggestion);

        String timeZoneId = suggestion.getZoneId();
        String cause = "Manual time suggestion received: suggestion=" + suggestion;

        TimeZoneCapabilitiesAndConfig capabilitiesAndConfig =
                currentUserConfig.createCapabilitiesAndConfig();
        TimeZoneCapabilities capabilities = capabilitiesAndConfig.getCapabilities();
        if (capabilities.getSuggestManualTimeZoneCapability() != CAPABILITY_POSSESSED) {
            Slog.i(LOG_TAG, "User does not have the capability needed to set the time zone manually"
                    + ", capabilities=" + capabilities
                    + ", timeZoneId=" + timeZoneId
                    + ", cause=" + cause);
            return false;
        }

        // Record the manual suggestion for debugging / metrics (but only if manual detection is
        // currently enabled).
        // Note: This is not used to set the device back to a previous manual suggestion if the user
        // later disables automatic time zone detection.
        mLatestManualSuggestion.set(suggestion);

        setDeviceTimeZoneIfRequired(timeZoneId, cause);
        return true;
    }

    @Override
    public synchronized void suggestTelephonyTimeZone(
            @NonNull TelephonyTimeZoneSuggestion suggestion) {

        ConfigurationInternal currentUserConfig = mCurrentConfigurationInternal;
        if (DBG) {
            Slog.d(LOG_TAG, "Telephony suggestion received. currentUserConfig=" + currentUserConfig
                    + " newSuggestion=" + suggestion);
        }
        Objects.requireNonNull(suggestion);

        // Score the suggestion.
        int score = scoreTelephonySuggestion(suggestion);
        QualifiedTelephonyTimeZoneSuggestion scoredSuggestion =
                new QualifiedTelephonyTimeZoneSuggestion(suggestion, score);

        // Store the suggestion against the correct slotIndex.
        mTelephonySuggestionsBySlotIndex.put(suggestion.getSlotIndex(), scoredSuggestion);

        // Now perform auto time zone detection: the new suggestion might be used to modify the
        // time zone setting.
        String reason = "New telephony time zone suggested. suggestion=" + suggestion;
        doAutoTimeZoneDetection(currentUserConfig, reason);
    }

    @Override
    public synchronized void enableTelephonyTimeZoneFallback() {
        // Only do any work if fallback is currently not enabled.
        if (!mTelephonyTimeZoneFallbackEnabled.getValue()) {
            ConfigurationInternal currentUserConfig = mCurrentConfigurationInternal;
            final boolean fallbackEnabled = true;
            mTelephonyTimeZoneFallbackEnabled = new TimestampedValue<>(
                    mEnvironment.elapsedRealtimeMillis(), fallbackEnabled);

            String logMsg = "enableTelephonyTimeZoneFallbackMode"
                    + ": currentUserConfig=" + currentUserConfig
                    + ", mTelephonyTimeZoneFallbackEnabled="
                    + mTelephonyTimeZoneFallbackEnabled;
            logTimeZoneDetectorChange(logMsg);

            // mTelephonyTimeZoneFallbackEnabled and mLatestGeoLocationSuggestion interact.
            // If there is currently a certain geolocation suggestion, then the telephony fallback
            // value needs to be considered after changing it.
            // With the way that the mTelephonyTimeZoneFallbackEnabled time is currently chosen
            // above, and the fact that geolocation suggestions should never have a time in the
            // future, the following call will be a no-op, and telephony fallback will remain
            // enabled. This comment / call is left as a reminder that it is possible for there to
            // be a current, "certain" geolocation suggestion when this signal arrives and it is
            // intentional that fallback stays enabled in this case. The choice to do this
            // is mostly for symmetry WRT the case where fallback is enabled and an old "certain"
            // geolocation is received; that would also leave telephony fallback enabled.
            // This choice means that telephony fallback will remain enabled until a new "certain"
            // geolocation suggestion is received. If, instead, the next geolocation is "uncertain",
            // then telephony fallback will occur.
            disableTelephonyFallbackIfNeeded();

            if (currentUserConfig.isTelephonyFallbackSupported()) {
                String reason = "enableTelephonyTimeZoneFallbackMode";
                doAutoTimeZoneDetection(currentUserConfig, reason);
            }
        }
    }

    @Override
    @NonNull
    public synchronized MetricsTimeZoneDetectorState generateMetricsState() {
        // Just capture one telephony suggestion: the one that would be used right now if telephony
        // detection is in use.
        QualifiedTelephonyTimeZoneSuggestion bestQualifiedTelephonySuggestion =
                findBestTelephonySuggestion();
        TelephonyTimeZoneSuggestion telephonySuggestion =
                bestQualifiedTelephonySuggestion == null
                        ? null : bestQualifiedTelephonySuggestion.suggestion;
        // A new generator is created each time: we don't want / require consistency.
        OrdinalGenerator<String> tzIdOrdinalGenerator =
                new OrdinalGenerator<>(new TimeZoneCanonicalizer());
        return MetricsTimeZoneDetectorState.create(
                tzIdOrdinalGenerator,
                mCurrentConfigurationInternal,
                mEnvironment.getDeviceTimeZone(),
                getLatestManualSuggestion(),
                telephonySuggestion,
                getLatestGeolocationSuggestion());
    }

    @Override
    public boolean isTelephonyTimeZoneDetectionSupported() {
        synchronized (this) {
            return mCurrentConfigurationInternal.isTelephonyDetectionSupported();
        }
    }

    @Override
    public boolean isGeoTimeZoneDetectionSupported() {
        synchronized (this) {
            return mCurrentConfigurationInternal.isGeoDetectionSupported();
        }
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
     * Performs automatic time zone detection.
     */
    @GuardedBy("this")
    private void doAutoTimeZoneDetection(
            @NonNull ConfigurationInternal currentUserConfig, @NonNull String detectionReason) {
        // Use the correct algorithm based on the user's current configuration. If it changes, then
        // detection will be re-run.
        switch (currentUserConfig.getDetectionMode()) {
            case ConfigurationInternal.DETECTION_MODE_MANUAL:
                // No work to do.
                break;
            case ConfigurationInternal.DETECTION_MODE_GEO: {
                boolean isGeoDetectionCertain = doGeolocationTimeZoneDetection(detectionReason);

                // When geolocation detection is uncertain of the time zone, telephony detection
                // can be used if telephony fallback is enabled and supported.
                if (!isGeoDetectionCertain
                        && mTelephonyTimeZoneFallbackEnabled.getValue()
                        && currentUserConfig.isTelephonyFallbackSupported()) {

                    // This "only look at telephony if geolocation is uncertain" approach is
                    // deliberate to try to keep the logic simple and keep telephony and geolocation
                    // detection decoupled: when geolocation detection is in use, it is fully
                    // trusted and the most recent "certain" geolocation suggestion available will
                    // be used, even if the information it is based on is quite old.
                    // There could be newer telephony suggestions available, but telephony
                    // suggestions tend not to be withdrawn when they should be, and are based on
                    // combining information like MCC and NITZ signals, which could have been
                    // received at different times; thus it is hard to say what time the suggestion
                    // is actually "for" and reason clearly about ordering between telephony and
                    // geolocation suggestions.
                    //
                    // This approach is reliant on the location_time_zone_manager (and the location
                    // time zone providers it manages) correctly sending "uncertain" suggestions
                    // when the current location is unknown so that telephony fallback will actually
                    // be used.
                    doTelephonyTimeZoneDetection(detectionReason + ", telephony fallback mode");
                }
                break;
            }
            case ConfigurationInternal.DETECTION_MODE_TELEPHONY:
                doTelephonyTimeZoneDetection(detectionReason);
                break;
            default:
                Slog.wtf(LOG_TAG, "Unknown detection mode: "
                        + currentUserConfig.getDetectionMode());
        }
    }

    /**
     * Detects the time zone using the latest available geolocation time zone suggestion, if one is
     * available. The outcome can be that this strategy becomes / remains un-opinionated and nothing
     * is set.
     *
     * @return true if geolocation time zone detection was certain of the time zone, false if it is
     * uncertain
     */
    @GuardedBy("this")
    private boolean doGeolocationTimeZoneDetection(@NonNull String detectionReason) {
        GeolocationTimeZoneSuggestion latestGeolocationSuggestion =
                mLatestGeoLocationSuggestion.get();
        if (latestGeolocationSuggestion == null) {
            return false;
        }

        List<String> zoneIds = latestGeolocationSuggestion.getZoneIds();
        if (zoneIds == null) {
            // This means the originator of the suggestion is uncertain about the time zone. The
            // existing time zone setting must be left as it is but detection can go on looking for
            // a different answer elsewhere.
            return false;
        } else if (zoneIds.isEmpty()) {
            // This means the originator is certain there is no time zone. The existing time zone
            // setting must be left as it is and detection must not go looking for a different
            // answer elsewhere.
            return true;
        }

        // GeolocationTimeZoneSuggestion has no measure of quality. We assume all suggestions are
        // reliable.
        String zoneId;

        // Introduce bias towards the device's current zone when there are multiple zone suggested.
        String deviceTimeZone = mEnvironment.getDeviceTimeZone();
        if (zoneIds.contains(deviceTimeZone)) {
            if (DBG) {
                Slog.d(LOG_TAG,
                        "Geo tz suggestion contains current device time zone. Applying bias.");
            }
            zoneId = deviceTimeZone;
        } else {
            zoneId = zoneIds.get(0);
        }
        setDeviceTimeZoneIfRequired(zoneId, detectionReason);
        return true;
    }

    /**
     * Sets the mTelephonyTimeZoneFallbackEnabled state to {@code false} if the latest geo
     * suggestion is a "certain" suggestion that comes after the time when telephony fallback was
     * enabled.
     */
    @GuardedBy("this")
    private void disableTelephonyFallbackIfNeeded() {
        GeolocationTimeZoneSuggestion suggestion = mLatestGeoLocationSuggestion.get();
        boolean isLatestSuggestionCertain = suggestion != null && suggestion.getZoneIds() != null;
        if (isLatestSuggestionCertain && mTelephonyTimeZoneFallbackEnabled.getValue()) {
            // This transition ONLY changes mTelephonyTimeZoneFallbackEnabled from
            // true -> false. See mTelephonyTimeZoneFallbackEnabled javadocs for details.

            // Telephony fallback will be disabled after a "certain" suggestion is processed
            // if and only if the location information it is based on is from after telephony
            // fallback was enabled.
            boolean latestSuggestionIsNewerThanFallbackEnabled =
                    suggestion.getEffectiveFromElapsedMillis()
                            > mTelephonyTimeZoneFallbackEnabled.getReferenceTimeMillis();
            if (latestSuggestionIsNewerThanFallbackEnabled) {
                final boolean fallbackEnabled = false;
                mTelephonyTimeZoneFallbackEnabled = new TimestampedValue<>(
                        mEnvironment.elapsedRealtimeMillis(), fallbackEnabled);

                String logMsg = "disableTelephonyFallbackIfNeeded"
                        + ": mTelephonyTimeZoneFallbackEnabled="
                        + mTelephonyTimeZoneFallbackEnabled;
                logTimeZoneDetectorChange(logMsg);
            }
        }
    }

    private void logTimeZoneDetectorChange(@NonNull String logMsg) {
        if (DBG) {
            Slog.d(LOG_TAG, logMsg);
        }
        mTimeZoneChangesLog.log(logMsg);
    }

    /**
     * Detects the time zone using the latest available telephony time zone suggestions.
     * Finds the best available time zone suggestion from all slotIndexes. If it is high-enough
     * quality and automatic time zone detection is enabled then it will be set on the device. The
     * outcome can be that this strategy becomes / remains un-opinionated and nothing is set.
     */
    @GuardedBy("this")
    private void doTelephonyTimeZoneDetection(@NonNull String detectionReason) {
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
        String zoneId = bestTelephonySuggestion.suggestion.getZoneId();
        if (zoneId == null) {
            Slog.w(LOG_TAG, "Empty zone suggestion scored higher than expected. This is an error:"
                    + " bestTelephonySuggestion=" + bestTelephonySuggestion
                    + " detectionReason=" + detectionReason);
            return;
        }

        String cause = "Found good suggestion."
                + ", bestTelephonySuggestion=" + bestTelephonySuggestion
                + ", detectionReason=" + detectionReason;
        setDeviceTimeZoneIfRequired(zoneId, cause);
    }

    @GuardedBy("this")
    private void setDeviceTimeZoneIfRequired(@NonNull String newZoneId, @NonNull String cause) {
        String currentZoneId = mEnvironment.getDeviceTimeZone();

        // Avoid unnecessary changes / intents.
        if (newZoneId.equals(currentZoneId)) {
            // No need to set the device time zone - the setting is already what we would be
            // suggesting.
            if (DBG) {
                Slog.d(LOG_TAG, "No need to change the time zone;"
                        + " device is already set to newZoneId."
                        + ", newZoneId=" + newZoneId
                        + ", cause=" + cause);
            }
            return;
        }

        mEnvironment.setDeviceTimeZone(newZoneId);
        String logMsg = "Set device time zone."
                + ", currentZoneId=" + currentZoneId
                + ", newZoneId=" + newZoneId
                + ", cause=" + cause;
        logTimeZoneDetectorChange(logMsg);
    }

    @GuardedBy("this")
    @Nullable
    private QualifiedTelephonyTimeZoneSuggestion findBestTelephonySuggestion() {
        QualifiedTelephonyTimeZoneSuggestion bestSuggestion = null;

        // Iterate over the latest QualifiedTelephonyTimeZoneSuggestion objects received for each
        // slotIndex and find the best. Note that we deliberately do not look at age: the caller can
        // rate-limit so age is not a strong indicator of confidence. Instead, the callers are
        // expected to withdraw suggestions they no longer have confidence in.
        for (int i = 0; i < mTelephonySuggestionsBySlotIndex.size(); i++) {
            QualifiedTelephonyTimeZoneSuggestion candidateSuggestion =
                    mTelephonySuggestionsBySlotIndex.valueAt(i);
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

    private synchronized void handleConfigurationInternalChanged() {
        ConfigurationInternal currentUserConfig =
                mEnvironment.getCurrentUserConfigurationInternal();
        String logMsg = "handleConfigurationInternalChanged:"
                + " oldConfiguration=" + mCurrentConfigurationInternal
                + ", newConfiguration=" + currentUserConfig;
        logTimeZoneDetectorChange(logMsg);
        mCurrentConfigurationInternal = currentUserConfig;

        // The configuration change may have changed available suggestions or the way suggestions
        // are used, so re-run detection.
        doAutoTimeZoneDetection(currentUserConfig, logMsg);
    }

    /**
     * Dumps internal state such as field values.
     */
    @Override
    public synchronized void dump(@NonNull IndentingPrintWriter ipw, @Nullable String[] args) {
        ipw.println("TimeZoneDetectorStrategy:");

        ipw.increaseIndent(); // level 1
        ipw.println("mCurrentConfigurationInternal=" + mCurrentConfigurationInternal);
        ipw.println("[Capabilities=" + mCurrentConfigurationInternal.createCapabilitiesAndConfig()
                + "]");
        ipw.println("mEnvironment.isDeviceTimeZoneInitialized()="
                + mEnvironment.isDeviceTimeZoneInitialized());
        ipw.println("mEnvironment.getDeviceTimeZone()=" + mEnvironment.getDeviceTimeZone());

        ipw.println("Misc state:");
        ipw.increaseIndent(); // level 2
        ipw.println("mTelephonyTimeZoneFallbackEnabled="
                + formatDebugString(mTelephonyTimeZoneFallbackEnabled));
        ipw.decreaseIndent(); // level 2

        ipw.println("Time zone change log:");
        ipw.increaseIndent(); // level 2
        mTimeZoneChangesLog.dump(ipw);
        ipw.decreaseIndent(); // level 2

        ipw.println("Manual suggestion history:");
        ipw.increaseIndent(); // level 2
        mLatestManualSuggestion.dump(ipw);
        ipw.decreaseIndent(); // level 2

        ipw.println("Geolocation suggestion history:");
        ipw.increaseIndent(); // level 2
        mLatestGeoLocationSuggestion.dump(ipw);
        ipw.decreaseIndent(); // level 2

        ipw.println("Telephony suggestion history:");
        ipw.increaseIndent(); // level 2
        mTelephonySuggestionsBySlotIndex.dump(ipw);
        ipw.decreaseIndent(); // level 2
        ipw.decreaseIndent(); // level 1
    }

    /**
     * A method used to inspect strategy state during tests. Not intended for general use.
     */
    @VisibleForTesting
    public synchronized ManualTimeZoneSuggestion getLatestManualSuggestion() {
        return mLatestManualSuggestion.get();
    }

    /**
     * A method used to inspect strategy state during tests. Not intended for general use.
     */
    @VisibleForTesting
    public synchronized QualifiedTelephonyTimeZoneSuggestion getLatestTelephonySuggestion(
            int slotIndex) {
        return mTelephonySuggestionsBySlotIndex.get(slotIndex);
    }

    /**
     * A method used to inspect strategy state during tests. Not intended for general use.
     */
    @VisibleForTesting
    public synchronized GeolocationTimeZoneSuggestion getLatestGeolocationSuggestion() {
        return mLatestGeoLocationSuggestion.get();
    }

    @VisibleForTesting
    public synchronized boolean isTelephonyFallbackEnabledForTests() {
        return mTelephonyTimeZoneFallbackEnabled.getValue();
    }

    /**
     * A {@link TelephonyTimeZoneSuggestion} with additional qualifying metadata.
     */
    @VisibleForTesting
    public static final class QualifiedTelephonyTimeZoneSuggestion {

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

    private static String formatDebugString(TimestampedValue<?> value) {
        return value.getValue() + " @ " + Duration.ofMillis(value.getReferenceTimeMillis());
    }
}
