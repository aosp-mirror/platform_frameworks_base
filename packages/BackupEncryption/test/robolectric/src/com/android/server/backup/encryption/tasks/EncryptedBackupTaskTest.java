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

import static com.android.server.backup.encryption.protos.nano.ChunksMetadataProto.AES_256_GCM;
import static com.android.server.backup.encryption.protos.nano.ChunksMetadataProto.CHUNK_ORDERING_TYPE_UNSPECIFIED;
import static com.android.server.backup.encryption.protos.nano.ChunksMetadataProto.SHA_256;

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Matchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.testng.Assert.assertThrows;

import android.platform.test.annotations.Presubmit;

import com.android.server.backup.encryption.chunk.ChunkHash;
import com.android.server.backup.encryption.chunking.BackupFileBuilder;
import com.android.server.backup.encryption.chunking.EncryptedChunk;
import com.android.server.backup.encryption.chunking.EncryptedChunkEncoder;
import com.android.server.backup.encryption.chunking.LengthlessEncryptedChunkEncoder;
import com.android.server.backup.encryption.client.CryptoBackupServer;
import com.android.server.backup.encryption.keys.TertiaryKeyGenerator;
import com.android.server.backup.encryption.protos.nano.ChunksMetadataProto;
import com.android.server.backup.encryption.protos.nano.ChunksMetadataProto.ChunkListing;
import com.android.server.backup.encryption.protos.nano.ChunksMetadataProto.ChunkOrdering;
import com.android.server.backup.encryption.protos.nano.ChunksMetadataProto.ChunksMetadata;
import com.android.server.backup.encryption.protos.nano.WrappedKeyProto.WrappedKey;
import com.android.server.backup.encryption.tasks.BackupEncrypter.Result;
import com.android.server.backup.testing.CryptoTestUtils;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.protobuf.nano.MessageNano;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;

import java.io.OutputStream;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.concurrent.CancellationException;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

@Config(shadows = {EncryptedBackupTaskTest.ShadowBackupFileBuilder.class})
@RunWith(RobolectricTestRunner.class)
@Presubmit
public class EncryptedBackupTaskTest {

    private static final String CIPHER_ALGORITHM = "AES/GCM/NoPadding";
    private static final int GCM_NONCE_LENGTH_BYTES = 12;
    private static final int GCM_TAG_LENGTH_BYTES = 16;
    private static final int BITS_PER_BYTE = 8;

    private static final byte[] TEST_FINGERPRINT_MIXER_SALT =
            Arrays.copyOf(new byte[] {22}, ChunkHash.HASH_LENGTH_BYTES);

    private static final byte[] TEST_NONCE =
            Arrays.copyOf(new byte[] {55}, EncryptedChunk.NONCE_LENGTH_BYTES);

    private static final ChunkHash TEST_HASH_1 =
            new ChunkHash(Arrays.copyOf(new byte[] {1}, ChunkHash.HASH_LENGTH_BYTES));
    private static final ChunkHash TEST_HASH_2 =
            new ChunkHash(Arrays.copyOf(new byte[] {2}, ChunkHash.HASH_LENGTH_BYTES));
    private static final ChunkHash TEST_HASH_3 =
            new ChunkHash(Arrays.copyOf(new byte[] {3}, ChunkHash.HASH_LENGTH_BYTES));

    private static final EncryptedChunk TEST_CHUNK_1 =
            EncryptedChunk.create(TEST_HASH_1, TEST_NONCE, new byte[] {1, 2, 3, 4, 5});
    private static final EncryptedChunk TEST_CHUNK_2 =
            EncryptedChunk.create(TEST_HASH_2, TEST_NONCE, new byte[] {6, 7, 8, 9, 10});
    private static final EncryptedChunk TEST_CHUNK_3 =
            EncryptedChunk.create(TEST_HASH_3, TEST_NONCE, new byte[] {11, 12, 13, 14, 15});

    private static final byte[] TEST_CHECKSUM = Arrays.copyOf(new byte[] {10}, 258 / 8);
    private static final String TEST_PACKAGE_NAME = "com.example.package";
    private static final String TEST_OLD_DOCUMENT_ID = "old_doc_1";
    private static final String TEST_NEW_DOCUMENT_ID = "new_doc_1";

    @Captor private ArgumentCaptor<ChunksMetadata> mMetadataCaptor;

    @Mock private CryptoBackupServer mCryptoBackupServer;
    @Mock private BackupEncrypter mBackupEncrypter;
    @Mock private BackupFileBuilder mBackupFileBuilder;

