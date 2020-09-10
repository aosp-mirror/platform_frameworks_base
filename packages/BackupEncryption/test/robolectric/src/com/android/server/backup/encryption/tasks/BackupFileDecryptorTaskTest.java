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

import static com.android.server.backup.testing.CryptoTestUtils.generateAesKey;
import static com.android.server.backup.testing.CryptoTestUtils.newChunkOrdering;
import static com.android.server.backup.testing.CryptoTestUtils.newChunksMetadata;
import static com.android.server.backup.testing.CryptoTestUtils.newPair;

import static com.google.common.truth.Truth.assertThat;

import static org.testng.Assert.assertThrows;
import static org.testng.Assert.expectThrows;

import android.annotation.Nullable;
import android.app.backup.BackupDataInput;
import android.platform.test.annotations.Presubmit;

import com.android.server.backup.encryption.chunk.ChunkHash;
import com.android.server.backup.encryption.chunking.ChunkHasher;
import com.android.server.backup.encryption.chunking.DecryptedChunkFileOutput;
import com.android.server.backup.encryption.chunking.EncryptedChunk;
import com.android.server.backup.encryption.chunking.cdc.FingerprintMixer;
import com.android.server.backup.encryption.kv.DecryptedChunkKvOutput;
import com.android.server.backup.encryption.protos.nano.ChunksMetadataProto;
import com.android.server.backup.encryption.protos.nano.ChunksMetadataProto.ChunkOrdering;
import com.android.server.backup.encryption.protos.nano.ChunksMetadataProto.ChunksMetadata;
import com.android.server.backup.encryption.protos.nano.KeyValuePairProto.KeyValuePair;
import com.android.server.backup.encryption.tasks.BackupEncrypter.Result;
import com.android.server.backup.testing.CryptoTestUtils;
import com.android.server.testing.shadows.ShadowBackupDataInput;

import com.google.common.collect.ImmutableMap;
import com.google.protobuf.nano.MessageNano;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.mockito.MockitoAnnotations;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileDescriptor;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.RandomAccessFile;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

import javax.crypto.AEADBadTagException;
import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

@Config(shadows = {ShadowBackupDataInput.class})
@RunWith(RobolectricTestRunner.class)
@Presubmit
public class BackupFileDecryptorTaskTest {
    private static final String READ_WRITE_MODE = "rw";
    private static final int BYTES_PER_KILOBYTE = 1024;
    private static final int MIN_CHUNK_SIZE_BYTES = 2 * BYTES_PER_KILOBYTE;
    private static final int AVERAGE_CHUNK_SIZE_BYTES = 4 * BYTES_PER_KILOBYTE;
    private static final int MAX_CHUNK_SIZE_BYTES = 64 * BYTES_PER_KILOBYTE;
    private static final int BACKUP_DATA_SIZE_BYTES = 60 * BYTES_PER_KILOBYTE;
    private static final int GCM_NONCE_LENGTH_BYTES = 12;
    private static final int GCM_TAG_LENGTH_BYTES = 16;
    private static final int BITS_PER_BYTE = 8;
    private static final int CHECKSUM_LENGTH_BYTES = 256 / BITS_PER_BYTE;
    @Nullable private static final FileDescriptor NULL_FILE_DESCRIPTOR = null;

    private static final Set<KeyValuePair> TEST_KV_DATA = new HashSet<>();

    static {
        TEST_KV_DATA.add(newPair("key1", "value1"));
        TEST_KV_DATA.add(newPair("key2", "value2"));
    }

    @Rule public final TemporaryFolder mTemporaryFolder = new TemporaryFolder();

    private SecretKey mTertiaryKey;
    private SecretKey mChunkEncryptionKey;
    private File mInputFile;
    private File mOutputFile;
    private DecryptedChunkOutput mFileOutput;
    private DecryptedChunkKvOutput mKvOutput;
    private Random mRandom;
    private BackupFileDecryptorTask mTask;

    @Before
    public void setUp() throws Exception {
        MockitoAnnotations.initMocks(this);

        mRandom = new Random();
        mTertiaryKey = generateAesKey();
        // In good situations it's always the same. We allow changing it for testing when somehow it
        // has become mismatched that we throw an error.
        mChunkEncryptionKey = mTertiaryKey;
        mInputFile = mTemporaryFolder.newFile();
        mOutputFile = mTemporaryFolder.newFile();
        mFileOutput = new DecryptedChunkFileOutput(mOutputFile);
        mKvOutput = new DecryptedChunkKvOutput(new ChunkHasher(mTertiaryKey));
        mTask = new BackupFileDecryptorTask(mTertiaryKey);
    }

