/*
 * Copyright (C) 2016 The Android Open Source Project
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

import static android.net.util.NetworkConstants.*;

import libcore.util.HexEncoding;

import junit.framework.TestCase;


/**
 * Tests for ConnectivityPacketSummary.
 *
 * @hide
 */
public class ConnectivityPacketSummaryTest extends TestCase {
    private static final byte[] MYHWADDR = {
        asByte(0x80), asByte(0x7a), asByte(0xbf), asByte(0x6f), asByte(0x48), asByte(0xf3)
    };

    private String getSummary(String hexBytes) {
        hexBytes = hexBytes.replaceAll("\\s+", "");
        final byte[] bytes = HexEncoding.decode(hexBytes.toCharArray(), false);
        return ConnectivityPacketSummary.summarize(MYHWADDR, bytes);
    }

    public void testParseICMPv6DADProbe() {
        final String packet =
                // Ethernet
                "3333FF6F48F3 807ABF6F48F3 86DD" +
                // IPv6
                "600000000018 3A FF" +
                "00000000000000000000000000000000" +
                "FF0200000000000000000001FF6F48F3" +
                // ICMPv6
                "87 00 A8E7" +
                "00000000" +
                "FE80000000000000827ABFFFFE6F48F3";

        final String expected =
                "TX 80:7a:bf:6f:48:f3 > 33:33:ff:6f:48:f3 ipv6" +
                " :: > ff02::1:ff6f:48f3 icmp6" +
                " ns fe80::827a:bfff:fe6f:48f3";

        assertEquals(expected, getSummary(packet));
    }

    public void testParseICMPv6RS() {
        final String packet =
                // Ethernet
                "333300000002 807ABF6F48F3 86DD" +
                // IPv6
                "600000000010 3A FF" +
                "FE80000000000000827ABFFFFE6F48F3" +
                "FF020000000000000000000000000002" +
                // ICMPv6 RS
                "85 00 6973" +
                "00000000" +
                "01 01 807ABF6F48F3";

        final String expected =
                "TX 80:7a:bf:6f:48:f3 > 33:33:00:00:00:02 ipv6" +
                " fe80::827a:bfff:fe6f:48f3 > ff02::2 icmp6" +
                " rs slla 80:7a:bf:6f:48:f3";

        assertEquals(expected, getSummary(packet));
    }

    public void testParseICMPv6RA() {
        final String packet =
                // Ethernet
                "807ABF6F48F3 100E7E263FC1 86DD" +
                // IPv6
                "600000000068 3A FF" +
                "FE80000000000000FA000004FD000001" +
                "FE80000000000000827ABFFFFE6F48F3" +
                // ICMPv6 RA
                "86 00 8141" +
                "40 00 0E10" +
                "00000000" +
                "00000000" +
                "01 01 00005E000265" +
                "05 01 0000000005DC" +
                "19 05 000000000E10" +
                "      20014860486000000000000000008844" +
                "      20014860486000000000000000008888" +
                "03 04 40 C0" +
                "      00278D00" +
                "      00093A80" +
                "      00000000" +
                "      2401FA000004FD000000000000000000";

        final String expected =
                "RX 10:0e:7e:26:3f:c1 > 80:7a:bf:6f:48:f3 ipv6" +
                " fe80::fa00:4:fd00:1 > fe80::827a:bfff:fe6f:48f3 icmp6" +
                " ra slla 00:00:5e:00:02:65 mtu 1500";

        assertEquals(expected, getSummary(packet));
    }

    public void testParseICMPv6NS() {
        final String packet =
                // Ethernet
                  "807ABF6F48F3 100E7E263FC1 86DD" +
                  // IPv6
                  "6C0000000020 3A FF" +
                  "FE80000000000000FA000004FD000001" +
                  "FF0200000000000000000001FF01C146" +
                  // ICMPv6 NS
                  "87 00 8AD4" +
                  "00000000" +
                  "2401FA000004FD0015EA6A5C7B01C146" +
                  "01 01 00005E000265";

        final String expected =
                "RX 10:0e:7e:26:3f:c1 > 80:7a:bf:6f:48:f3 ipv6" +
                " fe80::fa00:4:fd00:1 > ff02::1:ff01:c146 icmp6" +
                " ns 2401:fa00:4:fd00:15ea:6a5c:7b01:c146 slla 00:00:5e:00:02:65";

        assertEquals(expected, getSummary(packet));
    }

