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

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyZeroInteractions;
import static org.mockito.Mockito.when;

import android.app.KeyguardManager;
import android.app.Notification;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.PorterDuffColorFilter;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.Icon;
import android.media.AudioDeviceAttributes;
import android.media.AudioManager;
import android.media.MediaDescription;
import android.media.MediaMetadata;
import android.media.MediaRoute2Info;
import android.media.NearbyDevice;
import android.media.RoutingSessionInfo;
import android.media.session.ISessionController;
import android.media.session.MediaController;
import android.media.session.MediaSession;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.os.Bundle;
import android.os.PowerExemptionManager;
import android.os.RemoteException;
import android.os.UserHandle;
import android.service.notification.StatusBarNotification;
import android.testing.AndroidTestingRunner;
import android.testing.TestableLooper;
import android.text.TextUtils;
import android.view.View;

import androidx.core.graphics.drawable.IconCompat;
import androidx.test.filters.SmallTest;

import com.android.settingslib.bluetooth.CachedBluetoothDeviceManager;
import com.android.settingslib.bluetooth.LocalBluetoothManager;
import com.android.settingslib.media.LocalMediaManager;
import com.android.settingslib.media.MediaDevice;
import com.android.systemui.res.R;
import com.android.systemui.SysuiTestCase;
import com.android.systemui.animation.ActivityLaunchAnimator;
import com.android.systemui.animation.DialogLaunchAnimator;
import com.android.systemui.flags.FeatureFlags;
import com.android.systemui.media.nearby.NearbyMediaDevicesManager;
import com.android.systemui.plugins.ActivityStarter;
import com.android.systemui.settings.UserTracker;
import com.android.systemui.statusbar.notification.collection.NotificationEntry;
import com.android.systemui.statusbar.notification.collection.notifcollection.CommonNotifCollection;

import com.google.common.collect.ImmutableList;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.MockitoAnnotations;

import java.util.ArrayList;
import java.util.List;

@SmallTest
@RunWith(AndroidTestingRunner.class)
@TestableLooper.RunWithLooper(setAsMainLooper = true)
public class MediaOutputControllerTest extends SysuiTestCase {
    private static final String TEST_DEVICE_1_ID = "test_device_1_id";
    private static final String TEST_DEVICE_2_ID = "test_device_2_id";
    private static final String TEST_DEVICE_3_ID = "test_device_3_id";
    private static final String TEST_DEVICE_4_ID = "test_device_4_id";
    private static final String TEST_DEVICE_5_ID = "test_device_5_id";
    private static final String TEST_ARTIST = "test_artist";
    private static final String TEST_SONG = "test_song";
    private static final String TEST_SESSION_ID = "test_session_id";
    private static final String TEST_SESSION_NAME = "test_session_name";
    @Mock
    private DialogLaunchAnimator mDialogLaunchAnimator;
    @Mock
    private ActivityLaunchAnimator.Controller mActivityLaunchAnimatorController;
    @Mock
    private NearbyMediaDevicesManager mNearbyMediaDevicesManager;
    // Mock
    @Mock
    private MediaController mSessionMediaController;
    @Mock
    private MediaSessionManager mMediaSessionManager;
    @Mock
    private CachedBluetoothDeviceManager mCachedBluetoothDeviceManager;
    @Mock
    private LocalBluetoothManager mLocalBluetoothManager;
    @Mock
    private MediaOutputController.Callback mCb;
    @Mock
    private MediaDevice mMediaDevice1;
    @Mock
    private MediaDevice mMediaDevice2;
    @Mock
    private NearbyDevice mNearbyDevice1;
    @Mock
    private NearbyDevice mNearbyDevice2;
    @Mock
    private MediaMetadata mMediaMetadata;
    @Mock
    private RoutingSessionInfo mRemoteSessionInfo;
    @Mock
    private ActivityStarter mStarter;
    @Mock
    private AudioManager mAudioManager;
    @Mock
    private KeyguardManager mKeyguardManager;
    @Mock
    private ActivityLaunchAnimator.Controller mController;
    @Mock
    private PowerExemptionManager mPowerExemptionManager;
    @Mock
    private CommonNotifCollection mNotifCollection;
    @Mock
    private PackageManager mPackageManager;
    @Mock
    private Drawable mDrawable;
    @Mock
    private PlaybackState mPlaybackState;

    @Mock
    private UserTracker mUserTracker;

    private FeatureFlags mFlags = mock(FeatureFlags.class);
    private View mDialogLaunchView = mock(View.class);
    private MediaOutputController.Callback mCallback = mock(MediaOutputController.Callback.class);

    final Notification mNotification = mock(Notification.class);

    private Context mSpyContext;
    private String mPackageName = null;
    private MediaOutputController mMediaOutputController;
    private LocalMediaManager mLocalMediaManager;
    private List<MediaController> mMediaControllers = new ArrayList<>();
    private List<MediaDevice> mMediaDevices = new ArrayList<>();
    private List<NearbyDevice> mNearbyDevices = new ArrayList<>();
    private MediaDescription mMediaDescription;
    private List<RoutingSessionInfo> mRoutingSessionInfos = new ArrayList<>();

