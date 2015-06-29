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

package android.net.dhcp;

import android.net.NetworkUtils;
import android.net.DhcpResults;
import android.net.LinkAddress;
import android.system.OsConstants;
import android.test.suitebuilder.annotation.SmallTest;
import junit.framework.TestCase;

import java.net.Inet4Address;
import java.nio.ByteBuffer;
import java.util.Arrays;

import static android.net.dhcp.DhcpPacket.*;


public class DhcpPacketTest extends TestCase {

    private static Inet4Address SERVER_ADDR =
            (Inet4Address) NetworkUtils.numericToInetAddress("192.0.2.1");
    private static Inet4Address CLIENT_ADDR =
            (Inet4Address) NetworkUtils.numericToInetAddress("192.0.2.234");
    // Use our own empty address instead of Inet4Address.ANY or INADDR_ANY to ensure that the code
    // doesn't use == instead of equals when comparing addresses.
    private static Inet4Address ANY = (Inet4Address) NetworkUtils.numericToInetAddress("0.0.0.0");

    private static byte[] CLIENT_MAC = new byte[] { 0x00, 0x01, 0x02, 0x03, 0x04, 0x05 };

    public void setUp() {
        DhcpPacket.testOverrideVendorId = "android-dhcp-???";
        DhcpPacket.testOverrideHostname = "android-01234567890abcde";
    }

    class TestDhcpPacket extends DhcpPacket {
        private byte mType;
        // TODO: Make this a map of option numbers to bytes instead.
        private byte[] mDomainBytes, mVendorInfoBytes, mLeaseTimeBytes, mNetmaskBytes;

        public TestDhcpPacket(byte type, Inet4Address clientIp, Inet4Address yourIp) {
            super(0xdeadbeef, (short) 0, clientIp, yourIp, INADDR_ANY, INADDR_ANY,
                  CLIENT_MAC, true);
            mType = type;
        }

        public TestDhcpPacket(byte type) {
            this(type, INADDR_ANY, CLIENT_ADDR);
        }

        public TestDhcpPacket setDomainBytes(byte[] domainBytes) {
            mDomainBytes = domainBytes;
            return this;
        }

        public TestDhcpPacket setVendorInfoBytes(byte[] vendorInfoBytes) {
            mVendorInfoBytes = vendorInfoBytes;
            return this;
        }

        public TestDhcpPacket setLeaseTimeBytes(byte[] leaseTimeBytes) {
            mLeaseTimeBytes = leaseTimeBytes;
            return this;
        }

        public TestDhcpPacket setNetmaskBytes(byte[] netmaskBytes) {
            mNetmaskBytes = netmaskBytes;
            return this;
        }

        public ByteBuffer buildPacket(int encap, short unusedDestUdp, short unusedSrcUdp) {
            ByteBuffer result = ByteBuffer.allocate(MAX_LENGTH);
            fillInPacket(encap, CLIENT_ADDR, SERVER_ADDR,
                         DHCP_CLIENT, DHCP_SERVER, result, DHCP_BOOTREPLY, false);
            return result;
        }

        public void finishPacket(ByteBuffer buffer) {
            addTlv(buffer, DHCP_MESSAGE_TYPE, mType);
            if (mDomainBytes != null) {
                addTlv(buffer, DHCP_DOMAIN_NAME, mDomainBytes);
            }
            if (mVendorInfoBytes != null) {
                addTlv(buffer, DHCP_VENDOR_CLASS_ID, mVendorInfoBytes);
            }
            if (mLeaseTimeBytes != null) {
                addTlv(buffer, DHCP_LEASE_TIME, mLeaseTimeBytes);
            }
            if (mNetmaskBytes != null) {
                addTlv(buffer, DHCP_SUBNET_MASK, mNetmaskBytes);
            }
            addTlvEnd(buffer);
        }

        // Convenience method.
        public ByteBuffer build() {
            // ENCAP_BOOTP packets don't contain ports, so just pass in 0.
            ByteBuffer pkt = buildPacket(ENCAP_BOOTP, (short) 0, (short) 0);
            pkt.flip();
            return pkt;
        }
    }

