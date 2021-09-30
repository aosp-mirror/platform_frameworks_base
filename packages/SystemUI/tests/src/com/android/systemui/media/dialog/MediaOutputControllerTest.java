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
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.app.Notification;
import android.content.Context;
import android.graphics.drawable.Icon;
import android.media.MediaDescription;
import android.media.MediaMetadata;
import android.media.RoutingSessionInfo;
import android.media.session.MediaController;
import android.media.session.MediaSessionManager;
import android.service.notification.StatusBarNotification;
import android.testing.AndroidTestingRunner;
import android.text.TextUtils;

import androidx.core.graphics.drawable.IconCompat;
import androidx.test.filters.SmallTest;

import com.android.internal.logging.UiEventLogger;
import com.android.settingslib.bluetooth.CachedBluetoothDeviceManager;
import com.android.settingslib.bluetooth.LocalBluetoothManager;
import com.android.settingslib.media.LocalMediaManager;
import com.android.settingslib.media.MediaDevice;
import com.android.systemui.R;
import com.android.systemui.SysuiTestCase;
import com.android.systemui.animation.DialogLaunchAnimator;
import com.android.systemui.plugins.ActivityStarter;
import com.android.systemui.statusbar.notification.NotificationEntryManager;
import com.android.systemui.statusbar.notification.collection.NotificationEntry;
import com.android.systemui.statusbar.phone.ShadeController;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.ArrayList;
import java.util.List;

@SmallTest
@RunWith(AndroidTestingRunner.class)
public class MediaOutputControllerTest extends SysuiTestCase {

    private static final String TEST_PACKAGE_NAME = "com.test.package.name";
    private static final String TEST_DEVICE_1_ID = "test_device_1_id";
    private static final String TEST_DEVICE_2_ID = "test_device_2_id";
    private static final String TEST_DEVICE_3_ID = "test_device_3_id";
    private static final String TEST_DEVICE_4_ID = "test_device_4_id";
    private static final String TEST_DEVICE_5_ID = "test_device_5_id";
    private static final String TEST_ARTIST = "test_artist";
    private static final String TEST_SONG = "test_song";
    private static final String TEST_SESSION_ID = "test_session_id";
    private static final String TEST_SESSION_NAME = "test_session_name";
    // Mock
    private MediaController mMediaController = mock(MediaController.class);
    private MediaSessionManager mMediaSessionManager = mock(MediaSessionManager.class);
    private CachedBluetoothDeviceManager mCachedBluetoothDeviceManager =
            mock(CachedBluetoothDeviceManager.class);
    private LocalBluetoothManager mLocalBluetoothManager = mock(LocalBluetoothManager.class);
    private MediaOutputController.Callback mCb = mock(MediaOutputController.Callback.class);
    private MediaDevice mMediaDevice1 = mock(MediaDevice.class);
    private MediaDevice mMediaDevice2 = mock(MediaDevice.class);
    private MediaMetadata mMediaMetadata = mock(MediaMetadata.class);
    private RoutingSessionInfo mRemoteSessionInfo = mock(RoutingSessionInfo.class);
    private ShadeController mShadeController = mock(ShadeController.class);
    private ActivityStarter mStarter = mock(ActivityStarter.class);
    private NotificationEntryManager mNotificationEntryManager =
            mock(NotificationEntryManager.class);
    private final UiEventLogger mUiEventLogger = mock(UiEventLogger.class);
    private final DialogLaunchAnimator mDialogLaunchAnimator = mock(DialogLaunchAnimator.class);

    private Context mSpyContext;
    private MediaOutputController mMediaOutputController;
    private LocalMediaManager mLocalMediaManager;
    private List<MediaController> mMediaControllers = new ArrayList<>();
    private List<MediaDevice> mMediaDevices = new ArrayList<>();
    private MediaDescription mMediaDescription;
    private List<RoutingSessionInfo> mRoutingSessionInfos = new ArrayList<>();

