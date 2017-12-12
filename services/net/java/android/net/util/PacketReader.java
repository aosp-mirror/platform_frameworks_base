/*
 * Copyright (C) 2016 The Android Open Source Project
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

package android.net.util;

import static android.os.MessageQueue.OnFileDescriptorEventListener.EVENT_INPUT;
import static android.os.MessageQueue.OnFileDescriptorEventListener.EVENT_ERROR;

import android.annotation.Nullable;
import android.os.Handler;
import android.os.Looper;
import android.os.MessageQueue;
import android.os.MessageQueue.OnFileDescriptorEventListener;
import android.system.ErrnoException;
import android.system.Os;
import android.system.OsConstants;

import libcore.io.IoUtils;

import java.io.FileDescriptor;
import java.io.IOException;


/**
 * This class encapsulates the mechanics of registering a file descriptor
 * with a thread's Looper and handling read events (and errors).
 *
 * Subclasses MUST implement createFd() and SHOULD override handlePacket().

 * Subclasses can expect a call life-cycle like the following:
 *
 *     [1] start() calls createFd() and (if all goes well) onStart()
 *
 *     [2] yield, waiting for read event or error notification:
 *
 *             [a] readPacket() && handlePacket()
 *
 *             [b] if (no error):
 *                     goto 2
 *                 else:
 *                     goto 3
 *
 *     [3] stop() calls onStop() if not previously stopped
 *
 * The packet receive buffer is recycled on every read call, so subclasses
 * should make any copies they would like inside their handlePacket()
 * implementation.
 *
 * All public methods MUST only be called from the same thread with which
 * the Handler constructor argument is associated.
 *
 * TODO: rename this class to something more correctly descriptive (something
 * like [or less horrible than] FdReadEventsHandler?).
 *
 * @hide
 */
public abstract class PacketReader {
    private static final int FD_EVENTS = EVENT_INPUT | EVENT_ERROR;
    private static final int UNREGISTER_THIS_FD = 0;

    public static final int DEFAULT_RECV_BUF_SIZE = 2 * 1024;

    private final Handler mHandler;
    private final MessageQueue mQueue;
    private final byte[] mPacket;
    private FileDescriptor mFd;
    private long mPacketsReceived;

    protected static void closeFd(FileDescriptor fd) {
        IoUtils.closeQuietly(fd);
    }

    protected PacketReader(Handler h) {
        this(h, DEFAULT_RECV_BUF_SIZE);
    }

    protected PacketReader(Handler h, int recvbufsize) {
        mHandler = h;
        mQueue = mHandler.getLooper().getQueue();
        mPacket = new byte[Math.max(recvbufsize, DEFAULT_RECV_BUF_SIZE)];
    }

    public final void start() {
        if (onCorrectThread()) {
            createAndRegisterFd();
        } else {
            mHandler.post(() -> {
                logError("start() called from off-thread", null);
                createAndRegisterFd();
            });
        }
    }

    public final void stop() {
        if (onCorrectThread()) {
            unregisterAndDestroyFd();
        } else {
            mHandler.post(() -> {
                logError("stop() called from off-thread", null);
                unregisterAndDestroyFd();
            });
        }
    }

    public Handler getHandler() { return mHandler; }

    public final int recvBufSize() { return mPacket.length; }

    public final long numPacketsReceived() { return mPacketsReceived; }

    /**
     * Subclasses MUST create the listening socket here, including setting
     * all desired socket options, interface or address/port binding, etc.
     */
    protected abstract FileDescriptor createFd();

    /**
     * Subclasses MAY override this to change the default read() implementation
     * in favour of, say, recvfrom().
     *
     * Implementations MUST return the bytes read or throw an Exception.
     */
    protected int readPacket(FileDescriptor fd, byte[] packetBuffer) throws Exception {
        return Os.read(fd, packetBuffer, 0, packetBuffer.length);
    }

    /**
     * Called by the main loop for every packet.  Any desired copies of
     * |recvbuf| should be made in here, as the underlying byte array is
     * reused across all reads.
     */
    protected void handlePacket(byte[] recvbuf, int length) {}

    /**
     * Called by the main loop to log errors.  In some cases |e| may be null.
     */
    protected void logError(String msg, Exception e) {}

    /**
     * Called by start(), if successful, just prior to returning.
     */
    protected void onStart() {}

    /**
     * Called by stop() just prior to returning.
     */
    protected void onStop() {}

    private void createAndRegisterFd() {
        if (mFd != null) return;

        try {
            mFd = createFd();
            if (mFd != null) {
                // Force the socket to be non-blocking.
                IoUtils.setBlocking(mFd, false);
            }
        } catch (Exception e) {
            logError("Failed to create socket: ", e);
            closeFd(mFd);
            mFd = null;
            return;
        }

        if (mFd == null) return;

        mQueue.addOnFileDescriptorEventListener(
                mFd,
                FD_EVENTS,
                new OnFileDescriptorEventListener() {
                    @Override
                    public int onFileDescriptorEvents(FileDescriptor fd, int events) {
                        // Always call handleInput() so read/recvfrom are given
                        // a proper chance to encounter a meaningful errno and
                        // perhaps log a useful error message.
                        if (!isRunning() || !handleInput()) {
                            unregisterAndDestroyFd();
                            return UNREGISTER_THIS_FD;
                        }
                        return FD_EVENTS;
                    }
                });
        onStart();
    }

    private boolean isRunning() { return (mFd != null) && mFd.valid(); }

    // Keep trying to read until we get EAGAIN/EWOULDBLOCK or some fatal error.
    private boolean handleInput() {
        while (isRunning()) {
            final int bytesRead;

            try {
                bytesRead = readPacket(mFd, mPacket);
                if (bytesRead < 1) {
                    if (isRunning()) logError("Socket closed, exiting", null);
                    break;
                }
                mPacketsReceived++;
            } catch (ErrnoException e) {
                if (e.errno == OsConstants.EAGAIN) {
                    // We've read everything there is to read this time around.
                    return true;
                } else if (e.errno == OsConstants.EINTR) {
                    continue;
                } else {
                    if (isRunning()) logError("readPacket error: ", e);
                    break;
                }
            } catch (Exception e) {
                if (isRunning()) logError("readPacket error: ", e);
                break;
            }

            try {
                handlePacket(mPacket, bytesRead);
            } catch (Exception e) {
                logError("handlePacket error: ", e);
                break;
            }
        }

        return false;
    }

    private void unregisterAndDestroyFd() {
        if (mFd == null) return;

        mQueue.removeOnFileDescriptorEventListener(mFd);
        closeFd(mFd);
        mFd = null;
        onStop();
    }

    private boolean onCorrectThread() {
        return (mHandler.getLooper() == Looper.myLooper());
    }
}
