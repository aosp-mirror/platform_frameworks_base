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

import static android.net.netlink.StructNlMsgHdr.NLM_F_REQUEST;
import static android.net.netlink.StructNlMsgHdr.NLM_F_ACK;
import static android.net.netlink.StructNlMsgHdr.NLM_F_REPLACE;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.net.netlink.NetlinkConstants;
import android.net.netlink.NetlinkErrorMessage;
import android.net.netlink.NetlinkMessage;
import android.net.netlink.StructNlMsgErr;
import android.support.test.runner.AndroidJUnit4;
import android.support.test.filters.SmallTest;
import android.util.Log;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

import org.junit.runner.RunWith;
import org.junit.Test;

import libcore.util.HexEncoding;


@RunWith(AndroidJUnit4.class)
@SmallTest
public class NetlinkErrorMessageTest {
    private final String TAG = "NetlinkErrorMessageTest";

    // Hexadecimal representation of packet capture.
    public static final String NLM_ERROR_OK_HEX =
            // struct nlmsghdr
            "24000000" +     // length = 36
            "0200"     +     // type = 2 (NLMSG_ERROR)
            "0000"     +     // flags
            "26350000" +     // seqno
            "64100000" +     // pid = userspace process
            // error integer
            "00000000" +     // "errno" (0 == OK)
            // struct nlmsghdr
            "30000000" +     // length (48) of original request
            "1C00"     +     // type = 28 (RTM_NEWNEIGH)
            "0501"     +     // flags (NLM_F_REQUEST | NLM_F_ACK | NLM_F_REPLACE)
            "26350000" +     // seqno
            "00000000";      // pid = kernel
    public static final byte[] NLM_ERROR_OK =
            HexEncoding.decode(NLM_ERROR_OK_HEX.toCharArray(), false);

    @Test
    public void testParseNlmErrorOk() {
        final ByteBuffer byteBuffer = ByteBuffer.wrap(NLM_ERROR_OK);
        byteBuffer.order(ByteOrder.LITTLE_ENDIAN);  // For testing.
        final NetlinkMessage msg = NetlinkMessage.parse(byteBuffer);
        assertNotNull(msg);
        assertTrue(msg instanceof NetlinkErrorMessage);
        final NetlinkErrorMessage errorMsg = (NetlinkErrorMessage) msg;

        final StructNlMsgHdr hdr = errorMsg.getHeader();
        assertNotNull(hdr);
        assertEquals(36, hdr.nlmsg_len);
        assertEquals(NetlinkConstants.NLMSG_ERROR, hdr.nlmsg_type);
        assertEquals(0, hdr.nlmsg_flags);
        assertEquals(13606, hdr.nlmsg_seq);
        assertEquals(4196, hdr.nlmsg_pid);

        final StructNlMsgErr err = errorMsg.getNlMsgError();
        assertNotNull(err);
        assertEquals(0, err.error);
        assertNotNull(err.msg);
        assertEquals(48, err.msg.nlmsg_len);
        assertEquals(NetlinkConstants.RTM_NEWNEIGH, err.msg.nlmsg_type);
        assertEquals((NLM_F_REQUEST | NLM_F_ACK | NLM_F_REPLACE), err.msg.nlmsg_flags);
        assertEquals(13606, err.msg.nlmsg_seq);
        assertEquals(0, err.msg.nlmsg_pid);
    }
}
