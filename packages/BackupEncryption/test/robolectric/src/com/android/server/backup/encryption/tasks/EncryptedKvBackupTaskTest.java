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
import static com.google.common.truth.Truth.assertWithMessage;

import static org.mockito.Mockito.any;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertThrows;

import android.app.Application;
import android.util.Pair;

import androidx.test.core.app.ApplicationProvider;

import com.android.server.backup.encryption.chunk.ChunkHash;
import com.android.server.backup.encryption.chunking.ProtoStore;
import com.android.server.backup.encryption.keys.RecoverableKeyStoreSecondaryKey;
import com.android.server.backup.encryption.keys.TertiaryKeyManager;
import com.android.server.backup.encryption.kv.KeyValueListingBuilder;
import com.android.server.backup.encryption.protos.nano.ChunksMetadataProto;
import com.android.server.backup.encryption.protos.nano.KeyValueListingProto;
import com.android.server.backup.encryption.protos.nano.WrappedKeyProto;
import com.android.server.backup.testing.CryptoTestUtils;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.robolectric.RobolectricTestRunner;

import java.io.IOException;
import java.util.Arrays;
import java.util.Map;
import java.util.Map.Entry;

import javax.crypto.SecretKey;


@RunWith(RobolectricTestRunner.class)
public class EncryptedKvBackupTaskTest {
    private static final boolean INCREMENTAL = true;
    private static final boolean NON_INCREMENTAL = false;

    private static final String TEST_PACKAGE_1 = "com.example.app1";
    private static final String TEST_KEY_1 = "key_1";
    private static final String TEST_KEY_2 = "key_2";
    private static final ChunkHash TEST_HASH_1 =
            new ChunkHash(Arrays.copyOf(new byte[] {1}, ChunkHash.HASH_LENGTH_BYTES));
    private static final ChunkHash TEST_HASH_2 =
            new ChunkHash(Arrays.copyOf(new byte[] {2}, ChunkHash.HASH_LENGTH_BYTES));
    private static final int TEST_LENGTH_1 = 200;
    private static final int TEST_LENGTH_2 = 300;

    @Rule public TemporaryFolder mTemporaryFolder = new TemporaryFolder();

    @Captor private ArgumentCaptor<ChunksMetadataProto.ChunkListing> mChunkListingCaptor;

    @Mock private TertiaryKeyManager mTertiaryKeyManager;
    @Mock private RecoverableKeyStoreSecondaryKey mSecondaryKey;
    @Mock private ProtoStore<KeyValueListingProto.KeyValueListing> mKeyValueListingStore;
    @Mock private ProtoStore<ChunksMetadataProto.ChunkListing> mChunkListingStore;
    @Mock private KvBackupEncrypter mKvBackupEncrypter;
    @Mock private EncryptedBackupTask mEncryptedBackupTask;
    @Mock private SecretKey mTertiaryKey;

    private WrappedKeyProto.WrappedKey mWrappedTertiaryKey;
    private KeyValueListingProto.KeyValueListing mNewKeyValueListing;
    private ChunksMetadataProto.ChunkListing mNewChunkListing;
    private EncryptedKvBackupTask mTask;

