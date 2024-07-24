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

package com.android.server.power.stats;

import static com.android.internal.os.PowerProfile.POWER_GROUP_DISPLAY_SCREEN_FULL;
import static com.android.internal.os.PowerProfile.POWER_GROUP_DISPLAY_SCREEN_ON;

import static com.google.common.truth.Truth.assertThat;

import android.app.ActivityManager;
import android.os.BatteryConsumer;
import android.os.Process;
import android.os.UidBatteryConsumer;
import android.platform.test.ravenwood.RavenwoodRule;
import android.view.Display;

import androidx.test.filters.SmallTest;
import androidx.test.runner.AndroidJUnit4;

import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
@SmallTest
@SuppressWarnings("GuardedBy")
public class ScreenPowerCalculatorTest {
    @Rule(order = 0)
    public final RavenwoodRule mRavenwood = new RavenwoodRule.Builder()
            .setProvideMainThread(true)
            .build();

    private static final double PRECISION = 0.00001;
    private static final int APP_UID1 = Process.FIRST_APPLICATION_UID + 42;
    private static final int APP_UID2 = Process.FIRST_APPLICATION_UID + 43;
    private static final long MINUTE_IN_MS = 60 * 1000;
    private static final long MINUTE_IN_US = 60 * 1000 * 1000;

    @Rule(order = 1)
    public final BatteryUsageStatsRule mStatsRule = new BatteryUsageStatsRule()
            .setAveragePowerForOrdinal(POWER_GROUP_DISPLAY_SCREEN_ON, 0, 36.0)
            .setAveragePowerForOrdinal(POWER_GROUP_DISPLAY_SCREEN_FULL, 0, 48.0)
            .setNumDisplays(1);

