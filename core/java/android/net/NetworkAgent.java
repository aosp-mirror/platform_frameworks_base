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
import android.util.SparseArray;

import com.android.internal.util.AsyncChannel;
import com.android.internal.util.Protocol;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * A Utility class for handling NetworkRequests.
 *
 * Created by bearer-specific code to handle tracking requests, scores,
 * network data and handle communicating with ConnectivityService.  Two
 * abstract methods: connect and disconnect are used to act on the
 * underlying bearer code.  Connect is called when we have a NetworkRequest
 * and our score is better than the current handling network's score, while
 * disconnect is used when ConnectivityService requests a disconnect.
 *
 * A bearer may have more than one NetworkAgent if it can simultaneously
 * support separate networks (IMS / Internet / MMS Apns on cellular, or
 * perhaps connections with different SSID or P2P for Wi-Fi).  The bearer
 * code should pass its NetworkAgents the NetworkRequests each NetworkAgent
 * can handle, demultiplexing for different network types.  The bearer code
 * can also filter out requests it can never handle.
 *
 * Each NetworkAgent needs to be given a score and NetworkCapabilities for
 * their potential network.  While disconnected, the NetworkAgent will check
 * each time its score changes or a NetworkRequest changes to see if
 * the NetworkAgent can provide a higher scored network for a NetworkRequest
 * that the NetworkAgent's NetworkCapabilties can satisfy.  This condition will
 * trigger a connect request via connect().  After connection, connection data
 * should be given to the NetworkAgent by the bearer, including LinkProperties
 * NetworkCapabilties and NetworkInfo.  After that the NetworkAgent will register
 * with ConnectivityService and forward the data on.
 * @hide
 */
public abstract class NetworkAgent extends Handler {
    private final SparseArray<NetworkRequestAndScore> mNetworkRequests = new SparseArray<>();
    private boolean mConnectionRequested = false;

    private AsyncChannel mAsyncChannel;
    private final String LOG_TAG;
    private static final boolean DBG = true;
    private static final boolean VDBG = true;
    // TODO - this class shouldn't cache data or it runs the risk of getting out of sync
    // Make the API require each of these when any is updated so we have the data we need,
    // without caching.
    private LinkProperties mLinkProperties;
    private NetworkInfo mNetworkInfo;
    private NetworkCapabilities mNetworkCapabilities;
    private int mNetworkScore;
    private boolean mRegistered = false;
    private final Context mContext;
    private AtomicBoolean mHasRequests = new AtomicBoolean(false);

    // TODO - add a name member for logging purposes.

    protected final Object mLockObj = new Object();


    private static final int BASE = Protocol.BASE_NETWORK_AGENT;

    /**
     * Sent by self to queue up a new/modified request.
     * obj = NetworkRequestAndScore
     */
    private static final int CMD_ADD_REQUEST = BASE + 1;

    /**
     * Sent by self to queue up the removal of a request.
     * obj = NetworkRequest
     */
    private static final int CMD_REMOVE_REQUEST = BASE + 2;

    /**
     * Sent by ConnectivityService to the NetworkAgent to inform it of
     * suspected connectivity problems on its network.  The NetworkAgent
     * should take steps to verify and correct connectivity.
     */
    public static final int CMD_SUSPECT_BAD = BASE + 3;

    /**
     * Sent by the NetworkAgent (note the EVENT vs CMD prefix) to
     * ConnectivityService to pass the current NetworkInfo (connection state).
     * Sent when the NetworkInfo changes, mainly due to change of state.
     * obj = NetworkInfo
     */
    public static final int EVENT_NETWORK_INFO_CHANGED = BASE + 4;

    /**
     * Sent by the NetworkAgent to ConnectivityService to pass the current
     * NetworkCapabilties.
     * obj = NetworkCapabilities
     */
    public static final int EVENT_NETWORK_CAPABILITIES_CHANGED = BASE + 5;

    /**
     * Sent by the NetworkAgent to ConnectivityService to pass the current
     * NetworkProperties.
     * obj = NetworkProperties
     */
    public static final int EVENT_NETWORK_PROPERTIES_CHANGED = BASE + 6;

    /**
     * Sent by the NetworkAgent to ConnectivityService to pass the current
     * network score.
     * arg1 = network score int
     */
    public static final int EVENT_NETWORK_SCORE_CHANGED = BASE + 7;

    public NetworkAgent(Looper looper, Context context, String logTag) {
        super(looper);
        LOG_TAG = logTag;
        mContext = context;
    }

