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

package android.net.wifi.aware;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.SystemApi;
import android.net.NetworkSpecifier;
import android.os.Binder;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import com.android.internal.annotations.VisibleForTesting;

import dalvik.system.CloseGuard;

import java.lang.ref.WeakReference;

/**
 * This class represents a Wi-Fi Aware session - an attachment to the Wi-Fi Aware service through
 * which the app can execute discovery operations.
 */
public class WifiAwareSession implements AutoCloseable {
    private static final String TAG = "WifiAwareSession";
    private static final boolean DBG = false;
    private static final boolean VDBG = false; // STOPSHIP if true

    private final WeakReference<WifiAwareManager> mMgr;
    private final Binder mBinder;
    private final int mClientId;

    private boolean mTerminated = true;
    private final CloseGuard mCloseGuard = CloseGuard.get();

    /** @hide */
    public WifiAwareSession(WifiAwareManager manager, Binder binder, int clientId) {
        if (VDBG) Log.v(TAG, "New session created: manager=" + manager + ", clientId=" + clientId);

        mMgr = new WeakReference<>(manager);
        mBinder = binder;
        mClientId = clientId;
        mTerminated = false;

        mCloseGuard.open("close");
    }

    /**
     * Destroy the Wi-Fi Aware service session and, if no other applications are attached to Aware,
     * also disable Aware. This method destroys all outstanding operations - i.e. all publish and
     * subscribes are terminated, and any outstanding data-links are shut-down. However, it is
     * good practice to destroy these discovery sessions and connections explicitly before a
     * session-wide destroy.
     * <p>
     * An application may re-attach after a destroy using
     * {@link WifiAwareManager#attach(AttachCallback, Handler)} .
     */
    @Override
    public void close() {
        WifiAwareManager mgr = mMgr.get();
        if (mgr == null) {
            Log.w(TAG, "destroy: called post GC on WifiAwareManager");
            return;
        }
        mgr.disconnect(mClientId, mBinder);
        mTerminated = true;
        mMgr.clear();
        mCloseGuard.close();
    }

    /** @hide */
    @Override
    protected void finalize() throws Throwable {
        try {
            if (mCloseGuard != null) {
                mCloseGuard.warnIfOpen();
            }

            if (!mTerminated) {
                close();
            }
        } finally {
            super.finalize();
        }
    }

    /**
     * Access the client ID of the Aware session.
     *
     * Note: internal visibility for testing.
     *
     * @return The internal client ID.
     *
     * @hide
     */
    @VisibleForTesting
    public int getClientId() {
        return mClientId;
    }

    /**
     * Issue a request to the Aware service to create a new Aware publish discovery session, using
     * the specified {@code publishConfig} configuration. The results of the publish operation
     * are routed to the callbacks of {@link DiscoverySessionCallback}:
     * <ul>
     *     <li>
     *     {@link DiscoverySessionCallback#onPublishStarted(
     *PublishDiscoverySession)}
     *     is called when the publish session is created and provides a handle to the session.
     *     Further operations on the publish session can be executed on that object.
     *     <li>{@link DiscoverySessionCallback#onSessionConfigFailed()} is called if the
     *     publish operation failed.
     * </ul>
     * <p>
     * Other results of the publish session operations will also be routed to callbacks
     * on the {@code callback} object. The resulting publish session can be modified using
     * {@link PublishDiscoverySession#updatePublish(PublishConfig)}.
     * <p>
     *      An application must use the {@link DiscoverySession#close()} to
     *      terminate the publish discovery session once it isn't needed. This will free
     *      resources as well terminate any on-air transmissions.
     * <p>The application must have the {@link android.Manifest.permission#ACCESS_FINE_LOCATION}
     * permission to start a publish discovery session.
     *
     * @param publishConfig The {@link PublishConfig} specifying the
     *            configuration of the requested publish session.
     * @param callback A {@link DiscoverySessionCallback} derived object to be used for
     *                 session event callbacks.
     * @param handler The Handler on whose thread to execute the callbacks of the {@code
     * callback} object. If a null is provided then the application's main thread will be used.
     */
    public void publish(@NonNull PublishConfig publishConfig,
            @NonNull DiscoverySessionCallback callback, @Nullable Handler handler) {
        WifiAwareManager mgr = mMgr.get();
        if (mgr == null) {
            Log.e(TAG, "publish: called post GC on WifiAwareManager");
            return;
        }
        if (mTerminated) {
            Log.e(TAG, "publish: called after termination");
            return;
        }
        mgr.publish(mClientId, (handler == null) ? Looper.getMainLooper() : handler.getLooper(),
                publishConfig, callback);
    }

