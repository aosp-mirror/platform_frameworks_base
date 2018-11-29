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
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.bluetooth.BluetoothA2dp;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothHeadset;
import android.bluetooth.BluetoothHearingAid;
import android.bluetooth.BluetoothPan;
import android.bluetooth.BluetoothProfile;
import android.bluetooth.BluetoothUuid;
import android.content.Context;
import android.content.Intent;
import android.os.ParcelUuid;

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
public class LocalBluetoothProfileManagerTest {
    private final static long HISYNCID = 10;

    @Mock
    private CachedBluetoothDeviceManager mDeviceManager;
    @Mock
    private BluetoothEventManager mEventManager;
    @Mock
    private BluetoothDevice mDevice;
    @Mock
    private CachedBluetoothDevice mCachedBluetoothDevice;

    private Context mContext;
    private Intent mIntent;
    private LocalBluetoothAdapter mLocalBluetoothAdapter;
    private LocalBluetoothProfileManager mProfileManager;
    private ShadowBluetoothAdapter mShadowBluetoothAdapter;

    @Before
    public void setUp() {
        MockitoAnnotations.initMocks(this);
        mContext = spy(RuntimeEnvironment.application);
        mLocalBluetoothAdapter = LocalBluetoothAdapter.getInstance();
        mEventManager = spy(new BluetoothEventManager(mLocalBluetoothAdapter, mDeviceManager,
                mContext, /* handler= */ null, /* userHandle= */ null));
        mShadowBluetoothAdapter = Shadow.extract(BluetoothAdapter.getDefaultAdapter());
        when(mDeviceManager.findDevice(mDevice)).thenReturn(mCachedBluetoothDevice);
        when(mCachedBluetoothDevice.getDevice()).thenReturn(mDevice);
        mProfileManager = new LocalBluetoothProfileManager(mContext, mLocalBluetoothAdapter,
                mDeviceManager, mEventManager);
    }

    /**
     * Verify HID and HID Device profiles are not null without running updateUuids()
     */
    @Test
    public void constructor_initiateHidAndHidDeviceProfile() {
        mShadowBluetoothAdapter.setSupportedProfiles(generateList(
                new int[] {BluetoothProfile.HID_HOST, BluetoothProfile.HID_DEVICE}));
        mProfileManager.updateLocalProfiles();

        assertThat(mProfileManager.getHidProfile()).isNotNull();
        assertThat(mProfileManager.getHidDeviceProfile()).isNotNull();
    }

    @Test
    public void constructor_doNotUpdateProfiles() {
        mProfileManager = spy(new LocalBluetoothProfileManager(mContext, mLocalBluetoothAdapter,
                mDeviceManager, mEventManager));

        verify(mProfileManager, never()).updateLocalProfiles();
    }

    /**
     * Verify updateLocalProfiles() for a local A2DP source adds A2dpProfile
     */
    @Test
    public void updateLocalProfiles_addA2dpToLocalProfiles() {
        mProfileManager.updateLocalProfiles();
        assertThat(mProfileManager.getA2dpProfile()).isNull();
        assertThat(mProfileManager.getHeadsetProfile()).isNull();

        mShadowBluetoothAdapter.setSupportedProfiles(generateList(
                new int[] {BluetoothProfile.A2DP}));
        mProfileManager.updateLocalProfiles();

        assertThat(mProfileManager.getA2dpProfile()).isNotNull();
        assertThat(mProfileManager.getHeadsetProfile()).isNull();
    }

