/*
 * Copyright (C) 2020 The Android Open Source Project
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

import android.os.SystemClock;
import android.util.Slog;
import android.util.SparseArray;

import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * Reads cpu time bpf maps.
 *
 * It is implemented as singletons for each separate set of per-UID times. Get___Instance() method
 * returns the corresponding reader instance. In order to prevent frequent GC, it reuses the same
 * SparseArray to store data read from BPF maps.
 *
 * A KernelCpuUidBpfMapReader instance keeps an error counter. When the number of read errors within
 * that instance accumulates to 5, this instance will reject all further read requests.
 *
 * Data fetched within last 500ms is considered fresh, since the reading lifecycle can take up to
 * 25ms. KernelCpuUidBpfMapReader always tries to use cache if it is fresh and valid, but it can
 * be disabled through a parameter.
 *
 * A KernelCpuUidBpfMapReader instance is thread-safe. It acquires a write lock when reading the bpf
 * map, releases it right after, then acquires a read lock before returning a BpfMapIterator. Caller
 * is responsible for closing BpfMapIterator (also auto-closable) after reading, otherwise deadlock
 * will occur.
 */
public abstract class KernelCpuUidBpfMapReader {
    private static final int ERROR_THRESHOLD = 5;
    private static final long FRESHNESS_MS = 500L;

    private static final KernelCpuUidBpfMapReader FREQ_TIME_READER =
        new KernelCpuUidFreqTimeBpfMapReader();

    private static final KernelCpuUidBpfMapReader ACTIVE_TIME_READER =
        new KernelCpuUidActiveTimeBpfMapReader();

    private static final KernelCpuUidBpfMapReader CLUSTER_TIME_READER =
        new KernelCpuUidClusterTimeBpfMapReader();

    static KernelCpuUidBpfMapReader getFreqTimeReaderInstance() {
        return FREQ_TIME_READER;
    }

    static KernelCpuUidBpfMapReader getActiveTimeReaderInstance() {
        return ACTIVE_TIME_READER;
    }

    static KernelCpuUidBpfMapReader getClusterTimeReaderInstance() {
        return CLUSTER_TIME_READER;
    }

    final String mTag = this.getClass().getSimpleName();
    private int mErrors = 0;
    protected SparseArray<long[]> mData = new SparseArray<>();
    private long mLastReadTime = 0;
    protected final ReentrantReadWriteLock mLock = new ReentrantReadWriteLock();
    protected final ReentrantReadWriteLock.ReadLock mReadLock = mLock.readLock();
    protected final ReentrantReadWriteLock.WriteLock mWriteLock = mLock.writeLock();

    public boolean startTrackingBpfTimes() {
        return KernelCpuBpfTracking.startTracking();
    }

    protected abstract boolean readBpfData();

    /**
     * Returns an array of metadata used to inform the caller of 1) the size of array required by
     * getNextUid and 2) how to interpret the raw data copied to that array.
     */
    public abstract long[] getDataDimensions();

    public void removeUidsInRange(int startUid, int endUid) {
        if (mErrors > ERROR_THRESHOLD) {
            return;
        }
        if (endUid < startUid || startUid < 0) {
            return;
        }

        mWriteLock.lock();
        int firstIndex = mData.indexOfKey(startUid);
        if (firstIndex < 0) {
            mData.put(startUid, null);
            firstIndex = mData.indexOfKey(startUid);
        }
        int lastIndex = mData.indexOfKey(endUid);
        if (lastIndex < 0) {
            mData.put(endUid, null);
            lastIndex = mData.indexOfKey(endUid);
        }
        mData.removeAtRange(firstIndex, lastIndex - firstIndex + 1);
        mWriteLock.unlock();
    }

    public BpfMapIterator open() {
        return open(false);
    }

    public BpfMapIterator open(boolean ignoreCache) {
        if (mErrors > ERROR_THRESHOLD) {
            return null;
        }
        if (!startTrackingBpfTimes()) {
            Slog.w(mTag, "Failed to start tracking");
            mErrors++;
            return null;
        }
        if (ignoreCache) {
            mWriteLock.lock();
        } else {
            mReadLock.lock();
            if (dataValid()) {
                return new BpfMapIterator();
            }
            mReadLock.unlock();
            mWriteLock.lock();
            if (dataValid()) {
                mReadLock.lock();
                mWriteLock.unlock();
                return new BpfMapIterator();
            }
        }
        if (readBpfData()) {
            mLastReadTime = SystemClock.elapsedRealtime();
            mReadLock.lock();
            mWriteLock.unlock();
            return new BpfMapIterator();
        }

        mWriteLock.unlock();
        mErrors++;
        Slog.w(mTag, "Failed to read bpf times");
        return null;
    }

    private boolean dataValid() {
        return mData.size() > 0 && (SystemClock.elapsedRealtime() - mLastReadTime < FRESHNESS_MS);
    }

    public class BpfMapIterator implements AutoCloseable {
        private int mPos;

        public BpfMapIterator() {
        };

        public boolean getNextUid(long[] buf) {
            if (mPos >= mData.size()) {
                return false;
            }
            buf[0] = mData.keyAt(mPos);
            System.arraycopy(mData.valueAt(mPos), 0, buf, 1, mData.valueAt(mPos).length);
            mPos++;
            return true;
        }

        public void close() {
            mReadLock.unlock();
        }
    }

    public static class KernelCpuUidFreqTimeBpfMapReader extends KernelCpuUidBpfMapReader {

        private final native boolean removeUidRange(int startUid, int endUid);

        @Override
        protected final native boolean readBpfData();

        @Override
        public final long[] getDataDimensions() {
            return KernelCpuBpfTracking.getFreqsInternal();
        }

        @Override
        public void removeUidsInRange(int startUid, int endUid) {
            mWriteLock.lock();
            super.removeUidsInRange(startUid, endUid);
            removeUidRange(startUid, endUid);
            mWriteLock.unlock();
        }
    }

    public static class KernelCpuUidActiveTimeBpfMapReader extends KernelCpuUidBpfMapReader {

        @Override
        protected final native boolean readBpfData();

        @Override
        public final native long[] getDataDimensions();
    }

    public static class KernelCpuUidClusterTimeBpfMapReader extends KernelCpuUidBpfMapReader {

        @Override
        protected final native boolean readBpfData();

        @Override
        public final native long[] getDataDimensions();
    }
}