    /**
     * Issue a request to the Aware service to create a new Aware subscribe discovery session, using
     * the specified {@code subscribeConfig} configuration. The results of the subscribe
     * operation are routed to the callbacks of {@link DiscoverySessionCallback}:
     * <ul>
     *     <li>
     *  {@link DiscoverySessionCallback#onSubscribeStarted(
     *SubscribeDiscoverySession)}
     *     is called when the subscribe session is created and provides a handle to the session.
     *     Further operations on the subscribe session can be executed on that object.
     *     <li>{@link DiscoverySessionCallback#onSessionConfigFailed()} is called if the
     *     subscribe operation failed.
     * </ul>
     * <p>
     * Other results of the subscribe session operations will also be routed to callbacks
     * on the {@code callback} object. The resulting subscribe session can be modified using
     * {@link SubscribeDiscoverySession#updateSubscribe(SubscribeConfig)}.
     * <p>
     *      An application must use the {@link DiscoverySession#close()} to
     *      terminate the subscribe discovery session once it isn't needed. This will free
     *      resources as well terminate any on-air transmissions.
     * <p>The application must have the {@link android.Manifest.permission#ACCESS_FINE_LOCATION}
     * permission to start a subscribe discovery session.
     *
     * @param subscribeConfig The {@link SubscribeConfig} specifying the
     *            configuration of the requested subscribe session.
     * @param callback A {@link DiscoverySessionCallback} derived object to be used for
     *                 session event callbacks.
     * @param handler The Handler on whose thread to execute the callbacks of the {@code
     * callback} object. If a null is provided then the application's main thread will be used.
     */
    public void subscribe(@NonNull SubscribeConfig subscribeConfig,
            @NonNull DiscoverySessionCallback callback, @Nullable Handler handler) {
        WifiAwareManager mgr = mMgr.get();
        if (mgr == null) {
            Log.e(TAG, "publish: called post GC on WifiAwareManager");
            return;
        }
        if (mTerminated) {
            Log.e(TAG, "publish: called after termination");
            return;
        }
        mgr.subscribe(mClientId, (handler == null) ? Looper.getMainLooper() : handler.getLooper(),
                subscribeConfig, callback);
    }

    /**
     * Create a {@link android.net.NetworkRequest.Builder#setNetworkSpecifier(NetworkSpecifier)} for
     * an unencrypted WiFi Aware connection (link) to the specified peer. The
     * {@link android.net.NetworkRequest.Builder#addTransportType(int)} should be set to
     * {@link android.net.NetworkCapabilities#TRANSPORT_WIFI_AWARE}.
     * <p>
     *     This API is targeted for applications which can obtain the peer MAC address using OOB
     *     (out-of-band) discovery. Aware discovery does not provide the MAC address of the peer -
     *     when using Aware discovery use the alternative network specifier method -
     *     {@link android.net.wifi.aware.WifiAwareNetworkSpecifier.Builder}.
     * <p>
     * To set up an encrypted link use the
     * {@link #createNetworkSpecifierPassphrase(int, byte[], String)} API.
     *
     * @param role  The role of this device:
     *              {@link WifiAwareManager#WIFI_AWARE_DATA_PATH_ROLE_INITIATOR} or
     *              {@link WifiAwareManager#WIFI_AWARE_DATA_PATH_ROLE_RESPONDER}
     * @param peer  The MAC address of the peer's Aware discovery interface. On a RESPONDER this
     *              value is used to gate the acceptance of a connection request from only that
     *              peer.
     *
     * @return A {@link NetworkSpecifier} to be used to construct
     * {@link android.net.NetworkRequest.Builder#setNetworkSpecifier(NetworkSpecifier)} to pass to
     * {@link android.net.ConnectivityManager#requestNetwork(android.net.NetworkRequest,
     * android.net.ConnectivityManager.NetworkCallback)}
     * [or other varieties of that API].
     */
    public NetworkSpecifier createNetworkSpecifierOpen(
            @WifiAwareManager.DataPathRole int role, @NonNull byte[] peer) {
        WifiAwareManager mgr = mMgr.get();
        if (mgr == null) {
            Log.e(TAG, "createNetworkSpecifierOpen: called post GC on WifiAwareManager");
            return null;
        }
        if (mTerminated) {
            Log.e(TAG, "createNetworkSpecifierOpen: called after termination");
            return null;
        }
        return mgr.createNetworkSpecifier(mClientId, role, peer, null, null);
    }

