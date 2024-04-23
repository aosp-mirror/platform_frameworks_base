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

import static android.net.NetworkStats.DEFAULT_NETWORK_NO;
import static android.net.NetworkStats.METERED_NO;
import static android.net.NetworkStats.ROAMING_NO;
import static android.os.BatteryStats.POWER_DATA_UNAVAILABLE;

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import android.annotation.Nullable;
import android.app.usage.NetworkStatsManager;
import android.net.NetworkCapabilities;
import android.net.NetworkStats;
import android.os.BatteryConsumer;
import android.os.BatteryStats;
import android.os.BatteryUsageStatsQuery;
import android.os.Process;
import android.os.UidBatteryConsumer;
import android.platform.test.ravenwood.RavenwoodRule;
import android.telephony.AccessNetworkConstants;
import android.telephony.ActivityStatsTechSpecificInfo;
import android.telephony.CellSignalStrength;
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

import java.util.ArrayList;
import java.util.List;

@RunWith(AndroidJUnit4.class)
@SmallTest
@SuppressWarnings("GuardedBy")
public class MobileRadioPowerCalculatorTest {
    @Rule(order = 0)
    public final RavenwoodRule mRavenwood = new RavenwoodRule.Builder()
            .setProvideMainThread(true)
            .build();

    private static final double PRECISION = 0.00001;
    private static final int APP_UID = Process.FIRST_APPLICATION_UID + 42;
    private static final int APP_UID2 = Process.FIRST_APPLICATION_UID + 101;
    @Mock
    NetworkStatsManager mNetworkStatsManager;

    @Rule(order = 1)
    public final BatteryUsageStatsRule mStatsRule = new BatteryUsageStatsRule();

    @Test
    public void testCounterBasedModel() {
        mStatsRule.setTestPowerProfile("power_profile_test_modem_calculator")
                .initMeasuredEnergyStatsLocked();
        BatteryStatsImpl stats = mStatsRule.getBatteryStats();

        // The first ModemActivityInfo doesn't count up.
        setInitialEmptyModemActivityInfo(stats);

        // Scan for a cell
        stats.notePhoneStateLocked(ServiceState.STATE_OUT_OF_SERVICE,
                TelephonyManager.SIM_STATE_READY,
                2000, 2000);

        // Found a cell
        stats.notePhoneStateLocked(ServiceState.STATE_IN_SERVICE, TelephonyManager.SIM_STATE_READY,
                5000, 5000);

        ArrayList<CellSignalStrength> perRatCellStrength = new ArrayList();
        CellSignalStrength gsmSignalStrength = mock(CellSignalStrength.class);
        when(gsmSignalStrength.getLevel()).thenReturn(
                SignalStrength.SIGNAL_STRENGTH_NONE_OR_UNKNOWN);
        perRatCellStrength.add(gsmSignalStrength);

        // Note cell signal strength
        SignalStrength signalStrength = mock(SignalStrength.class);
        when(signalStrength.getCellSignalStrengths()).thenReturn(perRatCellStrength);
        stats.notePhoneSignalStrengthLocked(signalStrength, 5000, 5000);

        stats.noteMobileRadioPowerStateLocked(DataConnectionRealTimeInfo.DC_POWER_STATE_HIGH,
                8_000_000_000L, APP_UID, 8000, 8000);

        // Note established network
        stats.noteNetworkInterfaceForTransports("cellular",
                new int[]{NetworkCapabilities.TRANSPORT_CELLULAR});

        // Spend some time in each signal strength level. It doesn't matter how long.
        // The ModemActivityInfo reported level residency should be trusted over the BatteryStats
        // timers.
        when(gsmSignalStrength.getLevel()).thenReturn(
                SignalStrength.SIGNAL_STRENGTH_NONE_OR_UNKNOWN);
        stats.notePhoneSignalStrengthLocked(signalStrength, 8111, 8111);
        when(gsmSignalStrength.getLevel()).thenReturn(SignalStrength.SIGNAL_STRENGTH_POOR);
        stats.notePhoneSignalStrengthLocked(signalStrength, 8333, 8333);
        when(gsmSignalStrength.getLevel()).thenReturn(SignalStrength.SIGNAL_STRENGTH_MODERATE);
        stats.notePhoneSignalStrengthLocked(signalStrength, 8666, 8666);
        when(gsmSignalStrength.getLevel()).thenReturn(SignalStrength.SIGNAL_STRENGTH_GOOD);
        stats.notePhoneSignalStrengthLocked(signalStrength, 9110, 9110);

        stats.noteMobileRadioPowerStateLocked(DataConnectionRealTimeInfo.DC_POWER_STATE_HIGH,
                9_500_000_000L, APP_UID2, 9500, 9500);

        when(gsmSignalStrength.getLevel()).thenReturn(SignalStrength.SIGNAL_STRENGTH_GREAT);
        stats.notePhoneSignalStrengthLocked(signalStrength, 9665, 9665);

        // Note application network activity
        NetworkStats networkStats = mockNetworkStats(10000, 1,
                mockNetworkStatsEntry("cellular", APP_UID, 0, 0,
                        METERED_NO, ROAMING_NO, DEFAULT_NETWORK_NO, 1000, 150, 2000, 30, 100),
                mockNetworkStatsEntry("cellular", APP_UID2, 0, 0,
                        METERED_NO, ROAMING_NO, DEFAULT_NETWORK_NO, 500, 50, 300, 10, 111));
        mStatsRule.setNetworkStats(networkStats);

        ModemActivityInfo mai = new ModemActivityInfo(10000, 2000, 3000,
                new int[]{100, 200, 300, 400, 500}, 600);
        stats.noteModemControllerActivity(mai, POWER_DATA_UNAVAILABLE, 10000, 10000,
                mNetworkStatsManager);

        mStatsRule.setTime(10_000, 10_000);

        MobileRadioPowerCalculator calculator =
                new MobileRadioPowerCalculator(mStatsRule.getPowerProfile());

        mStatsRule.apply(BatteryUsageStatsRule.POWER_PROFILE_MODEL_ONLY, calculator);

        BatteryConsumer deviceConsumer = mStatsRule.getDeviceBatteryConsumer();
        //    720 mA * 100 ms  (level 0 TX drain rate * level 0 TX duration)
        // + 1080 mA * 200 ms  (level 1 TX drain rate * level 1 TX duration)
        // + 1440 mA * 300 ms  (level 2 TX drain rate * level 2 TX duration)
        // + 1800 mA * 400 ms  (level 3 TX drain rate * level 3 TX duration)
        // + 2160 mA * 500 ms  (level 4 TX drain rate * level 4 TX duration)
        // + 1440 mA * 600 ms  (RX drain rate * RX duration)
        // +  360 mA * 3000 ms (idle drain rate * idle duration)
        // +   70 mA * 2000 ms (sleep drain rate * sleep duration)
        // _________________
        // =    4604000 mA-ms or 1.27888 mA-h
        assertThat(deviceConsumer.getConsumedPower(BatteryConsumer.POWER_COMPONENT_MOBILE_RADIO))
                .isWithin(PRECISION).of(1.27888);
        assertThat(deviceConsumer.getPowerModel(BatteryConsumer.POWER_COMPONENT_MOBILE_RADIO))
                .isEqualTo(BatteryConsumer.POWER_MODEL_POWER_PROFILE);

        //    720 mA * 100 ms  (level 0 TX drain rate * level 0 TX duration)
        // + 1080 mA * 200 ms  (level 1 TX drain rate * level 1 TX duration)
        // + 1440 mA * 300 ms  (level 2 TX drain rate * level 2 TX duration)
        // + 1800 mA * 400 ms  (level 3 TX drain rate * level 3 TX duration)
        // + 2160 mA * 500 ms  (level 4 TX drain rate * level 4 TX duration)
        // + 1440 mA * 600 ms  (RX drain rate * RX duration)
        // _________________
        // =    3384000 mA-ms or 0.94 mA-h
        BatteryConsumer appsConsumer = mStatsRule.getAppsBatteryConsumer();
        assertThat(appsConsumer.getConsumedPower(BatteryConsumer.POWER_COMPONENT_MOBILE_RADIO))
                .isWithin(PRECISION).of(0.94);
        assertThat(appsConsumer.getPowerModel(BatteryConsumer.POWER_COMPONENT_MOBILE_RADIO))
                .isEqualTo(BatteryConsumer.POWER_MODEL_POWER_PROFILE);

        // 3/4 of total packets were sent by APP_UID so 75% of total
        UidBatteryConsumer uidConsumer = mStatsRule.getUidBatteryConsumer(APP_UID);
        assertThat(uidConsumer.getConsumedPower(BatteryConsumer.POWER_COMPONENT_MOBILE_RADIO))
                .isWithin(PRECISION).of(0.705);
        assertThat(uidConsumer.getPowerModel(BatteryConsumer.POWER_COMPONENT_MOBILE_RADIO))
                .isEqualTo(BatteryConsumer.POWER_MODEL_POWER_PROFILE);

        // Rest should go to the other app
        UidBatteryConsumer uidConsumer2 = mStatsRule.getUidBatteryConsumer(APP_UID2);
        assertThat(uidConsumer2.getConsumedPower(BatteryConsumer.POWER_COMPONENT_MOBILE_RADIO))
                .isWithin(PRECISION).of(0.235);
        assertThat(uidConsumer.getPowerModel(BatteryConsumer.POWER_COMPONENT_MOBILE_RADIO))
                .isEqualTo(BatteryConsumer.POWER_MODEL_POWER_PROFILE);
    }

