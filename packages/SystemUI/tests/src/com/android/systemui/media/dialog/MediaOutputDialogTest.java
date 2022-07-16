/*
 * Copyright (C) 2020 The Android Open Source Project
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

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.Mockito.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.media.AudioManager;
import android.media.MediaRoute2Info;
import android.media.session.MediaController;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.os.PowerExemptionManager;
import android.testing.AndroidTestingRunner;
import android.testing.TestableLooper;
import android.view.View;

import androidx.test.filters.SmallTest;

import com.android.internal.logging.UiEventLogger;
import com.android.settingslib.bluetooth.LocalBluetoothLeBroadcast;
import com.android.settingslib.bluetooth.LocalBluetoothManager;
import com.android.settingslib.bluetooth.LocalBluetoothProfileManager;
import com.android.settingslib.media.LocalMediaManager;
import com.android.settingslib.media.MediaDevice;
import com.android.systemui.SysuiTestCase;
import com.android.systemui.animation.DialogLaunchAnimator;
import com.android.systemui.broadcast.BroadcastSender;
import com.android.systemui.media.nearby.NearbyMediaDevicesManager;
import com.android.systemui.plugins.ActivityStarter;
import com.android.systemui.statusbar.notification.NotificationEntryManager;

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
public class MediaOutputDialogTest extends SysuiTestCase {

    private static final String TEST_PACKAGE = "test_package";

    // Mock
    private final MediaSessionManager mMediaSessionManager = mock(MediaSessionManager.class);
    private MediaController mMediaController = mock(MediaController.class);
    private PlaybackState mPlaybackState = mock(PlaybackState.class);
    private final LocalBluetoothManager mLocalBluetoothManager = mock(LocalBluetoothManager.class);
    private final LocalBluetoothProfileManager mLocalBluetoothProfileManager = mock(
            LocalBluetoothProfileManager.class);
    private final LocalBluetoothLeBroadcast mLocalBluetoothLeBroadcast = mock(
            LocalBluetoothLeBroadcast.class);
    private final ActivityStarter mStarter = mock(ActivityStarter.class);
    private final BroadcastSender mBroadcastSender = mock(BroadcastSender.class);
    private final LocalMediaManager mLocalMediaManager = mock(LocalMediaManager.class);
    private final MediaDevice mMediaDevice = mock(MediaDevice.class);
    private final NotificationEntryManager mNotificationEntryManager =
            mock(NotificationEntryManager.class);
    private final UiEventLogger mUiEventLogger = mock(UiEventLogger.class);
    private final DialogLaunchAnimator mDialogLaunchAnimator = mock(DialogLaunchAnimator.class);
    private final NearbyMediaDevicesManager mNearbyMediaDevicesManager = mock(
            NearbyMediaDevicesManager.class);
    private final AudioManager mAudioManager = mock(AudioManager.class);
    private PowerExemptionManager mPowerExemptionManager = mock(PowerExemptionManager.class);

    private List<MediaController> mMediaControllers = new ArrayList<>();
    private MediaOutputDialog mMediaOutputDialog;
    private MediaOutputController mMediaOutputController;
    private final List<String> mFeatures = new ArrayList<>();

    @Before
    public void setUp() {
        when(mLocalBluetoothManager.getProfileManager()).thenReturn(mLocalBluetoothProfileManager);
        when(mLocalBluetoothProfileManager.getLeAudioBroadcastProfile()).thenReturn(null);
        when(mMediaController.getPlaybackState()).thenReturn(mPlaybackState);
        when(mPlaybackState.getState()).thenReturn(PlaybackState.STATE_NONE);
        when(mMediaController.getPackageName()).thenReturn(TEST_PACKAGE);
        mMediaControllers.add(mMediaController);
        when(mMediaSessionManager.getActiveSessions(any())).thenReturn(mMediaControllers);

        mMediaOutputController = new MediaOutputController(mContext, TEST_PACKAGE,
                mMediaSessionManager, mLocalBluetoothManager, mStarter,
                mNotificationEntryManager, mDialogLaunchAnimator,
                Optional.of(mNearbyMediaDevicesManager), mAudioManager, mPowerExemptionManager);
        mMediaOutputController.mLocalMediaManager = mLocalMediaManager;
        mMediaOutputDialog = new MediaOutputDialog(mContext, false, mBroadcastSender,
                mMediaOutputController, mUiEventLogger);
        mMediaOutputDialog.show();

        when(mLocalMediaManager.getCurrentConnectedDevice()).thenReturn(mMediaDevice);
        when(mMediaDevice.getFeatures()).thenReturn(mFeatures);
    }

    @After
    public void tearDown() {
        mMediaOutputDialog.dismissDialog();
    }

    @Test
    public void getStopButtonVisibility_remoteDevice_returnVisible() {
        mFeatures.add(MediaRoute2Info.FEATURE_REMOTE_PLAYBACK);

        assertThat(mMediaOutputDialog.getStopButtonVisibility()).isEqualTo(View.VISIBLE);

        mFeatures.clear();
        mFeatures.add(MediaRoute2Info.FEATURE_REMOTE_AUDIO_PLAYBACK);

        assertThat(mMediaOutputDialog.getStopButtonVisibility()).isEqualTo(View.VISIBLE);

        mFeatures.clear();
        mFeatures.add(MediaRoute2Info.FEATURE_REMOTE_VIDEO_PLAYBACK);

        assertThat(mMediaOutputDialog.getStopButtonVisibility()).isEqualTo(View.VISIBLE);

        mFeatures.clear();
        mFeatures.add(MediaRoute2Info.FEATURE_REMOTE_GROUP_PLAYBACK);

        assertThat(mMediaOutputDialog.getStopButtonVisibility()).isEqualTo(View.VISIBLE);
    }

    @Test
    public void getStopButtonVisibility_remoteBLEDevice_returnVisible() {
        when(mLocalBluetoothProfileManager.getLeAudioBroadcastProfile()).thenReturn(
                mLocalBluetoothLeBroadcast);
        when(mLocalBluetoothLeBroadcast.isEnabled(any())).thenReturn(false);
        when(mPlaybackState.getState()).thenReturn(PlaybackState.STATE_PLAYING);
        when(mMediaDevice.isBLEDevice()).thenReturn(true);

        assertThat(mMediaOutputDialog.getStopButtonVisibility()).isEqualTo(View.VISIBLE);
    }

    @Test
    public void getStopButtonVisibility_remoteNonBLEDevice_returnGone() {
        when(mLocalBluetoothProfileManager.getLeAudioBroadcastProfile()).thenReturn(
                mLocalBluetoothLeBroadcast);
        when(mLocalBluetoothLeBroadcast.isEnabled(any())).thenReturn(false);
        when(mPlaybackState.getState()).thenReturn(PlaybackState.STATE_PLAYING);
        when(mMediaDevice.isBLEDevice()).thenReturn(false);

        assertThat(mMediaOutputDialog.getStopButtonVisibility()).isEqualTo(View.GONE);
    }

    @Test
    public void getStopButtonVisibility_localDevice_returnGone() {
        mFeatures.add(MediaRoute2Info.FEATURE_LOCAL_PLAYBACK);

        assertThat(mMediaOutputDialog.getStopButtonVisibility()).isEqualTo(View.GONE);
    }

    @Test
    public void getBroadcastIconVisibility_isBroadcasting_returnVisible() {
        when(mLocalBluetoothProfileManager.getLeAudioBroadcastProfile()).thenReturn(
                mLocalBluetoothLeBroadcast);
        when(mLocalBluetoothLeBroadcast.isEnabled(any())).thenReturn(true);
        when(mPlaybackState.getState()).thenReturn(PlaybackState.STATE_PLAYING);
        when(mMediaDevice.isBLEDevice()).thenReturn(true);

        assertThat(mMediaOutputDialog.getBroadcastIconVisibility()).isEqualTo(View.VISIBLE);
    }

    @Test
    public void getBroadcastIconVisibility_noBroadcasting_returnGone() {
        when(mLocalBluetoothProfileManager.getLeAudioBroadcastProfile()).thenReturn(
                mLocalBluetoothLeBroadcast);
        when(mLocalBluetoothLeBroadcast.isEnabled(any())).thenReturn(false);
        when(mPlaybackState.getState()).thenReturn(PlaybackState.STATE_PLAYING);
        when(mMediaDevice.isBLEDevice()).thenReturn(true);

        assertThat(mMediaOutputDialog.getBroadcastIconVisibility()).isEqualTo(View.GONE);
    }

    @Test
    public void getBroadcastIconVisibility_remoteNonLeDevice_returnGone() {
        when(mLocalBluetoothProfileManager.getLeAudioBroadcastProfile()).thenReturn(
                mLocalBluetoothLeBroadcast);
        when(mLocalBluetoothLeBroadcast.isEnabled(any())).thenReturn(false);
        when(mPlaybackState.getState()).thenReturn(PlaybackState.STATE_PLAYING);
        when(mMediaDevice.isBLEDevice()).thenReturn(false);

        assertThat(mMediaOutputDialog.getBroadcastIconVisibility()).isEqualTo(View.GONE);
    }

    @Test
    // Check the visibility metric logging by creating a new MediaOutput dialog,
    // and verify if the calling times increases.
    public void onCreate_ShouldLogVisibility() {
        MediaOutputDialog testDialog = new MediaOutputDialog(mContext, false, mBroadcastSender,
                mMediaOutputController, mUiEventLogger);
        testDialog.show();

        testDialog.dismissDialog();

        verify(mUiEventLogger, times(2))
                .log(MediaOutputDialog.MediaOutputEvent.MEDIA_OUTPUT_DIALOG_SHOW);
    }
}
