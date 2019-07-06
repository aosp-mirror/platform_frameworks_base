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

package com.android.server.backup.encryption.tasks;

import static com.google.common.truth.Truth.assertThat;

import android.platform.test.annotations.Presubmit;

import com.android.server.backup.encryption.chunk.ChunkHash;
import com.android.server.backup.encryption.chunking.EncryptedChunk;
import com.android.server.backup.testing.CryptoTestUtils;
import com.android.server.backup.testing.RandomInputStream;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

import java.io.ByteArrayInputStream;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Random;

import javax.crypto.SecretKey;

@RunWith(RobolectricTestRunner.class)
@Presubmit
public class BackupStreamEncrypterTest {
    private static final int SALT_LENGTH = 32;
    private static final int BITS_PER_BYTE = 8;
    private static final int BYTES_PER_KILOBYTE = 1024;
    private static final int BYTES_PER_MEGABYTE = 1024 * 1024;
    private static final int MIN_CHUNK_SIZE = 2 * BYTES_PER_KILOBYTE;
    private static final int AVERAGE_CHUNK_SIZE = 4 * BYTES_PER_KILOBYTE;
    private static final int MAX_CHUNK_SIZE = 64 * BYTES_PER_KILOBYTE;
    private static final int BACKUP_SIZE = 2 * BYTES_PER_MEGABYTE;
    private static final int SMALL_BACKUP_SIZE = BYTES_PER_KILOBYTE;
    // 16 bytes for the mac. iv is encoded in a separate field.
    private static final int BYTES_OVERHEAD_PER_CHUNK = 16;
    private static final int MESSAGE_DIGEST_SIZE_IN_BYTES = 256 / BITS_PER_BYTE;
    private static final int RANDOM_SEED = 42;
    private static final double TOLERANCE = 0.1;

    private Random mRandom;
    private SecretKey mSecretKey;
    private byte[] mSalt;

    @Before
    public void setUp() throws Exception {
        mSecretKey = CryptoTestUtils.generateAesKey();

        mSalt = new byte[SALT_LENGTH];
        // Make these tests deterministic
        mRandom = new Random(RANDOM_SEED);
        mRandom.nextBytes(mSalt);
    }

    @Test
    public void testBackup_producesChunksOfTheGivenAverageSize() throws Exception {
        BackupEncrypter.Result result = runBackup(BACKUP_SIZE);

        long totalSize = 0;
        for (EncryptedChunk chunk : result.getNewChunks()) {
            totalSize += chunk.encryptedBytes().length;
        }

        double meanSize = totalSize / result.getNewChunks().size();
        double expectedChunkSize = AVERAGE_CHUNK_SIZE + BYTES_OVERHEAD_PER_CHUNK;
        assertThat(Math.abs(meanSize - expectedChunkSize) / expectedChunkSize)
                .isLessThan(TOLERANCE);
    }

    @Test
    public void testBackup_producesNoChunksSmallerThanMinSize() throws Exception {
        BackupEncrypter.Result result = runBackup(BACKUP_SIZE);
        List<EncryptedChunk> chunks = result.getNewChunks();

        // Last chunk could be smaller, depending on the file size and how it is chunked
        for (EncryptedChunk chunk : chunks.subList(0, chunks.size() - 2)) {
            assertThat(chunk.encryptedBytes().length)
                    .isAtLeast(MIN_CHUNK_SIZE + BYTES_OVERHEAD_PER_CHUNK);
        }
    }

    @Test
    public void testBackup_producesNoChunksLargerThanMaxSize() throws Exception {
        BackupEncrypter.Result result = runBackup(BACKUP_SIZE);
        List<EncryptedChunk> chunks = result.getNewChunks();

        for (EncryptedChunk chunk : chunks) {
            assertThat(chunk.encryptedBytes().length)
                    .isAtMost(MAX_CHUNK_SIZE + BYTES_OVERHEAD_PER_CHUNK);
        }
    }

    @Test
    public void testBackup_producesAFileOfTheExpectedSize() throws Exception {
        BackupEncrypter.Result result = runBackup(BACKUP_SIZE);
        HashMap<ChunkHash, EncryptedChunk> chunksBySha256 =
                chunksIndexedByKey(result.getNewChunks());

        int expectedSize = BACKUP_SIZE + result.getAllChunks().size() * BYTES_OVERHEAD_PER_CHUNK;
        int size = 0;
        for (ChunkHash byteString : result.getAllChunks()) {
            size += chunksBySha256.get(byteString).encryptedBytes().length;
        }
        assertThat(size).isEqualTo(expectedSize);
    }

    @Test
    public void testBackup_forSameFile_producesNoNewChunks() throws Exception {
        byte[] backupData = getRandomData(BACKUP_SIZE);
        BackupEncrypter.Result result = runBackup(backupData, ImmutableList.of());

        BackupEncrypter.Result incrementalResult = runBackup(backupData, result.getAllChunks());

        assertThat(incrementalResult.getNewChunks()).isEmpty();
    }

    @Test
    public void testBackup_onlyUpdatesChangedChunks() throws Exception {
        byte[] backupData = getRandomData(BACKUP_SIZE);
        BackupEncrypter.Result result = runBackup(backupData, ImmutableList.of());

        // Let's update the 2nd and 5th chunk
        backupData[positionOfChunk(result, 1)]++;
        backupData[positionOfChunk(result, 4)]++;
        BackupEncrypter.Result incrementalResult = runBackup(backupData, result.getAllChunks());

        assertThat(incrementalResult.getNewChunks()).hasSize(2);
    }

