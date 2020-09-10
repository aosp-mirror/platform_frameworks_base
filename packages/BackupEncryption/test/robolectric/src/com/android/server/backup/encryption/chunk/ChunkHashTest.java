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

import static com.google.common.truth.Truth.assertThat;

import android.platform.test.annotations.Presubmit;

import com.google.common.primitives.Bytes;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

import java.util.Arrays;

@RunWith(RobolectricTestRunner.class)
@Presubmit
public class ChunkHashTest {
    private static final int HASH_LENGTH_BYTES = 256 / 8;
    private static final byte[] TEST_HASH_1 = Arrays.copyOf(new byte[] {1}, HASH_LENGTH_BYTES);
    private static final byte[] TEST_HASH_2 = Arrays.copyOf(new byte[] {2}, HASH_LENGTH_BYTES);

    @Test
    public void testGetHash_returnsHash() {
        ChunkHash chunkHash = new ChunkHash(TEST_HASH_1);

        byte[] hash = chunkHash.getHash();

        assertThat(hash).asList().containsExactlyElementsIn(Bytes.asList(TEST_HASH_1)).inOrder();
    }

    @Test
    public void testEquals() {
        ChunkHash chunkHash1 = new ChunkHash(TEST_HASH_1);
        ChunkHash equalChunkHash1 = new ChunkHash(TEST_HASH_1);
        ChunkHash chunkHash2 = new ChunkHash(TEST_HASH_2);

        assertThat(chunkHash1).isEqualTo(equalChunkHash1);
        assertThat(chunkHash1).isNotEqualTo(chunkHash2);
    }

    @Test
    public void testHashCode() {
        ChunkHash chunkHash1 = new ChunkHash(TEST_HASH_1);
        ChunkHash equalChunkHash1 = new ChunkHash(TEST_HASH_1);
        ChunkHash chunkHash2 = new ChunkHash(TEST_HASH_2);

        int hash1 = chunkHash1.hashCode();
        int equalHash1 = equalChunkHash1.hashCode();
        int hash2 = chunkHash2.hashCode();

        assertThat(hash1).isEqualTo(equalHash1);
        assertThat(hash1).isNotEqualTo(hash2);
    }

    @Test
    public void testCompareTo_whenEqual_returnsZero() {
        ChunkHash chunkHash = new ChunkHash(TEST_HASH_1);
        ChunkHash equalChunkHash = new ChunkHash(TEST_HASH_1);

        int result = chunkHash.compareTo(equalChunkHash);

        assertThat(result).isEqualTo(0);
    }

    @Test
    public void testCompareTo_whenArgumentGreater_returnsNegative() {
        ChunkHash chunkHash1 = new ChunkHash(TEST_HASH_1);
        ChunkHash chunkHash2 = new ChunkHash(TEST_HASH_2);

        int result = chunkHash1.compareTo(chunkHash2);

        assertThat(result).isLessThan(0);
    }

    @Test
    public void testCompareTo_whenArgumentSmaller_returnsPositive() {
        ChunkHash chunkHash1 = new ChunkHash(TEST_HASH_1);
        ChunkHash chunkHash2 = new ChunkHash(TEST_HASH_2);

        int result = chunkHash2.compareTo(chunkHash1);

        assertThat(result).isGreaterThan(0);
    }
}
