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

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.testng.Assert.assertThrows;

import android.platform.test.annotations.Presubmit;

import com.google.common.primitives.Bytes;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

import java.io.ByteArrayOutputStream;

@RunWith(RobolectricTestRunner.class)
@Presubmit
public class RawBackupWriterTest {
    private static final byte[] TEST_BYTES = {1, 2, 3, 4, 5, 6};

    private BackupWriter mWriter;
    private ByteArrayOutputStream mOutput;

    @Before
    public void setUp() {
        mOutput = new ByteArrayOutputStream();
        mWriter = new RawBackupWriter(mOutput);
    }

    @Test
    public void writeBytes_writesToOutputStream() throws Exception {
        mWriter.writeBytes(TEST_BYTES);

        assertThat(mOutput.toByteArray())
                .asList()
                .containsExactlyElementsIn(Bytes.asList(TEST_BYTES))
                .inOrder();
    }

    @Test
    public void writeChunk_throwsUnsupportedOperationException() throws Exception {
        assertThrows(UnsupportedOperationException.class, () -> mWriter.writeChunk(0, 0));
    }

    @Test
    public void getBytesWritten_returnsTotalSum() throws Exception {
        mWriter.writeBytes(TEST_BYTES);
        mWriter.writeBytes(TEST_BYTES);

        long bytesWritten = mWriter.getBytesWritten();

        assertThat(bytesWritten).isEqualTo(2 * TEST_BYTES.length);
    }

    @Test
    public void flush_flushesOutputStream() throws Exception {
        mOutput = mock(ByteArrayOutputStream.class);
        mWriter = new RawBackupWriter(mOutput);

        mWriter.flush();

        verify(mOutput).flush();
    }
}
