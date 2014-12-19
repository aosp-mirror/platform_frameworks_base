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
import com.android.server.connectivity.NetworkMonitor;

import java.util.ArrayList;

/**
 * A bag class used by ConnectivityService for holding a collection of most recent
 * information published by a particular NetworkAgent as well as the
 * AsyncChannel/messenger for reaching that NetworkAgent and lists of NetworkRequests
 * interested in using it.
 */
public class NetworkAgentInfo {
    public NetworkInfo networkInfo;
    public Network network;
    public LinkProperties linkProperties;
    public NetworkCapabilities networkCapabilities;
    public final NetworkMonitor networkMonitor;
    public final NetworkMisc networkMisc;
    // Indicates if netd has been told to create this Network.  Once created the appropriate routing
    // rules are setup and routes are added so packets can begin flowing over the Network.
    // NOTE: This is a sticky bit; once set it is never cleared.
    public boolean created;
    // Set to true if this Network successfully passed validation or if it did not satisfy the
    // default NetworkRequest in which case validation will not be attempted.
    // NOTE: This is a sticky bit; once set it is never cleared even if future validation attempts
    // fail.
    public boolean everValidated;

    // The result of the last validation attempt on this network (true if validated, false if not).
    // This bit exists only because we never unvalidate a network once it's been validated, and that
    // is because the network scoring and revalidation code does not (may not?) deal properly with
    // networks becoming unvalidated.
    // TODO: Fix the network scoring code, remove this, and rename everValidated to validated.
    public boolean lastValidated;

    // This represents the last score received from the NetworkAgent.
    private int currentScore;
    // Penalty applied to scores of Networks that have not been validated.
    private static final int UNVALIDATED_SCORE_PENALTY = 40;

    // Score for explicitly connected network.
    private static final int EXPLICITLY_SELECTED_NETWORK_SCORE = 100;

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

    public NetworkAgentInfo(Messenger messenger, AsyncChannel ac, NetworkInfo info,
            LinkProperties lp, NetworkCapabilities nc, int score, Context context, Handler handler,
            NetworkMisc misc, NetworkRequest defaultRequest) {
        this.messenger = messenger;
        asyncChannel = ac;
        network = null;
        networkInfo = info;
        linkProperties = lp;
        networkCapabilities = nc;
        currentScore = score;
        networkMonitor = new NetworkMonitor(context, handler, this, defaultRequest);
        networkMisc = misc;
        created = false;
        everValidated = false;
        lastValidated = false;
    }

    public void addRequest(NetworkRequest networkRequest) {
        networkRequests.put(networkRequest.requestId, networkRequest);
    }

    // Does this network satisfy request?
    public boolean satisfies(NetworkRequest request) {
        return created &&
                request.networkCapabilities.satisfiedByNetworkCapabilities(networkCapabilities);
    }

    public boolean isVPN() {
        return networkCapabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN);
    }

    private int getCurrentScore(boolean pretendValidated) {
        // TODO: We may want to refactor this into a NetworkScore class that takes a base score from
        // the NetworkAgent and signals from the NetworkAgent and uses those signals to modify the
        // score.  The NetworkScore class would provide a nice place to centralize score constants
        // so they are not scattered about the transports.

        int score = currentScore;

        if (!everValidated && !pretendValidated) score -= UNVALIDATED_SCORE_PENALTY;
        if (score < 0) score = 0;

        if (networkMisc.explicitlySelected) score = EXPLICITLY_SELECTED_NETWORK_SCORE;

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
                "created{" + created + "}  " +
                "explicitlySelected{" + networkMisc.explicitlySelected + "} }";
    }

    public String name() {
        return "NetworkAgentInfo [" + networkInfo.getTypeName() + " (" +
                networkInfo.getSubtypeName() + ") - " +
                (network == null ? "null" : network.toString()) + "]";
    }
}
