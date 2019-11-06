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

import static android.net.netlink.NetlinkConstants.SOCK_DIAG_BY_FAMILY;
import static android.net.netlink.NetlinkSocket.DEFAULT_RECV_BUFSIZE;
import static android.net.netlink.StructNlMsgHdr.NLM_F_DUMP;
import static android.net.netlink.StructNlMsgHdr.NLM_F_REQUEST;
import static android.os.Process.INVALID_UID;
import static android.system.OsConstants.AF_INET;
import static android.system.OsConstants.AF_INET6;
import static android.system.OsConstants.IPPROTO_UDP;
import static android.system.OsConstants.NETLINK_INET_DIAG;

import android.annotation.Nullable;
import android.net.util.SocketUtils;
import android.system.ErrnoException;
import android.util.Log;

import java.io.FileDescriptor;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetSocketAddress;
import java.net.SocketException;
import java.net.UnknownHostException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * A NetlinkMessage subclass for netlink inet_diag messages.
 *
 * see also: &lt;linux_src&gt;/include/uapi/linux/inet_diag.h
 *
 * @hide
 */
public class InetDiagMessage extends NetlinkMessage {
    public static final String TAG = "InetDiagMessage";
    private static final int TIMEOUT_MS = 500;

    public static byte[] InetDiagReqV2(int protocol, InetSocketAddress local,
            InetSocketAddress remote, int family, short flags) {
        return InetDiagReqV2(protocol, local, remote, family, flags, 0 /* pad */,
                0 /* idiagExt */, StructInetDiagReqV2.INET_DIAG_REQ_V2_ALL_STATES);
    }

    /**
     * Construct an inet_diag_req_v2 message. This method will throw {@code NullPointerException}
     * if local and remote are not both null or both non-null.
     *
     * @param protocol the request protocol type. This should be set to one of IPPROTO_TCP,
     *                 IPPROTO_UDP, or IPPROTO_UDPLITE.
     * @param local local socket address of the target socket. This will be packed into a
     *              {@Code StructInetDiagSockId}. Request to diagnose for all sockets if both of
     *              local or remote address is null.
     * @param remote remote socket address of the target socket. This will be packed into a
     *              {@Code StructInetDiagSockId}. Request to diagnose for all sockets if both of
     *              local or remote address is null.
     * @param family the ip family of the request message. This should be set to either AF_INET or
     *               AF_INET6 for IPv4 or IPv6 sockets respectively.
     * @param flags message flags. See &lt;linux_src&gt;/include/uapi/linux/netlink.h.
     * @param pad for raw socket protocol specification.
     * @param idiagExt a set of flags defining what kind of extended information to report.
     * @param state a bit mask that defines a filter of socket states.
     *
     * @return bytes array representation of the message
     **/
    public static byte[] InetDiagReqV2(int protocol, @Nullable InetSocketAddress local,
            @Nullable InetSocketAddress remote, int family, short flags, int pad, int idiagExt,
            int state) throws NullPointerException {
        final byte[] bytes = new byte[StructNlMsgHdr.STRUCT_SIZE + StructInetDiagReqV2.STRUCT_SIZE];
        final ByteBuffer byteBuffer = ByteBuffer.wrap(bytes);
        byteBuffer.order(ByteOrder.nativeOrder());

        final StructNlMsgHdr nlMsgHdr = new StructNlMsgHdr();
        nlMsgHdr.nlmsg_len = bytes.length;
        nlMsgHdr.nlmsg_type = SOCK_DIAG_BY_FAMILY;
        nlMsgHdr.nlmsg_flags = flags;
        nlMsgHdr.pack(byteBuffer);
        final StructInetDiagReqV2 inetDiagReqV2 =
                new StructInetDiagReqV2(protocol, local, remote, family, pad, idiagExt, state);

        inetDiagReqV2.pack(byteBuffer);
        return bytes;
    }

    public StructInetDiagMsg mStructInetDiagMsg;

    private InetDiagMessage(StructNlMsgHdr header) {
        super(header);
        mStructInetDiagMsg = new StructInetDiagMsg();
    }

    public static InetDiagMessage parse(StructNlMsgHdr header, ByteBuffer byteBuffer) {
        final InetDiagMessage msg = new InetDiagMessage(header);
        msg.mStructInetDiagMsg = StructInetDiagMsg.parse(byteBuffer);
        return msg;
    }

