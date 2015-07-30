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
import android.os.Handler;
import android.os.Messenger;
import android.util.SparseArray;

import com.android.internal.util.AsyncChannel;
import com.android.server.ConnectivityService;
import com.android.server.connectivity.NetworkMonitor;

import java.util.ArrayList;
import java.util.Comparator;

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
// 2. registered, uncreated, connected, unvalidated
//    This state is entered when a registered NetworkAgent transitions to the CONNECTED state
//    ConnectivityService will tell netd to create the network and immediately transition to
//    state #3.
// 3. registered, created, connected, unvalidated
//    If this network can satisfy the default NetworkRequest, then NetworkMonitor will
//    probe for Internet connectivity.
//    If this network cannot satisfy the default NetworkRequest, it will immediately be
//    transitioned to state #4.
//    A network may remain in this state if NetworkMonitor fails to find Internet connectivity,
//    for example:
//    a. a captive portal is present, or
//    b. a WiFi router whose Internet backhaul is down, or
//    c. a wireless connection stops transfering packets temporarily (e.g. device is in elevator
//       or tunnel) but does not disconnect from the AP/cell tower, or
//    d. a stand-alone device offering a WiFi AP without an uplink for configuration purposes.
// 4. registered, created, connected, validated
//
// The device's default network connection:
// ----------------------------------------
// Networks in states #3 and #4 may be used as a device's default network connection if they
// satisfy the default NetworkRequest.
// A network, that satisfies the default NetworkRequest, in state #4 should always be chosen
// in favor of a network, that satisfies the default NetworkRequest, in state #3.
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
// then that network can transition from any state (#1-#4) to unregistered.  This happens by
// the transport disconnecting their NetworkAgent's AsyncChannel with ConnectivityManager.
// ConnectivityService also tells netd to destroy the network.
//
// When ConnectivityService disconnects a network:
// -----------------------------------------------
// If a network has no chance of satisfying any requests (even if it were to become validated
// and enter state #4), ConnectivityService will disconnect the NetworkAgent's AsyncChannel.
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
    // Indicates if netd has been told to create this Network.  Once created the appropriate routing
    // rules are setup and routes are added so packets can begin flowing over the Network.
    // This is a sticky bit; once set it is never cleared.
    public boolean created;
    // Set to true if this Network successfully passed validation or if it did not satisfy the
    // default NetworkRequest in which case validation will not be attempted.
    // This is a sticky bit; once set it is never cleared even if future validation attempts fail.
    public boolean everValidated;

    // The result of the last validation attempt on this network (true if validated, false if not).
    // This bit exists only because we never unvalidate a network once it's been validated, and that
    // is because the network scoring and revalidation code does not (may not?) deal properly with
    // networks becoming unvalidated.
    // TODO: Fix the network scoring code, remove this, and rename everValidated to validated.
    public boolean lastValidated;

    // Whether a captive portal was ever detected on this network.
    // This is a sticky bit; once set it is never cleared.
    public boolean everCaptivePortalDetected;

    // Whether a captive portal was found during the last network validation attempt.
    public boolean lastCaptivePortalDetected;

    // Indicates whether the network is lingering.  Networks are lingered when they become unneeded
    // as a result of their NetworkRequests being satisfied by a different network, so as to allow
    // communication to wrap up before the network is taken down.  This usually only happens to the
    // default network.  Lingering ends with either the linger timeout expiring and the network
    // being taken down, or the network satisfying a request again.
    public boolean lingering;

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
    public final SparseArray<NetworkRequest> networkRequests = new SparseArray<NetworkRequest>();
    // The list of NetworkRequests that this Network previously satisfied with the highest
    // score.  A non-empty list indicates that if this Network was validated it is lingered.
    // NOTE: This list is only used for debugging.
    public final ArrayList<NetworkRequest> networkLingered = new ArrayList<NetworkRequest>();

    public final Messenger messenger;
    public final AsyncChannel asyncChannel;

    // Used by ConnectivityService to keep track of 464xlat.
    public Nat464Xlat clatd;

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
        networkMonitor = connService.createNetworkMonitor(context, handler, this, defaultRequest);
        networkMisc = misc;
    }

    /**
     * Add {@code networkRequest} to this network as it's satisfied by this network.
     * NOTE: This function must only be called on ConnectivityService's main thread.
     * @return true if {@code networkRequest} was added or false if {@code networkRequest} was
     *         already present.
     */
    public boolean addRequest(NetworkRequest networkRequest) {
        if (networkRequests.get(networkRequest.requestId) == networkRequest) return false;
        networkRequests.put(networkRequest.requestId, networkRequest);
        return true;
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
        // Use NET_CAPABILITY_VALIDATED here instead of lastValidated, this allows
        // ConnectivityService.updateCapabilities() to compute the old score prior to updating
        // networkCapabilities (with a potentially different validated state).
        if (!networkCapabilities.hasCapability(NET_CAPABILITY_VALIDATED) && !pretendValidated) {
            score -= UNVALIDATED_SCORE_PENALTY;
        }
        if (score < 0) score = 0;
        return score;
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

    public String toString() {
        return "NetworkAgentInfo{ ni{" + networkInfo + "}  network{" +
                network + "}  lp{" +
                linkProperties + "}  nc{" +
                networkCapabilities + "}  Score{" + getCurrentScore() + "}  " +
                "everValidated{" + everValidated + "}  lastValidated{" + lastValidated + "}  " +
                "created{" + created + "} lingering{" + lingering + "} " +
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
