/*
 * Copyright (C) 2020 The Android Open Source Project
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

package com.android.networkstack.tethering;

import static android.net.netlink.NetlinkSocket.DEFAULT_RECV_BUFSIZE;
import static android.net.netlink.StructNlMsgHdr.NLM_F_DUMP;
import static android.net.netlink.StructNlMsgHdr.NLM_F_REQUEST;

import static com.android.networkstack.tethering.OffloadHardwareInterface.IPCTNL_MSG_CT_GET;
import static com.android.networkstack.tethering.OffloadHardwareInterface.IPCTNL_MSG_CT_NEW;
import static com.android.networkstack.tethering.OffloadHardwareInterface.NFNL_SUBSYS_CTNETLINK;
import static com.android.networkstack.tethering.OffloadHardwareInterface.NF_NETLINK_CONNTRACK_DESTROY;
import static com.android.networkstack.tethering.OffloadHardwareInterface.NF_NETLINK_CONNTRACK_NEW;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.net.netlink.StructNlMsgHdr;
import android.net.util.SharedLog;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.NativeHandle;
import android.system.Os;

import androidx.test.filters.SmallTest;
import androidx.test.runner.AndroidJUnit4;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.MockitoAnnotations;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketAddress;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

@RunWith(AndroidJUnit4.class)
@SmallTest
public class ConntrackSocketTest {
    private static final long TIMEOUT = 500;

    private HandlerThread mHandlerThread;
    private Handler mHandler;
    private final SharedLog mLog = new SharedLog("privileged-test");

    private OffloadHardwareInterface mOffloadHw;
    private OffloadHardwareInterface.Dependencies mDeps;

    @Before
    public void setUp() throws Exception {
        MockitoAnnotations.initMocks(this);

        mHandlerThread = new HandlerThread(getClass().getSimpleName());
        mHandlerThread.start();
        mHandler = new Handler(mHandlerThread.getLooper());

        // Looper must be prepared here since AndroidJUnitRunner runs tests on separate threads.
        if (Looper.myLooper() == null) Looper.prepare();

        mDeps = new OffloadHardwareInterface.Dependencies(mLog);
        mOffloadHw = new OffloadHardwareInterface(mHandler, mLog, mDeps);
    }

    @Test
    public void testIpv4ConntrackSocket() throws Exception {
        // Set up server and connect.
        final InetSocketAddress anyAddress = new InetSocketAddress(
                InetAddress.getByName("127.0.0.1"), 0);
        final ServerSocket serverSocket = new ServerSocket();
        serverSocket.bind(anyAddress);
        final SocketAddress theAddress = serverSocket.getLocalSocketAddress();

        // Make a connection to the server.
        final Socket socket = new Socket();
        socket.connect(theAddress);
        final Socket acceptedSocket = serverSocket.accept();

        final NativeHandle handle = mDeps.createConntrackSocket(
                NF_NETLINK_CONNTRACK_NEW | NF_NETLINK_CONNTRACK_DESTROY);
        mOffloadHw.sendIpv4NfGenMsg(handle,
                (short) ((NFNL_SUBSYS_CTNETLINK << 8) | IPCTNL_MSG_CT_GET),
                (short) (NLM_F_REQUEST | NLM_F_DUMP));

        boolean foundConntrackEntry = false;
        ByteBuffer buffer = ByteBuffer.allocate(DEFAULT_RECV_BUFSIZE);
        buffer.order(ByteOrder.nativeOrder());

        try {
            while (Os.read(handle.getFileDescriptor(), buffer) > 0) {
                buffer.flip();

                // TODO: ConntrackMessage should get a parse API like StructNlMsgHdr
                // so we can confirm that the conntrack added is for the TCP connection above.
                final StructNlMsgHdr nlmsghdr = StructNlMsgHdr.parse(buffer);
                assertNotNull(nlmsghdr);

                // As long as 1 conntrack entry is found test case will pass, even if it's not
                // the from the TCP connection above.
                if (nlmsghdr.nlmsg_type == ((NFNL_SUBSYS_CTNETLINK << 8) | IPCTNL_MSG_CT_NEW)) {
                    foundConntrackEntry = true;
                    break;
                }
            }
        } finally {
            socket.close();
            serverSocket.close();
        }
        assertTrue("Did not receive any NFNL_SUBSYS_CTNETLINK/IPCTNL_MSG_CT_NEW message",
                foundConntrackEntry);
    }
}
