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

import static android.platform.test.flag.junit.FlagsParameterization.allCombinationsOf;

import static com.android.settingslib.flags.Flags.FLAG_ENABLE_HIDE_EXCLUSIVELY_MANAGED_BLUETOOTH_DEVICE;

import static com.google.common.truth.Truth.assertThat;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothProfile;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.platform.test.annotations.DisableFlags;
import android.platform.test.annotations.EnableFlags;
import android.platform.test.flag.junit.FlagsParameterization;
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
import com.android.systemui.settings.UserTracker;
import com.android.systemui.statusbar.policy.bluetooth.BluetoothRepository;
import com.android.systemui.statusbar.policy.bluetooth.FakeBluetoothRepository;
import com.android.systemui.util.concurrency.FakeExecutor;
import com.android.systemui.util.time.FakeSystemClock;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;

import platform.test.runner.parameterized.ParameterizedAndroidJunit4;
import platform.test.runner.parameterized.Parameters;

@RunWith(ParameterizedAndroidJunit4.class)
@RunWithLooper
@SmallTest
public class BluetoothControllerImplTest extends SysuiTestCase {

    @Parameters(name = "{0}")
    public static List<FlagsParameterization> getParams() {
        return allCombinationsOf(FLAG_ENABLE_HIDE_EXCLUSIVELY_MANAGED_BLUETOOTH_DEVICE);
    }

    private static final String TEST_EXCLUSIVE_MANAGER = "com.test.manager";

    @Mock
    private PackageManager mPackageManager;

    private UserTracker mUserTracker;
    private LocalBluetoothManager mMockBluetoothManager;
    private CachedBluetoothDeviceManager mMockDeviceManager;
    private LocalBluetoothAdapter mMockLocalAdapter;
    private TestableLooper mTestableLooper;
    private DumpManager mMockDumpManager;
    private BluetoothControllerImpl mBluetoothControllerImpl;
    private BluetoothAdapter mMockAdapter;
    private List<CachedBluetoothDevice> mDevices;

    private FakeExecutor mBackgroundExecutor;

    public BluetoothControllerImplTest(FlagsParameterization flags) {
        super();
        mSetFlagsRule.setFlagsParameterization(flags);
    }

    @Before
    public void setup() throws Exception {
        MockitoAnnotations.initMocks(this);
        mTestableLooper = TestableLooper.get(this);
        mMockBluetoothManager = mDependency.injectMockDependency(LocalBluetoothManager.class);
        mDevices = new ArrayList<>();
        mUserTracker = mock(UserTracker.class);
        mMockDeviceManager = mock(CachedBluetoothDeviceManager.class);
        mMockAdapter = mock(BluetoothAdapter.class);
        mContext.setMockPackageManager(mPackageManager);
        when(mMockDeviceManager.getCachedDevicesCopy()).thenReturn(mDevices);
        when(mMockBluetoothManager.getCachedDeviceManager()).thenReturn(mMockDeviceManager);
        mMockLocalAdapter = mock(LocalBluetoothAdapter.class);
        when(mMockBluetoothManager.getBluetoothAdapter()).thenReturn(mMockLocalAdapter);
        when(mMockBluetoothManager.getEventManager()).thenReturn(mock(BluetoothEventManager.class));
        when(mMockBluetoothManager.getProfileManager())
                .thenReturn(mock(LocalBluetoothProfileManager.class));
        mMockDumpManager = mock(DumpManager.class);
        mBackgroundExecutor = new FakeExecutor(new FakeSystemClock());

        BluetoothRepository bluetoothRepository =
                new FakeBluetoothRepository(mMockBluetoothManager);

        mBluetoothControllerImpl = new BluetoothControllerImpl(
                mContext,
                mUserTracker,
                mMockDumpManager,
                mock(BluetoothLogger.class),
                bluetoothRepository,
                mBackgroundExecutor,
                mTestableLooper.getLooper(),
                mMockBluetoothManager,
                mMockAdapter);
    }
    @Test
    public void testNoConnectionWithDevices() {
        CachedBluetoothDevice device = mock(CachedBluetoothDevice.class);
        when(device.isConnected()).thenReturn(true);
        when(device.getMaxConnectionState()).thenReturn(BluetoothProfile.STATE_CONNECTED);
        when(device.getDevice()).thenReturn(mock(BluetoothDevice.class));

        mDevices.add(device);
        when(mMockLocalAdapter.getConnectionState())
                .thenReturn(BluetoothAdapter.STATE_DISCONNECTED);

        mBluetoothControllerImpl.onConnectionStateChanged(null,
                BluetoothAdapter.STATE_DISCONNECTED);

        assertTrue(mBluetoothControllerImpl.isBluetoothConnected());
    }

