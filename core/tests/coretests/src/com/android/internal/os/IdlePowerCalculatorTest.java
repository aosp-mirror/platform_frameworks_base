/*
 * Copyright (C) 2020 The Android Open Source Project
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

import static com.google.common.truth.Truth.assertThat;

import android.os.BatteryConsumer;

import androidx.test.filters.SmallTest;
import androidx.test.runner.AndroidJUnit4;

import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
@SmallTest
public class IdlePowerCalculatorTest {
    private static final double PRECISION = 0.00001;

    @Rule
    public final BatteryUsageStatsRule mStatsRule = new BatteryUsageStatsRule()
            .setAveragePower(PowerProfile.POWER_CPU_IDLE, 720.0)
            .setAveragePower(PowerProfile.POWER_CPU_SUSPEND, 360.0);

    @Test
    public void testTimerBasedModel() {
        mStatsRule.setTime(3_000, 2_000);

        IdlePowerCalculator calculator = new IdlePowerCalculator(mStatsRule.getPowerProfile());

        mStatsRule.apply(calculator);

        BatteryConsumer deviceConsumer = mStatsRule.getDeviceBatteryConsumer();
        assertThat(deviceConsumer.getUsageDurationMillis(BatteryConsumer.POWER_COMPONENT_IDLE))
                .isEqualTo(3000);
        assertThat(deviceConsumer.getConsumedPower(BatteryConsumer.POWER_COMPONENT_IDLE))
                .isWithin(PRECISION).of(0.7);

        BatteryConsumer appsConsumer = mStatsRule.getAppsBatteryConsumer();
        assertThat(appsConsumer.getUsageDurationMillis(BatteryConsumer.POWER_COMPONENT_IDLE))
                .isEqualTo(0);
        assertThat(appsConsumer.getConsumedPower(BatteryConsumer.POWER_COMPONENT_IDLE))
                .isWithin(PRECISION).of(0);
    }
}
