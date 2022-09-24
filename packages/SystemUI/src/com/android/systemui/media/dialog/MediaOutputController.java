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

import static android.provider.Settings.ACTION_BLUETOOTH_SETTINGS;

import android.annotation.CallbackExecutor;
import android.app.AlertDialog;
import android.app.KeyguardManager;
import android.app.Notification;
import android.app.WallpaperColors;
import android.bluetooth.BluetoothLeBroadcast;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.Icon;
import android.media.AudioManager;
import android.media.INearbyMediaDevicesUpdateCallback;
import android.media.MediaMetadata;
import android.media.MediaRoute2Info;
import android.media.NearbyDevice;
import android.media.RoutingSessionInfo;
import android.media.session.MediaController;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.os.IBinder;
import android.os.PowerExemptionManager;
import android.os.RemoteException;
import android.os.UserHandle;
import android.os.UserManager;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.Log;
import android.view.View;
import android.view.WindowManager;

import androidx.annotation.NonNull;
import androidx.annotation.VisibleForTesting;
import androidx.core.graphics.drawable.IconCompat;

import com.android.settingslib.RestrictedLockUtilsInternal;
import com.android.settingslib.Utils;
import com.android.settingslib.bluetooth.BluetoothUtils;
import com.android.settingslib.bluetooth.LocalBluetoothLeBroadcast;
import com.android.settingslib.bluetooth.LocalBluetoothLeBroadcastMetadata;
import com.android.settingslib.bluetooth.LocalBluetoothManager;
import com.android.settingslib.media.InfoMediaManager;
import com.android.settingslib.media.LocalMediaManager;
import com.android.settingslib.media.MediaDevice;
import com.android.settingslib.utils.ThreadUtils;
import com.android.systemui.R;
import com.android.systemui.animation.ActivityLaunchAnimator;
import com.android.systemui.animation.DialogLaunchAnimator;
import com.android.systemui.broadcast.BroadcastSender;
import com.android.systemui.media.nearby.NearbyMediaDevicesManager;
import com.android.systemui.monet.ColorScheme;
import com.android.systemui.plugins.ActivityStarter;
import com.android.systemui.statusbar.notification.collection.NotificationEntry;
import com.android.systemui.statusbar.notification.collection.notifcollection.CommonNotifCollection;
import com.android.systemui.statusbar.phone.SystemUIDialog;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executor;

import javax.inject.Inject;

/**
 * Controller for media output dialog
 */
