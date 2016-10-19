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

import android.annotation.Nullable;
import android.system.ErrnoException;
import android.system.Os;
import android.system.OsConstants;

import libcore.io.IoBridge;

import java.io.FileDescriptor;
import java.io.InterruptedIOException;
import java.io.IOException;


/**
 * A thread that reads from a socket and passes the received packets to a
 * subclass's handlePacket() method.  The packet receive buffer is recycled
 * on every read call, so subclasses should make any copies they would like
 * inside their handlePacket() implementation.
 *
 * All public methods may be called from any thread.
 *
 * @hide
 */
public abstract class BlockingSocketReader {
    public static final int DEFAULT_RECV_BUF_SIZE = 2 * 1024;

    private final byte[] mPacket;
    private final Thread mThread;
    private volatile FileDescriptor mSocket;
    private volatile boolean mRunning;
    private volatile long mPacketsReceived;

    // Make it slightly easier for subclasses to properly close a socket
    // without having to know this incantation.
    public static final void closeSocket(@Nullable FileDescriptor fd) {
        try {
            IoBridge.closeAndSignalBlockedThreads(fd);
        } catch (IOException ignored) {}
    }

    protected BlockingSocketReader() {
        this(DEFAULT_RECV_BUF_SIZE);
    }

    protected BlockingSocketReader(int recvbufsize) {
        if (recvbufsize < DEFAULT_RECV_BUF_SIZE) {
            recvbufsize = DEFAULT_RECV_BUF_SIZE;
        }
        mPacket = new byte[recvbufsize];
        mThread = new Thread(() -> { mainLoop(); });
    }

    public final boolean start() {
        if (mSocket != null) return false;

        try {
            mSocket = createSocket();
        } catch (Exception e) {
            logError("Failed to create socket: ", e);
            return false;
        }

        if (mSocket == null) return false;

        mRunning = true;
        mThread.start();
        return true;
    }

    public final void stop() {
        mRunning = false;
        closeSocket(mSocket);
        mSocket = null;
    }

    public final boolean isRunning() { return mRunning; }

    public final long numPacketsReceived() { return mPacketsReceived; }

    /**
     * Subclasses MUST create the listening socket here, including setting
     * all desired socket options, interface or address/port binding, etc.
     */
    protected abstract FileDescriptor createSocket();

    /**
     * Called by the main loop for every packet.  Any desired copies of
     * |recvbuf| should be made in here, and the underlying byte array is
     * reused across all reads.
     */
    protected void handlePacket(byte[] recvbuf, int length) {}

    /**
     * Called by the main loop to log errors.  In some cases |e| may be null.
     */
    protected void logError(String msg, Exception e) {}

    /**
     * Called by the main loop just prior to exiting.
     */
    protected void onExit() {}

    private final void mainLoop() {
        while (isRunning()) {
            final int bytesRead;

            try {
                // Blocking read.
                // TODO: See if this can be converted to recvfrom.
                bytesRead = Os.read(mSocket, mPacket, 0, mPacket.length);
                if (bytesRead < 1) {
                    if (isRunning()) logError("Socket closed, exiting", null);
                    break;
                }
                mPacketsReceived++;
            } catch (ErrnoException e) {
                if (e.errno != OsConstants.EINTR) {
                    if (isRunning()) logError("read error: ", e);
                    break;
                }
                continue;
            } catch (IOException ioe) {
                if (isRunning()) logError("read error: ", ioe);
                continue;
            }

            try {
                handlePacket(mPacket, bytesRead);
            } catch (Exception e) {
                logError("Unexpected exception: ", e);
                break;
            }
        }

        stop();
        onExit();
    }
}
