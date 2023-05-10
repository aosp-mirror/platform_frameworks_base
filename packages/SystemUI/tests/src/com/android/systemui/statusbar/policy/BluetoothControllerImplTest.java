/*
 * Copyright (C) 2017 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file
 * except in compliance with the License. You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the
 * License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied. See the License for the specific language governing
 * permissions and limitations under the License.
 */

package com.android.systemui.statusbar.policy;

import static com.google.common.truth.Truth.assertThat;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothProfile;
import android.testing.AndroidTestingRunner;
import android.testing.TestableLooper;
import android.testing.TestableLooper.RunWithLooper;

import androidx.test.filters.SmallTest;

import com.android.settingslib.bluetooth.BluetoothEventManager;
import com.android.settingslib.bluetooth.CachedBluetoothDevice;
import com.android.settingslib.bluetooth.CachedBluetoothDeviceManager;
import com.android.settingslib.bluetooth.LocalBluetoothAdapter;
import com.android.settingslib.bluetooth.LocalBluetoothManager;
import com.android.settingslib.bluetooth.LocalBluetoothProfile;
import com.android.settingslib.bluetooth.LocalBluetoothProfileManager;
import com.android.systemui.SysuiTestCase;
import com.android.systemui.bluetooth.BluetoothLogger;
import com.android.systemui.dump.DumpManager;
import com.android.systemui.flags.FakeFeatureFlags;
import com.android.systemui.flags.Flags;
import com.android.systemui.settings.UserTracker;
import com.android.systemui.statusbar.policy.bluetooth.BluetoothRepository;
import com.android.systemui.statusbar.policy.bluetooth.FakeBluetoothRepository;
import com.android.systemui.util.concurrency.FakeExecutor;
import com.android.systemui.util.time.FakeSystemClock;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;

@RunWith(AndroidTestingRunner.class)
@RunWithLooper
@SmallTest
public class BluetoothControllerImplTest extends SysuiTestCase {

    private UserTracker mUserTracker;
    private LocalBluetoothManager mMockBluetoothManager;
    private CachedBluetoothDeviceManager mMockDeviceManager;
    private LocalBluetoothAdapter mMockLocalAdapter;
    private TestableLooper mTestableLooper;
    private DumpManager mMockDumpManager;
    private BluetoothControllerImpl mBluetoothControllerImpl;
    private BluetoothAdapter mMockAdapter;
    private final FakeFeatureFlags mFakeFeatureFlags = new FakeFeatureFlags();

    private List<CachedBluetoothDevice> mDevices;

    @Before
    public void setup() throws Exception {
        mTestableLooper = TestableLooper.get(this);
        mMockBluetoothManager = mDependency.injectMockDependency(LocalBluetoothManager.class);
        mDevices = new ArrayList<>();
        mUserTracker = mock(UserTracker.class);
        mMockDeviceManager = mock(CachedBluetoothDeviceManager.class);
        mMockAdapter = mock(BluetoothAdapter.class);
        when(mMockDeviceManager.getCachedDevicesCopy()).thenReturn(mDevices);
        when(mMockBluetoothManager.getCachedDeviceManager()).thenReturn(mMockDeviceManager);
        mMockLocalAdapter = mock(LocalBluetoothAdapter.class);
        when(mMockBluetoothManager.getBluetoothAdapter()).thenReturn(mMockLocalAdapter);
        when(mMockBluetoothManager.getEventManager()).thenReturn(mock(BluetoothEventManager.class));
        when(mMockBluetoothManager.getProfileManager())
                .thenReturn(mock(LocalBluetoothProfileManager.class));
        mMockDumpManager = mock(DumpManager.class);

        BluetoothRepository bluetoothRepository =
                new FakeBluetoothRepository(mMockBluetoothManager);
        mFakeFeatureFlags.set(Flags.NEW_BLUETOOTH_REPOSITORY, true);

        mBluetoothControllerImpl = new BluetoothControllerImpl(
                mContext,
                mFakeFeatureFlags,
                mUserTracker,
                mMockDumpManager,
                mock(BluetoothLogger.class),
                bluetoothRepository,
                mTestableLooper.getLooper(),
                mMockBluetoothManager,
                mMockAdapter);
    }

    @Test
    public void testNoConnectionWithDevices_repoFlagOff() {
        mFakeFeatureFlags.set(Flags.NEW_BLUETOOTH_REPOSITORY, false);

        CachedBluetoothDevice device = mock(CachedBluetoothDevice.class);
        when(device.isConnected()).thenReturn(true);
        when(device.getMaxConnectionState()).thenReturn(BluetoothProfile.STATE_CONNECTED);
        mDevices.add(device);
        when(mMockLocalAdapter.getConnectionState())
                .thenReturn(BluetoothAdapter.STATE_DISCONNECTED);

        mBluetoothControllerImpl.onConnectionStateChanged(null,
                BluetoothAdapter.STATE_DISCONNECTED);
        assertTrue(mBluetoothControllerImpl.isBluetoothConnected());
    }

