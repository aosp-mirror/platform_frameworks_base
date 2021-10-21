/*
 * Copyright (C) 2020 The Android Open Source Project
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

package com.android.server.timezonedetector.location;

import static android.service.timezone.TimeZoneProviderEvent.EVENT_TYPE_PERMANENT_FAILURE;
import static android.service.timezone.TimeZoneProviderEvent.EVENT_TYPE_SUGGESTION;
import static android.service.timezone.TimeZoneProviderEvent.EVENT_TYPE_UNCERTAIN;

import static com.android.server.timezonedetector.location.LocationTimeZoneManagerService.debugLog;
import static com.android.server.timezonedetector.location.LocationTimeZoneManagerService.warnLog;
import static com.android.server.timezonedetector.location.LocationTimeZoneProvider.ProviderState;
import static com.android.server.timezonedetector.location.LocationTimeZoneProvider.ProviderState.PROVIDER_STATE_DESTROYED;
import static com.android.server.timezonedetector.location.LocationTimeZoneProvider.ProviderState.PROVIDER_STATE_PERM_FAILED;
import static com.android.server.timezonedetector.location.LocationTimeZoneProvider.ProviderState.PROVIDER_STATE_STARTED_CERTAIN;
import static com.android.server.timezonedetector.location.LocationTimeZoneProvider.ProviderState.PROVIDER_STATE_STARTED_INITIALIZING;
import static com.android.server.timezonedetector.location.LocationTimeZoneProvider.ProviderState.PROVIDER_STATE_STARTED_UNCERTAIN;
import static com.android.server.timezonedetector.location.LocationTimeZoneProvider.ProviderState.PROVIDER_STATE_STOPPED;

import android.annotation.DurationMillisLong;
import android.annotation.ElapsedRealtimeLong;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.service.timezone.TimeZoneProviderEvent;
import android.service.timezone.TimeZoneProviderSuggestion;
import android.util.IndentingPrintWriter;

import com.android.internal.annotations.GuardedBy;
import com.android.server.timezonedetector.ConfigurationInternal;
import com.android.server.timezonedetector.GeolocationTimeZoneSuggestion;
import com.android.server.timezonedetector.location.ThreadingDomain.SingleRunnableQueue;

import java.time.Duration;
import java.util.Objects;

/**
 * A real implementation of {@link LocationTimeZoneProviderController} that supports a primary and a
 * secondary {@link LocationTimeZoneProvider}.
 *
 * <p>The primary is used until it fails or becomes uncertain. The secondary will then be started.
 * The controller will immediately make suggestions based on "certain" {@link
 * TimeZoneProviderEvent}s, i.e. events that demonstrate the provider is certain what the time zone
 * is. The controller will not make immediate suggestions based on "uncertain" events, giving
 * providers time to change their mind. This also gives the secondary provider time to initialize
 * when the primary becomes uncertain.
 */
class ControllerImpl extends LocationTimeZoneProviderController {

    @NonNull private final LocationTimeZoneProvider mPrimaryProvider;

    @NonNull private final LocationTimeZoneProvider mSecondaryProvider;

    @GuardedBy("mSharedLock")
    // Non-null after initialize()
    private ConfigurationInternal mCurrentUserConfiguration;

    @GuardedBy("mSharedLock")
    // Non-null after initialize()
    private Environment mEnvironment;

    @GuardedBy("mSharedLock")
    // Non-null after initialize()
    private Callback mCallback;

    /** Indicates both providers have completed initialization. */
    @GuardedBy("mSharedLock")
    private boolean mProvidersInitialized;

    /**
     * Used for scheduling uncertainty timeouts, i.e after a provider has reported uncertainty.
     * This timeout is not provider-specific: it is started when the controller becomes uncertain
     * due to events it has received from one or other provider.
     */
    @NonNull private final SingleRunnableQueue mUncertaintyTimeoutQueue;

    /** Contains the last suggestion actually made, if there is one. */
    @GuardedBy("mSharedLock")
    @Nullable
    private GeolocationTimeZoneSuggestion mLastSuggestion;

