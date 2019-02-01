/**
 * Copyright (c) 2019, The Android Open Source Project
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
package android.net.ip;

import android.net.LinkPropertiesParcelable;
import android.net.ip.IIpClient;
import android.net.DhcpResultsParcelable;

/** @hide */
oneway interface IIpClientCallbacks {
    void onIpClientCreated(in IIpClient ipClient);

    void onPreDhcpAction();
    void onPostDhcpAction();

    // This is purely advisory and not an indication of provisioning
    // success or failure.  This is only here for callers that want to
    // expose DHCPv4 results to other APIs (e.g., WifiInfo#setInetAddress).
    // DHCPv4 or static IPv4 configuration failure or success can be
    // determined by whether or not the passed-in DhcpResults object is
    // null or not.
    void onNewDhcpResults(in DhcpResultsParcelable dhcpResults);

    void onProvisioningSuccess(in LinkPropertiesParcelable newLp);
    void onProvisioningFailure(in LinkPropertiesParcelable newLp);

    // Invoked on LinkProperties changes.
    void onLinkPropertiesChange(in LinkPropertiesParcelable newLp);

    // Called when the internal IpReachabilityMonitor (if enabled) has
    // detected the loss of a critical number of required neighbors.
    void onReachabilityLost(in String logMsg);

    // Called when the IpClient state machine terminates.
    void onQuit();

    // Install an APF program to filter incoming packets.
    void installPacketFilter(in byte[] filter);

    // Asynchronously read back the APF program & data buffer from the wifi driver.
    // Due to Wifi HAL limitations, the current implementation only supports dumping the entire
    // buffer. In response to this request, the driver returns the data buffer asynchronously
    // by sending an IpClient#EVENT_READ_PACKET_FILTER_COMPLETE message.
    void startReadPacketFilter();

    // If multicast filtering cannot be accomplished with APF, this function will be called to
    // actuate multicast filtering using another means.
    void setFallbackMulticastFilter(boolean enabled);

    // Enabled/disable Neighbor Discover offload functionality. This is
    // called, for example, whenever 464xlat is being started or stopped.
    void setNeighborDiscoveryOffload(boolean enable);
}