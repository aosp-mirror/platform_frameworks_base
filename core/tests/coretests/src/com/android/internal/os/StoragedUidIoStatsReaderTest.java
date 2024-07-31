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

package com.android.internal.os;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;

import android.os.FileUtils;
import android.platform.test.annotations.DisabledOnRavenwood;
import android.platform.test.ravenwood.RavenwoodRule;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.SmallTest;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.io.BufferedWriter;
import java.io.File;
import java.nio.file.Files;

/**
 * Test class for {@link StoragedUidIoStatsReader}.
 *
 * To run it:
 * atest FrameworksCoreTests:com.android.internal.os.StoragedUidIoStatsReaderTest
 */
@SmallTest
@RunWith(AndroidJUnit4.class)
public class StoragedUidIoStatsReaderTest {
    @Rule
    public RavenwoodRule mRavenwood = new RavenwoodRule.Builder().build();

    private File mTestDir;
    private File mTestFile;
    // private Random mRand = new Random();

    private StoragedUidIoStatsReader mStoragedUidIoStatsReader;
    @Mock
    private StoragedUidIoStatsReader.Callback mCallback;

    @Before
    public void setUp() throws Exception {
        MockitoAnnotations.initMocks(this);
        mTestDir = Files.createTempDirectory("StoragedUidIoStatsReaderTest").toFile();
        mTestFile = new File(mTestDir, "test.file");
        mStoragedUidIoStatsReader = new StoragedUidIoStatsReader(mTestFile.getAbsolutePath());
    }

    @After
    public void tearDown() throws Exception {
        FileUtils.deleteContents(mTestDir);
    }

    /**
     * Tests that reading will never call the callback.
     */
    @Test
    public void testReadNonexistentFile() throws Exception {
        mStoragedUidIoStatsReader.readAbsolute(mCallback);
        verifyNoMoreInteractions(mCallback);
    }

    /**
     * Tests that reading a file with 3 uids works as expected.
     */
    @Test
    @DisabledOnRavenwood(reason = "b/324433654 -- depends on unsupported classes")
    public void testReadExpected() throws Exception {
        BufferedWriter bufferedWriter = Files.newBufferedWriter(mTestFile.toPath());
        int[] uids = {0, 100, 200};
        long[] fg_chars_read = {1L, 101L, 201L};
        long[] fg_chars_write = {2L, 102L, 202L};
        long[] fg_bytes_read = {3L, 103L, 203L};
        long[] fg_bytes_write = {4L, 104L, 204L};
        long[] bg_chars_read = {5L, 105L, 205L};
        long[] bg_chars_write = {6L, 106L, 206L};
        long[] bg_bytes_read = {7L, 107L, 207L};
        long[] bg_bytes_write = {8L, 108L, 208L};
        long[] fg_fsync = {9L, 109L, 209L};
        long[] bg_fsync = {10L, 110L, 210L};

        for (int i = 0; i < uids.length; i++) {
            bufferedWriter.write(String
                    .format("%d %d %d %d %d %d %d %d %d %d %d\n", uids[i], fg_chars_read[i],
                            fg_chars_write[i], fg_bytes_read[i], fg_bytes_write[i],
                            bg_chars_read[i], bg_chars_write[i], bg_bytes_read[i],
                            bg_bytes_write[i], fg_fsync[i], bg_fsync[i]));
        }
        bufferedWriter.close();

        mStoragedUidIoStatsReader.readAbsolute(mCallback);
        for (int i = 0; i < uids.length; i++) {
            verify(mCallback).onUidStorageStats(uids[i], fg_chars_read[i], fg_chars_write[i],
                    fg_bytes_read[i], fg_bytes_write[i], bg_chars_read[i], bg_chars_write[i],
                    bg_bytes_read[i], bg_bytes_write[i], fg_fsync[i], bg_fsync[i]);
        }
        verifyNoMoreInteractions(mCallback);

    }

    /**
     * Tests that a line with less than 11 items is passed over.
     */
    @Test
    @DisabledOnRavenwood(reason = "b/324433654 -- depends on unsupported classes")
    public void testLineDoesNotElevenEntries() throws Exception {
        BufferedWriter bufferedWriter = Files.newBufferedWriter(mTestFile.toPath());

        // Only has 10 numbers.
        bufferedWriter.write(String
                .format("%d %d %d %d %d %d %d %d %d %d\n", 0, 1, 2, 3, 4, 5, 6, 7, 8, 9));

        bufferedWriter.write(String
                .format("%d %d %d %d %d %d %d %d %d %d %d\n", 10, 11, 12, 13, 14, 15, 16, 17, 18,
                        19, 20));
        bufferedWriter.close();

        // Make sure we get the second line, but the first is skipped.
        mStoragedUidIoStatsReader.readAbsolute(mCallback);
        verify(mCallback).onUidStorageStats(10, 11, 12, 13, 14, 15, 16, 17, 18, 19, 20);
        verifyNoMoreInteractions(mCallback);
    }


    /**
     * Tests that a line that is malformed is passed over.
     */
    @Test
    @DisabledOnRavenwood(reason = "b/324433654 -- depends on unsupported classes")
    public void testLineIsMalformed() throws Exception {
        BufferedWriter bufferedWriter = Files.newBufferedWriter(mTestFile.toPath());

        // Line is not formatted properly. It has a string.
        bufferedWriter.write(String
                .format("%d %d %d %d %d %s %d %d %d %d %d\n", 0, 1, 2, 3, 4, "NotANumber", 5, 6, 7,
                        8, 9));

        bufferedWriter.write(String
                .format("%d %d %d %d %d %d %d %d %d %d %d\n", 10, 11, 12, 13, 14, 15, 16, 17, 18,
                        19, 20));
        bufferedWriter.close();

        // Make sure we get the second line, but the first is skipped.
        mStoragedUidIoStatsReader.readAbsolute(mCallback);
        verify(mCallback).onUidStorageStats(10, 11, 12, 13, 14, 15, 16, 17, 18, 19, 20);
        verifyNoMoreInteractions(mCallback);
    }
}
