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

import static com.android.server.SystemTimeZone.TIME_ZONE_CONFIDENCE_HIGH;
import static com.android.server.SystemTimeZone.TIME_ZONE_CONFIDENCE_LOW;

import android.annotation.ElapsedRealtimeLong;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.UserIdInt;
import android.app.time.DetectorStatusTypes;
import android.app.time.LocationTimeZoneAlgorithmStatus;
import android.app.time.TelephonyTimeZoneAlgorithmStatus;
import android.app.time.TimeZoneCapabilities;
import android.app.time.TimeZoneCapabilitiesAndConfig;
import android.app.time.TimeZoneConfiguration;
import android.app.time.TimeZoneDetectorStatus;
import android.app.time.TimeZoneState;
import android.app.timezonedetector.ManualTimeZoneSuggestion;
import android.app.timezonedetector.TelephonyTimeZoneSuggestion;
import android.os.Handler;
import android.os.TimestampedValue;
import android.util.IndentingPrintWriter;
import android.util.Slog;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.annotations.VisibleForTesting;
import com.android.server.SystemTimeZone.TimeZoneConfidence;
import com.android.server.timezonedetector.ConfigurationInternal.DetectionMode;

import java.io.PrintWriter;
import java.time.Duration;
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
     * Used by {@link TimeZoneDetectorStrategyImpl} to interact with device state besides that
     * available from {@link #mServiceConfigAccessor}. It can be faked for testing.
     */
    @VisibleForTesting
    public interface Environment {

        /**
         * Returns the device's currently configured time zone. May return an empty string.
         */
        @NonNull String getDeviceTimeZone();

        /**
         * Returns the confidence of the device's current time zone.
         */
        @TimeZoneConfidence int getDeviceTimeZoneConfidence();

        /**
         * Sets the device's time zone, associated confidence, and records a debug log entry.
         */
        void setDeviceTimeZoneAndConfidence(
                @NonNull String zoneId, @TimeZoneConfidence int confidence,
                @NonNull String logInfo);

        /**
         * Returns the time according to the elapsed realtime clock, the same as {@link
         * android.os.SystemClock#elapsedRealtime()}.
         */
        @ElapsedRealtimeLong
        long elapsedRealtimeMillis();

        /**
         * Adds a standalone entry to the time zone debug log.
         */
        void addDebugLogEntry(@NonNull String logMsg);

        /**
         * Dumps the time zone debug log to the supplied {@link PrintWriter}.
         */
        void dumpDebugLog(PrintWriter printWriter);

        /**
         * Requests that the supplied runnable be invoked asynchronously.
         */
        void runAsync(@NonNull Runnable runnable);
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
     * A mapping from slotIndex to a telephony time zone suggestion. We typically expect one or two
     * mappings: devices will have a small number of telephony devices and slotIndexes are assumed
     * to be stable.
     */
    @GuardedBy("this")
    private final ArrayMapWithHistory<Integer, QualifiedTelephonyTimeZoneSuggestion>
            mTelephonySuggestionsBySlotIndex =
            new ArrayMapWithHistory<>(KEEP_SUGGESTION_HISTORY_SIZE);

    /**
     * The latest location algorithm event received.
     */
    @GuardedBy("this")
    private final ReferenceWithHistory<LocationAlgorithmEvent> mLatestLocationAlgorithmEvent =
            new ReferenceWithHistory<>(KEEP_SUGGESTION_HISTORY_SIZE);

    /**
     * The latest manual suggestion received.
     */
    @GuardedBy("this")
    private final ReferenceWithHistory<ManualTimeZoneSuggestion> mLatestManualSuggestion =
            new ReferenceWithHistory<>(KEEP_SUGGESTION_HISTORY_SIZE);

    @NonNull
    private final ServiceConfigAccessor mServiceConfigAccessor;

    @GuardedBy("this")
    @NonNull private final List<StateChangeListener> mStateChangeListeners = new ArrayList<>();

    /**
     * A snapshot of the current detector status. A local copy is cached because it is relatively
     * heavyweight to obtain and is used more often than it is expected to change.
     */
    @GuardedBy("this")
    @NonNull
    private TimeZoneDetectorStatus mDetectorStatus;

    /**
     * A snapshot of the current user's {@link ConfigurationInternal}. A local copy is cached
     * because it is relatively heavyweight to obtain and is used more often than it is expected to
     * change. Because many operations are asynchronous, this value may be out of date but should
     * be "eventually consistent".
     */
    @GuardedBy("this")
    @NonNull
    private ConfigurationInternal mCurrentConfigurationInternal;

    /**
     * Whether telephony time zone detection fallback is currently enabled (when device config also
     * allows).
     *
     * <p>This field is only actually used when telephony time zone fallback is supported, but the
     * value is maintained even when it isn't supported as support can be turned on at any time via
     * server flags. The elapsed realtime when the mode last changed is used to help ordering
     * between fallback mode switches and suggestions.
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
            @NonNull Handler handler, @NonNull ServiceConfigAccessor serviceConfigAccessor) {

        Environment environment = new EnvironmentImpl(handler);
        return new TimeZoneDetectorStrategyImpl(serviceConfigAccessor, environment);
    }

    @VisibleForTesting
    public TimeZoneDetectorStrategyImpl(
            @NonNull ServiceConfigAccessor serviceConfigAccessor,
            @NonNull Environment environment) {
        mEnvironment = Objects.requireNonNull(environment);
        mServiceConfigAccessor = Objects.requireNonNull(serviceConfigAccessor);

        // Start with telephony fallback enabled.
        mTelephonyTimeZoneFallbackEnabled =
                new TimestampedValue<>(mEnvironment.elapsedRealtimeMillis(), true);

        synchronized (this) {
            // Listen for config and user changes and get an initial snapshot of configuration.
            StateChangeListener stateChangeListener = this::handleConfigurationInternalMaybeChanged;
            mServiceConfigAccessor.addConfigurationInternalChangeListener(stateChangeListener);

            // Initialize mCurrentConfigurationInternal and mDetectorStatus with their starting
            // values.
            updateCurrentConfigurationInternalIfRequired("TimeZoneDetectorStrategyImpl:");
        }
    }

    @Override
    public synchronized TimeZoneCapabilitiesAndConfig getCapabilitiesAndConfig(
            @UserIdInt int userId, boolean bypassUserPolicyChecks) {
        ConfigurationInternal configurationInternal;
        if (mCurrentConfigurationInternal.getUserId() == userId) {
            // Use the cached snapshot we have.
            configurationInternal = mCurrentConfigurationInternal;
        } else {
            // This is not a common case: It would be unusual to want the configuration for a user
            // other than the "current" user, but it is supported because it is trivial to do so.
            // Unlike the current user config, there's no cached copy to worry about so read it
            // directly from mServiceConfigAccessor.
            configurationInternal = mServiceConfigAccessor.getConfigurationInternal(userId);
        }
        return new TimeZoneCapabilitiesAndConfig(
                mDetectorStatus,
                configurationInternal.asCapabilities(bypassUserPolicyChecks),
                configurationInternal.asConfiguration());
    }

    @Override
    public synchronized boolean updateConfiguration(
            @UserIdInt int userId, @NonNull TimeZoneConfiguration configuration,
            boolean bypassUserPolicyChecks) {

        // Write-through
        boolean updateSuccessful = mServiceConfigAccessor.updateConfiguration(
                userId, configuration, bypassUserPolicyChecks);

        // The update above will trigger config update listeners asynchronously if they are needed,
        // but that could mean an immediate call to getCapabilitiesAndConfig() for the current user
        // wouldn't see the update. So, handle the cache update and notifications here. When the
        // async update listener triggers it will find everything already up to date and do nothing.
        if (updateSuccessful) {
            String logMsg = "updateConfiguration:"
                    + " userId=" + userId
                    + ", configuration=" + configuration
                    + ", bypassUserPolicyChecks=" + bypassUserPolicyChecks;
            updateCurrentConfigurationInternalIfRequired(logMsg);
        }
        return updateSuccessful;
    }

    @GuardedBy("this")
    private void updateCurrentConfigurationInternalIfRequired(@NonNull String logMsg) {
        ConfigurationInternal newCurrentConfigurationInternal =
                mServiceConfigAccessor.getCurrentUserConfigurationInternal();
        // mCurrentConfigurationInternal is null the first time this method is called.
        ConfigurationInternal oldCurrentConfigurationInternal = mCurrentConfigurationInternal;

        // If the configuration actually changed, update the cached copy synchronously and do
        // other necessary house-keeping / (async) listener notifications.
        if (!newCurrentConfigurationInternal.equals(oldCurrentConfigurationInternal)) {
            mCurrentConfigurationInternal = newCurrentConfigurationInternal;

            logMsg += " [oldConfiguration=" + oldCurrentConfigurationInternal
                    + ", newConfiguration=" + newCurrentConfigurationInternal
                    + "]";
            logTimeZoneDebugInfo(logMsg);

            // ConfigurationInternal changes can affect the detector's status.
            updateDetectorStatus();

            // The configuration and maybe the status changed so notify listeners.
            notifyStateChangeListenersAsynchronously();

            // The configuration change may have changed available suggestions or the way
            // suggestions are used, so re-run detection.
            doAutoTimeZoneDetection(mCurrentConfigurationInternal, logMsg);
        }
    }

    @GuardedBy("this")
    private void notifyStateChangeListenersAsynchronously() {
        for (StateChangeListener listener : mStateChangeListeners) {
            // This is queuing asynchronous notification, so no need to surrender the "this" lock.
            mEnvironment.runAsync(listener::onChange);
        }
    }

    @Override
    public synchronized void addChangeListener(StateChangeListener listener) {
        mStateChangeListeners.add(listener);
    }

    @Override
    public synchronized boolean confirmTimeZone(@NonNull String timeZoneId) {
        Objects.requireNonNull(timeZoneId);

        String currentTimeZoneId = mEnvironment.getDeviceTimeZone();
        if (!currentTimeZoneId.equals(timeZoneId)) {
            return false;
        }

        if (mEnvironment.getDeviceTimeZoneConfidence() < TIME_ZONE_CONFIDENCE_HIGH) {
            mEnvironment.setDeviceTimeZoneAndConfidence(currentTimeZoneId,
                    TIME_ZONE_CONFIDENCE_HIGH, "confirmTimeZone: timeZoneId=" + timeZoneId);
        }
        return true;
    }

    @Override
    public synchronized TimeZoneState getTimeZoneState() {
        boolean userShouldConfirmId =
                mEnvironment.getDeviceTimeZoneConfidence() < TIME_ZONE_CONFIDENCE_HIGH;
        return new TimeZoneState(mEnvironment.getDeviceTimeZone(), userShouldConfirmId);
    }

    @Override
    public void setTimeZoneState(@NonNull TimeZoneState timeZoneState) {
        Objects.requireNonNull(timeZoneState);

        @TimeZoneConfidence int confidence = timeZoneState.getUserShouldConfirmId()
                ? TIME_ZONE_CONFIDENCE_LOW : TIME_ZONE_CONFIDENCE_HIGH;
        mEnvironment.setDeviceTimeZoneAndConfidence(
                timeZoneState.getId(), confidence, "setTimeZoneState()");
    }

    @Override
    public synchronized void handleLocationAlgorithmEvent(@NonNull LocationAlgorithmEvent event) {
        ConfigurationInternal currentUserConfig = mCurrentConfigurationInternal;
        if (DBG) {
            Slog.d(LOG_TAG, "Location algorithm event received."
                    + " currentUserConfig=" + currentUserConfig
                    + " event=" + event);
        }
        Objects.requireNonNull(event);

        // Location algorithm events may be stored but not used during time zone detection if the
        // configuration doesn't have geo time zone detection enabled. The caller is expected to
        // withdraw a previous suggestion, i.e. submit an event containing an "uncertain"
        // suggestion, when geo time zone detection is disabled.

        // We currently assume events are made in a sensible order and the most recent is always the
        // best one to use.
        mLatestLocationAlgorithmEvent.set(event);

        // The latest location algorithm event can affect the cached detector status, so update it
        // and notify state change listeners as needed.
        boolean statusChanged = updateDetectorStatus();
        if (statusChanged) {
            notifyStateChangeListenersAsynchronously();
        }

        // Manage telephony fallback state.
        if (event.getAlgorithmStatus().couldEnableTelephonyFallback()) {
            // An event may trigger entry into telephony fallback mode if the status
            // indicates the location algorithm cannot work and is likely to stay not working.
            enableTelephonyTimeZoneFallback("handleLocationAlgorithmEvent(), event=" + event);
        } else {
            // A certain suggestion will exit telephony fallback mode.
            disableTelephonyFallbackIfNeeded();
        }

        // Now perform auto time zone detection. The new event may be used to modify the time zone
        // setting.
        String reason = "New location algorithm event received. event=" + event;
        doAutoTimeZoneDetection(currentUserConfig, reason);
    }

    @Override
    public synchronized boolean suggestManualTimeZone(
            @UserIdInt int userId, @NonNull ManualTimeZoneSuggestion suggestion,
            boolean bypassUserPolicyChecks) {

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

        TimeZoneCapabilities capabilities =
                currentUserConfig.asCapabilities(bypassUserPolicyChecks);
        if (capabilities.getSetManualTimeZoneCapability() != CAPABILITY_POSSESSED) {
            Slog.i(LOG_TAG, "User does not have the capability needed to set the time zone manually"
                    + ": capabilities=" + capabilities
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
                    + " suggestion=" + suggestion);
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
    public synchronized void enableTelephonyTimeZoneFallback(@NonNull String reason) {
        // Only do any work to enter fallback mode if fallback is currently not already enabled.
        if (!mTelephonyTimeZoneFallbackEnabled.getValue()) {
            ConfigurationInternal currentUserConfig = mCurrentConfigurationInternal;
            final boolean fallbackEnabled = true;
            mTelephonyTimeZoneFallbackEnabled = new TimestampedValue<>(
                    mEnvironment.elapsedRealtimeMillis(), fallbackEnabled);

            String logMsg = "enableTelephonyTimeZoneFallback: "
                    + " reason=" + reason
                    + ", currentUserConfig=" + currentUserConfig
                    + ", mTelephonyTimeZoneFallbackEnabled=" + mTelephonyTimeZoneFallbackEnabled;
            logTimeZoneDebugInfo(logMsg);

            // mTelephonyTimeZoneFallbackEnabled and mLatestLocationAlgorithmEvent interact.
            // If the latest location algorithm event contains a "certain" geolocation suggestion,
            // then the telephony fallback mode needs to be (re)considered after changing it.
            //
            // With the way that the mTelephonyTimeZoneFallbackEnabled time is currently chosen
            // above, and the fact that geolocation suggestions should never have a time in the
            // future, the following call will usually be a no-op, and telephony fallback mode will
            // remain enabled. This comment / call is left as a reminder that it is possible in some
            // cases for there to be a current, "certain" geolocation suggestion when an attempt is
            // made to enable telephony fallback mode and it is intentional that fallback mode stays
            // enabled in this case. The choice to do this is mostly for symmetry WRT the case where
            // fallback is enabled and then an old "certain" geolocation suggestion is received;
            // that would also leave telephony fallback mode enabled.
            //
            // This choice means that telephony fallback mode remains enabled if there is an
            // existing "certain" suggestion until a new "certain" geolocation suggestion is
            // received. If, instead, the next geolocation suggestion is "uncertain", then telephony
            // fallback, i.e. the use of a telephony suggestion, will actually occur.
            disableTelephonyFallbackIfNeeded();

            if (currentUserConfig.isTelephonyFallbackSupported()) {
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
                getLatestLocationAlgorithmEvent());
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
        // Use the correct detection algorithm based on the device's config and the user's current
        // configuration. If user config changes, then detection will be re-run.
        @DetectionMode int detectionMode = currentUserConfig.getDetectionMode();
        switch (detectionMode) {
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
            case ConfigurationInternal.DETECTION_MODE_UNKNOWN:
                // The "DETECTION_MODE_UNKNOWN" state can occur on devices with only location
                // detection algorithm support and when the user's master location toggle is off.
                Slog.i(LOG_TAG, "Unknown detection mode: " + detectionMode + ", is location off?");
                break;
            default:
                // Coding error
                Slog.wtf(LOG_TAG, "Unknown detection mode: " + detectionMode);
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
        // Terminate early if there's nothing to do.
        LocationAlgorithmEvent latestLocationAlgorithmEvent = mLatestLocationAlgorithmEvent.get();
        if (latestLocationAlgorithmEvent == null
                || latestLocationAlgorithmEvent.getSuggestion() == null) {
            return false;
        }

        GeolocationTimeZoneSuggestion suggestion = latestLocationAlgorithmEvent.getSuggestion();
        List<String> zoneIds = suggestion.getZoneIds();
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
     * Sets the mTelephonyTimeZoneFallbackEnabled state to {@code false} if the latest location
     * algorithm event contains a "certain" suggestion that comes after the time when telephony
     * fallback was enabled.
     */
    @GuardedBy("this")
    private void disableTelephonyFallbackIfNeeded() {
        LocationAlgorithmEvent latestLocationAlgorithmEvent = mLatestLocationAlgorithmEvent.get();
        if (latestLocationAlgorithmEvent == null) {
            return;
        }

        GeolocationTimeZoneSuggestion suggestion = latestLocationAlgorithmEvent.getSuggestion();
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

                String logMsg = "disableTelephonyFallbackIfNeeded:"
                        + " mTelephonyTimeZoneFallbackEnabled=" + mTelephonyTimeZoneFallbackEnabled;
                logTimeZoneDebugInfo(logMsg);
            }
        }
    }

    private void logTimeZoneDebugInfo(@NonNull String logMsg) {
        if (DBG) {
            Slog.d(LOG_TAG, logMsg);
        }
        mEnvironment.addDebugLogEntry(logMsg);
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
                Slog.d(LOG_TAG, "Best suggestion not good enough:"
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
                    + ", detectionReason=" + detectionReason);
            return;
        }

        String cause = "Found good suggestion:"
                + " bestTelephonySuggestion=" + bestTelephonySuggestion
                + ", detectionReason=" + detectionReason;
        setDeviceTimeZoneIfRequired(zoneId, cause);
    }

    @GuardedBy("this")
    private void setDeviceTimeZoneIfRequired(@NonNull String newZoneId, @NonNull String cause) {
        String currentZoneId = mEnvironment.getDeviceTimeZone();
        // All manual and automatic suggestions are considered high confidence as low-quality
        // suggestions are not currently passed on.
        int newConfidence = TIME_ZONE_CONFIDENCE_HIGH;
        int currentConfidence = mEnvironment.getDeviceTimeZoneConfidence();

        // Avoid unnecessary changes / intents. If the newConfidence is higher than the stored value
        // then we want to upgrade it.
        if (newZoneId.equals(currentZoneId) && newConfidence <= currentConfidence) {
            // No need to modify the device time zone settings.
            if (DBG) {
                Slog.d(LOG_TAG, "No need to change the time zone device is already set to newZoneId"
                        + ": newZoneId=" + newZoneId
                        + ", cause=" + cause
                        + ", currentScore=" + currentConfidence
                        + ", newConfidence=" + newConfidence);
            }
            return;
        }

        String logInfo = "Set device time zone or higher confidence:"
                + " newZoneId=" + newZoneId
                + ", cause=" + cause
                + ", newConfidence=" + newConfidence;
        if (DBG) {
            Slog.d(LOG_TAG, logInfo);
        }
        mEnvironment.setDeviceTimeZoneAndConfidence(newZoneId, newConfidence, logInfo);
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

    /**
     * Handles a configuration change notification.
     */
    private synchronized void handleConfigurationInternalMaybeChanged() {
        String logMsg = "handleConfigurationInternalMaybeChanged:";
        updateCurrentConfigurationInternalIfRequired(logMsg);
    }

    /**
     * Called whenever the information that contributes to {@link #mDetectorStatus} could have
     * changed. Updates the cached status snapshot if required.
     *
     * @return true if the status had changed and has been updated
     */
    @GuardedBy("this")
    private boolean updateDetectorStatus() {
        TimeZoneDetectorStatus newDetectorStatus = createTimeZoneDetectorStatus(
                mCurrentConfigurationInternal, mLatestLocationAlgorithmEvent.get());
        // mDetectorStatus is null the first time this method is called.
        TimeZoneDetectorStatus oldDetectorStatus = mDetectorStatus;
        boolean statusChanged = !newDetectorStatus.equals(oldDetectorStatus);
        if (statusChanged) {
            mDetectorStatus = newDetectorStatus;
        }
        return statusChanged;
    }

    /**
     * Dumps internal state such as field values.
     */
    @Override
    public synchronized void dump(@NonNull IndentingPrintWriter ipw, @Nullable String[] args) {
        ipw.println("TimeZoneDetectorStrategy:");

        ipw.increaseIndent(); // level 1
        ipw.println("mCurrentConfigurationInternal=" + mCurrentConfigurationInternal);
        ipw.println("mDetectorStatus=" + mDetectorStatus);
        final boolean bypassUserPolicyChecks = false;
        ipw.println("[Capabilities="
                + mCurrentConfigurationInternal.asCapabilities(bypassUserPolicyChecks) + "]");
        ipw.println("mEnvironment.getDeviceTimeZone()=" + mEnvironment.getDeviceTimeZone());
        ipw.println("mEnvironment.getDeviceTimeZoneConfidence()="
                + mEnvironment.getDeviceTimeZoneConfidence());

        ipw.println("Misc state:");
        ipw.increaseIndent(); // level 2
        ipw.println("mTelephonyTimeZoneFallbackEnabled="
                + formatDebugString(mTelephonyTimeZoneFallbackEnabled));
        ipw.decreaseIndent(); // level 2

        ipw.println("Time zone debug log:");
        ipw.increaseIndent(); // level 2
        mEnvironment.dumpDebugLog(ipw);
        ipw.decreaseIndent(); // level 2

        ipw.println("Manual suggestion history:");
        ipw.increaseIndent(); // level 2
        mLatestManualSuggestion.dump(ipw);
        ipw.decreaseIndent(); // level 2

        ipw.println("Location algorithm event history:");
        ipw.increaseIndent(); // level 2
        mLatestLocationAlgorithmEvent.dump(ipw);
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
    @Nullable
    public synchronized ManualTimeZoneSuggestion getLatestManualSuggestion() {
        return mLatestManualSuggestion.get();
    }

    /**
     * A method used to inspect strategy state during tests. Not intended for general use.
     */
    @VisibleForTesting
    @Nullable
    public synchronized QualifiedTelephonyTimeZoneSuggestion getLatestTelephonySuggestion(
            int slotIndex) {
        return mTelephonySuggestionsBySlotIndex.get(slotIndex);
    }

    /**
     * A method used to inspect strategy state during tests. Not intended for general use.
     */
    @VisibleForTesting
    @Nullable
    public synchronized LocationAlgorithmEvent getLatestLocationAlgorithmEvent() {
        return mLatestLocationAlgorithmEvent.get();
    }

    @VisibleForTesting
    public synchronized boolean isTelephonyFallbackEnabledForTests() {
        return mTelephonyTimeZoneFallbackEnabled.getValue();
    }

    @VisibleForTesting
    public synchronized ConfigurationInternal getCachedCapabilitiesAndConfigForTests() {
        return mCurrentConfigurationInternal;
    }

    @VisibleForTesting
    public synchronized TimeZoneDetectorStatus getCachedDetectorStatusForTests() {
        return mDetectorStatus;
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

    @NonNull
    private static TimeZoneDetectorStatus createTimeZoneDetectorStatus(
            @NonNull ConfigurationInternal currentConfigurationInternal,
            @Nullable LocationAlgorithmEvent latestLocationAlgorithmEvent) {

        int detectorStatus;
        if (!currentConfigurationInternal.isAutoDetectionSupported()) {
            detectorStatus = DetectorStatusTypes.DETECTOR_STATUS_NOT_SUPPORTED;
        } else if (currentConfigurationInternal.getAutoDetectionEnabledBehavior()) {
            detectorStatus = DetectorStatusTypes.DETECTOR_STATUS_RUNNING;
        } else {
            detectorStatus = DetectorStatusTypes.DETECTOR_STATUS_NOT_RUNNING;
        }

        TelephonyTimeZoneAlgorithmStatus telephonyAlgorithmStatus =
                createTelephonyAlgorithmStatus(currentConfigurationInternal);

        LocationTimeZoneAlgorithmStatus locationAlgorithmStatus = createLocationAlgorithmStatus(
                currentConfigurationInternal, latestLocationAlgorithmEvent);

        return new TimeZoneDetectorStatus(
                detectorStatus, telephonyAlgorithmStatus, locationAlgorithmStatus);
    }

    @NonNull
    private static LocationTimeZoneAlgorithmStatus createLocationAlgorithmStatus(
            ConfigurationInternal currentConfigurationInternal,
            LocationAlgorithmEvent latestLocationAlgorithmEvent) {
        LocationTimeZoneAlgorithmStatus locationAlgorithmStatus;
        if (latestLocationAlgorithmEvent != null) {
            locationAlgorithmStatus = latestLocationAlgorithmEvent.getAlgorithmStatus();
        } else if (!currentConfigurationInternal.isGeoDetectionSupported()) {
            locationAlgorithmStatus = LocationTimeZoneAlgorithmStatus.NOT_SUPPORTED;
        } else if (currentConfigurationInternal.isGeoDetectionExecutionEnabled()) {
            locationAlgorithmStatus = LocationTimeZoneAlgorithmStatus.RUNNING_NOT_REPORTED;
        } else {
            locationAlgorithmStatus = LocationTimeZoneAlgorithmStatus.NOT_RUNNING;
        }
        return locationAlgorithmStatus;
    }

    @NonNull
    private static TelephonyTimeZoneAlgorithmStatus createTelephonyAlgorithmStatus(
            @NonNull ConfigurationInternal currentConfigurationInternal) {
        int algorithmStatus;
        if (!currentConfigurationInternal.isTelephonyDetectionSupported()) {
            algorithmStatus = DetectorStatusTypes.DETECTION_ALGORITHM_STATUS_NOT_SUPPORTED;
        } else {
            // The telephony detector is passive, so we treat it as "running".
            algorithmStatus = DetectorStatusTypes.DETECTION_ALGORITHM_STATUS_RUNNING;
        }
        return new TelephonyTimeZoneAlgorithmStatus(algorithmStatus);
    }
}
