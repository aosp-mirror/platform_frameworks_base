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

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import android.annotation.Nullable;
import android.bluetooth.BluetoothActivityEnergyInfo;
import android.bluetooth.UidTraffic;
import android.os.BatteryConsumer;
import android.os.BatteryStats;
import android.os.BatteryUsageStatsQuery;
import android.os.Parcel;
import android.os.Process;
import android.os.UidBatteryConsumer;
import android.os.WorkSource;
import android.platform.test.ravenwood.RavenwoodRule;

import androidx.test.filters.SmallTest;
import androidx.test.runner.AndroidJUnit4;

import com.android.internal.os.PowerProfile;

import com.google.common.collect.ImmutableList;

import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.List;

@RunWith(AndroidJUnit4.class)
@SmallTest
@SuppressWarnings("GuardedBy")
public class BluetoothPowerCalculatorTest {
    @Rule(order = 0)
    public final RavenwoodRule mRavenwood = new RavenwoodRule.Builder()
            .setProvideMainThread(true)
            .build();

    private static final double PRECISION = 0.00001;
    private static final int APP_UID = Process.FIRST_APPLICATION_UID + 42;

    @Rule(order = 1)
    public final BatteryUsageStatsRule mStatsRule = new BatteryUsageStatsRule()
            .setAveragePower(PowerProfile.POWER_BLUETOOTH_CONTROLLER_IDLE, 10.0)
            .setAveragePower(PowerProfile.POWER_BLUETOOTH_CONTROLLER_RX, 50.0)
            .setAveragePower(PowerProfile.POWER_BLUETOOTH_CONTROLLER_TX, 100.0)
            .setAveragePower(PowerProfile.POWER_BLUETOOTH_CONTROLLER_OPERATING_VOLTAGE, 3700);

    @Test
    public void testTimerBasedModel() {
        BatteryStatsImpl batteryStats = mStatsRule.getBatteryStats();

        final WorkSource ws = new WorkSource(APP_UID);
        batteryStats.noteBluetoothScanStartedFromSourceLocked(ws, false, 0, 0);
        batteryStats.noteBluetoothScanStoppedFromSourceLocked(ws, false, 1000, 1000);

        setupBluetoothEnergyInfo(0, BatteryStats.POWER_DATA_UNAVAILABLE);

        BluetoothPowerCalculator calculator =
                new BluetoothPowerCalculator(mStatsRule.getPowerProfile());

        mStatsRule.apply(BatteryUsageStatsRule.POWER_PROFILE_MODEL_ONLY, calculator);

        assertBluetoothPowerAndDuration(
                mStatsRule.getUidBatteryConsumer(Process.BLUETOOTH_UID),
                0.06944, 3000, BatteryConsumer.POWER_MODEL_POWER_PROFILE);
        assertBluetoothPowerAndDuration(
                mStatsRule.getUidBatteryConsumer(APP_UID),
                0.19444, 9000, BatteryConsumer.POWER_MODEL_POWER_PROFILE);
        assertBluetoothPowerAndDuration(
                mStatsRule.getDeviceBatteryConsumer(),
                0.26388, 12000, BatteryConsumer.POWER_MODEL_POWER_PROFILE);
        assertBluetoothPowerAndDuration(
                mStatsRule.getAppsBatteryConsumer(),
                0.26388, 12000, BatteryConsumer.POWER_MODEL_POWER_PROFILE);
    }