    private void assertDomainAndVendorInfoParses(
            String expectedDomain, byte[] domainBytes,
            String expectedVendorInfo, byte[] vendorInfoBytes) {
        ByteBuffer packet = new TestDhcpPacket(DHCP_MESSAGE_TYPE_OFFER)
                .setDomainBytes(domainBytes)
                .setVendorInfoBytes(vendorInfoBytes)
                .build();
        DhcpPacket offerPacket = DhcpPacket.decodeFullPacket(packet, ENCAP_BOOTP);
        assertEquals(expectedDomain, offerPacket.mDomainName);
        assertEquals(expectedVendorInfo, offerPacket.mVendorId);
    }

    @SmallTest
    public void testDomainName() throws Exception {
        byte[] nullByte = new byte[] { 0x00 };
        byte[] twoNullBytes = new byte[] { 0x00, 0x00 };
        byte[] nonNullDomain = new byte[] {
            (byte) 'g', (byte) 'o', (byte) 'o', (byte) '.', (byte) 'g', (byte) 'l'
        };
        byte[] trailingNullDomain = new byte[] {
            (byte) 'g', (byte) 'o', (byte) 'o', (byte) '.', (byte) 'g', (byte) 'l', 0x00
        };
        byte[] embeddedNullsDomain = new byte[] {
            (byte) 'g', (byte) 'o', (byte) 'o', 0x00, 0x00, (byte) 'g', (byte) 'l'
        };
        byte[] metered = "ANDROID_METERED".getBytes("US-ASCII");

        byte[] meteredEmbeddedNull = metered.clone();
        meteredEmbeddedNull[7] = (char) 0;

        byte[] meteredTrailingNull = metered.clone();
        meteredTrailingNull[meteredTrailingNull.length - 1] = (char) 0;

        assertDomainAndVendorInfoParses("", nullByte, "\u0000", nullByte);
        assertDomainAndVendorInfoParses("", twoNullBytes, "\u0000\u0000", twoNullBytes);
        assertDomainAndVendorInfoParses("goo.gl", nonNullDomain, "ANDROID_METERED", metered);
        assertDomainAndVendorInfoParses("goo", embeddedNullsDomain,
                                        "ANDROID\u0000METERED", meteredEmbeddedNull);
        assertDomainAndVendorInfoParses("goo.gl", trailingNullDomain,
                                        "ANDROID_METERE\u0000", meteredTrailingNull);
    }

    private void assertLeaseTimeParses(boolean expectValid, Integer rawLeaseTime,
                                       long leaseTimeMillis, byte[] leaseTimeBytes) {
        TestDhcpPacket testPacket = new TestDhcpPacket(DHCP_MESSAGE_TYPE_OFFER);
        if (leaseTimeBytes != null) {
            testPacket.setLeaseTimeBytes(leaseTimeBytes);
        }
        ByteBuffer packet = testPacket.build();
        DhcpPacket offerPacket = DhcpPacket.decodeFullPacket(packet, ENCAP_BOOTP);
        if (!expectValid) {
            assertNull(offerPacket);
            return;
        }
        assertEquals(rawLeaseTime, offerPacket.mLeaseTime);
        DhcpResults dhcpResults = offerPacket.toDhcpResults();  // Just check this doesn't crash.
        assertEquals(leaseTimeMillis, offerPacket.getLeaseTimeMillis());
    }