    private ChunkListing mOldChunkListing;
    private SecretKey mTertiaryKey;
    private WrappedKey mWrappedTertiaryKey;
    private EncryptedChunkEncoder mEncryptedChunkEncoder;
    private EncryptedBackupTask mTask;

    @Before
    public void setUp() throws Exception {
        MockitoAnnotations.initMocks(this);

        SecureRandom secureRandom = new SecureRandom();
        mTertiaryKey = new TertiaryKeyGenerator(secureRandom).generate();
        mWrappedTertiaryKey = new WrappedKey();

        mEncryptedChunkEncoder = new LengthlessEncryptedChunkEncoder();

        ShadowBackupFileBuilder.sInstance = mBackupFileBuilder;

        mTask =
                new EncryptedBackupTask(
                        mCryptoBackupServer, secureRandom, TEST_PACKAGE_NAME, mBackupEncrypter);
    }

    @Test
    public void performNonIncrementalBackup_performsBackup() throws Exception {
        setUpWithoutExistingBackup();

        // Chunk listing and ordering don't matter for this test.
        when(mBackupFileBuilder.getNewChunkListing(any())).thenReturn(new ChunkListing());
        when(mBackupFileBuilder.getNewChunkOrdering(TEST_CHECKSUM)).thenReturn(new ChunkOrdering());

        when(mCryptoBackupServer.uploadNonIncrementalBackup(eq(TEST_PACKAGE_NAME), any(), any()))
                .thenReturn(TEST_NEW_DOCUMENT_ID);

        mTask.performNonIncrementalBackup(
                mTertiaryKey, mWrappedTertiaryKey, TEST_FINGERPRINT_MIXER_SALT);

        verify(mBackupFileBuilder)
                .writeChunks(
                        ImmutableList.of(TEST_HASH_1, TEST_HASH_2),
                        ImmutableMap.of(TEST_HASH_1, TEST_CHUNK_1, TEST_HASH_2, TEST_CHUNK_2));
        verify(mBackupFileBuilder).finish(any());
        verify(mCryptoBackupServer)
                .uploadNonIncrementalBackup(eq(TEST_PACKAGE_NAME), any(), eq(mWrappedTertiaryKey));
    }

    @Test
    public void performIncrementalBackup_performsBackup() throws Exception {
        setUpWithExistingBackup();

        // Chunk listing and ordering don't matter for this test.
        when(mBackupFileBuilder.getNewChunkListing(any())).thenReturn(new ChunkListing());
        when(mBackupFileBuilder.getNewChunkOrdering(TEST_CHECKSUM)).thenReturn(new ChunkOrdering());

        when(mCryptoBackupServer.uploadIncrementalBackup(
                        eq(TEST_PACKAGE_NAME), eq(TEST_OLD_DOCUMENT_ID), any(), any()))
                .thenReturn(TEST_NEW_DOCUMENT_ID);

        mTask.performIncrementalBackup(mTertiaryKey, mWrappedTertiaryKey, mOldChunkListing);

        verify(mBackupFileBuilder)
                .writeChunks(
                        ImmutableList.of(TEST_HASH_1, TEST_HASH_2, TEST_HASH_3),
                        ImmutableMap.of(TEST_HASH_2, TEST_CHUNK_2));
        verify(mBackupFileBuilder).finish(any());
        verify(mCryptoBackupServer)
                .uploadIncrementalBackup(
                        eq(TEST_PACKAGE_NAME),
                        eq(TEST_OLD_DOCUMENT_ID),
                        any(),
                        eq(mWrappedTertiaryKey));
    }

    @Test
    public void performIncrementalBackup_returnsNewChunkListingWithDocId() throws Exception {
        setUpWithExistingBackup();

        ChunkListing chunkListingWithoutDocId =
                CryptoTestUtils.newChunkListingWithoutDocId(
                        TEST_FINGERPRINT_MIXER_SALT,
                        AES_256_GCM,
                        CHUNK_ORDERING_TYPE_UNSPECIFIED,
                        createChunkProtoFor(TEST_HASH_1, TEST_CHUNK_1),
                        createChunkProtoFor(TEST_HASH_2, TEST_CHUNK_2));
        when(mBackupFileBuilder.getNewChunkListing(any())).thenReturn(chunkListingWithoutDocId);

        // Chunk ordering doesn't matter for this test.
        when(mBackupFileBuilder.getNewChunkOrdering(TEST_CHECKSUM)).thenReturn(new ChunkOrdering());

        when(mCryptoBackupServer.uploadIncrementalBackup(
                        eq(TEST_PACKAGE_NAME), eq(TEST_OLD_DOCUMENT_ID), any(), any()))
                .thenReturn(TEST_NEW_DOCUMENT_ID);

        ChunkListing actualChunkListing =
                mTask.performIncrementalBackup(mTertiaryKey, mWrappedTertiaryKey, mOldChunkListing);

        ChunkListing expectedChunkListing = CryptoTestUtils.clone(chunkListingWithoutDocId);
        expectedChunkListing.documentId = TEST_NEW_DOCUMENT_ID;
        assertChunkListingsAreEqual(actualChunkListing, expectedChunkListing);
    }

