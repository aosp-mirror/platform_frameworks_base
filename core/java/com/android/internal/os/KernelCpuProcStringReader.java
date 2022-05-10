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

import android.os.StrictMode;
import android.util.Slog;

import java.io.BufferedReader;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.nio.CharBuffer;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * Reads human-readable cpu time proc files.
 *
 * It is implemented as singletons for built-in kernel proc files. Get___Instance() method will
 * return corresponding reader instance. In order to prevent frequent GC, it reuses the same char[]
 * to store data read from proc files.
 *
 * A KernelCpuProcStringReader instance keeps an error counter. When the number of read errors
 * within that instance accumulates to 5, this instance will reject all further read requests.
 *
 * Data fetched within last 500ms is considered fresh, since the reading lifecycle can take up to
 * 100ms. KernelCpuProcStringReader always tries to use cache if it is fresh and valid, but it can
 * be disabled through a parameter.
 *
 * A KernelCpuProcReader instance is thread-safe. It acquires a write lock when reading the proc
 * file, releases it right after, then acquires a read lock before returning a ProcFileIterator.
 * Caller is responsible for closing ProcFileIterator (also auto-closable) after reading, otherwise
 * deadlock will occur.
 */
public class KernelCpuProcStringReader {
    private static final String TAG = KernelCpuProcStringReader.class.getSimpleName();
    private static final int ERROR_THRESHOLD = 5;
    // Data read within the last 500ms is considered fresh.
    private static final long FRESHNESS = 500L;
    private static final int MAX_BUFFER_SIZE = 1024 * 1024;

    private static final String PROC_UID_FREQ_TIME = "/proc/uid_time_in_state";
    private static final String PROC_UID_ACTIVE_TIME = "/proc/uid_concurrent_active_time";
    private static final String PROC_UID_CLUSTER_TIME = "/proc/uid_concurrent_policy_time";
    private static final String PROC_UID_USER_SYS_TIME = "/proc/uid_cputime/show_uid_stat";

    private static final KernelCpuProcStringReader FREQ_TIME_READER =
            new KernelCpuProcStringReader(PROC_UID_FREQ_TIME);
    private static final KernelCpuProcStringReader ACTIVE_TIME_READER =
            new KernelCpuProcStringReader(PROC_UID_ACTIVE_TIME);
    private static final KernelCpuProcStringReader CLUSTER_TIME_READER =
            new KernelCpuProcStringReader(PROC_UID_CLUSTER_TIME);
    private static final KernelCpuProcStringReader USER_SYS_TIME_READER =
            new KernelCpuProcStringReader(PROC_UID_USER_SYS_TIME);

    static KernelCpuProcStringReader getFreqTimeReaderInstance() {
        return FREQ_TIME_READER;
    }

    static KernelCpuProcStringReader getActiveTimeReaderInstance() {
        return ACTIVE_TIME_READER;
    }

    static KernelCpuProcStringReader getClusterTimeReaderInstance() {
        return CLUSTER_TIME_READER;
    }

    static KernelCpuProcStringReader getUserSysTimeReaderInstance() {
        return USER_SYS_TIME_READER;
    }

    private int mErrors = 0;
    private final Path mFile;
    private final Clock mClock;
    private char[] mBuf;
    private int mSize;
    private long mLastReadTime = 0;
    private final ReentrantReadWriteLock mLock = new ReentrantReadWriteLock();
    private final ReentrantReadWriteLock.ReadLock mReadLock = mLock.readLock();
    private final ReentrantReadWriteLock.WriteLock mWriteLock = mLock.writeLock();

    public KernelCpuProcStringReader(String file) {
        this(file, Clock.SYSTEM_CLOCK);
    }

    public KernelCpuProcStringReader(String file, Clock clock) {
        mFile = Paths.get(file);
        mClock = clock;
    }

    /**
     * @see #open(boolean) Default behavior is trying to use cache.
     */
    public ProcFileIterator open() {
        return open(false);
    }

