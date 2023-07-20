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

package com.android.internal.util;

import android.util.proto.ProtoOutputStream;

import com.android.internal.annotations.VisibleForTesting;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Queue;
import java.util.function.Consumer;

/**
 * Buffer used for tracing and logging.
 *
 * @param <P> The class type of the proto provider
 * @param <S> The proto class type of the encapsulating proto
 * @param <T> The proto class type of the individual entry protos in the buffer
 *
 * {@hide}
 */
public class TraceBuffer<P, S extends P, T extends P> {
    private final Object mBufferLock = new Object();

    private final ProtoProvider<P, S, T> mProtoProvider;
    private final Queue<T> mBuffer = new ArrayDeque<>();
    private final Consumer mProtoDequeuedCallback;
    private int mBufferUsedSize;
    private int mBufferCapacity;

    /**
     * An interface to get protos from different sources (ie. fw-proto/proto-lite/nano-proto) for
     * the trace buffer.
     *
     * @param <P> The class type of the proto provider
     * @param <S> The proto class type of the encapsulating proto
     * @param <T> The proto class type of the individual protos in the buffer
     */
    public interface ProtoProvider<P, S extends P, T extends P> {
        /**
         * @return The size of the given proto.
         */
        int getItemSize(P proto);

        /**
         * @return The bytes of the given proto.
         */
        byte[] getBytes(P proto);

        /**
         * Writes the given encapsulating proto and buffer of protos to the given output
         * stream.
         */
        void write(S encapsulatingProto, Queue<T> buffer, OutputStream os) throws IOException;
    }

    /**
     * An implementation of the ProtoProvider that uses only the framework ProtoOutputStream.
     */
    private static class ProtoOutputStreamProvider implements
            ProtoProvider<ProtoOutputStream, ProtoOutputStream, ProtoOutputStream> {
        @Override
        public int getItemSize(ProtoOutputStream proto) {
            return proto.getRawSize();
        }

        @Override
        public byte[] getBytes(ProtoOutputStream proto) {
            return proto.getBytes();
        }

        @Override
        public void write(ProtoOutputStream encapsulatingProto, Queue<ProtoOutputStream> buffer,
                OutputStream os) throws IOException {
            os.write(encapsulatingProto.getBytes());
            for (ProtoOutputStream protoOutputStream : buffer) {
                byte[] protoBytes = protoOutputStream.getBytes();
                os.write(protoBytes);
            }
        }
    }

    public TraceBuffer(int bufferCapacity) {
        this(bufferCapacity, new ProtoOutputStreamProvider(), null);
    }

    public TraceBuffer(int bufferCapacity, ProtoProvider protoProvider,
            Consumer<T> protoDequeuedCallback) {
        mBufferCapacity = bufferCapacity;
        mProtoProvider = protoProvider;
        mProtoDequeuedCallback = protoDequeuedCallback;
        resetBuffer();
    }

    public int getAvailableSpace() {
        return mBufferCapacity - mBufferUsedSize;
    }

    /**
     * Returns buffer size.
     */
    public int size() {
        return mBuffer.size();
    }

    public void setCapacity(int capacity) {
        mBufferCapacity = capacity;
    }

    /**
     * Inserts the specified element into this buffer.
     *
     * @param proto the element to add
     * @throws IllegalStateException if the element cannot be added because it is larger
     *                               than the buffer size.
     */
    public void add(T proto) {
        int protoLength = mProtoProvider.getItemSize(proto);
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

    @VisibleForTesting
    public boolean contains(byte[] other) {
        return mBuffer.stream()
                .anyMatch(p -> Arrays.equals(mProtoProvider.getBytes(p), other));
    }

    /**
     * Writes the trace buffer to disk inside the encapsulatingProto.
     */
    public void writeTraceToFile(File traceFile, S encapsulatingProto)
            throws IOException {
        synchronized (mBufferLock) {
            traceFile.delete();
            try (OutputStream os = new FileOutputStream(traceFile)) {
                traceFile.setReadable(true /* readable */, false /* ownerOnly */);
                mProtoProvider.write(encapsulatingProto, mBuffer, os);
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

            P item = mBuffer.poll();
            if (item == null) {
                throw new IllegalStateException("No element to discard from buffer");
            }
            mBufferUsedSize -= mProtoProvider.getItemSize(item);
            availableSpace = getAvailableSpace();

            if (mProtoDequeuedCallback != null) {
                mProtoDequeuedCallback.accept(item);
            }
        }
    }

    /**
     * Removes all elements form the buffer
     */
    public void resetBuffer() {
        synchronized (mBufferLock) {
            if (mProtoDequeuedCallback != null) {
                for (T item : mBuffer) {
                    mProtoDequeuedCallback.accept(item);
                }
            }
            mBuffer.clear();
            mBufferUsedSize = 0;
        }
    }

    @VisibleForTesting
    public int getBufferSize() {
        return mBufferUsedSize;
    }

    /**
     * Returns the buffer status in human-readable form.
     */
    public String getStatus() {
        synchronized (mBufferLock) {
            return "Buffer size: " + mBufferCapacity + " bytes" + "\n"
                    + "Buffer usage: " + mBufferUsedSize + " bytes" + "\n"
                    + "Elements in the buffer: " + mBuffer.size();
        }
    }
}
