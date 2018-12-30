/*
 * Copyright (C) 2014 The Android Open Source Project
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

package android.net;

import android.annotation.UnsupportedAppUsage;
import android.content.Context;
import android.net.ConnectivityManager.PacketKeepalive;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.os.Messenger;
import android.util.Log;

import com.android.internal.util.AsyncChannel;
import com.android.internal.util.Protocol;

import java.util.ArrayList;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * A Utility class for handling for communicating between bearer-specific
 * code and ConnectivityService.
 *
 * A bearer may have more than one NetworkAgent if it can simultaneously
 * support separate networks (IMS / Internet / MMS Apns on cellular, or
 * perhaps connections with different SSID or P2P for Wi-Fi).
 *
 * @hide
 */
public abstract class NetworkAgent extends Handler {
    // Guaranteed to be valid (not NETID_UNSET), otherwise registerNetworkAgent() would have thrown
    // an exception.
    public final int netId;

    private volatile AsyncChannel mAsyncChannel;
    private final String LOG_TAG;
    private static final boolean DBG = true;
    private static final boolean VDBG = false;
    private final Context mContext;
    private final ArrayList<Message>mPreConnectedQueue = new ArrayList<Message>();
    private volatile long mLastBwRefreshTime = 0;
    private static final long BW_REFRESH_MIN_WIN_MS = 500;
    private boolean mPollLceScheduled = false;
    private AtomicBoolean mPollLcePending = new AtomicBoolean(false);

    private static final int BASE = Protocol.BASE_NETWORK_AGENT;

    /**
     * Sent by ConnectivityService to the NetworkAgent to inform it of
     * suspected connectivity problems on its network.  The NetworkAgent
     * should take steps to verify and correct connectivity.
     */
    public static final int CMD_SUSPECT_BAD = BASE;

    /**
     * Sent by the NetworkAgent (note the EVENT vs CMD prefix) to
     * ConnectivityService to pass the current NetworkInfo (connection state).
     * Sent when the NetworkInfo changes, mainly due to change of state.
     * obj = NetworkInfo
     */
    public static final int EVENT_NETWORK_INFO_CHANGED = BASE + 1;

    /**
     * Sent by the NetworkAgent to ConnectivityService to pass the current
     * NetworkCapabilties.
     * obj = NetworkCapabilities
     */
    public static final int EVENT_NETWORK_CAPABILITIES_CHANGED = BASE + 2;

    /**
     * Sent by the NetworkAgent to ConnectivityService to pass the current
     * NetworkProperties.
     * obj = NetworkProperties
     */
    public static final int EVENT_NETWORK_PROPERTIES_CHANGED = BASE + 3;

    /* centralize place where base network score, and network score scaling, will be
     * stored, so as we can consistently compare apple and oranges, or wifi, ethernet and LTE
     */
    public static final int WIFI_BASE_SCORE = 60;

    /**
     * Sent by the NetworkAgent to ConnectivityService to pass the current
     * network score.
     * obj = network score Integer
     */
    public static final int EVENT_NETWORK_SCORE_CHANGED = BASE + 4;

    /**
     * Sent by ConnectivityService to the NetworkAgent to inform the agent of the
     * networks status - whether we could use the network or could not, due to
     * either a bad network configuration (no internet link) or captive portal.
     *
     * arg1 = either {@code VALID_NETWORK} or {@code INVALID_NETWORK}
     * obj = Bundle containing map from {@code REDIRECT_URL_KEY} to {@code String}
     *       representing URL that Internet probe was redirect to, if it was redirected,
     *       or mapping to {@code null} otherwise.
     */
    public static final int CMD_REPORT_NETWORK_STATUS = BASE + 7;

    public static final int VALID_NETWORK = 1;
    public static final int INVALID_NETWORK = 2;

    public static String REDIRECT_URL_KEY = "redirect URL";

     /**
     * Sent by the NetworkAgent to ConnectivityService to indicate this network was
     * explicitly selected.  This should be sent before the NetworkInfo is marked
     * CONNECTED so it can be given special treatment at that time.
     *
     * obj = boolean indicating whether to use this network even if unvalidated
     */
    public static final int EVENT_SET_EXPLICITLY_SELECTED = BASE + 8;

    /**
     * Sent by ConnectivityService to the NetworkAgent to inform the agent of
     * whether the network should in the future be used even if not validated.
     * This decision is made by the user, but it is the network transport's
     * responsibility to remember it.
     *
     * arg1 = 1 if true, 0 if false
     */
    public static final int CMD_SAVE_ACCEPT_UNVALIDATED = BASE + 9;

