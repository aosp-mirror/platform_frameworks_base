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
package com.android.server.connectivity;

import static android.net.NetworkAgent.EVENT_SOCKET_KEEPALIVE;
import static android.net.SocketKeepalive.DATA_RECEIVED;
import static android.net.SocketKeepalive.ERROR_INVALID_SOCKET;
import static android.net.SocketKeepalive.ERROR_SOCKET_NOT_IDLE;
import static android.os.MessageQueue.OnFileDescriptorEventListener.EVENT_ERROR;
import static android.os.MessageQueue.OnFileDescriptorEventListener.EVENT_INPUT;
import static android.system.OsConstants.FIONREAD;
import static android.system.OsConstants.IPPROTO_TCP;
import static android.system.OsConstants.TIOCOUTQ;

import android.annotation.NonNull;
import android.net.NetworkUtils;
import android.net.SocketKeepalive.InvalidSocketException;
import android.net.TcpKeepalivePacketData.TcpSocketInfo;
import android.net.TcpRepairWindow;
import android.os.Handler;
import android.os.Message;
import android.os.MessageQueue;
import android.os.Messenger;
import android.os.RemoteException;
import android.system.ErrnoException;
import android.system.Int32Ref;
import android.system.Os;
import android.util.Log;
import android.util.SparseArray;

import com.android.internal.annotations.GuardedBy;

import java.io.FileDescriptor;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.net.SocketException;

/**
 * Manage tcp socket which offloads tcp keepalive.
 *
 * The input socket will be changed to repair mode and the application
 * will not have permission to read/write data. If the application wants
 * to write data, it must stop tcp keepalive offload to leave repair mode
 * first. If a remote packet arrives, repair mode will be turned off and
 * offload will be stopped. The application will receive a callback to know
 * it can start reading data.
 *
 * {start,stop}SocketMonitor are thread-safe, but care must be taken in the
 * order in which they are called. Please note that while calling
 * {@link #startSocketMonitor(FileDescriptor, Messenger, int)} multiple times
 * with either the same slot or the same FileDescriptor without stopping it in
 * between will result in an exception, calling {@link #stopSocketMonitor(int)}
 * multiple times with the same int is explicitly a no-op.
 * Please also note that switching the socket to repair mode is not synchronized
 * with either of these operations and has to be done in an orderly fashion
 * with stopSocketMonitor. Take care in calling these in the right order.
 * @hide
 */
public class TcpKeepaliveController {
    private static final String TAG = "TcpKeepaliveController";
    private static final boolean DBG = false;

    private final MessageQueue mFdHandlerQueue;

    private static final int FD_EVENTS = EVENT_INPUT | EVENT_ERROR;

    // Reference include/uapi/linux/tcp.h
    private static final int TCP_REPAIR = 19;
    private static final int TCP_REPAIR_QUEUE = 20;
    private static final int TCP_QUEUE_SEQ = 21;
    private static final int TCP_NO_QUEUE = 0;
    private static final int TCP_RECV_QUEUE = 1;
    private static final int TCP_SEND_QUEUE = 2;
    private static final int TCP_REPAIR_OFF = 0;
    private static final int TCP_REPAIR_ON = 1;
    // Reference include/uapi/linux/sockios.h
    private static final int SIOCINQ = FIONREAD;
    private static final int SIOCOUTQ = TIOCOUTQ;

    /**
     * Keeps track of packet listeners.
     * Key: slot number of keepalive offload.
     * Value: {@link FileDescriptor} being listened to.
     */
    @GuardedBy("mListeners")
    private final SparseArray<FileDescriptor> mListeners = new SparseArray<>();

    public TcpKeepaliveController(final Handler connectivityServiceHandler) {
        mFdHandlerQueue = connectivityServiceHandler.getLooper().getQueue();
    }