    @Test
    public void decryptFile_throwsForNonExistentInput() throws Exception {
        assertThrows(
                FileNotFoundException.class,
                () ->
                        mTask.decryptFile(
                                new File(mTemporaryFolder.newFolder(), "nonexistent"),
                                mFileOutput));
    }

    @Test
    public void decryptFile_throwsForDirectoryInputFile() throws Exception {
        assertThrows(
                FileNotFoundException.class,
                () -> mTask.decryptFile(mTemporaryFolder.newFolder(), mFileOutput));
    }

    @Test
    public void decryptFile_withExplicitStarts_decryptsEncryptedData() throws Exception {
        byte[] backupData = randomData(BACKUP_DATA_SIZE_BYTES);
        createEncryptedFileUsingExplicitStarts(backupData);

        mTask.decryptFile(mInputFile, mFileOutput);

        assertThat(Files.readAllBytes(Paths.get(mOutputFile.toURI()))).isEqualTo(backupData);
    }

    @Test
    public void decryptFile_withInlineLengths_decryptsEncryptedData() throws Exception {
        createEncryptedFileUsingInlineLengths(
                TEST_KV_DATA, chunkOrdering -> chunkOrdering, chunksMetadata -> chunksMetadata);
        mTask.decryptFile(mInputFile, mKvOutput);
        assertThat(asMap(mKvOutput.getPairs())).containsExactlyEntriesIn(asMap(TEST_KV_DATA));
    }

    @Test
    public void decryptFile_withNoChunkOrderingType_decryptsUsingExplicitStarts() throws Exception {
        byte[] backupData = randomData(BACKUP_DATA_SIZE_BYTES);
        createEncryptedFileUsingExplicitStarts(
                backupData,
                chunkOrdering -> chunkOrdering,
                chunksMetadata -> {
                    ChunksMetadata metadata = CryptoTestUtils.clone(chunksMetadata);
                    metadata.chunkOrderingType =
                            ChunksMetadataProto.CHUNK_ORDERING_TYPE_UNSPECIFIED;
                    return metadata;
                });

        mTask.decryptFile(mInputFile, mFileOutput);

        assertThat(Files.readAllBytes(Paths.get(mOutputFile.toURI()))).isEqualTo(backupData);
    }

    @Test
    public void decryptFile_withInlineLengths_throwsForZeroLengths() throws Exception {
        createEncryptedFileUsingInlineLengths(
                TEST_KV_DATA, chunkOrdering -> chunkOrdering, chunksMetadata -> chunksMetadata);

        // Set the length of the first chunk to zero.
        RandomAccessFile raf = new RandomAccessFile(mInputFile, READ_WRITE_MODE);
        raf.seek(0);
        raf.writeInt(0);

        assertThrows(
                MalformedEncryptedFileException.class,
                () -> mTask.decryptFile(mInputFile, mKvOutput));
    }

    @Test
    public void decryptFile_withInlineLengths_throwsForLongLengths() throws Exception {
        createEncryptedFileUsingInlineLengths(
                TEST_KV_DATA, chunkOrdering -> chunkOrdering, chunksMetadata -> chunksMetadata);

        // Set the length of the first chunk to zero.
        RandomAccessFile raf = new RandomAccessFile(mInputFile, READ_WRITE_MODE);
        raf.seek(0);
        raf.writeInt((int) mInputFile.length());

        assertThrows(
                MalformedEncryptedFileException.class,
                () -> mTask.decryptFile(mInputFile, mKvOutput));
    }

    @Test
    public void decryptFile_throwsForBadKey() throws Exception {
        createEncryptedFileUsingExplicitStarts(randomData(BACKUP_DATA_SIZE_BYTES));

        assertThrows(
                AEADBadTagException.class,
                () ->
                        new BackupFileDecryptorTask(generateAesKey())
                                .decryptFile(mInputFile, mFileOutput));
    }

    @Test
    public void decryptFile_withExplicitStarts_throwsForMangledOrdering() throws Exception {
        createEncryptedFileUsingExplicitStarts(
                randomData(BACKUP_DATA_SIZE_BYTES),
                chunkOrdering -> {
                    ChunkOrdering ordering = CryptoTestUtils.clone(chunkOrdering);
                    Arrays.sort(ordering.starts);
                    return ordering;
                });

        assertThrows(
                MessageDigestMismatchException.class,
                () -> mTask.decryptFile(mInputFile, mFileOutput));
    }

