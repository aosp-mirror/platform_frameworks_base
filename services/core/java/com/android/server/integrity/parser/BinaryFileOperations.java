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

package com.android.server.integrity.parser;

import static com.android.server.integrity.model.ComponentBitSize.IS_HASHED_BITS;
import static com.android.server.integrity.model.ComponentBitSize.VALUE_SIZE_BITS;

import android.content.integrity.IntegrityUtils;

import com.android.server.integrity.model.BitInputStream;

import java.io.IOException;
import java.nio.ByteBuffer;

/**
 * Helper methods for reading standard data structures from {@link BitInputStream}.
 */
public class BinaryFileOperations {

    /**
     * Read an string value with the given size and hash status from a {@code BitInputStream}.
     *
     * If the value is hashed, get the hex-encoding of the value. Serialized values are in raw form.
     * All hashed values are hex-encoded.
     */
    public static String getStringValue(BitInputStream bitInputStream) throws IOException {
        boolean isHashedValue = bitInputStream.getNext(IS_HASHED_BITS) == 1;
        int valueSize = bitInputStream.getNext(VALUE_SIZE_BITS);
        return getStringValue(bitInputStream, valueSize, isHashedValue);
    }

    /**
     * Read an string value with the given size and hash status from a {@code BitInputStream}.
     *
     * If the value is hashed, get the hex-encoding of the value. Serialized values are in raw form.
     * All hashed values are hex-encoded.
     */
    public static String getStringValue(
            BitInputStream bitInputStream, int valueSize, boolean isHashedValue)
            throws IOException {
        if (!isHashedValue) {
            StringBuilder value = new StringBuilder();
            while (valueSize-- > 0) {
                value.append((char) bitInputStream.getNext(/* numOfBits= */ 8));
            }
            return value.toString();
        }
        ByteBuffer byteBuffer = ByteBuffer.allocate(valueSize);
        while (valueSize-- > 0) {
            byteBuffer.put((byte) (bitInputStream.getNext(/* numOfBits= */ 8) & 0xFF));
        }
        return IntegrityUtils.getHexDigest(byteBuffer.array());
    }

    /** Read an integer value from a {@code BitInputStream}. */
    public static int getIntValue(BitInputStream bitInputStream) throws IOException {
        return bitInputStream.getNext(/* numOfBits= */ 32);
    }

    /** Read an boolean value from a {@code BitInputStream}. */
    public static boolean getBooleanValue(BitInputStream bitInputStream) throws IOException {
        return bitInputStream.getNext(/* numOfBits= */ 1) == 1;
    }
}
