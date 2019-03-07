/*
 * Copyright (C) 2017 The Android Open Source Project
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

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assume.assumeTrue;

import android.system.OsConstants;

import androidx.test.filters.SmallTest;
import androidx.test.runner.AndroidJUnit4;

import libcore.util.HexEncoding;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.nio.ByteOrder;

@RunWith(AndroidJUnit4.class)
@SmallTest
public class ConntrackMessageTest {
    private static final boolean USING_LE = (ByteOrder.nativeOrder() == ByteOrder.LITTLE_ENDIAN);

    // Example 1: TCP (192.168.43.209, 44333) -> (23.211.13.26, 443)
    public static final String CT_V4UPDATE_TCP_HEX =
            // struct nlmsghdr
            "50000000" +      // length = 80
            "0001" +          // type = (1 << 8) | 0
            "0501" +          // flags
            "01000000" +      // seqno = 1
            "00000000" +      // pid = 0
            // struct nfgenmsg
            "02" +            // nfgen_family  = AF_INET
            "00" +            // version = NFNETLINK_V0
            "0000" +          // res_id
            // struct nlattr
            "3400" +          // nla_len = 52
            "0180" +          // nla_type = nested CTA_TUPLE_ORIG
                // struct nlattr
                "1400" +      // nla_len = 20
                "0180" +      // nla_type = nested CTA_TUPLE_IP
                    "0800 0100 C0A82BD1" +  // nla_type=CTA_IP_V4_SRC, ip=192.168.43.209
                    "0800 0200 17D30D1A" +  // nla_type=CTA_IP_V4_DST, ip=23.211.13.26
                // struct nlattr
                "1C00" +      // nla_len = 28
                "0280" +      // nla_type = nested CTA_TUPLE_PROTO
                    "0500 0100 06 000000" +  // nla_type=CTA_PROTO_NUM, proto=6
                    "0600 0200 AD2D 0000" +  // nla_type=CTA_PROTO_SRC_PORT, port=44333 (big endian)
                    "0600 0300 01BB 0000" +  // nla_type=CTA_PROTO_DST_PORT, port=443 (big endian)
            // struct nlattr
            "0800" +          // nla_len = 8
            "0700" +          // nla_type = CTA_TIMEOUT
            "00069780";       // nla_value = 432000 (big endian)
    public static final byte[] CT_V4UPDATE_TCP_BYTES =
            HexEncoding.decode(CT_V4UPDATE_TCP_HEX.replaceAll(" ", "").toCharArray(), false);

    // Example 2: UDP (100.96.167.146, 37069) -> (216.58.197.10, 443)
    public static final String CT_V4UPDATE_UDP_HEX =
            // struct nlmsghdr
            "50000000" +      // length = 80
            "0001" +          // type = (1 << 8) | 0
            "0501" +          // flags
            "01000000" +      // seqno = 1
            "00000000" +      // pid = 0
            // struct nfgenmsg
            "02" +            // nfgen_family  = AF_INET
            "00" +            // version = NFNETLINK_V0
            "0000" +          // res_id
            // struct nlattr
            "3400" +          // nla_len = 52
            "0180" +          // nla_type = nested CTA_TUPLE_ORIG
                // struct nlattr
                "1400" +      // nla_len = 20
                "0180" +      // nla_type = nested CTA_TUPLE_IP
                    "0800 0100 6460A792" +  // nla_type=CTA_IP_V4_SRC, ip=100.96.167.146
                    "0800 0200 D83AC50A" +  // nla_type=CTA_IP_V4_DST, ip=216.58.197.10
                // struct nlattr
                "1C00" +      // nla_len = 28
                "0280" +      // nla_type = nested CTA_TUPLE_PROTO
                    "0500 0100 11 000000" +  // nla_type=CTA_PROTO_NUM, proto=17
                    "0600 0200 90CD 0000" +  // nla_type=CTA_PROTO_SRC_PORT, port=37069 (big endian)
                    "0600 0300 01BB 0000" +  // nla_type=CTA_PROTO_DST_PORT, port=443 (big endian)
            // struct nlattr
            "0800" +          // nla_len = 8
            "0700" +          // nla_type = CTA_TIMEOUT
            "000000B4";       // nla_value = 180 (big endian)
    public static final byte[] CT_V4UPDATE_UDP_BYTES =
            HexEncoding.decode(CT_V4UPDATE_UDP_HEX.replaceAll(" ", "").toCharArray(), false);

    @Test
    public void testConntrackIPv4TcpTimeoutUpdate() throws Exception {
        assumeTrue(USING_LE);

        final byte[] tcp = ConntrackMessage.newIPv4TimeoutUpdateRequest(
                OsConstants.IPPROTO_TCP,
                (Inet4Address) InetAddress.getByName("192.168.43.209"), 44333,
                (Inet4Address) InetAddress.getByName("23.211.13.26"), 443,
                432000);
        assertArrayEquals(CT_V4UPDATE_TCP_BYTES, tcp);
    }

    @Test
    public void testConntrackIPv4UdpTimeoutUpdate() throws Exception {
        assumeTrue(USING_LE);

        final byte[] udp = ConntrackMessage.newIPv4TimeoutUpdateRequest(
                OsConstants.IPPROTO_UDP,
                (Inet4Address) InetAddress.getByName("100.96.167.146"), 37069,
                (Inet4Address) InetAddress.getByName("216.58.197.10"), 443,
                180);
        assertArrayEquals(CT_V4UPDATE_UDP_BYTES, udp);
    }
}