    @Before
    public void setUp() {
        mPackageName = mContext.getPackageName();

        MockitoAnnotations.initMocks(this);
        mContext.setMockPackageManager(mPackageManager);
        mSpyContext = spy(mContext);
        final UserHandle userHandle = mock(UserHandle.class);
        when(mUserTracker.getUserHandle()).thenReturn(userHandle);
        when(mSessionMediaController.getPackageName()).thenReturn(mPackageName);
        when(mSessionMediaController.getPlaybackState()).thenReturn(mPlaybackState);
        mMediaControllers.add(mSessionMediaController);
        when(mMediaSessionManager.getActiveSessionsForUser(any(),
                Mockito.eq(userHandle))).thenReturn(
                mMediaControllers);
        doReturn(mMediaSessionManager).when(mSpyContext).getSystemService(
                MediaSessionManager.class);
        when(mLocalBluetoothManager.getCachedDeviceManager()).thenReturn(
                mCachedBluetoothDeviceManager);

        mMediaOutputController = new MediaOutputController(mSpyContext, mPackageName,
                mMediaSessionManager, mLocalBluetoothManager, mStarter,
                mNotifCollection, mDialogLaunchAnimator,
                mNearbyMediaDevicesManager, mAudioManager, mPowerExemptionManager,
                mKeyguardManager, mFlags, mUserTracker);
        mLocalMediaManager = spy(mMediaOutputController.mLocalMediaManager);
        when(mLocalMediaManager.isPreferenceRouteListingExist()).thenReturn(false);
        mMediaOutputController.mLocalMediaManager = mLocalMediaManager;
        MediaDescription.Builder builder = new MediaDescription.Builder();
        builder.setTitle(TEST_SONG);
        builder.setSubtitle(TEST_ARTIST);
        mMediaDescription = builder.build();
        when(mMediaMetadata.getDescription()).thenReturn(mMediaDescription);
        when(mMediaDevice1.getId()).thenReturn(TEST_DEVICE_1_ID);
        when(mMediaDevice2.getId()).thenReturn(TEST_DEVICE_2_ID);
        mMediaDevices.add(mMediaDevice1);
        mMediaDevices.add(mMediaDevice2);


        when(mNearbyDevice1.getMediaRoute2Id()).thenReturn(TEST_DEVICE_1_ID);
        when(mNearbyDevice1.getRangeZone()).thenReturn(NearbyDevice.RANGE_FAR);
        when(mNearbyDevice2.getMediaRoute2Id()).thenReturn(TEST_DEVICE_2_ID);
        when(mNearbyDevice2.getRangeZone()).thenReturn(NearbyDevice.RANGE_CLOSE);
        mNearbyDevices.add(mNearbyDevice1);
        mNearbyDevices.add(mNearbyDevice2);

        final List<NotificationEntry> entryList = new ArrayList<>();
        final NotificationEntry entry = mock(NotificationEntry.class);
        final StatusBarNotification sbn = mock(StatusBarNotification.class);
        final Bundle bundle = mock(Bundle.class);
        final MediaSession.Token token = mock(MediaSession.Token.class);
        final ISessionController binder = mock(ISessionController.class);
        entryList.add(entry);

        when(mNotification.isMediaNotification()).thenReturn(false);
        when(mNotifCollection.getAllNotifs()).thenReturn(entryList);
        when(entry.getSbn()).thenReturn(sbn);
        when(sbn.getNotification()).thenReturn(mNotification);
        when(sbn.getPackageName()).thenReturn(mPackageName);
        mNotification.extras = bundle;
        when(bundle.getParcelable(Notification.EXTRA_MEDIA_SESSION,
                MediaSession.Token.class)).thenReturn(token);
        when(token.getBinder()).thenReturn(binder);
    }

    @Test
    public void start_verifyLocalMediaManagerInit() {
        mMediaOutputController.start(mCb);

        verify(mLocalMediaManager).registerCallback(mMediaOutputController);
        verify(mLocalMediaManager).startScan();
    }

    @Test
    public void stop_verifyLocalMediaManagerDeinit() {
        mMediaOutputController.start(mCb);
        reset(mLocalMediaManager);

        mMediaOutputController.stop();

        verify(mLocalMediaManager).unregisterCallback(mMediaOutputController);
        verify(mLocalMediaManager).stopScan();
    }

    @Test
    public void start_notificationNotFound_mediaControllerInitFromSession() {
        mMediaOutputController.start(mCb);

        verify(mSessionMediaController).registerCallback(any());
    }

    @Test
    public void start_MediaNotificationFound_mediaControllerNotInitFromSession() {
        when(mNotification.isMediaNotification()).thenReturn(true);
        mMediaOutputController.start(mCb);

        verify(mSessionMediaController, never()).registerCallback(any());
        verifyZeroInteractions(mMediaSessionManager);
    }

    @Test
    public void start_withoutPackageName_verifyMediaControllerInit() {
        mMediaOutputController = new MediaOutputController(mSpyContext, null,
                mMediaSessionManager, mLocalBluetoothManager, mStarter,
                mNotifCollection, mDialogLaunchAnimator,
                mNearbyMediaDevicesManager, mAudioManager, mPowerExemptionManager,
                mKeyguardManager, mFlags, mUserTracker);

        mMediaOutputController.start(mCb);

        verify(mSessionMediaController, never()).registerCallback(any());
    }

    @Test
    public void start_nearbyMediaDevicesManagerNotNull_registersNearbyDevicesCallback() {
        mMediaOutputController.start(mCb);

        verify(mNearbyMediaDevicesManager).registerNearbyDevicesCallback(any());
    }

    @Test
    public void stop_withPackageName_verifyMediaControllerDeinit() {
        mMediaOutputController.start(mCb);
        reset(mSessionMediaController);

        mMediaOutputController.stop();

        verify(mSessionMediaController).unregisterCallback(any());
    }

    @Test
    public void stop_withoutPackageName_verifyMediaControllerDeinit() {
        mMediaOutputController = new MediaOutputController(mSpyContext, null,
                mMediaSessionManager, mLocalBluetoothManager, mStarter,
                mNotifCollection, mDialogLaunchAnimator,
                mNearbyMediaDevicesManager, mAudioManager, mPowerExemptionManager,
                mKeyguardManager, mFlags, mUserTracker);

        mMediaOutputController.start(mCb);

        mMediaOutputController.stop();

        verify(mSessionMediaController, never()).unregisterCallback(any());
    }

    @Test
    public void stop_nearbyMediaDevicesManagerNotNull_unregistersNearbyDevicesCallback() {
        mMediaOutputController.start(mCb);
        reset(mSessionMediaController);

        mMediaOutputController.stop();

        verify(mNearbyMediaDevicesManager).unregisterNearbyDevicesCallback(any());
    }