    @Test
    public void testTimerBasedModel_byProcessState() {
        mStatsRule.setTime(1000, 1000);

        BatteryStatsImpl batteryStats = mStatsRule.getBatteryStats();

        BatteryStatsImpl.Uid uid = batteryStats.getUidStatsLocked(APP_UID);
        uid.setProcessStateForTest(
                BatteryStats.Uid.PROCESS_STATE_FOREGROUND, 1000);


        List<UidTraffic> trafficList1 = ImmutableList.of(
                createUidTraffic(Process.BLUETOOTH_UID, 1000, 2000),
                createUidTraffic(APP_UID, 3000, 4000));
        BluetoothActivityEnergyInfo info1 = createBtEnergyInfo(2000,
                BluetoothActivityEnergyInfo.BT_STACK_STATE_STATE_ACTIVE, 1000, 2000, 3000, 4000,
                trafficList1);

        batteryStats.updateBluetoothStateLocked(info1,
                0/*1_000_000*/, 2000, 2000);

        uid.setProcessStateForTest(
                BatteryStats.Uid.PROCESS_STATE_BACKGROUND, 3000);


        List<UidTraffic> trafficList2 = ImmutableList.of(
                createUidTraffic(Process.BLUETOOTH_UID, 5000, 6000),
                createUidTraffic(APP_UID, 7000, 8000));
        BluetoothActivityEnergyInfo info2 = createBtEnergyInfo(4000,
                BluetoothActivityEnergyInfo.BT_STACK_STATE_STATE_ACTIVE, 5000, 6000, 7000, 8000,
                trafficList2);


        batteryStats.updateBluetoothStateLocked(info2,
                0 /*5_000_000 */, 4000, 4000);

        BluetoothPowerCalculator calculator =
                new BluetoothPowerCalculator(mStatsRule.getPowerProfile());

        mStatsRule.apply(new BatteryUsageStatsQuery.Builder()
                .powerProfileModeledOnly()
                .includePowerModels()
                .includeProcessStateData()
                .build(), calculator);

        UidBatteryConsumer uidConsumer = mStatsRule.getUidBatteryConsumer(APP_UID);
        assertThat(uidConsumer.getUsageDurationMillis(BatteryConsumer.POWER_COMPONENT_BLUETOOTH))
                .isEqualTo(6166);
        assertThat(uidConsumer.getConsumedPower(BatteryConsumer.POWER_COMPONENT_BLUETOOTH))
                .isWithin(PRECISION).of(0.1226666);
        assertThat(uidConsumer.getPowerModel(BatteryConsumer.POWER_COMPONENT_BLUETOOTH))
                .isEqualTo(BatteryConsumer.POWER_MODEL_POWER_PROFILE);

        final BatteryConsumer.Key foreground = uidConsumer.getKey(
                BatteryConsumer.POWER_COMPONENT_BLUETOOTH,
                BatteryConsumer.PROCESS_STATE_FOREGROUND);
        final BatteryConsumer.Key background = uidConsumer.getKey(
                BatteryConsumer.POWER_COMPONENT_BLUETOOTH,
                BatteryConsumer.PROCESS_STATE_BACKGROUND);
        final BatteryConsumer.Key fgs = uidConsumer.getKey(
                BatteryConsumer.POWER_COMPONENT_BLUETOOTH,
                BatteryConsumer.PROCESS_STATE_FOREGROUND_SERVICE);
        final BatteryConsumer.Key cached = uidConsumer.getKey(
                BatteryConsumer.POWER_COMPONENT_BLUETOOTH,
                BatteryConsumer.PROCESS_STATE_CACHED);

        assertThat(uidConsumer.getConsumedPower(foreground)).isWithin(PRECISION).of(0.081);
        assertThat(uidConsumer.getConsumedPower(background)).isWithin(PRECISION).of(0.0416666);
        assertThat(uidConsumer.getConsumedPower(fgs)).isWithin(PRECISION).of(0);
        assertThat(uidConsumer.getConsumedPower(cached)).isWithin(PRECISION).of(0);
    }

