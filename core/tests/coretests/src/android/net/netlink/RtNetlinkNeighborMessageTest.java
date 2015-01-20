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

package android.net.netlink;

import android.net.netlink.NetlinkConstants;
import android.net.netlink.NetlinkMessage;
import android.net.netlink.RtNetlinkNeighborMessage;
import android.net.netlink.StructNdMsg;
import android.net.netlink.StructNlMsgHdr;
import android.system.OsConstants;
import android.util.Log;
import libcore.util.HexEncoding;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import junit.framework.TestCase;


public class RtNetlinkNeighborMessageTest extends TestCase {
    private final String TAG = "RtNetlinkNeighborMessageTest";

    // Hexadecimal representation of packet capture.
    public static final String RTM_DELNEIGH_HEX =
            // struct nlmsghdr
            "4c000000" +     // length = 76
            "1d00" +         // type = 29 (RTM_DELNEIGH)
            "0000" +         // flags
            "00000000" +     // seqno
            "00000000" +     // pid (0 == kernel)
            // struct ndmsg
            "02" +           // family
            "00" +           // pad1
            "0000" +         // pad2
            "15000000" +     // interface index (21  == wlan0, on test device)
            "0400" +         // NUD state (0x04 == NUD_STALE)
            "00" +           // flags
            "01" +           // type
            // struct nlattr: NDA_DST
            "0800" +         // length = 8
            "0100" +         // type (1 == NDA_DST, for neighbor messages)
            "c0a89ffe" +     // IPv4 address (== 192.168.159.254)
            // struct nlattr: NDA_LLADDR
            "0a00" +         // length = 10
            "0200" +         // type (2 == NDA_LLADDR, for neighbor messages)
            "00005e000164" + // MAC Address (== 00:00:5e:00:01:64)
            "0000" +         // padding, for 4 byte alignment
            // struct nlattr: NDA_PROBES
            "0800" +         // length = 8
            "0400" +         // type (4 == NDA_PROBES, for neighbor messages)
            "01000000" +     // number of probes
            // struct nlattr: NDA_CACHEINFO
            "1400" +         // length = 20
            "0300" +         // type (3 == NDA_CACHEINFO, for neighbor messages)
            "05190000" +     // ndm_used, as "clock ticks ago"
            "05190000" +     // ndm_confirmed, as "clock ticks ago"
            "190d0000" +     // ndm_updated, as "clock ticks ago"
            "00000000";      // ndm_refcnt
    public static final byte[] RTM_DELNEIGH =
            HexEncoding.decode(RTM_DELNEIGH_HEX.toCharArray(), false);

    // Hexadecimal representation of packet capture.
    public static final String RTM_NEWNEIGH_HEX =
            // struct nlmsghdr
            "58000000" +     // length = 88
            "1c00" +         // type = 28 (RTM_NEWNEIGH)
            "0000" +         // flags
            "00000000" +     // seqno
            "00000000" +     // pid (0 == kernel)
            // struct ndmsg
            "0a" +           // family
            "00" +           // pad1
            "0000" +         // pad2
            "15000000" +     // interface index (21  == wlan0, on test device)
            "0400" +         // NUD state (0x04 == NUD_STALE)
            "80" +           // flags
            "01" +           // type
            // struct nlattr: NDA_DST
            "1400" +         // length = 20
            "0100" +         // type (1 == NDA_DST, for neighbor messages)
            "fe8000000000000086c9b2fffe6aed4b" + // IPv6 address (== fe80::86c9:b2ff:fe6a:ed4b)
            // struct nlattr: NDA_LLADDR
            "0a00" +         // length = 10
            "0200" +         // type (2 == NDA_LLADDR, for neighbor messages)
            "84c9b26aed4b" + // MAC Address (== 84:c9:b2:6a:ed:4b)
            "0000" +         // padding, for 4 byte alignment
            // struct nlattr: NDA_PROBES
            "0800" +         // length = 8
            "0400" +         // type (4 == NDA_PROBES, for neighbor messages)
            "01000000" +     // number of probes
            // struct nlattr: NDA_CACHEINFO
            "1400" +         // length = 20
            "0300" +         // type (3 == NDA_CACHEINFO, for neighbor messages)
            "eb0e0000" +     // ndm_used, as "clock ticks ago"
            "861f0000" +     // ndm_confirmed, as "clock ticks ago"
            "00000000" +     // ndm_updated, as "clock ticks ago"
            "05000000";      // ndm_refcnt
    public static final byte[] RTM_NEWNEIGH =
            HexEncoding.decode(RTM_NEWNEIGH_HEX.toCharArray(), false);