    ControllerImpl(@NonNull ThreadingDomain threadingDomain,
            @NonNull LocationTimeZoneProvider primaryProvider,
            @NonNull LocationTimeZoneProvider secondaryProvider) {
        super(threadingDomain);
        mUncertaintyTimeoutQueue = threadingDomain.createSingleRunnableQueue();
        mPrimaryProvider = Objects.requireNonNull(primaryProvider);
        mSecondaryProvider = Objects.requireNonNull(secondaryProvider);
    }

    @Override
    void initialize(@NonNull Environment environment, @NonNull Callback callback) {
        mThreadingDomain.assertCurrentThread();

        synchronized (mSharedLock) {
            debugLog("initialize()");
            mEnvironment = Objects.requireNonNull(environment);
            mCallback = Objects.requireNonNull(callback);
            mCurrentUserConfiguration = environment.getCurrentUserConfigurationInternal();

            LocationTimeZoneProvider.ProviderListener providerListener =
                    ControllerImpl.this::onProviderStateChange;
            mPrimaryProvider.initialize(providerListener);
            mSecondaryProvider.initialize(providerListener);
            mProvidersInitialized = true;

            alterProvidersStartedStateIfRequired(
                    null /* oldConfiguration */, mCurrentUserConfiguration);
        }
    }

    @Override
    void onConfigChanged() {
        mThreadingDomain.assertCurrentThread();

        synchronized (mSharedLock) {
            debugLog("onConfigChanged()");

            ConfigurationInternal oldConfig = mCurrentUserConfiguration;
            ConfigurationInternal newConfig = mEnvironment.getCurrentUserConfigurationInternal();
            mCurrentUserConfiguration = newConfig;

            if (!newConfig.equals(oldConfig)) {
                if (newConfig.getUserId() != oldConfig.getUserId()) {
                    // If the user changed, stop the providers if needed. They may be re-started
                    // for the new user immediately afterwards if their settings allow.
                    debugLog("User changed. old=" + oldConfig.getUserId()
                            + ", new=" + newConfig.getUserId() + ": Stopping providers");
                    stopProviders();

                    alterProvidersStartedStateIfRequired(null /* oldConfiguration */, newConfig);
                } else {
                    alterProvidersStartedStateIfRequired(oldConfig, newConfig);
                }
            }
        }
    }

    @Override
    boolean isUncertaintyTimeoutSet() {
        return mUncertaintyTimeoutQueue.hasQueued();
    }

    @Override
    @DurationMillisLong
    long getUncertaintyTimeoutDelayMillis() {
        return mUncertaintyTimeoutQueue.getQueuedDelayMillis();
    }

    @Override
    void destroy() {
        mThreadingDomain.assertCurrentThread();

        synchronized (mSharedLock) {
            stopProviders();
            mPrimaryProvider.destroy();
            mSecondaryProvider.destroy();
        }
    }

    @GuardedBy("mSharedLock")
    private void stopProviders() {
        stopProviderIfStarted(mPrimaryProvider);
        stopProviderIfStarted(mSecondaryProvider);

        // By definition, if both providers are stopped, the controller is uncertain.
        cancelUncertaintyTimeout();

        // If a previous "certain" suggestion has been made, then a new "uncertain"
        // suggestion must now be made to indicate the controller {does not / no longer has}
        // an opinion and will not be sending further updates (until at least the providers are
        // re-started).
        if (mLastSuggestion != null && mLastSuggestion.getZoneIds() != null) {
            GeolocationTimeZoneSuggestion suggestion = createUncertainSuggestion(
                    mEnvironment.elapsedRealtimeMillis(), "Providers are stopping");
            makeSuggestion(suggestion);
        }
    }

    @GuardedBy("mSharedLock")
    private void stopProviderIfStarted(@NonNull LocationTimeZoneProvider provider) {
        if (provider.getCurrentState().isStarted()) {
            stopProvider(provider);
        }
    }

