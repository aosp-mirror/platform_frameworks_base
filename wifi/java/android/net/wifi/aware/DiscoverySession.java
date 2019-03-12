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
import android.util.Log;

import com.android.internal.annotations.VisibleForTesting;

import dalvik.system.CloseGuard;

import java.lang.ref.WeakReference;

/**
 * A class representing a single publish or subscribe Aware session. This object
 * will not be created directly - only its child classes are available:
 * {@link PublishDiscoverySession} and {@link SubscribeDiscoverySession}. This
 * class provides functionality common to both publish and subscribe discovery sessions:
 * <ul>
 *      <li>Sending messages: {@link #sendMessage(PeerHandle, int, byte[])} method.
 *      <li>Creating a network-specifier when requesting a Aware connection using
 *      {@link WifiAwareNetworkSpecifier.Builder}.
 * </ul>
 * <p>
 * The {@link #close()} method must be called to destroy discovery sessions once they are
 * no longer needed.
 */
public class DiscoverySession implements AutoCloseable {
    private static final String TAG = "DiscoverySession";
    private static final boolean DBG = false;
    private static final boolean VDBG = false; // STOPSHIP if true

    private static final int MAX_SEND_RETRY_COUNT = 5;

    /** @hide */
    protected WeakReference<WifiAwareManager> mMgr;
    /** @hide */
    protected final int mClientId;
    /** @hide */
    protected final int mSessionId;
    /** @hide */
    protected boolean mTerminated = false;

    private final CloseGuard mCloseGuard = CloseGuard.get();

    /**
     * Return the maximum permitted retry count when sending messages using
     * {@link #sendMessage(PeerHandle, int, byte[], int)}.
     *
     * @return Maximum retry count when sending messages.
     *
     * @hide
     */
    public static int getMaxSendRetryCount() {
        return MAX_SEND_RETRY_COUNT;
    }

    /** @hide */
    public DiscoverySession(WifiAwareManager manager, int clientId, int sessionId) {
        if (VDBG) {
            Log.v(TAG, "New discovery session created: manager=" + manager + ", clientId="
                    + clientId + ", sessionId=" + sessionId);
        }

        mMgr = new WeakReference<>(manager);
        mClientId = clientId;
        mSessionId = sessionId;

        mCloseGuard.open("close");
    }

    /**
     * Destroy the publish or subscribe session - free any resources, and stop
     * transmitting packets on-air (for an active session) or listening for
     * matches (for a passive session). The session may not be used for any
     * additional operations after its destruction.
     * <p>
     *     This operation must be done on a session which is no longer needed. Otherwise system
     *     resources will continue to be utilized until the application exits. The only
     *     exception is a session for which we received a termination callback,
     *     {@link DiscoverySessionCallback#onSessionTerminated()}.
     */
    @Override
    public void close() {
        WifiAwareManager mgr = mMgr.get();
        if (mgr == null) {
            Log.w(TAG, "destroy: called post GC on WifiAwareManager");
            return;
        }
        mgr.terminateSession(mClientId, mSessionId);
        mTerminated = true;
        mMgr.clear();
        mCloseGuard.close();
    }

