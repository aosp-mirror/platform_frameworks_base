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

import static android.provider.Settings.ACTION_BLUETOOTH_PAIRING_SETTINGS;

import android.app.Notification;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.Icon;
import android.media.MediaMetadata;
import android.media.MediaRoute2Info;
import android.media.RoutingSessionInfo;
import android.media.session.MediaController;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.os.UserHandle;
import android.os.UserManager;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.Log;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.annotation.VisibleForTesting;
import androidx.core.graphics.drawable.IconCompat;

import com.android.internal.logging.UiEventLogger;
import com.android.settingslib.RestrictedLockUtilsInternal;
import com.android.settingslib.Utils;
import com.android.settingslib.bluetooth.BluetoothUtils;
import com.android.settingslib.bluetooth.LocalBluetoothManager;
import com.android.settingslib.media.InfoMediaManager;
import com.android.settingslib.media.LocalMediaManager;
import com.android.settingslib.media.MediaDevice;
import com.android.settingslib.utils.ThreadUtils;
import com.android.systemui.R;
import com.android.systemui.animation.DialogLaunchAnimator;
import com.android.systemui.plugins.ActivityStarter;
import com.android.systemui.statusbar.notification.NotificationEntryManager;
import com.android.systemui.statusbar.notification.collection.NotificationEntry;
import com.android.systemui.statusbar.phone.ShadeController;
import com.android.systemui.statusbar.phone.SystemUIDialogManager;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import javax.inject.Inject;

/**
 * Controller for media output dialog
 */
public class MediaOutputController implements LocalMediaManager.DeviceCallback {

    private static final String TAG = "MediaOutputController";
    private static final boolean DEBUG = Log.isLoggable(TAG, Log.DEBUG);
    private static final String PAGE_CONNECTED_DEVICES_KEY =
            "top_level_connected_devices";
    private final String mPackageName;
    private final Context mContext;
    private final MediaSessionManager mMediaSessionManager;
    private final LocalBluetoothManager mLocalBluetoothManager;
    private final ShadeController mShadeController;
    private final ActivityStarter mActivityStarter;
    private final DialogLaunchAnimator mDialogLaunchAnimator;
    private final SystemUIDialogManager mDialogManager;
    private final List<MediaDevice> mGroupMediaDevices = new CopyOnWriteArrayList<>();
    private final boolean mAboveStatusbar;
    private final NotificationEntryManager mNotificationEntryManager;
    @VisibleForTesting
    final List<MediaDevice> mMediaDevices = new CopyOnWriteArrayList<>();

    private MediaController mMediaController;
    @VisibleForTesting
    Callback mCallback;
    @VisibleForTesting
    LocalMediaManager mLocalMediaManager;

    private MediaOutputMetricLogger mMetricLogger;
    private UiEventLogger mUiEventLogger;

    @Inject
    public MediaOutputController(@NonNull Context context, String packageName,
            boolean aboveStatusbar, MediaSessionManager mediaSessionManager, LocalBluetoothManager
            lbm, ShadeController shadeController, ActivityStarter starter,
            NotificationEntryManager notificationEntryManager, UiEventLogger uiEventLogger,
            DialogLaunchAnimator dialogLaunchAnimator, SystemUIDialogManager dialogManager) {
        mContext = context;
        mPackageName = packageName;
        mMediaSessionManager = mediaSessionManager;
        mLocalBluetoothManager = lbm;
        mShadeController = shadeController;
        mActivityStarter = starter;
        mAboveStatusbar = aboveStatusbar;
        mNotificationEntryManager = notificationEntryManager;
        InfoMediaManager imm = new InfoMediaManager(mContext, packageName, null, lbm);
        mLocalMediaManager = new LocalMediaManager(mContext, lbm, imm, packageName);
        mMetricLogger = new MediaOutputMetricLogger(mContext, mPackageName);
        mUiEventLogger = uiEventLogger;
        mDialogLaunchAnimator = dialogLaunchAnimator;
        mDialogManager = dialogManager;
    }

    void start(@NonNull Callback cb) {
        mMediaDevices.clear();
        if (!TextUtils.isEmpty(mPackageName)) {
            for (MediaController controller : mMediaSessionManager.getActiveSessions(null)) {
                if (TextUtils.equals(controller.getPackageName(), mPackageName)) {
                    mMediaController = controller;
                    mMediaController.unregisterCallback(mCb);
                    mMediaController.registerCallback(mCb);
                    break;
                }
            }
        }
        if (mMediaController == null) {
            if (DEBUG) {
                Log.d(TAG, "No media controller for " + mPackageName);
            }
        }
        if (mLocalMediaManager == null) {
            if (DEBUG) {
                Log.d(TAG, "No local media manager " + mPackageName);
            }
            return;
        }
        mCallback = cb;
        mLocalMediaManager.unregisterCallback(this);
        mLocalMediaManager.stopScan();
        mLocalMediaManager.registerCallback(this);
        mLocalMediaManager.startScan();
    }

