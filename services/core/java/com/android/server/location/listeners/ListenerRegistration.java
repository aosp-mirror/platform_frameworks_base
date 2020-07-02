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


import static com.android.internal.util.ConcurrentUtils.DIRECT_EXECUTOR;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.location.util.identity.CallerIdentity;
import android.os.Process;

import com.android.internal.listeners.ListenerExecutor;
import com.android.server.FgThread;

import java.util.Objects;
import java.util.concurrent.Executor;

/**
 * A listener registration object which holds data associated with the listener, such as an optional
 * request, and the identity of the listener owner.
 *
 * @param <TRequest>  request type
 * @param <TListener> listener type
 */
public class ListenerRegistration<TRequest, TListener> implements ListenerExecutor {

    private final Executor mExecutor;
    private final @Nullable TRequest mRequest;
    private final CallerIdentity mIdentity;

    private boolean mActive;

    private volatile @Nullable TListener mListener;

    protected ListenerRegistration(@Nullable TRequest request, CallerIdentity identity,
            TListener listener) {
        // if a client is in the same process as us, binder calls will execute synchronously and
        // we shouldn't run callbacks directly since they might be run under lock and deadlock
        if (identity.getPid() == Process.myPid()) {
            // there's a slight loophole here for pending intents - pending intent callbacks can
            // always be run on the direct executor since they're always asynchronous, but honestly
            // you shouldn't be using pending intent callbacks within the same process anyways
            mExecutor =  FgThread.getExecutor();
        } else {
            mExecutor =  DIRECT_EXECUTOR;
        }

        mRequest = request;
        mIdentity = Objects.requireNonNull(identity);
        mActive = false;
        mListener = Objects.requireNonNull(listener);
    }

    protected final Executor getExecutor() {
        return mExecutor;
    }

    /**
     * Returns the request associated with this listener, or null if one wasn't supplied.
     */
    public final @Nullable TRequest getRequest() {
        return mRequest;
    }

    /**
     * Returns the listener identity.
     */
    public final CallerIdentity getIdentity() {
        return mIdentity;
    }

    /**
     * May be overridden by subclasses. Invoked when registration occurs.
     */
    protected void onRegister(Object key) {}

    /**
     * May be overridden by subclasses. Invoked when unregistration occurs.
     */
    protected void onUnregister() {}

    /**
     * May be overridden by subclasses. Invoked when this registration becomes active. If this
     * returns a non-null operation, that operation will be invoked for the listener.
     */
    protected @Nullable ListenerOperation<TListener> onActive() {
        return null;
    }

    /**
     * May be overridden by subclasses. Invoked when registration becomes inactive.
     */
    protected void onInactive() {}

    final boolean isActive() {
        return mActive;
    }

    final boolean setActive(boolean active) {
        if (active != mActive) {
            mActive = active;
            return true;
        }

        return false;
    }

    final boolean isRegistered() {
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
     * even if the various bookkeeping associated with unregistration has not occurred yet.
     */
    protected void onListenerUnregister() {};

    final void executeInternal(@NonNull ListenerOperation<TListener> operation) {
        executeSafely(mExecutor, () -> mListener, operation);
    }

    @Override
    public String toString() {
        if (mRequest == null) {
            return "[]";
        } else {
            return mRequest.toString();
        }
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