    /**
     * Sent by ConnectivityService to the NetworkAgent to inform the agent to pull
     * the underlying network connection for updated bandwidth information.
     */
    public static final int CMD_REQUEST_BANDWIDTH_UPDATE = BASE + 10;

    /**
     * Sent by ConnectivityService to the NetworkAgent to request that the specified packet be sent
     * periodically on the given interval.
     *
     *   arg1 = the slot number of the keepalive to start
     *   arg2 = interval in seconds
     *   obj = KeepalivePacketData object describing the data to be sent
     *
     * Also used internally by ConnectivityService / KeepaliveTracker, with different semantics.
     */
    public static final int CMD_START_PACKET_KEEPALIVE = BASE + 11;

    /**
     * Requests that the specified keepalive packet be stopped.
     *
     * arg1 = slot number of the keepalive to stop.
     *
     * Also used internally by ConnectivityService / KeepaliveTracker, with different semantics.
     */
    public static final int CMD_STOP_PACKET_KEEPALIVE = BASE + 12;

    /**
     * Sent by the NetworkAgent to ConnectivityService to provide status on a packet keepalive
     * request. This may either be the reply to a CMD_START_PACKET_KEEPALIVE, or an asynchronous
     * error notification.
     *
     * This is also sent by KeepaliveTracker to the app's ConnectivityManager.PacketKeepalive to
     * so that the app's PacketKeepaliveCallback methods can be called.
     *
     * arg1 = slot number of the keepalive
     * arg2 = error code
     */
    public static final int EVENT_PACKET_KEEPALIVE = BASE + 13;

    /**
     * Sent by ConnectivityService to inform this network transport of signal strength thresholds
     * that when crossed should trigger a system wakeup and a NetworkCapabilities update.
     *
     *   obj = int[] describing signal strength thresholds.
     */
    public static final int CMD_SET_SIGNAL_STRENGTH_THRESHOLDS = BASE + 14;

    /**
     * Sent by ConnectivityService to the NeworkAgent to inform the agent to avoid
     * automatically reconnecting to this network (e.g. via autojoin).  Happens
     * when user selects "No" option on the "Stay connected?" dialog box.
     */
    public static final int CMD_PREVENT_AUTOMATIC_RECONNECT = BASE + 15;

    public NetworkAgent(Looper looper, Context context, String logTag, NetworkInfo ni,
            NetworkCapabilities nc, LinkProperties lp, int score) {
        this(looper, context, logTag, ni, nc, lp, score, null);
    }

    public NetworkAgent(Looper looper, Context context, String logTag, NetworkInfo ni,
            NetworkCapabilities nc, LinkProperties lp, int score, NetworkMisc misc) {
        super(looper);
        LOG_TAG = logTag;
        mContext = context;
        if (ni == null || nc == null || lp == null) {
            throw new IllegalArgumentException();
        }

        if (VDBG) log("Registering NetworkAgent");
        ConnectivityManager cm = (ConnectivityManager)mContext.getSystemService(
                Context.CONNECTIVITY_SERVICE);
        netId = cm.registerNetworkAgent(new Messenger(this), new NetworkInfo(ni),
                new LinkProperties(lp), new NetworkCapabilities(nc), score, misc);
    }

