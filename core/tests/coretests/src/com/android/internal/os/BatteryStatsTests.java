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
        AmbientDisplayPowerCalculatorTest.class,
        AudioPowerCalculatorTest.class,
        BatteryStatsCpuTimesTest.class,
        BatteryStatsBackgroundStatsTest.class,
        BatteryStatsBinderCallStatsTest.class,
        BatteryStatsCounterTest.class,
        BatteryStatsDualTimerTest.class,
        BatteryStatsDurationTimerTest.class,
        BatteryStatsHelperTest.class,
        BatteryStatsHistoryIteratorTest.class,
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
        BatteryUsageStatsProviderTest.class,
        BatteryUsageStatsTest.class,
        BatteryStatsUserLifecycleTests.class,
        BluetoothPowerCalculatorTest.class,
        BstatsCpuTimesValidationTest.class,
        CameraPowerCalculatorTest.class,
        CpuPowerCalculatorTest.class,
        CustomMeasuredPowerCalculatorTest.class,
        DischargedPowerCalculatorTest.class,
        FlashlightPowerCalculatorTest.class,
        GnssPowerCalculatorTest.class,
        IdlePowerCalculatorTest.class,
        KernelCpuProcStringReaderTest.class,
        KernelCpuUidActiveTimeReaderTest.class,
        KernelCpuUidBpfMapReaderTest.class,
        KernelCpuUidClusterTimeReaderTest.class,
        KernelCpuUidFreqTimeReaderTest.class,
        KernelCpuUidUserSysTimeReaderTest.class,
        KernelMemoryBandwidthStatsTest.class,
        KernelSingleUidTimeReaderTest.class,
        KernelWakelockReaderTest.class,
        LongSamplingCounterTest.class,
        LongSamplingCounterArrayTest.class,
        MobileRadioPowerCalculatorTest.class,
        PowerCalculatorTest.class,
        PowerProfileTest.class,
        ScreenPowerCalculatorTest.class,
        SensorPowerCalculatorTest.class,
        SystemServicePowerCalculatorTest.class,
        UserPowerCalculatorTest.class,
        VideoPowerCalculatorTest.class,
        WakelockPowerCalculatorTest.class,
        WifiPowerCalculatorTest.class,

        com.android.internal.power.MeasuredEnergyStatsTest.class
    })
public class BatteryStatsTests {
}