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

import static android.net.NetworkStats.DEFAULT_NETWORK_NO;
import static android.net.NetworkStats.METERED_NO;
import static android.net.NetworkStats.ROAMING_NO;
import static android.os.BatteryStats.POWER_DATA_UNAVAILABLE;

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import android.app.usage.NetworkStatsManager;
import android.net.NetworkCapabilities;
import android.net.NetworkStats;
import android.os.BatteryConsumer;
import android.os.BatteryStats;
import android.os.BatteryUsageStatsQuery;
import android.os.Process;
import android.os.UidBatteryConsumer;
import android.telephony.DataConnectionRealTimeInfo;
import android.telephony.ModemActivityInfo;
import android.telephony.ServiceState;
import android.telephony.SignalStrength;
import android.telephony.TelephonyManager;

import androidx.test.filters.SmallTest;
import androidx.test.runner.AndroidJUnit4;

import com.google.common.collect.Range;

import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;

@RunWith(AndroidJUnit4.class)
@SmallTest
@SuppressWarnings("GuardedBy")
public class MobileRadioPowerCalculatorTest {
    private static final double PRECISION = 0.00001;
    private static final int APP_UID = Process.FIRST_APPLICATION_UID + 42;
    @Mock
    NetworkStatsManager mNetworkStatsManager;

    @Rule
    public final BatteryUsageStatsRule mStatsRule = new BatteryUsageStatsRule()
            .setAveragePowerUnspecified(PowerProfile.POWER_RADIO_ACTIVE)
            .setAveragePowerUnspecified(PowerProfile.POWER_RADIO_ON)
            .setAveragePower(PowerProfile.POWER_MODEM_CONTROLLER_IDLE, 360.0)
            .setAveragePower(PowerProfile.POWER_RADIO_SCANNING, 720.0)
            .setAveragePower(PowerProfile.POWER_MODEM_CONTROLLER_RX, 1440.0)
            .setAveragePower(PowerProfile.POWER_MODEM_CONTROLLER_TX,
                    new double[]{720.0, 1080.0, 1440.0, 1800.0, 2160.0})
            .initMeasuredEnergyStatsLocked();

    @Test
    public void testCounterBasedModel() {
        BatteryStatsImpl stats = mStatsRule.getBatteryStats();

        // Scan for a cell
        stats.notePhoneStateLocked(ServiceState.STATE_OUT_OF_SERVICE,
                TelephonyManager.SIM_STATE_READY,
                2000, 2000);

        // Found a cell
        stats.notePhoneStateLocked(ServiceState.STATE_IN_SERVICE, TelephonyManager.SIM_STATE_READY,
                5000, 5000);

        // Note cell signal strength
        SignalStrength signalStrength = mock(SignalStrength.class);
        when(signalStrength.getLevel()).thenReturn(SignalStrength.SIGNAL_STRENGTH_MODERATE);
        stats.notePhoneSignalStrengthLocked(signalStrength, 5000, 5000);

        stats.noteMobileRadioPowerStateLocked(DataConnectionRealTimeInfo.DC_POWER_STATE_HIGH,
                8_000_000_000L, APP_UID, 8000, 8000);

        // Note established network
        stats.noteNetworkInterfaceForTransports("cellular",
                new int[]{NetworkCapabilities.TRANSPORT_CELLULAR});

        // Note application network activity
        NetworkStats networkStats = new NetworkStats(10000, 1)
                .addEntry(new NetworkStats.Entry("cellular", APP_UID, 0, 0,
                        METERED_NO, ROAMING_NO, DEFAULT_NETWORK_NO, 1000, 100, 2000, 20, 100));
        mStatsRule.setNetworkStats(networkStats);

        ModemActivityInfo mai = new ModemActivityInfo(10000, 2000, 3000,
                new int[]{100, 200, 300, 400, 500}, 600);
        stats.noteModemControllerActivity(mai, POWER_DATA_UNAVAILABLE, 10000, 10000,
                mNetworkStatsManager);

        mStatsRule.setTime(12_000, 12_000);

        MobileRadioPowerCalculator calculator =
                new MobileRadioPowerCalculator(mStatsRule.getPowerProfile());

        mStatsRule.apply(BatteryUsageStatsRule.POWER_PROFILE_MODEL_ONLY, calculator);

        UidBatteryConsumer uidConsumer = mStatsRule.getUidBatteryConsumer(APP_UID);
        assertThat(uidConsumer.getConsumedPower(BatteryConsumer.POWER_COMPONENT_MOBILE_RADIO))
                .isWithin(PRECISION).of(0.8);
        assertThat(uidConsumer.getPowerModel(BatteryConsumer.POWER_COMPONENT_MOBILE_RADIO))
                .isEqualTo(BatteryConsumer.POWER_MODEL_POWER_PROFILE);

        BatteryConsumer deviceConsumer = mStatsRule.getDeviceBatteryConsumer();
        assertThat(deviceConsumer.getConsumedPower(BatteryConsumer.POWER_COMPONENT_MOBILE_RADIO))
                .isWithin(PRECISION).of(2.2444);
        assertThat(deviceConsumer.getPowerModel(BatteryConsumer.POWER_COMPONENT_MOBILE_RADIO))
                .isEqualTo(BatteryConsumer.POWER_MODEL_POWER_PROFILE);

        BatteryConsumer appsConsumer = mStatsRule.getAppsBatteryConsumer();
        assertThat(appsConsumer.getConsumedPower(BatteryConsumer.POWER_COMPONENT_MOBILE_RADIO))
                .isWithin(PRECISION).of(0.8);
        assertThat(appsConsumer.getPowerModel(BatteryConsumer.POWER_COMPONENT_MOBILE_RADIO))
                .isEqualTo(BatteryConsumer.POWER_MODEL_POWER_PROFILE);
    }

