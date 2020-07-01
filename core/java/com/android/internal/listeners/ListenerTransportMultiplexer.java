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

package com.android.internal.listeners;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.os.Build;
import android.os.RemoteException;
import android.util.ArrayMap;
import android.util.IndentingPrintWriter;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.util.Preconditions;

import java.io.FileDescriptor;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Objects;
import java.util.concurrent.Executor;
import java.util.function.Consumer;

/**
 * A listener multiplexer designed for use by client-side code. This class ensures that listeners
 * are never invoked while a lock is held. This class is only useful for multiplexing listeners -
 * if all client listeners can be combined into a single server request, and all server results will
 * be delivered to all clients.
 *
 * By default, the multiplexer will replace requests on the server simply by registering the new
 * request and trusting the server to know this is replacing the old request. If the server needs to
 * have the old request unregistered first, subclasses should override
 * {@link #reregisterWithServer(Object, Object)}.
 *
 * @param <TRequest>  listener request type, may be Void
 * @param <TListener> listener type
 */
public abstract class ListenerTransportMultiplexer<TRequest, TListener> {

    private final Object mLock = new Object();

    @GuardedBy("mLock")
    private ArrayMap<Object, RequestListenerTransport<TRequest, TListener>> mRegistrations =
            new ArrayMap<>();

    @GuardedBy("mLock")
    private boolean mServiceRegistered = false;

    @GuardedBy("mLock")
    private TRequest mCurrentRequest;

    /**
     * Should be implemented to register the given merged request with the server.
     *
     * @see #reregisterWithServer(Object, Object)
     */
    protected abstract void registerWithServer(TRequest mergedRequest) throws RemoteException;

    /**
     * Invoked when the server already has a request registered, and it is being replaced with a new
     * request. The default implementation simply registers the new request, trusting the server to
     * overwrite the old request.
     */
    protected void reregisterWithServer(TRequest oldMergedRequest, TRequest mergedRequest)
            throws RemoteException {
        registerWithServer(mergedRequest);
    }

    /**
     * Should be implemented to unregister from the server.
     */
    protected abstract void unregisterWithServer() throws RemoteException;

    /**
     * Called in order to generate a merged request from the given requests. The list of requests
     * will never be empty.
     */
    protected @Nullable TRequest mergeRequests(Collection<TRequest> requests) {
        if (Build.IS_DEBUGGABLE) {
            for (TRequest request : requests) {
                // if using non-null requests then implementations must override this method
                Preconditions.checkState(request == null);
            }
        }

        return null;
    }

    /**
     * Adds a new listener with no request, using the listener as the key.
     */
    public void addListener(@NonNull TListener listener, @NonNull Executor executor) {
        addListener(listener, null, listener, executor);
    }

    /**
     * Adds a new listener with the given request, using the listener as the key.
     */
    public void addListener(@Nullable TRequest request, @NonNull TListener listener,
            @NonNull Executor executor) {
        addListener(listener, request, listener, executor);
    }

    /**
     * Adds a new listener with the given request using a custom key.
     */
    public void addListener(@NonNull Object key, @Nullable TRequest request,
            @NonNull TListener listener, @NonNull Executor executor) {
        Objects.requireNonNull(key);
        RequestListenerTransport<TRequest, TListener> registration =
                new RequestListenerTransport<>(request, executor, listener);

        synchronized (mLock) {
            ArrayMap<Object, RequestListenerTransport<TRequest, TListener>> newRegistrations =
                    new ArrayMap<>(mRegistrations.size() + 1);
            newRegistrations.putAll(mRegistrations);
            RequestListenerTransport<TRequest, TListener> old = newRegistrations.put(key,
                    registration);
            mRegistrations = newRegistrations;

            if (old != null) {
                old.unregister();
            }

            updateService();
        }
    }

    /**
     * Removes the listener with the given key.
     */
    public void removeListener(@NonNull Object key) {
        Objects.requireNonNull(key);

        synchronized (mLock) {
            if (!mRegistrations.containsKey(key)) {
                return;
            }

            ArrayMap<Object, RequestListenerTransport<TRequest, TListener>> newRegistrations =
                    new ArrayMap<>(mRegistrations);
            RequestListenerTransport<TRequest, TListener> old = newRegistrations.remove(key);
            mRegistrations = newRegistrations;

            if (old != null) {
                old.unregister();
                updateService();
            }
        }
    }

    private void updateService() {
        synchronized (mLock) {
            if (mRegistrations.isEmpty()) {
                mCurrentRequest = null;
                if (mServiceRegistered) {
                    try {
                        mServiceRegistered = false;
                        unregisterWithServer();
                    } catch (RemoteException e) {
                        throw e.rethrowFromSystemServer();
                    }
                }
                return;
            }

            ArrayList<TRequest> requests = new ArrayList<>(mRegistrations.size());
            for (int i = 0; i < mRegistrations.size(); i++) {
                requests.add(mRegistrations.valueAt(i).getRequest());
            }

            TRequest merged = mergeRequests(requests);
            if (!mServiceRegistered || !Objects.equals(merged, mCurrentRequest)) {
                TRequest old = mCurrentRequest;
                mCurrentRequest = null;
                try {
                    if (mServiceRegistered) {
                        // if a remote exception is thrown the service should not be registered
                        mServiceRegistered = false;
                        reregisterWithServer(old, merged);
                    } else {
                        registerWithServer(merged);
                    }
                    mCurrentRequest = merged;
                    mServiceRegistered = true;
                } catch (RemoteException e) {
                    throw e.rethrowFromSystemServer();
                }
            }
        }
    }

    protected final void deliverToListeners(Consumer<TListener> operation) {
        ArrayMap<Object, RequestListenerTransport<TRequest, TListener>> registrations;
        synchronized (mLock) {
            registrations = mRegistrations;
        }

        try {
            for (int i = 0; i < registrations.size(); i++) {
                registrations.valueAt(i).execute(operation);
            }
        } finally {
            onOperationFinished(operation);
        }
    }

    /**
     * Invoked when an operation is finished. This method will always be called once for every call
     * to {@link #deliverToListeners(Consumer)}, regardless of whether the operation encountered any
     * error or failed to execute in any way for any listeners.
     */
    protected void onOperationFinished(@NonNull Consumer<TListener> operation) {}

    /**
     * Dumps debug information.
     */
    public void dump(FileDescriptor fd, IndentingPrintWriter ipw, String[] args) {
        ArrayMap<Object, RequestListenerTransport<TRequest, TListener>> registrations;
        synchronized (mLock) {
            registrations = mRegistrations;

            ipw.print("service: ");
            if (mServiceRegistered) {
                if (mCurrentRequest == null) {
                    ipw.print("request registered");
                } else {
                    ipw.print("request registered - " + mCurrentRequest);
                }
            } else {
                ipw.print("unregistered");
            }
            ipw.println();
        }

        if (!registrations.isEmpty()) {
            ipw.println("listeners:");

            ipw.increaseIndent();
            for (int i = 0; i < registrations.size(); i++) {
                ipw.print(registrations.valueAt(i));
            }
            ipw.decreaseIndent();
        }
    }
}