    @Test
    public void tryToLaunchMediaApplication_nullIntent_skip() {
        mMediaOutputController.tryToLaunchMediaApplication(mDialogLaunchView);

        verify(mCb, never()).dismissDialog();
    }

    @Test
    public void tryToLaunchMediaApplication_intentNotNull_startActivity() {
        when(mDialogLaunchAnimator.createActivityLaunchController(any(View.class))).thenReturn(
                mController);
        Intent intent = new Intent(mPackageName);
        doReturn(intent).when(mPackageManager).getLaunchIntentForPackage(mPackageName);
        mMediaOutputController.start(mCallback);

        mMediaOutputController.tryToLaunchMediaApplication(mDialogLaunchView);

        verify(mStarter).startActivity(any(Intent.class), anyBoolean(),
                Mockito.eq(mController));
    }

    @Test
    public void tryToLaunchInAppRoutingIntent_componentNameNotNull_startActivity() {
        when(mDialogLaunchAnimator.createActivityLaunchController(any(View.class))).thenReturn(
                mController);
        mMediaOutputController.start(mCallback);
        when(mLocalMediaManager.getLinkedItemComponentName()).thenReturn(
                new ComponentName(mPackageName, ""));

        mMediaOutputController.tryToLaunchInAppRoutingIntent(TEST_DEVICE_1_ID, mDialogLaunchView);

        verify(mStarter).startActivity(any(Intent.class), anyBoolean(),
                Mockito.eq(mController));
    }

    @Test
    public void onDevicesUpdated_unregistersNearbyDevicesCallback() throws RemoteException {
        mMediaOutputController.start(mCb);

        mMediaOutputController.onDevicesUpdated(ImmutableList.of());

        verify(mNearbyMediaDevicesManager).unregisterNearbyDevicesCallback(any());
    }

    @Test
    public void onDeviceListUpdate_withNearbyDevices_updatesRangeInformation()
            throws RemoteException {
        mMediaOutputController.start(mCb);
        reset(mCb);

        mMediaOutputController.onDevicesUpdated(mNearbyDevices);
        mMediaOutputController.onDeviceListUpdate(mMediaDevices);

        verify(mMediaDevice1).setRangeZone(NearbyDevice.RANGE_FAR);
        verify(mMediaDevice2).setRangeZone(NearbyDevice.RANGE_CLOSE);
    }

    @Test
    public void onDeviceListUpdate_withNearbyDevices_rankByRangeInformation()
            throws RemoteException {
        mMediaOutputController.start(mCb);
        reset(mCb);

        mMediaOutputController.onDevicesUpdated(mNearbyDevices);
        mMediaOutputController.onDeviceListUpdate(mMediaDevices);

        assertThat(mMediaDevices.get(0).getId()).isEqualTo(TEST_DEVICE_1_ID);
    }

    @Test
    public void routeProcessSupport_onDeviceListUpdate_preferenceExist_NotUpdatesRangeInformation()
            throws RemoteException {
        when(mLocalMediaManager.isPreferenceRouteListingExist()).thenReturn(true);
        mMediaOutputController.start(mCb);
        reset(mCb);

        mMediaOutputController.onDevicesUpdated(mNearbyDevices);
        mMediaOutputController.onDeviceListUpdate(mMediaDevices);

        verify(mMediaDevice1, never()).setRangeZone(anyInt());
        verify(mMediaDevice2, never()).setRangeZone(anyInt());
    }

    @Test
    public void onDeviceListUpdate_verifyDeviceListCallback() {
        mMediaOutputController.start(mCb);
        reset(mCb);

        mMediaOutputController.onDeviceListUpdate(mMediaDevices);
        final List<MediaDevice> devices = new ArrayList<>();
        for (MediaItem item : mMediaOutputController.getMediaItemList()) {
            if (item.getMediaDevice().isPresent()) {
                devices.add(item.getMediaDevice().get());
            }
        }

        assertThat(devices.containsAll(mMediaDevices)).isTrue();
        assertThat(devices.size()).isEqualTo(mMediaDevices.size());
        assertThat(mMediaOutputController.getMediaItemList().size()).isEqualTo(
                mMediaDevices.size() + 2);
        verify(mCb).onDeviceListChanged();
    }

    @Test
    public void advanced_onDeviceListUpdateWithConnectedDeviceRemote_verifyItemSize() {
        when(mMediaDevice1.getFeatures()).thenReturn(
                ImmutableList.of(MediaRoute2Info.FEATURE_REMOTE_PLAYBACK));
        when(mLocalMediaManager.getCurrentConnectedDevice()).thenReturn(mMediaDevice1);
        mMediaOutputController.start(mCb);
        reset(mCb);

        mMediaOutputController.onDeviceListUpdate(mMediaDevices);
        final List<MediaDevice> devices = new ArrayList<>();
        for (MediaItem item : mMediaOutputController.getMediaItemList()) {
            if (item.getMediaDevice().isPresent()) {
                devices.add(item.getMediaDevice().get());
            }
        }

        assertThat(devices.containsAll(mMediaDevices)).isTrue();
        assertThat(devices.size()).isEqualTo(mMediaDevices.size());
        assertThat(mMediaOutputController.getMediaItemList().size()).isEqualTo(
                mMediaDevices.size() + 1);
        verify(mCb).onDeviceListChanged();
    }

