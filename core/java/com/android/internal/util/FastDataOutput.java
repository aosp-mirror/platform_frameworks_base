/*
 * Copyright (C) 2020 The Android Open Source Project
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

import android.annotation.NonNull;

import dalvik.system.VMRuntime;

import java.io.BufferedOutputStream;
import java.io.Closeable;
import java.io.DataOutput;
import java.io.DataOutputStream;
import java.io.Flushable;
import java.io.IOException;
import java.io.OutputStream;
import java.util.HashMap;
import java.util.Objects;

/**
 * Optimized implementation of {@link DataOutput} which buffers data in memory
 * before flushing to the underlying {@link OutputStream}.
 * <p>
 * Benchmarks have demonstrated this class is 2x more efficient than using a
 * {@link DataOutputStream} with a {@link BufferedOutputStream}.
 */
public class FastDataOutput implements DataOutput, Flushable, Closeable {
    protected static final int MAX_UNSIGNED_SHORT = 65_535;

    protected static final int DEFAULT_BUFFER_SIZE = 32_768;

    protected final VMRuntime mRuntime;

    protected final byte[] mBuffer;
    protected final int mBufferCap;

    private OutputStream mOut;
    protected int mBufferPos;

    /**
     * Values that have been "interned" by {@link #writeInternedUTF(String)}.
     */
    private final HashMap<String, Integer> mStringRefs = new HashMap<>();

    public FastDataOutput(@NonNull OutputStream out, int bufferSize) {
        mRuntime = VMRuntime.getRuntime();
        if (bufferSize < 8) {
            throw new IllegalArgumentException();
        }

        mBuffer = (byte[]) mRuntime.newNonMovableArray(byte.class, bufferSize);
        mBufferCap = mBuffer.length;

        setOutput(out);
    }

    /**
     * Obtain a {@link FastDataOutput} configured with the given
     * {@link OutputStream} and which encodes large code-points using 3-byte
     * sequences.
     * <p>
     * This <em>is</em> compatible with the {@link DataOutput} API contract,
     * which specifies that large code-points must be encoded with 3-byte
     * sequences.
     */
    public static FastDataOutput obtain(@NonNull OutputStream out) {
        return new FastDataOutput(out, DEFAULT_BUFFER_SIZE);
    }

    /**
     * Release a {@link FastDataOutput} to potentially be recycled. You must not
     * interact with the object after releasing it.
     */
    public void release() {
        if (mBufferPos > 0) {
            throw new IllegalStateException("Lingering data, call flush() before releasing.");
        }

        mOut = null;
        mBufferPos = 0;
        mStringRefs.clear();
    }

    /**
     * Re-initializes the object for the new output.
     */
    protected void setOutput(@NonNull OutputStream out) {
        if (mOut != null) {
            throw new IllegalStateException("setOutput() called before calling release()");
        }

        mOut = Objects.requireNonNull(out);
        mBufferPos = 0;
        mStringRefs.clear();
    }

    protected void drain() throws IOException {
        if (mBufferPos > 0) {
            mOut.write(mBuffer, 0, mBufferPos);
            mBufferPos = 0;
        }
    }

    @Override
    public void flush() throws IOException {
        drain();
        mOut.flush();
    }

    @Override
    public void close() throws IOException {
        mOut.close();
        release();
    }

    @Override
    public void write(int b) throws IOException {
        writeByte(b);
    }

    @Override
    public void write(byte[] b) throws IOException {
        write(b, 0, b.length);
    }

    @Override
    public void write(byte[] b, int off, int len) throws IOException {
        if (mBufferCap < len) {
            drain();
            mOut.write(b, off, len);
        } else {
            if (mBufferCap - mBufferPos < len) drain();
            System.arraycopy(b, off, mBuffer, mBufferPos, len);
            mBufferPos += len;
        }
    }

