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

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.UserIdInt;
import android.app.time.TimeZoneCapabilities;
import android.app.time.TimeZoneCapabilitiesAndConfig;
import android.app.time.TimeZoneConfiguration;
import android.app.timezonedetector.ManualTimeZoneSuggestion;
import android.app.timezonedetector.TelephonyTimeZoneSuggestion;
import android.content.Context;
import android.os.Handler;
import android.util.IndentingPrintWriter;
import android.util.LocalLog;
import android.util.Slog;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.annotations.VisibleForTesting;

import java.util.ArrayList;
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
         * changes that could affect time zone detection. This is invoked during system server
         * setup.
         */
        void setConfigChangeListener(@NonNull ConfigurationChangeListener listener);

        /** Returns the current user at the instant it is called. */
        @UserIdInt int getCurrentUserId();

        /** Returns the {@link ConfigurationInternal} for the specified user. */
        ConfigurationInternal getConfigurationInternal(@UserIdInt int userId);

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
         * Stores the configuration properties contained in {@code newConfiguration}.
         * All checks about user capabilities must be done by the caller and
         * {@link TimeZoneConfiguration#isComplete()} must be {@code true}.
         */
        void storeConfiguration(@UserIdInt int userId, TimeZoneConfiguration newConfiguration);
    }

    private static final String LOG_TAG = TimeZoneDetectorService.TAG;
    private static final boolean DBG = false;

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

    @GuardedBy("this")
    @NonNull
    private List<ConfigurationChangeListener> mConfigChangeListeners = new ArrayList<>();

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
            mTelephonySuggestionsBySlotIndex =
            new ArrayMapWithHistory<>(KEEP_SUGGESTION_HISTORY_SIZE);

    /**
     * The latest geolocation suggestion received. If the user disabled geolocation time zone
     * detection then the latest suggestion is cleared.
     */
    @GuardedBy("this")
    private ReferenceWithHistory<GeolocationTimeZoneSuggestion> mLatestGeoLocationSuggestion =
            new ReferenceWithHistory<>(KEEP_SUGGESTION_HISTORY_SIZE);

    /**
     * The latest manual suggestion received.
     */
    @GuardedBy("this")
    private ReferenceWithHistory<ManualTimeZoneSuggestion> mLatestManualSuggestion =
            new ReferenceWithHistory<>(KEEP_SUGGESTION_HISTORY_SIZE);

    @GuardedBy("this")
    private final List<Dumpable> mDumpables = new ArrayList<>();

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
        mEnvironment.setConfigChangeListener(this::handleConfigChanged);
    }

    /**
     * Adds a listener that allows the strategy to communicate with the surrounding service /
     * internal. This must be called before the instance is used.
     */
    @Override
    public synchronized void addConfigChangeListener(
            @NonNull ConfigurationChangeListener listener) {
        Objects.requireNonNull(listener);
        mConfigChangeListeners.add(listener);
    }

    @Override
    @NonNull
    public ConfigurationInternal getConfigurationInternal(@UserIdInt int userId) {
        return mEnvironment.getConfigurationInternal(userId);
    }

    @Override
    @NonNull
    public synchronized ConfigurationInternal getCurrentUserConfigurationInternal() {
        int currentUserId = mEnvironment.getCurrentUserId();
        return getConfigurationInternal(currentUserId);
    }

    @Override
    public synchronized boolean updateConfiguration(@UserIdInt int userId,
            @NonNull TimeZoneConfiguration requestedConfiguration) {
        Objects.requireNonNull(requestedConfiguration);

        TimeZoneCapabilitiesAndConfig capabilitiesAndConfig =
                getConfigurationInternal(userId).createCapabilitiesAndConfig();
        TimeZoneCapabilities capabilities = capabilitiesAndConfig.getCapabilities();
        TimeZoneConfiguration oldConfiguration = capabilitiesAndConfig.getConfiguration();

        final TimeZoneConfiguration newConfiguration =
                capabilities.tryApplyConfigChanges(oldConfiguration, requestedConfiguration);
        if (newConfiguration == null) {
            // The changes could not be made because the user's capabilities do not allow it.
            return false;
        }

        // Store the configuration / notify as needed. This will cause the mEnvironment to invoke
        // handleConfigChanged() asynchronously.
        mEnvironment.storeConfiguration(userId, newConfiguration);

        String logMsg = "Configuration changed:"
                + " oldConfiguration=" + oldConfiguration
                + ", newConfiguration=" + newConfiguration;
        mTimeZoneChangesLog.log(logMsg);
        if (DBG) {
            Slog.d(LOG_TAG, logMsg);
        }
        return true;
    }

    @Override
    public synchronized void suggestGeolocationTimeZone(
            @NonNull GeolocationTimeZoneSuggestion suggestion) {

        int currentUserId = mEnvironment.getCurrentUserId();
        ConfigurationInternal currentUserConfig =
                mEnvironment.getConfigurationInternal(currentUserId);
        if (DBG) {
            Slog.d(LOG_TAG, "Geolocation suggestion received."
                    + " currentUserConfig=" + currentUserConfig
                    + " newSuggestion=" + suggestion);
        }
        Objects.requireNonNull(suggestion);

        if (currentUserConfig.getGeoDetectionEnabledBehavior()) {
            // Only store a geolocation suggestion if geolocation detection is currently enabled.
            // See also clearGeolocationSuggestionIfNeeded().
            mLatestGeoLocationSuggestion.set(suggestion);

            // Now perform auto time zone detection. The new suggestion may be used to modify the
            // time zone setting.
            String reason = "New geolocation time zone suggested. suggestion=" + suggestion;
            doAutoTimeZoneDetection(currentUserConfig, reason);
        }
    }

    @Override
    public synchronized boolean suggestManualTimeZone(
            @UserIdInt int userId, @NonNull ManualTimeZoneSuggestion suggestion) {

        int currentUserId = mEnvironment.getCurrentUserId();
        if (userId != currentUserId) {
            Slog.w(LOG_TAG, "Manual suggestion received but user != current user, userId=" + userId
                    + " suggestion=" + suggestion);

            // Only listen to changes from the current user.
            return false;
        }

        Objects.requireNonNull(suggestion);

        String timeZoneId = suggestion.getZoneId();
        String cause = "Manual time suggestion received: suggestion=" + suggestion;

        TimeZoneCapabilitiesAndConfig capabilitiesAndConfig =
                getConfigurationInternal(userId).createCapabilitiesAndConfig();
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

        int currentUserId = mEnvironment.getCurrentUserId();
        ConfigurationInternal currentUserConfig =
                mEnvironment.getConfigurationInternal(currentUserId);
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

        // Now perform auto time zone detection. The new suggestion may be used to modify the time
        // zone setting.
        if (!currentUserConfig.getGeoDetectionEnabledBehavior()) {
            String reason = "New telephony time zone suggested. suggestion=" + suggestion;
            doAutoTimeZoneDetection(currentUserConfig, reason);
        }
    }

    @Override
    @NonNull
    public synchronized MetricsTimeZoneDetectorState generateMetricsState() {
        int currentUserId = mEnvironment.getCurrentUserId();
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
                getConfigurationInternal(currentUserId),
                mEnvironment.getDeviceTimeZone(),
                getLatestManualSuggestion(),
                telephonySuggestion,
                getLatestGeolocationSuggestion());
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
        if (!currentUserConfig.getAutoDetectionEnabledBehavior()) {
            // Avoid doing unnecessary work.
            return;
        }

        // Use the right suggestions based on the current configuration.
        if (currentUserConfig.getGeoDetectionEnabledBehavior()) {
            doGeolocationTimeZoneDetection(detectionReason);
        } else  {
            doTelephonyTimeZoneDetection(detectionReason);
        }
    }

    /**
     * Detects the time zone using the latest available geolocation time zone suggestion, if one is
     * available. The outcome can be that this strategy becomes / remains un-opinionated and nothing
     * is set.
     */
    @GuardedBy("this")
    private void doGeolocationTimeZoneDetection(@NonNull String detectionReason) {
        GeolocationTimeZoneSuggestion latestGeolocationSuggestion =
                mLatestGeoLocationSuggestion.get();
        if (latestGeolocationSuggestion == null) {
            return;
        }

        List<String> zoneIds = latestGeolocationSuggestion.getZoneIds();
        if (zoneIds == null || zoneIds.isEmpty()) {
            // This means the client has become uncertain about the time zone or it is certain there
            // is no known zone. In either case we must leave the existing time zone setting as it
            // is.
            return;
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
        String msg = "Set device time zone."
                + ", currentZoneId=" + currentZoneId
                + ", newZoneId=" + newZoneId
                + ", cause=" + cause;
        if (DBG) {
            Slog.d(LOG_TAG, msg);
        }
        mTimeZoneChangesLog.log(msg);
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

    private synchronized void handleConfigChanged() {
        if (DBG) {
            Slog.d(LOG_TAG, "handleConfigChanged()");
        }

        clearGeolocationSuggestionIfNeeded();

        for (ConfigurationChangeListener listener : mConfigChangeListeners) {
            listener.onChange();
        }
    }

    @GuardedBy("this")
    private void clearGeolocationSuggestionIfNeeded() {
        // This method is called whenever the user changes or the config for any user changes. We
        // don't know what happened, so we capture the current user's config, check to see if we
        // need to clear state associated with a previous user, and rerun detection.
        int currentUserId = mEnvironment.getCurrentUserId();
        ConfigurationInternal currentUserConfig =
                mEnvironment.getConfigurationInternal(currentUserId);

        GeolocationTimeZoneSuggestion latestGeoLocationSuggestion =
                mLatestGeoLocationSuggestion.get();
        if (latestGeoLocationSuggestion != null
                && !currentUserConfig.getGeoDetectionEnabledBehavior()) {
            // The current user's config has geodetection disabled, so clear the latest suggestion.
            // This is done to ensure we only ever keep a geolocation suggestion if the user has
            // said it is ok to do so.
            mLatestGeoLocationSuggestion.set(null);
            mTimeZoneChangesLog.log(
                    "clearGeolocationSuggestionIfNeeded: Cleared latest Geolocation suggestion.");
        }

        doAutoTimeZoneDetection(currentUserConfig, "clearGeolocationSuggestionIfNeeded()");
    }

    @Override
    public synchronized void addDumpable(@NonNull Dumpable dumpable) {
        mDumpables.add(dumpable);
    }

    /**
     * Dumps internal state such as field values.
     */
    @Override
    public synchronized void dump(@NonNull IndentingPrintWriter ipw, @Nullable String[] args) {
        ipw.println("TimeZoneDetectorStrategy:");

        ipw.increaseIndent(); // level 1
        int currentUserId = mEnvironment.getCurrentUserId();
        ipw.println("mEnvironment.getCurrentUserId()=" + currentUserId);
        ConfigurationInternal configuration = mEnvironment.getConfigurationInternal(currentUserId);
        ipw.println("mEnvironment.getConfiguration(currentUserId)=" + configuration);
        ipw.println("[Capabilities=" + configuration.createCapabilitiesAndConfig() + "]");
        ipw.println("mEnvironment.isDeviceTimeZoneInitialized()="
                + mEnvironment.isDeviceTimeZoneInitialized());
        ipw.println("mEnvironment.getDeviceTimeZone()=" + mEnvironment.getDeviceTimeZone());

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

        for (Dumpable dumpable : mDumpables) {
            dumpable.dump(ipw, args);
        }
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
}
