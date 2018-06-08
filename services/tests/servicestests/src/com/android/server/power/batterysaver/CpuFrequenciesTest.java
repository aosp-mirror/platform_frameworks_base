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

import static org.junit.Assert.assertEquals;

import android.support.test.filters.SmallTest;
import android.support.test.runner.AndroidJUnit4;
import android.util.ArrayMap;

import org.junit.Test;
import org.junit.runner.RunWith;

/**
 atest $ANDROID_BUILD_TOP/frameworks/base/services/tests/servicestests/src/com/android/server/power/batterysaver/CpuFrequenciesTest.java
 */
@SmallTest
@RunWith(AndroidJUnit4.class)
public class CpuFrequenciesTest {
    private void check(ArrayMap<String, String> expected, String config) {
        assertEquals(expected, (new CpuFrequencies().parseString(config))
                .toSysFileMap());
    }

    @Test
    public void test() {
        check(new ArrayMap<>(), "");

        final ArrayMap<String, String> expected = new ArrayMap<>();

        expected.clear();
        expected.put("/sys/devices/system/cpu/cpu0/cpufreq/scaling_max_freq", "0");
        check(expected, "0:0");

        expected.clear();
        expected.put("/sys/devices/system/cpu/cpu0/cpufreq/scaling_max_freq", "0");
        expected.put("/sys/devices/system/cpu/cpu1/cpufreq/scaling_max_freq", "1");
        check(expected, "0:0/1:1");

        expected.clear();
        expected.put("/sys/devices/system/cpu/cpu2/cpufreq/scaling_max_freq", "0");
        expected.put("/sys/devices/system/cpu/cpu1/cpufreq/scaling_max_freq", "1234567890");
        check(expected, "2:0/1:1234567890");

        expected.clear();
        expected.put("/sys/devices/system/cpu/cpu0/cpufreq/scaling_max_freq", "1900800");
        expected.put("/sys/devices/system/cpu/cpu4/cpufreq/scaling_max_freq", "1958400");
        check(expected, "0:1900800/4:1958400");

        check(expected, "0:1900800/4:1958400/"); // Shouldn't crash.
        check(expected, "0:1900800/4:1958400/1"); // Shouldn't crash.
        check(expected, "0:1900800/4:1958400/a:1"); // Shouldn't crash.
        check(expected, "0:1900800/4:1958400/1:"); // Shouldn't crash.
        check(expected, "0:1900800/4:1958400/1:b"); // Shouldn't crash.
    }
}