    @Override
    public void writeUTF(String s) throws IOException {
        final int len = (int) ModifiedUtf8.countBytes(s, false);
        if (len > MAX_UNSIGNED_SHORT) {
            throw new IOException("Modified UTF-8 length too large: " + len);
        }

        // Attempt to write directly to buffer space if there's enough room,
        // otherwise fall back to chunking into place
        if (mBufferCap >= 2 + len) {
            if (mBufferCap - mBufferPos < 2 + len) drain();
            writeShort(len);
            ModifiedUtf8.encode(mBuffer, mBufferPos, s);
            mBufferPos += len;
        } else {
            final byte[] tmp = (byte[]) mRuntime.newNonMovableArray(byte.class, len + 1);
            ModifiedUtf8.encode(tmp, 0, s);
            writeShort(len);
            write(tmp, 0, len);
        }
    }

    /**
     * Write a {@link String} value with the additional signal that the given
     * value is a candidate for being canonicalized, similar to
     * {@link String#intern()}.
     * <p>
     * Canonicalization is implemented by writing each unique string value once
     * the first time it appears, and then writing a lightweight {@code short}
     * reference when that string is written again in the future.
     *
     * @see FastDataInput#readInternedUTF()
     */
    public void writeInternedUTF(@NonNull String s) throws IOException {
        Integer ref = mStringRefs.get(s);
        if (ref != null) {
            writeShort(ref);
        } else {
            writeShort(MAX_UNSIGNED_SHORT);
            writeUTF(s);

            // We can only safely intern when we have remaining values; if we're
            // full we at least sent the string value above
            ref = mStringRefs.size();
            if (ref < MAX_UNSIGNED_SHORT) {
                mStringRefs.put(s, ref);
            }
        }
    }

    @Override
    public void writeBoolean(boolean v) throws IOException {
        writeByte(v ? 1 : 0);
    }

    @Override
    public void writeByte(int v) throws IOException {
        if (mBufferCap - mBufferPos < 1) drain();
        mBuffer[mBufferPos++] = (byte) ((v >>  0) & 0xff);
    }

    @Override
    public void writeShort(int v) throws IOException {
        if (mBufferCap - mBufferPos < 2) drain();
        mBuffer[mBufferPos++] = (byte) ((v >>  8) & 0xff);
        mBuffer[mBufferPos++] = (byte) ((v >>  0) & 0xff);
    }

    @Override
    public void writeChar(int v) throws IOException {
        writeShort((short) v);
    }

    @Override
    public void writeInt(int v) throws IOException {
        if (mBufferCap - mBufferPos < 4) drain();
        mBuffer[mBufferPos++] = (byte) ((v >> 24) & 0xff);
        mBuffer[mBufferPos++] = (byte) ((v >> 16) & 0xff);
        mBuffer[mBufferPos++] = (byte) ((v >>  8) & 0xff);
        mBuffer[mBufferPos++] = (byte) ((v >>  0) & 0xff);
    }

    @Override
    public void writeLong(long v) throws IOException {
        if (mBufferCap - mBufferPos < 8) drain();
        int i = (int) (v >> 32);
        mBuffer[mBufferPos++] = (byte) ((i >> 24) & 0xff);
        mBuffer[mBufferPos++] = (byte) ((i >> 16) & 0xff);
        mBuffer[mBufferPos++] = (byte) ((i >>  8) & 0xff);
        mBuffer[mBufferPos++] = (byte) ((i >>  0) & 0xff);
        i = (int) v;
        mBuffer[mBufferPos++] = (byte) ((i >> 24) & 0xff);
        mBuffer[mBufferPos++] = (byte) ((i >> 16) & 0xff);
        mBuffer[mBufferPos++] = (byte) ((i >>  8) & 0xff);
        mBuffer[mBufferPos++] = (byte) ((i >>  0) & 0xff);
    }

    @Override
    public void writeFloat(float v) throws IOException {
        writeInt(Float.floatToIntBits(v));
    }

    @Override
    public void writeDouble(double v) throws IOException {
        writeLong(Double.doubleToLongBits(v));
    }

    @Override
    public void writeBytes(String s) throws IOException {
        // Callers should use writeUTF()
        throw new UnsupportedOperationException();
    }

    @Override
    public void writeChars(String s) throws IOException {
        // Callers should use writeUTF()
        throw new UnsupportedOperationException();
    }
}
