/*
 * Copyright (C) 2018 The Android Open Source Project
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
 * Implements DHCP-RELEASE
 */
class DhcpReleasePacket extends DhcpPacket {

    final Inet4Address mClientAddr;

    /**
     * Generates a RELEASE packet with the specified parameters.
     */
    public DhcpReleasePacket(int transId, Inet4Address serverId, Inet4Address clientAddr,
            Inet4Address relayIp, byte[] clientMac) {
        super(transId, (short)0, clientAddr, INADDR_ANY /* yourIp */, INADDR_ANY /* nextIp */,
                relayIp, clientMac, false /* broadcast */);
        mServerIdentifier = serverId;
        mClientAddr = clientAddr;
    }


    @Override
    public ByteBuffer buildPacket(int encap, short destUdp, short srcUdp) {
        ByteBuffer result = ByteBuffer.allocate(MAX_LENGTH);
        fillInPacket(encap, mServerIdentifier /* destIp */, mClientIp /* srcIp */, destUdp, srcUdp,
                result, DHCP_BOOTREPLY, mBroadcast);
        result.flip();
        return result;
    }

    @Override
    void finishPacket(ByteBuffer buffer) {
        addTlv(buffer, DHCP_MESSAGE_TYPE, DHCP_MESSAGE_TYPE_RELEASE);
        addTlv(buffer, DHCP_CLIENT_IDENTIFIER, getClientId());
        addTlv(buffer, DHCP_SERVER_IDENTIFIER, mServerIdentifier);
        addCommonClientTlvs(buffer);
        addTlvEnd(buffer);
    }
}
