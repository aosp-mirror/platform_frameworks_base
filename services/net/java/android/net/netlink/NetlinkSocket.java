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

import android.system.ErrnoException;
import android.system.NetlinkSocketAddress;
import android.system.Os;
import android.system.OsConstants;
import android.system.StructTimeval;
import android.util.Log;
import libcore.io.IoUtils;
import libcore.io.Libcore;

import java.io.Closeable;
import java.io.FileDescriptor;
import java.io.InterruptedIOException;
import java.net.SocketAddress;
import java.net.SocketException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;


/**
 * NetlinkSocket
 *
 * A small wrapper class to assist with AF_NETLINK socket operations.
 *
 * @hide
 */
public class NetlinkSocket implements Closeable {
    private static final String TAG = "NetlinkSocket";
    private static final int SOCKET_RECV_BUFSIZE = 64 * 1024;
    private static final int DEFAULT_RECV_BUFSIZE = 8 * 1024;

    final private FileDescriptor mDescriptor;
    private NetlinkSocketAddress mAddr;
    private long mLastRecvTimeoutMs;
    private long mLastSendTimeoutMs;

    public static void sendOneShotKernelMessage(int nlProto, byte[] msg) throws ErrnoException {
        final String errPrefix = "Error in NetlinkSocket.sendOneShotKernelMessage";

        try (NetlinkSocket nlSocket = new NetlinkSocket(nlProto)) {
            final long IO_TIMEOUT = 300L;
            nlSocket.connectToKernel();
            nlSocket.sendMessage(msg, 0, msg.length, IO_TIMEOUT);
            final ByteBuffer bytes = nlSocket.recvMessage(IO_TIMEOUT);
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
                throw new ErrnoException(errmsg, OsConstants.EPROTO);
            }
        } catch (InterruptedIOException e) {
            Log.e(TAG, errPrefix, e);
            throw new ErrnoException(errPrefix, OsConstants.ETIMEDOUT, e);
        } catch (SocketException e) {
            Log.e(TAG, errPrefix, e);
            throw new ErrnoException(errPrefix, OsConstants.EIO, e);
        }
    }

    public NetlinkSocket(int nlProto) throws ErrnoException {
        mDescriptor = Os.socket(
                OsConstants.AF_NETLINK, OsConstants.SOCK_DGRAM, nlProto);

        Libcore.os.setsockoptInt(
                mDescriptor, OsConstants.SOL_SOCKET,
                OsConstants.SO_RCVBUF, SOCKET_RECV_BUFSIZE);
    }

    public NetlinkSocketAddress getLocalAddress() throws ErrnoException {
        return (NetlinkSocketAddress) Os.getsockname(mDescriptor);
    }

    public void bind(NetlinkSocketAddress localAddr) throws ErrnoException, SocketException {
        Os.bind(mDescriptor, (SocketAddress)localAddr);
    }

    public void connectTo(NetlinkSocketAddress peerAddr)
            throws ErrnoException, SocketException {
        Os.connect(mDescriptor, (SocketAddress) peerAddr);
    }

    public void connectToKernel() throws ErrnoException, SocketException {
        connectTo(new NetlinkSocketAddress(0, 0));
    }

    /**
     * Wait indefinitely (or until underlying socket error) for a
     * netlink message of at most DEFAULT_RECV_BUFSIZE size.
     */
    public ByteBuffer recvMessage()
            throws ErrnoException, InterruptedIOException {
        return recvMessage(DEFAULT_RECV_BUFSIZE, 0);
    }

    /**
     * Wait up to |timeoutMs| (or until underlying socket error) for a
     * netlink message of at most DEFAULT_RECV_BUFSIZE size.
     */
    public ByteBuffer recvMessage(long timeoutMs) throws ErrnoException, InterruptedIOException {
        return recvMessage(DEFAULT_RECV_BUFSIZE, timeoutMs);
    }

    private void checkTimeout(long timeoutMs) {
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
    public ByteBuffer recvMessage(int bufsize, long timeoutMs)
            throws ErrnoException, IllegalArgumentException, InterruptedIOException {
        checkTimeout(timeoutMs);

        synchronized (mDescriptor) {
            if (mLastRecvTimeoutMs != timeoutMs) {
                Os.setsockoptTimeval(mDescriptor,
                        OsConstants.SOL_SOCKET, OsConstants.SO_RCVTIMEO,
                        StructTimeval.fromMillis(timeoutMs));
                mLastRecvTimeoutMs = timeoutMs;
            }
        }

        ByteBuffer byteBuffer = ByteBuffer.allocate(bufsize);
        int length = Os.read(mDescriptor, byteBuffer);
        if (length == bufsize) {
            Log.w(TAG, "maximum read");
        }
        byteBuffer.position(0);
        byteBuffer.limit(length);
        byteBuffer.order(ByteOrder.nativeOrder());
        return byteBuffer;
    }

    /**
     * Send a message to a peer to which this socket has previously connected.
     *
     * This blocks until completion or an error occurs.
     */
    public boolean sendMessage(byte[] bytes, int offset, int count)
            throws ErrnoException, InterruptedIOException {
        return sendMessage(bytes, offset, count, 0);
    }

    /**
     * Send a message to a peer to which this socket has previously connected,
     * waiting at most |timeoutMs| milliseconds for the send to complete.
     *
     * Multi-threaded calls with different timeouts will cause unexpected results.
     */
    public boolean sendMessage(byte[] bytes, int offset, int count, long timeoutMs)
            throws ErrnoException, IllegalArgumentException, InterruptedIOException {
        checkTimeout(timeoutMs);

        synchronized (mDescriptor) {
            if (mLastSendTimeoutMs != timeoutMs) {
                Os.setsockoptTimeval(mDescriptor,
                        OsConstants.SOL_SOCKET, OsConstants.SO_SNDTIMEO,
                        StructTimeval.fromMillis(timeoutMs));
                mLastSendTimeoutMs = timeoutMs;
            }
        }

        return (count == Os.write(mDescriptor, bytes, offset, count));
    }

    @Override
    public void close() {
        IoUtils.closeQuietly(mDescriptor);
    }
}
