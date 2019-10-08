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

import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.bluetooth.BluetoothClass;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothProfile;
import android.content.Context;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;

import java.util.Collection;

@RunWith(RobolectricTestRunner.class)
public class CachedBluetoothDeviceManagerTest {
    private final static String DEVICE_NAME_1 = "TestName_1";
    private final static String DEVICE_NAME_2 = "TestName_2";
    private final static String DEVICE_NAME_3 = "TestName_3";
    private final static String DEVICE_ALIAS_1 = "TestAlias_1";
    private final static String DEVICE_ALIAS_2 = "TestAlias_2";
    private final static String DEVICE_ALIAS_3 = "TestAlias_3";
    private final static String DEVICE_ADDRESS_1 = "AA:BB:CC:DD:EE:11";
    private final static String DEVICE_ADDRESS_2 = "AA:BB:CC:DD:EE:22";
    private final static String DEVICE_ADDRESS_3 = "AA:BB:CC:DD:EE:33";
    private final static String DEVICE_SUMMARY_1 = "summary 1";
    private final static String DEVICE_SUMMARY_2 = "summary 2";
    private final static long HISYNCID1 = 10;
    private final static long HISYNCID2 = 11;
    private final BluetoothClass DEVICE_CLASS_1 =
        new BluetoothClass(BluetoothClass.Device.AUDIO_VIDEO_HEADPHONES);
    private final BluetoothClass DEVICE_CLASS_2 =
        new BluetoothClass(BluetoothClass.Device.AUDIO_VIDEO_HANDSFREE);
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
    private HearingAidProfile mHearingAidProfile;
    @Mock
    private BluetoothDevice mDevice1;
    @Mock
    private BluetoothDevice mDevice2;
    @Mock
    private BluetoothDevice mDevice3;
    private CachedBluetoothDevice mCachedDevice1;
    private CachedBluetoothDevice mCachedDevice2;
    private CachedBluetoothDevice mCachedDevice3;
    private CachedBluetoothDeviceManager mCachedDeviceManager;
    private HearingAidDeviceManager mHearingAidDeviceManager;
    private Context mContext;

    @Before
    public void setUp() {
        MockitoAnnotations.initMocks(this);
        mContext = RuntimeEnvironment.application;
        when(mDevice1.getAddress()).thenReturn(DEVICE_ADDRESS_1);
        when(mDevice2.getAddress()).thenReturn(DEVICE_ADDRESS_2);
        when(mDevice3.getAddress()).thenReturn(DEVICE_ADDRESS_3);
        when(mDevice1.getName()).thenReturn(DEVICE_NAME_1);
        when(mDevice2.getName()).thenReturn(DEVICE_NAME_2);
        when(mDevice3.getName()).thenReturn(DEVICE_NAME_3);
        when(mDevice1.getAliasName()).thenReturn(DEVICE_ALIAS_1);
        when(mDevice2.getAliasName()).thenReturn(DEVICE_ALIAS_2);
        when(mDevice3.getAliasName()).thenReturn(DEVICE_ALIAS_3);
        when(mDevice1.getBluetoothClass()).thenReturn(DEVICE_CLASS_1);
        when(mDevice2.getBluetoothClass()).thenReturn(DEVICE_CLASS_2);
        when(mDevice3.getBluetoothClass()).thenReturn(DEVICE_CLASS_2);

        when(mLocalBluetoothManager.getEventManager()).thenReturn(mBluetoothEventManager);
        when(mLocalBluetoothManager.getProfileManager()).thenReturn(mLocalProfileManager);
        when(mHfpProfile.isProfileReady()).thenReturn(true);
        when(mA2dpProfile.isProfileReady()).thenReturn(true);
        when(mPanProfile.isProfileReady()).thenReturn(true);
        when(mHearingAidProfile.isProfileReady()).thenReturn(true);
        doAnswer((invocation) -> mHearingAidProfile).
                when(mLocalProfileManager).getHearingAidProfile();
        mCachedDeviceManager = new CachedBluetoothDeviceManager(mContext, mLocalBluetoothManager);
        mCachedDevice1 = spy(new CachedBluetoothDevice(mContext, mLocalProfileManager, mDevice1));
        mCachedDevice2 = spy(new CachedBluetoothDevice(mContext, mLocalProfileManager, mDevice2));
        mCachedDevice3 = spy(new CachedBluetoothDevice(mContext, mLocalProfileManager, mDevice3));
    }

