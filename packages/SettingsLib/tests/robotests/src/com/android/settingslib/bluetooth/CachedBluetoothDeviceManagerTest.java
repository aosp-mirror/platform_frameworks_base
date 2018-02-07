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

import static org.mockito.Matchers.any;
import static org.mockito.Matchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothClass;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothProfile;
import android.content.Context;

import com.android.settingslib.R;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.util.Collection;

@RunWith(RobolectricTestRunner.class)
public class CachedBluetoothDeviceManagerTest {
    private final static String DEVICE_NAME_1 = "TestName_1";
    private final static String DEVICE_NAME_2 = "TestName_2";
    private final static String DEVICE_ALIAS_1 = "TestAlias_1";
    private final static String DEVICE_ALIAS_2 = "TestAlias_2";
    private final static String DEVICE_ADDRESS_1 = "AA:BB:CC:DD:EE:11";
    private final static String DEVICE_ADDRESS_2 = "AA:BB:CC:DD:EE:22";
    private final BluetoothClass DEVICE_CLASS_1 =
        new BluetoothClass(BluetoothClass.Device.AUDIO_VIDEO_HEADPHONES);
    private final BluetoothClass DEVICE_CLASS_2 =
        new BluetoothClass(BluetoothClass.Device.AUDIO_VIDEO_HANDSFREE);
    @Mock
    private LocalBluetoothAdapter mLocalAdapter;
    @Mock
    private LocalBluetoothProfileManager mLocalProfileManager;
    @Mock
    private LocalBluetoothManager mLocalBluetoothManager;
    @Mock
    private BluetoothEventManager mBluetoothEventManager;
    @Mock
    private HeadsetProfile mHfpProfile;
    @Mock
    private A2dpProfile mA2dpProfile;
    @Mock
    private PanProfile mPanProfile;
    @Mock
    private BluetoothDevice mDevice1;
    @Mock
    private BluetoothDevice mDevice2;
    private CachedBluetoothDeviceManager mCachedDeviceManager;
    private Context mContext;
    private String[] mActiveDeviceStringsArray;
    private String mActiveDeviceStringNone;
    private String mActiveDeviceStringAll;
    private String mActiveDeviceStringMedia;
    private String mActiveDeviceStringPhone;

    @Before
    public void setUp() {
        MockitoAnnotations.initMocks(this);
        mContext = RuntimeEnvironment.application;
        when(mDevice1.getAddress()).thenReturn(DEVICE_ADDRESS_1);
        when(mDevice2.getAddress()).thenReturn(DEVICE_ADDRESS_2);
        when(mDevice1.getName()).thenReturn(DEVICE_NAME_1);
        when(mDevice2.getName()).thenReturn(DEVICE_NAME_2);
        when(mDevice1.getAliasName()).thenReturn(DEVICE_ALIAS_1);
        when(mDevice2.getAliasName()).thenReturn(DEVICE_ALIAS_2);
        when(mDevice1.getBluetoothClass()).thenReturn(DEVICE_CLASS_1);
        when(mDevice2.getBluetoothClass()).thenReturn(DEVICE_CLASS_2);

        when(mLocalBluetoothManager.getEventManager()).thenReturn(mBluetoothEventManager);
        when(mLocalAdapter.getBluetoothState()).thenReturn(BluetoothAdapter.STATE_ON);
        when(mHfpProfile.isProfileReady()).thenReturn(true);
        when(mA2dpProfile.isProfileReady()).thenReturn(true);
        when(mPanProfile.isProfileReady()).thenReturn(true);
        mCachedDeviceManager = new CachedBluetoothDeviceManager(mContext, mLocalBluetoothManager);
    }

    /**
     * Test to verify addDevice().
     */
    @Test
    public void testAddDevice_validCachedDevices_devicesAdded() {
        CachedBluetoothDevice cachedDevice1 = mCachedDeviceManager.addDevice(mLocalAdapter,
                mLocalProfileManager, mDevice1);
        assertThat(cachedDevice1).isNotNull();
        CachedBluetoothDevice cachedDevice2 = mCachedDeviceManager.addDevice(mLocalAdapter,
                mLocalProfileManager, mDevice2);
        assertThat(cachedDevice2).isNotNull();

        Collection<CachedBluetoothDevice> devices = mCachedDeviceManager.getCachedDevicesCopy();
        assertThat(devices).contains(cachedDevice1);
        assertThat(devices).contains(cachedDevice2);

        assertThat(mCachedDeviceManager.findDevice(mDevice1)).isEqualTo(cachedDevice1);
        assertThat(mCachedDeviceManager.findDevice(mDevice2)).isEqualTo(cachedDevice2);
    }

