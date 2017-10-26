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

package android.net.metrics;

import static android.net.ConnectivityManager.NETID_UNSET;

import android.net.NetworkCapabilities;

/**
 * An event recorded by ConnectivityService when there is a change in the default network.
 * {@hide}
 */
public class DefaultNetworkEvent {

    // The ID of the network that has become the new default or NETID_UNSET if none.
    public int netId = NETID_UNSET;
    // The list of transport types of the new default network, for example TRANSPORT_WIFI, as
    // defined in NetworkCapabilities.java.
    public int[] transportTypes = new int[0];
    // The ID of the network that was the default before or NETID_UNSET if none.
    public int prevNetId = NETID_UNSET;
    // Whether the previous network had IPv4/IPv6 connectivity.
    public boolean prevIPv4;
    public boolean prevIPv6;

    @Override
    public String toString() {
        String prevNetwork = String.valueOf(prevNetId);
        String newNetwork = String.valueOf(netId);
        if (prevNetId != 0) {
            prevNetwork += ":" + ipSupport();
        }
        if (netId != 0) {
            newNetwork += ":" + NetworkCapabilities.transportNamesOf(transportTypes);
        }
        return String.format("DefaultNetworkEvent(%s -> %s)", prevNetwork, newNetwork);
    }

    private String ipSupport() {
        if (prevIPv4 && prevIPv6) {
            return "IPv4v6";
        }
        if (prevIPv6) {
            return "IPv6";
        }
        if (prevIPv4) {
            return "IPv4";
        }
        return "NONE";
    }
}
