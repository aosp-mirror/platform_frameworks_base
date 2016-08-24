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

package com.android.server.net;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.ConnectivityManager.NetworkCallback;
import android.net.Network;
import android.net.NetworkRequest;
import android.util.Log;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.annotations.VisibleForTesting;

/**
 * A class that pins a process to the first network that satisfies a particular NetworkRequest.
 *
 * We use this to maintain compatibility with pre-M apps that call WifiManager.enableNetwork()
 * to connect to a Wi-Fi network that has no Internet access, and then assume that they will be
 * able to use that network because it's the system default.
 *
 * In order to maintain compatibility with apps that call setProcessDefaultNetwork themselves,
 * we try not to set the default network unless they have already done so, and we try not to
 * clear the default network unless we set it ourselves.
 *
 * This should maintain behaviour that's compatible with L, which would pin the whole system to
 * any wifi network that was created via enableNetwork(..., true) until that network
 * disconnected.
 *
 * Note that while this hack allows network traffic to flow, it is quite limited. For example:
 *
 * 1. setProcessDefaultNetwork only affects this process, so:
 *    - Any subprocesses spawned by this process will not be pinned to Wi-Fi.
 *    - If this app relies on any other apps on the device also being on Wi-Fi, that won't work
 *      either, because other apps on the device will not be pinned.
 * 2. The behaviour of other APIs is not modified. For example:
 *    - getActiveNetworkInfo will return the system default network, not Wi-Fi.
 *    - There will be no CONNECTIVITY_ACTION broadcasts about TYPE_WIFI.
 *    - getProcessDefaultNetwork will not return null, so if any apps are relying on that, they
 *      will be surprised as well.
 *
 * This class is a per-process singleton because the process default network is a per-process
 * singleton.
 *
 */
public class NetworkPinner extends NetworkCallback {

    private static final String TAG = NetworkPinner.class.getSimpleName();

    @VisibleForTesting
    protected static final Object sLock = new Object();

    @GuardedBy("sLock")
    private static ConnectivityManager sCM;
    @GuardedBy("sLock")
    private static Callback sCallback;
    @VisibleForTesting
    @GuardedBy("sLock")
    protected static Network sNetwork;

    private static void maybeInitConnectivityManager(Context context) {
        // TODO: what happens if an app calls a WifiManager API before ConnectivityManager is
        // registered? Can we fix this by starting ConnectivityService before WifiService?
        if (sCM == null) {
            // Getting a ConnectivityManager does not leak the calling context, because it stores
            // the application context and not the calling context.
            sCM = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
            if (sCM == null) {
                throw new IllegalStateException("Bad luck, ConnectivityService not started.");
            }
        }
    }

    private static class Callback extends NetworkCallback {
        @Override
        public void onAvailable(Network network) {
            synchronized(sLock) {
                if (this != sCallback) return;

                if (sCM.getBoundNetworkForProcess() == null && sNetwork == null) {
                    sCM.bindProcessToNetwork(network);
                    sNetwork = network;
                    Log.d(TAG, "Wifi alternate reality enabled on network " + network);
                }
                sLock.notify();
            }
        }

        @Override
        public void onLost(Network network) {
            synchronized (sLock) {
                if (this != sCallback) return;

                if (network.equals(sNetwork) && network.equals(sCM.getBoundNetworkForProcess())) {
                    unpin();
                    Log.d(TAG, "Wifi alternate reality disabled on network " + network);
                }
                sLock.notify();
            }
        }
    }

    public static void pin(Context context, NetworkRequest request) {
        synchronized (sLock) {
            if (sCallback == null) {
                maybeInitConnectivityManager(context);
                sCallback = new Callback();
                try {
                    sCM.registerNetworkCallback(request, sCallback);
                } catch (SecurityException e) {
                    Log.d(TAG, "Failed to register network callback", e);
                    sCallback = null;
                }
            }
        }
    }

    public static void unpin() {
        synchronized (sLock) {
            if (sCallback != null) {
                try {
                    sCM.bindProcessToNetwork(null);
                    sCM.unregisterNetworkCallback(sCallback);
                } catch (SecurityException e) {
                    Log.d(TAG, "Failed to unregister network callback", e);
                }
                sCallback = null;
                sNetwork = null;
            }
        }
    }
}
