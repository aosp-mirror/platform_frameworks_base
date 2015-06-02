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
import android.system.OsConstants;
import android.test.suitebuilder.annotation.SmallTest;
import junit.framework.TestCase;

import java.net.Inet4Address;
import java.nio.ByteBuffer;

import static android.net.dhcp.DhcpPacket.*;


public class DhcpPacketTest extends TestCase {

    private static Inet4Address SERVER_ADDR =
            (Inet4Address) NetworkUtils.numericToInetAddress("192.0.2.1");
    private static Inet4Address CLIENT_ADDR =
            (Inet4Address) NetworkUtils.numericToInetAddress("192.0.2.234");
    private static byte[] CLIENT_MAC = new byte[] { 0x00, 0x01, 0x02, 0x03, 0x04, 0x05 };

    class TestDhcpPacket extends DhcpPacket {
        private byte mType;
        // TODO: Make this a map of option numbers to bytes instead.
        private byte[] mDomainBytes, mVendorInfoBytes, mLeaseTimeBytes;

        public TestDhcpPacket(byte type) {
            super(0xdeadbeef, (short) 0, INADDR_ANY, CLIENT_ADDR, INADDR_ANY, INADDR_ANY,
                  CLIENT_MAC, true);
            mType = type;
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
}