    @Test
    public void testReportedEnergyBasedModel() {
        setupBluetoothEnergyInfo(4000000, BatteryStats.POWER_DATA_UNAVAILABLE);

        BluetoothPowerCalculator calculator =
                new BluetoothPowerCalculator(mStatsRule.getPowerProfile());

        mStatsRule.apply(new BatteryUsageStatsQuery.Builder().includePowerModels().build(),
                calculator);

        assertBluetoothPowerAndDuration(
                mStatsRule.getUidBatteryConsumer(Process.BLUETOOTH_UID),
                0.08216, 3583, BatteryConsumer.POWER_MODEL_POWER_PROFILE);
        assertBluetoothPowerAndDuration(
                mStatsRule.getUidBatteryConsumer(APP_UID),
                0.18169, 8416, BatteryConsumer.POWER_MODEL_POWER_PROFILE);
        assertBluetoothPowerAndDuration(
                mStatsRule.getDeviceBatteryConsumer(),
                0.30030, 12000, BatteryConsumer.POWER_MODEL_POWER_PROFILE);
        assertBluetoothPowerAndDuration(
                mStatsRule.getAppsBatteryConsumer(),
                0.26386, 11999, BatteryConsumer.POWER_MODEL_POWER_PROFILE);
    }

    @Test
    public void testMeasuredEnergyBasedModel() {
        mStatsRule.initMeasuredEnergyStatsLocked();
        setupBluetoothEnergyInfo(0, 1200000);

        final BluetoothPowerCalculator calculator =
                new BluetoothPowerCalculator(mStatsRule.getPowerProfile());

        mStatsRule.apply(calculator);

        assertBluetoothPowerAndDuration(
                mStatsRule.getUidBatteryConsumer(Process.BLUETOOTH_UID),
                0.10378, 3583, BatteryConsumer.POWER_MODEL_ENERGY_CONSUMPTION);
        assertBluetoothPowerAndDuration(
                mStatsRule.getUidBatteryConsumer(APP_UID),
                0.22950, 8416, BatteryConsumer.POWER_MODEL_ENERGY_CONSUMPTION);
        assertBluetoothPowerAndDuration(
                mStatsRule.getDeviceBatteryConsumer(),
                0.33333, 12000, BatteryConsumer.POWER_MODEL_ENERGY_CONSUMPTION);
        assertBluetoothPowerAndDuration(
                mStatsRule.getAppsBatteryConsumer(),
                0.33329, 11999, BatteryConsumer.POWER_MODEL_ENERGY_CONSUMPTION);
    }