    @Override
    public void handleMessage(Message msg) {
        switch (msg.what) {
            case AsyncChannel.CMD_CHANNEL_FULL_CONNECTION: {
                if (mAsyncChannel != null) {
                    log("Received new connection while already connected!");
                } else {
                    if (VDBG) log("NetworkAgent fully connected");
                    AsyncChannel ac = new AsyncChannel();
                    ac.connected(null, this, msg.replyTo);
                    ac.replyToMessage(msg, AsyncChannel.CMD_CHANNEL_FULLY_CONNECTED,
                            AsyncChannel.STATUS_SUCCESSFUL);
                    synchronized (mPreConnectedQueue) {
                        mAsyncChannel = ac;
                        for (Message m : mPreConnectedQueue) {
                            ac.sendMessage(m);
                        }
                        mPreConnectedQueue.clear();
                    }
                }
                break;
            }
            case AsyncChannel.CMD_CHANNEL_DISCONNECT: {
                if (VDBG) log("CMD_CHANNEL_DISCONNECT");
                if (mAsyncChannel != null) mAsyncChannel.disconnect();
                break;
            }
            case AsyncChannel.CMD_CHANNEL_DISCONNECTED: {
                if (DBG) log("NetworkAgent channel lost");
                // let the client know CS is done with us.
                unwanted();
                synchronized (mPreConnectedQueue) {
                    mAsyncChannel = null;
                }
                break;
            }
            case CMD_SUSPECT_BAD: {
                log("Unhandled Message " + msg);
                break;
            }
            case CMD_REQUEST_BANDWIDTH_UPDATE: {
                long currentTimeMs = System.currentTimeMillis();
                if (VDBG) {
                    log("CMD_REQUEST_BANDWIDTH_UPDATE request received.");
                }
                if (currentTimeMs >= (mLastBwRefreshTime + BW_REFRESH_MIN_WIN_MS)) {
                    mPollLceScheduled = false;
                    if (mPollLcePending.getAndSet(true) == false) {
                        pollLceData();
                    }
                } else {
                    // deliver the request at a later time rather than discard it completely.
                    if (!mPollLceScheduled) {
                        long waitTime = mLastBwRefreshTime + BW_REFRESH_MIN_WIN_MS -
                                currentTimeMs + 1;
                        mPollLceScheduled = sendEmptyMessageDelayed(
                                CMD_REQUEST_BANDWIDTH_UPDATE, waitTime);
                    }
                }
                break;
            }
            case CMD_REPORT_NETWORK_STATUS: {
                String redirectUrl = ((Bundle)msg.obj).getString(REDIRECT_URL_KEY);
                if (VDBG) {
                    log("CMD_REPORT_NETWORK_STATUS(" +
                            (msg.arg1 == VALID_NETWORK ? "VALID, " : "INVALID, ") + redirectUrl);
                }
                networkStatus(msg.arg1, redirectUrl);
                break;
            }
            case CMD_SAVE_ACCEPT_UNVALIDATED: {
                saveAcceptUnvalidated(msg.arg1 != 0);
                break;
            }
            case CMD_START_PACKET_KEEPALIVE: {
                startPacketKeepalive(msg);
                break;
            }
            case CMD_STOP_PACKET_KEEPALIVE: {
                stopPacketKeepalive(msg);
                break;
            }

            case CMD_SET_SIGNAL_STRENGTH_THRESHOLDS: {
                ArrayList<Integer> thresholds =
                        ((Bundle) msg.obj).getIntegerArrayList("thresholds");
                // TODO: Change signal strength thresholds API to use an ArrayList<Integer>
                // rather than convert to int[].
                int[] intThresholds = new int[(thresholds != null) ? thresholds.size() : 0];
                for (int i = 0; i < intThresholds.length; i++) {
                    intThresholds[i] = thresholds.get(i);
                }
                setSignalStrengthThresholds(intThresholds);
                break;
            }
            case CMD_PREVENT_AUTOMATIC_RECONNECT: {
                preventAutomaticReconnect();
                break;
            }
        }
    }

    private void queueOrSendMessage(int what, Object obj) {
        queueOrSendMessage(what, 0, 0, obj);
    }

    private void queueOrSendMessage(int what, int arg1, int arg2) {
        queueOrSendMessage(what, arg1, arg2, null);
    }

    private void queueOrSendMessage(int what, int arg1, int arg2, Object obj) {
        Message msg = Message.obtain();
        msg.what = what;
        msg.arg1 = arg1;
        msg.arg2 = arg2;
        msg.obj = obj;
        queueOrSendMessage(msg);
    }

    private void queueOrSendMessage(Message msg) {
        synchronized (mPreConnectedQueue) {
            if (mAsyncChannel != null) {
                mAsyncChannel.sendMessage(msg);
            } else {
                mPreConnectedQueue.add(msg);
            }
        }
    }

    /**
     * Called by the bearer code when it has new LinkProperties data.
     */
    public void sendLinkProperties(LinkProperties linkProperties) {
        queueOrSendMessage(EVENT_NETWORK_PROPERTIES_CHANGED, new LinkProperties(linkProperties));
    }

