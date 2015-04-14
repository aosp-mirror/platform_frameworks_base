/*
 * Copyright (C) 2015 The Android Open Source Project
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

package com.android.systemui.usb;

import android.app.Notification;
import android.app.Notification.Action;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Intent;
import android.os.UserHandle;
import android.os.storage.DiskInfo;
import android.os.storage.StorageEventListener;
import android.os.storage.StorageManager;
import android.os.storage.VolumeInfo;
import android.util.Log;

import com.android.internal.R;
import com.android.systemui.SystemUI;

import java.util.List;

public class StorageNotification extends SystemUI {
    private static final String TAG = "StorageNotification";

    private static final int NOTIF_ID = 0x53544f52; // STOR

    // TODO: delay some notifications to avoid bumpy fast operations
    // TODO: annoy user when private media is missing

    private NotificationManager mNotificationManager;
    private StorageManager mStorageManager;

    private final StorageEventListener mListener = new StorageEventListener() {
        @Override
        public void onVolumeStateChanged(VolumeInfo vol, int oldState, int newState) {
            onVolumeStateChangedInternal(vol, oldState, newState);
        }
    };

    @Override
    public void start() {
        mNotificationManager = mContext.getSystemService(NotificationManager.class);

        mStorageManager = mContext.getSystemService(StorageManager.class);
        mStorageManager.registerListener(mListener);

        // Kick current state into place
        final List<VolumeInfo> vols = mStorageManager.getVolumes();
        for (VolumeInfo vol : vols) {
            onVolumeStateChangedInternal(vol, vol.state, vol.state);
        }
    }

    public void onVolumeStateChangedInternal(VolumeInfo vol, int oldState, int newState) {
        // We only care about public volumes
        if (vol.type != VolumeInfo.TYPE_PUBLIC) {
            return;
        }

        Log.d(TAG, vol.toString());

        // New state means we tear down any old notifications
        mNotificationManager.cancelAsUser(vol.id, NOTIF_ID, UserHandle.ALL);

        switch (newState) {
            case VolumeInfo.STATE_UNMOUNTED:
                onVolumeUnmounted(vol);
                break;
            case VolumeInfo.STATE_MOUNTING:
                onVolumeMounting(vol);
                break;
            case VolumeInfo.STATE_MOUNTED:
                onVolumeMounted(vol);
                break;
            case VolumeInfo.STATE_FORMATTING:
                onVolumeFormatting(vol);
                break;
            case VolumeInfo.STATE_UNMOUNTING:
                onVolumeUnmounting(vol);
                break;
            case VolumeInfo.STATE_UNMOUNTABLE:
                onVolumeUnmountable(vol);
                break;
            case VolumeInfo.STATE_REMOVED:
                onVolumeRemoved(vol);
                break;
        }
    }

    private void onVolumeUnmounted(VolumeInfo vol) {
        // Ignored
    }

    private void onVolumeMounting(VolumeInfo vol) {
        final DiskInfo disk = mStorageManager.findDiskById(vol.diskId);
        final CharSequence title = mContext.getString(
                R.string.ext_media_checking_notification_title, disk.getDescription());
        final CharSequence text = mContext.getString(
                R.string.ext_media_checking_notification_message, disk.getDescription());

        final Notification notif = buildNotificationBuilder(title, text)
                .setSmallIcon(R.drawable.stat_notify_sdcard_prepare)
                .setCategory(Notification.CATEGORY_PROGRESS)
                .setPriority(Notification.PRIORITY_LOW)
                .setOngoing(true)
                .build();

        mNotificationManager.notifyAsUser(vol.id, NOTIF_ID, notif, UserHandle.ALL);
    }

    private void onVolumeMounted(VolumeInfo vol) {
        final DiskInfo disk = mStorageManager.findDiskById(vol.diskId);
        final Notification notif;
        if (disk.isAdoptable()) {
            final CharSequence title = disk.getDescription();
            final CharSequence text = mContext.getString(
                    R.string.ext_media_new_notification_message, disk.getDescription());

            notif = buildNotificationBuilder(title, text)
                    .setSmallIcon(R.drawable.stat_notify_sdcard)
                    .addAction(new Action(0, mContext.getString(R.string.ext_media_init_action),
                            buildInitPendingIntent(vol)))
                    .addAction(new Action(0, mContext.getString(R.string.ext_media_unmount_action),
                            buildUnmountPendingIntent(vol)))
                    .setCategory(Notification.CATEGORY_SYSTEM)
                    .build();

        } else {
            final CharSequence title = disk.getDescription();
            final CharSequence text = mContext.getString(
                    R.string.ext_media_ready_notification_message, disk.getDescription());

            notif = buildNotificationBuilder(title, text)
                    .setSmallIcon(R.drawable.stat_notify_sdcard)
                    .addAction(new Action(0, mContext.getString(R.string.ext_media_browse_action),
                            buildBrowsePendingIntent(vol)))
                    .addAction(new Action(0, mContext.getString(R.string.ext_media_unmount_action),
                            buildUnmountPendingIntent(vol)))
                    .setCategory(Notification.CATEGORY_SYSTEM)
                    .setPriority(Notification.PRIORITY_LOW)
                    .build();
        }

        mNotificationManager.notifyAsUser(vol.id, NOTIF_ID, notif, UserHandle.ALL);
    }

    private void onVolumeFormatting(VolumeInfo vol) {
        // Ignored
    }

    private void onVolumeUnmounting(VolumeInfo vol) {
        final DiskInfo disk = mStorageManager.findDiskById(vol.diskId);
        final CharSequence title = mContext.getString(
                R.string.ext_media_unmounting_notification_title, disk.getDescription());
        final CharSequence text = mContext.getString(
                R.string.ext_media_unmounting_notification_message, disk.getDescription());

        final Notification notif = buildNotificationBuilder(title, text)
                .setSmallIcon(R.drawable.stat_notify_sdcard_prepare)
                .setCategory(Notification.CATEGORY_PROGRESS)
                .setPriority(Notification.PRIORITY_LOW)
                .setOngoing(true)
                .build();

        mNotificationManager.notifyAsUser(vol.id, NOTIF_ID, notif, UserHandle.ALL);
    }

    private void onVolumeUnmountable(VolumeInfo vol) {
        final DiskInfo disk = mStorageManager.findDiskById(vol.diskId);
        final CharSequence title = mContext.getString(
                R.string.ext_media_unmountable_notification_title, disk.getDescription());
        final CharSequence text = mContext.getString(
                R.string.ext_media_unmountable_notification_message, disk.getDescription());

        final Notification notif = buildNotificationBuilder(title, text)
                .setSmallIcon(R.drawable.stat_notify_sdcard)
                .setContentIntent(buildDetailsPendingIntent(vol))
                .setCategory(Notification.CATEGORY_ERROR)
                .build();

        mNotificationManager.notifyAsUser(vol.id, NOTIF_ID, notif, UserHandle.ALL);
    }

    private void onVolumeRemoved(VolumeInfo vol) {
        if (!vol.isPrimary()) {
            // Ignore non-primary media
            return;
        }

        final DiskInfo disk = mStorageManager.findDiskById(vol.diskId);
        final CharSequence title = mContext.getString(
                R.string.ext_media_nomedia_notification_title, disk.getDescription());
        final CharSequence text = mContext.getString(
                R.string.ext_media_nomedia_notification_message, disk.getDescription());

        final Notification notif = buildNotificationBuilder(title, text)
                .setSmallIcon(R.drawable.stat_notify_sdcard)
                .setCategory(Notification.CATEGORY_ERROR)
                .build();

        mNotificationManager.notifyAsUser(vol.id, NOTIF_ID, notif, UserHandle.ALL);
    }

    private Notification.Builder buildNotificationBuilder(CharSequence title, CharSequence text) {
        return new Notification.Builder(mContext)
                .setColor(mContext.getColor(R.color.system_notification_accent_color))
                .setContentTitle(title)
                .setContentText(text)
                .setStyle(new Notification.BigTextStyle().bigText(text))
                .setVisibility(Notification.VISIBILITY_PUBLIC)
                .setLocalOnly(true);
    }

    private PendingIntent buildInitPendingIntent(VolumeInfo vol) {
        final Intent intent = new Intent();
        intent.setClassName("com.android.settings",
                "com.android.settings.deviceinfo.StorageWizardInit");
        intent.putExtra(VolumeInfo.EXTRA_VOLUME_ID, vol.id);
        return PendingIntent.getActivityAsUser(mContext, 0, intent, 0, null, UserHandle.CURRENT);
    }

    private PendingIntent buildUnmountPendingIntent(VolumeInfo vol) {
        final Intent intent = new Intent();
        intent.setClassName("com.android.settings",
                "com.android.settings.deviceinfo.StorageUnmountReceiver");
        intent.putExtra(VolumeInfo.EXTRA_VOLUME_ID, vol.id);
        return PendingIntent.getBroadcastAsUser(mContext, 0, intent, 0, UserHandle.CURRENT);
    }

    private PendingIntent buildBrowsePendingIntent(VolumeInfo vol) {
        final Intent intent = vol.buildBrowseIntent();
        return PendingIntent.getActivityAsUser(mContext, 0, intent, 0, null, UserHandle.CURRENT);
    }

    private PendingIntent buildDetailsPendingIntent(VolumeInfo vol) {
        final Intent intent = new Intent();
        intent.setClassName("com.android.settings",
                "com.android.settings.Settings$StorageVolumeSettingsActivity");
        intent.putExtra(VolumeInfo.EXTRA_VOLUME_ID, vol.id);
        return PendingIntent.getActivityAsUser(mContext, 0, intent, 0, null, UserHandle.CURRENT);
    }
}
