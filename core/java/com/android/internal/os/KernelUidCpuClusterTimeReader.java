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

import android.annotation.Nullable;
import android.util.Slog;
import android.util.SparseArray;

import com.android.internal.annotations.VisibleForTesting;

import java.nio.ByteBuffer;
import java.nio.IntBuffer;

/**
 * Reads binary proc file /proc/uid_cpupower/concurrent_policy_time and reports CPU cluster times
 * to BatteryStats to compute cluster power. See
 * {@link PowerProfile#getAveragePowerForCpuCluster(int)}.
 *
 * concurrent_policy_time is an array of u32's in the following format:
 * [n, x0, ..., xn, uid0, time0a, time0b, ..., time0n,
 * uid1, time1a, time1b, ..., time1n,
 * uid2, time2a, time2b, ..., time2n, etc.]
 * where n is the number of policies
 * xi is the number cpus on a particular policy
 * Each uidX is followed by x0 time entries corresponding to the time UID X spent on cluster0
 * running concurrently with 0, 1, 2, ..., x0 - 1 other processes, then followed by x1, ..., xn
 * time entries.
 *
 * The file contains a monotonically increasing count of time for a single boot. This class
 * maintains the previous results of a call to {@link #readDelta} in order to provide a
 * proper delta.
 *
 * This class uses a throttler to reject any {@link #readDelta} call within
 * {@link #mThrottleInterval}. This is different from the throttler in {@link KernelCpuProcReader},
 * which has a shorter throttle interval and returns cached result from last read when the request
 * is throttled.
 *
 * This class is NOT thread-safe and NOT designed to be accessed by more than one caller since each
 * caller has its own view of delta.
 */
