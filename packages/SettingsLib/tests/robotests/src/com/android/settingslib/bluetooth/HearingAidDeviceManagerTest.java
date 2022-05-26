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

import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.bluetooth.BluetoothClass;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothHearingAid;
import android.bluetooth.BluetoothProfile;
import android.content.Context;
import android.os.Parcel;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;

@RunWith(RobolectricTestRunner.class)
public class HearingAidDeviceManagerTest {
    private final static long HISYNCID1 = 10;
    private final static long HISYNCID2 = 11;
    private final static String DEVICE_NAME_1 = "TestName_1";
    private final static String DEVICE_NAME_2 = "TestName_2";
    private final static String DEVICE_ALIAS_1 = "TestAlias_1";
    private final static String DEVICE_ALIAS_2 = "TestAlias_2";
    private final static String DEVICE_ADDRESS_1 = "AA:BB:CC:DD:EE:11";
    private final static String DEVICE_ADDRESS_2 = "AA:BB:CC:DD:EE:22";
    private final BluetoothClass DEVICE_CLASS =
            createBtClass(BluetoothClass.Device.AUDIO_VIDEO_HANDSFREE);
    @Mock
    private LocalBluetoothProfileManager mLocalProfileManager;
    @Mock
    private LocalBluetoothManager mLocalBluetoothManager;
    @Mock
    private BluetoothEventManager mBluetoothEventManager;
    @Mock
    private HearingAidProfile mHearingAidProfile;
    @Mock
    private BluetoothDevice mDevice1;
    @Mock
    private BluetoothDevice mDevice2;
    private CachedBluetoothDevice mCachedDevice1;
    private CachedBluetoothDevice mCachedDevice2;
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
        when(mDevice1.getName()).thenReturn(DEVICE_NAME_1);
        when(mDevice2.getName()).thenReturn(DEVICE_NAME_2);
        when(mDevice1.getAlias()).thenReturn(DEVICE_ALIAS_1);
        when(mDevice2.getAlias()).thenReturn(DEVICE_ALIAS_2);
        when(mDevice1.getBluetoothClass()).thenReturn(DEVICE_CLASS);
        when(mDevice2.getBluetoothClass()).thenReturn(DEVICE_CLASS);
        when(mLocalBluetoothManager.getEventManager()).thenReturn(mBluetoothEventManager);
        when(mLocalBluetoothManager.getProfileManager()).thenReturn(mLocalProfileManager);
        when(mLocalProfileManager.getHearingAidProfile()).thenReturn(mHearingAidProfile);

