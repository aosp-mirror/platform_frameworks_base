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
import android.net.Network;
import android.net.NetworkState;
import android.net.NetworkStats;
import android.net.NetworkStatsHistory;
import android.net.NetworkTemplate;
import android.os.IBinder;
import android.os.Messenger;
import com.android.internal.net.VpnInfo;

/** {@hide} */
interface INetworkStatsService {

    /** Start a statistics query session. */
    @UnsupportedAppUsage
    INetworkStatsSession openSession();

    /** Start a statistics query session. If calling package is profile or device owner then it is
     *  granted automatic access if apiLevel is NetworkStatsManager.API_LEVEL_DPC_ALLOWED. If
     *  apiLevel is at least NetworkStatsManager.API_LEVEL_REQUIRES_PACKAGE_USAGE_STATS then
     *  PACKAGE_USAGE_STATS permission is always checked. If PACKAGE_USAGE_STATS is not granted
     *  READ_NETWORK_USAGE_STATS is checked for.
     */
    @UnsupportedAppUsage
    INetworkStatsSession openSessionForUsageStats(int flags, String callingPackage);

    /** Return data layer snapshot of UID network usage. */
    @UnsupportedAppUsage
    NetworkStats getDataLayerSnapshotForUid(int uid);

    /** Get a detailed snapshot of stats since boot for all UIDs.
    *
    * <p>Results will not always be limited to stats on requiredIfaces when specified: stats for
    * interfaces stacked on the specified interfaces, or for interfaces on which the specified
    * interfaces are stacked on, will also be included.
    * @param requiredIfaces Interface names to get data for, or {@link NetworkStats#INTERFACES_ALL}.
    */
    NetworkStats getDetailedUidStats(in String[] requiredIfaces);

    /** Return set of any ifaces associated with mobile networks since boot. */
    @UnsupportedAppUsage
    String[] getMobileIfaces();

    /** Increment data layer count of operations performed for UID and tag. */
    void incrementOperationCount(int uid, int tag, int operationCount);

    /** Force update of ifaces. */
    void forceUpdateIfaces(
         in Network[] defaultNetworks,
         in VpnInfo[] vpnArray,
         in NetworkState[] networkStates,
         in String activeIface);
    /** Force update of statistics. */
    @UnsupportedAppUsage
    void forceUpdate();

    /** Registers a callback on data usage. */
    DataUsageRequest registerUsageCallback(String callingPackage,
            in DataUsageRequest request, in Messenger messenger, in IBinder binder);

    /** Unregisters a callback on data usage. */
    void unregisterUsageRequest(in DataUsageRequest request);

    /** Get the uid stats information since boot */
    long getUidStats(int uid, int type);

    /** Get the iface stats information since boot */
    long getIfaceStats(String iface, int type);

    /** Get the total network stats information since boot */
    long getTotalStats(int type);

}
