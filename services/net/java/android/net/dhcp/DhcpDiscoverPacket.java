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

/**
 * This class implements the DHCP-DISCOVER packet.
 */
class DhcpDiscoverPacket extends DhcpPacket {
    /**
     * Generates a DISCOVER packet with the specified parameters.
     */
    DhcpDiscoverPacket(int transId, byte[] clientMac, boolean broadcast) {
        super(transId, Inet4Address.ANY, Inet4Address.ANY, Inet4Address.ANY,
            Inet4Address.ANY, clientMac, broadcast);
    }

    public String toString() {
        String s = super.toString();
        return s + " DISCOVER " +
                (mBroadcast ? "broadcast " : "unicast ");
    }

    /**
     * Fills in a packet with the requested DISCOVER parameters.
     */
    public ByteBuffer buildPacket(int encap, short destUdp, short srcUdp) {
        ByteBuffer result = ByteBuffer.allocate(MAX_LENGTH);
        InetAddress destIp = Inet4Address.ALL;

        fillInPacket(encap, Inet4Address.ALL, Inet4Address.ANY, destUdp, srcUdp,
            result, DHCP_BOOTREQUEST, true);
        result.flip();
        return result;
    }

    /**
     * Adds optional parameters to a DISCOVER packet.
     */
    void finishPacket(ByteBuffer buffer) {
        addTlv(buffer, DHCP_MESSAGE_TYPE, DHCP_MESSAGE_TYPE_DISCOVER);
        addTlv(buffer, DHCP_PARAMETER_LIST, mRequestedParams);
        addTlvEnd(buffer);
    }

    /**
     * Informs the state machine of the arrival of a DISCOVER packet.
     */
    public void doNextOp(DhcpStateMachine machine) {
        // currently omitted: host name
        machine.onDiscoverReceived(mBroadcast, mTransId, mClientMac,
            mRequestedParams);
    }
}
