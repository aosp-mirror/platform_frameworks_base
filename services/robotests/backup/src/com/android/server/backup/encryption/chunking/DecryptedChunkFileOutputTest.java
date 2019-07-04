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

package com.android.server.backup.encryption.chunking;

import static com.google.common.truth.Truth.assertThat;

import static org.testng.Assert.assertThrows;

import android.platform.test.annotations.Presubmit;

import com.android.server.backup.encryption.tasks.DecryptedChunkOutput;

import com.google.common.io.Files;
import com.google.common.primitives.Bytes;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.security.MessageDigest;
import java.util.Arrays;

@RunWith(RobolectricTestRunner.class)
@Presubmit
public class DecryptedChunkFileOutputTest {
    private static final byte[] TEST_CHUNK_1 = {1, 2, 3};
    private static final byte[] TEST_CHUNK_2 = {4, 5, 6, 7, 8, 9, 10};
    private static final int TEST_BUFFER_LENGTH =
            Math.max(TEST_CHUNK_1.length, TEST_CHUNK_2.length);

    @Rule
    public TemporaryFolder temporaryFolder = new TemporaryFolder();

    private File mOutputFile;
    private DecryptedChunkFileOutput mDecryptedChunkFileOutput;

    @Before
    public void setUp() throws Exception {
        mOutputFile = temporaryFolder.newFile();
        mDecryptedChunkFileOutput = new DecryptedChunkFileOutput(mOutputFile);
    }

    @Test
    public void open_returnsInstance() throws Exception {
        DecryptedChunkOutput result = mDecryptedChunkFileOutput.open();
        assertThat(result).isEqualTo(mDecryptedChunkFileOutput);
    }

    @Test
    public void open_nonExistentOutputFolder_throwsException() throws Exception {
        mDecryptedChunkFileOutput =
                new DecryptedChunkFileOutput(
                        new File(temporaryFolder.newFolder(), "mOutput/directory"));
        assertThrows(FileNotFoundException.class, () -> mDecryptedChunkFileOutput.open());
    }

    @Test
    public void open_whenRunTwice_throwsException() throws Exception {
        mDecryptedChunkFileOutput.open();
        assertThrows(IllegalStateException.class, () -> mDecryptedChunkFileOutput.open());
    }

    @Test
    public void processChunk_beforeOpen_throwsException() throws Exception {
        assertThrows(IllegalStateException.class,
                () -> mDecryptedChunkFileOutput.processChunk(new byte[0], 0));
    }

    @Test
    public void processChunk_writesChunksToFile() throws Exception {
        processTestChunks();

        assertThat(Files.toByteArray(mOutputFile))
                .isEqualTo(Bytes.concat(TEST_CHUNK_1, TEST_CHUNK_2));
    }

    @Test
    public void getDigest_beforeClose_throws() throws Exception {
        mDecryptedChunkFileOutput.open();
        assertThrows(IllegalStateException.class, () -> mDecryptedChunkFileOutput.getDigest());
    }

    @Test
    public void getDigest_returnsCorrectDigest() throws Exception {
        processTestChunks();

        byte[] actualDigest = mDecryptedChunkFileOutput.getDigest();

        MessageDigest expectedDigest =
                MessageDigest.getInstance(DecryptedChunkFileOutput.DIGEST_ALGORITHM);
        expectedDigest.update(TEST_CHUNK_1);
        expectedDigest.update(TEST_CHUNK_2);
        assertThat(actualDigest).isEqualTo(expectedDigest.digest());
    }

    @Test
    public void getDigest_whenRunTwice_returnsIdenticalDigestBothTimes() throws Exception {
        processTestChunks();

        byte[] digest1 = mDecryptedChunkFileOutput.getDigest();
        byte[] digest2 = mDecryptedChunkFileOutput.getDigest();

        assertThat(digest1).isEqualTo(digest2);
    }

    private void processTestChunks() throws IOException {
        mDecryptedChunkFileOutput.open();
        mDecryptedChunkFileOutput.processChunk(Arrays.copyOf(TEST_CHUNK_1, TEST_BUFFER_LENGTH),
                TEST_CHUNK_1.length);
        mDecryptedChunkFileOutput.processChunk(Arrays.copyOf(TEST_CHUNK_2, TEST_BUFFER_LENGTH),
                TEST_CHUNK_2.length);
        mDecryptedChunkFileOutput.close();
    }
}
