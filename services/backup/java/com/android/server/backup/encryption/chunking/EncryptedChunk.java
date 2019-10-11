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
 * limitations under the License
 */

package com.android.server.backup.encryption.chunking;

import com.android.internal.util.Preconditions;
import com.android.server.backup.encryption.chunk.ChunkHash;
import java.util.Arrays;
import java.util.Objects;

/**
 * A chunk of a file encrypted using AES/GCM.
 *
 * <p>TODO(b/116575321): After all code is ported, remove the factory method and rename
 * encryptedBytes(), key() and nonce().
 */
public class EncryptedChunk {
    public static final int KEY_LENGTH_BYTES = ChunkHash.HASH_LENGTH_BYTES;
    public static final int NONCE_LENGTH_BYTES = 12;

    /**
     * Constructs a new instance with the given key, nonce, and encrypted bytes.
     *
     * @param key SHA-256 Hmac of the chunk plaintext.
     * @param nonce Nonce with which the bytes of the chunk were encrypted.
     * @param encryptedBytes Encrypted bytes of the chunk.
     */
    public static EncryptedChunk create(ChunkHash key, byte[] nonce, byte[] encryptedBytes) {
        Preconditions.checkArgument(
                nonce.length == NONCE_LENGTH_BYTES, "Nonce does not have the correct length.");
        return new EncryptedChunk(key, nonce, encryptedBytes);
    }

    private ChunkHash mKey;
    private byte[] mNonce;
    private byte[] mEncryptedBytes;

    private EncryptedChunk(ChunkHash key, byte[] nonce, byte[] encryptedBytes) {
        mKey = key;
        mNonce = nonce;
        mEncryptedBytes = encryptedBytes;
    }

    /** The SHA-256 Hmac of the plaintext bytes of the chunk. */
    public ChunkHash key() {
        return mKey;
    }

    /** The nonce with which the chunk was encrypted. */
    public byte[] nonce() {
        return mNonce;
    }

    /** The encrypted bytes of the chunk. */
    public byte[] encryptedBytes() {
        return mEncryptedBytes;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof EncryptedChunk)) {
            return false;
        }

        EncryptedChunk encryptedChunkOrdering = (EncryptedChunk) o;
        return Arrays.equals(mEncryptedBytes, encryptedChunkOrdering.mEncryptedBytes)
                && Arrays.equals(mNonce, encryptedChunkOrdering.mNonce)
                && mKey.equals(encryptedChunkOrdering.mKey);
    }

    @Override
    public int hashCode() {
        return Objects.hash(mKey, Arrays.hashCode(mNonce), Arrays.hashCode(mEncryptedBytes));
    }
}
