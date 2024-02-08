/*
 * Copyright (C) 2023 The Android Open Source Project
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
package com.android.internal.widget.remotecompose.core;

import java.util.Arrays;

/**
 * The base communication buffer capable of encoding and decoding various types
 */
public class WireBuffer {
    private static final int BUFFER_SIZE = 1024 * 1024 * 1;
    int mMaxSize;
    byte[] mBuffer;
    int mIndex = 0;
    int mStartingIndex = 0;
    int mSize = 0;

    public WireBuffer(int size) {
        mMaxSize = size;
        mBuffer = new byte[mMaxSize];
    }

    public WireBuffer() {
        this(BUFFER_SIZE);
    }

    public void resize(int need) {
        if (mSize + need >= mMaxSize) {
            mMaxSize = Math.max(mMaxSize * 2, mSize + need);
            mBuffer = Arrays.copyOf(mBuffer, mMaxSize);
        }
    }

    public byte[] getBuffer() {
        return mBuffer;
    }

    public int getMax_size() {
        return mMaxSize;
    }

    public int getIndex() {
        return mIndex;
    }

    public int getSize() {
        return mSize;
    }

    public void setIndex(int index) {
        this.mIndex = index;
    }

    public void start(int type) {
        mStartingIndex = mIndex;
        writeByte(type);
    }

    public void startWithSize(int type) {
        mStartingIndex = mIndex;
        writeByte(type);
        mIndex += 4; // skip ahead for the future size
    }

    public void endWithSize() {
        int size = mIndex - mStartingIndex;
        int currentIndex = mIndex;
        mIndex = mStartingIndex + 1; // (type)
        writeInt(size);
        mIndex = currentIndex;
    }

    public void reset() {
        mIndex = 0;
        mStartingIndex = 0;
        mSize = 0;
    }

    public int size() {
        return mSize;
    }

    public boolean available() {
        return mSize - mIndex > 0;
    }

    ///////////////////////////////////////////////////////////////////////////
    // Read values
    ///////////////////////////////////////////////////////////////////////////

    public int readOperationType() {
        return readByte();
    }

    public boolean readBoolean() {
        byte value = mBuffer[mIndex];
        mIndex++;
        return (value == 1);
    }

    public int readByte() {
        byte value = mBuffer[mIndex];
        mIndex++;
        return value;
    }

    public int readShort() {
        int v1 = (mBuffer[mIndex++] & 0xFF) << 8;
        int v2 = (mBuffer[mIndex++] & 0xFF) << 0;
        return v1 + v2;
    }

    public int readInt() {
        int v1 = (mBuffer[mIndex++] & 0xFF) << 24;
        int v2 = (mBuffer[mIndex++] & 0xFF) << 16;
        int v3 = (mBuffer[mIndex++] & 0xFF) << 8;
        int v4 = (mBuffer[mIndex++] & 0xFF) << 0;
        return v1 + v2 + v3 + v4;
    }

    public long readLong() {
        long v1 = (mBuffer[mIndex++] & 0xFFL) << 56;
        long v2 = (mBuffer[mIndex++] & 0xFFL) << 48;
        long v3 = (mBuffer[mIndex++] & 0xFFL) << 40;
        long v4 = (mBuffer[mIndex++] & 0xFFL) << 32;
        long v5 = (mBuffer[mIndex++] & 0xFFL) << 24;
        long v6 = (mBuffer[mIndex++] & 0xFFL) << 16;
        long v7 = (mBuffer[mIndex++] & 0xFFL) << 8;
        long v8 = (mBuffer[mIndex++] & 0xFFL) << 0;
        return v1 + v2 + v3 + v4 + v5 + v6 + v7 + v8;
    }

    public float readFloat() {
        return java.lang.Float.intBitsToFloat(readInt());
    }

    public double readDouble() {
        return java.lang.Double.longBitsToDouble(readLong());
    }

    public byte[] readBuffer() {
        int count = readInt();
        byte[] b = Arrays.copyOfRange(mBuffer, mIndex, mIndex + count);
        mIndex += count;
        return b;
    }

    public byte[] readBuffer(int maxSize) {
        int count = readInt();
        if (count < 0 || count > maxSize) {
            throw new RuntimeException("attempt read a buff of invalid size 0 <= "
                    + count + " > " + maxSize);
        }
        byte[] b = Arrays.copyOfRange(mBuffer, mIndex, mIndex + count);
        mIndex += count;
        return b;
    }

    public String readUTF8() {
        byte[] stringBuffer = readBuffer();
        return new String(stringBuffer);
    }

    public String readUTF8(int maxSize) {
        byte[] stringBuffer = readBuffer(maxSize);
        return new String(stringBuffer);
    }

    ///////////////////////////////////////////////////////////////////////////
    // Write values
    ///////////////////////////////////////////////////////////////////////////

    public void writeBoolean(boolean value) {
        resize(1);
        mBuffer[mIndex++] = (byte) ((value) ? 1 : 0);
        mSize++;
    }

    public void writeByte(int value) {
        resize(1);
        mBuffer[mIndex++] = (byte) value;
        mSize++;
    }

    public void writeShort(int value) {
        int need = 2;
        resize(need);
        mBuffer[mIndex++] = (byte) (value >>> 8 & 0xFF);
        mBuffer[mIndex++] = (byte) (value & 0xFF);
        mSize += need;
    }

    public void writeInt(int value) {
        int need = 4;
        resize(need);
        mBuffer[mIndex++] = (byte) (value >>> 24 & 0xFF);
        mBuffer[mIndex++] = (byte) (value >>> 16 & 0xFF);
        mBuffer[mIndex++] = (byte) (value >>> 8 & 0xFF);
        mBuffer[mIndex++] = (byte) (value & 0xFF);
        mSize += need;
    }

    public void writeLong(long value) {
        int need = 8;
        resize(need);
        mBuffer[mIndex++] = (byte) (value >>> 56 & 0xFF);
        mBuffer[mIndex++] = (byte) (value >>> 48 & 0xFF);
        mBuffer[mIndex++] = (byte) (value >>> 40 & 0xFF);
        mBuffer[mIndex++] = (byte) (value >>> 32 & 0xFF);
        mBuffer[mIndex++] = (byte) (value >>> 24 & 0xFF);
        mBuffer[mIndex++] = (byte) (value >>> 16 & 0xFF);
        mBuffer[mIndex++] = (byte) (value >>> 8 & 0xFF);
        mBuffer[mIndex++] = (byte) (value & 0xFF);
        mSize += need;
    }

    public void writeFloat(float value) {
        writeInt(Float.floatToRawIntBits(value));
    }

    public void writeDouble(double value) {
        writeLong(Double.doubleToRawLongBits(value));
    }

    public void writeBuffer(byte[] b) {
        resize(b.length + 4);
        writeInt(b.length);
        for (int i = 0; i < b.length; i++) {
            mBuffer[mIndex++] = b[i];

        }
        mSize += b.length;
    }

    public void writeUTF8(String content) {
        byte[] buffer = content.getBytes();
        writeBuffer(buffer);
    }

}

