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
import com.android.internal.util.HexDump;

import java.net.Inet4Address;
import java.nio.ByteBuffer;
import java.util.ArrayList;

import junit.framework.TestCase;
import libcore.util.HexEncoding;

import static android.net.dhcp.DhcpPacket.*;


public class DhcpPacketTest extends TestCase {

    private static Inet4Address SERVER_ADDR = v4Address("192.0.2.1");
    private static Inet4Address CLIENT_ADDR = v4Address("192.0.2.234");
    // Use our own empty address instead of Inet4Address.ANY or INADDR_ANY to ensure that the code
    // doesn't use == instead of equals when comparing addresses.
    private static Inet4Address ANY = (Inet4Address) v4Address("0.0.0.0");

    private static byte[] CLIENT_MAC = new byte[] { 0x00, 0x01, 0x02, 0x03, 0x04, 0x05 };

    private static final Inet4Address v4Address(String addrString) throws IllegalArgumentException {
        return (Inet4Address) NetworkUtils.numericToInetAddress(addrString);
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
                addTlv(buffer, DHCP_VENDOR_INFO, mVendorInfoBytes);
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
        assertEquals(expectedVendorInfo, offerPacket.mVendorInfo);
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
        Inet4Address example1 = v4Address("192.0.2.1");
        Inet4Address example2 = v4Address("192.0.2.43");

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

    private void assertDhcpResults(String ipAddress, String gateway, String dnsServersString,
            String domains, String serverAddress, String vendorInfo, int leaseDuration,
            boolean hasMeteredHint, DhcpResults dhcpResults) throws Exception {
        assertEquals(new LinkAddress(ipAddress), dhcpResults.ipAddress);
        assertEquals(v4Address(gateway), dhcpResults.gateway);

        String[] dnsServerStrings = dnsServersString.split(",");
        ArrayList dnsServers = new ArrayList();
        for (String dnsServerString : dnsServerStrings) {
            dnsServers.add(v4Address(dnsServerString));
        }
        assertEquals(dnsServers, dhcpResults.dnsServers);

        assertEquals(domains, dhcpResults.domains);
        assertEquals(v4Address(serverAddress), dhcpResults.serverAddress);
        assertEquals(vendorInfo, dhcpResults.vendorInfo);
        assertEquals(leaseDuration, dhcpResults.leaseDuration);
        assertEquals(hasMeteredHint, dhcpResults.hasMeteredHint());
    }

    @SmallTest
    public void testOffer1() throws Exception {
        // TODO: Turn all of these into golden files. This will probably require modifying
        // Android.mk appropriately, making this into an AndroidTestCase, and adding code to read
        // the golden files from the test APK's assets via mContext.getAssets().
        final ByteBuffer packet = ByteBuffer.wrap(HexEncoding.decode((
            // IP header.
            "451001480000000080118849c0a89003c0a89ff7" +
            // UDP header.
            "004300440134dcfa" +
            // BOOTP header.
            "02010600c997a63b0000000000000000c0a89ff70000000000000000" +
            // MAC address.
            "30766ff2a90c00000000000000000000" +
            // Server name.
            "0000000000000000000000000000000000000000000000000000000000000000" +
            "0000000000000000000000000000000000000000000000000000000000000000" +
            // File.
            "0000000000000000000000000000000000000000000000000000000000000000" +
            "0000000000000000000000000000000000000000000000000000000000000000" +
            "0000000000000000000000000000000000000000000000000000000000000000" +
            "0000000000000000000000000000000000000000000000000000000000000000" +
            // Options
            "638253633501023604c0a89003330400001c200104fffff0000304c0a89ffe06080808080808080404" +
            "3a0400000e103b040000189cff00000000000000000000"
        ).toCharArray(), false));

        DhcpPacket offerPacket = DhcpPacket.decodeFullPacket(packet, ENCAP_L3);
        assertTrue(offerPacket instanceof DhcpOfferPacket);  // Implicitly checks it's non-null.
        DhcpResults dhcpResults = offerPacket.toDhcpResults();
        assertDhcpResults("192.168.159.247/20", "192.168.159.254", "8.8.8.8,8.8.4.4",
                null, "192.168.144.3", null, 7200, false, dhcpResults);
    }

    @SmallTest
    public void testOffer2() throws Exception {
        final ByteBuffer packet = ByteBuffer.wrap(HexEncoding.decode((
            // IP header.
            "450001518d0600004011144dc0a82b01c0a82bf7" +
            // UDP header.
            "00430044013d9ac7" +
            // BOOTP header.
            "02010600dfc23d1f0002000000000000c0a82bf7c0a82b0100000000" +
            // MAC address.
            "30766ff2a90c00000000000000000000" +
            // Server name.
            "0000000000000000000000000000000000000000000000000000000000000000" +
            "0000000000000000000000000000000000000000000000000000000000000000" +
            // File.
            "0000000000000000000000000000000000000000000000000000000000000000" +
            "0000000000000000000000000000000000000000000000000000000000000000" +
            "0000000000000000000000000000000000000000000000000000000000000000" +
            "0000000000000000000000000000000000000000000000000000000000000000" +
            // Options
            "638253633501023604c0a82b01330400000e103a04000007083b0400000c4e0104ffffff00" +
            "1c04c0a82bff0304c0a82b010604c0a82b012b0f414e44524f49445f4d455445524544ff"
        ).toCharArray(), false));

        assertEquals(337, packet.limit());
        DhcpPacket offerPacket = DhcpPacket.decodeFullPacket(packet, ENCAP_L3);
        assertTrue(offerPacket instanceof DhcpOfferPacket);  // Implicitly checks it's non-null.
        DhcpResults dhcpResults = offerPacket.toDhcpResults();
        assertDhcpResults("192.168.43.247/24", "192.168.43.1", "192.168.43.1",
                null, "192.168.43.1", "ANDROID_METERED", 3600, true, dhcpResults);
        assertTrue(dhcpResults.hasMeteredHint());
    }

    @SmallTest
    public void testPadAndOverloadedOptionsOffer() throws Exception {
        // A packet observed in the real world that is interesting for two reasons:
        //
        // 1. It uses pad bytes, which we previously didn't support correctly.
        // 2. It uses DHCP option overloading, which we don't currently support (but it doesn't
        //    store any information in the overloaded fields).
        //
        // For now, we just check that it parses correctly.
        final ByteBuffer packet = ByteBuffer.wrap(HexEncoding.decode((
            // Ethernet header.
            "b4cef6000000e80462236e300800" +
            // IP header.
            "4500014c00000000ff11741701010101ac119876" +
            // UDP header. TODO: fix invalid checksum (due to MAC address obfuscation).
            "004300440138ae5a" +
            // BOOTP header.
            "020106000fa0059f0000000000000000ac1198760000000000000000" +
            // MAC address.
            "b4cef600000000000000000000000000" +
            // Server name.
            "ff00000000000000000000000000000000000000000000000000000000000000" +
            "0000000000000000000000000000000000000000000000000000000000000000" +
            // File.
            "ff00000000000000000000000000000000000000000000000000000000000000" +
            "0000000000000000000000000000000000000000000000000000000000000000" +
            "0000000000000000000000000000000000000000000000000000000000000000" +
            "0000000000000000000000000000000000000000000000000000000000000000" +
            // Options
            "638253633501023604010101010104ffff000033040000a8c03401030304ac1101010604ac110101" +
            "0000000000000000000000000000000000000000000000ff000000"
        ).toCharArray(), false));

        DhcpPacket offerPacket = DhcpPacket.decodeFullPacket(packet, ENCAP_L2);
        assertTrue(offerPacket instanceof DhcpOfferPacket);
        DhcpResults dhcpResults = offerPacket.toDhcpResults();
        assertDhcpResults("172.17.152.118/16", "172.17.1.1", "172.17.1.1",
                null, "1.1.1.1", null, 43200, false, dhcpResults);
    }

    @SmallTest
    public void testBug2111() throws Exception {
        final ByteBuffer packet = ByteBuffer.wrap(HexEncoding.decode((
            // IP header.
            "4500014c00000000ff119beac3eaf3880a3f5d04" +
            // UDP header. TODO: fix invalid checksum (due to MAC address obfuscation).
            "0043004401387464" +
            // BOOTP header.
            "0201060002554812000a0000000000000a3f5d040000000000000000" +
            // MAC address.
            "00904c00000000000000000000000000" +
            // Server name.
            "0000000000000000000000000000000000000000000000000000000000000000" +
            "0000000000000000000000000000000000000000000000000000000000000000" +
            // File.
            "0000000000000000000000000000000000000000000000000000000000000000" +
            "0000000000000000000000000000000000000000000000000000000000000000" +
            "0000000000000000000000000000000000000000000000000000000000000000" +
            "0000000000000000000000000000000000000000000000000000000000000000" +
            // Options.
            "638253633501023604c00002fe33040000bfc60104fffff00003040a3f50010608c0000201c0000202" +
            "0f0f646f6d61696e3132332e636f2e756b0000000000ff00000000"
        ).toCharArray(), false));

        DhcpPacket offerPacket = DhcpPacket.decodeFullPacket(packet, ENCAP_L3);
        assertTrue(offerPacket instanceof DhcpOfferPacket);
        DhcpResults dhcpResults = offerPacket.toDhcpResults();
        assertDhcpResults("10.63.93.4/20", "10.63.80.1", "192.0.2.1,192.0.2.2",
                "domain123.co.uk", "192.0.2.254", null, 49094, false, dhcpResults);
    }

    @SmallTest
    public void testBug2136() throws Exception {
        final ByteBuffer packet = ByteBuffer.wrap(HexEncoding.decode((
            // Ethernet header.
            "bcf5ac000000d0c7890000000800" +
            // IP header.
            "4500014c00000000ff119beac3eaf3880a3f5d04" +
            // UDP header. TODO: fix invalid checksum (due to MAC address obfuscation).
            "0043004401387574" +
            // BOOTP header.
            "0201060163339a3000050000000000000a209ecd0000000000000000" +
            // MAC address.
            "bcf5ac00000000000000000000000000" +
            // Server name.
            "0000000000000000000000000000000000000000000000000000000000000000" +
            "0000000000000000000000000000000000000000000000000000000000000000" +
            // File.
            "0000000000000000000000000000000000000000000000000000000000000000" +
            "0000000000000000000000000000000000000000000000000000000000000000" +
            "0000000000000000000000000000000000000000000000000000000000000000" +
            "0000000000000000000000000000000000000000000000000000000000000000" +
            // Options.
            "6382536335010236040a20ff80330400001c200104fffff00003040a20900106089458413494584135" +
            "0f0b6c616e63732e61632e756b000000000000000000ff00000000"
        ).toCharArray(), false));

        DhcpPacket offerPacket = DhcpPacket.decodeFullPacket(packet, ENCAP_L2);
        assertTrue(offerPacket instanceof DhcpOfferPacket);
        assertEquals("BCF5AC000000", HexDump.toHexString(offerPacket.getClientMac()));
        DhcpResults dhcpResults = offerPacket.toDhcpResults();
        assertDhcpResults("10.32.158.205/20", "10.32.144.1", "148.88.65.52,148.88.65.53",
                "lancs.ac.uk", "10.32.255.128", null, 7200, false, dhcpResults);
    }
}
