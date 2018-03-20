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

import static com.android.internal.util.Preconditions.checkNotNull;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.os.StrictMode;
import android.util.IntArray;
import android.util.Slog;
import android.util.SparseArray;

import com.android.internal.annotations.VisibleForTesting;

import java.io.BufferedReader;
import java.io.FileReader;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.util.function.Consumer;

/**
 * Reads /proc/uid_time_in_state which has the format:
 *
 * uid: [freq1] [freq2] [freq3] ...
 * [uid1]: [time in freq1] [time in freq2] [time in freq3] ...
 * [uid2]: [time in freq1] [time in freq2] [time in freq3] ...
 * ...
 *
 * Binary variation reads /proc/uid_cpupower/time_in_state in the following format:
 * [n, uid0, time0a, time0b, ..., time0n,
 * uid1, time1a, time1b, ..., time1n,
 * uid2, time2a, time2b, ..., time2n, etc.]
 * where n is the total number of frequencies.
 *
 * This provides the times a UID's processes spent executing at each different cpu frequency.
 * The file contains a monotonically increasing count of time for a single boot. This class
 * maintains the previous results of a call to {@link #readDelta} in order to provide a proper
 * delta.
 *
 * This class uses a throttler to reject any {@link #readDelta} call within
 * {@link #mThrottleInterval}. This is different from the throttler in {@link KernelCpuProcReader},
 * which has a shorter throttle interval and returns cached result from last read when the request
 * is throttled.
 *
 * This class is NOT thread-safe and NOT designed to be accessed by more than one caller since each
 * caller has its own view of delta.
 */
