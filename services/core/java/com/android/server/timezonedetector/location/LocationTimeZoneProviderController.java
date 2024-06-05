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
import android.annotation.StringDef;
import android.app.time.DetectorStatusTypes;
import android.app.time.DetectorStatusTypes.DetectionAlgorithmStatus;
import android.app.time.LocationTimeZoneAlgorithmStatus;
import android.app.time.LocationTimeZoneAlgorithmStatus.ProviderStatus;
import android.service.timezone.TimeZoneProviderEvent;
import android.service.timezone.TimeZoneProviderSuggestion;
import android.util.IndentingPrintWriter;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.annotations.VisibleForTesting;
import com.android.server.timezonedetector.ConfigurationInternal;
import com.android.server.timezonedetector.Dumpable;
import com.android.server.timezonedetector.GeolocationTimeZoneSuggestion;
import com.android.server.timezonedetector.LocationAlgorithmEvent;
import com.android.server.timezonedetector.ReferenceWithHistory;
import com.android.server.timezonedetector.location.ThreadingDomain.SingleRunnableQueue;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Objects;

/**
 * The component responsible handling events from {@link LocationTimeZoneProvider}s and synthesizing
 * time zone ID suggestions for sending to the time zone detector.
 *
 * <p>This class primarily exists to extract unit-testable logic from the surrounding service class,
 * i.e. with a minimal number of threading considerations or direct dependencies on Android
 * infrastructure.
 *
 * <p>This class supports a primary and a secondary {@link LocationTimeZoneProvider}. The primary is
 * used until it fails or becomes uncertain. The secondary will then be started. The controller will
 * immediately make suggestions based on "certain" {@link TimeZoneProviderEvent}s, i.e. events that
 * demonstrate the provider is certain what the time zone is. The controller will not make immediate
 * suggestions based on "uncertain" events, giving providers time to change their mind. This also
 * gives the secondary provider time to initialize when the primary becomes uncertain.
 *
 * <p>The controller interacts with the following components:
 * <ul>
 *     <li>The surrounding service, which calls {@link #initialize(Environment, Callback)}.
 *     <li>The {@link Environment} through which it obtains information it needs.</li>
 *     <li>The {@link Callback} through which it makes time zone suggestions.</li>
 *     <li>Any {@link LocationTimeZoneProvider} instances it owns, which communicate via the
 *     {@link LocationTimeZoneProvider.ProviderListener#onProviderStateChange(ProviderState)}
 *     method.</li>
 * </ul>
 *
 * <p>All incoming calls except for {@link
 * LocationTimeZoneProviderController#dump(android.util.IndentingPrintWriter, String[])} must be
 * made on the {@link android.os.Handler} thread of the {@link ThreadingDomain} passed to {@link
 * #LocationTimeZoneProviderController}.
 *
 * <p>Provider / controller integration notes:
 *
 * <p>Providers distinguish between "unknown unknowns" ("uncertain") and "known unknowns"
 * ("certain"), i.e. a provider can be uncertain and not know what the time zone is, which is
 * different from the certainty that there are no time zone IDs for the current location. A provider
 * can be certain about there being no time zone IDs for a location for good reason, e.g. for
 * disputed areas and oceans. Distinguishing uncertainty allows the controller to try other
 * providers (or give up), whereas certainty means it should not.
 *
 * <p>A provider can fail permanently. A permanent failure will stop the provider until next
 * boot.
 */
class LocationTimeZoneProviderController implements Dumpable {

    // String is used for easier logging / interpretation in bug reports Vs int.
    @StringDef(prefix = "STATE_",
            value = { STATE_UNKNOWN, STATE_PROVIDERS_INITIALIZING, STATE_STOPPED,
                    STATE_INITIALIZING, STATE_UNCERTAIN, STATE_CERTAIN, STATE_FAILED,
                    STATE_DESTROYED })
    @Retention(RetentionPolicy.SOURCE)
    @Target({ ElementType.TYPE_USE, ElementType.TYPE_PARAMETER })
    @interface State {}

