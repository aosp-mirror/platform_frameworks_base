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

import android.os.RemoteException;

import com.android.internal.annotations.GuardedBy;

import java.lang.ref.WeakReference;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * A listener transport manager which handles mappings between the client facing listener and system
 * server facing transport. Supports transports which may be removed either from the client side or
 * from the system server side without leaking memory.
 *
 * @param <TTransport>> transport type
 */
public abstract class ListenerTransportManager<TTransport extends ListenerTransport<?>> {

    @GuardedBy("mRegistrations")
    private final Map<Object, WeakReference<TTransport>> mRegistrations;

    protected ListenerTransportManager() {
        // using weakhashmap means that the transport may be GCed if the server drops its reference,
        // and thus the listener may be GCed as well if the client drops that reference. if the
        // server will never drop a reference without warning (ie, transport removal may only be
        // initiated from the client side), then arraymap or similar may be used without fear of
        // memory leaks.
        mRegistrations = new WeakHashMap<>();
    }

    /**
     * Adds a new transport with the given listener key.
     */
    public final void addListener(Object key, TTransport transport) {
        try {
            synchronized (mRegistrations) {
                // ordering of operations is important so that if an error occurs at any point we
                // are left in a reasonable state
                registerTransport(transport);
                WeakReference<TTransport> oldTransportRef = mRegistrations.put(key,
                        new WeakReference<>(transport));
                if (oldTransportRef != null) {
                    TTransport oldTransport = oldTransportRef.get();
                    if (oldTransport != null) {
                        oldTransport.unregister();
                        unregisterTransport(oldTransport);
                    }
                }
            }
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Removes the transport with the given listener key.
     */
    public final void removeListener(Object key) {
        try {
            synchronized (mRegistrations) {
                // ordering of operations is important so that if an error occurs at any point we
                // are left in a reasonable state
                WeakReference<TTransport> transportRef = mRegistrations.remove(key);
                if (transportRef != null) {
                    TTransport transport = transportRef.get();
                    if (transport != null) {
                        transport.unregister();
                        unregisterTransport(transport);
                    }
                }
            }
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    protected abstract void registerTransport(TTransport transport) throws RemoteException;

    protected abstract void unregisterTransport(TTransport transport) throws RemoteException;
}
