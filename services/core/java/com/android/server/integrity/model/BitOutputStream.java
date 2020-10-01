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

import static com.android.server.integrity.model.ComponentBitSize.BYTE_BITS;

import java.io.IOException;
import java.io.OutputStream;
import java.util.Arrays;

/** A wrapper class for writing a stream of bits. */
public class BitOutputStream {

    private static final int BUFFER_SIZE = 4 * 1024;

    private int mNextBitIndex;

    private final OutputStream mOutputStream;
    private final byte[] mBuffer;

    public BitOutputStream(OutputStream outputStream) {
        mBuffer = new byte[BUFFER_SIZE];
        mNextBitIndex = 0;
        mOutputStream = outputStream;
    }

    /**
     * Set the next number of bits in the stream to value.
     *
     * @param numOfBits The number of bits used to represent the value.
     * @param value The value to convert to bits.
     */
    public void setNext(int numOfBits, int value) throws IOException {
        if (numOfBits <= 0) {
            return;
        }

        // optional: we can do some clever size checking to "OR" an entire segment of bits instead
        // of setting bits one by one, but it is probably not worth it.
        int nextBitMask = 1 << (numOfBits - 1);
        while (numOfBits-- > 0) {
            setNext((value & nextBitMask) != 0);
            nextBitMask >>>= 1;
        }
    }

    /**
     * Set the next bit in the stream to value.
     *
     * @param value The value to set the bit to
     */
    public void setNext(boolean value) throws IOException {
        int byteToWrite = mNextBitIndex / BYTE_BITS;
        if (byteToWrite == BUFFER_SIZE) {
            mOutputStream.write(mBuffer);
            reset();
            byteToWrite = 0;
        }
        if (value) {
            mBuffer[byteToWrite] |= 1 << (BYTE_BITS - 1 - (mNextBitIndex % BYTE_BITS));
        }
        mNextBitIndex++;
    }

    /** Set the next bit in the stream to true. */
    public void setNext() throws IOException {
        setNext(/* value= */ true);
    }

    /**
     * Flush the data written to the underlying {@link java.io.OutputStream}. Any unfinished bytes
     * will be padded with 0.
     */
    public void flush() throws IOException {
        int endByte = mNextBitIndex / BYTE_BITS;
        if (mNextBitIndex % BYTE_BITS != 0) {
            // If next bit is not the first bit of a byte, then mNextBitIndex / BYTE_BITS would be
            // the byte that includes already written bits. We need to increment it so this byte
            // gets written.
            endByte++;
        }
        mOutputStream.write(mBuffer, 0, endByte);
        reset();
    }

    /** Reset this output stream to start state. */
    private void reset() {
        mNextBitIndex = 0;
        Arrays.fill(mBuffer, (byte) 0);
    }
}
