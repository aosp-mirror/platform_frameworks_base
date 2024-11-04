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

import android.app.KeyguardManager;
import android.graphics.Bitmap;
import android.media.AudioManager;
import android.media.MediaDescription;
import android.media.MediaMetadata;
import android.media.MediaRoute2Info;
import android.media.session.MediaController;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.os.PowerExemptionManager;
import android.os.UserHandle;
import android.platform.test.annotations.RequiresFlagsEnabled;
import android.platform.test.flag.junit.CheckFlagsRule;
import android.platform.test.flag.junit.DeviceFlagsValueProvider;
import android.testing.TestableLooper;
import android.util.FeatureFlagUtils;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.MediumTest;

import com.android.internal.logging.UiEventLogger;
import com.android.settingslib.bluetooth.LocalBluetoothLeBroadcast;
import com.android.settingslib.bluetooth.LocalBluetoothManager;
import com.android.settingslib.bluetooth.LocalBluetoothProfileManager;
import com.android.settingslib.flags.Flags;
import com.android.settingslib.media.LocalMediaManager;
import com.android.settingslib.media.MediaDevice;
import com.android.systemui.SysuiTestCase;
import com.android.systemui.SysuiTestCaseExtKt;
import com.android.systemui.animation.DialogTransitionAnimator;
import com.android.systemui.broadcast.BroadcastSender;
import com.android.systemui.flags.FeatureFlags;
import com.android.systemui.kosmos.Kosmos;
import com.android.systemui.media.nearby.NearbyMediaDevicesManager;
import com.android.systemui.plugins.ActivityStarter;
import com.android.systemui.res.R;
import com.android.systemui.settings.UserTracker;
import com.android.systemui.statusbar.notification.collection.notifcollection.CommonNotifCollection;
import com.android.systemui.volume.panel.domain.interactor.VolumePanelGlobalStateInteractor;
import com.android.systemui.volume.panel.domain.interactor.VolumePanelGlobalStateInteractorKosmosKt;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mockito;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

@MediumTest
@RunWith(AndroidJUnit4.class)
@TestableLooper.RunWithLooper
public class MediaOutputDialogTest extends SysuiTestCase {

    private static final String TEST_PACKAGE = "test_package";

    @Rule
    public final CheckFlagsRule mCheckFlagsRule = DeviceFlagsValueProvider.createCheckFlagsRule();

    private final Kosmos mKosmos = SysuiTestCaseExtKt.testKosmos(this);

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
    private final UiEventLogger mUiEventLogger = mock(UiEventLogger.class);
    private final DialogTransitionAnimator mDialogTransitionAnimator = mock(
            DialogTransitionAnimator.class);
    private final MediaMetadata mMediaMetadata = mock(MediaMetadata.class);
    private final MediaDescription mMediaDescription = mock(MediaDescription.class);
    private final NearbyMediaDevicesManager mNearbyMediaDevicesManager = mock(
            NearbyMediaDevicesManager.class);
    private final AudioManager mAudioManager = mock(AudioManager.class);
    private PowerExemptionManager mPowerExemptionManager = mock(PowerExemptionManager.class);
    private KeyguardManager mKeyguardManager = mock(KeyguardManager.class);
    private FeatureFlags mFlags = mock(FeatureFlags.class);
    private UserTracker mUserTracker = mock(UserTracker.class);

    private List<MediaController> mMediaControllers = new ArrayList<>();
    private MediaOutputDialog mMediaOutputDialog;
    private MediaSwitchingController mMediaSwitchingController;
    private final List<String> mFeatures = new ArrayList<>();

    @Override
    protected boolean shouldFailOnLeakedReceiver() {
        return true;
    }

