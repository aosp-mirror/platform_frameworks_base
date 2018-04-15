/*
 * Copyright (C) 2018 The Android Open Source Project
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

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothUuid;
import android.content.Context;
import android.os.ParcelUuid;

import java.util.ArrayList;
import java.util.List;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(resourceDir = "../../res")
public class LocalBluetoothProfileManagerTest {
    @Mock private CachedBluetoothDeviceManager mDeviceManager;
    @Mock private BluetoothEventManager mEventManager;
    @Mock private LocalBluetoothAdapter mAdapter;
    @Mock private BluetoothDevice mDevice;
    private Context mContext;
    private LocalBluetoothProfileManager mProfileManager;

    @Before
    public void setUp() {
        MockitoAnnotations.initMocks(this);
        mContext = RuntimeEnvironment.application;
        when(mAdapter.getBluetoothState()).thenReturn(BluetoothAdapter.STATE_ON);
    }

    /**
     * Verify HID and HID Device profiles are not null without running updateUuids()
     */
    @Test
    public void constructor_initiateHidAndHidDeviceProfile() {
        mProfileManager =
                new LocalBluetoothProfileManager(mContext, mAdapter, mDeviceManager, mEventManager);

        assertThat(mProfileManager.getHidProfile()).isNotNull();
        assertThat(mProfileManager.getHidDeviceProfile()).isNotNull();
    }

    /**
     * Verify updateLocalProfiles() for a local A2DP source adds A2dpProfile
     */
    @Test
    public void updateLocalProfiles_addA2dpToLocalProfiles() {
        mProfileManager =
                new LocalBluetoothProfileManager(mContext, mAdapter, mDeviceManager, mEventManager);
        when(mAdapter.getUuids()).thenReturn(new ParcelUuid[] {BluetoothUuid.AudioSource});
        assertThat(mProfileManager.getA2dpProfile()).isNull();
        assertThat(mProfileManager.getHeadsetProfile()).isNull();

        ParcelUuid[] uuids = mAdapter.getUuids();
        mProfileManager.updateLocalProfiles(uuids);

        assertThat(mProfileManager.getA2dpProfile()).isNotNull();
        assertThat(mProfileManager.getHeadsetProfile()).isNull();
    }

    /**
     * Verify updateProfiles() for a remote HID device updates profiles and removedProfiles
     */
    @Test
    public void updateProfiles_addHidProfileForRemoteDevice() {
        mProfileManager =
                new LocalBluetoothProfileManager(mContext, mAdapter, mDeviceManager, mEventManager);
        ParcelUuid[] uuids = new ParcelUuid[]{BluetoothUuid.Hid};
        ParcelUuid[] localUuids = new ParcelUuid[]{};
        List<LocalBluetoothProfile> profiles = new ArrayList<>();
        List<LocalBluetoothProfile> removedProfiles = new ArrayList<>();

        mProfileManager.updateProfiles(uuids, localUuids, profiles, removedProfiles, false,
                mDevice);

        assertThat(mProfileManager.getHidProfile()).isNotNull();
        assertThat(profiles.contains(mProfileManager.getHidProfile())).isTrue();
        assertThat(removedProfiles.contains(mProfileManager.getHidProfile())).isFalse();
    }
}