    @Test
    public void testCounterBasedModel_multipleDefinedRat() {
        mStatsRule.setTestPowerProfile("power_profile_test_modem_calculator_multiactive")
                .initMeasuredEnergyStatsLocked();
        BatteryStatsImpl stats = mStatsRule.getBatteryStats();

        // The first ModemActivityInfo doesn't count up.
        setInitialEmptyModemActivityInfo(stats);

        // Scan for a cell
        stats.notePhoneStateLocked(ServiceState.STATE_OUT_OF_SERVICE,
                TelephonyManager.SIM_STATE_READY,
                2000, 2000);

        // Found a cell
        stats.notePhoneStateLocked(ServiceState.STATE_IN_SERVICE, TelephonyManager.SIM_STATE_READY,
                5000, 5000);

        ArrayList<CellSignalStrength> perRatCellStrength = new ArrayList();
        CellSignalStrength gsmSignalStrength = mock(CellSignalStrength.class);
        when(gsmSignalStrength.getLevel()).thenReturn(
                SignalStrength.SIGNAL_STRENGTH_NONE_OR_UNKNOWN);
        perRatCellStrength.add(gsmSignalStrength);

        // Note cell signal strength
        SignalStrength signalStrength = mock(SignalStrength.class);
        when(signalStrength.getCellSignalStrengths()).thenReturn(perRatCellStrength);
        stats.notePhoneSignalStrengthLocked(signalStrength, 5000, 5000);

        stats.noteMobileRadioPowerStateLocked(DataConnectionRealTimeInfo.DC_POWER_STATE_HIGH,
                8_000_000_000L, APP_UID, 8000, 8000);

        // Note established network
        stats.noteNetworkInterfaceForTransports("cellular",
                new int[]{NetworkCapabilities.TRANSPORT_CELLULAR});

        // Spend some time in each signal strength level. It doesn't matter how long.
        // The ModemActivityInfo reported level residency should be trusted over the BatteryStats
        // timers.
        when(gsmSignalStrength.getLevel()).thenReturn(
                SignalStrength.SIGNAL_STRENGTH_NONE_OR_UNKNOWN);
        stats.notePhoneSignalStrengthLocked(signalStrength, 8111, 8111);
        when(gsmSignalStrength.getLevel()).thenReturn(SignalStrength.SIGNAL_STRENGTH_POOR);
        stats.notePhoneSignalStrengthLocked(signalStrength, 8333, 8333);
        when(gsmSignalStrength.getLevel()).thenReturn(SignalStrength.SIGNAL_STRENGTH_MODERATE);
        stats.notePhoneSignalStrengthLocked(signalStrength, 8666, 8666);
        when(gsmSignalStrength.getLevel()).thenReturn(SignalStrength.SIGNAL_STRENGTH_GOOD);
        stats.notePhoneSignalStrengthLocked(signalStrength, 9110, 9110);

        stats.noteMobileRadioPowerStateLocked(DataConnectionRealTimeInfo.DC_POWER_STATE_HIGH,
                9_500_000_000L, APP_UID2, 9500, 9500);

        when(gsmSignalStrength.getLevel()).thenReturn(SignalStrength.SIGNAL_STRENGTH_GREAT);
        stats.notePhoneSignalStrengthLocked(signalStrength, 9665, 9665);

        // Note application network activity
        NetworkStats networkStats = mockNetworkStats(10000, 1,
                mockNetworkStatsEntry("cellular", APP_UID, 0, 0,
                        METERED_NO, ROAMING_NO, DEFAULT_NETWORK_NO, 1000, 150, 2000, 30, 100),
                mockNetworkStatsEntry("cellular", APP_UID2, 0, 0,
                        METERED_NO, ROAMING_NO, DEFAULT_NETWORK_NO, 500, 50, 300, 10, 111));
        mStatsRule.setNetworkStats(networkStats);

        ActivityStatsTechSpecificInfo cdmaInfo = new ActivityStatsTechSpecificInfo(
                AccessNetworkConstants.AccessNetworkType.CDMA2000,
                ServiceState.FREQUENCY_RANGE_UNKNOWN,
                new int[]{10, 11, 12, 13, 14}, 15);
        ActivityStatsTechSpecificInfo lteInfo = new ActivityStatsTechSpecificInfo(
                AccessNetworkConstants.AccessNetworkType.EUTRAN,
                ServiceState.FREQUENCY_RANGE_UNKNOWN,
                new int[]{20, 21, 22, 23, 24}, 25);
        ActivityStatsTechSpecificInfo nrLowFreqInfo = new ActivityStatsTechSpecificInfo(
                AccessNetworkConstants.AccessNetworkType.NGRAN, ServiceState.FREQUENCY_RANGE_LOW,
                new int[]{30, 31, 32, 33, 34}, 35);
        ActivityStatsTechSpecificInfo nrMidFreqInfo = new ActivityStatsTechSpecificInfo(
                AccessNetworkConstants.AccessNetworkType.NGRAN, ServiceState.FREQUENCY_RANGE_MID,
                new int[]{40, 41, 42, 43, 44}, 45);
        ActivityStatsTechSpecificInfo nrHighFreqInfo = new ActivityStatsTechSpecificInfo(
                AccessNetworkConstants.AccessNetworkType.NGRAN, ServiceState.FREQUENCY_RANGE_HIGH,
                new int[]{50, 51, 52, 53, 54}, 55);
        ActivityStatsTechSpecificInfo nrMmwaveFreqInfo = new ActivityStatsTechSpecificInfo(
                AccessNetworkConstants.AccessNetworkType.NGRAN, ServiceState.FREQUENCY_RANGE_MMWAVE,
                new int[]{60, 61, 62, 63, 64}, 65);

        ActivityStatsTechSpecificInfo[] ratInfos =
                new ActivityStatsTechSpecificInfo[]{cdmaInfo, lteInfo, nrLowFreqInfo, nrMidFreqInfo,
                        nrHighFreqInfo, nrMmwaveFreqInfo};

        ModemActivityInfo mai = new ModemActivityInfo(10000, 2000, 3000, ratInfos);
        stats.noteModemControllerActivity(mai, POWER_DATA_UNAVAILABLE, 10000, 10000,
                mNetworkStatsManager);

        mStatsRule.setTime(10_000, 10_000);

        MobileRadioPowerCalculator calculator =
                new MobileRadioPowerCalculator(mStatsRule.getPowerProfile());

        mStatsRule.apply(BatteryUsageStatsRule.POWER_PROFILE_MODEL_ONLY, calculator);

        BatteryConsumer deviceConsumer = mStatsRule.getDeviceBatteryConsumer();
        // CDMA2000 [Tx0, Tx1, Tx2, Tx3, Tx4, Rx] drain * duration
        //   [720, 1080, 1440, 1800, 2160, 1440] mA . [10, 11, 12, 13, 14, 15] ms = 111600 mA-ms
        // LTE [Tx0, Tx1, Tx2, Tx3, Tx4, Rx] drain * duration
        //   [800, 1200, 1600, 2000, 2400, 2000] mA . [20, 21, 22, 23, 24, 25] ms = 230000 mA-ms
        // 5G Low Frequency [Tx0, Tx1, Tx2, Tx3, Tx4, Rx] drain * duration
        // (nrFrequency="LOW" values was not defined so fall back to nrFrequency="DEFAULT")
        //   [999, 1333, 1888, 2222, 2666, 2222] mA . [30, 31, 32, 33, 34, 35] ms = 373449 mA-ms
        // 5G Mid Frequency [Tx0, Tx1, Tx2, Tx3, Tx4, Rx] drain * duration
        // (nrFrequency="MID" values was not defined so fall back to nrFrequency="DEFAULT")
        //   [999, 1333, 1888, 2222, 2666, 2222] mA . [40, 41, 42, 43, 44, 45] ms = 486749 mA-ms
        // 5G High Frequency [Tx0, Tx1, Tx2, Tx3, Tx4, Rx] drain * duration
        //   [1818, 2727, 3636, 4545, 5454, 2727] mA . [50, 51, 52, 53, 54, 55] ms = 1104435 mA-ms
        // 5G Mmwave Frequency [Tx0, Tx1, Tx2, Tx3, Tx4, Rx] drain * duration
        //   [2345, 3456, 4567, 5678, 6789, 3456] mA . [60, 61, 62, 63, 64, 65] ms = 1651520 mA-ms
        // _________________
        // =    3957753 mA-ms or 1.09938 mA-h active consumption
        //
        // Idle drain rate * idle duration
        //   360 mA * 3000 ms = 1080000 mA-ms
        // Sleep drain rate * sleep duration
        //   70 mA * 2000 ms = 140000 mA-ms
        // _________________
        // =    5177753 mA-ms or 1.43826 mA-h total consumption
        assertThat(deviceConsumer.getConsumedPower(BatteryConsumer.POWER_COMPONENT_MOBILE_RADIO))
                .isWithin(PRECISION).of(1.43826);
        assertThat(deviceConsumer.getPowerModel(BatteryConsumer.POWER_COMPONENT_MOBILE_RADIO))
                .isEqualTo(BatteryConsumer.POWER_MODEL_POWER_PROFILE);

        //    720 mA * 100 ms  (level 0 TX drain rate * level 0 TX duration)
        // + 1080 mA * 200 ms  (level 1 TX drain rate * level 1 TX duration)
        // + 1440 mA * 300 ms  (level 2 TX drain rate * level 2 TX duration)
        // + 1800 mA * 400 ms  (level 3 TX drain rate * level 3 TX duration)
        // + 2160 mA * 500 ms  (level 4 TX drain rate * level 4 TX duration)
        // + 1440 mA * 600 ms  (RX drain rate * RX duration)
        // _________________
        // =    3384000 mA-ms or 0.94 mA-h
        BatteryConsumer appsConsumer = mStatsRule.getAppsBatteryConsumer();
        assertThat(appsConsumer.getConsumedPower(BatteryConsumer.POWER_COMPONENT_MOBILE_RADIO))
                .isWithin(PRECISION).of(1.09938);
        assertThat(appsConsumer.getPowerModel(BatteryConsumer.POWER_COMPONENT_MOBILE_RADIO))
                .isEqualTo(BatteryConsumer.POWER_MODEL_POWER_PROFILE);

        // 3/4 of total packets were sent by APP_UID so 75% of total
        UidBatteryConsumer uidConsumer = mStatsRule.getUidBatteryConsumer(APP_UID);
        assertThat(uidConsumer.getConsumedPower(BatteryConsumer.POWER_COMPONENT_MOBILE_RADIO))
                .isWithin(PRECISION).of(0.82453);
        assertThat(uidConsumer.getPowerModel(BatteryConsumer.POWER_COMPONENT_MOBILE_RADIO))
                .isEqualTo(BatteryConsumer.POWER_MODEL_POWER_PROFILE);

        // Rest should go to the other app
        UidBatteryConsumer uidConsumer2 = mStatsRule.getUidBatteryConsumer(APP_UID2);
        assertThat(uidConsumer2.getConsumedPower(BatteryConsumer.POWER_COMPONENT_MOBILE_RADIO))
                .isWithin(PRECISION).of(0.27484);
        assertThat(uidConsumer.getPowerModel(BatteryConsumer.POWER_COMPONENT_MOBILE_RADIO))
                .isEqualTo(BatteryConsumer.POWER_MODEL_POWER_PROFILE);
    }

