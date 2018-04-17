/*
 * Copyright (C) 2016 The Android Open Source Project
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

package com.android.printservice.recommendation.util;

import android.net.nsd.NsdManager;
import android.net.nsd.NsdServiceInfo;
import android.util.ArrayMap;
import android.util.Log;

import androidx.annotation.NonNull;

import java.util.ArrayList;

/**
 * Used to multiplex listening for NSD services. This is needed as only a limited amount of
 * {@link NsdManager.DiscoveryListener listeners} are allowed.
 */
public class DiscoveryListenerMultiplexer {
    private static final String LOG_TAG = "DiscoveryListenerMx";

    /** List of registered {@link DiscoveryListenerSet discovery sets}. */
    private static final @NonNull ArrayMap<String, DiscoveryListenerSet> sListeners =
            new ArrayMap<>();

    /**
     * Add a new {@link NsdManager.DiscoveryListener listener} for a {@code serviceType}.
     *
     * @param nsdManager  The {@link NsdManager NSD manager} to use
     * @param serviceType The service type to listen for
     * @param newListener the {@link NsdManager.DiscoveryListener listener} to add.
     */
    public static void addListener(@NonNull NsdManager nsdManager, @NonNull String serviceType,
            @NonNull NsdManager.DiscoveryListener newListener) {
        synchronized (sListeners) {
            DiscoveryListenerSet listenerSet = sListeners.get(serviceType);

            if (listenerSet == null) {
                ArrayList<NsdManager.DiscoveryListener> subListeners = new ArrayList<>(1);
                listenerSet = new DiscoveryListenerSet(subListeners,
                        new MultiListener(subListeners));

                sListeners.put(serviceType, listenerSet);
            }

            synchronized (listenerSet.subListeners) {
                if (listenerSet.subListeners.isEmpty()) {
                    nsdManager.discoverServices(serviceType, NsdManager.PROTOCOL_DNS_SD,
                            listenerSet.mainListener);
                }

                listenerSet.subListeners.add(newListener);
            }
        }
    }

    /**
     * Remove a previously added {@link NsdManager.DiscoveryListener listener}.
     *
     * @param nsdManager The {@link NsdManager NSD manager} to use
     * @param listener   The {@link NsdManager.DiscoveryListener listener} that was registered
     *
     * @return true iff the listener was removed
     */
    public static boolean removeListener(@NonNull NsdManager nsdManager,
            @NonNull NsdManager.DiscoveryListener listener) {
        boolean wasRemoved = false;

        synchronized (sListeners) {
            for (DiscoveryListenerSet listeners : sListeners.values()) {
                synchronized (listeners) {
                    wasRemoved = listeners.subListeners.remove(listener);

                    if (wasRemoved) {
                        if (listeners.subListeners.isEmpty()) {
                            nsdManager.stopServiceDiscovery(listeners.mainListener);
                        }

                        break;
                    }
                }
            }
        }

        return wasRemoved;
    }

    /** Private class holding all data for a service type */
    private static class DiscoveryListenerSet {
        /** The plugin's listeners */
        final @NonNull ArrayList<NsdManager.DiscoveryListener> subListeners;

        /** The listener registered with the NSD Manager */
        final @NonNull MultiListener mainListener;

        private DiscoveryListenerSet(ArrayList<NsdManager.DiscoveryListener> subListeners,
                MultiListener mainListener) {
            this.subListeners = subListeners;
            this.mainListener = mainListener;
        }
    }

    /**
     * A {@link NsdManager.DiscoveryListener} that calls a list of registered listeners when
     * a service is found or lost.
     */
    private static class MultiListener implements NsdManager.DiscoveryListener {
        private final @NonNull ArrayList<NsdManager.DiscoveryListener> mListeners;

        /**
         * Create a new multi listener.
         *
         * @param listeners The listeners to forward the calls.
         */
        public MultiListener(@NonNull ArrayList<NsdManager.DiscoveryListener> listeners) {
            mListeners = listeners;
        }

        @Override
        public void onStartDiscoveryFailed(String serviceType, int errorCode) {
            Log.w(LOG_TAG, "Failed to start network discovery for type " + serviceType + ": "
                    + errorCode);
        }

        @Override
        public void onStopDiscoveryFailed(String serviceType, int errorCode) {
            Log.w(LOG_TAG, "Failed to stop network discovery for type " + serviceType + ": "
                    + errorCode);
        }

        @Override
        public void onDiscoveryStarted(String serviceType) {
            // not implemented
        }

        @Override
        public void onDiscoveryStopped(String serviceType) {
            // not implemented
        }

        @Override
        public void onServiceFound(NsdServiceInfo serviceInfo) {
            synchronized (mListeners) {
                int numListeners = mListeners.size();
                for (int i = 0; i < numListeners; i++) {
                    NsdManager.DiscoveryListener listener = mListeners.get(i);

                    listener.onServiceFound(serviceInfo);
                }
            }
        }

        @Override
        public void onServiceLost(NsdServiceInfo serviceInfo) {
            synchronized (mListeners) {
                int numListeners = mListeners.size();
                for (int i = 0; i < numListeners; i++) {
                    NsdManager.DiscoveryListener listener = mListeners.get(i);

                    listener.onServiceLost(serviceInfo);
                }
            }
        }
    }
}
