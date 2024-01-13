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

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.bluetooth.BluetoothClass;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothHearingAid;
import android.bluetooth.BluetoothProfile;
import android.bluetooth.BluetoothUuid;
import android.bluetooth.le.ScanFilter;
import android.content.Context;
import android.media.AudioAttributes;
import android.media.AudioDeviceAttributes;
import android.media.AudioDeviceInfo;
import android.media.AudioManager;
import android.media.audiopolicy.AudioProductStrategy;
import android.os.Parcel;
import android.util.FeatureFlagUtils;

import androidx.test.core.app.ApplicationProvider;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.MockitoJUnit;
import org.mockito.junit.MockitoRule;
import org.robolectric.RobolectricTestRunner;

import java.util.List;

@RunWith(RobolectricTestRunner.class)
public class HearingAidDeviceManagerTest {
    @Rule
    public MockitoRule mMockitoRule = MockitoJUnit.rule();

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
    private final Context mContext = ApplicationProvider.getApplicationContext();

    private CachedBluetoothDevice mCachedDevice1;
    private CachedBluetoothDevice mCachedDevice2;
    private CachedBluetoothDeviceManager mCachedDeviceManager;
    private HearingAidDeviceManager mHearingAidDeviceManager;
    private AudioDeviceAttributes mHearingDeviceAttribute;
    @Spy
    private HearingAidAudioRoutingHelper mHelper = new HearingAidAudioRoutingHelper(mContext);
    @Mock
    private LocalBluetoothProfileManager mLocalProfileManager;
    @Mock
    private LocalBluetoothManager mLocalBluetoothManager;
    @Mock
    private BluetoothEventManager mBluetoothEventManager;
    @Mock
    private HearingAidProfile mHearingAidProfile;
    @Mock
    private AudioProductStrategy mAudioStrategy;
    @Mock
    private BluetoothDevice mDevice1;
    @Mock
    private BluetoothDevice mDevice2;


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
        FeatureFlagUtils.setEnabled(mContext, FeatureFlagUtils.SETTINGS_AUDIO_ROUTING, true);
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
        when(mAudioStrategy.getAudioAttributesForLegacyStreamType(
                AudioManager.STREAM_MUSIC))
                .thenReturn((new AudioAttributes.Builder()).build());
        doReturn(List.of(mAudioStrategy)).when(mHelper).getSupportedStrategies(any(int[].class));

