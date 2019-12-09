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

import java.io.IOException;
import java.io.InputStream;

/** A wrapper class for reading a stream of bits. */
public class BitInputStream {

    private long mBitPointer;
    private boolean mReadFromStream;

    private byte[] mRuleBytes;
    private InputStream mRuleInputStream;

    private byte mCurrentRuleByte;

    public BitInputStream(byte[] ruleBytes) {
        this.mRuleBytes = ruleBytes;
        this.mBitPointer = 0;
        this.mReadFromStream = false;
    }

    public BitInputStream(InputStream ruleInputStream) {
        this.mRuleInputStream = ruleInputStream;
        this.mReadFromStream = true;
    }

    /**
     * Read the next number of bits from the stream.
     *
     * @param numOfBits The number of bits to read.
     * @return The value read from the stream.
     */
    public int getNext(int numOfBits) throws IOException {
        int component = 0;
        int count = 0;

        while (count++ < numOfBits) {
            if (mBitPointer % 8 == 0) {
                mCurrentRuleByte = getNextByte();
            }
            int offset = 7 - (int) (mBitPointer % 8);

            component <<= 1;
            component |= (mCurrentRuleByte >>> offset) & 1;

            mBitPointer++;
        }

        return component;
    }

    /** Check if there are bits left in the stream. */
    public boolean hasNext() throws IOException {
        if (mReadFromStream) {
            return mRuleInputStream.available() > 0;
        } else {
            return mBitPointer / 8 < mRuleBytes.length;
        }
    }

    private byte getNextByte() throws IOException {
        if (mReadFromStream) {
            return (byte) mRuleInputStream.read();
        } else {
            int idx = (int) (mBitPointer / 8);
            if (idx >= mRuleBytes.length) {
                throw new IllegalArgumentException(String.format("Invalid byte index: %d", idx));
            }
            return mRuleBytes[idx];
        }
    }
}
