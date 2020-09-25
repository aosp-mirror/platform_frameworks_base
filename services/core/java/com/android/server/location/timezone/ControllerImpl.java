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

package com.android.server.location.timezone;

import static android.location.timezone.LocationTimeZoneEvent.EVENT_TYPE_PERMANENT_FAILURE;
import static android.location.timezone.LocationTimeZoneEvent.EVENT_TYPE_SUCCESS;
import static android.location.timezone.LocationTimeZoneEvent.EVENT_TYPE_UNCERTAIN;

import static com.android.server.location.timezone.LocationTimeZoneManagerService.debugLog;
import static com.android.server.location.timezone.LocationTimeZoneManagerService.warnLog;
import static com.android.server.location.timezone.LocationTimeZoneProvider.ProviderState;
import static com.android.server.location.timezone.LocationTimeZoneProvider.ProviderState.PROVIDER_STATE_DISABLED;
import static com.android.server.location.timezone.LocationTimeZoneProvider.ProviderState.PROVIDER_STATE_ENABLED;
import static com.android.server.location.timezone.LocationTimeZoneProvider.ProviderState.PROVIDER_STATE_PERM_FAILED;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.location.timezone.LocationTimeZoneEvent;
import android.util.IndentingPrintWriter;

import com.android.internal.annotations.GuardedBy;
import com.android.server.location.timezone.ThreadingDomain.SingleRunnableQueue;
import com.android.server.timezonedetector.ConfigurationInternal;
import com.android.server.timezonedetector.GeolocationTimeZoneSuggestion;

import java.time.Duration;
import java.util.Objects;

/**
 * A real implementation of {@link LocationTimeZoneProviderController} that supports a single
 * {@link LocationTimeZoneProvider}.
 *
 * TODO(b/152744911): This implementation currently only supports a single ("primary") provider.
 *  Support for a secondary provider will be added in a later commit.
 */
class ControllerImpl extends LocationTimeZoneProviderController {

    @NonNull private final LocationTimeZoneProvider mProvider;
    @NonNull private final SingleRunnableQueue mDelayedSuggestionQueue;

    @GuardedBy("mSharedLock")
    // Non-null after initialize()
    private ConfigurationInternal mCurrentUserConfiguration;

    @GuardedBy("mSharedLock")
    // Non-null after initialize()
    private Environment mEnvironment;

    @GuardedBy("mSharedLock")
    // Non-null after initialize()
    private Callback mCallback;

    /**
     * Contains any currently pending suggestion on {@link #mDelayedSuggestionQueue}, if there is
     * one.
     */
    @GuardedBy("mSharedLock")
    @Nullable
    private GeolocationTimeZoneSuggestion mPendingSuggestion;

    /** Contains the last suggestion actually made, if there is one. */
    @GuardedBy("mSharedLock")
    @Nullable
    private GeolocationTimeZoneSuggestion mLastSuggestion;

    ControllerImpl(@NonNull ThreadingDomain threadingDomain,
            @NonNull LocationTimeZoneProvider provider) {
        super(threadingDomain);
        mDelayedSuggestionQueue = threadingDomain.createSingleRunnableQueue();
        mProvider = Objects.requireNonNull(provider);
    }

    @Override
    void initialize(@NonNull Environment environment, @NonNull Callback callback) {
        mThreadingDomain.assertCurrentThread();

        synchronized (mSharedLock) {
            debugLog("initialize()");
            mEnvironment = Objects.requireNonNull(environment);
            mCallback = Objects.requireNonNull(callback);
            mCurrentUserConfiguration = environment.getCurrentUserConfigurationInternal();

            mProvider.initialize(ControllerImpl.this::onProviderStateChange);
            enableOrDisableProvider(mCurrentUserConfiguration);
        }
    }

