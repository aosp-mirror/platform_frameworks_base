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

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.content.Context;

import com.android.settingslib.bluetooth.A2dpProfile;
import com.android.settingslib.bluetooth.CachedBluetoothDevice;
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

import java.util.ArrayList;
import java.util.List;

@RunWith(RobolectricTestRunner.class)
public class LocalMediaManagerTest {

    private static final String TEST_DEVICE_ID_1 = "device_id_1";
    private static final String TEST_DEVICE_ID_2 = "device_id_2";
    private static final String TEST_DEVICE_ID_3 = "device_id_3";
    private static final String TEST_CURRENT_DEVICE_ID = "currentDevice_id";

    @Mock
    private BluetoothMediaManager mBluetoothMediaManager;
    @Mock
    private InfoMediaManager mInfoMediaManager;
    @Mock
    private LocalBluetoothManager mLocalBluetoothManager;
    @Mock
    private LocalMediaManager.DeviceCallback mCallback;
    @Mock
    private HearingAidProfile mHapProfile;
    @Mock
    private A2dpProfile mA2dpProfile;
    @Mock
    private LocalBluetoothProfileManager mLocalProfileManager;

    private Context mContext;
    private LocalMediaManager mLocalMediaManager;

    @Before
    public void setUp() {
        MockitoAnnotations.initMocks(this);
        mContext = RuntimeEnvironment.application;

        when(mLocalBluetoothManager.getProfileManager()).thenReturn(mLocalProfileManager);
        when(mLocalProfileManager.getA2dpProfile()).thenReturn(mA2dpProfile);
        when(mLocalProfileManager.getHearingAidProfile()).thenReturn(mHapProfile);

        mLocalMediaManager = new LocalMediaManager(mContext, mLocalBluetoothManager,
                mBluetoothMediaManager, mInfoMediaManager);
    }

    @Test
    public void startScan_mediaDevicesListShouldBeClear() {
        final MediaDevice device = mock(MediaDevice.class);
        mLocalMediaManager.mMediaDevices.add(device);

        assertThat(mLocalMediaManager.mMediaDevices).hasSize(1);
        mLocalMediaManager.startScan();
        assertThat(mLocalMediaManager.mMediaDevices).isEmpty();
    }

    @Test
    public void connectDevice_deviceNotEqualCurrentConnectedDevice_connectDevice() {
        final MediaDevice currentDevice = mock(MediaDevice.class);
        final MediaDevice device = mock(MediaDevice.class);
        mLocalMediaManager.mMediaDevices.add(currentDevice);
        mLocalMediaManager.mMediaDevices.add(device);
        mLocalMediaManager.mCurrentConnectedDevice = currentDevice;

        when(device.getId()).thenReturn(TEST_DEVICE_ID_1);
        when(currentDevice.getId()).thenReturn(TEST_CURRENT_DEVICE_ID);

        mLocalMediaManager.registerCallback(mCallback);
        mLocalMediaManager.connectDevice(device);

        verify(currentDevice).disconnect();
        verify(device).connect();
        verify(mCallback).onSelectedDeviceStateChanged(any(),
                eq(LocalMediaManager.MediaDeviceState.STATE_DISCONNECTED));
    }

    @Test
    public void connectDevice_bluetoothDeviceNotConnected_connectBluetoothDevice() {
        final MediaDevice device = mock(BluetoothMediaDevice.class);
        final CachedBluetoothDevice cachedDevice = mock(CachedBluetoothDevice.class);
        mLocalMediaManager.mMediaDevices.add(device);

        when(device.getId()).thenReturn(TEST_DEVICE_ID_1);
        when(((BluetoothMediaDevice) device).getCachedDevice()).thenReturn(cachedDevice);
        when(cachedDevice.isConnected()).thenReturn(false);
        when(cachedDevice.isBusy()).thenReturn(false);

        mLocalMediaManager.registerCallback(mCallback);
        mLocalMediaManager.connectDevice(device);

        verify(cachedDevice).connect(true);
    }