    /**
     * Test to verify getName().
     */
    @Test
    public void testGetName_validCachedDevice_nameFound() {
        CachedBluetoothDevice cachedDevice1 = mCachedDeviceManager.addDevice(mLocalAdapter,
                mLocalProfileManager, mDevice1);
        assertThat(cachedDevice1).isNotNull();
        assertThat(mCachedDeviceManager.getName(mDevice1)).isEqualTo(DEVICE_ALIAS_1);
    }

    /**
     * Test to verify onDeviceNameUpdated().
     */
    @Test
    public void testOnDeviceNameUpdated_validName_nameUpdated() {
        CachedBluetoothDevice cachedDevice1 = mCachedDeviceManager.addDevice(mLocalAdapter,
                mLocalProfileManager, mDevice1);
        assertThat(cachedDevice1).isNotNull();
        assertThat(cachedDevice1.getName()).isEqualTo(DEVICE_ALIAS_1);

        final String newAliasName = "NewAliasName";
        when(mDevice1.getAliasName()).thenReturn(newAliasName);
        mCachedDeviceManager.onDeviceNameUpdated(mDevice1);
        assertThat(cachedDevice1.getName()).isEqualTo(newAliasName);
    }

    /**
     * Test to verify clearNonBondedDevices().
     */
    @Test
    public void testClearNonBondedDevices_bondedAndNonBondedDevices_nonBondedDevicesCleared() {
        CachedBluetoothDevice cachedDevice1 = mCachedDeviceManager.addDevice(mLocalAdapter,
                mLocalProfileManager, mDevice1);
        assertThat(cachedDevice1).isNotNull();
        CachedBluetoothDevice cachedDevice2 = mCachedDeviceManager.addDevice(mLocalAdapter,
                mLocalProfileManager, mDevice2);
        assertThat(cachedDevice2).isNotNull();

        when(mDevice1.getBondState()).thenReturn(BluetoothDevice.BOND_BONDED);
        when(mDevice2.getBondState()).thenReturn(BluetoothDevice.BOND_BONDING);
        mCachedDeviceManager.clearNonBondedDevices();
        Collection<CachedBluetoothDevice> devices = mCachedDeviceManager.getCachedDevicesCopy();
        assertThat(devices).contains(cachedDevice1);
        assertThat(devices).contains(cachedDevice2);

        when(mDevice1.getBondState()).thenReturn(BluetoothDevice.BOND_BONDED);
        when(mDevice2.getBondState()).thenReturn(BluetoothDevice.BOND_NONE);
        mCachedDeviceManager.clearNonBondedDevices();
        devices = mCachedDeviceManager.getCachedDevicesCopy();
        assertThat(devices).contains(cachedDevice1);
        assertThat(devices).doesNotContain(cachedDevice2);

        when(mDevice1.getBondState()).thenReturn(BluetoothDevice.BOND_NONE);
        when(mDevice2.getBondState()).thenReturn(BluetoothDevice.BOND_NONE);
        mCachedDeviceManager.clearNonBondedDevices();
        devices = mCachedDeviceManager.getCachedDevicesCopy();
        assertThat(devices).doesNotContain(cachedDevice1);
        assertThat(devices).doesNotContain(cachedDevice2);
    }

    /**
     * Test to verify onBtClassChanged().
     */
    @Test
    public void testOnBtClassChanged_validBtClass_classChanged() {
        CachedBluetoothDevice cachedDevice1 = mCachedDeviceManager.addDevice(mLocalAdapter,
                mLocalProfileManager, mDevice1);
        assertThat(cachedDevice1).isNotNull();
        assertThat(cachedDevice1.getBtClass()).isEqualTo(DEVICE_CLASS_1);

        final BluetoothClass newBluetoothClass = DEVICE_CLASS_2;
        when(mDevice1.getBluetoothClass()).thenReturn(newBluetoothClass);
        mCachedDeviceManager.onBtClassChanged(mDevice1);
        assertThat(cachedDevice1.getBtClass()).isEqualTo(newBluetoothClass);
    }

    /**
     * Test to verify onDeviceDisappeared().
     */
    @Test
    public void testOnDeviceDisappeared_deviceBondedUnbonded_unbondedDeviceDisappeared() {
        CachedBluetoothDevice cachedDevice1 = mCachedDeviceManager.addDevice(mLocalAdapter,
                mLocalProfileManager, mDevice1);
        assertThat(cachedDevice1).isNotNull();

        when(mDevice1.getBondState()).thenReturn(BluetoothDevice.BOND_BONDED);
        assertThat(mCachedDeviceManager.onDeviceDisappeared(cachedDevice1)).isFalse();

        when(mDevice1.getBondState()).thenReturn(BluetoothDevice.BOND_NONE);
        assertThat(mCachedDeviceManager.onDeviceDisappeared(cachedDevice1)).isTrue();
    }

