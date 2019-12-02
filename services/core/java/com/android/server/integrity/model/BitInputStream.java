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

/** A wrapper class for reading a stream of bits. */
public class BitInputStream {

    private byte[] mRuleBytes;
    private long mBitPointer;

    public BitInputStream(byte[] ruleBytes) {
        this.mRuleBytes = ruleBytes;
        this.mBitPointer = 0;
    }

    /**
     * Read the next number of bits from the stream.
     *
     * @param numOfBits The number of bits to read.
     * @return The value read from the stream.
     */
    public int getNext(int numOfBits) {
        int component = 0;
        int count = 0;

        int idx = (int) (mBitPointer / 8);
        int offset = 7 - (int) (mBitPointer % 8);

        while (count++ < numOfBits) {
            if (idx >= mRuleBytes.length) {
                throw new IllegalArgumentException(String.format("Invalid byte index: %d", idx));
            }

            component <<= 1;
            component |= (mRuleBytes[idx] >>> offset) & 1;

            offset--;
            if (offset == -1) {
                idx++;
                offset = 7;
            }
        }

        mBitPointer += numOfBits;
        return component;
    }

    /** Check if there are bits left in the stream. */
    public boolean hasNext() {
        return mBitPointer / 8 < mRuleBytes.length;
    }
}
