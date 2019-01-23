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

import org.junit.runner.RunWith;
import org.junit.runners.Suite;

@RunWith(Suite.class)
@Suite.SuiteClasses({
        BatteryStatsCpuTimesTest.class,
        BatteryStatsBackgroundStatsTest.class,
        BatteryStatsCounterTest.class,
        BatteryStatsDualTimerTest.class,
        BatteryStatsDurationTimerTest.class,
        BatteryStatsHelperTest.class,
        BatteryStatsHistoryTest.class,
        BatteryStatsImplTest.class,
        BatteryStatsNoteTest.class,
        BatteryStatsSamplingTimerTest.class,
        BatteryStatsSensorTest.class,
        BatteryStatsServTest.class,
        BatteryStatsStopwatchTimerTest.class,
        BatteryStatsTimeBaseTest.class,
        BatteryStatsTimerTest.class,
        BatteryStatsUidTest.class,
        BatteryStatsUserLifecycleTests.class,
        KernelCpuProcStringReaderTest.class,
        KernelCpuUidActiveTimeReaderTest.class,
        KernelCpuUidClusterTimeReaderTest.class,
        KernelCpuUidFreqTimeReaderTest.class,
        KernelCpuUidUserSysTimeReaderTest.class,
        KernelMemoryBandwidthStatsTest.class,
        KernelSingleUidTimeReaderTest.class,
        KernelWakelockReaderTest.class,
        LongSamplingCounterTest.class,
        LongSamplingCounterArrayTest.class,
        PowerCalculatorTest.class,
        PowerProfileTest.class
    })
public class BatteryStatsTests {
}

