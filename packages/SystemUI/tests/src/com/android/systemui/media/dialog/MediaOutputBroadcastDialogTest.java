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

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.Mockito.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import android.app.KeyguardManager;
import android.media.AudioManager;
import android.media.session.MediaController;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.os.PowerExemptionManager;
import android.testing.AndroidTestingRunner;
import android.testing.TestableLooper;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.test.filters.SmallTest;

import com.android.settingslib.bluetooth.LocalBluetoothLeBroadcast;
import com.android.settingslib.bluetooth.LocalBluetoothLeBroadcastMetadata;
import com.android.settingslib.bluetooth.LocalBluetoothManager;
import com.android.settingslib.bluetooth.LocalBluetoothProfileManager;
import com.android.settingslib.media.LocalMediaManager;
import com.android.settingslib.media.MediaDevice;
import com.android.systemui.R;
import com.android.systemui.SysuiTestCase;
import com.android.systemui.animation.DialogLaunchAnimator;
import com.android.systemui.broadcast.BroadcastSender;
import com.android.systemui.flags.FeatureFlags;
import com.android.systemui.flags.Flags;
import com.android.systemui.media.nearby.NearbyMediaDevicesManager;
import com.android.systemui.plugins.ActivityStarter;
import com.android.systemui.statusbar.notification.collection.notifcollection.CommonNotifCollection;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

@SmallTest
@RunWith(AndroidTestingRunner.class)
@TestableLooper.RunWithLooper
public class MediaOutputBroadcastDialogTest extends SysuiTestCase {

    private static final String TEST_PACKAGE = "test_package";
    private static final String BROADCAST_NAME_TEST = "Broadcast_name_test";
    private static final String BROADCAST_CODE_TEST = "112233";
    private static final String BROADCAST_CODE_UPDATE_TEST = "11223344";

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
    private final CommonNotifCollection mNotifCollection = mock(CommonNotifCollection.class);
    private final DialogLaunchAnimator mDialogLaunchAnimator = mock(DialogLaunchAnimator.class);
    private final NearbyMediaDevicesManager mNearbyMediaDevicesManager = mock(
            NearbyMediaDevicesManager.class);
    private final AudioManager mAudioManager = mock(AudioManager.class);
    private PowerExemptionManager mPowerExemptionManager = mock(PowerExemptionManager.class);
    private KeyguardManager mKeyguardManager = mock(KeyguardManager.class);
    private final LocalBluetoothLeBroadcastMetadata mLocalBluetoothLeBroadcastMetadata =
            mock(LocalBluetoothLeBroadcastMetadata.class);
    private FeatureFlags mFlags = mock(FeatureFlags.class);

    private List<MediaController> mMediaControllers = new ArrayList<>();
    private MediaOutputBroadcastDialog mMediaOutputBroadcastDialog;
    private MediaOutputController mMediaOutputController;
    private final List<String> mFeatures = new ArrayList<>();

    @Before
    public void setUp() {
        when(mLocalBluetoothManager.getProfileManager()).thenReturn(mLocalBluetoothProfileManager);
        when(mLocalBluetoothProfileManager.getLeAudioBroadcastProfile())
                .thenReturn(mLocalBluetoothLeBroadcast);
        when(mMediaController.getPlaybackState()).thenReturn(mPlaybackState);
        when(mPlaybackState.getState()).thenReturn(PlaybackState.STATE_NONE);
        when(mMediaController.getPackageName()).thenReturn(TEST_PACKAGE);
        mMediaControllers.add(mMediaController);
        when(mMediaSessionManager.getActiveSessions(any())).thenReturn(mMediaControllers);
        when(mLocalBluetoothLeBroadcast.getLocalBluetoothLeBroadcastMetaData()).thenReturn(
                mLocalBluetoothLeBroadcastMetadata);
        when(mLocalBluetoothLeBroadcastMetadata.convertToQrCodeString())
                .thenReturn("metadata_test_convert");
        when(mLocalBluetoothLeBroadcast.getProgramInfo()).thenReturn(BROADCAST_NAME_TEST);
        when(mLocalBluetoothLeBroadcast.getBroadcastCode())
                .thenReturn(BROADCAST_CODE_TEST.getBytes(StandardCharsets.UTF_8));

        mMediaOutputController = new MediaOutputController(mContext, TEST_PACKAGE,
                mMediaSessionManager, mLocalBluetoothManager, mStarter,
                mNotifCollection, mDialogLaunchAnimator,
                Optional.of(mNearbyMediaDevicesManager), mAudioManager, mPowerExemptionManager,
                mKeyguardManager, mFlags);
        when(mFlags.isEnabled(Flags.OUTPUT_SWITCHER_ADVANCED_LAYOUT)).thenReturn(false);

        mMediaOutputController.mLocalMediaManager = mLocalMediaManager;
        mMediaOutputBroadcastDialog = new MediaOutputBroadcastDialog(mContext, false,
                mBroadcastSender, mMediaOutputController);
        mMediaOutputBroadcastDialog.show();

        when(mLocalMediaManager.getCurrentConnectedDevice()).thenReturn(mMediaDevice);
        when(mMediaDevice.getFeatures()).thenReturn(mFeatures);
    }

    @After
    public void tearDown() {
        mMediaOutputBroadcastDialog.dismissDialog();
    }

    @Test
    public void refreshUi_checkBroadcastQrCodeView() {
        mMediaOutputBroadcastDialog.refreshUi();
        final ImageView broadcastQrCodeView = mMediaOutputBroadcastDialog.mDialogView
                .requireViewById(R.id.qrcode_view);

        assertThat(broadcastQrCodeView.getDrawable()).isNotNull();
    }

    @Test
    public void refreshUi_checkBroadcastName() {
        mMediaOutputBroadcastDialog.refreshUi();
        final TextView broadcastName = mMediaOutputBroadcastDialog.mDialogView
                .requireViewById(R.id.broadcast_name_summary);

        assertThat(broadcastName.getText().toString()).isEqualTo(BROADCAST_NAME_TEST);
    }

    @Test
    public void refreshUi_checkBroadcastCode() {
        mMediaOutputBroadcastDialog.refreshUi();
        final TextView broadcastCode = mMediaOutputBroadcastDialog.mDialogView
                .requireViewById(R.id.broadcast_code_summary);

        assertThat(broadcastCode.getText().toString()).isEqualTo(BROADCAST_CODE_TEST);
    }

    @Test
    public void updateBroadcastInfo_stopBroadcastFailed_handleFailedUI() {
        mMediaOutputBroadcastDialog.launchBroadcastUpdatedDialog(true, BROADCAST_CODE_UPDATE_TEST);
        when(mLocalBluetoothProfileManager.getLeAudioBroadcastProfile())
                .thenReturn(null);

        mMediaOutputBroadcastDialog.updateBroadcastInfo(true, BROADCAST_CODE_UPDATE_TEST);
        assertThat(mMediaOutputBroadcastDialog.getRetryCount()).isEqualTo(1);

        mMediaOutputBroadcastDialog.updateBroadcastInfo(true, BROADCAST_CODE_UPDATE_TEST);
        assertThat(mMediaOutputBroadcastDialog.getRetryCount()).isEqualTo(2);

        // It will be the MAX Retry Count = 3
        mMediaOutputBroadcastDialog.updateBroadcastInfo(true, BROADCAST_CODE_UPDATE_TEST);
        assertThat(mMediaOutputBroadcastDialog.getRetryCount()).isEqualTo(0);
    }
}
