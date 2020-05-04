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

import static android.bluetooth.BluetoothClass.Device.AUDIO_VIDEO_HEADPHONES;
import static android.media.MediaRoute2ProviderService.REASON_UNKNOWN_ERROR;

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothClass;
import android.bluetooth.BluetoothDevice;
import android.content.Context;
import android.media.MediaRoute2Info;
import android.media.MediaRouter2Manager;
import android.media.RoutingSessionInfo;

import com.android.settingslib.bluetooth.A2dpProfile;
import com.android.settingslib.bluetooth.CachedBluetoothDevice;
import com.android.settingslib.bluetooth.CachedBluetoothDeviceManager;
import com.android.settingslib.bluetooth.HearingAidProfile;
import com.android.settingslib.bluetooth.LocalBluetoothManager;
import com.android.settingslib.bluetooth.LocalBluetoothProfileManager;
import com.android.settingslib.testutils.shadow.ShadowBluetoothAdapter;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.shadow.api.Shadow;

import java.util.ArrayList;
import java.util.List;

@RunWith(RobolectricTestRunner.class)
@Config(shadows = {ShadowBluetoothAdapter.class})
public class LocalMediaManagerTest {

    private static final String TEST_DEVICE_NAME_1 = "device_name_1";
    private static final String TEST_DEVICE_NAME_2 = "device_name_2";
    private static final String TEST_DEVICE_ID_1 = "device_id_1";
    private static final String TEST_DEVICE_ID_2 = "device_id_2";
    private static final String TEST_DEVICE_ID_3 = "device_id_3";
    private static final String TEST_CURRENT_DEVICE_ID = "currentDevice_id";
    private static final String TEST_PACKAGE_NAME = "com.test.playmusic";
    private static final String TEST_SESSION_ID = "session_id";

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
    @Mock
    private MediaRouter2Manager mMediaRouter2Manager;
    @Mock
    private MediaRoute2Info mRouteInfo1;
    @Mock
    private MediaRoute2Info mRouteInfo2;

    private Context mContext;
    private LocalMediaManager mLocalMediaManager;
    private ShadowBluetoothAdapter mShadowBluetoothAdapter;
    private InfoMediaDevice mInfoMediaDevice1;
    private InfoMediaDevice mInfoMediaDevice2;

    @Before
    public void setUp() {
        MockitoAnnotations.initMocks(this);
        mContext = RuntimeEnvironment.application;
        final List<BluetoothDevice> bluetoothDevices = new ArrayList<>();
        mShadowBluetoothAdapter = Shadow.extract(BluetoothAdapter.getDefaultAdapter());
        mShadowBluetoothAdapter.setMostRecentlyConnectedDevices(bluetoothDevices);
        when(mRouteInfo1.getName()).thenReturn(TEST_DEVICE_NAME_1);
        when(mRouteInfo1.getId()).thenReturn(TEST_DEVICE_ID_1);
        when(mRouteInfo2.getName()).thenReturn(TEST_DEVICE_NAME_2);
        when(mRouteInfo2.getId()).thenReturn(TEST_DEVICE_ID_2);
        when(mLocalBluetoothManager.getProfileManager()).thenReturn(mLocalProfileManager);
        when(mLocalProfileManager.getA2dpProfile()).thenReturn(mA2dpProfile);
        when(mLocalProfileManager.getHearingAidProfile()).thenReturn(mHapProfile);

        mInfoMediaDevice1 = spy(new InfoMediaDevice(mContext, mMediaRouter2Manager, mRouteInfo1,
                TEST_PACKAGE_NAME));
        mInfoMediaDevice2 = new InfoMediaDevice(mContext, mMediaRouter2Manager, mRouteInfo2,
                TEST_PACKAGE_NAME);
        mLocalMediaManager = new LocalMediaManager(mContext, mLocalBluetoothManager,
                mInfoMediaManager, "com.test.packagename");
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
    }

    @Test
    public void connectDevice_deviceNotEqualCurrentConnectedDevice_isConnectingState() {
        mLocalMediaManager.mMediaDevices.add(mInfoMediaDevice1);
        mLocalMediaManager.mMediaDevices.add(mInfoMediaDevice2);
        mLocalMediaManager.mCurrentConnectedDevice = mInfoMediaDevice1;

        mLocalMediaManager.registerCallback(mCallback);
        mLocalMediaManager.connectDevice(mInfoMediaDevice2);

        assertThat(mInfoMediaDevice2.getState()).isEqualTo(LocalMediaManager.MediaDeviceState
                .STATE_CONNECTING);
    }

