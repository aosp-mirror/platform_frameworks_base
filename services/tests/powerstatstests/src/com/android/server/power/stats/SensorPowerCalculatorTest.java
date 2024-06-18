/*
 * Copyright (C) 2021 The Android Open Source Project
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

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import android.hardware.Sensor;
import android.hardware.SensorManager;
import android.hardware.input.InputSensorInfo;
import android.os.BatteryConsumer;
import android.os.Process;
import android.os.UidBatteryConsumer;
import android.platform.test.ravenwood.RavenwoodRule;

import androidx.test.filters.SmallTest;
import androidx.test.runner.AndroidJUnit4;

import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.List;

@RunWith(AndroidJUnit4.class)
@SmallTest
public class SensorPowerCalculatorTest {
    @Rule(order = 0)
    public final RavenwoodRule mRavenwood = new RavenwoodRule.Builder()
            .setProvideMainThread(true)
            .build();

    private static final double PRECISION = 0.00001;

    private static final int SENSOR_HANDLE_1 = 1;
    private static final int SENSOR_HANDLE_2 = 2;

    private static final int APP_UID = Process.FIRST_APPLICATION_UID + 42;

    @Rule(order = 1)
    public final BatteryUsageStatsRule mStatsRule = new BatteryUsageStatsRule();

    @Test
    public void testTimerBasedModel() {
        Sensor sensor1 = createSensor(SENSOR_HANDLE_1, Sensor.TYPE_AMBIENT_TEMPERATURE, 360);
        Sensor sensor2 = createSensor(SENSOR_HANDLE_2, Sensor.TYPE_STEP_COUNTER, 720);

        SensorManager sensorManager = mock(SensorManager.class);
        when(sensorManager.getSensorList(Sensor.TYPE_ALL))
                .thenReturn(List.of(sensor1, sensor2));

        final BatteryStatsImpl stats = mStatsRule.getBatteryStats();
        synchronized (stats) {
            stats.noteStartSensorLocked(APP_UID, SENSOR_HANDLE_1, 1000, 1000);
            stats.noteStopSensorLocked(APP_UID, SENSOR_HANDLE_1, 2000, 2000);
            stats.noteStartSensorLocked(APP_UID, SENSOR_HANDLE_2, 3000, 3000);
            stats.noteStopSensorLocked(APP_UID, SENSOR_HANDLE_2, 5000, 5000);
        }

        SensorPowerCalculator calculator = new SensorPowerCalculator(sensorManager);

        mStatsRule.apply(calculator);

        UidBatteryConsumer consumer = mStatsRule.getUidBatteryConsumer(APP_UID);
        assertThat(consumer.getUsageDurationMillis(BatteryConsumer.POWER_COMPONENT_SENSORS))
                .isEqualTo(3000);
        assertThat(consumer.getConsumedPower(BatteryConsumer.POWER_COMPONENT_SENSORS))
                .isWithin(PRECISION).of(0.5);

        BatteryConsumer deviceConsumer = mStatsRule.getDeviceBatteryConsumer();
        assertThat(deviceConsumer.getConsumedPower(BatteryConsumer.POWER_COMPONENT_SENSORS))
                .isWithin(PRECISION).of(0.5);

        BatteryConsumer appsConsumer = mStatsRule.getDeviceBatteryConsumer();
        assertThat(appsConsumer.getConsumedPower(BatteryConsumer.POWER_COMPONENT_SENSORS))
                .isWithin(PRECISION).of(0.5);
    }

    private Sensor createSensor(int handle, int type, float power) {
        if (RavenwoodRule.isUnderRavenwood()) {
            Sensor sensor = mock(Sensor.class);

            when(sensor.getHandle()).thenReturn(handle);
            when(sensor.getType()).thenReturn(type);
            when(sensor.getPower()).thenReturn(power);
            return sensor;
        } else {
            return new Sensor(new InputSensorInfo("name", "vendor", 0 /* version */,
                    handle, type, 100.0f /*maxRange */, 0.02f /* resolution */,
                    (float) power, 1000 /* minDelay */, 0 /* fifoReservedEventCount */,
                    0 /* fifoMaxEventCount */, "" /* stringType */, "" /* requiredPermission */,
                    0 /* maxDelay */, 0 /* flags */, 0 /* id */));
        }
    }
}
