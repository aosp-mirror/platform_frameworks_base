/*
 * Copyright (C) 2017 The Android Open Source Project
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

import static com.android.internal.annotations.VisibleForTesting.Visibility.PACKAGE;

import android.annotation.NonNull;
import android.util.Slog;
import android.util.SparseArray;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.annotations.VisibleForTesting;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Arrays;

@VisibleForTesting(visibility = PACKAGE)
public class KernelSingleUidTimeReader {
    private final String TAG = KernelUidCpuFreqTimeReader.class.getName();
    private final boolean DBG = false;

    private final String PROC_FILE_DIR = "/proc/uid/";
    private final String PROC_FILE_NAME = "/time_in_state";

    @VisibleForTesting
    public static final int TOTAL_READ_ERROR_COUNT = 5;

    @GuardedBy("this")
    private final int mCpuFreqsCount;

    @GuardedBy("this")
    private SparseArray<long[]> mLastUidCpuTimeMs = new SparseArray<>();

    @GuardedBy("this")
    private int mReadErrorCounter;
    @GuardedBy("this")
    private boolean mSingleUidCpuTimesAvailable = true;
    @GuardedBy("this")
    private boolean mHasStaleData;

    private final Injector mInjector;

    KernelSingleUidTimeReader(int cpuFreqsCount) {
        this(cpuFreqsCount, new Injector());
    }

    public KernelSingleUidTimeReader(int cpuFreqsCount, Injector injector) {
        mInjector = injector;
        mCpuFreqsCount = cpuFreqsCount;
        if (mCpuFreqsCount == 0) {
            mSingleUidCpuTimesAvailable = false;
        }
    }

    public boolean singleUidCpuTimesAvailable() {
        return mSingleUidCpuTimesAvailable;
    }

    public long[] readDeltaMs(int uid) {
        synchronized (this) {
            if (!mSingleUidCpuTimesAvailable) {
                return null;
            }
            // Read total cpu times from the proc file.
            final String procFile = new StringBuilder(PROC_FILE_DIR)
                    .append(uid)
                    .append(PROC_FILE_NAME).toString();
            final long[] cpuTimesMs = new long[mCpuFreqsCount];
            try {
                final byte[] data = mInjector.readData(procFile);
                final ByteBuffer buffer = ByteBuffer.wrap(data);
                buffer.order(ByteOrder.nativeOrder());
                for (int i = 0; i < mCpuFreqsCount; ++i) {
                    // Times read will be in units of 10ms
                    cpuTimesMs[i] = buffer.getLong() * 10;
                }
            } catch (Exception e) {
                if (++mReadErrorCounter >= TOTAL_READ_ERROR_COUNT) {
                    mSingleUidCpuTimesAvailable = false;
                }
                if (DBG) Slog.e(TAG, "Some error occured while reading " + procFile, e);
                return null;
            }

            return computeDelta(uid, cpuTimesMs);
        }
    }

    /**
     * Compute and return cpu times delta of an uid using previously read cpu times and
     * {@param latestCpuTimesMs}.
     *
     * @return delta of cpu times if at least one of the cpu time at a freq is +ve, otherwise null.
     */
    public long[] computeDelta(int uid, @NonNull long[] latestCpuTimesMs) {
        synchronized (this) {
            if (!mSingleUidCpuTimesAvailable) {
                return null;
            }
            // Subtract the last read cpu times to get deltas.
            final long[] lastCpuTimesMs = mLastUidCpuTimeMs.get(uid);
            final long[] deltaTimesMs = getDeltaLocked(lastCpuTimesMs, latestCpuTimesMs);
            if (deltaTimesMs == null) {
                if (DBG) Slog.e(TAG, "Malformed data read for uid=" + uid
                        + "; last=" + Arrays.toString(lastCpuTimesMs)
                        + "; latest=" + Arrays.toString(latestCpuTimesMs));
                return null;
            }
            // If all elements are zero, return null to avoid unnecessary work on the caller side.
            boolean hasNonZero = false;
            for (int i = deltaTimesMs.length - 1; i >= 0; --i) {
                if (deltaTimesMs[i] > 0) {
                    hasNonZero = true;
                    break;
                }
            }
            if (hasNonZero) {
                mLastUidCpuTimeMs.put(uid, latestCpuTimesMs);
                return deltaTimesMs;
            } else {
                return null;
            }
        }
    }

    /**
     * Returns null if the latest cpu times are not valid**, otherwise delta of
     * {@param latestCpuTimesMs} and {@param lastCpuTimesMs}.
     *
     * **latest cpu times are considered valid if all the cpu times are +ve and
     * greater than or equal to previously read cpu times.
     */
    @GuardedBy("this")
    @VisibleForTesting(visibility = PACKAGE)
    public long[] getDeltaLocked(long[] lastCpuTimesMs, @NonNull long[] latestCpuTimesMs) {
        for (int i = latestCpuTimesMs.length - 1; i >= 0; --i) {
            if (latestCpuTimesMs[i] < 0) {
                return null;
            }
        }
        if (lastCpuTimesMs == null) {
            return latestCpuTimesMs;
        }
        final long[] deltaTimesMs = new long[latestCpuTimesMs.length];
        for (int i = latestCpuTimesMs.length - 1; i >= 0; --i) {
            deltaTimesMs[i] = latestCpuTimesMs[i] - lastCpuTimesMs[i];
            if (deltaTimesMs[i] < 0) {
                return null;
            }
        }
        return deltaTimesMs;
    }

    public void markDataAsStale(boolean hasStaleData) {
        synchronized (this) {
            mHasStaleData = hasStaleData;
        }
    }

    public boolean hasStaleData() {
        synchronized (this) {
            return mHasStaleData;
        }
    }

    public void setAllUidsCpuTimesMs(SparseArray<long[]> allUidsCpuTimesMs) {
        synchronized (this) {
            mLastUidCpuTimeMs.clear();
            for (int i = allUidsCpuTimesMs.size() - 1; i >= 0; --i) {
                final long[] cpuTimesMs = allUidsCpuTimesMs.valueAt(i);
                if (cpuTimesMs != null) {
                    mLastUidCpuTimeMs.put(allUidsCpuTimesMs.keyAt(i), cpuTimesMs.clone());
                }
            }
        }
    }

    public void removeUid(int uid) {
        synchronized (this) {
            mLastUidCpuTimeMs.delete(uid);
        }
    }

    public void removeUidsInRange(int startUid, int endUid) {
        if (endUid < startUid) {
            return;
        }
        synchronized (this) {
            mLastUidCpuTimeMs.put(startUid, null);
            mLastUidCpuTimeMs.put(endUid, null);
            final int startIdx = mLastUidCpuTimeMs.indexOfKey(startUid);
            final int endIdx = mLastUidCpuTimeMs.indexOfKey(endUid);
            mLastUidCpuTimeMs.removeAtRange(startIdx, endIdx - startIdx + 1);
        }
    }

    @VisibleForTesting
    public static class Injector {
        public byte[] readData(String procFile) throws IOException {
            return Files.readAllBytes(Paths.get(procFile));
        }
    }

    @VisibleForTesting
    public SparseArray<long[]> getLastUidCpuTimeMs() {
        return mLastUidCpuTimeMs;
    }

    @VisibleForTesting
    public void setSingleUidCpuTimesAvailable(boolean singleUidCpuTimesAvailable) {
        mSingleUidCpuTimesAvailable = singleUidCpuTimesAvailable;
    }
}