    @Test
    public void testMeasuredEnergyBasedModel_byProcessState() {
        mStatsRule.initMeasuredEnergyStatsLocked();
        mStatsRule.setTime(1000, 1000);

        BatteryStatsImpl batteryStats = mStatsRule.getBatteryStats();

        BatteryStatsImpl.Uid uid = batteryStats.getUidStatsLocked(APP_UID);
        uid.setProcessStateForTest(
                BatteryStats.Uid.PROCESS_STATE_FOREGROUND, 1000);


        List<UidTraffic> trafficList1 = ImmutableList.of(
                createUidTraffic(Process.BLUETOOTH_UID, 1000, 2000),
                createUidTraffic(APP_UID, 3000, 4000));
        BluetoothActivityEnergyInfo info1 = createBtEnergyInfo(2000,
                BluetoothActivityEnergyInfo.BT_STACK_STATE_STATE_ACTIVE, 1000, 2000, 3000, 4000,
                trafficList1);


        batteryStats.updateBluetoothStateLocked(info1,
                1_000_000, 2000, 2000);

        uid.setProcessStateForTest(
                BatteryStats.Uid.PROCESS_STATE_BACKGROUND, 3000);

        List<UidTraffic> trafficList2 = ImmutableList.of(
                createUidTraffic(Process.BLUETOOTH_UID, 5000, 6000),
                createUidTraffic(APP_UID, 7000, 8000));
        BluetoothActivityEnergyInfo info2 = createBtEnergyInfo(4000,
                BluetoothActivityEnergyInfo.BT_STACK_STATE_STATE_ACTIVE, 5000, 6000, 7000, 8000,
                trafficList2);


        batteryStats.updateBluetoothStateLocked(info2,
                5_000_000, 4000, 4000);

        BluetoothPowerCalculator calculator =
                new BluetoothPowerCalculator(mStatsRule.getPowerProfile());

        mStatsRule.apply(new BatteryUsageStatsQuery.Builder()
                .includePowerModels()
                .includeProcessStateData()
                .build(), calculator);

        UidBatteryConsumer uidConsumer = mStatsRule.getUidBatteryConsumer(APP_UID);
        assertThat(uidConsumer.getUsageDurationMillis(BatteryConsumer.POWER_COMPONENT_BLUETOOTH))
                .isEqualTo(6166);
        assertThat(uidConsumer.getConsumedPower(BatteryConsumer.POWER_COMPONENT_BLUETOOTH))
                .isWithin(PRECISION).of(0.8220561);
        assertThat(uidConsumer.getPowerModel(BatteryConsumer.POWER_COMPONENT_BLUETOOTH))
                .isEqualTo(BatteryConsumer.POWER_MODEL_ENERGY_CONSUMPTION);

        final BatteryConsumer.Key foreground = uidConsumer.getKey(
                BatteryConsumer.POWER_COMPONENT_BLUETOOTH,
                BatteryConsumer.PROCESS_STATE_FOREGROUND);
        final BatteryConsumer.Key background = uidConsumer.getKey(
                BatteryConsumer.POWER_COMPONENT_BLUETOOTH,
                BatteryConsumer.PROCESS_STATE_BACKGROUND);
        final BatteryConsumer.Key fgs = uidConsumer.getKey(
                BatteryConsumer.POWER_COMPONENT_BLUETOOTH,
                BatteryConsumer.PROCESS_STATE_FOREGROUND_SERVICE);
        final BatteryConsumer.Key cached = uidConsumer.getKey(
                BatteryConsumer.POWER_COMPONENT_BLUETOOTH,
                BatteryConsumer.PROCESS_STATE_CACHED);

        assertThat(uidConsumer.getConsumedPower(foreground)).isWithin(PRECISION).of(0.4965352);
        assertThat(uidConsumer.getConsumedPower(background)).isWithin(PRECISION).of(0.3255208);
        assertThat(uidConsumer.getConsumedPower(fgs)).isWithin(PRECISION).of(0);
        assertThat(uidConsumer.getConsumedPower(cached)).isWithin(PRECISION).of(0);
    }


    @Test
    public void testIgnoreMeasuredEnergyBasedModel() {
        mStatsRule.initMeasuredEnergyStatsLocked();
        setupBluetoothEnergyInfo(4000000, 1200000);

        BluetoothPowerCalculator calculator =
                new BluetoothPowerCalculator(mStatsRule.getPowerProfile());

        mStatsRule.apply(BatteryUsageStatsRule.POWER_PROFILE_MODEL_ONLY, calculator);

        assertBluetoothPowerAndDuration(
                mStatsRule.getUidBatteryConsumer(Process.BLUETOOTH_UID),
                0.08216, 3583, BatteryConsumer.POWER_MODEL_POWER_PROFILE);
        assertBluetoothPowerAndDuration(
                mStatsRule.getUidBatteryConsumer(APP_UID),
                0.18169, 8416, BatteryConsumer.POWER_MODEL_POWER_PROFILE);
        assertBluetoothPowerAndDuration(
                mStatsRule.getDeviceBatteryConsumer(),
                0.26388, 12000, BatteryConsumer.POWER_MODEL_POWER_PROFILE);
        assertBluetoothPowerAndDuration(
                mStatsRule.getAppsBatteryConsumer(),
                0.26386, 11999, BatteryConsumer.POWER_MODEL_POWER_PROFILE);
    }

    private void setupBluetoothEnergyInfo(long reportedEnergyUc, long consumedEnergyUc) {
        List<UidTraffic> trafficList = ImmutableList.of(
                createUidTraffic(Process.BLUETOOTH_UID, 1000, 2000),
                createUidTraffic(APP_UID, 3000, 4000));


        final BluetoothActivityEnergyInfo info = createBtEnergyInfo(1000,
                BluetoothActivityEnergyInfo.BT_STACK_STATE_STATE_ACTIVE, 7000, 5000, 0,
                reportedEnergyUc, trafficList);

        mStatsRule.getBatteryStats().updateBluetoothStateLocked(info,
                consumedEnergyUc, 1000, 1000);
    }

