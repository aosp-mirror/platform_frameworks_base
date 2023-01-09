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

import static org.mockito.Mockito.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.testng.Assert.assertThrows;

import com.android.server.backup.encryption.chunk.ChunkHash;
import com.android.server.backup.encryption.chunking.ProtoStore;
import com.android.server.backup.encryption.chunking.cdc.FingerprintMixer;
import com.android.server.backup.encryption.keys.TertiaryKeyManager;
import com.android.server.backup.encryption.protos.nano.ChunksMetadataProto;
import com.android.server.backup.encryption.protos.nano.ChunksMetadataProto.ChunkListing;
import com.android.server.backup.encryption.protos.nano.WrappedKeyProto.WrappedKey;
import com.android.server.backup.testing.CryptoTestUtils;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.io.IOException;
import java.io.InputStream;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Optional;

import javax.crypto.SecretKey;

@Config(shadows = {EncryptedBackupTaskTest.ShadowBackupFileBuilder.class})
@RunWith(RobolectricTestRunner.class)
public class EncryptedFullBackupTaskTest {
    private static final String TEST_PACKAGE_NAME = "com.example.package";
    private static final byte[] TEST_EXISTING_FINGERPRINT_MIXER_SALT =
            Arrays.copyOf(new byte[] {11}, ChunkHash.HASH_LENGTH_BYTES);
    private static final byte[] TEST_GENERATED_FINGERPRINT_MIXER_SALT =
            Arrays.copyOf(new byte[] {22}, ChunkHash.HASH_LENGTH_BYTES);
    private static final ChunkHash TEST_CHUNK_HASH_1 =
            new ChunkHash(Arrays.copyOf(new byte[] {1}, ChunkHash.HASH_LENGTH_BYTES));
    private static final ChunkHash TEST_CHUNK_HASH_2 =
            new ChunkHash(Arrays.copyOf(new byte[] {2}, ChunkHash.HASH_LENGTH_BYTES));
    private static final int TEST_CHUNK_LENGTH_1 = 20;
    private static final int TEST_CHUNK_LENGTH_2 = 40;

    @Mock private ProtoStore<ChunkListing> mChunkListingStore;
    @Mock private TertiaryKeyManager mTertiaryKeyManager;
    @Mock private InputStream mInputStream;
    @Mock private EncryptedBackupTask mEncryptedBackupTask;
    @Mock private SecretKey mTertiaryKey;
    @Mock private SecureRandom mSecureRandom;

    private EncryptedFullBackupTask mTask;
    private ChunkListing mOldChunkListing;
    private ChunkListing mNewChunkListing;
    private WrappedKey mWrappedTertiaryKey;

    @Before
    public void setUp() throws Exception {
        MockitoAnnotations.initMocks(this);

        mWrappedTertiaryKey = new WrappedKey();
        when(mTertiaryKeyManager.getKey()).thenReturn(mTertiaryKey);
        when(mTertiaryKeyManager.getWrappedKey()).thenReturn(mWrappedTertiaryKey);

        mOldChunkListing =
                CryptoTestUtils.newChunkListing(
                        /* docId */ null,
                        TEST_EXISTING_FINGERPRINT_MIXER_SALT,
                        ChunksMetadataProto.AES_256_GCM,
                        ChunksMetadataProto.CHUNK_ORDERING_TYPE_UNSPECIFIED,
                        CryptoTestUtils.newChunk(TEST_CHUNK_HASH_1.getHash(), TEST_CHUNK_LENGTH_1));
        mNewChunkListing =
                CryptoTestUtils.newChunkListing(
                        /* docId */ null,
                        /* fingerprintSalt */ null,
                        ChunksMetadataProto.AES_256_GCM,
                        ChunksMetadataProto.CHUNK_ORDERING_TYPE_UNSPECIFIED,
                        CryptoTestUtils.newChunk(TEST_CHUNK_HASH_1.getHash(), TEST_CHUNK_LENGTH_1),
                        CryptoTestUtils.newChunk(TEST_CHUNK_HASH_2.getHash(), TEST_CHUNK_LENGTH_2));
        when(mEncryptedBackupTask.performNonIncrementalBackup(any(), any(), any()))
                .thenReturn(mNewChunkListing);
        when(mEncryptedBackupTask.performIncrementalBackup(any(), any(), any()))
                .thenReturn(mNewChunkListing);
        when(mChunkListingStore.loadProto(TEST_PACKAGE_NAME)).thenReturn(Optional.empty());

        doAnswer(invocation -> {
            byte[] byteArray = (byte[]) invocation.getArguments()[0];
            System.arraycopy(
                    TEST_GENERATED_FINGERPRINT_MIXER_SALT,
                    /* srcPos */ 0,
                    byteArray,
                    /* destPos */ 0,
                    FingerprintMixer.SALT_LENGTH_BYTES);
            return null;
        })
                .when(mSecureRandom)
                .nextBytes(any(byte[].class));

        mTask =
                new EncryptedFullBackupTask(
                        mChunkListingStore,
                        mTertiaryKeyManager,
                        mEncryptedBackupTask,
                        mInputStream,
                        TEST_PACKAGE_NAME,
                        mSecureRandom);
    }

