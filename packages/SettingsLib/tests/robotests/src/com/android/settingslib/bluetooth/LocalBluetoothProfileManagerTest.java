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
import static org.mockito.Mockito.anyLong;
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

import java.util.ArrayList;
import java.util.List;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(resourceDir = "../../res")
public class LocalBluetoothProfileManagerTest {
    private final static long HI_SYNC_ID = 0x1234;
    @Mock
    private CachedBluetoothDeviceManager mDeviceManager;
    @Mock
    private BluetoothEventManager mEventManager;
    @Mock
    private LocalBluetoothAdapter mAdapter;
    @Mock
    private BluetoothDevice mDevice;
    @Mock
    private CachedBluetoothDevice mCachedBluetoothDevice;
    @Mock
    private CachedBluetoothDevice mHearingAidOtherDevice;

    private Context mContext;
    private LocalBluetoothProfileManager mProfileManager;
    private Intent mIntent;

    @Before
    public void setUp() {
        MockitoAnnotations.initMocks(this);
        mContext = spy(RuntimeEnvironment.application);
        mEventManager = spy(new BluetoothEventManager(mAdapter,
                mDeviceManager, mContext));
        when(mAdapter.getBluetoothState()).thenReturn(BluetoothAdapter.STATE_ON);
        when(mDeviceManager.findDevice(mDevice)).thenReturn(mCachedBluetoothDevice);
    }

    /**
     * Verify HID and HID Device profiles are not null without running updateUuids()
     */
    @Test
    public void constructor_initiateHidAndHidDeviceProfile() {
        mProfileManager =
                new LocalBluetoothProfileManager(mContext, mAdapter, mDeviceManager, mEventManager);

        assertThat(mProfileManager.getHidProfile()).isNotNull();
        assertThat(mProfileManager.getHidDeviceProfile()).isNotNull();
    }

    /**
     * Verify updateLocalProfiles() for a local A2DP source adds A2dpProfile
     */
    @Test
    public void updateLocalProfiles_addA2dpToLocalProfiles() {
        mProfileManager =
                new LocalBluetoothProfileManager(mContext, mAdapter, mDeviceManager, mEventManager);
        when(mAdapter.getUuids()).thenReturn(new ParcelUuid[]{BluetoothUuid.AudioSource});
        assertThat(mProfileManager.getA2dpProfile()).isNull();
        assertThat(mProfileManager.getHeadsetProfile()).isNull();

        ParcelUuid[] uuids = mAdapter.getUuids();
        mProfileManager.updateLocalProfiles(uuids);

        assertThat(mProfileManager.getA2dpProfile()).isNotNull();
        assertThat(mProfileManager.getHeadsetProfile()).isNull();
    }