    @Test
    public void testOnServiceConnected_updatesConnectionState() {
        when(mMockLocalAdapter.getConnectionState()).thenReturn(BluetoothAdapter.STATE_CONNECTING);

        mBluetoothControllerImpl.onServiceConnected();

        assertTrue(mBluetoothControllerImpl.isBluetoothConnecting());
        assertFalse(mBluetoothControllerImpl.isBluetoothConnected());
    }

    @Test
    public void getConnectedDevices_onlyReturnsConnected() {
        CachedBluetoothDevice device1Disconnected = mock(CachedBluetoothDevice.class);
        when(device1Disconnected.isConnected()).thenReturn(false);
        when(device1Disconnected.getDevice()).thenReturn(mock(BluetoothDevice.class));
        mDevices.add(device1Disconnected);

        CachedBluetoothDevice device2Connected = mock(CachedBluetoothDevice.class);
        when(device2Connected.isConnected()).thenReturn(true);
        when(device2Connected.getDevice()).thenReturn(mock(BluetoothDevice.class));
        mDevices.add(device2Connected);

        mBluetoothControllerImpl.onDeviceAdded(device1Disconnected);
        mBluetoothControllerImpl.onDeviceAdded(device2Connected);

        assertThat(mBluetoothControllerImpl.getConnectedDevices()).hasSize(1);
        assertThat(mBluetoothControllerImpl.getConnectedDevices().get(0))
                .isEqualTo(device2Connected);
    }

    @Test
    @EnableFlags(FLAG_ENABLE_HIDE_EXCLUSIVELY_MANAGED_BLUETOOTH_DEVICE)
    public void getConnectedDevice_exclusivelyManagedDevice_doNotReturn()
            throws PackageManager.NameNotFoundException {
        CachedBluetoothDevice cachedDevice = mock(CachedBluetoothDevice.class);
        when(cachedDevice.isConnected()).thenReturn(true);
        BluetoothDevice device = mock(BluetoothDevice.class);
        when(device.getMetadata(BluetoothDevice.METADATA_EXCLUSIVE_MANAGER)).thenReturn(
                TEST_EXCLUSIVE_MANAGER.getBytes());
        when(cachedDevice.getDevice()).thenReturn(device);
        doReturn(new ApplicationInfo()).when(mPackageManager).getApplicationInfo(
                TEST_EXCLUSIVE_MANAGER, 0);

        mDevices.add(cachedDevice);
        mBluetoothControllerImpl.onDeviceAdded(cachedDevice);

        assertThat(mBluetoothControllerImpl.getConnectedDevices()).isEmpty();
    }

