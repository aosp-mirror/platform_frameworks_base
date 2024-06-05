/*
 * Copyright (C) 2022 The Android Open Source Project
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

package com.android.settingslib.bluetooth;

import static com.android.settingslib.bluetooth.HearingAidStatsLogUtils.CONNECTED_HISTORY_EXPIRED_DAY;
import static com.android.settingslib.bluetooth.HearingAidStatsLogUtils.PAIRED_HISTORY_EXPIRED_DAY;

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.Mockito.when;

import android.content.Context;

import androidx.test.core.app.ApplicationProvider;

import com.android.internal.util.FrameworkStatsLog;

import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnit;
import org.mockito.junit.MockitoRule;
import org.robolectric.RobolectricTestRunner;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.concurrent.TimeUnit;

@RunWith(RobolectricTestRunner.class)
public class HearingAidStatsLogUtilsTest {

    private static final String TEST_DEVICE_ADDRESS = "00:A1:A1:A1:A1:A1";
    private static final int TEST_HISTORY_TYPE =
            HearingAidStatsLogUtils.HistoryType.TYPE_HEARING_AIDS_CONNECTED;

    @Rule
    public final MockitoRule mockito = MockitoJUnit.rule();

    private final Context mContext = ApplicationProvider.getApplicationContext();

    @Mock
    private CachedBluetoothDevice mCachedBluetoothDevice;

    @Test
    public void setBondEntryForDevice_addsEntryToDeviceAddressToBondEntryMap() {
        when(mCachedBluetoothDevice.getAddress()).thenReturn(TEST_DEVICE_ADDRESS);

        HearingAidStatsLogUtils.setBondEntryForDevice(
                FrameworkStatsLog.HEARING_AID_INFO_REPORTED__BOND_ENTRY__BLUETOOTH,
                mCachedBluetoothDevice);

        final HashMap<String, Integer> map =
                HearingAidStatsLogUtils.getDeviceAddressToBondEntryMap();
        assertThat(map.containsKey(TEST_DEVICE_ADDRESS)).isTrue();
        assertThat(map.get(TEST_DEVICE_ADDRESS)).isEqualTo(
                FrameworkStatsLog.HEARING_AID_INFO_REPORTED__BOND_ENTRY__BLUETOOTH);
    }

    @Test
    public void logHearingAidInfo_removesEntryFromDeviceAddressToBondEntryMap() {
        when(mCachedBluetoothDevice.getAddress()).thenReturn(TEST_DEVICE_ADDRESS);

        HearingAidStatsLogUtils.setBondEntryForDevice(
                FrameworkStatsLog.HEARING_AID_INFO_REPORTED__BOND_ENTRY__BLUETOOTH,
                mCachedBluetoothDevice);
        HearingAidStatsLogUtils.logHearingAidInfo(mCachedBluetoothDevice);

        final HashMap<String, Integer> map =
                HearingAidStatsLogUtils.getDeviceAddressToBondEntryMap();
        assertThat(map.containsKey(TEST_DEVICE_ADDRESS)).isFalse();
    }

    @Test
    public void addCurrentTimeToHistory_addNewData() {
        final long todayStartOfDay = convertToStartOfDayTime(System.currentTimeMillis());
        final long lastData = todayStartOfDay - TimeUnit.DAYS.toMillis(6);
        HearingAidStatsLogUtils.addToHistory(mContext, TEST_HISTORY_TYPE, lastData);

        HearingAidStatsLogUtils.addCurrentTimeToHistory(mContext, TEST_HISTORY_TYPE);

        LinkedList<Long> history = HearingAidStatsLogUtils.getHistory(mContext, TEST_HISTORY_TYPE);
        assertThat(history).isNotNull();
        assertThat(history.size()).isEqualTo(2);
    }
    @Test
    public void addCurrentTimeToHistory_skipSameDateData() {
        final long todayStartOfDay = convertToStartOfDayTime(System.currentTimeMillis());
        HearingAidStatsLogUtils.addToHistory(mContext, TEST_HISTORY_TYPE, todayStartOfDay);

        HearingAidStatsLogUtils.addCurrentTimeToHistory(mContext, TEST_HISTORY_TYPE);

        LinkedList<Long> history = HearingAidStatsLogUtils.getHistory(mContext, TEST_HISTORY_TYPE);
        assertThat(history).isNotNull();
        assertThat(history.size()).isEqualTo(1);
        assertThat(history.getFirst()).isEqualTo(todayStartOfDay);
    }

    @Test
    public void addCurrentTimeToHistory_cleanUpExpiredData() {
        final long todayStartOfDay = convertToStartOfDayTime(System.currentTimeMillis());
        final long expiredData = todayStartOfDay - TimeUnit.DAYS.toMillis(6) - 1;
        HearingAidStatsLogUtils.addToHistory(mContext, TEST_HISTORY_TYPE, expiredData);

        HearingAidStatsLogUtils.addCurrentTimeToHistory(mContext, TEST_HISTORY_TYPE);

        LinkedList<Long> history = HearingAidStatsLogUtils.getHistory(mContext, TEST_HISTORY_TYPE);
        assertThat(history).isNotNull();
        assertThat(history.size()).isEqualTo(1);
        assertThat(history.getFirst()).isNotEqualTo(expiredData);
    }

    @Test
    public void getUserCategory_hearingAidsUser() {
        prepareHearingAidsUserHistory();

        assertThat(HearingAidStatsLogUtils.getUserCategory(mContext)).isEqualTo(
                HearingAidStatsLogUtils.CATEGORY_HEARING_AIDS);
    }

    @Test
    public void getUserCategory_newHearingAidsUser() {
        prepareHearingAidsUserHistory();
        prepareNewUserHistory();

        assertThat(HearingAidStatsLogUtils.getUserCategory(mContext)).isEqualTo(
                HearingAidStatsLogUtils.CATEGORY_NEW_HEARING_AIDS);
    }

    @Test
    public void getUserCategory_hearableDevicesUser() {
        prepareHearableDevicesUserHistory();

        assertThat(HearingAidStatsLogUtils.getUserCategory(mContext)).isEqualTo(
                HearingAidStatsLogUtils.CATEGORY_HEARABLE_DEVICES);
    }

    @Test
    public void getUserCategory_newHearableDevicesUser() {
        prepareHearableDevicesUserHistory();
        prepareNewUserHistory();

        assertThat(HearingAidStatsLogUtils.getUserCategory(mContext)).isEqualTo(
                HearingAidStatsLogUtils.CATEGORY_NEW_HEARABLE_DEVICES);
    }

    @Test
    public void getUserCategory_bothHearingAidsAndHearableDevicesUser_returnHearingAidsUser() {
        prepareHearingAidsUserHistory();
        prepareHearableDevicesUserHistory();

        assertThat(HearingAidStatsLogUtils.getUserCategory(mContext)).isEqualTo(
                HearingAidStatsLogUtils.CATEGORY_HEARING_AIDS);
    }

    private long convertToStartOfDayTime(long timestamp) {
        ZoneId zoneId = ZoneId.systemDefault();
        LocalDate date = Instant.ofEpochMilli(timestamp).atZone(zoneId).toLocalDate();
        return date.atStartOfDay(zoneId).toInstant().toEpochMilli();
    }

    private void prepareHearingAidsUserHistory() {
        final long todayStartOfDay = convertToStartOfDayTime(System.currentTimeMillis());
        for (int i = CONNECTED_HISTORY_EXPIRED_DAY - 1; i >= 0; i--) {
            final long data = todayStartOfDay - TimeUnit.DAYS.toMillis(i);
            HearingAidStatsLogUtils.addToHistory(mContext,
                    HearingAidStatsLogUtils.HistoryType.TYPE_HEARING_AIDS_CONNECTED, data);
        }
    }

    private void prepareHearableDevicesUserHistory() {
        final long todayStartOfDay = convertToStartOfDayTime(System.currentTimeMillis());
        for (int i = CONNECTED_HISTORY_EXPIRED_DAY - 1; i >= 0; i--) {
            final long data = todayStartOfDay - TimeUnit.DAYS.toMillis(i);
            HearingAidStatsLogUtils.addToHistory(mContext,
                    HearingAidStatsLogUtils.HistoryType.TYPE_HEARABLE_DEVICES_CONNECTED, data);
        }
    }

    private void prepareNewUserHistory() {
        final long todayStartOfDay = convertToStartOfDayTime(System.currentTimeMillis());
        final long data = todayStartOfDay - TimeUnit.DAYS.toMillis(PAIRED_HISTORY_EXPIRED_DAY - 1);
        HearingAidStatsLogUtils.addToHistory(mContext,
                HearingAidStatsLogUtils.HistoryType.TYPE_HEARING_AIDS_PAIRED, data);
        HearingAidStatsLogUtils.addToHistory(mContext,
                HearingAidStatsLogUtils.HistoryType.TYPE_HEARABLE_DEVICES_PAIRED, data);
    }
}
