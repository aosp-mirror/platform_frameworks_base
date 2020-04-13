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
public class ListenerRegistration<TRequest, TListener> {

    /**
     * An listener operation to perform.
     *
     * @param <TListener> listener type
     */
    public interface ListenerOperation<TListener> {
        /**
         * Performs the operation on the given listener
         */
        void operate(TListener listener) throws Exception;
    }

    private final Executor mExecutor;
    private final @Nullable TRequest mRequest;
    private final CallerIdentity mCallerIdentity;

    private boolean mActive;

    private volatile @Nullable TListener mListener;

    protected ListenerRegistration(@Nullable TRequest request, CallerIdentity callerIdentity,
            TListener listener) {
        // if a client is in the same process as us, binder calls will execute synchronously and
        // we shouldn't run callbacks directly since they might be run under lock and deadlock
        if (callerIdentity.pid == Process.myPid()) {
            // there's a slight loophole here for pending intents - pending intent callbacks can
            // always be run on the direct executor since they're always asynchronous, but honestly
            // you shouldn't be using pending intent callbacks within the same process anyways
            mExecutor =  FgThread.getExecutor();
        } else {
            mExecutor =  DIRECT_EXECUTOR;
        }

        mRequest = request;
        mCallerIdentity = Objects.requireNonNull(callerIdentity);
        mActive = false;
        mListener = Objects.requireNonNull(listener);
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
        return mCallerIdentity;
    }

    /**
     * May be overridden by subclasses. Invoked when registration occurs. If this returns true,
     * then registration will complete successfully. If this returns false, registration will fail,
     * and {@link #onUnregister()} will not be called.
     */
    protected boolean onRegister(Object key) {
        return true;
    }

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
    }

    final void executeInternal(@NonNull ListenerOperation<TListener> operation) {
        Objects.requireNonNull(operation);
        mExecutor.execute(() -> {
            TListener listener = mListener;
            if (listener == null) {
                return;
            }

            try {
                operation.operate(listener);
            } catch (Exception e) {
                onOperationFailure(operation, e);
            }
        });
    }

    /**
     * Invoked when an operation throws an exception, and run on the same executor as the operation.
     */
    protected void onOperationFailure(@NonNull ListenerOperation<TListener> operation,
            @NonNull Exception exception) {
        if (exception instanceof RuntimeException) {
            throw (RuntimeException) exception;
        } else {
            // listeners should not throw exceptions that their registrations cannot handle
            throw new UnsupportedOperationException(exception);
        }
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

