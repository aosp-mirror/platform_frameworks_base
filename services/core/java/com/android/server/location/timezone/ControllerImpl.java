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
import static com.android.server.location.timezone.LocationTimeZoneProvider.ProviderState.PROVIDER_STATE_ENABLED_CERTAIN;
import static com.android.server.location.timezone.LocationTimeZoneProvider.ProviderState.PROVIDER_STATE_ENABLED_INITIALIZING;
import static com.android.server.location.timezone.LocationTimeZoneProvider.ProviderState.PROVIDER_STATE_ENABLED_UNCERTAIN;
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
import java.util.List;
import java.util.Objects;

/**
 * A real implementation of {@link LocationTimeZoneProviderController} that supports a single
 * {@link LocationTimeZoneProvider}.
 *
 * TODO(b/152744911): This implementation currently only supports a single ("primary") provider.
 *  Support for a secondary provider will be added in a later commit.
 */
class ControllerImpl extends LocationTimeZoneProviderController {

    @NonNull private final LocationTimeZoneProvider mPrimaryProvider;

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
     * Used for scheduling uncertainty timeouts, i.e after the provider has reported uncertainty.
     */
    @NonNull private final SingleRunnableQueue mUncertaintyTimeoutQueue;

    /** Contains the last suggestion actually made, if there is one. */
    @GuardedBy("mSharedLock")
    @Nullable
    private GeolocationTimeZoneSuggestion mLastSuggestion;

    ControllerImpl(@NonNull ThreadingDomain threadingDomain,
            @NonNull LocationTimeZoneProvider primaryProvider) {
        super(threadingDomain);
        mUncertaintyTimeoutQueue = threadingDomain.createSingleRunnableQueue();
        mPrimaryProvider = Objects.requireNonNull(primaryProvider);
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

            alterProviderEnabledStateIfRequired(
                    null /* oldConfiguration */, mCurrentUserConfiguration);
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
                            + ", new=" + newConfig.getUserId() + ": Disabling provider");
                    disableProvider();

