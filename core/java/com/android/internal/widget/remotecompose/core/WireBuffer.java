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

import android.annotation.NonNull;

import java.util.Arrays;

/** The base communication buffer capable of encoding and decoding various types */
public class WireBuffer {
    private static final int BUFFER_SIZE = 1024 * 1024 * 1;
    int mMaxSize;
    @NonNull byte[] mBuffer;
    int mIndex = 0;
    int mStartingIndex = 0;
    int mSize = 0;

    /**
     * Create a wire buffer
     *
     * @param size the initial size of the buffer
     */
    public WireBuffer(int size) {
        mMaxSize = size;
        mBuffer = new byte[mMaxSize];
    }

    /** Create a wire buffer of default size */
    public WireBuffer() {
        this(BUFFER_SIZE);
    }

    private void resize(int need) {
        if (mSize + need >= mMaxSize) {
            mMaxSize = Math.max(mMaxSize * 2, mSize + need);
            mBuffer = Arrays.copyOf(mBuffer, mMaxSize);
        }
    }

    /**
     * get the wire buffer's underlying byte array. Note the array will be bigger that the used
     * portion
     *
     * @return byte array of the wire buffer
     */
    public @NonNull byte[] getBuffer() {
        return mBuffer;
    }

    /**
     * The current mix size of the buffer
     *
     * @return max size
     */
    public int getMax_size() {
        return mMaxSize;
    }

    /**
     * The current point in the buffer which will be written to
     *
     * @return index pointing into the buffer
     */
    public int getIndex() {
        return mIndex;
    }

    /**
     * The size of the buffer
     *
     * @return the size of the buffer
     */
    public int getSize() {
        return mSize;
    }

    /**
     * Reposition the pointer
     *
     * @param index the new position of the index
     */
    public void setIndex(int index) {
        this.mIndex = index;
    }

    /**
     * Write a byte representing the command into the buffer
     *
     * @param type the command id
     */
    public void start(int type) {
        mStartingIndex = mIndex;
        writeByte(type);
    }

    /**
     * Unused Todo remove?
     *
     * @param type the type of object to write
     */
    public void startWithSize(int type) {
        mStartingIndex = mIndex;
        writeByte(type);
        mIndex += 4; // skip ahead for the future size
    }

    /** Unused Todo remove? */
    public void endWithSize() {
        int size = mIndex - mStartingIndex;
        int currentIndex = mIndex;
        mIndex = mStartingIndex + 1; // (type)
        writeInt(size);
        mIndex = currentIndex;
    }

    /**
     * Reset the internal buffer
     *
     * @param expectedSize provided hint for the buffer size
     */
    public void reset(int expectedSize) {
        mIndex = 0;
        mStartingIndex = 0;
        mSize = 0;
        if (expectedSize >= mMaxSize) {
            resize(expectedSize);
        }
    }

    /**
     * return the size of the buffer todo rename to getSize
     *
     * @return the size of the buffer
     */
    public int size() {
        return mSize;
    }

    /**
     * Bytes available
     *
     * @return the size - index
     */
    public boolean available() {
        return mSize - mIndex > 0;
    }

    ///////////////////////////////////////////////////////////////////////////
    // Read values
    ///////////////////////////////////////////////////////////////////////////

    /**
     * read the operation type (reads a single byte)
     *
     * @return the byte cast to an integer
     */
    public int readOperationType() {
        return readByte();
    }

    /**
     * Read a boolean (stored as a byte 1 = true)
     *
     * @return boolean of the byte
     */
    public boolean readBoolean() {
        byte value = mBuffer[mIndex];
        mIndex++;
        return (value == 1);
    }

    /**
     * read a single byte byte
     *
     * @return byte from 0..255 as an Integer
     */
    public int readByte() {
        int value = 0xFF & mBuffer[mIndex];
        mIndex++;
        return value;
    }

    /**
     * read a short [byte n] << 8 | [byte n+1]; index increast by 2
     *
     * @return return a short cast as an integer
     */
    public int readShort() {
        int v1 = (mBuffer[mIndex++] & 0xFF) << 8;
        int v2 = (mBuffer[mIndex++] & 0xFF) << 0;
        return v1 + v2;
    }

    /**
     * Read an integer without incrementing the index
     *
     * @return the integer
     */
    public int peekInt() {
        int tmp = mIndex;
        int v1 = (mBuffer[tmp++] & 0xFF) << 24;
        int v2 = (mBuffer[tmp++] & 0xFF) << 16;
        int v3 = (mBuffer[tmp++] & 0xFF) << 8;
        int v4 = (mBuffer[tmp++] & 0xFF) << 0;
        return v1 + v2 + v3 + v4;
    }