    /**
     * When conditions are right, register with ConnectivityService.
     * Connditions include having a well defined network and a request
     * that justifies it.  The NetworkAgent will remain registered until
     * disconnected.
     * TODO - this should have all data passed in rather than caching
     */
    private void registerSelf() {
        synchronized(mLockObj) {
            if (!mRegistered && mConnectionRequested &&
                    mNetworkInfo != null && mNetworkInfo.isConnected() &&
                    mNetworkCapabilities != null &&
                    mLinkProperties != null &&
                    mNetworkScore != 0) {
                if (DBG) log("Registering NetworkAgent");
                mRegistered = true;
                ConnectivityManager cm = (ConnectivityManager)mContext.getSystemService(
                        Context.CONNECTIVITY_SERVICE);
                cm.registerNetworkAgent(new Messenger(this), new NetworkInfo(mNetworkInfo),
                        new LinkProperties(mLinkProperties),
                        new NetworkCapabilities(mNetworkCapabilities), mNetworkScore);
            } else if (DBG && !mRegistered) {
                String err = "Not registering due to ";
                if (mConnectionRequested == false) err += "no Connect requested ";
                if (mNetworkInfo == null) err += "null NetworkInfo ";
                if (mNetworkInfo != null && mNetworkInfo.isConnected() == false) {
                    err += "NetworkInfo disconnected ";
                }
                if (mLinkProperties == null) err += "null LinkProperties ";
                if (mNetworkCapabilities == null) err += "null NetworkCapabilities ";
                if (mNetworkScore == 0) err += "null NetworkScore";
                log(err);
            }
        }
    }

