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

package android.net;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;

import android.net.SocketKeepalive.InvalidPacketException;
import android.net.TcpKeepalivePacketData.TcpSocketInfo;

import com.android.internal.util.TestUtils;

import libcore.net.InetAddressUtils;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

import java.net.InetAddress;
import java.nio.ByteBuffer;

@RunWith(JUnit4.class)
public final class TcpKeepalivePacketDataTest {

    @Before
    public void setUp() {}

    @Test
    public void testV4TcpKeepalivePacket() {
        final InetAddress srcAddr = InetAddressUtils.parseNumericAddress("192.168.0.1");
        final InetAddress dstAddr = InetAddressUtils.parseNumericAddress("192.168.0.10");
        final int srcPort = 1234;
        final int dstPort = 4321;
        final int seq = 0x11111111;
        final int ack = 0x22222222;
        final int wnd = 8000;
        final int wndScale = 2;
        TcpKeepalivePacketData resultData = null;
        TcpSocketInfo testInfo = new TcpSocketInfo(
                srcAddr, srcPort, dstAddr, dstPort, seq, ack, wnd, wndScale);
        try {
            resultData = TcpKeepalivePacketData.tcpKeepalivePacket(testInfo);
        } catch (InvalidPacketException e) {
            fail("InvalidPacketException: " + e);
        }

        assertEquals(testInfo.srcAddress, resultData.srcAddress);
        assertEquals(testInfo.dstAddress, resultData.dstAddress);
        assertEquals(testInfo.srcPort, resultData.srcPort);
        assertEquals(testInfo.dstPort, resultData.dstPort);
        assertEquals(testInfo.seq, resultData.tcpSeq);
        assertEquals(testInfo.ack, resultData.tcpAck);
        assertEquals(testInfo.rcvWndScale, resultData.tcpWndScale);

        TestUtils.assertParcelingIsLossless(resultData, TcpKeepalivePacketData.CREATOR);

        final byte[] packet = resultData.getPacket();
        // IP version and TOS.
        ByteBuffer buf = ByteBuffer.wrap(packet);
        assertEquals(buf.getShort(), 0x4500);
        // Source IP address.
        byte[] ip = new byte[4];
        buf = ByteBuffer.wrap(packet, 12, 4);
        buf.get(ip);
        assertArrayEquals(ip, srcAddr.getAddress());
        // Destination IP address.
        buf = ByteBuffer.wrap(packet, 16, 4);
        buf.get(ip);
        assertArrayEquals(ip, dstAddr.getAddress());

        buf = ByteBuffer.wrap(packet, 20, 12);
        // Source port.
        assertEquals(buf.getShort(), srcPort);
        // Destination port.
        assertEquals(buf.getShort(), dstPort);
        // Sequence number.
        assertEquals(buf.getInt(), seq);
        // Ack.
        assertEquals(buf.getInt(), ack);
        // Window size.
        buf = ByteBuffer.wrap(packet, 34, 2);
        assertEquals(buf.getShort(), wnd >> wndScale);
    }

    //TODO: add ipv6 test when ipv6 supported

    @Test
    public void testParcel() throws Exception {
        final InetAddress srcAddr = InetAddresses.parseNumericAddress("192.168.0.1");
        final InetAddress dstAddr = InetAddresses.parseNumericAddress("192.168.0.10");
        final int srcPort = 1234;
        final int dstPort = 4321;
        final int sequence = 0x11111111;
        final int ack = 0x22222222;
        final int wnd = 48_000;
        final int wndScale = 2;
        TcpKeepalivePacketData testData = null;
        TcpKeepalivePacketDataParcelable resultData = null;
        TcpSocketInfo testInfo = new TcpSocketInfo(
                srcAddr, srcPort, dstAddr, dstPort, sequence, ack, wnd, wndScale);
        testData = TcpKeepalivePacketData.tcpKeepalivePacket(testInfo);
        resultData = testData.toStableParcelable();
        assertArrayEquals(resultData.srcAddress, srcAddr.getAddress());
        assertArrayEquals(resultData.dstAddress, dstAddr.getAddress());
        assertEquals(resultData.srcPort, srcPort);
        assertEquals(resultData.dstPort, dstPort);
        assertEquals(resultData.seq, sequence);
        assertEquals(resultData.ack, ack);
    }
}
