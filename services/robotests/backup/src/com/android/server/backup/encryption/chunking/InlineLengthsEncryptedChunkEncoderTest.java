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
 * limitations under the License
 */

package com.android.server.backup.encryption.chunking;

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;

import android.platform.test.annotations.Presubmit;

import com.android.server.backup.encryption.chunk.ChunkHash;
import com.android.server.backup.encryption.chunk.ChunksMetadataProto;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.robolectric.RobolectricTestRunner;

import java.util.Arrays;

@RunWith(RobolectricTestRunner.class)
@Presubmit
public class InlineLengthsEncryptedChunkEncoderTest {

    private static final byte[] TEST_NONCE =
            Arrays.copyOf(new byte[] {1}, EncryptedChunk.NONCE_LENGTH_BYTES);
    private static final byte[] TEST_KEY_DATA =
            Arrays.copyOf(new byte[] {2}, EncryptedChunk.KEY_LENGTH_BYTES);
    private static final byte[] TEST_DATA = {5, 4, 5, 7, 10, 12, 1, 2, 9};

    @Mock private BackupWriter mMockBackupWriter;
    private ChunkHash mTestKey;
    private EncryptedChunk mTestChunk;
    private EncryptedChunkEncoder mEncoder;

    @Before
    public void setUp() throws Exception {
        mMockBackupWriter = mock(BackupWriter.class);
        mTestKey = new ChunkHash(TEST_KEY_DATA);
        mTestChunk = EncryptedChunk.create(mTestKey, TEST_NONCE, TEST_DATA);
        mEncoder = new InlineLengthsEncryptedChunkEncoder();
    }

    @Test
    public void writeChunkToWriter_writesLengthThenNonceThenData() throws Exception {
        mEncoder.writeChunkToWriter(mMockBackupWriter, mTestChunk);

        InOrder inOrder = inOrder(mMockBackupWriter);
        inOrder.verify(mMockBackupWriter)
                .writeBytes(
                        InlineLengthsEncryptedChunkEncoder.toByteArray(
                                TEST_NONCE.length + TEST_DATA.length));
        inOrder.verify(mMockBackupWriter).writeBytes(TEST_NONCE);
        inOrder.verify(mMockBackupWriter).writeBytes(TEST_DATA);
    }

    @Test
    public void getEncodedLengthOfChunk_returnsSumOfNonceAndDataLengths() {
        int encodedLength = mEncoder.getEncodedLengthOfChunk(mTestChunk);

        assertThat(encodedLength).isEqualTo(Integer.BYTES + TEST_NONCE.length + TEST_DATA.length);
    }

    @Test
    public void getChunkOrderingType_returnsExplicitStartsType() {
        assertThat(mEncoder.getChunkOrderingType()).isEqualTo(ChunksMetadataProto.INLINE_LENGTHS);
    }
}
