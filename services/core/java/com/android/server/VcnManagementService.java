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

package com.android.server;

import static java.util.Objects.requireNonNull;

import android.annotation.NonNull;
import android.content.Context;
import android.net.ConnectivityManager;
import android.net.NetworkProvider;
import android.net.NetworkRequest;
import android.net.vcn.IVcnManagementService;
import android.net.vcn.VcnConfig;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.ParcelUuid;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.annotations.VisibleForTesting.Visibility;

/**
 * VcnManagementService manages Virtual Carrier Network profiles and lifecycles.
 *
 * <pre>The internal structure of the VCN Management subsystem is as follows:
 *
 * +-------------------------+ 1:1                                +--------------------------------+
 * |  VcnManagementService   | ------------ Creates ------------> |  TelephonySubscriptionManager  |
 * |                         |                                    |                                |
 * |   Manages configs and   |                                    | Tracks subscriptions, carrier  |
 * | Vcn instance lifecycles | <--- Notifies of subscription & -- | privilege changes, caches maps |
 * +-------------------------+      carrier privilege changes     +--------------------------------+
 *      | 1:N          ^
 *      |              |
 *      |              +-------------------------------+
 *      +---------------+                              |
 *                      |                              |
 *         Creates when config present,                |
 *        subscription group active, and               |
 *      providing app is carrier privileged     Notifies of safe
 *                      |                      mode state changes
 *                      v                              |
 * +-----------------------------------------------------------------------+
 * |                                  Vcn                                  |
 * |                                                                       |
 * |       Manages GatewayConnection lifecycles based on fulfillable       |
 * |                NetworkRequest(s) and overall safe-mode                |
 * +-----------------------------------------------------------------------+
 *                      | 1:N                          ^
 *              Creates to fulfill                     |
 *           NetworkRequest(s), tears   Notifies of VcnGatewayConnection
 *          down when no longer needed   teardown (e.g. Network reaped)
 *                      |                 and safe-mode timer changes
 *                      v                              |
 * +-----------------------------------------------------------------------+
 * |                          VcnGatewayConnection                         |
 * |                                                                       |
 * |       Manages a single (IKEv2) tunnel session and NetworkAgent,       |
 * |  handles mobility events, (IPsec) Tunnel setup and safe-mode timers   |
 * +-----------------------------------------------------------------------+
 *                      | 1:1                          ^
 *                      |                              |
 *          Creates upon instantiation      Notifies of changes in
 *                      |                 selected underlying network
 *                      |                     or its properties
 *                      v                              |
 * +-----------------------------------------------------------------------+
 * |                       UnderlyingNetworkTracker                        |
 * |                                                                       |
 * | Manages lifecycle of underlying physical networks, filing requests to |
 * | bring them up, and releasing them as they become no longer necessary  |
 * +-----------------------------------------------------------------------+
 * </pre>
 *
 * @hide
 */
public class VcnManagementService extends IVcnManagementService.Stub {
    @NonNull private static final String TAG = VcnManagementService.class.getSimpleName();

    public static final boolean VDBG = false; // STOPSHIP: if true

    /* Binder context for this service */
    @NonNull private final Context mContext;
    @NonNull private final Dependencies mDeps;

    @NonNull private final Looper mLooper;
    @NonNull private final VcnNetworkProvider mNetworkProvider;

    @VisibleForTesting(visibility = Visibility.PRIVATE)
    VcnManagementService(@NonNull Context context, @NonNull Dependencies deps) {
        mContext = requireNonNull(context, "Missing context");
        mDeps = requireNonNull(deps, "Missing dependencies");

        mLooper = mDeps.getLooper();
        mNetworkProvider = new VcnNetworkProvider(mContext, mLooper);
    }

    // Package-visibility for SystemServer to create instances.
    static VcnManagementService create(@NonNull Context context) {
        return new VcnManagementService(context, new Dependencies());
    }

    /** External dependencies used by VcnManagementService, for injection in tests */
    @VisibleForTesting(visibility = Visibility.PRIVATE)
    public static class Dependencies {
        private HandlerThread mHandlerThread;

        /** Retrieves a looper for the VcnManagementService */
        public Looper getLooper() {
            if (mHandlerThread == null) {
                synchronized (this) {
                    if (mHandlerThread == null) {
                        mHandlerThread = new HandlerThread(TAG);
                        mHandlerThread.start();
                    }
                }
            }
            return mHandlerThread.getLooper();
        }
    }

    /** Notifies the VcnManagementService that external dependencies can be set up. */
    public void systemReady() {
        // TODO: Retrieve existing profiles from KeyStore

        mContext.getSystemService(ConnectivityManager.class)
                .registerNetworkProvider(mNetworkProvider);
    }

    /**
     * Sets a VCN config for a given subscription group.
     *
     * <p>Implements the IVcnManagementService Binder interface.
     */
    @Override
    public void setVcnConfig(@NonNull ParcelUuid subscriptionGroup, @NonNull VcnConfig config) {
        requireNonNull(subscriptionGroup, "subscriptionGroup was null");
        requireNonNull(config, "config was null");

        // TODO: Store VCN configuration, trigger startup as necessary
    }

    /**
     * Clears the VcnManagementService for a given subscription group.
     *
     * <p>Implements the IVcnManagementService Binder interface.
     */
    @Override
    public void clearVcnConfig(@NonNull ParcelUuid subscriptionGroup) {
        requireNonNull(subscriptionGroup, "subscriptionGroup was null");

        // TODO: Clear VCN configuration, trigger teardown as necessary
    }

    /**
     * Network provider for VCN networks.
     *
     * @hide
     */
    public class VcnNetworkProvider extends NetworkProvider {
        VcnNetworkProvider(Context context, Looper looper) {
            super(context, looper, VcnNetworkProvider.class.getSimpleName());
        }

        @Override
        public void onNetworkRequested(@NonNull NetworkRequest request, int score, int providerId) {
            // TODO: Handle network requests - Ensure VCN started, and start appropriate tunnels.
        }
    }
}