    @Test
    public void advanced_categorizeMediaItems_withSuggestedDevice_verifyDeviceListSize() {
        when(mMediaDevice1.isSuggestedDevice()).thenReturn(true);
        when(mMediaDevice2.isSuggestedDevice()).thenReturn(false);

        mMediaOutputController.start(mCb);
        reset(mCb);
        mMediaOutputController.getMediaItemList().clear();
        mMediaOutputController.onDeviceListUpdate(mMediaDevices);
        final List<MediaDevice> devices = new ArrayList<>();
        int dividerSize = 0;
        for (MediaItem item : mMediaOutputController.getMediaItemList()) {
            if (item.getMediaDevice().isPresent()) {
                devices.add(item.getMediaDevice().get());
            }
            if (item.getMediaItemType() == MediaItem.MediaItemType.TYPE_GROUP_DIVIDER) {
                dividerSize++;
            }
        }

        assertThat(devices.containsAll(mMediaDevices)).isTrue();
        assertThat(devices.size()).isEqualTo(mMediaDevices.size());
        assertThat(dividerSize).isEqualTo(2);
        verify(mCb).onDeviceListChanged();
    }

    @Test
    public void onDeviceListUpdate_isRefreshing_updatesNeedRefreshToTrue() {
        mMediaOutputController.start(mCb);
        reset(mCb);
        mMediaOutputController.mIsRefreshing = true;

        mMediaOutputController.onDeviceListUpdate(mMediaDevices);

        assertThat(mMediaOutputController.mNeedRefresh).isTrue();
    }

    @Test
    public void advanced_onDeviceListUpdate_isRefreshing_updatesNeedRefreshToTrue() {
        mMediaOutputController.start(mCb);
        reset(mCb);
        mMediaOutputController.mIsRefreshing = true;

        mMediaOutputController.onDeviceListUpdate(mMediaDevices);

        assertThat(mMediaOutputController.mNeedRefresh).isTrue();
    }

    @Test
    public void cancelMuteAwaitConnection_cancelsWithMediaManager() {
        when(mAudioManager.getMutingExpectedDevice()).thenReturn(mock(AudioDeviceAttributes.class));
        mMediaOutputController.start(mCb);
        reset(mCb);

        mMediaOutputController.cancelMuteAwaitConnection();

        verify(mAudioManager).cancelMuteAwaitConnection(any());
    }

    @Test
    public void cancelMuteAwaitConnection_audioManagerIsNull_noAction() {
        when(mAudioManager.getMutingExpectedDevice()).thenReturn(null);
        mMediaOutputController.start(mCb);
        reset(mCb);
        mMediaOutputController.cancelMuteAwaitConnection();

        verify(mAudioManager, never()).cancelMuteAwaitConnection(any());
    }

    @Test
    public void getAppSourceName_packageNameIsNull_returnsNull() {
        MediaOutputController testMediaOutputController = new MediaOutputController(mSpyContext,
                "",
                mMediaSessionManager, mLocalBluetoothManager, mStarter,
                mNotifCollection, mDialogLaunchAnimator,
                mNearbyMediaDevicesManager, mAudioManager, mPowerExemptionManager,
                mKeyguardManager, mFlags, mUserTracker);
        testMediaOutputController.start(mCb);
        reset(mCb);

        testMediaOutputController.getAppSourceName();

        assertThat(testMediaOutputController.getAppSourceName()).isNull();
    }

    @Test
    public void isActiveItem_deviceNotConnected_returnsFalse() {
        when(mLocalMediaManager.getCurrentConnectedDevice()).thenReturn(mMediaDevice2);

        assertThat(mMediaOutputController.isActiveItem(mMediaDevice1)).isFalse();
    }

    @Test
    public void getNotificationSmallIcon_packageNameIsNull_returnsNull() {
        MediaOutputController testMediaOutputController = new MediaOutputController(mSpyContext,
                "",
                mMediaSessionManager, mLocalBluetoothManager, mStarter,
                mNotifCollection, mDialogLaunchAnimator,
                mNearbyMediaDevicesManager, mAudioManager, mPowerExemptionManager,
                mKeyguardManager, mFlags, mUserTracker);
        testMediaOutputController.start(mCb);
        reset(mCb);

        testMediaOutputController.getAppSourceName();

        assertThat(testMediaOutputController.getNotificationSmallIcon()).isNull();
    }

    @Test
    public void refreshDataSetIfNeeded_needRefreshIsTrue_setsToFalse() {
        mMediaOutputController.start(mCb);
        reset(mCb);
        mMediaOutputController.mNeedRefresh = true;

        mMediaOutputController.refreshDataSetIfNeeded();

        assertThat(mMediaOutputController.mNeedRefresh).isFalse();
    }

    @Test
    public void isCurrentConnectedDeviceRemote_containsFeatures_returnsTrue() {
        when(mMediaDevice1.getFeatures()).thenReturn(
                ImmutableList.of(MediaRoute2Info.FEATURE_REMOTE_PLAYBACK));
        when(mLocalMediaManager.getCurrentConnectedDevice()).thenReturn(mMediaDevice1);

        assertThat(mMediaOutputController.isCurrentConnectedDeviceRemote()).isTrue();
    }

    @Test
    public void addDeviceToPlayMedia_triggersFromLocalMediaManager() {
        MediaOutputController testMediaOutputController = new MediaOutputController(mSpyContext,
                null,
                mMediaSessionManager, mLocalBluetoothManager, mStarter,
                mNotifCollection, mDialogLaunchAnimator,
                mNearbyMediaDevicesManager, mAudioManager, mPowerExemptionManager,
                mKeyguardManager, mFlags, mUserTracker);

        LocalMediaManager testLocalMediaManager = spy(testMediaOutputController.mLocalMediaManager);
        testMediaOutputController.mLocalMediaManager = testLocalMediaManager;

        testMediaOutputController.addDeviceToPlayMedia(mMediaDevice2);

        verify(testLocalMediaManager).addDeviceToPlayMedia(mMediaDevice2);
    }

