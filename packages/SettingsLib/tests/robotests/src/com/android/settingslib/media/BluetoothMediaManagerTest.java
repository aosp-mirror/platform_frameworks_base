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
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothProfile;
import android.content.Context;

import com.android.settingslib.bluetooth.A2dpProfile;
import com.android.settingslib.bluetooth.BluetoothEventManager;
import com.android.settingslib.bluetooth.CachedBluetoothDevice;
import com.android.settingslib.bluetooth.CachedBluetoothDeviceManager;
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
import java.util.Collection;
import java.util.List;

@RunWith(RobolectricTestRunner.class)
public class BluetoothMediaManagerTest {

    private static final String TEST_ADDRESS = "11:22:33:44:55:66";

    @Mock
    private LocalBluetoothManager mLocalBluetoothManager;
    @Mock
    private LocalBluetoothProfileManager mProfileManager;
    @Mock
    private A2dpProfile mA2dpProfile;
    @Mock
    private HearingAidProfile mHapProfile;
    @Mock
    private CachedBluetoothDeviceManager mCachedDeviceManager;
    @Mock
    private BluetoothEventManager mEventManager;
    @Mock
    private MediaManager.MediaDeviceCallback mCallback;

    private BluetoothMediaManager mMediaManager;
    private Context mContext;

    @Before
    public void setUp() {
        MockitoAnnotations.initMocks(this);
        mContext = RuntimeEnvironment.application;

        when(mLocalBluetoothManager.getProfileManager()).thenReturn(mProfileManager);
        when(mLocalBluetoothManager.getCachedDeviceManager()).thenReturn(mCachedDeviceManager);
        when(mProfileManager.getA2dpProfile()).thenReturn(mA2dpProfile);
        when(mProfileManager.getHearingAidProfile()).thenReturn(mHapProfile);
        when(mLocalBluetoothManager.getEventManager()).thenReturn(mEventManager);

        mMediaManager = new BluetoothMediaManager(mContext, mLocalBluetoothManager, null);
    }

    @Test
    public void startScan_haveA2dpProfileDeviceIsPreferredAndBonded_shouldAddDevice() {
        final List<BluetoothDevice> devices = new ArrayList<>();
        final CachedBluetoothDevice cachedDevice = mock(CachedBluetoothDevice.class);
        final BluetoothDevice bluetoothDevice = mock(BluetoothDevice.class);
        devices.add(bluetoothDevice);

        when(mA2dpProfile.getConnectableDevices()).thenReturn(devices);
        when(mCachedDeviceManager.findDevice(bluetoothDevice)).thenReturn(cachedDevice);
        when(cachedDevice.getBondState()).thenReturn(BluetoothDevice.BOND_BONDED);
        when(mA2dpProfile.isPreferred(bluetoothDevice)).thenReturn(true);

        assertThat(mMediaManager.mMediaDevices).isEmpty();
        mMediaManager.startScan();
        assertThat(mMediaManager.mMediaDevices).hasSize(devices.size());
    }

    @Test
    public void startScan_haveA2dpProfileDeviceIsPreferredAndBondNone_shouldNotAddDevice() {
        final List<BluetoothDevice> devices = new ArrayList<>();
        final CachedBluetoothDevice cachedDevice = mock(CachedBluetoothDevice.class);
        final BluetoothDevice bluetoothDevice = mock(BluetoothDevice.class);
        devices.add(bluetoothDevice);

        when(mA2dpProfile.getConnectableDevices()).thenReturn(devices);
        when(mCachedDeviceManager.findDevice(bluetoothDevice)).thenReturn(cachedDevice);
        when(cachedDevice.getBondState()).thenReturn(BluetoothDevice.BOND_NONE);
        when(mA2dpProfile.isPreferred(bluetoothDevice)).thenReturn(true);

        assertThat(mMediaManager.mMediaDevices).isEmpty();
        mMediaManager.startScan();
        assertThat(mMediaManager.mMediaDevices).isEmpty();
    }