    @GuardedBy("mSharedLock")
    private void stopProvider(@NonNull LocationTimeZoneProvider provider) {
        ProviderState providerState = provider.getCurrentState();
        switch (providerState.stateEnum) {
            case PROVIDER_STATE_STOPPED: {
                debugLog("No need to stop " + provider + ": already stopped");
                break;
            }
            case PROVIDER_STATE_STARTED_INITIALIZING:
            case PROVIDER_STATE_STARTED_CERTAIN:
            case PROVIDER_STATE_STARTED_UNCERTAIN: {
                debugLog("Stopping " + provider);
                provider.stopUpdates();
                break;
            }
            case PROVIDER_STATE_PERM_FAILED:
            case PROVIDER_STATE_DESTROYED: {
                debugLog("Unable to stop " + provider + ": it is terminated.");
                break;
            }
            default: {
                warnLog("Unknown provider state: " + provider);
                break;
            }
        }
    }

    /**
     * Sets the providers into the correct started/stopped state for the {@code newConfiguration}
     * and, if there is a provider state change, makes any suggestions required to inform the
     * downstream time zone detection code.
     *
     * <p>This is a utility method that exists to avoid duplicated logic for the various cases when
     * provider started / stopped state may need to be set or changed, e.g. during initialization
     * or when a new configuration has been received.
     */
    @GuardedBy("mSharedLock")
    private void alterProvidersStartedStateIfRequired(
            @Nullable ConfigurationInternal oldConfiguration,
            @NonNull ConfigurationInternal newConfiguration) {

        // Provider started / stopped states only need to be changed if geoDetectionEnabled has
        // changed.
        boolean oldGeoDetectionEnabled = oldConfiguration != null
                && oldConfiguration.getGeoDetectionEnabledBehavior();
        boolean newGeoDetectionEnabled = newConfiguration.getGeoDetectionEnabledBehavior();
        if (oldGeoDetectionEnabled == newGeoDetectionEnabled) {
            return;
        }

        // The check above ensures that the logic below only executes if providers are going from
        // {started *} -> {stopped}, or {stopped} -> {started initializing}. If this changes in
        // future and there could be {started *} -> {started *} cases, or cases where the provider
        // can't be assumed to go straight to the {started initializing} state, then the logic below
        // would need to cover extra conditions, for example:
        // 1) If the primary is in {started uncertain}, the secondary should be started.
        // 2) If (1), and the secondary instantly enters the {perm failed} state, the uncertainty
        //    timeout started when the primary entered {started uncertain} should be cancelled.

        if (newGeoDetectionEnabled) {
            // Try to start the primary provider.
            tryStartProvider(mPrimaryProvider, newConfiguration);

            // The secondary should only ever be started if the primary now isn't started (i.e. it
            // couldn't become {started initializing} because it is {perm failed}).
            ProviderState newPrimaryState = mPrimaryProvider.getCurrentState();
            if (!newPrimaryState.isStarted()) {
                // If the primary provider is {perm failed} then the controller must try to start
                // the secondary.
                tryStartProvider(mSecondaryProvider, newConfiguration);

                ProviderState newSecondaryState = mSecondaryProvider.getCurrentState();
                if (!newSecondaryState.isStarted()) {
                    // If both providers are {perm failed} then the controller immediately
                    // becomes uncertain.
                    GeolocationTimeZoneSuggestion suggestion = createUncertainSuggestion(
                            mEnvironment.elapsedRealtimeMillis(),
                            "Providers are failed:"
                                    + " primary=" + mPrimaryProvider.getCurrentState()
                                    + " secondary=" + mPrimaryProvider.getCurrentState());
                    makeSuggestion(suggestion);
                }
            }
        } else {
            stopProviders();
        }
    }

