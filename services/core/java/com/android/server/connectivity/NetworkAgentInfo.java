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

package com.android.server.connectivity;

import static android.net.NetworkCapabilities.NET_CAPABILITY_VALIDATED;

import android.content.Context;
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkInfo;
import android.net.NetworkMisc;
import android.net.NetworkRequest;
import android.net.NetworkState;
import android.os.Handler;
import android.os.Messenger;
import android.os.SystemClock;
import android.util.Log;
import android.util.SparseArray;

import com.android.internal.util.AsyncChannel;
import com.android.internal.util.WakeupMessage;
import com.android.server.ConnectivityService;
import com.android.server.connectivity.NetworkMonitor;

import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Objects;
import java.util.SortedSet;
import java.util.TreeSet;

/**
 * A bag class used by ConnectivityService for holding a collection of most recent
 * information published by a particular NetworkAgent as well as the
 * AsyncChannel/messenger for reaching that NetworkAgent and lists of NetworkRequests
 * interested in using it.  Default sort order is descending by score.
 */
// States of a network:
// --------------------
// 1. registered, uncreated, disconnected, unvalidated
//    This state is entered when a NetworkFactory registers a NetworkAgent in any state except
//    the CONNECTED state.
// 2. registered, uncreated, connecting, unvalidated
//    This state is entered when a registered NetworkAgent for a VPN network transitions to the
//    CONNECTING state (TODO: go through this state for every network, not just VPNs).
//    ConnectivityService will tell netd to create the network early in order to add extra UID
//    routing rules referencing the netID. These rules need to be in place before the network is
//    connected to avoid racing against client apps trying to connect to a half-setup network.
// 3. registered, uncreated, connected, unvalidated
//    This state is entered when a registered NetworkAgent transitions to the CONNECTED state.
//    ConnectivityService will tell netd to create the network if it was not already created, and
//    immediately transition to state #4.
// 4. registered, created, connected, unvalidated
//    If this network can satisfy the default NetworkRequest, then NetworkMonitor will
//    probe for Internet connectivity.
//    If this network cannot satisfy the default NetworkRequest, it will immediately be
//    transitioned to state #5.
//    A network may remain in this state if NetworkMonitor fails to find Internet connectivity,
//    for example:
//    a. a captive portal is present, or
//    b. a WiFi router whose Internet backhaul is down, or
//    c. a wireless connection stops transfering packets temporarily (e.g. device is in elevator
//       or tunnel) but does not disconnect from the AP/cell tower, or
//    d. a stand-alone device offering a WiFi AP without an uplink for configuration purposes.
// 5. registered, created, connected, validated
//
// The device's default network connection:
// ----------------------------------------
// Networks in states #4 and #5 may be used as a device's default network connection if they
// satisfy the default NetworkRequest.
// A network, that satisfies the default NetworkRequest, in state #5 should always be chosen
// in favor of a network, that satisfies the default NetworkRequest, in state #4.
// When deciding between two networks, that both satisfy the default NetworkRequest, to select
// for the default network connection, the one with the higher score should be chosen.
//
// When a network disconnects:
// ---------------------------
// If a network's transport disappears, for example:
// a. WiFi turned off, or
// b. cellular data turned off, or
// c. airplane mode is turned on, or
// d. a wireless connection disconnects from AP/cell tower entirely (e.g. device is out of range
//    of AP for an extended period of time, or switches to another AP without roaming)
// then that network can transition from any state (#1-#5) to unregistered.  This happens by
// the transport disconnecting their NetworkAgent's AsyncChannel with ConnectivityManager.
// ConnectivityService also tells netd to destroy the network.
//
// When ConnectivityService disconnects a network:
// -----------------------------------------------
// If a network has no chance of satisfying any requests (even if it were to become validated
// and enter state #5), ConnectivityService will disconnect the NetworkAgent's AsyncChannel.
// If the network ever for any period of time had satisfied a NetworkRequest (i.e. had been
// the highest scoring that satisfied the NetworkRequest's constraints), but is no longer the
// highest scoring network for any NetworkRequest, then there will be a 30s pause before
// ConnectivityService disconnects the NetworkAgent's AsyncChannel.  During this pause the
// network is considered "lingering".  This pause exists to allow network communication to be
// wrapped up rather than abruptly terminated.  During this pause if the network begins satisfying
// a NetworkRequest, ConnectivityService will cancel the future disconnection of the NetworkAgent's
// AsyncChannel, and the network is no longer considered "lingering".
public class NetworkAgentInfo implements Comparable<NetworkAgentInfo> {