    @SmallTest
    public void testLeaseTime() throws Exception {
        byte[] noLease = null;
        byte[] tooShortLease = new byte[] { 0x00, 0x00 };
        byte[] tooLongLease = new byte[] { 0x00, 0x00, 0x00, 60, 0x01 };
        byte[] zeroLease = new byte[] { 0x00, 0x00, 0x00, 0x00 };
        byte[] tenSecondLease = new byte[] { 0x00, 0x00, 0x00, 10 };
        byte[] oneMinuteLease = new byte[] { 0x00, 0x00, 0x00, 60 };
        byte[] fiveMinuteLease = new byte[] { 0x00, 0x00, 0x01, 0x2c };
        byte[] oneDayLease = new byte[] { 0x00, 0x01, 0x51, (byte) 0x80 };
        byte[] maxIntPlusOneLease = new byte[] { (byte) 0x80, 0x00, 0x00, 0x01 };
        byte[] infiniteLease = new byte[] { (byte) 0xff, (byte) 0xff, (byte) 0xff, (byte) 0xff };

        assertLeaseTimeParses(true, null, 0, noLease);
        assertLeaseTimeParses(false, null, 0, tooShortLease);
        assertLeaseTimeParses(false, null, 0, tooLongLease);
        assertLeaseTimeParses(true, 0, 60 * 1000, zeroLease);
        assertLeaseTimeParses(true, 10, 60 * 1000, tenSecondLease);
        assertLeaseTimeParses(true, 60, 60 * 1000, oneMinuteLease);
        assertLeaseTimeParses(true, 300, 300 * 1000, fiveMinuteLease);
        assertLeaseTimeParses(true, 86400, 86400 * 1000, oneDayLease);
        assertLeaseTimeParses(true, -2147483647, 2147483649L * 1000, maxIntPlusOneLease);
        assertLeaseTimeParses(true, DhcpPacket.INFINITE_LEASE, 0, infiniteLease);
    }

    private void checkIpAddress(String expected, Inet4Address clientIp, Inet4Address yourIp,
                                byte[] netmaskBytes) {
        checkIpAddress(expected, DHCP_MESSAGE_TYPE_OFFER, clientIp, yourIp, netmaskBytes);
        checkIpAddress(expected, DHCP_MESSAGE_TYPE_ACK, clientIp, yourIp, netmaskBytes);
    }

    private void checkIpAddress(String expected, byte type,
                                Inet4Address clientIp, Inet4Address yourIp,
                                byte[] netmaskBytes) {
        ByteBuffer packet = new TestDhcpPacket(type, clientIp, yourIp)
                .setNetmaskBytes(netmaskBytes)
                .build();
        DhcpPacket offerPacket = DhcpPacket.decodeFullPacket(packet, ENCAP_BOOTP);
        DhcpResults results = offerPacket.toDhcpResults();

        if (expected != null) {
            LinkAddress expectedAddress = new LinkAddress(expected);
            assertEquals(expectedAddress, results.ipAddress);
        } else {
            assertNull(results);
        }
    }

    @SmallTest
    public void testIpAddress() throws Exception {
        byte[] slash11Netmask = new byte[] { (byte) 0xff, (byte) 0xe0, 0x00, 0x00 };
        byte[] slash24Netmask = new byte[] { (byte) 0xff, (byte) 0xff, (byte) 0xff, 0x00 };
        byte[] invalidNetmask = new byte[] { (byte) 0xff, (byte) 0xfb, (byte) 0xff, 0x00 };
        Inet4Address example1 = (Inet4Address) NetworkUtils.numericToInetAddress("192.0.2.1");
        Inet4Address example2 = (Inet4Address) NetworkUtils.numericToInetAddress("192.0.2.43");

        // A packet without any addresses is not valid.
        checkIpAddress(null, ANY, ANY, slash24Netmask);

        // ClientIP is used iff YourIP is not present.
        checkIpAddress("192.0.2.1/24", example2, example1, slash24Netmask);
        checkIpAddress("192.0.2.43/11", example2, ANY, slash11Netmask);
        checkIpAddress("192.0.2.43/11", ANY, example2, slash11Netmask);

        // Invalid netmasks are ignored.
        checkIpAddress(null, example2, ANY, invalidNetmask);

        // If there is no netmask, implicit netmasks are used.
        checkIpAddress("192.0.2.43/24", ANY, example2, null);
    }

