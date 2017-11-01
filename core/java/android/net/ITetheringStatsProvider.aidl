/*
 * Copyright (C) 2017 The Android Open Source Project
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

/**
 * Interface for NetworkManagementService to query tethering statistics and set data limits.
 *
 * TODO: this does not really need to be an interface since Tethering runs in the same process
 * as NetworkManagementService. Consider refactoring Tethering to use direct access to
 * NetworkManagementService instead of using INetworkManagementService, and then deleting this
 * interface.
 *
 * @hide
 */
interface ITetheringStatsProvider {
    // Returns cumulative statistics for all tethering sessions since boot, on all upstreams.
    // @code {how} is one of the NetworkStats.STATS_PER_* constants. If {@code how} is
    // {@code STATS_PER_IFACE}, the provider should not include any traffic that is already
    // counted by kernel interface counters.
    NetworkStats getTetherStats(int how);

    // Sets the interface quota for the specified upstream interface. This is defined as the number
    // of bytes, starting from zero and counting from now, after which data should stop being
    // forwarded to/from the specified upstream. A value of QUOTA_UNLIMITED means there is no limit.
    void setInterfaceQuota(String iface, long quotaBytes);

    // Indicates that no data usage limit is set.
    const int QUOTA_UNLIMITED = -1;
}