    @Test
    public void testNoConnectionWithDevices_repoFlagOn() {
        mFakeFeatureFlags.set(Flags.NEW_BLUETOOTH_REPOSITORY, true);

        CachedBluetoothDevice device = mock(CachedBluetoothDevice.class);
        when(device.isConnected()).thenReturn(true);
        when(device.getMaxConnectionState()).thenReturn(BluetoothProfile.STATE_CONNECTED);

        mDevices.add(device);
        when(mMockLocalAdapter.getConnectionState())
                .thenReturn(BluetoothAdapter.STATE_DISCONNECTED);

        mBluetoothControllerImpl.onConnectionStateChanged(null,
                BluetoothAdapter.STATE_DISCONNECTED);

        assertTrue(mBluetoothControllerImpl.isBluetoothConnected());
    }

    @Test
    public void testOnServiceConnected_updatesConnectionState_repoFlagOff() {
        mFakeFeatureFlags.set(Flags.NEW_BLUETOOTH_REPOSITORY, false);

        when(mMockLocalAdapter.getConnectionState()).thenReturn(BluetoothAdapter.STATE_CONNECTING);

        mBluetoothControllerImpl.onServiceConnected();

        assertTrue(mBluetoothControllerImpl.isBluetoothConnecting());
        assertFalse(mBluetoothControllerImpl.isBluetoothConnected());
    }

    @Test
    public void testOnServiceConnected_updatesConnectionState_repoFlagOn() {
        mFakeFeatureFlags.set(Flags.NEW_BLUETOOTH_REPOSITORY, true);

        when(mMockLocalAdapter.getConnectionState()).thenReturn(BluetoothAdapter.STATE_CONNECTING);

        mBluetoothControllerImpl.onServiceConnected();

        assertTrue(mBluetoothControllerImpl.isBluetoothConnecting());
        assertFalse(mBluetoothControllerImpl.isBluetoothConnected());
    }

    @Test
    public void getConnectedDevices_onlyReturnsConnected_repoFlagOff() {
        mFakeFeatureFlags.set(Flags.NEW_BLUETOOTH_REPOSITORY, false);

        CachedBluetoothDevice device1Disconnected = mock(CachedBluetoothDevice.class);
        when(device1Disconnected.isConnected()).thenReturn(false);
        mDevices.add(device1Disconnected);

        CachedBluetoothDevice device2Connected = mock(CachedBluetoothDevice.class);
        when(device2Connected.isConnected()).thenReturn(true);
        mDevices.add(device2Connected);

        mBluetoothControllerImpl.onDeviceAdded(device1Disconnected);
        mBluetoothControllerImpl.onDeviceAdded(device2Connected);

        assertThat(mBluetoothControllerImpl.getConnectedDevices()).hasSize(1);
        assertThat(mBluetoothControllerImpl.getConnectedDevices().get(0))
                .isEqualTo(device2Connected);
    }

    @Test
    public void getConnectedDevices_onlyReturnsConnected_repoFlagOn() {
        mFakeFeatureFlags.set(Flags.NEW_BLUETOOTH_REPOSITORY, true);

        CachedBluetoothDevice device1Disconnected = mock(CachedBluetoothDevice.class);
        when(device1Disconnected.isConnected()).thenReturn(false);
        mDevices.add(device1Disconnected);

        CachedBluetoothDevice device2Connected = mock(CachedBluetoothDevice.class);
        when(device2Connected.isConnected()).thenReturn(true);
        mDevices.add(device2Connected);

        mBluetoothControllerImpl.onDeviceAdded(device1Disconnected);
        mBluetoothControllerImpl.onDeviceAdded(device2Connected);

        assertThat(mBluetoothControllerImpl.getConnectedDevices()).hasSize(1);
        assertThat(mBluetoothControllerImpl.getConnectedDevices().get(0))
                .isEqualTo(device2Connected);
    }

    @Test
    public void testOnBluetoothStateChange_updatesBluetoothState() {
        mBluetoothControllerImpl.onBluetoothStateChanged(BluetoothAdapter.STATE_OFF);

        assertEquals(BluetoothAdapter.STATE_OFF, mBluetoothControllerImpl.getBluetoothState());

        mBluetoothControllerImpl.onBluetoothStateChanged(BluetoothAdapter.STATE_ON);

        assertEquals(BluetoothAdapter.STATE_ON, mBluetoothControllerImpl.getBluetoothState());
    }

    @Test
    public void testOnBluetoothStateChange_updatesConnectionState() {
        when(mMockLocalAdapter.getConnectionState()).thenReturn(
                BluetoothAdapter.STATE_CONNECTING,
                BluetoothAdapter.STATE_DISCONNECTED);

        mBluetoothControllerImpl.onServiceConnected();
        mBluetoothControllerImpl.onBluetoothStateChanged(BluetoothAdapter.STATE_OFF);

        assertFalse(mBluetoothControllerImpl.isBluetoothConnecting());
        assertFalse(mBluetoothControllerImpl.isBluetoothConnected());
    }