    private static int lookupUidByFamily(int protocol, InetSocketAddress local,
                                         InetSocketAddress remote, int family, short flags,
                                         FileDescriptor fd)
            throws ErrnoException, InterruptedIOException {
        byte[] msg = InetDiagReqV2(protocol, local, remote, family, flags);
        NetlinkSocket.sendMessage(fd, msg, 0, msg.length, TIMEOUT_MS);
        ByteBuffer response = NetlinkSocket.recvMessage(fd, DEFAULT_RECV_BUFSIZE, TIMEOUT_MS);

        final NetlinkMessage nlMsg = NetlinkMessage.parse(response);
        final StructNlMsgHdr hdr = nlMsg.getHeader();
        if (hdr.nlmsg_type == NetlinkConstants.NLMSG_DONE) {
            return INVALID_UID;
        }
        if (nlMsg instanceof InetDiagMessage) {
            return ((InetDiagMessage) nlMsg).mStructInetDiagMsg.idiag_uid;
        }
        return INVALID_UID;
    }

    private static final int FAMILY[] = {AF_INET6, AF_INET};

    private static int lookupUid(int protocol, InetSocketAddress local,
                                 InetSocketAddress remote, FileDescriptor fd)
            throws ErrnoException, InterruptedIOException {
        int uid;

        for (int family : FAMILY) {
            /**
             * For exact match lookup, swap local and remote for UDP lookups due to kernel
             * bug which will not be fixed. See aosp/755889 and
             * https://www.mail-archive.com/netdev@vger.kernel.org/msg248638.html
             */
            if (protocol == IPPROTO_UDP) {
                uid = lookupUidByFamily(protocol, remote, local, family, NLM_F_REQUEST, fd);
            } else {
                uid = lookupUidByFamily(protocol, local, remote, family, NLM_F_REQUEST, fd);
            }
            if (uid != INVALID_UID) {
                return uid;
            }
        }

        /**
         * For UDP it's possible for a socket to send packets to arbitrary destinations, even if the
         * socket is not connected (and even if the socket is connected to a different destination).
         * If we want this API to work for such packets, then on miss we need to do a second lookup
         * with only the local address and port filled in.
         * Always use flags == NLM_F_REQUEST | NLM_F_DUMP for wildcard.
         */
        if (protocol == IPPROTO_UDP) {
            try {
                InetSocketAddress wildcard = new InetSocketAddress(
                        Inet6Address.getByName("::"), 0);
                uid = lookupUidByFamily(protocol, local, wildcard, AF_INET6,
                        (short) (NLM_F_REQUEST | NLM_F_DUMP), fd);
                if (uid != INVALID_UID) {
                    return uid;
                }
                wildcard = new InetSocketAddress(Inet4Address.getByName("0.0.0.0"), 0);
                uid = lookupUidByFamily(protocol, local, wildcard, AF_INET,
                        (short) (NLM_F_REQUEST | NLM_F_DUMP), fd);
                if (uid != INVALID_UID) {
                    return uid;
                }
            } catch (UnknownHostException e) {
                Log.e(TAG, e.toString());
            }
        }
        return INVALID_UID;
    }

    /**
     * Use an inet_diag socket to look up the UID associated with the input local and remote
     * address/port and protocol of a connection.
     */
    public static int getConnectionOwnerUid(int protocol, InetSocketAddress local,
                                            InetSocketAddress remote) {
        int uid = INVALID_UID;
        FileDescriptor fd = null;
        try {
            fd = NetlinkSocket.forProto(NETLINK_INET_DIAG);
            NetlinkSocket.connectToKernel(fd);
            uid = lookupUid(protocol, local, remote, fd);
        } catch (ErrnoException | SocketException | IllegalArgumentException
                | InterruptedIOException e) {
            Log.e(TAG, e.toString());
        } finally {
            if (fd != null) {
                try {
                    SocketUtils.closeSocket(fd);
                } catch (IOException e) {
                    Log.e(TAG, e.toString());
                }
            }
        }
        return uid;
    }

    @Override
    public String toString() {
        return "InetDiagMessage{ "
                + "nlmsghdr{" + (mHeader == null ? "" : mHeader.toString()) + "}, "
                + "inet_diag_msg{"
                + (mStructInetDiagMsg == null ? "" : mStructInetDiagMsg.toString()) + "} "
                + "}";
    }
}