    // An example of the full response from an RTM_GETNEIGH query.
    private static final String RTM_GETNEIGH_RESPONSE_HEX =
            // <-- struct nlmsghr             -->|<-- struct ndmsg           -->|<-- struct nlattr: NDA_DST             -->|<-- NDA_LLADDR          -->|<-- NDA_PROBES -->|<-- NDA_CACHEINFO                         -->|
            "58000000 1c00 0200 00000000 3e2b0000 0a 00 0000 15000000 4000 00 05 1400 0100 ff020000000000000000000000000001 0a00 0200 333300000001 0000 0800 0400 00000000 1400 0300 a2280000 32110000 32110000 01000000" +
            "58000000 1c00 0200 00000000 3e2b0000 0a 00 0000 15000000 4000 00 05 1400 0100 ff0200000000000000000001ff000001 0a00 0200 3333ff000001 0000 0800 0400 00000000 1400 0300 0d280000 9d100000 9d100000 00000000" +
            "58000000 1c00 0200 00000000 3e2b0000 0a 00 0000 15000000 0400 80 01 1400 0100 20010db800040ca00000000000000001 0a00 0200 84c9b26aed4b 0000 0800 0400 04000000 1400 0300 90100000 90100000 90080000 01000000" +
            "58000000 1c00 0200 00000000 3e2b0000 0a 00 0000 15000000 4000 00 05 1400 0100 ff0200000000000000000001ff47da19 0a00 0200 3333ff47da19 0000 0800 0400 00000000 1400 0300 a1280000 31110000 31110000 01000000" +
            "58000000 1c00 0200 00000000 3e2b0000 0a 00 0000 14000000 4000 00 05 1400 0100 ff020000000000000000000000000016 0a00 0200 333300000016 0000 0800 0400 00000000 1400 0300 912a0000 21130000 21130000 00000000" +
            "58000000 1c00 0200 00000000 3e2b0000 0a 00 0000 14000000 4000 00 05 1400 0100 ff0200000000000000000001ffeace3b 0a00 0200 3333ffeace3b 0000 0800 0400 00000000 1400 0300 922a0000 22130000 22130000 00000000" +
            "58000000 1c00 0200 00000000 3e2b0000 0a 00 0000 15000000 4000 00 05 1400 0100 ff0200000000000000000001ff5c2a83 0a00 0200 3333ff5c2a83 0000 0800 0400 00000000 1400 0300 391c0000 c9040000 c9040000 01000000" +
            "58000000 1c00 0200 00000000 3e2b0000 0a 00 0000 01000000 4000 00 02 1400 0100 00000000000000000000000000000000 0a00 0200 000000000000 0000 0800 0400 00000000 1400 0300 cd180200 5d010200 5d010200 08000000" +
            "58000000 1c00 0200 00000000 3e2b0000 0a 00 0000 15000000 4000 00 05 1400 0100 ff020000000000000000000000000002 0a00 0200 333300000002 0000 0800 0400 00000000 1400 0300 352a0000 c5120000 c5120000 00000000" +
            "58000000 1c00 0200 00000000 3e2b0000 0a 00 0000 15000000 4000 00 05 1400 0100 ff020000000000000000000000000016 0a00 0200 333300000016 0000 0800 0400 00000000 1400 0300 982a0000 28130000 28130000 00000000" +
            "58000000 1c00 0200 00000000 3e2b0000 0a 00 0000 15000000 0800 80 01 1400 0100 fe8000000000000086c9b2fffe6aed4b 0a00 0200 84c9b26aed4b 0000 0800 0400 00000000 1400 0300 23000000 24000000 57000000 13000000" +
            "58000000 1c00 0200 00000000 3e2b0000 0a 00 0000 15000000 4000 00 05 1400 0100 ff0200000000000000000001ffeace3b 0a00 0200 3333ffeace3b 0000 0800 0400 00000000 1400 0300 992a0000 29130000 29130000 01000000" +
            "58000000 1c00 0200 00000000 3e2b0000 0a 00 0000 14000000 4000 00 05 1400 0100 ff020000000000000000000000000002 0a00 0200 333300000002 0000 0800 0400 00000000 1400 0300 2e2a0000 be120000 be120000 00000000" +
            "44000000 1c00 0200 00000000 3e2b0000 02 00 0000 18000000 4000 00 03 0800 0100 00000000                         0400 0200                   0800 0400 00000000 1400 0300 75280000 05110000 05110000 22000000";
    public static final byte[] RTM_GETNEIGH_RESPONSE =
            HexEncoding.decode(RTM_GETNEIGH_RESPONSE_HEX.replaceAll(" ", "").toCharArray(), false);

