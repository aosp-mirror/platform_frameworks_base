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
    private final static String DEVICE_NAME_3 = "TestName_3";
    private final static String DEVICE_ALIAS_1 = "TestAlias_1";
    private final static String DEVICE_ALIAS_2 = "TestAlias_2";
    private final static String DEVICE_ALIAS_3 = "TestAlias_3";
    private final static String DEVICE_ADDRESS_1 = "AA:BB:CC:DD:EE:11";
    private final static String DEVICE_ADDRESS_2 = "AA:BB:CC:DD:EE:22";
    private final static String DEVICE_ADDRESS_3 = "AA:BB:CC:DD:EE:33";
    private final static String DEVICE_SUMMARY_1 = "summary 1";
    private final static String DEVICE_SUMMARY_2 = "summary 2";
    private final static String DEVICE_SUMMARY_3 = "summary 3";
    private final static long HISYNCID1 = 10;
    private final static long HISYNCID2 = 11;
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
        when(mLocalAdapter.getBluetoothState()).thenReturn(BluetoothAdapter.STATE_ON);
        when(mHfpProfile.isProfileReady()).thenReturn(true);
        when(mA2dpProfile.isProfileReady()).thenReturn(true);
        when(mPanProfile.isProfileReady()).thenReturn(true);
        when(mHearingAidProfile.isProfileReady()).thenReturn(true);
        mCachedDeviceManager = new CachedBluetoothDeviceManager(mContext, mLocalBluetoothManager);
        mCachedDevice1 = spy(
            new CachedBluetoothDevice(mContext, mLocalAdapter, mLocalProfileManager, mDevice1));
        mCachedDevice2 = spy(
            new CachedBluetoothDevice(mContext, mLocalAdapter, mLocalProfileManager, mDevice2));
        mCachedDevice3 = spy(
            new CachedBluetoothDevice(mContext, mLocalAdapter, mLocalProfileManager, mDevice3));
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
     * Test to verify clearNonBondedDevices() for hearing aids.
     */
    @Test
    public void testClearNonBondedDevices_HearingAids_nonBondedHAsClearedFromCachedDevicesMap() {
        when(mDevice1.getBondState()).thenReturn(BluetoothDevice.BOND_BONDED);
        when(mDevice2.getBondState()).thenReturn(BluetoothDevice.BOND_NONE);

        mCachedDevice1.setHiSyncId(HISYNCID1);
        mCachedDevice2.setHiSyncId(HISYNCID2);
        mCachedDeviceManager.mCachedDevicesMapForHearingAids.put(HISYNCID1, mCachedDevice1);
        mCachedDeviceManager.mCachedDevicesMapForHearingAids.put(HISYNCID2, mCachedDevice2);

        mCachedDeviceManager.clearNonBondedDevices();

        assertThat(mCachedDeviceManager.mCachedDevicesMapForHearingAids.values())
            .doesNotContain(mCachedDevice2);
        assertThat(mCachedDeviceManager.mCachedDevicesMapForHearingAids.values())
            .contains(mCachedDevice1);
    }

    /**
     * Test to verify onHiSyncIdChanged() for hearing aid devices with same HiSyncId.
     */
    @Test
    public void testOnHiSyncIdChanged_sameHiSyncId_populateInDifferentLists() {
        CachedBluetoothDevice cachedDevice1 = mCachedDeviceManager.addDevice(mLocalAdapter,
            mLocalProfileManager, mDevice1);
        assertThat(cachedDevice1).isNotNull();
        CachedBluetoothDevice cachedDevice2 = mCachedDeviceManager.addDevice(mLocalAdapter,
            mLocalProfileManager, mDevice2);
        assertThat(cachedDevice2).isNotNull();

        // Since both devices do not have hiSyncId, they should be added in mCachedDevices.
        assertThat(mCachedDeviceManager.getCachedDevicesCopy()).hasSize(2);
        assertThat(mCachedDeviceManager.mHearingAidDevicesNotAddedInCache).isEmpty();
        assertThat(mCachedDeviceManager.mCachedDevicesMapForHearingAids).isEmpty();

        cachedDevice1.setHiSyncId(HISYNCID1);
        cachedDevice2.setHiSyncId(HISYNCID1);
        mCachedDeviceManager.onHiSyncIdChanged(HISYNCID1);

        // Since both devices have the same hiSyncId, one should remain in mCachedDevices
        // and the other should be removed from mCachedDevices and get added in
        // mHearingAidDevicesNotAddedInCache. The one that is in mCachedDevices should also be
        // added in mCachedDevicesMapForHearingAids.
        assertThat(mCachedDeviceManager.getCachedDevicesCopy()).hasSize(1);
        assertThat(mCachedDeviceManager.mHearingAidDevicesNotAddedInCache).hasSize(1);
        assertThat(mCachedDeviceManager.mCachedDevicesMapForHearingAids).hasSize(1);
        Collection<CachedBluetoothDevice> devices = mCachedDeviceManager.getCachedDevicesCopy();
        assertThat(devices).contains(cachedDevice2);
        assertThat(mCachedDeviceManager.mCachedDevicesMapForHearingAids)
            .containsKey(HISYNCID1);
    }

    /**
     * Test to verify onHiSyncIdChanged() for 2 hearing aid devices with same HiSyncId but one
     * device is connected and other is disconnected. The connected device should be chosen.
     */
    @Test
    public void testOnHiSyncIdChanged_sameHiSyncIdAndOneConnected_chooseConnectedDevice() {
        CachedBluetoothDevice cachedDevice1 = mCachedDeviceManager.addDevice(mLocalAdapter,
            mLocalProfileManager, mDevice1);
        assertThat(cachedDevice1).isNotNull();
        CachedBluetoothDevice cachedDevice2 = mCachedDeviceManager.addDevice(mLocalAdapter,
            mLocalProfileManager, mDevice2);
        assertThat(cachedDevice2).isNotNull();
        cachedDevice1.onProfileStateChanged(mHearingAidProfile, BluetoothProfile.STATE_CONNECTED);
        cachedDevice2.onProfileStateChanged(mHearingAidProfile, BluetoothProfile.STATE_CONNECTED);

        /* Device 1 is connected and Device 2 is disconnected */
        when(mHearingAidProfile.getConnectionStatus(mDevice1)).
            thenReturn(BluetoothProfile.STATE_CONNECTED);
        when(mHearingAidProfile.getConnectionStatus(mDevice2)).
            thenReturn(BluetoothProfile.STATE_DISCONNECTED);

        // Since both devices do not have hiSyncId, they should be added in mCachedDevices.
        assertThat(mCachedDeviceManager.getCachedDevicesCopy()).hasSize(2);
        assertThat(mCachedDeviceManager.mHearingAidDevicesNotAddedInCache).isEmpty();
        assertThat(mCachedDeviceManager.mCachedDevicesMapForHearingAids).isEmpty();

        cachedDevice1.setHiSyncId(HISYNCID1);
        cachedDevice2.setHiSyncId(HISYNCID1);
        mCachedDeviceManager.onHiSyncIdChanged(HISYNCID1);

        // Only the connected device, device 1, should be visible to UI.
        assertThat(mCachedDeviceManager.getCachedDevicesCopy()).containsExactly(cachedDevice1);
        assertThat(mCachedDeviceManager.mCachedDevicesMapForHearingAids).
            containsExactly(HISYNCID1, cachedDevice1);
        assertThat(mCachedDeviceManager.mHearingAidDevicesNotAddedInCache).
            containsExactly(cachedDevice2);
    }

    /**
     * Test to verify onHiSyncIdChanged() for hearing aid devices with different HiSyncId.
     */
    @Test
    public void testOnHiSyncIdChanged_differentHiSyncId_populateInSameList() {
        CachedBluetoothDevice cachedDevice1 = mCachedDeviceManager.addDevice(mLocalAdapter,
            mLocalProfileManager, mDevice1);
        assertThat(cachedDevice1).isNotNull();
        CachedBluetoothDevice cachedDevice2 = mCachedDeviceManager.addDevice(mLocalAdapter,
            mLocalProfileManager, mDevice2);
        assertThat(cachedDevice2).isNotNull();

        // Since both devices do not have hiSyncId, they should be added in mCachedDevices.
        assertThat(mCachedDeviceManager.getCachedDevicesCopy()).hasSize(2);
        assertThat(mCachedDeviceManager.mHearingAidDevicesNotAddedInCache).isEmpty();
        assertThat(mCachedDeviceManager.mCachedDevicesMapForHearingAids).isEmpty();

        cachedDevice1.setHiSyncId(HISYNCID1);
        cachedDevice2.setHiSyncId(HISYNCID2);
        mCachedDeviceManager.onHiSyncIdChanged(HISYNCID1);
        mCachedDeviceManager.onHiSyncIdChanged(HISYNCID2);

        // Since both devices do not have same hiSyncId, they should remain in mCachedDevices and
        // also be added in mCachedDevicesMapForHearingAids.
        assertThat(mCachedDeviceManager.getCachedDevicesCopy()).hasSize(2);
        assertThat(mCachedDeviceManager.mHearingAidDevicesNotAddedInCache).isEmpty();
        assertThat(mCachedDeviceManager.mCachedDevicesMapForHearingAids).hasSize(2);
        Collection<CachedBluetoothDevice> devices = mCachedDeviceManager.getCachedDevicesCopy();
        assertThat(devices).contains(cachedDevice2);
        assertThat(devices).contains(cachedDevice1);
        assertThat(mCachedDeviceManager.mCachedDevicesMapForHearingAids.values())
            .contains(cachedDevice1);
        assertThat(mCachedDeviceManager.mCachedDevicesMapForHearingAids.values())
            .contains(cachedDevice2);
    }

    /**
     * Test to verify onProfileConnectionStateChanged() for single hearing aid device connection.
     */
    @Test
    public void testOnProfileConnectionStateChanged_singleDeviceConnected_visible() {
        CachedBluetoothDevice cachedDevice1 = mCachedDeviceManager.addDevice(mLocalAdapter,
            mLocalProfileManager, mDevice1);
        assertThat(cachedDevice1).isNotNull();
        cachedDevice1.onProfileStateChanged(mHearingAidProfile, BluetoothProfile.STATE_CONNECTED);

        // Since both devices do not have hiSyncId, they should be added in mCachedDevices.
        assertThat(mCachedDeviceManager.getCachedDevicesCopy()).containsExactly(cachedDevice1);
        assertThat(mCachedDeviceManager.mHearingAidDevicesNotAddedInCache).isEmpty();
        assertThat(mCachedDeviceManager.mCachedDevicesMapForHearingAids).isEmpty();

        cachedDevice1.setHiSyncId(HISYNCID1);
        mCachedDeviceManager.onHiSyncIdChanged(HISYNCID1);

        // Connect the Device 1
        mCachedDeviceManager.onProfileConnectionStateChanged(cachedDevice1,
            BluetoothProfile.STATE_CONNECTED, BluetoothProfile.HEARING_AID);

        assertThat(mCachedDeviceManager.getCachedDevicesCopy()).containsExactly(cachedDevice1);
        assertThat(mCachedDeviceManager.mCachedDevicesMapForHearingAids).
            containsExactly(HISYNCID1, cachedDevice1);
        assertThat(mCachedDeviceManager.mHearingAidDevicesNotAddedInCache).isEmpty();

        // Disconnect the Device 1
        mCachedDeviceManager.onProfileConnectionStateChanged(cachedDevice1,
            BluetoothProfile.STATE_DISCONNECTED, BluetoothProfile.HEARING_AID);

        assertThat(mCachedDeviceManager.getCachedDevicesCopy()).containsExactly(cachedDevice1);
        assertThat(mCachedDeviceManager.mCachedDevicesMapForHearingAids).
            containsExactly(HISYNCID1, cachedDevice1);
        assertThat(mCachedDeviceManager.mHearingAidDevicesNotAddedInCache).isEmpty();
    }

    /**
     * Test to verify onProfileConnectionStateChanged() for two hearing aid devices where both
     * devices are disconnected and they get connected.
     */
    @Test
    public void testOnProfileConnectionStateChanged_twoDevicesConnected_oneDeviceVisible() {
        CachedBluetoothDevice cachedDevice1 = mCachedDeviceManager.addDevice(mLocalAdapter,
            mLocalProfileManager, mDevice1);
        assertThat(cachedDevice1).isNotNull();
        CachedBluetoothDevice cachedDevice2 = mCachedDeviceManager.addDevice(mLocalAdapter,
            mLocalProfileManager, mDevice2);
        assertThat(cachedDevice2).isNotNull();
        cachedDevice1.onProfileStateChanged(mHearingAidProfile, BluetoothProfile.STATE_CONNECTED);
        cachedDevice2.onProfileStateChanged(mHearingAidProfile, BluetoothProfile.STATE_CONNECTED);
        when(mDevice1.getBondState()).thenReturn(BluetoothDevice.BOND_BONDED);
        when(mDevice2.getBondState()).thenReturn(BluetoothDevice.BOND_BONDED);

        // Since both devices do not have hiSyncId, they should be added in mCachedDevices.
        assertThat(mCachedDeviceManager.getCachedDevicesCopy()).hasSize(2);
        assertThat(mCachedDeviceManager.mHearingAidDevicesNotAddedInCache).isEmpty();
        assertThat(mCachedDeviceManager.mCachedDevicesMapForHearingAids).isEmpty();

        cachedDevice1.setHiSyncId(HISYNCID1);
        cachedDevice2.setHiSyncId(HISYNCID1);
        mCachedDeviceManager.onHiSyncIdChanged(HISYNCID1);

        // There should be one cached device but can be either one.
        assertThat(mCachedDeviceManager.getCachedDevicesCopy()).hasSize(1);

        // Connect the Device 1
        mCachedDeviceManager.onProfileConnectionStateChanged(cachedDevice1,
            BluetoothProfile.STATE_CONNECTED, BluetoothProfile.HEARING_AID);

        assertThat(mCachedDeviceManager.getCachedDevicesCopy()).containsExactly(cachedDevice1);
        assertThat(mCachedDeviceManager.mCachedDevicesMapForHearingAids).
            containsExactly(HISYNCID1, cachedDevice1);
        assertThat(mCachedDeviceManager.mHearingAidDevicesNotAddedInCache).contains(cachedDevice2);
        assertThat(mCachedDeviceManager.mCachedDevices).contains(cachedDevice1);

        when(mHearingAidProfile.getConnectionStatus(mDevice1)).
            thenReturn(BluetoothProfile.STATE_CONNECTED);
        when(mHearingAidProfile.getConnectionStatus(mDevice2)).
            thenReturn(BluetoothProfile.STATE_DISCONNECTED);

        // Connect the Device 2
        mCachedDeviceManager.onProfileConnectionStateChanged(cachedDevice2,
            BluetoothProfile.STATE_CONNECTED, BluetoothProfile.HEARING_AID);

        assertThat(mCachedDeviceManager.getCachedDevicesCopy()).hasSize(1);
        assertThat(mCachedDeviceManager.mHearingAidDevicesNotAddedInCache).hasSize(1);
        assertThat(mCachedDeviceManager.mCachedDevices).hasSize(1);
        assertThat(mCachedDeviceManager.mCachedDevicesMapForHearingAids).hasSize(1);
    }

    /**
     * Test to verify onProfileConnectionStateChanged() for two hearing aid devices where both
     * devices are connected and they get disconnected.
     */
    @Test
    public void testOnProfileConnectionStateChanged_twoDevicesDisconnected_oneDeviceVisible() {
        CachedBluetoothDevice cachedDevice1 = mCachedDeviceManager.addDevice(mLocalAdapter,
            mLocalProfileManager, mDevice1);
        assertThat(cachedDevice1).isNotNull();
        CachedBluetoothDevice cachedDevice2 = mCachedDeviceManager.addDevice(mLocalAdapter,
            mLocalProfileManager, mDevice2);
        assertThat(cachedDevice2).isNotNull();
        cachedDevice1.onProfileStateChanged(mHearingAidProfile, BluetoothProfile.STATE_CONNECTED);
        cachedDevice2.onProfileStateChanged(mHearingAidProfile, BluetoothProfile.STATE_CONNECTED);

        when(mDevice1.getBondState()).thenReturn(BluetoothDevice.BOND_BONDED);
        when(mDevice2.getBondState()).thenReturn(BluetoothDevice.BOND_BONDED);
        when(mHearingAidProfile.getConnectionStatus(mDevice1)).
            thenReturn(BluetoothProfile.STATE_CONNECTED);
        when(mHearingAidProfile.getConnectionStatus(mDevice2)).
            thenReturn(BluetoothProfile.STATE_CONNECTED);

        // Since both devices do not have hiSyncId, they should be added in mCachedDevices.
        assertThat(mCachedDeviceManager.getCachedDevicesCopy()).hasSize(2);
        assertThat(mCachedDeviceManager.mHearingAidDevicesNotAddedInCache).isEmpty();
        assertThat(mCachedDeviceManager.mCachedDevicesMapForHearingAids).isEmpty();

        cachedDevice1.setHiSyncId(HISYNCID1);
        cachedDevice2.setHiSyncId(HISYNCID1);
        mCachedDeviceManager.onHiSyncIdChanged(HISYNCID1);

        /* Disconnect the Device 1 */
        mCachedDeviceManager.onProfileConnectionStateChanged(cachedDevice1,
            BluetoothProfile.STATE_DISCONNECTED, BluetoothProfile.HEARING_AID);

        assertThat(mCachedDeviceManager.getCachedDevicesCopy()).containsExactly(cachedDevice2);
        assertThat(mCachedDeviceManager.mHearingAidDevicesNotAddedInCache).contains(cachedDevice1);
        assertThat(mCachedDeviceManager.mCachedDevices).contains(cachedDevice2);
        assertThat(mCachedDeviceManager.mCachedDevicesMapForHearingAids)
            .containsExactly(HISYNCID1, cachedDevice2);

        when(mHearingAidProfile.getConnectionStatus(mDevice1)).
            thenReturn(BluetoothProfile.STATE_DISCONNECTED);
        when(mHearingAidProfile.getConnectionStatus(mDevice2)).
            thenReturn(BluetoothProfile.STATE_CONNECTED);

        /* Disconnect the Device 2 */
        mCachedDeviceManager.onProfileConnectionStateChanged(cachedDevice2,
            BluetoothProfile.STATE_DISCONNECTED, BluetoothProfile.HEARING_AID);

        assertThat(mCachedDeviceManager.getCachedDevicesCopy()).hasSize(1);
        assertThat(mCachedDeviceManager.mHearingAidDevicesNotAddedInCache).hasSize(1);
        assertThat(mCachedDeviceManager.mCachedDevices).hasSize(1);
        assertThat(mCachedDeviceManager.mCachedDevicesMapForHearingAids).hasSize(1);
    }

    /**
     * Test to verify OnDeviceUnpaired() for a paired hearing Aid device pair.
     */
    @Test
    public void testOnDeviceUnpaired_bothHearingAidsPaired_removesItsPairFromList() {
        CachedBluetoothDevice cachedDevice1 = mCachedDeviceManager.addDevice(mLocalAdapter,
            mLocalProfileManager, mDevice1);
        assertThat(cachedDevice1).isNotNull();
        CachedBluetoothDevice cachedDevice2 = mCachedDeviceManager.addDevice(mLocalAdapter,
            mLocalProfileManager, mDevice2);
        assertThat(cachedDevice2).isNotNull();

        cachedDevice1.setHiSyncId(HISYNCID1);
        cachedDevice2.setHiSyncId(HISYNCID1);
        mCachedDeviceManager.onHiSyncIdChanged(HISYNCID1);

        // Check if one device is in mCachedDevices and one in mHearingAidDevicesNotAddedInCache.
        Collection<CachedBluetoothDevice> devices = mCachedDeviceManager.getCachedDevicesCopy();
        assertThat(devices).contains(cachedDevice2);
        assertThat(devices).doesNotContain(cachedDevice1);
        assertThat(mCachedDeviceManager.mHearingAidDevicesNotAddedInCache).contains(cachedDevice1);
        assertThat(mCachedDeviceManager.mHearingAidDevicesNotAddedInCache)
            .doesNotContain(cachedDevice2);

        // Call onDeviceUnpaired for the one in mCachedDevices.
        mCachedDeviceManager.onDeviceUnpaired(cachedDevice2);

        // Check if its pair is removed from mHearingAidDevicesNotAddedInCache.
        assertThat(mCachedDeviceManager.mHearingAidDevicesNotAddedInCache)
            .doesNotContain(cachedDevice1);
    }

    /**
     * Test to verify OnDeviceUnpaired() for paired hearing Aid devices which are not a pair.
     */
    @Test
    public void testOnDeviceUnpaired_bothHearingAidsNotPaired_doesNotRemoveAnyDeviceFromList() {
        CachedBluetoothDevice cachedDevice1 = mCachedDeviceManager.addDevice(mLocalAdapter,
            mLocalProfileManager, mDevice1);
        assertThat(cachedDevice1).isNotNull();
        CachedBluetoothDevice cachedDevice2 = mCachedDeviceManager.addDevice(mLocalAdapter,
            mLocalProfileManager, mDevice2);
        assertThat(cachedDevice2).isNotNull();
        CachedBluetoothDevice cachedDevice3 = mCachedDeviceManager.addDevice(mLocalAdapter,
            mLocalProfileManager, mDevice3);
        assertThat(cachedDevice2).isNotNull();

        cachedDevice1.setHiSyncId(HISYNCID1);
        cachedDevice2.setHiSyncId(HISYNCID1);
        cachedDevice3.setHiSyncId(HISYNCID2);
        mCachedDeviceManager.onHiSyncIdChanged(HISYNCID1);
        mCachedDeviceManager.onHiSyncIdChanged(HISYNCID2);

        // Check if one device is in mCachedDevices and one in mHearingAidDevicesNotAddedInCache.
        Collection<CachedBluetoothDevice> devices = mCachedDeviceManager.getCachedDevicesCopy();
        assertThat(devices).contains(cachedDevice2);
        assertThat(devices).contains(cachedDevice3);
        assertThat(devices).doesNotContain(cachedDevice1);
        assertThat(mCachedDeviceManager.mHearingAidDevicesNotAddedInCache).contains(cachedDevice1);
        assertThat(mCachedDeviceManager.mHearingAidDevicesNotAddedInCache)
            .doesNotContain(cachedDevice2);
        assertThat(mCachedDeviceManager.mHearingAidDevicesNotAddedInCache)
            .doesNotContain(cachedDevice3);

        // Call onDeviceUnpaired for the one in mCachedDevices with no pair.
        mCachedDeviceManager.onDeviceUnpaired(cachedDevice3);

        // Check if no list is changed.
        devices = mCachedDeviceManager.getCachedDevicesCopy();
        assertThat(devices).contains(cachedDevice2);
        assertThat(devices).contains(cachedDevice3);
        assertThat(devices).doesNotContain(cachedDevice1);
        assertThat(mCachedDeviceManager.mHearingAidDevicesNotAddedInCache).contains(cachedDevice1);
        assertThat(mCachedDeviceManager.mHearingAidDevicesNotAddedInCache)
            .doesNotContain(cachedDevice2);
        assertThat(mCachedDeviceManager.mHearingAidDevicesNotAddedInCache)
            .doesNotContain(cachedDevice3);
    }

    /**
     * Test to verify addDevice() for hearing aid devices with same HiSyncId.
     */
    @Test
    public void testAddDevice_hearingAidDevicesWithSameHiSyncId_populateInDifferentLists() {
        doAnswer((invocation) -> mHearingAidProfile).when(mLocalProfileManager)
            .getHearingAidProfile();
        doAnswer((invocation) -> HISYNCID1).when(mHearingAidProfile).getHiSyncId(mDevice1);
        doAnswer((invocation) -> HISYNCID1).when(mHearingAidProfile).getHiSyncId(mDevice2);

        CachedBluetoothDevice cachedDevice1 = mCachedDeviceManager.addDevice(mLocalAdapter,
            mLocalProfileManager, mDevice1);
        assertThat(cachedDevice1).isNotNull();
        // The first hearing aid device should be populated in mCachedDevice and
        // mCachedDevicesMapForHearingAids.
        assertThat(mCachedDeviceManager.getCachedDevicesCopy()).hasSize(1);
        assertThat(mCachedDeviceManager.mHearingAidDevicesNotAddedInCache).isEmpty();
        assertThat(mCachedDeviceManager.mCachedDevicesMapForHearingAids).hasSize(1);
        assertThat(mCachedDeviceManager.mCachedDevicesMapForHearingAids.values())
            .contains(cachedDevice1);

        CachedBluetoothDevice cachedDevice2 = mCachedDeviceManager.addDevice(mLocalAdapter,
            mLocalProfileManager, mDevice2);
        assertThat(cachedDevice2).isNotNull();
        // The second hearing aid device should be populated in mHearingAidDevicesNotAddedInCache.
        assertThat(mCachedDeviceManager.getCachedDevicesCopy()).hasSize(1);
        assertThat(mCachedDeviceManager.mHearingAidDevicesNotAddedInCache).hasSize(1);
        assertThat(mCachedDeviceManager.mCachedDevicesMapForHearingAids).hasSize(1);
    }

    /**
     * Test to verify addDevice() for hearing aid devices with different HiSyncId.
     */
    @Test
    public void testAddDevice_hearingAidDevicesWithDifferentHiSyncId_populateInSameList() {
        doAnswer((invocation) -> mHearingAidProfile).when(mLocalProfileManager)
            .getHearingAidProfile();
        doAnswer((invocation) -> HISYNCID1).when(mHearingAidProfile).getHiSyncId(mDevice1);
        doAnswer((invocation) -> HISYNCID2).when(mHearingAidProfile).getHiSyncId(mDevice2);
        CachedBluetoothDevice cachedDevice1 = mCachedDeviceManager.addDevice(mLocalAdapter,
            mLocalProfileManager, mDevice1);
        assertThat(cachedDevice1).isNotNull();
        // The first hearing aid device should be populated in mCachedDevice and
        // mCachedDevicesMapForHearingAids.
        assertThat(mCachedDeviceManager.getCachedDevicesCopy()).hasSize(1);
        assertThat(mCachedDeviceManager.mHearingAidDevicesNotAddedInCache).isEmpty();
        assertThat(mCachedDeviceManager.mCachedDevicesMapForHearingAids).hasSize(1);
        assertThat(mCachedDeviceManager.mCachedDevicesMapForHearingAids.values())
            .contains(cachedDevice1);

        CachedBluetoothDevice cachedDevice2 = mCachedDeviceManager.addDevice(mLocalAdapter,
            mLocalProfileManager, mDevice2);
        assertThat(cachedDevice2).isNotNull();
        // The second hearing aid device should also be populated in mCachedDevice
        // and mCachedDevicesMapForHearingAids as its not a pair of the first one.
        assertThat(mCachedDeviceManager.getCachedDevicesCopy()).hasSize(2);
        assertThat(mCachedDeviceManager.mHearingAidDevicesNotAddedInCache).isEmpty();
        assertThat(mCachedDeviceManager.mCachedDevicesMapForHearingAids).hasSize(2);
        assertThat(mCachedDeviceManager.mCachedDevicesMapForHearingAids.values())
            .contains(cachedDevice1);
        assertThat(mCachedDeviceManager.mCachedDevicesMapForHearingAids.values())
            .contains(cachedDevice2);
    }

    /**
     * Test to verify getHearingAidPairDeviceSummary() for hearing aid devices with same HiSyncId.
     */
    @Test
    public void testGetHearingAidPairDeviceSummary_bothHearingAidsPaired_returnsSummaryOfPair() {
        mCachedDevice1.setHiSyncId(HISYNCID1);
        mCachedDevice2.setHiSyncId(HISYNCID1);
        mCachedDeviceManager.mCachedDevices.add(mCachedDevice1);
        mCachedDeviceManager.mHearingAidDevicesNotAddedInCache.add(mCachedDevice2);
        doAnswer((invocation) -> DEVICE_SUMMARY_1).when(mCachedDevice1).getConnectionSummary();
        doAnswer((invocation) -> DEVICE_SUMMARY_2).when(mCachedDevice2).getConnectionSummary();

        assertThat(mCachedDeviceManager.getHearingAidPairDeviceSummary(mCachedDevice1))
            .isEqualTo(DEVICE_SUMMARY_2);
    }

    /**
     * Test to verify getHearingAidPairDeviceSummary() for hearing aid devices with different
     * HiSyncId.
     */
    @Test
    public void testGetHearingAidPairDeviceSummary_bothHearingAidsNotPaired_returnsNull() {
        mCachedDevice1.setHiSyncId(HISYNCID1);
        mCachedDevice2.setHiSyncId(HISYNCID2);
        mCachedDeviceManager.mCachedDevices.add(mCachedDevice1);
        mCachedDeviceManager.mHearingAidDevicesNotAddedInCache.add(mCachedDevice2);
        doAnswer((invocation) -> DEVICE_SUMMARY_1).when(mCachedDevice1).getConnectionSummary();
        doAnswer((invocation) -> DEVICE_SUMMARY_2).when(mCachedDevice2).getConnectionSummary();

        assertThat(mCachedDeviceManager.getHearingAidPairDeviceSummary(mCachedDevice1))
            .isEqualTo(null);
    }

    /**
     * Test to verify updateHearingAidsDevices().
     */
    @Test
    public void testUpdateHearingAidDevices_hiSyncIdAvailable_setsHiSyncId() {
        doAnswer((invocation) -> mHearingAidProfile).when(mLocalProfileManager)
            .getHearingAidProfile();
        doAnswer((invocation) -> HISYNCID1).when(mHearingAidProfile).getHiSyncId(mDevice1);
        doAnswer((invocation) -> HISYNCID1).when(mHearingAidProfile).getHiSyncId(mDevice2);
        mCachedDeviceManager.mCachedDevices.add(mCachedDevice1);
        mCachedDeviceManager.mCachedDevices.add(mCachedDevice2);
        mCachedDeviceManager.updateHearingAidsDevices(mLocalProfileManager);

        // Assert that the mCachedDevice1 and mCachedDevice2 have an updated HiSyncId.
        assertThat(mCachedDevice1.getHiSyncId()).isEqualTo(HISYNCID1);
        assertThat(mCachedDevice2.getHiSyncId()).isEqualTo(HISYNCID1);
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

    /**
     * Test to verify onActiveDeviceChanged() with A2DP and Hearing Aid.
     */
    @Test
    public void testOnActiveDeviceChanged_withA2dpAndHearingAid() {
        CachedBluetoothDevice cachedDevice1 = mCachedDeviceManager.addDevice(mLocalAdapter,
                mLocalProfileManager, mDevice1);
        assertThat(cachedDevice1).isNotNull();
        CachedBluetoothDevice cachedDevice2 = mCachedDeviceManager.addDevice(mLocalAdapter,
                mLocalProfileManager, mDevice2);
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
