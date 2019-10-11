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

import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import android.platform.test.annotations.Presubmit;

import com.google.common.primitives.Bytes;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.robolectric.RobolectricTestRunner;

import java.io.IOException;

/** Tests for {@link DiffScriptBackupWriter}. */
@RunWith(RobolectricTestRunner.class)
@Presubmit
public class DiffScriptBackupWriterTest {
    private static final byte[] TEST_BYTES = {1, 2, 3, 4, 5, 6, 7, 8, 9};

    @Captor private ArgumentCaptor<Byte> mBytesCaptor;
    @Mock private SingleStreamDiffScriptWriter mDiffScriptWriter;
    private BackupWriter mBackupWriter;

    @Before
    public void setUp() {
        mDiffScriptWriter = mock(SingleStreamDiffScriptWriter.class);
        mBackupWriter = new DiffScriptBackupWriter(mDiffScriptWriter);
        mBytesCaptor = ArgumentCaptor.forClass(Byte.class);
    }

    @Test
    public void writeBytes_writesBytesToWriter() throws Exception {
        mBackupWriter.writeBytes(TEST_BYTES);

        verify(mDiffScriptWriter, atLeastOnce()).writeByte(mBytesCaptor.capture());
        assertThat(mBytesCaptor.getAllValues())
                .containsExactlyElementsIn(Bytes.asList(TEST_BYTES))
                .inOrder();
    }

    @Test
    public void writeChunk_writesChunkToWriter() throws Exception {
        mBackupWriter.writeChunk(0, 10);

        verify(mDiffScriptWriter).writeChunk(0, 10);
    }

    @Test
    public void getBytesWritten_returnsTotalSum() throws Exception {
        mBackupWriter.writeBytes(TEST_BYTES);
        mBackupWriter.writeBytes(TEST_BYTES);
        mBackupWriter.writeChunk(/*start=*/ 0, /*length=*/ 10);

        long bytesWritten = mBackupWriter.getBytesWritten();

        assertThat(bytesWritten).isEqualTo(2 * TEST_BYTES.length + 10);
    }

    @Test
    public void flush_flushesWriter() throws IOException {
        mBackupWriter.flush();

        verify(mDiffScriptWriter).flush();
    }
}
