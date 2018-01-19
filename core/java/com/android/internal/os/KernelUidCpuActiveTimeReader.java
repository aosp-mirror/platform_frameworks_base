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
import android.os.StrictMode;
import android.os.SystemClock;
import android.util.Slog;
import android.util.SparseArray;
import android.util.TimeUtils;

import com.android.internal.annotations.VisibleForTesting;

import java.io.BufferedReader;
import java.io.FileReader;
import java.io.IOException;

/**
 * Reads /proc/uid_concurrent_active_time which has the format:
 * active: X (X is # cores)
 * [uid0]: [time-0] [time-1] [time-2] ... (# entries = # cores)
 * [uid1]: [time-0] [time-1] [time-2] ... ...
 * ...
 * Time-N means the CPU time a UID spent running concurrently with N other processes.
 * The file contains a monotonically increasing count of time for a single boot. This class
 * maintains the previous results of a call to {@link #readDelta} in order to provide a
 * proper delta.
 */
public class KernelUidCpuActiveTimeReader {
    private static final boolean DEBUG = false;
    private static final String TAG = "KernelUidCpuActiveTimeReader";
    private static final String UID_TIMES_PROC_FILE = "/proc/uid_concurrent_active_time";

    private int mCoreCount;
    private long mLastTimeReadMs;
    private long mNowTimeMs;
    private SparseArray<long[]> mLastUidCpuActiveTimeMs = new SparseArray<>();

    public interface Callback {
        void onUidCpuActiveTime(int uid, long cpuActiveTimeMs);
    }

    public void readDelta(@Nullable Callback cb) {
        final int oldMask = StrictMode.allowThreadDiskReadsMask();
        try (BufferedReader reader = new BufferedReader(new FileReader(UID_TIMES_PROC_FILE))) {
            mNowTimeMs = SystemClock.elapsedRealtime();
            readDeltaInternal(reader, cb);
            mLastTimeReadMs = mNowTimeMs;
        } catch (IOException e) {
            Slog.e(TAG, "Failed to read " + UID_TIMES_PROC_FILE + ": " + e);
        } finally {
            StrictMode.setThreadPolicyMask(oldMask);
        }
    }

    public void removeUid(int uid) {
        mLastUidCpuActiveTimeMs.delete(uid);
    }

    public void removeUidsInRange(int startUid, int endUid) {
        if (endUid < startUid) {
            Slog.w(TAG, "End UID " + endUid + " is smaller than start UID " + startUid);
            return;
        }
        mLastUidCpuActiveTimeMs.put(startUid, null);
        mLastUidCpuActiveTimeMs.put(endUid, null);
        final int firstIndex = mLastUidCpuActiveTimeMs.indexOfKey(startUid);
        final int lastIndex = mLastUidCpuActiveTimeMs.indexOfKey(endUid);
        mLastUidCpuActiveTimeMs.removeAtRange(firstIndex, lastIndex - firstIndex + 1);
    }

    @VisibleForTesting
    public void readDeltaInternal(BufferedReader reader, @Nullable Callback cb) throws IOException {
        String line = reader.readLine();
        if (line == null || !line.startsWith("active:")) {
            Slog.e(TAG, String.format("Malformed proc file: %s ", UID_TIMES_PROC_FILE));
            return;
        }
        if (mCoreCount == 0) {
            mCoreCount = Integer.parseInt(line.substring(line.indexOf(' ')+1));
        }
        while ((line = reader.readLine()) != null) {
            final int index = line.indexOf(' ');
            final int uid = Integer.parseInt(line.substring(0, index - 1), 10);
            readTimesForUid(uid, line.substring(index + 1), cb);
        }
    }

    private void readTimesForUid(int uid, String line, @Nullable Callback cb) {
        long[] lastActiveTime = mLastUidCpuActiveTimeMs.get(uid);
        if (lastActiveTime == null) {
            lastActiveTime = new long[mCoreCount];
            mLastUidCpuActiveTimeMs.put(uid, lastActiveTime);
        }
        final String[] timesStr = line.split(" ");
        if (timesStr.length != mCoreCount) {
            Slog.e(TAG, String.format("# readings don't match # cores, readings: %d, CPU cores: %d",
                    timesStr.length, mCoreCount));
            return;
        }
        long sumDeltas = 0;
        final long[] curActiveTime = new long[mCoreCount];
        boolean notify = false;
        for (int i = 0; i < mCoreCount; i++) {
            // Times read will be in units of 10ms
            curActiveTime[i] = Long.parseLong(timesStr[i], 10) * 10;
            long delta = curActiveTime[i] - lastActiveTime[i];
            if (delta < 0 || curActiveTime[i] < 0) {
                if (DEBUG) {
                    final StringBuilder sb = new StringBuilder();
                    sb.append(String.format("Malformed cpu active time for UID=%d\n", uid));
                    sb.append(String.format("data=(%d,%d)\n", lastActiveTime[i], curActiveTime[i]));
                    sb.append("times=(");
                    TimeUtils.formatDuration(mLastTimeReadMs, sb);
                    sb.append(",");
                    TimeUtils.formatDuration(mNowTimeMs, sb);
                    sb.append(")");
                    Slog.e(TAG, sb.toString());
                }
                return;
            }
            notify |= delta > 0;
            sumDeltas += delta / (i + 1);
        }
        if (notify) {
            System.arraycopy(curActiveTime, 0, lastActiveTime, 0, mCoreCount);
            if (cb != null) {
                cb.onUidCpuActiveTime(uid, sumDeltas);
            }
        }
    }
}