    @Test
    public void getMediaDeviceById_idExist_shouldReturnMediaDevice() {
        final MediaDevice device1 = mock(MediaDevice.class);
        final MediaDevice device2 = mock(MediaDevice.class);
        mLocalMediaManager.mMediaDevices.add(device1);
        mLocalMediaManager.mMediaDevices.add(device2);

        when(device1.getId()).thenReturn(TEST_DEVICE_ID_1);
        when(device2.getId()).thenReturn(TEST_DEVICE_ID_2);

        final MediaDevice device = mLocalMediaManager
                .getMediaDeviceById(mLocalMediaManager.mMediaDevices, TEST_DEVICE_ID_2);

        assertThat(device.getId()).isEqualTo(TEST_DEVICE_ID_2);
    }

    @Test
    public void getMediaDeviceById_idNotExist_shouldReturnNull() {
        final MediaDevice device1 = mock(MediaDevice.class);
        final MediaDevice device2 = mock(MediaDevice.class);
        mLocalMediaManager.mMediaDevices.add(device1);
        mLocalMediaManager.mMediaDevices.add(device2);

        when(device1.getId()).thenReturn(TEST_DEVICE_ID_1);
        when(device2.getId()).thenReturn(TEST_DEVICE_ID_2);

        final MediaDevice device = mLocalMediaManager
                .getMediaDeviceById(mLocalMediaManager.mMediaDevices, TEST_CURRENT_DEVICE_ID);

        assertThat(device).isNull();
    }

    @Test
    public void onDeviceAdded_mediaDeviceAndPhoneDeviceNotExistInList_addBothDevice() {
        final MediaDevice device = mock(MediaDevice.class);

        assertThat(mLocalMediaManager.mMediaDevices).isEmpty();
        mLocalMediaManager.registerCallback(mCallback);
        mLocalMediaManager.mMediaDeviceCallback.onDeviceAdded(device);

        assertThat(mLocalMediaManager.mMediaDevices).hasSize(2);
        verify(mCallback).onDeviceListUpdate(any());
    }

    @Test
    public void onDeviceAdded_mediaDeviceNotExistAndPhoneDeviceExistInList_addMediaDevice() {
        final MediaDevice device1 = mock(MediaDevice.class);
        final MediaDevice device2 = mock(MediaDevice.class);
        mLocalMediaManager.mPhoneDevice = mock(PhoneMediaDevice.class);
        mLocalMediaManager.mMediaDevices.add(device1);
        mLocalMediaManager.mMediaDevices.add(mLocalMediaManager.mPhoneDevice);

        assertThat(mLocalMediaManager.mMediaDevices).hasSize(2);
        mLocalMediaManager.registerCallback(mCallback);
        mLocalMediaManager.mMediaDeviceCallback.onDeviceAdded(device2);

        assertThat(mLocalMediaManager.mMediaDevices).hasSize(3);
        verify(mCallback).onDeviceListUpdate(any());
    }

    @Test
    public void onDeviceAdded_mediaDeviceAndPhoneDeviceExistInList_doNothing() {
        final MediaDevice device1 = mock(MediaDevice.class);
        mLocalMediaManager.mPhoneDevice = mock(PhoneMediaDevice.class);
        mLocalMediaManager.mMediaDevices.add(device1);
        mLocalMediaManager.mMediaDevices.add(mLocalMediaManager.mPhoneDevice);

        assertThat(mLocalMediaManager.mMediaDevices).hasSize(2);
        mLocalMediaManager.registerCallback(mCallback);
        mLocalMediaManager.mMediaDeviceCallback.onDeviceAdded(device1);

        assertThat(mLocalMediaManager.mMediaDevices).hasSize(2);
        verify(mCallback, never()).onDeviceListUpdate(any());
    }

    @Test
    public void onDeviceListAdded_phoneDeviceNotExistInList_addPhoneDeviceAndDevicesList() {
        final List<MediaDevice> devices = new ArrayList<>();
        final MediaDevice device1 = mock(MediaDevice.class);
        final MediaDevice device2 = mock(MediaDevice.class);
        devices.add(device1);
        devices.add(device2);

        when(device1.getId()).thenReturn(TEST_DEVICE_ID_1);
        when(device2.getId()).thenReturn(TEST_DEVICE_ID_2);

        assertThat(mLocalMediaManager.mMediaDevices).isEmpty();
        mLocalMediaManager.registerCallback(mCallback);
        mLocalMediaManager.mMediaDeviceCallback.onDeviceListAdded(devices);

        assertThat(mLocalMediaManager.mMediaDevices).hasSize(3);
        verify(mCallback).onDeviceListUpdate(any());
    }

