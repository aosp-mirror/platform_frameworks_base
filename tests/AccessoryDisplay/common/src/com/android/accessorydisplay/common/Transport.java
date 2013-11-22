/*
 * Copyright (C) 2013 The Android Open Source Project
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

package com.android.accessorydisplay.common;

import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.util.SparseArray;

import java.io.IOException;
import java.nio.ByteBuffer;

/**
 * A simple message transport.
 * <p>
 * This object's interface is thread-safe, however incoming messages
 * are always delivered on the {@link Looper} thread on which the transport
 * was created.
 * </p>
 */
public abstract class Transport {
    private static final int MAX_INPUT_BUFFERS = 8;

    private final Logger mLogger;

    // The transport thread looper and handler.
    private final TransportHandler mHandler;

    // Lock to guard all mutable state.
    private final Object mLock = new Object();

    // The output buffer.  Set to null when the transport is closed.
    private ByteBuffer mOutputBuffer;

    // The input buffer pool.
    private BufferPool mInputBufferPool;

    // The reader thread.  Initialized when reading starts.
    private ReaderThread mThread;

    // The list of callbacks indexed by service id.
    private final SparseArray<Callback> mServices = new SparseArray<Callback>();

    public Transport(Logger logger, int maxPacketSize) {
        mLogger = logger;
        mHandler = new TransportHandler();
        mOutputBuffer = ByteBuffer.allocate(maxPacketSize);
        mInputBufferPool = new BufferPool(
                maxPacketSize, Protocol.MAX_ENVELOPE_SIZE, MAX_INPUT_BUFFERS);
    }

    /**
     * Gets the logger for debugging.
     */
    public Logger getLogger() {
        return mLogger;
    }

    /**
     * Gets the handler on the transport's thread.
     */
    public Handler getHandler() {
        return mHandler;
    }

    /**
     * Closes the transport.
     */
    public void close() {
        synchronized (mLock) {
            if (mOutputBuffer != null) {
                if (mThread == null) {
                    ioClose();
                } else {
                    // If the thread was started then it will be responsible for
                    // closing the stream when it quits because it may currently
                    // be in the process of reading from the stream so we can't simply
                    // shut it down right now.
                    mThread.quit();
                }
                mOutputBuffer = null;
            }
        }
    }

    /**
     * Sends a message.
     *
     * @param service The service to whom the message is addressed.
     * @param what The message type.
     * @param content The content, or null if there is none.
     * @return True if the message was sent successfully, false if an error occurred.
     */
    public boolean sendMessage(int service, int what, ByteBuffer content) {
        checkServiceId(service);
        checkMessageId(what);

        try {
            synchronized (mLock) {
                if (mOutputBuffer == null) {
                    mLogger.logError("Send message failed because transport was closed.");
                    return false;
                }

                final byte[] outputArray = mOutputBuffer.array();
                final int capacity = mOutputBuffer.capacity();
                mOutputBuffer.clear();
                mOutputBuffer.putShort((short)service);
                mOutputBuffer.putShort((short)what);
                if (content == null) {
                    mOutputBuffer.putInt(0);
                } else {
                    final int contentLimit = content.limit();
                    int contentPosition = content.position();
                    int contentRemaining = contentLimit - contentPosition;
                    if (contentRemaining > Protocol.MAX_CONTENT_SIZE) {
                        throw new IllegalArgumentException("Message content too large: "
                                + contentRemaining + " > " + Protocol.MAX_CONTENT_SIZE);
                    }
                    mOutputBuffer.putInt(contentRemaining);
                    while (contentRemaining != 0) {
                        final int outputAvailable = capacity - mOutputBuffer.position();
                        if (contentRemaining <= outputAvailable) {
                            mOutputBuffer.put(content);
                            break;
                        }
                        content.limit(contentPosition + outputAvailable);
                        mOutputBuffer.put(content);
                        content.limit(contentLimit);
                        ioWrite(outputArray, 0, capacity);
                        contentPosition += outputAvailable;
                        contentRemaining -= outputAvailable;
                        mOutputBuffer.clear();
                    }
                }
                ioWrite(outputArray, 0, mOutputBuffer.position());
                return true;
            }
        } catch (IOException ex) {
            mLogger.logError("Send message failed: " + ex);
            return false;
        }
    }

    /**
     * Starts reading messages on a separate thread.
     */
    public void startReading() {
        synchronized (mLock) {
            if (mOutputBuffer == null) {
                throw new IllegalStateException("Transport has been closed");
            }

            mThread = new ReaderThread();
            mThread.start();
        }
    }

    /**
     * Registers a service and provides a callback to receive messages.
     *
     * @param service The service id.
     * @param callback The callback to use.
     */
    public void registerService(int service, Callback callback) {
        checkServiceId(service);
        if (callback == null) {
            throw new IllegalArgumentException("callback must not be null");
        }

        synchronized (mLock) {
            mServices.put(service, callback);
        }
    }

    /**
     * Unregisters a service.
     *
     * @param service The service to unregister.
     */
    public void unregisterService(int service) {
        checkServiceId(service);

        synchronized (mLock) {
            mServices.remove(service);
        }
    }