    @Before
    public void setUp() {
        when(mLocalBluetoothManager.getProfileManager()).thenReturn(mLocalBluetoothProfileManager);
        when(mLocalBluetoothProfileManager.getLeAudioBroadcastProfile()).thenReturn(null);
        when(mMediaController.getPlaybackState()).thenReturn(mPlaybackState);
        when(mPlaybackState.getState()).thenReturn(PlaybackState.STATE_NONE);
        when(mMediaController.getPackageName()).thenReturn(TEST_PACKAGE);
        when(mMediaController.getMetadata()).thenReturn(mMediaMetadata);
        when(mMediaMetadata.getDescription()).thenReturn(mMediaDescription);
        mMediaControllers.add(mMediaController);
        final UserHandle userHandle = mock(UserHandle.class);
        when(mUserTracker.getUserHandle()).thenReturn(userHandle);
        when(mMediaSessionManager.getActiveSessionsForUser(any(),
                Mockito.eq(userHandle))).thenReturn(
                mMediaControllers);
        VolumePanelGlobalStateInteractor volumePanelGlobalStateInteractor =
                VolumePanelGlobalStateInteractorKosmosKt.getVolumePanelGlobalStateInteractor(
                        mKosmos);

        mMediaSwitchingController =
                new MediaSwitchingController(
                        mContext,
                        TEST_PACKAGE,
                        mContext.getUser(),
                        /* token */ null,
                        mMediaSessionManager,
                        mLocalBluetoothManager,
                        mStarter,
                        mNotifCollection,
                        mDialogTransitionAnimator,
                        mNearbyMediaDevicesManager,
                        mAudioManager,
                        mPowerExemptionManager,
                        mKeyguardManager,
                        mFlags,
                        volumePanelGlobalStateInteractor,
                        mUserTracker);
        mMediaSwitchingController.mLocalMediaManager = mLocalMediaManager;
        mMediaOutputDialog = makeTestDialog(mMediaSwitchingController);
        mMediaOutputDialog.show();

        when(mLocalMediaManager.getCurrentConnectedDevice()).thenReturn(mMediaDevice);
        when(mMediaDevice.getFeatures()).thenReturn(mFeatures);
    }