    @Test
    public void testCounterBasedModel_legacyPowerProfile() {
        mStatsRule.setTestPowerProfile("power_profile_test_legacy_modem")
                .initMeasuredEnergyStatsLocked();
        BatteryStatsImpl stats = mStatsRule.getBatteryStats();

        // The first ModemActivityInfo doesn't count up.
        setInitialEmptyModemActivityInfo(stats);

        // Scan for a cell
        stats.notePhoneStateLocked(ServiceState.STATE_OUT_OF_SERVICE,
                TelephonyManager.SIM_STATE_READY,
                2000, 2000);

        // Found a cell
        stats.notePhoneStateLocked(ServiceState.STATE_IN_SERVICE, TelephonyManager.SIM_STATE_READY,
                5000, 5000);

        ArrayList<CellSignalStrength> perRatCellStrength = new ArrayList();
        CellSignalStrength gsmSignalStrength = mock(CellSignalStrength.class);
        when(gsmSignalStrength.getLevel()).thenReturn(
                SignalStrength.SIGNAL_STRENGTH_NONE_OR_UNKNOWN);
        perRatCellStrength.add(gsmSignalStrength);

        // Note cell signal strength
        SignalStrength signalStrength = mock(SignalStrength.class);
        when(signalStrength.getCellSignalStrengths()).thenReturn(perRatCellStrength);
        stats.notePhoneSignalStrengthLocked(signalStrength, 5000, 5000);

        stats.noteMobileRadioPowerStateLocked(DataConnectionRealTimeInfo.DC_POWER_STATE_HIGH,
                8_000_000_000L, APP_UID, 8000, 8000);

        // Note established network
        stats.noteNetworkInterfaceForTransports("cellular",
                new int[]{NetworkCapabilities.TRANSPORT_CELLULAR});

        // Spend some time in each signal strength level. It doesn't matter how long.
        // The ModemActivityInfo reported level residency should be trusted over the BatteryStats
        // timers.
        when(gsmSignalStrength.getLevel()).thenReturn(
                SignalStrength.SIGNAL_STRENGTH_NONE_OR_UNKNOWN);
        stats.notePhoneSignalStrengthLocked(signalStrength, 8111, 8111);
        when(gsmSignalStrength.getLevel()).thenReturn(SignalStrength.SIGNAL_STRENGTH_POOR);
        stats.notePhoneSignalStrengthLocked(signalStrength, 8333, 8333);
        when(gsmSignalStrength.getLevel()).thenReturn(SignalStrength.SIGNAL_STRENGTH_MODERATE);
        stats.notePhoneSignalStrengthLocked(signalStrength, 8666, 8666);
        when(gsmSignalStrength.getLevel()).thenReturn(SignalStrength.SIGNAL_STRENGTH_GOOD);
        stats.notePhoneSignalStrengthLocked(signalStrength, 9110, 9110);

        stats.noteMobileRadioPowerStateLocked(DataConnectionRealTimeInfo.DC_POWER_STATE_HIGH,
                9_500_000_000L, APP_UID2, 9500, 9500);

        when(gsmSignalStrength.getLevel()).thenReturn(SignalStrength.SIGNAL_STRENGTH_GREAT);
        stats.notePhoneSignalStrengthLocked(signalStrength, 9665, 9665);

        // Note application network activity
        NetworkStats networkStats = mockNetworkStats(10000, 1,
                mockNetworkStatsEntry("cellular", APP_UID, 0, 0,
                        METERED_NO, ROAMING_NO, DEFAULT_NETWORK_NO, 1000, 150, 2000, 30, 100),
                mockNetworkStatsEntry("cellular", APP_UID2, 0, 0,
                        METERED_NO, ROAMING_NO, DEFAULT_NETWORK_NO, 500, 50, 300, 10, 111));
        mStatsRule.setNetworkStats(networkStats);

        ModemActivityInfo mai = new ModemActivityInfo(10000, 2000, 3000,
                new int[]{100, 200, 300, 400, 500}, 600);
        stats.noteModemControllerActivity(mai, POWER_DATA_UNAVAILABLE, 10000, 10000,
                mNetworkStatsManager);

        mStatsRule.setTime(10_000, 10_000);

        MobileRadioPowerCalculator calculator =
                new MobileRadioPowerCalculator(mStatsRule.getPowerProfile());

        mStatsRule.apply(BatteryUsageStatsRule.POWER_PROFILE_MODEL_ONLY, calculator);

        BatteryConsumer deviceConsumer = mStatsRule.getDeviceBatteryConsumer();
        //    720 mA * 100 ms  (level 0 TX drain rate * level 0 TX duration)
        // + 1080 mA * 200 ms  (level 1 TX drain rate * level 1 TX duration)
        // + 1440 mA * 300 ms  (level 2 TX drain rate * level 2 TX duration)
        // + 1800 mA * 400 ms  (level 3 TX drain rate * level 3 TX duration)
        // + 2160 mA * 500 ms  (level 4 TX drain rate * level 4 TX duration)
        // + 1440 mA * 600 ms  (RX drain rate * RX duration)
        // +  360 mA * 3000 ms (idle drain rate * idle duration)
        // +   70 mA * 2000 ms (sleep drain rate * sleep duration)
        // _________________
        // =    4604000 mA-ms or 1.27888 mA-h
        assertThat(deviceConsumer.getConsumedPower(BatteryConsumer.POWER_COMPONENT_MOBILE_RADIO))
                .isWithin(PRECISION).of(1.27888);
        assertThat(deviceConsumer.getPowerModel(BatteryConsumer.POWER_COMPONENT_MOBILE_RADIO))
                .isEqualTo(BatteryConsumer.POWER_MODEL_POWER_PROFILE);

        //    720 mA * 100 ms  (level 0 TX drain rate * level 0 TX duration)
        // + 1080 mA * 200 ms  (level 1 TX drain rate * level 1 TX duration)
        // + 1440 mA * 300 ms  (level 2 TX drain rate * level 2 TX duration)
        // + 1800 mA * 400 ms  (level 3 TX drain rate * level 3 TX duration)
        // + 2160 mA * 500 ms  (level 4 TX drain rate * level 4 TX duration)
        // + 1440 mA * 600 ms  (RX drain rate * RX duration)
        // _________________
        // =    3384000 mA-ms or 0.94 mA-h
        BatteryConsumer appsConsumer = mStatsRule.getAppsBatteryConsumer();
        assertThat(appsConsumer.getConsumedPower(BatteryConsumer.POWER_COMPONENT_MOBILE_RADIO))
                .isWithin(PRECISION).of(0.94);
        assertThat(appsConsumer.getPowerModel(BatteryConsumer.POWER_COMPONENT_MOBILE_RADIO))
                .isEqualTo(BatteryConsumer.POWER_MODEL_POWER_PROFILE);

        // 3/4 of total packets were sent by APP_UID so 75% of total
        UidBatteryConsumer uidConsumer = mStatsRule.getUidBatteryConsumer(APP_UID);
        assertThat(uidConsumer.getConsumedPower(BatteryConsumer.POWER_COMPONENT_MOBILE_RADIO))
                .isWithin(PRECISION).of(0.705);
        assertThat(uidConsumer.getPowerModel(BatteryConsumer.POWER_COMPONENT_MOBILE_RADIO))
                .isEqualTo(BatteryConsumer.POWER_MODEL_POWER_PROFILE);

        // Rest should go to the other app
        UidBatteryConsumer uidConsumer2 = mStatsRule.getUidBatteryConsumer(APP_UID2);
        assertThat(uidConsumer2.getConsumedPower(BatteryConsumer.POWER_COMPONENT_MOBILE_RADIO))
                .isWithin(PRECISION).of(0.235);
        assertThat(uidConsumer.getPowerModel(BatteryConsumer.POWER_COMPONENT_MOBILE_RADIO))
                .isEqualTo(BatteryConsumer.POWER_MODEL_POWER_PROFILE);
    }