    public NetworkInfo networkInfo;
    // This Network object should always be used if possible, so as to encourage reuse of the
    // enclosed socket factory and connection pool.  Avoid creating other Network objects.
    // This Network object is always valid.
    public final Network network;
    public LinkProperties linkProperties;
    // This should only be modified via ConnectivityService.updateCapabilities().
    public NetworkCapabilities networkCapabilities;
    public final NetworkMonitor networkMonitor;
    public final NetworkMisc networkMisc;
    // Indicates if netd has been told to create this Network. From this point on the appropriate
    // routing rules are setup and routes are added so packets can begin flowing over the Network.
    // This is a sticky bit; once set it is never cleared.
    public boolean created;
    // Set to true after the first time this network is marked as CONNECTED. Once set, the network
    // shows up in API calls, is able to satisfy NetworkRequests and can become the default network.
    // This is a sticky bit; once set it is never cleared.
    public boolean everConnected;
    // Set to true if this Network successfully passed validation or if it did not satisfy the
    // default NetworkRequest in which case validation will not be attempted.
    // This is a sticky bit; once set it is never cleared even if future validation attempts fail.
    public boolean everValidated;

    // The result of the last validation attempt on this network (true if validated, false if not).
    public boolean lastValidated;

    // If true, becoming unvalidated will lower the network's score. This is only meaningful if the
    // system is configured not to do this for certain networks, e.g., if the
    // config_networkAvoidBadWifi option is set to 0 and the user has not overridden that via
    // Settings.Global.NETWORK_AVOID_BAD_WIFI.
    public boolean avoidUnvalidated;

    // Whether a captive portal was ever detected on this network.
    // This is a sticky bit; once set it is never cleared.
    public boolean everCaptivePortalDetected;

    // Whether a captive portal was found during the last network validation attempt.
    public boolean lastCaptivePortalDetected;

    // Networks are lingered when they become unneeded as a result of their NetworkRequests being
    // satisfied by a higher-scoring network. so as to allow communication to wrap up before the
    // network is taken down.  This usually only happens to the default network. Lingering ends with
    // either the linger timeout expiring and the network being taken down, or the network
    // satisfying a request again.
    public static class LingerTimer implements Comparable<LingerTimer> {
        public final NetworkRequest request;
        public final long expiryMs;

        public LingerTimer(NetworkRequest request, long expiryMs) {
            this.request = request;
            this.expiryMs = expiryMs;
        }
        public boolean equals(Object o) {
            if (!(o instanceof LingerTimer)) return false;
            LingerTimer other = (LingerTimer) o;
            return (request.requestId == other.request.requestId) && (expiryMs == other.expiryMs);
        }
        public int hashCode() {
            return Objects.hash(request.requestId, expiryMs);
        }
        public int compareTo(LingerTimer other) {
            return (expiryMs != other.expiryMs) ?
                    Long.compare(expiryMs, other.expiryMs) :
                    Integer.compare(request.requestId, other.request.requestId);
        }
        public String toString() {
            return String.format("%s, expires %dms", request.toString(),
                    expiryMs - SystemClock.elapsedRealtime());
        }
    }

    /**
     * Inform ConnectivityService that the network LINGER period has
     * expired.
     * obj = this NetworkAgentInfo
     */
    public static final int EVENT_NETWORK_LINGER_COMPLETE = 1001;

    // All linger timers for this network, sorted by expiry time. A linger timer is added whenever
    // a request is moved to a network with a better score, regardless of whether the network is or
    // was lingering or not.
    // TODO: determine if we can replace this with a smaller or unsorted data structure. (e.g.,
    // SparseLongArray) combined with the timestamp of when the last timer is scheduled to fire.
    private final SortedSet<LingerTimer> mLingerTimers = new TreeSet<>();

