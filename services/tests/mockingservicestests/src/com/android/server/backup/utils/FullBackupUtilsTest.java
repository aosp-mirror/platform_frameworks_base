/*
 * Copyright (C) 2017 The Android Open Source Project
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

package com.android.server.backup.utils;

import static com.google.common.truth.Truth.assertThat;

import static org.junit.Assert.fail;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyZeroInteractions;

import android.os.ParcelFileDescriptor;
import android.platform.test.annotations.Presubmit;

import androidx.test.filters.SmallTest;
import androidx.test.runner.AndroidJUnit4;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.util.Random;

@SmallTest
@Presubmit
@RunWith(AndroidJUnit4.class)
public class FullBackupUtilsTest {
    @Mock private ParcelFileDescriptor mParcelFileDescriptorMock;
    @Mock private OutputStream mOutputStreamMock;
    private File mTemporaryFile;
    private ByteArrayOutputStream mByteArrayOutputStream;
    private ParcelFileDescriptor mTemporaryFileDescriptor;

    @Before
    public void setUp() throws Exception {
        MockitoAnnotations.initMocks(this);

        mTemporaryFile = File.createTempFile("backup-data", ".txt");
        mByteArrayOutputStream = new ByteArrayOutputStream();
    }

    @After
    public void tearDown() throws Exception {
        if (mTemporaryFileDescriptor != null) {
            mTemporaryFileDescriptor.close();
        }
        if (mTemporaryFile != null) {
            mTemporaryFile.delete();
        }
    }

    @Test
    public void routeSocketDataToOutput_inPipeIsNull_throwsNPE() throws Exception {
        try {
            FullBackupUtils.routeSocketDataToOutput(null, mOutputStreamMock);
            fail();
        } catch (NullPointerException expected) {
        }
    }

    @Test
    public void routeSocketDataToOutput_outNull_throwsNPE() throws Exception {
        try {
            FullBackupUtils.routeSocketDataToOutput(mParcelFileDescriptorMock, null);
            fail();
        } catch (NullPointerException expected) {
        }
    }

    @Test
    public void routeSocketDataToOutput_emptyInput_throwsEOFException() throws Exception {
        DataOutputStream outputStream = new DataOutputStream(new FileOutputStream(mTemporaryFile));
        outputStream.close();

        mTemporaryFileDescriptor = ParcelFileDescriptor.open(mTemporaryFile,
                ParcelFileDescriptor.MODE_READ_ONLY);

        try {
            FullBackupUtils.routeSocketDataToOutput(mTemporaryFileDescriptor,
                    mOutputStreamMock);
            fail();
        } catch (EOFException expected) {
        }

        verifyZeroInteractions(mOutputStreamMock);
        assertThat(mTemporaryFileDescriptor.getFileDescriptor().valid()).isTrue();
    }

    @Test
    public void routeSocketDataToOutput_incompleteChunkSizeInput_throwsEOFException()
            throws Exception {
        DataOutputStream outputStream = new DataOutputStream(new FileOutputStream(mTemporaryFile));
        outputStream.writeByte(100);
        outputStream.close();

        mTemporaryFileDescriptor = ParcelFileDescriptor.open(mTemporaryFile,
                ParcelFileDescriptor.MODE_READ_ONLY);

        try {
            FullBackupUtils.routeSocketDataToOutput(mTemporaryFileDescriptor,
                    mOutputStreamMock);
            fail();
        } catch (EOFException expected) {
        }

        verifyZeroInteractions(mOutputStreamMock);
        assertThat(mTemporaryFileDescriptor.getFileDescriptor().valid()).isTrue();
    }

    @Test
    public void routeSocketDataToOutput_validEmptyInput_doesNotWriteAnything() throws Exception {
        DataOutputStream outputStream = new DataOutputStream(new FileOutputStream(mTemporaryFile));
        outputStream.writeInt(0);
        outputStream.close();

        mTemporaryFileDescriptor = ParcelFileDescriptor.open(mTemporaryFile,
                ParcelFileDescriptor.MODE_READ_ONLY);

        FullBackupUtils.routeSocketDataToOutput(mTemporaryFileDescriptor, mOutputStreamMock);

        verifyZeroInteractions(mOutputStreamMock);
        assertThat(mTemporaryFileDescriptor.getFileDescriptor().valid()).isTrue();
    }

    @Test
    public void routeSocketDataToOutput_notEnoughData_throwsEOFException() throws Exception {
        byte[] data = createFakeDataArray(100);
        DataOutputStream outputStream = new DataOutputStream(new FileOutputStream(mTemporaryFile));
        outputStream.writeInt(data.length + 1);
        outputStream.write(data);
        outputStream.close();

        mTemporaryFileDescriptor = ParcelFileDescriptor.open(mTemporaryFile,
                ParcelFileDescriptor.MODE_READ_ONLY);

        try {
            FullBackupUtils.routeSocketDataToOutput(mTemporaryFileDescriptor,
                    mByteArrayOutputStream);
            fail();
        } catch (EOFException expected) {
        }

        verify(mOutputStreamMock, never()).close();
        assertThat(mTemporaryFileDescriptor.getFileDescriptor().valid()).isTrue();
    }

    @Test
    public void routeSocketDataToOutput_oneSmallChunk_writesOutputCorrectly() throws Exception {
        byte[] data = createFakeDataArray(100);
        DataOutputStream outputStream = new DataOutputStream(new FileOutputStream(mTemporaryFile));
        outputStream.writeInt(data.length);
        outputStream.write(data);
        outputStream.writeInt(0);
        outputStream.close();

        mTemporaryFileDescriptor = ParcelFileDescriptor.open(mTemporaryFile,
                ParcelFileDescriptor.MODE_READ_ONLY);

        FullBackupUtils.routeSocketDataToOutput(mTemporaryFileDescriptor,
                mByteArrayOutputStream);

        assertThat(mByteArrayOutputStream.toByteArray()).isEqualTo(data);
        verify(mOutputStreamMock, never()).close();
        assertThat(mTemporaryFileDescriptor.getFileDescriptor().valid()).isTrue();
    }

    @Test
    public void routeSocketDataToOutput_oneLargeChunk_writesOutputCorrectly() throws Exception {
        byte[] data = createFakeDataArray(128000);
        DataOutputStream outputStream = new DataOutputStream(new FileOutputStream(mTemporaryFile));
        outputStream.writeInt(data.length);
        outputStream.write(data);
        outputStream.writeInt(0);
        outputStream.close();

        mTemporaryFileDescriptor = ParcelFileDescriptor.open(mTemporaryFile,
                ParcelFileDescriptor.MODE_READ_ONLY);

        FullBackupUtils.routeSocketDataToOutput(mTemporaryFileDescriptor,
                mByteArrayOutputStream);

        assertThat(mByteArrayOutputStream.toByteArray()).isEqualTo(data);
        verify(mOutputStreamMock, never()).close();
        assertThat(mTemporaryFileDescriptor.getFileDescriptor().valid()).isTrue();
    }

    @Test
    public void routeSocketDataToOutput_twoSmallChunks_writesOutputCorrectly() throws Exception {
        byte[] data = createFakeDataArray(200);
        int chunk1Length = 97;
        int chunk2Length = data.length - chunk1Length;

        DataOutputStream outputStream = new DataOutputStream(new FileOutputStream(mTemporaryFile));
        outputStream.writeInt(chunk1Length);
        outputStream.write(data, 0, chunk1Length);
        outputStream.writeInt(chunk2Length);
        outputStream.write(data, chunk1Length, chunk2Length);
        outputStream.writeInt(0);
        outputStream.close();

        mTemporaryFileDescriptor = ParcelFileDescriptor.open(mTemporaryFile,
                ParcelFileDescriptor.MODE_READ_ONLY);

        FullBackupUtils.routeSocketDataToOutput(mTemporaryFileDescriptor,
                mByteArrayOutputStream);

        assertThat(mByteArrayOutputStream.toByteArray()).isEqualTo(data);
        verify(mOutputStreamMock, never()).close();
        assertThat(mTemporaryFileDescriptor.getFileDescriptor().valid()).isTrue();
    }

    @Test
    public void routeSocketDataToOutput_twoLargeChunks_writesOutputCorrectly() throws Exception {
        byte[] data = createFakeDataArray(256000);
        int chunk1Length = 127313;
        int chunk2Length = data.length - chunk1Length;

        DataOutputStream outputStream = new DataOutputStream(new FileOutputStream(mTemporaryFile));
        outputStream.writeInt(chunk1Length);
        outputStream.write(data, 0, chunk1Length);
        outputStream.writeInt(chunk2Length);
        outputStream.write(data, chunk1Length, chunk2Length);
        outputStream.writeInt(0);
        outputStream.close();

        mTemporaryFileDescriptor = ParcelFileDescriptor.open(mTemporaryFile,
                ParcelFileDescriptor.MODE_READ_ONLY);

        FullBackupUtils.routeSocketDataToOutput(mTemporaryFileDescriptor,
                mByteArrayOutputStream);

        assertThat(mByteArrayOutputStream.toByteArray()).isEqualTo(data);
        verify(mOutputStreamMock, never()).close();
        assertThat(mTemporaryFileDescriptor.getFileDescriptor().valid()).isTrue();
    }

    private static byte[] createFakeDataArray(int length) {
        byte[] data = new byte[length];
        new Random(3742).nextBytes(data);
        return data;
    }
}
