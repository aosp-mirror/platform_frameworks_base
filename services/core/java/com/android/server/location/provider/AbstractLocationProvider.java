/*
 * Copyright (C) 2010 The Android Open Source Project
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

package com.android.server.location.provider;

import android.annotation.Nullable;
import android.location.LocationResult;
import android.location.provider.ProviderProperties;
import android.location.provider.ProviderRequest;
import android.location.util.identity.CallerIdentity;
import android.os.Binder;
import android.os.Bundle;

import com.android.internal.util.Preconditions;

import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.util.Collections;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.UnaryOperator;

/**
 * Base class for all location providers.
 *
 * @hide
 */
public abstract class AbstractLocationProvider {

    /**
     * Interface for listening to location providers.
     */
    public interface Listener {

        /**
         * Called when a provider's state changes. May be invoked from any thread. Will be
         * invoked with a cleared binder identity.
         */
        void onStateChanged(State oldState, State newState);

        /**
         * Called when a provider has a new location available. May be invoked from any thread. Will
         * be invoked with a cleared binder identity.
         */
        void onReportLocation(LocationResult locationResult);
    }

    /**
     * Holds a representation of the public state of a provider.
     */
    public static final class State {

        /**
         * Default state value for a location provider that is disabled with no properties and an
         * empty extra attribution tag set.
         */
        public static final State EMPTY_STATE = new State(false, null, null,
                Collections.emptySet());

        /**
         * The provider's allowed state.
         */
        public final boolean allowed;

        /**
         * The provider's properties.
         */
        @Nullable public final ProviderProperties properties;

        /**
         * The provider's identity - providers may be afforded special privileges.
         */
        @Nullable public final CallerIdentity identity;

        /**
         * A set of attribution tags also associated with this provider - these attribution tags may
         * be afforded special privileges.
         */
        public final Set<String> extraAttributionTags;

        private State(boolean allowed, ProviderProperties properties, CallerIdentity identity,
                Set<String> extraAttributionTags) {
            this.allowed = allowed;
            this.properties = properties;
            this.identity = identity;
            this.extraAttributionTags = Objects.requireNonNull(extraAttributionTags);
        }

        /**
         * Returns a state the same as the current but with allowed set as specified.
         */
        public State withAllowed(boolean allowed) {
            if (allowed == this.allowed) {
                return this;
            } else {
                return new State(allowed, properties, identity, extraAttributionTags);
            }
        }

        /**
         * Returns a state the same as the current but with properties set as specified.
         */
        public State withProperties(@Nullable ProviderProperties properties) {
            if (Objects.equals(properties, this.properties)) {
                return this;
            } else {
                return new State(allowed, properties, identity, extraAttributionTags);
            }
        }

        /**
         * Returns a state the same as the current but with an identity set as specified.
         */
        public State withIdentity(@Nullable CallerIdentity identity) {
            if (Objects.equals(identity, this.identity)) {
                return this;
            } else {
                return new State(allowed, properties, identity, extraAttributionTags);
            }
        }

        /**
         * Returns a state the same as the current but with extra attribution tags set as specified.
         */
        public State withExtraAttributionTags(Set<String> extraAttributionTags) {
            if (extraAttributionTags.equals(this.extraAttributionTags)) {
                return this;
            } else {
                return new State(allowed, properties, identity, extraAttributionTags);
            }
        }


        @Override
        public boolean equals(Object o) {
            if (this == o) {
                return true;
            }
            if (!(o instanceof State)) {
                return false;
            }
            State state = (State) o;
            return allowed == state.allowed && properties == state.properties
                    && Objects.equals(identity, state.identity)
                    && extraAttributionTags.equals(state.extraAttributionTags);
        }

        @Override
        public int hashCode() {
            return Objects.hash(allowed, properties, identity, extraAttributionTags);
        }
    }

    // combines listener and state information so that they can be updated atomically with respect
    // to each other and an ordering established.
    private static class InternalState {
        @Nullable public final Listener listener;
        public final State state;

        InternalState(@Nullable Listener listener, State state) {
            this.listener = listener;
            this.state = state;
        }

        InternalState withListener(Listener listener) {
            if (listener == this.listener) {
                return this;
            } else {
                return new InternalState(listener, state);
            }
        }

        InternalState withState(State state) {
            if (state.equals(this.state)) {
                return this;
            } else {
                return new InternalState(listener, state);
            }
        }

        InternalState withState(UnaryOperator<State> operator) {
            return withState(operator.apply(state));
        }
    }

    protected final Executor mExecutor;

    // we use a lock-free implementation to update state to ensure atomicity between updating the
    // provider state and setting the listener, so that the state updates a listener sees are
    // consistent with when the listener was set (a listener should not see any updates that occur
    // before it was set, and should not miss any updates that occur after it was set).
    private final AtomicReference<InternalState> mInternalState;

    private final LocationProviderController mController;

    /**
     * Creates a new location provider.
     *
     * All callback methods will be invoked on the given executor. A direct executor may be provided
     * only if the provider can guarantee that all callback methods will never synchronously invoke
     * any {@link LocationProviderController} methods. If this invariant is not held, use a normal
     * executor or risk deadlock.
     *
     * An optional identity and properties may be provided to initialize the location provider.
     */
    protected AbstractLocationProvider(Executor executor, @Nullable CallerIdentity identity,
            @Nullable ProviderProperties properties, Set<String> extraAttributionTags) {
        Preconditions.checkArgument(identity == null || identity.getListenerId() == null);
        mExecutor = Objects.requireNonNull(executor);
        mInternalState = new AtomicReference<>(new InternalState(null,
                State.EMPTY_STATE
                        .withIdentity(identity)
                        .withProperties(properties)
                        .withExtraAttributionTags(extraAttributionTags)));
        mController = new Controller();
    }

