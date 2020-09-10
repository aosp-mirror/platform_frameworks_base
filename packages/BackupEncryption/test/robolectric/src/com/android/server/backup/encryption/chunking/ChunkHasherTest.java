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

package com.android.server.backup.encryption.chunking;

import static com.google.common.truth.Truth.assertThat;

import android.platform.test.annotations.Presubmit;

import com.android.server.backup.encryption.chunk.ChunkHash;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

import javax.crypto.Mac;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;

@RunWith(RobolectricTestRunner.class)
@Presubmit
public class ChunkHasherTest {
    private static final String KEY_ALGORITHM = "AES";
    private static final String MAC_ALGORITHM = "HmacSHA256";

    private static final byte[] TEST_KEY = {100, 120};
    private static final byte[] TEST_DATA = {1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13};

    private SecretKey mSecretKey;
    private ChunkHasher mChunkHasher;

    @Before
    public void setUp() throws Exception {
        mSecretKey = new SecretKeySpec(TEST_KEY, KEY_ALGORITHM);
        mChunkHasher = new ChunkHasher(mSecretKey);
    }

    @Test
    public void computeHash_returnsHmacForData() throws Exception {
        ChunkHash chunkHash = mChunkHasher.computeHash(TEST_DATA);

        byte[] hash = chunkHash.getHash();
        Mac mac = Mac.getInstance(MAC_ALGORITHM);
        mac.init(mSecretKey);
        byte[] expectedHash = mac.doFinal(TEST_DATA);
        assertThat(hash).isEqualTo(expectedHash);
    }

    @Test
    public void computeHash_generates256BitHmac() throws Exception {
        int expectedLength = 256 / Byte.SIZE;

        byte[] hash = mChunkHasher.computeHash(TEST_DATA).getHash();

        assertThat(hash).hasLength(expectedLength);
    }
}
