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

package com.android.server.backup.encryption.chunking;

import static com.android.server.backup.encryption.protos.nano.ChunksMetadataProto.AES_256_GCM;
import static com.android.server.backup.encryption.protos.nano.ChunksMetadataProto.CHUNK_ORDERING_TYPE_UNSPECIFIED;
import static com.android.server.backup.testing.CryptoTestUtils.newChunk;

import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.Truth.assertWithMessage;

import static junit.framework.Assert.fail;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.testng.Assert.assertThrows;

import static java.nio.charset.StandardCharsets.UTF_8;

import android.platform.test.annotations.Presubmit;

import com.android.server.backup.encryption.chunk.ChunkHash;
import com.android.server.backup.encryption.protos.nano.ChunksMetadataProto;
import com.android.server.backup.encryption.testing.DiffScriptProcessor;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.io.Files;
import com.google.common.primitives.Bytes;
import com.google.common.primitives.Longs;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

@RunWith(RobolectricTestRunner.class)
@Presubmit
public class BackupFileBuilderTest {
    private static final String TEST_DATA_1 =
            "I'm already there or close to [T7-9/executive level] in terms of big-picture vision";
    private static final String TEST_DATA_2 =
            "I was known for Real Games and should have been brought in for advice";
    private static final String TEST_DATA_3 =
            "Pride is rooted in the delusional belief held by all humans in an unchanging self";

    private static final byte[] TEST_FINGERPRINT_MIXER_SALT =
            Arrays.copyOf(new byte[] {22}, ChunkHash.HASH_LENGTH_BYTES);

    private static final ChunkHash TEST_HASH_1 =
            new ChunkHash(Arrays.copyOf(new byte[] {0}, EncryptedChunk.KEY_LENGTH_BYTES));
    private static final ChunkHash TEST_HASH_2 =
            new ChunkHash(Arrays.copyOf(new byte[] {1}, EncryptedChunk.KEY_LENGTH_BYTES));
    private static final ChunkHash TEST_HASH_3 =
            new ChunkHash(Arrays.copyOf(new byte[] {2}, EncryptedChunk.KEY_LENGTH_BYTES));

    private static final byte[] TEST_NONCE =
            Arrays.copyOf(new byte[] {3}, EncryptedChunk.NONCE_LENGTH_BYTES);

    private static final EncryptedChunk TEST_CHUNK_1 =
            EncryptedChunk.create(TEST_HASH_1, TEST_NONCE, TEST_DATA_1.getBytes(UTF_8));
    private static final EncryptedChunk TEST_CHUNK_2 =
            EncryptedChunk.create(TEST_HASH_2, TEST_NONCE, TEST_DATA_2.getBytes(UTF_8));
    private static final EncryptedChunk TEST_CHUNK_3 =
            EncryptedChunk.create(TEST_HASH_3, TEST_NONCE, TEST_DATA_3.getBytes(UTF_8));

    private static final byte[] TEST_CHECKSUM = {1, 2, 3, 4, 5, 6};

    @Rule public TemporaryFolder mTemporaryFolder = new TemporaryFolder();

    private File mOldFile;
    private ChunksMetadataProto.ChunkListing mOldChunkListing;
    private EncryptedChunkEncoder mEncryptedChunkEncoder;

    @Before
    public void setUp() {
        mEncryptedChunkEncoder = new LengthlessEncryptedChunkEncoder();
    }

    @Test
    public void writeChunks_nonIncremental_writesCorrectRawData() throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        BackupFileBuilder backupFileBuilder = BackupFileBuilder.createForNonIncremental(output);

        backupFileBuilder.writeChunks(
                ImmutableList.of(TEST_HASH_1, TEST_HASH_2),
                getNewChunkMap(TEST_HASH_1, TEST_HASH_2));

