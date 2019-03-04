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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import androidx.test.filters.SmallTest;
import androidx.test.runner.AndroidJUnit4;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.Arrays;
import java.util.List;

@RunWith(AndroidJUnit4.class)
@SmallTest
public class DnsPacketTest {
    private void assertHeaderParses(DnsPacket.DnsHeader header, int id, int flag,
            int qCount, int aCount, int nsCount, int arCount) {
        assertEquals(header.id, id);
        assertEquals(header.flags, flag);
        assertEquals(header.getRecordCount(DnsPacket.QDSECTION), qCount);
        assertEquals(header.getRecordCount(DnsPacket.ANSECTION), aCount);
        assertEquals(header.getRecordCount(DnsPacket.NSSECTION), nsCount);
        assertEquals(header.getRecordCount(DnsPacket.ARSECTION), arCount);
    }

    private void assertRecordParses(DnsPacket.DnsRecord record, String dname,
            int dtype, int dclass, int ttl, byte[] rr) {
        assertEquals(record.dName, dname);
        assertEquals(record.nsType, dtype);
        assertEquals(record.nsClass, dclass);
        assertEquals(record.ttl, ttl);
        assertTrue(Arrays.equals(record.getRR(), rr));
    }

    class TestDnsPacket extends DnsPacket {
        TestDnsPacket(byte[] data) throws ParseException {
            super(data);
        }

        public DnsHeader getHeader() {
            return mHeader;
        }
        public List<DnsRecord> getRecordList(int secType) {
            return mRecords[secType];
        }
    }

    @Test
    public void testNullDisallowed() {
        try {
            new TestDnsPacket(null);
            fail("Exception not thrown for null byte array");
        } catch (ParseException e) {
        }
    }

    @Test
    public void testV4Answer() throws Exception {
        final byte[] v4blob = new byte[] {
            /* Header */
            0x55, 0x66, /* Transaction ID */
            (byte) 0x81, (byte) 0x80, /* Flags */
            0x00, 0x01, /* Questions */
            0x00, 0x01, /* Answer RRs */
            0x00, 0x00, /* Authority RRs */
            0x00, 0x00, /* Additional RRs */
            /* Queries */
            0x03, 0x77, 0x77, 0x77, 0x06, 0x67, 0x6F, 0x6F, 0x67, 0x6c, 0x65,
            0x03, 0x63, 0x6f, 0x6d, 0x00, /* Name */
            0x00, 0x01, /* Type */
            0x00, 0x01, /* Class */
            /* Answers */
            (byte) 0xc0, 0x0c, /* Name */
            0x00, 0x01, /* Type */
            0x00, 0x01, /* Class */
            0x00, 0x00, 0x01, 0x2b, /* TTL */
            0x00, 0x04, /* Data length */
            (byte) 0xac, (byte) 0xd9, (byte) 0xa1, (byte) 0x84 /* Address */
        };
        TestDnsPacket packet = new TestDnsPacket(v4blob);

        // Header part
        assertHeaderParses(packet.getHeader(), 0x5566, 0x8180, 1, 1, 0, 0);

        // Record part
        List<DnsPacket.DnsRecord> qdRecordList =
                packet.getRecordList(DnsPacket.QDSECTION);
        assertEquals(qdRecordList.size(), 1);
        assertRecordParses(qdRecordList.get(0), "www.google.com", 1, 1, 0, null);

        List<DnsPacket.DnsRecord> anRecordList =
                packet.getRecordList(DnsPacket.ANSECTION);
        assertEquals(anRecordList.size(), 1);
        assertRecordParses(anRecordList.get(0), "www.google.com", 1, 1, 0x12b,
                new byte[]{ (byte) 0xac, (byte) 0xd9, (byte) 0xa1, (byte) 0x84 });
    }

    @Test
    public void testV6Answer() throws Exception {
        final byte[] v6blob = new byte[] {
            /* Header */
            0x77, 0x22, /* Transaction ID */
            (byte) 0x81, (byte) 0x80, /* Flags */
            0x00, 0x01, /* Questions */
            0x00, 0x01, /* Answer RRs */
            0x00, 0x00, /* Authority RRs */
            0x00, 0x00, /* Additional RRs */
            /* Queries */
            0x03, 0x77, 0x77, 0x77, 0x06, 0x67, 0x6F, 0x6F, 0x67, 0x6c, 0x65,
            0x03, 0x63, 0x6f, 0x6d, 0x00, /* Name */
            0x00, 0x1c, /* Type */
            0x00, 0x01, /* Class */
            /* Answers */
            (byte) 0xc0, 0x0c, /* Name */
            0x00, 0x1c, /* Type */
            0x00, 0x01, /* Class */
            0x00, 0x00, 0x00, 0x37, /* TTL */
            0x00, 0x10, /* Data length */
            0x24, 0x04, 0x68, 0x00, 0x40, 0x05, 0x08, 0x0d,
            0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x20, 0x04 /* Address */
        };
        TestDnsPacket packet = new TestDnsPacket(v6blob);

        // Header part
        assertHeaderParses(packet.getHeader(), 0x7722, 0x8180, 1, 1, 0, 0);

        // Record part
        List<DnsPacket.DnsRecord> qdRecordList =
                packet.getRecordList(DnsPacket.QDSECTION);
        assertEquals(qdRecordList.size(), 1);
        assertRecordParses(qdRecordList.get(0), "www.google.com", 28, 1, 0, null);

        List<DnsPacket.DnsRecord> anRecordList =
                packet.getRecordList(DnsPacket.ANSECTION);
        assertEquals(anRecordList.size(), 1);
        assertRecordParses(anRecordList.get(0), "www.google.com", 28, 1, 0x37,
                new byte[]{ 0x24, 0x04, 0x68, 0x00, 0x40, 0x05, 0x08, 0x0d,
                    0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x20, 0x04 });
    }
}