    /**
     * Retrieves the controller for this location provider. Should never be invoked by subclasses,
     * as a location provider should not be controlling itself. Using this method from subclasses
     * could also result in deadlock.
     */
    LocationProviderController getController() {
        return mController;
    }

    protected void setState(UnaryOperator<State> operator) {
        AtomicReference<State> oldStateRef = new AtomicReference<>();
        InternalState newInternalState = mInternalState.updateAndGet(
                internalState -> {
                    oldStateRef.set(internalState.state);
                    return internalState.withState(operator);
                });
        State oldState = oldStateRef.get();

        if (oldState.equals(newInternalState.state)) {
            return;
        }

        if (newInternalState.listener != null) {
            final long identity = Binder.clearCallingIdentity();
            try {
                newInternalState.listener.onStateChanged(oldState, newInternalState.state);
            } finally {
                Binder.restoreCallingIdentity(identity);
            }
        }
    }

    /**
     * The current allowed state of this provider.
     */
    public final boolean isAllowed() {
        return mInternalState.get().state.allowed;
    }

    /**
     * Call this method to report a change in provider allowed status.
     */
    protected void setAllowed(boolean allowed) {
        setState(state -> state.withAllowed(allowed));
    }

    /**
     * The current provider properties of this provider.
     */
    public final @Nullable ProviderProperties getProperties() {
        return mInternalState.get().state.properties;
    }

    /**
     * Call this method to report a change in provider properties.
     */
    protected void setProperties(@Nullable ProviderProperties properties) {
        setState(state -> state.withProperties(properties));
    }

    /**
     * The current identity of this provider.
     */
    public final @Nullable CallerIdentity getIdentity() {
        return mInternalState.get().state.identity;
    }

    /**
     * Call this method to report a change in the provider's identity.
     */
    protected void setIdentity(@Nullable CallerIdentity identity) {
        Preconditions.checkArgument(identity == null || identity.getListenerId() == null);
        setState(state -> state.withIdentity(identity));
    }

    public final Set<String> getExtraAttributionTags() {
        return mInternalState.get().state.extraAttributionTags;
    }

    /**
     * Call this method to report a change in the provider's extra attribution tags.
     */
    protected void setExtraAttributionTags(Set<String> extraAttributionTags) {
        setState(state -> state.withExtraAttributionTags(extraAttributionTags));
    }

    /**
     * Call this method to report a new location.
     */
    protected void reportLocation(LocationResult locationResult) {
        Listener listener = mInternalState.get().listener;
        if (listener != null) {
            final long identity = Binder.clearCallingIdentity();
            try {
                listener.onReportLocation(Objects.requireNonNull(locationResult));
            } finally {
                Binder.restoreCallingIdentity(identity);
            }
        }
    }

    /**
     * Callback invoked when the provider is started, and signals that other callback invocations
     * can now be expected. Always implies that the provider request is set to the empty request.
     * Always invoked on the provider executor.
     */
    protected void onStart() { }

    /**
     * Callback invoked when the provider is stopped, and signals that no further callback
     * invocations will occur (until a further call to {@link #onStart()}. Always invoked on the
     * provider executor.
     */
    protected void onStop() { }

    /**
     * Callback invoked to inform the provider of a new provider request which replaces any prior
     * provider request. Always invoked on the provider executor.
     */
    protected abstract void onSetRequest(ProviderRequest request);

    /**
     * Callback invoked to request any batched locations to be flushed. The argument callback must
     * always be invoked exactly once for every invocation of this method, and should only be
     * invoked only after {@link #reportLocation(LocationResult)} has been called for all flushed
     * locations. If no locations are flushed, the argument callback may be invoked immediately.
     * Always invoked on the provider executor.
     */
    protected abstract void onFlush(Runnable callback);

    /**
     * Callback invoked to informs the provider of an extra command it may choose to respond to.
     * Always invoked on the provider executor.
     */
    protected abstract void onExtraCommand(int uid, int pid, String command, Bundle extras);

    /**
     * Dumps debug or log information. May be invoked from any thread.
     */
    protected abstract void dump(FileDescriptor fd, PrintWriter pw, String[] args);

    private class Controller implements LocationProviderController {

        private boolean mStarted = false;

        Controller() {}

        @Override
        public State setListener(@Nullable Listener listener) {
            InternalState oldInternalState = mInternalState.getAndUpdate(
                    internalState -> internalState.withListener(listener));
            Preconditions.checkState(listener == null || oldInternalState.listener == null);
            return oldInternalState.state;
        }

        @Override
        public boolean isStarted() {
            return mStarted;
        }

        @Override
        public void start() {
            Preconditions.checkState(!mStarted);
            mStarted = true;
            mExecutor.execute(AbstractLocationProvider.this::onStart);
        }

        @Override
        public void stop() {
            Preconditions.checkState(mStarted);
            mStarted = false;
            mExecutor.execute(AbstractLocationProvider.this::onStop);
        }

        @Override
        public void setRequest(ProviderRequest request) {
            Preconditions.checkState(mStarted);
            mExecutor.execute(() -> onSetRequest(request));
        }

        @Override
        public void flush(Runnable listener) {
            Preconditions.checkState(mStarted);
            mExecutor.execute(() -> onFlush(listener));
        }

        @Override
        public void sendExtraCommand(int uid, int pid, String command, Bundle extras) {
            Preconditions.checkState(mStarted);
            mExecutor.execute(() -> onExtraCommand(uid, pid, command, extras));
        }
    }
}