    @GuardedBy("mSharedLock")
    private void tryStartProvider(@NonNull LocationTimeZoneProvider provider,
            @NonNull ConfigurationInternal configuration) {
        ProviderState providerState = provider.getCurrentState();
        switch (providerState.stateEnum) {
            case PROVIDER_STATE_STOPPED: {
                debugLog("Enabling " + provider);
                provider.startUpdates(configuration,
                        mEnvironment.getProviderInitializationTimeout(),
                        mEnvironment.getProviderInitializationTimeoutFuzz(),
                        mEnvironment.getProviderEventFilteringAgeThreshold());
                break;
            }
            case PROVIDER_STATE_STARTED_INITIALIZING:
            case PROVIDER_STATE_STARTED_CERTAIN:
            case PROVIDER_STATE_STARTED_UNCERTAIN: {
                debugLog("No need to start " + provider + ": already started");
                break;
            }
            case PROVIDER_STATE_PERM_FAILED:
            case PROVIDER_STATE_DESTROYED: {
                debugLog("Unable to start " + provider + ": it is terminated");
                break;
            }
            default: {
                throw new IllegalStateException("Unknown provider state:"
                        + " provider=" + provider);
            }
        }
    }

    void onProviderStateChange(@NonNull ProviderState providerState) {
        mThreadingDomain.assertCurrentThread();
        LocationTimeZoneProvider provider = providerState.provider;
        assertProviderKnown(provider);

        synchronized (mSharedLock) {
            // Ignore provider state changes during initialization. e.g. if the primary provider
            // moves to PROVIDER_STATE_PERM_FAILED during initialization, the secondary will not
            // be ready to take over yet.
            if (!mProvidersInitialized) {
                warnLog("onProviderStateChange: Ignoring provider state change because both"
                        + " providers have not yet completed initialization."
                        + " providerState=" + providerState);
                return;
            }

            switch (providerState.stateEnum) {
                case PROVIDER_STATE_STARTED_INITIALIZING:
                case PROVIDER_STATE_STOPPED:
                case PROVIDER_STATE_DESTROYED: {
                    // This should never happen: entering initializing, stopped or destroyed are
                    // triggered by the controller so and should not trigger a state change
                    // callback.
                    warnLog("onProviderStateChange: Unexpected state change for provider,"
                            + " provider=" + provider);
                    break;
                }
                case PROVIDER_STATE_STARTED_CERTAIN:
                case PROVIDER_STATE_STARTED_UNCERTAIN: {
                    // These are valid and only happen if an event is received while the provider is
                    // started.
                    debugLog("onProviderStateChange: Received notification of a state change while"
                            + " started, provider=" + provider);
                    handleProviderStartedStateChange(providerState);
                    break;
                }
                case PROVIDER_STATE_PERM_FAILED: {
                    debugLog("Received notification of permanent failure for"
                            + " provider=" + provider);
                    handleProviderFailedStateChange(providerState);
                    break;
                }
                default: {
                    warnLog("onProviderStateChange: Unexpected provider=" + provider);
                }
            }
        }
    }

    private void assertProviderKnown(@NonNull LocationTimeZoneProvider provider) {
        if (provider != mPrimaryProvider && provider != mSecondaryProvider) {
            throw new IllegalArgumentException("Unknown provider: " + provider);
        }
    }