    @Test
    public void testTimerBasedModel_byProcessState() {
        mStatsRule.setTestPowerProfile("power_profile_test_legacy_modem")
                .initMeasuredEnergyStatsLocked();
        BatteryStatsImpl stats = mStatsRule.getBatteryStats();
        BatteryStatsImpl.Uid uid = stats.getUidStatsLocked(APP_UID);

        mStatsRule.setTime(1000, 1000);
        uid.setProcessStateForTest(
                BatteryStats.Uid.PROCESS_STATE_FOREGROUND, 1000);

        // Scan for a cell
        stats.notePhoneStateLocked(ServiceState.STATE_OUT_OF_SERVICE,
                TelephonyManager.SIM_STATE_READY,
                2000, 2000);

        ArrayList<CellSignalStrength> perRatCellStrength = new ArrayList();
        CellSignalStrength gsmSignalStrength = mock(CellSignalStrength.class);
        when(gsmSignalStrength.getLevel()).thenReturn(
                SignalStrength.SIGNAL_STRENGTH_NONE_OR_UNKNOWN);
        perRatCellStrength.add(gsmSignalStrength);

        // Found a cell
        stats.notePhoneStateLocked(ServiceState.STATE_IN_SERVICE, TelephonyManager.SIM_STATE_READY,
                5000, 5000);

        // Note cell signal strength
        SignalStrength signalStrength = mock(SignalStrength.class);
        when(signalStrength.getCellSignalStrengths()).thenReturn(perRatCellStrength);
        stats.notePhoneSignalStrengthLocked(signalStrength, 5000, 5000);

        stats.noteMobileRadioPowerStateLocked(DataConnectionRealTimeInfo.DC_POWER_STATE_HIGH,
                8_000_000_000L, APP_UID, 8000, 8000);

        // Note established network
        stats.noteNetworkInterfaceForTransports("cellular",
                new int[]{NetworkCapabilities.TRANSPORT_CELLULAR});

        // Spend some time in each signal strength level. It doesn't matter how long.
        // The ModemActivityInfo reported level residency should be trusted over the BatteryStats
        // timers.
        when(gsmSignalStrength.getLevel()).thenReturn(
                SignalStrength.SIGNAL_STRENGTH_NONE_OR_UNKNOWN);
        stats.notePhoneSignalStrengthLocked(signalStrength, 8111, 8111);
        when(gsmSignalStrength.getLevel()).thenReturn(SignalStrength.SIGNAL_STRENGTH_POOR);
        stats.notePhoneSignalStrengthLocked(signalStrength, 8333, 8333);
        when(gsmSignalStrength.getLevel()).thenReturn(SignalStrength.SIGNAL_STRENGTH_MODERATE);
        stats.notePhoneSignalStrengthLocked(signalStrength, 8666, 8666);
        when(gsmSignalStrength.getLevel()).thenReturn(SignalStrength.SIGNAL_STRENGTH_GOOD);
        stats.notePhoneSignalStrengthLocked(signalStrength, 9110, 9110);
        when(gsmSignalStrength.getLevel()).thenReturn(SignalStrength.SIGNAL_STRENGTH_GREAT);
        stats.notePhoneSignalStrengthLocked(signalStrength, 9665, 9665);

        // Note application network activity
        mStatsRule.setNetworkStats(mockNetworkStats(10000, 1,
                mockNetworkStatsEntry("cellular", APP_UID, 0, 0,
                        METERED_NO, ROAMING_NO, DEFAULT_NETWORK_NO, 1000, 100, 2000, 20, 100)));

        stats.noteModemControllerActivity(null, POWER_DATA_UNAVAILABLE, 10000, 10000,
                mNetworkStatsManager);

        uid.setProcessStateForTest(
                BatteryStats.Uid.PROCESS_STATE_BACKGROUND, 11000);

        mStatsRule.setNetworkStats(mockNetworkStats(12000, 1,
                mockNetworkStatsEntry("cellular", APP_UID, 0, 0,
                        METERED_NO, ROAMING_NO, DEFAULT_NETWORK_NO, 1000, 250, 2000, 80, 200)));

        stats.noteModemControllerActivity(null, POWER_DATA_UNAVAILABLE, 12000, 12000,
                mNetworkStatsManager);

        assertThat(uid.getMobileRadioEnergyConsumptionUC()).isAtMost(0);
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

        mStatsRule.setTime(12_000, 12_000);

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


        assertThat(uidConsumer.getConsumedPower(BatteryConsumer.POWER_COMPONENT_MOBILE_RADIO))
                .isWithin(PRECISION).of(1.6);

        assertThat(uidConsumer.getConsumedPower(foreground)).isWithin(PRECISION).of(1.2);
        assertThat(uidConsumer.getConsumedPower(background)).isWithin(PRECISION).of(0.4);
        assertThat(uidConsumer.getConsumedPower(fgs)).isWithin(PRECISION).of(0);
    }

