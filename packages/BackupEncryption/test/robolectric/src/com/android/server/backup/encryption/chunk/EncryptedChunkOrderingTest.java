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
 * limitations under the License.
 */

package com.android.server.backup.encryption.chunk;

import static com.google.common.truth.Truth.assertThat;

import android.platform.test.annotations.Presubmit;

import com.google.common.primitives.Bytes;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

@RunWith(RobolectricTestRunner.class)
@Presubmit
public class EncryptedChunkOrderingTest {
    private static final byte[] TEST_BYTE_ARRAY_1 = new byte[] {1, 2, 3, 4, 5};
    private static final byte[] TEST_BYTE_ARRAY_2 = new byte[] {5, 4, 3, 2, 1};

    @Test
    public void testEncryptedChunkOrdering_returnsValue() {
        EncryptedChunkOrdering encryptedChunkOrdering =
                EncryptedChunkOrdering.create(TEST_BYTE_ARRAY_1);

        byte[] bytes = encryptedChunkOrdering.encryptedChunkOrdering();

        assertThat(bytes)
                .asList()
                .containsExactlyElementsIn(Bytes.asList(TEST_BYTE_ARRAY_1))
                .inOrder();
    }

    @Test
    public void testEquals() {
        EncryptedChunkOrdering chunkOrdering1 = EncryptedChunkOrdering.create(TEST_BYTE_ARRAY_1);
        EncryptedChunkOrdering equalChunkOrdering1 =
                EncryptedChunkOrdering.create(TEST_BYTE_ARRAY_1);
        EncryptedChunkOrdering chunkOrdering2 = EncryptedChunkOrdering.create(TEST_BYTE_ARRAY_2);

        assertThat(chunkOrdering1).isEqualTo(equalChunkOrdering1);
        assertThat(chunkOrdering1).isNotEqualTo(chunkOrdering2);
    }

    @Test
    public void testHashCode() {
        EncryptedChunkOrdering chunkOrdering1 = EncryptedChunkOrdering.create(TEST_BYTE_ARRAY_1);
        EncryptedChunkOrdering equalChunkOrdering1 =
                EncryptedChunkOrdering.create(TEST_BYTE_ARRAY_1);
        EncryptedChunkOrdering chunkOrdering2 = EncryptedChunkOrdering.create(TEST_BYTE_ARRAY_2);

        int hash1 = chunkOrdering1.hashCode();
        int equalHash1 = equalChunkOrdering1.hashCode();
        int hash2 = chunkOrdering2.hashCode();

        assertThat(hash1).isEqualTo(equalHash1);
        assertThat(hash1).isNotEqualTo(hash2);
    }
}