    @Test
    public void performNonIncrementalBackup_returnsNewChunkListingWithDocId() throws Exception {
        setUpWithoutExistingBackup();

        ChunkListing chunkListingWithoutDocId =
                CryptoTestUtils.newChunkListingWithoutDocId(
                        TEST_FINGERPRINT_MIXER_SALT,
                        AES_256_GCM,
                        CHUNK_ORDERING_TYPE_UNSPECIFIED,
                        createChunkProtoFor(TEST_HASH_1, TEST_CHUNK_1),
                        createChunkProtoFor(TEST_HASH_2, TEST_CHUNK_2));
        when(mBackupFileBuilder.getNewChunkListing(any())).thenReturn(chunkListingWithoutDocId);

        // Chunk ordering doesn't matter for this test.
        when(mBackupFileBuilder.getNewChunkOrdering(TEST_CHECKSUM)).thenReturn(new ChunkOrdering());

        when(mCryptoBackupServer.uploadNonIncrementalBackup(eq(TEST_PACKAGE_NAME), any(), any()))
                .thenReturn(TEST_NEW_DOCUMENT_ID);

        ChunkListing actualChunkListing =
                mTask.performNonIncrementalBackup(
                        mTertiaryKey, mWrappedTertiaryKey, TEST_FINGERPRINT_MIXER_SALT);

        ChunkListing expectedChunkListing = CryptoTestUtils.clone(chunkListingWithoutDocId);
        expectedChunkListing.documentId = TEST_NEW_DOCUMENT_ID;
        assertChunkListingsAreEqual(actualChunkListing, expectedChunkListing);
    }

    @Test
    public void performNonIncrementalBackup_buildsCorrectChunkMetadata() throws Exception {
        setUpWithoutExistingBackup();

        // Chunk listing doesn't matter for this test.
        when(mBackupFileBuilder.getNewChunkListing(any())).thenReturn(new ChunkListing());

        ChunkOrdering expectedOrdering =
                CryptoTestUtils.newChunkOrdering(new int[10], TEST_CHECKSUM);
        when(mBackupFileBuilder.getNewChunkOrdering(TEST_CHECKSUM)).thenReturn(expectedOrdering);

        when(mCryptoBackupServer.uploadNonIncrementalBackup(eq(TEST_PACKAGE_NAME), any(), any()))
                .thenReturn(TEST_NEW_DOCUMENT_ID);

        mTask.performNonIncrementalBackup(
                mTertiaryKey, mWrappedTertiaryKey, TEST_FINGERPRINT_MIXER_SALT);

        verify(mBackupFileBuilder).finish(mMetadataCaptor.capture());

        ChunksMetadata actualMetadata = mMetadataCaptor.getValue();
        assertThat(actualMetadata.checksumType).isEqualTo(SHA_256);
        assertThat(actualMetadata.cipherType).isEqualTo(AES_256_GCM);

        ChunkOrdering actualOrdering = decryptChunkOrdering(actualMetadata.chunkOrdering);
        assertThat(actualOrdering.checksum).isEqualTo(TEST_CHECKSUM);
        assertThat(actualOrdering.starts).isEqualTo(expectedOrdering.starts);
    }

    @Test
    public void cancel_incrementalBackup_doesNotUploadOrSaveChunkListing() throws Exception {
        setUpWithExistingBackup();

        // Chunk listing and ordering don't matter for this test.
        when(mBackupFileBuilder.getNewChunkListing(any())).thenReturn(new ChunkListing());
        when(mBackupFileBuilder.getNewChunkOrdering(TEST_CHECKSUM)).thenReturn(new ChunkOrdering());

        mTask.cancel();
        assertThrows(
                CancellationException.class,
                () ->
                        mTask.performIncrementalBackup(
                                mTertiaryKey, mWrappedTertiaryKey, mOldChunkListing));

        verify(mCryptoBackupServer, never()).uploadIncrementalBackup(any(), any(), any(), any());
        verify(mCryptoBackupServer, never()).uploadNonIncrementalBackup(any(), any(), any());
    }

