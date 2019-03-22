/**
 * Copyright (c) 2018, The Android Open Source Project
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
 * See the License for the specific language governing perNmissions and
 * limitations under the License.
 */
package android.net;

import android.net.IIpMemoryStoreCallbacks;
import android.net.INetworkMonitorCallbacks;
import android.net.Network;
import android.net.dhcp.DhcpServingParamsParcel;
import android.net.dhcp.IDhcpServerCallbacks;
import android.net.ip.IIpClientCallbacks;

/** @hide */
oneway interface INetworkStackConnector {
    void makeDhcpServer(in String ifName, in DhcpServingParamsParcel params,
        in IDhcpServerCallbacks cb);
    void makeNetworkMonitor(in Network network, String name, in INetworkMonitorCallbacks cb);
    void makeIpClient(in String ifName, in IIpClientCallbacks callbacks);
    void fetchIpMemoryStore(in IIpMemoryStoreCallbacks cb);
}