    @Test
    public void connectDevice_deviceEqualCurrentConnectedDevice_notConnectingState() {
        mLocalMediaManager.mMediaDevices.add(mInfoMediaDevice1);
        mLocalMediaManager.mMediaDevices.add(mInfoMediaDevice2);
        mLocalMediaManager.mCurrentConnectedDevice = mInfoMediaDevice1;

        mLocalMediaManager.registerCallback(mCallback);
        mLocalMediaManager.connectDevice(mInfoMediaDevice1);

        assertThat(mInfoMediaDevice1.getState()).isNotEqualTo(LocalMediaManager.MediaDeviceState
                .STATE_CONNECTING);
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

        verify(cachedDevice).connect();
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
    public void getMediaDeviceById_idIsNull_shouldReturnNull() {
        final MediaDevice device1 = mock(MediaDevice.class);
        final MediaDevice device2 = mock(MediaDevice.class);
        mLocalMediaManager.mMediaDevices.add(device1);
        mLocalMediaManager.mMediaDevices.add(device2);

        when(device1.getId()).thenReturn(null);
        when(device2.getId()).thenReturn(null);

        MediaDevice device = mLocalMediaManager
                .getMediaDeviceById(mLocalMediaManager.mMediaDevices, TEST_CURRENT_DEVICE_ID);

        assertThat(device).isNull();

        device = mLocalMediaManager.getMediaDeviceById(TEST_CURRENT_DEVICE_ID);

        assertThat(device).isNull();
    }

    @Test
    public void onDeviceAdded_addDevice() {
        final MediaDevice device = mock(MediaDevice.class);

        assertThat(mLocalMediaManager.mMediaDevices).isEmpty();
        mLocalMediaManager.registerCallback(mCallback);
        mLocalMediaManager.mMediaDeviceCallback.onDeviceAdded(device);

        assertThat(mLocalMediaManager.mMediaDevices).hasSize(1);
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
    public void onDeviceListAdded_addDevicesList() {
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

        assertThat(mLocalMediaManager.mMediaDevices).hasSize(2);
        verify(mCallback).onDeviceListUpdate(any());
    }

    @Test
    public void onDeviceListAdded_addDeviceList() {
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

        assertThat(mLocalMediaManager.mMediaDevices).hasSize(2);
        verify(mCallback).onDeviceListUpdate(any());
    }

    @Test
    public void onDeviceRemoved_removeDevice() {
        final MediaDevice device1 = mock(MediaDevice.class);
        mLocalMediaManager.mPhoneDevice = mock(PhoneMediaDevice.class);
        mLocalMediaManager.mMediaDevices.add(device1);
        mLocalMediaManager.mMediaDevices.add(mLocalMediaManager.mPhoneDevice);

        assertThat(mLocalMediaManager.mMediaDevices).hasSize(2);
        mLocalMediaManager.registerCallback(mCallback);
        mLocalMediaManager.mMediaDeviceCallback.onDeviceRemoved(device1);

        assertThat(mLocalMediaManager.mMediaDevices).hasSize(1);
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
    public void onDeviceListRemoved_removeAll() {
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
        mLocalMediaManager.mMediaDeviceCallback
                .onDeviceListRemoved(mLocalMediaManager.mMediaDevices);

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
        verify(mCallback).onSelectedDeviceStateChanged(device2,
                LocalMediaManager.MediaDeviceState.STATE_CONNECTED);
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

        verify(mCallback, never()).onDeviceAttributesChanged();
    }

    @Test
    public void onConnectedDeviceChanged_isConnectedState() {
        mLocalMediaManager.mMediaDevices.add(mInfoMediaDevice1);
        mLocalMediaManager.mMediaDevices.add(mInfoMediaDevice2);
        mInfoMediaDevice1.setState(LocalMediaManager.MediaDeviceState.STATE_DISCONNECTED);

        assertThat(mInfoMediaDevice1.getState()).isEqualTo(LocalMediaManager.MediaDeviceState
                .STATE_DISCONNECTED);
        mLocalMediaManager.registerCallback(mCallback);
        mLocalMediaManager.mMediaDeviceCallback.onConnectedDeviceChanged(TEST_DEVICE_ID_1);

        assertThat(mInfoMediaDevice1.getState()).isEqualTo(LocalMediaManager.MediaDeviceState
                .STATE_CONNECTED);
    }

    @Test
    public void onConnectedDeviceChanged_nullConnectedDevice_noException() {
        mLocalMediaManager.registerCallback(mCallback);
        mLocalMediaManager.mMediaDeviceCallback.onConnectedDeviceChanged(TEST_DEVICE_ID_2);
    }

    @Test
    public void onDeviceAttributesChanged_shouldDispatchDeviceListUpdate() {
        mLocalMediaManager.registerCallback(mCallback);

        mLocalMediaManager.mMediaDeviceCallback.onDeviceAttributesChanged();

        verify(mCallback).onDeviceAttributesChanged();
    }

    @Test
    public void onDeviceAttributesChanged_failingTransferring_shouldResetState() {
        final MediaDevice currentDevice = mock(MediaDevice.class);
        final MediaDevice device = mock(BluetoothMediaDevice.class);
        final CachedBluetoothDevice cachedDevice = mock(CachedBluetoothDevice.class);
        mLocalMediaManager.mMediaDevices.add(device);
        mLocalMediaManager.mMediaDevices.add(currentDevice);
        when(device.getId()).thenReturn(TEST_DEVICE_ID_1);
        when(currentDevice.getId()).thenReturn(TEST_CURRENT_DEVICE_ID);
        when(((BluetoothMediaDevice) device).getCachedDevice()).thenReturn(cachedDevice);
        when(cachedDevice.isConnected()).thenReturn(false);
        when(cachedDevice.isBusy()).thenReturn(false);

        mLocalMediaManager.registerCallback(mCallback);
        mLocalMediaManager.connectDevice(device);

        mLocalMediaManager.mDeviceAttributeChangeCallback.onDeviceAttributesChanged();
        verify(device).setState(LocalMediaManager.MediaDeviceState.STATE_DISCONNECTED);
    }

    @Test
    public void onRequestFailed_checkDevicesState() {
        mLocalMediaManager.mMediaDevices.add(mInfoMediaDevice1);
        mLocalMediaManager.mMediaDevices.add(mInfoMediaDevice2);
        mInfoMediaDevice1.setState(LocalMediaManager.MediaDeviceState.STATE_CONNECTING);
        mInfoMediaDevice2.setState(LocalMediaManager.MediaDeviceState.STATE_CONNECTED);

        assertThat(mInfoMediaDevice1.getState()).isEqualTo(LocalMediaManager.MediaDeviceState
                .STATE_CONNECTING);
        assertThat(mInfoMediaDevice2.getState()).isEqualTo(LocalMediaManager.MediaDeviceState
                .STATE_CONNECTED);
        mLocalMediaManager.registerCallback(mCallback);
        mLocalMediaManager.mMediaDeviceCallback.onRequestFailed(REASON_UNKNOWN_ERROR);

        assertThat(mInfoMediaDevice1.getState()).isEqualTo(LocalMediaManager.MediaDeviceState
                .STATE_CONNECTING_FAILED);
        assertThat(mInfoMediaDevice2.getState()).isEqualTo(LocalMediaManager.MediaDeviceState
                .STATE_CONNECTED);
    }

    @Test
    public void onDeviceAttributesChanged_shouldBeCalled() {
        mLocalMediaManager.registerCallback(mCallback);

        mLocalMediaManager.mDeviceAttributeChangeCallback.onDeviceAttributesChanged();

        verify(mCallback).onDeviceAttributesChanged();
    }

    @Test
    public void getActiveMediaSession_verifyCorrectSession() {
        final List<RoutingSessionInfo> routingSessionInfos = new ArrayList<>();
        final RoutingSessionInfo info = mock(RoutingSessionInfo.class);
        when(info.getId()).thenReturn(TEST_SESSION_ID);
        routingSessionInfos.add(info);
        when(mInfoMediaManager.getActiveMediaSession()).thenReturn(routingSessionInfos);

        assertThat(mLocalMediaManager.getActiveMediaSession().get(0).getId())
                .matches(TEST_SESSION_ID);
    }

    @Test
    public void onDeviceListAdded_haveDisconnectedDevice_addDisconnectedDevice() {
        final List<MediaDevice> devices = new ArrayList<>();
        final MediaDevice device1 = mock(MediaDevice.class);
        final MediaDevice device2 = mock(MediaDevice.class);
        final MediaDevice device3 = mock(MediaDevice.class);
        mLocalMediaManager.mPhoneDevice = mock(PhoneMediaDevice.class);
        devices.add(device1);
        devices.add(device2);
        mLocalMediaManager.mMediaDevices.add(device3);
        mLocalMediaManager.mMediaDevices.add(mLocalMediaManager.mPhoneDevice);

        final List<BluetoothDevice> bluetoothDevices = new ArrayList<>();
        final BluetoothDevice bluetoothDevice = mock(BluetoothDevice.class);
        final CachedBluetoothDevice cachedDevice = mock(CachedBluetoothDevice.class);
        final CachedBluetoothDeviceManager cachedManager = mock(CachedBluetoothDeviceManager.class);
        bluetoothDevices.add(bluetoothDevice);
        mShadowBluetoothAdapter.setMostRecentlyConnectedDevices(bluetoothDevices);

        when(mLocalBluetoothManager.getCachedDeviceManager()).thenReturn(cachedManager);
        when(cachedManager.findDevice(bluetoothDevice)).thenReturn(cachedDevice);
        when(cachedDevice.getBondState()).thenReturn(BluetoothDevice.BOND_BONDED);
        when(cachedDevice.isConnected()).thenReturn(false);

        when(device1.getId()).thenReturn(TEST_DEVICE_ID_1);
        when(device2.getId()).thenReturn(TEST_DEVICE_ID_2);
        when(device3.getId()).thenReturn(TEST_DEVICE_ID_3);
        when(mLocalMediaManager.mPhoneDevice.getId()).thenReturn("test_phone_id");

        assertThat(mLocalMediaManager.mMediaDevices).hasSize(2);
        mLocalMediaManager.registerCallback(mCallback);
        mLocalMediaManager.mMediaDeviceCallback.onDeviceListAdded(devices);

        assertThat(mLocalMediaManager.mMediaDevices).hasSize(3);
        verify(mCallback).onDeviceListUpdate(any());
    }

    @Test
    public void onDeviceListAdded_transferToDisconnectedBluetooth_verifyConnectDevice() {
        final List<MediaDevice> devices = new ArrayList<>();
        final MediaDevice currentDevice = mock(MediaDevice.class);
        final MediaDevice device = mock(BluetoothMediaDevice.class);
        final CachedBluetoothDevice cachedDevice = mock(CachedBluetoothDevice.class);
        mLocalMediaManager.mMediaDevices.add(device);
        mLocalMediaManager.mMediaDevices.add(currentDevice);

        when(device.getId()).thenReturn(TEST_DEVICE_ID_1);
        when(currentDevice.getId()).thenReturn(TEST_CURRENT_DEVICE_ID);
        when(((BluetoothMediaDevice) device).getCachedDevice()).thenReturn(cachedDevice);
        when(cachedDevice.isConnected()).thenReturn(false);
        when(cachedDevice.isBusy()).thenReturn(false);

        mLocalMediaManager.registerCallback(mCallback);
        mLocalMediaManager.connectDevice(device);

        verify(cachedDevice).connect();
        when(device.isConnected()).thenReturn(true);
        mLocalMediaManager.mCurrentConnectedDevice = currentDevice;
        devices.add(mInfoMediaDevice1);
        devices.add(currentDevice);
        mLocalMediaManager.mMediaDeviceCallback.onDeviceListAdded(devices);

        verify(mInfoMediaDevice1).connect();
    }

    @Test
    public void onRequestFailed_shouldDispatchOnRequestFailed() {
        mLocalMediaManager.registerCallback(mCallback);

        mLocalMediaManager.mMediaDeviceCallback.onRequestFailed(1);

        verify(mCallback).onRequestFailed(1);
    }

    @Test
    public void onDeviceListAdded_haveDisconnectedDevice_list5DisconnectedDevice() {
        final List<MediaDevice> devices = new ArrayList<>();
        final MediaDevice device1 = mock(MediaDevice.class);
        final MediaDevice device2 = mock(MediaDevice.class);
        final MediaDevice device3 = mock(MediaDevice.class);
        mLocalMediaManager.mPhoneDevice = mock(PhoneMediaDevice.class);
        devices.add(device1);
        devices.add(device2);
        mLocalMediaManager.mMediaDevices.add(device3);
        mLocalMediaManager.mMediaDevices.add(mLocalMediaManager.mPhoneDevice);

        final List<BluetoothDevice> bluetoothDevices = new ArrayList<>();
        final BluetoothDevice bluetoothDevice = mock(BluetoothDevice.class);
        final BluetoothDevice bluetoothDevice2 = mock(BluetoothDevice.class);
        final BluetoothDevice bluetoothDevice3 = mock(BluetoothDevice.class);
        final BluetoothDevice bluetoothDevice4 = mock(BluetoothDevice.class);
        final BluetoothDevice bluetoothDevice5 = mock(BluetoothDevice.class);
        final BluetoothDevice bluetoothDevice6 = mock(BluetoothDevice.class);
        final BluetoothClass bluetoothClass = mock(BluetoothClass.class);
        final CachedBluetoothDevice cachedDevice = mock(CachedBluetoothDevice.class);
        final CachedBluetoothDeviceManager cachedManager = mock(CachedBluetoothDeviceManager.class);
        bluetoothDevices.add(bluetoothDevice);
        bluetoothDevices.add(bluetoothDevice2);
        bluetoothDevices.add(bluetoothDevice3);
        bluetoothDevices.add(bluetoothDevice4);
        bluetoothDevices.add(bluetoothDevice5);
        bluetoothDevices.add(bluetoothDevice6);
        mShadowBluetoothAdapter.setMostRecentlyConnectedDevices(bluetoothDevices);

        when(mLocalBluetoothManager.getCachedDeviceManager()).thenReturn(cachedManager);
        when(cachedManager.findDevice(bluetoothDevice)).thenReturn(cachedDevice);
        when(cachedManager.findDevice(bluetoothDevice2)).thenReturn(cachedDevice);
        when(cachedManager.findDevice(bluetoothDevice3)).thenReturn(cachedDevice);
        when(cachedManager.findDevice(bluetoothDevice4)).thenReturn(cachedDevice);
        when(cachedManager.findDevice(bluetoothDevice5)).thenReturn(cachedDevice);
        when(cachedManager.findDevice(bluetoothDevice6)).thenReturn(cachedDevice);
        when(cachedDevice.getBondState()).thenReturn(BluetoothDevice.BOND_BONDED);
        when(cachedDevice.isConnected()).thenReturn(false);
        when(cachedDevice.getDevice()).thenReturn(bluetoothDevice);
        when(bluetoothDevice.getBluetoothClass()).thenReturn(bluetoothClass);
        when(bluetoothClass.getDeviceClass()).thenReturn(AUDIO_VIDEO_HEADPHONES);

        when(device1.getId()).thenReturn(TEST_DEVICE_ID_1);
        when(device2.getId()).thenReturn(TEST_DEVICE_ID_2);
        when(device3.getId()).thenReturn(TEST_DEVICE_ID_3);
        when(mLocalMediaManager.mPhoneDevice.getId()).thenReturn("test_phone_id");

        assertThat(mLocalMediaManager.mMediaDevices).hasSize(2);
        mLocalMediaManager.registerCallback(mCallback);
        mLocalMediaManager.mMediaDeviceCallback.onDeviceListAdded(devices);

        assertThat(mLocalMediaManager.mMediaDevices).hasSize(7);
        verify(mCallback).onDeviceListUpdate(any());
    }

    @Test
    public void onDeviceListAdded_bluetoothAdapterIsNull_noDisconnectedDeviceAdded() {
        final List<MediaDevice> devices = new ArrayList<>();
        final MediaDevice device1 = mock(MediaDevice.class);
        final MediaDevice device2 = mock(MediaDevice.class);
        final MediaDevice device3 = mock(MediaDevice.class);
        mLocalMediaManager.mPhoneDevice = mock(PhoneMediaDevice.class);
        devices.add(device1);
        devices.add(device2);
        mLocalMediaManager.mMediaDevices.add(device3);
        mLocalMediaManager.mMediaDevices.add(mLocalMediaManager.mPhoneDevice);

        mShadowBluetoothAdapter = null;

        when(mLocalMediaManager.mPhoneDevice.getId()).thenReturn("test_phone_id");

        assertThat(mLocalMediaManager.mMediaDevices).hasSize(2);
        mLocalMediaManager.registerCallback(mCallback);
        mLocalMediaManager.mMediaDeviceCallback.onDeviceListAdded(devices);

        assertThat(mLocalMediaManager.mMediaDevices).hasSize(2);
        verify(mCallback).onDeviceListUpdate(any());
    }

    @Test
    public void adjustSessionVolume_verifyCorrectSessionVolume() {
        final List<RoutingSessionInfo> routingSessionInfos = new ArrayList<>();
        final RoutingSessionInfo info = mock(RoutingSessionInfo.class);
        when(info.getId()).thenReturn(TEST_SESSION_ID);
        routingSessionInfos.add(info);
        when(mInfoMediaManager.getActiveMediaSession()).thenReturn(routingSessionInfos);

        mLocalMediaManager.adjustSessionVolume(TEST_SESSION_ID, 10);

        verify(mInfoMediaManager).adjustSessionVolume(info, 10);
    }
}