        mCachedDeviceManager = new CachedBluetoothDeviceManager(mContext, mLocalBluetoothManager);
        mHearingAidDeviceManager = spy(new HearingAidDeviceManager(mLocalBluetoothManager,
                mCachedDeviceManager.mCachedDevices));
        mCachedDevice1 = spy(new CachedBluetoothDevice(mContext, mLocalProfileManager, mDevice1));
        mCachedDevice2 = spy(new CachedBluetoothDevice(mContext, mLocalProfileManager, mDevice2));
    }

    /**
     * Test initHearingAidDeviceIfNeeded, a valid HiSyncId will be assigned
     */
    @Test
    public void initHearingAidDeviceIfNeeded_validHiSyncId_verifyHiSyncId() {
        when(mHearingAidProfile.getHiSyncId(mDevice1)).thenReturn(HISYNCID1);

        assertThat(mCachedDevice1.getHiSyncId()).isNotEqualTo(HISYNCID1);
        mHearingAidDeviceManager.initHearingAidDeviceIfNeeded(mCachedDevice1);

        assertThat(mCachedDevice1.getHiSyncId()).isEqualTo(HISYNCID1);
    }

    /**
     * Test initHearingAidDeviceIfNeeded, an invalid HiSyncId will not be assigned
     */
    @Test
    public void initHearingAidDeviceIfNeeded_invalidHiSyncId_notToSetHiSyncId() {
        when(mHearingAidProfile.getHiSyncId(mDevice1)).thenReturn(
                BluetoothHearingAid.HI_SYNC_ID_INVALID);
        mHearingAidDeviceManager.initHearingAidDeviceIfNeeded(mCachedDevice1);

        verify(mCachedDevice1, never()).setHiSyncId(anyLong());
    }

    /**
     * Test setSubDeviceIfNeeded, a device with same HiSyncId will be set as sub device
     */
    @Test
    public void setSubDeviceIfNeeded_sameHiSyncId_setSubDevice() {
        mCachedDevice1.setHiSyncId(HISYNCID1);
        mCachedDevice2.setHiSyncId(HISYNCID1);
        mCachedDeviceManager.mCachedDevices.add(mCachedDevice1);
        mHearingAidDeviceManager.setSubDeviceIfNeeded(mCachedDevice2);

        assertThat(mCachedDevice1.getSubDevice()).isEqualTo(mCachedDevice2);
    }

    /**
     * Test setSubDeviceIfNeeded, a device with different HiSyncId will not be set as sub device
     */
    @Test
    public void setSubDeviceIfNeeded_differentHiSyncId_notSetSubDevice() {
        mCachedDevice1.setHiSyncId(HISYNCID1);
        mCachedDevice2.setHiSyncId(HISYNCID2);
        mCachedDeviceManager.mCachedDevices.add(mCachedDevice1);
        mHearingAidDeviceManager.setSubDeviceIfNeeded(mCachedDevice2);

        assertThat(mCachedDevice1.getSubDevice()).isNull();
    }

    /**
     * Test updateHearingAidsDevices, to link two devices with the same HiSyncId.
     * When first paired devices is connected and second paired device is disconnected, first
     * paired device would be set as main device and second device will be removed from
     * CachedDevices list.
     */
    @Test
    public void updateHearingAidsDevices_firstPairedDevicesConnected_verifySubDevice() {
        when(mHearingAidProfile.getHiSyncId(mDevice1)).thenReturn(HISYNCID1);
        when(mHearingAidProfile.getHiSyncId(mDevice2)).thenReturn(HISYNCID1);
        when(mCachedDevice1.isConnected()).thenReturn(true);
        when(mCachedDevice2.isConnected()).thenReturn(false);
        mCachedDeviceManager.mCachedDevices.add(mCachedDevice1);
        mCachedDeviceManager.mCachedDevices.add(mCachedDevice2);

        assertThat(mCachedDeviceManager.mCachedDevices.contains(mCachedDevice2)).isTrue();
        assertThat(mCachedDevice1.getSubDevice()).isNull();
        mHearingAidDeviceManager.updateHearingAidsDevices();

        assertThat(mCachedDeviceManager.mCachedDevices.contains(mCachedDevice2)).isFalse();
        assertThat(mCachedDevice1.getSubDevice()).isEqualTo(mCachedDevice2);
    }

    /**
     * Test updateHearingAidsDevices, to link two devices with the same HiSyncId.
     * When second paired devices is connected and first paired device is disconnected, second
     * paired device would be set as main device and first device will be removed from
     * CachedDevices list.
     */
    @Test
    public void updateHearingAidsDevices_secondPairedDeviceConnected_verifySubDevice() {
        when(mHearingAidProfile.getHiSyncId(mDevice1)).thenReturn(HISYNCID1);
        when(mHearingAidProfile.getHiSyncId(mDevice2)).thenReturn(HISYNCID1);
        when(mCachedDevice1.isConnected()).thenReturn(false);
        when(mCachedDevice2.isConnected()).thenReturn(true);
        mCachedDeviceManager.mCachedDevices.add(mCachedDevice1);
        mCachedDeviceManager.mCachedDevices.add(mCachedDevice2);

        assertThat(mCachedDeviceManager.mCachedDevices.contains(mCachedDevice1)).isTrue();
        assertThat(mCachedDevice2.getSubDevice()).isNull();
        mHearingAidDeviceManager.updateHearingAidsDevices();

        assertThat(mCachedDeviceManager.mCachedDevices.contains(mCachedDevice1)).isFalse();
        assertThat(mCachedDevice2.getSubDevice()).isEqualTo(mCachedDevice1);
    }

    /**
     * Test updateHearingAidsDevices, to link two devices with the same HiSyncId.
     * When both devices are connected, to build up main and sub relationship and to remove sub
     * device from CachedDevices list.
     */
    @Test
    public void updateHearingAidsDevices_BothConnected_verifySubDevice() {
        when(mHearingAidProfile.getHiSyncId(mDevice1)).thenReturn(HISYNCID1);
        when(mHearingAidProfile.getHiSyncId(mDevice2)).thenReturn(HISYNCID1);
        when(mCachedDevice1.isConnected()).thenReturn(true);
        when(mCachedDevice2.isConnected()).thenReturn(true);
        mCachedDeviceManager.mCachedDevices.add(mCachedDevice1);
        mCachedDeviceManager.mCachedDevices.add(mCachedDevice2);

        assertThat(mCachedDeviceManager.mCachedDevices.contains(mCachedDevice2)).isTrue();
        assertThat(mCachedDevice1.getSubDevice()).isNull();
        mHearingAidDeviceManager.updateHearingAidsDevices();

        assertThat(mCachedDeviceManager.mCachedDevices.contains(mCachedDevice2)).isFalse();
        assertThat(mCachedDevice1.getSubDevice()).isEqualTo(mCachedDevice2);
    }

    /**
     * Test updateHearingAidsDevices, dispatch callback
     */
    @Test
    public void updateHearingAidsDevices_dispatchDeviceRemovedCallback() {
        when(mHearingAidProfile.getHiSyncId(mDevice1)).thenReturn(HISYNCID1);
        when(mHearingAidProfile.getHiSyncId(mDevice2)).thenReturn(HISYNCID1);
        mCachedDeviceManager.mCachedDevices.add(mCachedDevice1);
        mCachedDeviceManager.mCachedDevices.add(mCachedDevice2);
        mHearingAidDeviceManager.updateHearingAidsDevices();

        verify(mBluetoothEventManager).dispatchDeviceRemoved(mCachedDevice1);
    }

    /**
     * Test updateHearingAidsDevices, do nothing when HiSyncId is invalid
     */
    @Test
    public void updateHearingAidsDevices_invalidHiSyncId_doNothing() {
        when(mHearingAidProfile.getHiSyncId(mDevice1)).
                thenReturn(BluetoothHearingAid.HI_SYNC_ID_INVALID);
        when(mHearingAidProfile.getHiSyncId(mDevice2)).
                thenReturn(BluetoothHearingAid.HI_SYNC_ID_INVALID);
        mCachedDeviceManager.mCachedDevices.add(mCachedDevice1);
        mCachedDeviceManager.mCachedDevices.add(mCachedDevice2);
        mHearingAidDeviceManager.updateHearingAidsDevices();

        verify(mHearingAidDeviceManager, never()).onHiSyncIdChanged(anyLong());
    }

    /**
     * Test onProfileConnectionStateChangedIfProcessed.
     * When first hearing aid device is connected, to process it same as other generic devices.
     * No need to process it.
     */
    @Test
    public void onProfileConnectionStateChanged_connected_singleDevice_returnFalse() {
        when(mHearingAidProfile.getHiSyncId(mDevice1)).thenReturn(HISYNCID1);

        assertThat(mHearingAidDeviceManager.onProfileConnectionStateChangedIfProcessed(
                mCachedDevice1, BluetoothProfile.STATE_CONNECTED)).isFalse();
    }

    /**
     * Test onProfileConnectionStateChangedIfProcessed.
     * When a new hearing aid device is connected, to set it as sub device by onHiSyncIdChanged().
     * And, to verify new device is not in CachedDevices list.
     */
    @Test
    public void onProfileConnectionStateChanged_connected_newDevice_verifySubDevice() {
        when(mCachedDevice1.getHiSyncId()).thenReturn(HISYNCID1);
        when(mCachedDevice2.getHiSyncId()).thenReturn(HISYNCID1);
        when(mCachedDevice1.isConnected()).thenReturn(true);
        when(mCachedDevice2.isConnected()).thenReturn(true);
        mCachedDeviceManager.mCachedDevices.add(mCachedDevice1);
        mCachedDeviceManager.mCachedDevices.add(mCachedDevice2);

        assertThat(mCachedDeviceManager.mCachedDevices.contains(mCachedDevice2)).isTrue();
        assertThat(mCachedDevice1.getSubDevice()).isNull();
        mHearingAidDeviceManager.onProfileConnectionStateChangedIfProcessed(mCachedDevice1,
                BluetoothProfile.STATE_CONNECTED);

        assertThat(mCachedDeviceManager.mCachedDevices.contains(mCachedDevice2)).isFalse();
        assertThat(mCachedDevice1.getSubDevice()).isEqualTo(mCachedDevice2);
        verify(mHearingAidDeviceManager).onHiSyncIdChanged(anyLong());
    }

    /**
     * Test onProfileConnectionStateChangedIfProcessed.
     * When sub device is disconnected, do nothing and return False for main device connected event
     */
    @Test
    public void
    onProfileConnectionStateChanged_connected_mainDevice_subDeviceDisconnected_returnFalse() {
        when(mCachedDevice1.getHiSyncId()).thenReturn(HISYNCID1);
        when(mCachedDevice2.getHiSyncId()).thenReturn(HISYNCID1);
        when(mCachedDevice2.isConnected()).thenReturn(false);
        mCachedDeviceManager.mCachedDevices.add(mCachedDevice1);
        mCachedDevice1.setSubDevice(mCachedDevice2);

        assertThat(mHearingAidDeviceManager.onProfileConnectionStateChangedIfProcessed(
                mCachedDevice1, BluetoothProfile.STATE_CONNECTED)).isFalse();
        verify(mHearingAidDeviceManager).onHiSyncIdChanged(anyLong());
    }

    /**
     * Test onProfileConnectionStateChangedIfProcessed.
     * When main device is connected, do main device refresh() for sub device connected event
     */
    @Test
    public void
    onProfileConnectionStateChanged_connected_subDevice_mainDeviceConnected_verifyRefresh() {
        when(mCachedDevice1.getHiSyncId()).thenReturn(HISYNCID1);
        when(mCachedDevice2.getHiSyncId()).thenReturn(HISYNCID1);
        when(mCachedDevice1.isConnected()).thenReturn(true);
        mCachedDeviceManager.mCachedDevices.add(mCachedDevice1);
        mCachedDevice1.setSubDevice(mCachedDevice2);

        assertThat(mHearingAidDeviceManager.onProfileConnectionStateChangedIfProcessed(
                mCachedDevice2, BluetoothProfile.STATE_CONNECTED)).isTrue();
        verify(mHearingAidDeviceManager).onHiSyncIdChanged(anyLong());
        verify(mCachedDevice1).refresh();
    }

    /**
     * Test onProfileConnectionStateChangedIfProcessed.
     * When main device is disconnected, to verify switch() result for sub device connected
     * event
     */
    @Test
    public void onProfileConnectionStateChanged_connected_subDevice_mainDeviceDisconnected_switch()
    {
        when(mCachedDevice1.getHiSyncId()).thenReturn(HISYNCID1);
        when(mCachedDevice2.getHiSyncId()).thenReturn(HISYNCID1);
        when(mCachedDevice1.isConnected()).thenReturn(false);
        mCachedDeviceManager.mCachedDevices.add(mCachedDevice1);
        mCachedDevice1.setSubDevice(mCachedDevice2);

        assertThat(mCachedDevice1.mDevice).isEqualTo(mDevice1);
        assertThat(mCachedDevice2.mDevice).isEqualTo(mDevice2);
        assertThat(mHearingAidDeviceManager.onProfileConnectionStateChangedIfProcessed(
                mCachedDevice2, BluetoothProfile.STATE_CONNECTED)).isTrue();

        assertThat(mCachedDevice1.mDevice).isEqualTo(mDevice2);
        assertThat(mCachedDevice2.mDevice).isEqualTo(mDevice1);
        verify(mHearingAidDeviceManager).onHiSyncIdChanged(anyLong());
        verify(mCachedDevice1).refresh();
    }

    /**
     * Test onProfileConnectionStateChangedIfProcessed.
     * When sub device is connected, to verify switch() result for main device disconnected
     * event
     */
    @Test
    public void onProfileConnectionStateChanged_disconnected_mainDevice_subDeviceConnected_switch()
    {
        when(mCachedDevice1.getHiSyncId()).thenReturn(HISYNCID1);
        mCachedDevice1.setDeviceSide(HearingAidProfile.DeviceSide.SIDE_LEFT);
        when(mCachedDevice2.getHiSyncId()).thenReturn(HISYNCID1);
        when(mCachedDevice2.isConnected()).thenReturn(true);
        mCachedDevice2.setDeviceSide(HearingAidProfile.DeviceSide.SIDE_RIGHT);
        mCachedDeviceManager.mCachedDevices.add(mCachedDevice1);
        mCachedDevice1.setSubDevice(mCachedDevice2);

        assertThat(mCachedDevice1.mDevice).isEqualTo(mDevice1);
        assertThat(mCachedDevice2.mDevice).isEqualTo(mDevice2);
        assertThat(mHearingAidDeviceManager.onProfileConnectionStateChangedIfProcessed(
                mCachedDevice1, BluetoothProfile.STATE_DISCONNECTED)).isTrue();

        assertThat(mCachedDevice1.mDevice).isEqualTo(mDevice2);
        assertThat(mCachedDevice2.mDevice).isEqualTo(mDevice1);
        assertThat(mCachedDevice1.getDeviceSide()).isEqualTo(
                HearingAidProfile.DeviceSide.SIDE_RIGHT);
        assertThat(mCachedDevice2.getDeviceSide()).isEqualTo(
                HearingAidProfile.DeviceSide.SIDE_LEFT);
        verify(mCachedDevice1).refresh();
    }

    /**
     * Test onProfileConnectionStateChangedIfProcessed.
     * When sub device is disconnected, do nothing and return False for main device disconnected
     * event
     */
    @Test
    public void
    onProfileConnectionStateChanged_disconnected_mainDevice_subDeviceDisconnected_returnFalse() {
        when(mCachedDevice1.getHiSyncId()).thenReturn(HISYNCID1);
        when(mCachedDevice2.getHiSyncId()).thenReturn(HISYNCID1);
        when(mCachedDevice2.isConnected()).thenReturn(false);
        mCachedDeviceManager.mCachedDevices.add(mCachedDevice1);
        mCachedDevice1.setSubDevice(mCachedDevice2);

        assertThat(mHearingAidDeviceManager.onProfileConnectionStateChangedIfProcessed(
                mCachedDevice1, BluetoothProfile.STATE_DISCONNECTED)).isFalse();
    }

    /**
     * Test onProfileConnectionStateChangedIfProcessed.
     * Refresh main device UI for sub device disconnected event
     */
    @Test
    public void onProfileConnectionStateChanged_disconnected_subDevice_verifyRefresh() {
        when(mCachedDevice1.getHiSyncId()).thenReturn(HISYNCID1);
        when(mCachedDevice2.getHiSyncId()).thenReturn(HISYNCID1);
        mCachedDeviceManager.mCachedDevices.add(mCachedDevice1);
        mCachedDevice1.setSubDevice(mCachedDevice2);

        assertThat(mHearingAidDeviceManager.onProfileConnectionStateChangedIfProcessed(
                mCachedDevice2, BluetoothProfile.STATE_DISCONNECTED)).isTrue();
        verify(mCachedDevice1).refresh();
    }

    @Test
    public void findMainDevice() {
        when(mCachedDevice1.getHiSyncId()).thenReturn(HISYNCID1);
        when(mCachedDevice2.getHiSyncId()).thenReturn(HISYNCID1);
        mCachedDeviceManager.mCachedDevices.add(mCachedDevice1);
        mCachedDevice1.setSubDevice(mCachedDevice2);

        assertThat(mHearingAidDeviceManager.findMainDevice(mCachedDevice2)).
                isEqualTo(mCachedDevice1);
    }
}
