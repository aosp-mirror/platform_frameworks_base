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
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothProfile;
import android.bluetooth.BluetoothStatusCodes;
import android.content.Context;
import android.graphics.drawable.BitmapDrawable;
import android.media.AudioManager;
import android.util.LruCache;

import com.android.settingslib.R;
import com.android.settingslib.testutils.shadow.ShadowBluetoothAdapter;
import com.android.settingslib.widget.AdaptiveOutlineDrawable;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.shadow.api.Shadow;

@RunWith(RobolectricTestRunner.class)
@Config(shadows = {ShadowBluetoothAdapter.class})
public class CachedBluetoothDeviceTest {
    private static final String DEVICE_NAME = "TestName";
    private static final String DEVICE_ALIAS = "TestAlias";
    private static final String DEVICE_ADDRESS = "AA:BB:CC:DD:EE:FF";
    private static final String DEVICE_ALIAS_NEW = "TestAliasNew";
    private static final String TWS_BATTERY_LEFT = "15";
    private static final String TWS_BATTERY_RIGHT = "25";
    private static final short RSSI_1 = 10;
    private static final short RSSI_2 = 11;
    private static final boolean JUSTDISCOVERED_1 = true;
    private static final boolean JUSTDISCOVERED_2 = false;
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
    private BluetoothDevice mDevice;
    @Mock
    private BluetoothDevice mSubDevice;
    private CachedBluetoothDevice mCachedDevice;
    private CachedBluetoothDevice mSubCachedDevice;
    private AudioManager mAudioManager;
    private Context mContext;
    private int mBatteryLevel = BluetoothDevice.BATTERY_LEVEL_UNKNOWN;
    private ShadowBluetoothAdapter mShadowBluetoothAdapter;