    /**
     * Switch the tcp socket to repair mode and query tcp socket information.
     *
     * @param fd the fd of socket on which to use keepalive offload
     * @return a {@link TcpKeepalivePacketData#TcpSocketInfo} object for current
     * tcp/ip information.
     */
    // TODO : make this private. It's far too confusing that this gets called from outside
    // at a time that nobody can understand, but the switch out is in this class only.
    public static TcpSocketInfo switchToRepairMode(FileDescriptor fd)
            throws InvalidSocketException {
        if (DBG) Log.i(TAG, "switchToRepairMode to start tcp keepalive : " + fd);
        final SocketAddress srcSockAddr;
        final SocketAddress dstSockAddr;
        final InetAddress srcAddress;
        final InetAddress dstAddress;
        final int srcPort;
        final int dstPort;
        int seq;
        final int ack;
        final TcpRepairWindow trw;

        // Query source address and port.
        try {
            srcSockAddr = Os.getsockname(fd);
        } catch (ErrnoException e) {
            Log.e(TAG, "Get sockname fail: ", e);
            throw new InvalidSocketException(ERROR_INVALID_SOCKET, e);
        }
        if (srcSockAddr instanceof InetSocketAddress) {
            srcAddress = getAddress((InetSocketAddress) srcSockAddr);
            srcPort = getPort((InetSocketAddress) srcSockAddr);
        } else {
            Log.e(TAG, "Invalid or mismatched SocketAddress");
            throw new InvalidSocketException(ERROR_INVALID_SOCKET);
        }
        // Query destination address and port.
        try {
            dstSockAddr = Os.getpeername(fd);
        } catch (ErrnoException e) {
            Log.e(TAG, "Get peername fail: ", e);
            throw new InvalidSocketException(ERROR_INVALID_SOCKET, e);
        }
        if (dstSockAddr instanceof InetSocketAddress) {
            dstAddress = getAddress((InetSocketAddress) dstSockAddr);
            dstPort = getPort((InetSocketAddress) dstSockAddr);
        } else {
            Log.e(TAG, "Invalid or mismatched peer SocketAddress");
            throw new InvalidSocketException(ERROR_INVALID_SOCKET);
        }

        // Query sequence and ack number
        dropAllIncomingPackets(fd, true);
        try {
            // Enter tcp repair mode.
            Os.setsockoptInt(fd, IPPROTO_TCP, TCP_REPAIR, TCP_REPAIR_ON);
            // Check if socket is idle.
            if (!isSocketIdle(fd)) {
                throw new InvalidSocketException(ERROR_SOCKET_NOT_IDLE);
            }
            // Query write sequence number from SEND_QUEUE.
            Os.setsockoptInt(fd, IPPROTO_TCP, TCP_REPAIR_QUEUE, TCP_SEND_QUEUE);
            seq = Os.getsockoptInt(fd, IPPROTO_TCP, TCP_QUEUE_SEQ);
            // Query read sequence number from RECV_QUEUE.
            Os.setsockoptInt(fd, IPPROTO_TCP, TCP_REPAIR_QUEUE, TCP_RECV_QUEUE);
            ack = Os.getsockoptInt(fd, IPPROTO_TCP, TCP_QUEUE_SEQ);
            // Switch to NO_QUEUE to prevent illegal socket read/write in repair mode.
            Os.setsockoptInt(fd, IPPROTO_TCP, TCP_REPAIR_QUEUE, TCP_NO_QUEUE);
            // Finally, check if socket is still idle. TODO : this check needs to move to
            // after starting polling to prevent a race.
            if (!isSocketIdle(fd)) {
                throw new InvalidSocketException(ERROR_INVALID_SOCKET);
            }

            // Query tcp window size.
            trw = NetworkUtils.getTcpRepairWindow(fd);
        } catch (ErrnoException e) {
            Log.e(TAG, "Exception reading TCP state from socket", e);
            try {
                Os.setsockoptInt(fd, IPPROTO_TCP, TCP_REPAIR, TCP_REPAIR_OFF);
            } catch (ErrnoException ex) {
                Log.e(TAG, "Exception while turning off repair mode due to exception", ex);
            }
            throw new InvalidSocketException(ERROR_INVALID_SOCKET, e);
        } finally {
            dropAllIncomingPackets(fd, false);
        }

        // Keepalive sequence number is last sequence number - 1. If it couldn't be retrieved,
        // then it must be set to -1, so decrement in all cases.
        seq = seq - 1;

        return new TcpSocketInfo(srcAddress, srcPort, dstAddress, dstPort, seq, ack, trw.rcvWnd,
                trw.rcvWndScale);
    }

    private static void switchOutOfRepairMode(@NonNull final FileDescriptor fd)
            throws ErrnoException {
        Os.setsockoptInt(fd, IPPROTO_TCP, TCP_REPAIR, TCP_REPAIR_OFF);
    }