    /**
     * Verify updateProfiles() for a remote HID device updates profiles and removedProfiles
     */
    @Test
    public void updateProfiles_addHidProfileForRemoteDevice() {
        mShadowBluetoothAdapter.setSupportedProfiles(generateList(
                new int[] {BluetoothProfile.HID_HOST}));
        mProfileManager.updateLocalProfiles();
        ParcelUuid[] uuids = new ParcelUuid[]{BluetoothUuid.Hid};
        ParcelUuid[] localUuids = new ParcelUuid[]{};
        List<LocalBluetoothProfile> profiles = new ArrayList<>();
        List<LocalBluetoothProfile> removedProfiles = new ArrayList<>();

        mProfileManager.updateProfiles(uuids, localUuids, profiles, removedProfiles, false,
                mDevice);

        assertThat(mProfileManager.getHidProfile()).isNotNull();
        assertThat(profiles.contains(mProfileManager.getHidProfile())).isTrue();
        assertThat(removedProfiles.contains(mProfileManager.getHidProfile())).isFalse();
    }

    /**
     * Verify BluetoothA2dp.ACTION_CONNECTION_STATE_CHANGED with uuid intent will dispatch to
     * profile connection state changed callback
     */
    @Test
    public void stateChangedHandler_receiveA2dpConnectionStateChanged_shouldDispatchCallback() {
        mShadowBluetoothAdapter.setSupportedProfiles(generateList(
                new int[] {BluetoothProfile.A2DP}));
        mProfileManager.updateLocalProfiles();

        mIntent = new Intent(BluetoothA2dp.ACTION_CONNECTION_STATE_CHANGED);
        mIntent.putExtra(BluetoothDevice.EXTRA_DEVICE, mDevice);
        mIntent.putExtra(BluetoothProfile.EXTRA_PREVIOUS_STATE, BluetoothProfile.STATE_CONNECTING);
        mIntent.putExtra(BluetoothProfile.EXTRA_STATE, BluetoothProfile.STATE_CONNECTED);

        mContext.sendBroadcast(mIntent);

        verify(mEventManager).dispatchProfileConnectionStateChanged(
                mCachedBluetoothDevice, BluetoothProfile.STATE_CONNECTED, BluetoothProfile.A2DP);
    }

    /**
     * Verify BluetoothHeadset.ACTION_CONNECTION_STATE_CHANGED with uuid intent will dispatch to
     * profile connection state changed callback
     */
    @Test
    public void stateChangedHandler_receiveHeadsetConnectionStateChanged_shouldDispatchCallback() {
        mShadowBluetoothAdapter.setSupportedProfiles(generateList(
                new int[] {BluetoothProfile.HEADSET}));
        mProfileManager.updateLocalProfiles();

        mIntent = new Intent(BluetoothHeadset.ACTION_CONNECTION_STATE_CHANGED);
        mIntent.putExtra(BluetoothDevice.EXTRA_DEVICE, mDevice);
        mIntent.putExtra(BluetoothProfile.EXTRA_PREVIOUS_STATE, BluetoothProfile.STATE_CONNECTING);
        mIntent.putExtra(BluetoothProfile.EXTRA_STATE, BluetoothProfile.STATE_CONNECTED);

        mContext.sendBroadcast(mIntent);

        verify(mEventManager).dispatchProfileConnectionStateChanged(mCachedBluetoothDevice,
                BluetoothProfile.STATE_CONNECTED, BluetoothProfile.HEADSET);
    }

    /**
     * Verify BluetoothHearingAid.ACTION_CONNECTION_STATE_CHANGED with uuid intent will dispatch to
     * CachedBluetoothDeviceManager method
     */
    @Test
    public void stateChangedHandler_receiveHAPConnectionStateChanged_shouldDispatchDeviceManager() {
        mShadowBluetoothAdapter.setSupportedProfiles(generateList(
                new int[] {BluetoothProfile.HEARING_AID}));
        mProfileManager.updateLocalProfiles();
        when(mCachedBluetoothDevice.getHiSyncId()).thenReturn(HISYNCID);

        mIntent = new Intent(BluetoothHearingAid.ACTION_CONNECTION_STATE_CHANGED);
        mIntent.putExtra(BluetoothDevice.EXTRA_DEVICE, mDevice);
        mIntent.putExtra(BluetoothProfile.EXTRA_PREVIOUS_STATE, BluetoothProfile.STATE_CONNECTING);
        mIntent.putExtra(BluetoothProfile.EXTRA_STATE, BluetoothProfile.STATE_CONNECTED);

        mContext.sendBroadcast(mIntent);

        verify(mDeviceManager).onProfileConnectionStateChangedIfProcessed(mCachedBluetoothDevice,
                BluetoothProfile.STATE_CONNECTED);
    }