    @Test
    public void onDeviceListAdded_phoneDeviceExistInList_addDeviceList() {
        final List<MediaDevice> devices = new ArrayList<>();
        final MediaDevice device1 = mock(MediaDevice.class);
        final MediaDevice device2 = mock(MediaDevice.class);
        final MediaDevice device3 = mock(MediaDevice.class);
        mLocalMediaManager.mPhoneDevice = mock(PhoneMediaDevice.class);
        devices.add(device1);
        devices.add(device2);
        mLocalMediaManager.mMediaDevices.add(device3);
        mLocalMediaManager.mMediaDevices.add(mLocalMediaManager.mPhoneDevice);

        when(device1.getId()).thenReturn(TEST_DEVICE_ID_1);
        when(device2.getId()).thenReturn(TEST_DEVICE_ID_2);
        when(device3.getId()).thenReturn(TEST_DEVICE_ID_3);
        when(mLocalMediaManager.mPhoneDevice.getId()).thenReturn("test_phone_id");

        assertThat(mLocalMediaManager.mMediaDevices).hasSize(2);
        mLocalMediaManager.registerCallback(mCallback);
        mLocalMediaManager.mMediaDeviceCallback.onDeviceListAdded(devices);

        assertThat(mLocalMediaManager.mMediaDevices).hasSize(4);
        verify(mCallback).onDeviceListUpdate(any());
    }

    @Test
    public void onDeviceRemoved_phoneDeviceIsLastDeviceAfterRemoveMediaDevice_removeBothDevice() {
        final MediaDevice device1 = mock(MediaDevice.class);
        mLocalMediaManager.mPhoneDevice = mock(PhoneMediaDevice.class);
        mLocalMediaManager.mMediaDevices.add(device1);
        mLocalMediaManager.mMediaDevices.add(mLocalMediaManager.mPhoneDevice);

        assertThat(mLocalMediaManager.mMediaDevices).hasSize(2);
        mLocalMediaManager.registerCallback(mCallback);
        mLocalMediaManager.mMediaDeviceCallback.onDeviceRemoved(device1);

        assertThat(mLocalMediaManager.mMediaDevices).isEmpty();
        verify(mCallback).onDeviceListUpdate(any());
    }

    @Test
    public void onDeviceRemoved_phoneDeviceNotLastDeviceAfterRemoveMediaDevice_removeMediaDevice() {
        final MediaDevice device1 = mock(MediaDevice.class);
        final MediaDevice device2 = mock(MediaDevice.class);
        mLocalMediaManager.mPhoneDevice = mock(PhoneMediaDevice.class);
        mLocalMediaManager.mMediaDevices.add(device1);
        mLocalMediaManager.mMediaDevices.add(device2);
        mLocalMediaManager.mMediaDevices.add(mLocalMediaManager.mPhoneDevice);

        assertThat(mLocalMediaManager.mMediaDevices).hasSize(3);
        mLocalMediaManager.registerCallback(mCallback);
        mLocalMediaManager.mMediaDeviceCallback.onDeviceRemoved(device2);

        assertThat(mLocalMediaManager.mMediaDevices).hasSize(2);
        verify(mCallback).onDeviceListUpdate(any());
    }

    @Test
    public void onDeviceRemoved_removeMediaDeviceNotInList_doNothing() {
        final MediaDevice device1 = mock(MediaDevice.class);
        final MediaDevice device2 = mock(MediaDevice.class);
        mLocalMediaManager.mPhoneDevice = mock(PhoneMediaDevice.class);
        mLocalMediaManager.mMediaDevices.add(device2);
        mLocalMediaManager.mMediaDevices.add(mLocalMediaManager.mPhoneDevice);

        assertThat(mLocalMediaManager.mMediaDevices).hasSize(2);
        mLocalMediaManager.registerCallback(mCallback);
        mLocalMediaManager.mMediaDeviceCallback.onDeviceRemoved(device1);

        assertThat(mLocalMediaManager.mMediaDevices).hasSize(2);
        verify(mCallback, never()).onDeviceListUpdate(any());
    }