    @Test
    public void removeDeviceFromPlayMedia_triggersFromLocalMediaManager() {
        MediaOutputController testMediaOutputController = new MediaOutputController(mSpyContext,
                null,
                mMediaSessionManager, mLocalBluetoothManager, mStarter,
                mNotifCollection, mDialogLaunchAnimator,
                mNearbyMediaDevicesManager, mAudioManager, mPowerExemptionManager,
                mKeyguardManager, mFlags, mUserTracker);

        LocalMediaManager testLocalMediaManager = spy(testMediaOutputController.mLocalMediaManager);
        testMediaOutputController.mLocalMediaManager = testLocalMediaManager;

        testMediaOutputController.removeDeviceFromPlayMedia(mMediaDevice2);

        verify(testLocalMediaManager).removeDeviceFromPlayMedia(mMediaDevice2);
    }

    @Test
    public void getDeselectableMediaDevice_triggersFromLocalMediaManager() {
        mMediaOutputController.getDeselectableMediaDevice();

        verify(mLocalMediaManager).getDeselectableMediaDevice();
    }

    @Test
    public void adjustSessionVolume_adjustWithoutId_triggersFromLocalMediaManager() {
        int testVolume = 10;
        mMediaOutputController.adjustSessionVolume(testVolume);

        verify(mLocalMediaManager).adjustSessionVolume(testVolume);
    }

    @Test
    public void logInteractionAdjustVolume_triggersFromMetricLogger() {
        MediaOutputMetricLogger spyMediaOutputMetricLogger = spy(
                mMediaOutputController.mMetricLogger);
        mMediaOutputController.mMetricLogger = spyMediaOutputMetricLogger;

        mMediaOutputController.logInteractionAdjustVolume(mMediaDevice1);

        verify(spyMediaOutputMetricLogger).logInteractionAdjustVolume(mMediaDevice1);
    }

    @Test
    public void getSessionVolumeMax_triggersFromLocalMediaManager() {
        mMediaOutputController.getSessionVolumeMax();

        verify(mLocalMediaManager).getSessionVolumeMax();
    }

    @Test
    public void getSessionVolume_triggersFromLocalMediaManager() {
        mMediaOutputController.getSessionVolume();

        verify(mLocalMediaManager).getSessionVolume();
    }

    @Test
    public void getSessionName_triggersFromLocalMediaManager() {
        mMediaOutputController.getSessionName();

        verify(mLocalMediaManager).getSessionName();
    }

    @Test
    public void releaseSession_triggersFromLocalMediaManager() {
        mMediaOutputController.releaseSession();

        verify(mLocalMediaManager).releaseSession();
    }

    @Test
    public void isAnyDeviceTransferring_noDevicesStateIsConnecting_returnsFalse() {
        mMediaOutputController.start(mCb);
        reset(mCb);

        mMediaOutputController.onDeviceListUpdate(mMediaDevices);

        assertThat(mMediaOutputController.isAnyDeviceTransferring()).isFalse();
    }

    @Test
    public void isAnyDeviceTransferring_deviceStateIsConnecting_returnsTrue() {
        when(mMediaDevice1.getState()).thenReturn(
                LocalMediaManager.MediaDeviceState.STATE_CONNECTING);
        mMediaOutputController.start(mCb);
        reset(mCb);

        mMediaOutputController.onDeviceListUpdate(mMediaDevices);

        assertThat(mMediaOutputController.isAnyDeviceTransferring()).isTrue();
    }

    @Test
    public void isAnyDeviceTransferring_advancedLayoutSupport() {
        when(mMediaDevice1.getState()).thenReturn(
                LocalMediaManager.MediaDeviceState.STATE_CONNECTING);
        mMediaOutputController.start(mCb);
        mMediaOutputController.onDeviceListUpdate(mMediaDevices);

        assertThat(mMediaOutputController.isAnyDeviceTransferring()).isTrue();
    }

    @Test
    public void isPlaying_stateIsNull() {
        when(mSessionMediaController.getPlaybackState()).thenReturn(null);

        assertThat(mMediaOutputController.isPlaying()).isFalse();
    }

    @Test
    public void onSelectedDeviceStateChanged_verifyCallback() {
        when(mLocalMediaManager.getCurrentConnectedDevice()).thenReturn(mMediaDevice2);
        mMediaOutputController.start(mCb);
        reset(mCb);
        mMediaOutputController.connectDevice(mMediaDevice1);

        mMediaOutputController.onSelectedDeviceStateChanged(mMediaDevice1,
                LocalMediaManager.MediaDeviceState.STATE_CONNECTED);

        verify(mCb).onRouteChanged();
    }

    @Test
    public void onDeviceAttributesChanged_verifyCallback() {
        mMediaOutputController.start(mCb);
        reset(mCb);

        mMediaOutputController.onDeviceAttributesChanged();

        verify(mCb).onRouteChanged();
    }

    @Test
    public void onRequestFailed_verifyCallback() {
        when(mLocalMediaManager.getCurrentConnectedDevice()).thenReturn(mMediaDevice1);
        mMediaOutputController.start(mCb);
        reset(mCb);
        mMediaOutputController.connectDevice(mMediaDevice2);

        mMediaOutputController.onRequestFailed(0 /* reason */);

        verify(mCb, atLeastOnce()).onRouteChanged();
    }

    @Test
    public void getHeaderTitle_withoutMetadata_returnDefaultString() {
        when(mSessionMediaController.getMetadata()).thenReturn(null);

        mMediaOutputController.start(mCb);

        assertThat(mMediaOutputController.getHeaderTitle()).isEqualTo(
                mContext.getText(R.string.controls_media_title));
    }

    @Test
    public void getHeaderTitle_withMetadata_returnSongName() {
        when(mSessionMediaController.getMetadata()).thenReturn(mMediaMetadata);

        mMediaOutputController.start(mCb);

        assertThat(mMediaOutputController.getHeaderTitle()).isEqualTo(TEST_SONG);
    }

    @Test
    public void getHeaderSubTitle_withoutMetadata_returnNull() {
        when(mSessionMediaController.getMetadata()).thenReturn(null);

        mMediaOutputController.start(mCb);

        assertThat(mMediaOutputController.getHeaderSubTitle()).isNull();
    }