    @Test
    public void cancel_nonIncrementalBackup_doesNotUploadOrSaveChunkListing() throws Exception {
        setUpWithoutExistingBackup();

        // Chunk listing and ordering don't matter for this test.
        when(mBackupFileBuilder.getNewChunkListing(any())).thenReturn(new ChunkListing());
        when(mBackupFileBuilder.getNewChunkOrdering(TEST_CHECKSUM)).thenReturn(new ChunkOrdering());

        mTask.cancel();
        assertThrows(
                CancellationException.class,
                () ->
                        mTask.performNonIncrementalBackup(
                                mTertiaryKey, mWrappedTertiaryKey, TEST_FINGERPRINT_MIXER_SALT));

        verify(mCryptoBackupServer, never()).uploadIncrementalBackup(any(), any(), any(), any());
        verify(mCryptoBackupServer, never()).uploadNonIncrementalBackup(any(), any(), any());
    }

    /** Sets up a backup of [CHUNK 1][CHUNK 2] with no existing data. */
    private void setUpWithoutExistingBackup() throws Exception {
        Result result =
                new Result(
                        ImmutableList.of(TEST_HASH_1, TEST_HASH_2),
                        ImmutableList.of(TEST_CHUNK_1, TEST_CHUNK_2),
                        TEST_CHECKSUM);
        when(mBackupEncrypter.backup(any(), eq(TEST_FINGERPRINT_MIXER_SALT), eq(ImmutableSet.of())))
                .thenReturn(result);
    }

    /**
     * Sets up a backup of [CHUNK 1][CHUNK 2][CHUNK 3] where the previous backup contained [CHUNK
     * 1][CHUNK 3].
     */
    private void setUpWithExistingBackup() throws Exception {
        mOldChunkListing =
                CryptoTestUtils.newChunkListing(
                        TEST_OLD_DOCUMENT_ID,
                        TEST_FINGERPRINT_MIXER_SALT,
                        AES_256_GCM,
                        CHUNK_ORDERING_TYPE_UNSPECIFIED,
                        createChunkProtoFor(TEST_HASH_1, TEST_CHUNK_1),
                        createChunkProtoFor(TEST_HASH_3, TEST_CHUNK_3));

        Result result =
                new Result(
                        ImmutableList.of(TEST_HASH_1, TEST_HASH_2, TEST_HASH_3),
                        ImmutableList.of(TEST_CHUNK_2),
                        TEST_CHECKSUM);
        when(mBackupEncrypter.backup(
                        any(),
                        eq(TEST_FINGERPRINT_MIXER_SALT),
                        eq(ImmutableSet.of(TEST_HASH_1, TEST_HASH_3))))
                .thenReturn(result);
    }

    private ChunksMetadataProto.Chunk createChunkProtoFor(
            ChunkHash chunkHash, EncryptedChunk encryptedChunk) {
        return CryptoTestUtils.newChunk(
                chunkHash, mEncryptedChunkEncoder.getEncodedLengthOfChunk(encryptedChunk));
    }

    private ChunkOrdering decryptChunkOrdering(byte[] encryptedOrdering) throws Exception {
        Cipher cipher = Cipher.getInstance(CIPHER_ALGORITHM);
        cipher.init(
                Cipher.DECRYPT_MODE,
                mTertiaryKey,
                new GCMParameterSpec(
                        GCM_TAG_LENGTH_BYTES * BITS_PER_BYTE,
                        encryptedOrdering,
                        /*offset=*/ 0,
                        GCM_NONCE_LENGTH_BYTES));
        byte[] decrypted =
                cipher.doFinal(
                        encryptedOrdering,
                        GCM_NONCE_LENGTH_BYTES,
                        encryptedOrdering.length - GCM_NONCE_LENGTH_BYTES);
        return ChunkOrdering.parseFrom(decrypted);
    }

    // This method is needed because nano protobuf generated classes dont implmenent
    // .equals
    private void assertChunkListingsAreEqual(ChunkListing a, ChunkListing b) {
        byte[] aBytes = MessageNano.toByteArray(a);
        byte[] bBytes = MessageNano.toByteArray(b);

        assertThat(aBytes).isEqualTo(bBytes);
    }

    @Implements(BackupFileBuilder.class)
    public static class ShadowBackupFileBuilder {

        private static BackupFileBuilder sInstance;

        @Implementation
        public static BackupFileBuilder createForNonIncremental(OutputStream outputStream) {
            return sInstance;
        }

        @Implementation
        public static BackupFileBuilder createForIncremental(
                OutputStream outputStream, ChunkListing oldChunkListing) {
            return sInstance;
        }
    }
}
