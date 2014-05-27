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

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.os.Messenger;
import android.os.Parcel;
import android.os.Parcelable;
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
    private volatile AsyncChannel mAsyncChannel;
    private final String LOG_TAG;
    private static final boolean DBG = true;
    private static final boolean VDBG = true;
    private final Context mContext;
    private final ArrayList<Message>mPreConnectedQueue = new ArrayList<Message>();

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

    /**
     * Sent by the NetworkAgent to ConnectivityService to pass the current
     * network score.
     * obj = network score Integer
     */
    public static final int EVENT_NETWORK_SCORE_CHANGED = BASE + 4;

    public NetworkAgent(Looper looper, Context context, String logTag, NetworkInfo ni,
            NetworkCapabilities nc, LinkProperties lp, int score) {
        super(looper);
        LOG_TAG = logTag;
        mContext = context;
        if (ni == null || nc == null || lp == null) {
            throw new IllegalArgumentException();
        }

        if (DBG) log("Registering NetworkAgent");
        ConnectivityManager cm = (ConnectivityManager)mContext.getSystemService(
                Context.CONNECTIVITY_SERVICE);
        cm.registerNetworkAgent(new Messenger(this), new NetworkInfo(ni),
                new LinkProperties(lp), new NetworkCapabilities(nc), score);
    }

    @Override
    public void handleMessage(Message msg) {
        switch (msg.what) {
            case AsyncChannel.CMD_CHANNEL_FULL_CONNECTION: {
                if (mAsyncChannel != null) {
                    log("Received new connection while already connected!");
                } else {
                    if (DBG) log("NetworkAgent fully connected");
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
                if (DBG) log("CMD_CHANNEL_DISCONNECT");
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
        }
    }

    private void queueOrSendMessage(int what, Object obj) {
        synchronized (mPreConnectedQueue) {
            if (mAsyncChannel != null) {
                mAsyncChannel.sendMessage(what, obj);
            } else {
                Message msg = Message.obtain();
                msg.what = what;
                msg.obj = obj;
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
    public void sendNetworkInfo(NetworkInfo networkInfo) {
        queueOrSendMessage(EVENT_NETWORK_INFO_CHANGED, new NetworkInfo(networkInfo));
    }

    /**
     * Called by the bearer code when it has new NetworkCapabilities data.
     */
    public void sendNetworkCapabilities(NetworkCapabilities networkCapabilities) {
        queueOrSendMessage(EVENT_NETWORK_CAPABILITIES_CHANGED,
                new NetworkCapabilities(networkCapabilities));
    }

    /**
     * Called by the bearer code when it has a new score for this network.
     */
    public void sendNetworkScore(int score) {
        queueOrSendMessage(EVENT_NETWORK_SCORE_CHANGED, new Integer(score));
    }

    /**
     * Called when ConnectivityService has indicated they no longer want this network.
     * The parent factory should (previously) have received indication of the change
     * as well, either canceling NetworkRequests or altering their score such that this
     * network won't be immediately requested again.
     */
    abstract protected void unwanted();

    protected void log(String s) {
        Log.d(LOG_TAG, "NetworkAgent: " + s);
    }
}