    @Test
    public void testMeasuredEnergyBasedModel() {
        mStatsRule.initMeasuredEnergyStatsLocked();
        BatteryStatsImpl batteryStats = mStatsRule.getBatteryStats();

        batteryStats.noteScreenStateLocked(0, Display.STATE_ON, 0, 0, 0);
        batteryStats.updateDisplayEnergyConsumerStatsLocked(new long[]{0},
                new int[]{Display.STATE_ON}, 0);
        setProcState(APP_UID1, ActivityManager.PROCESS_STATE_TOP, true,
                0, 0);

        batteryStats.updateDisplayEnergyConsumerStatsLocked(new long[]{200_000_000},
                new int[]{Display.STATE_ON}, 15 * MINUTE_IN_MS);

        setProcState(APP_UID1, ActivityManager.PROCESS_STATE_CACHED_EMPTY, false,
                20 * MINUTE_IN_MS, 20 * MINUTE_IN_MS);

        setProcState(APP_UID2, ActivityManager.PROCESS_STATE_TOP, true,
                20 * MINUTE_IN_MS, 20 * MINUTE_IN_MS);

        batteryStats.updateDisplayEnergyConsumerStatsLocked(new long[]{300_000_000},
                new int[]{Display.STATE_ON}, 60 * MINUTE_IN_MS);

        batteryStats.noteScreenStateLocked(0, Display.STATE_OFF,
                80 * MINUTE_IN_MS, 80 * MINUTE_IN_MS, 80 * MINUTE_IN_MS);
        setProcState(APP_UID2, ActivityManager.PROCESS_STATE_TOP_SLEEPING, false,
                80 * MINUTE_IN_MS, 80 * MINUTE_IN_MS);

        batteryStats.updateDisplayEnergyConsumerStatsLocked(new long[]{100_000_000},
                new int[]{Display.STATE_DOZE}, 120 * MINUTE_IN_MS);

        mStatsRule.setTime(120 * MINUTE_IN_US, 120 * MINUTE_IN_US);

        ScreenPowerCalculator calculator =
                new ScreenPowerCalculator(mStatsRule.getPowerProfile());

        mStatsRule.apply(calculator);

        UidBatteryConsumer uid1 = mStatsRule.getUidBatteryConsumer(APP_UID1);
        assertThat(uid1.getUsageDurationMillis(BatteryConsumer.POWER_COMPONENT_SCREEN))
                .isEqualTo(20 * MINUTE_IN_MS);

        // Uid1 took all of the foreground time during the first Display update.
        // It also ran for 5 out of 45 min during the second Display update:
        // Uid1 charge = 200000000 + 5 / 45 * 300000000 mAs = 64.81 mAh
        assertThat(uid1.getConsumedPower(BatteryConsumer.POWER_COMPONENT_SCREEN))
                .isWithin(PRECISION).of(64.81481);
        assertThat(uid1.getPowerModel(BatteryConsumer.POWER_COMPONENT_SCREEN))
                .isEqualTo(BatteryConsumer.POWER_MODEL_ENERGY_CONSUMPTION);

        UidBatteryConsumer uid2 = mStatsRule.getUidBatteryConsumer(APP_UID2);
        assertThat(uid2.getUsageDurationMillis(BatteryConsumer.POWER_COMPONENT_SCREEN))
                .isEqualTo(60 * MINUTE_IN_MS);

        // Uid2 ran for 40 minutes out of the total 45 min of foreground time during the second
        // Display update and then took all of the time during the third Display update.
        // Uid2 charge = 40 / 45 * 300000000 + 100000000 mAs = 101.85 mAh
        assertThat(uid2.getConsumedPower(BatteryConsumer.POWER_COMPONENT_SCREEN))
                .isWithin(PRECISION).of(101.85185);
        assertThat(uid2.getPowerModel(BatteryConsumer.POWER_COMPONENT_SCREEN))
                .isEqualTo(BatteryConsumer.POWER_MODEL_ENERGY_CONSUMPTION);

        BatteryConsumer deviceConsumer = mStatsRule.getDeviceBatteryConsumer();
        assertThat(deviceConsumer.getUsageDurationMillis(BatteryConsumer.POWER_COMPONENT_SCREEN))
                .isEqualTo(80 * MINUTE_IN_MS);

        // 600000000 uAs * (1 mA / 1000 uA) * (1 h / 3600 s)  = 166.66666 mAh
        assertThat(deviceConsumer.getConsumedPower(BatteryConsumer.POWER_COMPONENT_SCREEN))
                .isWithin(PRECISION).of(166.66666);
        assertThat(deviceConsumer.getPowerModel(BatteryConsumer.POWER_COMPONENT_SCREEN))
                .isEqualTo(BatteryConsumer.POWER_MODEL_ENERGY_CONSUMPTION);

        BatteryConsumer appsConsumer = mStatsRule.getAppsBatteryConsumer();
        assertThat(appsConsumer.getUsageDurationMillis(BatteryConsumer.POWER_COMPONENT_SCREEN))
                .isEqualTo(80 * MINUTE_IN_MS);

        assertThat(appsConsumer.getConsumedPower(BatteryConsumer.POWER_COMPONENT_SCREEN))
                .isWithin(PRECISION).of(166.66666);
        assertThat(appsConsumer.getPowerModel(BatteryConsumer.POWER_COMPONENT_SCREEN))
                .isEqualTo(BatteryConsumer.POWER_MODEL_ENERGY_CONSUMPTION);
    }