    /**
     * Create a {@link android.net.NetworkRequest.Builder#setNetworkSpecifier(NetworkSpecifier)} for
     * an encrypted WiFi Aware connection (link) to the specified peer. The
     * {@link android.net.NetworkRequest.Builder#addTransportType(int)} should be set to
     * {@link android.net.NetworkCapabilities#TRANSPORT_WIFI_AWARE}.
     * <p>
     *     This API is targeted for applications which can obtain the peer MAC address using OOB
     *     (out-of-band) discovery. Aware discovery does not provide the MAC address of the peer -
     *     when using Aware discovery use the alternative network specifier method -
     *     {@link android.net.wifi.aware.WifiAwareNetworkSpecifier.Builder}.
     *
     * @param role  The role of this device:
     *              {@link WifiAwareManager#WIFI_AWARE_DATA_PATH_ROLE_INITIATOR} or
     *              {@link WifiAwareManager#WIFI_AWARE_DATA_PATH_ROLE_RESPONDER}
     * @param peer  The MAC address of the peer's Aware discovery interface. On a RESPONDER this
     *              value is used to gate the acceptance of a connection request from only that
     *              peer.
     * @param passphrase The passphrase to be used to encrypt the link. The PMK is generated from
     *                   the passphrase. Use {@link #createNetworkSpecifierOpen(int, byte[])} to
     *                   specify an open (unencrypted) link.
     *
     * @return A {@link NetworkSpecifier} to be used to construct
     * {@link android.net.NetworkRequest.Builder#setNetworkSpecifier(NetworkSpecifier)} to pass to
     * {@link android.net.ConnectivityManager#requestNetwork(android.net.NetworkRequest,
     * android.net.ConnectivityManager.NetworkCallback)}
     * [or other varieties of that API].
     */
    public NetworkSpecifier createNetworkSpecifierPassphrase(
            @WifiAwareManager.DataPathRole int role, @NonNull byte[] peer,
            @NonNull String passphrase) {
        WifiAwareManager mgr = mMgr.get();
        if (mgr == null) {
            Log.e(TAG, "createNetworkSpecifierPassphrase: called post GC on WifiAwareManager");
            return null;
        }
        if (mTerminated) {
            Log.e(TAG, "createNetworkSpecifierPassphrase: called after termination");
            return null;
        }
        if (!WifiAwareUtils.validatePassphrase(passphrase)) {
            throw new IllegalArgumentException("Passphrase must meet length requirements");
        }

        return mgr.createNetworkSpecifier(mClientId, role, peer, null, passphrase);
    }

    /**
     * Create a {@link android.net.NetworkRequest.Builder#setNetworkSpecifier(NetworkSpecifier)} for
     * an encrypted WiFi Aware connection (link) to the specified peer. The
     * {@link android.net.NetworkRequest.Builder#addTransportType(int)} should be set to
     * {@link android.net.NetworkCapabilities#TRANSPORT_WIFI_AWARE}.
     * <p>
     *     This API is targeted for applications which can obtain the peer MAC address using OOB
     *     (out-of-band) discovery. Aware discovery does not provide the MAC address of the peer -
     *     when using Aware discovery use the alternative network specifier method -
     *     {@link android.net.wifi.aware.WifiAwareNetworkSpecifier.Builder}.
     *
     * @param role  The role of this device:
     *              {@link WifiAwareManager#WIFI_AWARE_DATA_PATH_ROLE_INITIATOR} or
     *              {@link WifiAwareManager#WIFI_AWARE_DATA_PATH_ROLE_RESPONDER}
     * @param peer  The MAC address of the peer's Aware discovery interface. On a RESPONDER this
     *              value is used to gate the acceptance of a connection request from only that
     *              peer.
     * @param pmk A PMK (pairwise master key, see IEEE 802.11i) specifying the key to use for
     *            encrypting the data-path. Use the
     *            {@link #createNetworkSpecifierPassphrase(int, byte[], String)} to specify a
     *            Passphrase or {@link #createNetworkSpecifierOpen(int, byte[])} to specify an
     *            open (unencrypted) link.
     *
     * @return A {@link NetworkSpecifier} to be used to construct
     * {@link android.net.NetworkRequest.Builder#setNetworkSpecifier(NetworkSpecifier)} to pass to
     * {@link android.net.ConnectivityManager#requestNetwork(android.net.NetworkRequest,
     * android.net.ConnectivityManager.NetworkCallback)}
     * [or other varieties of that API].
     *
     * @hide
     */
    @SystemApi
    public NetworkSpecifier createNetworkSpecifierPmk(
            @WifiAwareManager.DataPathRole int role, @NonNull byte[] peer, @NonNull byte[] pmk) {
        WifiAwareManager mgr = mMgr.get();
        if (mgr == null) {
            Log.e(TAG, "createNetworkSpecifierPmk: called post GC on WifiAwareManager");
            return null;
        }
        if (mTerminated) {
            Log.e(TAG, "createNetworkSpecifierPmk: called after termination");
            return null;
        }
        if (!WifiAwareUtils.validatePmk(pmk)) {
            throw new IllegalArgumentException("PMK must 32 bytes");
        }
        return mgr.createNetworkSpecifier(mClientId, role, peer, pmk, null);
    }
}
