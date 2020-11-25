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

import static com.android.internal.location.timezone.LocationTimeZoneEvent.EVENT_TYPE_PERMANENT_FAILURE;
import static com.android.internal.location.timezone.LocationTimeZoneEvent.EVENT_TYPE_SUCCESS;
import static com.android.internal.location.timezone.LocationTimeZoneEvent.EVENT_TYPE_UNCERTAIN;
import static com.android.server.location.timezone.LocationTimeZoneManagerService.debugLog;
import static com.android.server.location.timezone.LocationTimeZoneProvider.ProviderState.PROVIDER_STATE_DISABLED;
import static com.android.server.location.timezone.LocationTimeZoneProvider.ProviderState.PROVIDER_STATE_ENABLED_CERTAIN;
import static com.android.server.location.timezone.LocationTimeZoneProvider.ProviderState.PROVIDER_STATE_ENABLED_INITIALIZING;
import static com.android.server.location.timezone.LocationTimeZoneProvider.ProviderState.PROVIDER_STATE_ENABLED_UNCERTAIN;
import static com.android.server.location.timezone.LocationTimeZoneProvider.ProviderState.PROVIDER_STATE_PERM_FAILED;

import android.annotation.IntDef;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.os.Handler;
import android.os.SystemClock;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.location.timezone.LocationTimeZoneEvent;
import com.android.server.location.timezone.LocationTimeZoneProvider.ProviderState.ProviderStateEnum;
import com.android.server.location.timezone.ThreadingDomain.SingleRunnableQueue;
import com.android.server.timezonedetector.ConfigurationInternal;
import com.android.server.timezonedetector.Dumpable;
import com.android.server.timezonedetector.ReferenceWithHistory;

import java.time.Duration;
import java.util.Objects;

/**
 * A facade used by the {@link LocationTimeZoneProviderController} to interact with a location time
 * zone provider. The provider could have a binder implementation with logic running in another
 * process, or could be a stubbed instance when no real provider is registered.
 *
 * <p>The provider is supplied with a {@link ProviderListener} via {@link
 * #initialize(ProviderListener)}. This enables it to communicates asynchronous detection / error
 * events back to the {@link LocationTimeZoneProviderController} via the {@link
 * ProviderListener#onProviderStateChange} method. This call must be made on the
 * {@link Handler} thread from the {@link ThreadingDomain} passed to the constructor.
 *
 * <p>All incoming calls from the controller except for {@link
 * LocationTimeZoneProvider#dump(android.util.IndentingPrintWriter, String[])} will be made on the
 * {@link Handler} thread of the {@link ThreadingDomain} passed to the constructor.
 */
abstract class LocationTimeZoneProvider implements Dumpable {

    /**
     * Listener interface used by the {@link LocationTimeZoneProviderController} to register an
     * interest in provider events.
     */
    interface ProviderListener {
        /**
         * Indicated that a provider changed states. The {@code providerState} indicates which one
         */
        void onProviderStateChange(@NonNull ProviderState providerState);
    }

    /**
     * Information about the provider's current state.
     */
    static class ProviderState {

        @IntDef({ PROVIDER_STATE_UNKNOWN, PROVIDER_STATE_ENABLED_INITIALIZING,
                PROVIDER_STATE_ENABLED_CERTAIN, PROVIDER_STATE_ENABLED_UNCERTAIN,
                PROVIDER_STATE_DISABLED, PROVIDER_STATE_PERM_FAILED })
        @interface ProviderStateEnum {}

        /**
         * Uninitialized value. Must not be used afte {@link LocationTimeZoneProvider#initialize}.
         */
        static final int PROVIDER_STATE_UNKNOWN = 0;

        /**
         * The provider is enabled and has not reported its first event.
         */
        static final int PROVIDER_STATE_ENABLED_INITIALIZING = 1;