public class MediaOutputController implements LocalMediaManager.DeviceCallback,
        INearbyMediaDevicesUpdateCallback {

    private static final String TAG = "MediaOutputController";
    private static final boolean DEBUG = Log.isLoggable(TAG, Log.DEBUG);
    private static final String PAGE_CONNECTED_DEVICES_KEY =
            "top_level_connected_devices";
    private static final long ALLOWLIST_DURATION_MS = 20000;
    private static final String ALLOWLIST_REASON = "mediaoutput:remote_transfer";

    private final String mPackageName;
    private final Context mContext;
    private final MediaSessionManager mMediaSessionManager;
    private final LocalBluetoothManager mLocalBluetoothManager;
    private final ActivityStarter mActivityStarter;
    private final DialogLaunchAnimator mDialogLaunchAnimator;
    private final List<MediaDevice> mGroupMediaDevices = new CopyOnWriteArrayList<>();
    private final CommonNotifCollection mNotifCollection;
    private final Object mMediaDevicesLock = new Object();
    @VisibleForTesting
    final List<MediaDevice> mMediaDevices = new CopyOnWriteArrayList<>();
    final List<MediaDevice> mCachedMediaDevices = new CopyOnWriteArrayList<>();
    private final AudioManager mAudioManager;
    private final PowerExemptionManager mPowerExemptionManager;
    private final KeyguardManager mKeyGuardManager;
    private final NearbyMediaDevicesManager mNearbyMediaDevicesManager;
    private final Map<String, Integer> mNearbyDeviceInfoMap = new ConcurrentHashMap<>();

    @VisibleForTesting
    boolean mIsRefreshing = false;
    @VisibleForTesting
    boolean mNeedRefresh = false;
    private MediaController mMediaController;
    @VisibleForTesting
    Callback mCallback;
    @VisibleForTesting
    LocalMediaManager mLocalMediaManager;
    @VisibleForTesting
    private MediaOutputMetricLogger mMetricLogger;
    private int mCurrentState;

    private int mColorItemContent;
    private int mColorSeekbarProgress;
    private int mColorButtonBackground;
    private int mColorItemBackground;
    private int mColorConnectedItemBackground;
    private int mColorPositiveButtonText;
    private int mColorDialogBackground;
    private float mInactiveRadius;
    private float mActiveRadius;

    public enum BroadcastNotifyDialog {
        ACTION_FIRST_LAUNCH,
        ACTION_BROADCAST_INFO_ICON
    }

    @Inject
    public MediaOutputController(@NonNull Context context, String packageName,
            MediaSessionManager mediaSessionManager, LocalBluetoothManager
            lbm, ActivityStarter starter,
            CommonNotifCollection notifCollection,
            DialogLaunchAnimator dialogLaunchAnimator,
            Optional<NearbyMediaDevicesManager> nearbyMediaDevicesManagerOptional,
            AudioManager audioManager,
            PowerExemptionManager powerExemptionManager,
            KeyguardManager keyGuardManager) {
        mContext = context;
        mPackageName = packageName;
        mMediaSessionManager = mediaSessionManager;
        mLocalBluetoothManager = lbm;
        mActivityStarter = starter;
        mNotifCollection = notifCollection;
        mAudioManager = audioManager;
        mPowerExemptionManager = powerExemptionManager;
        mKeyGuardManager = keyGuardManager;
        InfoMediaManager imm = new InfoMediaManager(mContext, packageName, null, lbm);
        mLocalMediaManager = new LocalMediaManager(mContext, lbm, imm, packageName);
        mMetricLogger = new MediaOutputMetricLogger(mContext, mPackageName);
        mDialogLaunchAnimator = dialogLaunchAnimator;
        mNearbyMediaDevicesManager = nearbyMediaDevicesManagerOptional.orElse(null);
        mColorItemContent = Utils.getColorStateListDefaultColor(mContext,
                R.color.media_dialog_item_main_content);
        mColorSeekbarProgress = Utils.getColorStateListDefaultColor(mContext,
                R.color.media_dialog_seekbar_progress);
        mColorButtonBackground = Utils.getColorStateListDefaultColor(mContext,
                R.color.media_dialog_button_background);
        mColorItemBackground = Utils.getColorStateListDefaultColor(mContext,
                R.color.media_dialog_item_background);
        mColorConnectedItemBackground = Utils.getColorStateListDefaultColor(mContext,
                R.color.media_dialog_connected_item_background);
        mColorPositiveButtonText = Utils.getColorStateListDefaultColor(mContext,
                R.color.media_dialog_solid_button_text);
        mInactiveRadius = mContext.getResources().getDimension(
                R.dimen.media_output_dialog_background_radius);
        mActiveRadius = mContext.getResources().getDimension(
                R.dimen.media_output_dialog_active_background_radius);
        mColorDialogBackground = Utils.getColorStateListDefaultColor(mContext,
                R.color.media_dialog_background);
    }

    void start(@NonNull Callback cb) {
        synchronized (mMediaDevicesLock) {
            mCachedMediaDevices.clear();
            mMediaDevices.clear();
        }
        mNearbyDeviceInfoMap.clear();
        if (mNearbyMediaDevicesManager != null) {
            mNearbyMediaDevicesManager.registerNearbyDevicesCallback(this);
        }
        if (!TextUtils.isEmpty(mPackageName)) {
            for (MediaController controller : mMediaSessionManager.getActiveSessions(null)) {
                if (TextUtils.equals(controller.getPackageName(), mPackageName)) {
                    mMediaController = controller;
                    mMediaController.unregisterCallback(mCb);
                    if (mMediaController.getPlaybackState() != null) {
                        mCurrentState = mMediaController.getPlaybackState().getState();
                    }
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
        mCallback = cb;
        mLocalMediaManager.registerCallback(this);
        mLocalMediaManager.startScan();
    }

    boolean shouldShowLaunchSection() {
        // TODO(b/231398073): Implements this when available.
        return false;
    }

    boolean isRefreshing() {
        return mIsRefreshing;
    }

    void setRefreshing(boolean refreshing) {
        mIsRefreshing = refreshing;
    }

    void stop() {
        if (mMediaController != null) {
            mMediaController.unregisterCallback(mCb);
        }
        mLocalMediaManager.unregisterCallback(this);
        mLocalMediaManager.stopScan();
        synchronized (mMediaDevicesLock) {
            mCachedMediaDevices.clear();
            mMediaDevices.clear();
        }
        if (mNearbyMediaDevicesManager != null) {
            mNearbyMediaDevicesManager.unregisterNearbyDevicesCallback(this);
        }
        mNearbyDeviceInfoMap.clear();
    }

    @Override
    public void onDeviceListUpdate(List<MediaDevice> devices) {
        if (mMediaDevices.isEmpty() || !mIsRefreshing) {
            buildMediaDevices(devices);
            mCallback.onDeviceListChanged();
        } else {
            synchronized (mMediaDevicesLock) {
                mNeedRefresh = true;
                mCachedMediaDevices.clear();
                mCachedMediaDevices.addAll(devices);
            }
        }
    }

    @Override
    public void onSelectedDeviceStateChanged(MediaDevice device,
            @LocalMediaManager.MediaDeviceState int state) {
        mCallback.onRouteChanged();
        mMetricLogger.logOutputSuccess(device.toString(), new ArrayList<>(mMediaDevices));
    }

    @Override
    public void onDeviceAttributesChanged() {
        mCallback.onRouteChanged();
    }

    @Override
    public void onRequestFailed(int reason) {
        mCallback.onRouteChanged();
        mMetricLogger.logOutputFailure(new ArrayList<>(mMediaDevices), reason);
    }

    /**
     * Checks if there's any muting expected device exist
     */
    public boolean hasMutingExpectedDevice() {
        return mAudioManager.getMutingExpectedDevice() != null;
    }

    /**
     * Cancels mute await connection action in follow up request
     */
    public void cancelMuteAwaitConnection() {
        if (mAudioManager.getMutingExpectedDevice() == null) {
            return;
        }
        try {
            synchronized (mMediaDevicesLock) {
                mMediaDevices.removeIf(MediaDevice::isMutingExpectedDevice);
            }
            mAudioManager.cancelMuteAwaitConnection(mAudioManager.getMutingExpectedDevice());
        } catch (Exception e) {
            Log.d(TAG, "Unable to cancel mute await connection");
        }
    }

    Drawable getAppSourceIconFromPackage() {
        if (mPackageName.isEmpty()) {
            return null;
        }
        try {
            Log.d(TAG, "try to get app icon");
            return mContext.getPackageManager()
                    .getApplicationIcon(mPackageName);
        } catch (PackageManager.NameNotFoundException e) {
            Log.d(TAG, "icon not found");
            return null;
        }
    }

    String getAppSourceName() {
        if (mPackageName.isEmpty()) {
            return null;
        }
        final PackageManager packageManager = mContext.getPackageManager();
        ApplicationInfo applicationInfo;
        try {
            applicationInfo = packageManager.getApplicationInfo(mPackageName,
                    PackageManager.ApplicationInfoFlags.of(0));
        } catch (PackageManager.NameNotFoundException e) {
            applicationInfo = null;
        }
        final String applicationName =
                (String) (applicationInfo != null ? packageManager.getApplicationLabel(
                        applicationInfo)
                        : mContext.getString(R.string.media_output_dialog_unknown_launch_app_name));
        return applicationName;
    }

    Intent getAppLaunchIntent() {
        if (mPackageName.isEmpty()) {
            return null;
        }
        return mContext.getPackageManager().getLaunchIntentForPackage(mPackageName);
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
        if (!(drawable instanceof BitmapDrawable)) {
            setColorFilter(drawable, isActiveItem(device));
        }
        return BluetoothUtils.createIconWithDrawable(drawable);
    }

    void setColorFilter(Drawable drawable, boolean isActive) {
        drawable.setColorFilter(new PorterDuffColorFilter(mColorItemContent,
                PorterDuff.Mode.SRC_IN));
    }

    boolean isActiveItem(MediaDevice device) {
        boolean isConnected = mLocalMediaManager.getCurrentConnectedDevice().getId().equals(
                device.getId());
        boolean isSelectedDeviceInGroup = getSelectedMediaDevice().size() > 1
                && getSelectedMediaDevice().contains(device);
        return (!hasAdjustVolumeUserRestriction() && isConnected && !isAnyDeviceTransferring())
                || isSelectedDeviceInGroup;
    }

    IconCompat getNotificationSmallIcon() {
        if (TextUtils.isEmpty(mPackageName)) {
            return null;
        }
        for (NotificationEntry entry : mNotifCollection.getAllNotifs()) {
            final Notification notification = entry.getSbn().getNotification();
            if (notification.isMediaNotification()
                    && TextUtils.equals(entry.getSbn().getPackageName(), mPackageName)) {
                final Icon icon = notification.getSmallIcon();
                if (icon == null) {
                    break;
                }
                return IconCompat.createFromIcon(icon);
            }
        }
        return null;
    }

    IconCompat getNotificationIcon() {
        if (TextUtils.isEmpty(mPackageName)) {
            return null;
        }
        for (NotificationEntry entry : mNotifCollection.getAllNotifs()) {
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

    void setCurrentColorScheme(WallpaperColors wallpaperColors, boolean isDarkTheme) {
        ColorScheme mCurrentColorScheme = new ColorScheme(wallpaperColors,
                isDarkTheme);
        if (isDarkTheme) {
            mColorItemContent = mCurrentColorScheme.getAccent1().get(2); // A1-100
            mColorSeekbarProgress = mCurrentColorScheme.getAccent2().get(7); // A2-600
            mColorButtonBackground = mCurrentColorScheme.getAccent1().get(4); // A1-300
            mColorItemBackground = mCurrentColorScheme.getNeutral2().get(9); // N2-800
            mColorConnectedItemBackground = mCurrentColorScheme.getAccent2().get(9); // A2-800
            mColorPositiveButtonText = mCurrentColorScheme.getAccent2().get(9); // A2-800
            mColorDialogBackground = mCurrentColorScheme.getNeutral1().get(10); // N1-900
        } else {
            mColorItemContent = mCurrentColorScheme.getAccent1().get(9); // A1-800
            mColorSeekbarProgress = mCurrentColorScheme.getAccent1().get(4); // A1-300
            mColorButtonBackground = mCurrentColorScheme.getAccent1().get(7); // A1-600
            mColorItemBackground = mCurrentColorScheme.getAccent2().get(1); // A2-50
            mColorConnectedItemBackground = mCurrentColorScheme.getAccent1().get(2); // A1-100
            mColorPositiveButtonText = mCurrentColorScheme.getNeutral1().get(1); // N1-50
            mColorDialogBackground = mCurrentColorScheme.getBackgroundColor();
        }
    }

    void refreshDataSetIfNeeded() {
        if (mNeedRefresh) {
            buildMediaDevices(mCachedMediaDevices);
            mCallback.onDeviceListChanged();
            mNeedRefresh = false;
        }
    }

    public int getColorConnectedItemBackground() {
        return mColorConnectedItemBackground;
    }

    public int getColorPositiveButtonText() {
        return mColorPositiveButtonText;
    }

    public int getColorDialogBackground() {
        return mColorDialogBackground;
    }

    public int getColorItemContent() {
        return mColorItemContent;
    }

    public int getColorSeekbarProgress() {
        return mColorSeekbarProgress;
    }

    public int getColorButtonBackground() {
        return mColorButtonBackground;
    }

    public int getColorItemBackground() {
        return mColorItemBackground;
    }

    public float getInactiveRadius() {
        return mInactiveRadius;
    }

    public float getActiveRadius() {
        return mActiveRadius;
    }

    private void buildMediaDevices(List<MediaDevice> devices) {
        synchronized (mMediaDevicesLock) {
            attachRangeInfo(devices);
            Collections.sort(devices, Comparator.naturalOrder());
            // For the first time building list, to make sure the top device is the connected
            // device.
            if (mMediaDevices.isEmpty()) {
                boolean needToHandleMutingExpectedDevice =
                        hasMutingExpectedDevice() && !isCurrentConnectedDeviceRemote();
                final MediaDevice connectedMediaDevice =
                        needToHandleMutingExpectedDevice ? null
                                : getCurrentConnectedMediaDevice();
                if (connectedMediaDevice == null) {
                    if (DEBUG) {
                        Log.d(TAG, "No connected media device or muting expected device exist.");
                    }
                    if (needToHandleMutingExpectedDevice) {
                        for (MediaDevice device : devices) {
                            if (device.isMutingExpectedDevice()) {
                                mMediaDevices.add(0, device);
                            } else {
                                mMediaDevices.add(device);
                            }
                        }
                    } else {
                        mMediaDevices.addAll(devices);
                    }
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
            final List<MediaDevice> targetMediaDevices = new ArrayList<>();
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
    }

    private void attachRangeInfo(List<MediaDevice> devices) {
        for (MediaDevice mediaDevice : devices) {
            if (mNearbyDeviceInfoMap.containsKey(mediaDevice.getId())) {
                mediaDevice.setRangeZone(mNearbyDeviceInfoMap.get(mediaDevice.getId()));
            }
        }

    }

    boolean isCurrentConnectedDeviceRemote() {
        MediaDevice currentConnectedMediaDevice = getCurrentConnectedMediaDevice();
        return currentConnectedMediaDevice != null && isActiveRemoteDevice(
                currentConnectedMediaDevice);
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

    boolean addDeviceToPlayMedia(MediaDevice device) {
        mMetricLogger.logInteractionExpansion(device);
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
        mMetricLogger.logInteractionStopCasting();
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
        mMetricLogger.logInteractionAdjustVolume(device);
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

    boolean isAnyDeviceTransferring() {
        synchronized (mMediaDevicesLock) {
            for (MediaDevice device : mMediaDevices) {
                if (device.getState() == LocalMediaManager.MediaDeviceState.STATE_CONNECTING) {
                    return true;
                }
            }
        }
        return false;
    }

    void launchBluetoothPairing(View view) {
        ActivityLaunchAnimator.Controller controller =
                mDialogLaunchAnimator.createActivityLaunchController(view);

        if (controller == null || (mKeyGuardManager != null
                && mKeyGuardManager.isKeyguardLocked())) {
            mCallback.dismissDialog();
        }

        Intent launchIntent =
                new Intent(ACTION_BLUETOOTH_SETTINGS)
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
            mActivityStarter.startActivity(deepLinkIntent, true, controller);
            return;
        }
        mActivityStarter.startActivity(launchIntent, true, controller);
    }

    void launchLeBroadcastNotifyDialog(View mediaOutputDialog, BroadcastSender broadcastSender,
            BroadcastNotifyDialog action, final DialogInterface.OnClickListener listener) {
        final AlertDialog.Builder builder = new AlertDialog.Builder(mContext);
        switch (action) {
            case ACTION_FIRST_LAUNCH:
                builder.setTitle(R.string.media_output_first_broadcast_title);
                builder.setMessage(R.string.media_output_first_notify_broadcast_message);
                builder.setNegativeButton(android.R.string.cancel, null);
                builder.setPositiveButton(R.string.media_output_broadcast, listener);
                break;
            case ACTION_BROADCAST_INFO_ICON:
                builder.setTitle(R.string.media_output_broadcast);
                builder.setMessage(R.string.media_output_broadcasting_message);
                builder.setPositiveButton(android.R.string.ok, null);
                break;
        }

        final AlertDialog dialog = builder.create();
        dialog.getWindow().setType(WindowManager.LayoutParams.TYPE_KEYGUARD_DIALOG);
        SystemUIDialog.setShowForAllUsers(dialog, true);
        SystemUIDialog.registerDismissListener(dialog);
        dialog.show();
    }

    void launchMediaOutputBroadcastDialog(View mediaOutputDialog, BroadcastSender broadcastSender) {
        MediaOutputController controller = new MediaOutputController(mContext, mPackageName,
                mMediaSessionManager, mLocalBluetoothManager, mActivityStarter,
                mNotifCollection, mDialogLaunchAnimator, Optional.of(mNearbyMediaDevicesManager),
                mAudioManager, mPowerExemptionManager, mKeyGuardManager);
        MediaOutputBroadcastDialog dialog = new MediaOutputBroadcastDialog(mContext, true,
                broadcastSender, controller);
        mDialogLaunchAnimator.showFromView(dialog, mediaOutputDialog);
    }

    String getBroadcastName() {
        LocalBluetoothLeBroadcast broadcast =
                mLocalBluetoothManager.getProfileManager().getLeAudioBroadcastProfile();
        if (broadcast == null) {
            Log.d(TAG, "getBroadcastName: LE Audio Broadcast is null");
            return "";
        }
        return broadcast.getProgramInfo();
    }

    void setBroadcastName(String broadcastName) {
        LocalBluetoothLeBroadcast broadcast =
                mLocalBluetoothManager.getProfileManager().getLeAudioBroadcastProfile();
        if (broadcast == null) {
            Log.d(TAG, "setBroadcastName: LE Audio Broadcast is null");
            return;
        }
        broadcast.setProgramInfo(broadcastName);
    }

    String getBroadcastCode() {
        LocalBluetoothLeBroadcast broadcast =
                mLocalBluetoothManager.getProfileManager().getLeAudioBroadcastProfile();
        if (broadcast == null) {
            Log.d(TAG, "getBroadcastCode: LE Audio Broadcast is null");
            return "";
        }
        return new String(broadcast.getBroadcastCode(), StandardCharsets.UTF_8);
    }

    void setBroadcastCode(String broadcastCode) {
        LocalBluetoothLeBroadcast broadcast =
                mLocalBluetoothManager.getProfileManager().getLeAudioBroadcastProfile();
        if (broadcast == null) {
            Log.d(TAG, "setBroadcastCode: LE Audio Broadcast is null");
            return;
        }
        broadcast.setBroadcastCode(broadcastCode.getBytes(StandardCharsets.UTF_8));
    }

    void setTemporaryAllowListExceptionIfNeeded(MediaDevice targetDevice) {
        if (mPowerExemptionManager == null || mPackageName == null) {
            Log.w(TAG, "powerExemptionManager or package name is null");
            return;
        }
        mPowerExemptionManager.addToTemporaryAllowList(mPackageName,
                PowerExemptionManager.REASON_MEDIA_NOTIFICATION_TRANSFER,
                ALLOWLIST_REASON,
                ALLOWLIST_DURATION_MS);
    }

    String getBroadcastMetadata() {
        LocalBluetoothLeBroadcast broadcast =
                mLocalBluetoothManager.getProfileManager().getLeAudioBroadcastProfile();
        if (broadcast == null) {
            Log.d(TAG, "getBroadcastMetadata: LE Audio Broadcast is null");
            return "";
        }
        final LocalBluetoothLeBroadcastMetadata metadata =
                broadcast.getLocalBluetoothLeBroadcastMetaData();
        return metadata != null ? metadata.convertToQrCodeString() : "";
    }

    boolean isActiveRemoteDevice(@NonNull MediaDevice device) {
        final List<String> features = device.getFeatures();
        return (features.contains(MediaRoute2Info.FEATURE_REMOTE_PLAYBACK)
                || features.contains(MediaRoute2Info.FEATURE_REMOTE_AUDIO_PLAYBACK)
                || features.contains(MediaRoute2Info.FEATURE_REMOTE_VIDEO_PLAYBACK)
                || features.contains(MediaRoute2Info.FEATURE_REMOTE_GROUP_PLAYBACK));
    }

    boolean isBluetoothLeDevice(@NonNull MediaDevice device) {
        return device.isBLEDevice();
    }

    boolean isBroadcastSupported() {
        LocalBluetoothLeBroadcast broadcast =
                mLocalBluetoothManager.getProfileManager().getLeAudioBroadcastProfile();
        return broadcast != null ? true : false;
    }

    boolean isBluetoothLeBroadcastEnabled() {
        LocalBluetoothLeBroadcast broadcast =
                mLocalBluetoothManager.getProfileManager().getLeAudioBroadcastProfile();
        if (broadcast == null) {
            return false;
        }
        return broadcast.isEnabled(null);
    }

    boolean startBluetoothLeBroadcast() {
        LocalBluetoothLeBroadcast broadcast =
                mLocalBluetoothManager.getProfileManager().getLeAudioBroadcastProfile();
        if (broadcast == null) {
            Log.d(TAG, "The broadcast profile is null");
            return false;
        }
        broadcast.startBroadcast(getAppSourceName(), /*language*/ null);
        return true;
    }

    boolean stopBluetoothLeBroadcast() {
        LocalBluetoothLeBroadcast broadcast =
                mLocalBluetoothManager.getProfileManager().getLeAudioBroadcastProfile();
        if (broadcast == null) {
            Log.d(TAG, "The broadcast profile is null");
            return false;
        }
        broadcast.stopLatestBroadcast();
        return true;
    }

    boolean updateBluetoothLeBroadcast() {
        LocalBluetoothLeBroadcast broadcast =
                mLocalBluetoothManager.getProfileManager().getLeAudioBroadcastProfile();
        if (broadcast == null) {
            Log.d(TAG, "The broadcast profile is null");
            return false;
        }
        broadcast.updateBroadcast(getAppSourceName(), /*language*/ null);
        return true;
    }

    void registerLeBroadcastServiceCallBack(
            @NonNull @CallbackExecutor Executor executor,
            @NonNull BluetoothLeBroadcast.Callback callback) {
        LocalBluetoothLeBroadcast broadcast =
                mLocalBluetoothManager.getProfileManager().getLeAudioBroadcastProfile();
        if (broadcast == null) {
            Log.d(TAG, "The broadcast profile is null");
            return;
        }
        broadcast.registerServiceCallBack(executor, callback);
    }

    void unregisterLeBroadcastServiceCallBack(
            @NonNull BluetoothLeBroadcast.Callback callback) {
        LocalBluetoothLeBroadcast broadcast =
                mLocalBluetoothManager.getProfileManager().getLeAudioBroadcastProfile();
        if (broadcast == null) {
            Log.d(TAG, "The broadcast profile is null");
            return;
        }
        broadcast.unregisterServiceCallBack(callback);
    }

    private boolean isPlayBackInfoLocal() {
        return mMediaController != null
                && mMediaController.getPlaybackInfo() != null
                && mMediaController.getPlaybackInfo().getPlaybackType()
                == MediaController.PlaybackInfo.PLAYBACK_TYPE_LOCAL;
    }

    boolean isPlaying() {
        if (mMediaController == null) {
            return false;
        }

        PlaybackState state = mMediaController.getPlaybackState();
        if (state == null) {
            return false;
        }

        return (state.getState() == PlaybackState.STATE_PLAYING);
    }

    boolean isVolumeControlEnabled(@NonNull MediaDevice device) {
        return (isPlayBackInfoLocal()
                || device.getDeviceType() != MediaDevice.MediaDeviceType.TYPE_CAST_GROUP_DEVICE)
                && !device.isVolumeFixed();
    }

    @Override
    public void onDevicesUpdated(List<NearbyDevice> nearbyDevices) throws RemoteException {
        mNearbyDeviceInfoMap.clear();
        for (NearbyDevice nearbyDevice : nearbyDevices) {
            mNearbyDeviceInfoMap.put(nearbyDevice.getMediaRoute2Id(), nearbyDevice.getRangeZone());
        }
        mNearbyMediaDevicesManager.unregisterNearbyDevicesCallback(this);
    }

    @Override
    public IBinder asBinder() {
        return null;
    }

    private final MediaController.Callback mCb = new MediaController.Callback() {
        @Override
        public void onMetadataChanged(MediaMetadata metadata) {
            mCallback.onMediaChanged();
        }

        @Override
        public void onPlaybackStateChanged(PlaybackState playbackState) {
            final int newState =
                    playbackState == null ? PlaybackState.STATE_STOPPED : playbackState.getState();
            if (mCurrentState == newState) {
                return;
            }

            if (newState == PlaybackState.STATE_STOPPED) {
                mCallback.onMediaStoppedOrPaused();
            }
            mCurrentState = newState;
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
