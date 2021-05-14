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
import android.annotation.Nullable;
import android.net.INetworkOfferCallback;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.os.Messenger;

import java.util.Objects;


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
public class NetworkOffer {
    @NonNull public final FullScore score;
    @NonNull public final NetworkCapabilities caps;
    @NonNull public final INetworkOfferCallback callback;
    @NonNull public final Messenger provider;

    private static NetworkCapabilities emptyCaps() {
        final NetworkCapabilities nc = new NetworkCapabilities();
        return nc;
    }

    // Ideally the caps argument would be non-null, but null has historically meant no filter
    // and telephony passes null. Keep backward compatibility.
    public NetworkOffer(@NonNull final FullScore score,
            @Nullable final NetworkCapabilities caps,
            @NonNull final INetworkOfferCallback callback,
            @NonNull final Messenger provider) {
        this.score = Objects.requireNonNull(score);
        this.caps = null != caps ? caps : emptyCaps();
        this.callback = Objects.requireNonNull(callback);
        this.provider = Objects.requireNonNull(provider);
    }

    /**
     * Migrate from, and take over, a previous offer.
     *
     * When an updated offer is sent from a provider, call this method on the new offer, passing
     * the old one, to take over the state.
     *
     * @param previousOffer
     */
    public void migrateFrom(@NonNull final NetworkOffer previousOffer) {
        if (!callback.equals(previousOffer.callback)) {
            throw new IllegalArgumentException("Can only migrate from a previous version of"
                    + " the same offer");
        }
    }

    /**
     * Returns whether an offer can satisfy a NetworkRequest, according to its capabilities.
     * @param request The request to test against.
     * @return Whether this offer can satisfy the request.
     */
    public final boolean canSatisfy(@NonNull final NetworkRequest request) {
        return request.networkCapabilities.satisfiedByNetworkCapabilities(caps);
    }

    @Override
    public String toString() {
        return "NetworkOffer [ Score " + score + " ]";
    }
}
