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

import static com.google.common.truth.Truth.assertThat;

import static org.testng.Assert.assertThrows;

import android.platform.test.annotations.Presubmit;

import com.android.server.backup.encryption.chunk.ChunkHash;

import com.google.common.primitives.Bytes;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

import java.util.Arrays;

@RunWith(RobolectricTestRunner.class)
@Presubmit
public class EncryptedChunkTest {
    private static final byte[] CHUNK_HASH_1_BYTES =
            Arrays.copyOf(new byte[] {1}, ChunkHash.HASH_LENGTH_BYTES);
    private static final byte[] NONCE_1 =
            Arrays.copyOf(new byte[] {2}, EncryptedChunk.NONCE_LENGTH_BYTES);
    private static final byte[] ENCRYPTED_BYTES_1 =
            Arrays.copyOf(new byte[] {3}, EncryptedChunk.KEY_LENGTH_BYTES);

    private static final byte[] CHUNK_HASH_2_BYTES =
            Arrays.copyOf(new byte[] {4}, ChunkHash.HASH_LENGTH_BYTES);
    private static final byte[] NONCE_2 =
            Arrays.copyOf(new byte[] {5}, EncryptedChunk.NONCE_LENGTH_BYTES);
    private static final byte[] ENCRYPTED_BYTES_2 =
            Arrays.copyOf(new byte[] {6}, EncryptedChunk.KEY_LENGTH_BYTES);

    @Test
    public void testCreate_withIncorrectLength_throwsException() {
        ChunkHash chunkHash = new ChunkHash(CHUNK_HASH_1_BYTES);
        byte[] shortNonce = Arrays.copyOf(new byte[] {2}, EncryptedChunk.NONCE_LENGTH_BYTES - 1);

        assertThrows(
                IllegalArgumentException.class,
                () -> EncryptedChunk.create(chunkHash, shortNonce, ENCRYPTED_BYTES_1));
    }

    @Test
    public void testEncryptedBytes_forNewlyCreatedObject_returnsCorrectValue() {
        ChunkHash chunkHash = new ChunkHash(CHUNK_HASH_1_BYTES);
        EncryptedChunk encryptedChunk =
                EncryptedChunk.create(chunkHash, NONCE_1, ENCRYPTED_BYTES_1);

        byte[] returnedBytes = encryptedChunk.encryptedBytes();

        assertThat(returnedBytes)
                .asList()
                .containsExactlyElementsIn(Bytes.asList(ENCRYPTED_BYTES_1))
                .inOrder();
    }

    @Test
    public void testKey_forNewlyCreatedObject_returnsCorrectValue() {
        ChunkHash chunkHash = new ChunkHash(CHUNK_HASH_1_BYTES);
        EncryptedChunk encryptedChunk =
                EncryptedChunk.create(chunkHash, NONCE_1, ENCRYPTED_BYTES_1);

        ChunkHash returnedKey = encryptedChunk.key();

        assertThat(returnedKey).isEqualTo(chunkHash);
    }

    @Test
    public void testNonce_forNewlycreatedObject_returnCorrectValue() {
        ChunkHash chunkHash = new ChunkHash(CHUNK_HASH_1_BYTES);
        EncryptedChunk encryptedChunk =
                EncryptedChunk.create(chunkHash, NONCE_1, ENCRYPTED_BYTES_1);

        byte[] returnedNonce = encryptedChunk.nonce();

        assertThat(returnedNonce).asList().containsExactlyElementsIn(Bytes.asList(NONCE_1));
    }

    @Test
    public void testEquals() {
        ChunkHash chunkHash1 = new ChunkHash(CHUNK_HASH_1_BYTES);
        ChunkHash equalChunkHash1 = new ChunkHash(CHUNK_HASH_1_BYTES);
        ChunkHash chunkHash2 = new ChunkHash(CHUNK_HASH_2_BYTES);
        EncryptedChunk encryptedChunk1 =
                EncryptedChunk.create(chunkHash1, NONCE_1, ENCRYPTED_BYTES_1);
        EncryptedChunk equalEncryptedChunk1 =
                EncryptedChunk.create(equalChunkHash1, NONCE_1, ENCRYPTED_BYTES_1);
        EncryptedChunk encryptedChunk2 =
                EncryptedChunk.create(chunkHash2, NONCE_2, ENCRYPTED_BYTES_2);

        assertThat(encryptedChunk1).isEqualTo(equalEncryptedChunk1);
        assertThat(encryptedChunk1).isNotEqualTo(encryptedChunk2);
    }

    @Test
    public void testHashCode() {
        ChunkHash chunkHash1 = new ChunkHash(CHUNK_HASH_1_BYTES);
        ChunkHash equalChunkHash1 = new ChunkHash(CHUNK_HASH_1_BYTES);
        ChunkHash chunkHash2 = new ChunkHash(CHUNK_HASH_2_BYTES);
        EncryptedChunk encryptedChunk1 =
                EncryptedChunk.create(chunkHash1, NONCE_1, ENCRYPTED_BYTES_1);
        EncryptedChunk equalEncryptedChunk1 =
                EncryptedChunk.create(equalChunkHash1, NONCE_1, ENCRYPTED_BYTES_1);
        EncryptedChunk encryptedChunk2 =
                EncryptedChunk.create(chunkHash2, NONCE_2, ENCRYPTED_BYTES_2);

        int hash1 = encryptedChunk1.hashCode();
        int equalHash1 = equalEncryptedChunk1.hashCode();
        int hash2 = encryptedChunk2.hashCode();

        assertThat(hash1).isEqualTo(equalHash1);
        assertThat(hash1).isNotEqualTo(hash2);
    }
}