    @Test
    public void testMeasuredEnergyBasedModel_mobileRadioActiveTimeModel() {
        mStatsRule.setTestPowerProfile("power_profile_test_legacy_modem")
                .setPerUidModemModel(
                        BatteryStatsImpl.PER_UID_MODEM_POWER_MODEL_MOBILE_RADIO_ACTIVE_TIME)
                .initMeasuredEnergyStatsLocked();
        BatteryStatsImpl stats = mStatsRule.getBatteryStats();

        // The first ModemActivityInfo doesn't count up.
        setInitialEmptyModemActivityInfo(stats);

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

        stats.notePhoneOnLocked(9800, 9800);

        // Note application network activity
        NetworkStats networkStats = mockNetworkStats(10000, 1,
                mockNetworkStatsEntry("cellular", APP_UID, 0, 0,
                        METERED_NO, ROAMING_NO, DEFAULT_NETWORK_NO, 1000, 100, 2000, 20, 100));
        mStatsRule.setNetworkStats(networkStats);

        ModemActivityInfo mai = new ModemActivityInfo(10000, 2000, 3000,
                new int[]{100, 200, 300, 400, 500}, 600);
        stats.noteModemControllerActivity(mai, 10_000_000, 10000, 10000, mNetworkStatsManager);

        mStatsRule.setTime(12_000, 12_000);

        MobileRadioPowerCalculator mobileRadioPowerCalculator =
                new MobileRadioPowerCalculator(mStatsRule.getPowerProfile());
        PhonePowerCalculator phonePowerCalculator =
                new PhonePowerCalculator(mStatsRule.getPowerProfile());
        mStatsRule.apply(mobileRadioPowerCalculator, phonePowerCalculator);

        BatteryConsumer deviceConsumer = mStatsRule.getDeviceBatteryConsumer();
        // 10_000_000 micro-Coulomb * 1/1000 milli/micro * 1/3600 hour/second = 2.77778 mAh
        // 1800ms data duration / 2000 total duration *  2.77778 mAh = 2.5
        // 200ms phone on duration / 2000 total duration *  2.77778 mAh = 0.27777
        assertThat(deviceConsumer.getConsumedPower(BatteryConsumer.POWER_COMPONENT_MOBILE_RADIO))
                .isWithin(PRECISION).of(2.5);
        assertThat(deviceConsumer.getPowerModel(BatteryConsumer.POWER_COMPONENT_MOBILE_RADIO))
                .isEqualTo(BatteryConsumer.POWER_MODEL_ENERGY_CONSUMPTION);
        assertThat(deviceConsumer.getConsumedPower(BatteryConsumer.POWER_COMPONENT_PHONE))
                .isWithin(PRECISION).of(0.27778);
        assertThat(deviceConsumer.getPowerModel(BatteryConsumer.POWER_COMPONENT_PHONE))
                .isEqualTo(BatteryConsumer.POWER_MODEL_ENERGY_CONSUMPTION);

        BatteryConsumer appsConsumer = mStatsRule.getAppsBatteryConsumer();
        assertThat(appsConsumer.getConsumedPower(BatteryConsumer.POWER_COMPONENT_MOBILE_RADIO))
                .isWithin(PRECISION).of(1.38541);
        assertThat(appsConsumer.getPowerModel(BatteryConsumer.POWER_COMPONENT_MOBILE_RADIO))
                .isEqualTo(BatteryConsumer.POWER_MODEL_ENERGY_CONSUMPTION);

        UidBatteryConsumer uidConsumer = mStatsRule.getUidBatteryConsumer(APP_UID);
        assertThat(uidConsumer.getConsumedPower(BatteryConsumer.POWER_COMPONENT_MOBILE_RADIO))
                .isWithin(PRECISION).of(1.38541);
        assertThat(uidConsumer.getPowerModel(BatteryConsumer.POWER_COMPONENT_MOBILE_RADIO))
                .isEqualTo(BatteryConsumer.POWER_MODEL_ENERGY_CONSUMPTION);
    }

