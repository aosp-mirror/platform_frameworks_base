/*
 * Copyright (C) 2017 The Android Open Source Project
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

import static com.android.settingslib.flags.Flags.FLAG_ENABLE_LE_AUDIO_SHARING;
import static com.android.settingslib.flags.Flags.FLAG_ENABLE_SET_PREFERRED_TRANSPORT_FOR_LE_AUDIO_DEVICE;

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothCsipSetCoordinator;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothLeAudio;
import android.bluetooth.BluetoothLeBroadcastReceiveState;
import android.bluetooth.BluetoothProfile;
import android.bluetooth.BluetoothStatusCodes;
import android.content.Context;
import android.graphics.drawable.BitmapDrawable;
import android.media.AudioManager;
import android.platform.test.flag.junit.SetFlagsRule;
import android.provider.Settings;
import android.text.Spannable;
import android.text.style.ForegroundColorSpan;
import android.util.LruCache;

import com.android.settingslib.R;
import com.android.settingslib.media.flags.Flags;
import com.android.settingslib.testutils.shadow.ShadowBluetoothAdapter;
import com.android.settingslib.widget.AdaptiveOutlineDrawable;

import com.google.common.collect.ImmutableList;

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
import java.util.List;

@RunWith(RobolectricTestRunner.class)
@Config(shadows = {ShadowBluetoothAdapter.class})
public class CachedBluetoothDeviceTest {
    private static final String DEVICE_NAME = "TestName";
    private static final String DEVICE_ALIAS = "TestAlias";
    private static final String DEVICE_ADDRESS = "AA:BB:CC:DD:EE:FF";
    private static final String DEVICE_ALIAS_NEW = "TestAliasNew";
    private static final String TWS_BATTERY_LEFT = "15";
    private static final String TWS_BATTERY_RIGHT = "25";
    private static final String TWS_LOW_BATTERY_THRESHOLD_LOW = "10";
    private static final String TWS_LOW_BATTERY_THRESHOLD_HIGH = "25";
    private static final short RSSI_1 = 10;
    private static final short RSSI_2 = 11;
    private static final boolean JUSTDISCOVERED_1 = true;
    private static final boolean JUSTDISCOVERED_2 = false;
    private static final int LOW_BATTERY_COLOR = android.R.color.holo_red_dark;
    @Mock
    private LocalBluetoothProfileManager mProfileManager;
    @Mock
    private HeadsetProfile mHfpProfile;
    @Mock
    private A2dpProfile mA2dpProfile;
    @Mock
    private PanProfile mPanProfile;
    @Mock
    private HearingAidProfile mHearingAidProfile;
    @Mock
    private HapClientProfile mHapClientProfile;
    @Mock
    private LeAudioProfile mLeAudioProfile;

    @Mock
    private HidProfile mHidProfile;
    @Mock
    private BluetoothDevice mDevice;
    @Mock
    private BluetoothDevice mSubDevice;
    @Mock
    private LocalBluetoothLeBroadcast mBroadcast;
    @Mock
    private LocalBluetoothManager mLocalBluetoothManager;
    @Mock
    private LocalBluetoothLeBroadcastAssistant mAssistant;
    @Mock
    private BluetoothLeBroadcastReceiveState mLeBroadcastReceiveState;
    private CachedBluetoothDevice mCachedDevice;
    private CachedBluetoothDevice mSubCachedDevice;
    private AudioManager mAudioManager;
    private Context mContext;
    private int mBatteryLevel = BluetoothDevice.BATTERY_LEVEL_UNKNOWN;
    private ShadowBluetoothAdapter mShadowBluetoothAdapter;

    @Rule
    public final SetFlagsRule mSetFlagsRule = new SetFlagsRule();

    @Before
    public void setUp() {
        MockitoAnnotations.initMocks(this);
        mSetFlagsRule.enableFlags(Flags.FLAG_ENABLE_TV_MEDIA_OUTPUT_DIALOG);
        mSetFlagsRule.enableFlags(FLAG_ENABLE_SET_PREFERRED_TRANSPORT_FOR_LE_AUDIO_DEVICE);
        mSetFlagsRule.enableFlags(FLAG_ENABLE_LE_AUDIO_SHARING);
        mContext = RuntimeEnvironment.application;
        mAudioManager = mContext.getSystemService(AudioManager.class);
        mShadowBluetoothAdapter = Shadow.extract(BluetoothAdapter.getDefaultAdapter());
        mShadowBluetoothAdapter.setIsLeAudioBroadcastSourceSupported(
                BluetoothStatusCodes.FEATURE_SUPPORTED);
        mShadowBluetoothAdapter.setIsLeAudioBroadcastAssistantSupported(
                BluetoothStatusCodes.FEATURE_SUPPORTED);
        when(mDevice.getAddress()).thenReturn(DEVICE_ADDRESS);
        when(mHfpProfile.isProfileReady()).thenReturn(true);
        when(mHfpProfile.getProfileId()).thenReturn(BluetoothProfile.HEADSET);
        when(mA2dpProfile.isProfileReady()).thenReturn(true);
        when(mA2dpProfile.getProfileId()).thenReturn(BluetoothProfile.A2DP);
        when(mPanProfile.isProfileReady()).thenReturn(true);
        when(mPanProfile.getProfileId()).thenReturn(BluetoothProfile.PAN);
        when(mHearingAidProfile.isProfileReady()).thenReturn(true);
        when(mHearingAidProfile.getProfileId()).thenReturn(BluetoothProfile.HEARING_AID);
        when(mLeAudioProfile.isProfileReady()).thenReturn(true);
        when(mLeAudioProfile.getProfileId()).thenReturn(BluetoothProfile.LE_AUDIO);
        when(mHidProfile.isProfileReady()).thenReturn(true);
        when(mHidProfile.getProfileId()).thenReturn(BluetoothProfile.HID_HOST);
        when(mLocalBluetoothManager.getProfileManager()).thenReturn(mProfileManager);
        when(mBroadcast.isEnabled(any())).thenReturn(false);
        when(mProfileManager.getLeAudioBroadcastProfile()).thenReturn(mBroadcast);
        when(mProfileManager.getLeAudioBroadcastAssistantProfile()).thenReturn(mAssistant);
        mCachedDevice = spy(new CachedBluetoothDevice(mContext, mProfileManager, mDevice));
        mCachedDevice.setLocalBluetoothManager(mLocalBluetoothManager);
        mSubCachedDevice = spy(new CachedBluetoothDevice(mContext, mProfileManager, mSubDevice));
        doAnswer((invocation) -> mBatteryLevel).when(mCachedDevice).getBatteryLevel();
        doAnswer((invocation) -> mBatteryLevel).when(mSubCachedDevice).getBatteryLevel();
    }

    private void testTransitionFromConnectingToDisconnected(
        LocalBluetoothProfile connectingProfile, LocalBluetoothProfile connectedProfile,
        int connectionPolicy, String expectedSummary) {
        // Arrange:
        // At least one profile has to be connected
        updateProfileStatus(connectedProfile, BluetoothProfile.STATE_CONNECTED);
        // Set profile under test to CONNECTING
        updateProfileStatus(connectingProfile, BluetoothProfile.STATE_CONNECTING);
        // Set connection policy
        when(connectingProfile.getConnectionPolicy(mDevice)).thenReturn(connectionPolicy);

        // Act & Assert:
        //   Get the expected connection summary.
        updateProfileStatus(connectingProfile, BluetoothProfile.STATE_DISCONNECTED);
        assertThat(mCachedDevice.getConnectionSummary()).isEqualTo(expectedSummary);
    }

    @Test
    public void onProfileStateChanged_testConnectingToDisconnected_policyAllowed_problem() {
        String connectTimeoutString = mContext.getString(R.string.profile_connect_timeout_subtext);

        testTransitionFromConnectingToDisconnected(mA2dpProfile, mLeAudioProfile,
        BluetoothProfile.CONNECTION_POLICY_ALLOWED, connectTimeoutString);
        testTransitionFromConnectingToDisconnected(mHearingAidProfile, mLeAudioProfile,
        BluetoothProfile.CONNECTION_POLICY_ALLOWED, connectTimeoutString);
        testTransitionFromConnectingToDisconnected(mHfpProfile, mLeAudioProfile,
        BluetoothProfile.CONNECTION_POLICY_ALLOWED, connectTimeoutString);
        testTransitionFromConnectingToDisconnected(mLeAudioProfile, mA2dpProfile,
        BluetoothProfile.CONNECTION_POLICY_ALLOWED, connectTimeoutString);
    }

    @Test
    public void onProfileStateChanged_testConnectingToDisconnected_policyForbidden_noProblem() {
        testTransitionFromConnectingToDisconnected(mA2dpProfile, mLeAudioProfile,
        BluetoothProfile.CONNECTION_POLICY_FORBIDDEN, null);
        testTransitionFromConnectingToDisconnected(mHearingAidProfile, mLeAudioProfile,
        BluetoothProfile.CONNECTION_POLICY_FORBIDDEN, null);
        testTransitionFromConnectingToDisconnected(mHfpProfile, mLeAudioProfile,
        BluetoothProfile.CONNECTION_POLICY_FORBIDDEN, null);
        testTransitionFromConnectingToDisconnected(mLeAudioProfile, mA2dpProfile,
        BluetoothProfile.CONNECTION_POLICY_FORBIDDEN, null);
    }

    @Test
    public void onProfileStateChanged_testConnectingToDisconnected_policyUnknown_noProblem() {
        testTransitionFromConnectingToDisconnected(mA2dpProfile, mLeAudioProfile,
        BluetoothProfile.CONNECTION_POLICY_UNKNOWN, null);
        testTransitionFromConnectingToDisconnected(mHearingAidProfile, mLeAudioProfile,
        BluetoothProfile.CONNECTION_POLICY_UNKNOWN, null);
        testTransitionFromConnectingToDisconnected(mHfpProfile, mLeAudioProfile,
        BluetoothProfile.CONNECTION_POLICY_UNKNOWN, null);
        testTransitionFromConnectingToDisconnected(mLeAudioProfile, mA2dpProfile,
        BluetoothProfile.CONNECTION_POLICY_UNKNOWN, null);
    }

    @Test
    public void getConnectionSummary_testProfilesInactive_returnPairing() {
        // Arrange:
        //   Bond State: Bonding
        when(mDevice.getBondState()).thenReturn(BluetoothDevice.BOND_BONDING);

        // Act & Assert:
        //   Get "Pairing…" result without Battery Level.
        assertThat(mCachedDevice.getConnectionSummary()).isEqualTo("Pairing…");
    }

    @Test
    public void getTvConnectionSummary_testProfilesInactive_returnPairing() {
        // Arrange:
        //   Bond State: Bonding
        when(mDevice.getBondState()).thenReturn(BluetoothDevice.BOND_BONDING);

        // Act & Assert:
        //   Get "Pairing…" result without Battery Level.
        assertThat(mCachedDevice.getTvConnectionSummary().toString()).isEqualTo("Pairing…");
    }

    @Test
    public void getConnectionSummary_testSingleProfileConnectDisconnect() {
        // Test without battery level
        // Set PAN profile to be connected and test connection state summary
        updateProfileStatus(mPanProfile, BluetoothProfile.STATE_CONNECTED);
        assertThat(mCachedDevice.getConnectionSummary()).isNull();

        // Set PAN profile to be disconnected and test connection state summary
        updateProfileStatus(mPanProfile, BluetoothProfile.STATE_DISCONNECTED);
        assertThat(mCachedDevice.getConnectionSummary()).isNull();

        // Test with battery level
        mBatteryLevel = 10;
        // Set PAN profile to be connected and test connection state summary
        updateProfileStatus(mPanProfile, BluetoothProfile.STATE_CONNECTED);
        assertThat(mCachedDevice.getConnectionSummary()).isEqualTo("10% battery");

        // Set PAN profile to be disconnected and test connection state summary
        updateProfileStatus(mPanProfile, BluetoothProfile.STATE_DISCONNECTED);
        assertThat(mCachedDevice.getConnectionSummary()).isNull();

        // Test with BluetoothDevice.BATTERY_LEVEL_UNKNOWN battery level
        mBatteryLevel = BluetoothDevice.BATTERY_LEVEL_UNKNOWN;

        // Set PAN profile to be connected and test connection state summary
        updateProfileStatus(mPanProfile, BluetoothProfile.STATE_CONNECTED);
        assertThat(mCachedDevice.getConnectionSummary()).isNull();

        // Set PAN profile to be disconnected and test connection state summary
        updateProfileStatus(mPanProfile, BluetoothProfile.STATE_DISCONNECTED);
        assertThat(mCachedDevice.getConnectionSummary()).isNull();
    }

    @Test
    public void getTvConnectionSummary_testSingleProfileConnectDisconnect() {
        // Test without battery level
        // Set PAN profile to be connected and test connection state summary
        updateProfileStatus(mPanProfile, BluetoothProfile.STATE_CONNECTED);
        assertThat(mCachedDevice.getTvConnectionSummary()).isNull();

        // Set PAN profile to be disconnected and test connection state summary
        updateProfileStatus(mPanProfile, BluetoothProfile.STATE_DISCONNECTED);
        assertThat(mCachedDevice.getTvConnectionSummary()).isNull();

        // Test with battery level
        mBatteryLevel = 10;
        // Set PAN profile to be connected and test connection state summary
        updateProfileStatus(mPanProfile, BluetoothProfile.STATE_CONNECTED);
        assertThat(mCachedDevice.getTvConnectionSummary().toString()).isEqualTo("Battery 10%");

        // Set PAN profile to be disconnected and test connection state summary
        updateProfileStatus(mPanProfile, BluetoothProfile.STATE_DISCONNECTED);
        assertThat(mCachedDevice.getTvConnectionSummary()).isNull();

        // Test with BluetoothDevice.BATTERY_LEVEL_UNKNOWN battery level
        mBatteryLevel = BluetoothDevice.BATTERY_LEVEL_UNKNOWN;

        // Set PAN profile to be connected and test connection state summary
        updateProfileStatus(mPanProfile, BluetoothProfile.STATE_CONNECTED);
        assertThat(mCachedDevice.getTvConnectionSummary()).isNull();

        // Set PAN profile to be disconnected and test connection state summary
        updateProfileStatus(mPanProfile, BluetoothProfile.STATE_DISCONNECTED);
        assertThat(mCachedDevice.getTvConnectionSummary()).isNull();
    }

    @Test
    public void getConnectionSummary_testMultipleProfileConnectDisconnect() {
        mBatteryLevel = 10;

        // Set HFP, A2DP and PAN profile to be connected and test connection state summary
        updateProfileStatus(mHfpProfile, BluetoothProfile.STATE_CONNECTED);
        updateProfileStatus(mA2dpProfile, BluetoothProfile.STATE_CONNECTED);
        updateProfileStatus(mPanProfile, BluetoothProfile.STATE_CONNECTED);
        assertThat(mCachedDevice.getConnectionSummary()).isEqualTo("10% battery");

        // Disconnect HFP only and test connection state summary
        updateProfileStatus(mHfpProfile, BluetoothProfile.STATE_DISCONNECTED);
        assertThat(mCachedDevice.getConnectionSummary()).isEqualTo(
                "10% battery");

        // Disconnect A2DP only and test connection state summary
        updateProfileStatus(mHfpProfile, BluetoothProfile.STATE_CONNECTED);
        updateProfileStatus(mA2dpProfile, BluetoothProfile.STATE_DISCONNECTED);
        assertThat(mCachedDevice.getConnectionSummary()).isEqualTo(
                "10% battery");

        // Disconnect both HFP and A2DP and test connection state summary
        updateProfileStatus(mHfpProfile, BluetoothProfile.STATE_DISCONNECTED);
        assertThat(mCachedDevice.getConnectionSummary()).isEqualTo(
                "10% battery");

        // Disconnect all profiles and test connection state summary
        updateProfileStatus(mPanProfile, BluetoothProfile.STATE_DISCONNECTED);
        assertThat(mCachedDevice.getConnectionSummary()).isNull();
    }

    @Test
    public void getTvConnectionSummary_testMultipleProfileConnectDisconnect() {
        mBatteryLevel = 10;

        // Set HFP, A2DP and PAN profile to be connected and test connection state summary
        updateProfileStatus(mHfpProfile, BluetoothProfile.STATE_CONNECTED);
        updateProfileStatus(mA2dpProfile, BluetoothProfile.STATE_CONNECTED);
        updateProfileStatus(mPanProfile, BluetoothProfile.STATE_CONNECTED);
        assertThat(mCachedDevice.getTvConnectionSummary().toString()).isEqualTo("Battery 10%");

        // Disconnect HFP only and test connection state summary
        updateProfileStatus(mHfpProfile, BluetoothProfile.STATE_DISCONNECTED);
        assertThat(mCachedDevice.getTvConnectionSummary().toString()).isEqualTo(
                "Battery 10%");

        // Disconnect A2DP only and test connection state summary
        updateProfileStatus(mHfpProfile, BluetoothProfile.STATE_CONNECTED);
        updateProfileStatus(mA2dpProfile, BluetoothProfile.STATE_DISCONNECTED);
        assertThat(mCachedDevice.getTvConnectionSummary().toString()).isEqualTo(
                "Battery 10%");

        // Disconnect both HFP and A2DP and test connection state summary
        updateProfileStatus(mHfpProfile, BluetoothProfile.STATE_DISCONNECTED);
        assertThat(mCachedDevice.getTvConnectionSummary().toString()).isEqualTo(
                "Battery 10%");

        // Disconnect all profiles and test connection state summary
        updateProfileStatus(mPanProfile, BluetoothProfile.STATE_DISCONNECTED);
        assertThat(mCachedDevice.getTvConnectionSummary()).isNull();
    }

    @Test
    public void getConnectionSummary_testSingleProfileActiveDeviceA2dp() {
        // Test without battery level
        // Set A2DP profile to be connected and test connection state summary
        updateProfileStatus(mA2dpProfile, BluetoothProfile.STATE_CONNECTED);
        assertThat(mCachedDevice.getConnectionSummary()).isNull();

        // Set device as Active for A2DP and test connection state summary
        mCachedDevice.onActiveDeviceChanged(true, BluetoothProfile.A2DP);
        assertThat(mCachedDevice.getConnectionSummary())
                .isEqualTo(mContext.getString(R.string.bluetooth_active_no_battery_level));

        // Test with battery level
        mBatteryLevel = 10;
        assertThat(mCachedDevice.getConnectionSummary())
                .isEqualTo(mContext.getString(R.string.bluetooth_active_battery_level, "10%"));

        // Set A2DP profile to be disconnected and test connection state summary
        updateProfileStatus(mA2dpProfile, BluetoothProfile.STATE_DISCONNECTED);
        assertThat(mCachedDevice.getConnectionSummary()).isNull();

        // Test with BluetoothDevice.BATTERY_LEVEL_UNKNOWN battery level
        mBatteryLevel = BluetoothDevice.BATTERY_LEVEL_UNKNOWN;
        // Set A2DP profile to be connected, Active and test connection state summary
        updateProfileStatus(mA2dpProfile, BluetoothProfile.STATE_CONNECTED);
        mCachedDevice.onActiveDeviceChanged(true, BluetoothProfile.A2DP);
        assertThat(mCachedDevice.getConnectionSummary())
                .isEqualTo(mContext.getString(R.string.bluetooth_active_no_battery_level));

        // Set A2DP profile to be disconnected and test connection state summary
        updateProfileStatus(mA2dpProfile, BluetoothProfile.STATE_DISCONNECTED);
        assertThat(mCachedDevice.getConnectionSummary()).isNull();
    }

    @Test
    public void getTvConnectionSummary_testSingleProfileActiveDeviceA2dp() {
        // Test without battery level
        // Set A2DP profile to be connected and test connection state summary
        updateProfileStatus(mA2dpProfile, BluetoothProfile.STATE_CONNECTED);
        assertThat(mCachedDevice.getTvConnectionSummary()).isNull();

        // Set device as Active for A2DP and test connection state summary
        mCachedDevice.onActiveDeviceChanged(true, BluetoothProfile.A2DP);
        assertThat(mCachedDevice.getConnectionSummary()).isEqualTo("Active");

        // Test with battery level
        mBatteryLevel = 10;
        assertThat(mCachedDevice.getTvConnectionSummary().toString()).isEqualTo("Battery 10%");

        // Set A2DP profile to be disconnected and test connection state summary
        updateProfileStatus(mA2dpProfile, BluetoothProfile.STATE_DISCONNECTED);
        assertThat(mCachedDevice.getTvConnectionSummary()).isNull();

        // Test with BluetoothDevice.BATTERY_LEVEL_UNKNOWN battery level
        mBatteryLevel = BluetoothDevice.BATTERY_LEVEL_UNKNOWN;
        // Set A2DP profile to be connected, Active and test connection state summary
        updateProfileStatus(mA2dpProfile, BluetoothProfile.STATE_CONNECTED);
        mCachedDevice.onActiveDeviceChanged(true, BluetoothProfile.A2DP);
        assertThat(mCachedDevice.getTvConnectionSummary().toString()).isEqualTo("Active");

        // Set A2DP profile to be disconnected and test connection state summary
        updateProfileStatus(mA2dpProfile, BluetoothProfile.STATE_DISCONNECTED);
        assertThat(mCachedDevice.getTvConnectionSummary()).isNull();
    }

    @Test
    public void getConnectionSummary_shortSummary_returnShortSummary() {
        // Test without battery level
        // Set A2DP profile to be connected and test connection state summary
        updateProfileStatus(mA2dpProfile, BluetoothProfile.STATE_CONNECTED);
        assertThat(mCachedDevice.getConnectionSummary(true /* shortSummary */)).isNull();

        // Set device as Active for A2DP and test connection state summary
        mCachedDevice.onActiveDeviceChanged(true, BluetoothProfile.A2DP);
        assertThat(mCachedDevice.getConnectionSummary(true /* shortSummary */)).isEqualTo("Active");

        // Test with battery level
        mBatteryLevel = 10;
        assertThat(mCachedDevice.getConnectionSummary(true /* shortSummary */)).isEqualTo(
                "Active");

        // Set A2DP profile to be disconnected and test connection state summary
        updateProfileStatus(mA2dpProfile, BluetoothProfile.STATE_DISCONNECTED);
        assertThat(mCachedDevice.getConnectionSummary()).isNull();
    }

    @Test
    public void getConnectionSummary_testA2dpBatteryInactive_returnBattery() {
        // Arrange:
        //   1. Profile:       {A2DP, CONNECTED, Inactive}
        //   2. Battery Level: 10
        updateProfileStatus(mA2dpProfile, BluetoothProfile.STATE_CONNECTED);
        mBatteryLevel = 10;

        // Act & Assert:
        //   Get "10% battery" result without Battery Level.
        assertThat(mCachedDevice.getConnectionSummary()).isEqualTo("10% battery");
    }

    @Test
    public void getTvConnectionSummary_testA2dpBatteryInactive_returnBattery() {
        // Arrange:
        //   1. Profile:       {A2DP, CONNECTED, Inactive}
        //   2. Battery Level: 10
        updateProfileStatus(mA2dpProfile, BluetoothProfile.STATE_CONNECTED);
        mBatteryLevel = 10;

        // Act & Assert:
        assertThat(mCachedDevice.getTvConnectionSummary().toString()).isEqualTo("Battery 10%");
    }

    @Test
    public void getConnectionSummary_testA2dpInCall_returnNull() {
        // Arrange:
        //   1. Profile:       {A2DP, Connected, Active}
        //   2. Audio Manager: In Call
        updateProfileStatus(mA2dpProfile, BluetoothProfile.STATE_CONNECTED);
        mCachedDevice.onActiveDeviceChanged(true, BluetoothProfile.A2DP);
        mAudioManager.setMode(AudioManager.MODE_IN_CALL);

        // Act & Assert:
        //   Get null result without Battery Level.
        assertThat(mCachedDevice.getConnectionSummary()).isNull();
    }

    @Test
    public void getTvConnectionSummary_testA2dpInCall_returnNull() {
        // Arrange:
        //   1. Profile:       {A2DP, Connected, Active}
        //   2. Audio Manager: In Call
        updateProfileStatus(mA2dpProfile, BluetoothProfile.STATE_CONNECTED);
        mCachedDevice.onActiveDeviceChanged(true, BluetoothProfile.A2DP);
        mAudioManager.setMode(AudioManager.MODE_IN_CALL);

        // Act & Assert:
        //   Get null result without Battery Level.
        assertThat(mCachedDevice.getTvConnectionSummary()).isNull();
    }

    @Test
    public void getConnectionSummary_testA2dpBatteryInCall_returnBattery() {
        // Arrange:
        //   1. Profile:       {A2DP, Connected, Active}
        //   3. Battery Level: 10
        //   2. Audio Manager: In Call
        updateProfileStatus(mA2dpProfile, BluetoothProfile.STATE_CONNECTED);
        mCachedDevice.onActiveDeviceChanged(true, BluetoothProfile.A2DP);
        mBatteryLevel = 10;
        mAudioManager.setMode(AudioManager.MODE_IN_CALL);

        // Act & Assert:
        //   Get "10% battery" result with Battery Level 10.
        assertThat(mCachedDevice.getConnectionSummary()).isEqualTo("10% battery");
    }

    @Test
    public void getTvConnectionSummary_testA2dpBatteryInCall_returnBattery() {
        // Arrange:
        //   1. Profile:       {A2DP, Connected, Active}
        //   3. Battery Level: 10
        //   2. Audio Manager: In Call
        updateProfileStatus(mA2dpProfile, BluetoothProfile.STATE_CONNECTED);
        mCachedDevice.onActiveDeviceChanged(true, BluetoothProfile.A2DP);
        mBatteryLevel = 10;
        mAudioManager.setMode(AudioManager.MODE_IN_CALL);

        // Act & Assert:
        //   Get "10% battery" result with Battery Level 10.
        assertThat(mCachedDevice.getTvConnectionSummary().toString()).isEqualTo("Battery 10%");
    }

    @Test
    public void getConnectionSummary_testSingleProfileActiveDeviceHfp() {
        // Test without battery level
        // Set HFP profile to be connected and test connection state summary
        updateProfileStatus(mHfpProfile, BluetoothProfile.STATE_CONNECTED);
        assertThat(mCachedDevice.getConnectionSummary()).isNull();

        // Set device as Active for HFP and test connection state summary
        mCachedDevice.onAudioModeChanged();
        mAudioManager.setMode(AudioManager.MODE_IN_CALL);
        mCachedDevice.onActiveDeviceChanged(true, BluetoothProfile.HEADSET);
        assertThat(mCachedDevice.getConnectionSummary())
                .isEqualTo(mContext.getString(R.string.bluetooth_active_no_battery_level));

        // Test with battery level
        mBatteryLevel = 10;
        assertThat(mCachedDevice.getConnectionSummary())
                .isEqualTo(mContext.getString(R.string.bluetooth_active_battery_level, "10%"));

        // Set HFP profile to be disconnected and test connection state summary
        updateProfileStatus(mHfpProfile, BluetoothProfile.STATE_DISCONNECTED);
        assertThat(mCachedDevice.getConnectionSummary()).isNull();

        // Test with BluetoothDevice.BATTERY_LEVEL_UNKNOWN battery level
        mBatteryLevel = BluetoothDevice.BATTERY_LEVEL_UNKNOWN;
        // Set HFP profile to be connected, Active and test connection state summary
        updateProfileStatus(mHfpProfile, BluetoothProfile.STATE_CONNECTED);
        mCachedDevice.onActiveDeviceChanged(true, BluetoothProfile.HEADSET);
        assertThat(mCachedDevice.getConnectionSummary())
                .isEqualTo(mContext.getString(R.string.bluetooth_active_no_battery_level));

        // Set HFP profile to be disconnected and test connection state summary
        updateProfileStatus(mHfpProfile, BluetoothProfile.STATE_DISCONNECTED);
        assertThat(mCachedDevice.getConnectionSummary()).isNull();
    }

    @Test
    public void getTvConnectionSummary_testSingleProfileActiveDeviceHfp() {
        // Test without battery level
        // Set HFP profile to be connected and test connection state summary
        updateProfileStatus(mHfpProfile, BluetoothProfile.STATE_CONNECTED);
        assertThat(mCachedDevice.getTvConnectionSummary()).isNull();

        // Set device as Active for HFP and test connection state summary
        mCachedDevice.onAudioModeChanged();
        mAudioManager.setMode(AudioManager.MODE_IN_CALL);
        mCachedDevice.onActiveDeviceChanged(true, BluetoothProfile.HEADSET);
        assertThat(mCachedDevice.getTvConnectionSummary().toString()).isEqualTo("Active");

        // Test with battery level
        mBatteryLevel = 10;
        assertThat(mCachedDevice.getTvConnectionSummary().toString()).isEqualTo("Battery 10%");

        // Set HFP profile to be disconnected and test connection state summary
        updateProfileStatus(mHfpProfile, BluetoothProfile.STATE_DISCONNECTED);
        assertThat(mCachedDevice.getTvConnectionSummary()).isNull();

        // Test with BluetoothDevice.BATTERY_LEVEL_UNKNOWN battery level
        mBatteryLevel = BluetoothDevice.BATTERY_LEVEL_UNKNOWN;
        // Set HFP profile to be connected, Active and test connection state summary
        updateProfileStatus(mHfpProfile, BluetoothProfile.STATE_CONNECTED);
        mCachedDevice.onActiveDeviceChanged(true, BluetoothProfile.HEADSET);
        assertThat(mCachedDevice.getTvConnectionSummary().toString()).isEqualTo("Active");

        // Set HFP profile to be disconnected and test connection state summary
        updateProfileStatus(mHfpProfile, BluetoothProfile.STATE_DISCONNECTED);
        assertThat(mCachedDevice.getTvConnectionSummary()).isNull();
    }

    @Test
    public void getConnectionSummary_testHeadsetBatteryInactive_returnBattery() {
        // Arrange:
        //   1. Profile:       {HEADSET, CONNECTED, Inactive}
        //   2. Battery Level: 10
        updateProfileStatus(mHfpProfile, BluetoothProfile.STATE_CONNECTED);
        mBatteryLevel = 10;

        // Act & Assert:
        //   Get "10% battery" result without Battery Level.
        assertThat(mCachedDevice.getConnectionSummary()).isEqualTo("10% battery");
    }

    @Test
    public void getTvConnectionSummary_testHeadsetBatteryInactive_returnBattery() {
        // Arrange:
        //   1. Profile:       {HEADSET, CONNECTED, Inactive}
        //   2. Battery Level: 10
        updateProfileStatus(mHfpProfile, BluetoothProfile.STATE_CONNECTED);
        mBatteryLevel = 10;

        // Act & Assert:
        //   Get "10% battery" result without Battery Level.
        assertThat(mCachedDevice.getTvConnectionSummary().toString()).isEqualTo("Battery 10%");
    }

    @Test
    public void getConnectionSummary_testHeadsetWithoutInCall_returnNull() {
        // Arrange:
        //   1. Profile:       {HEADSET, Connected, Active}
        //   2. Audio Manager: Normal (Without In Call)
        updateProfileStatus(mHfpProfile, BluetoothProfile.STATE_CONNECTED);
        mCachedDevice.onActiveDeviceChanged(true, BluetoothProfile.HEADSET);

        // Act & Assert:
        //   Get null result without Battery Level.
        assertThat(mCachedDevice.getConnectionSummary()).isNull();
    }

    @Test
    public void getTvConnectionSummary_testHeadsetWithoutInCall_returnNull() {
        // Arrange:
        //   1. Profile:       {HEADSET, Connected, Active}
        //   2. Audio Manager: Normal (Without In Call)
        updateProfileStatus(mHfpProfile, BluetoothProfile.STATE_CONNECTED);
        mCachedDevice.onActiveDeviceChanged(true, BluetoothProfile.HEADSET);

        // Act & Assert:
        //   Get null result without Battery Level.
        assertThat(mCachedDevice.getTvConnectionSummary()).isNull();
    }

    @Test
    public void getConnectionSummary_testHeadsetBatteryWithoutInCall_returnBattery() {
        // Arrange:
        //   1. Profile:       {HEADSET, Connected, Active}
        //   2. Battery Level: 10
        //   3. Audio Manager: Normal (Without In Call)
        updateProfileStatus(mHfpProfile, BluetoothProfile.STATE_CONNECTED);
        mCachedDevice.onActiveDeviceChanged(true, BluetoothProfile.HEADSET);
        mBatteryLevel = 10;

        // Act & Assert:
        //   Get "10% battery" result with Battery Level 10.
        assertThat(mCachedDevice.getConnectionSummary()).isEqualTo("10% battery");
    }

    @Test
    public void getTvConnectionSummary_testHeadsetBatteryWithoutInCall_returnBattery() {
        // Arrange:
        //   1. Profile:       {HEADSET, Connected, Active}
        //   2. Battery Level: 10
        //   3. Audio Manager: Normal (Without In Call)
        updateProfileStatus(mHfpProfile, BluetoothProfile.STATE_CONNECTED);
        mCachedDevice.onActiveDeviceChanged(true, BluetoothProfile.HEADSET);
        mBatteryLevel = 10;

        // Act & Assert:
        //   Get "10% battery" result with Battery Level 10.
        assertThat(mCachedDevice.getTvConnectionSummary().toString()).isEqualTo("Battery 10%");
    }


    @Test
    public void getConnectionSummary_testSingleProfileActiveDeviceHearingAid() {
        // Test without battery level
        // Set Hearing Aid profile to be connected and test connection state summary
        updateProfileStatus(mHearingAidProfile, BluetoothProfile.STATE_CONNECTED);
        assertThat(mCachedDevice.getConnectionSummary()).isNull();

        // Set device as Active for Hearing Aid and test connection state summary
        mCachedDevice.setHearingAidInfo(getLeftAshaHearingAidInfo());
        mCachedDevice.onActiveDeviceChanged(true, BluetoothProfile.HEARING_AID);
        assertThat(mCachedDevice.getConnectionSummary()).isEqualTo("Active (left only)");

        // Set Hearing Aid profile to be disconnected and test connection state summary
        mCachedDevice.onActiveDeviceChanged(false, BluetoothProfile.HEARING_AID);
        mCachedDevice.
                onProfileStateChanged(mHearingAidProfile, BluetoothProfile.STATE_DISCONNECTED);
        assertThat(mCachedDevice.getConnectionSummary()).isNull();
    }

    @Test
    public void getTvConnectionSummary_testSingleProfileActiveDeviceHearingAid() {
        // Test without battery level
        // Set Hearing Aid profile to be connected and test connection state summary
        updateProfileStatus(mHearingAidProfile, BluetoothProfile.STATE_CONNECTED);
        assertThat(mCachedDevice.getTvConnectionSummary()).isNull();

        // Set device as Active for Hearing Aid and test connection state summary
        mCachedDevice.setHearingAidInfo(getLeftAshaHearingAidInfo());
        mCachedDevice.onActiveDeviceChanged(true, BluetoothProfile.HEARING_AID);
        assertThat(mCachedDevice.getTvConnectionSummary().toString()).isEqualTo(
                "Active (left only)");

        // Set Hearing Aid profile to be disconnected and test connection state summary
        mCachedDevice.onActiveDeviceChanged(false, BluetoothProfile.HEARING_AID);
        mCachedDevice.onProfileStateChanged(mHearingAidProfile,
                BluetoothProfile.STATE_DISCONNECTED);
        assertThat(mCachedDevice.getTvConnectionSummary()).isNull();
    }

    @Test
    public void getConnectionSummary_testHearingAidBatteryInactive_returnBattery() {
        // Arrange:
        //   1. Profile:       {HEARING_AID, CONNECTED, Inactive}
        //   2. Battery Level: 10
        updateProfileStatus(mHearingAidProfile, BluetoothProfile.STATE_CONNECTED);
        mBatteryLevel = 10;

        // Act & Assert:
        //   Get "10% battery" result without Battery Level.
        assertThat(mCachedDevice.getConnectionSummary()).isEqualTo("10% battery");
    }

    @Test
    public void getTvConnectionSummary_testHearingAidBatteryInactive_returnBattery() {
        // Arrange:
        //   1. Profile:       {HEARING_AID, CONNECTED, Inactive}
        //   2. Battery Level: 10
        updateProfileStatus(mHearingAidProfile, BluetoothProfile.STATE_CONNECTED);
        mBatteryLevel = 10;

        // Act & Assert:
        //   Get "10% battery" result without Battery Level.
        assertThat(mCachedDevice.getTvConnectionSummary().toString()).isEqualTo("Battery 10%");
    }

    @Test
    public void getConnectionSummary_testHearingAidLeftEarBatteryNotInCall_returnActiveBattery() {
        // Arrange:
        //   1. Profile:       {HEARING_AID, Connected, Active, Left ear}
        //   2. Battery Level: 10
        //   3. Audio Manager: Normal (Without In Call)
        updateProfileStatus(mHearingAidProfile, BluetoothProfile.STATE_CONNECTED);
        mCachedDevice.setHearingAidInfo(getLeftAshaHearingAidInfo());
        mCachedDevice.onActiveDeviceChanged(true, BluetoothProfile.HEARING_AID);
        mBatteryLevel = 10;

        // Act & Assert:
        //   Get "Active. L: 10% battery." result with Battery Level 10.
        assertThat(mCachedDevice.getConnectionSummary()).isEqualTo("Active. L: 10% battery.");
    }

    @Test
    public void getTvConnectionSummary_testHearingAidLeftEarBatteryWithoutInCall_returnBattery() {
        // Arrange:
        //   1. Profile:       {HEARING_AID, Connected, Active, Left ear}
        //   2. Battery Level: 10
        //   3. Audio Manager: Normal (Without In Call)
        updateProfileStatus(mHearingAidProfile, BluetoothProfile.STATE_CONNECTED);
        mCachedDevice.setHearingAidInfo(getLeftAshaHearingAidInfo());
        mCachedDevice.onActiveDeviceChanged(true, BluetoothProfile.HEARING_AID);
        mBatteryLevel = 10;

        // Act & Assert:
        //   Get "Left 10%" result with Battery Level 10.
        assertThat(mCachedDevice.getTvConnectionSummary().toString()).isEqualTo("Left 10%");
    }

    @Test
    public void getConnectionSummary_testHearingAidLeftEarBatteryInCall_returnActiveBattery() {
        // Arrange:
        //   1. Profile:       {HEARING_AID, Connected, Active, Left ear}
        //   2. Battery Level: 10
        //   3. Audio Manager: In Call
        updateProfileStatus(mHearingAidProfile, BluetoothProfile.STATE_CONNECTED);
        mCachedDevice.setHearingAidInfo(getLeftAshaHearingAidInfo());
        mCachedDevice.onActiveDeviceChanged(true, BluetoothProfile.HEARING_AID);
        mAudioManager.setMode(AudioManager.MODE_IN_CALL);
        mBatteryLevel = 10;

        // Act & Assert:
        //   Get "Active. L: 10% battery." result with Battery Level 10.
        assertThat(mCachedDevice.getConnectionSummary()).isEqualTo("Active. L: 10% battery.");
    }

    @Test
    public void getTvConnectionSummary_testHearingAidLeftEarBatteryInCall_returnBattery() {
        // Arrange:
        //   1. Profile:       {HEARING_AID, Connected, Active, Left ear}
        //   2. Battery Level: 10
        //   3. Audio Manager: In Call
        updateProfileStatus(mHearingAidProfile, BluetoothProfile.STATE_CONNECTED);
        mCachedDevice.setHearingAidInfo(getLeftAshaHearingAidInfo());
        mCachedDevice.onActiveDeviceChanged(true, BluetoothProfile.HEARING_AID);
        mAudioManager.setMode(AudioManager.MODE_IN_CALL);
        mBatteryLevel = 10;

        // Act & Assert:
        //   Get "Left 10%" result with Battery Level 10.
        assertThat(mCachedDevice.getTvConnectionSummary().toString()).isEqualTo(
                "Left 10%");
    }

    @Test
    public void getConnectionSummary_testHearingAidRightEarInCall_returnActiveRightEar() {
        // Arrange:
        //   1. Profile:       {HEARING_AID, Connected, Active, Right ear}
        //   2. Audio Manager: In Call
        updateProfileStatus(mHearingAidProfile, BluetoothProfile.STATE_CONNECTED);
        mCachedDevice.setHearingAidInfo(getRightAshaHearingAidInfo());
        mCachedDevice.onActiveDeviceChanged(true, BluetoothProfile.HEARING_AID);
        mAudioManager.setMode(AudioManager.MODE_IN_CALL);

        // Act & Assert:
        //   Get "Active" result without Battery Level.
        assertThat(mCachedDevice.getConnectionSummary()).isEqualTo("Active (right only)");
    }

    @Test
    public void getTvConnectionSummary_testHearingAidRightEarInCall_returnActiveRightEar() {
        // Arrange:
        //   1. Profile:       {HEARING_AID, Connected, Active, Right ear}
        //   2. Audio Manager: In Call
        updateProfileStatus(mHearingAidProfile, BluetoothProfile.STATE_CONNECTED);
        mCachedDevice.setHearingAidInfo(getRightAshaHearingAidInfo());
        mCachedDevice.onActiveDeviceChanged(true, BluetoothProfile.HEARING_AID);
        mAudioManager.setMode(AudioManager.MODE_IN_CALL);

        // Act & Assert:
        //   Get "Active" result without Battery Level.
        assertThat(mCachedDevice.getTvConnectionSummary().toString()).isEqualTo(
                "Active (right only)");
    }

    @Test
    public void getConnectionSummary_testHearingAidBothEarInCall_returnActiveBothEar() {
        // Arrange:
        //   1. Profile:       {HEARING_AID, Connected, Active, Both ear}
        //   2. Audio Manager: In Call
        mCachedDevice.setHearingAidInfo(getRightAshaHearingAidInfo());
        updateProfileStatus(mHearingAidProfile, BluetoothProfile.STATE_CONNECTED);
        mSubCachedDevice.setHearingAidInfo(getLeftAshaHearingAidInfo());
        updateSubDeviceProfileStatus(mHearingAidProfile, BluetoothProfile.STATE_CONNECTED);
        mCachedDevice.setSubDevice(mSubCachedDevice);
        mCachedDevice.onActiveDeviceChanged(true, BluetoothProfile.HEARING_AID);
        mAudioManager.setMode(AudioManager.MODE_IN_CALL);

        // Act & Assert:
        //   Get "Active" result without Battery Level.
        assertThat(mCachedDevice.getConnectionSummary()).isEqualTo("Active (left and right)");
    }

    @Test
    public void getTvConnectionSummary_testHearingAidBothEarInCall_returnActiveBothEar() {
        // Arrange:
        //   1. Profile:       {HEARING_AID, Connected, Active, Both ear}
        //   2. Audio Manager: In Call
        mCachedDevice.setHearingAidInfo(getRightAshaHearingAidInfo());
        updateProfileStatus(mHearingAidProfile, BluetoothProfile.STATE_CONNECTED);
        mSubCachedDevice.setHearingAidInfo(getLeftAshaHearingAidInfo());
        updateSubDeviceProfileStatus(mHearingAidProfile, BluetoothProfile.STATE_CONNECTED);
        mCachedDevice.setSubDevice(mSubCachedDevice);
        mCachedDevice.onActiveDeviceChanged(true, BluetoothProfile.HEARING_AID);
        mAudioManager.setMode(AudioManager.MODE_IN_CALL);

        // Act & Assert:
        //   Get "Active" result without Battery Level.
        assertThat(mCachedDevice.getTvConnectionSummary().toString())
                .isEqualTo("Active (left and right)");
    }

    @Test
    public void getConnectionSummary_testHearingAidBothEarBattery_returnActiveBattery() {
        // Arrange:
        //   1. Profile:       {HEARING_AID, Connected, Active, Both ear}
        //   2. Battery Level: 10
        //   3. Audio Manager: In Call
        mCachedDevice.setHearingAidInfo(getRightAshaHearingAidInfo());
        updateProfileStatus(mHearingAidProfile, BluetoothProfile.STATE_CONNECTED);
        mSubCachedDevice.setHearingAidInfo(getLeftAshaHearingAidInfo());
        updateSubDeviceProfileStatus(mHearingAidProfile, BluetoothProfile.STATE_CONNECTED);
        mCachedDevice.setSubDevice(mSubCachedDevice);
        mCachedDevice.onActiveDeviceChanged(true, BluetoothProfile.HEARING_AID);
        mAudioManager.setMode(AudioManager.MODE_IN_CALL);
        mBatteryLevel = 10;

        // Act & Assert:
        //   Get "Active. L: 10%, R: 10% battery." result with Battery Level 10.
        assertThat(mCachedDevice.getConnectionSummary().toString())
                .isEqualTo("Active. L: 10%, R: 10% battery.");
    }

    @Test
    public void getTvConnectionSummary_testHearingAidBothEarBattery_returnActiveBattery() {
        // Arrange:
        //   1. Profile:       {HEARING_AID, Connected, Active, Both ear}
        //   2. Battery Level: 10
        //   3. Audio Manager: In Call
        mCachedDevice.setHearingAidInfo(getRightAshaHearingAidInfo());
        updateProfileStatus(mHearingAidProfile, BluetoothProfile.STATE_CONNECTED);
        mSubCachedDevice.setHearingAidInfo(getLeftAshaHearingAidInfo());
        updateSubDeviceProfileStatus(mHearingAidProfile, BluetoothProfile.STATE_CONNECTED);
        mCachedDevice.setSubDevice(mSubCachedDevice);
        mCachedDevice.onActiveDeviceChanged(true, BluetoothProfile.HEARING_AID);
        mAudioManager.setMode(AudioManager.MODE_IN_CALL);
        mBatteryLevel = 10;

        // Act & Assert:
        //   Get "Left 10% Right 10%" result with Battery Level 10.
        assertThat(mCachedDevice.getTvConnectionSummary().toString())
                .isEqualTo("Left 10% Right 10%");
    }

    @Test
    public void getConnectionSummary_testActiveDeviceLeAudioHearingAid() {
        // Test without battery level
        // Set HAP Client and LE Audio profile to be connected and test connection state summary
        when(mProfileManager.getHapClientProfile()).thenReturn(mHapClientProfile);
        updateProfileStatus(mHapClientProfile, BluetoothProfile.STATE_CONNECTED);
        updateProfileStatus(mLeAudioProfile, BluetoothProfile.STATE_CONNECTED);
        assertThat(mCachedDevice.getConnectionSummary()).isNull();

        // Set device as Active for LE Audio and test connection state summary
        mCachedDevice.setHearingAidInfo(getLeftLeAudioHearingAidInfo());
        mCachedDevice.onActiveDeviceChanged(true, BluetoothProfile.LE_AUDIO);
        assertThat(mCachedDevice.getConnectionSummary()).isEqualTo("Active (left only)");

        // Set LE Audio profile to be disconnected and test connection state summary
        mCachedDevice.onActiveDeviceChanged(false, BluetoothProfile.LE_AUDIO);
        mCachedDevice.onProfileStateChanged(mLeAudioProfile, BluetoothProfile.STATE_DISCONNECTED);
        assertThat(mCachedDevice.getConnectionSummary()).isNull();
    }

    @Test
    public void getTvConnectionSummary_testActiveDeviceLeAudioHearingAid() {
        // Test without battery level
        // Set HAP Client and LE Audio profile to be connected and test connection state summary
        when(mProfileManager.getHapClientProfile()).thenReturn(mHapClientProfile);
        updateProfileStatus(mHapClientProfile, BluetoothProfile.STATE_CONNECTED);
        updateProfileStatus(mLeAudioProfile, BluetoothProfile.STATE_CONNECTED);
        assertThat(mCachedDevice.getTvConnectionSummary()).isNull();

        // Set device as Active for LE Audio and test connection state summary
        mCachedDevice.setHearingAidInfo(getLeftLeAudioHearingAidInfo());
        mCachedDevice.onActiveDeviceChanged(true, BluetoothProfile.LE_AUDIO);
        assertThat(mCachedDevice.getTvConnectionSummary().toString()).isEqualTo(
                "Active (left only)");

        // Set LE Audio profile to be disconnected and test connection state summary
        mCachedDevice.onActiveDeviceChanged(false, BluetoothProfile.LE_AUDIO);
        mCachedDevice.onProfileStateChanged(mLeAudioProfile, BluetoothProfile.STATE_DISCONNECTED);
        assertThat(mCachedDevice.getTvConnectionSummary()).isNull();
    }

    @Test
    public void getConnectionSummary_testMemberDevicesExist_returnMinBattery() {
        // One device is active with battery level 70.
        mBatteryLevel = 70;
        updateProfileStatus(mA2dpProfile, BluetoothProfile.STATE_CONNECTED);
        mCachedDevice.onActiveDeviceChanged(true, BluetoothProfile.A2DP);


        // Add a member device with battery level 30.
        int lowerBatteryLevel = 30;
        mCachedDevice.addMemberDevice(mSubCachedDevice);
        doAnswer((invocation) -> lowerBatteryLevel).when(mSubCachedDevice).getBatteryLevel();

        assertThat(mCachedDevice.getConnectionSummary())
                .isEqualTo(mContext.getString(R.string.bluetooth_active_battery_level, "30%"));
    }

    @Test
    public void getTvConnectionSummary_testMemberDevicesExist_returnMinBattery() {
        // One device is active with battery level 70.
        mBatteryLevel = 70;
        updateProfileStatus(mA2dpProfile, BluetoothProfile.STATE_CONNECTED);
        mCachedDevice.onActiveDeviceChanged(true, BluetoothProfile.A2DP);


        // Add a member device with battery level 30.
        int lowerBatteryLevel = 30;
        mCachedDevice.addMemberDevice(mSubCachedDevice);
        doAnswer((invocation) -> lowerBatteryLevel).when(mSubCachedDevice).getBatteryLevel();

        assertThat(mCachedDevice.getTvConnectionSummary().toString()).isEqualTo("Battery 30%");
    }

    @Test
    public void getConnectionSummary_testMemberDevicesBatteryUnknown_returnMinBattery() {
        // One device is active with battery level 70.
        mBatteryLevel = 70;
        updateProfileStatus(mA2dpProfile, BluetoothProfile.STATE_CONNECTED);
        mCachedDevice.onActiveDeviceChanged(true, BluetoothProfile.A2DP);

        // Add a member device with battery level unknown.
        mCachedDevice.addMemberDevice(mSubCachedDevice);
        doAnswer((invocation) -> BluetoothDevice.BATTERY_LEVEL_UNKNOWN).when(
                mSubCachedDevice).getBatteryLevel();

        assertThat(mCachedDevice.getConnectionSummary())
                .isEqualTo(mContext.getString(R.string.bluetooth_active_battery_level, "70%"));
    }

    @Test
    public void getTvConnectionSummary_testMemberDevicesBatteryUnknown_returnMinBattery() {
        // One device is active with battery level 70.
        mBatteryLevel = 70;
        updateProfileStatus(mA2dpProfile, BluetoothProfile.STATE_CONNECTED);
        mCachedDevice.onActiveDeviceChanged(true, BluetoothProfile.A2DP);

        // Add a member device with battery level unknown.
        mCachedDevice.addMemberDevice(mSubCachedDevice);
        doAnswer((invocation) -> BluetoothDevice.BATTERY_LEVEL_UNKNOWN).when(
                mSubCachedDevice).getBatteryLevel();

        assertThat(mCachedDevice.getTvConnectionSummary().toString()).isEqualTo("Battery 70%");
    }

    @Test
    public void getConnectionSummary_testAllDevicesBatteryUnknown_returnNoBattery() {
        // One device is active with battery level unknown.
        updateProfileStatus(mA2dpProfile, BluetoothProfile.STATE_CONNECTED);
        mCachedDevice.onActiveDeviceChanged(true, BluetoothProfile.A2DP);

        // Add a member device with battery level unknown.
        mCachedDevice.addMemberDevice(mSubCachedDevice);
        doAnswer((invocation) -> BluetoothDevice.BATTERY_LEVEL_UNKNOWN).when(
                mSubCachedDevice).getBatteryLevel();

        assertThat(mCachedDevice.getConnectionSummary()).isEqualTo("Active");
    }

    @Test
    public void getTvConnectionSummary_testAllDevicesBatteryUnknown_returnNoBattery() {
        // One device is active with battery level unknown.
        updateProfileStatus(mA2dpProfile, BluetoothProfile.STATE_CONNECTED);
        mCachedDevice.onActiveDeviceChanged(true, BluetoothProfile.A2DP);

        // Add a member device with battery level unknown.
        mCachedDevice.addMemberDevice(mSubCachedDevice);
        doAnswer((invocation) -> BluetoothDevice.BATTERY_LEVEL_UNKNOWN).when(
                mSubCachedDevice).getBatteryLevel();

        assertThat(mCachedDevice.getTvConnectionSummary().toString()).isEqualTo("Active");
    }

    @Test
    public void getConnectionSummary_testMultipleProfilesActiveDevice() {
        // Test without battery level
        // Set A2DP and HFP profiles to be connected and test connection state summary
        updateProfileStatus(mA2dpProfile, BluetoothProfile.STATE_CONNECTED);
        updateProfileStatus(mHfpProfile, BluetoothProfile.STATE_CONNECTED);
        assertThat(mCachedDevice.getConnectionSummary()).isNull();

        // Set device as Active for A2DP and HFP and test connection state summary
        mCachedDevice.onActiveDeviceChanged(true, BluetoothProfile.A2DP);
        mCachedDevice.onActiveDeviceChanged(true, BluetoothProfile.HEADSET);
        assertThat(mCachedDevice.getConnectionSummary())
                .isEqualTo(mContext.getString(R.string.bluetooth_active_no_battery_level));

        // Test with battery level
        mBatteryLevel = 10;
        assertThat(mCachedDevice.getConnectionSummary())
                .isEqualTo(mContext.getString(R.string.bluetooth_active_battery_level, "10%"));

        // Disconnect A2DP only and test connection state summary
        mCachedDevice.onActiveDeviceChanged(false, BluetoothProfile.A2DP);
        updateProfileStatus(mA2dpProfile, BluetoothProfile.STATE_DISCONNECTED);
        assertThat(mCachedDevice.getConnectionSummary())
                .isEqualTo(mContext.getString(R.string.bluetooth_battery_level, "10%"));

        // Disconnect HFP only and test connection state summary
        mCachedDevice.onActiveDeviceChanged(false, BluetoothProfile.HEADSET);
        updateProfileStatus(mHfpProfile, BluetoothProfile.STATE_DISCONNECTED);
        updateProfileStatus(mA2dpProfile, BluetoothProfile.STATE_CONNECTED);
        mCachedDevice.onActiveDeviceChanged(true, BluetoothProfile.A2DP);
        assertThat(mCachedDevice.getConnectionSummary())
                .isEqualTo(mContext.getString(R.string.bluetooth_active_battery_level, "10%"));

        // Test with BluetoothDevice.BATTERY_LEVEL_UNKNOWN battery level
        mBatteryLevel = BluetoothDevice.BATTERY_LEVEL_UNKNOWN;
        // Set A2DP and HFP profiles to be connected, Active and test connection state summary
        updateProfileStatus(mA2dpProfile, BluetoothProfile.STATE_CONNECTED);
        updateProfileStatus(mHfpProfile, BluetoothProfile.STATE_CONNECTED);
        mCachedDevice.onActiveDeviceChanged(true, BluetoothProfile.A2DP);
        mCachedDevice.onActiveDeviceChanged(true, BluetoothProfile.HEADSET);
        assertThat(mCachedDevice.getConnectionSummary())
                .isEqualTo(mContext.getString(R.string.bluetooth_active_no_battery_level));

        // Set A2DP and HFP profiles to be disconnected and test connection state summary
        updateProfileStatus(mA2dpProfile, BluetoothProfile.STATE_DISCONNECTED);
        updateProfileStatus(mHfpProfile, BluetoothProfile.STATE_DISCONNECTED);
        assertThat(mCachedDevice.getConnectionSummary()).isNull();
    }

    @Test
    public void getTvConnectionSummary_testMultipleProfilesActiveDevice() {
        // Test without battery level
        // Set A2DP and HFP profiles to be connected and test connection state summary
        updateProfileStatus(mA2dpProfile, BluetoothProfile.STATE_CONNECTED);
        updateProfileStatus(mHfpProfile, BluetoothProfile.STATE_CONNECTED);
        assertThat(mCachedDevice.getTvConnectionSummary()).isNull();

        // Set device as Active for A2DP and HFP and test connection state summary
        mCachedDevice.onActiveDeviceChanged(true, BluetoothProfile.A2DP);
        mCachedDevice.onActiveDeviceChanged(true, BluetoothProfile.HEADSET);
        assertThat(mCachedDevice.getTvConnectionSummary().toString()).isEqualTo("Active");

        // Test with battery level
        mBatteryLevel = 10;
        assertThat(mCachedDevice.getTvConnectionSummary().toString()).isEqualTo(
                "Battery 10%");

        // Disconnect A2DP only and test connection state summary
        mCachedDevice.onActiveDeviceChanged(false, BluetoothProfile.A2DP);
        updateProfileStatus(mA2dpProfile, BluetoothProfile.STATE_DISCONNECTED);
        assertThat(mCachedDevice.getTvConnectionSummary().toString()).isEqualTo(
                "Battery 10%");

        // Disconnect HFP only and test connection state summary
        mCachedDevice.onActiveDeviceChanged(false, BluetoothProfile.HEADSET);
        updateProfileStatus(mHfpProfile, BluetoothProfile.STATE_DISCONNECTED);
        updateProfileStatus(mA2dpProfile, BluetoothProfile.STATE_CONNECTED);
        mCachedDevice.onActiveDeviceChanged(true, BluetoothProfile.A2DP);
        assertThat(mCachedDevice.getTvConnectionSummary().toString()).isEqualTo(
                "Battery 10%");

        // Test with BluetoothDevice.BATTERY_LEVEL_UNKNOWN battery level
        mBatteryLevel = BluetoothDevice.BATTERY_LEVEL_UNKNOWN;
        // Set A2DP and HFP profiles to be connected, Active and test connection state summary
        updateProfileStatus(mA2dpProfile, BluetoothProfile.STATE_CONNECTED);
        updateProfileStatus(mHfpProfile, BluetoothProfile.STATE_CONNECTED);
        mCachedDevice.onActiveDeviceChanged(true, BluetoothProfile.A2DP);
        mCachedDevice.onActiveDeviceChanged(true, BluetoothProfile.HEADSET);
        assertThat(mCachedDevice.getTvConnectionSummary().toString()).isEqualTo("Active");

        // Set A2DP and HFP profiles to be disconnected and test connection state summary
        updateProfileStatus(mA2dpProfile, BluetoothProfile.STATE_DISCONNECTED);
        updateProfileStatus(mHfpProfile, BluetoothProfile.STATE_DISCONNECTED);
        assertThat(mCachedDevice.getTvConnectionSummary()).isNull();
    }

    @Test
    public void getConnectionSummary_testMultipleProfilesInactive_returnPairing() {
        // Arrange:
        //   1. Profile 1:  {A2DP, CONNECTED, Inactive}
        //   2. Profile 2:  {HEADSET, CONNECTED, Inactive}
        //   3. Profile 3:  {HEARING_AID, CONNECTED, Inactive}
        //   4. Bond State: Bonding
        updateProfileStatus(mA2dpProfile, BluetoothProfile.STATE_CONNECTED);
        updateProfileStatus(mHfpProfile, BluetoothProfile.STATE_CONNECTED);
        updateProfileStatus(mHearingAidProfile, BluetoothProfile.STATE_CONNECTED);
        when(mDevice.getBondState()).thenReturn(BluetoothDevice.BOND_BONDING);

        // Act & Assert:
        //    Get "Pairing…" result without Battery Level.
        assertThat(mCachedDevice.getConnectionSummary()).isEqualTo("Pairing…");
    }

    @Test
    public void getTvConnectionSummary_testMultipleProfilesInactive_returnPairing() {
        // Arrange:
        //   1. Profile 1:  {A2DP, CONNECTED, Inactive}
        //   2. Profile 2:  {HEADSET, CONNECTED, Inactive}
        //   3. Profile 3:  {HEARING_AID, CONNECTED, Inactive}
        //   4. Bond State: Bonding
        updateProfileStatus(mA2dpProfile, BluetoothProfile.STATE_CONNECTED);
        updateProfileStatus(mHfpProfile, BluetoothProfile.STATE_CONNECTED);
        updateProfileStatus(mHearingAidProfile, BluetoothProfile.STATE_CONNECTED);
        when(mDevice.getBondState()).thenReturn(BluetoothDevice.BOND_BONDING);

        // Act & Assert:
        //    Get "Pairing…" result without Battery Level.
        assertThat(mCachedDevice.getTvConnectionSummary().toString()).isEqualTo("Pairing…");
    }

    @Test
    public void getConnectionSummary_trueWirelessActiveDeviceWithBattery_returnActiveWithBattery() {
        updateProfileStatus(mA2dpProfile, BluetoothProfile.STATE_CONNECTED);
        updateProfileStatus(mHfpProfile, BluetoothProfile.STATE_CONNECTED);
        updateProfileStatus(mHearingAidProfile, BluetoothProfile.STATE_CONNECTED);
        when(mDevice.getBondState()).thenReturn(BluetoothDevice.BOND_BONDED);
        mCachedDevice.onActiveDeviceChanged(true, BluetoothProfile.A2DP);
        when(mDevice.getMetadata(BluetoothDevice.METADATA_IS_UNTETHERED_HEADSET)).thenReturn(
                "true".getBytes());
        when(mDevice.getMetadata(BluetoothDevice.METADATA_UNTETHERED_LEFT_BATTERY)).thenReturn(
                TWS_BATTERY_LEFT.getBytes());
        when(mDevice.getMetadata(BluetoothDevice.METADATA_UNTETHERED_RIGHT_BATTERY)).thenReturn(
                TWS_BATTERY_RIGHT.getBytes());

        assertThat(mCachedDevice.getConnectionSummary()).isEqualTo(
                "Active. L: 15%, R: 25% battery.");
    }

    @Test
    public void getTvConnectionSummary_trueWirelessActiveDeviceWithBattery_returnBattery() {
        updateProfileStatus(mA2dpProfile, BluetoothProfile.STATE_CONNECTED);
        updateProfileStatus(mHfpProfile, BluetoothProfile.STATE_CONNECTED);
        updateProfileStatus(mHearingAidProfile, BluetoothProfile.STATE_CONNECTED);
        when(mDevice.getBondState()).thenReturn(BluetoothDevice.BOND_BONDED);
        mCachedDevice.onActiveDeviceChanged(true, BluetoothProfile.A2DP);
        when(mDevice.getMetadata(BluetoothDevice.METADATA_IS_UNTETHERED_HEADSET)).thenReturn(
                "true".getBytes());
        when(mDevice.getMetadata(BluetoothDevice.METADATA_UNTETHERED_LEFT_BATTERY)).thenReturn(
                TWS_BATTERY_LEFT.getBytes());
        when(mDevice.getMetadata(BluetoothDevice.METADATA_UNTETHERED_RIGHT_BATTERY)).thenReturn(
                TWS_BATTERY_RIGHT.getBytes());

        assertThat(mCachedDevice.getTvConnectionSummary().toString()).isEqualTo(
                "Left 15% Right 25%");
    }

    @Test
    public void getConnectionSummary_trueWirelessDeviceWithBattery_returnActiveWithBattery() {
        updateProfileStatus(mA2dpProfile, BluetoothProfile.STATE_CONNECTED);
        updateProfileStatus(mHfpProfile, BluetoothProfile.STATE_CONNECTED);
        updateProfileStatus(mHearingAidProfile, BluetoothProfile.STATE_CONNECTED);
        when(mDevice.getBondState()).thenReturn(BluetoothDevice.BOND_BONDED);
        when(mDevice.getMetadata(BluetoothDevice.METADATA_IS_UNTETHERED_HEADSET)).thenReturn(
                "true".getBytes());
        when(mDevice.getMetadata(BluetoothDevice.METADATA_UNTETHERED_LEFT_BATTERY)).thenReturn(
                TWS_BATTERY_LEFT.getBytes());
        when(mDevice.getMetadata(BluetoothDevice.METADATA_UNTETHERED_RIGHT_BATTERY)).thenReturn(
                TWS_BATTERY_RIGHT.getBytes());

        assertThat(mCachedDevice.getConnectionSummary())
                .isEqualTo(
                        mContext.getString(
                                R.string.bluetooth_battery_level_untethered, "15%", "25%"));
    }

    @Test
    public void getTvConnectionSummary_trueWirelessDeviceWithBattery_returnBattery() {
        updateProfileStatus(mA2dpProfile, BluetoothProfile.STATE_CONNECTED);
        updateProfileStatus(mHfpProfile, BluetoothProfile.STATE_CONNECTED);
        updateProfileStatus(mHearingAidProfile, BluetoothProfile.STATE_CONNECTED);
        when(mDevice.getBondState()).thenReturn(BluetoothDevice.BOND_BONDED);
        when(mDevice.getMetadata(BluetoothDevice.METADATA_IS_UNTETHERED_HEADSET)).thenReturn(
                "true".getBytes());
        when(mDevice.getMetadata(BluetoothDevice.METADATA_UNTETHERED_LEFT_BATTERY)).thenReturn(
                TWS_BATTERY_LEFT.getBytes());
        when(mDevice.getMetadata(BluetoothDevice.METADATA_UNTETHERED_RIGHT_BATTERY)).thenReturn(
                TWS_BATTERY_RIGHT.getBytes());

        assertThat(mCachedDevice.getTvConnectionSummary().toString())
                .isEqualTo("Left 15% Right 25%");
    }

    @Test
    public void getTvConnectionSummary_trueWirelessDeviceWithLowBattery() {
        updateProfileStatus(mA2dpProfile, BluetoothProfile.STATE_CONNECTED);
        updateProfileStatus(mHfpProfile, BluetoothProfile.STATE_CONNECTED);
        updateProfileStatus(mHearingAidProfile, BluetoothProfile.STATE_CONNECTED);
        when(mDevice.getBondState()).thenReturn(BluetoothDevice.BOND_BONDED);
        when(mDevice.getMetadata(BluetoothDevice.METADATA_IS_UNTETHERED_HEADSET))
                .thenReturn("true".getBytes());
        when(mDevice.getMetadata(BluetoothDevice.METADATA_UNTETHERED_LEFT_BATTERY))
                .thenReturn(TWS_BATTERY_LEFT.getBytes());
        when(mDevice.getMetadata(BluetoothDevice.METADATA_UNTETHERED_RIGHT_BATTERY))
                .thenReturn(TWS_BATTERY_RIGHT.getBytes());

        int lowBatteryColor = mContext.getColor(LOW_BATTERY_COLOR);
        String leftBattery = "Left 15%";
        String rightBattery = "Right 25%";

        // Default low battery threshold, only left battery is low
        CharSequence summary = mCachedDevice.getTvConnectionSummary(LOW_BATTERY_COLOR);
        assertForegroundColorSpan(summary, 0, 0, leftBattery.length(), lowBatteryColor);
        assertThat(summary.toString()).isEqualTo(leftBattery + " " + rightBattery);

        // Lower threshold, neither battery should be low
        when(mDevice.getMetadata(BluetoothDevice.METADATA_UNTETHERED_LEFT_LOW_BATTERY_THRESHOLD))
                .thenReturn(TWS_LOW_BATTERY_THRESHOLD_LOW.getBytes());
        when(mDevice.getMetadata(BluetoothDevice.METADATA_UNTETHERED_RIGHT_LOW_BATTERY_THRESHOLD))
                .thenReturn(TWS_LOW_BATTERY_THRESHOLD_LOW.getBytes());
        summary = mCachedDevice.getTvConnectionSummary(LOW_BATTERY_COLOR);
        assertNoForegroundColorSpans(summary);
        assertThat(summary.toString()).isEqualTo(leftBattery + " " + rightBattery);

        // Higher Threshold, both batteries are low
        when(mDevice.getMetadata(BluetoothDevice.METADATA_UNTETHERED_LEFT_LOW_BATTERY_THRESHOLD))
                .thenReturn(TWS_LOW_BATTERY_THRESHOLD_HIGH.getBytes());
        when(mDevice.getMetadata(BluetoothDevice.METADATA_UNTETHERED_RIGHT_LOW_BATTERY_THRESHOLD))
                .thenReturn(TWS_LOW_BATTERY_THRESHOLD_HIGH.getBytes());
        summary = mCachedDevice.getTvConnectionSummary(LOW_BATTERY_COLOR);
        assertForegroundColorSpan(summary, 0, 0, leftBattery.length(), lowBatteryColor);
        assertForegroundColorSpan(
                summary,
                1,
                leftBattery.length() + 1,
                leftBattery.length() + rightBattery.length() + 1,
                lowBatteryColor);
        assertThat(summary.toString()).isEqualTo(leftBattery + " " + rightBattery);
    }

    private void assertNoForegroundColorSpans(CharSequence charSequence) {
        if (charSequence instanceof Spannable) {
            Spannable summarySpan = (Spannable) charSequence;
            ForegroundColorSpan[] spans = summarySpan.getSpans(0, summarySpan.length(),
                    ForegroundColorSpan.class);
            assertThat(spans).isEmpty();
        }
    }

    private void assertForegroundColorSpan(CharSequence charSequence, int indexInSpannable,
            int start, int end, int color) {
        assertThat(charSequence).isInstanceOf(Spannable.class);
        Spannable summarySpan = (Spannable) charSequence;
        ForegroundColorSpan[] spans = summarySpan.getSpans(0, summarySpan.length(),
                ForegroundColorSpan.class);
        assertThat(spans[indexInSpannable].getForegroundColor()).isEqualTo(color);
        assertThat(summarySpan.getSpanStart(spans[indexInSpannable])).isEqualTo(start);
        assertThat(summarySpan.getSpanEnd(spans[indexInSpannable])).isEqualTo(end);
    }

    @Test
    public void getCarConnectionSummary_singleProfileConnectDisconnect() {
        // Test without battery level
        // Set PAN profile to be connected and test connection state summary
        updateProfileStatus(mPanProfile, BluetoothProfile.STATE_CONNECTED);
        assertThat(mCachedDevice.getCarConnectionSummary())
                .isEqualTo(mContext.getString(R.string.bluetooth_connected, ""));

        // Set PAN profile to be disconnected and test connection state summary
        updateProfileStatus(mPanProfile, BluetoothProfile.STATE_DISCONNECTED);
        assertThat(mCachedDevice.getCarConnectionSummary())
                .isEqualTo(mContext.getString(R.string.bluetooth_disconnected, ""));

        // Test with battery level
        mBatteryLevel = 10;
        // Set PAN profile to be connected and test connection state summary
        updateProfileStatus(mPanProfile, BluetoothProfile.STATE_CONNECTED);
        assertThat(mCachedDevice.getCarConnectionSummary())
                .isEqualTo(
                        mContext.getString(R.string.bluetooth_connected_battery_level, "10%", ""));

        // Set PAN profile to be disconnected and test connection state summary
        updateProfileStatus(mPanProfile, BluetoothProfile.STATE_DISCONNECTED);
        assertThat(mCachedDevice.getCarConnectionSummary())
                .isEqualTo(mContext.getString(R.string.bluetooth_disconnected, ""));

        // Test with BluetoothDevice.BATTERY_LEVEL_UNKNOWN battery level
        mBatteryLevel = BluetoothDevice.BATTERY_LEVEL_UNKNOWN;

        // Set PAN profile to be connected and test connection state summary
        updateProfileStatus(mPanProfile, BluetoothProfile.STATE_CONNECTED);
        assertThat(mCachedDevice.getCarConnectionSummary())
                .isEqualTo(mContext.getString(R.string.bluetooth_connected, ""));

        // Set PAN profile to be disconnected and test connection state summary
        updateProfileStatus(mPanProfile, BluetoothProfile.STATE_DISCONNECTED);
        assertThat(mCachedDevice.getCarConnectionSummary())
                .isEqualTo(mContext.getString(R.string.bluetooth_disconnected, ""));
    }

    @Test
    public void getCarConnectionSummary_multipleProfileConnectDisconnect() {
        mBatteryLevel = 10;

        // Set HFP, A2DP and PAN profile to be connected and test connection state summary
        updateProfileStatus(mHfpProfile, BluetoothProfile.STATE_CONNECTED);
        updateProfileStatus(mA2dpProfile, BluetoothProfile.STATE_CONNECTED);
        updateProfileStatus(mPanProfile, BluetoothProfile.STATE_CONNECTED);
        assertThat(mCachedDevice.getCarConnectionSummary())
                .isEqualTo(
                        mContext.getString(R.string.bluetooth_connected_battery_level, "10%", ""));

        // Disconnect HFP only and test connection state summary
        updateProfileStatus(mHfpProfile, BluetoothProfile.STATE_DISCONNECTED);
        assertThat(mCachedDevice.getCarConnectionSummary())
                .isEqualTo(
                        mContext.getString(
                                R.string.bluetooth_connected_no_headset_battery_level, "10%", ""));

        // Disconnect A2DP only and test connection state summary
        updateProfileStatus(mHfpProfile, BluetoothProfile.STATE_CONNECTED);
        updateProfileStatus(mA2dpProfile, BluetoothProfile.STATE_DISCONNECTED);
        assertThat(mCachedDevice.getCarConnectionSummary())
                .isEqualTo(
                        mContext.getString(
                                R.string.bluetooth_connected_no_a2dp_battery_level, "10%", ""));

        // Disconnect both HFP and A2DP and test connection state summary
        updateProfileStatus(mHfpProfile, BluetoothProfile.STATE_DISCONNECTED);
        assertThat(mCachedDevice.getCarConnectionSummary())
                .isEqualTo(
                        mContext.getString(
                                R.string.bluetooth_connected_no_headset_no_a2dp_battery_level,
                                "10%",
                                ""));

        // Disconnect all profiles and test connection state summary
        updateProfileStatus(mPanProfile, BluetoothProfile.STATE_DISCONNECTED);
        assertThat(mCachedDevice.getCarConnectionSummary())
                .isEqualTo(mContext.getString(R.string.bluetooth_disconnected));
    }

    @Test
    public void getCarConnectionSummary_singleProfileActiveDeviceA2dp() {
        // Test without battery level
        // Set A2DP profile to be connected and test connection state summary
        updateProfileStatus(mA2dpProfile, BluetoothProfile.STATE_CONNECTED);
        assertThat(mCachedDevice.getCarConnectionSummary()).isEqualTo("Connected");

        // Set device as Active for A2DP and test connection state summary
        mCachedDevice.onActiveDeviceChanged(true, BluetoothProfile.A2DP);
        assertThat(mCachedDevice.getCarConnectionSummary()).isEqualTo("Connected, active (media)");

        // Test with battery level
        mBatteryLevel = 10;
        assertThat(mCachedDevice.getCarConnectionSummary()).isEqualTo(
                "Connected, battery 10%, active (media)");

        // Set A2DP profile to be disconnected and test connection state summary
        updateProfileStatus(mA2dpProfile, BluetoothProfile.STATE_DISCONNECTED);
        assertThat(mCachedDevice.getCarConnectionSummary()).isEqualTo("Disconnected");

        // Test with BluetoothDevice.BATTERY_LEVEL_UNKNOWN battery level
        mBatteryLevel = BluetoothDevice.BATTERY_LEVEL_UNKNOWN;
        // Set A2DP profile to be connected, Active and test connection state summary
        updateProfileStatus(mA2dpProfile, BluetoothProfile.STATE_CONNECTED);
        mCachedDevice.onActiveDeviceChanged(true, BluetoothProfile.A2DP);
        assertThat(mCachedDevice.getCarConnectionSummary()).isEqualTo("Connected, active (media)");

        // Set A2DP profile to be disconnected and test connection state summary
        updateProfileStatus(mA2dpProfile, BluetoothProfile.STATE_DISCONNECTED);
        assertThat(mCachedDevice.getCarConnectionSummary()).isEqualTo("Disconnected");
    }

    @Test
    public void getCarConnectionSummary_singleProfileActiveDeviceHfp() {
        // Test without battery level
        // Set HFP profile to be connected and test connection state summary
        updateProfileStatus(mHfpProfile, BluetoothProfile.STATE_CONNECTED);
        assertThat(mCachedDevice.getCarConnectionSummary()).isEqualTo("Connected");

        // Set device as Active for HFP and test connection state summary
        mCachedDevice.onActiveDeviceChanged(true, BluetoothProfile.HEADSET);
        assertThat(mCachedDevice.getCarConnectionSummary()).isEqualTo("Connected, active (phone)");

        // Test with battery level
        mBatteryLevel = 10;
        assertThat(mCachedDevice.getCarConnectionSummary()).isEqualTo(
                "Connected, battery 10%, active (phone)");

        // Set HFP profile to be disconnected and test connection state summary
        updateProfileStatus(mHfpProfile, BluetoothProfile.STATE_DISCONNECTED);
        assertThat(mCachedDevice.getCarConnectionSummary()).isEqualTo("Disconnected");

        // Test with BluetoothDevice.BATTERY_LEVEL_UNKNOWN battery level
        mBatteryLevel = BluetoothDevice.BATTERY_LEVEL_UNKNOWN;
        // Set HFP profile to be connected, Active and test connection state summary
        updateProfileStatus(mHfpProfile, BluetoothProfile.STATE_CONNECTED);
        mCachedDevice.onActiveDeviceChanged(true, BluetoothProfile.HEADSET);
        assertThat(mCachedDevice.getCarConnectionSummary()).isEqualTo("Connected, active (phone)");

        // Set HFP profile to be disconnected and test connection state summary
        updateProfileStatus(mHfpProfile, BluetoothProfile.STATE_DISCONNECTED);
        assertThat(mCachedDevice.getCarConnectionSummary()).isEqualTo("Disconnected");
    }

    @Test
    public void getCarConnectionSummary_singleProfileActiveDeviceHearingAid() {
        // Test without battery level
        // Set Hearing Aid profile to be connected and test connection state summary
        updateProfileStatus(mHearingAidProfile, BluetoothProfile.STATE_CONNECTED);
        assertThat(mCachedDevice.getCarConnectionSummary()).isEqualTo("Connected");

        // Set device as Active for Hearing Aid and test connection state summary
        mCachedDevice.onActiveDeviceChanged(true, BluetoothProfile.HEARING_AID);
        assertThat(mCachedDevice.getCarConnectionSummary()).isEqualTo("Connected, active");

        // Set Hearing Aid profile to be disconnected and test connection state summary
        mCachedDevice.onActiveDeviceChanged(false, BluetoothProfile.HEARING_AID);
        updateProfileStatus(mHearingAidProfile, BluetoothProfile.STATE_DISCONNECTED);
        assertThat(mCachedDevice.getCarConnectionSummary()).isEqualTo("Disconnected");
    }

    @Test
    public void getCarConnectionSummary_multipleProfilesActiveDevice() {
        // Test without battery level
        // Set A2DP and HFP profiles to be connected and test connection state summary
        updateProfileStatus(mA2dpProfile, BluetoothProfile.STATE_CONNECTED);
        updateProfileStatus(mHfpProfile, BluetoothProfile.STATE_CONNECTED);
        assertThat(mCachedDevice.getCarConnectionSummary()).isEqualTo("Connected");

        // Set device as Active for A2DP and HFP and test connection state summary
        mCachedDevice.onActiveDeviceChanged(true, BluetoothProfile.A2DP);
        mCachedDevice.onActiveDeviceChanged(true, BluetoothProfile.HEADSET);
        assertThat(mCachedDevice.getCarConnectionSummary()).isEqualTo("Connected, active");

        // Test with battery level
        mBatteryLevel = 10;
        assertThat(mCachedDevice.getCarConnectionSummary()).isEqualTo(
                "Connected, battery 10%, active");

        // Disconnect A2DP only and test connection state summary
        mCachedDevice.onActiveDeviceChanged(false, BluetoothProfile.A2DP);
        updateProfileStatus(mA2dpProfile, BluetoothProfile.STATE_DISCONNECTED);
        assertThat(mCachedDevice.getCarConnectionSummary()).isEqualTo(
                "Connected (no media), battery 10%, active (phone)");

        // Disconnect HFP only and test connection state summary
        mCachedDevice.onActiveDeviceChanged(false, BluetoothProfile.HEADSET);
        updateProfileStatus(mHfpProfile, BluetoothProfile.STATE_DISCONNECTED);
        updateProfileStatus(mA2dpProfile, BluetoothProfile.STATE_CONNECTED);
        mCachedDevice.onActiveDeviceChanged(true, BluetoothProfile.A2DP);
        assertThat(mCachedDevice.getCarConnectionSummary()).isEqualTo(
                "Connected (no phone), battery 10%, active (media)");

        // Test with BluetoothDevice.BATTERY_LEVEL_UNKNOWN battery level
        mBatteryLevel = BluetoothDevice.BATTERY_LEVEL_UNKNOWN;
        // Set A2DP and HFP profiles to be connected, Active and test connection state summary
        updateProfileStatus(mA2dpProfile, BluetoothProfile.STATE_CONNECTED);
        updateProfileStatus(mHfpProfile, BluetoothProfile.STATE_CONNECTED);
        mCachedDevice.onActiveDeviceChanged(true, BluetoothProfile.A2DP);
        mCachedDevice.onActiveDeviceChanged(true, BluetoothProfile.HEADSET);
        assertThat(mCachedDevice.getCarConnectionSummary()).isEqualTo("Connected, active");

        // Set A2DP and HFP profiles to be disconnected and test connection state summary
        updateProfileStatus(mA2dpProfile, BluetoothProfile.STATE_DISCONNECTED);
        updateProfileStatus(mHfpProfile, BluetoothProfile.STATE_DISCONNECTED);
        assertThat(mCachedDevice.getCarConnectionSummary()).isEqualTo("Disconnected");
    }

    @Test
    public void getCarConnectionSummary_shortSummary_returnShortSummary() {
        // Test without battery level
        // Set A2DP profile to be connected and test connection state summary
        updateProfileStatus(mA2dpProfile, BluetoothProfile.STATE_CONNECTED);
        assertThat(mCachedDevice.getCarConnectionSummary(true /* shortSummary */))
                .isEqualTo("Connected");

        // Set device as Active for A2DP and test connection state summary
        mCachedDevice.onActiveDeviceChanged(true, BluetoothProfile.A2DP);
        assertThat(mCachedDevice.getCarConnectionSummary(true /* shortSummary */))
                .isEqualTo("Connected");

        // Test with battery level
        mBatteryLevel = 10;
        assertThat(mCachedDevice.getCarConnectionSummary(true /* shortSummary */))
                .isEqualTo("Connected");

        // Set A2DP profile to be disconnected and test connection state summary
        updateProfileStatus(mA2dpProfile, BluetoothProfile.STATE_DISCONNECTED);
        assertThat(mCachedDevice.getCarConnectionSummary(true /* shortSummary */))
                .isEqualTo("Disconnected");
    }

    @Test
    public void deviceName_testAliasNameAvailable() {
        when(mDevice.getAlias()).thenReturn(DEVICE_ALIAS);
        when(mDevice.getName()).thenReturn(DEVICE_NAME);
        CachedBluetoothDevice cachedBluetoothDevice =
                new CachedBluetoothDevice(mContext, mProfileManager, mDevice);
        // Verify alias is returned on getName
        assertThat(cachedBluetoothDevice.getName()).isEqualTo(DEVICE_ALIAS);
        // Verify device is visible
        assertThat(cachedBluetoothDevice.hasHumanReadableName()).isTrue();
    }

    @Test
    public void deviceName_testNameNotAvailable() {
        CachedBluetoothDevice cachedBluetoothDevice =
                new CachedBluetoothDevice(mContext, mProfileManager, mDevice);
        // Verify device address is returned on getName
        assertThat(cachedBluetoothDevice.getName()).isEqualTo(DEVICE_ADDRESS);
        // Verify device is not visible
        assertThat(cachedBluetoothDevice.hasHumanReadableName()).isFalse();
    }

    @Test
    public void deviceName_testRenameDevice() {
        final String[] alias = {DEVICE_ALIAS};
        doAnswer(invocation -> alias[0]).when(mDevice).getAlias();
        doAnswer(invocation -> {
            alias[0] = (String) invocation.getArguments()[0];
            return BluetoothStatusCodes.SUCCESS;
        }).when(mDevice).setAlias(anyString());
        when(mDevice.getName()).thenReturn(DEVICE_NAME);
        CachedBluetoothDevice cachedBluetoothDevice =
                new CachedBluetoothDevice(mContext, mProfileManager, mDevice);
        // Verify alias is returned on getName
        assertThat(cachedBluetoothDevice.getName()).isEqualTo(DEVICE_ALIAS);
        // Verify null name does not get set
        cachedBluetoothDevice.setName(null);
        verify(mDevice, never()).setAlias(any());
        // Verify new name is set properly
        cachedBluetoothDevice.setName(DEVICE_ALIAS_NEW);
        verify(mDevice).setAlias(DEVICE_ALIAS_NEW);
        // Verify new alias is returned on getName
        assertThat(cachedBluetoothDevice.getName()).isEqualTo(DEVICE_ALIAS_NEW);
    }

    @Test
    public void setActive() {
        when(mProfileManager.getA2dpProfile()).thenReturn(mA2dpProfile);
        when(mProfileManager.getHeadsetProfile()).thenReturn(mHfpProfile);
        when(mA2dpProfile.setActiveDevice(any(BluetoothDevice.class))).thenReturn(true);
        when(mHfpProfile.setActiveDevice(any(BluetoothDevice.class))).thenReturn(true);

        assertThat(mCachedDevice.setActive()).isFalse();

        updateProfileStatus(mA2dpProfile, BluetoothProfile.STATE_CONNECTED);
        assertThat(mCachedDevice.setActive()).isTrue();

        updateProfileStatus(mA2dpProfile, BluetoothProfile.STATE_DISCONNECTED);
        updateProfileStatus(mHfpProfile, BluetoothProfile.STATE_CONNECTED);
        assertThat(mCachedDevice.setActive()).isTrue();

        updateProfileStatus(mHfpProfile, BluetoothProfile.STATE_DISCONNECTED);
        assertThat(mCachedDevice.setActive()).isFalse();
    }

    @Test
    public void isA2dpDevice_isA2dpDevice() {
        when(mProfileManager.getA2dpProfile()).thenReturn(mA2dpProfile);
        when(mA2dpProfile.getConnectionStatus(mDevice)).
                thenReturn(BluetoothProfile.STATE_CONNECTED);

        assertThat(mCachedDevice.isConnectedA2dpDevice()).isTrue();
    }

    @Test
    public void isA2dpDevice_isNotA2dpDevice() {
        when(mProfileManager.getA2dpProfile()).thenReturn(mA2dpProfile);
        when(mA2dpProfile.getConnectionStatus(mDevice)).
                thenReturn(BluetoothProfile.STATE_DISCONNECTING);

        assertThat(mCachedDevice.isConnectedA2dpDevice()).isFalse();
    }

    @Test
    public void isHfpDevice_isHfpDevice() {
        when(mProfileManager.getHeadsetProfile()).thenReturn(mHfpProfile);
        when(mHfpProfile.getConnectionStatus(mDevice)).
                thenReturn(BluetoothProfile.STATE_CONNECTED);

        assertThat(mCachedDevice.isConnectedHfpDevice()).isTrue();
    }

    @Test
    public void testIsHfpDevice_isNotHfpDevice() {
        when(mProfileManager.getHeadsetProfile()).thenReturn(mHfpProfile);
        when(mHfpProfile.getConnectionStatus(mDevice)).
                thenReturn(BluetoothProfile.STATE_DISCONNECTING);

        assertThat(mCachedDevice.isConnectedHfpDevice()).isFalse();
    }

    @Test
    public void isConnectedAshaHearingAidDevice_connected_returnTrue() {
        when(mProfileManager.getHearingAidProfile()).thenReturn(mHearingAidProfile);
        when(mHearingAidProfile.getConnectionStatus(mDevice)).
                thenReturn(BluetoothProfile.STATE_CONNECTED);

        assertThat(mCachedDevice.isConnectedAshaHearingAidDevice()).isTrue();
    }

    @Test
    public void isConnectedAshaHearingAidDevice_disconnected_returnFalse() {
        when(mProfileManager.getHearingAidProfile()).thenReturn(mHearingAidProfile);
        when(mHearingAidProfile.getConnectionStatus(mDevice)).
                thenReturn(BluetoothProfile.STATE_DISCONNECTED);

        assertThat(mCachedDevice.isConnectedAshaHearingAidDevice()).isFalse();
    }

    @Test
    public void isConnectedHfpDevice_profileIsNull_returnFalse() {
        when(mProfileManager.getHeadsetProfile()).thenReturn(null);

        assertThat(mCachedDevice.isConnectedHfpDevice()).isFalse();
    }

    @Test
    public void isConnectedA2dpDevice_profileIsNull_returnFalse() {
        when(mProfileManager.getA2dpProfile()).thenReturn(null);

        assertThat(mCachedDevice.isConnectedA2dpDevice()).isFalse();
    }

    @Test
    public void isConnectedAshaHearingAidDevice_profileIsNull_returnFalse() {
        when(mProfileManager.getHearingAidProfile()).thenReturn(null);

        assertThat(mCachedDevice.isConnectedAshaHearingAidDevice()).isFalse();
    }

    @Test
    public void getName_aliasNameNotNull_returnAliasName() {
        when(mDevice.getAlias()).thenReturn(DEVICE_NAME);

        assertThat(mCachedDevice.getName()).isEqualTo(DEVICE_NAME);
    }

    @Test
    public void getName_aliasNameIsNull_returnAddress() {
        when(mDevice.getAlias()).thenReturn(null);

        assertThat(mCachedDevice.getName()).isEqualTo(DEVICE_ADDRESS);
    }

    @Test
    public void setName_setDeviceNameIsNotNull() {
        final String name = "test name";
        when(mDevice.getAlias()).thenReturn(DEVICE_NAME);

        mCachedDevice.setName(name);

        verify(mDevice).setAlias(name);
    }

    @Test
    public void setName_setDeviceNameIsNull() {
        mCachedDevice.setName(null);

        verify(mDevice, never()).setAlias(any());
    }

    @Test
    public void setName_setDeviceNameIsEmpty() {
        mCachedDevice.setName("");

        verify(mDevice, never()).setAlias(any());
    }

    @Test
    public void setName_memberDeviceNameIsSet() {
        when(mDevice.getAlias()).thenReturn(DEVICE_NAME);
        when(mSubDevice.getAlias()).thenReturn(DEVICE_NAME);

        mCachedDevice.addMemberDevice(mSubCachedDevice);
        mCachedDevice.setName(DEVICE_ALIAS);

        verify(mDevice).setAlias(DEVICE_ALIAS);
        verify(mSubDevice).setAlias(DEVICE_ALIAS);
    }

    @Test
    public void setName_subDeviceNameIsSet() {
        when(mDevice.getAlias()).thenReturn(DEVICE_NAME);
        when(mSubDevice.getAlias()).thenReturn(DEVICE_NAME);

        mCachedDevice.setSubDevice(mSubCachedDevice);
        mCachedDevice.setName(DEVICE_ALIAS);

        verify(mDevice).setAlias(DEVICE_ALIAS);
        verify(mSubDevice).setAlias(DEVICE_ALIAS);
    }

    @Test
    public void getProfileConnectionState_nullProfile_returnDisconnected() {
        assertThat(mCachedDevice.getProfileConnectionState(null)).isEqualTo(
                BluetoothProfile.STATE_DISCONNECTED);
    }

    @Test
    public void getProfileConnectionState_profileConnected_returnConnected() {
        doReturn(BluetoothProfile.STATE_CONNECTED).when(mA2dpProfile).getConnectionStatus(
                any(BluetoothDevice.class));

        assertThat(mCachedDevice.getProfileConnectionState(mA2dpProfile)).isEqualTo(
                BluetoothProfile.STATE_CONNECTED);
    }

    @Test
    public void getSubDevice_setSubDevice() {
        mCachedDevice.setSubDevice(mSubCachedDevice);

        assertThat(mCachedDevice.getSubDevice()).isEqualTo(mSubCachedDevice);
    }

    @Test
    public void switchSubDeviceContent() {

        mCachedDevice.mRssi = RSSI_1;
        mCachedDevice.mJustDiscovered = JUSTDISCOVERED_1;
        mCachedDevice.setHearingAidInfo(getLeftAshaHearingAidInfo());
        mSubCachedDevice.mRssi = RSSI_2;
        mSubCachedDevice.mJustDiscovered = JUSTDISCOVERED_2;
        mSubCachedDevice.setHearingAidInfo(getRightAshaHearingAidInfo());
        mCachedDevice.setSubDevice(mSubCachedDevice);

        mCachedDevice.switchSubDeviceContent();

        verify(mCachedDevice).release();
        assertThat(mCachedDevice.mRssi).isEqualTo(RSSI_2);
        assertThat(mCachedDevice.mJustDiscovered).isEqualTo(JUSTDISCOVERED_2);
        assertThat(mCachedDevice.mDevice).isEqualTo(mSubDevice);
        assertThat(mCachedDevice.getDeviceSide()).isEqualTo(HearingAidInfo.DeviceSide.SIDE_RIGHT);
        verify(mSubCachedDevice).release();
        assertThat(mSubCachedDevice.mRssi).isEqualTo(RSSI_1);
        assertThat(mSubCachedDevice.mJustDiscovered).isEqualTo(JUSTDISCOVERED_1);
        assertThat(mSubCachedDevice.mDevice).isEqualTo(mDevice);
        assertThat(mSubCachedDevice.getDeviceSide()).isEqualTo(HearingAidInfo.DeviceSide.SIDE_LEFT);
    }

    @Test
    public void getConnectionSummary_profileConnectedFail_showErrorMessage() {
        final A2dpProfile profile = mock(A2dpProfile.class);
        mCachedDevice.onProfileStateChanged(profile, BluetoothProfile.STATE_CONNECTED);
        mCachedDevice.setProfileConnectedStatus(BluetoothProfile.A2DP, true);

        when(profile.getConnectionStatus(mDevice)).thenReturn(BluetoothProfile.STATE_CONNECTED);

        assertThat(mCachedDevice.getConnectionSummary()).isEqualTo(
                mContext.getString(R.string.profile_connect_timeout_subtext));
    }

    @Test
    public void getTvConnectionSummary_profileConnectedFail_showErrorMessage() {
        final A2dpProfile profile = mock(A2dpProfile.class);
        mCachedDevice.onProfileStateChanged(profile, BluetoothProfile.STATE_CONNECTED);
        mCachedDevice.setProfileConnectedStatus(BluetoothProfile.A2DP, true);

        when(profile.getConnectionStatus(mDevice)).thenReturn(BluetoothProfile.STATE_CONNECTED);

        assertThat(mCachedDevice.getTvConnectionSummary().toString()).isEqualTo(
                mContext.getString(R.string.profile_connect_timeout_subtext));
    }

    @Test
    public void onUuidChanged_bluetoothClassIsNull_shouldNotCrash() {
        mShadowBluetoothAdapter.setUuids(PbapServerProfile.PBAB_CLIENT_UUIDS);
        when(mDevice.getUuids()).thenReturn(PbapServerProfile.PBAB_CLIENT_UUIDS);
        when(mDevice.getBondState()).thenReturn(BluetoothDevice.BOND_BONDED);
        when(mDevice.getPhonebookAccessPermission()).thenReturn(BluetoothDevice.ACCESS_UNKNOWN);
        when(mDevice.getBluetoothClass()).thenReturn(null);

        mCachedDevice.onUuidChanged();

        // Should not crash
    }

    @Test
    public void getDrawableWithDescription_isAdvancedDevice_returnAdvancedIcon() {
        LruCache lruCache = mock(LruCache.class);
        mCachedDevice.mDrawableCache = lruCache;
        BitmapDrawable drawable = mock(BitmapDrawable.class);
        when(lruCache.get("fake_uri")).thenReturn(drawable);
        when(mDevice.getMetadata(BluetoothDevice.METADATA_MAIN_ICON))
                .thenReturn("fake_uri".getBytes());
        when(mDevice.getMetadata(BluetoothDevice.METADATA_IS_UNTETHERED_HEADSET))
                .thenReturn("true".getBytes());

        mCachedDevice.refresh();

        assertThat(mCachedDevice.getDrawableWithDescription().first).isInstanceOf(
                AdaptiveOutlineDrawable.class);
    }

    @Test
    public void getDrawableWithDescription_isNotAdvancedDevice_returnBluetoothIcon() {
        when(mDevice.getMetadata(BluetoothDevice.METADATA_IS_UNTETHERED_HEADSET))
                .thenReturn("false".getBytes());

        mCachedDevice.refresh();

        assertThat(mCachedDevice.getDrawableWithDescription().first).isNotInstanceOf(
                AdaptiveOutlineDrawable.class);
    }

    @Test
    public void releaseLruCache_lruCacheShouldBeRelease() {
        when(mDevice.getMetadata(BluetoothDevice.METADATA_MAIN_ICON))
                .thenReturn("fake_uri".getBytes());
        when(mDevice.getMetadata(BluetoothDevice.METADATA_IS_UNTETHERED_HEADSET))
                .thenReturn("true".getBytes());

        mCachedDevice.refresh();
        mCachedDevice.releaseLruCache();

        assertThat(mCachedDevice.mDrawableCache.size()).isEqualTo(0);
    }

    @Test
    public void switchMemberDeviceContent_switchMainDevice_switchesSuccessful() {
        mCachedDevice.mRssi = RSSI_1;
        mCachedDevice.mJustDiscovered = JUSTDISCOVERED_1;
        mCachedDevice.setHearingAidInfo(getLeftAshaHearingAidInfo());
        mSubCachedDevice.mRssi = RSSI_2;
        mSubCachedDevice.mJustDiscovered = JUSTDISCOVERED_2;
        mSubCachedDevice.setHearingAidInfo(getRightAshaHearingAidInfo());
        mCachedDevice.addMemberDevice(mSubCachedDevice);

        mCachedDevice.switchMemberDeviceContent(mSubCachedDevice);

        assertThat(mCachedDevice.mRssi).isEqualTo(RSSI_2);
        assertThat(mCachedDevice.mJustDiscovered).isEqualTo(JUSTDISCOVERED_2);
        assertThat(mCachedDevice.mDevice).isEqualTo(mSubDevice);
        assertThat(mCachedDevice.getDeviceSide()).isEqualTo(HearingAidInfo.DeviceSide.SIDE_RIGHT);
        verify(mCachedDevice).fillData();
        assertThat(mSubCachedDevice.mRssi).isEqualTo(RSSI_1);
        assertThat(mSubCachedDevice.mJustDiscovered).isEqualTo(JUSTDISCOVERED_1);
        assertThat(mSubCachedDevice.mDevice).isEqualTo(mDevice);
        assertThat(mSubCachedDevice.getDeviceSide()).isEqualTo(HearingAidInfo.DeviceSide.SIDE_LEFT);
        verify(mSubCachedDevice).fillData();
        assertThat(mCachedDevice.getMemberDevice().contains(mSubCachedDevice)).isTrue();
    }

    @Test
    public void isConnectedHearingAidDevice_isConnectedAshaHearingAidDevice_returnTrue() {
        when(mProfileManager.getHearingAidProfile()).thenReturn(mHearingAidProfile);

        updateProfileStatus(mHearingAidProfile, BluetoothProfile.STATE_CONNECTED);

        assertThat(mCachedDevice.isConnectedHearingAidDevice()).isTrue();
    }

    @Test
    public void isConnectedHearingAidDevice_isConnectedLeAudioHearingAidDevice_returnTrue() {
        when(mProfileManager.getHapClientProfile()).thenReturn(mHapClientProfile);
        when(mProfileManager.getLeAudioProfile()).thenReturn(mLeAudioProfile);

        updateProfileStatus(mHapClientProfile, BluetoothProfile.STATE_CONNECTED);
        updateProfileStatus(mLeAudioProfile, BluetoothProfile.STATE_CONNECTED);

        assertThat(mCachedDevice.isConnectedHearingAidDevice()).isTrue();
    }

    @Test
    public void isConnectedHearingAidDevice_isNotAnyConnectedHearingAidDevice_returnFalse() {
        when(mProfileManager.getHearingAidProfile()).thenReturn(mHearingAidProfile);
        when(mProfileManager.getHapClientProfile()).thenReturn(mHapClientProfile);
        when(mProfileManager.getLeAudioProfile()).thenReturn(mLeAudioProfile);

        updateProfileStatus(mHearingAidProfile, BluetoothProfile.STATE_DISCONNECTED);
        updateProfileStatus(mHapClientProfile, BluetoothProfile.STATE_DISCONNECTED);
        updateProfileStatus(mLeAudioProfile, BluetoothProfile.STATE_DISCONNECTED);

        assertThat(mCachedDevice.isConnectedHearingAidDevice()).isFalse();
    }

    @Test
    public void leAudioHidDevice_leAudioEnabled_setPreferredTransportToLE() {

        when(mProfileManager.getHidProfile()).thenReturn(mHidProfile);
        when(mProfileManager.getLeAudioProfile()).thenReturn(mLeAudioProfile);
        when(mLeAudioProfile.isEnabled(mDevice)).thenReturn(true);

        updateProfileStatus(mHidProfile, BluetoothProfile.STATE_CONNECTED);
        updateProfileStatus(mLeAudioProfile, BluetoothProfile.STATE_CONNECTED);

        verify(mHidProfile).setPreferredTransport(mDevice, BluetoothDevice.TRANSPORT_LE);
    }

    @Test
    public void leAudioHidDevice_leAudioDisabled_setPreferredTransportToBredr() {
        when(mProfileManager.getHidProfile()).thenReturn(mHidProfile);
        when(mProfileManager.getLeAudioProfile()).thenReturn(mLeAudioProfile);
        when(mLeAudioProfile.isEnabled(mDevice)).thenReturn(false);

        updateProfileStatus(mLeAudioProfile, BluetoothProfile.STATE_CONNECTED);
        updateProfileStatus(mLeAudioProfile, BluetoothProfile.STATE_DISCONNECTED);
        updateProfileStatus(mHidProfile, BluetoothProfile.STATE_CONNECTED);

        verify(mHidProfile).setPreferredTransport(mDevice, BluetoothDevice.TRANSPORT_BREDR);
    }

    @Test
    public void getConnectionSummary_isBroadcastPrimary_returnActive() {
        when(mBroadcast.isEnabled(any())).thenReturn(true);
        when(mCachedDevice.getDevice()).thenReturn(mDevice);
        Settings.Secure.putInt(
                mContext.getContentResolver(),
                "bluetooth_le_broadcast_fallback_active_group_id",
                1);

        List<Long> bisSyncState = new ArrayList<>();
        bisSyncState.add(1L);
        when(mLeBroadcastReceiveState.getBisSyncState()).thenReturn(bisSyncState);
        List<BluetoothLeBroadcastReceiveState> sourceList = new ArrayList<>();
        sourceList.add(mLeBroadcastReceiveState);
        when(mAssistant.getAllSources(any())).thenReturn(sourceList);

        when(mCachedDevice.getGroupId())
                .thenReturn(
                        Settings.Secure.getInt(
                                mContext.getContentResolver(),
                                "bluetooth_le_broadcast_fallback_active_group_id",
                                BluetoothCsipSetCoordinator.GROUP_ID_INVALID));

        assertThat(mCachedDevice.getConnectionSummary(false))
                .isEqualTo(mContext.getString(R.string.bluetooth_active_no_battery_level));
    }

    @Test
    public void getConnectionSummary_isBroadcastNotPrimary_returnActiveMedia() {
        when(mBroadcast.isEnabled(any())).thenReturn(true);
        when(mCachedDevice.getDevice()).thenReturn(mDevice);
        Settings.Secure.putInt(
                mContext.getContentResolver(),
                "bluetooth_le_broadcast_fallback_active_group_id",
                1);

        List<Long> bisSyncState = new ArrayList<>();
        bisSyncState.add(1L);
        when(mLeBroadcastReceiveState.getBisSyncState()).thenReturn(bisSyncState);
        List<BluetoothLeBroadcastReceiveState> sourceList = new ArrayList<>();
        sourceList.add(mLeBroadcastReceiveState);
        when(mAssistant.getAllSources(any())).thenReturn(sourceList);

        when(mCachedDevice.getGroupId()).thenReturn(BluetoothCsipSetCoordinator.GROUP_ID_INVALID);

        assertThat(mCachedDevice.getConnectionSummary(false))
                .isEqualTo(
                        mContext.getString(R.string.bluetooth_active_media_only_no_battery_level));
    }

    @Test
    public void getConnectionSummary_supportBroadcastConnected_returnConnectedSupportLe() {
        when(mBroadcast.isEnabled(any())).thenReturn(true);
        when(mCachedDevice.getDevice()).thenReturn(mDevice);
        when(mLeAudioProfile.isEnabled(mDevice)).thenReturn(true);

        when(mCachedDevice.getProfiles()).thenReturn(ImmutableList.of(mLeAudioProfile));
        when(mCachedDevice.isConnected()).thenReturn(true);

        assertThat(mCachedDevice.getConnectionSummary(false))
                .isEqualTo(mContext.getString(R.string.bluetooth_no_battery_level_lea_support));
    }

    @Test
    public void getConnectionSummary_supportBroadcastNotConnected_returnSupportLe() {
        when(mBroadcast.isEnabled(any())).thenReturn(true);
        when(mCachedDevice.getDevice()).thenReturn(mDevice);
        when(mLeAudioProfile.isEnabled(mDevice)).thenReturn(true);

        when(mCachedDevice.getProfiles()).thenReturn(ImmutableList.of(mLeAudioProfile));
        when(mCachedDevice.isConnected()).thenReturn(false);

        assertThat(mCachedDevice.getConnectionSummary(false))
                .isEqualTo(mContext.getString(R.string.bluetooth_saved_device_lea_support));
    }

    @Test
    public void getConnectionSummary_doNotSupportBroadcast_returnNull() {
        when(mBroadcast.isEnabled(any())).thenReturn(true);

        when(mCachedDevice.getProfiles()).thenReturn(ImmutableList.of());

        assertThat(mCachedDevice.getConnectionSummary(false)).isNull();
    }

    private void updateProfileStatus(LocalBluetoothProfile profile, int status) {
        doReturn(status).when(profile).getConnectionStatus(mDevice);
        mCachedDevice.onProfileStateChanged(profile, status);
        updateConnectionStatus(mCachedDevice);
    }

    private void updateSubDeviceProfileStatus(LocalBluetoothProfile profile, int status) {
        doReturn(status).when(profile).getConnectionStatus(mSubDevice);
        mSubCachedDevice.onProfileStateChanged(profile, status);
        updateConnectionStatus(mSubCachedDevice);
    }

    private void updateConnectionStatus(CachedBluetoothDevice cachedBluetoothDevice) {
        for (LocalBluetoothProfile profile : cachedBluetoothDevice.getProfiles()) {
            int status = cachedBluetoothDevice.getProfileConnectionState(profile);
            if (status == BluetoothProfile.STATE_CONNECTED) {
                when(cachedBluetoothDevice.getDevice().isConnected()).thenReturn(true);
                return;
            }
        }
        when(cachedBluetoothDevice.getDevice().isConnected()).thenReturn(false);
    }

    private HearingAidInfo getLeftAshaHearingAidInfo() {
        return new HearingAidInfo.Builder()
                .setAshaDeviceSide(HearingAidProfile.DeviceSide.SIDE_LEFT)
                .build();
    }

    private HearingAidInfo getRightAshaHearingAidInfo() {
        return new HearingAidInfo.Builder()
                .setAshaDeviceSide(HearingAidProfile.DeviceSide.SIDE_RIGHT)
                .build();
    }

    private HearingAidInfo getLeftLeAudioHearingAidInfo() {
        return new HearingAidInfo.Builder()
                .setLeAudioLocation(BluetoothLeAudio.AUDIO_LOCATION_SIDE_LEFT)
                .build();
    }
}