    @Before
    public void setUp() {
        mSpyContext = spy(mContext);
        when(mMediaController.getPackageName()).thenReturn(TEST_PACKAGE_NAME);
        mMediaControllers.add(mMediaController);
        when(mMediaSessionManager.getActiveSessions(any())).thenReturn(mMediaControllers);
        doReturn(mMediaSessionManager).when(mSpyContext).getSystemService(
                MediaSessionManager.class);
        when(mLocalBluetoothManager.getCachedDeviceManager()).thenReturn(
                mCachedBluetoothDeviceManager);

        mMediaOutputController = new MediaOutputController(mSpyContext, TEST_PACKAGE_NAME, false,
                mMediaSessionManager, mLocalBluetoothManager, mShadeController, mStarter,
                mNotificationEntryManager, mUiEventLogger, mDialogLaunchAnimator);
        mLocalMediaManager = spy(mMediaOutputController.mLocalMediaManager);
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
    public void start_withPackageName_verifyMediaControllerInit() {
        mMediaOutputController.start(mCb);

        verify(mMediaController).registerCallback(any());
    }

    @Test
    public void start_withoutPackageName_verifyMediaControllerInit() {
        mMediaOutputController = new MediaOutputController(mSpyContext, null, false,
                mMediaSessionManager, mLocalBluetoothManager, mShadeController, mStarter,
                mNotificationEntryManager, mUiEventLogger, mDialogLaunchAnimator);

        mMediaOutputController.start(mCb);

        verify(mMediaController, never()).registerCallback(any());
    }

    @Test
    public void stop_withPackageName_verifyMediaControllerDeinit() {
        mMediaOutputController.start(mCb);
        reset(mMediaController);

        mMediaOutputController.stop();

        verify(mMediaController).unregisterCallback(any());
    }

    @Test
    public void stop_withoutPackageName_verifyMediaControllerDeinit() {
        mMediaOutputController = new MediaOutputController(mSpyContext, null, false,
                mMediaSessionManager, mLocalBluetoothManager, mShadeController, mStarter,
                mNotificationEntryManager, mUiEventLogger, mDialogLaunchAnimator);

        mMediaOutputController.start(mCb);

        mMediaOutputController.stop();

        verify(mMediaController, never()).unregisterCallback(any());
    }

    @Test
    public void onDeviceListUpdate_verifyDeviceListCallback() {
        mMediaOutputController.start(mCb);
        reset(mCb);

        mMediaOutputController.onDeviceListUpdate(mMediaDevices);
        final List<MediaDevice> devices = new ArrayList<>(mMediaOutputController.getMediaDevices());

        assertThat(devices.containsAll(mMediaDevices)).isTrue();
        assertThat(devices.size()).isEqualTo(mMediaDevices.size());
        verify(mCb).onRouteChanged();
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

        verify(mCb).onRouteChanged();
    }

    @Test
    public void getHeaderTitle_withoutMetadata_returnDefaultString() {
        when(mMediaController.getMetadata()).thenReturn(null);

        mMediaOutputController.start(mCb);

        assertThat(mMediaOutputController.getHeaderTitle()).isEqualTo(
                mContext.getText(R.string.controls_media_title));
    }

    @Test
    public void getHeaderTitle_withMetadata_returnSongName() {
        when(mMediaController.getMetadata()).thenReturn(mMediaMetadata);

        mMediaOutputController.start(mCb);

        assertThat(mMediaOutputController.getHeaderTitle()).isEqualTo(TEST_SONG);
    }

    @Test
    public void getHeaderSubTitle_withoutMetadata_returnNull() {
        when(mMediaController.getMetadata()).thenReturn(null);

        mMediaOutputController.start(mCb);

        assertThat(mMediaOutputController.getHeaderSubTitle()).isNull();
    }

    @Test
    public void getHeaderSubTitle_withMetadata_returnArtistName() {
        when(mMediaController.getMetadata()).thenReturn(mMediaMetadata);

        mMediaOutputController.start(mCb);

        assertThat(mMediaOutputController.getHeaderSubTitle()).isEqualTo(TEST_ARTIST);
    }

    @Test
    public void connectDevice_verifyConnect() {
        when(mLocalMediaManager.getCurrentConnectedDevice()).thenReturn(mMediaDevice2);

        mMediaOutputController.connectDevice(mMediaDevice1);

        // Wait for background thread execution
        try {
            Thread.sleep(100);
        } catch (InterruptedException e) {
            e.printStackTrace();
        }

        verify(mLocalMediaManager).connectDevice(mMediaDevice1);
    }

    @Test
    public void getActiveRemoteMediaDevice_isSystemSession_returnSession() {
        when(mRemoteSessionInfo.getId()).thenReturn(TEST_SESSION_ID);
        when(mRemoteSessionInfo.getName()).thenReturn(TEST_SESSION_NAME);
        when(mRemoteSessionInfo.getVolumeMax()).thenReturn(100);
        when(mRemoteSessionInfo.getVolume()).thenReturn(10);
        when(mRemoteSessionInfo.isSystemSession()).thenReturn(false);
        mRoutingSessionInfos.add(mRemoteSessionInfo);
        when(mLocalMediaManager.getActiveMediaSession()).thenReturn(mRoutingSessionInfos);

        assertThat(mMediaOutputController.getActiveRemoteMediaDevices()).containsExactly(
                mRemoteSessionInfo);
    }

    @Test
    public void getActiveRemoteMediaDevice_notSystemSession_returnEmpty() {
        when(mRemoteSessionInfo.getId()).thenReturn(TEST_SESSION_ID);
        when(mRemoteSessionInfo.getName()).thenReturn(TEST_SESSION_NAME);
        when(mRemoteSessionInfo.getVolumeMax()).thenReturn(100);
        when(mRemoteSessionInfo.getVolume()).thenReturn(10);
        when(mRemoteSessionInfo.isSystemSession()).thenReturn(true);
        mRoutingSessionInfos.add(mRemoteSessionInfo);
        when(mLocalMediaManager.getActiveMediaSession()).thenReturn(mRoutingSessionInfos);

        assertThat(mMediaOutputController.getActiveRemoteMediaDevices()).isEmpty();
    }

    @Test
    public void isZeroMode_onlyFromPhoneOutput_returnTrue() {
        // Multiple available devices
        assertThat(mMediaOutputController.isZeroMode()).isFalse();
        when(mMediaDevice1.getDeviceType()).thenReturn(
                MediaDevice.MediaDeviceType.TYPE_PHONE_DEVICE);
        mMediaDevices.clear();
        mMediaDevices.add(mMediaDevice1);
        mMediaOutputController.start(mCb);
        mMediaOutputController.onDeviceListUpdate(mMediaDevices);

        assertThat(mMediaOutputController.isZeroMode()).isTrue();

        when(mMediaDevice1.getDeviceType()).thenReturn(
                MediaDevice.MediaDeviceType.TYPE_3POINT5_MM_AUDIO_DEVICE);

        assertThat(mMediaOutputController.isZeroMode()).isTrue();

        when(mMediaDevice1.getDeviceType()).thenReturn(
                MediaDevice.MediaDeviceType.TYPE_USB_C_AUDIO_DEVICE);

        assertThat(mMediaOutputController.isZeroMode()).isTrue();
    }

    @Test
    public void isZeroMode_notFromPhoneOutput_returnFalse() {
        when(mMediaDevice1.getDeviceType()).thenReturn(
                MediaDevice.MediaDeviceType.TYPE_UNKNOWN);
        mMediaDevices.clear();
        mMediaDevices.add(mMediaDevice1);
        mMediaOutputController.start(mCb);
        mMediaOutputController.onDeviceListUpdate(mMediaDevices);

        assertThat(mMediaOutputController.isZeroMode()).isFalse();

        when(mMediaDevice1.getDeviceType()).thenReturn(
                MediaDevice.MediaDeviceType.TYPE_FAST_PAIR_BLUETOOTH_DEVICE);

        assertThat(mMediaOutputController.isZeroMode()).isFalse();

        when(mMediaDevice1.getDeviceType()).thenReturn(
                MediaDevice.MediaDeviceType.TYPE_BLUETOOTH_DEVICE);

        assertThat(mMediaOutputController.isZeroMode()).isFalse();

        when(mMediaDevice1.getDeviceType()).thenReturn(
                MediaDevice.MediaDeviceType.TYPE_CAST_DEVICE);

        assertThat(mMediaOutputController.isZeroMode()).isFalse();

        when(mMediaDevice1.getDeviceType()).thenReturn(
                MediaDevice.MediaDeviceType.TYPE_CAST_GROUP_DEVICE);

        assertThat(mMediaOutputController.isZeroMode()).isFalse();
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
        mMediaOutputController = new MediaOutputController(mSpyContext, null, false,
                mMediaSessionManager, mLocalBluetoothManager, mShadeController, mStarter,
                mNotificationEntryManager, mUiEventLogger, mDialogLaunchAnimator);

        assertThat(mMediaOutputController.getNotificationIcon()).isNull();
    }

    @Test
    public void getNotificationLargeIcon_withoutLargeIcon_returnsNull() {
        final List<NotificationEntry> entryList = new ArrayList<>();
        final NotificationEntry entry = mock(NotificationEntry.class);
        final StatusBarNotification sbn = mock(StatusBarNotification.class);
        final Notification notification = mock(Notification.class);
        entryList.add(entry);

        when(mNotificationEntryManager.getActiveNotificationsForCurrentUser())
                .thenReturn(entryList);
        when(entry.getSbn()).thenReturn(sbn);
        when(sbn.getNotification()).thenReturn(notification);
        when(sbn.getPackageName()).thenReturn(TEST_PACKAGE_NAME);
        when(notification.hasMediaSession()).thenReturn(true);
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

        when(mNotificationEntryManager.getActiveNotificationsForCurrentUser())
                .thenReturn(entryList);
        when(entry.getSbn()).thenReturn(sbn);
        when(sbn.getNotification()).thenReturn(notification);
        when(sbn.getPackageName()).thenReturn(TEST_PACKAGE_NAME);
        when(notification.hasMediaSession()).thenReturn(true);
        when(notification.getLargeIcon()).thenReturn(icon);

        assertThat(mMediaOutputController.getNotificationIcon() instanceof IconCompat).isTrue();
    }

    @Test
    public void getNotificationLargeIcon_withPackageNameAndNoMediaSession_returnsNull() {
        final List<NotificationEntry> entryList = new ArrayList<>();
        final NotificationEntry entry = mock(NotificationEntry.class);
        final StatusBarNotification sbn = mock(StatusBarNotification.class);
        final Notification notification = mock(Notification.class);
        final Icon icon = mock(Icon.class);
        entryList.add(entry);

        when(mNotificationEntryManager.getActiveNotificationsForCurrentUser())
                .thenReturn(entryList);
        when(entry.getSbn()).thenReturn(sbn);
        when(sbn.getNotification()).thenReturn(notification);
        when(sbn.getPackageName()).thenReturn(TEST_PACKAGE_NAME);
        when(notification.hasMediaSession()).thenReturn(false);
        when(notification.getLargeIcon()).thenReturn(icon);

        assertThat(mMediaOutputController.getNotificationIcon()).isNull();
    }
}