    @Test
    public void getHeaderSubTitle_withMetadata_returnArtistName() {
        when(mSessionMediaController.getMetadata()).thenReturn(mMediaMetadata);

        mMediaOutputController.start(mCb);

        assertThat(mMediaOutputController.getHeaderSubTitle()).isEqualTo(TEST_ARTIST);
    }

    @Test
    public void getActiveRemoteMediaDevices() {
        when(mRemoteSessionInfo.getId()).thenReturn(TEST_SESSION_ID);
        when(mRemoteSessionInfo.getName()).thenReturn(TEST_SESSION_NAME);
        when(mRemoteSessionInfo.getVolumeMax()).thenReturn(100);
        when(mRemoteSessionInfo.getVolume()).thenReturn(10);
        when(mRemoteSessionInfo.isSystemSession()).thenReturn(false);
        mRoutingSessionInfos.add(mRemoteSessionInfo);
        when(mLocalMediaManager.getRemoteRoutingSessions()).thenReturn(mRoutingSessionInfos);

        assertThat(mMediaOutputController.getActiveRemoteMediaDevices()).containsExactly(
                mRemoteSessionInfo);
    }

    @Test
    public void getGroupMediaDevices_differentDeviceOrder_showingSameOrder() {
        final MediaDevice selectedMediaDevice1 = mock(MediaDevice.class);
        final MediaDevice selectedMediaDevice2 = mock(MediaDevice.class);
        final MediaDevice selectableMediaDevice1 = mock(MediaDevice.class);
        final MediaDevice selectableMediaDevice2 = mock(MediaDevice.class);
        final List<MediaDevice> selectedMediaDevices = new ArrayList<>();
        final List<MediaDevice> selectableMediaDevices = new ArrayList<>();
        when(selectedMediaDevice1.getId()).thenReturn(TEST_DEVICE_1_ID);
        when(selectedMediaDevice2.getId()).thenReturn(TEST_DEVICE_2_ID);
        when(selectableMediaDevice1.getId()).thenReturn(TEST_DEVICE_3_ID);
        when(selectableMediaDevice2.getId()).thenReturn(TEST_DEVICE_4_ID);
        selectedMediaDevices.add(selectedMediaDevice1);
        selectedMediaDevices.add(selectedMediaDevice2);
        selectableMediaDevices.add(selectableMediaDevice1);
        selectableMediaDevices.add(selectableMediaDevice2);
        doReturn(selectedMediaDevices).when(mLocalMediaManager).getSelectedMediaDevice();
        doReturn(selectableMediaDevices).when(mLocalMediaManager).getSelectableMediaDevice();
        final List<MediaDevice> groupMediaDevices = mMediaOutputController.getGroupMediaDevices();
        // Reset order
        selectedMediaDevices.clear();
        selectedMediaDevices.add(selectedMediaDevice2);
        selectedMediaDevices.add(selectedMediaDevice1);
        selectableMediaDevices.clear();
        selectableMediaDevices.add(selectableMediaDevice2);
        selectableMediaDevices.add(selectableMediaDevice1);
        final List<MediaDevice> newDevices = mMediaOutputController.getGroupMediaDevices();

        assertThat(newDevices.size()).isEqualTo(groupMediaDevices.size());
        for (int i = 0; i < groupMediaDevices.size(); i++) {
            assertThat(TextUtils.equals(groupMediaDevices.get(i).getId(),
                    newDevices.get(i).getId())).isTrue();
        }
    }

    @Test
    public void getGroupMediaDevices_newDevice_verifyDeviceOrder() {
        final MediaDevice selectedMediaDevice1 = mock(MediaDevice.class);
        final MediaDevice selectedMediaDevice2 = mock(MediaDevice.class);
        final MediaDevice selectableMediaDevice1 = mock(MediaDevice.class);
        final MediaDevice selectableMediaDevice2 = mock(MediaDevice.class);
        final MediaDevice selectableMediaDevice3 = mock(MediaDevice.class);
        final List<MediaDevice> selectedMediaDevices = new ArrayList<>();
        final List<MediaDevice> selectableMediaDevices = new ArrayList<>();
        when(selectedMediaDevice1.getId()).thenReturn(TEST_DEVICE_1_ID);
        when(selectedMediaDevice2.getId()).thenReturn(TEST_DEVICE_2_ID);
        when(selectableMediaDevice1.getId()).thenReturn(TEST_DEVICE_3_ID);
        when(selectableMediaDevice2.getId()).thenReturn(TEST_DEVICE_4_ID);
        when(selectableMediaDevice3.getId()).thenReturn(TEST_DEVICE_5_ID);
        selectedMediaDevices.add(selectedMediaDevice1);
        selectedMediaDevices.add(selectedMediaDevice2);
        selectableMediaDevices.add(selectableMediaDevice1);
        selectableMediaDevices.add(selectableMediaDevice2);
        doReturn(selectedMediaDevices).when(mLocalMediaManager).getSelectedMediaDevice();
        doReturn(selectableMediaDevices).when(mLocalMediaManager).getSelectableMediaDevice();
        final List<MediaDevice> groupMediaDevices = mMediaOutputController.getGroupMediaDevices();
        // Reset order
        selectedMediaDevices.clear();
        selectedMediaDevices.add(selectedMediaDevice2);
        selectedMediaDevices.add(selectedMediaDevice1);
        selectableMediaDevices.clear();
        selectableMediaDevices.add(selectableMediaDevice3);
        selectableMediaDevices.add(selectableMediaDevice2);
        selectableMediaDevices.add(selectableMediaDevice1);
        final List<MediaDevice> newDevices = mMediaOutputController.getGroupMediaDevices();

        assertThat(newDevices.size()).isEqualTo(5);
        for (int i = 0; i < groupMediaDevices.size(); i++) {
            assertThat(TextUtils.equals(groupMediaDevices.get(i).getId(),
                    newDevices.get(i).getId())).isTrue();
        }
        assertThat(newDevices.get(4).getId()).isEqualTo(TEST_DEVICE_5_ID);
    }

