/**
 * Copyright (c) 2014, The Android Open Source Project
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

import android.net.INetworkScoreCache;
import android.net.NetworkKey;
import android.net.NetworkScorerAppData;
import android.net.ScoredNetwork;

/**
 * A service for updating network scores from a network scorer application.
 * @hide
 */
interface INetworkScoreService
{
    /**
     * Update scores.
     * @return whether the update was successful.
     * @throws SecurityException if the caller is not the current active scorer.
     */
    boolean updateScores(in ScoredNetwork[] networks);

    /**
     * Clear all scores.
     * @return whether the clear was successful.
     * @throws SecurityException if the caller is neither the current active scorer nor the system.
     */
    boolean clearScores();

    /**
     * Set the active scorer and clear existing scores.
     * @param packageName the package name of the new scorer to use.
     * @return true if the operation succeeded, or false if the new package is not a valid scorer.
     * @throws SecurityException if the caller is not the system or a network scorer.
     */
    boolean setActiveScorer(in String packageName);

    /**
     * Disable the current active scorer and clear existing scores.
     * @throws SecurityException if the caller is not the current scorer or the system.
     */
    void disableScoring();

    /**
     * Register a cache to receive scoring updates.
     *
     * @param networkType the type of network this cache can handle. See {@link NetworkKey#type}
     * @param scoreCache implementation of {@link INetworkScoreCache} to store the scores
     * @param filterType the {@link CacheUpdateFilter} to apply
     * @throws SecurityException if the caller is not the system
     * @throws IllegalArgumentException if a score cache is already registed for this type
     * @hide
     */
    void registerNetworkScoreCache(int networkType, INetworkScoreCache scoreCache, int filterType);

    /**
     * Unregister a cache to receive scoring updates.
     *
     * @param networkType the type of network this cache can handle. See {@link NetworkKey#type}.
     * @param scoreCache implementation of {@link INetworkScoreCache} to store the scores.
     * @throws SecurityException if the caller is not the system.
     * @hide
     */
    void unregisterNetworkScoreCache(int networkType, INetworkScoreCache scoreCache);

    /**
     * Request scoring for networks.
     *
     * Implementations should delegate to the registered network recommendation provider or
     * fulfill the request locally if possible.
     *
     * @param networks an array of {@link NetworkKey}s to score
     * @return true if the request was delegated or fulfilled locally, false otherwise
     * @throws SecurityException if the caller is not the system
     * @hide
     */
    boolean requestScores(in NetworkKey[] networks);

    /**
     * Determine whether the application with the given UID is the enabled scorer.
     *
     * @param callingUid the UID to check
     * @return true if the provided UID is the active scorer, false otherwise.
     * @hide
     */
    boolean isCallerActiveScorer(int callingUid);

    /**
     * Obtain the package name of the current active network scorer.
     *
     * @return the full package name of the current active scorer, or null if there is no active
     *         scorer.
     */
    String getActiveScorerPackage();

    /**
     * Returns metadata about the active scorer or <code>null</code> if there is no active scorer.
     */
    NetworkScorerAppData getActiveScorer();

    /**
     * Returns the list of available scorer apps. The list will be empty if there are
     * no valid scorers.
     */
    List<NetworkScorerAppData> getAllValidScorers();
}