    /**
     * Opens the proc file and buffers all its content, which can be traversed through a
     * ProcFileIterator.
     *
     * This method will tolerate at most 5 errors. After that, it will always return null. This is
     * to save resources and to prevent log spam.
     *
     * This method is thread-safe. It first checks if there are other threads holding read/write
     * lock. If there are, it assumes data is fresh and reuses the data.
     *
     * A read lock is automatically acquired when a valid ProcFileIterator is returned. Caller MUST
     * call {@link ProcFileIterator#close()} when it is done to release the lock.
     *
     * @param ignoreCache If true, ignores the cache and refreshes the data anyway.
     * @return A {@link ProcFileIterator} to iterate through the file content, or null if there is
     * error.
     */
    public ProcFileIterator open(boolean ignoreCache) {
        if (mErrors >= ERROR_THRESHOLD) {
            return null;
        }

        if (ignoreCache) {
            mWriteLock.lock();
        } else {
            mReadLock.lock();
            if (dataValid()) {
                return new ProcFileIterator(mSize);
            }
            mReadLock.unlock();
            mWriteLock.lock();
            if (dataValid()) {
                // Recheck because another thread might have written data just before we did.
                mReadLock.lock();
                mWriteLock.unlock();
                return new ProcFileIterator(mSize);
            }
        }

        // At this point, write lock is held and data is invalid.
        int total = 0;
        int curr;
        mSize = 0;
        final int oldMask = StrictMode.allowThreadDiskReadsMask();
        try (BufferedReader r = Files.newBufferedReader(mFile)) {
            if (mBuf == null) {
                mBuf = new char[1024];
            }
            while ((curr = r.read(mBuf, total, mBuf.length - total)) >= 0) {
                total += curr;
                if (total == mBuf.length) {
                    // Hit the limit. Resize buffer.
                    if (mBuf.length == MAX_BUFFER_SIZE) {
                        mErrors++;
                        Slog.e(TAG, "Proc file too large: " + mFile);
                        return null;
                    }
                    mBuf = Arrays.copyOf(mBuf, Math.min(mBuf.length << 1, MAX_BUFFER_SIZE));
                }
            }
            mSize = total;
            mLastReadTime = mClock.elapsedRealtime();
            // ReentrantReadWriteLock allows lock downgrading.
            mReadLock.lock();
            return new ProcFileIterator(total);
        } catch (FileNotFoundException | NoSuchFileException e) {
            mErrors++;
            Slog.w(TAG, "File not found. It's normal if not implemented: " + mFile);
        } catch (IOException e) {
            mErrors++;
            Slog.e(TAG, "Error reading " + mFile, e);
        } finally {
            StrictMode.setThreadPolicyMask(oldMask);
            mWriteLock.unlock();
        }
        return null;
    }

    private boolean dataValid() {
        return mSize > 0 && (mClock.elapsedRealtime() - mLastReadTime < FRESHNESS);
    }

    /**
     * An autoCloseable iterator to iterate through a string proc file line by line. User must call
     * close() when finish using to prevent deadlock.
     */
    public class ProcFileIterator implements AutoCloseable {
        private final int mSize;
        private int mPos;

        public ProcFileIterator(int size) {
            mSize = size;
        }

        /** @return Whether there are more lines in the iterator. */
        public boolean hasNextLine() {
            return mPos < mSize;
        }

        /**
         * Fetches the next line. Note that all subsequent return values share the same char[]
         * under the hood.
         *
         * @return A {@link java.nio.CharBuffer} containing the next line without the new line
         * symbol.
         */
        public CharBuffer nextLine() {
            if (mPos >= mSize) {
                return null;
            }
            int i = mPos;
            // Move i to the next new line symbol, which is always '\n' in Android.
            while (i < mSize && mBuf[i] != '\n') {
                i++;
            }
            int start = mPos;
            mPos = i + 1;
            return CharBuffer.wrap(mBuf, start, i - start);
        }

        /** Total size of the proc file in chars. */
        public int size() {
            return mSize;
        }

        /** Must call close at the end to release the read lock! Or use try-with-resources. */
        public void close() {
            mReadLock.unlock();
        }


    }

    /**
     * Converts all numbers in the CharBuffer into longs, and puts into the given long[].
     *
     * Space and colon are treated as delimiters. All other chars are not allowed. All numbers
     * are non-negative. To avoid GC, caller should try to use the same array for all calls.
     *
     * This method also resets the given buffer to the original position before return so that
     * it can be read again.
     *
     * @param buf   The char buffer to be converted.
     * @param array An array to store the parsed numbers.
     * @return The number of elements written to the given array. -1 if buf is null, -2 if buf
     * contains invalid char, -3 if any number overflows.
     */
    public static int asLongs(CharBuffer buf, long[] array) {
        if (buf == null) {
            return -1;
        }
        final int initialPos = buf.position();
        int count = 0;
        long num = -1;
        char c;

        while (buf.remaining() > 0 && count < array.length) {
            c = buf.get();
            if (!(isNumber(c) || c == ' ' || c == ':')) {
                buf.position(initialPos);
                return -2;
            }
            if (num < 0) {
                if (isNumber(c)) {
                    num = c - '0';
                }
            } else {
                if (isNumber(c)) {
                    num = num * 10 + c - '0';
                    if (num < 0) {
                        buf.position(initialPos);
                        return -3;
                    }
                } else {
                    array[count++] = num;
                    num = -1;
                }
            }
        }
        if (num >= 0) {
            array[count++] = num;
        }
        buf.position(initialPos);
        return count;
    }

    private static boolean isNumber(char c) {
        return c >= '0' && c <= '9';
    }
}
