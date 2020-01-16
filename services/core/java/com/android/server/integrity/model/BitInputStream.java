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

/** A wrapper class for reading a stream of bits.
 *
 * <p>Note: this class reads from underlying stream byte-by-byte. It is advised to apply buffering
 * to underlying streams.
 */
public class BitInputStream {

    private long mBitsRead;

    private InputStream mInputStream;

    private byte mCurrentByte;

    public BitInputStream(InputStream inputStream) {
        mInputStream = inputStream;
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
            if (mBitsRead % 8 == 0) {
                mCurrentByte = getNextByte();
            }
            int offset = 7 - (int) (mBitsRead % 8);

            component <<= 1;
            component |= (mCurrentByte >>> offset) & 1;

            mBitsRead++;
        }

        return component;
    }

    /** Check if there are bits left in the stream. */
    public boolean hasNext() throws IOException {
        return mInputStream.available() > 0;
    }

    private byte getNextByte() throws IOException {
        return (byte) mInputStream.read();
    }
}