    // For fast lookups. Indexes into mLingerTimers by request ID.
    private final SparseArray<LingerTimer> mLingerTimerForRequest = new SparseArray<>();

    // Linger expiry timer. Armed whenever mLingerTimers is non-empty, regardless of whether the
    // network is lingering or not. Always set to the expiry of the LingerTimer that expires last.
    // When the timer fires, all linger state is cleared, and if the network has no requests, it is
    // torn down.
    private WakeupMessage mLingerMessage;

    // Linger expiry. Holds the expiry time of the linger timer, or 0 if the timer is not armed.
    private long mLingerExpiryMs;

    // Whether the network is lingering or not. Must be maintained separately from the above because
    // it depends on the state of other networks and requests, which only ConnectivityService knows.
    // (Example: we don't linger a network if it would become the best for a NetworkRequest if it
    // validated).
    private boolean mLingering;

    // This represents the last score received from the NetworkAgent.
    private int currentScore;
    // Penalty applied to scores of Networks that have not been validated.
    private static final int UNVALIDATED_SCORE_PENALTY = 40;

    // Score for explicitly connected network.
    //
    // This ensures that a) the explicitly selected network is never trumped by anything else, and
    // b) the explicitly selected network is never torn down.
    private static final int MAXIMUM_NETWORK_SCORE = 100;

    // The list of NetworkRequests being satisfied by this Network.
    private final SparseArray<NetworkRequest> mNetworkRequests = new SparseArray<>();
    // The list of NetworkRequests that this Network previously satisfied with the highest
    // score.  A non-empty list indicates that if this Network was validated it is lingered.
    // How many of the satisfied requests are actual requests and not listens.
    private int mNumRequestNetworkRequests = 0;

    public final Messenger messenger;
    public final AsyncChannel asyncChannel;

    // Used by ConnectivityService to keep track of 464xlat.
    public Nat464Xlat clatd;

    private static final String TAG = ConnectivityService.class.getSimpleName();
    private static final boolean VDBG = false;
    private final ConnectivityService mConnService;
    private final Context mContext;
    private final Handler mHandler;

    public NetworkAgentInfo(Messenger messenger, AsyncChannel ac, Network net, NetworkInfo info,
            LinkProperties lp, NetworkCapabilities nc, int score, Context context, Handler handler,
            NetworkMisc misc, NetworkRequest defaultRequest, ConnectivityService connService) {
        this.messenger = messenger;
        asyncChannel = ac;
        network = net;
        networkInfo = info;
        linkProperties = lp;
        networkCapabilities = nc;
        currentScore = score;
        mConnService = connService;
        mContext = context;
        mHandler = handler;
        networkMonitor = mConnService.createNetworkMonitor(context, handler, this, defaultRequest);
        networkMisc = misc;
    }

    // Functions for manipulating the requests satisfied by this network.
    //
    // These functions must only called on ConnectivityService's main thread.

    /**
     * Add {@code networkRequest} to this network as it's satisfied by this network.
     * @return true if {@code networkRequest} was added or false if {@code networkRequest} was
     *         already present.
     */
    public boolean addRequest(NetworkRequest networkRequest) {
        NetworkRequest existing = mNetworkRequests.get(networkRequest.requestId);
        if (existing == networkRequest) return false;
        if (existing != null && existing.isRequest()) mNumRequestNetworkRequests--;
        mNetworkRequests.put(networkRequest.requestId, networkRequest);
        if (networkRequest.isRequest()) mNumRequestNetworkRequests++;
        return true;
    }

    /**
     * Remove the specified request from this network.
     */
    public void removeRequest(int requestId) {
        NetworkRequest existing = mNetworkRequests.get(requestId);
        if (existing == null) return;
        mNetworkRequests.remove(requestId);
        if (existing.isRequest()) {
            mNumRequestNetworkRequests--;
            unlingerRequest(existing);
        }
    }

