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
        batteryStats.updateDisplayMeasuredEnergyStatsLocked(0, Display.STATE_ON, 0);
        setProcState(APP_UID1, ActivityManager.PROCESS_STATE_TOP, true,
                0, 0);

        batteryStats.updateDisplayMeasuredEnergyStatsLocked(200_000_000, Display.STATE_ON,
                15 * MINUTE_IN_MS);

        setProcState(APP_UID1, ActivityManager.PROCESS_STATE_CACHED_EMPTY, false,
                20 * MINUTE_IN_MS, 20 * MINUTE_IN_MS);

        setProcState(APP_UID2, ActivityManager.PROCESS_STATE_TOP, true,
                20 * MINUTE_IN_MS, 20 * MINUTE_IN_MS);

        batteryStats.updateDisplayMeasuredEnergyStatsLocked(300_000_000, Display.STATE_ON,
                60 * MINUTE_IN_MS);

        batteryStats.noteScreenStateLocked(Display.STATE_OFF,
                80 * MINUTE_IN_MS, 80 * MINUTE_IN_MS, 80 * MINUTE_IN_MS);
        setProcState(APP_UID2, ActivityManager.PROCESS_STATE_TOP_SLEEPING, false,
                80 * MINUTE_IN_MS, 80 * MINUTE_IN_MS);

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

        // 600000000 uAs * (1 mA / 1000 uA) * (1 h / 3600 s)  = 166.66666 mAh
        assertThat(consumer.getConsumedPower(BatteryConsumer.POWER_COMPONENT_USAGE))
                .isWithin(PRECISION).of(166.66666);
        assertThat(consumer.getPowerModel(BatteryConsumer.POWER_COMPONENT_USAGE))
                .isEqualTo(BatteryConsumer.POWER_MODEL_MEASURED_ENERGY);
        assertThat(consumer.getConsumedPower())
                .isWithin(PRECISION).of(166.66666);
        assertThat(consumer.getPowerConsumedByApps())
                .isWithin(PRECISION).of(166.66666);

        UidBatteryConsumer uid1 = mStatsRule.getUidBatteryConsumer(APP_UID1);
        assertThat(uid1.getUsageDurationMillis(BatteryConsumer.TIME_COMPONENT_SCREEN))
                .isEqualTo(20 * MINUTE_IN_MS);

        // Uid1 took all of the foreground time during the first Display update.
        // It also ran for 5 out of 45 min during the second Display update:
        // Uid1 charge = 200000000 + 5 / 45 * 300000000 mAs = 64.81 mAh
        assertThat(uid1.getConsumedPower(BatteryConsumer.POWER_COMPONENT_SCREEN))
                .isWithin(PRECISION).of(64.81481);
        assertThat(uid1.getPowerModel(BatteryConsumer.POWER_COMPONENT_SCREEN))
                .isEqualTo(BatteryConsumer.POWER_MODEL_MEASURED_ENERGY);

        UidBatteryConsumer uid2 = mStatsRule.getUidBatteryConsumer(APP_UID2);
        assertThat(uid2.getUsageDurationMillis(BatteryConsumer.TIME_COMPONENT_SCREEN))
                .isEqualTo(60 * MINUTE_IN_MS);

        // Uid2 ran for 40 minutes out of the total 45 min of foreground time during the second
        // Display update and then took all of the time during the third Display update.
        // Uid2 charge = 40 / 45 * 300000000 + 100000000 mAs = 101.85 mAh
        assertThat(uid2.getConsumedPower(BatteryConsumer.POWER_COMPONENT_SCREEN))
                .isWithin(PRECISION).of(101.85185);
        assertThat(uid2.getPowerModel(BatteryConsumer.POWER_COMPONENT_SCREEN))
                .isEqualTo(BatteryConsumer.POWER_MODEL_MEASURED_ENERGY);
    }

    @Test
    public void testPowerProfileBasedModel() {
        BatteryStatsImpl batteryStats = mStatsRule.getBatteryStats();

        batteryStats.noteScreenStateLocked(Display.STATE_ON, 0, 0, 0);
        batteryStats.noteScreenBrightnessLocked(255, 0, 0);
        setProcState(APP_UID1, ActivityManager.PROCESS_STATE_TOP, true,
                0, 0);

        batteryStats.noteScreenBrightnessLocked(100, 5 * MINUTE_IN_MS, 5 * MINUTE_IN_MS);
        batteryStats.noteScreenBrightnessLocked(200, 10 * MINUTE_IN_MS, 10 * MINUTE_IN_MS);

        setProcState(APP_UID1, ActivityManager.PROCESS_STATE_CACHED_EMPTY, false,
                20 * MINUTE_IN_MS, 20 * MINUTE_IN_MS);
        setProcState(APP_UID2, ActivityManager.PROCESS_STATE_TOP, true,
                20 * MINUTE_IN_MS, 20 * MINUTE_IN_MS);

        batteryStats.noteScreenStateLocked(Display.STATE_OFF,
                80 * MINUTE_IN_MS, 80 * MINUTE_IN_MS, 80 * MINUTE_IN_MS);
        setProcState(APP_UID2, ActivityManager.PROCESS_STATE_TOP_SLEEPING, false,
                80 * MINUTE_IN_MS, 80 * MINUTE_IN_MS);

        mStatsRule.setTime(120 * MINUTE_IN_US, 120 * MINUTE_IN_US);

        ScreenPowerCalculator calculator =
                new ScreenPowerCalculator(mStatsRule.getPowerProfile());

        mStatsRule.apply(BatteryUsageStatsRule.POWER_PROFILE_MODEL_ONLY, calculator);

        SystemBatteryConsumer consumer =
                mStatsRule.getSystemBatteryConsumer(SystemBatteryConsumer.DRAIN_TYPE_SCREEN);
        assertThat(consumer.getUsageDurationMillis(BatteryConsumer.TIME_COMPONENT_USAGE))
                .isEqualTo(80 * MINUTE_IN_MS);
        assertThat(consumer.getConsumedPower(BatteryConsumer.POWER_COMPONENT_USAGE))
                .isWithin(PRECISION).of(92.0);
        assertThat(consumer.getPowerModel(BatteryConsumer.POWER_COMPONENT_USAGE))
                .isEqualTo(BatteryConsumer.POWER_MODEL_POWER_PROFILE);
        assertThat(consumer.getConsumedPower())
                .isWithin(PRECISION).of(92.0);
        assertThat(consumer.getPowerConsumedByApps())
                .isWithin(PRECISION).of(92.0);

        UidBatteryConsumer uid1 = mStatsRule.getUidBatteryConsumer(APP_UID1);
        assertThat(uid1.getUsageDurationMillis(BatteryConsumer.TIME_COMPONENT_SCREEN))
                .isEqualTo(20 * MINUTE_IN_MS);

        // Uid1 took 20 out of the total of 80 min of foreground activity
        // Uid1 charge = 20 / 80 * 92.0 = 23.0 mAh
        assertThat(uid1.getConsumedPower(BatteryConsumer.POWER_COMPONENT_SCREEN))
                .isWithin(PRECISION).of(23.0);
        assertThat(uid1.getPowerModel(BatteryConsumer.POWER_COMPONENT_SCREEN))
                .isEqualTo(BatteryConsumer.POWER_MODEL_POWER_PROFILE);

        UidBatteryConsumer uid2 = mStatsRule.getUidBatteryConsumer(APP_UID2);
        assertThat(uid2.getUsageDurationMillis(BatteryConsumer.TIME_COMPONENT_SCREEN))
                .isEqualTo(60 * MINUTE_IN_MS);

        // Uid2 took 60 out of the total of 80 min of foreground activity
        // Uid2 charge = 60 / 80 * 92.0 = 69.0 mAh
        assertThat(uid2.getConsumedPower(BatteryConsumer.POWER_COMPONENT_SCREEN))
                .isWithin(PRECISION).of(69.0);
        assertThat(uid2.getPowerModel(BatteryConsumer.POWER_COMPONENT_SCREEN))
                .isEqualTo(BatteryConsumer.POWER_MODEL_POWER_PROFILE);
    }

    private void setProcState(int uid, int procState, boolean resumed, long realtimeMs,
            long uptimeMs) {
        BatteryStatsImpl batteryStats = mStatsRule.getBatteryStats();
        batteryStats.noteUidProcessStateLocked(uid, procState, realtimeMs, uptimeMs);
        if (resumed) {
            batteryStats.noteActivityResumedLocked(uid, realtimeMs, uptimeMs);
        } else {
            batteryStats.noteActivityPausedLocked(uid, realtimeMs, uptimeMs);
        }
    }
}
