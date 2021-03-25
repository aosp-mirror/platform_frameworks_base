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

package android.net;

import android.net.NetworkRequest;

/**
 * A callback registered with connectivity by network providers together with
 * a NetworkOffer.
 *
 * When the offer is needed to satisfy some application or system component,
 * connectivity will call onOfferNeeded on this callback. When this happens,
 * the provider should try and bring up the network.
 *
 * When the offer is no longer needed, for example because the application has
 * withdrawn the request or if the request is being satisfied by a network
 * that this offer will never be able to beat, connectivity calls
 * onOfferUnneeded. When this happens, the provider should stop trying to
 * bring up the network, or tear it down if it has already been brought up.
 *
 * When NetworkProvider#offerNetwork is called, the provider can expect to
 * immediately receive all requests that can be fulfilled by that offer and
 * are not already satisfied by a better network. It is possible no such
 * request is currently outstanding, because no requests have been made that
 * can be satisfied by this offer, or because all such requests are already
 * satisfied by a better network.
 * onOfferNeeded can be called at any time after registration and until the
 * offer is withdrawn with NetworkProvider#unofferNetwork is called. This
 * typically happens when a new network request is filed by an application,
 * or when the network satisfying a request disconnects and this offer now
 * stands a chance to be the best network for it.
 *
 * @hide
 */
oneway interface INetworkOfferCallback {
    /**
     * Informs the registrant that the offer is needed to fulfill this request.
     * @param networkRequest the request to satisfy
     * @param providerId the ID of the provider currently satisfying
     *          this request, or NetworkProvider.ID_NONE if none.
     */
    void onOfferNeeded(in NetworkRequest networkRequest, int providerId);

    /**
     * Informs the registrant that the offer is no longer needed to fulfill this request.
     */
    void onOfferUnneeded(in NetworkRequest networkRequest);
}