                    alterProviderEnabledStateIfRequired(null /* oldConfiguration */, newConfig);
                } else {
                    alterProviderEnabledStateIfRequired(oldConfig, newConfig);
                }
            }
        }
    }

    @Override
    boolean isUncertaintyTimeoutSet() {
        return mUncertaintyTimeoutQueue.hasQueued();
    }

    @Override
    long getUncertaintyTimeoutDelayMillis() {
        return mUncertaintyTimeoutQueue.getQueuedDelayMillis();
    }

    @GuardedBy("mSharedLock")
    private void disableProvider() {
        disableProviderIfEnabled(mPrimaryProvider);

        // By definition, if the provider is disabled, the controller is uncertain.
        cancelUncertaintyTimeout();
    }

    @GuardedBy("mSharedLock")
    private void disableProviderIfEnabled(LocationTimeZoneProvider provider) {
        if (provider.getCurrentState().isEnabled()) {
            disableProvider(provider);
        }
    }

    @GuardedBy("mSharedLock")
    private void disableProvider(LocationTimeZoneProvider provider) {
        ProviderState providerState = provider.getCurrentState();
        switch (providerState.stateEnum) {
            case PROVIDER_STATE_DISABLED: {
                debugLog("No need to disable " + provider + ": already disabled");
                break;
            }
            case PROVIDER_STATE_ENABLED_INITIALIZING:
            case PROVIDER_STATE_ENABLED_CERTAIN:
            case PROVIDER_STATE_ENABLED_UNCERTAIN: {
                debugLog("Disabling " + provider);
                provider.disable();
                break;
            }
            case PROVIDER_STATE_PERM_FAILED: {
                debugLog("Unable to disable " + provider + ": it is perm failed");
                break;
            }
            default: {
                warnLog("Unknown provider state: " + provider);
                break;
            }
        }
    }

    /**
     * Sets the provider into the correct enabled/disabled state for the {@code newConfiguration}
     * and, if there is a provider state change, makes any suggestions required to inform the
     * downstream time zone detection code.
     *
     * <p>This is a utility method that exists to avoid duplicated logic for the various cases when
     * provider enabled / disabled state may need to be set or changed, e.g. during initialization
     * or when a new configuration has been received.
     */
    @GuardedBy("mSharedLock")
    private void alterProviderEnabledStateIfRequired(
            @Nullable ConfigurationInternal oldConfiguration,
            @NonNull ConfigurationInternal newConfiguration) {

        // Provider enabled / disabled states only need to be changed if geoDetectionEnabled has
        // changed.
        boolean oldGeoDetectionEnabled = oldConfiguration != null
                && oldConfiguration.getGeoDetectionEnabledBehavior();
        boolean newGeoDetectionEnabled = newConfiguration.getGeoDetectionEnabledBehavior();
        if (oldGeoDetectionEnabled == newGeoDetectionEnabled) {
            return;
        }

        if (newGeoDetectionEnabled) {
            // Try to enable the primary provider.
            tryEnableProvider(mPrimaryProvider, newConfiguration);

            ProviderState newPrimaryState = mPrimaryProvider.getCurrentState();
            if (!newPrimaryState.isEnabled()) {
                // If the provider is perm failed then the controller is immediately considered
                // uncertain.
                GeolocationTimeZoneSuggestion suggestion = createUncertainSuggestion(
                        "Provider is failed:"
                                + " primary=" + mPrimaryProvider.getCurrentState());
                makeSuggestion(suggestion);
            }
        } else {
            disableProvider();

            // There can be an uncertainty timeout set if the controller most recently received
            // an uncertain event. This is a no-op if there isn't a timeout set.
            cancelUncertaintyTimeout();

            // If a previous "certain" suggestion has been made, then a new "uncertain"
            // suggestion must now be made to indicate the controller {does not / no longer has}
            // an opinion and will not be sending further updates (until at least the config
            // changes again and providers are re-enabled).
            if (mLastSuggestion != null && mLastSuggestion.getZoneIds() != null) {
                GeolocationTimeZoneSuggestion suggestion = createUncertainSuggestion(
                        "Provider is disabled:"
                                + " primary=" + mPrimaryProvider.getCurrentState());
                makeSuggestion(suggestion);
            }
        }
    }

    private void tryEnableProvider(@NonNull LocationTimeZoneProvider provider,
            @NonNull ConfigurationInternal configuration) {
        ProviderState providerState = provider.getCurrentState();
        switch (providerState.stateEnum) {
            case PROVIDER_STATE_DISABLED: {
                debugLog("Enabling " + provider);
                provider.enable(configuration, mEnvironment.getProviderInitializationTimeout(),
                        mEnvironment.getProviderInitializationTimeoutFuzz());
                break;
            }
            case PROVIDER_STATE_ENABLED_INITIALIZING:
            case PROVIDER_STATE_ENABLED_CERTAIN:
            case PROVIDER_STATE_ENABLED_UNCERTAIN: {
                debugLog("No need to enable " + provider + ": already enabled");
                break;
            }
            case PROVIDER_STATE_PERM_FAILED: {
                debugLog("Unable to enable " + provider + ": it is perm failed");
                break;
            }
            default: {
                throw new IllegalStateException("Unknown provider state:"
                        + " provider=" + provider
                        + ", state=" + providerState.stateEnum);
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
                case PROVIDER_STATE_ENABLED_INITIALIZING:
                case PROVIDER_STATE_ENABLED_CERTAIN:
                case PROVIDER_STATE_ENABLED_UNCERTAIN: {
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
                    providerFailedProcessEvent();
                    break;
                }
                default: {
                    warnLog("onProviderStateChange: Unexpected providerState=" + providerState);
                }
            }
        }
    }

    private void assertProviderKnown(LocationTimeZoneProvider provider) {
        if (provider != mPrimaryProvider) {
            throw new IllegalArgumentException("Unknown provider: " + provider);
        }
    }

    /**
     * Called when the provider has reported that it has failed permanently.
     */
    @GuardedBy("mSharedLock")
    private void providerFailedProcessEvent() {
        // If the provider is newly perm failed then the controller is uncertain by
        // definition.
        cancelUncertaintyTimeout();

        // If the provider is now failed, then we must send a suggestion informing the time
        // zone detector that there are no further updates coming in future.

        GeolocationTimeZoneSuggestion suggestion = createUncertainSuggestion(
                "The provider is permanently failed:"
                        + " primary=" + mPrimaryProvider.getCurrentState());
        makeSuggestion(suggestion);
    }

    /**
     * Called when a provider has changed state but just moved from one enabled state to another
     * enabled state, usually as a result of a new {@link LocationTimeZoneEvent} being received.
     * However, there are rare cases where the event can be null.
     */
    @GuardedBy("mSharedLock")
    private void providerEnabledProcessEvent(@NonNull ProviderState providerState) {
        LocationTimeZoneProvider provider = providerState.provider;
        LocationTimeZoneEvent event = providerState.event;
        if (event == null) {
            // Implicit uncertainty, i.e. where the provider is enabled, but a problem has been
            // detected without having received an event. For example, if the process has detected
            // the loss of a binder-based provider, or initialization took too long. This is treated
            // the same as explicit uncertainty, i.e. where the provider has explicitly told this
            // process it is uncertain.
            handleProviderUncertainty(provider, "provider=" + provider
                    + ", implicit uncertainty, event=null");
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
                // This shouldn't happen. A provider cannot be enabled and have this event.
                warnLog("Provider=" + providerState
                        + " is enabled, but event suggests it shouldn't be");
                break;
            }
            case EVENT_TYPE_UNCERTAIN: {
                handleProviderUncertainty(provider, "provider=" + provider
                        + ", explicit uncertainty. event=" + event);
                break;
            }
            case EVENT_TYPE_SUCCESS: {
                handleProviderCertainty(provider, event.getTimeZoneIds(),
                        "Event received provider=" + provider.getName() + ", event=" + event);
                break;
            }
            default: {
                warnLog("Unknown eventType=" + event.getEventType());
                break;
            }
        }
    }

    @GuardedBy("mSharedLock")
    private void handleProviderCertainty(
            @NonNull LocationTimeZoneProvider provider,
            @Nullable List<String> timeZoneIds,
            @NonNull String reason) {
        // By definition, the controller is now certain.
        cancelUncertaintyTimeout();

        GeolocationTimeZoneSuggestion suggestion =
                new GeolocationTimeZoneSuggestion(timeZoneIds);
        suggestion.addDebugInfo(reason);
        // Rely on the receiver to dedupe events. It is better to over-communicate.
        makeSuggestion(suggestion);
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
     * Indicates a provider has become uncertain with the event (if any) received that indicates
     * that.
     *
     * <p>A provider is expected to report its uncertainty as soon as it becomes uncertain, as
     * this enables the most flexibility for the controller to enable other providers when there are
     * multiple ones available. The controller is therefore responsible for deciding when to make a
     * "uncertain" suggestion.
     *
     * <p>This method schedules an "uncertain" suggestion (if one isn't already scheduled) to be
     * made later if nothing else preempts it. It can be preempted if the provider becomes certain
     * (or does anything else that calls {@link #makeSuggestion(GeolocationTimeZoneSuggestion)})
     * within {@link Environment#getUncertaintyDelay()}. Preemption causes the scheduled
     * "uncertain" event to be cancelled. If the provider repeatedly sends uncertainty events within
     * the uncertainty delay period, those events are effectively ignored (i.e. the timer is not
     * reset each time).
     */
    @GuardedBy("mSharedLock")
    void handleProviderUncertainty(@NonNull LocationTimeZoneProvider provider, String reason) {
        Objects.requireNonNull(provider);

        // Start the uncertainty timeout if needed.
        if (!mUncertaintyTimeoutQueue.hasQueued()) {
            debugLog("Starting uncertainty timeout: reason=" + reason);

            Duration delay = mEnvironment.getUncertaintyDelay();
            mUncertaintyTimeoutQueue.runDelayed(
                    this::onProviderUncertaintyTimeout, delay.toMillis());
        }
    }

    private void onProviderUncertaintyTimeout() {
        mThreadingDomain.assertCurrentThread();

        synchronized (mSharedLock) {
            GeolocationTimeZoneSuggestion suggestion = createUncertainSuggestion(
                    "Uncertainty timeout triggered:"
                            + " primary=" + mPrimaryProvider.getCurrentState());
            makeSuggestion(suggestion);
        }
    }

    private static GeolocationTimeZoneSuggestion createUncertainSuggestion(String reason) {
        GeolocationTimeZoneSuggestion suggestion = new GeolocationTimeZoneSuggestion(null);
        suggestion.addDebugInfo(reason);
        return suggestion;
    }

    /**
     * Asynchronously passes a {@link SimulatedBinderProviderEvent] to the appropriate provider.
     * If the provider name does not match a known provider, then the event is logged and discarded.
     */
    void simulateBinderProviderEvent(SimulatedBinderProviderEvent event) {
        String targetProviderName = event.getProviderName();
        LocationTimeZoneProvider targetProvider;
        if (Objects.equals(mPrimaryProvider.getName(), targetProviderName)) {
            targetProvider = mPrimaryProvider;
        } else {
            warnLog("Unable to process simulated binder provider event,"
                    + " unknown providerName in event=" + event);
            return;
        }
        if (!(targetProvider instanceof BinderLocationTimeZoneProvider)) {
            warnLog("Unable to process simulated binder provider event,"
                    + " provider=" + targetProvider
                    + " is not a " + BinderLocationTimeZoneProvider.class
                    + ", event=" + event);
            return;
        }
        ((BinderLocationTimeZoneProvider) targetProvider).simulateBinderProviderEvent(event);
    }
}
