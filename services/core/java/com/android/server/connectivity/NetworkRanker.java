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

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;

import java.util.Collection;

/**
 * A class that knows how to find the best network matching a request out of a list of networks.
 */
public class NetworkRanker {
    public NetworkRanker() { }

    /**
     * Find the best network satisfying this request among the list of passed networks.
     */
    // Almost equivalent to Collections.max(nais), but allows returning null if no network
    // satisfies the request.
    @Nullable
    public NetworkAgentInfo getBestNetwork(@NonNull final NetworkRequest request,
            @NonNull final Collection<NetworkAgentInfo> nais) {
        NetworkAgentInfo bestNetwork = null;
        int bestScore = Integer.MIN_VALUE;
        for (final NetworkAgentInfo nai : nais) {
            if (!nai.satisfies(request)) continue;
            if (nai.getCurrentScore() > bestScore) {
                bestNetwork = nai;
                bestScore = nai.getCurrentScore();
            }
        }
        return bestNetwork;
    }

    /**
     * Returns whether an offer has a chance to beat a champion network for a request.
     *
     * Offers are sent by network providers when they think they might be able to make a network
     * with the characteristics contained in the offer. If the offer has no chance to beat
     * the currently best network for a given request, there is no point in the provider spending
     * power trying to find and bring up such a network.
     *
     * Note that having an offer up does not constitute a commitment from the provider part
     * to be able to bring up a network with these characteristics, or a network at all for
     * that matter. This is only used to save power by letting providers know when they can't
     * beat a current champion.
     *
     * @param request The request to evaluate against.
     * @param championScore The currently best network for this request.
     * @param offer The offer.
     * @return Whether the offer stands a chance to beat the champion.
     */
    public boolean mightBeat(@NonNull final NetworkRequest request,
            @Nullable final FullScore championScore,
            @NonNull final NetworkOffer offer) {
        // If this network can't even satisfy the request then it can't beat anything, not
        // even an absence of network. It can't satisfy it anyway.
        if (!request.canBeSatisfiedBy(offer.caps)) return false;
        // If there is no satisfying network, then this network can beat, because some network
        // is always better than no network.
        if (null == championScore) return true;
        final int offerIntScore;
        if (offer.caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) {
            // If the offer might have Internet access, then it might validate.
            offerIntScore = offer.score.getLegacyIntAsValidated();
        } else {
            offerIntScore = offer.score.getLegacyInt();
        }
        return championScore.getLegacyInt() < offerIntScore;
    }
}