    void stop() {
        if (mMediaController != null) {
            mMediaController.unregisterCallback(mCb);
        }
        if (mLocalMediaManager != null) {
            mLocalMediaManager.unregisterCallback(this);
            mLocalMediaManager.stopScan();
        }
        mMediaDevices.clear();
    }

    @Override
    public void onDeviceListUpdate(List<MediaDevice> devices) {
        buildMediaDevices(devices);
        mCallback.onDeviceListChanged();
    }

    @Override
    public void onSelectedDeviceStateChanged(MediaDevice device,
            @LocalMediaManager.MediaDeviceState int state) {
        mCallback.onRouteChanged();
        mMetricLogger.logOutputSuccess(device.toString(), mMediaDevices);
    }

    @Override
    public void onDeviceAttributesChanged() {
        mCallback.onRouteChanged();
    }

    @Override
    public void onRequestFailed(int reason) {
        mCallback.onRouteChanged();
        mMetricLogger.logOutputFailure(mMediaDevices, reason);
    }

    CharSequence getHeaderTitle() {
        if (mMediaController != null) {
            final MediaMetadata metadata = mMediaController.getMetadata();
            if (metadata != null) {
                return metadata.getDescription().getTitle();
            }
        }
        return mContext.getText(R.string.controls_media_title);
    }

    CharSequence getHeaderSubTitle() {
        if (mMediaController == null) {
            return null;
        }
        final MediaMetadata metadata = mMediaController.getMetadata();
        if (metadata == null) {
            return null;
        }
        return metadata.getDescription().getSubtitle();
    }

    IconCompat getHeaderIcon() {
        if (mMediaController == null) {
            return null;
        }
        final MediaMetadata metadata = mMediaController.getMetadata();
        if (metadata != null) {
            final Bitmap bitmap = metadata.getDescription().getIconBitmap();
            if (bitmap != null) {
                final Bitmap roundBitmap = Utils.convertCornerRadiusBitmap(mContext, bitmap,
                        (float) mContext.getResources().getDimensionPixelSize(
                                R.dimen.media_output_dialog_icon_corner_radius));
                return IconCompat.createWithBitmap(roundBitmap);
            }
        }
        if (DEBUG) {
            Log.d(TAG, "Media meta data does not contain icon information");
        }
        return getNotificationIcon();
    }

    IconCompat getDeviceIconCompat(MediaDevice device) {
        Drawable drawable = device.getIcon();
        if (drawable == null) {
            if (DEBUG) {
                Log.d(TAG, "getDeviceIconCompat() device : " + device.getName()
                        + ", drawable is null");
            }
            // Use default Bluetooth device icon to handle getIcon() is null case.
            drawable = mContext.getDrawable(com.android.internal.R.drawable.ic_bt_headphones_a2dp);
        }
        return BluetoothUtils.createIconWithDrawable(drawable);
    }

    IconCompat getNotificationIcon() {
        if (TextUtils.isEmpty(mPackageName)) {
            return null;
        }
        for (NotificationEntry entry
                : mNotificationEntryManager.getActiveNotificationsForCurrentUser()) {
            final Notification notification = entry.getSbn().getNotification();
            if (notification.isMediaNotification()
                    && TextUtils.equals(entry.getSbn().getPackageName(), mPackageName)) {
                final Icon icon = notification.getLargeIcon();
                if (icon == null) {
                    break;
                }
                return IconCompat.createFromIcon(icon);
            }
        }
        return null;
    }

    private void buildMediaDevices(List<MediaDevice> devices) {
        // For the first time building list, to make sure the top device is the connected device.
        if (mMediaDevices.isEmpty()) {
            final MediaDevice connectedMediaDevice = getCurrentConnectedMediaDevice();
            if (connectedMediaDevice == null) {
                if (DEBUG) {
                    Log.d(TAG, "No connected media device.");
                }
                mMediaDevices.addAll(devices);
                return;
            }
            for (MediaDevice device : devices) {
                if (TextUtils.equals(device.getId(), connectedMediaDevice.getId())) {
                    mMediaDevices.add(0, device);
                } else {
                    mMediaDevices.add(device);
                }
            }
            return;
        }
        // To keep the same list order
        final Collection<MediaDevice> targetMediaDevices = new ArrayList<>();
        for (MediaDevice originalDevice : mMediaDevices) {
            for (MediaDevice newDevice : devices) {
                if (TextUtils.equals(originalDevice.getId(), newDevice.getId())) {
                    targetMediaDevices.add(newDevice);
                    break;
                }
            }
        }
        if (targetMediaDevices.size() != devices.size()) {
            devices.removeAll(targetMediaDevices);
            targetMediaDevices.addAll(devices);
        }
        mMediaDevices.clear();
        mMediaDevices.addAll(targetMediaDevices);
    }