    @Test
    public void testMeasuredEnergyBasedModel_multiDisplay() {
        mStatsRule.initMeasuredEnergyStatsLocked()
                .setAveragePowerForOrdinal(POWER_GROUP_DISPLAY_SCREEN_ON, 1, 60.0)
                .setAveragePowerForOrdinal(POWER_GROUP_DISPLAY_SCREEN_FULL, 1, 100.0)
                .setNumDisplays(2);

        BatteryStatsImpl batteryStats = mStatsRule.getBatteryStats();

        final int[] screenStates = new int[]{Display.STATE_ON, Display.STATE_OFF};

        batteryStats.noteScreenStateLocked(0, screenStates[0], 0, 0, 0);
        batteryStats.noteScreenStateLocked(1, screenStates[1], 0, 0, 0);
        batteryStats.noteScreenBrightnessLocked(0, 255, 0, 0);
        setProcState(APP_UID1, ActivityManager.PROCESS_STATE_TOP, true, 0, 0);
        batteryStats.updateDisplayEnergyConsumerStatsLocked(new long[]{300, 400}, screenStates, 0);

        batteryStats.noteScreenBrightnessLocked(0, 100, 5 * MINUTE_IN_MS, 5 * MINUTE_IN_MS);
        batteryStats.noteScreenBrightnessLocked(0, 200, 10 * MINUTE_IN_MS, 10 * MINUTE_IN_MS);

        setProcState(APP_UID1, ActivityManager.PROCESS_STATE_CACHED_EMPTY, false,
                20 * MINUTE_IN_MS, 20 * MINUTE_IN_MS);
        setProcState(APP_UID2, ActivityManager.PROCESS_STATE_TOP, true,
                20 * MINUTE_IN_MS, 20 * MINUTE_IN_MS);

        screenStates[0] = Display.STATE_OFF;
        screenStates[1] = Display.STATE_ON;
        batteryStats.noteScreenStateLocked(0, screenStates[0],
                80 * MINUTE_IN_MS, 80 * MINUTE_IN_MS, 80 * MINUTE_IN_MS);
        batteryStats.noteScreenStateLocked(1, screenStates[1], 80 * MINUTE_IN_MS,
                80 * MINUTE_IN_MS, 80 * MINUTE_IN_MS);
        batteryStats.updateDisplayEnergyConsumerStatsLocked(new long[]{600_000_000, 500},
                screenStates, 80 * MINUTE_IN_MS);

        batteryStats.noteScreenBrightnessLocked(1, 25, 80 * MINUTE_IN_MS, 80 * MINUTE_IN_MS);
        batteryStats.noteScreenBrightnessLocked(1, 250, 86 * MINUTE_IN_MS, 86 * MINUTE_IN_MS);
        batteryStats.noteScreenBrightnessLocked(1, 75, 98 * MINUTE_IN_MS, 98 * MINUTE_IN_MS);

        screenStates[1] = Display.STATE_OFF;
        batteryStats.noteScreenStateLocked(1, screenStates[1], 110 * MINUTE_IN_MS,
                110 * MINUTE_IN_MS, 110 * MINUTE_IN_MS);
        batteryStats.updateDisplayEnergyConsumerStatsLocked(new long[]{700, 800_000_000},
                screenStates, 110 * MINUTE_IN_MS);

        setProcState(APP_UID2, ActivityManager.PROCESS_STATE_TOP_SLEEPING, false,
                110 * MINUTE_IN_MS, 110 * MINUTE_IN_MS);

        mStatsRule.setTime(120 * MINUTE_IN_US, 120 * MINUTE_IN_US);

        ScreenPowerCalculator calculator =
                new ScreenPowerCalculator(mStatsRule.getPowerProfile());

        mStatsRule.apply(calculator);

        BatteryConsumer deviceConsumer = mStatsRule.getDeviceBatteryConsumer();
        assertThat(deviceConsumer.getUsageDurationMillis(BatteryConsumer.POWER_COMPONENT_SCREEN))
                .isEqualTo(110 * MINUTE_IN_MS);
        // (600000000 + 800000000) uAs * (1 mA / 1000 uA) * (1 h / 3600 s)  = 166.66666 mAh
        assertThat(deviceConsumer.getConsumedPower(BatteryConsumer.POWER_COMPONENT_SCREEN))
                .isWithin(PRECISION).of(388.88888);
        assertThat(deviceConsumer.getPowerModel(BatteryConsumer.POWER_COMPONENT_SCREEN))
                .isEqualTo(BatteryConsumer.POWER_MODEL_ENERGY_CONSUMPTION);

        UidBatteryConsumer uid1 = mStatsRule.getUidBatteryConsumer(APP_UID1);
        assertThat(uid1.getUsageDurationMillis(BatteryConsumer.POWER_COMPONENT_SCREEN))
                .isEqualTo(20 * MINUTE_IN_MS);

        // Uid1 ran for 20 out of 80 min during the first Display update.
        // It also ran for 5 out of 45 min during the second Display update:
        // Uid1 charge = 20 / 80 * 600000000 mAs = 41.66666 mAh
        assertThat(uid1.getConsumedPower(BatteryConsumer.POWER_COMPONENT_SCREEN))
                .isWithin(PRECISION).of(41.66666);
        assertThat(uid1.getPowerModel(BatteryConsumer.POWER_COMPONENT_SCREEN))
                .isEqualTo(BatteryConsumer.POWER_MODEL_ENERGY_CONSUMPTION);

        UidBatteryConsumer uid2 = mStatsRule.getUidBatteryConsumer(APP_UID2);
        assertThat(uid2.getUsageDurationMillis(BatteryConsumer.POWER_COMPONENT_SCREEN))
                .isEqualTo(90 * MINUTE_IN_MS);

        // Uid2 ran for 60 out of 80 min during the first Display update.
        // It also ran for all of the second Display update:
        // Uid1 charge = 60 / 80 * 600000000 + 800000000 mAs = 347.22222 mAh
        assertThat(uid2.getConsumedPower(BatteryConsumer.POWER_COMPONENT_SCREEN))
                .isWithin(PRECISION).of(347.22222);
        assertThat(uid2.getPowerModel(BatteryConsumer.POWER_COMPONENT_SCREEN))
                .isEqualTo(BatteryConsumer.POWER_MODEL_ENERGY_CONSUMPTION);

        BatteryConsumer appsConsumer = mStatsRule.getAppsBatteryConsumer();
        assertThat(appsConsumer.getUsageDurationMillis(BatteryConsumer.POWER_COMPONENT_SCREEN))
                .isEqualTo(110 * MINUTE_IN_MS);
        assertThat(appsConsumer.getConsumedPower(BatteryConsumer.POWER_COMPONENT_SCREEN))
                .isWithin(PRECISION).of(388.88888);
        assertThat(appsConsumer.getPowerModel(BatteryConsumer.POWER_COMPONENT_SCREEN))
                .isEqualTo(BatteryConsumer.POWER_MODEL_ENERGY_CONSUMPTION);

    }