    @Test
    public void startScan_noA2dpProfileBluetoothDevice_shouldNotAddDevice() {
        final List<BluetoothDevice> devices = new ArrayList<>();

        when(mA2dpProfile.getConnectableDevices()).thenReturn(devices);

        assertThat(mMediaManager.mMediaDevices).isEmpty();
        mMediaManager.startScan();
        assertThat(mMediaManager.mMediaDevices).isEmpty();
    }

    @Test
    public void startScan_haveHapProfileDeviceIsPreferredAndBonded_shouldAddDevice() {
        final List<BluetoothDevice> devices = new ArrayList<>();
        final CachedBluetoothDevice cachedDevice = mock(CachedBluetoothDevice.class);
        final BluetoothDevice bluetoothDevice = mock(BluetoothDevice.class);
        devices.add(bluetoothDevice);

        when(mHapProfile.getConnectableDevices()).thenReturn(devices);
        when(mCachedDeviceManager.findDevice(bluetoothDevice)).thenReturn(cachedDevice);
        when(cachedDevice.getBondState()).thenReturn(BluetoothDevice.BOND_BONDED);
        when(mHapProfile.isPreferred(bluetoothDevice)).thenReturn(true);

        assertThat(mMediaManager.mMediaDevices).isEmpty();
        mMediaManager.startScan();
        assertThat(mMediaManager.mMediaDevices).hasSize(devices.size());
    }

    @Test
    public void startScan_noHapProfileBluetoothDevice_shouldNotAddDevice() {
        final List<BluetoothDevice> devices = new ArrayList<>();

        when(mHapProfile.getConnectableDevices()).thenReturn(devices);

        assertThat(mMediaManager.mMediaDevices).isEmpty();
        mMediaManager.startScan();
        assertThat(mMediaManager.mMediaDevices).isEmpty();
    }

    @Test
    public void starScan_a2dpAndHapProfileNotReady_shouldRegisterCallback() {
        final Collection<CachedBluetoothDevice> mDevices = new ArrayList<>();
        final CachedBluetoothDevice cachedDevice = mock(CachedBluetoothDevice.class);
        mDevices.add(cachedDevice);

        when(mCachedDeviceManager.getCachedDevicesCopy()).thenReturn(mDevices);
        when(mA2dpProfile.isProfileReady()).thenReturn(false);
        when(mHapProfile.isProfileReady()).thenReturn(false);

        mMediaManager.startScan();

        verify(mProfileManager).addServiceListener(mMediaManager);
    }

    @Test
    public void starScan_a2dpAndHapProfileReady_shouldNotRegisterCallback() {
        final Collection<CachedBluetoothDevice> mDevices = new ArrayList<>();
        final CachedBluetoothDevice cachedDevice = mock(CachedBluetoothDevice.class);
        mDevices.add(cachedDevice);

        when(mCachedDeviceManager.getCachedDevicesCopy()).thenReturn(mDevices);
        when(mA2dpProfile.isProfileReady()).thenReturn(true);
        when(mHapProfile.isProfileReady()).thenReturn(true);

        mMediaManager.startScan();

        verify(mProfileManager, never()).addServiceListener(mMediaManager);
    }

    @Test
    public void onServiceConnected_a2dpAndHapProfileNotReady_doNothing() {
        final Collection<CachedBluetoothDevice> mDevices = new ArrayList<>();
        final CachedBluetoothDevice cachedDevice = mock(CachedBluetoothDevice.class);
        mDevices.add(cachedDevice);

        when(mCachedDeviceManager.getCachedDevicesCopy()).thenReturn(mDevices);
        when(mA2dpProfile.isProfileReady()).thenReturn(false);
        when(mHapProfile.isProfileReady()).thenReturn(false);

        mMediaManager.startScan();
        mMediaManager.onServiceConnected();

        verify(mProfileManager, never()).removeServiceListener(mMediaManager);
    }

