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
import java.util.ArrayList;
import java.util.List;

/**
 * Reads /proc/uid_concurrent_policy_time which has the format:
 * policy0: X policy4: Y (there are X cores on policy0, Y cores on policy4)
 * [uid0]: [time-0-0] [time-0-1] ... [time-1-0] [time-1-1] ...
 * [uid1]: [time-0-0] [time-0-1] ... [time-1-0] [time-1-1] ...
 * ...
 * Time-X-Y means the time a UID spent on clusterX running concurrently with Y other processes.
 * The file contains a monotonically increasing count of time for a single boot. This class
 * maintains the previous results of a call to {@link #readDelta} in order to provide a proper
 * delta.
 */
public class KernelUidCpuClusterTimeReader {

    private static final boolean DEBUG = false;
    private static final String TAG = "KernelUidCpuClusterTimeReader";
    private static final String UID_TIMES_PROC_FILE = "/proc/uid_concurrent_policy_time";

    // mCoreOnCluster[i] is the # of cores on cluster i
    private int[] mCoreOnCluster;
    private int mCores;
    private long mLastTimeReadMs;
    private long mNowTimeMs;
    private SparseArray<long[]> mLastUidPolicyTimeMs = new SparseArray<>();

    public interface Callback {
        /**
         * @param uid
         * @param cpuActiveTimeMs the first dimension is cluster, the second dimension is the # of
         *                        processes running concurrently with this uid.
         */
        void onUidCpuPolicyTime(int uid, long[] cpuActiveTimeMs);
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

    @VisibleForTesting
    public void readDeltaInternal(BufferedReader reader, @Nullable Callback cb) throws IOException {
        String line = reader.readLine();
        if (line == null || !line.startsWith("policy")) {
            Slog.e(TAG, String.format("Malformed proc file: %s ", UID_TIMES_PROC_FILE));
            return;
        }
        if (mCoreOnCluster == null) {
            List<Integer> list = new ArrayList<>();
            String[] policies = line.split(" ");

            if (policies.length == 0 || policies.length % 2 != 0) {
                Slog.e(TAG, String.format("Malformed proc file: %s ", UID_TIMES_PROC_FILE));
                return;
            }

            for (int i = 0; i < policies.length; i+=2) {
                list.add(Integer.parseInt(policies[i+1]));
            }

            mCoreOnCluster = new int[list.size()];
            for(int i=0;i<list.size();i++){
                mCoreOnCluster[i] = list.get(i);
                mCores += mCoreOnCluster[i];
            }
        }
        while ((line = reader.readLine()) != null) {
            final int index = line.indexOf(' ');
            final int uid = Integer.parseInt(line.substring(0, index - 1), 10);
            readTimesForUid(uid, line.substring(index + 1), cb);
        }
    }

    private void readTimesForUid(int uid, String line, @Nullable Callback cb) {
        long[] lastPolicyTime = mLastUidPolicyTimeMs.get(uid);
        if (lastPolicyTime == null) {
            lastPolicyTime = new long[mCores];
            mLastUidPolicyTimeMs.put(uid, lastPolicyTime);
        }
        final String[] timeStr = line.split(" ");
        if (timeStr.length != mCores) {
            Slog.e(TAG, String.format("# readings don't match # cores, readings: %d, # CPU cores: %d",
                    timeStr.length, mCores));
            return;
        }
        final long[] deltaPolicyTime = new long[mCores];
        final long[] currPolicyTime = new long[mCores];
        boolean notify = false;
        for (int i = 0; i < mCores; i++) {
            // Times read will be in units of 10ms
            currPolicyTime[i] = Long.parseLong(timeStr[i], 10) * 10;
            deltaPolicyTime[i] = currPolicyTime[i] - lastPolicyTime[i];
            if (deltaPolicyTime[i] < 0 || currPolicyTime[i] < 0) {
                if (DEBUG) {
                    final StringBuilder sb = new StringBuilder();
                    sb.append(String.format("Malformed cpu policy time for UID=%d\n", uid));
                    sb.append(String.format("data=(%d,%d)\n", lastPolicyTime[i], currPolicyTime[i]));
                    sb.append("times=(");
                    TimeUtils.formatDuration(mLastTimeReadMs, sb);
                    sb.append(",");
                    TimeUtils.formatDuration(mNowTimeMs, sb);
                    sb.append(")");
                    Slog.e(TAG, sb.toString());
                }
                return;
            }
            notify |= deltaPolicyTime[i] > 0;
        }
        if (notify) {
            System.arraycopy(currPolicyTime, 0, lastPolicyTime, 0, mCores);
            if (cb != null) {
                final long[] times = new long[mCoreOnCluster.length];
                int core = 0;
                for (int i = 0; i < mCoreOnCluster.length; i++) {
                    for (int j = 0; j < mCoreOnCluster[i]; j++) {
                        times[i] += deltaPolicyTime[core++] / (j+1);
                    }
                }
                cb.onUidCpuPolicyTime(uid, times);
            }
        }
    }
}