    @Test
    public void testTimerBasedModel_byProcessState() {
        BatteryStatsImpl stats = mStatsRule.getBatteryStats();
        BatteryStatsImpl.Uid uid = stats.getUidStatsLocked(APP_UID);

        mStatsRule.setTime(1000, 1000);
        uid.setProcessStateForTest(
                BatteryStats.Uid.PROCESS_STATE_FOREGROUND, 1000);

        // Scan for a cell
        stats.notePhoneStateLocked(ServiceState.STATE_OUT_OF_SERVICE,
                TelephonyManager.SIM_STATE_READY,
                2000, 2000);

        // Found a cell
        stats.notePhoneStateLocked(ServiceState.STATE_IN_SERVICE, TelephonyManager.SIM_STATE_READY,
                5000, 5000);

        // Note cell signal strength
        SignalStrength signalStrength = mock(SignalStrength.class);
        when(signalStrength.getLevel()).thenReturn(SignalStrength.SIGNAL_STRENGTH_MODERATE);
        stats.notePhoneSignalStrengthLocked(signalStrength, 5000, 5000);

        stats.noteMobileRadioPowerStateLocked(DataConnectionRealTimeInfo.DC_POWER_STATE_HIGH,
                8_000_000_000L, APP_UID, 8000, 8000);

        // Note established network
        stats.noteNetworkInterfaceForTransports("cellular",
                new int[]{NetworkCapabilities.TRANSPORT_CELLULAR});

        // Note application network activity
        mStatsRule.setNetworkStats(new NetworkStats(10000, 1)
                .addEntry(new NetworkStats.Entry("cellular", APP_UID, 0, 0,
                    METERED_NO, ROAMING_NO, DEFAULT_NETWORK_NO, 1000, 100, 2000, 20, 100)));

        stats.noteModemControllerActivity(null, POWER_DATA_UNAVAILABLE, 10000, 10000,
                mNetworkStatsManager);

        uid.setProcessStateForTest(
                BatteryStats.Uid.PROCESS_STATE_BACKGROUND, 11000);

        mStatsRule.setNetworkStats(new NetworkStats(12000, 1)
                .addEntry(new NetworkStats.Entry("cellular", APP_UID, 0, 0,
                    METERED_NO, ROAMING_NO, DEFAULT_NETWORK_NO, 1000, 250, 2000, 80, 200)));

        stats.noteModemControllerActivity(null, POWER_DATA_UNAVAILABLE, 12000, 12000,
                mNetworkStatsManager);

        assertThat(uid.getMobileRadioMeasuredBatteryConsumptionUC()).isAtMost(0);
        // 12000-8000 = 4000 ms == 4_000_000 us
        assertThat(uid.getMobileRadioActiveTimeInProcessState(BatteryConsumer.PROCESS_STATE_ANY))
                .isEqualTo(4_000_000);
        assertThat(uid.getMobileRadioActiveTimeInProcessState(
                BatteryConsumer.PROCESS_STATE_FOREGROUND))
                .isEqualTo(3_000_000);
        assertThat(uid.getMobileRadioActiveTimeInProcessState(
                BatteryConsumer.PROCESS_STATE_BACKGROUND))
                .isEqualTo(1_000_000);
        assertThat(uid.getMobileRadioActiveTimeInProcessState(
                BatteryConsumer.PROCESS_STATE_FOREGROUND_SERVICE))
                .isEqualTo(0);

        MobileRadioPowerCalculator calculator =
                new MobileRadioPowerCalculator(mStatsRule.getPowerProfile());

        mStatsRule.apply(new BatteryUsageStatsQuery.Builder()
                .powerProfileModeledOnly()
                .includePowerModels()
                .includeProcessStateData()
                .build(), calculator);

        UidBatteryConsumer uidConsumer = mStatsRule.getUidBatteryConsumer(APP_UID);

        final BatteryConsumer.Key foreground = uidConsumer.getKey(
                BatteryConsumer.POWER_COMPONENT_MOBILE_RADIO,
                BatteryConsumer.PROCESS_STATE_FOREGROUND);
        final BatteryConsumer.Key background = uidConsumer.getKey(
                BatteryConsumer.POWER_COMPONENT_MOBILE_RADIO,
                BatteryConsumer.PROCESS_STATE_BACKGROUND);
        final BatteryConsumer.Key fgs = uidConsumer.getKey(
                BatteryConsumer.POWER_COMPONENT_MOBILE_RADIO,
                BatteryConsumer.PROCESS_STATE_FOREGROUND_SERVICE);

        assertThat(uidConsumer.getConsumedPower(foreground)).isWithin(PRECISION).of(1.2);
        assertThat(uidConsumer.getConsumedPower(background)).isWithin(PRECISION).of(0.4);
        assertThat(uidConsumer.getConsumedPower(fgs)).isWithin(PRECISION).of(0);
    }