    @Test
    public void testMeasuredEnergyBasedModel_modemActivityInfoRxTxModel() {
        mStatsRule.setTestPowerProfile("power_profile_test_modem_calculator_multiactive")
                .setPerUidModemModel(
                        BatteryStatsImpl.PER_UID_MODEM_POWER_MODEL_MODEM_ACTIVITY_INFO_RX_TX)
                .initMeasuredEnergyStatsLocked();
        BatteryStatsImpl stats = mStatsRule.getBatteryStats();

        // The first ModemActivityInfo doesn't count up.
        setInitialEmptyModemActivityInfo(stats);

        stats.noteMobileRadioPowerStateLocked(DataConnectionRealTimeInfo.DC_POWER_STATE_HIGH, 0, -1,
                0, 0);

        // Scan for a cell
        stats.notePhoneStateLocked(ServiceState.STATE_OUT_OF_SERVICE,
                TelephonyManager.SIM_STATE_READY,
                2000, 2000);

        // Found a cell
        stats.notePhoneStateLocked(ServiceState.STATE_IN_SERVICE, TelephonyManager.SIM_STATE_READY,
                5000, 5000);

        ArrayList<CellSignalStrength> perRatCellStrength = new ArrayList();
        CellSignalStrength gsmSignalStrength = mock(CellSignalStrength.class);
        when(gsmSignalStrength.getLevel()).thenReturn(
                SignalStrength.SIGNAL_STRENGTH_NONE_OR_UNKNOWN);
        perRatCellStrength.add(gsmSignalStrength);

        // Note cell signal strength
        SignalStrength signalStrength = mock(SignalStrength.class);
        when(signalStrength.getCellSignalStrengths()).thenReturn(perRatCellStrength);
        stats.notePhoneSignalStrengthLocked(signalStrength, 5000, 5000);

        stats.noteMobileRadioPowerStateLocked(DataConnectionRealTimeInfo.DC_POWER_STATE_HIGH,
                8_000_000_000L, APP_UID, 8000, 8000);

        // Note established network
        stats.noteNetworkInterfaceForTransports("cellular",
                new int[]{NetworkCapabilities.TRANSPORT_CELLULAR});

        // Spend some time in each signal strength level. It doesn't matter how long.
        // The ModemActivityInfo reported level residency should be trusted over the BatteryStats
        // timers.
        when(gsmSignalStrength.getLevel()).thenReturn(
                SignalStrength.SIGNAL_STRENGTH_NONE_OR_UNKNOWN);
        stats.notePhoneSignalStrengthLocked(signalStrength, 8111, 8111);
        when(gsmSignalStrength.getLevel()).thenReturn(SignalStrength.SIGNAL_STRENGTH_POOR);
        stats.notePhoneSignalStrengthLocked(signalStrength, 8333, 8333);
        when(gsmSignalStrength.getLevel()).thenReturn(SignalStrength.SIGNAL_STRENGTH_MODERATE);
        stats.notePhoneSignalStrengthLocked(signalStrength, 8666, 8666);

        stats.notePhoneOnLocked(9000, 9000);

        when(gsmSignalStrength.getLevel()).thenReturn(SignalStrength.SIGNAL_STRENGTH_GOOD);
        stats.notePhoneSignalStrengthLocked(signalStrength, 9110, 9110);

        stats.noteMobileRadioPowerStateLocked(DataConnectionRealTimeInfo.DC_POWER_STATE_HIGH,
                9_500_000_000L, APP_UID2, 9500, 9500);

        when(gsmSignalStrength.getLevel()).thenReturn(SignalStrength.SIGNAL_STRENGTH_GREAT);
        stats.notePhoneSignalStrengthLocked(signalStrength, 9665, 9665);

        // Note application network activity
        NetworkStats networkStats = mockNetworkStats(10000, 1,
                mockNetworkStatsEntry("cellular", APP_UID, 0, 0,
                        METERED_NO, ROAMING_NO, DEFAULT_NETWORK_NO, 1000, 150, 300, 10, 100),
                mockNetworkStatsEntry("cellular", APP_UID2, 0, 0,
                        METERED_NO, ROAMING_NO, DEFAULT_NETWORK_NO, 500, 50, 2000, 30, 111));
        mStatsRule.setNetworkStats(networkStats);

        ActivityStatsTechSpecificInfo cdmaInfo = new ActivityStatsTechSpecificInfo(
                AccessNetworkConstants.AccessNetworkType.CDMA2000,
                ServiceState.FREQUENCY_RANGE_UNKNOWN,
                new int[]{10, 11, 12, 13, 14}, 15);
        ActivityStatsTechSpecificInfo lteInfo = new ActivityStatsTechSpecificInfo(
                AccessNetworkConstants.AccessNetworkType.EUTRAN,
                ServiceState.FREQUENCY_RANGE_UNKNOWN,
                new int[]{20, 21, 22, 23, 24}, 25);
        ActivityStatsTechSpecificInfo nrLowFreqInfo = new ActivityStatsTechSpecificInfo(
                AccessNetworkConstants.AccessNetworkType.NGRAN, ServiceState.FREQUENCY_RANGE_LOW,
                new int[]{30, 31, 32, 33, 34}, 35);
        ActivityStatsTechSpecificInfo nrMidFreqInfo = new ActivityStatsTechSpecificInfo(
                AccessNetworkConstants.AccessNetworkType.NGRAN, ServiceState.FREQUENCY_RANGE_MID,
                new int[]{40, 41, 42, 43, 44}, 45);
        ActivityStatsTechSpecificInfo nrHighFreqInfo = new ActivityStatsTechSpecificInfo(
                AccessNetworkConstants.AccessNetworkType.NGRAN, ServiceState.FREQUENCY_RANGE_HIGH,
                new int[]{50, 51, 52, 53, 54}, 55);
        ActivityStatsTechSpecificInfo nrMmwaveFreqInfo = new ActivityStatsTechSpecificInfo(
                AccessNetworkConstants.AccessNetworkType.NGRAN, ServiceState.FREQUENCY_RANGE_MMWAVE,
                new int[]{60, 61, 62, 63, 64}, 65);

        ActivityStatsTechSpecificInfo[] ratInfos =
                new ActivityStatsTechSpecificInfo[]{cdmaInfo, lteInfo, nrLowFreqInfo, nrMidFreqInfo,
                        nrHighFreqInfo, nrMmwaveFreqInfo};

        ModemActivityInfo mai = new ModemActivityInfo(10000, 2000, 3000, ratInfos);
        stats.noteModemControllerActivity(mai, 10_000_000, 10000, 10000,
                mNetworkStatsManager);

        mStatsRule.setTime(10_000, 10_000);

        MobileRadioPowerCalculator mobileRadioPowerCalculator =
                new MobileRadioPowerCalculator(mStatsRule.getPowerProfile());
        PhonePowerCalculator phonePowerCalculator =
                new PhonePowerCalculator(mStatsRule.getPowerProfile());
        mStatsRule.apply(mobileRadioPowerCalculator, phonePowerCalculator);

        BatteryConsumer deviceConsumer = mStatsRule.getDeviceBatteryConsumer();
        // 10_000_000 micro-Coulomb * 1/1000 milli/micro * 1/3600 hour/second = 2.77778 mAh
        // 9000ms data duration / 10000 total duration *  2.77778 mAh = 2.5
        // 1000ms phone on duration / 10000 total duration *  2.77778 mAh = 0.27777
        assertThat(deviceConsumer.getConsumedPower(BatteryConsumer.POWER_COMPONENT_MOBILE_RADIO))
                .isWithin(PRECISION).of(2.5);
        assertThat(deviceConsumer.getPowerModel(BatteryConsumer.POWER_COMPONENT_MOBILE_RADIO))
                .isEqualTo(BatteryConsumer.POWER_MODEL_ENERGY_CONSUMPTION);
        assertThat(deviceConsumer.getConsumedPower(BatteryConsumer.POWER_COMPONENT_PHONE))
                .isWithin(PRECISION).of(0.27778);
        assertThat(deviceConsumer.getPowerModel(BatteryConsumer.POWER_COMPONENT_PHONE))
                .isEqualTo(BatteryConsumer.POWER_MODEL_ENERGY_CONSUMPTION);

        // CDMA2000 [Tx0, Tx1, Tx2, Tx3, Tx4, Rx] drain * duration
        //   [720, 1080, 1440, 1800, 2160, 1440] mA . [10, 11, 12, 13, 14, 15] ms = 111600 mA-ms
        // LTE [Tx0, Tx1, Tx2, Tx3, Tx4, Rx] drain * duration
        //   [800, 1200, 1600, 2000, 2400, 2000] mA . [20, 21, 22, 23, 24, 25] ms = 230000 mA-ms
        // 5G Low Frequency [Tx0, Tx1, Tx2, Tx3, Tx4, Rx] drain * duration
        // (nrFrequency="LOW" values was not defined so fall back to nrFrequency="DEFAULT")
        //   [999, 1333, 1888, 2222, 2666, 2222] mA . [30, 31, 32, 33, 34, 35] ms = 373449 mA-ms
        // 5G Mid Frequency [Tx0, Tx1, Tx2, Tx3, Tx4, Rx] drain * duration
        // (nrFrequency="MID" values was not defined so fall back to nrFrequency="DEFAULT")
        //   [999, 1333, 1888, 2222, 2666, 2222] mA . [40, 41, 42, 43, 44, 45] ms = 486749 mA-ms
        // 5G High Frequency [Tx0, Tx1, Tx2, Tx3, Tx4, Rx] drain * duration
        //   [1818, 2727, 3636, 4545, 5454, 2727] mA . [50, 51, 52, 53, 54, 55] ms = 1104435 mA-ms
        // 5G Mmwave Frequency [Tx0, Tx1, Tx2, Tx3, Tx4, Rx] drain * duration
        //   [2345, 3456, 4567, 5678, 6789, 3456] mA . [60, 61, 62, 63, 64, 65] ms = 1651520 mA-ms
        // _________________
        // =    3957753 mA-ms estimated active consumption
        //
        // Idle drain rate * idle duration
        //   360 mA * 3000 ms = 1080000 mA-ms
        // Sleep drain rate * sleep duration
        //   70 mA * 2000 ms = 140000 mA-ms
        // _________________
        // =    5177753 mA-ms estimated total consumption
        //
        // 2.5 mA-h measured total consumption * 3957753 / 5177753 = 1.91094 mA-h
        BatteryConsumer appsConsumer = mStatsRule.getAppsBatteryConsumer();
        assertThat(appsConsumer.getConsumedPower(BatteryConsumer.POWER_COMPONENT_MOBILE_RADIO))
                .isWithin(PRECISION).of(1.91094);
        assertThat(appsConsumer.getPowerModel(BatteryConsumer.POWER_COMPONENT_MOBILE_RADIO))
                .isEqualTo(BatteryConsumer.POWER_MODEL_ENERGY_CONSUMPTION);

        // 240 ms Rx Time, 1110 ms Tx Time, 1350 ms active time
        // 150 App 1 Rx Packets, 10 App 1 Tx packets
        // 50 App 2 Rx Packets, 30 App 2 Tx packets
        // 200 total Rx Packets, 40 total Tx packets
        // 623985 mA-ms Rx consumption, 3333768 mA-ms Tx consumption
        //
        // Rx Power consumption * Ratio of App1 / Total Rx Packets:
        // 623985 * 150 / 200 = 467988.75 mA-ms App 1 Rx Power Consumption
        //
        // App 1 Tx Packets + App 1 Rx Packets * Ratio of Tx / Total active time
        // 10 + 150 * 1110 / 1350 = 133.3333 Estimated App 1 Rx/Tx Packets during Tx
        // Total Tx Packets + Total Rx Packets * Ratio of Tx / Total active time
        // 40 + 200 * 1110 / 1350 = 204.44444 Estimated Total Rx/Tx Packets during Tx
        // Tx Power consumption * Ratio of App 1 / Total Estimated Tx Packets:
        // 3333768 * 133.33333 / 204.44444 = 2174196.52174 mA-ms App 1 Tx Power Consumption
        //
        // Total App Power consumption * Ratio of App 1 / Total Estimated Power Consumption
        // 1.91094 * (467988.75 + 2174196.52174) / 3957753 = 1.27574 App 1 Power Consumption
        UidBatteryConsumer uidConsumer = mStatsRule.getUidBatteryConsumer(APP_UID);
        assertThat(uidConsumer.getConsumedPower(BatteryConsumer.POWER_COMPONENT_MOBILE_RADIO))
                .isWithin(PRECISION).of(1.27574);
        assertThat(uidConsumer.getPowerModel(BatteryConsumer.POWER_COMPONENT_MOBILE_RADIO))
                .isEqualTo(BatteryConsumer.POWER_MODEL_ENERGY_CONSUMPTION);

        // Rest should go to the other app
        UidBatteryConsumer uidConsumer2 = mStatsRule.getUidBatteryConsumer(APP_UID2);
        assertThat(uidConsumer2.getConsumedPower(BatteryConsumer.POWER_COMPONENT_MOBILE_RADIO))
                .isWithin(PRECISION).of(0.63520);
        assertThat(uidConsumer.getPowerModel(BatteryConsumer.POWER_COMPONENT_MOBILE_RADIO))
                .isEqualTo(BatteryConsumer.POWER_MODEL_ENERGY_CONSUMPTION);
    }

