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

package com.android.server.wm;

import static com.android.server.wm.WindowManagerTraceFileProto.MAGIC_NUMBER;
import static com.android.server.wm.WindowManagerTraceFileProto.MAGIC_NUMBER_H;
import static com.android.server.wm.WindowManagerTraceFileProto.MAGIC_NUMBER_L;

import android.os.Trace;
import android.util.proto.ProtoOutputStream;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;

/**
 * Buffer used for window tracing.
 */
abstract class WindowTraceBuffer {
    private static final long MAGIC_NUMBER_VALUE = ((long) MAGIC_NUMBER_H << 32) | MAGIC_NUMBER_L;

    final Object mBufferSizeLock = new Object();
    final BlockingQueue<byte[]> mBuffer;
    int mBufferSize;
    private final int mBufferCapacity;
    private final File mTraceFile;

    WindowTraceBuffer(int size, File traceFile) throws IOException {
        mBufferCapacity = size;
        mTraceFile = traceFile;
        mBuffer = new LinkedBlockingQueue<>();

        initTraceFile();
    }

    int getAvailableSpace() {
        return mBufferCapacity - mBufferSize;
    }

    /**
     * Inserts the specified element into this buffer.
     *
     * This method is synchronized with {@code #take()} and {@code #clear()}
     * for consistency.
     *
     * @param proto the element to add
     * @return {@code true} if the inserted item was inserted into the buffer
     * @throws IllegalStateException if the element cannot be added because it is larger
     *                               than the buffer size.
     */
    boolean add(ProtoOutputStream proto) throws InterruptedException {
        byte[] protoBytes = proto.getBytes();
        int protoLength = protoBytes.length;
        if (protoLength > mBufferCapacity) {
            throw new IllegalStateException("Trace object too large for the buffer. Buffer size:"
                    + mBufferCapacity + " Object size: " + protoLength);
        }
        synchronized (mBufferSizeLock) {
            boolean canAdd = canAdd(protoBytes);
            if (canAdd) {
                mBuffer.offer(protoBytes);
                mBufferSize += protoLength;
            }
            return canAdd;
        }
    }

    void writeNextBufferElementToFile() throws IOException {
        byte[] proto;
        try {
            proto = take();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return;
        }

        try {
            Trace.traceBegin(Trace.TRACE_TAG_WINDOW_MANAGER, "writeToFile");
            try (OutputStream os = new FileOutputStream(mTraceFile, true)) {
                os.write(proto);
            }
        } finally {
            Trace.traceEnd(Trace.TRACE_TAG_WINDOW_MANAGER);
        }
    }

    /**
     * Retrieves and removes the head of this queue, waiting if necessary
     * until an element becomes available.
     *
     * This method is synchronized with {@code #add(ProtoOutputStream)} and {@code #clear()}
     * for consistency.
     *
     * @return the head of this buffer, or {@code null} if this buffer is empty
     */
    private byte[] take() throws InterruptedException {
        byte[] item = mBuffer.take();
        synchronized (mBufferSizeLock) {
            mBufferSize -= item.length;
            return item;
        }
    }

    private void initTraceFile() throws IOException {
        mTraceFile.delete();
        try (OutputStream os = new FileOutputStream(mTraceFile)) {
            mTraceFile.setReadable(true, false);
            ProtoOutputStream proto = new ProtoOutputStream(os);
            proto.write(MAGIC_NUMBER, MAGIC_NUMBER_VALUE);
            proto.flush();
        }
    }

    /**
     * Checks if the element can be added to the buffer. The element is already certain to be
     * smaller than the overall buffer size.
     *
     * @param protoBytes byte array representation of the Proto object to add
     * @return <tt>true<</tt> if the element can be added to the buffer or not
     */
    abstract boolean canAdd(byte[] protoBytes) throws InterruptedException;

    /**
     * Flush all buffer content to the disk.
     *
     * @throws IOException if the buffer cannot write its contents to the {@link #mTraceFile}
     */
    abstract void writeToDisk() throws IOException, InterruptedException;

    /**
     * Builder for a {@code WindowTraceBuffer} which creates a {@link WindowTraceQueueBuffer}
     */
    static class Builder {
        private File mTraceFile;
        private int mBufferCapacity;


        Builder setTraceFile(File traceFile) {
            mTraceFile = traceFile;
            return this;
        }

        Builder setBufferCapacity(int size) {
            mBufferCapacity = size;
            return this;
        }

        File getFile() {
            return mTraceFile;
        }

        WindowTraceBuffer build() throws IOException {
            if (mBufferCapacity <= 0) {
                throw new IllegalStateException("Buffer capacity must be greater than 0.");
            }

            if (mTraceFile == null) {
                throw new IllegalArgumentException("A valid trace file must be specified.");
            }

            return new WindowTraceQueueBuffer(mBufferCapacity, mTraceFile);
        }
    }
}
