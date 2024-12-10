/*
 * Copyright (C) 2024 The Android Open Source Project
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

import static android.bluetooth.BluetoothDevice.BOND_BONDED;

import static com.android.settingslib.bluetooth.HearingAidInfo.DeviceSide.SIDE_LEFT;
import static com.android.settingslib.bluetooth.HearingAidInfo.DeviceSide.SIDE_RIGHT;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothProfile;
import android.content.Context;
import android.os.Handler;

import androidx.test.core.app.ApplicationProvider;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.Spy;
import org.mockito.junit.MockitoJUnit;
import org.mockito.junit.MockitoRule;
import org.mockito.stubbing.Answer;
import org.robolectric.RobolectricTestRunner;

import java.util.List;
import java.util.Set;
import java.util.concurrent.Executor;

/** Tests for {@link AmbientVolumeUiController}. */
@RunWith(RobolectricTestRunner.class)
public class AmbientVolumeUiControllerTest {

    @Rule
    public MockitoRule mockito = MockitoJUnit.rule();

    private static final String TEST_ADDRESS = "00:00:00:00:11";
    private static final String TEST_MEMBER_ADDRESS = "00:00:00:00:22";

    @Mock
    LocalBluetoothManager mBluetoothManager;
    @Mock
    LocalBluetoothProfileManager mProfileManager;
    @Mock
    BluetoothEventManager mEventManager;
    @Mock
    VolumeControlProfile mVolumeControlProfile;
    @Mock
    AmbientVolumeUi mAmbientLayout;
    @Mock
    private AmbientVolumeController mVolumeController;
    @Mock
    private CachedBluetoothDevice mCachedDevice;
    @Mock
    private CachedBluetoothDevice mCachedMemberDevice;
    @Mock
    private BluetoothDevice mDevice;
    @Mock
    private BluetoothDevice mMemberDevice;
    @Mock
    private Handler mTestHandler;

    @Spy
    private final Context mContext = ApplicationProvider.getApplicationContext();
    private AmbientVolumeUiController mController;

    @Before
    public void setUp() {
        when(mBluetoothManager.getProfileManager()).thenReturn(mProfileManager);
        when(mBluetoothManager.getEventManager()).thenReturn(mEventManager);

        mController = new AmbientVolumeUiController(mContext, mBluetoothManager, mAmbientLayout,
                mVolumeController);

        when(mProfileManager.getVolumeControlProfile()).thenReturn(mVolumeControlProfile);
        when(mVolumeControlProfile.getConnectionStatus(mDevice)).thenReturn(
                BluetoothProfile.STATE_CONNECTED);
        when(mVolumeControlProfile.getConnectionStatus(mMemberDevice)).thenReturn(
                BluetoothProfile.STATE_CONNECTED);
        when(mVolumeController.isAmbientControlAvailable(mDevice)).thenReturn(true);
        when(mVolumeController.isAmbientControlAvailable(mMemberDevice)).thenReturn(true);

        when(mContext.getMainThreadHandler()).thenReturn(mTestHandler);
        Answer<Object> answer = invocationOnMock -> {
            invocationOnMock.getArgument(0, Runnable.class).run();
            return null;
        };
        when(mTestHandler.post(any(Runnable.class))).thenAnswer(answer);
        when(mTestHandler.postDelayed(any(Runnable.class), anyLong())).thenAnswer(answer);

        prepareDevice(/* hasMember= */ true);
        mController.loadDevice(mCachedDevice);
        Mockito.reset(mAmbientLayout);
    }

    @Test
    public void loadDevice_deviceWithoutMember_controlNotExpandable() {
        prepareDevice(/* hasMember= */ false);

        mController.loadDevice(mCachedDevice);

        verify(mAmbientLayout).setExpandable(false);
    }

    @Test
    public void loadDevice_deviceWithMember_controlExpandable() {
        prepareDevice(/* hasMember= */ true);

        mController.loadDevice(mCachedDevice);

        verify(mAmbientLayout).setExpandable(true);
    }

    @Test
    public void loadDevice_deviceNotSupportVcp_ambientLayoutGone() {
        when(mCachedDevice.getProfiles()).thenReturn(List.of());

        mController.loadDevice(mCachedDevice);

        verify(mAmbientLayout).setVisible(false);
    }

    @Test
    public void loadDevice_ambientControlNotAvailable_ambientLayoutGone() {
        when(mVolumeController.isAmbientControlAvailable(mDevice)).thenReturn(false);
        when(mVolumeController.isAmbientControlAvailable(mMemberDevice)).thenReturn(false);

        mController.loadDevice(mCachedDevice);

        verify(mAmbientLayout).setVisible(false);
    }

    @Test
    public void loadDevice_supportVcpAndAmbientControlAvailable_ambientLayoutVisible() {
        when(mCachedDevice.getProfiles()).thenReturn(List.of(mVolumeControlProfile));
        when(mVolumeController.isAmbientControlAvailable(mDevice)).thenReturn(true);

        mController.loadDevice(mCachedDevice);

        verify(mAmbientLayout).setVisible(true);
    }

    @Test
    public void start_callbackRegistered() {
        mController.start();

        verify(mEventManager).registerCallback(mController);
        verify(mVolumeController).registerCallback(any(Executor.class), eq(mDevice));
        verify(mVolumeController).registerCallback(any(Executor.class), eq(mMemberDevice));
        verify(mCachedDevice).registerCallback(any(Executor.class),
                any(CachedBluetoothDevice.Callback.class));
        verify(mCachedMemberDevice).registerCallback(any(Executor.class),
                any(CachedBluetoothDevice.Callback.class));
    }

    @Test
    public void stop_callbackUnregistered() {
        mController.stop();

        verify(mEventManager).unregisterCallback(mController);
        verify(mVolumeController).unregisterCallback(mDevice);
        verify(mVolumeController).unregisterCallback(mMemberDevice);
        verify(mCachedDevice).unregisterCallback(any(CachedBluetoothDevice.Callback.class));
        verify(mCachedMemberDevice).unregisterCallback(any(CachedBluetoothDevice.Callback.class));
    }

    private void prepareDevice(boolean hasMember) {
        when(mCachedDevice.getDeviceSide()).thenReturn(SIDE_LEFT);
        when(mCachedDevice.getDevice()).thenReturn(mDevice);
        when(mCachedDevice.getBondState()).thenReturn(BOND_BONDED);
        when(mCachedDevice.getProfiles()).thenReturn(List.of(mVolumeControlProfile));
        when(mDevice.getAddress()).thenReturn(TEST_ADDRESS);
        when(mDevice.getAnonymizedAddress()).thenReturn(TEST_ADDRESS);
        when(mDevice.isConnected()).thenReturn(true);
        if (hasMember) {
            when(mCachedDevice.getMemberDevice()).thenReturn(Set.of(mCachedMemberDevice));
            when(mCachedMemberDevice.getDeviceSide()).thenReturn(SIDE_RIGHT);
            when(mCachedMemberDevice.getDevice()).thenReturn(mMemberDevice);
            when(mCachedMemberDevice.getBondState()).thenReturn(BOND_BONDED);
            when(mCachedMemberDevice.getProfiles()).thenReturn(List.of(mVolumeControlProfile));
            when(mMemberDevice.getAddress()).thenReturn(TEST_MEMBER_ADDRESS);
            when(mMemberDevice.getAnonymizedAddress()).thenReturn(TEST_MEMBER_ADDRESS);
            when(mMemberDevice.isConnected()).thenReturn(true);
        } else {
            when(mCachedDevice.getMemberDevice()).thenReturn(Set.of());
        }
    }
}