    @Test
    public void getNotificationLargeIcon_withoutPackageName_returnsNull() {
        mMediaOutputController = new MediaOutputController(mSpyContext, null,
                mMediaSessionManager, mLocalBluetoothManager, mStarter,
                mNotifCollection, mDialogLaunchAnimator,
                mNearbyMediaDevicesManager, mAudioManager, mPowerExemptionManager,
                mKeyguardManager, mFlags, mUserTracker);

        assertThat(mMediaOutputController.getNotificationIcon()).isNull();
    }

    @Test
    public void getNotificationLargeIcon_withoutLargeIcon_returnsNull() {
        final List<NotificationEntry> entryList = new ArrayList<>();
        final NotificationEntry entry = mock(NotificationEntry.class);
        final StatusBarNotification sbn = mock(StatusBarNotification.class);
        final Notification notification = mock(Notification.class);
        entryList.add(entry);

        when(mNotifCollection.getAllNotifs()).thenReturn(entryList);
        when(entry.getSbn()).thenReturn(sbn);
        when(sbn.getNotification()).thenReturn(notification);
        when(sbn.getPackageName()).thenReturn(mPackageName);
        when(notification.isMediaNotification()).thenReturn(true);
        when(notification.getLargeIcon()).thenReturn(null);

        assertThat(mMediaOutputController.getNotificationIcon()).isNull();
    }

    @Test
    public void getNotificationLargeIcon_withPackageNameAndMediaSession_returnsIconCompat() {
        final List<NotificationEntry> entryList = new ArrayList<>();
        final NotificationEntry entry = mock(NotificationEntry.class);
        final StatusBarNotification sbn = mock(StatusBarNotification.class);
        final Notification notification = mock(Notification.class);
        final Icon icon = mock(Icon.class);
        entryList.add(entry);

        when(mNotifCollection.getAllNotifs()).thenReturn(entryList);
        when(entry.getSbn()).thenReturn(sbn);
        when(sbn.getNotification()).thenReturn(notification);
        when(sbn.getPackageName()).thenReturn(mPackageName);
        when(notification.isMediaNotification()).thenReturn(true);
        when(notification.getLargeIcon()).thenReturn(icon);

        assertThat(mMediaOutputController.getNotificationIcon()).isInstanceOf(IconCompat.class);
    }

    @Test
    public void getNotificationLargeIcon_withPackageNameAndNoMediaSession_returnsNull() {
        final List<NotificationEntry> entryList = new ArrayList<>();
        final NotificationEntry entry = mock(NotificationEntry.class);
        final StatusBarNotification sbn = mock(StatusBarNotification.class);
        final Notification notification = mock(Notification.class);
        final Icon icon = mock(Icon.class);
        entryList.add(entry);

        when(mNotifCollection.getAllNotifs()).thenReturn(entryList);
        when(entry.getSbn()).thenReturn(sbn);
        when(sbn.getNotification()).thenReturn(notification);
        when(sbn.getPackageName()).thenReturn(mPackageName);
        when(notification.isMediaNotification()).thenReturn(false);
        when(notification.getLargeIcon()).thenReturn(icon);

        assertThat(mMediaOutputController.getNotificationIcon()).isNull();
    }

    @Test
    public void getNotificationSmallIcon_withoutSmallIcon_returnsNull() {
        final List<NotificationEntry> entryList = new ArrayList<>();
        final NotificationEntry entry = mock(NotificationEntry.class);
        final StatusBarNotification sbn = mock(StatusBarNotification.class);
        final Notification notification = mock(Notification.class);
        entryList.add(entry);

        when(mNotifCollection.getAllNotifs()).thenReturn(entryList);
        when(entry.getSbn()).thenReturn(sbn);
        when(sbn.getNotification()).thenReturn(notification);
        when(sbn.getPackageName()).thenReturn(mPackageName);
        when(notification.isMediaNotification()).thenReturn(true);
        when(notification.getSmallIcon()).thenReturn(null);

        assertThat(mMediaOutputController.getNotificationSmallIcon()).isNull();
    }

    @Test
    public void getNotificationSmallIcon_withPackageNameAndMediaSession_returnsIconCompat() {
        final List<NotificationEntry> entryList = new ArrayList<>();
        final NotificationEntry entry = mock(NotificationEntry.class);
        final StatusBarNotification sbn = mock(StatusBarNotification.class);
        final Notification notification = mock(Notification.class);
        final Icon icon = mock(Icon.class);
        entryList.add(entry);

        when(mNotifCollection.getAllNotifs()).thenReturn(entryList);
        when(entry.getSbn()).thenReturn(sbn);
        when(sbn.getNotification()).thenReturn(notification);
        when(sbn.getPackageName()).thenReturn(mPackageName);
        when(notification.isMediaNotification()).thenReturn(true);
        when(notification.getSmallIcon()).thenReturn(icon);

        assertThat(mMediaOutputController.getNotificationSmallIcon()).isInstanceOf(
                IconCompat.class);
    }

    @Test
    public void getDeviceIconCompat_deviceIconIsNotNull_returnsIcon() {
        when(mLocalMediaManager.getCurrentConnectedDevice()).thenReturn(mMediaDevice2);
        when(mMediaDevice1.getIcon()).thenReturn(mDrawable);

        assertThat(mMediaOutputController.getDeviceIconCompat(mMediaDevice1)).isInstanceOf(
                IconCompat.class);
    }

    @Test
    public void getDeviceIconCompat_deviceIconIsNull_returnsIcon() {
        when(mLocalMediaManager.getCurrentConnectedDevice()).thenReturn(mMediaDevice2);
        when(mMediaDevice1.getIcon()).thenReturn(null);

        assertThat(mMediaOutputController.getDeviceIconCompat(mMediaDevice1)).isInstanceOf(
                IconCompat.class);
    }