    /**
     * Returns whether this network is currently satisfying the request with the specified ID.
     */
    public boolean isSatisfyingRequest(int id) {
        return mNetworkRequests.get(id) != null;
    }

    /**
     * Returns the request at the specified position in the list of requests satisfied by this
     * network.
     */
    public NetworkRequest requestAt(int index) {
        return mNetworkRequests.valueAt(index);
    }

    /**
     * Returns the number of requests currently satisfied by this network for which
     * {@link android.net.NetworkRequest#isRequest} returns {@code true}.
     */
    public int numRequestNetworkRequests() {
        return mNumRequestNetworkRequests;
    }

    /**
     * Returns the number of requests of any type currently satisfied by this network.
     */
    public int numNetworkRequests() {
        return mNetworkRequests.size();
    }

    // Does this network satisfy request?
    public boolean satisfies(NetworkRequest request) {
        return created &&
                request.networkCapabilities.satisfiedByNetworkCapabilities(networkCapabilities);
    }

    public boolean satisfiesImmutableCapabilitiesOf(NetworkRequest request) {
        return created &&
                request.networkCapabilities.satisfiedByImmutableNetworkCapabilities(
                        networkCapabilities);
    }

    public boolean isVPN() {
        return networkCapabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN);
    }

    private int getCurrentScore(boolean pretendValidated) {
        // TODO: We may want to refactor this into a NetworkScore class that takes a base score from
        // the NetworkAgent and signals from the NetworkAgent and uses those signals to modify the
        // score.  The NetworkScore class would provide a nice place to centralize score constants
        // so they are not scattered about the transports.

        // If this network is explicitly selected and the user has decided to use it even if it's
        // unvalidated, give it the maximum score. Also give it the maximum score if it's explicitly
        // selected and we're trying to see what its score could be. This ensures that we don't tear
        // down an explicitly selected network before the user gets a chance to prefer it when
        // a higher-scoring network (e.g., Ethernet) is available.
        if (networkMisc.explicitlySelected && (networkMisc.acceptUnvalidated || pretendValidated)) {
            return MAXIMUM_NETWORK_SCORE;
        }

        int score = currentScore;
        if (!lastValidated && !pretendValidated && !ignoreWifiUnvalidationPenalty()) {
            score -= UNVALIDATED_SCORE_PENALTY;
        }
        if (score < 0) score = 0;
        return score;
    }

    // Return true on devices configured to ignore score penalty for wifi networks
    // that become unvalidated (b/31075769).
    private boolean ignoreWifiUnvalidationPenalty() {
        boolean isWifi = networkCapabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) &&
                networkCapabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET);
        boolean avoidBadWifi = mConnService.avoidBadWifi() || avoidUnvalidated;
        return isWifi && !avoidBadWifi && everValidated;
    }

    // Get the current score for this Network.  This may be modified from what the
    // NetworkAgent sent, as it has modifiers applied to it.
    public int getCurrentScore() {
        return getCurrentScore(false);
    }

    // Get the current score for this Network as if it was validated.  This may be modified from
    // what the NetworkAgent sent, as it has modifiers applied to it.
    public int getCurrentScoreAsValidated() {
        return getCurrentScore(true);
    }

    public void setCurrentScore(int newScore) {
        currentScore = newScore;
    }

    public NetworkState getNetworkState() {
        synchronized (this) {
            // Network objects are outwardly immutable so there is no point to duplicating.
            // Duplicating also precludes sharing socket factories and connection pools.
            final String subscriberId = (networkMisc != null) ? networkMisc.subscriberId : null;
            return new NetworkState(new NetworkInfo(networkInfo),
                    new LinkProperties(linkProperties),
                    new NetworkCapabilities(networkCapabilities), network, subscriberId, null);
        }
    }

    /**
     * Sets the specified request to linger on this network for the specified time. Called by
     * ConnectivityService when the request is moved to another network with a higher score.
     */
    public void lingerRequest(NetworkRequest request, long now, long duration) {
        if (mLingerTimerForRequest.get(request.requestId) != null) {
            // Cannot happen. Once a request is lingering on a particular network, we cannot
            // re-linger it unless that network becomes the best for that request again, in which
            // case we should have unlingered it.
            Log.wtf(TAG, this.name() + ": request " + request.requestId + " already lingered");
        }
        final long expiryMs = now + duration;
        LingerTimer timer = new LingerTimer(request, expiryMs);
        if (VDBG) Log.d(TAG, "Adding LingerTimer " + timer + " to " + this.name());
        mLingerTimers.add(timer);
        mLingerTimerForRequest.put(request.requestId, timer);
    }

    /**
     * Cancel lingering. Called by ConnectivityService when a request is added to this network.
     * Returns true if the given request was lingering on this network, false otherwise.
     */
    public boolean unlingerRequest(NetworkRequest request) {
        LingerTimer timer = mLingerTimerForRequest.get(request.requestId);
        if (timer != null) {
            if (VDBG) Log.d(TAG, "Removing LingerTimer " + timer + " from " + this.name());
            mLingerTimers.remove(timer);
            mLingerTimerForRequest.remove(request.requestId);
            return true;
        }
        return false;
    }

    public long getLingerExpiry() {
        return mLingerExpiryMs;
    }

    public void updateLingerTimer() {
        long newExpiry = mLingerTimers.isEmpty() ? 0 : mLingerTimers.last().expiryMs;
        if (newExpiry == mLingerExpiryMs) return;

        // Even if we're going to reschedule the timer, cancel it first. This is because the
        // semantics of WakeupMessage guarantee that if cancel is called then the alarm will
        // never call its callback (handleLingerComplete), even if it has already fired.
        // WakeupMessage makes no such guarantees about rescheduling a message, so if mLingerMessage
        // has already been dispatched, rescheduling to some time in the future it won't stop it
        // from calling its callback immediately.
        if (mLingerMessage != null) {
            mLingerMessage.cancel();
            mLingerMessage = null;
        }

        if (newExpiry > 0) {
            mLingerMessage = mConnService.makeWakeupMessage(
                    mContext, mHandler,
                    "NETWORK_LINGER_COMPLETE." + network.netId,
                    EVENT_NETWORK_LINGER_COMPLETE, this);
            mLingerMessage.schedule(newExpiry);
        }

        mLingerExpiryMs = newExpiry;
    }

    public void linger() {
        mLingering = true;
    }

    public void unlinger() {
        mLingering = false;
    }

    public boolean isLingering() {
        return mLingering;
    }

    public void clearLingerState() {
        if (mLingerMessage != null) {
            mLingerMessage.cancel();
            mLingerMessage = null;
        }
        mLingerTimers.clear();
        mLingerTimerForRequest.clear();
        updateLingerTimer();  // Sets mLingerExpiryMs, cancels and nulls out mLingerMessage.
        mLingering = false;
    }

    public void dumpLingerTimers(PrintWriter pw) {
        for (LingerTimer timer : mLingerTimers) { pw.println(timer); }
    }

    public String toString() {
        return "NetworkAgentInfo{ ni{" + networkInfo + "}  " +
                "network{" + network + "}  nethandle{" + network.getNetworkHandle() + "}  " +
                "lp{" + linkProperties + "}  " +
                "nc{" + networkCapabilities + "}  Score{" + getCurrentScore() + "}  " +
                "everValidated{" + everValidated + "}  lastValidated{" + lastValidated + "}  " +
                "created{" + created + "} lingering{" + isLingering() + "} " +
                "explicitlySelected{" + networkMisc.explicitlySelected + "} " +
                "acceptUnvalidated{" + networkMisc.acceptUnvalidated + "} " +
                "everCaptivePortalDetected{" + everCaptivePortalDetected + "} " +
                "lastCaptivePortalDetected{" + lastCaptivePortalDetected + "} " +
                "}";
    }

    public String name() {
        return "NetworkAgentInfo [" + networkInfo.getTypeName() + " (" +
                networkInfo.getSubtypeName() + ") - " +
                (network == null ? "null" : network.toString()) + "]";
    }

    // Enables sorting in descending order of score.
    @Override
    public int compareTo(NetworkAgentInfo other) {
        return other.getCurrentScore() - getCurrentScore();
    }
}