    List<MediaDevice> getGroupMediaDevices() {
        final List<MediaDevice> selectedDevices = getSelectedMediaDevice();
        final List<MediaDevice> selectableDevices = getSelectableMediaDevice();
        if (mGroupMediaDevices.isEmpty()) {
            mGroupMediaDevices.addAll(selectedDevices);
            mGroupMediaDevices.addAll(selectableDevices);
            return mGroupMediaDevices;
        }
        // To keep the same list order
        final Collection<MediaDevice> sourceDevices = new ArrayList<>();
        final Collection<MediaDevice> targetMediaDevices = new ArrayList<>();
        sourceDevices.addAll(selectedDevices);
        sourceDevices.addAll(selectableDevices);
        for (MediaDevice originalDevice : mGroupMediaDevices) {
            for (MediaDevice newDevice : sourceDevices) {
                if (TextUtils.equals(originalDevice.getId(), newDevice.getId())) {
                    targetMediaDevices.add(newDevice);
                    sourceDevices.remove(newDevice);
                    break;
                }
            }
        }
        // Add new devices at the end of list if necessary
        if (!sourceDevices.isEmpty()) {
            targetMediaDevices.addAll(sourceDevices);
        }
        mGroupMediaDevices.clear();
        mGroupMediaDevices.addAll(targetMediaDevices);

        return mGroupMediaDevices;
    }

    void resetGroupMediaDevices() {
        mGroupMediaDevices.clear();
    }

    void connectDevice(MediaDevice device) {
        mMetricLogger.updateOutputEndPoints(getCurrentConnectedMediaDevice(), device);

        ThreadUtils.postOnBackgroundThread(() -> {
            mLocalMediaManager.connectDevice(device);
        });
    }

    Collection<MediaDevice> getMediaDevices() {
        return mMediaDevices;
    }

    MediaDevice getCurrentConnectedMediaDevice() {
        return mLocalMediaManager.getCurrentConnectedDevice();
    }

    private MediaDevice getMediaDeviceById(String id) {
        return mLocalMediaManager.getMediaDeviceById(new ArrayList<>(mMediaDevices), id);
    }

    boolean addDeviceToPlayMedia(MediaDevice device) {
        return mLocalMediaManager.addDeviceToPlayMedia(device);
    }

    boolean removeDeviceFromPlayMedia(MediaDevice device) {
        return mLocalMediaManager.removeDeviceFromPlayMedia(device);
    }

    List<MediaDevice> getSelectableMediaDevice() {
        return mLocalMediaManager.getSelectableMediaDevice();
    }

    List<MediaDevice> getSelectedMediaDevice() {
        return mLocalMediaManager.getSelectedMediaDevice();
    }

    List<MediaDevice> getDeselectableMediaDevice() {
        return mLocalMediaManager.getDeselectableMediaDevice();
    }

    void adjustSessionVolume(String sessionId, int volume) {
        mLocalMediaManager.adjustSessionVolume(sessionId, volume);
    }

    void adjustSessionVolume(int volume) {
        mLocalMediaManager.adjustSessionVolume(volume);
    }

    int getSessionVolumeMax() {
        return mLocalMediaManager.getSessionVolumeMax();
    }

    int getSessionVolume() {
        return mLocalMediaManager.getSessionVolume();
    }

    CharSequence getSessionName() {
        return mLocalMediaManager.getSessionName();
    }

    void releaseSession() {
        mLocalMediaManager.releaseSession();
    }

    List<RoutingSessionInfo> getActiveRemoteMediaDevices() {
        final List<RoutingSessionInfo> sessionInfos = new ArrayList<>();
        for (RoutingSessionInfo info : mLocalMediaManager.getActiveMediaSession()) {
            if (!info.isSystemSession()) {
                sessionInfos.add(info);
            }
        }
        return sessionInfos;
    }

    void adjustVolume(MediaDevice device, int volume) {
        ThreadUtils.postOnBackgroundThread(() -> {
            device.requestSetVolume(volume);
        });
    }

    String getPackageName() {
        return mPackageName;
    }

    boolean hasAdjustVolumeUserRestriction() {
        if (RestrictedLockUtilsInternal.checkIfRestrictionEnforced(
                mContext, UserManager.DISALLOW_ADJUST_VOLUME, UserHandle.myUserId()) != null) {
            return true;
        }
        final UserManager um = mContext.getSystemService(UserManager.class);
        return um.hasBaseUserRestriction(UserManager.DISALLOW_ADJUST_VOLUME,
                UserHandle.of(UserHandle.myUserId()));
    }

