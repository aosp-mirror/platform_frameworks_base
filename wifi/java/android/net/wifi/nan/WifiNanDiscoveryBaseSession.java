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

package android.net.wifi.nan;

import android.annotation.Nullable;
import android.annotation.SystemApi;
import android.net.wifi.RttManager;
import android.util.Log;

import dalvik.system.CloseGuard;

import java.lang.ref.WeakReference;

/**
 * A class representing a single publish or subscribe NAN session. This object
 * will not be created directly - only its child classes are available:
 * {@link WifiNanPublishDiscoverySession} and {@link WifiNanSubscribeDiscoverySession}. This
 * class provides functionality common to both publish and subscribe discovery sessions:
 * <ul>
 *     <li>Sending messages: {@link #sendMessage(int, int, byte[])} or
 *     {@link #sendMessage(int, int, byte[], int)} methods.
 *     <li>Creating a network-specifier when requesting a NAN connection:
 *     {@link #createNetworkSpecifier(int, int, byte[])}.
 * </ul>
 * The {@link #destroy()} method must be called to destroy discovery sessions once they are
 * no longer needed.
 *
 * @hide PROPOSED_NAN_API
 */
public class WifiNanDiscoveryBaseSession {
    private static final String TAG = "WifiNanDiscoveryBaseSsn";
    private static final boolean DBG = false;
    private static final boolean VDBG = false; // STOPSHIP if true

    private static final int MAX_SEND_RETRY_COUNT = 5;

    /** @hide */
    protected WeakReference<WifiNanManager> mMgr;
    /** @hide */
    protected final int mClientId;
    /** @hide */
    protected final int mSessionId;
    /** @hide */
    protected boolean mTerminated = false;

    private final CloseGuard mCloseGuard = CloseGuard.get();

    /**
     * Return the maximum permitted retry count when sending messages using
     * {@link #sendMessage(int, int, byte[], int)}.
     *
     * @return Maximum retry count when sending messages.
     */
    public static int getMaxSendRetryCount() {
        return MAX_SEND_RETRY_COUNT;
    }

