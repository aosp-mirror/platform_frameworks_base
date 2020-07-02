/*
 * Copyright (C) 2019 The Android Open Source Project
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

package android.net.ip;

import android.net.DhcpResultsParcelable;
import android.net.Layer2PacketParcelable;
import android.net.LinkProperties;

import java.util.List;

/**
 * Callbacks for handling IpClient events.
 *
 * This is a convenience class to allow clients not to override all methods of IIpClientCallbacks,
 * and avoid unparceling arguments.
 * These methods are called asynchronously on a Binder thread, as IpClient lives in a different
 * process.
 * @hide
 */
public class IpClientCallbacks {

    /**
     * Callback called upon IpClient creation.
     *
     * @param ipClient The Binder token to communicate with IpClient.
     */
    public void onIpClientCreated(IIpClient ipClient) {}

    /**
     * Callback called prior to DHCP discovery/renewal.
     *
     * <p>In order to receive onPreDhcpAction(), call #withPreDhcpAction() when constructing a
     * ProvisioningConfiguration.
     *
     * <p>Implementations of onPreDhcpAction() must call IpClient#completedPreDhcpAction() to
     * indicate that DHCP is clear to proceed.
      */
    public void onPreDhcpAction() {}

    /**
     * Callback called after DHCP discovery/renewal.
     */
    public void onPostDhcpAction() {}

    /**
     * Callback called when new DHCP results are available.
     *
     * <p>This is purely advisory and not an indication of provisioning success or failure.  This is
     * only here for callers that want to expose DHCPv4 results to other APIs
     * (e.g., WifiInfo#setInetAddress).
     *
     * <p>DHCPv4 or static IPv4 configuration failure or success can be determined by whether or not
     * the passed-in DhcpResults object is null.
     */
    public void onNewDhcpResults(DhcpResultsParcelable dhcpResults) {
        // In general callbacks would not use a parcelable directly (DhcpResultsParcelable), and
        // would use a wrapper instead, because of the lack of safety of stable parcelables. But
        // there are already two classes in the tree for DHCP information: DhcpInfo and DhcpResults,
        // and neither of them exposes an appropriate API (they are bags of mutable fields and can't
        // be changed because they are public API and @UnsupportedAppUsage, being no better than the
        // stable parcelable). Adding a third class would cost more than the gain considering that
        // the only client of this callback is WiFi, which will end up converting the results to
        // DhcpInfo anyway.
    }

    /**
     * Indicates that provisioning was successful.
     */
    public void onProvisioningSuccess(LinkProperties newLp) {}

    /**
     * Indicates that provisioning failed.
     */
    public void onProvisioningFailure(LinkProperties newLp) {}

    /**
     * Invoked on LinkProperties changes.
     */
    public void onLinkPropertiesChange(LinkProperties newLp) {}

    /**Called when the internal IpReachabilityMonitor (if enabled) has
     * detected the loss of a critical number of required neighbors.
     */
    public void onReachabilityLost(String logMsg) {}

    /**
     * Called when the IpClient state machine terminates.
     */
    public void onQuit() {}

    /**
     * Called to indicate that a new APF program must be installed to filter incoming packets.
     */
    public void installPacketFilter(byte[] filter) {}

    /**
     * Called to indicate that the APF Program & data buffer must be read asynchronously from the
     * wifi driver.
     *
     * <p>Due to Wifi HAL limitations, the current implementation only supports dumping the entire
     * buffer. In response to this request, the driver returns the data buffer asynchronously
     * by sending an IpClient#EVENT_READ_PACKET_FILTER_COMPLETE message.
     */
    public void startReadPacketFilter() {}

    /**
     * If multicast filtering cannot be accomplished with APF, this function will be called to
     * actuate multicast filtering using another means.
     */
    public void setFallbackMulticastFilter(boolean enabled) {}

    /**
     * Enabled/disable Neighbor Discover offload functionality. This is called, for example,
     * whenever 464xlat is being started or stopped.
     */
    public void setNeighborDiscoveryOffload(boolean enable) {}

    /**
     * Invoked on starting preconnection process.
     */
    public void onPreconnectionStart(List<Layer2PacketParcelable> packets) {}
}