    @Before
    public void setUp() throws Exception {
        MockitoAnnotations.initMocks(this);

        Application application = ApplicationProvider.getApplicationContext();
        mKeyValueListingStore = ProtoStore.createKeyValueListingStore(application);
        mChunkListingStore = ProtoStore.createChunkListingStore(application);

        mWrappedTertiaryKey = new WrappedKeyProto.WrappedKey();

        when(mTertiaryKeyManager.wasKeyRotated()).thenReturn(false);
        when(mTertiaryKeyManager.getKey()).thenReturn(mTertiaryKey);
        when(mTertiaryKeyManager.getWrappedKey()).thenReturn(mWrappedTertiaryKey);

        mNewKeyValueListing =
                createKeyValueListing(
                        CryptoTestUtils.mapOf(
                                new Pair<>(TEST_KEY_1, TEST_HASH_1),
                                new Pair<>(TEST_KEY_2, TEST_HASH_2)));
        mNewChunkListing =
                createChunkListing(
                        CryptoTestUtils.mapOf(
                                new Pair<>(TEST_HASH_1, TEST_LENGTH_1),
                                new Pair<>(TEST_HASH_2, TEST_LENGTH_2)));
        when(mKvBackupEncrypter.getNewKeyValueListing()).thenReturn(mNewKeyValueListing);
        when(mEncryptedBackupTask.performIncrementalBackup(
                        eq(mTertiaryKey), eq(mWrappedTertiaryKey), any()))
                .thenReturn(mNewChunkListing);
        when(mEncryptedBackupTask.performNonIncrementalBackup(
                        eq(mTertiaryKey), eq(mWrappedTertiaryKey), any()))
                .thenReturn(mNewChunkListing);

        mTask =
                new EncryptedKvBackupTask(
                        mTertiaryKeyManager,
                        mKeyValueListingStore,
                        mSecondaryKey,
                        mChunkListingStore,
                        mKvBackupEncrypter,
                        mEncryptedBackupTask,
                        TEST_PACKAGE_1);
    }

    @Test
    public void testPerformBackup_rotationRequired_deletesListings() throws Exception {
        mKeyValueListingStore.saveProto(
                TEST_PACKAGE_1,
                createKeyValueListing(CryptoTestUtils.mapOf(new Pair<>(TEST_KEY_1, TEST_HASH_1))));
        mChunkListingStore.saveProto(
                TEST_PACKAGE_1,
                createChunkListing(CryptoTestUtils.mapOf(new Pair<>(TEST_HASH_1, TEST_LENGTH_1))));

        when(mTertiaryKeyManager.wasKeyRotated()).thenReturn(true);
        // Throw an IOException so it aborts before saving the new listings.
        when(mEncryptedBackupTask.performNonIncrementalBackup(any(), any(), any()))
                .thenThrow(IOException.class);

        assertThrows(IOException.class, () -> mTask.performBackup(NON_INCREMENTAL));

        assertFalse(mKeyValueListingStore.loadProto(TEST_PACKAGE_1).isPresent());
        assertFalse(mChunkListingStore.loadProto(TEST_PACKAGE_1).isPresent());
    }

    @Test
    public void testPerformBackup_rotationRequiredButIncremental_throws() throws Exception {
        mKeyValueListingStore.saveProto(
                TEST_PACKAGE_1,
                createKeyValueListing(CryptoTestUtils.mapOf(new Pair<>(TEST_KEY_1, TEST_HASH_1))));
        mChunkListingStore.saveProto(
                TEST_PACKAGE_1,
                createChunkListing(CryptoTestUtils.mapOf(new Pair<>(TEST_HASH_1, TEST_LENGTH_1))));

        when(mTertiaryKeyManager.wasKeyRotated()).thenReturn(true);

        assertThrows(NonIncrementalBackupRequiredException.class,
                () -> mTask.performBackup(INCREMENTAL));
    }

    @Test
    public void testPerformBackup_rotationRequiredAndNonIncremental_performsNonIncrementalBackup()
            throws Exception {
        mKeyValueListingStore.saveProto(
                TEST_PACKAGE_1,
                createKeyValueListing(CryptoTestUtils.mapOf(new Pair<>(TEST_KEY_1, TEST_HASH_1))));
        mChunkListingStore.saveProto(
                TEST_PACKAGE_1,
                createChunkListing(CryptoTestUtils.mapOf(new Pair<>(TEST_HASH_1, TEST_LENGTH_1))));

        when(mTertiaryKeyManager.wasKeyRotated()).thenReturn(true);

        mTask.performBackup(NON_INCREMENTAL);

        verify(mEncryptedBackupTask)
                .performNonIncrementalBackup(eq(mTertiaryKey), eq(mWrappedTertiaryKey), any());
    }

