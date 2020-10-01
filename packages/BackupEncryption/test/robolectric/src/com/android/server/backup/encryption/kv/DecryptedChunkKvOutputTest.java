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

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.testng.Assert.assertThrows;

import android.os.Debug;
import android.platform.test.annotations.Presubmit;

import com.android.server.backup.encryption.chunk.ChunkHash;
import com.android.server.backup.encryption.chunking.ChunkHasher;
import com.android.server.backup.encryption.protos.nano.KeyValuePairProto;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.robolectric.RobolectricTestRunner;

import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

@RunWith(RobolectricTestRunner.class)
@Presubmit
public class DecryptedChunkKvOutputTest {
    private static final String TEST_KEY_1 = "key_1";
    private static final String TEST_KEY_2 = "key_2";
    private static final byte[] TEST_VALUE_1 = {1, 2, 3};
    private static final byte[] TEST_VALUE_2 = {10, 11, 12, 13};
    private static final byte[] TEST_PAIR_1 = toByteArray(createPair(TEST_KEY_1, TEST_VALUE_1));
    private static final byte[] TEST_PAIR_2 = toByteArray(createPair(TEST_KEY_2, TEST_VALUE_2));
    private static final int TEST_BUFFER_SIZE = Math.max(TEST_PAIR_1.length, TEST_PAIR_2.length);

    @Mock private ChunkHasher mChunkHasher;
    private DecryptedChunkKvOutput mOutput;

    @Before
    public void setUp() throws Exception {
        MockitoAnnotations.initMocks(this);

        when(mChunkHasher.computeHash(any()))
                .thenAnswer(invocation -> fakeHash(invocation.getArgument(0)));
        mOutput = new DecryptedChunkKvOutput(mChunkHasher);
    }

    @Test
    public void open_returnsInstance() throws Exception {
        assertThat(mOutput.open()).isEqualTo(mOutput);
    }

    @Test
    public void processChunk_alreadyClosed_throws() throws Exception {
        mOutput.open();
        mOutput.close();

        assertThrows(
                IllegalStateException.class,
                () -> mOutput.processChunk(TEST_PAIR_1, TEST_PAIR_1.length));
    }

    @Test
    public void getDigest_beforeClose_throws() throws Exception {
        // TODO: b/141356823 We should add a test which calls .open() here
        assertThrows(IllegalStateException.class, () -> mOutput.getDigest());
    }

    @Test
    public void getDigest_returnsDigestOfSortedHashes() throws Exception {
        mOutput.open();
        Debug.waitForDebugger();
        mOutput.processChunk(Arrays.copyOf(TEST_PAIR_1, TEST_BUFFER_SIZE), TEST_PAIR_1.length);
        mOutput.processChunk(Arrays.copyOf(TEST_PAIR_2, TEST_BUFFER_SIZE), TEST_PAIR_2.length);
        mOutput.close();

        byte[] actualDigest = mOutput.getDigest();

        MessageDigest digest = MessageDigest.getInstance(DecryptedChunkKvOutput.DIGEST_ALGORITHM);
        Stream.of(TEST_PAIR_1, TEST_PAIR_2)
                .map(DecryptedChunkKvOutputTest::fakeHash)
                .sorted(Comparator.naturalOrder())
                .forEachOrdered(hash -> digest.update(hash.getHash()));
        assertThat(actualDigest).isEqualTo(digest.digest());
    }

    @Test
    public void getPairs_beforeClose_throws() throws Exception {
        // TODO: b/141356823 We should add a test which calls .open() here
        assertThrows(IllegalStateException.class, () -> mOutput.getPairs());
    }

    @Test
    public void getPairs_returnsPairsSortedByKey() throws Exception {
        mOutput.open();
        // Write out of order to check that it sorts the chunks.
        mOutput.processChunk(Arrays.copyOf(TEST_PAIR_2, TEST_BUFFER_SIZE), TEST_PAIR_2.length);
        mOutput.processChunk(Arrays.copyOf(TEST_PAIR_1, TEST_BUFFER_SIZE), TEST_PAIR_1.length);
        mOutput.close();

        List<KeyValuePairProto.KeyValuePair> pairs = mOutput.getPairs();

        assertThat(
                        isInOrder(
                                pairs,
                                Comparator.comparing(
                                        (KeyValuePairProto.KeyValuePair pair) -> pair.key)))
                .isTrue();
        assertThat(pairs).hasSize(2);
        assertThat(pairs.get(0).key).isEqualTo(TEST_KEY_1);
        assertThat(pairs.get(0).value).isEqualTo(TEST_VALUE_1);
        assertThat(pairs.get(1).key).isEqualTo(TEST_KEY_2);
        assertThat(pairs.get(1).value).isEqualTo(TEST_VALUE_2);
    }

    private static KeyValuePairProto.KeyValuePair createPair(String key, byte[] value) {
        KeyValuePairProto.KeyValuePair pair = new KeyValuePairProto.KeyValuePair();
        pair.key = key;
        pair.value = value;
        return pair;
    }

    private boolean isInOrder(
            List<KeyValuePairProto.KeyValuePair> list,
            Comparator<KeyValuePairProto.KeyValuePair> comparator) {
        if (list.size() < 2) {
            return true;
        }

        List<KeyValuePairProto.KeyValuePair> sortedList = new ArrayList<>(list);
        Collections.sort(sortedList, comparator);
        return list.equals(sortedList);
    }

    private static byte[] toByteArray(KeyValuePairProto.KeyValuePair nano) {
        return KeyValuePairProto.KeyValuePair.toByteArray(nano);
    }

    private static ChunkHash fakeHash(byte[] data) {
        return new ChunkHash(Arrays.copyOf(data, ChunkHash.HASH_LENGTH_BYTES));
    }
}
