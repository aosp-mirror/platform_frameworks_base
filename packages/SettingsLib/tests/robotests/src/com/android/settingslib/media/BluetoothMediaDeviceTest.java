/*
 * Copyright 2019 The Android Open Source Project
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

package com.android.settingslib.media;

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.Mockito.when;

import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothProfile;
import android.content.Context;

import com.android.settingslib.bluetooth.CachedBluetoothDevice;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;

@RunWith(RobolectricTestRunner.class)
public class BluetoothMediaDeviceTest {

    @Mock
    private CachedBluetoothDevice mDevice;

    private Context mContext;
    private BluetoothMediaDevice mBluetoothMediaDevice;

    @Before
    public void setUp() {
        MockitoAnnotations.initMocks(this);
        mContext = RuntimeEnvironment.application;

        when(mDevice.isActiveDevice(BluetoothProfile.A2DP)).thenReturn(true);
        when(mDevice.isActiveDevice(BluetoothProfile.HEARING_AID)).thenReturn(true);

        mBluetoothMediaDevice = new BluetoothMediaDevice(mContext, mDevice);
    }

    @Test
    public void connect_setActiveSuccess_isConnectedReturnTrue() {
        when(mDevice.setActive()).thenReturn(true);

        assertThat(mBluetoothMediaDevice.connect()).isTrue();
    }

    @Test
    public void connect_setActiveFail_isConnectedReturnFalse() {
        when(mDevice.setActive()).thenReturn(false);

        assertThat(mBluetoothMediaDevice.connect()).isFalse();
    }

    @Test
    public void isCachedBluetoothDeviceConnected_deviceConnected_returnTrue() {
        when(mDevice.getBondState()).thenReturn(BluetoothDevice.BOND_BONDED);
        when(mDevice.isConnected()).thenReturn(true);

        assertThat(mBluetoothMediaDevice.isConnected()).isTrue();
    }

    @Test
    public void isCachedBluetoothDeviceConnected_deviceNotConnected_returnFalse() {
        when(mDevice.getBondState()).thenReturn(BluetoothDevice.BOND_BONDED);
        when(mDevice.isConnected()).thenReturn(false);

        assertThat(mBluetoothMediaDevice.isConnected()).isFalse();
    }
}
