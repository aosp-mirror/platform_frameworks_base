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
    public final Network network;
    public LinkProperties linkProperties;
    public NetworkCapabilities networkCapabilities;
    public int currentScore;
    public final NetworkMonitor networkMonitor;

    // The list of NetworkRequests being satisfied by this Network.
    public final SparseArray<NetworkRequest> networkRequests = new SparseArray<NetworkRequest>();
    public final ArrayList<NetworkRequest> networkLingered = new ArrayList<NetworkRequest>();

    public final Messenger messenger;
    public final AsyncChannel asyncChannel;

    public NetworkAgentInfo(Messenger messenger, AsyncChannel ac, int netId, NetworkInfo info,
            LinkProperties lp, NetworkCapabilities nc, int score, Context context,
            Handler handler) {
        this.messenger = messenger;
        asyncChannel = ac;
        network = new Network(netId);
        networkInfo = info;
        linkProperties = lp;
        networkCapabilities = nc;
        currentScore = score;
        networkMonitor = new NetworkMonitor(context, handler, this);
    }

    public void addRequest(NetworkRequest networkRequest) {
        networkRequests.put(networkRequest.requestId, networkRequest);
    }

    public String toString() {
        return "NetworkAgentInfo{ ni{" + networkInfo + "}  network{" +
                network + "}  lp{" +
                linkProperties + "}  nc{" +
                networkCapabilities + "}  Score{" + currentScore + "} }";
    }

    public String name() {
        return "NetworkAgentInfo [" + networkInfo.getTypeName() + " (" +
                networkInfo.getSubtypeName() + ")]";
    }
}