    @Test
    public void testMeasuredEnergyBasedModel() {
        BatteryStatsImpl stats = mStatsRule.getBatteryStats();

        // Scan for a cell
        stats.notePhoneStateLocked(ServiceState.STATE_OUT_OF_SERVICE,
                TelephonyManager.SIM_STATE_READY,
                2000, 2000);

        // Found a cell
        stats.notePhoneStateLocked(ServiceState.STATE_IN_SERVICE, TelephonyManager.SIM_STATE_READY,
                5000, 5000);

        // Note cell signal strength
        SignalStrength signalStrength = mock(SignalStrength.class);
        when(signalStrength.getLevel()).thenReturn(SignalStrength.SIGNAL_STRENGTH_MODERATE);
        stats.notePhoneSignalStrengthLocked(signalStrength, 5000, 5000);

        stats.noteMobileRadioPowerStateLocked(DataConnectionRealTimeInfo.DC_POWER_STATE_HIGH,
                8_000_000_000L, APP_UID, 8000, 8000);

        // Note established network
        stats.noteNetworkInterfaceForTransports("cellular",
                new int[]{NetworkCapabilities.TRANSPORT_CELLULAR});

        // Note application network activity
        NetworkStats networkStats = new NetworkStats(10000, 1)
                .addEntry(new NetworkStats.Entry("cellular", APP_UID, 0, 0,
                        METERED_NO, ROAMING_NO, DEFAULT_NETWORK_NO, 1000, 100, 2000, 20, 100));
        mStatsRule.setNetworkStats(networkStats);

        ModemActivityInfo mai = new ModemActivityInfo(10000, 2000, 3000,
                new int[]{100, 200, 300, 400, 500}, 600);
        stats.noteModemControllerActivity(mai, 10_000_000, 10000, 10000, mNetworkStatsManager);

        mStatsRule.setTime(12_000, 12_000);

        MobileRadioPowerCalculator calculator =
                new MobileRadioPowerCalculator(mStatsRule.getPowerProfile());

        mStatsRule.apply(calculator);

        UidBatteryConsumer uidConsumer = mStatsRule.getUidBatteryConsumer(APP_UID);
        assertThat(uidConsumer.getConsumedPower(BatteryConsumer.POWER_COMPONENT_MOBILE_RADIO))
                .isWithin(PRECISION).of(1.53934);
        assertThat(uidConsumer.getPowerModel(BatteryConsumer.POWER_COMPONENT_MOBILE_RADIO))
                .isEqualTo(BatteryConsumer.POWER_MODEL_MEASURED_ENERGY);

        BatteryConsumer deviceConsumer = mStatsRule.getDeviceBatteryConsumer();
        // 10_000_000 micro-Coulomb * 1/1000 milli/micro * 1/3600 hour/second = 2.77778 mAh
        assertThat(deviceConsumer.getConsumedPower(BatteryConsumer.POWER_COMPONENT_MOBILE_RADIO))
                .isWithin(PRECISION).of(2.77778);
        assertThat(deviceConsumer.getPowerModel(BatteryConsumer.POWER_COMPONENT_MOBILE_RADIO))
                .isEqualTo(BatteryConsumer.POWER_MODEL_MEASURED_ENERGY);

        BatteryConsumer appsConsumer = mStatsRule.getAppsBatteryConsumer();
        assertThat(appsConsumer.getConsumedPower(BatteryConsumer.POWER_COMPONENT_MOBILE_RADIO))
                .isWithin(PRECISION).of(1.53934);
        assertThat(appsConsumer.getPowerModel(BatteryConsumer.POWER_COMPONENT_MOBILE_RADIO))
                .isEqualTo(BatteryConsumer.POWER_MODEL_MEASURED_ENERGY);
    }