    /**
     * Sets the status of the session to terminated - i.e. an indication that
     * already terminated rather than executing a termination.
     *
     * @hide
     */
    public void setTerminated() {
        if (mTerminated) {
            Log.w(TAG, "terminate: already terminated.");
            return;
        }

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
     * Access the discovery session ID of the Aware session.
     *
     * Note: internal visibility for testing.
     *
     * @return The internal discovery session ID.
     *
     * @hide
     */
    @VisibleForTesting
    public int getSessionId() {
        return mSessionId;
    }

    /**
     * Sends a message to the specified destination. Aware messages are transmitted in the context
     * of a discovery session - executed subsequent to a publish/subscribe
     * {@link DiscoverySessionCallback#onServiceDiscovered(PeerHandle,
     * byte[], java.util.List)} event.
     * <p>
     *     Aware messages are not guaranteed delivery. Callbacks on
     *     {@link DiscoverySessionCallback} indicate message was transmitted successfully,
     *     {@link DiscoverySessionCallback#onMessageSendSucceeded(int)}, or transmission
     *     failed (possibly after several retries) -
     *     {@link DiscoverySessionCallback#onMessageSendFailed(int)}.
     * <p>
     *     The peer will get a callback indicating a message was received using
     *     {@link DiscoverySessionCallback#onMessageReceived(PeerHandle,
     *     byte[])}.
     *
     * @param peerHandle The peer's handle for the message. Must be a result of an
     * {@link DiscoverySessionCallback#onServiceDiscovered(PeerHandle,
     * byte[], java.util.List)} or
     * {@link DiscoverySessionCallback#onMessageReceived(PeerHandle,
     * byte[])} events.
     * @param messageId An arbitrary integer used by the caller to identify the message. The same
     *            integer ID will be returned in the callbacks indicating message send success or
     *            failure. The {@code messageId} is not used internally by the Aware service - it
     *                  can be arbitrary and non-unique.
     * @param message The message to be transmitted.
     * @param retryCount An integer specifying how many additional service-level (as opposed to PHY
     *            or MAC level) retries should be attempted if there is no ACK from the receiver
     *            (note: no retransmissions are attempted in other failure cases). A value of 0
     *            indicates no retries. Max permitted value is {@link #getMaxSendRetryCount()}.
     *
     * @hide
     */
    public void sendMessage(@NonNull PeerHandle peerHandle, int messageId,
            @Nullable byte[] message, int retryCount) {
        if (mTerminated) {
            Log.w(TAG, "sendMessage: called on terminated session");
            return;
        }

        WifiAwareManager mgr = mMgr.get();
        if (mgr == null) {
            Log.w(TAG, "sendMessage: called post GC on WifiAwareManager");
            return;
        }

        mgr.sendMessage(mClientId, mSessionId, peerHandle, message, messageId, retryCount);
    }

    /**
     * Sends a message to the specified destination. Aware messages are transmitted in the context
     * of a discovery session - executed subsequent to a publish/subscribe
     * {@link DiscoverySessionCallback#onServiceDiscovered(PeerHandle,
     * byte[], java.util.List)} event.
     * <p>
     *     Aware messages are not guaranteed delivery. Callbacks on
     *     {@link DiscoverySessionCallback} indicate message was transmitted successfully,
     *     {@link DiscoverySessionCallback#onMessageSendSucceeded(int)}, or transmission
     *     failed (possibly after several retries) -
     *     {@link DiscoverySessionCallback#onMessageSendFailed(int)}.
     * <p>
     * The peer will get a callback indicating a message was received using
     * {@link DiscoverySessionCallback#onMessageReceived(PeerHandle,
     * byte[])}.
     *
     * @param peerHandle The peer's handle for the message. Must be a result of an
     * {@link DiscoverySessionCallback#onServiceDiscovered(PeerHandle,
     * byte[], java.util.List)} or
     * {@link DiscoverySessionCallback#onMessageReceived(PeerHandle,
     * byte[])} events.
     * @param messageId An arbitrary integer used by the caller to identify the message. The same
     *            integer ID will be returned in the callbacks indicating message send success or
     *            failure. The {@code messageId} is not used internally by the Aware service - it
     *                  can be arbitrary and non-unique.
     * @param message The message to be transmitted.
     */
    public void sendMessage(@NonNull PeerHandle peerHandle, int messageId,
            @Nullable byte[] message) {
        sendMessage(peerHandle, messageId, message, 0);
    }

    /**
     * Create a {@link android.net.NetworkRequest.Builder#setNetworkSpecifier(NetworkSpecifier)} for
     * an unencrypted WiFi Aware connection (link) to the specified peer. The
     * {@link android.net.NetworkRequest.Builder#addTransportType(int)} should be set to
     * {@link android.net.NetworkCapabilities#TRANSPORT_WIFI_AWARE}.
     * <p>
     * This method should be used when setting up a connection with a peer discovered through Aware
     * discovery or communication (in such scenarios the MAC address of the peer is shielded by
     * an opaque peer ID handle). If an Aware connection is needed to a peer discovered using other
     * OOB (out-of-band) mechanism then use the alternative
     * {@link WifiAwareSession#createNetworkSpecifierOpen(int, byte[])} method - which uses the
     * peer's MAC address.
     * <p>
     * Note: per the Wi-Fi Aware specification the roles are fixed - a Subscriber is an INITIATOR
     * and a Publisher is a RESPONDER.
     * <p>
     * To set up an encrypted link use the
     * {@link #createNetworkSpecifierPassphrase(PeerHandle, String)} API.
     * @deprecated Use the replacement {@link WifiAwareNetworkSpecifier.Builder}.
     *
     * @param peerHandle The peer's handle obtained through
     * {@link DiscoverySessionCallback#onServiceDiscovered(PeerHandle, byte[], java.util.List)}
     *                   or
     *                   {@link DiscoverySessionCallback#onMessageReceived(PeerHandle, byte[])}.
     *                   On a RESPONDER this value is used to gate the acceptance of a connection
     *                   request from only that peer.
     *
     * @return A {@link NetworkSpecifier} to be used to construct
     * {@link android.net.NetworkRequest.Builder#setNetworkSpecifier(NetworkSpecifier)} to pass to
     * {@link android.net.ConnectivityManager#requestNetwork(android.net.NetworkRequest,
     * android.net.ConnectivityManager.NetworkCallback)}
     * [or other varieties of that API].
     */
    @Deprecated
    public NetworkSpecifier createNetworkSpecifierOpen(@NonNull PeerHandle peerHandle) {
        if (mTerminated) {
            Log.w(TAG, "createNetworkSpecifierOpen: called on terminated session");
            return null;
        }

        WifiAwareManager mgr = mMgr.get();
        if (mgr == null) {
            Log.w(TAG, "createNetworkSpecifierOpen: called post GC on WifiAwareManager");
            return null;
        }

        int role = this instanceof SubscribeDiscoverySession
                ? WifiAwareManager.WIFI_AWARE_DATA_PATH_ROLE_INITIATOR
                : WifiAwareManager.WIFI_AWARE_DATA_PATH_ROLE_RESPONDER;

        return mgr.createNetworkSpecifier(mClientId, role, mSessionId, peerHandle, null, null);
    }

    /**
     * Create a {@link android.net.NetworkRequest.Builder#setNetworkSpecifier(NetworkSpecifier)} for
     * an encrypted WiFi Aware connection (link) to the specified peer. The
     * {@link android.net.NetworkRequest.Builder#addTransportType(int)} should be set to
     * {@link android.net.NetworkCapabilities#TRANSPORT_WIFI_AWARE}.
     * <p>
     * This method should be used when setting up a connection with a peer discovered through Aware
     * discovery or communication (in such scenarios the MAC address of the peer is shielded by
     * an opaque peer ID handle). If an Aware connection is needed to a peer discovered using other
     * OOB (out-of-band) mechanism then use the alternative
     * {@link WifiAwareSession#createNetworkSpecifierPassphrase(int, byte[], String)} method -
     * which uses the peer's MAC address.
     * <p>
     * Note: per the Wi-Fi Aware specification the roles are fixed - a Subscriber is an INITIATOR
     * and a Publisher is a RESPONDER.
     * @deprecated Use the replacement {@link WifiAwareNetworkSpecifier.Builder}.
     *
     * @param peerHandle The peer's handle obtained through
     * {@link DiscoverySessionCallback#onServiceDiscovered(PeerHandle,
     * byte[], java.util.List)} or
     * {@link DiscoverySessionCallback#onMessageReceived(PeerHandle,
     * byte[])}. On a RESPONDER this value is used to gate the acceptance of a connection request
     *                   from only that peer.
     * @param passphrase The passphrase to be used to encrypt the link. The PMK is generated from
     *                   the passphrase. Use the
     *                   {@link #createNetworkSpecifierOpen(PeerHandle)} API to
     *                   specify an open (unencrypted) link.
     *
     * @return A {@link NetworkSpecifier} to be used to construct
     * {@link android.net.NetworkRequest.Builder#setNetworkSpecifier(NetworkSpecifier)} to pass to
     * {@link android.net.ConnectivityManager#requestNetwork(android.net.NetworkRequest,
     * android.net.ConnectivityManager.NetworkCallback)}
     * [or other varieties of that API].
     */
    @Deprecated
    public NetworkSpecifier createNetworkSpecifierPassphrase(
            @NonNull PeerHandle peerHandle, @NonNull String passphrase) {
        if (!WifiAwareUtils.validatePassphrase(passphrase)) {
            throw new IllegalArgumentException("Passphrase must meet length requirements");
        }

        if (mTerminated) {
            Log.w(TAG, "createNetworkSpecifierPassphrase: called on terminated session");
            return null;
        }

        WifiAwareManager mgr = mMgr.get();
        if (mgr == null) {
            Log.w(TAG, "createNetworkSpecifierPassphrase: called post GC on WifiAwareManager");
            return null;
        }

        int role = this instanceof SubscribeDiscoverySession
                ? WifiAwareManager.WIFI_AWARE_DATA_PATH_ROLE_INITIATOR
                : WifiAwareManager.WIFI_AWARE_DATA_PATH_ROLE_RESPONDER;

        return mgr.createNetworkSpecifier(mClientId, role, mSessionId, peerHandle, null,
                passphrase);
    }

    /**
     * Create a {@link android.net.NetworkRequest.Builder#setNetworkSpecifier(NetworkSpecifier)} for
     * an encrypted WiFi Aware connection (link) to the specified peer. The
     * {@link android.net.NetworkRequest.Builder#addTransportType(int)} should be set to
     * {@link android.net.NetworkCapabilities#TRANSPORT_WIFI_AWARE}.
     * <p>
     * This method should be used when setting up a connection with a peer discovered through Aware
     * discovery or communication (in such scenarios the MAC address of the peer is shielded by
     * an opaque peer ID handle). If an Aware connection is needed to a peer discovered using other
     * OOB (out-of-band) mechanism then use the alternative
     * {@link WifiAwareSession#createNetworkSpecifierPmk(int, byte[], byte[])} method - which uses
     * the peer's MAC address.
     * <p>
     * Note: per the Wi-Fi Aware specification the roles are fixed - a Subscriber is an INITIATOR
     * and a Publisher is a RESPONDER.
     * @deprecated Use the replacement {@link WifiAwareNetworkSpecifier.Builder}.
     *
     * @param peerHandle The peer's handle obtained through
     * {@link DiscoverySessionCallback#onServiceDiscovered(PeerHandle,
     * byte[], java.util.List)} or
     * {@link DiscoverySessionCallback#onMessageReceived(PeerHandle,
     * byte[])}. On a RESPONDER this value is used to gate the acceptance of a connection request
     *                   from only that peer.
     * @param pmk A PMK (pairwise master key, see IEEE 802.11i) specifying the key to use for
     *            encrypting the data-path. Use the
     *            {@link #createNetworkSpecifierPassphrase(PeerHandle, String)} to specify a
     *            Passphrase or {@link #createNetworkSpecifierOpen(PeerHandle)} to specify an
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
    @Deprecated
    @SystemApi
    public NetworkSpecifier createNetworkSpecifierPmk(@NonNull PeerHandle peerHandle,
            @NonNull byte[] pmk) {
        if (!WifiAwareUtils.validatePmk(pmk)) {
            throw new IllegalArgumentException("PMK must 32 bytes");
        }

        if (mTerminated) {
            Log.w(TAG, "createNetworkSpecifierPmk: called on terminated session");
            return null;
        }

        WifiAwareManager mgr = mMgr.get();
        if (mgr == null) {
            Log.w(TAG, "createNetworkSpecifierPmk: called post GC on WifiAwareManager");
            return null;
        }

        int role = this instanceof SubscribeDiscoverySession
                ? WifiAwareManager.WIFI_AWARE_DATA_PATH_ROLE_INITIATOR
                : WifiAwareManager.WIFI_AWARE_DATA_PATH_ROLE_RESPONDER;

        return mgr.createNetworkSpecifier(mClientId, role, mSessionId, peerHandle, pmk, null);
    }
}
