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

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.Mockito.when;

import com.android.internal.util.FrameworkStatsLog;

import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnit;
import org.mockito.junit.MockitoRule;
import org.robolectric.RobolectricTestRunner;

import java.util.HashMap;

@RunWith(RobolectricTestRunner.class)
public class HearingAidStatsLogUtilsTest {

    private static final String TEST_DEVICE_ADDRESS = "00:A1:A1:A1:A1:A1";

    @Rule
    public final MockitoRule mockito = MockitoJUnit.rule();

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
}
