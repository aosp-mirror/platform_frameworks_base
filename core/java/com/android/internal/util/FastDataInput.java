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
import android.util.CharsetUtils;

import dalvik.system.VMRuntime;

import java.io.BufferedInputStream;
import java.io.Closeable;
import java.io.DataInput;
import java.io.DataInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;
import java.util.Objects;

/**
 * Optimized implementation of {@link DataInput} which buffers data in memory
 * from the underlying {@link InputStream}.
 * <p>
 * Benchmarks have demonstrated this class is 3x more efficient than using a
 * {@link DataInputStream} with a {@link BufferedInputStream}.
 */
public class FastDataInput implements DataInput, Closeable {
    private static final int MAX_UNSIGNED_SHORT = 65_535;

    private final VMRuntime mRuntime;
    private final InputStream mIn;

    private final byte[] mBuffer;
    private final long mBufferPtr;
    private final int mBufferCap;

    private int mBufferPos;
    private int mBufferLim;

    /**
     * Values that have been "interned" by {@link #readInternedUTF()}.
     */
    private int mStringRefCount = 0;
    private String[] mStringRefs = new String[32];

    public FastDataInput(@NonNull InputStream in, int bufferSize) {
        mRuntime = VMRuntime.getRuntime();
        mIn = Objects.requireNonNull(in);
        if (bufferSize < 8) {
            throw new IllegalArgumentException();
        }

        mBuffer = (byte[]) mRuntime.newNonMovableArray(byte.class, bufferSize);
        mBufferPtr = mRuntime.addressOf(mBuffer);
        mBufferCap = mBuffer.length;
    }

    private void fill(int need) throws IOException {
        final int remain = mBufferLim - mBufferPos;
        System.arraycopy(mBuffer, mBufferPos, mBuffer, 0, remain);
        mBufferPos = 0;
        mBufferLim = remain;
        need -= remain;

        while (need > 0) {
            int c = mIn.read(mBuffer, mBufferLim, mBufferCap - mBufferLim);
            if (c == -1) {
                throw new EOFException();
            } else {
                mBufferLim += c;
                need -= c;
            }
        }
    }

    @Override
    public void close() throws IOException {
        mIn.close();
    }

    @Override
    public void readFully(byte[] b) throws IOException {
        readFully(b, 0, b.length);
    }

    @Override
    public void readFully(byte[] b, int off, int len) throws IOException {
        // Attempt to read directly from buffer space if there's enough room,
        // otherwise fall back to chunking into place
        if (mBufferCap >= len) {
            if (mBufferLim - mBufferPos < len) fill(len);
            System.arraycopy(mBuffer, mBufferPos, b, off, len);
            mBufferPos += len;
        } else {
            final int remain = mBufferLim - mBufferPos;
            System.arraycopy(mBuffer, mBufferPos, b, off, remain);
            mBufferPos += remain;
            off += remain;
            len -= remain;

            while (len > 0) {
                int c = mIn.read(b, off, len);
                if (c == -1) {
                    throw new EOFException();
                } else {
                    off += c;
                    len -= c;
                }
            }
        }
    }

    @Override
    public String readUTF() throws IOException {
        // Attempt to read directly from buffer space if there's enough room,
        // otherwise fall back to chunking into place
        final int len = readUnsignedShort();
        if (mBufferCap > len) {
            if (mBufferLim - mBufferPos < len) fill(len);
            final String res = CharsetUtils.fromModifiedUtf8Bytes(mBufferPtr, mBufferPos, len);
            mBufferPos += len;
            return res;
        } else {
            final byte[] tmp = (byte[]) mRuntime.newNonMovableArray(byte.class, len + 1);
            readFully(tmp, 0, len);
            return CharsetUtils.fromModifiedUtf8Bytes(mRuntime.addressOf(tmp), 0, len);
        }
    }

    /**
     * Read a {@link String} value with the additional signal that the given
     * value is a candidate for being canonicalized, similar to
     * {@link String#intern()}.
     * <p>
     * Canonicalization is implemented by writing each unique string value once
     * the first time it appears, and then writing a lightweight {@code short}
     * reference when that string is written again in the future.
     *
     * @see FastDataOutput#writeInternedUTF(String)
     */
    public @NonNull String readInternedUTF() throws IOException {
        final int ref = readUnsignedShort();
        if (ref == MAX_UNSIGNED_SHORT) {
            final String s = readUTF();

            // We can only safely intern when we have remaining values; if we're
            // full we at least sent the string value above
            if (mStringRefCount < MAX_UNSIGNED_SHORT) {
                if (mStringRefCount == mStringRefs.length) {
                    mStringRefs = Arrays.copyOf(mStringRefs,
                            mStringRefCount + (mStringRefCount >> 1));
                }
                mStringRefs[mStringRefCount++] = s;
            }

            return s;
        } else {
            return mStringRefs[ref];
        }
    }

    @Override
    public boolean readBoolean() throws IOException {
        return readByte() != 0;
    }

    /**
     * Returns the same decoded value as {@link #readByte()} but without
     * actually consuming the underlying data.
     */
    public byte peekByte() throws IOException {
        if (mBufferLim - mBufferPos < 1) fill(1);
        return mBuffer[mBufferPos];
    }

    @Override
    public byte readByte() throws IOException {
        if (mBufferLim - mBufferPos < 1) fill(1);
        return mBuffer[mBufferPos++];
    }

    @Override
    public int readUnsignedByte() throws IOException {
        return Byte.toUnsignedInt(readByte());
    }

    @Override
    public short readShort() throws IOException {
        if (mBufferLim - mBufferPos < 2) fill(2);
        return (short) (((mBuffer[mBufferPos++] & 0xff) <<  8) |
                        ((mBuffer[mBufferPos++] & 0xff) <<  0));
    }

    @Override
    public int readUnsignedShort() throws IOException {
        return Short.toUnsignedInt((short) readShort());
    }

    @Override
    public char readChar() throws IOException {
        return (char) readShort();
    }

    @Override
    public int readInt() throws IOException {
        if (mBufferLim - mBufferPos < 4) fill(4);
        return (((mBuffer[mBufferPos++] & 0xff) << 24) |
                ((mBuffer[mBufferPos++] & 0xff) << 16) |
                ((mBuffer[mBufferPos++] & 0xff) <<  8) |
                ((mBuffer[mBufferPos++] & 0xff) <<  0));
    }

    @Override
    public long readLong() throws IOException {
        if (mBufferLim - mBufferPos < 8) fill(8);
        int h = ((mBuffer[mBufferPos++] & 0xff) << 24) |
                ((mBuffer[mBufferPos++] & 0xff) << 16) |
                ((mBuffer[mBufferPos++] & 0xff) <<  8) |
                ((mBuffer[mBufferPos++] & 0xff) <<  0);
        int l = ((mBuffer[mBufferPos++] & 0xff) << 24) |
                ((mBuffer[mBufferPos++] & 0xff) << 16) |
                ((mBuffer[mBufferPos++] & 0xff) <<  8) |
                ((mBuffer[mBufferPos++] & 0xff) <<  0);
        return (((long) h) << 32L) | ((long) l) & 0xffffffffL;
    }

    @Override
    public float readFloat() throws IOException {
        return Float.intBitsToFloat(readInt());
    }

    @Override
    public double readDouble() throws IOException {
        return Double.longBitsToDouble(readLong());
    }

    @Override
    public int skipBytes(int n) throws IOException {
        // Callers should read data piecemeal
        throw new UnsupportedOperationException();
    }

    @Override
    public String readLine() throws IOException {
        // Callers should read data piecemeal
        throw new UnsupportedOperationException();
    }
}
