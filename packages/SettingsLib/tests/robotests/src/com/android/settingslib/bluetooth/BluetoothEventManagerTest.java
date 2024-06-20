/*
 * Copyright 2018 The Android Open Source Project
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
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothHeadset;
import android.bluetooth.BluetoothProfile;
import android.bluetooth.BluetoothStatusCodes;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.UserHandle;
import android.platform.test.flag.junit.SetFlagsRule;
import android.telephony.TelephonyManager;

import com.android.settingslib.R;
import com.android.settingslib.flags.Flags;
import com.android.settingslib.testutils.shadow.ShadowBluetoothAdapter;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.shadow.api.Shadow;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

@RunWith(RobolectricTestRunner.class)
@Config(shadows = {ShadowBluetoothAdapter.class})
public class BluetoothEventManagerTest {
    @Rule
    public final SetFlagsRule mSetFlagsRule = new SetFlagsRule();

    private static final String DEVICE_NAME = "test_device_name";

    @Mock
    private LocalBluetoothAdapter mLocalAdapter;
    @Mock
    private LocalBluetoothManager mBtManager;
    @Mock
    private CachedBluetoothDeviceManager mCachedDeviceManager;
    @Mock
    private BluetoothCallback mBluetoothCallback;
    @Mock
    private CachedBluetoothDevice mCachedBluetoothDevice;
    @Mock
    private BluetoothDevice mBluetoothDevice;
    @Mock
    private HeadsetProfile mHfpProfile;
    @Mock
    private A2dpProfile mA2dpProfile;
    @Mock
    private HearingAidProfile mHearingAidProfile;
    @Mock
    private LeAudioProfile mLeAudioProfile;
    @Mock
    private BluetoothDevice mDevice1;
    @Mock
    private BluetoothDevice mDevice2;
    @Mock
    private BluetoothDevice mDevice3;
    @Mock
    private LocalBluetoothProfileManager mLocalProfileManager;
    @Mock
    private BluetoothUtils.ErrorListener mErrorListener;

    private Context mContext;
    private Intent mIntent;
    private BluetoothEventManager mBluetoothEventManager;
    private CachedBluetoothDevice mCachedDevice1;
    private CachedBluetoothDevice mCachedDevice2;
    private CachedBluetoothDevice mCachedDevice3;

    @Before
    public void setUp() {
        MockitoAnnotations.initMocks(this);
        mContext = RuntimeEnvironment.application;

        mBluetoothEventManager =
                new BluetoothEventManager(
                        mLocalAdapter,
                        mBtManager,
                        mCachedDeviceManager,
                        mContext,
                        /* handler= */ null,
                        /* userHandle= */ null);
        when(mBtManager.getProfileManager()).thenReturn(mLocalProfileManager);
        when(mCachedDeviceManager.findDevice(mBluetoothDevice)).thenReturn(mCachedBluetoothDevice);
        when(mHfpProfile.isProfileReady()).thenReturn(true);
        when(mA2dpProfile.isProfileReady()).thenReturn(true);
        when(mHearingAidProfile.isProfileReady()).thenReturn(true);
        when(mLeAudioProfile.isProfileReady()).thenReturn(true);
        mCachedDevice1 = new CachedBluetoothDevice(mContext, mLocalProfileManager, mDevice1);
        mCachedDevice2 = new CachedBluetoothDevice(mContext, mLocalProfileManager, mDevice2);
        mCachedDevice3 = new CachedBluetoothDevice(mContext, mLocalProfileManager, mDevice3);
        BluetoothUtils.setErrorListener(mErrorListener);
    }

    @Test
    public void ifUserHandleIsNull_registerReceiverIsCalled() {
        Context mockContext = mock(Context.class);
        BluetoothEventManager eventManager =
                new BluetoothEventManager(
                        mLocalAdapter,
                        mBtManager,
                        mCachedDeviceManager,
                        mockContext,
                        /* handler= */ null,
                        /* userHandle= */ null);

        verify(mockContext).registerReceiver(any(BroadcastReceiver.class), any(IntentFilter.class),
                eq(null), eq(null), eq(Context.RECEIVER_EXPORTED));
    }

    @Test
    public void ifUserHandleSpecified_registerReceiverAsUserIsCalled() {
        Context mockContext = mock(Context.class);
        BluetoothEventManager eventManager =
                new BluetoothEventManager(
                        mLocalAdapter,
                        mBtManager,
                        mCachedDeviceManager,
                        mockContext,
                        /* handler= */ null,
                        UserHandle.ALL);

        verify(mockContext).registerReceiverAsUser(any(BroadcastReceiver.class), eq(UserHandle.ALL),
                any(IntentFilter.class), eq(null), eq(null), eq(Context.RECEIVER_EXPORTED));
    }

    /**
     * Intent ACTION_AUDIO_STATE_CHANGED should dispatch to callback.
     */
    @Test
    public void intentWithExtraState_audioStateChangedShouldDispatchToRegisterCallback() {
        mBluetoothEventManager.registerCallback(mBluetoothCallback);
        mIntent = new Intent(BluetoothHeadset.ACTION_AUDIO_STATE_CHANGED);

        mContext.sendBroadcast(mIntent);

        verify(mBluetoothCallback).onAudioModeChanged();
    }

    /**
     * Intent ACTION_PHONE_STATE_CHANGED should dispatch to callback.
     */
    @Test
    public void intentWithExtraState_phoneStateChangedShouldDispatchToRegisterCallback() {
        mBluetoothEventManager.registerCallback(mBluetoothCallback);
        mIntent = new Intent(TelephonyManager.ACTION_PHONE_STATE_CHANGED);

        mContext.sendBroadcast(mIntent);

        verify(mBluetoothCallback).onAudioModeChanged();
    }

    /**
     * dispatchProfileConnectionStateChanged should dispatch to onProfileConnectionStateChanged
     * callback.
     */
    @Test
    public void dispatchProfileConnectionStateChanged_registerCallback_shouldDispatchCallback() {
        mBluetoothEventManager.registerCallback(mBluetoothCallback);

        mBluetoothEventManager.dispatchProfileConnectionStateChanged(mCachedBluetoothDevice,
                BluetoothProfile.STATE_CONNECTED, BluetoothProfile.A2DP);

        verify(mBluetoothCallback).onProfileConnectionStateChanged(mCachedBluetoothDevice,
                BluetoothProfile.STATE_CONNECTED, BluetoothProfile.A2DP);
    }

    /**
     * dispatchProfileConnectionStateChanged should not call {@link
     * LocalBluetoothLeBroadcast}#updateFallbackActiveDeviceIfNeeded when audio sharing flag is off.
     */
    @Test
    public void dispatchProfileConnectionStateChanged_flagOff_noUpdateFallbackDevice() {
        ShadowBluetoothAdapter shadowBluetoothAdapter =
                Shadow.extract(BluetoothAdapter.getDefaultAdapter());
        shadowBluetoothAdapter.setIsLeAudioBroadcastSourceSupported(
                BluetoothStatusCodes.FEATURE_SUPPORTED);
        shadowBluetoothAdapter.setIsLeAudioBroadcastAssistantSupported(
                BluetoothStatusCodes.FEATURE_SUPPORTED);
        mSetFlagsRule.disableFlags(Flags.FLAG_ENABLE_LE_AUDIO_SHARING);
        LocalBluetoothLeBroadcast broadcast = mock(LocalBluetoothLeBroadcast.class);
        when(broadcast.isProfileReady()).thenReturn(true);
        LocalBluetoothLeBroadcastAssistant assistant =
                mock(LocalBluetoothLeBroadcastAssistant.class);
        when(assistant.isProfileReady()).thenReturn(true);
        LocalBluetoothProfileManager profileManager = mock(LocalBluetoothProfileManager.class);
        when(profileManager.getLeAudioBroadcastProfile()).thenReturn(broadcast);
        when(profileManager.getLeAudioBroadcastAssistantProfile()).thenReturn(assistant);
        when(mBtManager.getProfileManager()).thenReturn(profileManager);
        mBluetoothEventManager.dispatchProfileConnectionStateChanged(
                mCachedBluetoothDevice,
                BluetoothProfile.STATE_DISCONNECTED,
                BluetoothProfile.LE_AUDIO_BROADCAST_ASSISTANT);

        verify(broadcast, times(0)).updateFallbackActiveDeviceIfNeeded();
    }

    /**
     * dispatchProfileConnectionStateChanged should not call {@link
     * LocalBluetoothLeBroadcast}#updateFallbackActiveDeviceIfNeeded when the device does not
     * support audio sharing.
     */
    @Test
    public void dispatchProfileConnectionStateChanged_notSupport_noUpdateFallbackDevice() {
        ShadowBluetoothAdapter shadowBluetoothAdapter =
                Shadow.extract(BluetoothAdapter.getDefaultAdapter());
        shadowBluetoothAdapter.setIsLeAudioBroadcastSourceSupported(
                BluetoothStatusCodes.FEATURE_NOT_SUPPORTED);
        shadowBluetoothAdapter.setIsLeAudioBroadcastAssistantSupported(
                BluetoothStatusCodes.FEATURE_SUPPORTED);
        mSetFlagsRule.enableFlags(Flags.FLAG_ENABLE_LE_AUDIO_SHARING);
        LocalBluetoothLeBroadcast broadcast = mock(LocalBluetoothLeBroadcast.class);
        when(broadcast.isProfileReady()).thenReturn(true);
        LocalBluetoothLeBroadcastAssistant assistant =
                mock(LocalBluetoothLeBroadcastAssistant.class);
        when(assistant.isProfileReady()).thenReturn(true);
        LocalBluetoothProfileManager profileManager = mock(LocalBluetoothProfileManager.class);
        when(profileManager.getLeAudioBroadcastProfile()).thenReturn(broadcast);
        when(profileManager.getLeAudioBroadcastAssistantProfile()).thenReturn(assistant);
        when(mBtManager.getProfileManager()).thenReturn(profileManager);
        mBluetoothEventManager.dispatchProfileConnectionStateChanged(
                mCachedBluetoothDevice,
                BluetoothProfile.STATE_DISCONNECTED,
                BluetoothProfile.LE_AUDIO_BROADCAST_ASSISTANT);

        verify(broadcast, times(0)).updateFallbackActiveDeviceIfNeeded();
    }

    /**
     * dispatchProfileConnectionStateChanged should not call {@link
     * LocalBluetoothLeBroadcast}#updateFallbackActiveDeviceIfNeeded when audio sharing profile is
     * not ready.
     */
    @Test
    public void dispatchProfileConnectionStateChanged_profileNotReady_noUpdateFallbackDevice() {
        ShadowBluetoothAdapter shadowBluetoothAdapter =
                Shadow.extract(BluetoothAdapter.getDefaultAdapter());
        shadowBluetoothAdapter.setIsLeAudioBroadcastSourceSupported(
                BluetoothStatusCodes.FEATURE_SUPPORTED);
        shadowBluetoothAdapter.setIsLeAudioBroadcastAssistantSupported(
                BluetoothStatusCodes.FEATURE_SUPPORTED);
        mSetFlagsRule.enableFlags(Flags.FLAG_ENABLE_LE_AUDIO_SHARING);
        LocalBluetoothLeBroadcast broadcast = mock(LocalBluetoothLeBroadcast.class);
        when(broadcast.isProfileReady()).thenReturn(false);
        LocalBluetoothLeBroadcastAssistant assistant =
                mock(LocalBluetoothLeBroadcastAssistant.class);
        when(assistant.isProfileReady()).thenReturn(true);
        LocalBluetoothProfileManager profileManager = mock(LocalBluetoothProfileManager.class);
        when(profileManager.getLeAudioBroadcastProfile()).thenReturn(broadcast);
        when(profileManager.getLeAudioBroadcastAssistantProfile()).thenReturn(assistant);
        when(mBtManager.getProfileManager()).thenReturn(profileManager);
        mBluetoothEventManager.dispatchProfileConnectionStateChanged(
                mCachedBluetoothDevice,
                BluetoothProfile.STATE_DISCONNECTED,
                BluetoothProfile.LE_AUDIO_BROADCAST_ASSISTANT);

        verify(broadcast, times(0)).updateFallbackActiveDeviceIfNeeded();
    }

    /**
     * dispatchProfileConnectionStateChanged should not call {@link
     * LocalBluetoothLeBroadcast}#updateFallbackActiveDeviceIfNeeded when triggered for profile
     * other than LE_AUDIO_BROADCAST_ASSISTANT or state other than STATE_DISCONNECTED.
     */
    @Test
    public void dispatchProfileConnectionStateChanged_notAssistantProfile_noUpdateFallbackDevice() {
        ShadowBluetoothAdapter shadowBluetoothAdapter =
                Shadow.extract(BluetoothAdapter.getDefaultAdapter());
        shadowBluetoothAdapter.setIsLeAudioBroadcastSourceSupported(
                BluetoothStatusCodes.FEATURE_SUPPORTED);
        shadowBluetoothAdapter.setIsLeAudioBroadcastAssistantSupported(
                BluetoothStatusCodes.FEATURE_SUPPORTED);
        mSetFlagsRule.enableFlags(Flags.FLAG_ENABLE_LE_AUDIO_SHARING);
        LocalBluetoothLeBroadcast broadcast = mock(LocalBluetoothLeBroadcast.class);
        when(broadcast.isProfileReady()).thenReturn(true);
        LocalBluetoothLeBroadcastAssistant assistant =
                mock(LocalBluetoothLeBroadcastAssistant.class);
        when(assistant.isProfileReady()).thenReturn(true);
        LocalBluetoothProfileManager profileManager = mock(LocalBluetoothProfileManager.class);
        when(profileManager.getLeAudioBroadcastProfile()).thenReturn(broadcast);
        when(profileManager.getLeAudioBroadcastAssistantProfile()).thenReturn(assistant);
        when(mBtManager.getProfileManager()).thenReturn(profileManager);
        mBluetoothEventManager.dispatchProfileConnectionStateChanged(
                mCachedBluetoothDevice,
                BluetoothProfile.STATE_DISCONNECTED,
                BluetoothProfile.LE_AUDIO);

        verify(broadcast, times(0)).updateFallbackActiveDeviceIfNeeded();
    }

    /**
     * dispatchProfileConnectionStateChanged should call {@link
     * LocalBluetoothLeBroadcast}#updateFallbackActiveDeviceIfNeeded when assistant profile is
     * disconnected and audio sharing is enabled.
     */
    @Test
    public void dispatchProfileConnectionStateChanged_audioSharing_updateFallbackDevice() {
        ShadowBluetoothAdapter shadowBluetoothAdapter =
                Shadow.extract(BluetoothAdapter.getDefaultAdapter());
        shadowBluetoothAdapter.setIsLeAudioBroadcastSourceSupported(
                BluetoothStatusCodes.FEATURE_SUPPORTED);
        shadowBluetoothAdapter.setIsLeAudioBroadcastAssistantSupported(
                BluetoothStatusCodes.FEATURE_SUPPORTED);
        mSetFlagsRule.enableFlags(Flags.FLAG_ENABLE_LE_AUDIO_SHARING);
        LocalBluetoothLeBroadcast broadcast = mock(LocalBluetoothLeBroadcast.class);
        when(broadcast.isProfileReady()).thenReturn(true);
        LocalBluetoothLeBroadcastAssistant assistant =
                mock(LocalBluetoothLeBroadcastAssistant.class);
        when(assistant.isProfileReady()).thenReturn(true);
        LocalBluetoothProfileManager profileManager = mock(LocalBluetoothProfileManager.class);
        when(profileManager.getLeAudioBroadcastProfile()).thenReturn(broadcast);
        when(profileManager.getLeAudioBroadcastAssistantProfile()).thenReturn(assistant);
        when(mBtManager.getProfileManager()).thenReturn(profileManager);
        mBluetoothEventManager.dispatchProfileConnectionStateChanged(
                mCachedBluetoothDevice,
                BluetoothProfile.STATE_DISCONNECTED,
                BluetoothProfile.LE_AUDIO_BROADCAST_ASSISTANT);

        verify(broadcast).updateFallbackActiveDeviceIfNeeded();
    }

    @Test
    public void dispatchAclConnectionStateChanged_aclDisconnected_shouldDispatchCallback() {
        mBluetoothEventManager.registerCallback(mBluetoothCallback);
        mIntent = new Intent(BluetoothDevice.ACTION_ACL_DISCONNECTED);
        mIntent.putExtra(BluetoothDevice.EXTRA_DEVICE, mBluetoothDevice);

        mContext.sendBroadcast(mIntent);

        verify(mBluetoothCallback).onAclConnectionStateChanged(mCachedBluetoothDevice,
                BluetoothAdapter.STATE_DISCONNECTED);
    }

    @Test
    public void dispatchAclConnectionStateChanged_aclConnected_shouldDispatchCallback() {
        mBluetoothEventManager.registerCallback(mBluetoothCallback);
        mIntent = new Intent(BluetoothDevice.ACTION_ACL_CONNECTED);
        mIntent.putExtra(BluetoothDevice.EXTRA_DEVICE, mBluetoothDevice);

        mContext.sendBroadcast(mIntent);

        verify(mBluetoothCallback).onAclConnectionStateChanged(mCachedBluetoothDevice,
                BluetoothAdapter.STATE_CONNECTED);
    }

    @Test
    public void dispatchAclConnectionStateChanged_aclDisconnected_shouldNotCallbackSubDevice() {
        when(mCachedDeviceManager.isSubDevice(mBluetoothDevice)).thenReturn(true);
        mBluetoothEventManager.registerCallback(mBluetoothCallback);
        mIntent = new Intent(BluetoothDevice.ACTION_ACL_DISCONNECTED);
        mIntent.putExtra(BluetoothDevice.EXTRA_DEVICE, mBluetoothDevice);

        mContext.sendBroadcast(mIntent);

        verify(mBluetoothCallback, never()).onAclConnectionStateChanged(mCachedBluetoothDevice,
                BluetoothAdapter.STATE_DISCONNECTED);
    }

    @Test
    public void dispatchAclConnectionStateChanged_aclConnected_shouldNotCallbackSubDevice() {
        when(mCachedDeviceManager.isSubDevice(mBluetoothDevice)).thenReturn(true);
        mBluetoothEventManager.registerCallback(mBluetoothCallback);
        mIntent = new Intent(BluetoothDevice.ACTION_ACL_CONNECTED);
        mIntent.putExtra(BluetoothDevice.EXTRA_DEVICE, mBluetoothDevice);

        mContext.sendBroadcast(mIntent);

        verify(mBluetoothCallback, never()).onAclConnectionStateChanged(mCachedBluetoothDevice,
                BluetoothAdapter.STATE_CONNECTED);
    }

    @Test
    public void dispatchAclConnectionStateChanged_findDeviceReturnNull_shouldNotDispatchCallback() {
        when(mCachedDeviceManager.findDevice(mBluetoothDevice)).thenReturn(null);
        mBluetoothEventManager.registerCallback(mBluetoothCallback);
        mIntent = new Intent(BluetoothDevice.ACTION_ACL_CONNECTED);
        mIntent.putExtra(BluetoothDevice.EXTRA_DEVICE, mBluetoothDevice);

        mContext.sendBroadcast(mIntent);

        verify(mBluetoothCallback, never()).onAclConnectionStateChanged(mCachedBluetoothDevice,
                BluetoothAdapter.STATE_CONNECTED);
    }

    /**
     * Test to verify onActiveDeviceChanged().
     */
    @Test
    public void dispatchActiveDeviceChanged_connectedDevices_activeDeviceChanged() {
        final List<CachedBluetoothDevice> cachedDevices = new ArrayList<>();
        cachedDevices.add(mCachedDevice1);
        cachedDevices.add(mCachedDevice2);

        when(mDevice1.getBondState()).thenReturn(BluetoothDevice.BOND_BONDED);
        when(mDevice2.getBondState()).thenReturn(BluetoothDevice.BOND_BONDED);
        when(mCachedDeviceManager.getCachedDevicesCopy()).thenReturn(cachedDevices);

        // Connect both devices for A2DP and HFP
        mCachedDevice1.onProfileStateChanged(mA2dpProfile, BluetoothProfile.STATE_CONNECTED);
        mCachedDevice2.onProfileStateChanged(mA2dpProfile, BluetoothProfile.STATE_CONNECTED);
        mCachedDevice1.onProfileStateChanged(mHfpProfile, BluetoothProfile.STATE_CONNECTED);
        mCachedDevice2.onProfileStateChanged(mHfpProfile, BluetoothProfile.STATE_CONNECTED);

        // Verify that both devices are connected and none is Active
        assertThat(mCachedDevice1.isActiveDevice(BluetoothProfile.A2DP)).isFalse();
        assertThat(mCachedDevice1.isActiveDevice(BluetoothProfile.HEADSET)).isFalse();
        assertThat(mCachedDevice2.isActiveDevice(BluetoothProfile.A2DP)).isFalse();
        assertThat(mCachedDevice2.isActiveDevice(BluetoothProfile.HEADSET)).isFalse();

        // The first device is active for A2DP, the second device is active for HFP
        mBluetoothEventManager.dispatchActiveDeviceChanged(mCachedDevice1, BluetoothProfile.A2DP);
        mBluetoothEventManager
                .dispatchActiveDeviceChanged(mCachedDevice2, BluetoothProfile.HEADSET);
        assertThat(mCachedDevice1.isActiveDevice(BluetoothProfile.A2DP)).isTrue();
        assertThat(mCachedDevice1.isActiveDevice(BluetoothProfile.HEADSET)).isFalse();
        assertThat(mCachedDevice2.isActiveDevice(BluetoothProfile.A2DP)).isFalse();
        assertThat(mCachedDevice2.isActiveDevice(BluetoothProfile.HEADSET)).isTrue();

        // The first device is active for A2DP and HFP
        mBluetoothEventManager
                .dispatchActiveDeviceChanged(mCachedDevice1, BluetoothProfile.HEADSET);
        assertThat(mCachedDevice1.isActiveDevice(BluetoothProfile.A2DP)).isTrue();
        assertThat(mCachedDevice1.isActiveDevice(BluetoothProfile.HEADSET)).isTrue();
        assertThat(mCachedDevice2.isActiveDevice(BluetoothProfile.A2DP)).isFalse();
        assertThat(mCachedDevice2.isActiveDevice(BluetoothProfile.HEADSET)).isFalse();

        // The second device is active for A2DP and HFP
        mBluetoothEventManager.dispatchActiveDeviceChanged(mCachedDevice2, BluetoothProfile.A2DP);
        mBluetoothEventManager
                .dispatchActiveDeviceChanged(mCachedDevice2, BluetoothProfile.HEADSET);
        assertThat(mCachedDevice1.isActiveDevice(BluetoothProfile.A2DP)).isFalse();
        assertThat(mCachedDevice1.isActiveDevice(BluetoothProfile.HEADSET)).isFalse();
        assertThat(mCachedDevice2.isActiveDevice(BluetoothProfile.A2DP)).isTrue();
        assertThat(mCachedDevice2.isActiveDevice(BluetoothProfile.HEADSET)).isTrue();

        // No active device for A2DP
        mBluetoothEventManager.dispatchActiveDeviceChanged(null, BluetoothProfile.A2DP);
        assertThat(mCachedDevice1.isActiveDevice(BluetoothProfile.A2DP)).isFalse();
        assertThat(mCachedDevice1.isActiveDevice(BluetoothProfile.HEADSET)).isFalse();
        assertThat(mCachedDevice2.isActiveDevice(BluetoothProfile.A2DP)).isFalse();
        assertThat(mCachedDevice2.isActiveDevice(BluetoothProfile.HEADSET)).isTrue();

        // No active device for HFP
        mBluetoothEventManager.dispatchActiveDeviceChanged(null, BluetoothProfile.HEADSET);
        assertThat(mCachedDevice1.isActiveDevice(BluetoothProfile.A2DP)).isFalse();
        assertThat(mCachedDevice1.isActiveDevice(BluetoothProfile.HEADSET)).isFalse();
        assertThat(mCachedDevice2.isActiveDevice(BluetoothProfile.A2DP)).isFalse();
        assertThat(mCachedDevice2.isActiveDevice(BluetoothProfile.HEADSET)).isFalse();
    }

    @Test
    public void dispatchActiveDeviceChanged_connectedMemberDevices_activeDeviceChanged() {
        final List<CachedBluetoothDevice> cachedDevices = new ArrayList<>();
        cachedDevices.add(mCachedDevice1);
        cachedDevices.add(mCachedDevice2);

        int group1 = 1;
        when(mDevice3.getAddress()).thenReturn("testAddress3");
        mCachedDevice1.setGroupId(group1);
        mCachedDevice3.setGroupId(group1);
        mCachedDevice1.addMemberDevice(mCachedDevice3);

        when(mDevice1.getBondState()).thenReturn(BluetoothDevice.BOND_BONDED);
        when(mDevice2.getBondState()).thenReturn(BluetoothDevice.BOND_BONDED);
        when(mDevice3.getBondState()).thenReturn(BluetoothDevice.BOND_BONDED);
        when(mCachedDeviceManager.getCachedDevicesCopy()).thenReturn(cachedDevices);

        // Connect device1 and device3 for LE and device2 for A2DP and HFP
        mCachedDevice1.onProfileStateChanged(mLeAudioProfile, BluetoothProfile.STATE_CONNECTED);
        mCachedDevice3.onProfileStateChanged(mLeAudioProfile, BluetoothProfile.STATE_CONNECTED);
        mCachedDevice2.onProfileStateChanged(mA2dpProfile, BluetoothProfile.STATE_CONNECTED);
        mCachedDevice2.onProfileStateChanged(mHfpProfile, BluetoothProfile.STATE_CONNECTED);

        // Verify that both devices are connected and none is Active
        assertThat(mCachedDevice1.isActiveDevice(BluetoothProfile.LE_AUDIO)).isFalse();
        assertThat(mCachedDevice2.isActiveDevice(BluetoothProfile.A2DP)).isFalse();
        assertThat(mCachedDevice2.isActiveDevice(BluetoothProfile.HEADSET)).isFalse();
        assertThat(mCachedDevice3.isActiveDevice(BluetoothProfile.LE_AUDIO)).isFalse();

        // The member device is active.
        mBluetoothEventManager.dispatchActiveDeviceChanged(mCachedDevice3,
                BluetoothProfile.LE_AUDIO);

        // The main device is active since the member is active.
        assertThat(mCachedDevice1.isActiveDevice(BluetoothProfile.LE_AUDIO)).isTrue();
    }

    /**
     * Test to verify onActiveDeviceChanged() with A2DP and Hearing Aid.
     */
    @Test
    public void dispatchActiveDeviceChanged_withA2dpAndHearingAid() {
        final List<CachedBluetoothDevice> cachedDevices = new ArrayList<>();
        cachedDevices.add(mCachedDevice1);
        cachedDevices.add(mCachedDevice2);

        when(mDevice1.getBondState()).thenReturn(BluetoothDevice.BOND_BONDED);
        when(mDevice2.getBondState()).thenReturn(BluetoothDevice.BOND_BONDED);
        when(mCachedDeviceManager.getCachedDevicesCopy()).thenReturn(cachedDevices);

        // Connect device1 for A2DP and HFP and device2 for Hearing Aid
        mCachedDevice1.onProfileStateChanged(mA2dpProfile, BluetoothProfile.STATE_CONNECTED);
        mCachedDevice1.onProfileStateChanged(mHfpProfile, BluetoothProfile.STATE_CONNECTED);
        mCachedDevice2.onProfileStateChanged(mHearingAidProfile, BluetoothProfile.STATE_CONNECTED);

        // Verify that both devices are connected and none is Active
        assertThat(mCachedDevice1.isActiveDevice(BluetoothProfile.A2DP)).isFalse();
        assertThat(mCachedDevice1.isActiveDevice(BluetoothProfile.HEADSET)).isFalse();
        assertThat(mCachedDevice1.isActiveDevice(BluetoothProfile.HEARING_AID)).isFalse();
        assertThat(mCachedDevice2.isActiveDevice(BluetoothProfile.A2DP)).isFalse();
        assertThat(mCachedDevice2.isActiveDevice(BluetoothProfile.HEADSET)).isFalse();
        assertThat(mCachedDevice2.isActiveDevice(BluetoothProfile.HEARING_AID)).isFalse();

        // The first device is active for A2DP and HFP
        mBluetoothEventManager.dispatchActiveDeviceChanged(mCachedDevice1, BluetoothProfile.A2DP);
        mBluetoothEventManager
                .dispatchActiveDeviceChanged(mCachedDevice1, BluetoothProfile.HEADSET);
        assertThat(mCachedDevice1.isActiveDevice(BluetoothProfile.A2DP)).isTrue();
        assertThat(mCachedDevice1.isActiveDevice(BluetoothProfile.HEADSET)).isTrue();
        assertThat(mCachedDevice1.isActiveDevice(BluetoothProfile.HEARING_AID)).isFalse();
        assertThat(mCachedDevice2.isActiveDevice(BluetoothProfile.A2DP)).isFalse();
        assertThat(mCachedDevice2.isActiveDevice(BluetoothProfile.HEADSET)).isFalse();
        assertThat(mCachedDevice2.isActiveDevice(BluetoothProfile.HEARING_AID)).isFalse();

        // The second device is active for Hearing Aid and the first device is not active
        mBluetoothEventManager.dispatchActiveDeviceChanged(null, BluetoothProfile.A2DP);
        mBluetoothEventManager.dispatchActiveDeviceChanged(null, BluetoothProfile.HEADSET);
        mBluetoothEventManager
                .dispatchActiveDeviceChanged(mCachedDevice2, BluetoothProfile.HEARING_AID);
        assertThat(mCachedDevice1.isActiveDevice(BluetoothProfile.A2DP)).isFalse();
        assertThat(mCachedDevice1.isActiveDevice(BluetoothProfile.HEADSET)).isFalse();
        assertThat(mCachedDevice1.isActiveDevice(BluetoothProfile.HEARING_AID)).isFalse();
        assertThat(mCachedDevice2.isActiveDevice(BluetoothProfile.A2DP)).isFalse();
        assertThat(mCachedDevice2.isActiveDevice(BluetoothProfile.HEADSET)).isFalse();
        assertThat(mCachedDevice2.isActiveDevice(BluetoothProfile.HEARING_AID)).isTrue();

        // No active device for Hearing Aid
        mBluetoothEventManager.dispatchActiveDeviceChanged(null, BluetoothProfile.HEARING_AID);
        assertThat(mCachedDevice1.isActiveDevice(BluetoothProfile.A2DP)).isFalse();
        assertThat(mCachedDevice1.isActiveDevice(BluetoothProfile.HEADSET)).isFalse();
        assertThat(mCachedDevice2.isActiveDevice(BluetoothProfile.A2DP)).isFalse();
        assertThat(mCachedDevice2.isActiveDevice(BluetoothProfile.A2DP)).isFalse();
        assertThat(mCachedDevice2.isActiveDevice(BluetoothProfile.HEADSET)).isFalse();
        assertThat(mCachedDevice2.isActiveDevice(BluetoothProfile.HEARING_AID)).isFalse();
    }

    @Test
    public void dispatchActiveDeviceChanged_callExpectedOnActiveDeviceChanged() {
        mBluetoothEventManager.registerCallback(mBluetoothCallback);
        when(mDevice1.getBondState()).thenReturn(BluetoothDevice.BOND_BONDED);
        when(mCachedDeviceManager.getCachedDevicesCopy()).thenReturn(
                Collections.singletonList(mCachedDevice1));

        mBluetoothEventManager.dispatchActiveDeviceChanged(mCachedDevice1,
                BluetoothProfile.HEARING_AID);

        verify(mCachedDeviceManager).onActiveDeviceChanged(mCachedDevice1);
        verify(mBluetoothCallback).onActiveDeviceChanged(mCachedDevice1,
                BluetoothProfile.HEARING_AID);
    }

    @Test
    public void dispatchActiveDeviceChanged_activeFromSubDevice_mainCachedDeviceActive() {
        CachedBluetoothDevice subDevice = new CachedBluetoothDevice(mContext, mLocalProfileManager,
                mDevice3);
        mCachedDevice1.setSubDevice(subDevice);
        when(mCachedDeviceManager.getCachedDevicesCopy()).thenReturn(
                Collections.singletonList(mCachedDevice1));
        mCachedDevice1.onProfileStateChanged(mHearingAidProfile,
                BluetoothProfile.STATE_CONNECTED);

        assertThat(mCachedDevice1.isActiveDevice(BluetoothProfile.HEARING_AID)).isFalse();
        mBluetoothEventManager.dispatchActiveDeviceChanged(subDevice, BluetoothProfile.HEARING_AID);
        assertThat(mCachedDevice1.isActiveDevice(BluetoothProfile.HEARING_AID)).isTrue();
    }

    @Test
    public void showUnbondMessage_reasonAuthTimeout_showCorrectedErrorCode() {
        mIntent = new Intent(BluetoothDevice.ACTION_BOND_STATE_CHANGED);
        mIntent.putExtra(BluetoothDevice.EXTRA_DEVICE, mBluetoothDevice);
        mIntent.putExtra(BluetoothDevice.EXTRA_BOND_STATE, BluetoothDevice.BOND_NONE);
        mIntent.putExtra(BluetoothDevice.EXTRA_UNBOND_REASON,
                BluetoothDevice.UNBOND_REASON_AUTH_TIMEOUT);
        when(mCachedDeviceManager.findDevice(mBluetoothDevice)).thenReturn(mCachedDevice1);
        when(mCachedDevice1.getName()).thenReturn(DEVICE_NAME);

        mContext.sendBroadcast(mIntent);

        verify(mErrorListener).onShowError(any(Context.class), eq(DEVICE_NAME),
                eq(R.string.bluetooth_pairing_error_message));
    }

    @Test
    public void showUnbondMessage_reasonRemoteDeviceDown_showCorrectedErrorCode() {
        mIntent = new Intent(BluetoothDevice.ACTION_BOND_STATE_CHANGED);
        mIntent.putExtra(BluetoothDevice.EXTRA_DEVICE, mBluetoothDevice);
        mIntent.putExtra(BluetoothDevice.EXTRA_BOND_STATE, BluetoothDevice.BOND_NONE);
        mIntent.putExtra(BluetoothDevice.EXTRA_UNBOND_REASON,
                BluetoothDevice.UNBOND_REASON_REMOTE_DEVICE_DOWN);
        when(mCachedDeviceManager.findDevice(mBluetoothDevice)).thenReturn(mCachedDevice1);
        when(mCachedDevice1.getName()).thenReturn(DEVICE_NAME);

        mContext.sendBroadcast(mIntent);

        verify(mErrorListener).onShowError(any(Context.class), eq(DEVICE_NAME),
                eq(R.string.bluetooth_pairing_device_down_error_message));
    }

    @Test
    public void showUnbondMessage_reasonAuthRejected_showCorrectedErrorCode() {
        mIntent = new Intent(BluetoothDevice.ACTION_BOND_STATE_CHANGED);
        mIntent.putExtra(BluetoothDevice.EXTRA_DEVICE, mBluetoothDevice);
        mIntent.putExtra(BluetoothDevice.EXTRA_BOND_STATE, BluetoothDevice.BOND_NONE);
        mIntent.putExtra(BluetoothDevice.EXTRA_UNBOND_REASON,
                BluetoothDevice.UNBOND_REASON_AUTH_REJECTED);
        when(mCachedDeviceManager.findDevice(mBluetoothDevice)).thenReturn(mCachedDevice1);
        when(mCachedDevice1.getName()).thenReturn(DEVICE_NAME);

        mContext.sendBroadcast(mIntent);

        verify(mErrorListener).onShowError(any(Context.class), eq(DEVICE_NAME),
                eq(R.string.bluetooth_pairing_rejected_error_message));
    }

    @Test
    public void showUnbondMessage_reasonAuthFailed_showCorrectedErrorCode() {
        mIntent = new Intent(BluetoothDevice.ACTION_BOND_STATE_CHANGED);
        mIntent.putExtra(BluetoothDevice.EXTRA_DEVICE, mBluetoothDevice);
        mIntent.putExtra(BluetoothDevice.EXTRA_BOND_STATE, BluetoothDevice.BOND_NONE);
        mIntent.putExtra(BluetoothDevice.EXTRA_UNBOND_REASON,
                BluetoothDevice.UNBOND_REASON_AUTH_FAILED);
        when(mCachedDeviceManager.findDevice(mBluetoothDevice)).thenReturn(mCachedDevice1);
        when(mCachedDevice1.getName()).thenReturn(DEVICE_NAME);

        mContext.sendBroadcast(mIntent);

        verify(mErrorListener).onShowError(any(Context.class), eq(DEVICE_NAME),
                eq(R.string.bluetooth_pairing_pin_error_message));
    }

    /**
     * Intent ACTION_AUTO_ON_STATE_CHANGED should dispatch to callback.
     */
    @Test
    public void intentWithExtraState_autoOnStateChangedShouldDispatchToRegisterCallback() {
        mBluetoothEventManager.registerCallback(mBluetoothCallback);
        mIntent = new Intent(BluetoothAdapter.ACTION_AUTO_ON_STATE_CHANGED);

        mContext.sendBroadcast(mIntent);

        verify(mBluetoothCallback).onAutoOnStateChanged(anyInt());
    }
}
