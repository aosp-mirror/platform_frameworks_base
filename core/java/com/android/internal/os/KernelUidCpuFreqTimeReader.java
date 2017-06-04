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

import java.io.BufferedReader;
import java.io.FileReader;
import java.io.IOException;

/**
 * Reads /proc/uid_time_in_state which has the format:
 *
 * uid: [freq1] [freq2] [freq3] ...
 * [uid1]: [time in freq1] [time in freq2] [time in freq3] ...
 * [uid2]: [time in freq1] [time in freq2] [time in freq3] ...
 * ...
 *
 * This provides the times a UID's processes spent executing at each different cpu frequency.
 * The file contains a monotonically increasing count of time for a single boot. This class
 * maintains the previous results of a call to {@link #readDelta} in order to provide a proper
 * delta.
 */
public class KernelUidCpuFreqTimeReader {
    private static final String TAG = "KernelUidCpuFreqTimeReader";
    private static final String UID_TIMES_PROC_FILE = "/proc/uid_time_in_state";

    public interface Callback {
        void onCpuFreqs(long[] cpuFreqs);
        void onUidCpuFreqTime(int uid, long[] cpuFreqTimeMs);
    }

    private long[] mCpuFreqs;
    private int mCpuFreqsCount;

    private SparseArray<long[]> mLastUidCpuFreqTimeMs = new SparseArray<>();

    // We check the existence of proc file a few times (just in case it is not ready yet when we
    // start reading) and if it is not available, we simply ignore further read requests.
    private static final int TOTAL_READ_ERROR_COUNT = 5;
    private int mReadErrorCounter;
    private boolean mProcFileAvailable;

    public void readDelta(@Nullable Callback callback) {
        if (!mProcFileAvailable && mReadErrorCounter >= TOTAL_READ_ERROR_COUNT) {
            return;
        }
        try (BufferedReader reader = new BufferedReader(new FileReader(UID_TIMES_PROC_FILE))) {
            readDelta(reader, callback);
            mProcFileAvailable = true;
        } catch (IOException e) {
            mReadErrorCounter++;
            Slog.e(TAG, "Failed to read " + UID_TIMES_PROC_FILE + ": " + e);
        }
    }

    @VisibleForTesting
    public void readDelta(BufferedReader reader, @Nullable Callback callback) throws IOException {
        String line = reader.readLine();
        if (line == null) {
            return;
        }
        readCpuFreqs(line, callback);
        while ((line = reader.readLine()) != null) {
            final int index = line.indexOf(' ');
            final int uid = Integer.parseInt(line.substring(0, index - 1), 10);
            readTimesForUid(uid, line.substring(index + 1, line.length()), callback);
        }
    }

    private void readTimesForUid(int uid, String line, Callback callback) {
        long[] uidTimeMs = mLastUidCpuFreqTimeMs.get(uid);
        if (uidTimeMs == null) {
            uidTimeMs = new long[mCpuFreqsCount];
            mLastUidCpuFreqTimeMs.put(uid, uidTimeMs);
        }
        final String[] timesStr = line.split(" ");
        final int size = timesStr.length;
        if (size != uidTimeMs.length) {
            Slog.e(TAG, "No. of readings don't match cpu freqs, readings: " + size
                    + " cpuFreqsCount: " + uidTimeMs.length);
            return;
        }
        final long[] deltaUidTimeMs = new long[size];
        for (int i = 0; i < size; ++i) {
            // Times read will be in units of 10ms
            final long totalTimeMs = Long.parseLong(timesStr[i], 10) * 10;
            deltaUidTimeMs[i] = totalTimeMs - uidTimeMs[i];
            uidTimeMs[i] = totalTimeMs;
        }
        if (callback != null) {
            callback.onUidCpuFreqTime(uid, deltaUidTimeMs);
        }
    }

    private void readCpuFreqs(String line, Callback callback) {
        if (mCpuFreqs == null) {
            final String[] freqStr = line.split(" ");
            // First item would be "uid:" which needs to be ignored
            mCpuFreqsCount = freqStr.length - 1;
            mCpuFreqs = new long[mCpuFreqsCount];
            for (int i = 0; i < mCpuFreqsCount; ++i) {
                mCpuFreqs[i] = Long.parseLong(freqStr[i + 1], 10);
            }
        }
        if (callback != null) {
            callback.onCpuFreqs(mCpuFreqs);
        }
    }
}
