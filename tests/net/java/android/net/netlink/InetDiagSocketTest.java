/*
 * Copyright (C) 2018 The Android Open Source Project
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

import static android.net.netlink.StructNlMsgHdr.NLM_F_DUMP;
import static android.net.netlink.StructNlMsgHdr.NLM_F_REQUEST;
import static android.os.Process.INVALID_UID;
import static android.system.OsConstants.AF_INET;
import static android.system.OsConstants.AF_INET6;
import static android.system.OsConstants.IPPROTO_TCP;
import static android.system.OsConstants.IPPROTO_UDP;
import static android.system.OsConstants.SOCK_DGRAM;
import static android.system.OsConstants.SOCK_STREAM;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.app.Instrumentation;
import android.content.Context;
import android.net.ConnectivityManager;
import android.net.netlink.StructNlMsgHdr;
import android.os.Process;
import android.system.Os;

import androidx.test.InstrumentationRegistry;
import androidx.test.filters.SmallTest;
import androidx.test.runner.AndroidJUnit4;

import libcore.util.HexEncoding;

import org.junit.Before;
import org.junit.Ignore;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.FileDescriptor;
import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

@RunWith(AndroidJUnit4.class)
@SmallTest
public class InetDiagSocketTest {
    private final String TAG = "InetDiagSocketTest";
    private ConnectivityManager mCm;
    private Context mContext;
    private final static int SOCKET_TIMEOUT_MS = 100;

    @Before
    public void setUp() throws Exception {
        Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();
        mContext = instrumentation.getTargetContext();
        mCm = (ConnectivityManager) mContext.getSystemService(Context.CONNECTIVITY_SERVICE);
    }

    private class Connection {
        public int socketDomain;
        public int socketType;
        public InetAddress localAddress;
        public InetAddress remoteAddress;
        public InetAddress localhostAddress;
        public InetSocketAddress local;
        public InetSocketAddress remote;
        public int protocol;
        public FileDescriptor localFd;
        public FileDescriptor remoteFd;

        public FileDescriptor createSocket() throws Exception {
            return Os.socket(socketDomain, socketType, protocol);
        }

        public Connection(String to, String from) throws Exception {
            remoteAddress = InetAddress.getByName(to);
            if (from != null) {
                localAddress = InetAddress.getByName(from);
            } else {
                localAddress = (remoteAddress instanceof Inet4Address) ?
                        Inet4Address.getByName("localhost") : Inet6Address.getByName("::");
            }
            if ((localAddress instanceof Inet4Address) && (remoteAddress instanceof Inet4Address)) {
                socketDomain = AF_INET;
                localhostAddress = Inet4Address.getByName("localhost");
            } else {
                socketDomain = AF_INET6;
                localhostAddress = Inet6Address.getByName("::");
            }
        }

        public void close() throws Exception {
            Os.close(localFd);
        }
    }

    private class TcpConnection extends Connection {
        public TcpConnection(String to, String from) throws Exception {
            super(to, from);
            protocol = IPPROTO_TCP;
            socketType = SOCK_STREAM;

            remoteFd = createSocket();
            Os.bind(remoteFd, remoteAddress, 0);
            Os.listen(remoteFd, 10);
            int remotePort = ((InetSocketAddress) Os.getsockname(remoteFd)).getPort();

            localFd = createSocket();
            Os.bind(localFd, localAddress, 0);
            Os.connect(localFd, remoteAddress, remotePort);

            local = (InetSocketAddress) Os.getsockname(localFd);
            remote = (InetSocketAddress) Os.getpeername(localFd);
        }

        public void close() throws Exception {
            super.close();
            Os.close(remoteFd);
        }
    }
    private class UdpConnection extends Connection {
        public UdpConnection(String to, String from) throws Exception {
            super(to, from);
            protocol = IPPROTO_UDP;
            socketType = SOCK_DGRAM;

            remoteFd = null;
            localFd = createSocket();
            Os.bind(localFd, localAddress, 0);

            Os.connect(localFd, remoteAddress, 7);
            local = (InetSocketAddress) Os.getsockname(localFd);
            remote = new InetSocketAddress(remoteAddress, 7);
        }
    }

    private void checkConnectionOwnerUid(int protocol, InetSocketAddress local,
                                         InetSocketAddress remote, boolean expectSuccess) {
        final int expectedUid = expectSuccess ? Process.myUid() : INVALID_UID;
        final int uid = mCm.getConnectionOwnerUid(protocol, local, remote);
        assertEquals(expectedUid, uid);
    }

    private int findLikelyFreeUdpPort(UdpConnection conn) throws Exception {
        UdpConnection udp = new UdpConnection(conn.remoteAddress.getHostAddress(),
                conn.localAddress.getHostAddress());
        final int localPort = udp.local.getPort();
        udp.close();
        return localPort;
    }

    public void checkGetConnectionOwnerUid(String to, String from) throws Exception {
        /**
         * For TCP connections, create a test connection and verify that this
         * {protocol, local, remote} socket result in receiving a valid UID.
         */
        TcpConnection tcp = new TcpConnection(to, from);
        checkConnectionOwnerUid(tcp.protocol, tcp.local, tcp.remote, true);
        checkConnectionOwnerUid(IPPROTO_UDP, tcp.local, tcp.remote, false);
        checkConnectionOwnerUid(tcp.protocol, new InetSocketAddress(0), tcp.remote, false);
        checkConnectionOwnerUid(tcp.protocol, tcp.local, new InetSocketAddress(0), false);
        tcp.close();

        /**
         * For UDP connections, either a complete match {protocol, local, remote} or a
         * partial match {protocol, local} should return a valid UID.
         */
        UdpConnection udp = new UdpConnection(to,from);
        checkConnectionOwnerUid(udp.protocol, udp.local, udp.remote, true);
        checkConnectionOwnerUid(udp.protocol, udp.local, new InetSocketAddress(0), true);
        checkConnectionOwnerUid(IPPROTO_TCP, udp.local, udp.remote, false);
        checkConnectionOwnerUid(udp.protocol, new InetSocketAddress(findLikelyFreeUdpPort(udp)),
                udp.remote, false);
        udp.close();
    }

    @Ignore
    @Test
    public void testGetConnectionOwnerUid() throws Exception {
        checkGetConnectionOwnerUid("::", null);
        checkGetConnectionOwnerUid("::", "::");
        checkGetConnectionOwnerUid("0.0.0.0", null);
        checkGetConnectionOwnerUid("0.0.0.0", "0.0.0.0");
        checkGetConnectionOwnerUid("127.0.0.1", null);
        checkGetConnectionOwnerUid("127.0.0.1", "127.0.0.2");
        checkGetConnectionOwnerUid("::1", null);
        checkGetConnectionOwnerUid("::1", "::1");
    }

    // Hexadecimal representation of InetDiagReqV2 request.
    private static final String INET_DIAG_REQ_V2_UDP_INET4_HEX =
            // struct nlmsghdr
            "48000000" +     // length = 72
            "1400" +         // type = SOCK_DIAG_BY_FAMILY
            "0103" +         // flags = NLM_F_REQUEST | NLM_F_DUMP
            "00000000" +     // seqno
            "00000000" +     // pid (0 == kernel)
            // struct inet_diag_req_v2
            "02" +           // family = AF_INET
            "11" +           // protcol = IPPROTO_UDP
            "00" +           // idiag_ext
            "00" +           // pad
            "ffffffff" +     // idiag_states
            // inet_diag_sockid
            "a5de" +         // idiag_sport = 42462
            "b971" +         // idiag_dport = 47473
            "0a006402000000000000000000000000" + // idiag_src = 10.0.100.2
            "08080808000000000000000000000000" + // idiag_dst = 8.8.8.8
            "00000000" +     // idiag_if
            "ffffffffffffffff"; // idiag_cookie = INET_DIAG_NOCOOKIE
    private static final byte[] INET_DIAG_REQ_V2_UDP_INET4_BYTES =
            HexEncoding.decode(INET_DIAG_REQ_V2_UDP_INET4_HEX.toCharArray(), false);

    @Test
    public void testInetDiagReqV2UdpInet4() throws Exception {
        InetSocketAddress local = new InetSocketAddress(InetAddress.getByName("10.0.100.2"),
                42462);
        InetSocketAddress remote = new InetSocketAddress(InetAddress.getByName("8.8.8.8"),
                47473);
        final byte[] msg = InetDiagMessage.InetDiagReqV2(IPPROTO_UDP, local, remote, AF_INET,
                (short) (NLM_F_REQUEST | NLM_F_DUMP));
        assertArrayEquals(INET_DIAG_REQ_V2_UDP_INET4_BYTES, msg);
    }

    // Hexadecimal representation of InetDiagReqV2 request.
    private static final String INET_DIAG_REQ_V2_TCP_INET6_HEX =
            // struct nlmsghdr
            "48000000" +     // length = 72
            "1400" +         // type = SOCK_DIAG_BY_FAMILY
            "0100" +         // flags = NLM_F_REQUEST
            "00000000" +     // seqno
            "00000000" +     // pid (0 == kernel)
            // struct inet_diag_req_v2
            "0a" +           // family = AF_INET6
            "06" +           // protcol = IPPROTO_TCP
            "00" +           // idiag_ext
            "00" +           // pad
            "ffffffff" +     // idiag_states
                // inet_diag_sockid
                "a5de" +         // idiag_sport = 42462
                "b971" +         // idiag_dport = 47473
                "fe8000000000000086c9b2fffe6aed4b" + // idiag_src = fe80::86c9:b2ff:fe6a:ed4b
                "08080808000000000000000000000000" + // idiag_dst = 8.8.8.8
                "00000000" +     // idiag_if
                "ffffffffffffffff"; // idiag_cookie = INET_DIAG_NOCOOKIE
    private static final byte[] INET_DIAG_REQ_V2_TCP_INET6_BYTES =
            HexEncoding.decode(INET_DIAG_REQ_V2_TCP_INET6_HEX.toCharArray(), false);

    @Test
    public void testInetDiagReqV2TcpInet6() throws Exception {
        InetSocketAddress local = new InetSocketAddress(
                InetAddress.getByName("fe80::86c9:b2ff:fe6a:ed4b"), 42462);
        InetSocketAddress remote = new InetSocketAddress(InetAddress.getByName("8.8.8.8"),
                47473);
        byte[] msg = InetDiagMessage.InetDiagReqV2(IPPROTO_TCP, local, remote, AF_INET6,
                NLM_F_REQUEST);

        assertArrayEquals(INET_DIAG_REQ_V2_TCP_INET6_BYTES, msg);
    }

    // Hexadecimal representation of InetDiagReqV2 request.
    private static final String INET_DIAG_MSG_HEX =
            // struct nlmsghdr
            "58000000" +     // length = 88
            "1400" +         // type = SOCK_DIAG_BY_FAMILY
            "0200" +         // flags = NLM_F_MULTI
            "00000000" +     // seqno
            "f5220000" +     // pid (0 == kernel)
            // struct inet_diag_msg
            "0a" +           // family = AF_INET6
            "01" +           // idiag_state
            "00" +           // idiag_timer
            "00" +           // idiag_retrans
                // inet_diag_sockid
                "a817" +     // idiag_sport = 43031
                "960f" +     // idiag_dport = 38415
                "fe8000000000000086c9b2fffe6aed4b" + // idiag_src = fe80::86c9:b2ff:fe6a:ed4b
                "00000000000000000000ffff08080808" + // idiag_dst = 8.8.8.8
                "00000000" + // idiag_if
                "ffffffffffffffff" + // idiag_cookie = INET_DIAG_NOCOOKIE
            "00000000" +     // idiag_expires
            "00000000" +     // idiag_rqueue
            "00000000" +     // idiag_wqueue
            "a3270000" +     // idiag_uid
            "A57E1900";      // idiag_inode
    private static final byte[] INET_DIAG_MSG_BYTES =
            HexEncoding.decode(INET_DIAG_MSG_HEX.toCharArray(), false);

    @Test
    public void testParseInetDiagResponse() throws Exception {
        final ByteBuffer byteBuffer = ByteBuffer.wrap(INET_DIAG_MSG_BYTES);
        byteBuffer.order(ByteOrder.LITTLE_ENDIAN);
        final NetlinkMessage msg = NetlinkMessage.parse(byteBuffer);
        assertNotNull(msg);

        assertTrue(msg instanceof InetDiagMessage);
        final InetDiagMessage inetDiagMsg = (InetDiagMessage) msg;
        assertEquals(10147, inetDiagMsg.mStructInetDiagMsg.idiag_uid);

        final StructNlMsgHdr hdr = inetDiagMsg.getHeader();
        assertNotNull(hdr);
        assertEquals(NetlinkConstants.SOCK_DIAG_BY_FAMILY, hdr.nlmsg_type);
        assertEquals(StructNlMsgHdr.NLM_F_MULTI, hdr.nlmsg_flags);
        assertEquals(0, hdr.nlmsg_seq);
        assertEquals(8949, hdr.nlmsg_pid);
    }
}