    @Test
    public void onDeviceListRemoved_phoneDeviceIsLastDeviceAfterRemoveDeviceList_removeAll() {
        final List<MediaDevice> devices = new ArrayList<>();
        final MediaDevice device1 = mock(MediaDevice.class);
        final MediaDevice device2 = mock(MediaDevice.class);
        mLocalMediaManager.mPhoneDevice = mock(PhoneMediaDevice.class);
        devices.add(device1);
        devices.add(device2);
        mLocalMediaManager.mMediaDevices.add(device1);
        mLocalMediaManager.mMediaDevices.add(device2);
        mLocalMediaManager.mMediaDevices.add(mLocalMediaManager.mPhoneDevice);

        assertThat(mLocalMediaManager.mMediaDevices).hasSize(3);
        mLocalMediaManager.registerCallback(mCallback);
        mLocalMediaManager.mMediaDeviceCallback.onDeviceListRemoved(devices);

        assertThat(mLocalMediaManager.mMediaDevices).isEmpty();
        verify(mCallback).onDeviceListUpdate(any());
    }

    @Test
    public void onDeviceListRemoved_phoneDeviceNotLastDeviceAfterRemoveDeviceList_removeList() {
        final List<MediaDevice> devices = new ArrayList<>();
        final MediaDevice device1 = mock(MediaDevice.class);
        final MediaDevice device2 = mock(MediaDevice.class);
        final MediaDevice device3 = mock(MediaDevice.class);
        mLocalMediaManager.mPhoneDevice = mock(PhoneMediaDevice.class);
        devices.add(device1);
        devices.add(device3);
        mLocalMediaManager.mMediaDevices.add(device1);
        mLocalMediaManager.mMediaDevices.add(device2);
        mLocalMediaManager.mMediaDevices.add(device3);
        mLocalMediaManager.mMediaDevices.add(mLocalMediaManager.mPhoneDevice);

        assertThat(mLocalMediaManager.mMediaDevices).hasSize(4);
        mLocalMediaManager.registerCallback(mCallback);
        mLocalMediaManager.mMediaDeviceCallback.onDeviceListRemoved(devices);

        assertThat(mLocalMediaManager.mMediaDevices).hasSize(2);
        verify(mCallback).onDeviceListUpdate(any());
    }

    @Test
    public void onConnectedDeviceChanged_connectedAndCurrentDeviceAreDifferent_notifyThemChanged() {
        final MediaDevice device1 = mock(MediaDevice.class);
        final MediaDevice device2 = mock(MediaDevice.class);

        mLocalMediaManager.mMediaDevices.add(device1);
        mLocalMediaManager.mMediaDevices.add(device2);
        mLocalMediaManager.mCurrentConnectedDevice = device1;

        when(device1.getId()).thenReturn(TEST_DEVICE_ID_1);
        when(device2.getId()).thenReturn(TEST_DEVICE_ID_2);

        mLocalMediaManager.registerCallback(mCallback);
        mLocalMediaManager.mMediaDeviceCallback.onConnectedDeviceChanged(TEST_DEVICE_ID_2);

        assertThat(mLocalMediaManager.getCurrentConnectedDevice()).isEqualTo(device2);
        verify(mCallback).onDeviceListUpdate(any());
    }

    @Test
    public void onConnectedDeviceChanged_connectedAndCurrentDeviceAreSame_doNothing() {
        final MediaDevice device1 = mock(MediaDevice.class);
        final MediaDevice device2 = mock(MediaDevice.class);

        mLocalMediaManager.mMediaDevices.add(device1);
        mLocalMediaManager.mMediaDevices.add(device2);
        mLocalMediaManager.mCurrentConnectedDevice = device1;

        when(device1.getId()).thenReturn(TEST_DEVICE_ID_1);
        when(device2.getId()).thenReturn(TEST_DEVICE_ID_2);

        mLocalMediaManager.registerCallback(mCallback);
        mLocalMediaManager.mMediaDeviceCallback.onConnectedDeviceChanged(TEST_DEVICE_ID_1);

        verify(mCallback, never()).onDeviceListUpdate(any());
    }

    @Test
    public void onDeviceAttributesChanged_shouldDispatchDeviceListUpdate() {
        mLocalMediaManager.registerCallback(mCallback);

        mLocalMediaManager.mMediaDeviceCallback.onDeviceAttributesChanged();

        verify(mCallback).onDeviceListUpdate(any());
    }
}
