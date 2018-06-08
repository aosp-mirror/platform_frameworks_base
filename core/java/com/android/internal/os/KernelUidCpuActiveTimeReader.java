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
import java.util.function.Consumer;

/**
 * Reads binary proc file /proc/uid_cpupower/concurrent_active_time and reports CPU active time to
 * BatteryStats to compute {@link PowerProfile#POWER_CPU_ACTIVE}.
 *
 * concurrent_active_time is an array of u32's in the following format:
 * [n, uid0, time0a, time0b, ..., time0n,
 * uid1, time1a, time1b, ..., time1n,
 * uid2, time2a, time2b, ..., time2n, etc.]
 * where n is the total number of cpus (num_possible_cpus)
 * ...
 * timeXn means the CPU time that a UID X spent running concurrently with n other processes.
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
public class KernelUidCpuActiveTimeReader extends
        KernelUidCpuTimeReaderBase<KernelUidCpuActiveTimeReader.Callback> {
    private static final String TAG = KernelUidCpuActiveTimeReader.class.getSimpleName();

    private final KernelCpuProcReader mProcReader;
    private SparseArray<Double> mLastUidCpuActiveTimeMs = new SparseArray<>();
    private int mCores;

    public interface Callback extends KernelUidCpuTimeReaderBase.Callback {
        /**
         * Notifies when new data is available.
         *
         * @param uid             uid int
         * @param cpuActiveTimeMs cpu active time spent by this uid in milliseconds
         */
        void onUidCpuActiveTime(int uid, long cpuActiveTimeMs);
    }

    public KernelUidCpuActiveTimeReader() {
        mProcReader = KernelCpuProcReader.getActiveTimeReaderInstance();
    }

    @VisibleForTesting
    public KernelUidCpuActiveTimeReader(KernelCpuProcReader procReader) {
        mProcReader = procReader;
    }

    @Override
    protected void readDeltaImpl(@Nullable Callback callback) {
        readImpl((buf) -> {
            int uid = buf.get();
            double activeTime = sumActiveTime(buf);
            if (activeTime > 0) {
                double delta = activeTime - mLastUidCpuActiveTimeMs.get(uid, 0.0);
                if (delta > 0) {
                    mLastUidCpuActiveTimeMs.put(uid, activeTime);
                    if (callback != null) {
                        callback.onUidCpuActiveTime(uid, (long) delta);
                    }
                } else if (delta < 0) {
                    Slog.e(TAG, "Negative delta from active time proc: " + delta);
                }
            }
        });
    }

    public void readAbsolute(Callback callback) {
        readImpl((buf) -> {
            int uid = buf.get();
            double activeTime = sumActiveTime(buf);
            if (activeTime > 0) {
                callback.onUidCpuActiveTime(uid, (long) activeTime);
            }
        });
    }

    private double sumActiveTime(IntBuffer buffer) {
        double sum = 0;
        boolean corrupted = false;
        for (int j = 1; j <= mCores; j++) {
            int time = buffer.get();
            if (time < 0) {
                // Even if error happens, we still need to continue reading.
                // Buffer cannot be skipped.
                Slog.e(TAG, "Negative time from active time proc: " + time);
                corrupted = true;
            } else {
                sum += (double) time * 10 / j; // Unit is 10ms.
            }
        }
        return corrupted ? -1 : sum;
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
            final ByteBuffer bytes = mProcReader.readBytes();
            if (bytes == null || bytes.remaining() <= 4) {
                // Error already logged in mProcReader.
                return;
            }
            if ((bytes.remaining() & 3) != 0) {
                Slog.wtf(TAG,
                        "Cannot parse active time proc bytes to int: " + bytes.remaining());
                return;
            }
            final IntBuffer buf = bytes.asIntBuffer();
            final int cores = buf.get();
            if (mCores != 0 && cores != mCores) {
                Slog.wtf(TAG, "Cpu active time wrong # cores: " + cores);
                return;
            }
            mCores = cores;
            if (cores <= 0 || buf.remaining() % (cores + 1) != 0) {
                Slog.wtf(TAG,
                        "Cpu active time format error: " + buf.remaining() + " / " + (cores
                                + 1));
                return;
            }
            int numUids = buf.remaining() / (cores + 1);
            for (int i = 0; i < numUids; i++) {
                processUid.accept(buf);
            }
            if (DEBUG) {
                Slog.d(TAG, "Read uids: " + numUids);
            }
        }
    }

    public void removeUid(int uid) {
        mLastUidCpuActiveTimeMs.delete(uid);
    }

    public void removeUidsInRange(int startUid, int endUid) {
        mLastUidCpuActiveTimeMs.put(startUid, null);
        mLastUidCpuActiveTimeMs.put(endUid, null);
        final int firstIndex = mLastUidCpuActiveTimeMs.indexOfKey(startUid);
        final int lastIndex = mLastUidCpuActiveTimeMs.indexOfKey(endUid);
        mLastUidCpuActiveTimeMs.removeAtRange(firstIndex, lastIndex - firstIndex + 1);
    }
}