    @Test
    @DisableFlags(FLAG_ENABLE_HIDE_EXCLUSIVELY_MANAGED_BLUETOOTH_DEVICE)
    public void getConnectedDevice_exclusivelyManagedDevice_returnsConnected()
            throws PackageManager.NameNotFoundException {
        CachedBluetoothDevice cachedDevice = mock(CachedBluetoothDevice.class);
        when(cachedDevice.isConnected()).thenReturn(true);
        BluetoothDevice device = mock(BluetoothDevice.class);
        when(device.getMetadata(BluetoothDevice.METADATA_EXCLUSIVE_MANAGER)).thenReturn(
                TEST_EXCLUSIVE_MANAGER.getBytes());
        when(cachedDevice.getDevice()).thenReturn(device);
        doReturn(new ApplicationInfo()).when(mPackageManager).getApplicationInfo(
                TEST_EXCLUSIVE_MANAGER, 0);

        mDevices.add(cachedDevice);
        mBluetoothControllerImpl.onDeviceAdded(cachedDevice);

        assertThat(mBluetoothControllerImpl.getConnectedDevices()).hasSize(1);
        assertThat(mBluetoothControllerImpl.getConnectedDevices().get(0))
                .isEqualTo(cachedDevice);
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
    public void testOnACLConnectionStateChange_updatesBluetoothStateOnConnection() {
        BluetoothController.Callback callback = mock(BluetoothController.Callback.class);
        mBluetoothControllerImpl.addCallback(callback);

        assertFalse(mBluetoothControllerImpl.isBluetoothConnected());
        CachedBluetoothDevice device = mock(CachedBluetoothDevice.class);
        when(device.getDevice()).thenReturn(mock(BluetoothDevice.class));
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

        CachedBluetoothDevice device = createBluetoothDevice(
                BluetoothProfile.HEADSET, /* isConnected= */ true, /* isActive= */ true);

        mBluetoothControllerImpl.onAclConnectionStateChanged(device,
                BluetoothProfile.STATE_CONNECTED);
        mBluetoothControllerImpl.onActiveDeviceChanged(device, BluetoothProfile.HEADSET);
        mBackgroundExecutor.runAllReady();

        assertTrue(mBluetoothControllerImpl.isBluetoothAudioActive());
        assertTrue(mBluetoothControllerImpl.isBluetoothAudioProfileOnly());
    }

    @Test
    public void isBluetoothAudioActive_headsetIsActive_true() {
        CachedBluetoothDevice device = createBluetoothDevice(
                BluetoothProfile.HEADSET, /* isConnected= */ true, /* isActive= */ true);

        mBluetoothControllerImpl.onActiveDeviceChanged(device, BluetoothProfile.HEADSET);

        assertThat(mBluetoothControllerImpl.isBluetoothAudioActive()).isTrue();
    }

    @Test
    public void isBluetoothAudioActive_a2dpIsActive_true() {
        CachedBluetoothDevice device = createBluetoothDevice(
                BluetoothProfile.A2DP, /* isConnected= */ true, /* isActive= */ true);

        mBluetoothControllerImpl.onActiveDeviceChanged(device, BluetoothProfile.A2DP);

        assertThat(mBluetoothControllerImpl.isBluetoothAudioActive()).isTrue();
    }

    @Test
    public void isBluetoothAudioActive_hearingAidIsActive_true() {
        CachedBluetoothDevice device = createBluetoothDevice(
                BluetoothProfile.HEARING_AID, /* isConnected= */ true, /* isActive= */ true);

        mBluetoothControllerImpl.onActiveDeviceChanged(device, BluetoothProfile.HEARING_AID);

        assertThat(mBluetoothControllerImpl.isBluetoothAudioActive()).isTrue();
    }

    @Test
    public void isBluetoothAudioActive_leAudioIsActive_true() {
        CachedBluetoothDevice device = createBluetoothDevice(
                BluetoothProfile.LE_AUDIO, /* isConnected= */ true, /* isActive= */ true);

        mBluetoothControllerImpl.onActiveDeviceChanged(device, BluetoothProfile.LE_AUDIO);

        assertThat(mBluetoothControllerImpl.isBluetoothAudioActive()).isTrue();
    }

    @Test
    public void isBluetoothAudioActive_otherProfile_false() {
        CachedBluetoothDevice device = createBluetoothDevice(
                BluetoothProfile.PAN, /* isConnected= */ true, /* isActive= */ true);

        mBluetoothControllerImpl.onActiveDeviceChanged(device, BluetoothProfile.PAN);

        assertThat(mBluetoothControllerImpl.isBluetoothAudioActive()).isFalse();
    }

    @Test
    public void isBluetoothAudioActive_leAudio_butNotActive_false() {
        CachedBluetoothDevice device = createBluetoothDevice(
                BluetoothProfile.LE_AUDIO, /* isConnected= */ true, /* isActive= */ false);

        mBluetoothControllerImpl.onActiveDeviceChanged(device, BluetoothProfile.LE_AUDIO);

        assertThat(mBluetoothControllerImpl.isBluetoothAudioActive()).isFalse();
    }

    @Test
    public void isBluetoothAudioProfileOnly_noneConnected_false() {
        CachedBluetoothDevice device1 = createBluetoothDevice(
                BluetoothProfile.LE_AUDIO, /* isConnected= */ false, /* isActive= */ false);
        CachedBluetoothDevice device2 = createBluetoothDevice(
                BluetoothProfile.HEADSET, /* isConnected= */ false, /* isActive= */ false);

        mBluetoothControllerImpl.onDeviceAdded(device1);
        mBluetoothControllerImpl.onDeviceAdded(device2);

        assertThat(mBluetoothControllerImpl.isBluetoothAudioProfileOnly()).isFalse();
    }

    /** Regression test for b/278982782. */
    @Test
    public void isBluetoothAudioProfileOnly_onlyLeAudioConnected_true() {
        CachedBluetoothDevice device = createBluetoothDevice(
                BluetoothProfile.LE_AUDIO, /* isConnected= */ true, /* isActive= */ false);

        mBluetoothControllerImpl.onDeviceAdded(device);
        mBackgroundExecutor.runAllReady();

        assertThat(mBluetoothControllerImpl.isBluetoothAudioProfileOnly()).isTrue();
    }

    @Test
    public void isBluetoothAudioProfileOnly_onlyHeadsetConnected_true() {
        CachedBluetoothDevice device = createBluetoothDevice(
                BluetoothProfile.HEADSET, /* isConnected= */ true, /* isActive= */ false);

        mBluetoothControllerImpl.onDeviceAdded(device);
        mBackgroundExecutor.runAllReady();

        assertThat(mBluetoothControllerImpl.isBluetoothAudioProfileOnly()).isTrue();
    }

    @Test
    public void isBluetoothAudioProfileOnly_onlyA2dpConnected_true() {
        CachedBluetoothDevice device = createBluetoothDevice(
                BluetoothProfile.A2DP, /* isConnected= */ true, /* isActive= */ false);

        mBluetoothControllerImpl.onDeviceAdded(device);
        mBackgroundExecutor.runAllReady();

        assertThat(mBluetoothControllerImpl.isBluetoothAudioProfileOnly()).isTrue();
    }

    @Test
    public void isBluetoothAudioProfileOnly_onlyHearingAidConnected_true() {
        CachedBluetoothDevice device = createBluetoothDevice(
                BluetoothProfile.HEARING_AID, /* isConnected= */ true, /* isActive= */ false);

        mBluetoothControllerImpl.onDeviceAdded(device);
        mBackgroundExecutor.runAllReady();

        assertThat(mBluetoothControllerImpl.isBluetoothAudioProfileOnly()).isTrue();
    }

    @Test
    public void isBluetoothAudioProfileOnly_multipleAudioOnlyProfilesConnected_true() {
        CachedBluetoothDevice device1 = createBluetoothDevice(
                BluetoothProfile.LE_AUDIO, /* isConnected= */ true, /* isActive= */ false);
        CachedBluetoothDevice device2 = createBluetoothDevice(
                BluetoothProfile.A2DP, /* isConnected= */ true, /* isActive= */ false);
        CachedBluetoothDevice device3 = createBluetoothDevice(
                BluetoothProfile.HEADSET, /* isConnected= */ true, /* isActive= */ false);

        mBluetoothControllerImpl.onDeviceAdded(device1);
        mBluetoothControllerImpl.onDeviceAdded(device2);
        mBluetoothControllerImpl.onDeviceAdded(device3);

        mBackgroundExecutor.runAllReady();

        assertThat(mBluetoothControllerImpl.isBluetoothAudioProfileOnly()).isTrue();
    }

    @Test
    public void isBluetoothAudioProfileOnly_leAudioAndOtherProfileConnected_false() {
        CachedBluetoothDevice device1 = createBluetoothDevice(
                BluetoothProfile.LE_AUDIO, /* isConnected= */ true, /* isActive= */ false);
        CachedBluetoothDevice device2 = createBluetoothDevice(
                BluetoothProfile.PAN, /* isConnected= */ true, /* isActive= */ false);

        mBluetoothControllerImpl.onDeviceAdded(device1);
        mBluetoothControllerImpl.onDeviceAdded(device2);
        mBackgroundExecutor.runAllReady();

        assertThat(mBluetoothControllerImpl.isBluetoothAudioProfileOnly()).isFalse();
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

    private CachedBluetoothDevice createBluetoothDevice(
            int profile, boolean isConnected, boolean isActive) {
        CachedBluetoothDevice device = mock(CachedBluetoothDevice.class);
        when(device.getDevice()).thenReturn(mock(BluetoothDevice.class));
        mDevices.add(device);
        when(device.isActiveDevice(profile)).thenReturn(isActive);

        List<LocalBluetoothProfile> localBluetoothProfiles = new ArrayList<>();
        LocalBluetoothProfile localBluetoothProfile = mock(LocalBluetoothProfile.class);
        localBluetoothProfiles.add(localBluetoothProfile);
        when(device.getProfiles()).thenReturn(localBluetoothProfiles);

        when(localBluetoothProfile.getProfileId()).thenReturn(profile);
        when(device.isConnectedProfile(localBluetoothProfile)).thenReturn(isConnected);

        return device;
    }
}
