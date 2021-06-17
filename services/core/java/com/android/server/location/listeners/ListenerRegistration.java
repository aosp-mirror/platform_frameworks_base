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

package com.android.server.location.listeners;

import android.annotation.Nullable;

import com.android.internal.listeners.ListenerExecutor;

import java.util.Objects;
import java.util.concurrent.Executor;

/**
 * A listener registration object which holds data associated with the listener, such as an optional
 * request, and an executor responsible for listener invocations.
 *
 * @param <TListener>          listener type
 */
public class ListenerRegistration<TListener> implements ListenerExecutor {

    private final Executor mExecutor;

    private boolean mActive;

    private volatile @Nullable TListener mListener;

    protected ListenerRegistration(Executor executor, TListener listener) {
        mExecutor = Objects.requireNonNull(executor);
        mActive = false;
        mListener = Objects.requireNonNull(listener);
    }

    protected final Executor getExecutor() {
        return mExecutor;
    }

    /**
     * May be overridden by subclasses. Invoked when registration occurs. Invoked while holding the
     * owning multiplexer's internal lock.
     */
    protected void onRegister(Object key) {}

    /**
     * May be overridden by subclasses. Invoked when unregistration occurs. Invoked while holding
     * the owning multiplexer's internal lock.
     */
    protected void onUnregister() {}

    /**
     * May be overridden by subclasses. Invoked when this registration becomes active. If this
     * returns a non-null operation, that operation will be invoked for the listener. Invoked
     * while holding the owning multiplexer's internal lock.
     */
    protected void onActive() {}

    /**
     * May be overridden by subclasses. Invoked when registration becomes inactive. If this returns
     * a non-null operation, that operation will be invoked for the listener. Invoked while holding
     * the owning multiplexer's internal lock.
     */
    protected void onInactive() {}

    public final boolean isActive() {
        return mActive;
    }

    final boolean setActive(boolean active) {
        if (active != mActive) {
            mActive = active;
            return true;
        }

        return false;
    }

    public final boolean isRegistered() {
        return mListener != null;
    }

    final void unregisterInternal() {
        mListener = null;
        onListenerUnregister();
    }

    /**
     * May be overridden by subclasses, however should rarely be needed. Invoked when the listener
     * associated with this registration is unregistered, which may occur before the registration
     * itself is unregistered. This immediately prevents the listener from being further invoked
     * until the registration itself can be finalized and unregistered completely.
     */
    protected void onListenerUnregister() {}

    /**
     * May be overridden by subclasses to handle listener operation failures. The default behavior
     * is to further propagate any exceptions. Will always be invoked on the executor thread.
     */
    protected void onOperationFailure(ListenerOperation<TListener> operation, Exception exception) {
        throw new AssertionError(exception);
    }

    /**
     * Executes the given listener operation, invoking
     * {@link #onOperationFailure(ListenerOperation, Exception)} in case the listener operation
     * fails.
     */
    protected final void executeOperation(@Nullable ListenerOperation<TListener> operation) {
        executeSafely(mExecutor, () -> mListener, operation, this::onOperationFailure);
    }

    @Override
    public String toString() {
        return "[]";
    }

    @Override
    public final boolean equals(Object obj) {
        // intentionally bound to reference equality so removal works as expected
        return this == obj;
    }

    @Override
    public final int hashCode() {
        return super.hashCode();
    }
}