    @Test
    public void testBackup_doesNotIncludeUpdatedChunksInNewListing() throws Exception {
        byte[] backupData = getRandomData(BACKUP_SIZE);
        BackupEncrypter.Result result = runBackup(backupData, ImmutableList.of());

        // Let's update the 2nd and 5th chunk
        backupData[positionOfChunk(result, 1)]++;
        backupData[positionOfChunk(result, 4)]++;
        BackupEncrypter.Result incrementalResult = runBackup(backupData, result.getAllChunks());

        List<EncryptedChunk> newChunks = incrementalResult.getNewChunks();
        List<ChunkHash> chunkListing = result.getAllChunks();
        assertThat(newChunks).doesNotContain(chunkListing.get(1));
        assertThat(newChunks).doesNotContain(chunkListing.get(4));
    }

    @Test
    public void testBackup_includesUnchangedChunksInNewListing() throws Exception {
        byte[] backupData = getRandomData(BACKUP_SIZE);
        BackupEncrypter.Result result = runBackup(backupData, ImmutableList.of());

        // Let's update the 2nd and 5th chunk
        backupData[positionOfChunk(result, 1)]++;
        backupData[positionOfChunk(result, 4)]++;
        BackupEncrypter.Result incrementalResult = runBackup(backupData, result.getAllChunks());

        HashSet<ChunkHash> chunksPresentInIncremental =
                new HashSet<>(incrementalResult.getAllChunks());
        chunksPresentInIncremental.removeAll(result.getAllChunks());

        assertThat(chunksPresentInIncremental).hasSize(2);
    }

    @Test
    public void testBackup_forSameData_createsSameDigest() throws Exception {
        byte[] backupData = getRandomData(SMALL_BACKUP_SIZE);

        BackupEncrypter.Result result = runBackup(backupData, ImmutableList.of());
        BackupEncrypter.Result result2 = runBackup(backupData, ImmutableList.of());
        assertThat(result.getDigest()).isEqualTo(result2.getDigest());
    }

    @Test
    public void testBackup_forDifferentData_createsDifferentDigest() throws Exception {
        byte[] backup1Data = getRandomData(SMALL_BACKUP_SIZE);
        byte[] backup2Data = getRandomData(SMALL_BACKUP_SIZE);

        BackupEncrypter.Result result = runBackup(backup1Data, ImmutableList.of());
        BackupEncrypter.Result result2 = runBackup(backup2Data, ImmutableList.of());
        assertThat(result.getDigest()).isNotEqualTo(result2.getDigest());
    }

    @Test
    public void testBackup_createsDigestOf32Bytes() throws Exception {
        assertThat(runBackup(getRandomData(SMALL_BACKUP_SIZE), ImmutableList.of()).getDigest())
                .hasLength(MESSAGE_DIGEST_SIZE_IN_BYTES);
    }

    private byte[] getRandomData(int size) throws Exception {
        RandomInputStream randomInputStream = new RandomInputStream(mRandom, size);
        byte[] backupData = new byte[size];
        randomInputStream.read(backupData);
        return backupData;
    }

    private BackupEncrypter.Result runBackup(int backupSize) throws Exception {
        RandomInputStream dataStream = new RandomInputStream(mRandom, backupSize);
        BackupStreamEncrypter task =
                new BackupStreamEncrypter(
                        dataStream, MIN_CHUNK_SIZE, MAX_CHUNK_SIZE, AVERAGE_CHUNK_SIZE);
        return task.backup(mSecretKey, mSalt, ImmutableSet.of());
    }

    private BackupEncrypter.Result runBackup(byte[] data, List<ChunkHash> existingChunks)
            throws Exception {
        ByteArrayInputStream dataStream = new ByteArrayInputStream(data);
        BackupStreamEncrypter task =
                new BackupStreamEncrypter(
                        dataStream, MIN_CHUNK_SIZE, MAX_CHUNK_SIZE, AVERAGE_CHUNK_SIZE);
        return task.backup(mSecretKey, mSalt, ImmutableSet.copyOf(existingChunks));
    }

    /** Returns a {@link HashMap} of the chunks, indexed by the SHA-256 Mac key. */
    private static HashMap<ChunkHash, EncryptedChunk> chunksIndexedByKey(
            List<EncryptedChunk> chunks) {
        HashMap<ChunkHash, EncryptedChunk> chunksByKey = new HashMap<>();
        for (EncryptedChunk chunk : chunks) {
            chunksByKey.put(chunk.key(), chunk);
        }
        return chunksByKey;
    }

    /**
     * Returns the start position of the chunk in the plaintext backup data.
     *
     * @param result The result from a backup.
     * @param index The index of the chunk in question.
     * @return the start position.
     */
    private static int positionOfChunk(BackupEncrypter.Result result, int index) {
        HashMap<ChunkHash, EncryptedChunk> byKey = chunksIndexedByKey(result.getNewChunks());
        List<ChunkHash> listing = result.getAllChunks();

        int position = 0;
        for (int i = 0; i < index - 1; i++) {
            EncryptedChunk chunk = byKey.get(listing.get(i));
            position += chunk.encryptedBytes().length - BYTES_OVERHEAD_PER_CHUNK;
        }

        return position;
    }
}
