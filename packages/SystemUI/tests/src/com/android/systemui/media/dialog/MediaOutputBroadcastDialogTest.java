/*
 * Copyright (C) 2023 The Android Open Source Project
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

package com.android.systemui.media.dialog;

import static org.mockito.Mockito.any;
import static org.mockito.Mockito.anyBoolean;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.app.KeyguardManager;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothLeBroadcastMetadata;
import android.bluetooth.BluetoothLeBroadcastReceiveState;
import android.media.AudioManager;
import android.media.session.MediaSessionManager;
import android.os.PowerExemptionManager;
import android.testing.AndroidTestingRunner;
import android.testing.TestableLooper;

import androidx.test.filters.SmallTest;

import com.android.settingslib.bluetooth.CachedBluetoothDevice;
import com.android.settingslib.bluetooth.LocalBluetoothLeBroadcast;
import com.android.settingslib.bluetooth.LocalBluetoothLeBroadcastAssistant;
import com.android.settingslib.bluetooth.LocalBluetoothManager;
import com.android.settingslib.bluetooth.LocalBluetoothProfileManager;
import com.android.settingslib.media.BluetoothMediaDevice;
import com.android.settingslib.media.LocalMediaManager;
import com.android.settingslib.media.MediaDevice;
import com.android.systemui.SysuiTestCase;
import com.android.systemui.animation.DialogLaunchAnimator;
import com.android.systemui.broadcast.BroadcastSender;
import com.android.systemui.flags.FeatureFlags;
import com.android.systemui.media.nearby.NearbyMediaDevicesManager;
import com.android.systemui.plugins.ActivityStarter;
import com.android.systemui.settings.UserTracker;
import com.android.systemui.statusbar.notification.collection.notifcollection.CommonNotifCollection;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

@SmallTest
@RunWith(AndroidTestingRunner.class)
@TestableLooper.RunWithLooper
public class MediaOutputBroadcastDialogTest extends SysuiTestCase {

    private static final String TEST_PACKAGE = "test_package";

    // Mock
    private final MediaSessionManager mMediaSessionManager = mock(MediaSessionManager.class);
    private final LocalBluetoothManager mLocalBluetoothManager = mock(LocalBluetoothManager.class);
    private final LocalBluetoothProfileManager mLocalBluetoothProfileManager = mock(
            LocalBluetoothProfileManager.class);
    private final LocalBluetoothLeBroadcast mLocalBluetoothLeBroadcast = mock(
            LocalBluetoothLeBroadcast.class);
    private final LocalBluetoothLeBroadcastAssistant mLocalBluetoothLeBroadcastAssistant = mock(
            LocalBluetoothLeBroadcastAssistant.class);
    private final BluetoothLeBroadcastMetadata mBluetoothLeBroadcastMetadata = mock(
            BluetoothLeBroadcastMetadata.class);
    private final BluetoothLeBroadcastReceiveState mBluetoothLeBroadcastReceiveState = mock(
            BluetoothLeBroadcastReceiveState.class);
    private final ActivityStarter mStarter = mock(ActivityStarter.class);
    private final BroadcastSender mBroadcastSender = mock(BroadcastSender.class);
    private final LocalMediaManager mLocalMediaManager = mock(LocalMediaManager.class);
    private final MediaDevice mBluetoothMediaDevice = mock(BluetoothMediaDevice.class);
    private final BluetoothDevice mBluetoothDevice = mock(BluetoothDevice.class);
    private final CachedBluetoothDevice mCachedBluetoothDevice = mock(CachedBluetoothDevice.class);
    private final CommonNotifCollection mNotifCollection = mock(CommonNotifCollection.class);
    private final DialogLaunchAnimator mDialogLaunchAnimator = mock(DialogLaunchAnimator.class);
    private final NearbyMediaDevicesManager mNearbyMediaDevicesManager = mock(
            NearbyMediaDevicesManager.class);
    private final AudioManager mAudioManager = mock(AudioManager.class);
    private PowerExemptionManager mPowerExemptionManager = mock(PowerExemptionManager.class);
    private KeyguardManager mKeyguardManager = mock(KeyguardManager.class);
    private FeatureFlags mFlags = mock(FeatureFlags.class);
    private UserTracker mUserTracker = mock(UserTracker.class);

    private MediaOutputBroadcastDialog mMediaOutputBroadcastDialog;
    private MediaOutputController mMediaOutputController;

    @Before
    public void setUp() {
        when(mLocalBluetoothManager.getProfileManager()).thenReturn(mLocalBluetoothProfileManager);
        when(mLocalBluetoothProfileManager.getLeAudioBroadcastProfile()).thenReturn(null);
        when(mLocalBluetoothProfileManager.getLeAudioBroadcastAssistantProfile()).thenReturn(null);

        mMediaOutputController = new MediaOutputController(mContext, TEST_PACKAGE,
                mMediaSessionManager, mLocalBluetoothManager, mStarter,
                mNotifCollection, mDialogLaunchAnimator,
                Optional.of(mNearbyMediaDevicesManager), mAudioManager, mPowerExemptionManager,
                mKeyguardManager, mFlags, mUserTracker);
        mMediaOutputController.mLocalMediaManager = mLocalMediaManager;
        mMediaOutputBroadcastDialog = new MediaOutputBroadcastDialog(mContext, false,
                mBroadcastSender, mMediaOutputController);
        mMediaOutputBroadcastDialog.show();
    }

    @After
    public void tearDown() {
        mMediaOutputBroadcastDialog.dismissDialog();
    }

    @Test
    public void connectBroadcastWithActiveDevice_noBroadcastMetadata_failToAddSource() {
        when(mLocalBluetoothProfileManager.getLeAudioBroadcastProfile()).thenReturn(
                mLocalBluetoothLeBroadcast);
        when(mLocalBluetoothLeBroadcast.getLatestBluetoothLeBroadcastMetadata()).thenReturn(null);
        when(mLocalBluetoothProfileManager.getLeAudioBroadcastAssistantProfile()).thenReturn(
                mLocalBluetoothLeBroadcastAssistant);

        mMediaOutputBroadcastDialog.connectBroadcastWithActiveDevice();

        verify(mLocalBluetoothLeBroadcastAssistant, never()).addSource(any(), any(), anyBoolean());
    }

    @Test
    public void connectBroadcastWithActiveDevice_noConnectedMediaDevice_failToAddSource() {
        when(mLocalBluetoothProfileManager.getLeAudioBroadcastProfile()).thenReturn(
                mLocalBluetoothLeBroadcast);
        when(mLocalBluetoothLeBroadcast.getLatestBluetoothLeBroadcastMetadata()).thenReturn(
                mBluetoothLeBroadcastMetadata);
        when(mLocalBluetoothProfileManager.getLeAudioBroadcastAssistantProfile()).thenReturn(
                mLocalBluetoothLeBroadcastAssistant);
        when(mLocalMediaManager.getCurrentConnectedDevice()).thenReturn(null);

        mMediaOutputBroadcastDialog.connectBroadcastWithActiveDevice();

        verify(mLocalBluetoothLeBroadcastAssistant, never()).addSource(any(), any(), anyBoolean());
    }

    @Test
    public void connectBroadcastWithActiveDevice_hasBroadcastSource_failToAddSource() {
        when(mLocalBluetoothProfileManager.getLeAudioBroadcastProfile()).thenReturn(
                mLocalBluetoothLeBroadcast);
        when(mLocalBluetoothLeBroadcast.getLatestBluetoothLeBroadcastMetadata()).thenReturn(
                mBluetoothLeBroadcastMetadata);
        when(mLocalBluetoothProfileManager.getLeAudioBroadcastAssistantProfile()).thenReturn(
                mLocalBluetoothLeBroadcastAssistant);
        when(mLocalMediaManager.getCurrentConnectedDevice()).thenReturn(mBluetoothMediaDevice);
        when(((BluetoothMediaDevice) mBluetoothMediaDevice).getCachedDevice())
                .thenReturn(mCachedBluetoothDevice);
        when(mCachedBluetoothDevice.getDevice()).thenReturn(mBluetoothDevice);
        List<BluetoothLeBroadcastReceiveState> sourceList = new ArrayList<>();
        sourceList.add(mBluetoothLeBroadcastReceiveState);
        when(mLocalBluetoothLeBroadcastAssistant.getAllSources(mBluetoothDevice)).thenReturn(
                sourceList);

        mMediaOutputBroadcastDialog.connectBroadcastWithActiveDevice();

        verify(mLocalBluetoothLeBroadcastAssistant, never()).addSource(any(), any(), anyBoolean());
    }

    @Test
    public void connectBroadcastWithActiveDevice_noBroadcastSource_failToAddSource() {
        when(mLocalBluetoothProfileManager.getLeAudioBroadcastProfile()).thenReturn(
                mLocalBluetoothLeBroadcast);
        when(mLocalBluetoothProfileManager.getLeAudioBroadcastAssistantProfile()).thenReturn(
                mLocalBluetoothLeBroadcastAssistant);
        when(mLocalBluetoothLeBroadcast.getLatestBluetoothLeBroadcastMetadata()).thenReturn(
                mBluetoothLeBroadcastMetadata);
        when(mLocalMediaManager.getCurrentConnectedDevice()).thenReturn(mBluetoothMediaDevice);
        when(mBluetoothMediaDevice.isBLEDevice()).thenReturn(true);
        when(((BluetoothMediaDevice) mBluetoothMediaDevice).getCachedDevice()).thenReturn(
                mCachedBluetoothDevice);
        when(mCachedBluetoothDevice.getDevice()).thenReturn(mBluetoothDevice);
        List<BluetoothLeBroadcastReceiveState> sourceList = new ArrayList<>();
        when(mLocalBluetoothLeBroadcastAssistant.getAllSources(mBluetoothDevice)).thenReturn(
                sourceList);

        mMediaOutputBroadcastDialog.connectBroadcastWithActiveDevice();

        verify(mLocalBluetoothLeBroadcastAssistant, times(1)).addSource(any(), any(), anyBoolean());
    }
}
