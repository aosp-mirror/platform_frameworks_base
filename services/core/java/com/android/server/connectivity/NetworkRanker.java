/*
 * Copyright (C) 2020 The Android Open Source Project
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

import static android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET;
import static android.net.NetworkCapabilities.NET_CAPABILITY_VALIDATED;
import static android.net.NetworkCapabilities.TRANSPORT_VPN;
import static android.net.NetworkScore.POLICY_IGNORE_ON_WIFI;

import static com.android.internal.util.FunctionalUtils.findFirst;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;

import java.util.ArrayList;
import java.util.Collection;

/**
 * A class that knows how to find the best network matching a request out of a list of networks.
 */
public class NetworkRanker {
    public NetworkRanker() { }

    /**
     * Find the best network satisfying this request among the list of passed networks.
     */
    @Nullable
    public NetworkAgentInfo getBestNetwork(@NonNull final NetworkRequest request,
            @NonNull final Collection<NetworkAgentInfo> nais) {
        final ArrayList<NetworkAgentInfo> candidates = new ArrayList<>(nais);
        candidates.removeIf(nai -> !nai.satisfies(request));

        // Enforce policy. The order in which the policy is computed is essential, because each
        // step may remove some of the candidates. For example, filterValidated drops non-validated
        // networks in presence of validated networks for INTERNET requests, but the bad wifi
        // avoidance policy takes priority over this, so it must be done before.
        filterVpn(candidates);
        filterExplicitlySelected(candidates);
        filterBadWifiAvoidance(candidates);
        filterValidated(request, candidates);

        NetworkAgentInfo bestNetwork = null;
        int bestScore = Integer.MIN_VALUE;
        for (final NetworkAgentInfo nai : candidates) {
            final int score = nai.getCurrentScore();
            if (score > bestScore) {
                bestNetwork = nai;
                bestScore = score;
            }
        }
        return bestNetwork;
    }

    // If a network is a VPN it has priority.
    private void filterVpn(@NonNull final ArrayList<NetworkAgentInfo> candidates) {
        final NetworkAgentInfo vpn = findFirst(candidates,
                nai -> nai.networkCapabilities.hasTransport(TRANSPORT_VPN));
        if (null == vpn) return; // No VPN : this policy doesn't apply.
        candidates.removeIf(nai -> !nai.networkCapabilities.hasTransport(TRANSPORT_VPN));
    }

    // If some network is explicitly selected and set to accept unvalidated connectivity, then
    // drop all networks that are not explicitly selected.
    private void filterExplicitlySelected(
            @NonNull final ArrayList<NetworkAgentInfo> candidates) {
        final NetworkAgentInfo explicitlySelected = findFirst(candidates,
                nai -> nai.networkAgentConfig.explicitlySelected
                        && nai.networkAgentConfig.acceptUnvalidated);
        if (null == explicitlySelected) return; // No explicitly selected network accepting unvalid
        candidates.removeIf(nai -> !nai.networkAgentConfig.explicitlySelected);
    }

    // If some network with wifi transport is present, drop all networks with POLICY_IGNORE_ON_WIFI.
    private void filterBadWifiAvoidance(@NonNull final ArrayList<NetworkAgentInfo> candidates) {
        final NetworkAgentInfo wifi = findFirst(candidates,
                nai -> nai.networkCapabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
                        && nai.everValidated
                        // Horrible hack : there is old UI that will let a user say they want to
                        // override the policy only for this network only at this time and it
                        // feeds into the following member. This old UI should probably be removed
                        // but for now keep backward compatibility.
                        && !nai.avoidUnvalidated);
        if (null == wifi) return; // No wifi : this policy doesn't apply
        candidates.removeIf(nai -> nai.getNetworkScore().hasPolicy(POLICY_IGNORE_ON_WIFI));
    }

    // If some network is validated and the request asks for INTERNET, drop all networks that are
    // not validated.
    private void filterValidated(@NonNull final NetworkRequest request,
            @NonNull final ArrayList<NetworkAgentInfo> candidates) {
        if (!request.hasCapability(NET_CAPABILITY_INTERNET)) return;
        final NetworkAgentInfo validated = findFirst(candidates,
                nai -> nai.networkCapabilities.hasCapability(NET_CAPABILITY_VALIDATED));
        if (null == validated) return; // No validated network
        candidates.removeIf(nai ->
                !nai.networkCapabilities.hasCapability(NET_CAPABILITY_VALIDATED));
    }
}