    @Test
    public void onDeviceAttributesChanged_a2dpAndHapProfileReady_shouldUnregisterCallback() {
        final Collection<CachedBluetoothDevice> mDevices = new ArrayList<>();
        final CachedBluetoothDevice cachedDevice = mock(CachedBluetoothDevice.class);
        mDevices.add(cachedDevice);

        when(mCachedDeviceManager.getCachedDevicesCopy()).thenReturn(mDevices);
        when(mA2dpProfile.isProfileReady()).thenReturn(true);
        when(mHapProfile.isProfileReady()).thenReturn(true);

        mMediaManager.startScan();
        mMediaManager.onServiceConnected();

        verify(mProfileManager).removeServiceListener(mMediaManager);
    }

    @Test
    public void onBluetoothStateChanged_bluetoothStateIsOn_callOnDeviceListAdded() {
        mMediaManager.registerCallback(mCallback);
        mMediaManager.onBluetoothStateChanged(BluetoothAdapter.STATE_ON);

        verify(mCallback).onDeviceListAdded(any());
    }

    @Test
    public void onBluetoothStateChanged_bluetoothStateIsOff_callOnDeviceListRemoved() {
        final BluetoothMediaDevice device1 = mock(BluetoothMediaDevice.class);
        final BluetoothMediaDevice device2 = mock(BluetoothMediaDevice.class);
        final CachedBluetoothDevice cachedDevice1 = mock(CachedBluetoothDevice.class);
        final CachedBluetoothDevice cachedDevice2 = mock(CachedBluetoothDevice.class);
        mMediaManager.mMediaDevices.add(device1);
        mMediaManager.mMediaDevices.add(device2);

        when(device1.getCachedDevice()).thenReturn(cachedDevice1);
        when(device2.getCachedDevice()).thenReturn(cachedDevice2);

        mMediaManager.registerCallback(mCallback);
        mMediaManager.onBluetoothStateChanged(BluetoothAdapter.STATE_OFF);

        assertThat(mMediaManager.mMediaDevices).isEmpty();
        verify(mCallback).onDeviceListRemoved(any());
    }

    @Test
    public void onDeviceAdded_cachedDeviceIsConnected_callOnDeviceAdded() {
        final CachedBluetoothDevice device = mock(CachedBluetoothDevice.class);

        when(device.isConnectedHearingAidDevice()).thenReturn(true);
        when(device.isConnectedA2dpDevice()).thenReturn(true);

        assertThat(mMediaManager.mMediaDevices).isEmpty();
        mMediaManager.registerCallback(mCallback);
        mMediaManager.onDeviceAdded(device);

        assertThat(mMediaManager.mMediaDevices).hasSize(1);
        verify(mCallback).onDeviceAdded(any());

    }

    @Test
    public void onDeviceAdded_cachedDeviceIsDisconnected_doNothing() {
        final CachedBluetoothDevice device = mock(CachedBluetoothDevice.class);

        when(device.isConnectedHearingAidDevice()).thenReturn(false);
        when(device.isConnectedA2dpDevice()).thenReturn(false);

        assertThat(mMediaManager.mMediaDevices).isEmpty();
        mMediaManager.registerCallback(mCallback);
        mMediaManager.onDeviceAdded(device);

        assertThat(mMediaManager.mMediaDevices).isEmpty();
        verify(mCallback, never()).onDeviceAdded(any());

    }

    @Test
    public void onDeviceDeleted_cachedDeviceIsConnected_doNothing() {
        final CachedBluetoothDevice device = mock(CachedBluetoothDevice.class);
        final BluetoothMediaDevice bluetoothMediaDevice = mock(BluetoothMediaDevice.class);
        mMediaManager.mMediaDevices.add(bluetoothMediaDevice);

        when(device.isConnectedHearingAidDevice()).thenReturn(true);
        when(device.isConnectedA2dpDevice()).thenReturn(true);
        when(device.getAddress()).thenReturn(TEST_ADDRESS);
        when(bluetoothMediaDevice.getId()).thenReturn(TEST_ADDRESS);

        assertThat(mMediaManager.mMediaDevices).hasSize(1);
        mMediaManager.registerCallback(mCallback);
        mMediaManager.onDeviceDeleted(device);

        assertThat(mMediaManager.mMediaDevices).hasSize(1);
        verify(mCallback, never()).onDeviceRemoved(any());
    }

