/*
 * Copyright (C) 2021 The Android Open Source Project
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

import static org.junit.Assert.assertTrue;

import android.os.FileUtils;
import android.util.IntArray;

import androidx.test.filters.SmallTest;
import androidx.test.runner.AndroidJUnit4;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;

@SmallTest
@RunWith(AndroidJUnit4.class)
public class ProcLocksReaderTest implements
        ProcLocksReader.ProcLocksReaderCallback {
    private File mProcDirectory;

    private ArrayList<int[]> mPids = new ArrayList<>();

    @Before
    public void setUp() throws Exception {
        mProcDirectory = Files.createTempDirectory("ProcLocksReaderTest").toFile();
    }

    @After
    public void tearDown() throws Exception {
        FileUtils.deleteContents(mProcDirectory);
    }

    @Test
    public void testRunSimpleLocks() throws Exception {
        String simpleLocks = "1: POSIX  ADVISORY  READ  18403 fd:09:9070 1073741826 1073742335\n"
                           + "2: POSIX  ADVISORY  WRITE 18292 fd:09:34062 0 EOF\n";
        runHandleBlockingFileLocks(simpleLocks);
        assertTrue(mPids.isEmpty());
    }

    @Test
    public void testRunBlockingLocks() throws Exception {
        String blockedLocks = "1: POSIX  ADVISORY  READ  18403 fd:09:9070 1073741826 1073742335\n"
                            + "2: POSIX  ADVISORY  WRITE 18292 fd:09:34062 0 EOF\n"
                            + "2: -> POSIX  ADVISORY  WRITE 18291 fd:09:34062 0 EOF\n"
                            + "2: -> POSIX  ADVISORY  WRITE 18293 fd:09:34062 0 EOF\n"
                            + "3: POSIX  ADVISORY  READ  3888 fd:09:13992 128 128\n"
                            + "4: POSIX  ADVISORY  READ  3888 fd:09:14230 1073741826 1073742335\n";
        runHandleBlockingFileLocks(blockedLocks);
        assertTrue(Arrays.equals(mPids.remove(0), new int[]{18292, 18291, 18293}));
        assertTrue(mPids.isEmpty());
    }

    @Test
    public void testRunLastBlockingLocks() throws Exception {
        String blockedLocks = "1: POSIX  ADVISORY  READ  18403 fd:09:9070 1073741826 1073742335\n"
                            + "2: POSIX  ADVISORY  WRITE 18292 fd:09:34062 0 EOF\n"
                            + "2: -> POSIX  ADVISORY  WRITE 18291 fd:09:34062 0 EOF\n"
                            + "2: -> POSIX  ADVISORY  WRITE 18293 fd:09:34062 0 EOF\n";
        runHandleBlockingFileLocks(blockedLocks);
        assertTrue(Arrays.equals(mPids.remove(0), new int[]{18292, 18291, 18293}));
        assertTrue(mPids.isEmpty());
    }

    @Test
    public void testRunMultipleBlockingLocks() throws Exception {
        String blockedLocks = "1: POSIX  ADVISORY  READ  18403 fd:09:9070 1073741826 1073742335\n"
                            + "2: POSIX  ADVISORY  WRITE 18292 fd:09:34062 0 EOF\n"
                            + "2: -> POSIX  ADVISORY  WRITE 18291 fd:09:34062 0 EOF\n"
                            + "2: -> POSIX  ADVISORY  WRITE 18293 fd:09:34062 0 EOF\n"
                            + "3: POSIX  ADVISORY  READ  3888 fd:09:13992 128 128\n"
                            + "4: FLOCK  ADVISORY  WRITE 3840 fe:01:5111809 0 EOF\n"
                            + "4: -> FLOCK  ADVISORY  WRITE 3841 fe:01:5111809 0 EOF\n"
                            + "5: FLOCK  ADVISORY  READ  3888 fd:09:14230 0 EOF\n"
                            + "5: -> FLOCK  ADVISORY  READ  3887 fd:09:14230 0 EOF\n";
        runHandleBlockingFileLocks(blockedLocks);
        assertTrue(Arrays.equals(mPids.remove(0), new int[]{18292, 18291, 18293}));
        assertTrue(Arrays.equals(mPids.remove(0), new int[]{3840, 3841}));
        assertTrue(Arrays.equals(mPids.remove(0), new int[]{3888, 3887}));
        assertTrue(mPids.isEmpty());
    }

    private void runHandleBlockingFileLocks(String fileContents) throws Exception {
        File tempFile = File.createTempFile("locks", null, mProcDirectory);
        Files.write(tempFile.toPath(), fileContents.getBytes());
        mPids.clear();
        new ProcLocksReader(tempFile.toString()).handleBlockingFileLocks(this);
        Files.delete(tempFile.toPath());
    }

    /**
     * Call the callback function of handleBlockingFileLocks().
     * @param pids Each process that hold file locks blocking other processes.
     *             pids[0] is the process blocking others
     *             pids[1..n-1] are the processes being blocked
     */
    @Override
    public void onBlockingFileLock(IntArray pids) {
        mPids.add(pids.toArray());
    }
}
