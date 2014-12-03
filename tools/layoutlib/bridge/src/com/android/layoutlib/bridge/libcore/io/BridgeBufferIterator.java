/*
 * Copyright (C) 2014 The Android Open Source Project
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

package com.android.layoutlib.bridge.libcore.io;

import java.nio.MappedByteBuffer;

import libcore.io.BufferIterator;

/**
 * Provides an implementation of {@link BufferIterator} over a {@link MappedByteBuffer}.
 */
public class BridgeBufferIterator extends BufferIterator {

    private int mPosition;
    private final long mSize;
    private final MappedByteBuffer mMappedByteBuffer;

    public BridgeBufferIterator(long size, MappedByteBuffer buffer) {
        mSize = size;
        mMappedByteBuffer = buffer;
    }

    @Override
    public void seek(int offset) {
        assert offset < mSize;
       mPosition = offset;
    }

    @Override
    public void skip(int byteCount) {
        assert mPosition + byteCount <= mSize;
        mPosition += byteCount;
    }

    @Override
    public void readByteArray(byte[] dst, int dstOffset, int byteCount) {
        assert dst.length >= dstOffset + byteCount;
        mMappedByteBuffer.position(mPosition);
        mMappedByteBuffer.get(dst, dstOffset, byteCount);
        mPosition = mMappedByteBuffer.position();
    }

    @Override
    public byte readByte() {
        mMappedByteBuffer.position(mPosition);
        byte b = mMappedByteBuffer.get();
        mPosition = mMappedByteBuffer.position();
        return b;
    }

    @Override
    public int readInt() {
        mMappedByteBuffer.position(mPosition);
        int i = mMappedByteBuffer.getInt();
        mPosition = mMappedByteBuffer.position();
        return i;
    }

    @Override
    public void readIntArray(int[] dst, int dstOffset, int intCount) {
        mMappedByteBuffer.position(mPosition);
        while (--intCount >= 0) {
            dst[dstOffset++] = mMappedByteBuffer.getInt();
        }
        mPosition = mMappedByteBuffer.position();
    }

    @Override
    public short readShort() {
        mMappedByteBuffer.position(mPosition);
        short s = mMappedByteBuffer.getShort();
        mPosition = mMappedByteBuffer.position();
        return s;
    }
}
