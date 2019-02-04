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
abstract class WindowTraceBuffer {
    private static final long MAGIC_NUMBER_VALUE = ((long) MAGIC_NUMBER_H << 32) | MAGIC_NUMBER_L;

    final Object mBufferLock = new Object();
    final Queue<ProtoOutputStream> mBuffer = new ArrayDeque<>();
    final File mTraceFile;
    int mBufferSize;
    private final int mBufferCapacity;

    WindowTraceBuffer(int size, File traceFile) throws IOException {
        mBufferCapacity = size;
        mTraceFile = traceFile;

        initTraceFile();
    }

    int getAvailableSpace() {
        return mBufferCapacity - mBufferSize;
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
            boolean canAdd = canAdd(protoLength);
            if (canAdd) {
                mBuffer.add(proto);
                mBufferSize += protoLength;
            }
            mBufferLock.notify();
        }
    }

    /**
     * Stops the buffer execution and flush all buffer content to the disk.
     *
     * @throws IOException if the buffer cannot write its contents to the {@link #mTraceFile}
     */
    void dump() throws IOException, InterruptedException {
        try {
            Trace.traceBegin(Trace.TRACE_TAG_WINDOW_MANAGER, "writeTraceToFile");
            writeTraceToFile();
        } finally {
            Trace.traceEnd(Trace.TRACE_TAG_WINDOW_MANAGER);
        }
    }

    @VisibleForTesting
    boolean contains(byte[] other) {
        return mBuffer.stream()
                .anyMatch(p -> Arrays.equals(p.getBytes(), other));
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
     * @param protoLength byte array representation of the Proto object to add
     * @return {@code true} if the element can be added to the buffer or not
     */
    abstract boolean canAdd(int protoLength);

    /**
     * Flush all buffer content to the disk.
     *
     * @throws IOException if the buffer cannot write its contents to the {@link #mTraceFile}
     */
    abstract void writeTraceToFile() throws IOException, InterruptedException;

    /**
     * Builder for a {@code WindowTraceBuffer} which creates a {@link WindowTraceRingBuffer} for
     * continuous mode or a {@link WindowTraceQueueBuffer} otherwise
     */
    static class Builder {
        private boolean mContinuous;
        private File mTraceFile;
        private int mBufferCapacity;

        Builder setContinuousMode(boolean continuous) {
            mContinuous = continuous;
            return this;
        }

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

            if (mContinuous) {
                return new WindowTraceRingBuffer(mBufferCapacity, mTraceFile);
            } else {
                return new WindowTraceQueueBuffer(mBufferCapacity, mTraceFile);
            }
        }
    }
}