    public void testInvalidICMPv6NDLength() {
        final String packet =
                // Ethernet
                "807ABF6F48F3 100E7E263FC1 86DD" +
                // IPv6
                "600000000068 3A FF" +
                "FE80000000000000FA000004FD000001" +
                "FE80000000000000827ABFFFFE6F48F3" +
                // ICMPv6 RA
                "86 00 8141" +
                "40 00 0E10" +
                "00000000" +
                "00000000" +
                "01 01 00005E000265" +
                "00 00 0102030405D6";

        final String expected =
                "RX 10:0e:7e:26:3f:c1 > 80:7a:bf:6f:48:f3 ipv6" +
                " fe80::fa00:4:fd00:1 > fe80::827a:bfff:fe6f:48f3 icmp6" +
                " ra slla 00:00:5e:00:02:65 <malformed>";

        assertEquals(expected, getSummary(packet));
    }

    public void testParseICMPv6NA() {
        final String packet =
                // Ethernet
                "00005E000265 807ABF6F48F3 86DD" +
                "600000000020 3A FF" +
                "2401FA000004FD0015EA6A5C7B01C146" +
                "FE80000000000000FA000004FD000001" +
                "88 00 E8126" +
                "0000000" +
                "2401FA000004FD0015EA6A5C7B01C146" +
                "02 01 807ABF6F48F3";

        final String expected =
                "TX 80:7a:bf:6f:48:f3 > 00:00:5e:00:02:65 ipv6" +
                " 2401:fa00:4:fd00:15ea:6a5c:7b01:c146 > fe80::fa00:4:fd00:1 icmp6" +
                " na 2401:fa00:4:fd00:15ea:6a5c:7b01:c146 tlla 80:7a:bf:6f:48:f3";

        assertEquals(expected, getSummary(packet));
    }

    public void testParseARPRequest() {
        final String packet =
                // Ethernet
                  "FFFFFFFFFFFF 807ABF6F48F3 0806" +
                  // ARP
                  "0001 0800 06 04" +
                  // Request
                  "0001" +
                  "807ABF6F48F3 64706ADB" +
                  "000000000000 64706FFD";

        final String expected =
                "TX 80:7a:bf:6f:48:f3 > ff:ff:ff:ff:ff:ff arp" +
                " who-has 100.112.111.253";

        assertEquals(expected, getSummary(packet));
    }

    public void testParseARPReply() {
        final String packet =
                // Ethernet
                  "807ABF6F48F3 288A1CA8DFC1 0806" +
                  // ARP
                  "0001 0800 06 04" +
                  // Reply
                  "0002" +
                  "288A1CA8DFC1 64706FFD"+
                  "807ABF6F48F3 64706ADB" +
                  // Ethernet padding to packet min size.
                  "0000000000000000000000000000";

        final String expected =
                "RX 28:8a:1c:a8:df:c1 > 80:7a:bf:6f:48:f3 arp" +
                " reply 100.112.111.253 28:8a:1c:a8:df:c1";

        assertEquals(expected, getSummary(packet));
    }

    public void testParseDHCPv4Discover() {
        final String packet =
                // Ethernet
                "FFFFFFFFFFFF 807ABF6F48F3 0800" +
                // IPv4
                "451001580000400040113986" +
                "00000000" +
                "FFFFFFFF" +
                // UDP
                "0044 0043" +
                "0144 5559" +
                // DHCPv4
                "01 01 06 00" +
                "79F7ACA4" +
                "0000 0000" +
                "00000000" +
                "00000000" +
                "00000000" +
                "00000000" +
                "807ABF6F48F300000000000000000000" +
                "0000000000000000000000000000000000000000000000000000000000000000" +
                "0000000000000000000000000000000000000000000000000000000000000000" +
                "0000000000000000000000000000000000000000000000000000000000000000" +
                "0000000000000000000000000000000000000000000000000000000000000000" +
                "0000000000000000000000000000000000000000000000000000000000000000" +
                "0000000000000000000000000000000000000000000000000000000000000000" +
                "63 82 53 63" +
                "35 01 01" +
                "3D 07 01807ABF6F48F3" +
                "39 02 05DC" +
                "3C 12 616E64726F69642D646863702D372E312E32" +
                "0C 18 616E64726F69642D36623030366333313333393835343139" +
                "37 0A 01 03 06 0F 1A 1C 33 3A 3B 2B" +
                "FF" +
                "00";

        final String expectedPrefix =
                "TX 80:7a:bf:6f:48:f3 > ff:ff:ff:ff:ff:ff ipv4" +
                " 0.0.0.0 > 255.255.255.255 udp" +
                " 68 > 67 dhcp4" +
                " 80:7a:bf:6f:48:f3 DISCOVER";

        assertTrue(getSummary(packet).startsWith(expectedPrefix));
    }