    /** The state used for an uninitialized controller. */
    static final @State String STATE_UNKNOWN = "UNKNOWN";

    /**
     * A state used while the location time zone providers are initializing. Enables detection
     * / avoidance of unwanted fail-over behavior before both providers are initialized.
     */
    static final @State String STATE_PROVIDERS_INITIALIZING = "PROVIDERS_INITIALIZING";
    /** An inactive state: Detection is disabled. */
    static final @State String STATE_STOPPED = "STOPPED";
    /** An active state: No suggestion has yet been made. */
    static final @State String STATE_INITIALIZING = "INITIALIZING";
    /** An active state: The last suggestion was "uncertain". */
    static final @State String STATE_UNCERTAIN = "UNCERTAIN";
    /** An active state: The last suggestion was "certain". */
    static final @State String STATE_CERTAIN = "CERTAIN";
    /** An inactive state: The location time zone providers have failed. */
    static final @State String STATE_FAILED = "FAILED";
    /** An inactive state: The controller is destroyed. */
    static final @State String STATE_DESTROYED = "DESTROYED";

    @NonNull private final ThreadingDomain mThreadingDomain;
    @NonNull private final Object mSharedLock;
    /**
     * Used for scheduling uncertainty timeouts, i.e. after a provider has reported uncertainty.
     * This timeout is not provider-specific: it is started when the controller becomes uncertain
     * due to events it has received from one or other provider.
     */
    @NonNull private final SingleRunnableQueue mUncertaintyTimeoutQueue;

    @NonNull private final MetricsLogger mMetricsLogger;
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

    /** Usually {@code false} but can be set to {@code true} to record state changes for testing. */
    private final boolean mRecordStateChanges;

    @GuardedBy("mSharedLock")
    @NonNull
    private final ArrayList<@State String> mRecordedStates = new ArrayList<>(0);

    /**
     * The current state. This is primarily for metrics / reporting of how long the controller
     * spends active / inactive during a period. There is overlap with the provider states, but
     * providers operate independently of each other, so this can help to understand how long the
     * geo detection system overall was certain or uncertain when multiple providers might have been
     * enabled concurrently.
     */
    @GuardedBy("mSharedLock")
    private final ReferenceWithHistory<@State String> mState = new ReferenceWithHistory<>(10);

    /** Contains the last event reported, if there is one. */
    @GuardedBy("mSharedLock")
    @Nullable
    private LocationAlgorithmEvent mLastEvent;

    LocationTimeZoneProviderController(@NonNull ThreadingDomain threadingDomain,
            @NonNull MetricsLogger metricsLogger,
            @NonNull LocationTimeZoneProvider primaryProvider,
            @NonNull LocationTimeZoneProvider secondaryProvider,
            boolean recordStateChanges) {
        mThreadingDomain = Objects.requireNonNull(threadingDomain);
        mSharedLock = threadingDomain.getLockObject();
        mUncertaintyTimeoutQueue = threadingDomain.createSingleRunnableQueue();
        mMetricsLogger = Objects.requireNonNull(metricsLogger);
        mPrimaryProvider = Objects.requireNonNull(primaryProvider);
        mSecondaryProvider = Objects.requireNonNull(secondaryProvider);
        mRecordStateChanges = recordStateChanges;

        synchronized (mSharedLock) {
            mState.set(STATE_UNKNOWN);
        }
    }

    /**
     * Called to initialize the controller during boot. Called once only.
     * {@link LocationTimeZoneProvider#initialize} must be called by this method.
     */
    void initialize(@NonNull Environment environment, @NonNull Callback callback) {
        mThreadingDomain.assertCurrentThread();

        synchronized (mSharedLock) {
            debugLog("initialize()");
            mEnvironment = Objects.requireNonNull(environment);
            mCallback = Objects.requireNonNull(callback);
            mCurrentUserConfiguration = environment.getCurrentUserConfigurationInternal();

            LocationTimeZoneProvider.ProviderListener providerListener =
                    LocationTimeZoneProviderController.this::onProviderStateChange;
            setState(STATE_PROVIDERS_INITIALIZING);
            mPrimaryProvider.initialize(providerListener);
            mSecondaryProvider.initialize(providerListener);
            setStateAndReportStatusOnlyEvent(STATE_STOPPED, "initialize()");

            alterProvidersStartedStateIfRequired(
                    null /* oldConfiguration */, mCurrentUserConfiguration);
        }
    }