    @Before
    public void setUp() {
        MockitoAnnotations.initMocks(this);
        mContext = RuntimeEnvironment.application;
        mAudioManager = mContext.getSystemService(AudioManager.class);
        mShadowBluetoothAdapter = Shadow.extract(BluetoothAdapter.getDefaultAdapter());
        when(mDevice.getAddress()).thenReturn(DEVICE_ADDRESS);
        when(mHfpProfile.isProfileReady()).thenReturn(true);
        when(mA2dpProfile.isProfileReady()).thenReturn(true);
        when(mPanProfile.isProfileReady()).thenReturn(true);
        when(mHearingAidProfile.isProfileReady()).thenReturn(true);
        mCachedDevice = spy(new CachedBluetoothDevice(mContext, mProfileManager, mDevice));
        mSubCachedDevice = spy(new CachedBluetoothDevice(mContext, mProfileManager, mSubDevice));
        doAnswer((invocation) -> mBatteryLevel).when(mCachedDevice).getBatteryLevel();
        doAnswer((invocation) -> mBatteryLevel).when(mSubCachedDevice).getBatteryLevel();
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
    public void getConnectionSummary_testSingleProfileActiveDeviceA2dp() {
        // Test without battery level
        // Set A2DP profile to be connected and test connection state summary
        updateProfileStatus(mA2dpProfile, BluetoothProfile.STATE_CONNECTED);
        assertThat(mCachedDevice.getConnectionSummary()).isNull();

        // Set device as Active for A2DP and test connection state summary
        mCachedDevice.onActiveDeviceChanged(true, BluetoothProfile.A2DP);
        assertThat(mCachedDevice.getConnectionSummary()).isEqualTo("Active");

        // Test with battery level
        mBatteryLevel = 10;
        assertThat(mCachedDevice.getConnectionSummary()).isEqualTo(
                "Active, 10% battery");

        // Set A2DP profile to be disconnected and test connection state summary
        updateProfileStatus(mA2dpProfile, BluetoothProfile.STATE_DISCONNECTED);
        assertThat(mCachedDevice.getConnectionSummary()).isNull();

        // Test with BluetoothDevice.BATTERY_LEVEL_UNKNOWN battery level
        mBatteryLevel = BluetoothDevice.BATTERY_LEVEL_UNKNOWN;
        // Set A2DP profile to be connected, Active and test connection state summary
        updateProfileStatus(mA2dpProfile, BluetoothProfile.STATE_CONNECTED);
        mCachedDevice.onActiveDeviceChanged(true, BluetoothProfile.A2DP);
        assertThat(mCachedDevice.getConnectionSummary()).isEqualTo("Active");

        // Set A2DP profile to be disconnected and test connection state summary
        updateProfileStatus(mA2dpProfile, BluetoothProfile.STATE_DISCONNECTED);
        assertThat(mCachedDevice.getConnectionSummary()).isNull();
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
    public void getConnectionSummary_testSingleProfileActiveDeviceHfp() {
        // Test without battery level
        // Set HFP profile to be connected and test connection state summary
        updateProfileStatus(mHfpProfile, BluetoothProfile.STATE_CONNECTED);
        assertThat(mCachedDevice.getConnectionSummary()).isNull();

        // Set device as Active for HFP and test connection state summary
        mCachedDevice.onAudioModeChanged();
        mAudioManager.setMode(AudioManager.MODE_IN_CALL);
        mCachedDevice.onActiveDeviceChanged(true, BluetoothProfile.HEADSET);
        assertThat(mCachedDevice.getConnectionSummary()).isEqualTo("Active");

        // Test with battery level
        mBatteryLevel = 10;
        assertThat(mCachedDevice.getConnectionSummary()).isEqualTo("Active, 10% battery");

        // Set HFP profile to be disconnected and test connection state summary
        updateProfileStatus(mHfpProfile, BluetoothProfile.STATE_DISCONNECTED);
        assertThat(mCachedDevice.getConnectionSummary()).isNull();

        // Test with BluetoothDevice.BATTERY_LEVEL_UNKNOWN battery level
        mBatteryLevel = BluetoothDevice.BATTERY_LEVEL_UNKNOWN;
        // Set HFP profile to be connected, Active and test connection state summary
        updateProfileStatus(mHfpProfile, BluetoothProfile.STATE_CONNECTED);
        mCachedDevice.onActiveDeviceChanged(true, BluetoothProfile.HEADSET);
        assertThat(mCachedDevice.getConnectionSummary()).isEqualTo("Active");

        // Set HFP profile to be disconnected and test connection state summary
        updateProfileStatus(mHfpProfile, BluetoothProfile.STATE_DISCONNECTED);
        assertThat(mCachedDevice.getConnectionSummary()).isNull();
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
    public void getConnectionSummary_testSingleProfileActiveDeviceHearingAid() {
        // Test without battery level
        // Set Hearing Aid profile to be connected and test connection state summary
        updateProfileStatus(mHearingAidProfile, BluetoothProfile.STATE_CONNECTED);
        assertThat(mCachedDevice.getConnectionSummary()).isNull();

        // Set device as Active for Hearing Aid and test connection state summary
        mCachedDevice.setDeviceSide(HearingAidProfile.DeviceSide.SIDE_LEFT);
        mCachedDevice.onActiveDeviceChanged(true, BluetoothProfile.HEARING_AID);
        assertThat(mCachedDevice.getConnectionSummary()).isEqualTo("Active, left only");

        // Set Hearing Aid profile to be disconnected and test connection state summary
        mCachedDevice.onActiveDeviceChanged(false, BluetoothProfile.HEARING_AID);
        mCachedDevice.
                onProfileStateChanged(mHearingAidProfile, BluetoothProfile.STATE_DISCONNECTED);
        assertThat(mCachedDevice.getConnectionSummary()).isNull();
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
    public void getConnectionSummary_testHearingAidBatteryWithoutInCall_returnActiveBattery() {
        // Arrange:
        //   1. Profile:       {HEARING_AID, Connected, Active}
        //   2. Battery Level: 10
        //   3. Audio Manager: Normal (Without In Call)
        updateProfileStatus(mHearingAidProfile, BluetoothProfile.STATE_CONNECTED);
        mCachedDevice.onActiveDeviceChanged(true, BluetoothProfile.HEARING_AID);
        mBatteryLevel = 10;

        // Act & Assert:
        //   Get "Active, 10% battery" result with Battery Level 10.
        assertThat(mCachedDevice.getConnectionSummary()).isEqualTo("Active, 10% battery");
    }

    @Test
    public void getConnectionSummary_testHearingAidRightEarInCall_returnActiveRightEar() {
        // Arrange:
        //   1. Profile:       {HEARING_AID, Connected, Active, Right ear}
        //   2. Audio Manager: In Call
        updateProfileStatus(mHearingAidProfile, BluetoothProfile.STATE_CONNECTED);
        mCachedDevice.setDeviceSide(HearingAidProfile.DeviceSide.SIDE_RIGHT);
        mCachedDevice.onActiveDeviceChanged(true, BluetoothProfile.HEARING_AID);
        mAudioManager.setMode(AudioManager.MODE_IN_CALL);

        // Act & Assert:
        //   Get "Active" result without Battery Level.
        assertThat(mCachedDevice.getConnectionSummary()).isEqualTo("Active, right only");
    }

    @Test
    public void getConnectionSummary_testHearingAidBothEarInCall_returnActiveBothEar() {
        // Arrange:
        //   1. Profile:       {HEARING_AID, Connected, Active, Both ear}
        //   2. Audio Manager: In Call
        mCachedDevice.setDeviceSide(HearingAidProfile.DeviceSide.SIDE_RIGHT);
        updateProfileStatus(mHearingAidProfile, BluetoothProfile.STATE_CONNECTED);
        mSubCachedDevice.setDeviceSide(HearingAidProfile.DeviceSide.SIDE_LEFT);
        updateSubDeviceProfileStatus(mHearingAidProfile, BluetoothProfile.STATE_CONNECTED);
        mCachedDevice.setSubDevice(mSubCachedDevice);
        mCachedDevice.onActiveDeviceChanged(true, BluetoothProfile.HEARING_AID);
        mAudioManager.setMode(AudioManager.MODE_IN_CALL);

        // Act & Assert:
        //   Get "Active" result without Battery Level.
        assertThat(mCachedDevice.getConnectionSummary()).isEqualTo("Active, left and right");
    }

    @Test
    public void getConnectionSummary_testHearingAidBatteryInCall_returnActiveBattery() {
        // Arrange:
        //   1. Profile:       {HEARING_AID, Connected, Active}
        //   2. Battery Level: 10
        //   3. Audio Manager: In Call
        updateProfileStatus(mHearingAidProfile, BluetoothProfile.STATE_CONNECTED);
        mCachedDevice.onActiveDeviceChanged(true, BluetoothProfile.HEARING_AID);
        mAudioManager.setMode(AudioManager.MODE_IN_CALL);
        mBatteryLevel = 10;

        // Act & Assert:
        //   Get "Active, 10% battery" result with Battery Level 10.
        assertThat(mCachedDevice.getConnectionSummary()).isEqualTo("Active, 10% battery");
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
        assertThat(mCachedDevice.getConnectionSummary()).isEqualTo("Active");

        // Test with battery level
        mBatteryLevel = 10;
        assertThat(mCachedDevice.getConnectionSummary()).isEqualTo(
                "Active, 10% battery");

        // Disconnect A2DP only and test connection state summary
        mCachedDevice.onActiveDeviceChanged(false, BluetoothProfile.A2DP);
        updateProfileStatus(mA2dpProfile, BluetoothProfile.STATE_DISCONNECTED);
        assertThat(mCachedDevice.getConnectionSummary()).isEqualTo(
                "10% battery");

        // Disconnect HFP only and test connection state summary
        mCachedDevice.onActiveDeviceChanged(false, BluetoothProfile.HEADSET);
        updateProfileStatus(mHfpProfile, BluetoothProfile.STATE_DISCONNECTED);
        updateProfileStatus(mA2dpProfile, BluetoothProfile.STATE_CONNECTED);
        mCachedDevice.onActiveDeviceChanged(true, BluetoothProfile.A2DP);
        assertThat(mCachedDevice.getConnectionSummary()).isEqualTo(
                "Active, 10% battery");

        // Test with BluetoothDevice.BATTERY_LEVEL_UNKNOWN battery level
        mBatteryLevel = BluetoothDevice.BATTERY_LEVEL_UNKNOWN;
        // Set A2DP and HFP profiles to be connected, Active and test connection state summary
        updateProfileStatus(mA2dpProfile, BluetoothProfile.STATE_CONNECTED);
        updateProfileStatus(mHfpProfile, BluetoothProfile.STATE_CONNECTED);
        mCachedDevice.onActiveDeviceChanged(true, BluetoothProfile.A2DP);
        mCachedDevice.onActiveDeviceChanged(true, BluetoothProfile.HEADSET);
        assertThat(mCachedDevice.getConnectionSummary()).isEqualTo("Active");

        // Set A2DP and HFP profiles to be disconnected and test connection state summary
        updateProfileStatus(mA2dpProfile, BluetoothProfile.STATE_DISCONNECTED);
        updateProfileStatus(mHfpProfile, BluetoothProfile.STATE_DISCONNECTED);
        assertThat(mCachedDevice.getConnectionSummary()).isNull();
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
    public void getConnectionSummary_trueWirelessActiveDeviceWithBattery_returnActiveWithBattery() {
        updateProfileStatus(mA2dpProfile, BluetoothProfile.STATE_CONNECTED);
        updateProfileStatus(mHfpProfile, BluetoothProfile.STATE_CONNECTED);
        updateProfileStatus(mHearingAidProfile, BluetoothProfile.STATE_CONNECTED);
        when(mDevice.getBondState()).thenReturn(BluetoothDevice.BOND_BONDED);
        mCachedDevice.onActiveDeviceChanged(true, BluetoothProfile.HEARING_AID);
        when(mDevice.getMetadata(BluetoothDevice.METADATA_IS_UNTETHERED_HEADSET)).thenReturn(
                "true".getBytes());
        when(mDevice.getMetadata(BluetoothDevice.METADATA_UNTETHERED_LEFT_BATTERY)).thenReturn(
                TWS_BATTERY_LEFT.getBytes());
        when(mDevice.getMetadata(BluetoothDevice.METADATA_UNTETHERED_RIGHT_BATTERY)).thenReturn(
                TWS_BATTERY_RIGHT.getBytes());

        assertThat(mCachedDevice.getConnectionSummary()).isEqualTo(
                "Active, L: 15% battery, R: 25% battery");
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

        assertThat(mCachedDevice.getConnectionSummary()).isEqualTo(
                "L: 15% battery, R: 25% battery");
    }

    @Test
    public void getCarConnectionSummary_singleProfileConnectDisconnect() {
        // Test without battery level
        // Set PAN profile to be connected and test connection state summary
        updateProfileStatus(mPanProfile, BluetoothProfile.STATE_CONNECTED);
        assertThat(mCachedDevice.getCarConnectionSummary()).isEqualTo("Connected");

        // Set PAN profile to be disconnected and test connection state summary
        updateProfileStatus(mPanProfile, BluetoothProfile.STATE_DISCONNECTED);
        assertThat(mCachedDevice.getCarConnectionSummary()).isEqualTo("Disconnected");

        // Test with battery level
        mBatteryLevel = 10;
        // Set PAN profile to be connected and test connection state summary
        updateProfileStatus(mPanProfile, BluetoothProfile.STATE_CONNECTED);
        assertThat(mCachedDevice.getCarConnectionSummary()).isEqualTo("Connected, battery 10%");

        // Set PAN profile to be disconnected and test connection state summary
        updateProfileStatus(mPanProfile, BluetoothProfile.STATE_DISCONNECTED);
        assertThat(mCachedDevice.getCarConnectionSummary()).isEqualTo("Disconnected");

        // Test with BluetoothDevice.BATTERY_LEVEL_UNKNOWN battery level
        mBatteryLevel = BluetoothDevice.BATTERY_LEVEL_UNKNOWN;

        // Set PAN profile to be connected and test connection state summary
        updateProfileStatus(mPanProfile, BluetoothProfile.STATE_CONNECTED);
        assertThat(mCachedDevice.getCarConnectionSummary()).isEqualTo("Connected");

        // Set PAN profile to be disconnected and test connection state summary
        updateProfileStatus(mPanProfile, BluetoothProfile.STATE_DISCONNECTED);
        assertThat(mCachedDevice.getCarConnectionSummary()).isEqualTo("Disconnected");
    }

    @Test
    public void getCarConnectionSummary_multipleProfileConnectDisconnect() {
        mBatteryLevel = 10;

        // Set HFP, A2DP and PAN profile to be connected and test connection state summary
        updateProfileStatus(mHfpProfile, BluetoothProfile.STATE_CONNECTED);
        updateProfileStatus(mA2dpProfile, BluetoothProfile.STATE_CONNECTED);
        updateProfileStatus(mPanProfile, BluetoothProfile.STATE_CONNECTED);
        assertThat(mCachedDevice.getCarConnectionSummary()).isEqualTo("Connected, battery 10%");

        // Disconnect HFP only and test connection state summary
        updateProfileStatus(mHfpProfile, BluetoothProfile.STATE_DISCONNECTED);
        assertThat(mCachedDevice.getCarConnectionSummary()).isEqualTo(
                "Connected (no phone), battery 10%");

        // Disconnect A2DP only and test connection state summary
        updateProfileStatus(mHfpProfile, BluetoothProfile.STATE_CONNECTED);
        updateProfileStatus(mA2dpProfile, BluetoothProfile.STATE_DISCONNECTED);
        assertThat(mCachedDevice.getCarConnectionSummary()).isEqualTo(
                "Connected (no media), battery 10%");

        // Disconnect both HFP and A2DP and test connection state summary
        updateProfileStatus(mHfpProfile, BluetoothProfile.STATE_DISCONNECTED);
        assertThat(mCachedDevice.getCarConnectionSummary()).isEqualTo(
                "Connected (no phone or media), battery 10%");

        // Disconnect all profiles and test connection state summary
        updateProfileStatus(mPanProfile, BluetoothProfile.STATE_DISCONNECTED);
        assertThat(mCachedDevice.getCarConnectionSummary()).isEqualTo("Disconnected");
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
    public void isConnectedHearingAidDevice_connected_returnTrue() {
        when(mProfileManager.getHearingAidProfile()).thenReturn(mHearingAidProfile);
        when(mHearingAidProfile.getConnectionStatus(mDevice)).
                thenReturn(BluetoothProfile.STATE_CONNECTED);

        assertThat(mCachedDevice.isConnectedHearingAidDevice()).isTrue();
    }

    @Test
    public void isConnectedHearingAidDevice_disconnected_returnFalse() {
        when(mProfileManager.getHearingAidProfile()).thenReturn(mHearingAidProfile);
        when(mHearingAidProfile.getConnectionStatus(mDevice)).
                thenReturn(BluetoothProfile.STATE_DISCONNECTED);

        assertThat(mCachedDevice.isConnectedHearingAidDevice()).isFalse();
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
    public void isConnectedHearingAidDevice_profileIsNull_returnFalse() {
        when(mProfileManager.getHearingAidProfile()).thenReturn(null);

        assertThat(mCachedDevice.isConnectedHearingAidDevice()).isFalse();
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

    private void updateProfileStatus(LocalBluetoothProfile profile, int status) {
        doReturn(status).when(profile).getConnectionStatus(mDevice);
        mCachedDevice.onProfileStateChanged(profile, status);
    }

    private void updateSubDeviceProfileStatus(LocalBluetoothProfile profile, int status) {
        doReturn(status).when(profile).getConnectionStatus(mSubDevice);
        mSubCachedDevice.onProfileStateChanged(profile, status);
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
        mSubCachedDevice.mRssi = RSSI_2;
        mSubCachedDevice.mJustDiscovered = JUSTDISCOVERED_2;
        mCachedDevice.setSubDevice(mSubCachedDevice);

        assertThat(mCachedDevice.mRssi).isEqualTo(RSSI_1);
        assertThat(mCachedDevice.mJustDiscovered).isEqualTo(JUSTDISCOVERED_1);
        assertThat(mCachedDevice.mDevice).isEqualTo(mDevice);
        assertThat(mSubCachedDevice.mRssi).isEqualTo(RSSI_2);
        assertThat(mSubCachedDevice.mJustDiscovered).isEqualTo(JUSTDISCOVERED_2);
        assertThat(mSubCachedDevice.mDevice).isEqualTo(mSubDevice);
        mCachedDevice.switchSubDeviceContent();

        assertThat(mCachedDevice.mRssi).isEqualTo(RSSI_2);
        assertThat(mCachedDevice.mJustDiscovered).isEqualTo(JUSTDISCOVERED_2);
        assertThat(mCachedDevice.mDevice).isEqualTo(mSubDevice);
        assertThat(mSubCachedDevice.mRssi).isEqualTo(RSSI_1);
        assertThat(mSubCachedDevice.mJustDiscovered).isEqualTo(JUSTDISCOVERED_1);
        assertThat(mSubCachedDevice.mDevice).isEqualTo(mDevice);
    }

    @Test
    public void getConnectionSummary_profileConnectedFail_showErrorMessage() {
        final A2dpProfile profle = mock(A2dpProfile.class);
        mCachedDevice.onProfileStateChanged(profle, BluetoothProfile.STATE_CONNECTED);
        mCachedDevice.setProfileConnectedStatus(BluetoothProfile.A2DP, true);

        when(profle.getConnectionStatus(mDevice)).thenReturn(BluetoothProfile.STATE_CONNECTED);

        assertThat(mCachedDevice.getConnectionSummary()).isEqualTo(
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
}