    @Test
    public void decryptFile_withExplicitStarts_noChunks_returnsNoData() throws Exception {
        byte[] backupData = randomData(/*length=*/ 0);
        createEncryptedFileUsingExplicitStarts(
                backupData,
                chunkOrdering -> {
                    ChunkOrdering ordering = CryptoTestUtils.clone(chunkOrdering);
                    ordering.starts = new int[0];
                    return ordering;
                });

        mTask.decryptFile(mInputFile, mFileOutput);

        assertThat(Files.readAllBytes(Paths.get(mOutputFile.toURI()))).isEqualTo(backupData);
    }

    @Test
    public void decryptFile_throwsForMismatchedChecksum() throws Exception {
        createEncryptedFileUsingExplicitStarts(
                randomData(BACKUP_DATA_SIZE_BYTES),
                chunkOrdering -> {
                    ChunkOrdering ordering = CryptoTestUtils.clone(chunkOrdering);
                    ordering.checksum =
                            Arrays.copyOf(randomData(CHECKSUM_LENGTH_BYTES), CHECKSUM_LENGTH_BYTES);
                    return ordering;
                });

        assertThrows(
                MessageDigestMismatchException.class,
                () -> mTask.decryptFile(mInputFile, mFileOutput));
    }

    @Test
    public void decryptFile_throwsForBadChunksMetadataOffset() throws Exception {
        createEncryptedFileUsingExplicitStarts(randomData(BACKUP_DATA_SIZE_BYTES));

        // Replace the metadata with all 1s.
        RandomAccessFile raf = new RandomAccessFile(mInputFile, READ_WRITE_MODE);
        raf.seek(raf.length() - Long.BYTES);
        int metadataOffset = (int) raf.readLong();
        int metadataLength = (int) raf.length() - metadataOffset - Long.BYTES;

        byte[] allOnes = new byte[metadataLength];
        Arrays.fill(allOnes, (byte) 1);

        raf.seek(metadataOffset);
        raf.write(allOnes, /*off=*/ 0, metadataLength);

        MalformedEncryptedFileException thrown =
                expectThrows(
                        MalformedEncryptedFileException.class,
                        () -> mTask.decryptFile(mInputFile, mFileOutput));
        assertThat(thrown)
                .hasMessageThat()
                .isEqualTo(
                        "Could not read chunks metadata at position "
                                + metadataOffset
                                + " of file of "
                                + raf.length()
                                + " bytes");
    }

    @Test
    public void decryptFile_throwsForChunksMetadataOffsetBeyondEndOfFile() throws Exception {
        createEncryptedFileUsingExplicitStarts(randomData(BACKUP_DATA_SIZE_BYTES));

        RandomAccessFile raf = new RandomAccessFile(mInputFile, READ_WRITE_MODE);
        raf.seek(raf.length() - Long.BYTES);
        raf.writeLong(raf.length());

        MalformedEncryptedFileException thrown =
                expectThrows(
                        MalformedEncryptedFileException.class,
                        () -> mTask.decryptFile(mInputFile, mFileOutput));
        assertThat(thrown)
                .hasMessageThat()
                .isEqualTo(
                        raf.length()
                                + " is not valid position for chunks metadata in file of "
                                + raf.length()
                                + " bytes");
    }

    @Test
    public void decryptFile_throwsForChunksMetadataOffsetBeforeBeginningOfFile() throws Exception {
        createEncryptedFileUsingExplicitStarts(randomData(BACKUP_DATA_SIZE_BYTES));

        RandomAccessFile raf = new RandomAccessFile(mInputFile, READ_WRITE_MODE);
        raf.seek(raf.length() - Long.BYTES);
        raf.writeLong(-1);

        MalformedEncryptedFileException thrown =
                expectThrows(
                        MalformedEncryptedFileException.class,
                        () -> mTask.decryptFile(mInputFile, mFileOutput));
        assertThat(thrown)
                .hasMessageThat()
                .isEqualTo(
                        "-1 is not valid position for chunks metadata in file of "
                                + raf.length()
                                + " bytes");
    }

    @Test
    public void decryptFile_throwsForMangledChunks() throws Exception {
        createEncryptedFileUsingExplicitStarts(randomData(BACKUP_DATA_SIZE_BYTES));

        // Mess up some bits in a random byte
        RandomAccessFile raf = new RandomAccessFile(mInputFile, READ_WRITE_MODE);
        raf.seek(50);
        byte fiftiethByte = raf.readByte();
        raf.seek(50);
        raf.write(~fiftiethByte);

        assertThrows(AEADBadTagException.class, () -> mTask.decryptFile(mInputFile, mFileOutput));
    }