    @Test
    public void testOnACLConnectionStateChange_updatesBluetoothStateOnConnection_repoFlagOff() {
        mFakeFeatureFlags.set(Flags.NEW_BLUETOOTH_REPOSITORY, false);

        BluetoothController.Callback callback = mock(BluetoothController.Callback.class);
        mBluetoothControllerImpl.addCallback(callback);

        assertFalse(mBluetoothControllerImpl.isBluetoothConnected());
        CachedBluetoothDevice device = mock(CachedBluetoothDevice.class);
        mDevices.add(device);
        when(device.isConnected()).thenReturn(true);
        when(device.getMaxConnectionState()).thenReturn(BluetoothProfile.STATE_CONNECTED);
        reset(callback);
        mBluetoothControllerImpl.onAclConnectionStateChanged(device,
                BluetoothProfile.STATE_CONNECTED);

        mTestableLooper.processAllMessages();

        assertTrue(mBluetoothControllerImpl.isBluetoothConnected());
        verify(callback, atLeastOnce()).onBluetoothStateChange(anyBoolean());
    }

    @Test
    public void testOnACLConnectionStateChange_updatesBluetoothStateOnConnection_repoFlagOn() {
        mFakeFeatureFlags.set(Flags.NEW_BLUETOOTH_REPOSITORY, true);

        BluetoothController.Callback callback = mock(BluetoothController.Callback.class);
        mBluetoothControllerImpl.addCallback(callback);

        assertFalse(mBluetoothControllerImpl.isBluetoothConnected());
        CachedBluetoothDevice device = mock(CachedBluetoothDevice.class);
        mDevices.add(device);
        when(device.isConnected()).thenReturn(true);
        when(device.getMaxConnectionState()).thenReturn(BluetoothProfile.STATE_CONNECTED);
        reset(callback);
        mBluetoothControllerImpl.onAclConnectionStateChanged(device,
                BluetoothProfile.STATE_CONNECTED);

        mTestableLooper.processAllMessages();

        assertTrue(mBluetoothControllerImpl.isBluetoothConnected());
        verify(callback, atLeastOnce()).onBluetoothStateChange(anyBoolean());
    }


    @Test
    public void testOnActiveDeviceChanged_updatesAudioActive() {
        assertFalse(mBluetoothControllerImpl.isBluetoothAudioActive());
        assertFalse(mBluetoothControllerImpl.isBluetoothAudioProfileOnly());

        CachedBluetoothDevice device = mock(CachedBluetoothDevice.class);
        mDevices.add(device);
        when(device.isActiveDevice(BluetoothProfile.HEADSET)).thenReturn(true);

        List<LocalBluetoothProfile> profiles = new ArrayList<>();
        LocalBluetoothProfile profile = mock(LocalBluetoothProfile.class);
        profiles.add(profile);
        when(profile.getProfileId()).thenReturn(BluetoothProfile.HEADSET);
        when(device.getProfiles()).thenReturn(profiles);
        when(device.isConnectedProfile(profile)).thenReturn(true);

        mBluetoothControllerImpl.onAclConnectionStateChanged(device,
                BluetoothProfile.STATE_CONNECTED);
        mBluetoothControllerImpl.onActiveDeviceChanged(device, BluetoothProfile.HEADSET);

        assertTrue(mBluetoothControllerImpl.isBluetoothAudioActive());
        assertTrue(mBluetoothControllerImpl.isBluetoothAudioProfileOnly());
    }

    @Test
    public void testAddOnMetadataChangedListener_registersListenerOnAdapter() {
        CachedBluetoothDevice cachedDevice = mock(CachedBluetoothDevice.class);
        BluetoothDevice device = mock(BluetoothDevice.class);
        when(cachedDevice.getDevice()).thenReturn(device);
        Executor executor = new FakeExecutor(new FakeSystemClock());
        BluetoothAdapter.OnMetadataChangedListener listener = (bluetoothDevice, i, bytes) -> {
        };

        mBluetoothControllerImpl.addOnMetadataChangedListener(cachedDevice, executor, listener);

        verify(mMockAdapter, times(1)).addOnMetadataChangedListener(device, executor, listener);
    }

    @Test
    public void testRemoveOnMetadataChangedListener_removesListenerFromAdapter() {
        CachedBluetoothDevice cachedDevice = mock(CachedBluetoothDevice.class);
        BluetoothDevice device = mock(BluetoothDevice.class);
        when(cachedDevice.getDevice()).thenReturn(device);
        BluetoothAdapter.OnMetadataChangedListener listener = (bluetoothDevice, i, bytes) -> {
        };

        mBluetoothControllerImpl.removeOnMetadataChangedListener(cachedDevice, listener);

        verify(mMockAdapter, times(1)).removeOnMetadataChangedListener(device, listener);
    }

    /** Regression test for b/246876230. */
    @Test
    public void testOnActiveDeviceChanged_null_noCrash() {
        mBluetoothControllerImpl.onActiveDeviceChanged(null, BluetoothProfile.HEADSET);
        // No assert, just need no crash.
    }
}
