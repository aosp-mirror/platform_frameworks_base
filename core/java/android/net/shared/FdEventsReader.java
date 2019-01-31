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

package android.net.shared;

import static android.os.MessageQueue.OnFileDescriptorEventListener.EVENT_ERROR;
import static android.os.MessageQueue.OnFileDescriptorEventListener.EVENT_INPUT;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.net.util.SocketUtils;
import android.os.Handler;
import android.os.Looper;
import android.os.MessageQueue;
import android.system.ErrnoException;
import android.system.OsConstants;

import java.io.FileDescriptor;
import java.io.IOException;


/**
 * This class encapsulates the mechanics of registering a file descriptor
 * with a thread's Looper and handling read events (and errors).
 *
 * Subclasses MUST implement createFd() and SHOULD override handlePacket(). They MAY override
 * onStop() and onStart().
 *
 * Subclasses can expect a call life-cycle like the following:
 *
 *     [1] when a client calls start(), createFd() is called, followed by the onStart() hook if all
 *         goes well. Implementations may override onStart() for additional initialization.
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
 *     [3] when a client calls stop(), the onStop() hook is called (unless already stopped or never
 *         started). Implementations may override onStop() for additional cleanup.
 *
 * The packet receive buffer is recycled on every read call, so subclasses
 * should make any copies they would like inside their handlePacket()
 * implementation.
 *
 * All public methods MUST only be called from the same thread with which
 * the Handler constructor argument is associated.
 *
 * @param <BufferType> the type of the buffer used to read data.
 * @hide
 */
public abstract class FdEventsReader<BufferType> {
    private static final int FD_EVENTS = EVENT_INPUT | EVENT_ERROR;
    private static final int UNREGISTER_THIS_FD = 0;

    @NonNull
    private final Handler mHandler;
    @NonNull
    private final MessageQueue mQueue;
    @NonNull
    private final BufferType mBuffer;
    @Nullable
    private FileDescriptor mFd;
    private long mPacketsReceived;

    protected static void closeFd(FileDescriptor fd) {
        try {
            SocketUtils.closeSocket(fd);
        } catch (IOException ignored) {
        }
    }

    protected FdEventsReader(@NonNull Handler h, @NonNull BufferType buffer) {
        mHandler = h;
        mQueue = mHandler.getLooper().getQueue();
        mBuffer = buffer;
    }

    /** Start this FdEventsReader. */
    public void start() {
        if (onCorrectThread()) {
            createAndRegisterFd();
        } else {
            mHandler.post(() -> {
                logError("start() called from off-thread", null);
                createAndRegisterFd();
            });
        }
    }

    /** Stop this FdEventsReader and destroy the file descriptor. */
    public void stop() {
        if (onCorrectThread()) {
            unregisterAndDestroyFd();
        } else {
            mHandler.post(() -> {
                logError("stop() called from off-thread", null);
                unregisterAndDestroyFd();
            });
        }
    }

    @NonNull
    public Handler getHandler() {
        return mHandler;
    }

    protected abstract int recvBufSize(@NonNull BufferType buffer);

    /** Returns the size of the receive buffer. */
    public int recvBufSize() {
        return recvBufSize(mBuffer);
    }

    /**
     * Get the number of successful calls to {@link #readPacket(FileDescriptor, Object)}.
     *
     * <p>A call was successful if {@link #readPacket(FileDescriptor, Object)} returned a value > 0.
     */
    public final long numPacketsReceived() {
        return mPacketsReceived;
    }

    /**
     * Subclasses MUST create the listening socket here, including setting all desired socket
     * options, interface or address/port binding, etc. The socket MUST be created nonblocking.
     */
    @Nullable
    protected abstract FileDescriptor createFd();

    /**
     * Implementations MUST return the bytes read or throw an Exception.
     *
     * <p>The caller may throw a {@link ErrnoException} with {@link OsConstants#EAGAIN} or
     * {@link OsConstants#EINTR}, in which case {@link FdEventsReader} will ignore the buffer
     * contents and respectively wait for further input or retry the read immediately. For all other
     * exceptions, the {@link FdEventsReader} will be stopped with no more interactions with this
     * method.
     */
    protected abstract int readPacket(@NonNull FileDescriptor fd, @NonNull BufferType buffer)
            throws Exception;

    /**
     * Called by the main loop for every packet.  Any desired copies of
     * |recvbuf| should be made in here, as the underlying byte array is
     * reused across all reads.
     */
    protected void handlePacket(@NonNull BufferType recvbuf, int length) {}

    /**
     * Called by the main loop to log errors.  In some cases |e| may be null.
     */
    protected void logError(@NonNull String msg, @Nullable Exception e) {}

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
        } catch (Exception e) {
            logError("Failed to create socket: ", e);
            closeFd(mFd);
            mFd = null;
        }

        if (mFd == null) return;

        mQueue.addOnFileDescriptorEventListener(
                mFd,
                FD_EVENTS,
                (fd, events) -> {
                    // Always call handleInput() so read/recvfrom are given
                    // a proper chance to encounter a meaningful errno and
                    // perhaps log a useful error message.
                    if (!isRunning() || !handleInput()) {
                        unregisterAndDestroyFd();
                        return UNREGISTER_THIS_FD;
                    }
                    return FD_EVENTS;
                });
        onStart();
    }

    private boolean isRunning() {
        return (mFd != null) && mFd.valid();
    }

    // Keep trying to read until we get EAGAIN/EWOULDBLOCK or some fatal error.
    private boolean handleInput() {
        while (isRunning()) {
            final int bytesRead;

            try {
                bytesRead = readPacket(mFd, mBuffer);
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
                handlePacket(mBuffer, bytesRead);
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
