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

import libcore.io.IoUtils;

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

    public ProcLocksReader() {
        mPath = "/proc/locks";
    }

    public ProcLocksReader(String path) {
        mPath = path;
    }

    /**
     * Checks if a process corresponding to a specific pid owns any file locks.
     * @param pid The process ID for which we want to know the existence of file locks.
     * @return true If the process holds any file locks, false otherwise.
     * @throws IOException if /proc/locks can't be accessed.
     */
    public boolean hasFileLocks(int pid) throws Exception {
        ProcFileReader reader = null;
        long last = -1;
        long id; // ordinal position of the lock in the list
        int owner; // the PID of the process that owns the lock

        try {
            reader = new ProcFileReader(new FileInputStream(mPath));

            while (reader.hasMoreData()) {
                id = reader.nextLong(true); // lock id
                if (id == last) {
                    reader.finishLine(); // blocked lock
                    continue;
                }

                reader.nextIgnored(); // lock type: POSIX?
                reader.nextIgnored(); // lock type: MANDATORY?
                reader.nextIgnored(); // lock type: RW?

                owner = reader.nextInt(); // pid
                if (owner == pid) {
                    return true;
                }
                reader.finishLine();
                last = id;
            }
        } catch (IOException e) {
            // TODO: let ProcFileReader log the failed line
            throw new Exception("Exception parsing /proc/locks");
        } finally {
            IoUtils.closeQuietly(reader);
        }
        return false;
    }
}
