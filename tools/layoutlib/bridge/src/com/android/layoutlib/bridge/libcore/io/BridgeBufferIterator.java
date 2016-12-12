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

import java.nio.ByteBuffer;

import libcore.io.BufferIterator;

/**
 * Provides an implementation of {@link BufferIterator} over a {@link ByteBuffer}.
 */
public class BridgeBufferIterator extends BufferIterator {

    private final long mSize;
    private final ByteBuffer mByteBuffer;

    public BridgeBufferIterator(long size, ByteBuffer buffer) {
        mSize = size;
        mByteBuffer = buffer;
    }

    @Override
    public void seek(int offset) {
        assert offset <= mSize;
        mByteBuffer.position(offset);
    }

    @Override
    public int pos() {
        return mByteBuffer.position();
    }

    @Override
    public void skip(int byteCount) {
        int newPosition = mByteBuffer.position() + byteCount;
        assert newPosition <= mSize;
        mByteBuffer.position(newPosition);
    }

    @Override
    public void readByteArray(byte[] dst, int dstOffset, int byteCount) {
        assert dst.length >= dstOffset + byteCount;
        mByteBuffer.get(dst, dstOffset, byteCount);
    }

    @Override
    public byte readByte() {
        return mByteBuffer.get();
    }

    @Override
    public int readInt() {
        return mByteBuffer.getInt();
    }

    @Override
    public void readIntArray(int[] dst, int dstOffset, int intCount) {
        while (--intCount >= 0) {
            dst[dstOffset++] = mByteBuffer.getInt();
        }
    }

    @Override
    public short readShort() {
        return mByteBuffer.getShort();
    }
}