    /**
     * Test to verify onActiveDeviceChanged().
     */
    @Test
    public void testOnActiveDeviceChanged_connectedDevices_activeDeviceChanged() {
        CachedBluetoothDevice cachedDevice1 = mCachedDeviceManager.addDevice(mLocalAdapter,
                mLocalProfileManager, mDevice1);
        assertThat(cachedDevice1).isNotNull();
        CachedBluetoothDevice cachedDevice2 = mCachedDeviceManager.addDevice(mLocalAdapter,
                mLocalProfileManager, mDevice2);
        assertThat(cachedDevice2).isNotNull();

        when(mDevice1.getBondState()).thenReturn(BluetoothDevice.BOND_BONDED);
        when(mDevice2.getBondState()).thenReturn(BluetoothDevice.BOND_BONDED);

        // Connect both devices for A2DP and HFP
        cachedDevice1.onProfileStateChanged(mA2dpProfile, BluetoothProfile.STATE_CONNECTED);
        cachedDevice2.onProfileStateChanged(mA2dpProfile, BluetoothProfile.STATE_CONNECTED);
        cachedDevice1.onProfileStateChanged(mHfpProfile, BluetoothProfile.STATE_CONNECTED);
        cachedDevice2.onProfileStateChanged(mHfpProfile, BluetoothProfile.STATE_CONNECTED);

        // Verify that both devices are connected and none is Active
        assertThat(cachedDevice1.isActiveDevice(BluetoothProfile.A2DP)).isFalse();
        assertThat(cachedDevice1.isActiveDevice(BluetoothProfile.HEADSET)).isFalse();
        assertThat(cachedDevice2.isActiveDevice(BluetoothProfile.A2DP)).isFalse();
        assertThat(cachedDevice2.isActiveDevice(BluetoothProfile.HEADSET)).isFalse();

        // The first device is active for A2DP, the second device is active for HFP
        mCachedDeviceManager.onActiveDeviceChanged(cachedDevice1, BluetoothProfile.A2DP);
        mCachedDeviceManager.onActiveDeviceChanged(cachedDevice2, BluetoothProfile.HEADSET);
        assertThat(cachedDevice1.isActiveDevice(BluetoothProfile.A2DP)).isTrue();
        assertThat(cachedDevice1.isActiveDevice(BluetoothProfile.HEADSET)).isFalse();
        assertThat(cachedDevice2.isActiveDevice(BluetoothProfile.A2DP)).isFalse();
        assertThat(cachedDevice2.isActiveDevice(BluetoothProfile.HEADSET)).isTrue();

        // The first device is active for A2DP and HFP
        mCachedDeviceManager.onActiveDeviceChanged(cachedDevice1, BluetoothProfile.HEADSET);
        assertThat(cachedDevice1.isActiveDevice(BluetoothProfile.A2DP)).isTrue();
        assertThat(cachedDevice1.isActiveDevice(BluetoothProfile.HEADSET)).isTrue();
        assertThat(cachedDevice2.isActiveDevice(BluetoothProfile.A2DP)).isFalse();
        assertThat(cachedDevice2.isActiveDevice(BluetoothProfile.HEADSET)).isFalse();

        // The second device is active for A2DP and HFP
        mCachedDeviceManager.onActiveDeviceChanged(cachedDevice2, BluetoothProfile.A2DP);
        mCachedDeviceManager.onActiveDeviceChanged(cachedDevice2, BluetoothProfile.HEADSET);
        assertThat(cachedDevice1.isActiveDevice(BluetoothProfile.A2DP)).isFalse();
        assertThat(cachedDevice1.isActiveDevice(BluetoothProfile.HEADSET)).isFalse();
        assertThat(cachedDevice2.isActiveDevice(BluetoothProfile.A2DP)).isTrue();
        assertThat(cachedDevice2.isActiveDevice(BluetoothProfile.HEADSET)).isTrue();

        // No active device for A2DP
        mCachedDeviceManager.onActiveDeviceChanged(null, BluetoothProfile.A2DP);
        assertThat(cachedDevice1.isActiveDevice(BluetoothProfile.A2DP)).isFalse();
        assertThat(cachedDevice1.isActiveDevice(BluetoothProfile.HEADSET)).isFalse();
        assertThat(cachedDevice2.isActiveDevice(BluetoothProfile.A2DP)).isFalse();
        assertThat(cachedDevice2.isActiveDevice(BluetoothProfile.HEADSET)).isTrue();

        // No active device for HFP
        mCachedDeviceManager.onActiveDeviceChanged(null, BluetoothProfile.HEADSET);
        assertThat(cachedDevice1.isActiveDevice(BluetoothProfile.A2DP)).isFalse();
        assertThat(cachedDevice1.isActiveDevice(BluetoothProfile.HEADSET)).isFalse();
        assertThat(cachedDevice2.isActiveDevice(BluetoothProfile.A2DP)).isFalse();
        assertThat(cachedDevice2.isActiveDevice(BluetoothProfile.HEADSET)).isFalse();
    }
}