    @Test
    public void testPowerProfileBasedModel() {
        BatteryStatsImpl batteryStats = mStatsRule.getBatteryStats();

        batteryStats.noteScreenStateLocked(0, Display.STATE_ON, 0, 0, 0);
        batteryStats.noteScreenBrightnessLocked(0, 255, 0, 0);
        setProcState(APP_UID1, ActivityManager.PROCESS_STATE_TOP, true,
                0, 0);

        batteryStats.noteScreenBrightnessLocked(0, 100, 5 * MINUTE_IN_MS, 5 * MINUTE_IN_MS);
        batteryStats.noteScreenBrightnessLocked(0, 200, 10 * MINUTE_IN_MS, 10 * MINUTE_IN_MS);

        setProcState(APP_UID1, ActivityManager.PROCESS_STATE_CACHED_EMPTY, false,
                20 * MINUTE_IN_MS, 20 * MINUTE_IN_MS);
        setProcState(APP_UID2, ActivityManager.PROCESS_STATE_TOP, true,
                20 * MINUTE_IN_MS, 20 * MINUTE_IN_MS);

        batteryStats.noteScreenStateLocked(0, Display.STATE_OFF,
                80 * MINUTE_IN_MS, 80 * MINUTE_IN_MS, 80 * MINUTE_IN_MS);
        setProcState(APP_UID2, ActivityManager.PROCESS_STATE_TOP_SLEEPING, false,
                80 * MINUTE_IN_MS, 80 * MINUTE_IN_MS);

        mStatsRule.setTime(120 * MINUTE_IN_US, 120 * MINUTE_IN_US);

        ScreenPowerCalculator calculator =
                new ScreenPowerCalculator(mStatsRule.getPowerProfile());

        mStatsRule.apply(BatteryUsageStatsRule.POWER_PROFILE_MODEL_ONLY, calculator);

        UidBatteryConsumer uid1 = mStatsRule.getUidBatteryConsumer(APP_UID1);
        assertThat(uid1.getUsageDurationMillis(BatteryConsumer.POWER_COMPONENT_SCREEN))
                .isEqualTo(20 * MINUTE_IN_MS);

        // Uid1 took 20 out of the total of 80 min of foreground activity
        // Uid1 charge = 20 / 80 * 92.0 = 23.0 mAh
        assertThat(uid1.getConsumedPower(BatteryConsumer.POWER_COMPONENT_SCREEN))
                .isWithin(PRECISION).of(23.0);
        assertThat(uid1.getPowerModel(BatteryConsumer.POWER_COMPONENT_SCREEN))
                .isEqualTo(BatteryConsumer.POWER_MODEL_POWER_PROFILE);

        UidBatteryConsumer uid2 = mStatsRule.getUidBatteryConsumer(APP_UID2);
        assertThat(uid2.getUsageDurationMillis(BatteryConsumer.POWER_COMPONENT_SCREEN))
                .isEqualTo(60 * MINUTE_IN_MS);

        // Uid2 took 60 out of the total of 80 min of foreground activity
        // Uid2 charge = 60 / 80 * 92.0 = 69.0 mAh
        assertThat(uid2.getConsumedPower(BatteryConsumer.POWER_COMPONENT_SCREEN))
                .isWithin(PRECISION).of(69.0);
        assertThat(uid2.getPowerModel(BatteryConsumer.POWER_COMPONENT_SCREEN))
                .isEqualTo(BatteryConsumer.POWER_MODEL_POWER_PROFILE);

        BatteryConsumer deviceConsumer = mStatsRule.getDeviceBatteryConsumer();
        assertThat(deviceConsumer.getUsageDurationMillis(BatteryConsumer.POWER_COMPONENT_SCREEN))
                .isEqualTo(80 * MINUTE_IN_MS);
        assertThat(deviceConsumer.getConsumedPower(BatteryConsumer.POWER_COMPONENT_SCREEN))
                .isWithin(PRECISION).of(92);
        assertThat(deviceConsumer.getPowerModel(BatteryConsumer.POWER_COMPONENT_SCREEN))
                .isEqualTo(BatteryConsumer.POWER_MODEL_POWER_PROFILE);

        BatteryConsumer appsConsumer = mStatsRule.getAppsBatteryConsumer();
        assertThat(appsConsumer.getUsageDurationMillis(BatteryConsumer.POWER_COMPONENT_SCREEN))
                .isEqualTo(80 * MINUTE_IN_MS);
        assertThat(appsConsumer.getConsumedPower(BatteryConsumer.POWER_COMPONENT_SCREEN))
                .isWithin(PRECISION).of(92);
        assertThat(appsConsumer.getPowerModel(BatteryConsumer.POWER_COMPONENT_SCREEN))
                .isEqualTo(BatteryConsumer.POWER_MODEL_POWER_PROFILE);
    }