        /**
         * The provider is enabled and most recently reported a "success" event.
         */
        static final int PROVIDER_STATE_ENABLED_CERTAIN = 2;

        /**
         * The provider is enabled and most recently reported an "uncertain" event.
         */
        static final int PROVIDER_STATE_ENABLED_UNCERTAIN = 3;

        /**
         * The provider is disabled.
         *
         * This is the state after {@link #initialize} is called.
         */
        static final int PROVIDER_STATE_DISABLED = 4;

        /**
         * The provider has failed and cannot be re-enabled.
         *
         * Providers may enter this state after a provider is enabled.
         */
        static final int PROVIDER_STATE_PERM_FAILED = 5;

        /** The {@link LocationTimeZoneProvider} the state is for. */
        public final @NonNull LocationTimeZoneProvider provider;

        /** The state enum value of the current state. */
        public final @ProviderStateEnum int stateEnum;

        /**
         * The last {@link LocationTimeZoneEvent} received. Only populated when {@link #stateEnum}
         * is either {@link #PROVIDER_STATE_ENABLED_CERTAIN} or {@link
         * #PROVIDER_STATE_ENABLED_UNCERTAIN}, but it can be {@code null} then too if no event has
         * yet been received.
         */
        @Nullable public final LocationTimeZoneEvent event;

        /**
         * The user configuration associated with the current state. Only and always present when
         * {@link #stateEnum} is one of the enabled states.
         */
        @Nullable public final ConfigurationInternal currentUserConfiguration;

        /**
         * The time according to the elapsed realtime clock when the provider entered the current
         * state. Included for debugging, not used for equality.
         */
        private final long mStateEntryTimeMillis;

        /**
         * Debug information providing context for the transition to this state. Included for
         * debugging, not used for equality.
         */
        @Nullable private final String mDebugInfo;


        private ProviderState(@NonNull LocationTimeZoneProvider provider,
                @ProviderStateEnum int stateEnum,
                @Nullable LocationTimeZoneEvent event,
                @Nullable ConfigurationInternal currentUserConfiguration,
                @Nullable String debugInfo) {
            this.provider = Objects.requireNonNull(provider);
            this.stateEnum = stateEnum;
            this.event = event;
            this.currentUserConfiguration = currentUserConfiguration;
            this.mStateEntryTimeMillis = SystemClock.elapsedRealtime();
            this.mDebugInfo = debugInfo;
        }

        /** Creates the bootstrap state, uses {@link #PROVIDER_STATE_UNKNOWN}. */
        static ProviderState createStartingState(
                @NonNull LocationTimeZoneProvider provider) {
            return new ProviderState(
                    provider, PROVIDER_STATE_UNKNOWN, null, null, "Initial state");
        }

