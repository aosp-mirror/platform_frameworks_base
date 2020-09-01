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

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.testng.Assert.assertThrows;

import android.platform.test.annotations.Presubmit;

import com.android.server.backup.encryption.FullRestoreDownloader;
import com.android.server.backup.encryption.FullRestoreDownloader.FinishType;

import com.google.common.io.Files;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.robolectric.RobolectricTestRunner;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.util.Random;

@RunWith(RobolectricTestRunner.class)
@Presubmit
public class FullRestoreToFileTaskTest {
    private static final int TEST_RANDOM_SEED = 34;
    private static final int TEST_MAX_CHUNK_SIZE_BYTES = 5;
    private static final int TEST_DATA_LENGTH_BYTES = TEST_MAX_CHUNK_SIZE_BYTES * 20;

    @Rule public TemporaryFolder mTemporaryFolder = new TemporaryFolder();

    private byte[] mTestData;
    private File mTargetFile;
    private FakeFullRestoreDownloader mFakeFullRestoreDownloader;
    @Mock private FullRestoreDownloader mMockFullRestoreDownloader;

    @Before
    public void setUp() throws Exception {
        MockitoAnnotations.initMocks(this);

        mTargetFile = mTemporaryFolder.newFile();

        mTestData = new byte[TEST_DATA_LENGTH_BYTES];
        new Random(TEST_RANDOM_SEED).nextBytes(mTestData);
        mFakeFullRestoreDownloader = new FakeFullRestoreDownloader(mTestData);
    }

    private FullRestoreToFileTask createTaskWithFakeDownloader() {
        return new FullRestoreToFileTask(mFakeFullRestoreDownloader, TEST_MAX_CHUNK_SIZE_BYTES);
    }

    private FullRestoreToFileTask createTaskWithMockDownloader() {
        return new FullRestoreToFileTask(mMockFullRestoreDownloader, TEST_MAX_CHUNK_SIZE_BYTES);
    }

    @Test
    public void restoreToFile_readsDataAndWritesToFile() throws Exception {
        FullRestoreToFileTask task = createTaskWithFakeDownloader();
        task.restoreToFile(mTargetFile);
        assertThat(Files.toByteArray(mTargetFile)).isEqualTo(mTestData);
    }

    @Test
    public void restoreToFile_noErrors_closesDownloaderWithFinished() throws Exception {
        FullRestoreToFileTask task = createTaskWithMockDownloader();
        when(mMockFullRestoreDownloader.readNextChunk(any())).thenReturn(-1);

        task.restoreToFile(mTargetFile);

        verify(mMockFullRestoreDownloader).finish(FinishType.FINISHED);
    }

    @Test
    public void restoreToFile_ioException_closesDownloaderWithTransferFailure() throws Exception {
        FullRestoreToFileTask task = createTaskWithMockDownloader();
        when(mMockFullRestoreDownloader.readNextChunk(any())).thenThrow(IOException.class);

        assertThrows(IOException.class, () -> task.restoreToFile(mTargetFile));

        verify(mMockFullRestoreDownloader).finish(FinishType.TRANSFER_FAILURE);
    }

    /** Fake package wrapper which returns data from a byte array. */
    private static class FakeFullRestoreDownloader extends FullRestoreDownloader {

        private final ByteArrayInputStream mData;

        FakeFullRestoreDownloader(byte[] data) {
            // We override all methods of the superclass, so it does not require any collaborators.
            super();
            this.mData = new ByteArrayInputStream(data);
        }

        @Override
        public int readNextChunk(byte[] buffer) throws IOException {
            return mData.read(buffer);
        }

        @Override
        public void finish(FinishType finishType) {
            // Do nothing.
        }
    }
}
