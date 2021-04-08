/*
 * Copyright (C) 2021 The Android Open Source Project
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
import android.net.INetworkOfferCallback;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.os.RemoteException;

import java.util.HashSet;
import java.util.Objects;
import java.util.Set;

/**
 * Represents an offer made by a NetworkProvider to create a network if a need arises.
 *
 * This class contains the prospective score and capabilities of the network. The provider
 * is not obligated to caps able to create a network satisfying this, nor to build a network
 * with the exact score and/or capabilities passed ; after all, not all providers know in
 * advance what a network will look like after it's connected. Instead, this is meant as a
 * filter to limit requests sent to the provider by connectivity to those that this offer stands
 * a chance to fulfill.
 *
 * @see NetworkProvider#offerNetwork.
 *
 * @hide
 */
public class NetworkOffer implements NetworkRanker.Scoreable {
    @NonNull public final FullScore score;
    @NonNull public final NetworkCapabilities caps;
    @NonNull public final INetworkOfferCallback callback;
    @NonNull public final int providerId;
    // While this could, in principle, be deduced from the old values of the satisfying networks,
    // doing so would add a lot of complexity and performance penalties. For each request, the
    // ranker would have to run again to figure out if this offer used to be able to beat the
    // previous satisfier to know if there is a change in whether this offer is now needed ;
    // besides, there would be a need to handle an edge case when a new request comes online,
    // where it's not satisfied before the first rematch, where starting to satisfy a request
    // should not result in sending unneeded to this offer. This boolean, while requiring that
    // the offers are only ever manipulated on the CS thread, is by far a simpler and
    // economical solution.
    private final Set<NetworkRequest> mCurrentlyNeeded = new HashSet<>();

    public NetworkOffer(@NonNull final FullScore score,
            @NonNull final NetworkCapabilities caps,
            @NonNull final INetworkOfferCallback callback,
            @NonNull final int providerId) {
        this.score = Objects.requireNonNull(score);
        this.caps = Objects.requireNonNull(caps);
        this.callback = Objects.requireNonNull(callback);
        this.providerId = providerId;
    }

    /**
     * Get the score filter of this offer
     */
    @Override @NonNull public FullScore getScore() {
        return score;
    }

    /**
     * Get the capabilities filter of this offer
     */
    @Override @NonNull public NetworkCapabilities getCaps() {
        return caps;
    }

    /**
     * Tell the provider for this offer that the network is needed for a request.
     * @param request the request for which the offer is needed
     */
    public void onNetworkNeeded(@NonNull final NetworkRequest request) {
        if (mCurrentlyNeeded.contains(request)) {
            throw new IllegalStateException("Network already needed");
        }
        mCurrentlyNeeded.add(request);
        try {
            callback.onNetworkNeeded(request);
        } catch (final RemoteException e) {
            // The provider is dead. It will be removed by the death recipient.
        }
    }

    /**
     * Tell the provider for this offer that the network is no longer needed for this request.
     *
     * onNetworkNeeded will have been called with the same request before.
     *
     * @param request the request
     */
    public void onNetworkUnneeded(@NonNull final NetworkRequest request) {
        if (!mCurrentlyNeeded.contains(request)) {
            throw new IllegalStateException("Network already unneeded");
        }
        mCurrentlyNeeded.remove(request);
        try {
            callback.onNetworkUnneeded(request);
        } catch (final RemoteException e) {
            // The provider is dead. It will be removed by the death recipient.
        }
    }

    /**
     * Returns whether this offer is currently needed for this request.
     * @param request the request
     * @return whether the offer is currently considered needed
     */
    public boolean neededFor(@NonNull final NetworkRequest request) {
        return mCurrentlyNeeded.contains(request);
    }

    /**
     * Migrate from, and take over, a previous offer.
     *
     * When an updated offer is sent from a provider, call this method on the new offer, passing
     * the old one, to take over the state.
     *
     * @param previousOffer the previous offer
     */
    public void migrateFrom(@NonNull final NetworkOffer previousOffer) {
        if (!callback.equals(previousOffer.callback)) {
            throw new IllegalArgumentException("Can only migrate from a previous version of"
                    + " the same offer");
        }
        mCurrentlyNeeded.clear();
        mCurrentlyNeeded.addAll(previousOffer.mCurrentlyNeeded);
    }

    @Override
    public String toString() {
        return "NetworkOffer [ Score " + score + " ]";
    }
}
