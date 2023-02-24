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
import android.bluetooth.BluetoothCsipSetCoordinator;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothUuid;
import android.content.Context;
import android.os.Parcel;
import android.os.ParcelUuid;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;

import java.util.Collection;
import java.util.Map;

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
    private final static long HISYNCID1 = 10;
    private final static long HISYNCID2 = 11;
    private final static Map<Integer, ParcelUuid> CAP_GROUP1 =
            Map.of(1, BluetoothUuid.CAP);
    private final static Map<Integer, ParcelUuid> CAP_GROUP2 =
            Map.of(2, BluetoothUuid.CAP);
    private final BluetoothClass DEVICE_CLASS_1 =
            createBtClass(BluetoothClass.Device.AUDIO_VIDEO_HEADPHONES);
    private final BluetoothClass DEVICE_CLASS_2 =
            createBtClass(BluetoothClass.Device.AUDIO_VIDEO_HANDSFREE);
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
    private CsipSetCoordinatorProfile mCsipSetCoordinatorProfile;
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

    private BluetoothClass createBtClass(int deviceClass) {
        Parcel p = Parcel.obtain();
        p.writeInt(deviceClass);
        p.setDataPosition(0); // reset position of parcel before passing to constructor

        BluetoothClass bluetoothClass = BluetoothClass.CREATOR.createFromParcel(p);
        p.recycle();
        return bluetoothClass;
    }

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
        when(mDevice1.getAlias()).thenReturn(DEVICE_ALIAS_1);
        when(mDevice2.getAlias()).thenReturn(DEVICE_ALIAS_2);
        when(mDevice3.getAlias()).thenReturn(DEVICE_ALIAS_3);
        when(mDevice1.getBluetoothClass()).thenReturn(DEVICE_CLASS_1);
        when(mDevice2.getBluetoothClass()).thenReturn(DEVICE_CLASS_2);
        when(mDevice3.getBluetoothClass()).thenReturn(DEVICE_CLASS_2);

        when(mLocalBluetoothManager.getEventManager()).thenReturn(mBluetoothEventManager);
        when(mLocalBluetoothManager.getProfileManager()).thenReturn(mLocalProfileManager);
        when(mHfpProfile.isProfileReady()).thenReturn(true);
        when(mA2dpProfile.isProfileReady()).thenReturn(true);
        when(mPanProfile.isProfileReady()).thenReturn(true);
        when(mHearingAidProfile.isProfileReady()).thenReturn(true);
        when(mCsipSetCoordinatorProfile.isProfileReady())
                .thenReturn(true);
        doAnswer((invocation) -> mHearingAidProfile).
                when(mLocalProfileManager).getHearingAidProfile();
        doAnswer((invocation) -> mCsipSetCoordinatorProfile)
                .when(mLocalProfileManager).getCsipSetCoordinatorProfile();
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
     * Test to verify addDevice(), the duplicated device should not added.
     */
    @Test
    public void addDevice_addDuplicatedDevice_duplicateDeviceShouldNotAdded() {
        final CachedBluetoothDevice cachedDevice1 = mCachedDeviceManager.addDevice(mDevice1);
        assertThat(cachedDevice1).isNotNull();
        final CachedBluetoothDevice cachedDevice2 = mCachedDeviceManager.addDevice(mDevice2);
        assertThat(cachedDevice2).isNotNull();
        final CachedBluetoothDevice cachedDevice3 = mCachedDeviceManager.addDevice(mDevice2);
        assertThat(cachedDevice3).isNotNull();
        final CachedBluetoothDevice cachedDevice4 = mCachedDeviceManager.addDevice(mDevice2);
        assertThat(cachedDevice4).isNotNull();

        final Collection<CachedBluetoothDevice> devices =
                mCachedDeviceManager.getCachedDevicesCopy();
        assertThat(devices.size()).isEqualTo(2);
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
        when(mDevice1.getAlias()).thenReturn(newAliasName);
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
    public void onDeviceUnpaired_unpairHearingAidMainDevice() {
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
     * Test to verify getMemberDevice(), new device has the same group id.
     */
    @Test
    public void addDevice_sameGroupId_validMemberDevice() {
        doAnswer((invocation) -> CAP_GROUP1).when(mCsipSetCoordinatorProfile)
                .getGroupUuidMapByDevice(mDevice1);
        doAnswer((invocation) -> CAP_GROUP1).when(mCsipSetCoordinatorProfile)
                .getGroupUuidMapByDevice(mDevice2);
        doAnswer((invocation) -> CAP_GROUP1).when(mCsipSetCoordinatorProfile)
                .getGroupUuidMapByDevice(mDevice3);
        CachedBluetoothDevice cachedDevice1 = mCachedDeviceManager.addDevice(mDevice1);
        CachedBluetoothDevice cachedDevice2 = mCachedDeviceManager.addDevice(mDevice2);
        CachedBluetoothDevice cachedDevice3 = mCachedDeviceManager.addDevice(mDevice3);

        assertThat(cachedDevice1.getMemberDevice()).contains(cachedDevice2);
        assertThat(cachedDevice1.getMemberDevice()).contains(cachedDevice3);
    }

    /**
     * Test to verify getMemberDevice(), new device has the different group id.
     */
    @Test
    public void addDevice_differentGroupId_validMemberDevice() {
        doAnswer((invocation) -> CAP_GROUP1).when(mCsipSetCoordinatorProfile)
                .getGroupUuidMapByDevice(mDevice1);
        doAnswer((invocation) -> CAP_GROUP2).when(mCsipSetCoordinatorProfile)
                .getGroupUuidMapByDevice(mDevice2);
        CachedBluetoothDevice cachedDevice1 = mCachedDeviceManager.addDevice(mDevice1);
        CachedBluetoothDevice cachedDevice2 = mCachedDeviceManager.addDevice(mDevice2);

        assertThat(cachedDevice1.getMemberDevice()).isEmpty();
    }

    /**
     * Test to verify addDevice(), new device has the same group id.
     */
    @Test
    public void addDevice_sameGroupId_validCachedDevices_mainDevicesAdded_memberDevicesNotAdded() {
        doAnswer((invocation) -> CAP_GROUP1).when(mCsipSetCoordinatorProfile)
                .getGroupUuidMapByDevice(mDevice1);
        doAnswer((invocation) -> CAP_GROUP1).when(mCsipSetCoordinatorProfile)
                .getGroupUuidMapByDevice(mDevice2);
        CachedBluetoothDevice cachedDevice1 = mCachedDeviceManager.addDevice(mDevice1);
        CachedBluetoothDevice cachedDevice2 = mCachedDeviceManager.addDevice(mDevice2);

        Collection<CachedBluetoothDevice> devices = mCachedDeviceManager.getCachedDevicesCopy();
        assertThat(devices).contains(cachedDevice1);
        assertThat(devices).doesNotContain(cachedDevice2);
    }

    /**
     * Test to verify addDevice(), new device has the different group id.
     */
    @Test
    public void addDevice_differentGroupId_validCachedDevices_bothAdded() {
        doAnswer((invocation) -> CAP_GROUP1).when(mCsipSetCoordinatorProfile)
                .getGroupUuidMapByDevice(mDevice1);
        doAnswer((invocation) -> CAP_GROUP2).when(mCsipSetCoordinatorProfile)
                .getGroupUuidMapByDevice(mDevice2);
        CachedBluetoothDevice cachedDevice1 = mCachedDeviceManager.addDevice(mDevice1);
        CachedBluetoothDevice cachedDevice2 = mCachedDeviceManager.addDevice(mDevice2);

        Collection<CachedBluetoothDevice> devices = mCachedDeviceManager.getCachedDevicesCopy();
        assertThat(devices).contains(cachedDevice1);
        assertThat(devices).contains(cachedDevice2);
    }

    /**
     * Test to verify clearNonBondedDevices() for csip set member device.
     */
    @Test
    public void clearNonBondedDevices_nonBondedMemberDevice() {
        when(mDevice1.getBondState()).thenReturn(BluetoothDevice.BOND_BONDED);
        when(mDevice2.getBondState()).thenReturn(BluetoothDevice.BOND_NONE);
        when(mDevice3.getBondState()).thenReturn(BluetoothDevice.BOND_BONDED);
        CachedBluetoothDevice cachedDevice1 = mCachedDeviceManager.addDevice(mDevice1);
        CachedBluetoothDevice cachedDevice2 = mCachedDeviceManager.addDevice(mDevice2);
        CachedBluetoothDevice cachedDevice3 = mCachedDeviceManager.addDevice(mDevice3);
        cachedDevice1.addMemberDevice(cachedDevice2);
        cachedDevice1.addMemberDevice(cachedDevice3);

        assertThat(cachedDevice1.getMemberDevice()).contains(cachedDevice2);
        assertThat(cachedDevice1.getMemberDevice()).contains(cachedDevice3);
        mCachedDeviceManager.clearNonBondedDevices();

        assertThat(cachedDevice1.getMemberDevice().contains(cachedDevice2)).isFalse();
        assertThat(cachedDevice1.getMemberDevice().contains(cachedDevice3)).isTrue();
    }

    /**
     * Test to verify OnDeviceUnpaired() for csip device unpair.
     */
    @Test
    public void onDeviceUnpaired_unpairCsipMainDevice() {
        when(mDevice1.getBondState()).thenReturn(BluetoothDevice.BOND_NONE);
        when(mDevice2.getBondState()).thenReturn(BluetoothDevice.BOND_BONDED);
        CachedBluetoothDevice cachedDevice1 = mCachedDeviceManager.addDevice(mDevice1);
        CachedBluetoothDevice cachedDevice2 = mCachedDeviceManager.addDevice(mDevice2);
        cachedDevice1.setGroupId(1);
        cachedDevice2.setGroupId(1);
        cachedDevice1.addMemberDevice(cachedDevice2);

        // Call onDeviceUnpaired for the one in mCachedDevices.
        mCachedDeviceManager.onDeviceUnpaired(cachedDevice1);

        verify(mDevice2).removeBond();
        assertThat(cachedDevice1.getGroupId()).isEqualTo(
                BluetoothCsipSetCoordinator.GROUP_ID_INVALID);
        assertThat(cachedDevice2.getGroupId()).isEqualTo(
                BluetoothCsipSetCoordinator.GROUP_ID_INVALID);
    }

    /**
     * Test to verify OnDeviceUnpaired() for csip device unpair.
     */
    @Test
    public void onDeviceUnpaired_unpairCsipSubDevice() {
        when(mDevice1.getBondState()).thenReturn(BluetoothDevice.BOND_BONDED);
        when(mDevice2.getBondState()).thenReturn(BluetoothDevice.BOND_NONE);
        CachedBluetoothDevice cachedDevice1 = mCachedDeviceManager.addDevice(mDevice1);
        CachedBluetoothDevice cachedDevice2 = mCachedDeviceManager.addDevice(mDevice2);
        cachedDevice1.setGroupId(1);
        cachedDevice2.setGroupId(1);
        cachedDevice1.addMemberDevice(cachedDevice2);

        // Call onDeviceUnpaired for the one in mCachedDevices.
        mCachedDeviceManager.onDeviceUnpaired(cachedDevice2);

        verify(mDevice1).removeBond();
        assertThat(cachedDevice2.getGroupId()).isEqualTo(
                BluetoothCsipSetCoordinator.GROUP_ID_INVALID);
    }

    /**
     * Test to verify isSubDevice_validSubDevice().
     */
    @Test
    public void isSubDevice_validMemberDevice() {
        doReturn(CAP_GROUP1).when(mCsipSetCoordinatorProfile).getGroupUuidMapByDevice(mDevice1);
        mCachedDeviceManager.addDevice(mDevice1);

        // Both device are not sub device in default value.
        assertThat(mCachedDeviceManager.isSubDevice(mDevice1)).isFalse();
        assertThat(mCachedDeviceManager.isSubDevice(mDevice2)).isFalse();

        // Add Device-2 as device with Device-1 with the same group id, and add Device-3 with
        // the different group id.
        doReturn(CAP_GROUP1).when(mCsipSetCoordinatorProfile).getGroupUuidMapByDevice(mDevice2);
        doReturn(CAP_GROUP2).when(mCsipSetCoordinatorProfile).getGroupUuidMapByDevice(mDevice3);
        mCachedDeviceManager.addDevice(mDevice2);
        mCachedDeviceManager.addDevice(mDevice3);

        // Verify Device-2 is sub device, but Device-1, and Device-3 is not.
        assertThat(mCachedDeviceManager.isSubDevice(mDevice1)).isFalse();
        assertThat(mCachedDeviceManager.isSubDevice(mDevice2)).isTrue();
        assertThat(mCachedDeviceManager.isSubDevice(mDevice3)).isFalse();
    }
}
