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
import android.content.Context;

import com.android.settingslib.bluetooth.A2dpProfile;
import com.android.settingslib.bluetooth.HearingAidProfile;
import com.android.settingslib.bluetooth.LocalBluetoothManager;
import com.android.settingslib.bluetooth.LocalBluetoothProfileManager;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;

@RunWith(RobolectricTestRunner.class)
public class PhoneMediaDeviceTest {

    @Mock
    private LocalBluetoothProfileManager mLocalProfileManager;
    @Mock
    private LocalBluetoothManager mLocalBluetoothManager;
    @Mock
    private HearingAidProfile mHapProfile;
    @Mock
    private A2dpProfile mA2dpProfile;
    @Mock
    private BluetoothDevice mDevice;

    private Context mContext;
    private PhoneMediaDevice mPhoneMediaDevice;

    @Before
    public void setUp() {
        MockitoAnnotations.initMocks(this);
        mContext = RuntimeEnvironment.application;

        when(mLocalBluetoothManager.getProfileManager()).thenReturn(mLocalProfileManager);
        when(mLocalProfileManager.getA2dpProfile()).thenReturn(mA2dpProfile);
        when(mLocalProfileManager.getHearingAidProfile()).thenReturn(mHapProfile);
        when(mA2dpProfile.getActiveDevice()).thenReturn(mDevice);

        mPhoneMediaDevice = new PhoneMediaDevice(mContext, mLocalBluetoothManager);
    }

    @Test
    public void connect_phoneDeviceSetActiveSuccess_isConnectedReturnTrue() {
        when(mA2dpProfile.setActiveDevice(null)).thenReturn(true);
        when(mHapProfile.setActiveDevice(null)).thenReturn(true);

        assertThat(mPhoneMediaDevice.connect()).isTrue();
    }

    @Test
    public void connect_a2dpProfileSetActiveFail_isConnectedReturnFalse() {
        when(mA2dpProfile.setActiveDevice(null)).thenReturn(false);
        when(mHapProfile.setActiveDevice(null)).thenReturn(true);

        assertThat(mPhoneMediaDevice.connect()).isFalse();
    }

    @Test
    public void connect_hearingAidProfileSetActiveFail_isConnectedReturnFalse() {
        when(mA2dpProfile.setActiveDevice(null)).thenReturn(true);
        when(mHapProfile.setActiveDevice(null)).thenReturn(false);

        assertThat(mPhoneMediaDevice.connect()).isFalse();
    }

    @Test
    public void connect_hearingAidAndA2dpProfileSetActiveFail_isConnectedReturnFalse() {
        when(mA2dpProfile.setActiveDevice(null)).thenReturn(false);
        when(mHapProfile.setActiveDevice(null)).thenReturn(false);

        assertThat(mPhoneMediaDevice.connect()).isFalse();
    }
}
