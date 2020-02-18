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
        // Enforce policy.
        filterBadWifiAvoidancePolicy(candidates);

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

    // If some network with wifi transport is present, drop all networks with POLICY_IGNORE_ON_WIFI.
    private void filterBadWifiAvoidancePolicy(
            @NonNull final ArrayList<NetworkAgentInfo> candidates) {
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
}
