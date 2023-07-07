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

import android.os.BatteryConsumer;
import android.os.Process;
import android.os.UidBatteryConsumer;

import androidx.test.filters.SmallTest;
import androidx.test.runner.AndroidJUnit4;

import com.android.internal.os.PowerProfile;

import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
@SmallTest
public class FlashlightPowerCalculatorTest {
    private static final double PRECISION = 0.00001;

    private static final int APP_UID = Process.FIRST_APPLICATION_UID + 42;

    @Rule
    public final BatteryUsageStatsRule mStatsRule = new BatteryUsageStatsRule()
            .setAveragePower(PowerProfile.POWER_FLASHLIGHT, 360.0);

    @Test
    public void testTimerBasedModel() {
        BatteryStatsImpl.Uid uidStats = mStatsRule.getUidStats(APP_UID);
        uidStats.noteFlashlightTurnedOnLocked(1000);
        uidStats.noteFlashlightTurnedOffLocked(2000);

        FlashlightPowerCalculator calculator =
                new FlashlightPowerCalculator(mStatsRule.getPowerProfile());

        mStatsRule.apply(calculator);

        UidBatteryConsumer consumer = mStatsRule.getUidBatteryConsumer(APP_UID);
        assertThat(consumer.getUsageDurationMillis(BatteryConsumer.POWER_COMPONENT_FLASHLIGHT))
                .isEqualTo(1000);
        assertThat(consumer.getConsumedPower(BatteryConsumer.POWER_COMPONENT_FLASHLIGHT))
                .isWithin(PRECISION).of(0.1);
    }
}
