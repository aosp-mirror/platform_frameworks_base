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
import android.os.RemoteException;
import android.util.ArrayMap;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.util.Preconditions;

import java.util.Objects;

/**
 * A listener manager which tracks listeners along with their keys. This class enforces proper use
 * of transport objects and ensure unregistration race conditions are handled properly. If listeners
 * should be multiplexed before being sent to the server, see {@link ListenerTransportMultiplexer}
 * instead.
 *
 * @param <TTransport> transport type
 */
public abstract class ListenerTransportManager<TTransport extends ListenerTransport<?>> {

    @GuardedBy("mTransports")
    private final ArrayMap<Object, TTransport> mTransports = new ArrayMap<>();

    /**
     * Should be implemented to register the transport with the server.
     *
     * @see #reregisterWithServer(ListenerTransport, ListenerTransport)
     */
    protected abstract void registerWithServer(TTransport transport) throws RemoteException;

    /**
     * Invoked when the server already has a transport registered for a key, and it is being
     * replaced with a new transport. The default implementation unregisters the old transport, then
     * registers the new transport, but this may be overridden by subclasses in order to reregister
     * more efficiently.
     */
    protected void reregisterWithServer(TTransport oldTransport, TTransport newTransport)
            throws RemoteException {
        unregisterWithServer(oldTransport);
        registerWithServer(newTransport);
    }

    /**
     * Should be implemented to unregister the transport from the server.
     */
    protected abstract void unregisterWithServer(TTransport transport) throws RemoteException;

    /**
     * Adds a new transport with the given key and makes a call to add the transport server side. If
     * a transport already exists with that key, it will be replaced by the new transport and
     * {@link #reregisterWithServer(ListenerTransport, ListenerTransport)} will be invoked to
     * replace the old transport with the new transport server side. If no transport exists with
     * that key, it will be added server side via {@link #registerWithServer(ListenerTransport)}.
     */
    protected void registerListener(@NonNull Object key, @NonNull TTransport transport) {
        Objects.requireNonNull(key);
        Objects.requireNonNull(transport);

        synchronized (mTransports) {
            TTransport oldTransport = mTransports.put(key, transport);
            if (oldTransport != null) {
                oldTransport.unregister();
            }

            Preconditions.checkState(transport.isRegistered());

            boolean registered = false;
            try {
                if (oldTransport == null) {
                    registerWithServer(transport);
                } else {
                    reregisterWithServer(oldTransport, transport);
                }
                registered = true;
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            } finally {
                if (!registered) {
                    transport.unregister();
                    mTransports.remove(key);
                }
            }
        }
    }

    /**
     * Removes the transport with the given key, and makes a call to remove the transport server
     * side via {@link #unregisterWithServer(ListenerTransport)}.
     */
    protected void unregisterListener(@NonNull Object key) {
        Objects.requireNonNull(key);

        synchronized (mTransports) {
            TTransport transport = mTransports.remove(key);
            if (transport == null) {
                return;
            }

            transport.unregister();
            try {
                unregisterWithServer(transport);
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
        }
    }

    /**
     * Removes the given transport with the given key if such a mapping exists. This only removes
     * the client registration, it does not make any calls to remove the transport server side. The
     * intended use is for when the transport is already removed server side and only client side
     * cleanup is necessary.
     */
    protected void removeTransport(@NonNull Object key, @NonNull ListenerTransport<?> transport) {
        Objects.requireNonNull(key);
        Objects.requireNonNull(transport);

        synchronized (mTransports) {
            TTransport typedTransport = mTransports.get(key);
            if (typedTransport != transport) {
                return;
            }

            mTransports.remove(key);
            typedTransport.unregister();
        }
    }
}
