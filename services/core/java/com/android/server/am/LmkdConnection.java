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

package com.android.server.am;

import static android.os.MessageQueue.OnFileDescriptorEventListener.EVENT_ERROR;
import static android.os.MessageQueue.OnFileDescriptorEventListener.EVENT_INPUT;

import static com.android.server.am.ActivityManagerDebugConfig.TAG_AM;
import static com.android.server.am.ActivityManagerDebugConfig.TAG_WITH_CLASS_NAME;

import android.net.LocalSocket;
import android.net.LocalSocketAddress;
import android.os.MessageQueue;
import android.util.Slog;

import com.android.internal.annotations.GuardedBy;

import libcore.io.IoUtils;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.FileDescriptor;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;

/**
 * Lmkd connection to communicate with lowmemorykiller daemon.
 */
public class LmkdConnection {
    private static final String TAG = TAG_WITH_CLASS_NAME ? "LmkdConnection" : TAG_AM;

    /**
     * Max LMKD reply packet length in bytes
     * Used to hold the data for the statsd atoms logging
     * Must be in sync with statslog.h
     */
    private static final int LMKD_REPLY_MAX_SIZE = 222;

    // connection listener interface
    interface LmkdConnectionListener {
        public boolean onConnect(OutputStream ostream);
        public void onDisconnect();
        /**
         * Check if received reply was expected (reply to an earlier request)
         *
         * @param replyBuf The buffer provided in exchange() to receive the reply.
         *                 It can be used by exchange() caller to store reply-specific
         *                 tags for later use in isReplyExpected() to verify if
         *                 received packet is the expected reply.
         * @param dataReceived The buffer holding received data
         * @param receivedLen Size of the data received
         */
        public boolean isReplyExpected(ByteBuffer replyBuf, ByteBuffer dataReceived,
            int receivedLen);

        /**
         * Handle the received message if it's unsolicited.
         *
         * @param dataReceived The buffer holding received data
         * @param receivedLen Size of the data received
         * @return True if the message has been handled correctly, false otherwise.
         */
        boolean handleUnsolicitedMessage(DataInputStream inputData, int receivedLen);
    }

    private final MessageQueue mMsgQueue;

    // lmkd connection listener
    private final LmkdConnectionListener mListener;

    // mutex to synchronize access to the socket
    private final Object mLmkdSocketLock = new Object();

    // socket to communicate with lmkd
    @GuardedBy("mLmkdSocketLock")
    private LocalSocket mLmkdSocket = null;

    // mutex to synchronize socket output stream with socket creation/destruction
    private final Object mLmkdOutputStreamLock = new Object();

    // socket output stream
    @GuardedBy("mLmkdOutputStreamLock")
    private OutputStream mLmkdOutputStream = null;

    // mutex to synchronize socket input stream with socket creation/destruction
    private final Object mLmkdInputStreamLock = new Object();

    // socket input stream
    @GuardedBy("mLmkdInputStreamLock")
    private InputStream mLmkdInputStream = null;

    // buffer to store incoming data
    private final ByteBuffer mInputBuf =
            ByteBuffer.allocate(LMKD_REPLY_MAX_SIZE);

    // Input stream to parse the incoming data
    private final DataInputStream mInputData = new DataInputStream(
            new ByteArrayInputStream(mInputBuf.array()));

    // object to protect mReplyBuf and to wait/notify when reply is received
    private final Object mReplyBufLock = new Object();

    // reply buffer
    @GuardedBy("mReplyBufLock")
    private ByteBuffer mReplyBuf = null;

    ////////////////////  END FIELDS  ////////////////////

    LmkdConnection(MessageQueue msgQueue, LmkdConnectionListener listener) {
        mMsgQueue = msgQueue;
        mListener = listener;
    }

    public boolean connect() {
        synchronized (mLmkdSocketLock) {
            if (mLmkdSocket != null) {
                return true;
            }
            // temporary sockets and I/O streams
            final LocalSocket socket = openSocket();

            if (socket == null) {
                Slog.w(TAG, "Failed to connect to lowmemorykiller, retry later");
                return false;
            }

            final OutputStream ostream;
            final InputStream istream;
            try {
                ostream = socket.getOutputStream();
                istream = socket.getInputStream();
            } catch (IOException ex) {
                IoUtils.closeQuietly(socket);
                return false;
            }
            // execute onConnect callback
            if (mListener != null && !mListener.onConnect(ostream)) {
                Slog.w(TAG, "Failed to communicate with lowmemorykiller, retry later");
                IoUtils.closeQuietly(socket);
                return false;
            }
            // connection established
            synchronized(mLmkdOutputStreamLock) {
                synchronized(mLmkdInputStreamLock) {
                    mLmkdSocket = socket;
                    mLmkdOutputStream = ostream;
                    mLmkdInputStream = istream;
                }
            }
            mMsgQueue.addOnFileDescriptorEventListener(mLmkdSocket.getFileDescriptor(),
                EVENT_INPUT | EVENT_ERROR,
                new MessageQueue.OnFileDescriptorEventListener() {
                    public int onFileDescriptorEvents(FileDescriptor fd, int events) {
                        return fileDescriptorEventHandler(fd, events);
                    }
                }
            );
            mLmkdSocketLock.notifyAll();
        }
        return true;
    }

