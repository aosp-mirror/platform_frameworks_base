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

import static android.net.util.SocketUtils.makeNetlinkSocketAddress;
import static android.system.OsConstants.AF_NETLINK;
import static android.system.OsConstants.EIO;
import static android.system.OsConstants.EPROTO;
import static android.system.OsConstants.ETIMEDOUT;
import static android.system.OsConstants.SOCK_DGRAM;
import static android.system.OsConstants.SOL_SOCKET;
import static android.system.OsConstants.SO_RCVBUF;
import static android.system.OsConstants.SO_RCVTIMEO;
import static android.system.OsConstants.SO_SNDTIMEO;

import android.net.util.SocketUtils;
import android.system.ErrnoException;
import android.system.Os;
import android.util.Log;

import java.io.FileDescriptor;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.net.SocketException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;


/**
 * NetlinkSocket
 *
 * A small static class to assist with AF_NETLINK socket operations.
 *
 * @hide
 */
public class NetlinkSocket {
    private static final String TAG = "NetlinkSocket";

    public static final int DEFAULT_RECV_BUFSIZE = 8 * 1024;
    public static final int SOCKET_RECV_BUFSIZE = 64 * 1024;

    public static void sendOneShotKernelMessage(int nlProto, byte[] msg) throws ErrnoException {
        final String errPrefix = "Error in NetlinkSocket.sendOneShotKernelMessage";
        final long IO_TIMEOUT = 300L;

        final FileDescriptor fd = forProto(nlProto);

        try {
            connectToKernel(fd);
            sendMessage(fd, msg, 0, msg.length, IO_TIMEOUT);
            final ByteBuffer bytes = recvMessage(fd, DEFAULT_RECV_BUFSIZE, IO_TIMEOUT);
            // recvMessage() guaranteed to not return null if it did not throw.
            final NetlinkMessage response = NetlinkMessage.parse(bytes);
            if (response != null && response instanceof NetlinkErrorMessage &&
                    (((NetlinkErrorMessage) response).getNlMsgError() != null)) {
                final int errno = ((NetlinkErrorMessage) response).getNlMsgError().error;
                if (errno != 0) {
                    // TODO: consider ignoring EINVAL (-22), which appears to be
                    // normal when probing a neighbor for which the kernel does
                    // not already have / no longer has a link layer address.
                    Log.e(TAG, errPrefix + ", errmsg=" + response.toString());
                    // Note: convert kernel errnos (negative) into userspace errnos (positive).
                    throw new ErrnoException(response.toString(), Math.abs(errno));
                }
            } else {
                final String errmsg;
                if (response == null) {
                    bytes.position(0);
                    errmsg = "raw bytes: " + NetlinkConstants.hexify(bytes);
                } else {
                    errmsg = response.toString();
                }
                Log.e(TAG, errPrefix + ", errmsg=" + errmsg);
                throw new ErrnoException(errmsg, EPROTO);
            }
        } catch (InterruptedIOException e) {
            Log.e(TAG, errPrefix, e);
            throw new ErrnoException(errPrefix, ETIMEDOUT, e);
        } catch (SocketException e) {
            Log.e(TAG, errPrefix, e);
            throw new ErrnoException(errPrefix, EIO, e);
        } finally {
            try {
                SocketUtils.closeSocket(fd);
            } catch (IOException e) {
                // Nothing we can do here
            }
        }
    }

    public static FileDescriptor forProto(int nlProto) throws ErrnoException {
        final FileDescriptor fd = Os.socket(AF_NETLINK, SOCK_DGRAM, nlProto);
        Os.setsockoptInt(fd, SOL_SOCKET, SO_RCVBUF, SOCKET_RECV_BUFSIZE);
        return fd;
    }

    public static void connectToKernel(FileDescriptor fd) throws ErrnoException, SocketException {
        SocketUtils.connectSocket(fd, makeNetlinkSocketAddress(0, 0));
    }

    private static void checkTimeout(long timeoutMs) {
        if (timeoutMs < 0) {
            throw new IllegalArgumentException("Negative timeouts not permitted");
        }
    }

    /**
     * Wait up to |timeoutMs| (or until underlying socket error) for a
     * netlink message of at most |bufsize| size.
     *
     * Multi-threaded calls with different timeouts will cause unexpected results.
     */
    public static ByteBuffer recvMessage(FileDescriptor fd, int bufsize, long timeoutMs)
            throws ErrnoException, IllegalArgumentException, InterruptedIOException {
        checkTimeout(timeoutMs);

        SocketUtils.setSocketTimeValueOption(fd, SOL_SOCKET, SO_RCVTIMEO, timeoutMs);

        ByteBuffer byteBuffer = ByteBuffer.allocate(bufsize);
        int length = Os.read(fd, byteBuffer);
        if (length == bufsize) {
            Log.w(TAG, "maximum read");
        }
        byteBuffer.position(0);
        byteBuffer.limit(length);
        byteBuffer.order(ByteOrder.nativeOrder());
        return byteBuffer;
    }

    /**
     * Send a message to a peer to which this socket has previously connected,
     * waiting at most |timeoutMs| milliseconds for the send to complete.
     *
     * Multi-threaded calls with different timeouts will cause unexpected results.
     */
    public static int sendMessage(
            FileDescriptor fd, byte[] bytes, int offset, int count, long timeoutMs)
            throws ErrnoException, IllegalArgumentException, InterruptedIOException {
        checkTimeout(timeoutMs);
        SocketUtils.setSocketTimeValueOption(fd, SOL_SOCKET, SO_SNDTIMEO, timeoutMs);
        return Os.write(fd, bytes, offset, count);
    }
}