public class KernelUidCpuFreqTimeReader extends
        KernelUidCpuTimeReaderBase<KernelUidCpuFreqTimeReader.Callback> {
    private static final String TAG = KernelUidCpuFreqTimeReader.class.getSimpleName();
    static final String UID_TIMES_PROC_FILE = "/proc/uid_time_in_state";

    public interface Callback extends KernelUidCpuTimeReaderBase.Callback {
        void onUidCpuFreqTime(int uid, long[] cpuFreqTimeMs);
    }

    private long[] mCpuFreqs;
    private long[] mCurTimes; // Reuse to prevent GC.
    private long[] mDeltaTimes; // Reuse to prevent GC.
    private int mCpuFreqsCount;
    private final KernelCpuProcReader mProcReader;

    private SparseArray<long[]> mLastUidCpuFreqTimeMs = new SparseArray<>();

    // We check the existence of proc file a few times (just in case it is not ready yet when we
    // start reading) and if it is not available, we simply ignore further read requests.
    private static final int TOTAL_READ_ERROR_COUNT = 5;
    private int mReadErrorCounter;
    private boolean mPerClusterTimesAvailable;
    private boolean mAllUidTimesAvailable = true;

    public KernelUidCpuFreqTimeReader() {
        mProcReader = KernelCpuProcReader.getFreqTimeReaderInstance();
    }

    @VisibleForTesting
    public KernelUidCpuFreqTimeReader(KernelCpuProcReader procReader) {
        mProcReader = procReader;
    }

    public boolean perClusterTimesAvailable() {
        return mPerClusterTimesAvailable;
    }

    public boolean allUidTimesAvailable() {
        return mAllUidTimesAvailable;
    }

    public SparseArray<long[]> getAllUidCpuFreqTimeMs() {
        return mLastUidCpuFreqTimeMs;
    }

    public long[] readFreqs(@NonNull PowerProfile powerProfile) {
        checkNotNull(powerProfile);
        if (mCpuFreqs != null) {
            // No need to read cpu freqs more than once.
            return mCpuFreqs;
        }
        if (!mAllUidTimesAvailable) {
            return null;
        }
        final int oldMask = StrictMode.allowThreadDiskReadsMask();
        try (BufferedReader reader = new BufferedReader(new FileReader(UID_TIMES_PROC_FILE))) {
            return readFreqs(reader, powerProfile);
        } catch (IOException e) {
            if (++mReadErrorCounter >= TOTAL_READ_ERROR_COUNT) {
                mAllUidTimesAvailable = false;
            }
            Slog.e(TAG, "Failed to read " + UID_TIMES_PROC_FILE + ": " + e);
            return null;
        } finally {
            StrictMode.setThreadPolicyMask(oldMask);
        }
    }

    @VisibleForTesting
    public long[] readFreqs(BufferedReader reader, PowerProfile powerProfile)
            throws IOException {
        final String line = reader.readLine();
        if (line == null) {
            return null;
        }
        final String[] freqStr = line.split(" ");
        // First item would be "uid: " which needs to be ignored.
        mCpuFreqsCount = freqStr.length - 1;
        mCpuFreqs = new long[mCpuFreqsCount];
        mCurTimes = new long[mCpuFreqsCount];
        mDeltaTimes = new long[mCpuFreqsCount];
        for (int i = 0; i < mCpuFreqsCount; ++i) {
            mCpuFreqs[i] = Long.parseLong(freqStr[i + 1], 10);
        }

        // Check if the freqs in the proc file correspond to per-cluster freqs.
        final IntArray numClusterFreqs = extractClusterInfoFromProcFileFreqs();
        final int numClusters = powerProfile.getNumCpuClusters();
        if (numClusterFreqs.size() == numClusters) {
            mPerClusterTimesAvailable = true;
            for (int i = 0; i < numClusters; ++i) {
                if (numClusterFreqs.get(i) != powerProfile.getNumSpeedStepsInCpuCluster(i)) {
                    mPerClusterTimesAvailable = false;
                    break;
                }
            }
        } else {
            mPerClusterTimesAvailable = false;
        }
        Slog.i(TAG, "mPerClusterTimesAvailable=" + mPerClusterTimesAvailable);
        return mCpuFreqs;
    }

    @Override
    @VisibleForTesting
    public void readDeltaImpl(@Nullable Callback callback) {
        if (mCpuFreqs == null) {
            return;
        }
        readImpl((buf) -> {
            int uid = buf.get();
            long[] lastTimes = mLastUidCpuFreqTimeMs.get(uid);
            if (lastTimes == null) {
                lastTimes = new long[mCpuFreqsCount];
                mLastUidCpuFreqTimeMs.put(uid, lastTimes);
            }
            if (!getFreqTimeForUid(buf, mCurTimes)) {
                return;
            }
            boolean notify = false;
            boolean valid = true;
            for (int i = 0; i < mCpuFreqsCount; i++) {
                mDeltaTimes[i] = mCurTimes[i] - lastTimes[i];
                if (mDeltaTimes[i] < 0) {
                    Slog.e(TAG, "Negative delta from freq time proc: " + mDeltaTimes[i]);
                    valid = false;
                }
                notify |= mDeltaTimes[i] > 0;
            }
            if (notify && valid) {
                System.arraycopy(mCurTimes, 0, lastTimes, 0, mCpuFreqsCount);
                if (callback != null) {
                    callback.onUidCpuFreqTime(uid, mDeltaTimes);
                }
            }
        });
    }

    public void readAbsolute(Callback callback) {
        readImpl((buf) -> {
            int uid = buf.get();
            if (getFreqTimeForUid(buf, mCurTimes)) {
                callback.onUidCpuFreqTime(uid, mCurTimes);
            }
        });
    }

    private boolean getFreqTimeForUid(IntBuffer buffer, long[] freqTime) {
        boolean valid = true;
        for (int i = 0; i < mCpuFreqsCount; i++) {
            freqTime[i] = (long) buffer.get() * 10; // Unit is 10ms.
            if (freqTime[i] < 0) {
                Slog.e(TAG, "Negative time from freq time proc: " + freqTime[i]);
                valid = false;
            }
        }
        return valid;
    }

    /**
     * readImpl accepts a callback to process the uid entry. readDeltaImpl needs to store the last
     * seen results while processing the buffer, while readAbsolute returns the absolute value read
     * from the buffer without storing. So readImpl contains the common logic of the two, leaving
     * the difference to a processUid function.
     *
     * @param processUid the callback function to process the uid entry in the buffer.
     */
    private void readImpl(Consumer<IntBuffer> processUid) {
        synchronized (mProcReader) {
            ByteBuffer bytes = mProcReader.readBytes();
            if (bytes == null || bytes.remaining() <= 4) {
                // Error already logged in mProcReader.
                return;
            }
            if ((bytes.remaining() & 3) != 0) {
                Slog.wtf(TAG, "Cannot parse freq time proc bytes to int: " + bytes.remaining());
                return;
            }
            IntBuffer buf = bytes.asIntBuffer();
            final int freqs = buf.get();
            if (freqs != mCpuFreqsCount) {
                Slog.wtf(TAG, "Cpu freqs expect " + mCpuFreqsCount + " , got " + freqs);
                return;
            }
            if (buf.remaining() % (freqs + 1) != 0) {
                Slog.wtf(TAG, "Freq time format error: " + buf.remaining() + " / " + (freqs + 1));
                return;
            }
            int numUids = buf.remaining() / (freqs + 1);
            for (int i = 0; i < numUids; i++) {
                processUid.accept(buf);
            }
            if (DEBUG) {
                Slog.d(TAG, "Read uids: #" + numUids);
            }
        }
    }

    public void removeUid(int uid) {
        mLastUidCpuFreqTimeMs.delete(uid);
    }

    public void removeUidsInRange(int startUid, int endUid) {
        mLastUidCpuFreqTimeMs.put(startUid, null);
        mLastUidCpuFreqTimeMs.put(endUid, null);
        final int firstIndex = mLastUidCpuFreqTimeMs.indexOfKey(startUid);
        final int lastIndex = mLastUidCpuFreqTimeMs.indexOfKey(endUid);
        mLastUidCpuFreqTimeMs.removeAtRange(firstIndex, lastIndex - firstIndex + 1);
    }

    /**
     * Extracts no. of cpu clusters and no. of freqs in each of these clusters from the freqs
     * read from the proc file.
     *
     * We need to assume that freqs in each cluster are strictly increasing.
     * For e.g. if the freqs read from proc file are: 12, 34, 15, 45, 12, 15, 52. Then it means
     * there are 3 clusters: (12, 34), (15, 45), (12, 15, 52)
     *
     * @return an IntArray filled with no. of freqs in each cluster.
     */
    private IntArray extractClusterInfoFromProcFileFreqs() {
        final IntArray numClusterFreqs = new IntArray();
        int freqsFound = 0;
        for (int i = 0; i < mCpuFreqsCount; ++i) {
            freqsFound++;
            if (i + 1 == mCpuFreqsCount || mCpuFreqs[i + 1] <= mCpuFreqs[i]) {
                numClusterFreqs.add(freqsFound);
                freqsFound = 0;
            }
        }
        return numClusterFreqs;
    }
}
