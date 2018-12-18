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

package com.android.server.backup.encryption.chunk;

import static com.google.common.truth.Truth.assertThat;

import android.platform.test.annotations.Presubmit;
import android.util.proto.ProtoInputStream;
import android.util.proto.ProtoOutputStream;

import com.google.common.base.Charsets;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

import java.io.ByteArrayInputStream;
import java.util.Arrays;

@RunWith(RobolectricTestRunner.class)
@Presubmit
public class ChunkTest {
    private static final String CHUNK_A = "CHUNK_A";
    private static final int CHUNK_A_LENGTH = 256;

    private ChunkHash mChunkHashA;

    @Before
    public void setUp() throws Exception {
        mChunkHashA = getHash(CHUNK_A);
    }

    @Test
    public void testReadFromProto_readsCorrectly() throws Exception {
        ProtoOutputStream out = new ProtoOutputStream();
        out.write(ChunksMetadataProto.Chunk.HASH, mChunkHashA.getHash());
        out.write(ChunksMetadataProto.Chunk.LENGTH, CHUNK_A_LENGTH);
        out.flush();
        byte[] protoBytes = out.getBytes();

        Chunk chunk =
                Chunk.readFromProto(new ProtoInputStream(new ByteArrayInputStream(protoBytes)));

        assertThat(chunk.getHash()).isEqualTo(mChunkHashA.getHash());
        assertThat(chunk.getLength()).isEqualTo(CHUNK_A_LENGTH);
    }

    @Test
    public void testReadFromProto_whenFieldsWrittenInReversedOrder_readsCorrectly()
            throws Exception {
        ProtoOutputStream out = new ProtoOutputStream();
        // Write fields of Chunk proto in reverse order.
        out.write(ChunksMetadataProto.Chunk.LENGTH, CHUNK_A_LENGTH);
        out.write(ChunksMetadataProto.Chunk.HASH, mChunkHashA.getHash());
        out.flush();
        byte[] protoBytes = out.getBytes();

        Chunk chunk =
                Chunk.readFromProto(new ProtoInputStream(new ByteArrayInputStream(protoBytes)));

        assertThat(chunk.getHash()).isEqualTo(mChunkHashA.getHash());
        assertThat(chunk.getLength()).isEqualTo(CHUNK_A_LENGTH);
    }

    @Test
    public void testReadFromProto_whenEmptyProto_returnsEmptyHash() throws Exception {
        ProtoInputStream emptyProto = new ProtoInputStream(new ByteArrayInputStream(new byte[] {}));

        Chunk chunk = Chunk.readFromProto(emptyProto);

        assertThat(chunk.getHash()).asList().hasSize(0);
        assertThat(chunk.getLength()).isEqualTo(0);
    }

    @Test
    public void testReadFromProto_whenOnlyHashSet_returnsChunkWithOnlyHash() throws Exception {
        ProtoOutputStream out = new ProtoOutputStream();
        out.write(ChunksMetadataProto.Chunk.HASH, mChunkHashA.getHash());
        out.flush();
        byte[] protoBytes = out.getBytes();

        Chunk chunk =
                Chunk.readFromProto(new ProtoInputStream(new ByteArrayInputStream(protoBytes)));

        assertThat(chunk.getHash()).isEqualTo(mChunkHashA.getHash());
        assertThat(chunk.getLength()).isEqualTo(0);
    }

    @Test
    public void testReadFromProto_whenOnlyLengthSet_returnsChunkWithOnlyLength() throws Exception {
        ProtoOutputStream out = new ProtoOutputStream();
        out.write(ChunksMetadataProto.Chunk.LENGTH, CHUNK_A_LENGTH);
        out.flush();
        byte[] protoBytes = out.getBytes();

        Chunk chunk =
                Chunk.readFromProto(new ProtoInputStream(new ByteArrayInputStream(protoBytes)));

        assertThat(chunk.getHash()).isEqualTo(new byte[] {});
        assertThat(chunk.getLength()).isEqualTo(CHUNK_A_LENGTH);
    }

    private ChunkHash getHash(String name) {
        return new ChunkHash(
                Arrays.copyOf(name.getBytes(Charsets.UTF_8), ChunkHash.HASH_LENGTH_BYTES));
    }
}