    /**
     * Verify BluetoothPan.ACTION_CONNECTION_STATE_CHANGED intent with uuid will dispatch to
     * profile connection state changed callback
     */
    @Test
    public void stateChangedHandler_receivePanConnectionStateChanged_shouldNotDispatchCallback() {
        mShadowBluetoothAdapter.setSupportedProfiles(generateList(
                new int[] {BluetoothProfile.PAN}));
        mProfileManager.updateLocalProfiles();

        mIntent = new Intent(BluetoothPan.ACTION_CONNECTION_STATE_CHANGED);
        mIntent.putExtra(BluetoothDevice.EXTRA_DEVICE, mDevice);
        mIntent.putExtra(BluetoothProfile.EXTRA_PREVIOUS_STATE, BluetoothProfile.STATE_CONNECTING);
        mIntent.putExtra(BluetoothProfile.EXTRA_STATE, BluetoothProfile.STATE_CONNECTED);

        mContext.sendBroadcast(mIntent);

        verify(mEventManager).dispatchProfileConnectionStateChanged(
                any(CachedBluetoothDevice.class), anyInt(), anyInt());
    }

    /**
     * Verify BluetoothPan.ACTION_CONNECTION_STATE_CHANGED intent without uuids will not dispatch to
     * handler and refresh CachedBluetoothDevice
     */
    @Test
    public void stateChangedHandler_receivePanConnectionStateChangedWithoutProfile_shouldNotRefresh
    () {
        mShadowBluetoothAdapter.setSupportedProfiles(null);
        mProfileManager.updateLocalProfiles();

        mIntent = new Intent(BluetoothPan.ACTION_CONNECTION_STATE_CHANGED);
        mIntent.putExtra(BluetoothDevice.EXTRA_DEVICE, mDevice);
        mIntent.putExtra(BluetoothProfile.EXTRA_PREVIOUS_STATE, BluetoothProfile.STATE_CONNECTING);
        mIntent.putExtra(BluetoothProfile.EXTRA_STATE, BluetoothProfile.STATE_CONNECTED);

        mContext.sendBroadcast(mIntent);

        verify(mCachedBluetoothDevice, never()).refresh();
    }

    /**
     * Verify BluetoothPan.ACTION_CONNECTION_STATE_CHANGED intent with uuids will dispatch to
     * handler and refresh CachedBluetoothDevice
     */
    @Test
    public void stateChangedHandler_receivePanConnectionStateChangedWithProfile_shouldRefresh() {
        mShadowBluetoothAdapter.setSupportedProfiles(generateList(
                new int[] {BluetoothProfile.PAN}));
        mProfileManager.updateLocalProfiles();

        mIntent = new Intent(BluetoothPan.ACTION_CONNECTION_STATE_CHANGED);
        mIntent.putExtra(BluetoothDevice.EXTRA_DEVICE, mDevice);
        mIntent.putExtra(BluetoothProfile.EXTRA_PREVIOUS_STATE, BluetoothProfile.STATE_CONNECTING);
        mIntent.putExtra(BluetoothProfile.EXTRA_STATE, BluetoothProfile.STATE_CONNECTED);

        mContext.sendBroadcast(mIntent);

        verify(mCachedBluetoothDevice).refresh();
    }

    private List<Integer> generateList(int[] profiles) {
        if (profiles == null) {
            return null;
        }
        final List<Integer> profileList = new ArrayList<>(profiles.length);
        for (int profile : profiles) {
            profileList.add(profile);
        }
        return profileList;
    }
}
