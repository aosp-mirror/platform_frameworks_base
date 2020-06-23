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

package com.android.server.location;

import static com.android.internal.util.function.pooled.PooledLambda.obtainRunnable;

import android.annotation.Nullable;
import android.location.Location;
import android.location.util.identity.CallerIdentity;
import android.os.Binder;
import android.os.Bundle;

import com.android.internal.location.ProviderProperties;
import com.android.internal.location.ProviderRequest;

import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
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
        void onReportLocation(Location location);

        /**
         * Called when a provider has a new location available. May be invoked from any thread. Will
         * be invoked with a cleared binder identity.
         */
        void onReportLocation(List<Location> locations);
    }

    /**
     * Holds a representation of the public state of a provider.
     */
    public static final class State {

        /**
         * Default state value for a location provider that is disabled with no properties and an
         * empty provider package list.
         */
        public static final State EMPTY_STATE = new State(false, null, null);

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

        private State(boolean allowed, ProviderProperties properties, CallerIdentity identity) {
            this.allowed = allowed;
            this.properties = properties;
            this.identity = identity;
        }

        State withAllowed(boolean allowed) {
            if (allowed == this.allowed) {
                return this;
            } else {
                return new State(allowed, properties, identity);
            }
        }

        State withProperties(@Nullable ProviderProperties properties) {
            if (Objects.equals(properties, this.properties)) {
                return this;
            } else {
                return new State(allowed, properties, identity);
            }
        }

        State withIdentity(@Nullable CallerIdentity identity) {
            if (Objects.equals(identity, this.identity)) {
                return this;
            } else {
                return new State(allowed, properties, identity);
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
                    && Objects.equals(identity, state.identity);
        }

        @Override
        public int hashCode() {
            return Objects.hash(allowed, properties, identity);
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


    protected AbstractLocationProvider(Executor executor) {
        mExecutor = executor;
        mInternalState = new AtomicReference<>(
                new InternalState(null, State.EMPTY_STATE));
    }

    protected AbstractLocationProvider(Executor executor, CallerIdentity identity) {
        mExecutor = executor;
        mInternalState = new AtomicReference<>(
                new InternalState(null, State.EMPTY_STATE.withIdentity(identity)));
    }

    /**
     * Sets the listener and returns the state at the moment the listener was set. The listener can
     * expect to receive all state updates from after this point.
     */
    protected State setListener(@Nullable Listener listener) {
        return mInternalState.updateAndGet(
                internalState -> internalState.withListener(listener)).state;
    }

    /**
     * Retrieves the state of the provider.
     */
    public State getState() {
        return mInternalState.get().state;
    }

    /**
     * Sets the state of the provider to the new state.
     */
    protected void setState(State newState) {
        InternalState oldInternalState = mInternalState.getAndUpdate(
                internalState -> internalState.withState(newState));
        if (newState.equals(oldInternalState.state)) {
            return;
        }

        // we know that we only updated the state, so the listener for the old state is the same as
        // the listener for the new state.
        if (oldInternalState.listener != null) {
            long identity = Binder.clearCallingIdentity();
            try {
                oldInternalState.listener.onStateChanged(oldInternalState.state, newState);
            } finally {
                Binder.restoreCallingIdentity(identity);
            }
        }
    }

    private void setState(UnaryOperator<State> operator) {
        InternalState oldInternalState = mInternalState.getAndUpdate(
                internalState -> internalState.withState(operator));

        // recreate the new state from our knowledge of the old state - unfortunately may result in
        // an extra allocation, but oh well...
        State newState = operator.apply(oldInternalState.state);

        if (newState.equals(oldInternalState.state)) {
            return;
        }

        // we know that we only updated the state, so the listener for the old state is the same as
        // the listener for the new state.
        if (oldInternalState.listener != null) {
            long identity = Binder.clearCallingIdentity();
            try {
                oldInternalState.listener.onStateChanged(oldInternalState.state, newState);
            } finally {
                Binder.restoreCallingIdentity(identity);
            }
        }
    }

    /**
     * The current allowed state of this provider.
     */
    protected boolean isAllowed() {
        return mInternalState.get().state.allowed;
    }

    /**
     * The current provider properties of this provider.
     */
    @Nullable
    protected ProviderProperties getProperties() {
        return mInternalState.get().state.properties;
    }

    /**
     * The current identity of this provider.
     */
    @Nullable
    protected CallerIdentity getIdentity() {
        return mInternalState.get().state.identity;
    }

    /**
     * Call this method to report a change in provider allowed status.
     */
    protected void setAllowed(boolean allowed) {
        setState(state -> state.withAllowed(allowed));
    }

    /**
     * Call this method to report a change in provider properties.
     */
    protected void setProperties(ProviderProperties properties) {
        setState(state -> state.withProperties(properties));
    }

    /**
     * Call this method to report a change in provider packages.
     */
    protected void setIdentity(CallerIdentity identity) {
        setState(state -> state.withIdentity(identity));
    }

    /**
     * Call this method to report a new location.
     */
    protected void reportLocation(Location location) {
        Listener listener = mInternalState.get().listener;
        if (listener != null) {
            long identity = Binder.clearCallingIdentity();
            try {
                // copy location so if provider makes further changes they do not propagate
                listener.onReportLocation(new Location(location));
            } finally {
                Binder.restoreCallingIdentity(identity);
            }
        }
    }

    /**
     * Call this method to report a new location.
     */
    protected void reportLocation(List<Location> locations) {
        Listener listener = mInternalState.get().listener;
        if (listener != null) {
            long identity = Binder.clearCallingIdentity();
            try {
                // copy location so if provider makes further changes they do not propagate
                ArrayList<Location> copy = new ArrayList<>(locations.size());
                for (Location location : locations) {
                    copy.add(new Location(location));
                }
                listener.onReportLocation(copy);
            } finally {
                Binder.restoreCallingIdentity(identity);
            }
        }
    }

    /**
     * Sets a new request and worksource for the provider.
     */
    public final void setRequest(ProviderRequest request) {
        // all calls into the provider must be moved onto the provider thread to prevent deadlock
        mExecutor.execute(obtainRunnable(AbstractLocationProvider::onSetRequest, this, request)
                .recycleOnUse());
    }

    /**
     * Always invoked on the provider executor.
     */
    protected abstract void onSetRequest(ProviderRequest request);

    /**
     * Sends an extra command to the provider for it to interpret as it likes.
     */
    public final void sendExtraCommand(int uid, int pid, String command, Bundle extras) {
        // all calls into the provider must be moved onto the provider thread to prevent deadlock

        // the integer boxing done here likely cancels out any gains from removing lambda
        // allocation, but since this an infrequently used api with no real performance needs, we
        // we use pooled lambdas anyways for consistency.
        mExecutor.execute(
                obtainRunnable(AbstractLocationProvider::onExtraCommand, this, uid, pid, command,
                        extras).recycleOnUse());
    }

    /**
     * Always invoked on the provider executor.
     */
    protected abstract void onExtraCommand(int uid, int pid, String command, Bundle extras);

    /**
     * Dumps debug or log information. May be invoked from any thread.
     */
    public abstract void dump(FileDescriptor fd, PrintWriter pw, String[] args);
}