    /**
     * Test to verify addDevice().
     */
    @Test
    public void addDevice_validCachedDevices_devicesAdded() {
        CachedBluetoothDevice cachedDevice1 = mCachedDeviceManager.addDevice(mDevice1);
        assertThat(cachedDevice1).isNotNull();
        CachedBluetoothDevice cachedDevice2 = mCachedDeviceManager.addDevice(mDevice2);
        assertThat(cachedDevice2).isNotNull();

        Collection<CachedBluetoothDevice> devices = mCachedDeviceManager.getCachedDevicesCopy();
        assertThat(devices).contains(cachedDevice1);
        assertThat(devices).contains(cachedDevice2);

        assertThat(mCachedDeviceManager.findDevice(mDevice1)).isEqualTo(cachedDevice1);
        assertThat(mCachedDeviceManager.findDevice(mDevice2)).isEqualTo(cachedDevice2);
    }

    /**
     * Test to verify getSubDevice(), new device has the same HiSyncId.
     */
    @Test
    public void addDevice_sameHiSyncId_validSubDevice() {
        doAnswer((invocation) -> HISYNCID1).when(mHearingAidProfile).getHiSyncId(mDevice1);
        doAnswer((invocation) -> HISYNCID1).when(mHearingAidProfile).getHiSyncId(mDevice2);
        CachedBluetoothDevice cachedDevice1 = mCachedDeviceManager.addDevice(mDevice1);
        CachedBluetoothDevice cachedDevice2 = mCachedDeviceManager.addDevice(mDevice2);

        assertThat(cachedDevice1.getSubDevice()).isEqualTo(cachedDevice2);
    }

    /**
     * Test to verify getSubDevice(), new device has the different HiSyncId.
     */
    @Test
    public void addDevice_differentHiSyncId_validSubDevice() {
        doAnswer((invocation) -> HISYNCID1).when(mHearingAidProfile).getHiSyncId(mDevice1);
        doAnswer((invocation) -> HISYNCID2).when(mHearingAidProfile).getHiSyncId(mDevice2);
        CachedBluetoothDevice cachedDevice1 = mCachedDeviceManager.addDevice(mDevice1);
        CachedBluetoothDevice cachedDevice2 = mCachedDeviceManager.addDevice(mDevice2);

        assertThat(cachedDevice1.getSubDevice()).isNull();
    }

    /**
     * Test to verify addDevice(), new device has the same HiSyncId.
     */
    @Test
    public void addDevice_sameHiSyncId_validCachedDevices_mainDevicesAdded_subDevicesNotAdded() {
        doAnswer((invocation) -> HISYNCID1).when(mHearingAidProfile).getHiSyncId(mDevice1);
        doAnswer((invocation) -> HISYNCID1).when(mHearingAidProfile).getHiSyncId(mDevice2);
        CachedBluetoothDevice cachedDevice1 = mCachedDeviceManager.addDevice(mDevice1);
        CachedBluetoothDevice cachedDevice2 = mCachedDeviceManager.addDevice(mDevice2);

        Collection<CachedBluetoothDevice> devices = mCachedDeviceManager.getCachedDevicesCopy();
        assertThat(devices).contains(cachedDevice1);
        assertThat(devices).doesNotContain(cachedDevice2);
    }

    /**
     * Test to verify addDevice(), new device has the different HiSyncId.
     */
    @Test
    public void addDevice_differentHiSyncId_validCachedDevices_bothAdded() {
        doAnswer((invocation) -> HISYNCID1).when(mHearingAidProfile).getHiSyncId(mDevice1);
        doAnswer((invocation) -> HISYNCID2).when(mHearingAidProfile).getHiSyncId(mDevice2);
        CachedBluetoothDevice cachedDevice1 = mCachedDeviceManager.addDevice(mDevice1);
        CachedBluetoothDevice cachedDevice2 = mCachedDeviceManager.addDevice(mDevice2);

        Collection<CachedBluetoothDevice> devices = mCachedDeviceManager.getCachedDevicesCopy();
        assertThat(devices).contains(cachedDevice1);
        assertThat(devices).contains(cachedDevice2);
    }