    private int fileDescriptorEventHandler(FileDescriptor fd, int events) {
        if (mListener == null) {
            return 0;
        }
        if ((events & EVENT_INPUT) != 0) {
            processIncomingData();
        }
        if ((events & EVENT_ERROR) != 0) {
            synchronized (mLmkdSocketLock) {
                // stop listening on this socket
                mMsgQueue.removeOnFileDescriptorEventListener(
                        mLmkdSocket.getFileDescriptor());
                IoUtils.closeQuietly(mLmkdSocket);
                synchronized(mLmkdOutputStreamLock) {
                    synchronized(mLmkdInputStreamLock) {
                        mLmkdOutputStream = null;
                        mLmkdInputStream = null;
                        mLmkdSocket = null;
                    }
                }
            }
            // wake up reply waiters if any
            synchronized (mReplyBufLock) {
                if (mReplyBuf != null) {
                    mReplyBuf = null;
                    mReplyBufLock.notifyAll();
                }
            }
            // notify listener
            mListener.onDisconnect();
            return 0;
        }
        return (EVENT_INPUT | EVENT_ERROR);
    }

    private void processIncomingData() {
        int len = read(mInputBuf);
        if (len > 0) {
            try {
                // reset InputStream to point into mInputBuf.array() begin
                mInputData.reset();
                synchronized (mReplyBufLock) {
                    if (mReplyBuf != null) {
                        if (mListener.isReplyExpected(mReplyBuf, mInputBuf, len)) {
                            // copy into reply buffer
                            mReplyBuf.put(mInputBuf.array(), 0, len);
                            mReplyBuf.rewind();
                            // wakeup the waiting thread
                            mReplyBufLock.notifyAll();
                        } else if (!mListener.handleUnsolicitedMessage(mInputData, len)) {
                            // received unexpected packet
                            // treat this as an error
                            mReplyBuf = null;
                            mReplyBufLock.notifyAll();
                            Slog.e(TAG, "Received an unexpected packet from lmkd");
                        }
                    } else if (!mListener.handleUnsolicitedMessage(mInputData, len)) {
                        // received asynchronous communication from lmkd
                        // but we don't recognize it.
                        Slog.w(TAG, "Received an unexpected packet from lmkd");
                    }
                }
            } catch (IOException e) {
                Slog.e(TAG, "Failed to parse lmkd data buffer. Size = " + len);
            }
        }
    }

    public boolean isConnected() {
        synchronized (mLmkdSocketLock) {
            return (mLmkdSocket != null);
        }
    }

    public boolean waitForConnection(long timeoutMs) {
        synchronized (mLmkdSocketLock) {
            if (mLmkdSocket != null) {
                return true;
            }
            try {
                mLmkdSocketLock.wait(timeoutMs);
                return (mLmkdSocket != null);
            } catch (InterruptedException e) {
                return false;
            }
        }
    }

    private LocalSocket openSocket() {
        final LocalSocket socket;

        try {
            socket = new LocalSocket(LocalSocket.SOCKET_SEQPACKET);
            socket.connect(
                new LocalSocketAddress("lmkd",
                        LocalSocketAddress.Namespace.RESERVED));
        } catch (IOException ex) {
            Slog.e(TAG, "Connection failed: " + ex.toString());
            return null;
        }
        return socket;
    }

    private boolean write(ByteBuffer buf) {
        boolean result = false;

        synchronized(mLmkdOutputStreamLock) {
            if (mLmkdOutputStream != null) {
                try {
                    mLmkdOutputStream.write(buf.array(), 0, buf.position());
                    result = true;
                } catch (IOException ex) {
                }
            }
        }

        return result;
    }

    private int read(ByteBuffer buf) {
        int result = -1;

        synchronized(mLmkdInputStreamLock) {
            if (mLmkdInputStream != null) {
                try {
                    result = mLmkdInputStream.read(buf.array(), 0, buf.array().length);
                } catch (IOException ex) {
                }
            }
        }
        return result;
    }

    /**
     * Exchange a request/reply packets with lmkd
     *
     * @param req The buffer holding the request data to be sent
     * @param repl The buffer to receive the reply
     */
    public boolean exchange(ByteBuffer req, ByteBuffer repl) {
        if (repl == null) {
            return write(req);
        }

        boolean result = false;
        // set reply buffer to user-defined one to fill it
        synchronized (mReplyBufLock) {
            mReplyBuf = repl;

            if (write(req)) {
                try {
                    // wait for the reply
                    mReplyBufLock.wait();
                    result = (mReplyBuf != null);
                } catch (InterruptedException ie) {
                    result = false;
                }
            }

            // reset reply buffer
            mReplyBuf = null;
        }
        return result;
    }
}