        /**
         * Create a new state from this state. Validates that the state transition is valid
         * and that the required parameters for the new state are present / absent.
         */
        ProviderState newState(@ProviderStateEnum int newStateEnum,
                @Nullable LocationTimeZoneEvent event,
                @Nullable ConfigurationInternal currentUserConfig,
                @Nullable String debugInfo) {

            // Check valid "from" transitions.
            switch (this.stateEnum) {
                case PROVIDER_STATE_UNKNOWN: {
                    if (newStateEnum != PROVIDER_STATE_DISABLED) {
                        throw new IllegalArgumentException(
                                "Must transition from " + prettyPrintStateEnum(
                                        PROVIDER_STATE_UNKNOWN)
                                        + " to " + prettyPrintStateEnum(PROVIDER_STATE_DISABLED));
                    }
                    break;
                }
                case PROVIDER_STATE_DISABLED:
                case PROVIDER_STATE_ENABLED_INITIALIZING:
                case PROVIDER_STATE_ENABLED_CERTAIN:
                case PROVIDER_STATE_ENABLED_UNCERTAIN: {
                    // These can go to each other or PROVIDER_STATE_PERM_FAILED.
                    break;
                }
                case PROVIDER_STATE_PERM_FAILED: {
                    throw new IllegalArgumentException("Illegal transition out of "
                            + prettyPrintStateEnum(PROVIDER_STATE_UNKNOWN));
                }
                default: {
                    throw new IllegalArgumentException("Invalid this.stateEnum=" + this.stateEnum);
                }
            }

            // Validate "to" transitions / arguments.
            switch (newStateEnum) {
                case PROVIDER_STATE_UNKNOWN: {
                    throw new IllegalArgumentException("Cannot transition to "
                            + prettyPrintStateEnum(PROVIDER_STATE_UNKNOWN));
                }
                case PROVIDER_STATE_DISABLED: {
                    if (event != null || currentUserConfig != null) {
                        throw new IllegalArgumentException(
                                "Disabled state: event and currentUserConfig must be null"
                                        + ", event=" + event
                                        + ", currentUserConfig=" + currentUserConfig);
                    }
                    break;
                }
                case PROVIDER_STATE_ENABLED_INITIALIZING:
                case PROVIDER_STATE_ENABLED_CERTAIN:
                case PROVIDER_STATE_ENABLED_UNCERTAIN: {
                    if (currentUserConfig == null) {
                        throw new IllegalArgumentException(
                                "Enabled state: currentUserConfig must not be null");
                    }
                    break;
                }
                case PROVIDER_STATE_PERM_FAILED: {
                    if (event != null || currentUserConfig != null) {
                        throw new IllegalArgumentException(
                                "Perf failed state: event and currentUserConfig must be null"
                                        + ", event=" + event
                                        + ", currentUserConfig=" + currentUserConfig);
                    }
                    break;
                }
                default: {
                    throw new IllegalArgumentException("Unknown newStateEnum=" + newStateEnum);
                }
            }
            return new ProviderState(provider, newStateEnum, event, currentUserConfig, debugInfo);
        }

        /** Returns {@code true} if {@link #stateEnum} is one of the enabled states. */
        boolean isEnabled() {
            return stateEnum == PROVIDER_STATE_ENABLED_INITIALIZING
                    || stateEnum == PROVIDER_STATE_ENABLED_CERTAIN
                    || stateEnum == PROVIDER_STATE_ENABLED_UNCERTAIN;
        }

        @Override
        public String toString() {
            // this.provider is omitted deliberately to avoid recursion, since the provider holds
            // a reference to its state.
            return "ProviderState{"
                    + "stateEnum=" + prettyPrintStateEnum(stateEnum)
                    + ", event=" + event
                    + ", currentUserConfiguration=" + currentUserConfiguration
                    + ", mStateEntryTimeMillis=" + mStateEntryTimeMillis
                    + ", mDebugInfo=" + mDebugInfo
                    + '}';
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) {
                return true;
            }
            if (o == null || getClass() != o.getClass()) {
                return false;
            }
            ProviderState state = (ProviderState) o;
            return stateEnum == state.stateEnum
                    && Objects.equals(event, state.event)
                    && Objects.equals(currentUserConfiguration, state.currentUserConfiguration);
        }

        @Override
        public int hashCode() {
            return Objects.hash(stateEnum, event, currentUserConfiguration);
        }

