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

import java.net.Inet4Address;
import java.nio.ByteBuffer;

/**
 * This class implements the DHCP-DECLINE packet.
 */
class DhcpDeclinePacket extends DhcpPacket {
    /**
     * Generates a DECLINE packet with the specified parameters.
     */
    DhcpDeclinePacket(int transId, short secs, Inet4Address clientIp, Inet4Address yourIp,
                      Inet4Address nextIp, Inet4Address relayIp,
                      byte[] clientMac) {
        super(transId, secs, clientIp, yourIp, nextIp, relayIp, clientMac, false);
    }

    public String toString() {
        String s = super.toString();
        return s + " DECLINE";
    }

    /**
     * Fills in a packet with the requested DECLINE attributes.
     */
    public ByteBuffer buildPacket(int encap, short destUdp, short srcUdp) {
        ByteBuffer result = ByteBuffer.allocate(MAX_LENGTH);

        fillInPacket(encap, mClientIp, mYourIp, destUdp, srcUdp, result,
            DHCP_BOOTREQUEST, false);
        result.flip();
        return result;
    }

    /**
     * Adds optional parameters to the DECLINE packet.
     */
    void finishPacket(ByteBuffer buffer) {
        addTlv(buffer, DHCP_MESSAGE_TYPE, DHCP_MESSAGE_TYPE_DECLINE);
        addTlv(buffer, DHCP_CLIENT_IDENTIFIER, getClientId());
        // RFC 2131 says we MUST NOT include our common client TLVs or the parameter request list.
        addTlvEnd(buffer);
    }
}
