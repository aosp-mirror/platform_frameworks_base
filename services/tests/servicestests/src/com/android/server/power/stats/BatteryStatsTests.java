/*
 * Copyright (C) 2022 The Android Open Source Project
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

package com.android.server.power.stats;

import org.junit.runner.RunWith;
import org.junit.runners.Suite;

@RunWith(Suite.class)
@Suite.SuiteClasses({
        AmbientDisplayPowerCalculatorTest.class,
        AudioPowerCalculatorTest.class,
        BatteryChargeCalculatorTest.class,
        BatteryExternalStatsWorkerTest.class,
        BatteryStatsCpuTimesTest.class,
        BatteryStatsBackgroundStatsTest.class,
        BatteryStatsBinderCallStatsTest.class,
        BatteryStatsCounterTest.class,
        BatteryStatsDualTimerTest.class,
        BatteryStatsDurationTimerTest.class,
        BatteryStatsHistoryIteratorTest.class,
        BatteryStatsHistoryTest.class,
        BatteryStatsImplTest.class,
        BatteryStatsManagerTest.class,
        BatteryStatsNoteTest.class,
        BatteryStatsSamplingTimerTest.class,
        BatteryStatsSensorTest.class,
        BatteryStatsServTest.class,
        BatteryStatsStopwatchTimerTest.class,
        BatteryStatsTimeBaseTest.class,
        BatteryStatsTimerTest.class,
        BatteryUsageStatsProviderTest.class,
        BatteryUsageStatsTest.class,
        BatteryUsageStatsStoreTest.class,
        BatteryStatsUserLifecycleTests.class,
        BluetoothPowerCalculatorTest.class,
        BstatsCpuTimesValidationTest.class,
        CameraPowerCalculatorTest.class,
        CpuPowerCalculatorTest.class,
        CustomEnergyConsumerPowerCalculatorTest.class,
        FlashlightPowerCalculatorTest.class,
        GnssPowerCalculatorTest.class,
        IdlePowerCalculatorTest.class,
        KernelWakelockReaderTest.class,
        LongSamplingCounterTest.class,
        LongSamplingCounterArrayTest.class,
        EnergyConsumerSnapshotTest.class,
        MobileRadioPowerCalculatorTest.class,
        ScreenPowerCalculatorTest.class,
        SensorPowerCalculatorTest.class,
        SystemServerCpuThreadReaderTest.class,
        SystemServicePowerCalculatorTest.class,
        UserPowerCalculatorTest.class,
        VideoPowerCalculatorTest.class,
        WakelockPowerCalculatorTest.class,
        WifiPowerCalculatorTest.class,
})
public class BatteryStatsTests {
}
