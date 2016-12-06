/*
 * Copyright (C) 2015 The Android Open Source Project
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
import android.os.SystemClock;
import android.text.TextUtils;
import android.util.Slog;
import android.util.SparseLongArray;
import android.util.TimeUtils;

import java.io.BufferedReader;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;

/**
 * Reads /proc/uid_cputime/show_uid_stat which has the line format:
 *
 * uid: user_time_micro_seconds system_time_micro_seconds power_in_milli-amp-micro_seconds
 *
 * This provides the time a UID's processes spent executing in user-space and kernel-space.
 * The file contains a monotonically increasing count of time for a single boot. This class
 * maintains the previous results of a call to {@link #readDelta} in order to provide a proper
 * delta.
 */
public class KernelUidCpuTimeReader {
    private static final String TAG = "KernelUidCpuTimeReader";
    private static final String sProcFile = "/proc/uid_cputime/show_uid_stat";
    private static final String sRemoveUidProcFile = "/proc/uid_cputime/remove_uid_range";

    /**
     * Callback interface for processing each line of the proc file.
     */
    public interface Callback {
        /**
         * @param uid UID of the app
         * @param userTimeUs time spent executing in user space in microseconds
         * @param systemTimeUs time spent executing in kernel space in microseconds
         * @param powerMaUs power consumed executing, in milli-ampere microseconds
         */
        void onUidCpuTime(int uid, long userTimeUs, long systemTimeUs, long powerMaUs);
    }

    private SparseLongArray mLastUserTimeUs = new SparseLongArray();
    private SparseLongArray mLastSystemTimeUs = new SparseLongArray();
    private SparseLongArray mLastPowerMaUs = new SparseLongArray();
    private long mLastTimeReadUs = 0;

    /**
     * Reads the proc file, calling into the callback with a delta of time for each UID.
     * @param callback The callback to invoke for each line of the proc file. If null,
     *                 the data is consumed and subsequent calls to readDelta will provide
     *                 a fresh delta.
     */
    public void readDelta(@Nullable Callback callback) {
        long nowUs = SystemClock.elapsedRealtime() * 1000;
        try (BufferedReader reader = new BufferedReader(new FileReader(sProcFile))) {
            TextUtils.SimpleStringSplitter splitter = new TextUtils.SimpleStringSplitter(' ');
            String line;
            while ((line = reader.readLine()) != null) {
                splitter.setString(line);
                final String uidStr = splitter.next();
                final int uid = Integer.parseInt(uidStr.substring(0, uidStr.length() - 1), 10);
                final long userTimeUs = Long.parseLong(splitter.next(), 10);
                final long systemTimeUs = Long.parseLong(splitter.next(), 10);
                final long powerMaUs;
                if (splitter.hasNext()) {
                    powerMaUs = Long.parseLong(splitter.next(), 10) / 1000;
                } else {
                    powerMaUs = 0;
                }

                // Only report if there is a callback and if this is not the first read.
                if (callback != null && mLastTimeReadUs != 0) {
                    long userTimeDeltaUs = userTimeUs;
                    long systemTimeDeltaUs = systemTimeUs;
                    long powerDeltaMaUs = powerMaUs;
                    int index = mLastUserTimeUs.indexOfKey(uid);
                    if (index >= 0) {
                        userTimeDeltaUs -= mLastUserTimeUs.valueAt(index);
                        systemTimeDeltaUs -= mLastSystemTimeUs.valueAt(index);
                        powerDeltaMaUs -= mLastPowerMaUs.valueAt(index);

                        final long timeDiffUs = nowUs - mLastTimeReadUs;
                        if (userTimeDeltaUs < 0 || systemTimeDeltaUs < 0 || powerDeltaMaUs < 0) {
                            StringBuilder sb = new StringBuilder("Malformed cpu data for UID=");
                            sb.append(uid).append("!\n");
                            sb.append("Time between reads: ");
                            TimeUtils.formatDuration(timeDiffUs / 1000, sb);
                            sb.append("\n");
                            sb.append("Previous times: u=");
                            TimeUtils.formatDuration(mLastUserTimeUs.valueAt(index) / 1000, sb);
                            sb.append(" s=");
                            TimeUtils.formatDuration(mLastSystemTimeUs.valueAt(index) / 1000, sb);
                            sb.append(" p=").append(mLastPowerMaUs.valueAt(index) / 1000);
                            sb.append("mAms\n");

                            sb.append("Current times: u=");
                            TimeUtils.formatDuration(userTimeUs / 1000, sb);
                            sb.append(" s=");
                            TimeUtils.formatDuration(systemTimeUs / 1000, sb);
                            sb.append(" p=").append(powerMaUs / 1000);
                            sb.append("mAms\n");
                            sb.append("Delta: u=");
                            TimeUtils.formatDuration(userTimeDeltaUs / 1000, sb);
                            sb.append(" s=");
                            TimeUtils.formatDuration(systemTimeDeltaUs / 1000, sb);
                            sb.append(" p=").append(powerDeltaMaUs / 1000).append("mAms");
                            Slog.e(TAG, sb.toString());

                            userTimeDeltaUs = 0;
                            systemTimeDeltaUs = 0;
                            powerDeltaMaUs = 0;
                        }
                    }

                    if (userTimeDeltaUs != 0 || systemTimeDeltaUs != 0 || powerDeltaMaUs != 0) {
                        callback.onUidCpuTime(uid, userTimeDeltaUs, systemTimeDeltaUs,
                                powerDeltaMaUs);
                    }
                }
                mLastUserTimeUs.put(uid, userTimeUs);
                mLastSystemTimeUs.put(uid, systemTimeUs);
                mLastPowerMaUs.put(uid, powerMaUs);
            }
        } catch (IOException e) {
            Slog.e(TAG, "Failed to read uid_cputime: " + e.getMessage());
        }
        mLastTimeReadUs = nowUs;
    }

    /**
     * Removes the UID from the kernel module and from internal accounting data.
     * @param uid The UID to remove.
     */
    public void removeUid(int uid) {
        int index = mLastUserTimeUs.indexOfKey(uid);
        if (index >= 0) {
            mLastUserTimeUs.removeAt(index);
            mLastSystemTimeUs.removeAt(index);
            mLastPowerMaUs.removeAt(index);
        }

        try (FileWriter writer = new FileWriter(sRemoveUidProcFile)) {
            writer.write(Integer.toString(uid) + "-" + Integer.toString(uid));
            writer.flush();
        } catch (IOException e) {
            Slog.e(TAG, "failed to remove uid from uid_cputime module", e);
        }
    }
}