    /**
     * Read an integer. index increased by 4
     *
     * @return integer
     */
    public int readInt() {
        int v1 = (mBuffer[mIndex++] & 0xFF) << 24;
        int v2 = (mBuffer[mIndex++] & 0xFF) << 16;
        int v3 = (mBuffer[mIndex++] & 0xFF) << 8;
        int v4 = (mBuffer[mIndex++] & 0xFF) << 0;
        return v1 + v2 + v3 + v4;
    }

    /**
     * Read a long index is increased by 8
     *
     * @return long
     */
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

    /**
     * Read a 32 bit float IEEE standard index is increased by 4
     *
     * @return the float
     */
    public float readFloat() {
        return java.lang.Float.intBitsToFloat(readInt());
    }

    /**
     * Read a 64 bit double index is increased by 8
     *
     * @return double
     */
    public double readDouble() {
        return java.lang.Double.longBitsToDouble(readLong());
    }

    /**
     * Read a byte buffer bytes are encoded as 4 byte length followed by length bytes index is
     * increased by 4 + number of bytes
     *
     * @return byte array
     */
    public @NonNull byte[] readBuffer() {
        int count = readInt();
        byte[] b = Arrays.copyOfRange(mBuffer, mIndex, mIndex + count);
        mIndex += count;
        return b;
    }

    /**
     * Read a byte buffer limited to max size. bytes are encoded as 4 byte length followed by length
     * bytes index is increased by 4 + number of bytes Throw an exception if the read excedes the
     * max size. This is the preferred form of read buffer.
     *
     * @return byte array
     */
    public @NonNull byte[] readBuffer(int maxSize) {
        int count = readInt();
        if (count < 0 || count > maxSize) {
            throw new RuntimeException(
                    "attempt read a buff of invalid size 0 <= " + count + " > " + maxSize);
        }
        byte[] b = Arrays.copyOfRange(mBuffer, mIndex, mIndex + count);
        mIndex += count;
        return b;
    }

    /**
     * Read a string encoded in UTF8 The buffer is red with readBuffer and converted to a String
     *
     * @return unicode string
     */
    @NonNull
    public String readUTF8() {
        byte[] stringBuffer = readBuffer();
        return new String(stringBuffer);
    }

    /**
     * Read a string encoded in UTF8 The buffer is red with readBuffer and converted to a String
     * This is the preferred readUTF8 because it catches errors
     *
     * @return unicode string
     */
    @NonNull
    public String readUTF8(int maxSize) {
        byte[] stringBuffer = readBuffer(maxSize);
        return new String(stringBuffer);
    }

    ///////////////////////////////////////////////////////////////////////////
    // Write values
    ///////////////////////////////////////////////////////////////////////////

    /**
     * Write a boolean value. (written as a byte 1=true)
     *
     * @param value value to write
     */
    public void writeBoolean(boolean value) {
        resize(1);
        mBuffer[mIndex++] = (byte) (value ? 1 : 0);
        mSize++;
    }

    /**
     * Write a byte value
     *
     * @param value value to write
     */
    public void writeByte(int value) {
        resize(1);
        mBuffer[mIndex++] = (byte) value;
        mSize++;
    }

    /**
     * Write a short value
     *
     * @param value value to write
     */
    public void writeShort(int value) {
        int need = 2;
        resize(need);
        mBuffer[mIndex++] = (byte) (value >>> 8 & 0xFF);
        mBuffer[mIndex++] = (byte) (value & 0xFF);
        mSize += need;
    }

    /**
     * Write a int (4 byte) value
     *
     * @param value value to write
     */
    public void writeInt(int value) {
        int need = 4;
        resize(need);
        mBuffer[mIndex++] = (byte) (value >>> 24 & 0xFF);
        mBuffer[mIndex++] = (byte) (value >>> 16 & 0xFF);
        mBuffer[mIndex++] = (byte) (value >>> 8 & 0xFF);
        mBuffer[mIndex++] = (byte) (value & 0xFF);
        mSize += need;
    }

    /**
     * Write a long (8 byte) value
     *
     * @param value value to write
     */
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

    /**
     * Write a 32 bit IEEE float value
     *
     * @param value value to write
     */
    public void writeFloat(float value) {
        writeInt(Float.floatToRawIntBits(value));
    }

    /**
     * Write a 64 bit IEEE double value
     *
     * @param value value to write
     */
    public void writeDouble(double value) {
        writeLong(Double.doubleToRawLongBits(value));
    }

    /**
     * Write a buffer The buffer length is first written followed by the bytes
     *
     * @param b array of bytes write
     */
    public void writeBuffer(@NonNull byte[] b) {
        resize(b.length + 4);
        writeInt(b.length);
        for (int i = 0; i < b.length; i++) {
            mBuffer[mIndex++] = b[i];
        }
        mSize += b.length;
    }

    /**
     * Write a string is encoded as UTF8
     *
     * @param content the string to write
     */
    public void writeUTF8(@NonNull String content) {
        byte[] buffer = content.getBytes();
        writeBuffer(buffer);
    }
}
