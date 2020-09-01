/*
 * Copyright (C) 2018 The Android Open Source Project
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

package com.android.server.backup.encryption.chunk;

import com.android.internal.util.Preconditions;

import java.util.Arrays;
import java.util.Base64;

/**
 * Represents the SHA-256 hash of the plaintext of a chunk, which is frequently used as a key.
 *
 * <p>This class is {@link Comparable} and implements {@link #equals(Object)} and {@link
 * #hashCode()}.
 */
public class ChunkHash implements Comparable<ChunkHash> {
    /** The length of the hash in bytes. The hash is a SHA-256, so this is 256 bits. */
    public static final int HASH_LENGTH_BYTES = 256 / 8;

    private static final int UNSIGNED_MASK = 0xFF;

    private final byte[] mHash;

    /** Constructs a new instance which wraps the given SHA-256 hash bytes. */
    public ChunkHash(byte[] hash) {
        Preconditions.checkArgument(hash.length == HASH_LENGTH_BYTES, "Hash must have 256 bits");
        mHash = hash;
    }

    public byte[] getHash() {
        return mHash;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof ChunkHash)) {
            return false;
        }

        ChunkHash chunkHash = (ChunkHash) o;
        return Arrays.equals(mHash, chunkHash.mHash);
    }

    @Override
    public int hashCode() {
        return Arrays.hashCode(mHash);
    }

    @Override
    public int compareTo(ChunkHash other) {
        return lexicographicalCompareUnsignedBytes(getHash(), other.getHash());
    }

    @Override
    public String toString() {
        return Base64.getEncoder().encodeToString(mHash);
    }

    private static int lexicographicalCompareUnsignedBytes(byte[] left, byte[] right) {
        int minLength = Math.min(left.length, right.length);
        for (int i = 0; i < minLength; i++) {
            int result = toInt(left[i]) - toInt(right[i]);
            if (result != 0) {
                return result;
            }
        }
        return left.length - right.length;
    }

    private static int toInt(byte value) {
        return value & UNSIGNED_MASK;
    }
}