    /**
     * Called when a provider has reported that it has failed permanently.
     */
    @GuardedBy("mSharedLock")
    private void handleProviderFailedStateChange(@NonNull ProviderState providerState) {
        LocationTimeZoneProvider failedProvider = providerState.provider;
        ProviderState primaryCurrentState = mPrimaryProvider.getCurrentState();
        ProviderState secondaryCurrentState = mSecondaryProvider.getCurrentState();

        // If a provider has failed, the other may need to be started.
        if (failedProvider == mPrimaryProvider) {
            if (!secondaryCurrentState.isTerminated()) {
                // Try to start the secondary. This does nothing if the provider is already
                // started, and will leave the provider in {started initializing} if the provider is
                // stopped.
                tryStartProvider(mSecondaryProvider, mCurrentUserConfiguration);
            }
        } else if (failedProvider == mSecondaryProvider) {
            // No-op: The secondary will only be active if the primary is uncertain or is
            // terminated. So, there the primary should not need to be started when the secondary
            // fails.
            if (primaryCurrentState.stateEnum != PROVIDER_STATE_STARTED_UNCERTAIN
                    && !primaryCurrentState.isTerminated()) {
                warnLog("Secondary provider unexpected reported a failure:"
                        + " failed provider=" + failedProvider.getName()
                        + ", primary provider=" + mPrimaryProvider
                        + ", secondary provider=" + mSecondaryProvider);
            }
        }

        // If both providers are now terminated, the controller needs to tell the next component in
        // the time zone detection process.
        if (primaryCurrentState.isTerminated() && secondaryCurrentState.isTerminated()) {

            // If both providers are newly terminated then the controller is uncertain by definition
            // and it will never recover so it can send a suggestion immediately.
            cancelUncertaintyTimeout();

            // If both providers are now terminated, then a suggestion must be sent informing the
            // time zone detector that there are no further updates coming in future.
            GeolocationTimeZoneSuggestion suggestion = createUncertainSuggestion(
                    mEnvironment.elapsedRealtimeMillis(),
                    "Both providers are terminated:"
                            + " primary=" + primaryCurrentState.provider
                            + ", secondary=" + secondaryCurrentState.provider);
            makeSuggestion(suggestion);
        }
    }

    /**
     * Called when a provider has changed state but just moved from one started state to another
     * started state, usually as a result of a new {@link TimeZoneProviderEvent} being received.
     * However, there are rare cases where the event can also be null.
     */
    @GuardedBy("mSharedLock")
    private void handleProviderStartedStateChange(@NonNull ProviderState providerState) {
        LocationTimeZoneProvider provider = providerState.provider;
        TimeZoneProviderEvent event = providerState.event;
        if (event == null) {
            // Implicit uncertainty, i.e. where the provider is started, but a problem has been
            // detected without having received an event. For example, if the process has detected
            // the loss of a binder-based provider, or initialization took too long. This is treated
            // the same as explicit uncertainty, i.e. where the provider has explicitly told this
            // process it is uncertain.
            long uncertaintyStartedElapsedMillis = mEnvironment.elapsedRealtimeMillis();
            handleProviderUncertainty(provider, uncertaintyStartedElapsedMillis,
                    "provider=" + provider + ", implicit uncertainty, event=null");
            return;
        }

        if (!mCurrentUserConfiguration.getGeoDetectionEnabledBehavior()) {
            // This should not happen: the provider should not be in an started state if the user
            // does not have geodetection enabled.
            warnLog("Provider=" + provider + " is started, but"
                    + " currentUserConfiguration=" + mCurrentUserConfiguration
                    + " suggests it shouldn't be.");
        }

        switch (event.getType()) {
            case EVENT_TYPE_PERMANENT_FAILURE: {
                // This shouldn't happen. A provider cannot be started and have this event type.
                warnLog("Provider=" + provider + " is started, but event suggests it shouldn't be");
                break;
            }
            case EVENT_TYPE_UNCERTAIN: {
                long uncertaintyStartedElapsedMillis = event.getCreationElapsedMillis();
                handleProviderUncertainty(provider, uncertaintyStartedElapsedMillis,
                        "provider=" + provider + ", explicit uncertainty. event=" + event);
                break;
            }
            case EVENT_TYPE_SUGGESTION: {
                handleProviderSuggestion(provider, event);
                break;
            }
            default: {
                warnLog("Unknown eventType=" + event.getType());
                break;
            }
        }
    }

