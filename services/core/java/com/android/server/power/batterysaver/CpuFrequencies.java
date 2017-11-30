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
package com.android.server.power.batterysaver;

import android.util.ArrayMap;
import android.util.Slog;

import com.android.internal.annotations.GuardedBy;

import java.util.Map;


/**
 * Helper to parse a list of "core-number:frequency" pairs concatenated with / as a separator,
 * and convert them into a map of "filename -> value" that should be written to
 * /sys/.../scaling_max_freq.
 *
 * Example input: "0:1900800/4:2500000", which will be converted into:
 *   "/sys/devices/system/cpu/cpu0/cpufreq/scaling_max_freq" "1900800"
 *   "/sys/devices/system/cpu/cpu4/cpufreq/scaling_max_freq" "2500000"
 *
 * Test:
 atest $ANDROID_BUILD_TOP/frameworks/base/services/tests/servicestests/src/com/android/server/power/batterysaver/CpuFrequenciesTest.java
 */
public class CpuFrequencies {
    private static final String TAG = "CpuFrequencies";

    private final Object mLock = new Object();

    @GuardedBy("mLock")
    private final ArrayMap<Integer, Long> mCoreAndFrequencies = new ArrayMap<>();

    public CpuFrequencies() {
    }

    /**
     * Parse a string.
     */
    public CpuFrequencies parseString(String cpuNumberAndFrequencies) {
        synchronized (mLock) {
            mCoreAndFrequencies.clear();
            try {
                for (String pair : cpuNumberAndFrequencies.split("/")) {
                    pair = pair.trim();
                    if (pair.length() == 0) {
                        continue;
                    }
                    final String[] coreAndFreq = pair.split(":", 2);

                    if (coreAndFreq.length != 2) {
                        throw new IllegalArgumentException("Wrong format");
                    }
                    final int core = Integer.parseInt(coreAndFreq[0]);
                    final long freq = Long.parseLong(coreAndFreq[1]);

                    mCoreAndFrequencies.put(core, freq);
                }
            } catch (IllegalArgumentException e) {
                Slog.wtf(TAG, "Invalid configuration: '" + cpuNumberAndFrequencies + "'");
            }
        }
        return this;
    }

    /**
     * Return a new map containing the filename-value pairs.
     */
    public ArrayMap<String, String> toSysFileMap() {
        final ArrayMap<String, String> map = new ArrayMap<>();
        addToSysFileMap(map);
        return map;
    }

    /**
     * Add the filename-value pairs to an existing map.
     */
    public void addToSysFileMap(Map<String, String> map) {
        synchronized (mLock) {
            final int size = mCoreAndFrequencies.size();

            for (int i = 0; i < size; i++) {
                final int core = mCoreAndFrequencies.keyAt(i);
                final long freq = mCoreAndFrequencies.valueAt(i);

                final String file = "/sys/devices/system/cpu/cpu" + Integer.toString(core) +
                        "/cpufreq/scaling_max_freq";

                map.put(file, Long.toString(freq));
            }
        }
    }
}
