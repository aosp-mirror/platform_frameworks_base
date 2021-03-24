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

package com.android.internal.os;

import static com.google.common.truth.Truth.assertThat;

import android.os.BatteryConsumer;
import android.os.BatteryUsageStatsQuery;
import android.os.Process;
import android.os.UidBatteryConsumer;

import androidx.test.filters.SmallTest;
import androidx.test.runner.AndroidJUnit4;

import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
@SmallTest
public class GnssPowerCalculatorTest {
    private static final double PRECISION = 0.00001;

    private static final int APP_UID = Process.FIRST_APPLICATION_UID + 42;
    private static final int APP_UID2 = Process.FIRST_APPLICATION_UID + 222;

    @Rule
    public final BatteryUsageStatsRule mStatsRule = new BatteryUsageStatsRule()
            .setAveragePower(PowerProfile.POWER_GPS_ON, 360.0)
            .setAveragePower(PowerProfile.POWER_GPS_SIGNAL_QUALITY_BASED,
                    new double[] {720.0, 1440.0, 1800.0})
            .initMeasuredEnergyStatsLocked(0);

    @Test
    public void testTimerBasedModel() {
        BatteryStatsImpl.Uid uidStats = mStatsRule.getUidStats(APP_UID);
        uidStats.noteStartGps(1000);
        uidStats.noteStopGps(2000);

        GnssPowerCalculator calculator =
                new GnssPowerCalculator(mStatsRule.getPowerProfile());

        mStatsRule.apply(new BatteryUsageStatsQuery.Builder().powerProfileModeledOnly().build(),
                calculator);

        UidBatteryConsumer consumer = mStatsRule.getUidBatteryConsumer(APP_UID);
        assertThat(consumer.getUsageDurationMillis(BatteryConsumer.TIME_COMPONENT_GNSS))
                .isEqualTo(1000);
        assertThat(consumer.getConsumedPower(BatteryConsumer.POWER_COMPONENT_GNSS))
                .isWithin(PRECISION).of(0.1);
    }

    @Test
    public void testMeasuredEnergyBasedModel() {
        BatteryStatsImpl.Uid uidStats = mStatsRule.getUidStats(APP_UID);
        uidStats.noteStartGps(1000);
        uidStats.noteStopGps(2000);

        BatteryStatsImpl.Uid uidStats2 = mStatsRule.getUidStats(APP_UID2);
        uidStats2.noteStartGps(3000);
        uidStats2.noteStopGps(5000);

        BatteryStatsImpl stats = mStatsRule.getBatteryStats();
        stats.updateGnssMeasuredEnergyStatsLocked(30_000_000, 6000);

        GnssPowerCalculator calculator =
                new GnssPowerCalculator(mStatsRule.getPowerProfile());

        mStatsRule.apply(calculator);

        UidBatteryConsumer consumer = mStatsRule.getUidBatteryConsumer(APP_UID);
        assertThat(consumer.getUsageDurationMillis(BatteryConsumer.TIME_COMPONENT_GNSS))
                .isEqualTo(1000);
        assertThat(consumer.getConsumedPower(BatteryConsumer.POWER_COMPONENT_GNSS))
                .isWithin(PRECISION).of(2.77777);

        UidBatteryConsumer consumer2 = mStatsRule.getUidBatteryConsumer(APP_UID2);
        assertThat(consumer2.getUsageDurationMillis(BatteryConsumer.TIME_COMPONENT_GNSS))
                .isEqualTo(2000);
        assertThat(consumer2.getConsumedPower(BatteryConsumer.POWER_COMPONENT_GNSS))
                .isWithin(PRECISION).of(5.55555);
    }
}