    @Test
    public void onDeviceDeleted_cachedDeviceIsDisconnected_callOnDeviceRemoved() {
        final CachedBluetoothDevice device = mock(CachedBluetoothDevice.class);
        final BluetoothMediaDevice bluetoothMediaDevice = mock(BluetoothMediaDevice.class);
        mMediaManager.mMediaDevices.add(bluetoothMediaDevice);

        when(device.isConnectedHearingAidDevice()).thenReturn(false);
        when(device.isConnectedA2dpDevice()).thenReturn(false);
        when(device.getAddress()).thenReturn(TEST_ADDRESS);
        when(bluetoothMediaDevice.getId()).thenReturn(TEST_ADDRESS);

        assertThat(mMediaManager.mMediaDevices).hasSize(1);
        mMediaManager.registerCallback(mCallback);
        mMediaManager.onDeviceDeleted(device);

        assertThat(mMediaManager.mMediaDevices).isEmpty();
        verify(mCallback).onDeviceRemoved(any());
    }

    @Test
    public void onProfileConnectionStateChanged_cachedDeviceIsBonded_callDeviceAttributesChanged() {
        final CachedBluetoothDevice device = mock(CachedBluetoothDevice.class);
        final BluetoothMediaDevice bluetoothMediaDevice = mock(BluetoothMediaDevice.class);
        mMediaManager.mMediaDevices.add(bluetoothMediaDevice);

        when(device.getBondState()).thenReturn(BluetoothDevice.BOND_BONDED);
        when(device.getAddress()).thenReturn(TEST_ADDRESS);
        when(bluetoothMediaDevice.getId()).thenReturn(TEST_ADDRESS);

        assertThat(mMediaManager.mMediaDevices).hasSize(1);
        mMediaManager.registerCallback(mCallback);
        mMediaManager.onProfileConnectionStateChanged(device, 0, 0);

        assertThat(mMediaManager.mMediaDevices).hasSize(1);
        verify(mCallback).onDeviceAttributesChanged();
    }

    @Test
    public void onProfileConnectionStateChanged_cachedDeviceIsBondNone_callOnDeviceRemoved() {
        final CachedBluetoothDevice device = mock(CachedBluetoothDevice.class);
        final BluetoothMediaDevice bluetoothMediaDevice = mock(BluetoothMediaDevice.class);
        mMediaManager.mMediaDevices.add(bluetoothMediaDevice);

        when(device.getBondState()).thenReturn(BluetoothDevice.BOND_NONE);
        when(device.getAddress()).thenReturn(TEST_ADDRESS);
        when(bluetoothMediaDevice.getId()).thenReturn(TEST_ADDRESS);

        assertThat(mMediaManager.mMediaDevices).hasSize(1);
        mMediaManager.registerCallback(mCallback);
        mMediaManager.onProfileConnectionStateChanged(device, 0, 0);

        assertThat(mMediaManager.mMediaDevices).isEmpty();
        verify(mCallback).onDeviceRemoved(any());
    }

    @Test
    public void onAclConnectionStateChanged_cachedDeviceIsBonded_callDeviceAttributesChanged() {
        final CachedBluetoothDevice device = mock(CachedBluetoothDevice.class);
        final BluetoothMediaDevice bluetoothMediaDevice = mock(BluetoothMediaDevice.class);
        mMediaManager.mMediaDevices.add(bluetoothMediaDevice);

        when(device.getBondState()).thenReturn(BluetoothDevice.BOND_BONDED);
        when(device.getAddress()).thenReturn(TEST_ADDRESS);
        when(bluetoothMediaDevice.getId()).thenReturn(TEST_ADDRESS);

        assertThat(mMediaManager.mMediaDevices).hasSize(1);
        mMediaManager.registerCallback(mCallback);
        mMediaManager.onAclConnectionStateChanged(device, 0);

        assertThat(mMediaManager.mMediaDevices).hasSize(1);
        verify(mCallback).onDeviceAttributesChanged();
    }

