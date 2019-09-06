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
import android.util.proto.ProtoInputStream;
import android.util.proto.ProtoOutputStream;

import com.android.internal.util.Preconditions;

import com.google.common.base.Charsets;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

import java.io.ByteArrayInputStream;
import java.util.Arrays;

@RunWith(RobolectricTestRunner.class)
@Presubmit
public class ChunkListingMapTest {
    private static final String CHUNK_A = "CHUNK_A";
    private static final String CHUNK_B = "CHUNK_B";
    private static final String CHUNK_C = "CHUNK_C";

    private static final int CHUNK_A_LENGTH = 256;
    private static final int CHUNK_B_LENGTH = 1024;
    private static final int CHUNK_C_LENGTH = 4055;

    private ChunkHash mChunkHashA;
    private ChunkHash mChunkHashB;
    private ChunkHash mChunkHashC;

    @Before
    public void setUp() throws Exception {
        mChunkHashA = getHash(CHUNK_A);
        mChunkHashB = getHash(CHUNK_B);
        mChunkHashC = getHash(CHUNK_C);
    }

    @Test
    public void testHasChunk_whenChunkInListing_returnsTrue() throws Exception {
        byte[] chunkListingProto =
                createChunkListingProto(
                        new ChunkHash[] {mChunkHashA, mChunkHashB, mChunkHashC},
                        new int[] {CHUNK_A_LENGTH, CHUNK_B_LENGTH, CHUNK_C_LENGTH});
        ChunkListingMap chunkListingMap =
                ChunkListingMap.readFromProto(
                        new ProtoInputStream(new ByteArrayInputStream(chunkListingProto)));

        boolean chunkAInList = chunkListingMap.hasChunk(mChunkHashA);
        boolean chunkBInList = chunkListingMap.hasChunk(mChunkHashB);
        boolean chunkCInList = chunkListingMap.hasChunk(mChunkHashC);

        assertThat(chunkAInList).isTrue();
        assertThat(chunkBInList).isTrue();
        assertThat(chunkCInList).isTrue();
    }

    @Test
    public void testHasChunk_whenChunkNotInListing_returnsFalse() throws Exception {
        byte[] chunkListingProto =
                createChunkListingProto(
                        new ChunkHash[] {mChunkHashA, mChunkHashB},
                        new int[] {CHUNK_A_LENGTH, CHUNK_B_LENGTH});
        ChunkListingMap chunkListingMap =
                ChunkListingMap.readFromProto(
                        new ProtoInputStream(new ByteArrayInputStream(chunkListingProto)));
        ChunkHash chunkHashEmpty = getHash("");

        boolean chunkCInList = chunkListingMap.hasChunk(mChunkHashC);
        boolean emptyChunkInList = chunkListingMap.hasChunk(chunkHashEmpty);

        assertThat(chunkCInList).isFalse();
        assertThat(emptyChunkInList).isFalse();
    }

    @Test
    public void testGetChunkEntry_returnsEntryWithCorrectLength() throws Exception {
        byte[] chunkListingProto =
                createChunkListingProto(
                        new ChunkHash[] {mChunkHashA, mChunkHashB, mChunkHashC},
                        new int[] {CHUNK_A_LENGTH, CHUNK_B_LENGTH, CHUNK_C_LENGTH});
        ChunkListingMap chunkListingMap =
                ChunkListingMap.readFromProto(
                        new ProtoInputStream(new ByteArrayInputStream(chunkListingProto)));

        ChunkListingMap.Entry entryA = chunkListingMap.getChunkEntry(mChunkHashA);
        ChunkListingMap.Entry entryB = chunkListingMap.getChunkEntry(mChunkHashB);
        ChunkListingMap.Entry entryC = chunkListingMap.getChunkEntry(mChunkHashC);

        assertThat(entryA.getLength()).isEqualTo(CHUNK_A_LENGTH);
        assertThat(entryB.getLength()).isEqualTo(CHUNK_B_LENGTH);
        assertThat(entryC.getLength()).isEqualTo(CHUNK_C_LENGTH);
    }

