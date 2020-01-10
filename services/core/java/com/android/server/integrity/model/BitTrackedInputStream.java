/*
 * Copyright (C) 2020 The Android Open Source Project
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

/**
 * An input stream that tracks the total number read bytes since construction and allows moving
 * fast forward to a certain byte any time during the execution.
 *
 * This class is used for efficient reading of rules based on the rule indexing.
 */
public class BitTrackedInputStream extends BitInputStream {

    private static int sReadBitsCount;

    /** Constructor with byte array. */
    public BitTrackedInputStream(byte[] inputStream) {
        super(inputStream);
        sReadBitsCount = 0;
    }

    /** Constructor with input stream. */
    public BitTrackedInputStream(InputStream inputStream) {
        super(inputStream);
        sReadBitsCount = 0;
    }

    /** Obtains an integer value of the next {@code numOfBits}. */
    @Override
    public int getNext(int numOfBits) throws IOException {
        sReadBitsCount += numOfBits;
        return super.getNext(numOfBits);
    }

    /** Returns the current cursor position showing the number of bits that are read. */
    public int getReadBitsCount() {
        return sReadBitsCount;
    }

    /**
     * Sets the cursor to the specified byte location.
     *
     * Note that the integer parameter specifies the location in bytes -- not bits.
     */
    public void setCursorToByteLocation(int byteLocation) throws IOException {
        int bitCountToRead = byteLocation * 8 - sReadBitsCount;
        if (bitCountToRead < 0) {
            throw new IllegalStateException("The byte position is already read.");
        }
        super.getNext(bitCountToRead);
        sReadBitsCount = byteLocation * 8;
    }
}