    public void testParseRtNetlinkNeighborRtmDelNeigh() {
        final ByteBuffer byteBuffer = ByteBuffer.wrap(RTM_DELNEIGH);
        byteBuffer.order(ByteOrder.LITTLE_ENDIAN);  // For testing.
        final NetlinkMessage msg = NetlinkMessage.parse(byteBuffer);
        assertNotNull(msg);
        assertTrue(msg instanceof RtNetlinkNeighborMessage);
        final RtNetlinkNeighborMessage neighMsg = (RtNetlinkNeighborMessage) msg;

        final StructNlMsgHdr hdr = neighMsg.getHeader();
        assertNotNull(hdr);
        assertEquals(76, hdr.nlmsg_len);
        assertEquals(NetlinkConstants.RTM_DELNEIGH, hdr.nlmsg_type);
        assertEquals(0, hdr.nlmsg_flags);
        assertEquals(0, hdr.nlmsg_seq);
        assertEquals(0, hdr.nlmsg_pid);

        final StructNdMsg ndmsgHdr = neighMsg.getNdHeader();
        assertNotNull(ndmsgHdr);
        assertEquals((byte) OsConstants.AF_INET, ndmsgHdr.ndm_family);
        assertEquals(21, ndmsgHdr.ndm_ifindex);
        assertEquals(StructNdMsg.NUD_STALE, ndmsgHdr.ndm_state);
        final InetAddress destination = neighMsg.getDestination();
        assertNotNull(destination);
        assertEquals(InetAddress.parseNumericAddress("192.168.159.254"), destination);
    }

    public void testParseRtNetlinkNeighborRtmNewNeigh() {
        final ByteBuffer byteBuffer = ByteBuffer.wrap(RTM_NEWNEIGH);
        byteBuffer.order(ByteOrder.LITTLE_ENDIAN);  // For testing.
        final NetlinkMessage msg = NetlinkMessage.parse(byteBuffer);
        assertNotNull(msg);
        assertTrue(msg instanceof RtNetlinkNeighborMessage);
        final RtNetlinkNeighborMessage neighMsg = (RtNetlinkNeighborMessage) msg;

        final StructNlMsgHdr hdr = neighMsg.getHeader();
        assertNotNull(hdr);
        assertEquals(88, hdr.nlmsg_len);
        assertEquals(NetlinkConstants.RTM_NEWNEIGH, hdr.nlmsg_type);
        assertEquals(0, hdr.nlmsg_flags);
        assertEquals(0, hdr.nlmsg_seq);
        assertEquals(0, hdr.nlmsg_pid);

        final StructNdMsg ndmsgHdr = neighMsg.getNdHeader();
        assertNotNull(ndmsgHdr);
        assertEquals((byte) OsConstants.AF_INET6, ndmsgHdr.ndm_family);
        assertEquals(21, ndmsgHdr.ndm_ifindex);
        assertEquals(StructNdMsg.NUD_STALE, ndmsgHdr.ndm_state);
        final InetAddress destination = neighMsg.getDestination();
        assertNotNull(destination);
        assertEquals(InetAddress.parseNumericAddress("fe80::86c9:b2ff:fe6a:ed4b"), destination);
    }

    public void testParseRtNetlinkNeighborRtmGetNeighResponse() {
        final ByteBuffer byteBuffer = ByteBuffer.wrap(RTM_GETNEIGH_RESPONSE);
        byteBuffer.order(ByteOrder.LITTLE_ENDIAN);  // For testing.

        int messageCount = 0;
        while (byteBuffer.remaining() > 0) {
            final NetlinkMessage msg = NetlinkMessage.parse(byteBuffer);
            assertNotNull(msg);
            assertTrue(msg instanceof RtNetlinkNeighborMessage);
            final RtNetlinkNeighborMessage neighMsg = (RtNetlinkNeighborMessage) msg;

            final StructNlMsgHdr hdr = neighMsg.getHeader();
            assertNotNull(hdr);
            assertEquals(NetlinkConstants.RTM_NEWNEIGH, hdr.nlmsg_type);
            assertEquals(StructNlMsgHdr.NLM_F_MULTI, hdr.nlmsg_flags);
            assertEquals(0, hdr.nlmsg_seq);
            assertEquals(11070, hdr.nlmsg_pid);

            messageCount++;
        }
        // TODO: add more detailed spot checks.
        assertEquals(14, messageCount);
    }
}