    @Override
    public void handleMessage(Message msg) {
        switch (msg.what) {
            case AsyncChannel.CMD_CHANNEL_FULL_CONNECTION: {
                synchronized (mLockObj) {
                    if (mAsyncChannel != null) {
                        log("Received new connection while already connected!");
                    } else {
                        if (DBG) log("NetworkAgent fully connected");
                        mAsyncChannel = new AsyncChannel();
                        mAsyncChannel.connected(null, this, msg.replyTo);
                        mAsyncChannel.replyToMessage(msg, AsyncChannel.CMD_CHANNEL_FULLY_CONNECTED,
                                AsyncChannel.STATUS_SUCCESSFUL);
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
                disconnect();
                clear();
                break;
            }
            case CMD_SUSPECT_BAD: {
                log("Unhandled Message " + msg);
                break;
            }
            case CMD_ADD_REQUEST: {
                handleAddRequest(msg);
                break;
            }
            case CMD_REMOVE_REQUEST: {
                handleRemoveRequest(msg);
                break;
            }
        }
    }

    private void clear() {
        synchronized(mLockObj) {
            mNetworkRequests.clear();
            mHasRequests.set(false);
            mConnectionRequested = false;
            mAsyncChannel = null;
            mRegistered = false;
        }
    }

    private static class NetworkRequestAndScore {
        NetworkRequest req;
        int score;

        NetworkRequestAndScore(NetworkRequest networkRequest, int score) {
            req = networkRequest;
            this.score = score;
        }
    }

    private void handleAddRequest(Message msg) {
        NetworkRequestAndScore n = (NetworkRequestAndScore)msg.obj;
        // replaces old request, updating score
        mNetworkRequests.put(n.req.requestId, n);
        mHasRequests.set(true);
        evalScores();
    }

    private void handleRemoveRequest(Message msg) {
        NetworkRequest networkRequest = (NetworkRequest)msg.obj;

        if (mNetworkRequests.get(networkRequest.requestId) != null) {
            mNetworkRequests.remove(networkRequest.requestId);
            if (mNetworkRequests.size() == 0) mHasRequests.set(false);
            evalScores();
        }
    }

    /**
     * Called to go through our list of requests and see if we're
     * good enough to try connecting, or if we have gotten worse and
     * need to disconnect.
     *
     * Once we are registered, does nothing: we disconnect when requested via
     * CMD_CHANNEL_DISCONNECTED, generated by either a loss of connection
     * between modules (bearer or ConnectivityService dies) or more commonly
     * when the NetworkInfo reports to ConnectivityService it is disconnected.
     */
    private void evalScores() {
        synchronized(mLockObj) {
            if (mRegistered) {
                if (VDBG) log("evalScores - already connected - size=" + mNetworkRequests.size());
                // already trying
                return;
            }
            if (VDBG) log("evalScores!");
            for (int i=0; i < mNetworkRequests.size(); i++) {
                int score = mNetworkRequests.valueAt(i).score;
                if (VDBG) log(" checking request Min " + score + " vs my score " + mNetworkScore);
                if (score < mNetworkScore) {
                    // have a request that has a lower scored network servicing it
                    // (or no network) than we could provide, so let's connect!
                    mConnectionRequested = true;
                    connect();
                    return;
                }
            }
            // Our score is not high enough to satisfy any current request.
            // This can happen if our score goes down after a connection is
            // requested but before we actually connect. In this case, disconnect
            // rather than continue trying - there's no point connecting if we know
            // we'll just be torn down as soon as we do.
            if (mConnectionRequested) {
                mConnectionRequested = false;
                disconnect();
            }
        }
    }

    public void addNetworkRequest(NetworkRequest networkRequest, int score) {
        if (DBG) log("adding NetworkRequest " + networkRequest + " with score " + score);
        sendMessage(obtainMessage(CMD_ADD_REQUEST,
                new NetworkRequestAndScore(networkRequest, score)));
    }

    public void removeNetworkRequest(NetworkRequest networkRequest) {
        if (DBG) log("removing NetworkRequest " + networkRequest);
        sendMessage(obtainMessage(CMD_REMOVE_REQUEST, networkRequest));
    }

    /**
     * Called by the bearer code when it has new LinkProperties data.
     * If we're a registered NetworkAgent, this new data will get forwarded on,
     * otherwise we store a copy in anticipation of registering.  This call
     * may also prompt registration if it causes the NetworkAgent to meet
     * the conditions (fully configured, connected, satisfys a request and
     * has sufficient score).
     */
    public void sendLinkProperties(LinkProperties linkProperties) {
        linkProperties = new LinkProperties(linkProperties);
        synchronized(mLockObj) {
            mLinkProperties = linkProperties;
            if (mAsyncChannel != null) {
                mAsyncChannel.sendMessage(EVENT_NETWORK_PROPERTIES_CHANGED, linkProperties);
            } else {
                registerSelf();
            }
        }
    }

    /**
     * Called by the bearer code when it has new NetworkInfo data.
     * If we're a registered NetworkAgent, this new data will get forwarded on,
     * otherwise we store a copy in anticipation of registering.  This call
     * may also prompt registration if it causes the NetworkAgent to meet
     * the conditions (fully configured, connected, satisfys a request and
     * has sufficient score).
     */
    public void sendNetworkInfo(NetworkInfo networkInfo) {
        networkInfo = new NetworkInfo(networkInfo);
        synchronized(mLockObj) {
            mNetworkInfo = networkInfo;
            if (mAsyncChannel != null) {
                mAsyncChannel.sendMessage(EVENT_NETWORK_INFO_CHANGED, networkInfo);
            } else {
                registerSelf();
            }
        }
    }

    /**
     * Called by the bearer code when it has new NetworkCapabilities data.
     * If we're a registered NetworkAgent, this new data will get forwarded on,
     * otherwise we store a copy in anticipation of registering.  This call
     * may also prompt registration if it causes the NetworkAgent to meet
     * the conditions (fully configured, connected, satisfys a request and
     * has sufficient score).
     * Note that if these capabilities make the network non-useful,
     * ConnectivityServce will tear this network down.
     */
    public void sendNetworkCapabilities(NetworkCapabilities networkCapabilities) {
        networkCapabilities = new NetworkCapabilities(networkCapabilities);
        synchronized(mLockObj) {
            mNetworkCapabilities = networkCapabilities;
            if (mAsyncChannel != null) {
                mAsyncChannel.sendMessage(EVENT_NETWORK_CAPABILITIES_CHANGED, networkCapabilities);
            } else {
                registerSelf();
            }
        }
    }

    public NetworkCapabilities getNetworkCapabilities() {
        synchronized(mLockObj) {
            return new NetworkCapabilities(mNetworkCapabilities);
        }
    }

    /**
     * Called by the bearer code when it has a new score for this network.
     * If we're a registered NetworkAgent, this new data will get forwarded on,
     * otherwise we store a copy.
     */
    public synchronized void sendNetworkScore(int score) {
        synchronized(mLockObj) {
            mNetworkScore = score;
            evalScores();
            if (mAsyncChannel != null) {
                mAsyncChannel.sendMessage(EVENT_NETWORK_SCORE_CHANGED, mNetworkScore);
            } else {
                registerSelf();
            }
        }
    }

    public boolean hasRequests() {
        return mHasRequests.get();
    }

    public boolean isConnectionRequested() {
        synchronized(mLockObj) {
            return mConnectionRequested;
        }
    }


    abstract protected void connect();
    abstract protected void disconnect();

    protected void log(String s) {
        Log.d(LOG_TAG, "NetworkAgent: " + s);
    }
}