    @Test
    public void testPowerProfileBasedModel_multiDisplay() {
        mStatsRule.setAveragePowerForOrdinal(POWER_GROUP_DISPLAY_SCREEN_ON, 1, 60.0)
                .setAveragePowerForOrdinal(POWER_GROUP_DISPLAY_SCREEN_FULL, 1, 100.0)
                .setNumDisplays(2);

        BatteryStatsImpl batteryStats = mStatsRule.getBatteryStats();

        batteryStats.noteScreenStateLocked(0, Display.STATE_ON, 0, 0, 0);
        batteryStats.noteScreenStateLocked(1, Display.STATE_OFF, 0, 0, 0);
        batteryStats.noteScreenBrightnessLocked(0, 255, 0, 0);
        setProcState(APP_UID1, ActivityManager.PROCESS_STATE_TOP, true,
                0, 0);

        batteryStats.noteScreenBrightnessLocked(0, 100, 5 * MINUTE_IN_MS, 5 * MINUTE_IN_MS);
        batteryStats.noteScreenBrightnessLocked(0, 200, 10 * MINUTE_IN_MS, 10 * MINUTE_IN_MS);

        setProcState(APP_UID1, ActivityManager.PROCESS_STATE_CACHED_EMPTY, false,
                20 * MINUTE_IN_MS, 20 * MINUTE_IN_MS);
        setProcState(APP_UID2, ActivityManager.PROCESS_STATE_TOP, true,
                20 * MINUTE_IN_MS, 20 * MINUTE_IN_MS);

        batteryStats.noteScreenStateLocked(0, Display.STATE_OFF,
                80 * MINUTE_IN_MS, 80 * MINUTE_IN_MS, 80 * MINUTE_IN_MS);
        batteryStats.noteScreenStateLocked(1, Display.STATE_ON, 80 * MINUTE_IN_MS,
                80 * MINUTE_IN_MS, 80 * MINUTE_IN_MS);
        batteryStats.noteScreenBrightnessLocked(1, 20, 80 * MINUTE_IN_MS, 80 * MINUTE_IN_MS);

        batteryStats.noteScreenBrightnessLocked(1, 250, 86 * MINUTE_IN_MS, 86 * MINUTE_IN_MS);
        batteryStats.noteScreenBrightnessLocked(1, 75, 98 * MINUTE_IN_MS, 98 * MINUTE_IN_MS);
        batteryStats.noteScreenStateLocked(1, Display.STATE_OFF, 110 * MINUTE_IN_MS,
                110 * MINUTE_IN_MS, 110 * MINUTE_IN_MS);

        setProcState(APP_UID2, ActivityManager.PROCESS_STATE_TOP_SLEEPING, false,
                110 * MINUTE_IN_MS, 110 * MINUTE_IN_MS);

        mStatsRule.setTime(120 * MINUTE_IN_US, 120 * MINUTE_IN_US);
        ScreenPowerCalculator calculator =
                new ScreenPowerCalculator(mStatsRule.getPowerProfile());

        mStatsRule.apply(BatteryUsageStatsRule.POWER_PROFILE_MODEL_ONLY, calculator);

        BatteryConsumer deviceConsumer = mStatsRule.getDeviceBatteryConsumer();
        assertThat(deviceConsumer.getUsageDurationMillis(BatteryConsumer.POWER_COMPONENT_SCREEN))
                .isEqualTo(110 * MINUTE_IN_MS);
        // First display consumed 92 mAh.
        // Second display ran for 0.5 hours at a base drain rate of 60 mA.
        // 6 minutes (0.1 hours) spent in the first brightness level which drains an extra 10 mA.
        // 12 minutes (0.2 hours) spent in the fifth brightness level which drains an extra 90 mA.
        // 12 minutes (0.2 hours) spent in the second brightness level which drains an extra 30 mA.
        // 92 + 60 * 0.5 + 10 * 0.1 + 90 * 0.2 + 30 * 0.2 = 147
        assertThat(deviceConsumer.getConsumedPower(BatteryConsumer.POWER_COMPONENT_SCREEN))
                .isWithin(PRECISION).of(147);
        assertThat(deviceConsumer.getPowerModel(BatteryConsumer.POWER_COMPONENT_SCREEN))
                .isEqualTo(BatteryConsumer.POWER_MODEL_POWER_PROFILE);

        UidBatteryConsumer uid1 = mStatsRule.getUidBatteryConsumer(APP_UID1);
        assertThat(uid1.getUsageDurationMillis(BatteryConsumer.POWER_COMPONENT_SCREEN))
                .isEqualTo(20 * MINUTE_IN_MS);

        // Uid1 took 20 out of the total of 110 min of foreground activity
        // Uid1 charge = 20 / 110 * 147.0 = 23.0 mAh
        assertThat(uid1.getConsumedPower(BatteryConsumer.POWER_COMPONENT_SCREEN))
                .isWithin(PRECISION).of(26.72727);
        assertThat(uid1.getPowerModel(BatteryConsumer.POWER_COMPONENT_SCREEN))
                .isEqualTo(BatteryConsumer.POWER_MODEL_POWER_PROFILE);

        UidBatteryConsumer uid2 = mStatsRule.getUidBatteryConsumer(APP_UID2);
        assertThat(uid2.getUsageDurationMillis(BatteryConsumer.POWER_COMPONENT_SCREEN))
                .isEqualTo(90 * MINUTE_IN_MS);

        // Uid2 took 90 out of the total of 110 min of foreground activity
        // Uid2 charge = 90 / 110 * 92.0 = 69.0 mAh
        assertThat(uid2.getConsumedPower(BatteryConsumer.POWER_COMPONENT_SCREEN))
                .isWithin(PRECISION).of(120.272727);
        assertThat(uid2.getPowerModel(BatteryConsumer.POWER_COMPONENT_SCREEN))
                .isEqualTo(BatteryConsumer.POWER_MODEL_POWER_PROFILE);

        BatteryConsumer appsConsumer = mStatsRule.getAppsBatteryConsumer();
        assertThat(appsConsumer.getUsageDurationMillis(BatteryConsumer.POWER_COMPONENT_SCREEN))
                .isEqualTo(110 * MINUTE_IN_MS);
        assertThat(appsConsumer.getConsumedPower(BatteryConsumer.POWER_COMPONENT_SCREEN))
                .isWithin(PRECISION).of(147);
        assertThat(appsConsumer.getPowerModel(BatteryConsumer.POWER_COMPONENT_SCREEN))
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
