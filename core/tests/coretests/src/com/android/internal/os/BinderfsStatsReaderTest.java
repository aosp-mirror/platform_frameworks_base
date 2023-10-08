/*
 * Copyright (C) 2023 The Android Open Source Project
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

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

import android.content.Context;
import android.os.FileUtils;
import android.util.IntArray;

import androidx.test.InstrumentationRegistry;
import androidx.test.filters.SmallTest;
import androidx.test.runner.AndroidJUnit4;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.nio.file.Files;

@SmallTest
@RunWith(AndroidJUnit4.class)
public class BinderfsStatsReaderTest {
    private static final String BINDER_LOGS_STATS_HEADER = """
            binder stats:
            BC_TRANSACTION: 695756
            BC_REPLY: 547779
            BC_FREE_BUFFER: 1283223
            BR_FAILED_REPLY: 4
            BR_FROZEN_REPLY: 3
            BR_ONEWAY_SPAM_SUSPECT: 1
            proc: active 313 total 377
            thread: active 3077 total 5227
            """;
    private static final String BINDER_LOGS_STATS_PROC1 = """
            proc 14505
            context binder
              threads: 4
              requested threads: 0+2/15
              ready threads 0
              free async space 520192
              nodes: 9
              refs: 29 s 29 w 29
              buffers: 0
            """;
    private static final String BINDER_LOGS_STATS_PROC2 = """
            proc 14461
            context binder
              threads: 8
              requested threads: 0+2/15
              ready threads 0
              free async space 62
              nodes: 30
              refs: 51 s 51 w 51
              buffers: 0
            """;
    private static final String BINDER_LOGS_STATS_PROC3 = """
            proc 542
            context binder
              threads: 2
              requested threads: 0+0/15
              ready threads 0
              free async space 519896
              nodes: 1
              refs: 2 s 3 w 2
              buffers: 1
            """;
    private static final String BINDER_LOGS_STATS_PROC4 = """
            proc 540
            context binder
              threads: 1
              requested threads: 0+0/0
              ready threads 1
              free async space 44
              nodes: 4
              refs: 1 s 1 w 1
              buffers: 0
            """;
    private File mStatsDirectory;
    private int mFreezerBinderAsyncThreshold;
    private IntArray mValidPids; // The pool of valid pids
    private IntArray mStatsPids; // The pids read from binderfs stats that are also valid
    private IntArray mStatsFree; // The free async space of the above pids
    private boolean mHasError;

    @Before
    public void setUp() {
        Context context = InstrumentationRegistry.getContext();
        mStatsDirectory = context.getDir("binder_logs", Context.MODE_PRIVATE);
        mFreezerBinderAsyncThreshold = 1024;
        mValidPids = IntArray.fromArray(new int[]{14505, 14461, 542, 540}, 4);
        mStatsPids = new IntArray();
        mStatsFree = new IntArray();
        mHasError = false;
    }

    @After
    public void tearDown() throws Exception {
        FileUtils.deleteContents(mStatsDirectory);
    }

    @Test
    public void testNoneProc() throws Exception {
        runHandleBlockingFileLocks(BINDER_LOGS_STATS_HEADER);
        assertFalse(mHasError);
        assertEquals(0, mStatsPids.size());
        assertEquals(0, mStatsFree.size());
    }

    @Test
    public void testOneProc() throws Exception {
        runHandleBlockingFileLocks(BINDER_LOGS_STATS_HEADER + BINDER_LOGS_STATS_PROC1);
        assertFalse(mHasError);
        assertEquals(0, mStatsPids.size());
        assertEquals(0, mStatsFree.size());
    }

    @Test
    public void testTwoProc() throws Exception {
        runHandleBlockingFileLocks(BINDER_LOGS_STATS_HEADER + BINDER_LOGS_STATS_PROC1
                + BINDER_LOGS_STATS_PROC2);
        assertFalse(mHasError);
        assertArrayEquals(mStatsPids.toArray(), new int[]{14461});
        assertArrayEquals(mStatsFree.toArray(), new int[]{62});
    }

    @Test
    public void testThreeProc() throws Exception {
        runHandleBlockingFileLocks(BINDER_LOGS_STATS_HEADER + BINDER_LOGS_STATS_PROC1
                + BINDER_LOGS_STATS_PROC2 + BINDER_LOGS_STATS_PROC3);
        assertFalse(mHasError);
        assertArrayEquals(mStatsPids.toArray(), new int[]{14461});
        assertArrayEquals(mStatsFree.toArray(), new int[]{62});
    }

    @Test
    public void testFourProc() throws Exception {
        runHandleBlockingFileLocks(BINDER_LOGS_STATS_HEADER + BINDER_LOGS_STATS_PROC1
                + BINDER_LOGS_STATS_PROC2 + BINDER_LOGS_STATS_PROC3 + BINDER_LOGS_STATS_PROC4);
        assertFalse(mHasError);
        assertArrayEquals(mStatsPids.toArray(), new int[]{14461, 540});
        assertArrayEquals(mStatsFree.toArray(), new int[]{62, 44});
    }

    @Test
    public void testInvalidProc() throws Exception {
        mValidPids = new IntArray();
        runHandleBlockingFileLocks(BINDER_LOGS_STATS_HEADER + BINDER_LOGS_STATS_PROC1
                + BINDER_LOGS_STATS_PROC2 + BINDER_LOGS_STATS_PROC3 + BINDER_LOGS_STATS_PROC4);
        assertFalse(mHasError);
        assertEquals(0, mStatsPids.size());
        assertEquals(0, mStatsFree.size());
    }

    private void runHandleBlockingFileLocks(String fileContents) throws Exception {
        File tempFile = File.createTempFile("stats", null, mStatsDirectory);
        Files.write(tempFile.toPath(), fileContents.getBytes());
        new BinderfsStatsReader(tempFile.toString()).handleFreeAsyncSpace(
                // Check if the current process is a valid one
                pid -> mValidPids.indexOf(pid) != -1,

                // Check if the current process is running out of async binder space
                (pid, free) -> {
                    if (free < mFreezerBinderAsyncThreshold) {
                        mStatsPids.add(pid);
                        mStatsFree.add(free);
                    }
                },

                // Log the error if binderfs stats can't be accesses or correctly parsed
                exception -> mHasError = true);
        Files.delete(tempFile.toPath());
    }
}
