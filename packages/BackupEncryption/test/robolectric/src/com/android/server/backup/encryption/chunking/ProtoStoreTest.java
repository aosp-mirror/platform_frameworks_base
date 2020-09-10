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

import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.Truth.assertWithMessage;

import static org.testng.Assert.assertThrows;

import android.content.Context;
import android.platform.test.annotations.Presubmit;

import androidx.test.core.app.ApplicationProvider;

import com.android.server.backup.encryption.chunk.ChunkHash;
import com.android.server.backup.encryption.protos.nano.ChunksMetadataProto;
import com.android.server.backup.encryption.protos.nano.KeyValueListingProto;

import com.google.common.collect.ImmutableMap;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map.Entry;
import java.util.Optional;

@RunWith(RobolectricTestRunner.class)
@Presubmit
public class ProtoStoreTest {
    private static final String TEST_KEY_1 = "test_key_1";
    private static final ChunkHash TEST_HASH_1 =
            new ChunkHash(Arrays.copyOf(new byte[] {1}, EncryptedChunk.KEY_LENGTH_BYTES));
    private static final ChunkHash TEST_HASH_2 =
            new ChunkHash(Arrays.copyOf(new byte[] {2}, EncryptedChunk.KEY_LENGTH_BYTES));
    private static final int TEST_LENGTH_1 = 10;
    private static final int TEST_LENGTH_2 = 18;

    private static final String TEST_PACKAGE_1 = "com.example.test1";
    private static final String TEST_PACKAGE_2 = "com.example.test2";

    @Rule public TemporaryFolder mTemporaryFolder = new TemporaryFolder();

    private File mStoreFolder;
    private ProtoStore<ChunksMetadataProto.ChunkListing> mProtoStore;

    @Before
    public void setUp() throws Exception {
        mStoreFolder = mTemporaryFolder.newFolder();
        mProtoStore = new ProtoStore<>(ChunksMetadataProto.ChunkListing.class, mStoreFolder);
    }

    @Test
    public void differentStoreTypes_operateSimultaneouslyWithoutInterfering() throws Exception {
        ChunksMetadataProto.ChunkListing chunkListing =
                createChunkListing(ImmutableMap.of(TEST_HASH_1, TEST_LENGTH_1));
        KeyValueListingProto.KeyValueListing keyValueListing =
                new KeyValueListingProto.KeyValueListing();
        keyValueListing.entries = new KeyValueListingProto.KeyValueEntry[1];
        keyValueListing.entries[0] = new KeyValueListingProto.KeyValueEntry();
        keyValueListing.entries[0].key = TEST_KEY_1;
        keyValueListing.entries[0].hash = TEST_HASH_1.getHash();

        Context application = ApplicationProvider.getApplicationContext();
        ProtoStore<ChunksMetadataProto.ChunkListing> chunkListingStore =
                ProtoStore.createChunkListingStore(application);
        ProtoStore<KeyValueListingProto.KeyValueListing> keyValueListingStore =
                ProtoStore.createKeyValueListingStore(application);

        chunkListingStore.saveProto(TEST_PACKAGE_1, chunkListing);
        keyValueListingStore.saveProto(TEST_PACKAGE_1, keyValueListing);

        ChunksMetadataProto.ChunkListing actualChunkListing =
                chunkListingStore.loadProto(TEST_PACKAGE_1).get();
        KeyValueListingProto.KeyValueListing actualKeyValueListing =
                keyValueListingStore.loadProto(TEST_PACKAGE_1).get();
        assertListingsEqual(actualChunkListing, chunkListing);
        assertThat(actualKeyValueListing.entries.length).isEqualTo(1);
        assertThat(actualKeyValueListing.entries[0].key).isEqualTo(TEST_KEY_1);
        assertThat(actualKeyValueListing.entries[0].hash).isEqualTo(TEST_HASH_1.getHash());
    }

    @Test
    public void construct_storeLocationIsFile_throws() throws Exception {
        assertThrows(
                IOException.class,
                () ->
                        new ProtoStore<>(
                                ChunksMetadataProto.ChunkListing.class,
                                mTemporaryFolder.newFile()));
    }

    @Test
    public void loadChunkListing_noListingExists_returnsEmptyListing() throws Exception {
        Optional<ChunksMetadataProto.ChunkListing> chunkListing =
                mProtoStore.loadProto(TEST_PACKAGE_1);
        assertThat(chunkListing.isPresent()).isFalse();
    }

    @Test
    public void loadChunkListing_listingExists_returnsExistingListing() throws Exception {
        ChunksMetadataProto.ChunkListing expected =
                createChunkListing(
                        ImmutableMap.of(TEST_HASH_1, TEST_LENGTH_1, TEST_HASH_2, TEST_LENGTH_2));
        mProtoStore.saveProto(TEST_PACKAGE_1, expected);

        ChunksMetadataProto.ChunkListing result = mProtoStore.loadProto(TEST_PACKAGE_1).get();

        assertListingsEqual(result, expected);
    }

    @Test
    public void loadProto_emptyPackageName_throwsException() throws Exception {
        assertThrows(IllegalArgumentException.class, () -> mProtoStore.loadProto(""));
    }

