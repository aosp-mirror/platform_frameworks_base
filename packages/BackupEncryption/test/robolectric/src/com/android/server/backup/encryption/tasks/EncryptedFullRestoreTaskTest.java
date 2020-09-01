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
import static org.mockito.Mockito.doAnswer;

import static java.util.stream.Collectors.toList;

import com.android.server.backup.encryption.FullRestoreDownloader;

import com.google.common.io.Files;
import com.google.common.primitives.Bytes;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.robolectric.RobolectricTestRunner;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;

@RunWith(RobolectricTestRunner.class)
public class EncryptedFullRestoreTaskTest {
    private static final int TEST_BUFFER_SIZE = 10;
    private static final byte[] TEST_ENCRYPTED_DATA = {1, 2, 3, 4, 5, 6};
    private static final byte[] TEST_DECRYPTED_DATA = fakeDecrypt(TEST_ENCRYPTED_DATA);

    @Rule public TemporaryFolder temporaryFolder = new TemporaryFolder();

    @Mock private BackupFileDecryptorTask mDecryptorTask;

    private File mFolder;
    private FakeFullRestoreDownloader mFullRestorePackageWrapper;
    private EncryptedFullRestoreTask mTask;

    @Before
    public void setUp() throws Exception {
        MockitoAnnotations.initMocks(this);

        mFolder = temporaryFolder.newFolder();
        mFullRestorePackageWrapper = new FakeFullRestoreDownloader(TEST_ENCRYPTED_DATA);

        doAnswer(
            invocation -> {
                File source = invocation.getArgument(0);
                DecryptedChunkOutput target = invocation.getArgument(1);
                byte[] decrypted = fakeDecrypt(Files.toByteArray(source));
                target.open();
                target.processChunk(decrypted, decrypted.length);
                target.close();
                return null;
            })
                .when(mDecryptorTask)
                .decryptFile(any(), any());

        mTask = new EncryptedFullRestoreTask(mFolder, mFullRestorePackageWrapper, mDecryptorTask);
    }

    @Test
    public void readNextChunk_downloadsAndDecryptsBackup() throws Exception {
        ByteArrayOutputStream decryptedOutput = new ByteArrayOutputStream();

        byte[] buffer = new byte[TEST_BUFFER_SIZE];
        int bytesRead = mTask.readNextChunk(buffer);
        while (bytesRead != -1) {
            decryptedOutput.write(buffer, 0, bytesRead);
            bytesRead = mTask.readNextChunk(buffer);
        }

        assertThat(decryptedOutput.toByteArray()).isEqualTo(TEST_DECRYPTED_DATA);
    }

    @Test
    public void finish_deletesTemporaryFiles() throws Exception {
        mTask.readNextChunk(new byte[10]);
        mTask.finish(FullRestoreDownloader.FinishType.UNKNOWN_FINISH);

        assertThat(mFolder.listFiles()).isEmpty();
    }

    /** Fake package wrapper which returns data from a byte array. */
    private static class FakeFullRestoreDownloader extends FullRestoreDownloader {
        private final ByteArrayInputStream mData;

        FakeFullRestoreDownloader(byte[] data) {
            // We override all methods of the superclass, so it does not require any collaborators.
            super();
            mData = new ByteArrayInputStream(data);
        }

        @Override
        public int readNextChunk(byte[] buffer) throws IOException {
            return mData.read(buffer);
        }

        @Override
        public void finish(FinishType finishType) {
            // Nothing to do.
        }
    }

    /** Fake decrypts a byte array by subtracting 1 from each byte. */
    private static byte[] fakeDecrypt(byte[] input) {
        return Bytes.toArray(Bytes.asList(input).stream().map(b -> b + 1).collect(toList()));
    }
}
