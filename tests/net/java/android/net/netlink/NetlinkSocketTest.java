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

import static android.net.netlink.NetlinkSocket.DEFAULT_RECV_BUFSIZE;
import static android.system.OsConstants.NETLINK_ROUTE;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.net.netlink.NetlinkSocket;
import android.net.netlink.RtNetlinkNeighborMessage;
import android.net.netlink.StructNdMsg;
import android.net.netlink.StructNlMsgHdr;
import android.support.test.runner.AndroidJUnit4;
import android.support.test.filters.SmallTest;
import android.system.ErrnoException;
import android.system.NetlinkSocketAddress;
import android.system.Os;
import android.util.Log;
import libcore.io.IoUtils;

import java.io.InterruptedIOException;
import java.io.FileDescriptor;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

import org.junit.runner.RunWith;
import org.junit.Test;


@RunWith(AndroidJUnit4.class)
@SmallTest
public class NetlinkSocketTest {
    private final String TAG = "NetlinkSocketTest";

    @Test
    public void testBasicWorkingGetNeighborsQuery() throws Exception {
        final FileDescriptor fd = NetlinkSocket.forProto(NETLINK_ROUTE);
        assertNotNull(fd);

        NetlinkSocket.connectToKernel(fd);

        final NetlinkSocketAddress localAddr = (NetlinkSocketAddress) Os.getsockname(fd);
        assertNotNull(localAddr);
        assertEquals(0, localAddr.getGroupsMask());
        assertTrue(0 != localAddr.getPortId());

        final int TEST_SEQNO = 5;
        final byte[] req = RtNetlinkNeighborMessage.newGetNeighborsRequest(TEST_SEQNO);
        assertNotNull(req);

        final long TIMEOUT = 500;
        assertEquals(req.length, NetlinkSocket.sendMessage(fd, req, 0, req.length, TIMEOUT));

        int neighMessageCount = 0;
        int doneMessageCount = 0;

        while (doneMessageCount == 0) {
            ByteBuffer response = NetlinkSocket.recvMessage(fd, DEFAULT_RECV_BUFSIZE, TIMEOUT);
            assertNotNull(response);
            assertTrue(StructNlMsgHdr.STRUCT_SIZE <= response.limit());
            assertEquals(0, response.position());
            assertEquals(ByteOrder.nativeOrder(), response.order());

            // Verify the messages at least appears minimally reasonable.
            while (response.remaining() > 0) {
                final NetlinkMessage msg = NetlinkMessage.parse(response);
                assertNotNull(msg);
                final StructNlMsgHdr hdr = msg.getHeader();
                assertNotNull(hdr);

                if (hdr.nlmsg_type == NetlinkConstants.NLMSG_DONE) {
                    doneMessageCount++;
                    continue;
                }

                assertEquals(NetlinkConstants.RTM_NEWNEIGH, hdr.nlmsg_type);
                assertTrue(msg instanceof RtNetlinkNeighborMessage);
                assertTrue((hdr.nlmsg_flags & StructNlMsgHdr.NLM_F_MULTI) != 0);
                assertEquals(TEST_SEQNO, hdr.nlmsg_seq);
                assertEquals(localAddr.getPortId(), hdr.nlmsg_pid);

                neighMessageCount++;
            }
        }

        assertEquals(1, doneMessageCount);
        // TODO: make sure this test passes sanely in airplane mode.
        assertTrue(neighMessageCount > 0);

        IoUtils.closeQuietly(fd);
    }
}