    @Test
    public void testMeasuredEnergyBasedModel_modemActivityInfoRxTxModel_legacyPowerProfile() {
        mStatsRule.setTestPowerProfile("power_profile_test_legacy_modem")
                .setPerUidModemModel(
                        BatteryStatsImpl.PER_UID_MODEM_POWER_MODEL_MODEM_ACTIVITY_INFO_RX_TX)
                .initMeasuredEnergyStatsLocked();
        BatteryStatsImpl stats = mStatsRule.getBatteryStats();

        // The first ModemActivityInfo doesn't count up.
        setInitialEmptyModemActivityInfo(stats);

        stats.noteMobileRadioPowerStateLocked(DataConnectionRealTimeInfo.DC_POWER_STATE_HIGH, 0, -1,
                0, 0);

        // Scan for a cell
        stats.notePhoneStateLocked(ServiceState.STATE_OUT_OF_SERVICE,
                TelephonyManager.SIM_STATE_READY,
                2000, 2000);

        ArrayList<CellSignalStrength> perRatCellStrength = new ArrayList();
        CellSignalStrength gsmSignalStrength = mock(CellSignalStrength.class);
        when(gsmSignalStrength.getLevel()).thenReturn(
                SignalStrength.SIGNAL_STRENGTH_NONE_OR_UNKNOWN);
        perRatCellStrength.add(gsmSignalStrength);

        // Found a cell
        stats.notePhoneStateLocked(ServiceState.STATE_IN_SERVICE, TelephonyManager.SIM_STATE_READY,
                5000, 5000);

        // Note cell signal strength
        SignalStrength signalStrength = mock(SignalStrength.class);
        when(signalStrength.getCellSignalStrengths()).thenReturn(perRatCellStrength);
        stats.notePhoneSignalStrengthLocked(signalStrength, 5000, 5000);

        stats.noteMobileRadioPowerStateLocked(DataConnectionRealTimeInfo.DC_POWER_STATE_HIGH,
                8_000_000_000L, APP_UID, 8000, 8000);

        // Note established network
        stats.noteNetworkInterfaceForTransports("cellular",
                new int[]{NetworkCapabilities.TRANSPORT_CELLULAR});

        // Spend some time in each signal strength level. It doesn't matter how long.
        // The ModemActivityInfo reported level residency should be trusted over the BatteryStats
        // timers.
        when(gsmSignalStrength.getLevel()).thenReturn(
                SignalStrength.SIGNAL_STRENGTH_NONE_OR_UNKNOWN);
        stats.notePhoneSignalStrengthLocked(signalStrength, 8111, 8111);
        when(gsmSignalStrength.getLevel()).thenReturn(SignalStrength.SIGNAL_STRENGTH_POOR);
        stats.notePhoneSignalStrengthLocked(signalStrength, 8333, 8333);
        when(gsmSignalStrength.getLevel()).thenReturn(SignalStrength.SIGNAL_STRENGTH_MODERATE);
        stats.notePhoneSignalStrengthLocked(signalStrength, 8666, 8666);

        stats.notePhoneOnLocked(9000, 9000);

        when(gsmSignalStrength.getLevel()).thenReturn(SignalStrength.SIGNAL_STRENGTH_GOOD);
        stats.notePhoneSignalStrengthLocked(signalStrength, 9110, 9110);
        when(gsmSignalStrength.getLevel()).thenReturn(SignalStrength.SIGNAL_STRENGTH_GREAT);
        stats.notePhoneSignalStrengthLocked(signalStrength, 9665, 9665);

        // Note application network activity
        NetworkStats networkStats = mockNetworkStats(10000, 1,
                mockNetworkStatsEntry("cellular", APP_UID, 0, 0,
                        METERED_NO, ROAMING_NO, DEFAULT_NETWORK_NO, 1000, 100, 2000, 20, 100));
        mStatsRule.setNetworkStats(networkStats);

        ModemActivityInfo mai = new ModemActivityInfo(10000, 2000, 3000,
                new int[]{100, 200, 300, 400, 500}, 600);
        stats.noteModemControllerActivity(mai, 10_000_000, 10000, 10000, mNetworkStatsManager);

        mStatsRule.setTime(12_000, 12_000);


        MobileRadioPowerCalculator mobileRadioPowerCalculator =
                new MobileRadioPowerCalculator(mStatsRule.getPowerProfile());
        PhonePowerCalculator phonePowerCalculator =
                new PhonePowerCalculator(mStatsRule.getPowerProfile());
        mStatsRule.apply(mobileRadioPowerCalculator, phonePowerCalculator);

        BatteryConsumer deviceConsumer = mStatsRule.getDeviceBatteryConsumer();
        // 10_000_000 micro-Coulomb * 1/1000 milli/micro * 1/3600 hour/second = 2.77778 mAh
        // 9000ms data duration / 10000 total duration *  2.77778 mAh = 2.5
        // 1000ms phone on duration / 10000 total duration *  2.77778 mAh = 0.27777
        assertThat(deviceConsumer.getConsumedPower(BatteryConsumer.POWER_COMPONENT_MOBILE_RADIO))
                .isWithin(PRECISION).of(2.5);
        assertThat(deviceConsumer.getPowerModel(BatteryConsumer.POWER_COMPONENT_MOBILE_RADIO))
                .isEqualTo(BatteryConsumer.POWER_MODEL_ENERGY_CONSUMPTION);
        assertThat(deviceConsumer.getConsumedPower(BatteryConsumer.POWER_COMPONENT_PHONE))
                .isWithin(PRECISION).of(0.27778);
        assertThat(deviceConsumer.getPowerModel(BatteryConsumer.POWER_COMPONENT_PHONE))
                .isEqualTo(BatteryConsumer.POWER_MODEL_ENERGY_CONSUMPTION);

        BatteryConsumer appsConsumer = mStatsRule.getAppsBatteryConsumer();
        // Estimated Rx/Tx modem consumption = 0.94 mAh
        // Estimated total modem consumption = 1.27888 mAh
        // 2.5 * 0.94 / 1.27888 = 1.83754 mAh
        assertThat(appsConsumer.getConsumedPower(BatteryConsumer.POWER_COMPONENT_MOBILE_RADIO))
                .isWithin(PRECISION).of(1.83754);
        assertThat(appsConsumer.getPowerModel(BatteryConsumer.POWER_COMPONENT_MOBILE_RADIO))
                .isEqualTo(BatteryConsumer.POWER_MODEL_ENERGY_CONSUMPTION);

        UidBatteryConsumer uidConsumer = mStatsRule.getUidBatteryConsumer(APP_UID);
        assertThat(uidConsumer.getConsumedPower(BatteryConsumer.POWER_COMPONENT_MOBILE_RADIO))
                .isWithin(PRECISION).of(1.83754);
        assertThat(uidConsumer.getPowerModel(BatteryConsumer.POWER_COMPONENT_MOBILE_RADIO))
                .isEqualTo(BatteryConsumer.POWER_MODEL_ENERGY_CONSUMPTION);
    }