    /**
     * Start monitoring incoming packets.
     *
     * @param fd socket fd to monitor.
     * @param messenger a callback to notify socket status.
     * @param slot keepalive slot.
     */
    public void startSocketMonitor(@NonNull final FileDescriptor fd,
            @NonNull final Messenger messenger, final int slot) {
        synchronized (mListeners) {
            if (null != mListeners.get(slot)) {
                throw new IllegalArgumentException("This slot is already taken");
            }
            for (int i = 0; i < mListeners.size(); ++i) {
                if (fd.equals(mListeners.valueAt(i))) {
                    throw new IllegalArgumentException("This fd is already registered");
                }
            }
            mFdHandlerQueue.addOnFileDescriptorEventListener(fd, FD_EVENTS, (readyFd, events) -> {
                // This can't be called twice because the queue guarantees that once the listener
                // is unregistered it can't be called again, even for a message that arrived
                // before it was unregistered.
                int result;
                try {
                    // First move the socket out of repair mode.
                    if (DBG) Log.d(TAG, "Moving socket out of repair mode for event : " + readyFd);
                    switchOutOfRepairMode(readyFd);
                    result = (0 != (events & EVENT_ERROR)) ? ERROR_INVALID_SOCKET : DATA_RECEIVED;
                } catch (ErrnoException e) {
                    // Could not move the socket out of repair mode. Still continue with notifying
                    // the client
                    Log.e(TAG, "Cannot switch socket out of repair mode", e);
                    result = ERROR_INVALID_SOCKET;
                }
                // Prepare and send the message to the receiver.
                final Message message = Message.obtain();
                message.what = EVENT_SOCKET_KEEPALIVE;
                message.arg1 = slot;
                message.arg2 = result;
                try {
                    messenger.send(message);
                } catch (RemoteException e) {
                    // Remote process died
                }
                synchronized (mListeners) {
                    mListeners.remove(slot);
                }
                // The listener returns the new set of events to listen to. Because 0 means no
                // event, the listener gets unregistered.
                return 0;
            });
            mListeners.put(slot, fd);
        }
    }

    /** Stop socket monitor */
    // This slot may have been stopped automatically already because the socket received data,
    // was closed on the other end or otherwise suffered some error. In this case, this function
    // is a no-op.
    public void stopSocketMonitor(final int slot) {
        final FileDescriptor fd;
        synchronized (mListeners) {
            fd = mListeners.get(slot);
            if (null == fd) return;
            mListeners.remove(slot);
        }
        mFdHandlerQueue.removeOnFileDescriptorEventListener(fd);
        try {
            if (DBG) Log.d(TAG, "Moving socket out of repair mode for stop : " + fd);
            switchOutOfRepairMode(fd);
        } catch (ErrnoException e) {
            Log.e(TAG, "Cannot switch socket out of repair mode", e);
            // Well, there is not much to do here to recover
        }
    }

    private static InetAddress getAddress(InetSocketAddress inetAddr) {
        return inetAddr.getAddress();
    }

    private static int getPort(InetSocketAddress inetAddr) {
        return inetAddr.getPort();
    }

    private static boolean isSocketIdle(FileDescriptor fd) throws ErrnoException {
        return isReceiveQueueEmpty(fd) && isSendQueueEmpty(fd);
    }

    private static boolean isReceiveQueueEmpty(FileDescriptor fd)
            throws ErrnoException {
        Int32Ref result = new Int32Ref(-1);
        Os.ioctlInt(fd, SIOCINQ, result);
        if (result.value != 0) {
            Log.e(TAG, "Read queue has data");
            return false;
        }
        return true;
    }

    private static boolean isSendQueueEmpty(FileDescriptor fd)
            throws ErrnoException {
        Int32Ref result = new Int32Ref(-1);
        Os.ioctlInt(fd, SIOCOUTQ, result);
        if (result.value != 0) {
            Log.e(TAG, "Write queue has data");
            return false;
        }
        return true;
    }

    private static void dropAllIncomingPackets(FileDescriptor fd, boolean enable)
            throws InvalidSocketException {
        try {
            if (enable) {
                NetworkUtils.attachDropAllBPFFilter(fd);
            } else {
                NetworkUtils.detachBPFFilter(fd);
            }
        } catch (SocketException e) {
            Log.e(TAG, "Socket Exception: ", e);
            throw new InvalidSocketException(ERROR_INVALID_SOCKET, e);
        }
    }
}
