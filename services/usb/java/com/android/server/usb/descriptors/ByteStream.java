/*
 * Copyright (C) 2017 The Android Open Source Project
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
package com.android.server.usb.descriptors;

import android.annotation.NonNull;

/**
 * @hide
 * A stream interface wrapping a byte array. Very much like a java.io.ByteArrayInputStream
 * but with the capability to "back up" in situations where the parser discovers that a
 * UsbDescriptor has overrun its length.
 */
public class ByteStream {
    private static final String TAG = "ByteStream";

    /** The byte array being wrapped */
    @NonNull
    private final byte[] mBytes; // this is never null.

    /**
     * The index into the byte array to be read next.
     * This value is altered by reading data out of the stream
     * (using either the getByte() or unpack*() methods), or alternatively
     * by explicitly offseting the stream position with either
     * advance() or reverse().
     */
    private int mIndex;

    /*
     * This member used with resetReadCount() & getReadCount() can be used to determine how many
     * bytes a UsbDescriptor subclass ACTUALLY reads (as opposed to what its length field says).
     * using this info, the parser can mark a descriptor as valid or invalid and correct the stream
     * position with advance() & reverse() to keep from "getting lost" in the descriptor stream.
     */
    private int mReadCount;

    /**
     * Create a ByteStream object wrapping the specified byte array.
     *
     * @param bytes The byte array containing the raw descriptor information retrieved from
     *              the USB device.
     * @throws IllegalArgumentException
     */
    public ByteStream(@NonNull byte[] bytes) {
        if (bytes == null) {
            throw new IllegalArgumentException();
        }
        mBytes = bytes;
    }

    /**
     * Resets the running count of bytes read so that later we can see how much more has been read.
     */
    public void resetReadCount() {
        mReadCount = 0;
    }

    /**
     * Retrieves the running count of bytes read from the stream.
     */
    public int getReadCount() {
        return mReadCount;
    }

    /**
     * @return The value of the next byte in the stream without advancing the stream.
     * Does not affect the running count as the byte hasn't been "consumed".
     * @throws IndexOutOfBoundsException
     */
    public byte peekByte() {
        if (available() > 0) {
            return mBytes[mIndex + 1];
        } else {
            throw new IndexOutOfBoundsException();
        }
    }

    /**
     * @return the next byte from the stream and advances the stream and the read count. Note
     * that this is a signed byte (as is the case of byte in Java). The user may need to understand
     * from context if it should be interpreted as an unsigned value.
     * @throws IndexOutOfBoundsException
     */
    public byte getByte() {
        if (available() > 0) {
            mReadCount++;
            return mBytes[mIndex++];
        } else {
            throw new IndexOutOfBoundsException();
        }
    }

    /**
     * Reads 2 bytes in *little endian format* from the stream and composes a 16-bit integer.
     * As we are storing the 2-byte value in a 4-byte integer, the upper 2 bytes are always
     * 0, essentially making the returned value *unsigned*.
     * @return The 16-bit integer (packed into the lower 2 bytes of an int) encoded by the
     * next 2 bytes in the stream.
     * @throws IndexOutOfBoundsException
     */
    public int unpackUsbWord() {
        if (available() >= 2) {
            int b0 = getByte();
            int b1 = getByte();
            return ((b1 << 8) & 0x0000FF00) | (b0 & 0x000000FF);
        } else {
            throw new IndexOutOfBoundsException();
        }
    }

    /**
     * Reads 3 bytes in *little endian format* from the stream and composes a 24-bit integer.
     * As we are storing the 3-byte value in a 4-byte integer, the upper byte is always
     * 0, essentially making the returned value *unsigned*.
     * @return The 24-bit integer (packed into the lower 3 bytes of an int) encoded by the
     * next 3 bytes in the stream.
     * @throws IndexOutOfBoundsException
     */
    public int unpackUsbTriple() {
        if (available() >= 3) {
            int b0 = getByte();
            int b1 = getByte();
            int b2 = getByte();
            return ((b2 << 16) & 0x00FF0000) | ((b1 << 8) & 0x0000FF00) | (b0 & 0x000000FF);
        } else {
            throw new IndexOutOfBoundsException();
        }
    }

    /**
     * Advances the logical position in the stream. Affects the running count also.
     * @param numBytes The number of bytes to advance.
     * @throws IndexOutOfBoundsException
     * @throws IllegalArgumentException
     */
    public void advance(int numBytes) {
        if (numBytes < 0) {
            // Positive offsets only
            throw new IllegalArgumentException();
        }
        // do arithmetic and comparison in long to ovoid potention integer overflow
        long longNewIndex = (long) mIndex + (long) numBytes;
        if (longNewIndex < (long) mBytes.length) {
            mReadCount += numBytes;
            mIndex += numBytes;
        } else {
            throw new IndexOutOfBoundsException();
        }
    }

    /**
     * Reverse the logical position in the stream. Affects the running count also.
     * @param numBytes The (positive) number of bytes to reverse.
     * @throws IndexOutOfBoundsException
     * @throws IllegalArgumentException
     */
    public void reverse(int numBytes) {
        if (numBytes < 0) {
            // Positive (reverse) offsets only
            throw new IllegalArgumentException();
        }
        if (mIndex >= numBytes) {
            mReadCount -= numBytes;
            mIndex -= numBytes;
        } else {
            throw new IndexOutOfBoundsException();
        }
    }

    /**
     * @return The number of bytes available to be read in the stream.
     */
    public int available() {
        return mBytes.length - mIndex;
    }
}
