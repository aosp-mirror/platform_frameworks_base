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

package com.android.server.integrity.model;

import java.util.BitSet;

/** A wrapper class for writing a stream of bits. */
public class BitOutputStream {

    private BitSet mBitSet;
    private int mIndex;

    public BitOutputStream() {
        mBitSet = new BitSet();
        mIndex = 0;
    }

    /**
     * Set the next number of bits in the stream to value.
     *
     * @param numOfBits The number of bits used to represent the value.
     * @param value The value to convert to bits.
     */
    public void setNext(int numOfBits, int value) {
        if (numOfBits <= 0) {
            return;
        }
        int offset = 1 << (numOfBits - 1);
        while (numOfBits-- > 0) {
            mBitSet.set(mIndex, (value & offset) != 0);
            offset >>= 1;
            mIndex++;
        }
    }

    /**
     * Set the next bit in the stream to value.
     *
     * @param value The value to set the bit to.
     */
    public void setNext(boolean value) {
        mBitSet.set(mIndex, value);
        mIndex++;
    }

    /** Set the next bit in the stream to true. */
    public void setNext() {
        setNext(/* value= */ true);
    }

    /** Convert BitSet in big-endian to ByteArray in big-endian. */
    public byte[] toByteArray() {
        int bitSetSize = mBitSet.length();
        int numOfBytes = bitSetSize / 8;
        if (bitSetSize % 8 != 0) {
            numOfBytes++;
        }
        byte[] bytes = new byte[numOfBytes];
        for (int i = 0; i < mBitSet.length(); i++) {
            if (mBitSet.get(i)) {
                bytes[i / 8] |= 1 << (7 - (i % 8));
            }
        }
        return bytes;
    }

    /** Clear the stream. */
    public void clear() {
        mBitSet.clear();
        mIndex = 0;
    }
}