    @After
    public void tearDown() {
        mMediaOutputDialog.dismiss();
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
    @RequiresFlagsEnabled(Flags.FLAG_LEGACY_LE_AUDIO_SHARING)
    public void getStopButtonVisibility_remoteBLEDevice_returnVisible() {
        when(mLocalBluetoothProfileManager.getLeAudioBroadcastProfile()).thenReturn(
                mLocalBluetoothLeBroadcast);
        when(mLocalBluetoothLeBroadcast.isEnabled(any())).thenReturn(false);
        when(mPlaybackState.getState()).thenReturn(PlaybackState.STATE_PLAYING);
        when(mMediaDevice.isBLEDevice()).thenReturn(true);

        assertThat(mMediaOutputDialog.getStopButtonVisibility()).isEqualTo(View.VISIBLE);
    }

    @Test
    @RequiresFlagsEnabled(Flags.FLAG_LEGACY_LE_AUDIO_SHARING)
    public void getStopButtonVisibility_remoteNonBLEDevice_returnGone() {
        when(mLocalBluetoothProfileManager.getLeAudioBroadcastProfile()).thenReturn(
                mLocalBluetoothLeBroadcast);
        when(mLocalBluetoothLeBroadcast.isEnabled(any())).thenReturn(false);
        when(mPlaybackState.getState()).thenReturn(PlaybackState.STATE_PLAYING);
        FeatureFlagUtils.setEnabled(mContext,
                FeatureFlagUtils.SETTINGS_NEED_CONNECTED_BLE_DEVICE_FOR_BROADCAST, true);
        when(mMediaDevice.isBLEDevice()).thenReturn(false);

        assertThat(mMediaOutputDialog.getStopButtonVisibility()).isEqualTo(View.GONE);
    }

    @Test
    public void getStopButtonVisibility_localDevice_returnGone() {
        mFeatures.add(MediaRoute2Info.FEATURE_LOCAL_PLAYBACK);

        assertThat(mMediaOutputDialog.getStopButtonVisibility()).isEqualTo(View.GONE);
    }

    @Test
    @RequiresFlagsEnabled(Flags.FLAG_LEGACY_LE_AUDIO_SHARING)
    public void isBroadcastSupported_flagOnAndConnectBleDevice_returnsTrue() {
        when(mLocalBluetoothProfileManager.getLeAudioBroadcastProfile()).thenReturn(
                mLocalBluetoothLeBroadcast);
        when(mLocalBluetoothLeBroadcast.isEnabled(any())).thenReturn(false);
        FeatureFlagUtils.setEnabled(mContext,
                FeatureFlagUtils.SETTINGS_NEED_CONNECTED_BLE_DEVICE_FOR_BROADCAST, true);
        when(mMediaDevice.isBLEDevice()).thenReturn(true);

        assertThat(mMediaOutputDialog.isBroadcastSupported()).isTrue();
    }

    @Test
    @RequiresFlagsEnabled(Flags.FLAG_LEGACY_LE_AUDIO_SHARING)
    public void isBroadcastSupported_flagOnAndNoBleDevice_returnsFalse() {
        when(mLocalBluetoothProfileManager.getLeAudioBroadcastProfile()).thenReturn(
                mLocalBluetoothLeBroadcast);
        when(mLocalBluetoothLeBroadcast.isEnabled(any())).thenReturn(false);
        FeatureFlagUtils.setEnabled(mContext,
                FeatureFlagUtils.SETTINGS_NEED_CONNECTED_BLE_DEVICE_FOR_BROADCAST, true);
        when(mMediaDevice.isBLEDevice()).thenReturn(false);

        assertThat(mMediaOutputDialog.isBroadcastSupported()).isFalse();
    }

    @Test
    @RequiresFlagsEnabled(Flags.FLAG_LEGACY_LE_AUDIO_SHARING)
    public void isBroadcastSupported_notSupportBroadcastAndflagOn_returnsFalse() {
        FeatureFlagUtils.setEnabled(mContext,
                FeatureFlagUtils.SETTINGS_NEED_CONNECTED_BLE_DEVICE_FOR_BROADCAST, true);

        assertThat(mMediaOutputDialog.isBroadcastSupported()).isFalse();
    }

    @Test
    @RequiresFlagsEnabled(Flags.FLAG_LEGACY_LE_AUDIO_SHARING)
    public void isBroadcastSupported_flagOffAndConnectToBleDevice_returnsTrue() {
        when(mLocalBluetoothProfileManager.getLeAudioBroadcastProfile()).thenReturn(
                mLocalBluetoothLeBroadcast);
        when(mLocalBluetoothLeBroadcast.isEnabled(any())).thenReturn(false);
        FeatureFlagUtils.setEnabled(mContext,
                FeatureFlagUtils.SETTINGS_NEED_CONNECTED_BLE_DEVICE_FOR_BROADCAST, false);
        when(mMediaDevice.isBLEDevice()).thenReturn(true);

        assertThat(mMediaOutputDialog.isBroadcastSupported()).isTrue();
    }

    @Test
    @RequiresFlagsEnabled(Flags.FLAG_LEGACY_LE_AUDIO_SHARING)
    public void isBroadcastSupported_flagOffAndNoBleDevice_returnsTrue() {
        when(mLocalBluetoothProfileManager.getLeAudioBroadcastProfile()).thenReturn(
                mLocalBluetoothLeBroadcast);
        when(mLocalBluetoothLeBroadcast.isEnabled(any())).thenReturn(false);
        FeatureFlagUtils.setEnabled(mContext,
                FeatureFlagUtils.SETTINGS_NEED_CONNECTED_BLE_DEVICE_FOR_BROADCAST, false);
        when(mMediaDevice.isBLEDevice()).thenReturn(false);

        assertThat(mMediaOutputDialog.isBroadcastSupported()).isTrue();
    }

    @Test
    @RequiresFlagsEnabled(Flags.FLAG_LEGACY_LE_AUDIO_SHARING)
    public void isBroadcastSupported_noBleDeviceAndEnabledBroadcast_returnsTrue() {
        when(mLocalBluetoothProfileManager.getLeAudioBroadcastProfile()).thenReturn(
                mLocalBluetoothLeBroadcast);
        when(mLocalBluetoothLeBroadcast.isEnabled(any())).thenReturn(true);
        FeatureFlagUtils.setEnabled(mContext,
                FeatureFlagUtils.SETTINGS_NEED_CONNECTED_BLE_DEVICE_FOR_BROADCAST, true);
        when(mMediaDevice.isBLEDevice()).thenReturn(false);

        assertThat(mMediaOutputDialog.isBroadcastSupported()).isTrue();
    }

    @Test
    @RequiresFlagsEnabled(Flags.FLAG_LEGACY_LE_AUDIO_SHARING)
    public void isBroadcastSupported_noBleDeviceAndDisabledBroadcast_returnsFalse() {
        when(mLocalBluetoothProfileManager.getLeAudioBroadcastProfile()).thenReturn(
                mLocalBluetoothLeBroadcast);
        when(mLocalBluetoothLeBroadcast.isEnabled(any())).thenReturn(false);
        FeatureFlagUtils.setEnabled(mContext,
                FeatureFlagUtils.SETTINGS_NEED_CONNECTED_BLE_DEVICE_FOR_BROADCAST, true);
        when(mMediaDevice.isBLEDevice()).thenReturn(false);

        assertThat(mMediaOutputDialog.isBroadcastSupported()).isFalse();
    }

    @Test
    @RequiresFlagsEnabled(Flags.FLAG_LEGACY_LE_AUDIO_SHARING)
    public void getBroadcastIconVisibility_isBroadcasting_returnVisible() {
        when(mLocalBluetoothProfileManager.getLeAudioBroadcastProfile()).thenReturn(
                mLocalBluetoothLeBroadcast);
        when(mLocalBluetoothLeBroadcast.isEnabled(any())).thenReturn(true);
        when(mPlaybackState.getState()).thenReturn(PlaybackState.STATE_PLAYING);
        when(mMediaDevice.isBLEDevice()).thenReturn(true);

        assertThat(mMediaOutputDialog.getBroadcastIconVisibility()).isEqualTo(View.VISIBLE);
    }

    @Test
    @RequiresFlagsEnabled(Flags.FLAG_LEGACY_LE_AUDIO_SHARING)
    public void getBroadcastIconVisibility_noBroadcasting_returnGone() {
        when(mLocalBluetoothProfileManager.getLeAudioBroadcastProfile()).thenReturn(
                mLocalBluetoothLeBroadcast);
        when(mLocalBluetoothLeBroadcast.isEnabled(any())).thenReturn(false);
        when(mPlaybackState.getState()).thenReturn(PlaybackState.STATE_PLAYING);
        when(mMediaDevice.isBLEDevice()).thenReturn(true);

        assertThat(mMediaOutputDialog.getBroadcastIconVisibility()).isEqualTo(View.GONE);
    }

    @Test
    @RequiresFlagsEnabled(Flags.FLAG_LEGACY_LE_AUDIO_SHARING)
    public void getBroadcastIconVisibility_remoteNonLeDevice_returnGone() {
        when(mLocalBluetoothProfileManager.getLeAudioBroadcastProfile()).thenReturn(
                mLocalBluetoothLeBroadcast);
        when(mLocalBluetoothLeBroadcast.isEnabled(any())).thenReturn(false);
        when(mPlaybackState.getState()).thenReturn(PlaybackState.STATE_PLAYING);
        when(mMediaDevice.isBLEDevice()).thenReturn(false);

        assertThat(mMediaOutputDialog.getBroadcastIconVisibility()).isEqualTo(View.GONE);
    }

    @Test
    public void getHeaderIcon_getFromMediaControllerMetaData() {
        int testWidth = 10;
        int testHeight = 20;
        when(mMediaDescription.getIconBitmap())
                .thenReturn(Bitmap.createBitmap(testWidth, testHeight, Bitmap.Config.ARGB_8888));

        assertThat(mMediaOutputDialog.getHeaderIcon().getBitmap().getHeight()).isEqualTo(
                testHeight);
        assertThat(mMediaOutputDialog.getHeaderIcon().getBitmap().getWidth()).isEqualTo(testWidth);
    }

    @Test
    public void getHeaderText_getFromMediaControllerMetaData() {
        String testTitle = "test title";
        when(mMediaDescription.getTitle())
                .thenReturn(testTitle);
        assertThat(mMediaOutputDialog.getHeaderText().toString()).isEqualTo(testTitle);
    }

    @Test
    public void getHeaderSubtitle_getFromMediaControllerMetaData() {
        String testSubtitle = "test title";
        when(mMediaDescription.getSubtitle())
                .thenReturn(testSubtitle);

        assertThat(mMediaOutputDialog.getHeaderSubtitle().toString()).isEqualTo(testSubtitle);
    }

    @Test
    public void getStopButtonText_notSupportsBroadcast_returnsDefaultText() {
        String stopText = mContext.getText(
                R.string.media_output_dialog_button_stop_casting).toString();
        MediaSwitchingController mockMediaSwitchingController =
                mock(MediaSwitchingController.class);
        when(mockMediaSwitchingController.isBroadcastSupported()).thenReturn(false);

        withTestDialog(
                mockMediaSwitchingController,
                testDialog -> {
                    assertThat(testDialog.getStopButtonText().toString()).isEqualTo(stopText);
                });
    }

    @Test
    @RequiresFlagsEnabled(Flags.FLAG_LEGACY_LE_AUDIO_SHARING)
    public void getStopButtonText_supportsBroadcast_returnsBroadcastText() {
        String stopText = mContext.getText(R.string.media_output_broadcast).toString();
        MediaDevice mMediaDevice = mock(MediaDevice.class);
        MediaSwitchingController mockMediaSwitchingController =
                mock(MediaSwitchingController.class);
        when(mockMediaSwitchingController.isBroadcastSupported()).thenReturn(true);
        when(mockMediaSwitchingController.getCurrentConnectedMediaDevice())
                .thenReturn(mMediaDevice);
        when(mockMediaSwitchingController.isBluetoothLeDevice(any())).thenReturn(true);
        when(mockMediaSwitchingController.isPlaying()).thenReturn(true);
        when(mockMediaSwitchingController.isBluetoothLeBroadcastEnabled()).thenReturn(false);
        withTestDialog(
                mockMediaSwitchingController,
                testDialog -> {
                    assertThat(testDialog.getStopButtonText().toString()).isEqualTo(stopText);
                });
    }

    @Test
    public void onStopButtonClick_notPlaying_releaseSession() {
        MediaSwitchingController mockMediaSwitchingController =
                mock(MediaSwitchingController.class);
        when(mockMediaSwitchingController.isBroadcastSupported()).thenReturn(false);
        when(mockMediaSwitchingController.getCurrentConnectedMediaDevice()).thenReturn(null);
        when(mockMediaSwitchingController.isPlaying()).thenReturn(false);
        withTestDialog(
                mockMediaSwitchingController,
                testDialog -> {
                    testDialog.onStopButtonClick();
                });

        verify(mockMediaSwitchingController).releaseSession();
        verify(mDialogTransitionAnimator).disableAllCurrentDialogsExitAnimations();
    }

    @Test
    // Check the visibility metric logging by creating a new MediaOutput dialog,
    // and verify if the calling times increases.
    public void onCreate_ShouldLogVisibility() {
        withTestDialog(mMediaSwitchingController, testDialog -> {});

        verify(mUiEventLogger, times(2))
                .log(MediaOutputDialog.MediaOutputEvent.MEDIA_OUTPUT_DIALOG_SHOW);
    }

    @NonNull
    private MediaOutputDialog makeTestDialog(MediaSwitchingController controller) {
        return new MediaOutputDialog(
                mContext,
                false,
                mBroadcastSender,
                controller,
                mDialogTransitionAnimator,
                mUiEventLogger,
                true);
    }

    private void withTestDialog(
            MediaSwitchingController controller, Consumer<MediaOutputDialog> c) {
        MediaOutputDialog testDialog = makeTestDialog(controller);
        testDialog.show();
        c.accept(testDialog);
        testDialog.dismiss();
    }
}