    @Override
    void onConfigChanged() {
        mThreadingDomain.assertCurrentThread();

        synchronized (mSharedLock) {
            debugLog("onEnvironmentConfigChanged()");

            ConfigurationInternal oldConfig = mCurrentUserConfiguration;
            ConfigurationInternal newConfig = mEnvironment.getCurrentUserConfigurationInternal();
            mCurrentUserConfiguration = newConfig;

            if (!newConfig.equals(oldConfig)) {
                if (newConfig.getUserId() != oldConfig.getUserId()) {
                    // If the user changed, disable the provider if needed. It may be re-enabled for
                    // the new user below if their settings allow.
                    debugLog("User changed. old=" + oldConfig.getUserId()
                            + ", new=" + newConfig.getUserId());
                    debugLog("Disabling LocationTimeZoneProviders as needed");
                    if (mProvider.getCurrentState().stateEnum == PROVIDER_STATE_ENABLED) {
                        mProvider.disable();
                    }
                }

                enableOrDisableProvider(newConfig);
            }
        }
    }

    @GuardedBy("mSharedLock")
    private void enableOrDisableProvider(@NonNull ConfigurationInternal configuration) {
        ProviderState providerState = mProvider.getCurrentState();
        boolean geoDetectionEnabled = configuration.getGeoDetectionEnabledBehavior();
        boolean providerWasEnabled = providerState.stateEnum == PROVIDER_STATE_ENABLED;
        if (geoDetectionEnabled) {
            switch (providerState.stateEnum) {
                case PROVIDER_STATE_DISABLED: {
                    debugLog("Enabling " + mProvider);
                    mProvider.enable(
                            configuration, mEnvironment.getProviderInitializationTimeout());
                    break;
                }
                case PROVIDER_STATE_ENABLED: {
                    debugLog("No need to enable " + mProvider + ": already enabled");
                    break;
                }
                case PROVIDER_STATE_PERM_FAILED: {
                    debugLog("Unable to enable " + mProvider + ": it is perm failed");
                    break;
                }
                default:
                    warnLog("Unknown provider state: " + mProvider);
                    break;
            }
        } else {
            switch (providerState.stateEnum) {
                case PROVIDER_STATE_DISABLED: {
                    debugLog("No need to disable " + mProvider + ": already enabled");
                    break;
                }
                case PROVIDER_STATE_ENABLED: {
                    debugLog("Disabling " + mProvider);
                    mProvider.disable();
                    break;
                }
                case PROVIDER_STATE_PERM_FAILED: {
                    debugLog("Unable to disable " + mProvider + ": it is perm failed");
                    break;
                }
                default: {
                    warnLog("Unknown provider state: " + mProvider);
                    break;
                }
            }
        }

        boolean isProviderEnabled =
                mProvider.getCurrentState().stateEnum == PROVIDER_STATE_ENABLED;

        if (isProviderEnabled) {
            if (!providerWasEnabled) {
                // When a provider has first been enabled, we allow it some time for it to
                // initialize before sending its first event.
                Duration initializationTimeout = mEnvironment.getProviderInitializationTimeout()
                        .plus(mEnvironment.getProviderInitializationTimeoutFuzz());
                // This sets up an empty suggestion to trigger if no explicit "certain" or
                // "uncertain" suggestion preempts it within initializationTimeout. If, for some
                // reason, the provider does not produce any events then this scheduled suggestion
                // will ensure the controller makes at least an "uncertain" suggestion.
                suggestDelayed(createEmptySuggestion("No event received from provider in"
                                + " initializationTimeout=" + initializationTimeout),
                        initializationTimeout);
            }
        } else {
            // Clear any queued suggestions.
            clearDelayedSuggestion();

            // If the provider is now not enabled, and a previous "certain" suggestion has been
            // made, then a new "uncertain" suggestion must be made to indicate the provider no
            // longer has an opinion and will not be sending updates.
            if (mLastSuggestion != null && mLastSuggestion.getZoneIds() != null) {
                suggestImmediate(createEmptySuggestion(
                        "Provider disabled, clearing previous suggestion"));
            }
        }
    }

