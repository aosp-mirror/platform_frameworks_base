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

package com.android.server.backup.encryption.kv;

import static com.google.common.truth.Truth.assertThat;

import static org.testng.Assert.assertThrows;

import android.platform.test.annotations.Presubmit;

import com.android.server.backup.encryption.chunk.ChunkHash;
import com.android.server.backup.encryption.protos.nano.KeyValueListingProto;

import com.google.common.collect.ImmutableMap;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

import java.util.Arrays;

@RunWith(RobolectricTestRunner.class)
@Presubmit
public class KeyValueListingBuilderTest {
    private static final String TEST_KEY_1 = "test_key_1";
    private static final String TEST_KEY_2 = "test_key_2";
    private static final ChunkHash TEST_HASH_1 =
            new ChunkHash(Arrays.copyOf(new byte[] {1, 2}, ChunkHash.HASH_LENGTH_BYTES));
    private static final ChunkHash TEST_HASH_2 =
            new ChunkHash(Arrays.copyOf(new byte[] {5, 6}, ChunkHash.HASH_LENGTH_BYTES));

    private KeyValueListingBuilder mBuilder;

    @Before
    public void setUp() {
        mBuilder = new KeyValueListingBuilder();
    }

    @Test
    public void addPair_nullKey_throws() {
        assertThrows(NullPointerException.class, () -> mBuilder.addPair(null, TEST_HASH_1));
    }

    @Test
    public void addPair_emptyKey_throws() {
        assertThrows(IllegalArgumentException.class, () -> mBuilder.addPair("", TEST_HASH_1));
    }

    @Test
    public void addPair_nullHash_throws() {
        assertThrows(NullPointerException.class, () -> mBuilder.addPair(TEST_KEY_1, null));
    }

    @Test
    public void build_noPairs_buildsEmptyListing() {
        KeyValueListingProto.KeyValueListing listing = mBuilder.build();

        assertThat(listing.entries).isEmpty();
    }

    @Test
    public void build_returnsCorrectListing() {
        mBuilder.addPair(TEST_KEY_1, TEST_HASH_1);

        KeyValueListingProto.KeyValueListing listing = mBuilder.build();

        assertThat(listing.entries.length).isEqualTo(1);
        assertThat(listing.entries[0].key).isEqualTo(TEST_KEY_1);
        assertThat(listing.entries[0].hash).isEqualTo(TEST_HASH_1.getHash());
    }

    @Test
    public void addAll_addsAllPairsInMap() {
        ImmutableMap<String, ChunkHash> pairs =
                new ImmutableMap.Builder<String, ChunkHash>()
                        .put(TEST_KEY_1, TEST_HASH_1)
                        .put(TEST_KEY_2, TEST_HASH_2)
                        .build();

        mBuilder.addAll(pairs);
        KeyValueListingProto.KeyValueListing listing = mBuilder.build();

        assertThat(listing.entries.length).isEqualTo(2);
        assertThat(listing.entries[0].key).isEqualTo(TEST_KEY_1);
        assertThat(listing.entries[0].hash).isEqualTo(TEST_HASH_1.getHash());
        assertThat(listing.entries[1].key).isEqualTo(TEST_KEY_2);
        assertThat(listing.entries[1].hash).isEqualTo(TEST_HASH_2.getHash());
    }

    @Test
    public void emptyListing_returnsListingWithoutAnyPairs() {
        KeyValueListingProto.KeyValueListing emptyListing = KeyValueListingBuilder.emptyListing();
        assertThat(emptyListing.entries).isEmpty();
    }
}
