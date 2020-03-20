/*
 * Copyright (C) 2008 The Android Open Source Project
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

import android.compat.annotation.UnsupportedAppUsage;

/**
 * An object that provides bitwise incremental read access to a byte array.
 *
 * This is useful, for example, when accessing a series of fields that
 * may not be aligned on byte boundaries.
 *
 * NOTE -- This class is not threadsafe.
 */
public class BitwiseInputStream {

    // The byte array being read from.
    private byte[] mBuf;

    // The current position offset, in bits, from the msb in byte 0.
    private int mPos;

    // The last valid bit offset.
    private int mEnd;

    /**
     * An exception to report access problems.
     */
    public static class AccessException extends Exception {
        public AccessException(String s) {
            super("BitwiseInputStream access failed: " + s);
        }
    }

    /**
     * Create object from byte array.
     *
     * @param buf a byte array containing data
     */
    @UnsupportedAppUsage
    public BitwiseInputStream(byte buf[]) {
        mBuf = buf;
        mEnd = buf.length << 3;
        mPos = 0;
    }

    /**
     * Return the number of bit still available for reading.
     */
    @UnsupportedAppUsage
    public int available() {
        return mEnd - mPos;
    }

    /**
     * Read some data and increment the current position.
     *
     * The 8-bit limit on access to bitwise streams is intentional to
     * avoid endianness issues.
     *
     * @param bits the amount of data to read (gte 0, lte 8)
     * @return byte of read data (possibly partially filled, from lsb)
     */
    @UnsupportedAppUsage
    public int read(int bits) throws AccessException {
        int index = mPos >>> 3;
        int offset = 16 - (mPos & 0x07) - bits;  // &7==%8
        if ((bits < 0) || (bits > 8) || ((mPos + bits) > mEnd)) {
            throw new AccessException("illegal read " +
                "(pos " + mPos + ", end " + mEnd + ", bits " + bits + ")");
        }
        int data = (mBuf[index] & 0xFF) << 8;
        if (offset < 8) data |= mBuf[index + 1] & 0xFF;
        data >>>= offset;
        data &= (-1 >>> (32 - bits));
        mPos += bits;
        return data;
    }

    /**
     * Read data in bulk into a byte array and increment the current position.
     *
     * @param bits the amount of data to read
     * @return newly allocated byte array of read data
     */
    @UnsupportedAppUsage
    public byte[] readByteArray(int bits) throws AccessException {
        int bytes = (bits >>> 3) + ((bits & 0x07) > 0 ? 1 : 0);  // &7==%8
        byte[] arr = new byte[bytes];
        for (int i = 0; i < bytes; i++) {
            int increment = Math.min(8, bits - (i << 3));
            arr[i] = (byte)(read(increment) << (8 - increment));
        }
        return arr;
    }

    /**
     * Increment the current position and ignore contained data.
     *
     * @param bits the amount by which to increment the position
     */
    @UnsupportedAppUsage
    public void skip(int bits) throws AccessException {
        if ((mPos + bits) > mEnd) {
            throw new AccessException("illegal skip " +
                "(pos " + mPos + ", end " + mEnd + ", bits " + bits + ")");
        }
        mPos += bits;
    }
}
