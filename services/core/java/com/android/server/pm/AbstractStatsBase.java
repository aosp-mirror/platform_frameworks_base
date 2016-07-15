/*
 * Copyright (C) 2016 The Android Open Source Project
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

package com.android.server.pm;

import android.os.Environment;
import android.os.SystemClock;
import android.util.AtomicFile;

import java.io.File;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * A simple base class for statistics that need to be saved/restored from a dedicated file. This
 * class provides a base implementation that:
 * <ul>
 * <li>Provide an AtomicFile to the actual read/write code
 * <li>A background-thread write and a synchronous write
 * <li>Write-limiting for the background-thread (by default writes are at least 30 minutes apart)
 * <li>Can lock on the provided data object before writing
 * </ul>
 * For completion, a subclass needs to implement actual {@link #writeInternal(Object) writing} and
 * {@link #readInternal(Object) reading}.
 */
public abstract class AbstractStatsBase<T> {

    private static final int WRITE_INTERVAL_MS =
            (PackageManagerService.DEBUG_DEXOPT) ? 0 : 30*60*1000;
    private final Object mFileLock = new Object();
    private final AtomicLong mLastTimeWritten = new AtomicLong(0);
    private final AtomicBoolean mBackgroundWriteRunning = new AtomicBoolean(false);
    private final String mFileName;
    private final String mBackgroundThreadName;
    private final boolean mLock;

    protected AbstractStatsBase(String fileName, String threadName, boolean lock) {
        mFileName = fileName;
        mBackgroundThreadName = threadName;
        mLock = lock;
    }

    protected AtomicFile getFile() {
        File dataDir = Environment.getDataDirectory();
        File systemDir = new File(dataDir, "system");
        File fname = new File(systemDir, mFileName);
        return new AtomicFile(fname);
    }

    void writeNow(final T data) {
        writeImpl(data);
        mLastTimeWritten.set(SystemClock.elapsedRealtime());
    }

    boolean maybeWriteAsync(final T data) {
        if (SystemClock.elapsedRealtime() - mLastTimeWritten.get() < WRITE_INTERVAL_MS
            && !PackageManagerService.DEBUG_DEXOPT) {
            return false;
        }

        if (mBackgroundWriteRunning.compareAndSet(false, true)) {
            new Thread(mBackgroundThreadName) {
                @Override
                public void run() {
                    try {
                        writeImpl(data);
                        mLastTimeWritten.set(SystemClock.elapsedRealtime());
                    } finally {
                        mBackgroundWriteRunning.set(false);
                    }
                }
            }.start();
            return true;
        }

        return false;
    }

    private void writeImpl(T data) {
        if (mLock) {
            synchronized (data) {
                synchronized (mFileLock) {
                    writeInternal(data);
                }
            }
        } else {
            synchronized (mFileLock) {
                writeInternal(data);
            }
        }
    }

    protected abstract void writeInternal(T data);

    void read(T data) {
        if (mLock) {
            synchronized (data) {
                synchronized (mFileLock) {
                    readInternal(data);
                }
            }
        } else {
            synchronized (mFileLock) {
                readInternal(data);
            }
        }
        // We use the current time as last-written. read() is called on system server startup
        // (current situation), and we want to postpone I/O at boot.
        mLastTimeWritten.set(SystemClock.elapsedRealtime());
    }

    protected abstract void readInternal(T data);
}
