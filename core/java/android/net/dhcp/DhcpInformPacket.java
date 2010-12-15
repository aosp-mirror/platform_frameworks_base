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
import java.nio.ByteBuffer;

/**
 * This class implements the (unused) DHCP-INFORM packet.
 */
class DhcpInformPacket extends DhcpPacket {
    /**
     * Generates an INFORM packet with the specified parameters.
     */
    DhcpInformPacket(int transId, InetAddress clientIp, InetAddress yourIp,
                     InetAddress nextIp, InetAddress relayIp,
                     byte[] clientMac) {
        super(transId, clientIp, yourIp, nextIp, relayIp, clientMac, false);
    }

    public String toString() {
        String s = super.toString();
        return s + " INFORM";
    }

    /**
     * Builds an INFORM packet.
     */
    public ByteBuffer buildPacket(int encap, short destUdp, short srcUdp) {
        ByteBuffer result = ByteBuffer.allocate(MAX_LENGTH);

        fillInPacket(encap, mClientIp, mYourIp, destUdp, srcUdp, result,
            DHCP_BOOTREQUEST, false);
        result.flip();
        return result;
    }

    /**
     * Adds additional parameters to the INFORM packet.
     */
    void finishPacket(ByteBuffer buffer) {
        byte[] clientId = new byte[7];

        clientId[0] = CLIENT_ID_ETHER;
        System.arraycopy(mClientMac, 0, clientId, 1, 6);

        addTlv(buffer, DHCP_MESSAGE_TYPE, DHCP_MESSAGE_TYPE_REQUEST);
        addTlv(buffer, DHCP_PARAMETER_LIST, mRequestedParams);
        addTlvEnd(buffer);
    }

    /**
     * Informs the state machine of the arrival of an INFORM packet.  Not
     * used currently.
     */
    public void doNextOp(DhcpStateMachine machine) {
        InetAddress clientRequest =
            mRequestedIp == null ? mClientIp : mRequestedIp;
        machine.onInformReceived(mTransId, mClientMac, clientRequest,
            mRequestedParams);
    }
}