    @Test
    public void call_existingChunkListingButTertiaryKeyRotated_performsNonIncrementalBackup()
            throws Exception {
        when(mTertiaryKeyManager.wasKeyRotated()).thenReturn(true);
        when(mChunkListingStore.loadProto(TEST_PACKAGE_NAME))
                .thenReturn(Optional.of(mOldChunkListing));

        mTask.call();

        verify(mEncryptedBackupTask)
                .performNonIncrementalBackup(
                        eq(mTertiaryKey),
                        eq(mWrappedTertiaryKey),
                        eq(TEST_GENERATED_FINGERPRINT_MIXER_SALT));
    }

    @Test
    public void call_noExistingChunkListing_performsNonIncrementalBackup() throws Exception {
        when(mChunkListingStore.loadProto(TEST_PACKAGE_NAME)).thenReturn(Optional.empty());
        mTask.call();
        verify(mEncryptedBackupTask)
                .performNonIncrementalBackup(
                        eq(mTertiaryKey),
                        eq(mWrappedTertiaryKey),
                        eq(TEST_GENERATED_FINGERPRINT_MIXER_SALT));
    }

    @Test
    public void call_existingChunkListing_performsIncrementalBackup() throws Exception {
        when(mChunkListingStore.loadProto(TEST_PACKAGE_NAME))
                .thenReturn(Optional.of(mOldChunkListing));
        mTask.call();
        verify(mEncryptedBackupTask)
                .performIncrementalBackup(
                        eq(mTertiaryKey), eq(mWrappedTertiaryKey), eq(mOldChunkListing));
    }

    @Test
    public void
            call_existingChunkListingWithNoFingerprintMixerSalt_doesntSetSaltBeforeIncBackup()
                    throws Exception {
        mOldChunkListing.fingerprintMixerSalt = new byte[0];
        when(mChunkListingStore.loadProto(TEST_PACKAGE_NAME))
                .thenReturn(Optional.of(mOldChunkListing));

        mTask.call();

        verify(mEncryptedBackupTask)
                .performIncrementalBackup(
                        eq(mTertiaryKey), eq(mWrappedTertiaryKey), eq(mOldChunkListing));
    }

    @Test
    public void call_noExistingChunkListing_storesNewChunkListing() throws Exception {
        when(mChunkListingStore.loadProto(TEST_PACKAGE_NAME)).thenReturn(Optional.empty());
        mTask.call();
        verify(mChunkListingStore).saveProto(TEST_PACKAGE_NAME, mNewChunkListing);
    }

    @Test
    public void call_existingChunkListing_storesNewChunkListing() throws Exception {
        when(mChunkListingStore.loadProto(TEST_PACKAGE_NAME))
                .thenReturn(Optional.of(mOldChunkListing));
        mTask.call();
        verify(mChunkListingStore).saveProto(TEST_PACKAGE_NAME, mNewChunkListing);
    }

    @Test
    public void call_exceptionDuringBackup_doesNotSaveNewChunkListing() throws Exception {
        when(mChunkListingStore.loadProto(TEST_PACKAGE_NAME)).thenReturn(Optional.empty());
        when(mEncryptedBackupTask.performNonIncrementalBackup(any(), any(), any()))
                .thenThrow(GeneralSecurityException.class);

        assertThrows(Exception.class, () -> mTask.call());

        assertThat(mChunkListingStore.loadProto(TEST_PACKAGE_NAME).isPresent()).isFalse();
    }

    @Test
    public void call_incrementalThrowsPermanentException_clearsState() throws Exception {
        when(mChunkListingStore.loadProto(TEST_PACKAGE_NAME))
                .thenReturn(Optional.of(mOldChunkListing));
        when(mEncryptedBackupTask.performIncrementalBackup(any(), any(), any()))
                .thenThrow(IOException.class);

        assertThrows(IOException.class, () -> mTask.call());

        verify(mChunkListingStore).deleteProto(TEST_PACKAGE_NAME);
    }

    @Test
    public void call_closesInputStream() throws Exception {
        mTask.call();
        verify(mInputStream).close();
    }

    @Test
    public void cancel_cancelsTask() throws Exception {
        mTask.cancel();
        verify(mEncryptedBackupTask).cancel();
    }
}
