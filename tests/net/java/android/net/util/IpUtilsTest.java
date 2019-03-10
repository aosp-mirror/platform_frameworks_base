/*
 * Copyright (C) 2015 The Android Open Source Project
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

package android.net.util;

import static org.junit.Assert.assertEquals;

import androidx.test.filters.SmallTest;
import androidx.test.runner.AndroidJUnit4;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.nio.ByteBuffer;

@RunWith(AndroidJUnit4.class)
@SmallTest
public class IpUtilsTest {

    private static final int IPV4_HEADER_LENGTH = 20;
    private static final int IPV6_HEADER_LENGTH = 40;
    private static final int TCP_HEADER_LENGTH = 20;
    private static final int UDP_HEADER_LENGTH = 8;
    private static final int IP_CHECKSUM_OFFSET = 10;
    private static final int TCP_CHECKSUM_OFFSET = 16;
    private static final int UDP_CHECKSUM_OFFSET = 6;

    private int getUnsignedByte(ByteBuffer buf, int offset) {
        return buf.get(offset) & 0xff;
    }

    private int getChecksum(ByteBuffer buf, int offset) {
        return getUnsignedByte(buf, offset) * 256 + getUnsignedByte(buf, offset + 1);
    }

    private void assertChecksumEquals(int expected, short actual) {
        assertEquals(Integer.toHexString(expected), Integer.toHexString(actual & 0xffff));
    }

    // Generate test packets using Python code like this::
    //
    // from scapy import all as scapy
    //
    // def JavaPacketDefinition(bytes):
    //   out = "        ByteBuffer packet = ByteBuffer.wrap(new byte[] {\n            "
    //   for i in xrange(len(bytes)):
    //     out += "(byte) 0x%02x" % ord(bytes[i])
    //     if i < len(bytes) - 1:
    //       if i % 4 == 3:
    //         out += ",\n            "
    //       else:
    //         out += ", "
    //   out += "\n        });"
    //   return out
    //
    // packet = (scapy.IPv6(src="2001:db8::1", dst="2001:db8::2") /
    //           scapy.UDP(sport=12345, dport=7) /
    //           "hello")
    // print JavaPacketDefinition(str(packet))

    @Test
    public void testIpv6TcpChecksum() throws Exception {
        // packet = (scapy.IPv6(src="2001:db8::1", dst="2001:db8::2", tc=0x80) /
        //           scapy.TCP(sport=12345, dport=7,
        //                     seq=1692871236, ack=128376451, flags=16,
        //                     window=32768) /
        //           "hello, world")
        ByteBuffer packet = ByteBuffer.wrap(new byte[] {
            (byte) 0x68, (byte) 0x00, (byte) 0x00, (byte) 0x00,
            (byte) 0x00, (byte) 0x20, (byte) 0x06, (byte) 0x40,
            (byte) 0x20, (byte) 0x01, (byte) 0x0d, (byte) 0xb8,
            (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x00,
            (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x00,
            (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x01,
            (byte) 0x20, (byte) 0x01, (byte) 0x0d, (byte) 0xb8,
            (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x00,
            (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x00,
            (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x02,
            (byte) 0x30, (byte) 0x39, (byte) 0x00, (byte) 0x07,
            (byte) 0x64, (byte) 0xe7, (byte) 0x2a, (byte) 0x44,
            (byte) 0x07, (byte) 0xa6, (byte) 0xde, (byte) 0x83,
            (byte) 0x50, (byte) 0x10, (byte) 0x80, (byte) 0x00,
            (byte) 0xee, (byte) 0x71, (byte) 0x00, (byte) 0x00,
            (byte) 0x68, (byte) 0x65, (byte) 0x6c, (byte) 0x6c,
            (byte) 0x6f, (byte) 0x2c, (byte) 0x20, (byte) 0x77,
            (byte) 0x6f, (byte) 0x72, (byte) 0x6c, (byte) 0x64
        });

        // Check that a valid packet has checksum 0.
        int transportLen = packet.limit() - IPV6_HEADER_LENGTH;
        assertEquals(0, IpUtils.tcpChecksum(packet, 0, IPV6_HEADER_LENGTH, transportLen));

        // Check that we can calculate the checksum from scratch.
        int sumOffset = IPV6_HEADER_LENGTH + TCP_CHECKSUM_OFFSET;
        int sum = getUnsignedByte(packet, sumOffset) * 256 + getUnsignedByte(packet, sumOffset + 1);
        assertEquals(0xee71, sum);

        packet.put(sumOffset, (byte) 0);
        packet.put(sumOffset + 1, (byte) 0);
        assertChecksumEquals(sum, IpUtils.tcpChecksum(packet, 0, IPV6_HEADER_LENGTH, transportLen));

        // Check that writing the checksum back into the packet results in a valid packet.
        packet.putShort(
            sumOffset,
            IpUtils.tcpChecksum(packet, 0, IPV6_HEADER_LENGTH, transportLen));
        assertEquals(0, IpUtils.tcpChecksum(packet, 0, IPV6_HEADER_LENGTH, transportLen));
    }

    @Test
    public void testIpv4UdpChecksum() {
        // packet = (scapy.IP(src="192.0.2.1", dst="192.0.2.2", tos=0x40) /
        //           scapy.UDP(sport=32012, dport=4500) /
        //           "\xff")
        ByteBuffer packet = ByteBuffer.wrap(new byte[] {
            (byte) 0x45, (byte) 0x40, (byte) 0x00, (byte) 0x1d,
            (byte) 0x00, (byte) 0x01, (byte) 0x00, (byte) 0x00,
            (byte) 0x40, (byte) 0x11, (byte) 0xf6, (byte) 0x8b,
            (byte) 0xc0, (byte) 0x00, (byte) 0x02, (byte) 0x01,
            (byte) 0xc0, (byte) 0x00, (byte) 0x02, (byte) 0x02,
            (byte) 0x7d, (byte) 0x0c, (byte) 0x11, (byte) 0x94,
            (byte) 0x00, (byte) 0x09, (byte) 0xee, (byte) 0x36,
            (byte) 0xff
        });

        // Check that a valid packet has IP checksum 0 and UDP checksum 0xffff (0 is not a valid
        // UDP checksum, so the udpChecksum rewrites 0 to 0xffff).
        assertEquals(0, IpUtils.ipChecksum(packet, 0));
        assertEquals((short) 0xffff, IpUtils.udpChecksum(packet, 0, IPV4_HEADER_LENGTH));

        // Check that we can calculate the checksums from scratch.
        final int ipSumOffset = IP_CHECKSUM_OFFSET;
        final int ipSum = getChecksum(packet, ipSumOffset);
        assertEquals(0xf68b, ipSum);

        packet.put(ipSumOffset, (byte) 0);
        packet.put(ipSumOffset + 1, (byte) 0);
        assertChecksumEquals(ipSum, IpUtils.ipChecksum(packet, 0));

        final int udpSumOffset = IPV4_HEADER_LENGTH + UDP_CHECKSUM_OFFSET;
        final int udpSum = getChecksum(packet, udpSumOffset);
        assertEquals(0xee36, udpSum);

        packet.put(udpSumOffset, (byte) 0);
        packet.put(udpSumOffset + 1, (byte) 0);
        assertChecksumEquals(udpSum, IpUtils.udpChecksum(packet, 0, IPV4_HEADER_LENGTH));

        // Check that writing the checksums back into the packet results in a valid packet.
        packet.putShort(ipSumOffset, IpUtils.ipChecksum(packet, 0));
        packet.putShort(udpSumOffset, IpUtils.udpChecksum(packet, 0, IPV4_HEADER_LENGTH));
        assertEquals(0, IpUtils.ipChecksum(packet, 0));
        assertEquals((short) 0xffff, IpUtils.udpChecksum(packet, 0, IPV4_HEADER_LENGTH));
    }
}