        mHearingDeviceAttribute = new AudioDeviceAttributes(
                AudioDeviceAttributes.ROLE_OUTPUT,
                AudioDeviceInfo.TYPE_HEARING_AID,
                DEVICE_ADDRESS_1);
        mCachedDeviceManager = new CachedBluetoothDeviceManager(mContext, mLocalBluetoothManager);
        mHearingAidDeviceManager = spy(new HearingAidDeviceManager(mContext, mLocalBluetoothManager,
                mCachedDeviceManager.mCachedDevices, mHelper));
        mCachedDevice1 = spy(new CachedBluetoothDevice(mContext, mLocalProfileManager, mDevice1));
        mCachedDevice2 = spy(new CachedBluetoothDevice(mContext, mLocalProfileManager, mDevice2));
    }

    /**
     * Test initHearingAidDeviceIfNeeded, set HearingAid's information, including HiSyncId,
     * deviceSide, deviceMode.
     */
    @Test
    public void initHearingAidDeviceIfNeeded_validHiSyncId_setHearingAidInfo() {
        when(mHearingAidProfile.getHiSyncId(mDevice1)).thenReturn(HISYNCID1);
        when(mHearingAidProfile.getDeviceMode(mDevice1)).thenReturn(
                HearingAidProfile.DeviceMode.MODE_BINAURAL);
        when(mHearingAidProfile.getDeviceSide(mDevice1)).thenReturn(
                HearingAidProfile.DeviceSide.SIDE_RIGHT);

        assertThat(mCachedDevice1.getHiSyncId()).isNotEqualTo(HISYNCID1);
        mHearingAidDeviceManager.initHearingAidDeviceIfNeeded(mCachedDevice1, null);

        assertThat(mCachedDevice1.getHiSyncId()).isEqualTo(HISYNCID1);
        assertThat(mCachedDevice1.getDeviceMode()).isEqualTo(
                HearingAidInfo.DeviceMode.MODE_BINAURAL);
        assertThat(mCachedDevice1.getDeviceSide()).isEqualTo(
                HearingAidInfo.DeviceSide.SIDE_RIGHT);
    }

    /**
     * Test initHearingAidDeviceIfNeeded, an invalid HiSyncId will not be assigned
     */
    @Test
    public void initHearingAidDeviceIfNeeded_invalidHiSyncId_notToSetHearingAidInfo() {
        when(mHearingAidProfile.getHiSyncId(mDevice1)).thenReturn(
                BluetoothHearingAid.HI_SYNC_ID_INVALID);

        mHearingAidDeviceManager.initHearingAidDeviceIfNeeded(mCachedDevice1, null);

        verify(mCachedDevice1, never()).setHearingAidInfo(any(HearingAidInfo.class));
    }

    /**
     * Test initHearingAidDeviceIfNeeded, an invalid HiSyncId and hearing aid scan filter, set an
     * empty hearing aid info on the device.
     */
    @Test
    public void initHearingAidDeviceIfNeeded_hearingAidScanFilter_setHearingAidInfo() {
        when(mHearingAidProfile.getHiSyncId(mDevice1)).thenReturn(
                BluetoothHearingAid.HI_SYNC_ID_INVALID);
        final ScanFilter scanFilter = new ScanFilter.Builder()
                .setServiceData(BluetoothUuid.HEARING_AID, new byte[]{0}).build();

        mHearingAidDeviceManager.initHearingAidDeviceIfNeeded(mCachedDevice1, List.of(scanFilter));

        assertThat(mCachedDevice1.isHearingAidDevice()).isTrue();
    }

    /**
     * Test initHearingAidDeviceIfNeeded, an invalid HiSyncId and random scan filter, not to set
     * hearing aid info on the device.
     */
    @Test
    public void initHearingAidDeviceIfNeeded_randomScanFilter_setHearingAidInfo() {
        when(mHearingAidProfile.getHiSyncId(mDevice1)).thenReturn(
                BluetoothHearingAid.HI_SYNC_ID_INVALID);
        final ScanFilter scanFilter = new ScanFilter.Builder().build();

        mHearingAidDeviceManager.initHearingAidDeviceIfNeeded(mCachedDevice1, List.of(scanFilter));

        assertThat(mCachedDevice1.isHearingAidDevice()).isFalse();
    }

    /**
     * Test setSubDeviceIfNeeded, a device with same HiSyncId will be set as sub device
     */
    @Test
    public void setSubDeviceIfNeeded_sameHiSyncId_setSubDevice() {
        mCachedDevice1.setHearingAidInfo(getLeftAshaHearingAidInfo(HISYNCID1));
        mCachedDevice2.setHearingAidInfo(getRightAshaHearingAidInfo(HISYNCID1));
        mCachedDeviceManager.mCachedDevices.add(mCachedDevice1);

        mHearingAidDeviceManager.setSubDeviceIfNeeded(mCachedDevice2);

        assertThat(mCachedDevice1.getSubDevice()).isEqualTo(mCachedDevice2);
    }

    /**
     * Test setSubDeviceIfNeeded, a device with different HiSyncId will not be set as sub device
     */
    @Test
    public void setSubDeviceIfNeeded_differentHiSyncId_notSetSubDevice() {
        mCachedDevice1.setHearingAidInfo(getLeftAshaHearingAidInfo(HISYNCID1));
        mCachedDevice2.setHearingAidInfo(getRightAshaHearingAidInfo(HISYNCID2));
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
     * Test updateHearingAidsDevices, set HearingAid's information, including HiSyncId, deviceSide,
     * deviceMode.
     */
    @Test
    public void updateHearingAidsDevices_validHiSyncId_setHearingAidInfos() {
        when(mHearingAidProfile.getHiSyncId(mDevice1)).thenReturn(HISYNCID1);
        when(mHearingAidProfile.getDeviceMode(mDevice1)).thenReturn(
                HearingAidProfile.DeviceMode.MODE_BINAURAL);
        when(mHearingAidProfile.getDeviceSide(mDevice1)).thenReturn(
                HearingAidProfile.DeviceSide.SIDE_RIGHT);
        mCachedDeviceManager.mCachedDevices.add(mCachedDevice1);

        mHearingAidDeviceManager.updateHearingAidsDevices();

        assertThat(mCachedDevice1.getHiSyncId()).isEqualTo(HISYNCID1);
        assertThat(mCachedDevice1.getDeviceMode()).isEqualTo(
                HearingAidInfo.DeviceMode.MODE_BINAURAL);
        assertThat(mCachedDevice1.getDeviceSide()).isEqualTo(
                HearingAidInfo.DeviceSide.SIDE_RIGHT);
        verify(mHearingAidDeviceManager).onHiSyncIdChanged(HISYNCID1);
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
        mCachedDevice1.setHearingAidInfo(getLeftAshaHearingAidInfo(HISYNCID1));
        mCachedDevice2.setHearingAidInfo(getRightAshaHearingAidInfo(HISYNCID1));
        when(mCachedDevice2.isConnected()).thenReturn(true);
        mCachedDeviceManager.mCachedDevices.add(mCachedDevice1);
        mCachedDevice1.setSubDevice(mCachedDevice2);

        assertThat(mCachedDevice1.mDevice).isEqualTo(mDevice1);
        assertThat(mCachedDevice2.mDevice).isEqualTo(mDevice2);
        assertThat(mHearingAidDeviceManager.onProfileConnectionStateChangedIfProcessed(
                mCachedDevice1, BluetoothProfile.STATE_DISCONNECTED)).isTrue();

        assertThat(mCachedDevice1.mDevice).isEqualTo(mDevice2);
        assertThat(mCachedDevice2.mDevice).isEqualTo(mDevice1);
        assertThat(mCachedDevice1.getDeviceSide()).isEqualTo(HearingAidInfo.DeviceSide.SIDE_RIGHT);
        assertThat(mCachedDevice2.getDeviceSide()).isEqualTo(HearingAidInfo.DeviceSide.SIDE_LEFT);
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
    public void onActiveDeviceChanged_connected_callSetStrategies() {
        when(mHelper.getMatchedHearingDeviceAttributes(mCachedDevice1)).thenReturn(
                mHearingDeviceAttribute);
        when(mCachedDevice1.isActiveDevice(BluetoothProfile.HEARING_AID)).thenReturn(true);
        doReturn(true).when(mHelper).setPreferredDeviceRoutingStrategies(anyList(),
                eq(mHearingDeviceAttribute), anyInt());

        mHearingAidDeviceManager.onActiveDeviceChanged(mCachedDevice1);

        verify(mHelper, atLeastOnce()).setPreferredDeviceRoutingStrategies(
                eq(List.of(mAudioStrategy)), any(AudioDeviceAttributes.class), anyInt());
    }

    @Test
    public void onActiveDeviceChanged_disconnected_callSetStrategiesWithAutoValue() {
        when(mHelper.getMatchedHearingDeviceAttributes(mCachedDevice1)).thenReturn(
                mHearingDeviceAttribute);
        when(mCachedDevice1.isActiveDevice(BluetoothProfile.HEARING_AID)).thenReturn(false);
        doReturn(true).when(mHelper).setPreferredDeviceRoutingStrategies(anyList(), any(),
                anyInt());

        mHearingAidDeviceManager.onActiveDeviceChanged(mCachedDevice1);

        verify(mHelper, atLeastOnce()).setPreferredDeviceRoutingStrategies(
                eq(List.of(mAudioStrategy)), /* hearingDevice= */ isNull(),
                eq(HearingAidAudioRoutingConstants.RoutingValue.AUTO));
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

    private HearingAidInfo getLeftAshaHearingAidInfo(long hiSyncId) {
        return new HearingAidInfo.Builder()
                .setAshaDeviceSide(HearingAidInfo.DeviceSide.SIDE_LEFT)
                .setHiSyncId(hiSyncId)
                .build();
    }

    private HearingAidInfo getRightAshaHearingAidInfo(long hiSyncId) {
        return new HearingAidInfo.Builder()
                .setAshaDeviceSide(HearingAidInfo.DeviceSide.SIDE_RIGHT)
                .setHiSyncId(hiSyncId)
                .build();
    }
}