    public void testParseDHCPv4Offer() {
        final String packet =
                // Ethernet
                "807ABF6F48F3 288A1CA8DFC1 0800" +
                // IPv4
                "4500013D4D2C0000401188CB" +
                "64706FFD" +
                "64706ADB" +
                // UDP
                "0043 0044" +
                "0129 371D" +
                // DHCPv4
                "02 01 06 01" +
                "79F7ACA4" +
                "0000 0000" +
                "00000000" +
                "64706ADB" +
                "00000000" +
                "00000000" +
                "807ABF6F48F300000000000000000000" +
                "0000000000000000000000000000000000000000000000000000000000000000" +
                "0000000000000000000000000000000000000000000000000000000000000000" +
                "0000000000000000000000000000000000000000000000000000000000000000" +
                "0000000000000000000000000000000000000000000000000000000000000000" +
                "0000000000000000000000000000000000000000000000000000000000000000" +
                "0000000000000000000000000000000000000000000000000000000000000000" +
                "63 82 53 63" +
                "35 01 02" +
                "36 04 AC188A0B" +
                "33 04 00000708" +
                "01 04 FFFFF000" +
                "03 04 64706FFE" +
                "06 08 08080808" +
                "      08080404" +
                "FF0001076165313A363636FF";

        final String expectedPrefix =
                "RX 28:8a:1c:a8:df:c1 > 80:7a:bf:6f:48:f3 ipv4" +
                " 100.112.111.253 > 100.112.106.219 udp" +
                " 67 > 68 dhcp4" +
                " 80:7a:bf:6f:48:f3 OFFER";

        assertTrue(getSummary(packet).startsWith(expectedPrefix));
    }

    public void testParseDHCPv4Request() {
        final String packet =
                // Ethernet
                "FFFFFFFFFFFF 807ABF6F48F3 0800" +
                // IPv4
                "45100164000040004011397A" +
                "00000000" +
                "FFFFFFFF" +
                // UDP
                "0044 0043" +
                "0150 E5C7" +
                // DHCPv4
                "01 01 06 00" +
                "79F7ACA4" +
                "0001 0000" +
                "00000000" +
                "00000000" +
                "00000000" +
                "00000000" +
                "807ABF6F48F300000000000000000000" +
                "0000000000000000000000000000000000000000000000000000000000000000" +
                "0000000000000000000000000000000000000000000000000000000000000000" +
                "0000000000000000000000000000000000000000000000000000000000000000" +
                "0000000000000000000000000000000000000000000000000000000000000000" +
                "0000000000000000000000000000000000000000000000000000000000000000" +
                "0000000000000000000000000000000000000000000000000000000000000000" +
                "63 82 53 63" +
                "35 01 03" +
                "3D 07 01807ABF6F48F3" +
                "32 04 64706ADB" +
                "36 04 AC188A0B" +
                "39 02 05DC" +
                "3C 12 616E64726F69642D646863702D372E312E32" +
                "0C 18 616E64726F69642D36623030366333313333393835343139" +
                "37 0A 01 03 06 0F 1A 1C 33 3A 3B 2B" +
                "FF" +
                "00";

        final String expectedPrefix =
                "TX 80:7a:bf:6f:48:f3 > ff:ff:ff:ff:ff:ff ipv4" +
                " 0.0.0.0 > 255.255.255.255 udp" +
                " 68 > 67 dhcp4" +
                " 80:7a:bf:6f:48:f3 REQUEST";

        assertTrue(getSummary(packet).startsWith(expectedPrefix));
    }

    public void testParseDHCPv4Ack() {
        final String packet =
                // Ethernet
                "807ABF6F48F3 288A1CA8DFC1 0800" +
                // IPv4
                "4500013D4D3B0000401188BC" +
                "64706FFD" +
                "64706ADB" +
                // UDP
                "0043 0044" +
                "0129 341C" +
                // DHCPv4
                "02 01 06 01" +
                "79F7ACA4" +
                "0001 0000" +
                "00000000" +
                "64706ADB" +
                "00000000" +
                "00000000" +
                "807ABF6F48F300000000000000000000" +
                "0000000000000000000000000000000000000000000000000000000000000000" +
                "0000000000000000000000000000000000000000000000000000000000000000" +
                "0000000000000000000000000000000000000000000000000000000000000000" +
                "0000000000000000000000000000000000000000000000000000000000000000" +
                "0000000000000000000000000000000000000000000000000000000000000000" +
                "0000000000000000000000000000000000000000000000000000000000000000" +
                "63 82 53 63" +
                "35 01 05" +
                "36 04 AC188A0B" +
                "33 04 00000708" +
                "01 04 FFFFF000" +
                "03 04 64706FFE" +
                "06 08 08080808" +
                "      08080404" +
                "FF0001076165313A363636FF";

        final String expectedPrefix =
                "RX 28:8a:1c:a8:df:c1 > 80:7a:bf:6f:48:f3 ipv4" +
                " 100.112.111.253 > 100.112.106.219 udp" +
                " 67 > 68 dhcp4" +
                " 80:7a:bf:6f:48:f3 ACK";

        assertTrue(getSummary(packet).startsWith(expectedPrefix));
    }
}