    @Test
    public void testGetChunkEntry_returnsEntryWithCorrectStart() throws Exception {
        byte[] chunkListingProto =
                createChunkListingProto(
                        new ChunkHash[] {mChunkHashA, mChunkHashB, mChunkHashC},
                        new int[] {CHUNK_A_LENGTH, CHUNK_B_LENGTH, CHUNK_C_LENGTH});
        ChunkListingMap chunkListingMap =
                ChunkListingMap.readFromProto(
                        new ProtoInputStream(new ByteArrayInputStream(chunkListingProto)));

        ChunkListingMap.Entry entryA = chunkListingMap.getChunkEntry(mChunkHashA);
        ChunkListingMap.Entry entryB = chunkListingMap.getChunkEntry(mChunkHashB);
        ChunkListingMap.Entry entryC = chunkListingMap.getChunkEntry(mChunkHashC);

        assertThat(entryA.getStart()).isEqualTo(0);
        assertThat(entryB.getStart()).isEqualTo(CHUNK_A_LENGTH);
        assertThat(entryC.getStart()).isEqualTo(CHUNK_A_LENGTH + CHUNK_B_LENGTH);
    }

    @Test
    public void testGetChunkEntry_returnsNullForNonExistentChunk() throws Exception {
        byte[] chunkListingProto =
                createChunkListingProto(
                        new ChunkHash[] {mChunkHashA, mChunkHashB},
                        new int[] {CHUNK_A_LENGTH, CHUNK_B_LENGTH});
        ChunkListingMap chunkListingMap =
                ChunkListingMap.readFromProto(
                        new ProtoInputStream(new ByteArrayInputStream(chunkListingProto)));

        ChunkListingMap.Entry chunkEntryNonexistentChunk =
                chunkListingMap.getChunkEntry(mChunkHashC);

        assertThat(chunkEntryNonexistentChunk).isNull();
    }

    @Test
    public void testReadFromProto_whenEmptyProto_returnsChunkListingMapWith0Chunks()
            throws Exception {
        ProtoInputStream emptyProto = new ProtoInputStream(new ByteArrayInputStream(new byte[] {}));

        ChunkListingMap chunkListingMap = ChunkListingMap.readFromProto(emptyProto);

        assertThat(chunkListingMap.getChunkCount()).isEqualTo(0);
    }

    @Test
    public void testReadFromProto_returnsChunkListingWithCorrectSize() throws Exception {
        byte[] chunkListingProto =
                createChunkListingProto(
                        new ChunkHash[] {mChunkHashA, mChunkHashB, mChunkHashC},
                        new int[] {CHUNK_A_LENGTH, CHUNK_B_LENGTH, CHUNK_C_LENGTH});

        ChunkListingMap chunkListingMap =
                ChunkListingMap.readFromProto(
                        new ProtoInputStream(new ByteArrayInputStream(chunkListingProto)));

        assertThat(chunkListingMap.getChunkCount()).isEqualTo(3);
    }

    private byte[] createChunkListingProto(ChunkHash[] hashes, int[] lengths) {
        Preconditions.checkArgument(hashes.length == lengths.length);
        ProtoOutputStream outputStream = new ProtoOutputStream();

        for (int i = 0; i < hashes.length; ++i) {
            writeToProtoOutputStream(outputStream, hashes[i], lengths[i]);
        }
        outputStream.flush();

        return outputStream.getBytes();
    }

    private void writeToProtoOutputStream(ProtoOutputStream out, ChunkHash chunkHash, int length) {
        long token = out.start(ChunksMetadataProto.ChunkListing.CHUNKS);
        out.write(ChunksMetadataProto.Chunk.HASH, chunkHash.getHash());
        out.write(ChunksMetadataProto.Chunk.LENGTH, length);
        out.end(token);
    }

    private ChunkHash getHash(String name) {
        return new ChunkHash(
                Arrays.copyOf(name.getBytes(Charsets.UTF_8), ChunkHash.HASH_LENGTH_BYTES));
    }
}
