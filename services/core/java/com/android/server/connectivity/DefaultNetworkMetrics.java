/*
 * Copyright (C) 2017 The Android Open Source Project
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

import static android.net.ConnectivityManager.NETID_UNSET;

import android.net.LinkProperties;
import android.net.metrics.DefaultNetworkEvent;
import android.net.metrics.IpConnectivityLog;

/**
 * Tracks events related to the default network for the purpose of default network metrics.
 * {@hide}
 */
public class DefaultNetworkMetrics {

    private final IpConnectivityLog mMetricsLog = new IpConnectivityLog();

    public void logDefaultNetworkEvent(NetworkAgentInfo newNai, NetworkAgentInfo prevNai) {
        int newNetid = NETID_UNSET;
        int prevNetid = NETID_UNSET;
        int[] transports = new int[0];
        boolean hadIPv4 = false;
        boolean hadIPv6 = false;

        if (newNai != null) {
            newNetid = newNai.network.netId;
            transports = newNai.networkCapabilities.getTransportTypes();
        }
        if (prevNai != null) {
            prevNetid = prevNai.network.netId;
            final LinkProperties lp = prevNai.linkProperties;
            hadIPv4 = lp.hasIPv4Address() && lp.hasIPv4DefaultRoute();
            hadIPv6 = lp.hasGlobalIPv6Address() && lp.hasIPv6DefaultRoute();
        }

        mMetricsLog.log(new DefaultNetworkEvent(newNetid, transports, prevNetid, hadIPv4, hadIPv6));
    }
}