        byte[] actual = output.toByteArray();
        byte[] expected =
                Bytes.concat(
                        TEST_CHUNK_1.nonce(),
                        TEST_CHUNK_1.encryptedBytes(),
                        TEST_CHUNK_2.nonce(),
                        TEST_CHUNK_2.encryptedBytes());
        assertThat(actual).asList().containsExactlyElementsIn(Bytes.asList(expected)).inOrder();
    }

    @Test
    public void writeChunks_nonIncrementalWithDuplicates_writesEachChunkOnlyOnce()
            throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        BackupFileBuilder backupFileBuilder = BackupFileBuilder.createForNonIncremental(output);

        backupFileBuilder.writeChunks(
                ImmutableList.of(TEST_HASH_1, TEST_HASH_2, TEST_HASH_1),
                getNewChunkMap(TEST_HASH_1, TEST_HASH_2));

        byte[] actual = output.toByteArray();
        byte[] expected =
                Bytes.concat(
                        TEST_CHUNK_1.nonce(),
                        TEST_CHUNK_1.encryptedBytes(),
                        TEST_CHUNK_2.nonce(),
                        TEST_CHUNK_2.encryptedBytes());
        assertThat(actual).asList().containsExactlyElementsIn(Bytes.asList(expected)).inOrder();
    }

    @Test
    public void writeChunks_incremental_writesParsableDiffScript() throws Exception {
        // We will insert chunk 2 in between chunks 1 and 3.
        setUpOldBackupWithChunks(ImmutableList.of(TEST_CHUNK_1, TEST_CHUNK_3));
        ByteArrayOutputStream diffOutputStream = new ByteArrayOutputStream();
        BackupFileBuilder backupFileBuilder =
                BackupFileBuilder.createForIncremental(diffOutputStream, mOldChunkListing);

        backupFileBuilder.writeChunks(
                ImmutableList.of(TEST_HASH_1, TEST_HASH_2, TEST_HASH_3),
                getNewChunkMap(TEST_HASH_2));
        backupFileBuilder.finish(getTestMetadata());

        byte[] actual =
                stripMetadataAndPositionFromOutput(parseDiffScript(diffOutputStream.toByteArray()));
        byte[] expected =
                Bytes.concat(
                        TEST_CHUNK_1.nonce(),
                        TEST_CHUNK_1.encryptedBytes(),
                        TEST_CHUNK_2.nonce(),
                        TEST_CHUNK_2.encryptedBytes(),
                        TEST_CHUNK_3.nonce(),
                        TEST_CHUNK_3.encryptedBytes());
        assertThat(actual).asList().containsExactlyElementsIn(Bytes.asList(expected)).inOrder();
    }

    @Test
    public void writeChunks_incrementalWithDuplicates_writesEachChunkOnlyOnce() throws Exception {
        // We will insert chunk 2 twice in between chunks 1 and 3.
        setUpOldBackupWithChunks(ImmutableList.of(TEST_CHUNK_1, TEST_CHUNK_3));
        ByteArrayOutputStream diffOutputStream = new ByteArrayOutputStream();
        BackupFileBuilder backupFileBuilder =
                BackupFileBuilder.createForIncremental(diffOutputStream, mOldChunkListing);

        backupFileBuilder.writeChunks(
                ImmutableList.of(TEST_HASH_1, TEST_HASH_2, TEST_HASH_2, TEST_HASH_3),
                getNewChunkMap(TEST_HASH_2));
        backupFileBuilder.finish(getTestMetadata());

        byte[] actual =
                stripMetadataAndPositionFromOutput(parseDiffScript(diffOutputStream.toByteArray()));
        byte[] expected =
                Bytes.concat(
                        TEST_CHUNK_1.nonce(),
                        TEST_CHUNK_1.encryptedBytes(),
                        TEST_CHUNK_2.nonce(),
                        TEST_CHUNK_2.encryptedBytes(),
                        TEST_CHUNK_3.nonce(),
                        TEST_CHUNK_3.encryptedBytes());
        assertThat(actual).asList().containsExactlyElementsIn(Bytes.asList(expected)).inOrder();
    }

    @Test
    public void writeChunks_writesChunksInOrderOfHash() throws Exception {
        setUpOldBackupWithChunks(ImmutableList.of());
        ByteArrayOutputStream diffOutputStream = new ByteArrayOutputStream();
        BackupFileBuilder backupFileBuilder =
                BackupFileBuilder.createForIncremental(diffOutputStream, mOldChunkListing);

        // Write chunks out of order.
        backupFileBuilder.writeChunks(
                ImmutableList.of(TEST_HASH_2, TEST_HASH_1),
                getNewChunkMap(TEST_HASH_2, TEST_HASH_1));
        backupFileBuilder.finish(getTestMetadata());

        byte[] actual =
                stripMetadataAndPositionFromOutput(parseDiffScript(diffOutputStream.toByteArray()));
        byte[] expected =
                Bytes.concat(
                        TEST_CHUNK_1.nonce(),
                        TEST_CHUNK_1.encryptedBytes(),
                        TEST_CHUNK_2.nonce(),
                        TEST_CHUNK_2.encryptedBytes());
        assertThat(actual).asList().containsExactlyElementsIn(Bytes.asList(expected)).inOrder();
    }

    @Test
    public void writeChunks_alreadyFlushed_throwsException() throws Exception {
        BackupFileBuilder backupFileBuilder =
                BackupFileBuilder.createForIncremental(
                        new ByteArrayOutputStream(), new ChunksMetadataProto.ChunkListing());
        backupFileBuilder.finish(getTestMetadata());

        assertThrows(
                IllegalStateException.class,
                () -> backupFileBuilder.writeChunks(ImmutableList.of(), getNewChunkMap()));
    }

    @Test
    public void getNewChunkListing_hasChunksInOrderOfKey() throws Exception {
        // We will insert chunk 2 in between chunks 1 and 3.
        setUpOldBackupWithChunks(ImmutableList.of(TEST_CHUNK_1, TEST_CHUNK_3));
        BackupFileBuilder backupFileBuilder =
                BackupFileBuilder.createForIncremental(
                        new ByteArrayOutputStream(), mOldChunkListing);

        // Write chunks out of order.
        backupFileBuilder.writeChunks(
                ImmutableList.of(TEST_HASH_1, TEST_HASH_3, TEST_HASH_2),
                getNewChunkMap(TEST_HASH_2));
        backupFileBuilder.finish(getTestMetadata());

        ChunksMetadataProto.ChunkListing expected = expectedChunkListing();
        ChunksMetadataProto.ChunkListing actual =
                backupFileBuilder.getNewChunkListing(TEST_FINGERPRINT_MIXER_SALT);
        assertListingsEqual(actual, expected);
    }

    @Test
    public void getNewChunkListing_writeChunksInTwoBatches_returnsListingContainingAllChunks()
            throws Exception {
        // We will insert chunk 2 in between chunks 1 and 3.
        setUpOldBackupWithChunks(ImmutableList.of(TEST_CHUNK_1, TEST_CHUNK_3));
        BackupFileBuilder backupFileBuilder =
                BackupFileBuilder.createForIncremental(
                        new ByteArrayOutputStream(), mOldChunkListing);

        backupFileBuilder.writeChunks(
                ImmutableList.of(TEST_HASH_1, TEST_HASH_2), getNewChunkMap(TEST_HASH_2));
        backupFileBuilder.writeChunks(ImmutableList.of(TEST_HASH_3), getNewChunkMap(TEST_HASH_2));
        backupFileBuilder.finish(getTestMetadata());

        ChunksMetadataProto.ChunkListing expected = expectedChunkListing();
        ChunksMetadataProto.ChunkListing actual =
                backupFileBuilder.getNewChunkListing(TEST_FINGERPRINT_MIXER_SALT);
        assertListingsEqual(actual, expected);
    }

    @Test
    public void getNewChunkListing_writeDuplicateChunks_writesEachChunkOnlyOnce() throws Exception {
        // We will append [2][3][3][2] onto [1].
        setUpOldBackupWithChunks(ImmutableList.of(TEST_CHUNK_1));
        BackupFileBuilder backupFileBuilder =
                BackupFileBuilder.createForIncremental(
                        new ByteArrayOutputStream(), mOldChunkListing);

        backupFileBuilder.writeChunks(
                ImmutableList.of(TEST_HASH_1, TEST_HASH_2, TEST_HASH_3),
                getNewChunkMap(TEST_HASH_3, TEST_HASH_2));
        backupFileBuilder.writeChunks(
                ImmutableList.of(TEST_HASH_3, TEST_HASH_2),
                getNewChunkMap(TEST_HASH_3, TEST_HASH_2));
        backupFileBuilder.finish(getTestMetadata());

        ChunksMetadataProto.ChunkListing expected = expectedChunkListing();
        ChunksMetadataProto.ChunkListing actual =
                backupFileBuilder.getNewChunkListing(TEST_FINGERPRINT_MIXER_SALT);
        assertListingsEqual(actual, expected);
    }

    @Test
    public void getNewChunkListing_nonIncrementalWithNoSalt_doesNotThrowOnSerialisation() {
        BackupFileBuilder backupFileBuilder =
                BackupFileBuilder.createForNonIncremental(new ByteArrayOutputStream());

        ChunksMetadataProto.ChunkListing newChunkListing =
                backupFileBuilder.getNewChunkListing(/*fingerprintMixerSalt=*/ null);

        // Does not throw.
        ChunksMetadataProto.ChunkListing.toByteArray(newChunkListing);
    }

    @Test
    public void getNewChunkListing_incrementalWithNoSalt_doesNotThrowOnSerialisation()
            throws Exception {

        setUpOldBackupWithChunks(ImmutableList.of());
        BackupFileBuilder backupFileBuilder =
                BackupFileBuilder.createForIncremental(
                        new ByteArrayOutputStream(), mOldChunkListing);

        ChunksMetadataProto.ChunkListing newChunkListing =
                backupFileBuilder.getNewChunkListing(/*fingerprintMixerSalt=*/ null);

        // Does not throw.
        ChunksMetadataProto.ChunkListing.toByteArray(newChunkListing);
    }

    @Test
    public void getNewChunkListing_nonIncrementalWithNoSalt_hasEmptySalt() {
        BackupFileBuilder backupFileBuilder =
                BackupFileBuilder.createForNonIncremental(new ByteArrayOutputStream());

        ChunksMetadataProto.ChunkListing newChunkListing =
                backupFileBuilder.getNewChunkListing(/*fingerprintMixerSalt=*/ null);

        assertThat(newChunkListing.fingerprintMixerSalt).isEmpty();
    }

    @Test
    public void getNewChunkListing_incrementalWithNoSalt_hasEmptySalt() throws Exception {
        setUpOldBackupWithChunks(ImmutableList.of());
        BackupFileBuilder backupFileBuilder =
                BackupFileBuilder.createForIncremental(
                        new ByteArrayOutputStream(), mOldChunkListing);

        ChunksMetadataProto.ChunkListing newChunkListing =
                backupFileBuilder.getNewChunkListing(/*fingerprintMixerSalt=*/ null);

        assertThat(newChunkListing.fingerprintMixerSalt).isEmpty();
    }

    @Test
    public void getNewChunkListing_nonIncrementalWithSalt_hasGivenSalt() {
        BackupFileBuilder backupFileBuilder =
                BackupFileBuilder.createForNonIncremental(new ByteArrayOutputStream());

        ChunksMetadataProto.ChunkListing newChunkListing =
                backupFileBuilder.getNewChunkListing(TEST_FINGERPRINT_MIXER_SALT);

        assertThat(newChunkListing.fingerprintMixerSalt).isEqualTo(TEST_FINGERPRINT_MIXER_SALT);
    }

    @Test
    public void getNewChunkListing_incrementalWithSalt_hasGivenSalt() throws Exception {
        setUpOldBackupWithChunks(ImmutableList.of());
        BackupFileBuilder backupFileBuilder =
                BackupFileBuilder.createForIncremental(
                        new ByteArrayOutputStream(), mOldChunkListing);

        ChunksMetadataProto.ChunkListing newChunkListing =
                backupFileBuilder.getNewChunkListing(TEST_FINGERPRINT_MIXER_SALT);

        assertThat(newChunkListing.fingerprintMixerSalt).isEqualTo(TEST_FINGERPRINT_MIXER_SALT);
    }

    @Test
    public void getNewChunkListing_nonIncremental_hasCorrectCipherTypeAndChunkOrderingType() {
        BackupFileBuilder backupFileBuilder =
                BackupFileBuilder.createForNonIncremental(new ByteArrayOutputStream());

        ChunksMetadataProto.ChunkListing newChunkListing =
                backupFileBuilder.getNewChunkListing(/*fingerprintMixerSalt=*/ null);

        assertThat(newChunkListing.cipherType).isEqualTo(ChunksMetadataProto.AES_256_GCM);
        assertThat(newChunkListing.chunkOrderingType)
                .isEqualTo(ChunksMetadataProto.CHUNK_ORDERING_TYPE_UNSPECIFIED);
    }

    @Test
    public void getNewChunkListing_incremental_hasCorrectCipherTypeAndChunkOrderingType()
            throws Exception {
        setUpOldBackupWithChunks(ImmutableList.of());
        BackupFileBuilder backupFileBuilder =
                BackupFileBuilder.createForIncremental(
                        new ByteArrayOutputStream(), mOldChunkListing);

        ChunksMetadataProto.ChunkListing newChunkListing =
                backupFileBuilder.getNewChunkListing(/*fingerprintMixerSalt=*/ null);

        assertThat(newChunkListing.cipherType).isEqualTo(ChunksMetadataProto.AES_256_GCM);
        assertThat(newChunkListing.chunkOrderingType)
                .isEqualTo(ChunksMetadataProto.CHUNK_ORDERING_TYPE_UNSPECIFIED);
    }

    @Test
    public void getNewChunkOrdering_chunksHaveCorrectStartPositions() throws Exception {
        BackupFileBuilder backupFileBuilder =
                BackupFileBuilder.createForIncremental(
                        new ByteArrayOutputStream(), new ChunksMetadataProto.ChunkListing());

        // Write out of order by key to check that ordering is maintained.
        backupFileBuilder.writeChunks(
                ImmutableList.of(TEST_HASH_1, TEST_HASH_3, TEST_HASH_2),
                getNewChunkMap(TEST_HASH_1, TEST_HASH_3, TEST_HASH_2));
        backupFileBuilder.finish(getTestMetadata());

        ChunksMetadataProto.ChunkOrdering actual =
                backupFileBuilder.getNewChunkOrdering(TEST_CHECKSUM);
        // The chunks are listed in the order they are written above, but the start positions are
        // determined by the order in the encrypted blob (which is lexicographical by key).
        int chunk1Start = 0;
        int chunk2Start =
                chunk1Start + mEncryptedChunkEncoder.getEncodedLengthOfChunk(TEST_CHUNK_1);
        int chunk3Start =
                chunk2Start + mEncryptedChunkEncoder.getEncodedLengthOfChunk(TEST_CHUNK_2);

        int[] expected = {chunk1Start, chunk3Start, chunk2Start};
        assertThat(actual.starts.length).isEqualTo(expected.length);
        for (int i = 0; i < actual.starts.length; i++) {
            assertThat(expected[i]).isEqualTo(actual.starts[i]);
        }
    }

    @Test
    public void getNewChunkOrdering_duplicateChunks_writesDuplicates() throws Exception {
        BackupFileBuilder backupFileBuilder =
                BackupFileBuilder.createForIncremental(
                        new ByteArrayOutputStream(), new ChunksMetadataProto.ChunkListing());

        backupFileBuilder.writeChunks(
                ImmutableList.of(TEST_HASH_1, TEST_HASH_2, TEST_HASH_2),
                getNewChunkMap(TEST_HASH_1, TEST_HASH_2));
        backupFileBuilder.writeChunks(
                ImmutableList.of(TEST_HASH_3, TEST_HASH_3), getNewChunkMap(TEST_HASH_3));
        backupFileBuilder.finish(getTestMetadata());

        ChunksMetadataProto.ChunkOrdering actual =
                backupFileBuilder.getNewChunkOrdering(TEST_CHECKSUM);
        int chunk1Start = 0;
        int chunk2Start =
                chunk1Start + mEncryptedChunkEncoder.getEncodedLengthOfChunk(TEST_CHUNK_1);
        int chunk3Start =
                chunk2Start + mEncryptedChunkEncoder.getEncodedLengthOfChunk(TEST_CHUNK_2);

        int[] expected = {chunk1Start, chunk2Start, chunk2Start, chunk3Start, chunk3Start};
        assertThat(actual.starts.length).isEqualTo(expected.length);
        for (int i = 0; i < actual.starts.length; i++) {
            assertThat(expected[i]).isEqualTo(actual.starts[i]);
        }
    }

    @Test
    public void getNewChunkOrdering_returnsOrderingWithChecksum() throws Exception {
        BackupFileBuilder backupFileBuilder =
                BackupFileBuilder.createForIncremental(
                        new ByteArrayOutputStream(), new ChunksMetadataProto.ChunkListing());

        backupFileBuilder.writeChunks(ImmutableList.of(TEST_HASH_1), getNewChunkMap(TEST_HASH_1));
        backupFileBuilder.finish(getTestMetadata());

        ChunksMetadataProto.ChunkOrdering actual =
                backupFileBuilder.getNewChunkOrdering(TEST_CHECKSUM);
        assertThat(actual.checksum).isEqualTo(TEST_CHECKSUM);
    }

    @Test
    public void finish_writesMetadata() throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        BackupFileBuilder builder = BackupFileBuilder.createForNonIncremental(output);
        ChunksMetadataProto.ChunksMetadata expectedMetadata = getTestMetadata();

        builder.finish(expectedMetadata);

        // The output is [metadata]+[long giving size of metadata].
        byte[] metadataBytes =
                Arrays.copyOfRange(output.toByteArray(), 0, output.size() - Long.BYTES);
        ChunksMetadataProto.ChunksMetadata actualMetadata =
                ChunksMetadataProto.ChunksMetadata.parseFrom(metadataBytes);
        assertThat(actualMetadata.checksumType).isEqualTo(ChunksMetadataProto.SHA_256);
        assertThat(actualMetadata.cipherType).isEqualTo(ChunksMetadataProto.AES_256_GCM);
    }

    @Test
    public void finish_writesMetadataPosition() throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        BackupFileBuilder builder = BackupFileBuilder.createForNonIncremental(output);

        builder.writeChunks(
                ImmutableList.of(TEST_HASH_1, TEST_HASH_2),
                getNewChunkMap(TEST_HASH_1, TEST_HASH_2));
        builder.writeChunks(ImmutableList.of(TEST_HASH_3), getNewChunkMap(TEST_HASH_3));
        builder.finish(getTestMetadata());

        long expectedPosition =
                (long) mEncryptedChunkEncoder.getEncodedLengthOfChunk(TEST_CHUNK_1)
                        + mEncryptedChunkEncoder.getEncodedLengthOfChunk(TEST_CHUNK_2)
                        + mEncryptedChunkEncoder.getEncodedLengthOfChunk(TEST_CHUNK_3);
        long actualPosition =
                Longs.fromByteArray(
                        Arrays.copyOfRange(
                                output.toByteArray(), output.size() - Long.BYTES, output.size()));
        assertThat(actualPosition).isEqualTo(expectedPosition);
    }

    @Test
    public void finish_flushesOutputStream() throws Exception {
        OutputStream diffOutputStream = mock(OutputStream.class);
        BackupFileBuilder backupFileBuilder =
                BackupFileBuilder.createForIncremental(
                        diffOutputStream, new ChunksMetadataProto.ChunkListing());

        backupFileBuilder.writeChunks(ImmutableList.of(TEST_HASH_1), getNewChunkMap(TEST_HASH_1));
        diffOutputStream.flush();

        verify(diffOutputStream).flush();
    }

    private void setUpOldBackupWithChunks(List<EncryptedChunk> chunks) throws Exception {
        mOldFile = mTemporaryFolder.newFile();
        ChunksMetadataProto.ChunkListing chunkListing = new ChunksMetadataProto.ChunkListing();
        chunkListing.fingerprintMixerSalt =
                Arrays.copyOf(TEST_FINGERPRINT_MIXER_SALT, TEST_FINGERPRINT_MIXER_SALT.length);
        chunkListing.cipherType = AES_256_GCM;
        chunkListing.chunkOrderingType = CHUNK_ORDERING_TYPE_UNSPECIFIED;

        List<ChunksMetadataProto.Chunk> knownChunks = new ArrayList<>();
        try (FileOutputStream outputStream = new FileOutputStream(mOldFile)) {
            for (EncryptedChunk chunk : chunks) {
                // Chunks are encoded in the format [nonce]+[data].
                outputStream.write(chunk.nonce());
                outputStream.write(chunk.encryptedBytes());

                knownChunks.add(createChunkFor(chunk));
            }

            outputStream.flush();
        }

        chunkListing.chunks = knownChunks.toArray(new ChunksMetadataProto.Chunk[0]);
        mOldChunkListing = chunkListing;
    }

    private byte[] parseDiffScript(byte[] diffScript) throws Exception {
        File newFile = mTemporaryFolder.newFile();
        new DiffScriptProcessor(mOldFile, newFile).process(new ByteArrayInputStream(diffScript));
        return Files.toByteArray(newFile);
    }

    private void assertListingsEqual(
            ChunksMetadataProto.ChunkListing result, ChunksMetadataProto.ChunkListing expected) {
        assertThat(result.chunks.length).isEqualTo(expected.chunks.length);
        for (int i = 0; i < result.chunks.length; i++) {
            assertWithMessage("Chunk " + i)
                    .that(result.chunks[i].length)
                    .isEqualTo(expected.chunks[i].length);
            assertWithMessage("Chunk " + i)
                    .that(result.chunks[i].hash)
                    .isEqualTo(expected.chunks[i].hash);
        }
    }

    private static ImmutableMap<ChunkHash, EncryptedChunk> getNewChunkMap(ChunkHash... hashes) {
        ImmutableMap.Builder<ChunkHash, EncryptedChunk> builder = ImmutableMap.builder();
        for (ChunkHash hash : hashes) {
            if (TEST_HASH_1.equals(hash)) {
                builder.put(TEST_HASH_1, TEST_CHUNK_1);
            } else if (TEST_HASH_2.equals(hash)) {
                builder.put(TEST_HASH_2, TEST_CHUNK_2);
            } else if (TEST_HASH_3.equals(hash)) {
                builder.put(TEST_HASH_3, TEST_CHUNK_3);
            } else {
                fail("Hash was not recognised: " + hash);
            }
        }
        return builder.build();
    }

    private static ChunksMetadataProto.ChunksMetadata getTestMetadata() {
        ChunksMetadataProto.ChunksMetadata metadata = new ChunksMetadataProto.ChunksMetadata();
        metadata.checksumType = ChunksMetadataProto.SHA_256;
        metadata.cipherType = AES_256_GCM;
        return metadata;
    }

    private static byte[] stripMetadataAndPositionFromOutput(byte[] output) {
        long metadataStart =
                Longs.fromByteArray(
                        Arrays.copyOfRange(output, output.length - Long.BYTES, output.length));
        return Arrays.copyOfRange(output, 0, (int) metadataStart);
    }

    private ChunksMetadataProto.ChunkListing expectedChunkListing() {
        ChunksMetadataProto.ChunkListing chunkListing = new ChunksMetadataProto.ChunkListing();
        chunkListing.fingerprintMixerSalt =
                Arrays.copyOf(TEST_FINGERPRINT_MIXER_SALT, TEST_FINGERPRINT_MIXER_SALT.length);
        chunkListing.cipherType = AES_256_GCM;
        chunkListing.chunkOrderingType = CHUNK_ORDERING_TYPE_UNSPECIFIED;
        chunkListing.chunks = new ChunksMetadataProto.Chunk[3];
        chunkListing.chunks[0] = createChunkFor(TEST_CHUNK_1);
        chunkListing.chunks[1] = createChunkFor(TEST_CHUNK_2);
        chunkListing.chunks[2] = createChunkFor(TEST_CHUNK_3);
        return chunkListing;
    }

    private ChunksMetadataProto.Chunk createChunkFor(EncryptedChunk encryptedChunk) {
        byte[] chunkHash = encryptedChunk.key().getHash();
        byte[] hashCopy = Arrays.copyOf(chunkHash, chunkHash.length);
        return newChunk(hashCopy, mEncryptedChunkEncoder.getEncodedLengthOfChunk(encryptedChunk));
    }
}
