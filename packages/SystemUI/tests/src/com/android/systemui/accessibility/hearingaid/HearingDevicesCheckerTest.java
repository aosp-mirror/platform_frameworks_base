/*
 * Copyright (C) 2024 The Android Open Source Project
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

package com.android.systemui.accessibility.hearingaid;

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.Mockito.when;

import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothProfile;
import android.testing.TestableLooper;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.SmallTest;

import com.android.settingslib.bluetooth.CachedBluetoothDevice;
import com.android.settingslib.bluetooth.CachedBluetoothDeviceManager;
import com.android.settingslib.bluetooth.LocalBluetoothAdapter;
import com.android.settingslib.bluetooth.LocalBluetoothManager;
import com.android.systemui.SysuiTestCase;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnit;
import org.mockito.junit.MockitoRule;

import java.util.ArrayList;
import java.util.List;

@RunWith(AndroidJUnit4.class)
@TestableLooper.RunWithLooper(setAsMainLooper = true)
@SmallTest
public class HearingDevicesCheckerTest extends SysuiTestCase {
    @Rule
    public MockitoRule mockito = MockitoJUnit.rule();

    private final List<CachedBluetoothDevice> mCachedDevices = new ArrayList<>();
    @Mock
    private LocalBluetoothManager mLocalBluetoothManager;
    @Mock
    private LocalBluetoothAdapter mLocalBluetoothAdapter;
    @Mock
    private CachedBluetoothDeviceManager mCachedBluetoothDeviceManager;
    @Mock
    private CachedBluetoothDevice mCachedDevice;
    @Mock
    private BluetoothDevice mDevice;
    private HearingDevicesChecker mDevicesChecker;

    @Before
    public void setUp() {
        when(mLocalBluetoothManager.getBluetoothAdapter()).thenReturn(mLocalBluetoothAdapter);
        when(mLocalBluetoothManager.getCachedDeviceManager()).thenReturn(
                mCachedBluetoothDeviceManager);
        when(mCachedBluetoothDeviceManager.getCachedDevicesCopy()).thenReturn(mCachedDevices);
        when(mCachedDevice.getDevice()).thenReturn(mDevice);
        when(mDevice.getMetadata(BluetoothDevice.METADATA_EXCLUSIVE_MANAGER)).thenReturn(
                null);

        mDevicesChecker = new HearingDevicesChecker(mContext, mLocalBluetoothManager);
    }

    @Test
    public void isAnyPairedHearingDevice_bluetoothDisable_returnFalse() {
        when(mLocalBluetoothAdapter.isEnabled()).thenReturn(false);

        assertThat(mDevicesChecker.isAnyPairedHearingDevice()).isFalse();
    }

    @Test
    public void isAnyActiveHearingDevice_bluetoothDisable_returnFalse() {
        when(mLocalBluetoothAdapter.isEnabled()).thenReturn(false);

        assertThat(mDevicesChecker.isAnyActiveHearingDevice()).isFalse();
    }

    @Test
    public void isAnyPairedHearingDevice_hearingAidBonded_returnTrue() {
        when(mLocalBluetoothAdapter.isEnabled()).thenReturn(true);
        when(mCachedDevice.isHearingAidDevice()).thenReturn(true);
        when(mCachedDevice.getBondState()).thenReturn(BluetoothDevice.BOND_BONDED);
        mCachedDevices.add(mCachedDevice);

        assertThat(mDevicesChecker.isAnyPairedHearingDevice()).isTrue();
    }

    @Test
    public void isAnyActiveHearingDevice_hearingAidActiveAndConnected_returnTrue() {
        when(mLocalBluetoothAdapter.isEnabled()).thenReturn(true);
        when(mCachedDevice.isActiveDevice(BluetoothProfile.HEARING_AID)).thenReturn(true);
        when(mCachedDevice.getBondState()).thenReturn(BluetoothDevice.BOND_BONDED);
        when(mDevice.getBondState()).thenReturn(BluetoothDevice.BOND_BONDED);
        when(mDevice.isConnected()).thenReturn(true);
        when(mCachedDevice.isConnectedHearingAidDevice()).thenReturn(true);
        mCachedDevices.add(mCachedDevice);

        assertThat(mDevicesChecker.isAnyActiveHearingDevice()).isTrue();
    }
}
