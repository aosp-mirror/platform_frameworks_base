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
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.os.Messenger;
import android.util.Log;
import android.util.SparseArray;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.util.AsyncChannel;
import com.android.internal.util.IndentingPrintWriter;
import com.android.internal.util.Protocol;

import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A NetworkFactory is an entity that creates NetworkAgent objects.
 * The bearers register with ConnectivityService using {@link #register} and
 * their factory will start receiving scored NetworkRequests.  NetworkRequests
 * can be filtered 3 ways: by NetworkCapabilities, by score and more complexly by
 * overridden function.  All of these can be dynamic - changing NetworkCapabilities
 * or score forces re-evaluation of all current requests.
 *
 * If any requests pass the filter some overrideable functions will be called.
 * If the bearer only cares about very simple start/stopNetwork callbacks, those
 * functions can be overridden.  If the bearer needs more interaction, it can
 * override addNetworkRequest and removeNetworkRequest which will give it each
 * request that passes their current filters.
 * @hide
 **/
public class NetworkFactory extends Handler {
    /** @hide */
    public static class SerialNumber {
        // Guard used by no network factory.
        public static final int NONE = -1;
        // A hardcoded serial number for NetworkAgents representing VPNs. These agents are
        // not created by any factory, so they use this constant for clarity instead of NONE.
        public static final int VPN = -2;
        private static final AtomicInteger sNetworkFactorySerialNumber = new AtomicInteger(1);
        /** Returns a unique serial number for a factory. */
        public static final int nextSerialNumber() {
            return sNetworkFactorySerialNumber.getAndIncrement();
        }
    }

    private static final boolean DBG = true;
    private static final boolean VDBG = false;

    private static final int BASE = Protocol.BASE_NETWORK_FACTORY;
    /**
     * Pass a network request to the bearer.  If the bearer believes it can
     * satisfy the request it should connect to the network and create a
     * NetworkAgent.  Once the NetworkAgent is fully functional it will
     * register itself with ConnectivityService using registerNetworkAgent.
     * If the bearer cannot immediately satisfy the request (no network,
     * user disabled the radio, lower-scored network) it should remember
     * any NetworkRequests it may be able to satisfy in the future.  It may
     * disregard any that it will never be able to service, for example
     * those requiring a different bearer.
     * msg.obj = NetworkRequest
     * msg.arg1 = score - the score of the network currently satisfying this
     *            request.  If this bearer knows in advance it cannot
     *            exceed this score it should not try to connect, holding the request
     *            for the future.
     *            Note that subsequent events may give a different (lower
     *            or higher) score for this request, transmitted to each
     *            NetworkFactory through additional CMD_REQUEST_NETWORK msgs
     *            with the same NetworkRequest but an updated score.
     *            Also, network conditions may change for this bearer
     *            allowing for a better score in the future.
     * msg.arg2 = the serial number of the factory currently responsible for the
     *            NetworkAgent handling this request, or SerialNumber.NONE if none.
     */
    public static final int CMD_REQUEST_NETWORK = BASE;

    /**
     * Cancel a network request
     * msg.obj = NetworkRequest
     */
    public static final int CMD_CANCEL_REQUEST = BASE + 1;

    /**
     * Internally used to set our best-guess score.
     * msg.arg1 = new score
     */
    private static final int CMD_SET_SCORE = BASE + 2;

    /**
     * Internally used to set our current filter for coarse bandwidth changes with
     * technology changes.
     * msg.obj = new filter
     */
    private static final int CMD_SET_FILTER = BASE + 3;

    /**
     * Sent by NetworkFactory to ConnectivityService to indicate that a request is
     * unfulfillable.
     * @see #releaseRequestAsUnfulfillableByAnyFactory(NetworkRequest).
     */
    public static final int EVENT_UNFULFILLABLE_REQUEST = BASE + 4;

    private final Context mContext;
    private final ArrayList<Message> mPreConnectedQueue = new ArrayList<Message>();
    private AsyncChannel mAsyncChannel;
    private final String LOG_TAG;

    private final SparseArray<NetworkRequestInfo> mNetworkRequests =
            new SparseArray<NetworkRequestInfo>();

    private int mScore;
    private NetworkCapabilities mCapabilityFilter;

    private int mRefCount = 0;
    private Messenger mMessenger = null;
    private int mSerialNumber;

    @UnsupportedAppUsage
    public NetworkFactory(Looper looper, Context context, String logTag,
            NetworkCapabilities filter) {
        super(looper);
        LOG_TAG = logTag;
        mContext = context;
        mCapabilityFilter = filter;
    }

    public void register() {
        if (DBG) log("Registering NetworkFactory");
        if (mMessenger == null) {
            mMessenger = new Messenger(this);
            mSerialNumber = ConnectivityManager.from(mContext).registerNetworkFactory(mMessenger,
                    LOG_TAG);
        }
    }

    public void unregister() {
        if (DBG) log("Unregistering NetworkFactory");
        if (mMessenger != null) {
            ConnectivityManager.from(mContext).unregisterNetworkFactory(mMessenger);
            mMessenger = null;
        }
    }

    @Override
    public void handleMessage(Message msg) {
        switch (msg.what) {
            case AsyncChannel.CMD_CHANNEL_FULL_CONNECTION: {
                if (mAsyncChannel != null) {
                    log("Received new connection while already connected!");
                    break;
                }
                if (VDBG) log("NetworkFactory fully connected");
                AsyncChannel ac = new AsyncChannel();
                ac.connected(null, this, msg.replyTo);
                ac.replyToMessage(msg, AsyncChannel.CMD_CHANNEL_FULLY_CONNECTED,
                        AsyncChannel.STATUS_SUCCESSFUL);
                mAsyncChannel = ac;
                for (Message m : mPreConnectedQueue) {
                    ac.sendMessage(m);
                }
                mPreConnectedQueue.clear();
                break;
            }
            case AsyncChannel.CMD_CHANNEL_DISCONNECT: {
                if (VDBG) log("CMD_CHANNEL_DISCONNECT");
                if (mAsyncChannel != null) {
                    mAsyncChannel.disconnect();
                    mAsyncChannel = null;
                }
                break;
            }
            case AsyncChannel.CMD_CHANNEL_DISCONNECTED: {
                if (DBG) log("NetworkFactory channel lost");
                mAsyncChannel = null;
                break;
            }
            case CMD_REQUEST_NETWORK: {
                handleAddRequest((NetworkRequest) msg.obj, msg.arg1, msg.arg2);
                break;
            }
            case CMD_CANCEL_REQUEST: {
                handleRemoveRequest((NetworkRequest) msg.obj);
                break;
            }
            case CMD_SET_SCORE: {
                handleSetScore(msg.arg1);
                break;
            }
            case CMD_SET_FILTER: {
                handleSetFilter((NetworkCapabilities) msg.obj);
                break;
            }
        }
    }

    private class NetworkRequestInfo {
        public final NetworkRequest request;
        public int score;
        public boolean requested; // do we have a request outstanding, limited by score
        public int factorySerialNumber;

        NetworkRequestInfo(NetworkRequest request, int score, int factorySerialNumber) {
            this.request = request;
            this.score = score;
            this.requested = false;
            this.factorySerialNumber = factorySerialNumber;
        }

        @Override
        public String toString() {
            return "{" + request + ", score=" + score + ", requested=" + requested + "}";
        }
    }

    /**
     * Add a NetworkRequest that the bearer may want to attempt to satisfy.
     * @see #CMD_REQUEST_NETWORK
     *
     * @param request the request to handle.
     * @param score the score of the NetworkAgent currently satisfying this request.
     * @param servingFactorySerialNumber the serial number of the NetworkFactory that
     *         created the NetworkAgent currently satisfying this request.
     */
    // TODO : remove this method. It is a stopgap measure to help sheperding a number
    // of dependent changes that would conflict throughout the automerger graph. Having this
    // temporarily helps with the process of going through with all these dependent changes across
    // the entire tree.
    @VisibleForTesting
    protected void handleAddRequest(NetworkRequest request, int score) {
        handleAddRequest(request, score, SerialNumber.NONE);
    }

    /**
     * Add a NetworkRequest that the bearer may want to attempt to satisfy.
     * @see #CMD_REQUEST_NETWORK
     *
     * @param request the request to handle.
     * @param score the score of the NetworkAgent currently satisfying this request.
     * @param servingFactorySerialNumber the serial number of the NetworkFactory that
     *         created the NetworkAgent currently satisfying this request.
     */
    @VisibleForTesting
    protected void handleAddRequest(NetworkRequest request, int score,
            int servingFactorySerialNumber) {
        NetworkRequestInfo n = mNetworkRequests.get(request.requestId);
        if (n == null) {
            if (DBG) {
                log("got request " + request + " with score " + score
                        + " and serial " + servingFactorySerialNumber);
            }
            n = new NetworkRequestInfo(request, score, servingFactorySerialNumber);
            mNetworkRequests.put(n.request.requestId, n);
        } else {
            if (VDBG) {
                log("new score " + score + " for exisiting request " + request
                        + " with serial " + servingFactorySerialNumber);
            }
            n.score = score;
            n.factorySerialNumber = servingFactorySerialNumber;
        }
        if (VDBG) log("  my score=" + mScore + ", my filter=" + mCapabilityFilter);

        evalRequest(n);
    }

    @VisibleForTesting
    protected void handleRemoveRequest(NetworkRequest request) {
        NetworkRequestInfo n = mNetworkRequests.get(request.requestId);
        if (n != null) {
            mNetworkRequests.remove(request.requestId);
            if (n.requested) releaseNetworkFor(n.request);
        }
    }

    private void handleSetScore(int score) {
        mScore = score;
        evalRequests();
    }

    private void handleSetFilter(NetworkCapabilities netCap) {
        mCapabilityFilter = netCap;
        evalRequests();
    }

    /**
     * Overridable function to provide complex filtering.
     * Called for every request every time a new NetworkRequest is seen
     * and whenever the filterScore or filterNetworkCapabilities change.
     *
     * acceptRequest can be overridden to provide complex filter behavior
     * for the incoming requests
     *
     * For output, this class will call {@link #needNetworkFor} and
     * {@link #releaseNetworkFor} for every request that passes the filters.
     * If you don't need to see every request, you can leave the base
     * implementations of those two functions and instead override
     * {@link #startNetwork} and {@link #stopNetwork}.
     *
     * If you want to see every score fluctuation on every request, set
     * your score filter to a very high number and watch {@link #needNetworkFor}.
     *
     * @return {@code true} to accept the request.
     */
    public boolean acceptRequest(NetworkRequest request, int score) {
        return true;
    }

    private void evalRequest(NetworkRequestInfo n) {
        if (VDBG) {
            log("evalRequest");
            log(" n.requests = " + n.requested);
            log(" n.score = " + n.score);
            log(" mScore = " + mScore);
            log(" n.factorySerialNumber = " + n.factorySerialNumber);
            log(" mSerialNumber = " + mSerialNumber);
        }
        if (shouldNeedNetworkFor(n)) {
            if (VDBG) log("  needNetworkFor");
            needNetworkFor(n.request, n.score);
            n.requested = true;
        } else if (shouldReleaseNetworkFor(n)) {
            if (VDBG) log("  releaseNetworkFor");
            releaseNetworkFor(n.request);
            n.requested = false;
        } else {
            if (VDBG) log("  done");
        }
    }

    private boolean shouldNeedNetworkFor(NetworkRequestInfo n) {
        // If this request is already tracked, it doesn't qualify for need
        return !n.requested
            // If the score of this request is higher or equal to that of this factory and some
            // other factory is responsible for it, then this factory should not track the request
            // because it has no hope of satisfying it.
            && (n.score < mScore || n.factorySerialNumber == mSerialNumber)
            // If this factory can't satisfy the capability needs of this request, then it
            // should not be tracked.
            && n.request.networkCapabilities.satisfiedByNetworkCapabilities(mCapabilityFilter)
            // Finally if the concrete implementation of the factory rejects the request, then
            // don't track it.
            && acceptRequest(n.request, n.score);
    }

    private boolean shouldReleaseNetworkFor(NetworkRequestInfo n) {
        // Don't release a request that's not tracked.
        return n.requested
            // The request should be released if it can't be satisfied by this factory. That
            // means either of the following conditions are met :
            // - Its score is too high to be satisfied by this factory and it's not already
            //   assigned to the factory
            // - This factory can't satisfy the capability needs of the request
            // - The concrete implementation of the factory rejects the request
            && ((n.score > mScore && n.factorySerialNumber != mSerialNumber)
                    || !n.request.networkCapabilities.satisfiedByNetworkCapabilities(
                            mCapabilityFilter)
                    || !acceptRequest(n.request, n.score));
    }

    private void evalRequests() {
        for (int i = 0; i < mNetworkRequests.size(); i++) {
            NetworkRequestInfo n = mNetworkRequests.valueAt(i);
            evalRequest(n);
        }
    }

    /**
     * Post a command, on this NetworkFactory Handler, to re-evaluate all
     * oustanding requests. Can be called from a factory implementation.
     */
    protected void reevaluateAllRequests() {
        post(() -> {
            evalRequests();
        });
    }

    /**
     * Can be called by a factory to release a request as unfulfillable: the request will be
     * removed, and the caller will get a
     * {@link ConnectivityManager.NetworkCallback#onUnavailable()} callback after this function
     * returns.
     *
     * Note: this should only be called by factory which KNOWS that it is the ONLY factory which
     * is able to fulfill this request!
     */
    protected void releaseRequestAsUnfulfillableByAnyFactory(NetworkRequest r) {
        post(() -> {
            if (DBG) log("releaseRequestAsUnfulfillableByAnyFactory: " + r);
            Message msg = obtainMessage(EVENT_UNFULFILLABLE_REQUEST, r);
            if (mAsyncChannel != null) {
                mAsyncChannel.sendMessage(msg);
            } else {
                mPreConnectedQueue.add(msg);
            }
        });
    }

    // override to do simple mode (request independent)
    protected void startNetwork() { }
    protected void stopNetwork() { }

    // override to do fancier stuff
    protected void needNetworkFor(NetworkRequest networkRequest, int score) {
        if (++mRefCount == 1) startNetwork();
    }

    protected void releaseNetworkFor(NetworkRequest networkRequest) {
        if (--mRefCount == 0) stopNetwork();
    }

    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.P, trackingBug = 115609023)
    public void setScoreFilter(int score) {
        sendMessage(obtainMessage(CMD_SET_SCORE, score, 0));
    }

    public void setCapabilityFilter(NetworkCapabilities netCap) {
        sendMessage(obtainMessage(CMD_SET_FILTER, new NetworkCapabilities(netCap)));
    }

    @VisibleForTesting
    protected int getRequestCount() {
        return mNetworkRequests.size();
    }

    public int getSerialNumber() {
        return mSerialNumber;
    }

    protected void log(String s) {
        Log.d(LOG_TAG, s);
    }

    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.P, trackingBug = 115609023)
    public void dump(FileDescriptor fd, PrintWriter writer, String[] args) {
        final IndentingPrintWriter pw = new IndentingPrintWriter(writer, "  ");
        pw.println(toString());
        pw.increaseIndent();
        for (int i = 0; i < mNetworkRequests.size(); i++) {
            pw.println(mNetworkRequests.valueAt(i));
        }
        pw.decreaseIndent();
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder("{").append(LOG_TAG).append(" - mSerialNumber=")
                .append(mSerialNumber).append(", ScoreFilter=")
                .append(mScore).append(", Filter=").append(mCapabilityFilter).append(", requests=")
                .append(mNetworkRequests.size()).append(", refCount=").append(mRefCount)
                .append("}");
        return sb.toString();
    }
}