    @Test
    public void loadProto_nullPackageName_throwsException() throws Exception {
        assertThrows(IllegalArgumentException.class, () -> mProtoStore.loadProto(null));
    }

    @Test
    public void loadProto_packageNameContainsSlash_throwsException() throws Exception {
        assertThrows(
                IllegalArgumentException.class, () -> mProtoStore.loadProto(TEST_PACKAGE_1 + "/"));
    }

    @Test
    public void saveProto_persistsToNewInstance() throws Exception {
        ChunksMetadataProto.ChunkListing expected =
                createChunkListing(
                        ImmutableMap.of(TEST_HASH_1, TEST_LENGTH_1, TEST_HASH_2, TEST_LENGTH_2));
        mProtoStore.saveProto(TEST_PACKAGE_1, expected);
        mProtoStore = new ProtoStore<>(ChunksMetadataProto.ChunkListing.class, mStoreFolder);

        ChunksMetadataProto.ChunkListing result = mProtoStore.loadProto(TEST_PACKAGE_1).get();

        assertListingsEqual(result, expected);
    }

    @Test
    public void saveProto_emptyPackageName_throwsException() throws Exception {
        assertThrows(
                IllegalArgumentException.class,
                () -> mProtoStore.saveProto("", new ChunksMetadataProto.ChunkListing()));
    }

    @Test
    public void saveProto_nullPackageName_throwsException() throws Exception {
        assertThrows(
                IllegalArgumentException.class,
                () -> mProtoStore.saveProto(null, new ChunksMetadataProto.ChunkListing()));
    }

    @Test
    public void saveProto_packageNameContainsSlash_throwsException() throws Exception {
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        mProtoStore.saveProto(
                                TEST_PACKAGE_1 + "/", new ChunksMetadataProto.ChunkListing()));
    }

    @Test
    public void saveProto_nullListing_throwsException() throws Exception {
        assertThrows(NullPointerException.class, () -> mProtoStore.saveProto(TEST_PACKAGE_1, null));
    }

    @Test
    public void deleteProto_noListingExists_doesNothing() throws Exception {
        ChunksMetadataProto.ChunkListing listing =
                createChunkListing(ImmutableMap.of(TEST_HASH_1, TEST_LENGTH_1));
        mProtoStore.saveProto(TEST_PACKAGE_1, listing);

        mProtoStore.deleteProto(TEST_PACKAGE_2);

        assertThat(mProtoStore.loadProto(TEST_PACKAGE_1).get().chunks.length).isEqualTo(1);
    }

    @Test
    public void deleteProto_listingExists_deletesListing() throws Exception {
        ChunksMetadataProto.ChunkListing listing =
                createChunkListing(ImmutableMap.of(TEST_HASH_1, TEST_LENGTH_1));
        mProtoStore.saveProto(TEST_PACKAGE_1, listing);

        mProtoStore.deleteProto(TEST_PACKAGE_1);

        assertThat(mProtoStore.loadProto(TEST_PACKAGE_1).isPresent()).isFalse();
    }

    @Test
    public void deleteAllProtos_deletesAllProtos() throws Exception {
        ChunksMetadataProto.ChunkListing listing1 =
                createChunkListing(ImmutableMap.of(TEST_HASH_1, TEST_LENGTH_1));
        ChunksMetadataProto.ChunkListing listing2 =
                createChunkListing(ImmutableMap.of(TEST_HASH_2, TEST_LENGTH_2));
        mProtoStore.saveProto(TEST_PACKAGE_1, listing1);
        mProtoStore.saveProto(TEST_PACKAGE_2, listing2);

        mProtoStore.deleteAllProtos();

        assertThat(mProtoStore.loadProto(TEST_PACKAGE_1).isPresent()).isFalse();
        assertThat(mProtoStore.loadProto(TEST_PACKAGE_2).isPresent()).isFalse();
    }

    @Test
    public void deleteAllProtos_folderDeleted_doesNotCrash() throws Exception {
        mStoreFolder.delete();

        mProtoStore.deleteAllProtos();
    }

    private static ChunksMetadataProto.ChunkListing createChunkListing(
            ImmutableMap<ChunkHash, Integer> chunks) {
        ChunksMetadataProto.ChunkListing listing = new ChunksMetadataProto.ChunkListing();
        listing.cipherType = ChunksMetadataProto.AES_256_GCM;
        listing.chunkOrderingType = ChunksMetadataProto.CHUNK_ORDERING_TYPE_UNSPECIFIED;

        List<ChunksMetadataProto.Chunk> chunkProtos = new ArrayList<>();
        for (Entry<ChunkHash, Integer> entry : chunks.entrySet()) {
            ChunksMetadataProto.Chunk chunk = new ChunksMetadataProto.Chunk();
            chunk.hash = entry.getKey().getHash();
            chunk.length = entry.getValue();
            chunkProtos.add(chunk);
        }
        listing.chunks = chunkProtos.toArray(new ChunksMetadataProto.Chunk[0]);
        return listing;
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
}