    void onProviderStateChange(@NonNull ProviderState providerState) {
        mThreadingDomain.assertCurrentThread();
        assertProviderKnown(providerState.provider);

        synchronized (mSharedLock) {
            switch (providerState.stateEnum) {
                case PROVIDER_STATE_DISABLED: {
                    // This should never happen: entering disabled does not trigger an event.
                    warnLog("onProviderStateChange: Unexpected state change for disabled provider,"
                            + " providerState=" + providerState);
                    break;
                }
                case PROVIDER_STATE_ENABLED: {
                    // Entering enabled does not trigger an event, so this only happens if an event
                    // is received while the provider is enabled.
                    debugLog("onProviderStateChange: Received notification of an event while"
                            + " enabled, providerState=" + providerState);
                    providerEnabledProcessEvent(providerState);
                    break;
                }
                case PROVIDER_STATE_PERM_FAILED: {
                    debugLog("Received notification of permanent failure for"
                            + " provider=" + providerState);
                    GeolocationTimeZoneSuggestion suggestion = createEmptySuggestion(
                            "provider=" + providerState.provider
                                    + " permanently failed: " + providerState);
                    suggestImmediate(suggestion);
                    break;
                }
                default: {
                    warnLog("onProviderStateChange: Unexpected providerState=" + providerState);
                }
            }
        }
    }

    private void assertProviderKnown(LocationTimeZoneProvider provider) {
        if (provider != mProvider) {
            throw new IllegalArgumentException("Unknown provider: " + provider);
        }
    }

    /**
     * Called when a provider has changed state but just moved from a PROVIDER_STATE_ENABLED state
     * to another PROVIDER_STATE_ENABLED state, usually as a result of a new {@link
     * LocationTimeZoneEvent} being received. There are some cases where event can be null.
     */
    private void providerEnabledProcessEvent(@NonNull ProviderState providerState) {
        LocationTimeZoneEvent event = providerState.event;
        if (event == null) {
            // Implicit uncertainty, i.e. where the provider is enabled, but a problem has been
            // detected without having received an event. For example, if the process has detected
            // the loss of a binder-based provider. This is treated like explicit uncertainty, i.e.
            // where the provider has explicitly told this process it is uncertain.
            scheduleUncertainSuggestionIfNeeded(null);
            return;
        }

        // Consistency check for user. This may be possible as there are various races around
        // current user switches.
        if (!Objects.equals(event.getUserHandle(), mCurrentUserConfiguration.getUserHandle())) {
            warnLog("Using event=" + event + " from a different user="
                    + mCurrentUserConfiguration);
        }

        if (!mCurrentUserConfiguration.getGeoDetectionEnabledBehavior()) {
            // This should not happen: the provider should not be in an enabled state if the user
            // does not have geodetection enabled.
            warnLog("Provider=" + providerState + " is enabled, but currentUserConfiguration="
                    + mCurrentUserConfiguration + " suggests it shouldn't be.");
        }

        switch (event.getEventType()) {
            case EVENT_TYPE_PERMANENT_FAILURE: {
                // This shouldn't happen. Providers cannot be enabled and have this event.
                warnLog("Provider=" + providerState
                        + " is enabled, but event suggests it shouldn't be");
                break;
            }
            case EVENT_TYPE_UNCERTAIN: {
                scheduleUncertainSuggestionIfNeeded(event);
                break;
            }
            case EVENT_TYPE_SUCCESS: {
                GeolocationTimeZoneSuggestion suggestion =
                        new GeolocationTimeZoneSuggestion(event.getTimeZoneIds());
                suggestion.addDebugInfo("Event received provider=" + mProvider.getName()
                        + ", event=" + event);
                // Rely on the receiver to dedupe events. It is better to over-communicate.
                suggestImmediate(suggestion);
                break;
            }
            default: {
                warnLog("Unknown eventType=" + event.getEventType());
                break;
            }
        }
    }