public class KernelUidCpuClusterTimeReader extends
        KernelUidCpuTimeReaderBase<KernelUidCpuClusterTimeReader.Callback> {
    private static final String TAG = KernelUidCpuClusterTimeReader.class.getSimpleName();

    private final KernelCpuProcReader mProcReader;
    private SparseArray<double[]> mLastUidPolicyTimeMs = new SparseArray<>();

    private int mNumClusters = -1;
    private int mNumCores;
    private int[] mNumCoresOnCluster;

    private double[] mCurTime; // Reuse to avoid GC.
    private long[] mDeltaTime; // Reuse to avoid GC.
    private long[] mCurTimeRounded; // Reuse to avoid GC.

    public interface Callback extends KernelUidCpuTimeReaderBase.Callback {
        /**
         * Notifies when new data is available.
         *
         * @param uid              uid int
         * @param cpuClusterTimeMs an array of times spent by this uid on corresponding clusters.
         *                         The array index is the cluster index.
         */
        void onUidCpuPolicyTime(int uid, long[] cpuClusterTimeMs);
    }

    public KernelUidCpuClusterTimeReader() {
        mProcReader = KernelCpuProcReader.getClusterTimeReaderInstance();
    }

    @VisibleForTesting
    public KernelUidCpuClusterTimeReader(KernelCpuProcReader procReader) {
        mProcReader = procReader;
    }

    @Override
    protected void readDeltaImpl(@Nullable Callback cb) {
        synchronized (mProcReader) {
            ByteBuffer bytes = mProcReader.readBytes();
            if (bytes == null || bytes.remaining() <= 4) {
                // Error already logged in mProcReader.
                return;
            }
            if ((bytes.remaining() & 3) != 0) {
                Slog.wtf(TAG,
                        "Cannot parse cluster time proc bytes to int: " + bytes.remaining());
                return;
            }
            IntBuffer buf = bytes.asIntBuffer();
            final int numClusters = buf.get();
            if (numClusters <= 0) {
                Slog.wtf(TAG, "Cluster time format error: " + numClusters);
                return;
            }
            if (mNumClusters == -1) {
                mNumClusters = numClusters;
            }
            if (buf.remaining() < numClusters) {
                Slog.wtf(TAG, "Too few data left in the buffer: " + buf.remaining());
                return;
            }
            if (mNumCores <= 0) {
                if (!readCoreInfo(buf, numClusters)) {
                    return;
                }
            } else {
                buf.position(buf.position() + numClusters);
            }

            if (buf.remaining() % (mNumCores + 1) != 0) {
                Slog.wtf(TAG,
                        "Cluster time format error: " + buf.remaining() + " / " + (mNumCores
                                + 1));
                return;
            }
            int numUids = buf.remaining() / (mNumCores + 1);

            for (int i = 0; i < numUids; i++) {
                processUid(buf, cb);
            }
            if (DEBUG) {
                Slog.d(TAG, "Read uids: " + numUids);
            }
        }
    }

    public void readAbsolute(Callback cb) {
        synchronized (mProcReader) {
            readDelta(null);
            int total = mLastUidPolicyTimeMs.size();
            for (int i = 0; i < total; i ++){
                int uid = mLastUidPolicyTimeMs.keyAt(i);
                double[] lastTimes = mLastUidPolicyTimeMs.get(uid);
                for (int j = 0; j < mNumClusters; j++) {
                    mCurTimeRounded[j] = (long) lastTimes[j];
                }
                cb.onUidCpuPolicyTime(uid, mCurTimeRounded);
            }
        }
    }

    private void processUid(IntBuffer buf, @Nullable Callback cb) {
        int uid = buf.get();
        double[] lastTimes = mLastUidPolicyTimeMs.get(uid);
        if (lastTimes == null) {
            lastTimes = new double[mNumClusters];
            mLastUidPolicyTimeMs.put(uid, lastTimes);
        }

        boolean notify = false;
        boolean corrupted = false;

        for (int j = 0; j < mNumClusters; j++) {
            mCurTime[j] = 0;
            for (int k = 1; k <= mNumCoresOnCluster[j]; k++) {
                int time = buf.get();
                if (time < 0) {
                    Slog.e(TAG, "Corrupted data from cluster time proc uid: " + uid);
                    corrupted = true;
                }
                mCurTime[j] += (double) time * 10 / k; // Unit is 10ms.
            }
            mDeltaTime[j] = (long) (mCurTime[j] - lastTimes[j]);
            if (mDeltaTime[j] < 0) {
                Slog.e(TAG, "Unexpected delta from cluster time proc uid: " + uid);
                corrupted = true;
            }
            notify |= mDeltaTime[j] > 0;
        }
        if (notify && !corrupted) {
            System.arraycopy(mCurTime, 0, lastTimes, 0, mNumClusters);
            if (cb != null) {
                cb.onUidCpuPolicyTime(uid, mDeltaTime);
            }
        }
    }

    // Returns if it has read valid info.
    private boolean readCoreInfo(IntBuffer buf, int numClusters) {
        int numCores = 0;
        int[] numCoresOnCluster = new int[numClusters];
        for (int i = 0; i < numClusters; i++) {
            numCoresOnCluster[i] = buf.get();
            numCores += numCoresOnCluster[i];
        }
        if (numCores <= 0) {
            Slog.e(TAG, "Invalid # cores from cluster time proc file: " + numCores);
            return false;
        }
        mNumCores = numCores;
        mNumCoresOnCluster = numCoresOnCluster;
        mCurTime = new double[numClusters];
        mDeltaTime = new long[numClusters];
        mCurTimeRounded = new long[numClusters];
        return true;
    }

    public void removeUid(int uid) {
        mLastUidPolicyTimeMs.delete(uid);
    }

    public void removeUidsInRange(int startUid, int endUid) {
        if (endUid < startUid) {
            Slog.w(TAG, "End UID " + endUid + " is smaller than start UID " + startUid);
            return;
        }
        mLastUidPolicyTimeMs.put(startUid, null);
        mLastUidPolicyTimeMs.put(endUid, null);
        final int firstIndex = mLastUidPolicyTimeMs.indexOfKey(startUid);
        final int lastIndex = mLastUidPolicyTimeMs.indexOfKey(endUid);
        mLastUidPolicyTimeMs.removeAtRange(firstIndex, lastIndex - firstIndex + 1);
    }
}
