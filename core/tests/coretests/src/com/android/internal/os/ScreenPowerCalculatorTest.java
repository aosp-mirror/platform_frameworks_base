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

import android.app.ActivityManager;
import android.os.BatteryConsumer;
import android.os.BatteryUsageStatsQuery;
import android.os.Process;
import android.os.SystemBatteryConsumer;
import android.os.UidBatteryConsumer;
import android.view.Display;

import androidx.test.filters.SmallTest;
import androidx.test.runner.AndroidJUnit4;

import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
@SmallTest
public class ScreenPowerCalculatorTest {
    private static final double PRECISION = 0.00001;
    private static final int APP_UID1 = Process.FIRST_APPLICATION_UID + 42;
    private static final int APP_UID2 = Process.FIRST_APPLICATION_UID + 43;
    private static final long MINUTE_IN_MS = 60 * 1000;
    private static final long MINUTE_IN_US = 60 * 1000 * 1000;

    @Rule
    public final BatteryUsageStatsRule mStatsRule = new BatteryUsageStatsRule()
            .setAveragePower(PowerProfile.POWER_SCREEN_ON, 36.0)
            .setAveragePower(PowerProfile.POWER_SCREEN_FULL, 48.0);

    @Test
    public void testMeasuredEnergyBasedModel() {
        BatteryStatsImpl batteryStats = mStatsRule.getBatteryStats();

        batteryStats.noteScreenStateLocked(Display.STATE_ON, 0, 0, 0);
        batteryStats.updateDisplayMeasuredEnergyStatsLocked(0, Display.STATE_ON, 2 * MINUTE_IN_MS);

        setFgState(APP_UID1, true, 2 * MINUTE_IN_MS, 2 * MINUTE_IN_MS);
        setFgState(APP_UID1, false, 20 * MINUTE_IN_MS, 20 * MINUTE_IN_MS);
        setFgState(APP_UID2, true, 30 * MINUTE_IN_MS, 30 * MINUTE_IN_MS);

        batteryStats.updateDisplayMeasuredEnergyStatsLocked(300_000_000, Display.STATE_ON,
                60 * MINUTE_IN_MS);

        batteryStats.noteScreenStateLocked(Display.STATE_OFF,
                80 * MINUTE_IN_MS, 80 * MINUTE_IN_MS, 80 * MINUTE_IN_MS);

        batteryStats.updateDisplayMeasuredEnergyStatsLocked(100_000_000, Display.STATE_DOZE,
                120 * MINUTE_IN_MS);

        mStatsRule.setTime(120 * MINUTE_IN_US, 120 * MINUTE_IN_US);

        ScreenPowerCalculator calculator =
                new ScreenPowerCalculator(mStatsRule.getPowerProfile());

        mStatsRule.apply(calculator);

        SystemBatteryConsumer consumer =
                mStatsRule.getSystemBatteryConsumer(SystemBatteryConsumer.DRAIN_TYPE_SCREEN);
        assertThat(consumer.getUsageDurationMillis(BatteryConsumer.TIME_COMPONENT_USAGE))
                .isEqualTo(80 * MINUTE_IN_MS);

        // 400000000 uAs * (1 mA / 1000 uA) * (1 h / 3600 s)  = 111.11111 mAh
        assertThat(consumer.getConsumedPower(BatteryConsumer.POWER_COMPONENT_USAGE))
                .isWithin(PRECISION).of(111.11111);

        UidBatteryConsumer uid1 = mStatsRule.getUidBatteryConsumer(APP_UID1);
        assertThat(uid1.getUsageDurationMillis(BatteryConsumer.TIME_COMPONENT_SCREEN))
                .isEqualTo(18 * MINUTE_IN_MS);

        // Uid1 ran for 18 minutes out of the total 48 min of foreground time during the first
        // Display update. Uid1 charge = 18 / 48 * 300000000 uAs = 31.25 mAh
        assertThat(uid1.getConsumedPower(BatteryConsumer.POWER_COMPONENT_SCREEN))
                .isWithin(PRECISION).of(31.25);

        UidBatteryConsumer uid2 = mStatsRule.getUidBatteryConsumer(APP_UID2);
        assertThat(uid2.getUsageDurationMillis(BatteryConsumer.TIME_COMPONENT_SCREEN))
                .isEqualTo(90 * MINUTE_IN_MS);

        // Uid2 ran for 30 minutes out of the total 48 min of foreground time during the first
        // Display update and then took all of the time during the second Display update.
        // Uid1 charge = 30 / 48 * 300000000 + 100000000 mAs = 79.86111 mAh
        assertThat(uid2.getConsumedPower(BatteryConsumer.POWER_COMPONENT_SCREEN))
                .isWithin(PRECISION).of(79.86111);
    }