    private void dispatchMessageReceived(int service, int what, ByteBuffer content) {
        final Callback callback;
        synchronized (mLock) {
            callback = mServices.get(service);
        }
        if (callback != null) {
            callback.onMessageReceived(service, what, content);
        } else {
            mLogger.log("Discarding message " + what
                    + " for unregistered service " + service);
        }
    }

    private static void checkServiceId(int service) {
        if (service < 0 || service > 0xffff) {
            throw new IllegalArgumentException("service id out of range: " + service);
        }
    }

    private static void checkMessageId(int what) {
        if (what < 0 || what > 0xffff) {
            throw new IllegalArgumentException("message id out of range: " + what);
        }
    }

    // The IO methods must be safe to call on any thread.
    // They may be called concurrently.
    protected abstract void ioClose();
    protected abstract int ioRead(byte[] buffer, int offset, int count)
            throws IOException;
    protected abstract void ioWrite(byte[] buffer, int offset, int count)
            throws IOException;

    /**
     * Callback for services that handle received messages.
     */
    public interface Callback {
        /**
         * Indicates that a message was received.
         *
         * @param service The service to whom the message is addressed.
         * @param what The message type.
         * @param content The content, or null if there is none.
         */
        public void onMessageReceived(int service, int what, ByteBuffer content);
    }

    final class TransportHandler extends Handler {
        @Override
        public void handleMessage(Message msg) {
            final ByteBuffer buffer = (ByteBuffer)msg.obj;
            try {
                final int limit = buffer.limit();
                while (buffer.position() < limit) {
                    final int service = buffer.getShort() & 0xffff;
                    final int what = buffer.getShort() & 0xffff;
                    final int contentSize = buffer.getInt();
                    if (contentSize == 0) {
                        dispatchMessageReceived(service, what, null);
                    } else {
                        final int end = buffer.position() + contentSize;
                        buffer.limit(end);
                        dispatchMessageReceived(service, what, buffer);
                        buffer.limit(limit);
                        buffer.position(end);
                    }
                }
            } finally {
                mInputBufferPool.release(buffer);
            }
        }
    }

    final class ReaderThread extends Thread {
        // Set to true when quitting.
        private volatile boolean mQuitting;

        public ReaderThread() {
            super("Accessory Display Transport");
        }

        @Override
        public void run() {
            loop();
            ioClose();
        }

        private void loop() {
            ByteBuffer buffer = null;
            int length = Protocol.HEADER_SIZE;
            int contentSize = -1;
            outer: while (!mQuitting) {
                // Get a buffer.
                if (buffer == null) {
                    buffer = mInputBufferPool.acquire(length);
                } else {
                    buffer = mInputBufferPool.grow(buffer, length);
                }

                // Read more data until needed number of bytes obtained.
                int position = buffer.position();
                int count;
                try {
                    count = ioRead(buffer.array(), position, buffer.capacity() - position);
                    if (count < 0) {
                        break; // end of stream
                    }
                } catch (IOException ex) {
                    mLogger.logError("Read failed: " + ex);
                    break; // error
                }
                position += count;
                buffer.position(position);
                if (contentSize < 0 && position >= Protocol.HEADER_SIZE) {
                    contentSize = buffer.getInt(4);
                    if (contentSize < 0 || contentSize > Protocol.MAX_CONTENT_SIZE) {
                        mLogger.logError("Encountered invalid content size: " + contentSize);
                        break; // malformed stream
                    }
                    length += contentSize;
                }
                if (position < length) {
                    continue; // need more data
                }

                // There is at least one complete message in the buffer.
                // Find the end of a contiguous chunk of complete messages.
                int next = length;
                int remaining;
                for (;;) {
                    length = Protocol.HEADER_SIZE;
                    remaining = position - next;
                    if (remaining < length) {
                        contentSize = -1;
                        break; // incomplete header, need more data
                    }
                    contentSize = buffer.getInt(next + 4);
                    if (contentSize < 0 || contentSize > Protocol.MAX_CONTENT_SIZE) {
                        mLogger.logError("Encountered invalid content size: " + contentSize);
                        break outer; // malformed stream
                    }
                    length += contentSize;
                    if (remaining < length) {
                        break; // incomplete content, need more data
                    }
                    next += length;
                }

                // Post the buffer then don't modify it anymore.
                // Now this is kind of sneaky.  We know that no other threads will
                // be acquiring buffers from the buffer pool so we can keep on
                // referring to this buffer as long as we don't modify its contents.
                // This allows us to operate in a single-buffered mode if desired.
                buffer.limit(next);
                buffer.rewind();
                mHandler.obtainMessage(0, buffer).sendToTarget();

                // If there is an incomplete message at the end, then we will need
                // to copy it to a fresh buffer before continuing.  In the single-buffered
                // case, we may acquire the same buffer as before which is fine.
                if (remaining == 0) {
                    buffer = null;
                } else {
                    final ByteBuffer oldBuffer = buffer;
                    buffer = mInputBufferPool.acquire(length);
                    System.arraycopy(oldBuffer.array(), next, buffer.array(), 0, remaining);
                    buffer.position(remaining);
                }
            }

            if (buffer != null) {
                mInputBufferPool.release(buffer);
            }
        }

        public void quit() {
            mQuitting = true;
        }
    }
}
