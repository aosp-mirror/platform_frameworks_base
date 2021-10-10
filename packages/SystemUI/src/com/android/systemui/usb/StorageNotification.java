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

import android.annotation.NonNull;
import android.app.Notification;
import android.app.Notification.Action;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.content.pm.PackageManager.MoveCallback;
import android.os.Bundle;
import android.os.Handler;
import android.os.StrictMode;
import android.os.UserHandle;
import android.os.storage.DiskInfo;
import android.os.storage.StorageEventListener;
import android.os.storage.StorageManager;
import android.os.storage.VolumeInfo;
import android.os.storage.VolumeRecord;
import android.provider.Settings;
import android.text.TextUtils;
import android.text.format.DateUtils;
import android.util.Log;
import android.util.SparseArray;

import com.android.internal.R;
import com.android.internal.messages.nano.SystemMessageProto.SystemMessage;
import com.android.systemui.SystemUI;
import com.android.systemui.util.NotificationChannels;

import java.util.List;

public class StorageNotification extends SystemUI {
    private static final String TAG = "StorageNotification";

    private static final String ACTION_SNOOZE_VOLUME = "com.android.systemui.action.SNOOZE_VOLUME";
    private static final String ACTION_FINISH_WIZARD = "com.android.systemui.action.FINISH_WIZARD";

    // TODO: delay some notifications to avoid bumpy fast operations

    private NotificationManager mNotificationManager;
    private StorageManager mStorageManager;

    public StorageNotification(Context context) {
        super(context);
    }

    private static class MoveInfo {
        public int moveId;
        public Bundle extras;
        public String packageName;
        public String label;
        public String volumeUuid;
    }

    private final SparseArray<MoveInfo> mMoves = new SparseArray<>();

    private final StorageEventListener mListener = new StorageEventListener() {
        @Override
        public void onVolumeStateChanged(VolumeInfo vol, int oldState, int newState) {
            onVolumeStateChangedInternal(vol);
        }

        @Override
        public void onVolumeRecordChanged(VolumeRecord rec) {
            // Avoid kicking notifications when getting early metadata before
            // mounted. If already mounted, we're being kicked because of a
            // nickname or init'ed change.
            final VolumeInfo vol = mStorageManager.findVolumeByUuid(rec.getFsUuid());
            if (vol != null && vol.isMountedReadable()) {
                onVolumeStateChangedInternal(vol);
            }
        }

        @Override
        public void onVolumeForgotten(String fsUuid) {
            // Stop annoying the user
            mNotificationManager.cancelAsUser(fsUuid, SystemMessage.NOTE_STORAGE_PRIVATE,
                    UserHandle.ALL);
        }

        @Override
        public void onDiskScanned(DiskInfo disk, int volumeCount) {
            onDiskScannedInternal(disk, volumeCount);
        }

        @Override
        public void onDiskDestroyed(DiskInfo disk) {
            onDiskDestroyedInternal(disk);
        }
    };