    @Test
    public void testPerformBackup_existingStateButNonIncremental_deletesListings() throws Exception {
        mKeyValueListingStore.saveProto(
                TEST_PACKAGE_1,
                createKeyValueListing(CryptoTestUtils.mapOf(new Pair<>(TEST_KEY_1, TEST_HASH_1))));
        mChunkListingStore.saveProto(
                TEST_PACKAGE_1,
                createChunkListing(CryptoTestUtils.mapOf(new Pair<>(TEST_HASH_1, TEST_LENGTH_1))));

        // Throw an IOException so it aborts before saving the new listings.
        when(mEncryptedBackupTask.performNonIncrementalBackup(any(), any(), any()))
                .thenThrow(IOException.class);

        assertThrows(IOException.class, () -> mTask.performBackup(NON_INCREMENTAL));

        assertFalse(mKeyValueListingStore.loadProto(TEST_PACKAGE_1).isPresent());
        assertFalse(mChunkListingStore.loadProto(TEST_PACKAGE_1).isPresent());
    }

    @Test
    public void testPerformBackup_keyValueListingMissing_deletesChunkListingAndPerformsNonIncremental()
            throws Exception {
        mChunkListingStore.saveProto(
                TEST_PACKAGE_1,
                createChunkListing(CryptoTestUtils.mapOf(new Pair<>(TEST_HASH_1, TEST_LENGTH_1))));

        // Throw an IOException so it aborts before saving the new listings.
        when(mEncryptedBackupTask.performNonIncrementalBackup(any(), any(), any()))
                .thenThrow(IOException.class);

        assertThrows(IOException.class, () -> mTask.performBackup(NON_INCREMENTAL));

        verify(mEncryptedBackupTask).performNonIncrementalBackup(any(), any(), any());
        assertFalse(mKeyValueListingStore.loadProto(TEST_PACKAGE_1).isPresent());
        assertFalse(mChunkListingStore.loadProto(TEST_PACKAGE_1).isPresent());
    }

    @Test
    public void testPerformBackup_chunkListingMissing_deletesKeyValueListingAndPerformsNonIncremental()
            throws Exception {
        mKeyValueListingStore.saveProto(
                TEST_PACKAGE_1,
                createKeyValueListing(CryptoTestUtils.mapOf(new Pair<>(TEST_KEY_1, TEST_HASH_1))));

        // Throw an IOException so it aborts before saving the new listings.
        when(mEncryptedBackupTask.performNonIncrementalBackup(any(), any(), any()))
                .thenThrow(IOException.class);

        assertThrows(IOException.class, () -> mTask.performBackup(NON_INCREMENTAL));

        verify(mEncryptedBackupTask).performNonIncrementalBackup(any(), any(), any());
        assertFalse(mKeyValueListingStore.loadProto(TEST_PACKAGE_1).isPresent());
        assertFalse(mChunkListingStore.loadProto(TEST_PACKAGE_1).isPresent());
    }

    @Test
    public void testPerformBackup_existingStateAndIncremental_performsIncrementalBackup()
            throws Exception {
        mKeyValueListingStore.saveProto(
                TEST_PACKAGE_1,
                createKeyValueListing(CryptoTestUtils.mapOf(new Pair<>(TEST_KEY_1, TEST_HASH_1))));
        ChunksMetadataProto.ChunkListing oldChunkListing =
                createChunkListing(CryptoTestUtils.mapOf(new Pair<>(TEST_HASH_1, TEST_LENGTH_1)));
        mChunkListingStore.saveProto(TEST_PACKAGE_1, oldChunkListing);

        mTask.performBackup(INCREMENTAL);

        verify(mEncryptedBackupTask)
                .performIncrementalBackup(
                        eq(mTertiaryKey), eq(mWrappedTertiaryKey), mChunkListingCaptor.capture());
        assertChunkListingsEqual(mChunkListingCaptor.getValue(), oldChunkListing);
    }

    @Test
    public void testPerformBackup_noExistingStateAndNonIncremental_performsNonIncrementalBackup()
            throws Exception {
        mTask.performBackup(NON_INCREMENTAL);

        verify(mEncryptedBackupTask)
                .performNonIncrementalBackup(eq(mTertiaryKey), eq(mWrappedTertiaryKey), eq(null));
    }