    /**
     * Called by the bearer code when it has new NetworkInfo data.
     */
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.P, trackingBug = 115609023)
    public void sendNetworkInfo(NetworkInfo networkInfo) {
        queueOrSendMessage(EVENT_NETWORK_INFO_CHANGED, new NetworkInfo(networkInfo));
    }

    /**
     * Called by the bearer code when it has new NetworkCapabilities data.
     */
    public void sendNetworkCapabilities(NetworkCapabilities networkCapabilities) {
        mPollLcePending.set(false);
        mLastBwRefreshTime = System.currentTimeMillis();
        queueOrSendMessage(EVENT_NETWORK_CAPABILITIES_CHANGED,
                new NetworkCapabilities(networkCapabilities));
    }

    /**
     * Called by the bearer code when it has a new score for this network.
     */
    public void sendNetworkScore(int score) {
        if (score < 0) {
            throw new IllegalArgumentException("Score must be >= 0");
        }
        queueOrSendMessage(EVENT_NETWORK_SCORE_CHANGED,  score, 0);
    }

    /**
     * Called by the bearer to indicate this network was manually selected by the user.
     * This should be called before the NetworkInfo is marked CONNECTED so that this
     * Network can be given special treatment at that time. If {@code acceptUnvalidated} is
     * {@code true}, then the system will switch to this network. If it is {@code false} and the
     * network cannot be validated, the system will ask the user whether to switch to this network.
     * If the user confirms and selects "don't ask again", then the system will call
     * {@link #saveAcceptUnvalidated} to persist the user's choice. Thus, if the transport ever
     * calls this method with {@code acceptUnvalidated} set to {@code false}, it must also implement
     * {@link #saveAcceptUnvalidated} to respect the user's choice.
     */
    public void explicitlySelected(boolean acceptUnvalidated) {
        queueOrSendMessage(EVENT_SET_EXPLICITLY_SELECTED, acceptUnvalidated ? 1 : 0, 0);
    }

    /**
     * Called when ConnectivityService has indicated they no longer want this network.
     * The parent factory should (previously) have received indication of the change
     * as well, either canceling NetworkRequests or altering their score such that this
     * network won't be immediately requested again.
     */
    abstract protected void unwanted();

    /**
     * Called when ConnectivityService request a bandwidth update. The parent factory
     * shall try to overwrite this method and produce a bandwidth update if capable.
     */
    protected void pollLceData() {
    }

    /**
     * Called when the system determines the usefulness of this network.
     *
     * Networks claiming internet connectivity will have their internet
     * connectivity verified.
     *
     * Currently there are two possible values:
     * {@code VALID_NETWORK} if the system is happy with the connection,
     * {@code INVALID_NETWORK} if the system is not happy.
     * TODO - add indications of captive portal-ness and related success/failure,
     * ie, CAPTIVE_SUCCESS_NETWORK, CAPTIVE_NETWORK for successful login and detection
     *
     * This may be called multiple times as the network status changes and may
     * generate false negatives if we lose ip connectivity before the link is torn down.
     *
     * @param status one of {@code VALID_NETWORK} or {@code INVALID_NETWORK}.
     * @param redirectUrl If the Internet probe was redirected, this is the destination it was
     *         redirected to, otherwise {@code null}.
     */
    protected void networkStatus(int status, String redirectUrl) {
    }

    /**
     * Called when the user asks to remember the choice to use this network even if unvalidated.
     * The transport is responsible for remembering the choice, and the next time the user connects
     * to the network, should explicitlySelected with {@code acceptUnvalidated} set to {@code true}.
     * This method will only be called if {@link #explicitlySelected} was called with
     * {@code acceptUnvalidated} set to {@code false}.
     */
    protected void saveAcceptUnvalidated(boolean accept) {
    }

    /**
     * Requests that the network hardware send the specified packet at the specified interval.
     */
    protected void startPacketKeepalive(Message msg) {
        onPacketKeepaliveEvent(msg.arg1, PacketKeepalive.ERROR_HARDWARE_UNSUPPORTED);
    }

    /**
     * Requests that the network hardware send the specified packet at the specified interval.
     */
    protected void stopPacketKeepalive(Message msg) {
        onPacketKeepaliveEvent(msg.arg1, PacketKeepalive.ERROR_HARDWARE_UNSUPPORTED);
    }

    /**
     * Called by the network when a packet keepalive event occurs.
     */
    public void onPacketKeepaliveEvent(int slot, int reason) {
        queueOrSendMessage(EVENT_PACKET_KEEPALIVE, slot, reason);
    }

    /**
     * Called by ConnectivityService to inform this network transport of signal strength thresholds
     * that when crossed should trigger a system wakeup and a NetworkCapabilities update.
     */
    protected void setSignalStrengthThresholds(int[] thresholds) {
    }

    /**
     * Called when the user asks to not stay connected to this network because it was found to not
     * provide Internet access.  Usually followed by call to {@code unwanted}.  The transport is
     * responsible for making sure the device does not automatically reconnect to the same network
     * after the {@code unwanted} call.
     */
    protected void preventAutomaticReconnect() {
    }

    protected void log(String s) {
        Log.d(LOG_TAG, "NetworkAgent: " + s);
    }
}