    /**
     * Verify updateProfiles() for a remote HID device updates profiles and removedProfiles
     */
    @Test
    public void updateProfiles_addHidProfileForRemoteDevice() {
        mProfileManager =
                new LocalBluetoothProfileManager(mContext, mAdapter, mDeviceManager, mEventManager);
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
        when(mAdapter.getUuids()).thenReturn(new ParcelUuid[]{BluetoothUuid.AudioSource});
        mProfileManager = new LocalBluetoothProfileManager(mContext, mAdapter, mDeviceManager,
                mEventManager);
        // Refer to BluetoothControllerImpl, it will call setReceiverHandler after
        // LocalBluetoothProfileManager created.
        mEventManager.setReceiverHandler(null);
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
        when(mAdapter.getUuids()).thenReturn(new ParcelUuid[]{BluetoothUuid.Handsfree_AG});
        mProfileManager = new LocalBluetoothProfileManager(mContext, mAdapter, mDeviceManager,
                mEventManager);
        // Refer to BluetoothControllerImpl, it will call setReceiverHandler after
        // LocalBluetoothProfileManager created.
        mEventManager.setReceiverHandler(null);
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
     * profile connection state changed callback
     */
    @Test
    public void stateChangedHandler_receiveHAPConnectionStateChanged_shouldDispatchCallback() {
        ArrayList<Integer> supportProfiles = new ArrayList<>();
        supportProfiles.add(BluetoothProfile.HEARING_AID);
        when(mAdapter.getSupportedProfiles()).thenReturn(supportProfiles);
        when(mAdapter.getUuids()).thenReturn(new ParcelUuid[]{BluetoothUuid.HearingAid});
        mProfileManager = new LocalBluetoothProfileManager(mContext, mAdapter, mDeviceManager,
                mEventManager);
        // Refer to BluetoothControllerImpl, it will call setReceiverHandler after
        // LocalBluetoothProfileManager created.
        mEventManager.setReceiverHandler(null);
        mIntent = new Intent(BluetoothHearingAid.ACTION_CONNECTION_STATE_CHANGED);
        mIntent.putExtra(BluetoothDevice.EXTRA_DEVICE, mDevice);
        mIntent.putExtra(BluetoothProfile.EXTRA_PREVIOUS_STATE, BluetoothProfile.STATE_CONNECTING);
        mIntent.putExtra(BluetoothProfile.EXTRA_STATE, BluetoothProfile.STATE_CONNECTED);

        mContext.sendBroadcast(mIntent);

        verify(mEventManager).dispatchProfileConnectionStateChanged(mCachedBluetoothDevice,
                BluetoothProfile.STATE_CONNECTED, BluetoothProfile.HEARING_AID);
    }

    /**
     * Verify BluetoothHearingAid.ACTION_CONNECTION_STATE_CHANGED with uuid intent will dispatch to
     * refresh both sides devices.
     */
    @Test
    public void stateChangedHandler_receiveHAPConnectionStateChanged_shouldRefreshBothSides() {
        ArrayList<Integer> supportProfiles = new ArrayList<>();
        supportProfiles.add(BluetoothProfile.HEARING_AID);
        when(mAdapter.getSupportedProfiles()).thenReturn(supportProfiles);
        when(mCachedBluetoothDevice.getHiSyncId()).thenReturn(HI_SYNC_ID);
        when(mDeviceManager.getHearingAidOtherDevice(mCachedBluetoothDevice, HI_SYNC_ID))
            .thenReturn(mHearingAidOtherDevice);

        mProfileManager = new LocalBluetoothProfileManager(mContext, mAdapter, mDeviceManager,
                mEventManager);
        mIntent = new Intent(BluetoothHearingAid.ACTION_CONNECTION_STATE_CHANGED);
        mIntent.putExtra(BluetoothDevice.EXTRA_DEVICE, mDevice);
        mIntent.putExtra(BluetoothProfile.EXTRA_PREVIOUS_STATE, BluetoothProfile.STATE_CONNECTING);
        mIntent.putExtra(BluetoothProfile.EXTRA_STATE, BluetoothProfile.STATE_CONNECTED);

        mContext.sendBroadcast(mIntent);

        verify(mCachedBluetoothDevice).refresh();
        verify(mHearingAidOtherDevice).refresh();
    }

    /**
     * Verify BluetoothPan.ACTION_CONNECTION_STATE_CHANGED intent with uuid will dispatch to
     * profile connection state changed callback
     */
    @Test
    public void stateChangedHandler_receivePanConnectionStateChanged_shouldNotDispatchCallback() {
        when(mAdapter.getUuids()).thenReturn(new ParcelUuid[]{BluetoothUuid.AudioSource});
        mProfileManager = new LocalBluetoothProfileManager(mContext, mAdapter, mDeviceManager,
                mEventManager);
        // Refer to BluetoothControllerImpl, it will call setReceiverHandler after
        // LocalBluetoothProfileManager created.
        mEventManager.setReceiverHandler(null);
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
    public void stateChangedHandler_receivePanConnectionStateChangedWithoutUuid_shouldNotRefresh() {
        when(mAdapter.getUuids()).thenReturn(null);
        mProfileManager = new LocalBluetoothProfileManager(mContext, mAdapter, mDeviceManager,
                mEventManager);
        // Refer to BluetoothControllerImpl, it will call setReceiverHandler after
        // LocalBluetoothProfileManager created.
        mEventManager.setReceiverHandler(null);
        mIntent = new Intent(BluetoothPan.ACTION_CONNECTION_STATE_CHANGED);
        mIntent.putExtra(BluetoothDevice.EXTRA_DEVICE, mDevice);
        mIntent.putExtra(BluetoothProfile.EXTRA_PREVIOUS_STATE, BluetoothProfile.STATE_CONNECTING);
        mIntent.putExtra(BluetoothProfile.EXTRA_STATE, BluetoothProfile.STATE_CONNECTED);

        mContext.sendBroadcast(mIntent);

        verify(mCachedBluetoothDevice).refresh();
    }

    /**
     * Verify BluetoothPan.ACTION_CONNECTION_STATE_CHANGED intent with uuids will dispatch to
     * handler and refresh CachedBluetoothDevice
     */
    @Test
    public void stateChangedHandler_receivePanConnectionStateChangedWithUuids_shouldRefresh() {
        when(mAdapter.getUuids()).thenReturn(new ParcelUuid[]{BluetoothUuid.AudioSource});
        mProfileManager = new LocalBluetoothProfileManager(mContext, mAdapter, mDeviceManager,
                mEventManager);
        // Refer to BluetoothControllerImpl, it will call setReceiverHandler after
        // LocalBluetoothProfileManager created.
        mEventManager.setReceiverHandler(null);
        mIntent = new Intent(BluetoothPan.ACTION_CONNECTION_STATE_CHANGED);
        mIntent.putExtra(BluetoothDevice.EXTRA_DEVICE, mDevice);
        mIntent.putExtra(BluetoothProfile.EXTRA_PREVIOUS_STATE, BluetoothProfile.STATE_CONNECTING);
        mIntent.putExtra(BluetoothProfile.EXTRA_STATE, BluetoothProfile.STATE_CONNECTED);

        mContext.sendBroadcast(mIntent);

        verify(mCachedBluetoothDevice).refresh();
    }
}