    /**
     * Test to verify findDevice(), new device has the same HiSyncId.
     */
    @Test
    public void findDevice_sameHiSyncId_foundBothDevice() {
        doAnswer((invocation) -> HISYNCID1).when(mHearingAidProfile).getHiSyncId(mDevice1);
        doAnswer((invocation) -> HISYNCID1).when(mHearingAidProfile).getHiSyncId(mDevice2);
        CachedBluetoothDevice cachedDevice1 = mCachedDeviceManager.addDevice(mDevice1);
        CachedBluetoothDevice cachedDevice2 = mCachedDeviceManager.addDevice(mDevice2);

        assertThat(mCachedDeviceManager.findDevice(mDevice1)).isEqualTo(cachedDevice1);
        assertThat(mCachedDeviceManager.findDevice(mDevice2)).isEqualTo(cachedDevice2);
    }

    /**
     * Test to verify getName().
     */
    @Test
    public void getName_validCachedDevice_nameFound() {
        CachedBluetoothDevice cachedDevice1 = mCachedDeviceManager.addDevice(mDevice1);
        assertThat(cachedDevice1).isNotNull();
        assertThat(mCachedDeviceManager.getName(mDevice1)).isEqualTo(DEVICE_ALIAS_1);
    }

    /**
     * Test to verify onDeviceNameUpdated().
     */
    @Test
    public void onDeviceNameUpdated_validName_nameUpdated() {
        CachedBluetoothDevice cachedDevice1 = mCachedDeviceManager.addDevice(mDevice1);
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
    public void clearNonBondedDevices_bondedAndNonBondedDevices_nonBondedDevicesCleared() {
        CachedBluetoothDevice cachedDevice1 = mCachedDeviceManager.addDevice(mDevice1);
        assertThat(cachedDevice1).isNotNull();
        CachedBluetoothDevice cachedDevice2 = mCachedDeviceManager.addDevice(mDevice2);
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
     * Test to verify clearNonBondedDevices() for hearing aids sub device.
     */
    @Test
    public void clearNonBondedDevices_nonBondedSubDevice() {
        when(mDevice1.getBondState()).thenReturn(BluetoothDevice.BOND_BONDED);
        when(mDevice2.getBondState()).thenReturn(BluetoothDevice.BOND_NONE);
        CachedBluetoothDevice cachedDevice1 = mCachedDeviceManager.addDevice(mDevice1);
        CachedBluetoothDevice cachedDevice2 = mCachedDeviceManager.addDevice(mDevice2);
        cachedDevice1.setSubDevice(cachedDevice2);

        assertThat(cachedDevice1.getSubDevice()).isEqualTo(cachedDevice2);
        mCachedDeviceManager.clearNonBondedDevices();

        assertThat(cachedDevice1.getSubDevice()).isNull();
    }

    /**
     * Test to verify OnDeviceUnpaired() for main hearing Aid device unpair.
     */
    @Test
    public void onDeviceUnpaired_unpairMainDevice() {
        when(mDevice1.getBondState()).thenReturn(BluetoothDevice.BOND_BONDED);
        CachedBluetoothDevice cachedDevice1 = mCachedDeviceManager.addDevice(mDevice1);
        CachedBluetoothDevice cachedDevice2 = mCachedDeviceManager.addDevice(mDevice2);
        cachedDevice1.setHiSyncId(HISYNCID1);
        cachedDevice2.setHiSyncId(HISYNCID1);
        cachedDevice1.setSubDevice(cachedDevice2);

        // Call onDeviceUnpaired for the one in mCachedDevices.
        mCachedDeviceManager.onDeviceUnpaired(cachedDevice2);
        verify(mDevice1).removeBond();
    }

    /**
     * Test to verify OnDeviceUnpaired() for sub hearing Aid device unpair.
     */
    @Test
    public void onDeviceUnpaired_unpairSubDevice() {
        when(mDevice2.getBondState()).thenReturn(BluetoothDevice.BOND_BONDED);
        CachedBluetoothDevice cachedDevice1 = mCachedDeviceManager.addDevice(mDevice1);
        CachedBluetoothDevice cachedDevice2 = mCachedDeviceManager.addDevice(mDevice2);
        cachedDevice1.setSubDevice(cachedDevice2);

        // Call onDeviceUnpaired for the one in mCachedDevices.
        mCachedDeviceManager.onDeviceUnpaired(cachedDevice1);
        verify(mDevice2).removeBond();
    }

    /**
     * Test to verify getSubDeviceSummary() for disconnected sub device
     */
    @Test
    public void getSubDeviceSummary_SubDeviceDisconnected() {
        when(mCachedDevice2.isConnected()).thenReturn(false);
        mCachedDevice1.setSubDevice(mCachedDevice2);
        mCachedDeviceManager.getSubDeviceSummary(mCachedDevice1);

        verify(mCachedDevice2, never()).getConnectionSummary();
    }

    /**
     * Test to verify getSubDeviceSummary() for connected sub device
     */
    @Test
    public void getSubDeviceSummary_SubDeviceConnected() {
        when(mCachedDevice2.isConnected()).thenReturn(true);
        mCachedDevice1.setSubDevice(mCachedDevice2);
        mCachedDeviceManager.getSubDeviceSummary(mCachedDevice1);

        verify(mCachedDevice2).getConnectionSummary();
    }

    /**
     * Test to verify isSubDevice_validSubDevice().
     */
    @Test
    public void isSubDevice_validSubDevice() {
        doReturn(HISYNCID1).when(mHearingAidProfile).getHiSyncId(mDevice1);
        mCachedDeviceManager.addDevice(mDevice1);

        // Both device are not sub device in default value.
        assertThat(mCachedDeviceManager.isSubDevice(mDevice1)).isFalse();
        assertThat(mCachedDeviceManager.isSubDevice(mDevice2)).isFalse();

        // Add Device-2 as sub device of Device-1 with same HiSyncId.
        doReturn(HISYNCID1).when(mHearingAidProfile).getHiSyncId(mDevice2);
        mCachedDeviceManager.addDevice(mDevice2);

        // Verify Device-2 is sub device, but Device-1 is not.
        assertThat(mCachedDeviceManager.isSubDevice(mDevice2)).isTrue();
        assertThat(mCachedDeviceManager.isSubDevice(mDevice1)).isFalse();
    }

    /**
     * Test to verify updateHearingAidsDevices().
     */
    @Test
    public void updateHearingAidDevices_directToHearingAidDeviceManager() {
        mHearingAidDeviceManager = spy(new HearingAidDeviceManager(mLocalBluetoothManager,
                mCachedDeviceManager.mCachedDevices));
        mCachedDeviceManager.mHearingAidDeviceManager = mHearingAidDeviceManager;
        mCachedDeviceManager.updateHearingAidsDevices();

        verify(mHearingAidDeviceManager).updateHearingAidsDevices();
    }

    /**
     * Test to verify onDeviceDisappeared().
     */
    @Test
    public void onDeviceDisappeared_deviceBondedUnbonded_unbondedDeviceDisappeared() {
        CachedBluetoothDevice cachedDevice1 = mCachedDeviceManager.addDevice(mDevice1);
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
    public void onActiveDeviceChanged_connectedDevices_activeDeviceChanged() {
        CachedBluetoothDevice cachedDevice1 = mCachedDeviceManager.addDevice(mDevice1);
        assertThat(cachedDevice1).isNotNull();
        CachedBluetoothDevice cachedDevice2 = mCachedDeviceManager.addDevice(mDevice2);
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

    /**
     * Test to verify onActiveDeviceChanged() with A2DP and Hearing Aid.
     */
    @Test
    public void onActiveDeviceChanged_withA2dpAndHearingAid() {
        CachedBluetoothDevice cachedDevice1 = mCachedDeviceManager.addDevice(mDevice1);
        assertThat(cachedDevice1).isNotNull();
        CachedBluetoothDevice cachedDevice2 = mCachedDeviceManager.addDevice(mDevice2);
        assertThat(cachedDevice2).isNotNull();

        when(mDevice1.getBondState()).thenReturn(BluetoothDevice.BOND_BONDED);
        when(mDevice2.getBondState()).thenReturn(BluetoothDevice.BOND_BONDED);

        // Connect device1 for A2DP and HFP and device2 for Hearing Aid
        cachedDevice1.onProfileStateChanged(mA2dpProfile, BluetoothProfile.STATE_CONNECTED);
        cachedDevice1.onProfileStateChanged(mHfpProfile, BluetoothProfile.STATE_CONNECTED);
        cachedDevice2.onProfileStateChanged(mHearingAidProfile, BluetoothProfile.STATE_CONNECTED);

        // Verify that both devices are connected and none is Active
        assertThat(cachedDevice1.isActiveDevice(BluetoothProfile.A2DP)).isFalse();
        assertThat(cachedDevice1.isActiveDevice(BluetoothProfile.HEADSET)).isFalse();
        assertThat(cachedDevice1.isActiveDevice(BluetoothProfile.HEARING_AID)).isFalse();
        assertThat(cachedDevice2.isActiveDevice(BluetoothProfile.A2DP)).isFalse();
        assertThat(cachedDevice2.isActiveDevice(BluetoothProfile.HEADSET)).isFalse();
        assertThat(cachedDevice2.isActiveDevice(BluetoothProfile.HEARING_AID)).isFalse();

        // The first device is active for A2DP and HFP
        mCachedDeviceManager.onActiveDeviceChanged(cachedDevice1, BluetoothProfile.A2DP);
        mCachedDeviceManager.onActiveDeviceChanged(cachedDevice1, BluetoothProfile.HEADSET);
        assertThat(cachedDevice1.isActiveDevice(BluetoothProfile.A2DP)).isTrue();
        assertThat(cachedDevice1.isActiveDevice(BluetoothProfile.HEADSET)).isTrue();
        assertThat(cachedDevice1.isActiveDevice(BluetoothProfile.HEARING_AID)).isFalse();
        assertThat(cachedDevice2.isActiveDevice(BluetoothProfile.A2DP)).isFalse();
        assertThat(cachedDevice2.isActiveDevice(BluetoothProfile.HEADSET)).isFalse();
        assertThat(cachedDevice2.isActiveDevice(BluetoothProfile.HEARING_AID)).isFalse();

        // The second device is active for Hearing Aid and the first device is not active
        mCachedDeviceManager.onActiveDeviceChanged(null, BluetoothProfile.A2DP);
        mCachedDeviceManager.onActiveDeviceChanged(null, BluetoothProfile.HEADSET);
        mCachedDeviceManager.onActiveDeviceChanged(cachedDevice2, BluetoothProfile.HEARING_AID);
        assertThat(cachedDevice1.isActiveDevice(BluetoothProfile.A2DP)).isFalse();
        assertThat(cachedDevice1.isActiveDevice(BluetoothProfile.HEADSET)).isFalse();
        assertThat(cachedDevice1.isActiveDevice(BluetoothProfile.HEARING_AID)).isFalse();
        assertThat(cachedDevice2.isActiveDevice(BluetoothProfile.A2DP)).isFalse();
        assertThat(cachedDevice2.isActiveDevice(BluetoothProfile.HEADSET)).isFalse();
        assertThat(cachedDevice2.isActiveDevice(BluetoothProfile.HEARING_AID)).isTrue();

        // No active device for Hearing Aid
        mCachedDeviceManager.onActiveDeviceChanged(null, BluetoothProfile.HEARING_AID);
        assertThat(cachedDevice1.isActiveDevice(BluetoothProfile.A2DP)).isFalse();
        assertThat(cachedDevice1.isActiveDevice(BluetoothProfile.HEADSET)).isFalse();
        assertThat(cachedDevice2.isActiveDevice(BluetoothProfile.A2DP)).isFalse();
        assertThat(cachedDevice2.isActiveDevice(BluetoothProfile.A2DP)).isFalse();
        assertThat(cachedDevice2.isActiveDevice(BluetoothProfile.HEADSET)).isFalse();
        assertThat(cachedDevice2.isActiveDevice(BluetoothProfile.HEARING_AID)).isFalse();
    }
}
