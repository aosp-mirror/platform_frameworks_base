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

import android.util.IntArray;

import com.android.internal.util.ProcFileReader;

import java.io.FileInputStream;
import java.io.IOException;

/**
 * Reads and parses {@code locks} files in the {@code proc} filesystem.
 * A typical example of /proc/locks
 *
 * 1: POSIX  ADVISORY  READ  18403 fd:09:9070 1073741826 1073742335
 * 2: POSIX  ADVISORY  WRITE 18292 fd:09:34062 0 EOF
 * 2: -> POSIX  ADVISORY  WRITE 18291 fd:09:34062 0 EOF
 * 2: -> POSIX  ADVISORY  WRITE 18293 fd:09:34062 0 EOF
 * 3: POSIX  ADVISORY  READ  3888 fd:09:13992 128 128
 * 4: POSIX  ADVISORY  READ  3888 fd:09:14230 1073741826 1073742335
 */
@android.ravenwood.annotation.RavenwoodKeepWholeClass
public class ProcLocksReader {
    private final String mPath;
    private ProcFileReader mReader = null;
    private IntArray mPids = new IntArray();

    public ProcLocksReader() {
        mPath = "/proc/locks";
    }

    public ProcLocksReader(String path) {
        mPath = path;
    }

    /**
     * This interface is for AMS to run callback function on every processes one by one
     * that hold file locks blocking other processes.
     */
    public interface ProcLocksReaderCallback {
        /**
         * Call the callback function of handleBlockingFileLocks().
         * @param pids Each process that hold file locks blocking other processes.
         *             pids[0] is the process blocking others
         *             pids[1..n-1] are the processes being blocked
         * NOTE: pids are cleared immediately after onBlockingFileLock() returns. If the caller
         * needs to cache it, please make a copy, e.g. by calling pids.toArray().
         */
        void onBlockingFileLock(IntArray pids);
    }

    /**
     * Checks if a process corresponding to a specific pid owns any file locks.
     * @param callback Callback function, accepting pid as the input parameter.
     * @throws IOException if /proc/locks can't be accessed or correctly parsed.
     */
    public void handleBlockingFileLocks(ProcLocksReaderCallback callback) throws IOException {
        long last = -1;
        long id; // ordinal position of the lock in the list
        int pid = -1; // the PID of the process being blocked

        if (mReader == null) {
            mReader = new ProcFileReader(new FileInputStream(mPath));
        } else {
            mReader.rewind();
        }

        mPids.clear();
        while (mReader.hasMoreData()) {
            id = mReader.nextLong(true); // lock id
            if (id == last) {
                // blocked lock found
                mReader.nextIgnored(); // ->
                mReader.nextIgnored(); // lock type: POSIX?
                mReader.nextIgnored(); // lock type: MANDATORY?
                mReader.nextIgnored(); // lock type: RW?

                pid = mReader.nextInt(); // pid
                if (pid > 0) {
                    mPids.add(pid);
                }

                mReader.finishLine();
            } else {
                // process blocking lock and move on to a new lock
                if (mPids.size() > 1) {
                    callback.onBlockingFileLock(mPids);
                    mPids.clear();
                }

                // new lock found
                mReader.nextIgnored(); // lock type: POSIX?
                mReader.nextIgnored(); // lock type: MANDATORY?
                mReader.nextIgnored(); // lock type: RW?

                pid = mReader.nextInt(); // pid
                if (pid > 0) {
                    if (mPids.size() == 0) {
                        mPids.add(pid);
                    } else {
                        mPids.set(0, pid);
                    }
                }
                mReader.finishLine();
                last = id;
            }
        }
        // The last unprocessed blocking lock immediately before EOF
        if (mPids.size() > 1) {
            callback.onBlockingFileLock(mPids);
        }
    }
}