    private final BroadcastReceiver mSnoozeReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            // TODO: kick this onto background thread
            final String fsUuid = intent.getStringExtra(VolumeRecord.EXTRA_FS_UUID);
            mStorageManager.setVolumeSnoozed(fsUuid, true);
        }
    };

    private final BroadcastReceiver mFinishReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            // When finishing the adoption wizard, clean up any notifications
            // for moving primary storage
            mNotificationManager.cancelAsUser(null, SystemMessage.NOTE_STORAGE_MOVE,
                    UserHandle.ALL);
        }
    };

    private final MoveCallback mMoveCallback = new MoveCallback() {
        @Override
        public void onCreated(int moveId, Bundle extras) {
            final MoveInfo move = new MoveInfo();
            move.moveId = moveId;
            move.extras = extras;
            if (extras != null) {
                move.packageName = extras.getString(Intent.EXTRA_PACKAGE_NAME);
                move.label = extras.getString(Intent.EXTRA_TITLE);
                move.volumeUuid = extras.getString(VolumeRecord.EXTRA_FS_UUID);
            }
            mMoves.put(moveId, move);
        }

        @Override
        public void onStatusChanged(int moveId, int status, long estMillis) {
            final MoveInfo move = mMoves.get(moveId);
            if (move == null) {
                Log.w(TAG, "Ignoring unknown move " + moveId);
                return;
            }

            if (PackageManager.isMoveStatusFinished(status)) {
                onMoveFinished(move, status);
            } else {
                onMoveProgress(move, status, estMillis);
            }
        }
    };

    @Override
    public void start() {
        mNotificationManager = mContext.getSystemService(NotificationManager.class);

        mStorageManager = mContext.getSystemService(StorageManager.class);
        mStorageManager.registerListener(mListener);

        mContext.registerReceiver(mSnoozeReceiver, new IntentFilter(ACTION_SNOOZE_VOLUME),
                android.Manifest.permission.MOUNT_UNMOUNT_FILESYSTEMS, null);
        mContext.registerReceiver(mFinishReceiver, new IntentFilter(ACTION_FINISH_WIZARD),
                android.Manifest.permission.MOUNT_UNMOUNT_FILESYSTEMS, null);

        // Kick current state into place
        final List<DiskInfo> disks = mStorageManager.getDisks();
        for (DiskInfo disk : disks) {
            onDiskScannedInternal(disk, disk.volumeCount);
        }

        final List<VolumeInfo> vols = mStorageManager.getVolumes();
        for (VolumeInfo vol : vols) {
            onVolumeStateChangedInternal(vol);
        }

        mContext.getPackageManager().registerMoveCallback(mMoveCallback, new Handler());

        updateMissingPrivateVolumes();
    }

    private void updateMissingPrivateVolumes() {
        if (isTv() || isAutomotive()) {
            // On TV, TvSettings displays a modal full-screen activity in this case.
            // Not applicable for automotive.
            return;
        }

        final List<VolumeRecord> recs = mStorageManager.getVolumeRecords();
        for (VolumeRecord rec : recs) {
            if (rec.getType() != VolumeInfo.TYPE_PRIVATE) continue;

            final String fsUuid = rec.getFsUuid();
            final VolumeInfo info = mStorageManager.findVolumeByUuid(fsUuid);
            if ((info != null && info.isMountedWritable()) || rec.isSnoozed()) {
                // Yay, private volume is here, or user snoozed
                mNotificationManager.cancelAsUser(fsUuid, SystemMessage.NOTE_STORAGE_PRIVATE,
                        UserHandle.ALL);

            } else {
                // Boo, annoy the user to reinsert the private volume
                final CharSequence title = mContext.getString(R.string.ext_media_missing_title,
                        rec.getNickname());
                final CharSequence text = mContext.getString(R.string.ext_media_missing_message);

                Notification.Builder builder =
                        new Notification.Builder(mContext, NotificationChannels.STORAGE)
                                .setSmallIcon(R.drawable.ic_sd_card_48dp)
                                .setColor(mContext.getColor(
                                        R.color.system_notification_accent_color))
                                .setContentTitle(title)
                                .setContentText(text)
                                .setContentIntent(buildForgetPendingIntent(rec))
                                .setStyle(new Notification.BigTextStyle().bigText(text))
                                .setVisibility(Notification.VISIBILITY_PUBLIC)
                                .setLocalOnly(true)
                                .setCategory(Notification.CATEGORY_SYSTEM)
                                .setDeleteIntent(buildSnoozeIntent(fsUuid))
                                .extend(new Notification.TvExtender());
                SystemUI.overrideNotificationAppName(mContext, builder, false);

                mNotificationManager.notifyAsUser(fsUuid, SystemMessage.NOTE_STORAGE_PRIVATE,
                        builder.build(), UserHandle.ALL);
            }
        }
    }

    private void onDiskScannedInternal(DiskInfo disk, int volumeCount) {
        if (volumeCount == 0 && disk.size > 0) {
            // No supported volumes found, give user option to format
            final CharSequence title = mContext.getString(
                    R.string.ext_media_unsupported_notification_title, disk.getDescription());
            final CharSequence text = mContext.getString(
                    R.string.ext_media_unsupported_notification_message, disk.getDescription());

            Notification.Builder builder =
                    new Notification.Builder(mContext, NotificationChannels.STORAGE)
                            .setSmallIcon(getSmallIcon(disk, VolumeInfo.STATE_UNMOUNTABLE))
                            .setColor(mContext.getColor(R.color.system_notification_accent_color))
                            .setContentTitle(title)
                            .setContentText(text)
                            .setContentIntent(buildInitPendingIntent(disk))
                            .setStyle(new Notification.BigTextStyle().bigText(text))
                            .setVisibility(Notification.VISIBILITY_PUBLIC)
                            .setLocalOnly(true)
                            .setCategory(Notification.CATEGORY_ERROR)
                            .extend(new Notification.TvExtender());
            SystemUI.overrideNotificationAppName(mContext, builder, false);

            mNotificationManager.notifyAsUser(disk.getId(), SystemMessage.NOTE_STORAGE_DISK,
                    builder.build(), UserHandle.ALL);

        } else {
            // Yay, we have volumes!
            mNotificationManager.cancelAsUser(disk.getId(), SystemMessage.NOTE_STORAGE_DISK,
                    UserHandle.ALL);
        }
    }

    /**
     * Remove all notifications for a disk when it goes away.
     *
     * @param disk The disk that went away.
     */
    private void onDiskDestroyedInternal(@NonNull DiskInfo disk) {
        mNotificationManager.cancelAsUser(disk.getId(), SystemMessage.NOTE_STORAGE_DISK,
                UserHandle.ALL);
    }

    private void onVolumeStateChangedInternal(VolumeInfo vol) {
        switch (vol.getType()) {
            case VolumeInfo.TYPE_PRIVATE:
                onPrivateVolumeStateChangedInternal(vol);
                break;
            case VolumeInfo.TYPE_PUBLIC:
                onPublicVolumeStateChangedInternal(vol);
                break;
        }
    }

    private void onPrivateVolumeStateChangedInternal(VolumeInfo vol) {
        Log.d(TAG, "Notifying about private volume: " + vol.toString());

        updateMissingPrivateVolumes();
    }

    private void onPublicVolumeStateChangedInternal(VolumeInfo vol) {
        Log.d(TAG, "Notifying about public volume: " + vol.toString());

        // Volume state change event may come from removed user, in this case, mountedUserId will
        // equals to UserHandle.USER_NULL (-10000) which will do nothing when call cancelAsUser(),
        // but cause crash when call notifyAsUser(). Here we return directly for USER_NULL, and
        // leave all notifications belong to removed user to NotificationManagerService, the latter
        // will remove all notifications of the removed user when handles user stopped broadcast.
        if (vol.getMountUserId() == UserHandle.USER_NULL) {
            Log.d(TAG, "Ignore public volume state change event of removed user");
            return;
        }

        final Notification notif;
        switch (vol.getState()) {
            case VolumeInfo.STATE_UNMOUNTED:
                notif = onVolumeUnmounted(vol);
                break;
            case VolumeInfo.STATE_CHECKING:
                notif = onVolumeChecking(vol);
                break;
            case VolumeInfo.STATE_MOUNTED:
            case VolumeInfo.STATE_MOUNTED_READ_ONLY:
                notif = onVolumeMounted(vol);
                break;
            case VolumeInfo.STATE_FORMATTING:
                notif = onVolumeFormatting(vol);
                break;
            case VolumeInfo.STATE_EJECTING:
                notif = onVolumeEjecting(vol);
                break;
            case VolumeInfo.STATE_UNMOUNTABLE:
                notif = onVolumeUnmountable(vol);
                break;
            case VolumeInfo.STATE_REMOVED:
                notif = onVolumeRemoved(vol);
                break;
            case VolumeInfo.STATE_BAD_REMOVAL:
                notif = onVolumeBadRemoval(vol);
                break;
            default:
                notif = null;
                break;
        }

        if (notif != null) {
            mNotificationManager.notifyAsUser(vol.getId(), SystemMessage.NOTE_STORAGE_PUBLIC,
                    notif, UserHandle.of(vol.getMountUserId()));
        } else {
            mNotificationManager.cancelAsUser(vol.getId(), SystemMessage.NOTE_STORAGE_PUBLIC,
                    UserHandle.of(vol.getMountUserId()));
        }
    }

    private Notification onVolumeUnmounted(VolumeInfo vol) {
        // Ignored
        return null;
    }

    private Notification onVolumeChecking(VolumeInfo vol) {
        final DiskInfo disk = vol.getDisk();
        final CharSequence title = mContext.getString(
                R.string.ext_media_checking_notification_title, disk.getDescription());
        final CharSequence text = mContext.getString(
                R.string.ext_media_checking_notification_message, disk.getDescription());

        return buildNotificationBuilder(vol, title, text)
                .setCategory(Notification.CATEGORY_PROGRESS)
                .setOngoing(true)
                .build();
    }

    private Notification onVolumeMounted(VolumeInfo vol) {
        final VolumeRecord rec = mStorageManager.findRecordByUuid(vol.getFsUuid());
        final DiskInfo disk = vol.getDisk();

        // Don't annoy when user dismissed in past.  (But make sure the disk is adoptable; we
        // used to allow snoozing non-adoptable disks too.)
        if (rec.isSnoozed() && disk.isAdoptable()) {
            return null;
        }

        if (disk.isAdoptable() && !rec.isInited()) {
            final CharSequence title = disk.getDescription();
            final CharSequence text = mContext.getString(
                    R.string.ext_media_new_notification_message, disk.getDescription());

            final PendingIntent initIntent = buildInitPendingIntent(vol);
            final PendingIntent unmountIntent = buildUnmountPendingIntent(vol);

            if (isAutomotive()) {
                return buildNotificationBuilder(vol, title, text)
                        .setContentIntent(unmountIntent)
                        .setDeleteIntent(buildSnoozeIntent(vol.getFsUuid()))
                        .build();
            } else {
                return buildNotificationBuilder(vol, title, text)
                        .addAction(new Action(R.drawable.ic_settings_24dp,
                                mContext.getString(R.string.ext_media_init_action), initIntent))
                        .addAction(new Action(R.drawable.ic_eject_24dp,
                                mContext.getString(R.string.ext_media_unmount_action),
                                unmountIntent))
                        .setContentIntent(initIntent)
                        .setDeleteIntent(buildSnoozeIntent(vol.getFsUuid()))
                        .build();
            }
        } else {
            final CharSequence title = disk.getDescription();
            final CharSequence text = mContext.getString(
                    R.string.ext_media_ready_notification_message, disk.getDescription());

            final PendingIntent browseIntent = buildBrowsePendingIntent(vol);
            final Notification.Builder builder = buildNotificationBuilder(vol, title, text)
                    .addAction(new Action(R.drawable.ic_folder_24dp,
                            mContext.getString(R.string.ext_media_browse_action),
                            browseIntent))
                    .addAction(new Action(R.drawable.ic_eject_24dp,
                            mContext.getString(R.string.ext_media_unmount_action),
                            buildUnmountPendingIntent(vol)))
                    .setContentIntent(browseIntent)
                    .setCategory(Notification.CATEGORY_SYSTEM);
            // Non-adoptable disks can't be snoozed.
            if (disk.isAdoptable()) {
                builder.setDeleteIntent(buildSnoozeIntent(vol.getFsUuid()));
            }

            return builder.build();
        }
    }

    private Notification onVolumeFormatting(VolumeInfo vol) {
        // Ignored
        return null;
    }

    private Notification onVolumeEjecting(VolumeInfo vol) {
        final DiskInfo disk = vol.getDisk();
        final CharSequence title = mContext.getString(
                R.string.ext_media_unmounting_notification_title, disk.getDescription());
        final CharSequence text = mContext.getString(
                R.string.ext_media_unmounting_notification_message, disk.getDescription());

        return buildNotificationBuilder(vol, title, text)
                .setCategory(Notification.CATEGORY_PROGRESS)
                .setOngoing(true)
                .build();
    }

    private Notification onVolumeUnmountable(VolumeInfo vol) {
        final DiskInfo disk = vol.getDisk();
        final CharSequence title = mContext.getString(
                R.string.ext_media_unmountable_notification_title, disk.getDescription());
        final CharSequence text = mContext.getString(
                R.string.ext_media_unmountable_notification_message, disk.getDescription());
        PendingIntent action;
        if (isAutomotive()) {
            action = buildUnmountPendingIntent(vol);
        } else {
            action = buildInitPendingIntent(vol);
        }

        return buildNotificationBuilder(vol, title, text)
                .setContentIntent(action)
                .setCategory(Notification.CATEGORY_ERROR)
                .build();
    }

    private Notification onVolumeRemoved(VolumeInfo vol) {
        if (!vol.isPrimary()) {
            // Ignore non-primary media
            return null;
        }

        final DiskInfo disk = vol.getDisk();
        final CharSequence title = mContext.getString(
                R.string.ext_media_nomedia_notification_title, disk.getDescription());
        final CharSequence text = mContext.getString(
                R.string.ext_media_nomedia_notification_message, disk.getDescription());

        return buildNotificationBuilder(vol, title, text)
                .setCategory(Notification.CATEGORY_ERROR)
                .build();
    }

    private Notification onVolumeBadRemoval(VolumeInfo vol) {
        if (!vol.isPrimary()) {
            // Ignore non-primary media
            return null;
        }

        final DiskInfo disk = vol.getDisk();
        final CharSequence title = mContext.getString(
                R.string.ext_media_badremoval_notification_title, disk.getDescription());
        final CharSequence text = mContext.getString(
                R.string.ext_media_badremoval_notification_message, disk.getDescription());

        return buildNotificationBuilder(vol, title, text)
                .setCategory(Notification.CATEGORY_ERROR)
                .build();
    }

    private void onMoveProgress(MoveInfo move, int status, long estMillis) {
        final CharSequence title;
        if (!TextUtils.isEmpty(move.label)) {
            title = mContext.getString(R.string.ext_media_move_specific_title, move.label);
        } else {
            title = mContext.getString(R.string.ext_media_move_title);
        }

        final CharSequence text;
        if (estMillis < 0) {
            text = null;
        } else {
            text = DateUtils.formatDuration(estMillis);
        }

        final PendingIntent intent;
        if (move.packageName != null) {
            intent = buildWizardMovePendingIntent(move);
        } else {
            intent = buildWizardMigratePendingIntent(move);
        }

        Notification.Builder builder =
                new Notification.Builder(mContext, NotificationChannels.STORAGE)
                        .setSmallIcon(R.drawable.ic_sd_card_48dp)
                        .setColor(mContext.getColor(R.color.system_notification_accent_color))
                        .setContentTitle(title)
                        .setContentText(text)
                        .setContentIntent(intent)
                        .setStyle(new Notification.BigTextStyle().bigText(text))
                        .setVisibility(Notification.VISIBILITY_PUBLIC)
                        .setLocalOnly(true)
                        .setCategory(Notification.CATEGORY_PROGRESS)
                        .setProgress(100, status, false)
                        .setOngoing(true);
        SystemUI.overrideNotificationAppName(mContext, builder, false);

        mNotificationManager.notifyAsUser(move.packageName, SystemMessage.NOTE_STORAGE_MOVE,
                builder.build(), UserHandle.ALL);
    }

    private void onMoveFinished(MoveInfo move, int status) {
        if (move.packageName != null) {
            // We currently ignore finished app moves; just clear the last
            // published progress
            mNotificationManager.cancelAsUser(move.packageName, SystemMessage.NOTE_STORAGE_MOVE,
                    UserHandle.ALL);
            return;
        }

        final VolumeInfo privateVol = mContext.getPackageManager().getPrimaryStorageCurrentVolume();
        final String descrip = mStorageManager.getBestVolumeDescription(privateVol);

        final CharSequence title;
        final CharSequence text;
        if (status == PackageManager.MOVE_SUCCEEDED) {
            title = mContext.getString(R.string.ext_media_move_success_title);
            text = mContext.getString(R.string.ext_media_move_success_message, descrip);
        } else {
            title = mContext.getString(R.string.ext_media_move_failure_title);
            text = mContext.getString(R.string.ext_media_move_failure_message);
        }

        // Jump back into the wizard flow if we moved to a real disk
        final PendingIntent intent;
        if (privateVol != null && privateVol.getDisk() != null) {
            intent = buildWizardReadyPendingIntent(privateVol.getDisk());
        } else if (privateVol != null) {
            intent = buildVolumeSettingsPendingIntent(privateVol);
        } else {
            intent = null;
        }

        Notification.Builder builder =
                new Notification.Builder(mContext, NotificationChannels.STORAGE)
                        .setSmallIcon(R.drawable.ic_sd_card_48dp)
                        .setColor(mContext.getColor(R.color.system_notification_accent_color))
                        .setContentTitle(title)
                        .setContentText(text)
                        .setContentIntent(intent)
                        .setStyle(new Notification.BigTextStyle().bigText(text))
                        .setVisibility(Notification.VISIBILITY_PUBLIC)
                        .setLocalOnly(true)
                        .setCategory(Notification.CATEGORY_SYSTEM)
                        .setAutoCancel(true);
        SystemUI.overrideNotificationAppName(mContext, builder, false);

        mNotificationManager.notifyAsUser(move.packageName, SystemMessage.NOTE_STORAGE_MOVE,
                builder.build(), UserHandle.ALL);
    }

    private int getSmallIcon(DiskInfo disk, int state) {
        if (disk.isSd()) {
            switch (state) {
                case VolumeInfo.STATE_CHECKING:
                case VolumeInfo.STATE_EJECTING:
                    return R.drawable.ic_sd_card_48dp;
                default:
                    return R.drawable.ic_sd_card_48dp;
            }
        } else if (disk.isUsb()) {
            return R.drawable.ic_usb_48dp;
        } else {
            return R.drawable.ic_sd_card_48dp;
        }
    }

    private Notification.Builder buildNotificationBuilder(VolumeInfo vol, CharSequence title,
            CharSequence text) {
        Notification.Builder builder =
                new Notification.Builder(mContext, NotificationChannels.STORAGE)
                        .setSmallIcon(getSmallIcon(vol.getDisk(), vol.getState()))
                        .setColor(mContext.getColor(R.color.system_notification_accent_color))
                        .setContentTitle(title)
                        .setContentText(text)
                        .setStyle(new Notification.BigTextStyle().bigText(text))
                        .setVisibility(Notification.VISIBILITY_PUBLIC)
                        .setLocalOnly(true)
                        .extend(new Notification.TvExtender());
        overrideNotificationAppName(mContext, builder, false);
        return builder;
    }

    private PendingIntent buildInitPendingIntent(DiskInfo disk) {
        final Intent intent = new Intent();
        if (isTv()) {
            intent.setPackage("com.android.tv.settings");
            intent.setAction("com.android.tv.settings.action.NEW_STORAGE");
        } else if (isAutomotive()) {
            // TODO(b/151671685): add intent to handle unsupported usb
            return null;
        } else {
            intent.setClassName("com.android.settings",
                    "com.android.settings.deviceinfo.StorageWizardInit");
        }
        intent.putExtra(DiskInfo.EXTRA_DISK_ID, disk.getId());

        final int requestKey = disk.getId().hashCode();
        return PendingIntent.getActivityAsUser(mContext, requestKey, intent,
                PendingIntent.FLAG_CANCEL_CURRENT | PendingIntent.FLAG_IMMUTABLE,
                null, UserHandle.CURRENT);
    }

    private PendingIntent buildInitPendingIntent(VolumeInfo vol) {
        final Intent intent = new Intent();
        if (isTv()) {
            intent.setPackage("com.android.tv.settings");
            intent.setAction("com.android.tv.settings.action.NEW_STORAGE");
        } else if (isAutomotive()) {
            // TODO(b/151671685): add intent to handle unmountable usb
            return null;
        } else {
            intent.setClassName("com.android.settings",
                    "com.android.settings.deviceinfo.StorageWizardInit");
        }
        intent.putExtra(VolumeInfo.EXTRA_VOLUME_ID, vol.getId());

        final int requestKey = vol.getId().hashCode();
        return PendingIntent.getActivityAsUser(mContext, requestKey, intent,
                PendingIntent.FLAG_CANCEL_CURRENT | PendingIntent.FLAG_IMMUTABLE,
                null, UserHandle.CURRENT);
    }

    private PendingIntent buildUnmountPendingIntent(VolumeInfo vol) {
        final Intent intent = new Intent();
        if (isTv()) {
            intent.setPackage("com.android.tv.settings");
            intent.setAction("com.android.tv.settings.action.UNMOUNT_STORAGE");
            intent.putExtra(VolumeInfo.EXTRA_VOLUME_ID, vol.getId());

            final int requestKey = vol.getId().hashCode();
            return PendingIntent.getActivityAsUser(mContext, requestKey, intent,
                    PendingIntent.FLAG_CANCEL_CURRENT | PendingIntent.FLAG_IMMUTABLE,
                    null, UserHandle.CURRENT);
        } else if (isAutomotive()) {
            intent.setClassName("com.android.car.settings",
                    "com.android.car.settings.storage.StorageUnmountReceiver");
            intent.putExtra(VolumeInfo.EXTRA_VOLUME_ID, vol.getId());

            final int requestKey = vol.getId().hashCode();
            return PendingIntent.getBroadcastAsUser(mContext, requestKey, intent,
                    PendingIntent.FLAG_CANCEL_CURRENT | PendingIntent.FLAG_IMMUTABLE,
                    UserHandle.CURRENT);
        } else {
            intent.setClassName("com.android.settings",
                    "com.android.settings.deviceinfo.StorageUnmountReceiver");
            intent.putExtra(VolumeInfo.EXTRA_VOLUME_ID, vol.getId());

            final int requestKey = vol.getId().hashCode();
            return PendingIntent.getBroadcastAsUser(mContext, requestKey, intent,
                    PendingIntent.FLAG_CANCEL_CURRENT | PendingIntent.FLAG_IMMUTABLE,
                    UserHandle.CURRENT);
        }
    }

    private PendingIntent buildBrowsePendingIntent(VolumeInfo vol) {
        final StrictMode.VmPolicy oldPolicy = StrictMode.allowVmViolations();
        try {
            final Intent intent = vol.buildBrowseIntentForUser(vol.getMountUserId());

            final int requestKey = vol.getId().hashCode();
            return PendingIntent.getActivityAsUser(mContext, requestKey, intent,
                    PendingIntent.FLAG_CANCEL_CURRENT | PendingIntent.FLAG_IMMUTABLE,
                    null, UserHandle.CURRENT);
        } finally {
            StrictMode.setVmPolicy(oldPolicy);
        }
    }

    private PendingIntent buildVolumeSettingsPendingIntent(VolumeInfo vol) {
        final Intent intent = new Intent();
        if (isTv()) {
            intent.setPackage("com.android.tv.settings");
            intent.setAction(Settings.ACTION_INTERNAL_STORAGE_SETTINGS);
        } else if (isAutomotive()) {
            // TODO(b/151671685): add volume settings intent for automotive
            return null;
        } else {
            switch (vol.getType()) {
                case VolumeInfo.TYPE_PRIVATE:
                    intent.setClassName("com.android.settings",
                            "com.android.settings.Settings$PrivateVolumeSettingsActivity");
                    break;
                case VolumeInfo.TYPE_PUBLIC:
                    intent.setClassName("com.android.settings",
                            "com.android.settings.Settings$PublicVolumeSettingsActivity");
                    break;
                default:
                    return null;
            }
        }
        intent.putExtra(VolumeInfo.EXTRA_VOLUME_ID, vol.getId());

        final int requestKey = vol.getId().hashCode();
        return PendingIntent.getActivityAsUser(mContext, requestKey, intent,
                PendingIntent.FLAG_CANCEL_CURRENT | PendingIntent.FLAG_IMMUTABLE,
                null, UserHandle.CURRENT);
    }

    private PendingIntent buildSnoozeIntent(String fsUuid) {
        final Intent intent = new Intent(ACTION_SNOOZE_VOLUME);
        intent.putExtra(VolumeRecord.EXTRA_FS_UUID, fsUuid);

        final int requestKey = fsUuid.hashCode();
        return PendingIntent.getBroadcastAsUser(mContext, requestKey, intent,
                PendingIntent.FLAG_CANCEL_CURRENT | PendingIntent.FLAG_IMMUTABLE,
                UserHandle.CURRENT);
    }

    private PendingIntent buildForgetPendingIntent(VolumeRecord rec) {
        // Not used on TV and Automotive
        final Intent intent = new Intent();
        intent.setClassName("com.android.settings",
                "com.android.settings.Settings$PrivateVolumeForgetActivity");
        intent.putExtra(VolumeRecord.EXTRA_FS_UUID, rec.getFsUuid());

        final int requestKey = rec.getFsUuid().hashCode();
        return PendingIntent.getActivityAsUser(mContext, requestKey, intent,
                PendingIntent.FLAG_CANCEL_CURRENT | PendingIntent.FLAG_IMMUTABLE,
                null, UserHandle.CURRENT);
    }

    private PendingIntent buildWizardMigratePendingIntent(MoveInfo move) {
        final Intent intent = new Intent();
        if (isTv()) {
            intent.setPackage("com.android.tv.settings");
            intent.setAction("com.android.tv.settings.action.MIGRATE_STORAGE");
        } else if (isAutomotive()) {
            // TODO(b/151671685): add storage migrate intent for automotive
            return null;
        } else {
            intent.setClassName("com.android.settings",
                    "com.android.settings.deviceinfo.StorageWizardMigrateProgress");
        }
        intent.putExtra(PackageManager.EXTRA_MOVE_ID, move.moveId);

        final VolumeInfo vol = mStorageManager.findVolumeByQualifiedUuid(move.volumeUuid);
        if (vol != null) {
            intent.putExtra(VolumeInfo.EXTRA_VOLUME_ID, vol.getId());
        }
        return PendingIntent.getActivityAsUser(mContext, move.moveId, intent,
                PendingIntent.FLAG_CANCEL_CURRENT | PendingIntent.FLAG_IMMUTABLE,
                null, UserHandle.CURRENT);
    }

    private PendingIntent buildWizardMovePendingIntent(MoveInfo move) {
        final Intent intent = new Intent();
        if (isTv()) {
            intent.setPackage("com.android.tv.settings");
            intent.setAction("com.android.tv.settings.action.MOVE_APP");
        } else if (isAutomotive()) {
            // TODO(b/151671685): add storage move intent for automotive
            return null;
        } else {
            intent.setClassName("com.android.settings",
                    "com.android.settings.deviceinfo.StorageWizardMoveProgress");
        }
        intent.putExtra(PackageManager.EXTRA_MOVE_ID, move.moveId);

        return PendingIntent.getActivityAsUser(mContext, move.moveId, intent,
                PendingIntent.FLAG_CANCEL_CURRENT | PendingIntent.FLAG_IMMUTABLE,
                null, UserHandle.CURRENT);
    }

    private PendingIntent buildWizardReadyPendingIntent(DiskInfo disk) {
        final Intent intent = new Intent();
        if (isTv()) {
            intent.setPackage("com.android.tv.settings");
            intent.setAction(Settings.ACTION_INTERNAL_STORAGE_SETTINGS);
        } else if (isAutomotive()) {
            // TODO(b/151671685): add storage ready intent for automotive
            return null;
        } else {
            intent.setClassName("com.android.settings",
                    "com.android.settings.deviceinfo.StorageWizardReady");
        }
        intent.putExtra(DiskInfo.EXTRA_DISK_ID, disk.getId());

        final int requestKey = disk.getId().hashCode();
        return PendingIntent.getActivityAsUser(mContext, requestKey, intent,
                PendingIntent.FLAG_CANCEL_CURRENT | PendingIntent.FLAG_IMMUTABLE,
                null, UserHandle.CURRENT);
    }

    private boolean isAutomotive() {
        PackageManager packageManager = mContext.getPackageManager();
        return packageManager.hasSystemFeature(PackageManager.FEATURE_AUTOMOTIVE);
    }

    private boolean isTv() {
        PackageManager packageManager = mContext.getPackageManager();
        return packageManager.hasSystemFeature(PackageManager.FEATURE_LEANBACK);
    }
}