    @Test
    public void testPerformBackup_incremental_savesNewListings() throws Exception {
        mKeyValueListingStore.saveProto(
                TEST_PACKAGE_1,
                createKeyValueListing(CryptoTestUtils.mapOf(new Pair<>(TEST_KEY_1, TEST_HASH_1))));
        mChunkListingStore.saveProto(
                TEST_PACKAGE_1,
                createChunkListing(CryptoTestUtils.mapOf(new Pair<>(TEST_HASH_1, TEST_LENGTH_1))));

        mTask.performBackup(INCREMENTAL);

        KeyValueListingProto.KeyValueListing actualKeyValueListing =
                mKeyValueListingStore.loadProto(TEST_PACKAGE_1).get();
        ChunksMetadataProto.ChunkListing actualChunkListing =
                mChunkListingStore.loadProto(TEST_PACKAGE_1).get();
        assertKeyValueListingsEqual(actualKeyValueListing, mNewKeyValueListing);
        assertChunkListingsEqual(actualChunkListing, mNewChunkListing);
    }

    @Test
    public void testPerformBackup_nonIncremental_savesNewListings() throws Exception {
        mTask.performBackup(NON_INCREMENTAL);

        KeyValueListingProto.KeyValueListing actualKeyValueListing =
                mKeyValueListingStore.loadProto(TEST_PACKAGE_1).get();
        ChunksMetadataProto.ChunkListing actualChunkListing =
                mChunkListingStore.loadProto(TEST_PACKAGE_1).get();
        assertKeyValueListingsEqual(actualKeyValueListing, mNewKeyValueListing);
        assertChunkListingsEqual(actualChunkListing, mNewChunkListing);
    }

    private static KeyValueListingProto.KeyValueListing createKeyValueListing(
            Map<String, ChunkHash> pairs) {
        return new KeyValueListingBuilder().addAll(pairs).build();
    }

    private static ChunksMetadataProto.ChunkListing createChunkListing(
            Map<ChunkHash, Integer> chunks) {
        ChunksMetadataProto.Chunk[] listingChunks = new ChunksMetadataProto.Chunk[chunks.size()];
        int chunksAdded = 0;
        for (Entry<ChunkHash, Integer> entry : chunks.entrySet()) {
            listingChunks[chunksAdded] = CryptoTestUtils.newChunk(entry.getKey(), entry.getValue());
            chunksAdded++;
        }
        return CryptoTestUtils.newChunkListingWithoutDocId(
                /* fingerprintSalt */ new byte[0],
                ChunksMetadataProto.AES_256_GCM,
                ChunksMetadataProto.CHUNK_ORDERING_TYPE_UNSPECIFIED,
                listingChunks);
    }

    private static void assertKeyValueListingsEqual(
            KeyValueListingProto.KeyValueListing actual,
            KeyValueListingProto.KeyValueListing expected) {
        KeyValueListingProto.KeyValueEntry[] actualEntries = actual.entries;
        KeyValueListingProto.KeyValueEntry[] expectedEntries = expected.entries;
        assertThat(actualEntries.length).isEqualTo(expectedEntries.length);
        for (int i = 0; i < actualEntries.length; i++) {
            assertWithMessage("entry " + i)
                    .that(actualEntries[i].key)
                    .isEqualTo(expectedEntries[i].key);
            assertWithMessage("entry " + i)
                    .that(actualEntries[i].hash)
                    .isEqualTo(expectedEntries[i].hash);
        }
    }

    private static void assertChunkListingsEqual(
            ChunksMetadataProto.ChunkListing actual, ChunksMetadataProto.ChunkListing expected) {
        ChunksMetadataProto.Chunk[] actualChunks = actual.chunks;
        ChunksMetadataProto.Chunk[] expectedChunks = expected.chunks;
        assertThat(actualChunks.length).isEqualTo(expectedChunks.length);
        for (int i = 0; i < actualChunks.length; i++) {
            assertWithMessage("chunk " + i)
                    .that(actualChunks[i].hash)
                    .isEqualTo(expectedChunks[i].hash);
            assertWithMessage("chunk " + i)
                    .that(actualChunks[i].length)
                    .isEqualTo(expectedChunks[i].length);
        }
        assertThat(actual.cipherType).isEqualTo(expected.cipherType);
        assertThat(actual.documentId)
                .isEqualTo(expected.documentId == null ? "" : expected.documentId);
    }
}
