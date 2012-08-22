/*
 * Copyright (C) 2012 The Android Open Source Project
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

package android.net.arp;

import android.os.SystemClock;
import android.util.Log;
import java.io.IOException;
import java.net.InetAddress;
import java.net.Inet6Address;
import java.net.SocketException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;

import libcore.net.RawSocket;

/**
 * This class allows simple ARP exchanges over an uninitialized network
 * interface.
 *
 * @hide
 */
public class ArpPeer {
    private String mInterfaceName;
    private final InetAddress mMyAddr;
    private final byte[] mMyMac = new byte[6];
    private final InetAddress mPeer;
    private final RawSocket mSocket;
    private final byte[] L2_BROADCAST;  // TODO: refactor from DhcpClient.java
    private static final int MAX_LENGTH = 1500; // refactor from DhcpPacket.java
    private static final int ETHERNET_TYPE = 1;
    private static final int ARP_LENGTH = 28;
    private static final int MAC_ADDR_LENGTH = 6;
    private static final int IPV4_LENGTH = 4;
    private static final String TAG = "ArpPeer";

    public ArpPeer(String interfaceName, InetAddress myAddr, String mac,
                   InetAddress peer) throws SocketException {
        mInterfaceName = interfaceName;
        mMyAddr = myAddr;

        if (mac != null) {
            for (int i = 0; i < MAC_ADDR_LENGTH; i++) {
                mMyMac[i] = (byte) Integer.parseInt(mac.substring(
                            i*3, (i*3) + 2), 16);
            }
        }

        if (myAddr instanceof Inet6Address || peer instanceof Inet6Address) {
            throw new IllegalArgumentException("IPv6 unsupported");
        }

        mPeer = peer;
        L2_BROADCAST = new byte[MAC_ADDR_LENGTH];
        Arrays.fill(L2_BROADCAST, (byte) 0xFF);

        mSocket = new RawSocket(mInterfaceName, RawSocket.ETH_P_ARP);
    }

    /**
     * Returns the MAC address (or null if timeout) for the requested
     * peer.
     */
    public byte[] doArp(int timeoutMillis) {
        ByteBuffer buf = ByteBuffer.allocate(MAX_LENGTH);
        byte[] desiredIp = mPeer.getAddress();
        long timeout = SystemClock.elapsedRealtime() + timeoutMillis;

        // construct ARP request packet, using a ByteBuffer as a
        // convenient container
        buf.clear();
        buf.order(ByteOrder.BIG_ENDIAN);

        buf.putShort((short) ETHERNET_TYPE); // Ethernet type, 16 bits
        buf.putShort(RawSocket.ETH_P_IP); // Protocol type IP, 16 bits
        buf.put((byte)MAC_ADDR_LENGTH);  // MAC address length, 6 bytes
        buf.put((byte)IPV4_LENGTH);  // IPv4 protocol size
        buf.putShort((short) 1); // ARP opcode 1: 'request'
        buf.put(mMyMac);        // six bytes: sender MAC
        buf.put(mMyAddr.getAddress());  // four bytes: sender IP address
        buf.put(new byte[MAC_ADDR_LENGTH]); // target MAC address: unknown
        buf.put(desiredIp); // target IP address, 4 bytes
        buf.flip();
        mSocket.write(L2_BROADCAST, buf.array(), 0, buf.limit());

        byte[] recvBuf = new byte[MAX_LENGTH];

        while (SystemClock.elapsedRealtime() < timeout) {
            long duration = (long) timeout - SystemClock.elapsedRealtime();
            int readLen = mSocket.read(recvBuf, 0, recvBuf.length, -1,
                (int) duration);

            // Verify packet details. see RFC 826
            if ((readLen >= ARP_LENGTH) // trailing bytes at times
                && (recvBuf[0] == 0) && (recvBuf[1] == ETHERNET_TYPE) // type Ethernet
                && (recvBuf[2] == 8) && (recvBuf[3] == 0) // protocol IP
                && (recvBuf[4] == MAC_ADDR_LENGTH) // mac length
                && (recvBuf[5] == IPV4_LENGTH) // IPv4 protocol size
                && (recvBuf[6] == 0) && (recvBuf[7] == 2) // ARP reply
                // verify desired IP address
                && (recvBuf[14] == desiredIp[0]) && (recvBuf[15] == desiredIp[1])
                && (recvBuf[16] == desiredIp[2]) && (recvBuf[17] == desiredIp[3]))
            {
                // looks good.  copy out the MAC
                byte[] result = new byte[MAC_ADDR_LENGTH];
                System.arraycopy(recvBuf, 8, result, 0, MAC_ADDR_LENGTH);
                return result;
            }
        }

        return null;
    }

    public void close() {
        try {
            mSocket.close();
        } catch (IOException ex) {
        }
    }
}
