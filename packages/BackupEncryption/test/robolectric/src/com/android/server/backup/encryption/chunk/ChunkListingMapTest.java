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

package com.android.server.backup.encryption.chunk;

import static com.google.common.truth.Truth.assertThat;

import android.platform.test.annotations.Presubmit;

import com.android.server.backup.encryption.protos.nano.ChunksMetadataProto;

import com.google.common.base.Charsets;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

import java.util.Arrays;

@RunWith(RobolectricTestRunner.class)
@Presubmit
public class ChunkListingMapTest {
    private static final ChunkHash CHUNK_A_HASH = getHash("CHUNK_A");
    private static final ChunkHash CHUNK_B_HASH = getHash("CHUNK_B");
    private static final ChunkHash CHUNK_C_HASH = getHash("CHUNK_C");

    private static final int CHUNK_A_LENGTH = 256;
    private static final int CHUNK_B_LENGTH = 1024;
    private static final int CHUNK_C_LENGTH = 4055;

    private static final int CHUNK_A_START = 0;
    private static final int CHUNK_B_START = CHUNK_A_START + CHUNK_A_LENGTH;
    private static final int CHUNK_C_START = CHUNK_B_START + CHUNK_B_LENGTH;

    private ChunkListingMap mChunkListingMap;

    @Before
    public void setUp() {
        mChunkListingMap = createFromFixture();
    }

    @Test
    public void hasChunk_isTrueForExistingChunks() {
        assertThat(mChunkListingMap.hasChunk(CHUNK_A_HASH)).isTrue();
        assertThat(mChunkListingMap.hasChunk(CHUNK_B_HASH)).isTrue();
        assertThat(mChunkListingMap.hasChunk(CHUNK_C_HASH)).isTrue();
    }

    @Test
    public void hasChunk_isFalseForNonexistentChunks() {
        assertThat(mChunkListingMap.hasChunk(getHash("CHUNK_D"))).isFalse();
        assertThat(mChunkListingMap.hasChunk(getHash(""))).isFalse();
    }

    @Test
    public void getChunkListing_hasCorrectLengths() {
        assertThat(mChunkListingMap.getChunkEntry(CHUNK_A_HASH).getLength())
                .isEqualTo(CHUNK_A_LENGTH);
        assertThat(mChunkListingMap.getChunkEntry(CHUNK_B_HASH).getLength())
                .isEqualTo(CHUNK_B_LENGTH);
        assertThat(mChunkListingMap.getChunkEntry(CHUNK_C_HASH).getLength())
                .isEqualTo(CHUNK_C_LENGTH);
    }

    @Test
    public void getChunkListing_hasCorrectStarts() {
        assertThat(mChunkListingMap.getChunkEntry(CHUNK_A_HASH).getStart())
                .isEqualTo(CHUNK_A_START);
        assertThat(mChunkListingMap.getChunkEntry(CHUNK_B_HASH).getStart())
                .isEqualTo(CHUNK_B_START);
        assertThat(mChunkListingMap.getChunkEntry(CHUNK_C_HASH).getStart())
                .isEqualTo(CHUNK_C_START);
    }

    @Test
    public void getChunkListing_isNullForNonExistentChunks() {
        assertThat(mChunkListingMap.getChunkEntry(getHash("Hey"))).isNull();
    }

    private static ChunkListingMap createFromFixture() {
        ChunksMetadataProto.ChunkListing chunkListing = new ChunksMetadataProto.ChunkListing();
        chunkListing.chunks = new ChunksMetadataProto.Chunk[3];
        chunkListing.chunks[0] = newChunk(CHUNK_A_HASH.getHash(), CHUNK_A_LENGTH);
        chunkListing.chunks[1] = newChunk(CHUNK_B_HASH.getHash(), CHUNK_B_LENGTH);
        chunkListing.chunks[2] = newChunk(CHUNK_C_HASH.getHash(), CHUNK_C_LENGTH);
        return ChunkListingMap.fromProto(chunkListing);
    }

    private static ChunkHash getHash(String name) {
        return new ChunkHash(
                Arrays.copyOf(name.getBytes(Charsets.UTF_8), ChunkHash.HASH_LENGTH_BYTES));
    }

    public static ChunksMetadataProto.Chunk newChunk(byte[] hash, int length) {
        ChunksMetadataProto.Chunk newChunk = new ChunksMetadataProto.Chunk();
        newChunk.hash = Arrays.copyOf(hash, hash.length);
        newChunk.length = length;
        return newChunk;
    }
}
