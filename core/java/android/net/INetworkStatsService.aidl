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

import android.net.DataUsageRequest;
import android.net.INetworkStatsSession;
import android.net.NetworkStats;
import android.net.NetworkStatsHistory;
import android.net.NetworkTemplate;
import android.os.IBinder;
import android.os.Messenger;

/** {@hide} */
interface INetworkStatsService {

    /** Start a statistics query session. */
    INetworkStatsSession openSession();

    /** Start a statistics query session. If calling package is profile or device owner then it is
     *  granted automatic access if apiLevel is NetworkStatsManager.API_LEVEL_DPC_ALLOWED. If
     *  apiLevel is at least NetworkStatsManager.API_LEVEL_REQUIRES_PACKAGE_USAGE_STATS then
     *  PACKAGE_USAGE_STATS permission is always checked. If PACKAGE_USAGE_STATS is not granted
     *  READ_NETWORK_USAGE_STATS is checked for.
     */
    INetworkStatsSession openSessionForUsageStats(String callingPackage);

    /** Return network layer usage total for traffic that matches template. */
    long getNetworkTotalBytes(in NetworkTemplate template, long start, long end);

    /** Return data layer snapshot of UID network usage. */
    NetworkStats getDataLayerSnapshotForUid(int uid);
    /** Return set of any ifaces associated with mobile networks since boot. */
    String[] getMobileIfaces();

    /** Increment data layer count of operations performed for UID and tag. */
    void incrementOperationCount(int uid, int tag, int operationCount);

    /** Mark given UID as being in foreground for stats purposes. */
    void setUidForeground(int uid, boolean uidForeground);

    /** Force update of ifaces. */
    void forceUpdateIfaces();
    /** Force update of statistics. */
    void forceUpdate();

    /** Advise persistance threshold; may be overridden internally. */
    void advisePersistThreshold(long thresholdBytes);

    /** Registers a callback on data usage. */
    DataUsageRequest registerUsageCallback(String callingPackage,
            in DataUsageRequest request, in Messenger messenger, in IBinder binder);

    /** Unregisters a callback on data usage. */
    void unregisterUsageRequest(in DataUsageRequest request);

}