    @Test
    public void testPowerProfileBasedModel() {
        BatteryStatsImpl batteryStats = mStatsRule.getBatteryStats();

        batteryStats.noteScreenStateLocked(Display.STATE_ON, 0, 0, 0);

        setFgState(APP_UID1, true, 2 * MINUTE_IN_MS, 2 * MINUTE_IN_MS);

        batteryStats.noteScreenBrightnessLocked(100, 5 * MINUTE_IN_MS, 5 * MINUTE_IN_MS);
        batteryStats.noteScreenBrightnessLocked(200, 10 * MINUTE_IN_MS, 10 * MINUTE_IN_MS);

        setFgState(APP_UID1, false, 20 * MINUTE_IN_MS, 20 * MINUTE_IN_MS);

        setFgState(APP_UID2, true, 30 * MINUTE_IN_MS, 30 * MINUTE_IN_MS);

        batteryStats.noteScreenStateLocked(Display.STATE_OFF,
                80 * MINUTE_IN_MS, 80 * MINUTE_IN_MS, 80 * MINUTE_IN_MS);

        mStatsRule.setTime(120 * MINUTE_IN_US, 120 * MINUTE_IN_US);

        ScreenPowerCalculator calculator =
                new ScreenPowerCalculator(mStatsRule.getPowerProfile());

        mStatsRule.apply(new BatteryUsageStatsQuery.Builder().powerProfileModeledOnly().build(),
                calculator);

        SystemBatteryConsumer consumer =
                mStatsRule.getSystemBatteryConsumer(SystemBatteryConsumer.DRAIN_TYPE_SCREEN);
        assertThat(consumer.getUsageDurationMillis(BatteryConsumer.TIME_COMPONENT_USAGE))
                .isEqualTo(80 * MINUTE_IN_MS);
        assertThat(consumer.getConsumedPower(BatteryConsumer.POWER_COMPONENT_USAGE))
                .isWithin(PRECISION).of(88.4);

        UidBatteryConsumer uid1 = mStatsRule.getUidBatteryConsumer(APP_UID1);
        assertThat(uid1.getUsageDurationMillis(BatteryConsumer.TIME_COMPONENT_SCREEN))
                .isEqualTo(18 * MINUTE_IN_MS);
        assertThat(uid1.getConsumedPower(BatteryConsumer.POWER_COMPONENT_SCREEN))
                .isWithin(PRECISION).of(14.73333);

        UidBatteryConsumer uid2 = mStatsRule.getUidBatteryConsumer(APP_UID2);
        assertThat(uid2.getUsageDurationMillis(BatteryConsumer.TIME_COMPONENT_SCREEN))
                .isEqualTo(90 * MINUTE_IN_MS);
        assertThat(uid2.getConsumedPower(BatteryConsumer.POWER_COMPONENT_SCREEN))
                .isWithin(PRECISION).of(73.66666);
    }

    private void setFgState(int uid, boolean fgOn, long realtimeMs, long uptimeMs) {
        BatteryStatsImpl batteryStats = mStatsRule.getBatteryStats();
        if (fgOn) {
            batteryStats.noteActivityResumedLocked(uid, realtimeMs, uptimeMs);
            batteryStats.noteUidProcessStateLocked(uid, ActivityManager.PROCESS_STATE_TOP,
                    realtimeMs, uptimeMs);
        } else {
            batteryStats.noteActivityPausedLocked(uid, realtimeMs, uptimeMs);
            batteryStats.noteUidProcessStateLocked(uid, ActivityManager.PROCESS_STATE_CACHED_EMPTY,
                    realtimeMs, uptimeMs);
        }
    }
}