    boolean isTransferring() {
        for (MediaDevice device : mMediaDevices) {
            if (device.getState() == LocalMediaManager.MediaDeviceState.STATE_CONNECTING) {
                return true;
            }
        }
        return false;
    }

    boolean isZeroMode() {
        if (mMediaDevices.size() == 1) {
            final MediaDevice device = mMediaDevices.iterator().next();
            // Add "pair new" only when local output device exists
            final int type = device.getDeviceType();
            if (type == MediaDevice.MediaDeviceType.TYPE_PHONE_DEVICE
                    || type == MediaDevice.MediaDeviceType.TYPE_3POINT5_MM_AUDIO_DEVICE
                    || type == MediaDevice.MediaDeviceType.TYPE_USB_C_AUDIO_DEVICE) {
                return true;
            }
        }
        return false;
    }

    void launchBluetoothPairing() {
        // Dismissing a dialog into its touch surface and starting an activity at the same time
        // looks bad, so let's make sure the dialog just fades out quickly.
        mDialogLaunchAnimator.disableAllCurrentDialogsExitAnimations();

        mCallback.dismissDialog();
        Intent launchIntent =
                new Intent(ACTION_BLUETOOTH_PAIRING_SETTINGS)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        final Intent deepLinkIntent =
                new Intent(Settings.ACTION_SETTINGS_EMBED_DEEP_LINK_ACTIVITY);
        if (deepLinkIntent.resolveActivity(mContext.getPackageManager()) != null) {
            Log.d(TAG, "Device support split mode, launch page with deep link");
            deepLinkIntent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            deepLinkIntent.putExtra(
                    Settings.EXTRA_SETTINGS_EMBEDDED_DEEP_LINK_INTENT_URI,
                    launchIntent.toUri(Intent.URI_INTENT_SCHEME));
            deepLinkIntent.putExtra(
                    Settings.EXTRA_SETTINGS_EMBEDDED_DEEP_LINK_HIGHLIGHT_MENU_KEY,
                    PAGE_CONNECTED_DEVICES_KEY);
            mActivityStarter.startActivity(deepLinkIntent, true);
            return;
        }
        mActivityStarter.startActivity(launchIntent, true);
    }

    void launchMediaOutputGroupDialog(View mediaOutputDialog) {
        // We show the output group dialog from the output dialog.
        MediaOutputController controller = new MediaOutputController(mContext, mPackageName,
                mAboveStatusbar, mMediaSessionManager, mLocalBluetoothManager, mShadeController,
                mActivityStarter, mNotificationEntryManager, mUiEventLogger, mDialogLaunchAnimator,
                mDialogManager);
        MediaOutputGroupDialog dialog = new MediaOutputGroupDialog(mContext, mAboveStatusbar,
                controller, mDialogManager);
        mDialogLaunchAnimator.showFromView(dialog, mediaOutputDialog);
    }

    boolean isActiveRemoteDevice(@NonNull MediaDevice device) {
        final List<String> features = device.getFeatures();
        return (features.contains(MediaRoute2Info.FEATURE_REMOTE_PLAYBACK)
                || features.contains(MediaRoute2Info.FEATURE_REMOTE_AUDIO_PLAYBACK)
                || features.contains(MediaRoute2Info.FEATURE_REMOTE_VIDEO_PLAYBACK)
                || features.contains(MediaRoute2Info.FEATURE_REMOTE_GROUP_PLAYBACK));
    }

    private boolean isPlayBackInfoLocal() {
        return mMediaController.getPlaybackInfo() != null
                && mMediaController.getPlaybackInfo().getPlaybackType()
                        == MediaController.PlaybackInfo.PLAYBACK_TYPE_LOCAL;
    }

    boolean isVolumeControlEnabled(@NonNull MediaDevice device) {
        return isPlayBackInfoLocal()
                || mLocalMediaManager.isMediaSessionAvailableForVolumeControl();
    }

    private final MediaController.Callback mCb = new MediaController.Callback() {
        @Override
        public void onMetadataChanged(MediaMetadata metadata) {
            mCallback.onMediaChanged();
        }

        @Override
        public void onPlaybackStateChanged(PlaybackState playbackState) {
            final int state = playbackState.getState();
            if (state == PlaybackState.STATE_STOPPED || state == PlaybackState.STATE_PAUSED) {
                mCallback.onMediaStoppedOrPaused();
            }
        }
    };

    interface Callback {
        /**
         * Override to handle the media content updating.
         */
        void onMediaChanged();

        /**
         * Override to handle the media state updating.
         */
        void onMediaStoppedOrPaused();

        /**
         * Override to handle the device status or attributes updating.
         */
        void onRouteChanged();

        /**
         * Override to handle the devices set updating.
         */
        void onDeviceListChanged();

        /**
         * Override to dismiss dialog.
         */
        void dismissDialog();
    }
}
