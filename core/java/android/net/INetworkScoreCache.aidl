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

import android.net.ScoredNetwork;

/**
 * A service which stores a subset of scored networks from the active network scorer.
 *
 * <p>To be implemented by network subsystems (e.g. Wi-Fi). NetworkScoreService will propagate
 * scores down to each subsystem depending on the network type. Implementations may register for
 * a given network type by calling NetworkScoreManager.registerNetworkSubsystem.
 *
 * <p>A proper implementation should throw SecurityException whenever the caller is not privileged.
 * It may request scores by calling NetworkScoreManager#requestScores(NetworkKey[]); a call to
 * updateScores may follow but may not depending on the active scorer's implementation, and in
 * general this method may be called at any time.
 *
 * <p>Implementations should also override dump() so that "adb shell dumpsys network_score" includes
 * the current scores for each network for debugging purposes.
 * @hide
 */
interface INetworkScoreCache
{
    void updateScores(in List<ScoredNetwork> networks);

    void clearScores();
}

