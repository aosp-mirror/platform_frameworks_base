/**
 * Copyright (c) 2016, The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package android.net;

import android.net.NetworkKey;
import android.net.RecommendationRequest;
import android.os.IRemoteCallback;

/**
 * The service responsible for answering network recommendation requests.
 * @hide
 */
oneway interface INetworkRecommendationProvider {

    /**
     * Request a recommendation for the best network to connect to
     * taking into account the inputs from the {@link RecommendationRequest}.
     *
     * @param request a {@link RecommendationRequest} instance containing the details of the request
     * @param callback a {@link IRemoteCallback} instance to invoke when the recommendation
     *                 is available
     * @param sequence an internal number used for tracking the request
     * @hide
     */
    void requestRecommendation(in RecommendationRequest request,
                               in IRemoteCallback callback,
                               int sequence);

    /**
     * Request scoring for networks.
     *
     * Implementations should use {@link NetworkScoreManager#updateScores(ScoredNetwork[])} to
     * respond to score requests.
     *
     * @param networks an array of {@link NetworkKey}s to score
     * @hide
     */
    void requestScores(in NetworkKey[] networks);
}