    private void assertBluetoothPowerAndDuration(@Nullable BatteryConsumer batteryConsumer,
            double powerMah, int durationMs, @BatteryConsumer.PowerModel int powerModel) {
        assertThat(batteryConsumer).isNotNull();

        double consumedPower = batteryConsumer.getConsumedPower(
                BatteryConsumer.POWER_COMPONENT_BLUETOOTH);
        assertThat(consumedPower).isWithin(PRECISION).of(powerMah);
        assertThat(batteryConsumer.getPowerModel(BatteryConsumer.POWER_COMPONENT_BLUETOOTH))
                .isEqualTo(powerModel);

        long usageDurationMillis = batteryConsumer.getUsageDurationMillis(
                BatteryConsumer.POWER_COMPONENT_BLUETOOTH);
        assertThat(usageDurationMillis).isEqualTo(durationMs);
    }

    private UidTraffic createUidTraffic(int appUid, long rxBytes, long txBytes) {
        if (RavenwoodRule.isUnderRavenwood()) {
            UidTraffic uidTraffic = mock(UidTraffic.class);
            when(uidTraffic.getUid()).thenReturn(appUid);
            when(uidTraffic.getRxBytes()).thenReturn(rxBytes);
            when(uidTraffic.getTxBytes()).thenReturn(txBytes);
            return uidTraffic;
        } else {
            final Parcel uidTrafficParcel = Parcel.obtain();
            uidTrafficParcel.writeInt(appUid);
            uidTrafficParcel.writeLong(rxBytes);
            uidTrafficParcel.writeLong(txBytes);
            uidTrafficParcel.setDataPosition(0);

            UidTraffic traffic = UidTraffic.CREATOR.createFromParcel(uidTrafficParcel);
            uidTrafficParcel.recycle();
            return traffic;
        }
    }

    private BluetoothActivityEnergyInfo createBtEnergyInfo(long timestamp, int stackState,
            long txTime, long rxTime, long idleTime, long energyUsed, List<UidTraffic> traffic) {
        if (RavenwoodRule.isUnderRavenwood()) {
            BluetoothActivityEnergyInfo info = mock(BluetoothActivityEnergyInfo.class);
            when(info.getTimestampMillis()).thenReturn(timestamp);
            when(info.getBluetoothStackState()).thenReturn(stackState);
            when(info.getControllerTxTimeMillis()).thenReturn(txTime);
            when(info.getControllerRxTimeMillis()).thenReturn(rxTime);
            when(info.getControllerIdleTimeMillis()).thenReturn(idleTime);
            when(info.getControllerEnergyUsed()).thenReturn(energyUsed);
            when(info.getUidTraffic()).thenReturn(ImmutableList.copyOf(traffic));
            return info;
        } else {
            final Parcel btActivityEnergyInfoParcel = Parcel.obtain();
            btActivityEnergyInfoParcel.writeLong(timestamp);
            btActivityEnergyInfoParcel.writeInt(stackState);
            btActivityEnergyInfoParcel.writeLong(txTime);
            btActivityEnergyInfoParcel.writeLong(rxTime);
            btActivityEnergyInfoParcel.writeLong(idleTime);
            btActivityEnergyInfoParcel.writeLong(energyUsed);
            btActivityEnergyInfoParcel.writeTypedList(traffic);
            btActivityEnergyInfoParcel.setDataPosition(0);

            BluetoothActivityEnergyInfo info = BluetoothActivityEnergyInfo.CREATOR
                    .createFromParcel(btActivityEnergyInfoParcel);
            btActivityEnergyInfoParcel.recycle();
            return info;
        }
    }
}
