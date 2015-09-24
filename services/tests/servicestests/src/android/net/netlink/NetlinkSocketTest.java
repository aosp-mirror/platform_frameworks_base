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

import android.net.netlink.NetlinkSocket;
import android.net.netlink.RtNetlinkNeighborMessage;
import android.net.netlink.StructNdMsg;
import android.net.netlink.StructNlMsgHdr;
import android.system.ErrnoException;
import android.system.NetlinkSocketAddress;
import android.system.OsConstants;
import android.util.Log;
import java.io.InterruptedIOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import junit.framework.TestCase;


public class NetlinkSocketTest extends TestCase {
    private final String TAG = "NetlinkSocketTest";

    public void testBasicWorkingGetNeighborsQuery() throws Exception {
        NetlinkSocket s = new NetlinkSocket(OsConstants.NETLINK_ROUTE);
        assertNotNull(s);

        s.connectToKernel();

        NetlinkSocketAddress localAddr = s.getLocalAddress();
        assertNotNull(localAddr);
        assertEquals(0, localAddr.getGroupsMask());
        assertTrue(0 != localAddr.getPortId());

        final int TEST_SEQNO = 5;
        final byte[] request = RtNetlinkNeighborMessage.newGetNeighborsRequest(TEST_SEQNO);
        assertNotNull(request);

        final long TIMEOUT = 500;
        assertTrue(s.sendMessage(request, 0, request.length, TIMEOUT));

        int neighMessageCount = 0;
        int doneMessageCount = 0;

        while (doneMessageCount == 0) {
            ByteBuffer response = null;
            response = s.recvMessage(TIMEOUT);
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

        s.close();
    }

    public void testRepeatedCloseCallsAreQuiet() throws Exception {
        // Create a working NetlinkSocket.
        NetlinkSocket s = new NetlinkSocket(OsConstants.NETLINK_ROUTE);
        assertNotNull(s);
        s.connectToKernel();
        NetlinkSocketAddress localAddr = s.getLocalAddress();
        assertNotNull(localAddr);
        assertEquals(0, localAddr.getGroupsMask());
        assertTrue(0 != localAddr.getPortId());
        // Close once.
        s.close();
        // Test that it is closed.
        boolean expectedErrorSeen = false;
        try {
            localAddr = s.getLocalAddress();
        } catch (ErrnoException e) {
            expectedErrorSeen = true;
        }
        assertTrue(expectedErrorSeen);
        // Close once more.
        s.close();
    }
}
