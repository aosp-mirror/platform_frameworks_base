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

import android.util.proto.ProtoOutputStream;

import com.android.internal.annotations.VisibleForTesting;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Queue;

/**
 * Buffer used for window tracing.
 */
class WindowTraceBuffer {
    private static final long MAGIC_NUMBER_VALUE = ((long) MAGIC_NUMBER_H << 32) | MAGIC_NUMBER_L;

    private final Object mBufferLock = new Object();

    private final Queue<ProtoOutputStream> mBuffer = new ArrayDeque<>();
    private int mBufferUsedSize;
    private int mBufferCapacity;

    WindowTraceBuffer(int bufferCapacity) {
        mBufferCapacity = bufferCapacity;
        resetBuffer();
    }

    int getAvailableSpace() {
        return mBufferCapacity - mBufferUsedSize;
    }

    int size() {
        return mBuffer.size();
    }

    void setCapacity(int capacity) {
        mBufferCapacity = capacity;
    }

    /**
     * Inserts the specified element into this buffer.
     *
     * @param proto the element to add
     * @throws IllegalStateException if the element cannot be added because it is larger
     *                               than the buffer size.
     */
    void add(ProtoOutputStream proto) {
        int protoLength = proto.getRawSize();
        if (protoLength > mBufferCapacity) {
            throw new IllegalStateException("Trace object too large for the buffer. Buffer size:"
                    + mBufferCapacity + " Object size: " + protoLength);
        }
        synchronized (mBufferLock) {
            discardOldest(protoLength);
            mBuffer.add(proto);
            mBufferUsedSize += protoLength;
            mBufferLock.notify();
        }
    }

    boolean contains(byte[] other) {
        return mBuffer.stream()
                .anyMatch(p -> Arrays.equals(p.getBytes(), other));
    }

    /**
     * Writes the trace buffer to disk.
     */
    void writeTraceToFile(File traceFile) throws IOException {
        synchronized (mBufferLock) {
            traceFile.delete();
            traceFile.setReadable(true, false);
            try (OutputStream os = new FileOutputStream(traceFile)) {
                ProtoOutputStream proto = new ProtoOutputStream();
                proto.write(MAGIC_NUMBER, MAGIC_NUMBER_VALUE);
                os.write(proto.getBytes());
                while (!mBuffer.isEmpty()) {
                    proto = mBuffer.poll();
                    mBufferUsedSize -= proto.getRawSize();
                    byte[] protoBytes = proto.getBytes();
                    os.write(protoBytes);
                }
                os.flush();
            }
        }
    }

    /**
     * Checks if the element can be added to the buffer. The element is already certain to be
     * smaller than the overall buffer size.
     *
     * @param protoLength byte array representation of the Proto object to add
     */
    private void discardOldest(int protoLength) {
        long availableSpace = getAvailableSpace();

        while (availableSpace < protoLength) {

            ProtoOutputStream item = mBuffer.poll();
            if (item == null) {
                throw new IllegalStateException("No element to discard from buffer");
            }
            mBufferUsedSize -= item.getRawSize();
            availableSpace = getAvailableSpace();
        }
    }

    /**
     * Removes all elements form the buffer
     */
    void resetBuffer() {
        synchronized (mBufferLock) {
            mBuffer.clear();
            mBufferUsedSize = 0;
        }
    }

    @VisibleForTesting
    int getBufferSize() {
        return mBufferUsedSize;
    }

    String getStatus() {
        synchronized (mBufferLock) {
            return "Buffer size: "
                    + mBufferCapacity
                    + " bytes"
                    + "\n"
                    + "Buffer usage: "
                    + mBufferUsedSize
                    + " bytes"
                    + "\n"
                    + "Elements in the buffer: "
                    + mBuffer.size();
        }
    }
}