    /**
     * Called when a provider has become "certain" about the time zone(s).
     */
    @GuardedBy("mSharedLock")
    private void handleProviderSuggestion(
            @NonNull LocationTimeZoneProvider provider,
            @NonNull TimeZoneProviderEvent providerEvent) {

        // By definition, the controller is now certain.
        cancelUncertaintyTimeout();

        if (provider == mPrimaryProvider) {
            stopProviderIfStarted(mSecondaryProvider);
        }

        TimeZoneProviderSuggestion providerSuggestion = providerEvent.getSuggestion();

        // For the suggestion's effectiveFromElapsedMillis, use the time embedded in the provider's
        // suggestion (which indicates the time when the provider detected the location used to
        // establish the time zone).
        //
        // An alternative would be to use the current time or the providerEvent creation time, but
        // this would hinder the ability for the time_zone_detector to judge which suggestions are
        // based on newer information when comparing suggestions between different sources.
        long effectiveFromElapsedMillis = providerSuggestion.getElapsedRealtimeMillis();
        GeolocationTimeZoneSuggestion geoSuggestion =
                GeolocationTimeZoneSuggestion.createCertainSuggestion(
                        effectiveFromElapsedMillis, providerSuggestion.getTimeZoneIds());

        String debugInfo = "Event received provider=" + provider
                + ", providerEvent=" + providerEvent
                + ", suggestionCreationTime=" + mEnvironment.elapsedRealtimeMillis();
        geoSuggestion.addDebugInfo(debugInfo);
        makeSuggestion(geoSuggestion);
    }

    @Override
    public void dump(@NonNull IndentingPrintWriter ipw, @Nullable String[] args) {
        synchronized (mSharedLock) {
            ipw.println("LocationTimeZoneProviderController:");

            ipw.increaseIndent(); // level 1
            ipw.println("mCurrentUserConfiguration=" + mCurrentUserConfiguration);
            ipw.println("providerInitializationTimeout="
                    + mEnvironment.getProviderInitializationTimeout());
            ipw.println("providerInitializationTimeoutFuzz="
                    + mEnvironment.getProviderInitializationTimeoutFuzz());
            ipw.println("uncertaintyDelay=" + mEnvironment.getUncertaintyDelay());
            ipw.println("mLastSuggestion=" + mLastSuggestion);

            ipw.println("Primary Provider:");
            ipw.increaseIndent(); // level 2
            mPrimaryProvider.dump(ipw, args);
            ipw.decreaseIndent(); // level 2

            ipw.println("Secondary Provider:");
            ipw.increaseIndent(); // level 2
            mSecondaryProvider.dump(ipw, args);
            ipw.decreaseIndent(); // level 2

            ipw.decreaseIndent(); // level 1
        }
    }

    /** Sends an immediate suggestion, updating mLastSuggestion. */
    @GuardedBy("mSharedLock")
    private void makeSuggestion(@NonNull GeolocationTimeZoneSuggestion suggestion) {
        debugLog("makeSuggestion: suggestion=" + suggestion);
        mCallback.suggest(suggestion);
        mLastSuggestion = suggestion;
    }

    /** Clears the uncertainty timeout. */
    @GuardedBy("mSharedLock")
    private void cancelUncertaintyTimeout() {
        mUncertaintyTimeoutQueue.cancel();
    }

    /**
     * Called when a provider has become "uncertain" about the time zone.
     *
     * <p>A provider is expected to report its uncertainty as soon as it becomes uncertain, as
     * this enables the most flexibility for the controller to start other providers when there are
     * multiple ones available. The controller is therefore responsible for deciding when to make a
     * "uncertain" suggestion to the downstream time zone detector.
     *
     * <p>This method schedules an "uncertainty" timeout (if one isn't already scheduled) to be
     * triggered later if nothing else preempts it. It can be preempted if the provider becomes
     * certain (or does anything else that calls {@link
     * #makeSuggestion(GeolocationTimeZoneSuggestion)}) within {@link
     * Environment#getUncertaintyDelay()}. Preemption causes the scheduled
     * "uncertainty" timeout to be cancelled. If the provider repeatedly sends uncertainty events
     * within the uncertainty delay period, those events are effectively ignored (i.e. the timeout
     * is not reset each time).
     */
    @GuardedBy("mSharedLock")
    void handleProviderUncertainty(
            @NonNull LocationTimeZoneProvider provider,
            @ElapsedRealtimeLong long uncertaintyStartedElapsedMillis,
            @NonNull String reason) {
        Objects.requireNonNull(provider);

        // Start the uncertainty timeout if needed to ensure the controller will eventually make an
        // uncertain suggestion if no success event arrives in time to counteract it.
        if (!mUncertaintyTimeoutQueue.hasQueued()) {
            debugLog("Starting uncertainty timeout: reason=" + reason);

            Duration uncertaintyDelay = mEnvironment.getUncertaintyDelay();
            mUncertaintyTimeoutQueue.runDelayed(
                    () -> onProviderUncertaintyTimeout(
                            provider, uncertaintyStartedElapsedMillis, uncertaintyDelay),
                    uncertaintyDelay.toMillis());
        }

        if (provider == mPrimaryProvider) {
            // (Try to) start the secondary. It could already be started, or enabling might not
            // succeed if the provider has previously reported it is perm failed. The uncertainty
            // timeout (set above) is used to ensure that an uncertain suggestion will be made if
            // the secondary cannot generate a success event in time.
            tryStartProvider(mSecondaryProvider, mCurrentUserConfiguration);
        }
    }