    @SmallTest
    public void testDiscoverPacket() throws Exception {
        short secs = 7;
        int transactionId = 0xdeadbeef;
        byte[] hwaddr = {
                (byte) 0xda, (byte) 0x01, (byte) 0x19, (byte) 0x5b, (byte) 0xb1, (byte) 0x7a
        };
        byte[] params = new byte[] {
            DHCP_SUBNET_MASK,
            DHCP_ROUTER,
            DHCP_DNS_SERVER,
            DHCP_DOMAIN_NAME,
            DHCP_MTU,
            DHCP_LEASE_TIME,
        };

        ByteBuffer packet = DhcpPacket.buildDiscoverPacket(
                DhcpPacket.ENCAP_L2, transactionId, secs, hwaddr,
                false /* do unicast */, params);

        byte[] headers = new byte[] {
            // Ethernet header.
            (byte) 0xff, (byte) 0xff, (byte) 0xff, (byte) 0xff, (byte) 0xff, (byte) 0xff,
            (byte) 0xda, (byte) 0x01, (byte) 0x19, (byte) 0x5b, (byte) 0xb1, (byte) 0x7a,
            (byte) 0x08, (byte) 0x00,
            // IP header.
            (byte) 0x45, (byte) 0x10, (byte) 0x01, (byte) 0x52,
            (byte) 0x00, (byte) 0x00, (byte) 0x40, (byte) 0x00,
            (byte) 0x40, (byte) 0x11, (byte) 0x39, (byte) 0x8c,
            (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x00,
            (byte) 0xff, (byte) 0xff, (byte) 0xff, (byte) 0xff,
            // UDP header.
            (byte) 0x00, (byte) 0x44, (byte) 0x00, (byte) 0x43,
            (byte) 0x01, (byte) 0x3e, (byte) 0xd8, (byte) 0xa4,
            // BOOTP.
            (byte) 0x01, (byte) 0x01, (byte) 0x06, (byte) 0x00,
            (byte) 0xde, (byte) 0xad, (byte) 0xbe, (byte) 0xef,
            (byte) 0x00, (byte) 0x07, (byte) 0x00, (byte) 0x00,
            (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x00,
            (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x00,
            (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x00,
            (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x00,
            (byte) 0xda, (byte) 0x01, (byte) 0x19, (byte) 0x5b,
            (byte) 0xb1, (byte) 0x7a
        };
        byte[] options = new byte[] {
            // Magic cookie 0x63825363.
            (byte) 0x63, (byte) 0x82, (byte) 0x53, (byte) 0x63,
            // Message type DISCOVER.
            (byte) 0x35, (byte) 0x01, (byte) 0x01,
            // Client identifier Ethernet, da:01:19:5b:b1:7a.
            (byte) 0x3d, (byte) 0x07,
                    (byte) 0x01,
                    (byte) 0xda, (byte) 0x01, (byte) 0x19, (byte) 0x5b, (byte) 0xb1, (byte) 0x7a,
            // Max message size 1500.
            (byte) 0x39, (byte) 0x02, (byte) 0x05, (byte) 0xdc,
            // Version "android-dhcp-???".
            (byte) 0x3c, (byte) 0x10,
                    'a', 'n', 'd', 'r', 'o', 'i', 'd', '-', 'd', 'h', 'c', 'p', '-', '?', '?', '?',
            // Hostname "android-01234567890abcde"
            (byte) 0x0c, (byte) 0x18,
                    'a', 'n', 'd', 'r', 'o', 'i', 'd', '-',
                    '0', '1', '2', '3', '4', '5', '6', '7', '8', '9', '0', 'a', 'b', 'c', 'd', 'e',
            // Requested parameter list.
            (byte) 0x37, (byte) 0x06,
                DHCP_SUBNET_MASK,
                DHCP_ROUTER,
                DHCP_DNS_SERVER,
                DHCP_DOMAIN_NAME,
                DHCP_MTU,
                DHCP_LEASE_TIME,
            // End options.
            (byte) 0xff,
            // Our packets are always of even length. TODO: find out why and possibly fix it.
            (byte) 0x00
        };
        byte[] expected = new byte[DhcpPacket.MIN_PACKET_LENGTH_L2 + options.length];
        assertTrue((expected.length & 1) == 0);
        System.arraycopy(headers, 0, expected, 0, headers.length);
        System.arraycopy(options, 0, expected, DhcpPacket.MIN_PACKET_LENGTH_L2, options.length);

        byte[] actual = new byte[packet.limit()];
        packet.get(actual);
        String msg =
                "Expected:\n  " + Arrays.toString(expected) +
                "\nActual:\n  " + Arrays.toString(actual);
        assertTrue(msg, Arrays.equals(expected, actual));
    }
}
