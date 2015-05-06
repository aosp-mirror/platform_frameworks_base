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

import android.text.TextUtils;
import android.util.Slog;

import java.io.BufferedReader;
import java.io.FileReader;
import java.io.IOException;
import java.util.Arrays;

/**
 * Reads CPU time spent at various frequencies and provides a delta from the last call to
 * {@link #readDelta}. Each line in the proc file has the format:
 *
 * freq time
 *
 * where time is measured in 1/100 seconds.
 */
public class KernelCpuSpeedReader {
    private static final String TAG = "KernelCpuSpeedReader";
    private static final String sProcFile =
            "/sys/devices/system/cpu/cpu0/cpufreq/stats/time_in_state";
    private static final int MAX_SPEEDS = 60;

    private long[] mLastSpeedTimes = new long[MAX_SPEEDS];
    private long[] mDeltaSpeedTimes = new long[MAX_SPEEDS];

    /**
     * The returned array is modified in subsequent calls to {@link #readDelta}.
     * @return The time (in milliseconds) spent at different cpu speeds since the last call to
     * {@link #readDelta}.
     */
    public long[] readDelta() {
        try (BufferedReader reader = new BufferedReader(new FileReader(sProcFile))) {
            TextUtils.SimpleStringSplitter splitter = new TextUtils.SimpleStringSplitter(' ');
            String line;
            int speedIndex = 0;
            while ((line = reader.readLine()) != null) {
                splitter.setString(line);
                Long.parseLong(splitter.next());

                // The proc file reports time in 1/100 sec, so convert to milliseconds.
                long time = Long.parseLong(splitter.next()) * 10;
                mDeltaSpeedTimes[speedIndex] = time - mLastSpeedTimes[speedIndex];
                mLastSpeedTimes[speedIndex] = time;
                speedIndex++;
            }
        } catch (IOException e) {
            Slog.e(TAG, "Failed to read cpu-freq", e);
            Arrays.fill(mDeltaSpeedTimes, 0);
        }
        return mDeltaSpeedTimes;
    }
}