        private static String prettyPrintStateEnum(@ProviderStateEnum int state) {
            switch (state) {
                case PROVIDER_STATE_DISABLED:
                    return "Disabled (" + PROVIDER_STATE_DISABLED + ")";
                case PROVIDER_STATE_ENABLED_INITIALIZING:
                    return "Enabled initializing (" + PROVIDER_STATE_ENABLED_INITIALIZING + ")";
                case PROVIDER_STATE_ENABLED_CERTAIN:
                    return "Enabled certain (" + PROVIDER_STATE_ENABLED_CERTAIN + ")";
                case PROVIDER_STATE_ENABLED_UNCERTAIN:
                    return "Enabled uncertain (" + PROVIDER_STATE_ENABLED_UNCERTAIN + ")";
                case PROVIDER_STATE_PERM_FAILED:
                    return "Perm failure (" + PROVIDER_STATE_PERM_FAILED + ")";
                case PROVIDER_STATE_UNKNOWN:
                default:
                    return "Unknown (" + state + ")";
            }
        }
    }

    @NonNull final ThreadingDomain mThreadingDomain;
    @NonNull final Object mSharedLock;
    @NonNull final String mProviderName;

    /**
     * The current state (with history for debugging).
     */
    @GuardedBy("mSharedLock")
    final ReferenceWithHistory<ProviderState> mCurrentState =
            new ReferenceWithHistory<>(10);

    /**
     * Used for scheduling initialization timeouts, i.e. for providers that have just been enabled.
     */
    @NonNull private final SingleRunnableQueue mInitializationTimeoutQueue;

    // Non-null and effectively final after initialize() is called.
    ProviderListener mProviderListener;

    /** Creates the instance. */
    LocationTimeZoneProvider(@NonNull ThreadingDomain threadingDomain,
            @NonNull String providerName) {
        mThreadingDomain = Objects.requireNonNull(threadingDomain);
        mInitializationTimeoutQueue = threadingDomain.createSingleRunnableQueue();
        mSharedLock = threadingDomain.getLockObject();
        mProviderName = Objects.requireNonNull(providerName);
    }

    /**
     * Called before the provider is first used.
     */
    final void initialize(@NonNull ProviderListener providerListener) {
        mThreadingDomain.assertCurrentThread();

        synchronized (mSharedLock) {
            if (mProviderListener != null) {
                throw new IllegalStateException("initialize already called");
            }
            mProviderListener = Objects.requireNonNull(providerListener);
            ProviderState currentState = ProviderState.createStartingState(this);
            ProviderState newState = currentState.newState(
                    PROVIDER_STATE_DISABLED, null, null,
                    "initialize() called");
            setCurrentState(newState, false);

            onInitialize();
        }
    }

    /**
     * Implemented by subclasses to do work during {@link #initialize}.
     */
    abstract void onInitialize();

    /**
     * Set the current state, for use by this class and subclasses only. If {@code #notifyChanges}
     * is {@code true} and {@code newState} is not equal to the old state, then {@link
     * ProviderListener#onProviderStateChange(ProviderState)} must be called on
     * {@link #mProviderListener}.
     */
    final void setCurrentState(@NonNull ProviderState newState, boolean notifyChanges) {
        mThreadingDomain.assertCurrentThread();
        synchronized (mSharedLock) {
            ProviderState oldState = mCurrentState.get();
            mCurrentState.set(newState);
            onSetCurrentState(newState);
            if (notifyChanges) {
                if (!Objects.equals(newState, oldState)) {
                    mProviderListener.onProviderStateChange(newState);
                }
            }
        }
    }

    /**
     * Overridden by subclasses to do work during {@link #setCurrentState}.
     */
    @GuardedBy("mSharedLock")
    void onSetCurrentState(ProviderState newState) {
        // Default no-op.
    }

    /**
     * Returns the current state of the provider. This method must be called using the handler
     * thread from the {@link ThreadingDomain}.
     */
    @NonNull
    final ProviderState getCurrentState() {
        mThreadingDomain.assertCurrentThread();
        synchronized (mSharedLock) {
            return mCurrentState.get();
        }
    }

    /**
     * Returns the name of the provider. This method must be called using the handler thread from
     * the {@link ThreadingDomain}.
     */
    final String getName() {
        mThreadingDomain.assertCurrentThread();
        return mProviderName;
    }

    /**
     * Enables the provider. It is an error to call this method except when the {@link
     * #getCurrentState()} is at {@link ProviderState#PROVIDER_STATE_DISABLED}. This method must be
     * called using the handler thread from the {@link ThreadingDomain}.
     */
    final void enable(@NonNull ConfigurationInternal currentUserConfiguration,
            @NonNull Duration initializationTimeout, @NonNull Duration initializationTimeoutFuzz) {
        mThreadingDomain.assertCurrentThread();

        synchronized (mSharedLock) {
            assertCurrentState(PROVIDER_STATE_DISABLED);

            ProviderState currentState = mCurrentState.get();
            ProviderState newState = currentState.newState(
                    PROVIDER_STATE_ENABLED_INITIALIZING, null /* event */,
                    currentUserConfiguration, "enable() called");
            setCurrentState(newState, false);

            Duration delay = initializationTimeout.plus(initializationTimeoutFuzz);
            mInitializationTimeoutQueue.runDelayed(
                    this::handleInitializationTimeout, delay.toMillis());

            onEnable(initializationTimeout);
        }
    }

    private void handleInitializationTimeout() {
        mThreadingDomain.assertCurrentThread();

        synchronized (mSharedLock) {
            ProviderState currentState = mCurrentState.get();
            if (currentState.stateEnum == PROVIDER_STATE_ENABLED_INITIALIZING) {
                // On initialization timeout the provider becomes uncertain.
                ProviderState newState = currentState.newState(
                        PROVIDER_STATE_ENABLED_UNCERTAIN, null /* event */,
                        currentState.currentUserConfiguration, "initialization timeout");
                setCurrentState(newState, true);
            }
        }
    }

    /**
     * Implemented by subclasses to do work during {@link #enable}.
     */
    abstract void onEnable(@NonNull Duration initializationTimeout);

    /**
     * Disables the provider. It is an error* to call this method except when the {@link
     * #getCurrentState()} is one of the enabled states. This method must be
     * called using the handler thread from the {@link ThreadingDomain}.
     */
    final void disable() {
        mThreadingDomain.assertCurrentThread();

        synchronized (mSharedLock) {
            assertIsEnabled();

            ProviderState currentState = mCurrentState.get();
            ProviderState newState = currentState.newState(
                    PROVIDER_STATE_DISABLED, null, null, "disable() called");
            setCurrentState(newState, false);

            if (mInitializationTimeoutQueue.hasQueued()) {
                mInitializationTimeoutQueue.cancel();
            }

            onDisable();
        }
    }

    /**
     * Implemented by subclasses to do work during {@link #disable}.
     */
    abstract void onDisable();

    /** For subclasses to invoke when a {@link LocationTimeZoneEvent} has been received. */
    final void handleLocationTimeZoneEvent(
            @NonNull LocationTimeZoneEvent locationTimeZoneEvent) {
        mThreadingDomain.assertCurrentThread();
        Objects.requireNonNull(locationTimeZoneEvent);

        synchronized (mSharedLock) {
            debugLog("handleLocationTimeZoneEvent: mProviderName=" + mProviderName
                    + ", locationTimeZoneEvent=" + locationTimeZoneEvent);

            ProviderState currentState = mCurrentState.get();
            int eventType = locationTimeZoneEvent.getEventType();
            switch (currentState.stateEnum) {
                case PROVIDER_STATE_PERM_FAILED: {
                    // After entering perm failed, there is nothing to do. The remote peer is
                    // supposed to stop sending events after it has reported perm failure.
                    logWarn("handleLocationTimeZoneEvent: Event=" + locationTimeZoneEvent
                            + " received for provider=" + this + " when in failed state");
                    return;
                }
                case PROVIDER_STATE_DISABLED: {
                    switch (eventType) {
                        case EVENT_TYPE_PERMANENT_FAILURE: {
                            String msg = "handleLocationTimeZoneEvent:"
                                    + " Failure event=" + locationTimeZoneEvent
                                    + " received for disabled provider=" + this
                                    + ", entering permanently failed state";
                            logWarn(msg);
                            ProviderState newState = currentState.newState(
                                    PROVIDER_STATE_PERM_FAILED, null, null, msg);
                            setCurrentState(newState, true);
                            if (mInitializationTimeoutQueue.hasQueued()) {
                                mInitializationTimeoutQueue.cancel();
                            }
                            return;
                        }
                        case EVENT_TYPE_SUCCESS:
                        case EVENT_TYPE_UNCERTAIN: {
                            // Any geolocation-related events received for a disabled provider are
                            // ignored: they should not happen.
                            logWarn("handleLocationTimeZoneEvent:"
                                    + " event=" + locationTimeZoneEvent
                                    + " received for disabled provider=" + this
                                    + ", ignoring");

                            return;
                        }
                        default: {
                            throw new IllegalStateException(
                                    "Unknown eventType=" + locationTimeZoneEvent);
                        }
                    }
                }
                case PROVIDER_STATE_ENABLED_INITIALIZING:
                case PROVIDER_STATE_ENABLED_CERTAIN:
                case PROVIDER_STATE_ENABLED_UNCERTAIN: {
                    switch (eventType) {
                        case EVENT_TYPE_PERMANENT_FAILURE: {
                            String msg = "handleLocationTimeZoneEvent:"
                                    + " Failure event=" + locationTimeZoneEvent
                                    + " received for provider=" + this
                                    + ", entering permanently failed state";
                            logWarn(msg);
                            ProviderState newState = currentState.newState(
                                    PROVIDER_STATE_PERM_FAILED, null, null, msg);
                            setCurrentState(newState, true);
                            if (mInitializationTimeoutQueue.hasQueued()) {
                                mInitializationTimeoutQueue.cancel();
                            }

                            return;
                        }
                        case EVENT_TYPE_UNCERTAIN:
                        case EVENT_TYPE_SUCCESS: {
                            @ProviderStateEnum int providerStateEnum;
                            if (eventType == EVENT_TYPE_UNCERTAIN) {
                                providerStateEnum = PROVIDER_STATE_ENABLED_UNCERTAIN;
                            } else {
                                providerStateEnum = PROVIDER_STATE_ENABLED_CERTAIN;
                            }
                            ProviderState newState = currentState.newState(providerStateEnum,
                                    locationTimeZoneEvent, currentState.currentUserConfiguration,
                                    "handleLocationTimeZoneEvent() when enabled");
                            setCurrentState(newState, true);
                            if (mInitializationTimeoutQueue.hasQueued()) {
                                mInitializationTimeoutQueue.cancel();
                            }
                            return;
                        }
                        default: {
                            throw new IllegalStateException(
                                    "Unknown eventType=" + locationTimeZoneEvent);
                        }
                    }
                }
                default: {
                    throw new IllegalStateException("Unknown providerType=" + currentState);
                }
            }
        }
    }

    /**
     * Implemented by subclasses.
     */
    abstract void logWarn(String msg);

    @GuardedBy("mSharedLock")
    private void assertIsEnabled() {
        ProviderState currentState = mCurrentState.get();
        if (!currentState.isEnabled()) {
            throw new IllegalStateException("Required an enabled state, but was " + currentState);
        }
    }

    @GuardedBy("mSharedLock")
    private void assertCurrentState(@ProviderStateEnum int requiredState) {
        ProviderState currentState = mCurrentState.get();
        if (currentState.stateEnum != requiredState) {
            throw new IllegalStateException(
                    "Required stateEnum=" + requiredState + ", but was " + currentState);
        }
    }

    @VisibleForTesting
    boolean isInitializationTimeoutSet() {
        synchronized (mSharedLock) {
            return mInitializationTimeoutQueue.hasQueued();
        }
    }

    @VisibleForTesting
    Duration getInitializationTimeoutDelay() {
        synchronized (mSharedLock) {
            return Duration.ofMillis(mInitializationTimeoutQueue.getQueuedDelayMillis());
        }
    }
}
