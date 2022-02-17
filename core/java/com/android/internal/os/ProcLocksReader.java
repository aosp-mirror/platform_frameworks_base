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
public class ProcLocksReader {
    private final String mPath;
    private ProcFileReader mReader = null;

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
         * @param pid Each process that hold file locks blocking other processes.
         */
        void onBlockingFileLock(int pid);
    }

    /**
     * Checks if a process corresponding to a specific pid owns any file locks.
     * @param callback Callback function, accepting pid as the input parameter.
     * @throws IOException if /proc/locks can't be accessed or correctly parsed.
     */
    public void handleBlockingFileLocks(ProcLocksReaderCallback callback) throws IOException {
        long last = -1;
        long id; // ordinal position of the lock in the list
        int owner = -1; // the PID of the process that owns the lock
        int pid = -1; // the PID of the process blocking others

        if (mReader == null) {
            mReader = new ProcFileReader(new FileInputStream(mPath));
        } else {
            mReader.rewind();
        }

        while (mReader.hasMoreData()) {
            id = mReader.nextLong(true); // lock id
            if (id == last) {
                mReader.finishLine(); // blocked lock
                if (pid < 0) {
                    pid = owner; // get pid from the previous line
                    callback.onBlockingFileLock(pid);
                }
                continue;
            } else {
                pid = -1; // a new lock
            }

            mReader.nextIgnored(); // lock type: POSIX?
            mReader.nextIgnored(); // lock type: MANDATORY?
            mReader.nextIgnored(); // lock type: RW?

            owner = mReader.nextInt(); // pid
            mReader.finishLine();
            last = id;
        }
    }
}