    @Test
    public void testMeasuredEnergyBasedModel_byProcessState() {
        mStatsRule.setTestPowerProfile("power_profile_test_legacy_modem")
                .initMeasuredEnergyStatsLocked();
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
        mStatsRule.setNetworkStats(mockNetworkStats(10000, 1,
                mockNetworkStatsEntry("cellular", APP_UID, 0, 0,
                        METERED_NO, ROAMING_NO, DEFAULT_NETWORK_NO, 1000, 100, 2000, 20, 100)));

        stats.noteModemControllerActivity(null, 10_000_000, 10000, 10000, mNetworkStatsManager);

        uid.setProcessStateForTest(
                BatteryStats.Uid.PROCESS_STATE_BACKGROUND, 11000);

        mStatsRule.setNetworkStats(mockNetworkStats(12000, 1,
                mockNetworkStatsEntry("cellular", APP_UID, 0, 0,
                        METERED_NO, ROAMING_NO, DEFAULT_NETWORK_NO, 1000, 250, 2000, 80, 200)));

        stats.noteModemControllerActivity(null, 15_000_000, 12000, 12000, mNetworkStatsManager);

        mStatsRule.setTime(20000, 20000);

        assertThat(uid.getMobileRadioEnergyConsumptionUC())
                .isIn(Range.open(20_000_000L, 21_000_000L));
        assertThat(uid.getMobileRadioEnergyConsumptionUC(
                BatteryConsumer.PROCESS_STATE_FOREGROUND))
                .isIn(Range.open(13_000_000L, 14_000_000L));
        assertThat(uid.getMobileRadioEnergyConsumptionUC(
                BatteryConsumer.PROCESS_STATE_BACKGROUND))
                .isIn(Range.open(7_000_000L, 8_000_000L));
        assertThat(uid.getMobileRadioEnergyConsumptionUC(
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

    public void setInitialEmptyModemActivityInfo(BatteryStatsImpl stats) {
        // Initial empty ModemActivityInfo.
        final ModemActivityInfo emptyMai = new ModemActivityInfo(0L, 0L, 0L, new int[5], 0L);
        stats.noteModemControllerActivity(emptyMai, 0, 0, 0, mNetworkStatsManager);
    }

    private NetworkStats mockNetworkStats(int elapsedTime, int initialSize,
            NetworkStats.Entry... entries) {
        NetworkStats stats;
        if (RavenwoodRule.isOnRavenwood()) {
            stats = mock(NetworkStats.class);
            when(stats.iterator()).thenAnswer(inv -> List.of(entries).iterator());
        } else {
            stats = new NetworkStats(elapsedTime, initialSize);
            for (NetworkStats.Entry entry : entries) {
                stats = stats.addEntry(entry);
            }
        }
        return stats;
    }

    private static NetworkStats.Entry mockNetworkStatsEntry(@Nullable String iface, int uid,
            int set, int tag, int metered, int roaming, int defaultNetwork, long rxBytes,
            long rxPackets, long txBytes, long txPackets, long operations) {
        if (RavenwoodRule.isOnRavenwood()) {
            NetworkStats.Entry entry = mock(NetworkStats.Entry.class);
            when(entry.getUid()).thenReturn(uid);
            when(entry.getMetered()).thenReturn(metered);
            when(entry.getRoaming()).thenReturn(roaming);
            when(entry.getDefaultNetwork()).thenReturn(defaultNetwork);
            when(entry.getRxBytes()).thenReturn(rxBytes);
            when(entry.getRxPackets()).thenReturn(rxPackets);
            when(entry.getTxBytes()).thenReturn(txBytes);
            when(entry.getTxPackets()).thenReturn(txPackets);
            when(entry.getOperations()).thenReturn(operations);
            return entry;
        } else {
            return new NetworkStats.Entry(iface, uid, set, tag, metered,
                    roaming, defaultNetwork, rxBytes, rxPackets, txBytes, txPackets, operations);
        }
    }
}
