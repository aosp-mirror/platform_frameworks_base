/*
 * Copyright (C) 2011 The Android Open Source Project
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

import android.net.NetworkStats;
import android.net.NetworkStatsHistory;

/** {@hide} */
interface INetworkStatsService {

    /** Return historical stats for traffic that matches template. */
    NetworkStatsHistory getHistoryForNetwork(int networkTemplate);
    /** Return historical stats for specific UID traffic that matches template. */
    NetworkStatsHistory getHistoryForUid(int uid, int networkTemplate);

    /** Return usage summary for traffic that matches template. */
    NetworkStats getSummaryForNetwork(long start, long end, int networkTemplate, String subscriberId);
    /** Return usage summary per UID for traffic that matches template. */
    NetworkStats getSummaryForAllUid(long start, long end, int networkTemplate);

}