    /**
     * Called when the content of the {@link ConfigurationInternal} may have changed. The receiver
     * should call {@link Environment#getCurrentUserConfigurationInternal()} to get the current
     * user's config. This call must be made on the {@link ThreadingDomain} handler thread.
     */
    void onConfigurationInternalChanged() {
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
                    String reason = "User changed. old=" + oldConfig.getUserId()
                            + ", new=" + newConfig.getUserId();
                    debugLog("Stopping providers: " + reason);
                    stopProviders(reason);

                    alterProvidersStartedStateIfRequired(null /* oldConfiguration */, newConfig);
                } else {
                    alterProvidersStartedStateIfRequired(oldConfig, newConfig);
                }
            }
        }
    }

    @VisibleForTesting
    boolean isUncertaintyTimeoutSet() {
        return mUncertaintyTimeoutQueue.hasQueued();
    }

    @VisibleForTesting
    @DurationMillisLong
    long getUncertaintyTimeoutDelayMillis() {
        return mUncertaintyTimeoutQueue.getQueuedDelayMillis();
    }

    /** Called if the geolocation time zone detection is being reconfigured. */
    void destroy() {
        mThreadingDomain.assertCurrentThread();

        synchronized (mSharedLock) {
            stopProviders("destroy()");

            // Enter destroyed state.
            mPrimaryProvider.destroy();
            mSecondaryProvider.destroy();
            setStateAndReportStatusOnlyEvent(STATE_DESTROYED, "destroy()");
        }
    }

    /**
     * Sets the state and reports an event containing the algorithm status and a {@code null}
     * suggestion.
     */
    @GuardedBy("mSharedLock")
    private void setStateAndReportStatusOnlyEvent(@State String state, @NonNull String reason) {
        setState(state);

        final GeolocationTimeZoneSuggestion suggestion = null;
        LocationAlgorithmEvent event =
                new LocationAlgorithmEvent(generateCurrentAlgorithmStatus(), suggestion);
        event.addDebugInfo(reason);
        reportEvent(event);
    }

    /**
     * Reports an event containing the algorithm status and the supplied suggestion.
     */
    @GuardedBy("mSharedLock")
    private void reportSuggestionEvent(
            @NonNull GeolocationTimeZoneSuggestion suggestion, @NonNull String reason) {
        LocationTimeZoneAlgorithmStatus algorithmStatus = generateCurrentAlgorithmStatus();
        LocationAlgorithmEvent event = new LocationAlgorithmEvent(algorithmStatus, suggestion);
        event.addDebugInfo(reason);
        reportEvent(event);
    }

    /**
     * Sends an event immediately. This method updates {@link #mLastEvent}.
     */
    @GuardedBy("mSharedLock")
    private void reportEvent(@NonNull LocationAlgorithmEvent event) {
        debugLog("makeSuggestion: suggestion=" + event);
        mCallback.sendEvent(event);
        mLastEvent = event;
    }

    /**
     * Updates the state if needed. This includes setting {@link #mState} and performing all the
     * record-keeping / callbacks associated with state changes.
     */
    @GuardedBy("mSharedLock")
    private void setState(@State String state) {
        if (!Objects.equals(mState.get(), state)) {
            mState.set(state);
            if (mRecordStateChanges) {
                mRecordedStates.add(state);
            }
            mMetricsLogger.onStateChange(state);
        }
    }

    @GuardedBy("mSharedLock")
    private void stopProviders(@NonNull String reason) {
        stopProviderIfStarted(mPrimaryProvider);
        stopProviderIfStarted(mSecondaryProvider);

        // By definition, if both providers are stopped, the controller is uncertain.
        cancelUncertaintyTimeout();

        setStateAndReportStatusOnlyEvent(STATE_STOPPED, "Providers stopped: " + reason);
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
        boolean oldIsGeoDetectionExecutionEnabled = oldConfiguration != null
                && oldConfiguration.isGeoDetectionExecutionEnabled();
        boolean newIsGeoDetectionExecutionEnabled =
                newConfiguration.isGeoDetectionExecutionEnabled();
        if (oldIsGeoDetectionExecutionEnabled == newIsGeoDetectionExecutionEnabled) {
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

        if (newIsGeoDetectionExecutionEnabled) {
            setStateAndReportStatusOnlyEvent(STATE_INITIALIZING, "initializing()");

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
                    // reports the failure.
                    String reason = "Providers are failed:"
                            + " primary=" + mPrimaryProvider.getCurrentState()
                            + " secondary=" + mPrimaryProvider.getCurrentState();
                    setStateAndReportStatusOnlyEvent(STATE_FAILED, reason);
                }
            }
        } else {
            stopProviders("Geo detection behavior disabled");
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
            if (Objects.equals(mState.get(), STATE_PROVIDERS_INITIALIZING)) {
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
            // time zone detector that there are no further updates coming in the future.
            String reason = "Both providers are terminated:"
                    + " primary=" + primaryCurrentState.provider
                    + ", secondary=" + secondaryCurrentState.provider;
            setStateAndReportStatusOnlyEvent(STATE_FAILED, reason);
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

        if (!mCurrentUserConfiguration.isGeoDetectionExecutionEnabled()) {
            // This should not happen: the provider should not be in a started state if
            // geodetection is not enabled.
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

        // Set the current state so it is correct when the suggestion event is created.
        setState(STATE_CERTAIN);

        // For the suggestion's effectiveFromElapsedMillis, use the time embedded in the provider's
        // suggestion (which indicates the time when the provider detected the location used to
        // establish the time zone).
        //
        // An alternative would be to use the current time or the providerEvent creation time, but
        // this would hinder the ability for the time_zone_detector to judge which suggestions are
        // based on newer information when comparing suggestions between different sources.
        long effectiveFromElapsedMillis = providerSuggestion.getElapsedRealtimeMillis();
        GeolocationTimeZoneSuggestion suggestion =
                GeolocationTimeZoneSuggestion.createCertainSuggestion(
                        effectiveFromElapsedMillis, providerSuggestion.getTimeZoneIds());
        String debugInfo = "Provider event received: provider=" + provider
                + ", providerEvent=" + providerEvent
                + ", suggestionCreationTime=" + mEnvironment.elapsedRealtimeMillis();
        reportSuggestionEvent(suggestion, debugInfo);
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
            ipw.println("mState=" + mState.get());
            ipw.println("mLastEvent=" + mLastEvent);

            ipw.println("State history:");
            ipw.increaseIndent(); // level 2
            mState.dump(ipw);
            ipw.decreaseIndent(); // level 2

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

    /** Clears the uncertainty timeout. */
    @GuardedBy("mSharedLock")
    private void cancelUncertaintyTimeout() {
        mUncertaintyTimeoutQueue.cancel();
    }

    /**
     * Called when a provider has reported it is "uncertain" about the time zone.
     *
     * <p>A provider is expected to report its uncertainty as soon as it becomes uncertain, as
     * this enables the most flexibility for the controller to start other providers when there are
     * multiple ones available. The controller is therefore responsible for deciding when to pass
     * the "uncertain" suggestion to the downstream time zone detector.
     *
     * <p>This method schedules an "uncertainty" timeout (if one isn't already scheduled) to be
     * triggered later if nothing else preempts it. It can be preempted if the provider becomes
     * certain within {@link Environment#getUncertaintyDelay()}. Preemption causes the scheduled
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
            if (STATE_UNCERTAIN.equals(mState.get())) {
                // If the controller is already uncertain, there's no reason to start a timeout;
                // just forward the suggestion immediately to make it obvious in the logs what has
                // happened. Making a new suggestion potentially captures new LTZP status info.
                GeolocationTimeZoneSuggestion suggestion =
                        GeolocationTimeZoneSuggestion.createUncertainSuggestion(
                                uncertaintyStartedElapsedMillis);
                String debugInfo = "Uncertainty received from " + provider.getName() + ":"
                        + " primary=" + mPrimaryProvider
                        + ", secondary=" + mSecondaryProvider
                        + ", uncertaintyStarted="
                        + Duration.ofMillis(uncertaintyStartedElapsedMillis);
                reportSuggestionEvent(suggestion, debugInfo);
            } else {
                debugLog("Starting uncertainty timeout: reason=" + reason);

                Duration uncertaintyDelay = mEnvironment.getUncertaintyDelay();
                mUncertaintyTimeoutQueue.runDelayed(
                        () -> onProviderUncertaintyTimeout(
                                provider, uncertaintyStartedElapsedMillis, uncertaintyDelay),
                        uncertaintyDelay.toMillis());
            }
        }

        if (provider == mPrimaryProvider) {
            // (Try to) start the secondary. It could already be started, or enabling might not
            // succeed if the provider has previously reported it is perm failed. The uncertainty
            // timeout (may be set above) is used to ensure that an uncertain suggestion will be
            // made if the secondary cannot generate a success event in time.
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

            setState(STATE_UNCERTAIN);

            // For the effectiveFromElapsedMillis suggestion property, use the
            // uncertaintyStartedElapsedMillis. This is the time when the provider first reported
            // uncertainty, i.e. before the uncertainty timeout.
            //
            // afterUncertaintyTimeoutElapsedMillis could be used instead, which is the time when
            // the location_time_zone_manager finally confirms that the time zone was uncertain,
            // but the suggestion property allows the information to be back-dated, which should
            // help when comparing suggestions from different sources.
            GeolocationTimeZoneSuggestion suggestion =
                    GeolocationTimeZoneSuggestion.createUncertainSuggestion(
                            uncertaintyStartedElapsedMillis);
            String debugInfo = "Uncertainty timeout triggered for " + provider.getName() + ":"
                    + " primary=" + mPrimaryProvider
                    + ", secondary=" + mSecondaryProvider
                    + ", uncertaintyStarted="
                    + Duration.ofMillis(uncertaintyStartedElapsedMillis)
                    + ", afterUncertaintyTimeout="
                    + Duration.ofMillis(afterUncertaintyTimeoutElapsedMillis)
                    + ", uncertaintyDelay=" + uncertaintyDelay;
            reportSuggestionEvent(suggestion, debugInfo);
        }
    }

    @GuardedBy("mSharedLock")
    @NonNull
    private LocationTimeZoneAlgorithmStatus generateCurrentAlgorithmStatus() {
        @State String controllerState = mState.get();
        ProviderState primaryProviderState = mPrimaryProvider.getCurrentState();
        ProviderState secondaryProviderState = mSecondaryProvider.getCurrentState();
        return createAlgorithmStatus(controllerState, primaryProviderState, secondaryProviderState);
    }

    @NonNull
    private static LocationTimeZoneAlgorithmStatus createAlgorithmStatus(
            @NonNull @State String controllerState,
            @NonNull ProviderState primaryProviderState,
            @NonNull ProviderState secondaryProviderState) {

        @DetectionAlgorithmStatus int algorithmStatus =
                mapControllerStateToDetectionAlgorithmStatus(controllerState);
        @ProviderStatus int primaryProviderStatus = primaryProviderState.getProviderStatus();
        @ProviderStatus int secondaryProviderStatus = secondaryProviderState.getProviderStatus();

        // Neither provider is running. The algorithm is not running.
        return new LocationTimeZoneAlgorithmStatus(algorithmStatus,
                primaryProviderStatus, primaryProviderState.getReportedStatus(),
                secondaryProviderStatus, secondaryProviderState.getReportedStatus());
    }

    /**
     * Maps the internal state enum value to one of the status values exposed to the layers above.
     */
    private static @DetectionAlgorithmStatus int mapControllerStateToDetectionAlgorithmStatus(
            @NonNull @State String controllerState) {
        switch (controllerState) {
            case STATE_INITIALIZING:
            case STATE_PROVIDERS_INITIALIZING:
            case STATE_CERTAIN:
            case STATE_UNCERTAIN:
                return DetectorStatusTypes.DETECTION_ALGORITHM_STATUS_RUNNING;
            case STATE_STOPPED:
            case STATE_DESTROYED:
            case STATE_FAILED:
            case STATE_UNKNOWN:
            default:
                return DetectorStatusTypes.DETECTION_ALGORITHM_STATUS_NOT_RUNNING;
        }
    }

    /**
     * Clears recorded controller and provider state changes (for use during tests).
     */
    void clearRecordedStates() {
        mThreadingDomain.assertCurrentThread();

        synchronized (mSharedLock) {
            mRecordedStates.clear();
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
            if (mLastEvent != null) {
                builder.setLastEvent(mLastEvent);
            }
            builder.setControllerState(mState.get())
                    .setStateChanges(mRecordedStates)
                    .setPrimaryProviderStateChanges(mPrimaryProvider.getRecordedStates())
                    .setSecondaryProviderStateChanges(mSecondaryProvider.getRecordedStates());
            return builder.build();
        }
    }

    /**
     * Used by {@link LocationTimeZoneProviderController} to obtain information from the surrounding
     * service. It can easily be faked for tests.
     */
    abstract static class Environment {

        @NonNull protected final ThreadingDomain mThreadingDomain;
        @NonNull protected final Object mSharedLock;

        Environment(@NonNull ThreadingDomain threadingDomain) {
            mThreadingDomain = Objects.requireNonNull(threadingDomain);
            mSharedLock = threadingDomain.getLockObject();
        }

        /** Destroys the environment, i.e. deregisters listeners, etc. */
        abstract void destroy();

        /** Returns the {@link ConfigurationInternal} for the current user of the device. */
        abstract ConfigurationInternal getCurrentUserConfigurationInternal();

        /**
         * Returns the value passed to LocationTimeZoneProviders informing them of how long they
         * have to return their first time zone suggestion.
         */
        abstract Duration getProviderInitializationTimeout();

        /**
         * Returns the extra time granted on top of {@link #getProviderInitializationTimeout()} to
         * allow for slop like communication delays.
         */
        abstract Duration getProviderInitializationTimeoutFuzz();

        /**
         * Returns the value passed to LocationTimeZoneProviders to control rate limiting of
         * equivalent events.
         */
        abstract Duration getProviderEventFilteringAgeThreshold();

        /**
         * Returns the delay allowed after receiving uncertainty from a provider before it should be
         * passed on.
         */
        abstract Duration getUncertaintyDelay();

        /**
         * Returns the elapsed realtime as millis, the same as {@link
         * android.os.SystemClock#elapsedRealtime()}.
         */
        abstract @ElapsedRealtimeLong long elapsedRealtimeMillis();
    }

    /**
     * Used by {@link LocationTimeZoneProviderController} to interact with the surrounding service.
     * It can easily be faked for tests.
     */
    abstract static class Callback {

        @NonNull protected final ThreadingDomain mThreadingDomain;

        Callback(@NonNull ThreadingDomain threadingDomain) {
            mThreadingDomain = Objects.requireNonNull(threadingDomain);
        }

        /**
         * Suggests the latest time zone state for the device.
         */
        abstract void sendEvent(@NonNull LocationAlgorithmEvent event);
    }

    /**
     * Used by {@link LocationTimeZoneProviderController} to record events for metrics / telemetry.
     */
    interface MetricsLogger {
        /** Called when the controller's state changes. */
        void onStateChange(@State String stateEnum);
    }
}