    @Test
    public void testMeasuredEnergyBasedModel_byProcessState() {
        BatteryStatsImpl stats = mStatsRule.getBatteryStats();
        BatteryStatsImpl.Uid uid = stats.getUidStatsLocked(APP_UID);

        mStatsRule.setTime(1000, 1000);
        uid.setProcessStateForTest(
                BatteryStats.Uid.PROCESS_STATE_FOREGROUND, 1000);

        // Scan for a cell
        stats.notePhoneStateLocked(ServiceState.STATE_OUT_OF_SERVICE,
                TelephonyManager.SIM_STATE_READY,
                2000, 2000);

        // Found a cell
        stats.notePhoneStateLocked(ServiceState.STATE_IN_SERVICE, TelephonyManager.SIM_STATE_READY,
                5000, 5000);

        // Note cell signal strength
        SignalStrength signalStrength = mock(SignalStrength.class);
        when(signalStrength.getLevel()).thenReturn(SignalStrength.SIGNAL_STRENGTH_MODERATE);
        stats.notePhoneSignalStrengthLocked(signalStrength, 5000, 5000);

        stats.noteMobileRadioPowerStateLocked(DataConnectionRealTimeInfo.DC_POWER_STATE_HIGH,
                8_000_000_000L, APP_UID, 8000, 8000);

        // Note established network
        stats.noteNetworkInterfaceForTransports("cellular",
                new int[]{NetworkCapabilities.TRANSPORT_CELLULAR});

        // Note application network activity
        mStatsRule.setNetworkStats(new NetworkStats(10000, 1)
                .addEntry(new NetworkStats.Entry("cellular", APP_UID, 0, 0,
                    METERED_NO, ROAMING_NO, DEFAULT_NETWORK_NO, 1000, 100, 2000, 20, 100)));

        stats.noteModemControllerActivity(null, 10_000_000, 10000, 10000, mNetworkStatsManager);

        uid.setProcessStateForTest(
                BatteryStats.Uid.PROCESS_STATE_BACKGROUND, 11000);

        mStatsRule.setNetworkStats(new NetworkStats(12000, 1)
                .addEntry(new NetworkStats.Entry("cellular", APP_UID, 0, 0,
                    METERED_NO, ROAMING_NO, DEFAULT_NETWORK_NO, 1000, 250, 2000, 80, 200)));

        stats.noteModemControllerActivity(null, 15_000_000, 12000, 12000, mNetworkStatsManager);

        mStatsRule.setTime(20000, 20000);

        assertThat(uid.getMobileRadioMeasuredBatteryConsumptionUC())
                .isIn(Range.open(20_000_000L, 21_000_000L));
        assertThat(uid.getMobileRadioMeasuredBatteryConsumptionUC(
                BatteryConsumer.PROCESS_STATE_FOREGROUND))
                .isIn(Range.open(13_000_000L, 14_000_000L));
        assertThat(uid.getMobileRadioMeasuredBatteryConsumptionUC(
                BatteryConsumer.PROCESS_STATE_BACKGROUND))
                .isIn(Range.open(7_000_000L, 8_000_000L));
        assertThat(uid.getMobileRadioMeasuredBatteryConsumptionUC(
                BatteryConsumer.PROCESS_STATE_FOREGROUND_SERVICE))
                .isEqualTo(0);

        MobileRadioPowerCalculator calculator =
                new MobileRadioPowerCalculator(mStatsRule.getPowerProfile());

        mStatsRule.apply(new BatteryUsageStatsQuery.Builder()
                .includePowerModels()
                .includeProcessStateData()
                .build(), calculator);

        UidBatteryConsumer uidConsumer = mStatsRule.getUidBatteryConsumer(APP_UID);

        final BatteryConsumer.Key foreground = uidConsumer.getKey(
                BatteryConsumer.POWER_COMPONENT_MOBILE_RADIO,
                BatteryConsumer.PROCESS_STATE_FOREGROUND);
        final BatteryConsumer.Key background = uidConsumer.getKey(
                BatteryConsumer.POWER_COMPONENT_MOBILE_RADIO,
                BatteryConsumer.PROCESS_STATE_BACKGROUND);
        final BatteryConsumer.Key fgs = uidConsumer.getKey(
                BatteryConsumer.POWER_COMPONENT_MOBILE_RADIO,
                BatteryConsumer.PROCESS_STATE_FOREGROUND_SERVICE);

        assertThat(uidConsumer.getConsumedPower(foreground)).isWithin(PRECISION).of(3.62064);
        assertThat(uidConsumer.getConsumedPower(background)).isWithin(PRECISION).of(2.08130);
        assertThat(uidConsumer.getConsumedPower(fgs)).isWithin(PRECISION).of(0);
    }
}
