/*
 * Copyright (C) 2024 The Android Open Source Project
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

package com.android.server.power.stats.processor;

import static android.os.BatteryConsumer.PROCESS_STATE_BACKGROUND;
import static android.os.BatteryConsumer.PROCESS_STATE_CACHED;
import static android.os.BatteryConsumer.PROCESS_STATE_FOREGROUND;
import static android.os.BatteryConsumer.PROCESS_STATE_FOREGROUND_SERVICE;

import static com.android.server.power.stats.processor.AggregatedPowerStatsConfig.POWER_STATE_OTHER;
import static com.android.server.power.stats.processor.AggregatedPowerStatsConfig.SCREEN_STATE_ON;
import static com.android.server.power.stats.processor.AggregatedPowerStatsConfig.SCREEN_STATE_OTHER;
import static com.android.server.power.stats.processor.AggregatedPowerStatsConfig.STATE_POWER;
import static com.android.server.power.stats.processor.AggregatedPowerStatsConfig.STATE_PROCESS_STATE;
import static com.android.server.power.stats.processor.AggregatedPowerStatsConfig.STATE_SCREEN;

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import android.hardware.Sensor;
import android.hardware.SensorManager;
import android.hardware.input.InputSensorInfo;
import android.os.BatteryConsumer;
import android.os.BatteryStats;
import android.os.Process;
import android.platform.test.ravenwood.RavenwoodRule;

import com.android.internal.os.MonotonicClock;
import com.android.internal.os.PowerStats;
import com.android.server.power.stats.BatteryUsageStatsRule;
import com.android.server.power.stats.format.SensorPowerStatsLayout;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.List;
import java.util.function.Supplier;

public class SensorPowerStatsProcessorTest {
    @Rule(order = 0)
    public final RavenwoodRule mRavenwood = new RavenwoodRule.Builder()
            .setProvideMainThread(true)
            .build();

    @Rule(order = 1)
    public final BatteryUsageStatsRule mStatsRule = new BatteryUsageStatsRule()
            .initMeasuredEnergyStatsLocked();

    private static final double PRECISION = 0.00001;
    private static final int APP_UID1 = Process.FIRST_APPLICATION_UID + 42;
    private static final int APP_UID2 = Process.FIRST_APPLICATION_UID + 101;
    private static final int SENSOR_HANDLE_1 = 77;
    private static final int SENSOR_HANDLE_2 = 88;
    private static final int SENSOR_HANDLE_3 = 99;

    @Mock
    private SensorManager mSensorManager;

    private MonotonicClock mMonotonicClock;

    @Before
    public void setup() {
        MockitoAnnotations.initMocks(this);
        mMonotonicClock = new MonotonicClock(0, mStatsRule.getMockClock());
        Sensor sensor1 = createSensor(SENSOR_HANDLE_1, Sensor.TYPE_STEP_COUNTER,
                Sensor.STRING_TYPE_STEP_COUNTER, "dancing", 100);
        Sensor sensor2 = createSensor(SENSOR_HANDLE_2, Sensor.TYPE_MOTION_DETECT,
                "com.example", "tango", 200);
        Sensor sensor3 = createSensor(SENSOR_HANDLE_3, Sensor.TYPE_MOTION_DETECT,
                "com.example", "waltz", 300);
        when(mSensorManager.getSensorList(Sensor.TYPE_ALL)).thenReturn(
                List.of(sensor1, sensor2, sensor3));
    }

    @Test
    public void testPowerEstimation() {
        PowerComponentAggregatedPowerStats stats = createAggregatedPowerStats(
                () -> new SensorPowerStatsProcessor(() -> mSensorManager));

        stats.noteStateChange(buildHistoryItem(0, true, APP_UID1, SENSOR_HANDLE_1));

        // Turn the screen off after 2.5 seconds
        stats.setState(STATE_SCREEN, SCREEN_STATE_OTHER, 2500);
        stats.setUidState(APP_UID1, STATE_PROCESS_STATE, PROCESS_STATE_BACKGROUND, 2500);
        stats.setUidState(APP_UID1, STATE_PROCESS_STATE, PROCESS_STATE_FOREGROUND_SERVICE, 5000);

        stats.noteStateChange(buildHistoryItem(6000, false, APP_UID1, SENSOR_HANDLE_1));
        stats.noteStateChange(buildHistoryItem(7000, true, APP_UID2, SENSOR_HANDLE_1));
        stats.noteStateChange(buildHistoryItem(8000, true, APP_UID2, SENSOR_HANDLE_2));
        stats.noteStateChange(buildHistoryItem(9000, false, APP_UID2, SENSOR_HANDLE_1));

        stats.finish(10000);

        PowerStats.Descriptor descriptor = stats.getPowerStatsDescriptor();
        SensorPowerStatsLayout statsLayout = new SensorPowerStatsLayout(descriptor);

        String dump = stats.toString();
        assertThat(dump).contains(" step_counter: ");
        assertThat(dump).contains(" com.example.tango: ");

        long[] uidStats = new long[descriptor.uidStatsArrayLength];

        // For UID1:
        // SENSOR1 was on for 6000 ms.
        //   Estimated power: 6000 * 100 = 0.167 mAh
        //     split between three different states
        //          fg screen-on: 6000 * 2500/10000
        //          bg screen-off: 6000 * 2500/10000
        //          fgs screen-off: 6000 * 5000/10000
        double expectedPower1 = 0.166666;
        stats.getUidStats(uidStats, APP_UID1,
                states(POWER_STATE_OTHER, SCREEN_STATE_ON, PROCESS_STATE_FOREGROUND));
        assertThat(statsLayout.getUidPowerEstimate(uidStats))
                .isWithin(PRECISION).of(expectedPower1 * 2500 / 10000);
        stats.getUidStats(uidStats, APP_UID1,
                states(POWER_STATE_OTHER, SCREEN_STATE_OTHER, PROCESS_STATE_BACKGROUND));
        assertThat(statsLayout.getUidPowerEstimate(uidStats))
                .isWithin(PRECISION).of(expectedPower1 * 2500 / 10000);
        stats.getUidStats(uidStats, APP_UID1,
                states(POWER_STATE_OTHER, SCREEN_STATE_OTHER, PROCESS_STATE_FOREGROUND_SERVICE));
        assertThat(statsLayout.getUidPowerEstimate(uidStats))
                .isWithin(PRECISION).of(expectedPower1 * 5000 / 10000);

        // For UID2:
        // SENSOR1 was on for 2000 ms.
        //   Estimated power: 2000 * 100 = 0.0556 mAh
        //     split between three different states
        //          cached screen-on: 2000 * 2500/10000
        //          cached screen-off: 2000 * 7500/10000
        // SENSOR2 was on for 2000 ms.
        //   Estimated power: 2000 * 200 = 0.11111 mAh
        //     split between three different states
        //          cached screen-on: 2000 * 2500/10000
        //          cached screen-off: 2000 * 7500/10000
        double expectedPower2 = 0.05555 + 0.11111;
        stats.getUidStats(uidStats, APP_UID2,
                states(POWER_STATE_OTHER, SCREEN_STATE_ON, PROCESS_STATE_CACHED));
        assertThat(statsLayout.getUidPowerEstimate(uidStats))
                .isWithin(PRECISION).of(expectedPower2 * 2500 / 10000);
        stats.getUidStats(uidStats, APP_UID2,
                states(POWER_STATE_OTHER, SCREEN_STATE_OTHER, PROCESS_STATE_CACHED));
        assertThat(statsLayout.getUidPowerEstimate(uidStats))
                .isWithin(PRECISION).of(expectedPower2 * 7500 / 10000);

        long[] deviceStats = new long[descriptor.statsArrayLength];

        stats.getDeviceStats(deviceStats, states(POWER_STATE_OTHER, SCREEN_STATE_ON));
        assertThat(statsLayout.getDevicePowerEstimate(deviceStats))
                .isWithin(PRECISION).of((expectedPower1 + expectedPower2) * 2500 / 10000);

        stats.getDeviceStats(deviceStats, states(POWER_STATE_OTHER, SCREEN_STATE_OTHER));
        assertThat(statsLayout.getDevicePowerEstimate(deviceStats))
                .isWithin(PRECISION).of((expectedPower1 + expectedPower2) * 7500 / 10000);
    }

    private BatteryStats.HistoryItem buildHistoryItem(int timestamp, boolean stateOn,
            int uid, int sensor) {
        mStatsRule.setTime(timestamp, timestamp);
        BatteryStats.HistoryItem historyItem = new BatteryStats.HistoryItem();
        historyItem.time = mMonotonicClock.monotonicTime();
        historyItem.states = stateOn ? BatteryStats.HistoryItem.STATE_SENSOR_ON_FLAG : 0;
        if (stateOn) {
            historyItem.eventCode = BatteryStats.HistoryItem.EVENT_STATE_CHANGE
                    | BatteryStats.HistoryItem.EVENT_FLAG_START;
        } else {
            historyItem.eventCode = BatteryStats.HistoryItem.EVENT_STATE_CHANGE
                    | BatteryStats.HistoryItem.EVENT_FLAG_FINISH;
        }
        historyItem.eventTag = historyItem.localEventTag;
        historyItem.eventTag.uid = uid;
        historyItem.eventTag.string = "sensor:0x" + Integer.toHexString(sensor);
        return historyItem;
    }

    private int[] states(int... states) {
        return states;
    }

    private static PowerComponentAggregatedPowerStats createAggregatedPowerStats(
            Supplier<PowerStatsProcessor> processorSupplier) {
        AggregatedPowerStatsConfig config = new AggregatedPowerStatsConfig();
        config.trackPowerComponent(BatteryConsumer.POWER_COMPONENT_SENSORS)
                .trackDeviceStates(
                        AggregatedPowerStatsConfig.STATE_POWER,
                        AggregatedPowerStatsConfig.STATE_SCREEN)
                .trackUidStates(
                        AggregatedPowerStatsConfig.STATE_POWER,
                        AggregatedPowerStatsConfig.STATE_SCREEN,
                        AggregatedPowerStatsConfig.STATE_PROCESS_STATE)
                .setProcessorSupplier(processorSupplier);

        PowerComponentAggregatedPowerStats powerComponentStats = new AggregatedPowerStats(config)
                .getPowerComponentStats(BatteryConsumer.POWER_COMPONENT_SENSORS);
        powerComponentStats.start(0);

        powerComponentStats.setState(STATE_POWER, POWER_STATE_OTHER, 0);
        powerComponentStats.setState(STATE_SCREEN, SCREEN_STATE_ON, 0);
        powerComponentStats.setUidState(APP_UID1, STATE_PROCESS_STATE, PROCESS_STATE_FOREGROUND, 0);
        powerComponentStats.setUidState(APP_UID2, STATE_PROCESS_STATE, PROCESS_STATE_CACHED, 0);

        return powerComponentStats;
    }

    private Sensor createSensor(int handle, int type, String stringType, String name, float power) {
        if (RavenwoodRule.isOnRavenwood()) {
            Sensor sensor = mock(Sensor.class);
            when(sensor.getHandle()).thenReturn(handle);
            when(sensor.getType()).thenReturn(type);
            when(sensor.getStringType()).thenReturn(stringType);
            when(sensor.getName()).thenReturn(name);
            when(sensor.getPower()).thenReturn(power);
            return sensor;
        } else {
            return new Sensor(new InputSensorInfo(name, "vendor", 0 /* version */,
                    handle, type, 100.0f /*maxRange */, 0.02f /* resolution */,
                    (float) power, 1000 /* minDelay */, 0 /* fifoReservedEventCount */,
                    0 /* fifoMaxEventCount */, stringType /* stringType */,
                    "" /* requiredPermission */, 0 /* maxDelay */, 0 /* flags */, 0 /* id */));
        }
    }
}
