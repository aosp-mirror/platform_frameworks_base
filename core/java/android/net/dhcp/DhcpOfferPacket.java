/*
 * Copyright (C) 2010 The Android Open Source Project
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

package android.net.dhcp;

import java.net.InetAddress;
import java.net.Inet4Address;
import java.nio.ByteBuffer;
import java.util.List;

/**
 * This class implements the DHCP-OFFER packet.
 */
class DhcpOfferPacket extends DhcpPacket {
    /**
     * The IP address of the server which sent this packet.
     */
    private final InetAddress mSrcIp;

    /**
     * Generates a OFFER packet with the specified parameters.
     */
    DhcpOfferPacket(int transId, boolean broadcast, InetAddress serverAddress,
                    InetAddress clientIp, byte[] clientMac) {
        super(transId, Inet4Address.ANY, clientIp, Inet4Address.ANY,
            Inet4Address.ANY, clientMac, broadcast);
        mSrcIp = serverAddress;
    }

    public String toString() {
        String s = super.toString();
        String dnsServers = ", DNS servers: ";

        if (mDnsServers != null) {
            for (InetAddress dnsServer: mDnsServers) {
                dnsServers += dnsServer + " ";
            }
        }

        return s + " OFFER, ip " + mYourIp + ", mask " + mSubnetMask +
                dnsServers + ", gateway " + mGateway +
                " lease time " + mLeaseTime + ", domain " + mDomainName;
    }

    /**
     * Fills in a packet with the specified OFFER attributes.
     */
    public ByteBuffer buildPacket(int encap, short destUdp, short srcUdp) {
        ByteBuffer result = ByteBuffer.allocate(MAX_LENGTH);
        InetAddress destIp = mBroadcast ? Inet4Address.ALL : mYourIp;
        InetAddress srcIp = mBroadcast ? Inet4Address.ANY : mSrcIp;

        fillInPacket(encap, destIp, srcIp, destUdp, srcUdp, result,
            DHCP_BOOTREPLY, mBroadcast);
        result.flip();
        return result;
    }

    /**
     * Adds the optional parameters to the server-generated OFFER packet.
     */
    void finishPacket(ByteBuffer buffer) {
        addTlv(buffer, DHCP_MESSAGE_TYPE, DHCP_MESSAGE_TYPE_OFFER);
        addTlv(buffer, DHCP_SERVER_IDENTIFIER, mServerIdentifier);
        addTlv(buffer, DHCP_LEASE_TIME, mLeaseTime);

        // the client should renew at 1/2 the lease-expiry interval
        if (mLeaseTime != null) {
            addTlv(buffer, DHCP_RENEWAL_TIME,
                Integer.valueOf(mLeaseTime.intValue() / 2));
        }

        addTlv(buffer, DHCP_SUBNET_MASK, mSubnetMask);
        addTlv(buffer, DHCP_ROUTER, mGateway);
        addTlv(buffer, DHCP_DOMAIN_NAME, mDomainName);
        addTlv(buffer, DHCP_BROADCAST_ADDRESS, mBroadcastAddress);
        addTlv(buffer, DHCP_DNS_SERVER, mDnsServers);
        addTlvEnd(buffer);
    }

    /**
     * Notifies the state machine of the OFFER packet parameters.
     */
    public void doNextOp(DhcpStateMachine machine) {
        machine.onOfferReceived(mBroadcast, mTransId, mClientMac, mYourIp,
            mServerIdentifier);
    }
}
