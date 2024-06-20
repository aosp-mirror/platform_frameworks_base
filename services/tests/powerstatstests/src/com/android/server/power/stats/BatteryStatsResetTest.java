/*
 * Copyright (C) 2023 The Android Open Source Project
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

import android.content.Context;
import android.os.BatteryManager;
import android.platform.test.ravenwood.RavenwoodRule;

import androidx.test.filters.SmallTest;
import androidx.test.runner.AndroidJUnit4;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.IOException;
import java.nio.file.Files;

@SmallTest
@RunWith(AndroidJUnit4.class)
public class BatteryStatsResetTest {

    @Rule(order = 0)
    public final RavenwoodRule mRavenwood = new RavenwoodRule.Builder()
            .setProvideMainThread(true)
            .build();

    private static final int BATTERY_NOMINAL_VOLTAGE_MV = 3700;
    private static final int BATTERY_CAPACITY_UAH = 4_000_000;
    private static final int BATTERY_CHARGE_RATE_SECONDS_PER_LEVEL = 100;

    private MockClock mMockClock;
    private BatteryStatsImpl.BatteryStatsConfig mConfig;
    private MockBatteryStatsImpl mBatteryStatsImpl;

    /**
     * Battery status. Must be one of the following:
     * {@link BatteryManager#BATTERY_STATUS_UNKNOWN}
     * {@link BatteryManager#BATTERY_STATUS_CHARGING}
     * {@link BatteryManager#BATTERY_STATUS_DISCHARGING}
     * {@link BatteryManager#BATTERY_STATUS_NOT_CHARGING}
     * {@link BatteryManager#BATTERY_STATUS_FULL}
     */
    private int mBatteryStatus;
    /**
     * Battery health. Must be one of the following:
     * {@link BatteryManager#BATTERY_HEALTH_UNKNOWN}
     * {@link BatteryManager#BATTERY_HEALTH_GOOD}
     * {@link BatteryManager#BATTERY_HEALTH_OVERHEAT}
     * {@link BatteryManager#BATTERY_HEALTH_DEAD}
     * {@link BatteryManager#BATTERY_HEALTH_OVER_VOLTAGE}
     * {@link BatteryManager#BATTERY_HEALTH_UNSPECIFIED_FAILURE}
     * {@link BatteryManager#BATTERY_HEALTH_COLD}
     */
    private int mBatteryHealth;
    /**
     * Battery plug type. Can be the union of any number of the following flags:
     * {@link BatteryManager#BATTERY_PLUGGED_AC}
     * {@link BatteryManager#BATTERY_PLUGGED_USB}
     * {@link BatteryManager#BATTERY_PLUGGED_WIRELESS}
     * {@link BatteryManager#BATTERY_PLUGGED_DOCK}
     *
     * Zero means the device is unplugged.
     */
    private int mBatteryPlugType;
    private int mBatteryLevel;
    private int mBatteryTemp;
    private int mBatteryVoltageMv;
    private int mBatteryChargeUah;
    private int mBatteryChargeFullUah;
    private long mBatteryChargeTimeToFullSeconds;

    @Before
    public void setUp() throws IOException {
        mConfig = mock(BatteryStatsImpl.BatteryStatsConfig.class);
        mMockClock = new MockClock();
        mBatteryStatsImpl = new MockBatteryStatsImpl(mConfig, mMockClock,
                Files.createTempDirectory("BatteryStatsResetTest").toFile());
        mBatteryStatsImpl.onSystemReady(mock(Context.class));

        // Set up the battery state. Start off with a fully charged plugged in battery.
        mBatteryStatus = BatteryManager.BATTERY_STATUS_FULL;
        mBatteryHealth = BatteryManager.BATTERY_HEALTH_GOOD;
        mBatteryPlugType = BatteryManager.BATTERY_PLUGGED_USB;
        mBatteryLevel = 100;
        mBatteryTemp = 70; // Arbitrary reasonable temperature.
        mBatteryVoltageMv = BATTERY_NOMINAL_VOLTAGE_MV;
        mBatteryChargeUah = BATTERY_CAPACITY_UAH;
        mBatteryChargeFullUah = BATTERY_CAPACITY_UAH;
        mBatteryChargeTimeToFullSeconds = 0;
    }

    @Test
    public void testResetOnUnplug_highBatteryLevel() {
        when(mConfig.shouldResetOnUnplugHighBatteryLevel()).thenReturn(true);

        long expectedResetTimeUs = 0;

        unplugBattery();
        dischargeToLevel(60);

        plugBattery(BatteryManager.BATTERY_PLUGGED_USB);
        chargeToLevel(80);
        unplugBattery();
        // Reset should not occur until battery level above 90.
        assertThat(mBatteryStatsImpl.getStatsStartRealtime()).isEqualTo(expectedResetTimeUs);

        plugBattery(BatteryManager.BATTERY_PLUGGED_USB);
        chargeToLevel(95);
        // Reset should not occur until unplug.
        assertThat(mBatteryStatsImpl.getStatsStartRealtime()).isEqualTo(expectedResetTimeUs);

        unplugBattery();
        // Reset should occur on unplug now that battery level is high (>=90)
        expectedResetTimeUs = mMockClock.elapsedRealtime() * 1000;
        assertThat(mBatteryStatsImpl.getStatsStartRealtime()).isEqualTo(expectedResetTimeUs);

        // disable high battery level reset on unplug.
        when(mConfig.shouldResetOnUnplugHighBatteryLevel()).thenReturn(false);

        dischargeToLevel(60);

        plugBattery(BatteryManager.BATTERY_PLUGGED_USB);
        chargeToLevel(95);
        unplugBattery();
        // Reset should not occur since the high battery level logic has been disabled.
        assertThat(mBatteryStatsImpl.getStatsStartRealtime()).isEqualTo(expectedResetTimeUs);
    }

    @Test
    public void testResetOnUnplug_significantCharge() {
        when(mConfig.shouldResetOnUnplugAfterSignificantCharge()).thenReturn(true);
        long expectedResetTimeUs = 0;

        unplugBattery();
        // Battery level dropped below 20%.
        dischargeToLevel(15);

        plugBattery(BatteryManager.BATTERY_PLUGGED_USB);
        chargeToLevel(50);
        unplugBattery();
        // Reset should not occur until battery level above 80
        assertThat(mBatteryStatsImpl.getStatsStartRealtime()).isEqualTo(expectedResetTimeUs);

        plugBattery(BatteryManager.BATTERY_PLUGGED_USB);
        chargeToLevel(85);
        unplugBattery();
        // Reset should not occur because the charge session did not go from 20% to 80%
        assertThat(mBatteryStatsImpl.getStatsStartRealtime()).isEqualTo(expectedResetTimeUs);

        // Battery level dropped below 20%.
        dischargeToLevel(15);

        plugBattery(BatteryManager.BATTERY_PLUGGED_USB);
        chargeToLevel(85);
        unplugBattery();
        // Reset should occur after significant charge amount.
        expectedResetTimeUs = mMockClock.elapsedRealtime() * 1000;
        assertThat(mBatteryStatsImpl.getStatsStartRealtime()).isEqualTo(expectedResetTimeUs);

        // disable reset on unplug after significant charge.
        when(mConfig.shouldResetOnUnplugAfterSignificantCharge()).thenReturn(false);

        // Battery level dropped below 20%.
        dischargeToLevel(15);

        plugBattery(BatteryManager.BATTERY_PLUGGED_USB);
        chargeToLevel(85);
        unplugBattery();
        // Reset should not occur after significant charge amount.
        assertThat(mBatteryStatsImpl.getStatsStartRealtime()).isEqualTo(expectedResetTimeUs);
    }

    @Test
    public void testResetOnUnplug_manyPartialCharges() {
        long expectedResetTimeUs = 0;

        unplugBattery();
        // Cumulative battery discharged: 60%.
        dischargeToLevel(40);

        plugBattery(BatteryManager.BATTERY_PLUGGED_USB);
        chargeToLevel(80);
        unplugBattery();
        // Reset should not occur
        assertThat(mBatteryStatsImpl.getStatsStartRealtime()).isEqualTo(expectedResetTimeUs);

        // Cumulative battery discharged: 100%.
        dischargeToLevel(40);

        plugBattery(BatteryManager.BATTERY_PLUGGED_USB);
        chargeToLevel(80);
        unplugBattery();
        // Reset should not occur
        assertThat(mBatteryStatsImpl.getStatsStartRealtime()).isEqualTo(expectedResetTimeUs);

        // Cumulative battery discharged: 140%.
        dischargeToLevel(40);

        plugBattery(BatteryManager.BATTERY_PLUGGED_USB);
        chargeToLevel(80);
        unplugBattery();
        // Reset should not occur
        assertThat(mBatteryStatsImpl.getStatsStartRealtime()).isEqualTo(expectedResetTimeUs);

        // Cumulative battery discharged: 180%.
        dischargeToLevel(40);

        plugBattery(BatteryManager.BATTERY_PLUGGED_USB);
        chargeToLevel(80);
        unplugBattery();
        // Reset should not occur
        assertThat(mBatteryStatsImpl.getStatsStartRealtime()).isEqualTo(expectedResetTimeUs);

        // Cumulative battery discharged: 220%.
        dischargeToLevel(40);

        plugBattery(BatteryManager.BATTERY_PLUGGED_USB);
        chargeToLevel(80);
        unplugBattery();
        // Should reset after >200% of cumulative battery discharge
        expectedResetTimeUs = mMockClock.elapsedRealtime() * 1000;
        assertThat(mBatteryStatsImpl.getStatsStartRealtime()).isEqualTo(expectedResetTimeUs);
    }

    @Test
    public void testResetWhilePluggedIn_longPlugIn() {
        // disable high battery level reset on unplug.
        when(mConfig.shouldResetOnUnplugHighBatteryLevel()).thenReturn(false);
        when(mConfig.shouldResetOnUnplugAfterSignificantCharge()).thenReturn(false);

        long expectedResetTimeUs = 0;

        plugBattery(BatteryManager.BATTERY_PLUGGED_USB);
        mBatteryStatsImpl.maybeResetWhilePluggedInLocked();
        // Reset should not occur
        assertThat(mBatteryStatsImpl.getStatsStartRealtime()).isEqualTo(expectedResetTimeUs);

        // Increment time a day
        incTimeMs(24L * 60L * 60L * 1000L);
        mBatteryStatsImpl.maybeResetWhilePluggedInLocked();
        // Reset should still not occur
        assertThat(mBatteryStatsImpl.getStatsStartRealtime()).isEqualTo(expectedResetTimeUs);

        // Increment time a day
        incTimeMs(24L * 60L * 60L * 1000L);
        mBatteryStatsImpl.maybeResetWhilePluggedInLocked();
        // Reset 47 hour threshold crossed, reset should occur.
        expectedResetTimeUs = mMockClock.elapsedRealtime() * 1000;
        assertThat(mBatteryStatsImpl.getStatsStartRealtime()).isEqualTo(expectedResetTimeUs);

        // Increment time a day
        incTimeMs(24L * 60L * 60L * 1000L);
        mBatteryStatsImpl.maybeResetWhilePluggedInLocked();
        // Reset should not occur
        assertThat(mBatteryStatsImpl.getStatsStartRealtime()).isEqualTo(expectedResetTimeUs);

        // Increment time a day
        incTimeMs(24L * 60L * 60L * 1000L);
        mBatteryStatsImpl.maybeResetWhilePluggedInLocked();
        // Reset another 47 hour threshold crossed, reset should occur.
        expectedResetTimeUs = mMockClock.elapsedRealtime() * 1000;
        assertThat(mBatteryStatsImpl.getStatsStartRealtime()).isEqualTo(expectedResetTimeUs);

        // Increment time a day
        incTimeMs(24L * 60L * 60L * 1000L);
        mBatteryStatsImpl.maybeResetWhilePluggedInLocked();
        // Reset should not occur
        assertThat(mBatteryStatsImpl.getStatsStartRealtime()).isEqualTo(expectedResetTimeUs);

        unplugBattery();
        plugBattery(BatteryManager.BATTERY_PLUGGED_USB);

        // Increment time a day
        incTimeMs(24L * 60L * 60L * 1000L);
        mBatteryStatsImpl.maybeResetWhilePluggedInLocked();
        // Reset should not occur, since unplug occurred recently.
        assertThat(mBatteryStatsImpl.getStatsStartRealtime()).isEqualTo(expectedResetTimeUs);

        // Increment time a day
        incTimeMs(24L * 60L * 60L * 1000L);
        mBatteryStatsImpl.maybeResetWhilePluggedInLocked();
        // Reset another 47 hour threshold crossed, reset should occur.
        expectedResetTimeUs = mMockClock.elapsedRealtime() * 1000;
        assertThat(mBatteryStatsImpl.getStatsStartRealtime()).isEqualTo(expectedResetTimeUs);
    }

    private void dischargeToLevel(int targetLevel) {
        mBatteryStatus = BatteryManager.BATTERY_STATUS_DISCHARGING;
        for (int level = mBatteryLevel - 1; level >= targetLevel; level--) {
            prepareBatteryLevel(level);
            incTimeMs(5000); // Arbitrary discharge rate.
            updateBatteryState();
        }
    }

    private void chargeToLevel(int targetLevel) {
        mBatteryStatus = BatteryManager.BATTERY_STATUS_CHARGING;
        for (int level = mBatteryLevel + 1; level <= targetLevel; level++) {
            if (level >= 100) mBatteryStatus = BatteryManager.BATTERY_STATUS_FULL;
            prepareBatteryLevel(level);
            incTimeMs(BATTERY_CHARGE_RATE_SECONDS_PER_LEVEL * 1000);
            updateBatteryState();
        }
    }

    private void unplugBattery() {
        mBatteryPlugType = 0;
        updateBatteryState();
    }

    private void plugBattery(int type) {
        mBatteryPlugType |= type;
        updateBatteryState();
    }

    private void prepareBatteryLevel(int level) {
        mBatteryLevel = level;
        mBatteryChargeUah = mBatteryLevel * mBatteryChargeFullUah / 100;
        mBatteryChargeTimeToFullSeconds =
                (100 - mBatteryLevel) * BATTERY_CHARGE_RATE_SECONDS_PER_LEVEL;
    }

    private void incTimeMs(long milliseconds) {
        mMockClock.realtime += milliseconds;
        mMockClock.uptime += milliseconds / 2; // Arbitrary slower uptime accumulation
        mMockClock.currentTime += milliseconds;
    }

    private void updateBatteryState() {
        mBatteryStatsImpl.setBatteryStateLocked(mBatteryStatus, mBatteryHealth, mBatteryPlugType,
                mBatteryLevel, mBatteryTemp, mBatteryVoltageMv, mBatteryChargeUah,
                mBatteryChargeFullUah, mBatteryChargeTimeToFullSeconds,
                mMockClock.elapsedRealtime(), mMockClock.uptimeMillis(),
                mMockClock.currentTimeMillis());
    }
}