    @Test
    public void decryptFile_throwsForBadChunkEncryptionKey() throws Exception {
        mChunkEncryptionKey = generateAesKey();

        createEncryptedFileUsingExplicitStarts(randomData(BACKUP_DATA_SIZE_BYTES));

        assertThrows(AEADBadTagException.class, () -> mTask.decryptFile(mInputFile, mFileOutput));
    }

    @Test
    public void decryptFile_throwsForUnsupportedCipherType() throws Exception {
        createEncryptedFileUsingExplicitStarts(
                randomData(BACKUP_DATA_SIZE_BYTES),
                chunkOrdering -> chunkOrdering,
                chunksMetadata -> {
                    ChunksMetadata metadata = CryptoTestUtils.clone(chunksMetadata);
                    metadata.cipherType = ChunksMetadataProto.UNKNOWN_CIPHER_TYPE;
                    return metadata;
                });

        assertThrows(
                UnsupportedEncryptedFileException.class,
                () -> mTask.decryptFile(mInputFile, mFileOutput));
    }

    @Test
    public void decryptFile_throwsForUnsupportedMessageDigestType() throws Exception {
        createEncryptedFileUsingExplicitStarts(
                randomData(BACKUP_DATA_SIZE_BYTES),
                chunkOrdering -> chunkOrdering,
                chunksMetadata -> {
                    ChunksMetadata metadata = CryptoTestUtils.clone(chunksMetadata);
                    metadata.checksumType = ChunksMetadataProto.UNKNOWN_CHECKSUM_TYPE;
                    return metadata;
                });

        assertThrows(
                UnsupportedEncryptedFileException.class,
                () -> mTask.decryptFile(mInputFile, mFileOutput));
    }

    /**
     * Creates an encrypted backup file from the given data.
     *
     * @param data The plaintext content.
     */
    private void createEncryptedFileUsingExplicitStarts(byte[] data) throws Exception {
        createEncryptedFileUsingExplicitStarts(data, chunkOrdering -> chunkOrdering);
    }

    /**
     * Creates an encrypted backup file from the given data.
     *
     * @param data The plaintext content.
     * @param chunkOrderingTransformer Transforms the ordering before it's encrypted.
     */
    private void createEncryptedFileUsingExplicitStarts(
            byte[] data, Transformer<ChunkOrdering> chunkOrderingTransformer) throws Exception {
        createEncryptedFileUsingExplicitStarts(
                data, chunkOrderingTransformer, chunksMetadata -> chunksMetadata);
    }

    /**
     * Creates an encrypted backup file from the given data in mode {@link
     * ChunksMetadataProto#EXPLICIT_STARTS}.
     *
     * @param data The plaintext content.
     * @param chunkOrderingTransformer Transforms the ordering before it's encrypted.
     * @param chunksMetadataTransformer Transforms the metadata before it's written.
     */
    private void createEncryptedFileUsingExplicitStarts(
            byte[] data,
            Transformer<ChunkOrdering> chunkOrderingTransformer,
            Transformer<ChunksMetadata> chunksMetadataTransformer)
            throws Exception {
        Result result = backupFullData(data);

        ArrayList<EncryptedChunk> chunks = new ArrayList<>(result.getNewChunks());
        Collections.shuffle(chunks);
        HashMap<ChunkHash, Integer> startPositions = new HashMap<>();

        try (FileOutputStream fos = new FileOutputStream(mInputFile);
                DataOutputStream dos = new DataOutputStream(fos)) {
            int position = 0;

            for (EncryptedChunk chunk : chunks) {
                startPositions.put(chunk.key(), position);
                dos.write(chunk.nonce());
                dos.write(chunk.encryptedBytes());
                position += chunk.nonce().length + chunk.encryptedBytes().length;
            }

            int[] starts = new int[chunks.size()];
            List<ChunkHash> chunkListing = result.getAllChunks();

            for (int i = 0; i < chunks.size(); i++) {
                starts[i] = startPositions.get(chunkListing.get(i));
            }

            ChunkOrdering chunkOrdering = newChunkOrdering(starts, result.getDigest());
            chunkOrdering = chunkOrderingTransformer.accept(chunkOrdering);

            ChunksMetadata metadata =
                    newChunksMetadata(
                            ChunksMetadataProto.AES_256_GCM,
                            ChunksMetadataProto.SHA_256,
                            ChunksMetadataProto.EXPLICIT_STARTS,
                            encrypt(chunkOrdering));
            metadata = chunksMetadataTransformer.accept(metadata);

            dos.write(MessageNano.toByteArray(metadata));
            dos.writeLong(position);
        }
    }