    private void onProviderUncertaintyTimeout(
            @NonNull LocationTimeZoneProvider provider,
            @ElapsedRealtimeLong long uncertaintyStartedElapsedMillis,
            @NonNull Duration uncertaintyDelay) {
        mThreadingDomain.assertCurrentThread();

        synchronized (mSharedLock) {
            long afterUncertaintyTimeoutElapsedMillis = mEnvironment.elapsedRealtimeMillis();

            // For the effectiveFromElapsedMillis suggestion property, use the
            // uncertaintyStartedElapsedMillis. This is the time when the provider first reported
            // uncertainty, i.e. before the uncertainty timeout.
            //
            // afterUncertaintyTimeoutElapsedMillis could be used instead, which is the time when
            // the location_time_zone_manager finally confirms that the time zone was uncertain,
            // but the suggestion property allows the information to be back-dated, which should
            // help when comparing suggestions from different sources.
            GeolocationTimeZoneSuggestion suggestion = createUncertainSuggestion(
                    uncertaintyStartedElapsedMillis,
                    "Uncertainty timeout triggered for " + provider.getName() + ":"
                            + " primary=" + mPrimaryProvider
                            + ", secondary=" + mSecondaryProvider
                            + ", uncertaintyStarted="
                            + Duration.ofMillis(uncertaintyStartedElapsedMillis)
                            + ", afterUncertaintyTimeout="
                            + Duration.ofMillis(afterUncertaintyTimeoutElapsedMillis)
                            + ", uncertaintyDelay=" + uncertaintyDelay
            );
            makeSuggestion(suggestion);
        }
    }

    @NonNull
    private static GeolocationTimeZoneSuggestion createUncertainSuggestion(
            @ElapsedRealtimeLong long effectiveFromElapsedMillis,
            @NonNull String reason) {
        GeolocationTimeZoneSuggestion suggestion =
                GeolocationTimeZoneSuggestion.createUncertainSuggestion(
                        effectiveFromElapsedMillis);
        suggestion.addDebugInfo(reason);
        return suggestion;
    }

    /**
     * Clears recorded provider state changes (for use during tests).
     */
    void clearRecordedProviderStates() {
        mThreadingDomain.assertCurrentThread();

        synchronized (mSharedLock) {
            mPrimaryProvider.clearRecordedStates();
            mSecondaryProvider.clearRecordedStates();
        }
    }

    /**
     * Returns a snapshot of the current controller state for tests.
     */
    @NonNull
    LocationTimeZoneManagerServiceState getStateForTests() {
        mThreadingDomain.assertCurrentThread();

        synchronized (mSharedLock) {
            LocationTimeZoneManagerServiceState.Builder builder =
                    new LocationTimeZoneManagerServiceState.Builder();
            if (mLastSuggestion != null) {
                builder.setLastSuggestion(mLastSuggestion);
            }
            builder.setPrimaryProviderStateChanges(mPrimaryProvider.getRecordedStates())
                    .setSecondaryProviderStateChanges(mSecondaryProvider.getRecordedStates());
            return builder.build();
        }
    }
}