    /**
     * Indicates a provider has become uncertain with the event (if any) received that indicates
     * that.
     *
     * <p>Providers are expected to report their uncertainty as soon as they become uncertain, as
     * this enables the most flexibility for the controller to enable other providers when there are
     * multiple ones available. The controller is therefore responsible for deciding when to make a
     * "uncertain" suggestion.
     *
     * <p>This method schedules an "uncertain" suggestion (if one isn't already scheduled) to be
     * made later if nothing else preempts it. It can be preempted if the provider becomes certain
     * (or does anything else that calls {@link #suggestImmediate(GeolocationTimeZoneSuggestion)})
     * within {@link Environment#getUncertaintyDelay()}. Preemption causes the scheduled
     * "uncertain" event to be cancelled. If the provider repeatedly sends uncertainty events within
     * the uncertainty delay period, those events are effectively ignored (i.e. the timer is not
     * reset each time).
     */
    private void scheduleUncertainSuggestionIfNeeded(@Nullable LocationTimeZoneEvent event) {
        if (mPendingSuggestion == null || mPendingSuggestion.getZoneIds() != null) {
            GeolocationTimeZoneSuggestion suggestion = createEmptySuggestion(
                    "provider=" + mProvider + " became uncertain, event=" + event);
            // Only send the empty suggestion after the uncertainty delay.
            suggestDelayed(suggestion, mEnvironment.getUncertaintyDelay());
        }
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
            ipw.println("mPendingSuggestion=" + mPendingSuggestion);
            ipw.println("mLastSuggestion=" + mLastSuggestion);

            ipw.println("Provider:");
            ipw.increaseIndent(); // level 2
            mProvider.dump(ipw, args);
            ipw.decreaseIndent(); // level 2

            ipw.decreaseIndent(); // level 1
        }
    }

    /** Sends an immediate suggestion, cancelling any pending suggestion. */
    @GuardedBy("mSharedLock")
    private void suggestImmediate(@NonNull GeolocationTimeZoneSuggestion suggestion) {
        debugLog("suggestImmediate: Executing suggestion=" + suggestion);
        mDelayedSuggestionQueue.runSynchronously(() -> mCallback.suggest(suggestion));
        mPendingSuggestion = null;
        mLastSuggestion = suggestion;
    }

    /** Clears any pending suggestion. */
    @GuardedBy("mSharedLock")
    private void clearDelayedSuggestion() {
        mDelayedSuggestionQueue.cancel();
        mPendingSuggestion = null;
    }


    /**
     * Schedules a delayed suggestion. There can only be one delayed suggestion at a time.
     * If there is a pending scheduled suggestion equal to the one passed, it will not be replaced.
     * Replacing a previous delayed suggestion has the effect of cancelling the timeout associated
     * with that previous suggestion.
     */
    @GuardedBy("mSharedLock")
    private void suggestDelayed(@NonNull GeolocationTimeZoneSuggestion suggestion,
            @NonNull Duration delay) {
        Objects.requireNonNull(suggestion);
        Objects.requireNonNull(delay);

        if (Objects.equals(mPendingSuggestion, suggestion)) {
            // Do not reset the timer.
            debugLog("suggestDelayed: Suggestion=" + suggestion + " is equal to existing."
                    + " Not scheduled.");
            return;
        }

        debugLog("suggestDelayed: Scheduling suggestion=" + suggestion);
        mPendingSuggestion = suggestion;

        mDelayedSuggestionQueue.runDelayed(() -> {
            debugLog("suggestDelayed: Executing suggestion=" + suggestion);
            mCallback.suggest(suggestion);
            mPendingSuggestion = null;
            mLastSuggestion = suggestion;
        }, delay.toMillis());
    }

    private static GeolocationTimeZoneSuggestion createEmptySuggestion(String reason) {
        GeolocationTimeZoneSuggestion suggestion = new GeolocationTimeZoneSuggestion(null);
        suggestion.addDebugInfo(reason);
        return suggestion;
    }

    /**
     * Asynchronously passes a {@link SimulatedBinderProviderEvent] to the appropriate provider.
     * If the provider name does not match a known provider, then the event is logged and discarded.
     */
    void simulateBinderProviderEvent(SimulatedBinderProviderEvent event) {
        if (!Objects.equals(mProvider.getName(), event.getProviderName())) {
            warnLog("Unable to process simulated binder provider event,"
                    + " unknown providerName in event=" + event);
            return;
        }
        if (!(mProvider instanceof BinderLocationTimeZoneProvider)) {
            warnLog("Unable to process simulated binder provider event,"
                    + " provider is not a " + BinderLocationTimeZoneProvider.class
                    + ", event=" + event);
            return;
        }
        ((BinderLocationTimeZoneProvider) mProvider).simulateBinderProviderEvent(event);
    }
}