    /** @hide */
    public WifiNanDiscoveryBaseSession(WifiNanManager manager, int clientId, int sessionId) {
        if (VDBG) {
            Log.v(TAG, "New discovery session created: manager=" + manager + ", clientId="
                    + clientId + ", sessionId=" + sessionId);
        }

        mMgr = new WeakReference<>(manager);
        mClientId = clientId;
        mSessionId = sessionId;

        mCloseGuard.open("destroy");
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
     *     {@link WifiNanDiscoverySessionCallback#onSessionTerminated(int)}.
     */
    public void destroy() {
        WifiNanManager mgr = mMgr.get();
        if (mgr == null) {
            Log.w(TAG, "destroy: called post GC on WifiNanManager");
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
            if (!mTerminated) {
                mCloseGuard.warnIfOpen();
                destroy();
            }
        } finally {
            super.finalize();
        }
    }

    /**
     * Sends a message to the specified destination. NAN messages are transmitted in the context
     * of a discovery session - executed subsequent to a publish/subscribe
     * {@link WifiNanDiscoverySessionCallback#onServiceDiscovered(int, byte[], byte[])} event.
     * <p>
     *     NAN messages are not guaranteed delivery. Callbacks on
     *     {@link WifiNanDiscoverySessionCallback} indicate message was transmitted successfully,
     *     {@link WifiNanDiscoverySessionCallback#onMessageSent(int)}, or transmission failed
     *     (possibly after several retries) -
     *     {@link WifiNanDiscoverySessionCallback#onMessageSendFailed(int)}.
     * <p>
     *     The peer will get a callback indicating a message was received using
     *     {@link WifiNanDiscoverySessionCallback#onMessageReceived(int, byte[])}.
     *
     * @param peerId The peer's ID for the message. Must be a result of an
     *            {@link WifiNanDiscoverySessionCallback#onServiceDiscovered(int, byte[], byte[])}
     *               or
     *               {@link WifiNanDiscoverySessionCallback#onMessageReceived(int, byte[])} events.
     * @param messageId An arbitrary integer used by the caller to identify the message. The same
     *            integer ID will be returned in the callbacks indicating message send success or
     *            failure. The {@code messageId} is not used internally by the NAN service - it
     *                  can be arbitrary and non-unique.
     * @param message The message to be transmitted.
     * @param retryCount An integer specifying how many additional service-level (as opposed to PHY
     *            or MAC level) retries should be attempted if there is no ACK from the receiver
     *            (note: no retransmissions are attempted in other failure cases). A value of 0
     *            indicates no retries. Max permitted value is {@link #getMaxSendRetryCount()}.
     */
    public void sendMessage(int peerId, int messageId, @Nullable byte[] message, int retryCount) {
        if (mTerminated) {
            Log.w(TAG, "sendMessage: called on terminated session");
            return;
        } else {
            WifiNanManager mgr = mMgr.get();
            if (mgr == null) {
                Log.w(TAG, "sendMessage: called post GC on WifiNanManager");
                return;
            }

            mgr.sendMessage(mClientId, mSessionId, peerId, message, messageId, retryCount);
        }
    }

    /**
     * Sends a message to the specified destination. NAN messages are transmitted in the context
     * of a discovery session - executed subsequent to a publish/subscribe
     * {@link WifiNanDiscoverySessionCallback#onServiceDiscovered(int, byte[], byte[])} event.
     * <p>
     *     NAN messages are not guaranteed delivery. Callbacks on
     *     {@link WifiNanDiscoverySessionCallback} indicate message was transmitted successfully,
     *     {@link WifiNanDiscoverySessionCallback#onMessageSent(int)}, or transmission failed
     *     (possibly after several retries) -
     *     {@link WifiNanDiscoverySessionCallback#onMessageSendFailed(int)}.
     * <p>
     *     The peer will get a callback indicating a message was received using
     *     {@link WifiNanDiscoverySessionCallback#onMessageReceived(int, byte[])}.
     * Equivalent to {@link #sendMessage(int, int, byte[], int)} with a {@code retryCount} of
     * 0.
     *
     * @param peerId The peer's ID for the message. Must be a result of an
     *            {@link WifiNanDiscoverySessionCallback#onServiceDiscovered(int, byte[], byte[])}
     *               or
     *               {@link WifiNanDiscoverySessionCallback#onMessageReceived(int, byte[])} events.
     * @param messageId An arbitrary integer used by the caller to identify the message. The same
     *            integer ID will be returned in the callbacks indicating message send success or
     *            failure. The {@code messageId} is not used internally by the NAN service - it
     *                  can be arbitrary and non-unique.
     * @param message The message to be transmitted.
     */
    public void sendMessage(int peerId, int messageId, @Nullable byte[] message) {
        sendMessage(peerId, messageId, message, 0);
    }

    /**
     * Start a ranging operation with the specified peers. The peer IDs are obtained from an
     * {@link WifiNanDiscoverySessionCallback#onServiceDiscovered(int, byte[], byte[])} or
     * {@link WifiNanDiscoverySessionCallback#onMessageReceived(int, byte[])} operation - can only
     * range devices which are part of an ongoing discovery session.
     *
     * @param params   RTT parameters - each corresponding to a specific peer ID (the array sizes
     *                 must be identical). The
     *                 {@link android.net.wifi.RttManager.RttParams#bssid} member must be set to
     *                 a peer ID - not to a MAC address.
     * @param listener The listener to receive the results of the ranging session.
     * @hide PROPOSED_NAN_SYSTEM_API [TODO: b/28847998 - track RTT API & visilibity]
     */
    public void startRanging(RttManager.RttParams[] params, RttManager.RttListener listener) {
        if (mTerminated) {
            Log.w(TAG, "startRanging: called on terminated session");
            return;
        } else {
            WifiNanManager mgr = mMgr.get();
            if (mgr == null) {
                Log.w(TAG, "startRanging: called post GC on WifiNanManager");
                return;
            }

            mgr.startRanging(mClientId, mSessionId, params, listener);
        }
    }

    /**
     * Create a {@link android.net.NetworkRequest.Builder#setNetworkSpecifier(String)} for a
     * WiFi NAN connection to the specified peer. The
     * {@link android.net.NetworkRequest.Builder#addTransportType(int)} should be set to
     * {@link android.net.NetworkCapabilities#TRANSPORT_WIFI_NAN}.
     * <p>
     * This method should be used when setting up a connection with a peer discovered through NAN
     * discovery or communication (in such scenarios the MAC address of the peer is shielded by
     * an opaque peer ID handle). If a NAN connection is needed to a peer discovered using other
     * OOB (out-of-band) mechanism then use the alternative
     * {@link WifiNanSession#createNetworkSpecifier(int, byte[], byte[])} method - which uses the
     * peer's MAC address.
     *
     * @param role The role of this device:
     * {@link WifiNanManager#WIFI_NAN_DATA_PATH_ROLE_INITIATOR} or
     * {@link WifiNanManager#WIFI_NAN_DATA_PATH_ROLE_RESPONDER}
     * @param peerId The peer ID obtained through
     * {@link WifiNanDiscoverySessionCallback#onServiceDiscovered(int, byte[], byte[])} or
     * {@link WifiNanDiscoverySessionCallback#onMessageReceived(int, byte[])}. On a RESPONDER this
     *              value is used to gate the acceptance of a connection request from only that
     *              peer. A RESPONDER may specified a 0 - indicating that it will accept
     *              connection requests from any device.
     * @param token An arbitrary token (message) to be used to match connection initiation request
     *              to a responder setup. A RESPONDER is set up with a {@code token} which must
     *              be matched by the token provided by the INITIATOR. A null token is permitted
     *              on the RESPONDER and matches any peer token. An empty ({@code ""}) token is
     *              not the same as a null token and requires the peer token to be empty as well.
     *
     * @return A string to be used to construct
     * {@link android.net.NetworkRequest.Builder#setNetworkSpecifier(String)} to pass to
     * {@link android.net.ConnectivityManager#requestNetwork(android.net.NetworkRequest,android.net.ConnectivityManager.NetworkCallback)}
     * [or other varieties of that API].
     */
    public String createNetworkSpecifier(@WifiNanManager.DataPathRole int role, int peerId,
            @Nullable byte[] token) {
        if (mTerminated) {
            Log.w(TAG, "createNetworkSpecifier: called on terminated session");
            return null;
        } else {
            WifiNanManager mgr = mMgr.get();
            if (mgr == null) {
                Log.w(TAG, "createNetworkSpecifier: called post GC on WifiNanManager");
                return null;
            }

            return mgr.createNetworkSpecifier(mClientId, role, mSessionId, peerId, token);
        }
    }
}