    /**
     * Creates an encrypted backup file from the given data in mode {@link
     * ChunksMetadataProto#INLINE_LENGTHS}.
     *
     * @param data The plaintext key value pairs to back up.
     * @param chunkOrderingTransformer Transforms the ordering before it's encrypted.
     * @param chunksMetadataTransformer Transforms the metadata before it's written.
     */
    private void createEncryptedFileUsingInlineLengths(
            Set<KeyValuePair> data,
            Transformer<ChunkOrdering> chunkOrderingTransformer,
            Transformer<ChunksMetadata> chunksMetadataTransformer)
            throws Exception {
        Result result = backupKvData(data);

        List<EncryptedChunk> chunks = new ArrayList<>(result.getNewChunks());
        System.out.println("we have chunk count " + chunks.size());
        Collections.shuffle(chunks);

        try (FileOutputStream fos = new FileOutputStream(mInputFile);
                DataOutputStream dos = new DataOutputStream(fos)) {
            for (EncryptedChunk chunk : chunks) {
                dos.writeInt(chunk.nonce().length + chunk.encryptedBytes().length);
                dos.write(chunk.nonce());
                dos.write(chunk.encryptedBytes());
            }

            ChunkOrdering chunkOrdering = newChunkOrdering(null, result.getDigest());
            chunkOrdering = chunkOrderingTransformer.accept(chunkOrdering);

            ChunksMetadata metadata =
                    newChunksMetadata(
                            ChunksMetadataProto.AES_256_GCM,
                            ChunksMetadataProto.SHA_256,
                            ChunksMetadataProto.INLINE_LENGTHS,
                            encrypt(chunkOrdering));
            metadata = chunksMetadataTransformer.accept(metadata);

            int metadataStart = dos.size();
            dos.write(MessageNano.toByteArray(metadata));
            dos.writeLong(metadataStart);
        }
    }

    /** Performs a full backup of the given data, and returns the chunks. */
    private BackupEncrypter.Result backupFullData(byte[] data) throws Exception {
        BackupStreamEncrypter encrypter =
                new BackupStreamEncrypter(
                        new ByteArrayInputStream(data),
                        MIN_CHUNK_SIZE_BYTES,
                        MAX_CHUNK_SIZE_BYTES,
                        AVERAGE_CHUNK_SIZE_BYTES);
        return encrypter.backup(
                mChunkEncryptionKey,
                randomData(FingerprintMixer.SALT_LENGTH_BYTES),
                new HashSet<>());
    }

    private Result backupKvData(Set<KeyValuePair> data) throws Exception {
        ShadowBackupDataInput.reset();
        for (KeyValuePair pair : data) {
            ShadowBackupDataInput.addEntity(pair.key, pair.value);
        }
        KvBackupEncrypter encrypter =
                new KvBackupEncrypter(new BackupDataInput(NULL_FILE_DESCRIPTOR));
        return encrypter.backup(
                mChunkEncryptionKey,
                randomData(FingerprintMixer.SALT_LENGTH_BYTES),
                Collections.EMPTY_SET);
    }

    /** Encrypts {@code chunkOrdering} using {@link #mTertiaryKey}. */
    private byte[] encrypt(ChunkOrdering chunkOrdering) throws Exception {
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        byte[] nonce = randomData(GCM_NONCE_LENGTH_BYTES);
        cipher.init(
                Cipher.ENCRYPT_MODE,
                mTertiaryKey,
                new GCMParameterSpec(GCM_TAG_LENGTH_BYTES * BITS_PER_BYTE, nonce));
        byte[] nanoBytes = MessageNano.toByteArray(chunkOrdering);
        byte[] encryptedBytes = cipher.doFinal(nanoBytes);

        try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            out.write(nonce);
            out.write(encryptedBytes);
            return out.toByteArray();
        }
    }

    /** Returns {@code length} random bytes. */
    private byte[] randomData(int length) {
        byte[] data = new byte[length];
        mRandom.nextBytes(data);
        return data;
    }

    private static ImmutableMap<String, String> asMap(Collection<KeyValuePair> pairs) {
        ImmutableMap.Builder<String, String> map = ImmutableMap.builder();
        for (KeyValuePair pair : pairs) {
            map.put(pair.key, new String(pair.value, Charset.forName("UTF-8")));
        }
        return map.build();
    }

    private interface Transformer<T> {
        T accept(T t);
    }
}