    @Test
    public void onAclConnectionStateChanged_cachedDeviceIsBondNone_callOnDeviceRemoved() {
        final CachedBluetoothDevice device = mock(CachedBluetoothDevice.class);
        final BluetoothMediaDevice bluetoothMediaDevice = mock(BluetoothMediaDevice.class);
        mMediaManager.mMediaDevices.add(bluetoothMediaDevice);

        when(device.getBondState()).thenReturn(BluetoothDevice.BOND_NONE);
        when(device.getAddress()).thenReturn(TEST_ADDRESS);
        when(bluetoothMediaDevice.getId()).thenReturn(TEST_ADDRESS);

        assertThat(mMediaManager.mMediaDevices).hasSize(1);
        mMediaManager.registerCallback(mCallback);
        mMediaManager.onAclConnectionStateChanged(device, 0);

        assertThat(mMediaManager.mMediaDevices).isEmpty();
        verify(mCallback).onDeviceRemoved(any());
    }

    @Test
    public void onActiveDeviceChanged_isHapProfile_callOnActiveDeviceChanged() {
        final CachedBluetoothDevice device = mock(CachedBluetoothDevice.class);

        when(device.getAddress()).thenReturn(TEST_ADDRESS);

        mMediaManager.registerCallback(mCallback);
        mMediaManager.onActiveDeviceChanged(device, BluetoothProfile.HEARING_AID);

        verify(mCallback).onConnectedDeviceChanged(any());
    }

    @Test
    public void onActiveDeviceChanged_isA2dpProfile_callOnActiveDeviceChanged() {
        final CachedBluetoothDevice device = mock(CachedBluetoothDevice.class);

        when(device.getAddress()).thenReturn(TEST_ADDRESS);

        mMediaManager.registerCallback(mCallback);
        mMediaManager.onActiveDeviceChanged(device, BluetoothProfile.A2DP);

        verify(mCallback).onConnectedDeviceChanged(any());
    }

    @Test
    public void onActiveDeviceChanged_isNotA2dpAndHapProfile_doNothing() {
        final CachedBluetoothDevice device = mock(CachedBluetoothDevice.class);

        when(device.getAddress()).thenReturn(TEST_ADDRESS);

        mMediaManager.registerCallback(mCallback);
        mMediaManager.onActiveDeviceChanged(device, BluetoothProfile.HEALTH);

        verify(mCallback, never()).onConnectedDeviceChanged(any());
    }

    @Test
    public void onActiveDeviceChanged_hearingAidDeviceIsActive_returnHearingAidDeviceId() {
        final BluetoothDevice bluetoothDevice = mock(BluetoothDevice.class);
        final List<BluetoothDevice> devices = new ArrayList<>();
        devices.add(bluetoothDevice);
        final BluetoothMediaDevice bluetoothMediaDevice = mock(BluetoothMediaDevice.class);
        mMediaManager.mMediaDevices.add(bluetoothMediaDevice);

        when(bluetoothDevice.getAddress()).thenReturn(TEST_ADDRESS);
        when(mHapProfile.getActiveDevices()).thenReturn(devices);
        when(bluetoothMediaDevice.getId()).thenReturn(TEST_ADDRESS);

        mMediaManager.registerCallback(mCallback);
        mMediaManager.onActiveDeviceChanged(null, BluetoothProfile.A2DP);

        verify(mCallback).onConnectedDeviceChanged(TEST_ADDRESS);
    }

    @Test
    public void onActiveDeviceChanged_hearingAidDeviceNotActive_returnPhoneDeviceId() {
        final List<BluetoothDevice> devices = new ArrayList<>();

        when(mHapProfile.getActiveDevices()).thenReturn(devices);

        mMediaManager.registerCallback(mCallback);
        mMediaManager.onActiveDeviceChanged(null, BluetoothProfile.A2DP);

        verify(mCallback).onConnectedDeviceChanged(PhoneMediaDevice.ID);
    }
}