    @Test
    public void setColorFilter_setColorFilterToDrawable() {
        mMediaOutputController.setColorFilter(mDrawable, true);

        verify(mDrawable).setColorFilter(any(PorterDuffColorFilter.class));
    }

    @Test
    public void resetGroupMediaDevices_clearGroupDevices() {
        final MediaDevice selectedMediaDevice1 = mock(MediaDevice.class);
        final MediaDevice selectedMediaDevice2 = mock(MediaDevice.class);
        final MediaDevice selectableMediaDevice1 = mock(MediaDevice.class);
        final MediaDevice selectableMediaDevice2 = mock(MediaDevice.class);
        final List<MediaDevice> selectedMediaDevices = new ArrayList<>();
        final List<MediaDevice> selectableMediaDevices = new ArrayList<>();
        when(selectedMediaDevice1.getId()).thenReturn(TEST_DEVICE_1_ID);
        when(selectedMediaDevice2.getId()).thenReturn(TEST_DEVICE_2_ID);
        when(selectableMediaDevice1.getId()).thenReturn(TEST_DEVICE_3_ID);
        when(selectableMediaDevice2.getId()).thenReturn(TEST_DEVICE_4_ID);
        selectedMediaDevices.add(selectedMediaDevice1);
        selectedMediaDevices.add(selectedMediaDevice2);
        selectableMediaDevices.add(selectableMediaDevice1);
        selectableMediaDevices.add(selectableMediaDevice2);
        doReturn(selectedMediaDevices).when(mLocalMediaManager).getSelectedMediaDevice();
        doReturn(selectableMediaDevices).when(mLocalMediaManager).getSelectableMediaDevice();
        assertThat(mMediaOutputController.getGroupMediaDevices().isEmpty()).isFalse();

        mMediaOutputController.resetGroupMediaDevices();

        assertThat(mMediaOutputController.mGroupMediaDevices.isEmpty()).isTrue();
    }

    @Test
    public void isVolumeControlEnabled_isCastWithVolumeFixed_returnsFalse() {
        when(mMediaDevice1.getDeviceType()).thenReturn(
                MediaDevice.MediaDeviceType.TYPE_CAST_DEVICE);

        when(mMediaDevice1.isVolumeFixed()).thenReturn(true);

        assertThat(mMediaOutputController.isVolumeControlEnabled(mMediaDevice1)).isFalse();
    }

    @Test
    public void isVolumeControlEnabled_isCastWithVolumeNotFixed_returnsTrue() {
        when(mMediaDevice1.getDeviceType()).thenReturn(
                MediaDevice.MediaDeviceType.TYPE_CAST_DEVICE);

        when(mMediaDevice1.isVolumeFixed()).thenReturn(false);

        assertThat(mMediaOutputController.isVolumeControlEnabled(mMediaDevice1)).isTrue();
    }

    @Test
    public void setTemporaryAllowListExceptionIfNeeded_fromRemoteToBluetooth_addsAllowList() {
        when(mLocalMediaManager.getCurrentConnectedDevice()).thenReturn(mMediaDevice1);
        when(mMediaDevice1.getDeviceType()).thenReturn(
                MediaDevice.MediaDeviceType.TYPE_CAST_DEVICE);
        when(mMediaDevice1.getFeatures()).thenReturn(
                ImmutableList.of(MediaRoute2Info.FEATURE_REMOTE_AUDIO_PLAYBACK));
        when(mMediaDevice2.getDeviceType()).thenReturn(
                MediaDevice.MediaDeviceType.TYPE_BLUETOOTH_DEVICE);

        mMediaOutputController.setTemporaryAllowListExceptionIfNeeded(mMediaDevice2);

        verify(mPowerExemptionManager).addToTemporaryAllowList(anyString(), anyInt(), anyString(),
                anyLong());
    }

    @Test
    public void setTemporaryAllowListExceptionIfNeeded_packageNameIsNull_NoAction() {
        MediaOutputController testMediaOutputController = new MediaOutputController(mSpyContext,
                null,
                mMediaSessionManager, mLocalBluetoothManager, mStarter,
                mNotifCollection, mDialogLaunchAnimator,
                mNearbyMediaDevicesManager, mAudioManager, mPowerExemptionManager,
                mKeyguardManager, mFlags, mUserTracker);

        testMediaOutputController.setTemporaryAllowListExceptionIfNeeded(mMediaDevice2);

        verify(mPowerExemptionManager, never()).addToTemporaryAllowList(anyString(), anyInt(),
                anyString(),
                anyLong());
    }

    @Test
    public void onMetadataChanged_triggersOnMetadataChanged() {
        mMediaOutputController.mCallback = this.mCallback;

        mMediaOutputController.mCb.onMetadataChanged(mMediaMetadata);

        verify(mMediaOutputController.mCallback).onMediaChanged();
    }

    @Test
    public void onPlaybackStateChanged_updateWithNullState_onMediaStoppedOrPaused() {
        when(mPlaybackState.getState()).thenReturn(PlaybackState.STATE_PLAYING);
        mMediaOutputController.mCallback = this.mCallback;
        mMediaOutputController.start(mCb);

        mMediaOutputController.mCb.onPlaybackStateChanged(null);

        verify(mMediaOutputController.mCallback).onMediaStoppedOrPaused();
    }

    @Test
    public void launchBluetoothPairing_isKeyguardLocked_dismissDialog() {
        when(mDialogLaunchAnimator.createActivityLaunchController(mDialogLaunchView)).thenReturn(
                mActivityLaunchAnimatorController);
        when(mKeyguardManager.isKeyguardLocked()).thenReturn(true);
        mMediaOutputController.mCallback = this.mCallback;

        mMediaOutputController.launchBluetoothPairing(mDialogLaunchView);

        verify(mCallback).dismissDialog();
    }
}
