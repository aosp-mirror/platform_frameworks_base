/*
 * Copyright (C) 2009 The Android Open Source Project
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

package com.android.server.backup;

import android.app.IWallpaperManager;
import android.app.backup.BackupAgentHelper;
import android.app.backup.BackupDataInput;
import android.app.backup.BackupHelper;
import android.app.backup.BackupManager;
import android.app.backup.FullBackup;
import android.app.backup.FullBackupDataOutput;
import android.app.backup.WallpaperBackupHelper;
import android.content.Context;
import android.os.Environment;
import android.os.ParcelFileDescriptor;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.os.UserHandle;
import android.util.Slog;

import com.google.android.collect.Sets;

import java.io.File;
import java.io.IOException;
import java.util.Set;

/**
 * Backup agent for various system-managed data.  Wallpapers are now handled by a
 * separate package, but we still process restores from legacy datasets here.
 */
public class SystemBackupAgent extends BackupAgentHelper {
    private static final String TAG = "SystemBackupAgent";

    // Names of the helper tags within the dataset.  Changing one of these names will
    // break the ability to restore from datasets that predate the change.
    private static final String WALLPAPER_HELPER = "wallpaper";
    private static final String SYNC_SETTINGS_HELPER = "account_sync_settings";
    private static final String PREFERRED_HELPER = "preferred_activities";
    private static final String NOTIFICATION_HELPER = "notifications";
    private static final String PERMISSION_HELPER = "permissions";
    private static final String USAGE_STATS_HELPER = "usage_stats";
    private static final String SHORTCUT_MANAGER_HELPER = "shortcut_manager";
    private static final String ACCOUNT_MANAGER_HELPER = "account_manager";
    private static final String SLICES_HELPER = "slices";
    private static final String PEOPLE_HELPER = "people";

    // These paths must match what the WallpaperManagerService uses.  The leaf *_FILENAME
    // are also used in the full-backup file format, so must not change unless steps are
    // taken to support the legacy backed-up datasets.
    private static final String WALLPAPER_IMAGE_FILENAME = "wallpaper";
    private static final String WALLPAPER_INFO_FILENAME = "wallpaper_info.xml";

    // TODO: Will need to change if backing up non-primary user's wallpaper
    // TODO: http://b/22388012
    private static final String WALLPAPER_IMAGE_DIR =
            Environment.getUserSystemDirectory(UserHandle.USER_SYSTEM).getAbsolutePath();
    public static final String WALLPAPER_IMAGE =
            new File(Environment.getUserSystemDirectory(UserHandle.USER_SYSTEM),
                    "wallpaper").getAbsolutePath();

    // TODO: Will need to change if backing up non-primary user's wallpaper
    // TODO: http://b/22388012
    private static final String WALLPAPER_INFO_DIR =
            Environment.getUserSystemDirectory(UserHandle.USER_SYSTEM).getAbsolutePath();
    public static final String WALLPAPER_INFO =
            new File(Environment.getUserSystemDirectory(UserHandle.USER_SYSTEM),
                    "wallpaper_info.xml").getAbsolutePath();
    // Use old keys to keep legacy data compatibility and avoid writing two wallpapers
    private static final String WALLPAPER_IMAGE_KEY = WallpaperBackupHelper.WALLPAPER_IMAGE_KEY;

    private static final Set<String> sEligibleForMultiUser = Sets.newArraySet(
            PERMISSION_HELPER, NOTIFICATION_HELPER, SYNC_SETTINGS_HELPER);

    private int mUserId = UserHandle.USER_SYSTEM;

    @Override
    public void onCreate(UserHandle user, @BackupManager.OperationType int operationType) {
        super.onCreate(user, operationType);

        mUserId = user.getIdentifier();

        addHelper(SYNC_SETTINGS_HELPER, new AccountSyncSettingsBackupHelper(this, mUserId));
        addHelper(PREFERRED_HELPER, new PreferredActivityBackupHelper(mUserId));
        addHelper(NOTIFICATION_HELPER, new NotificationBackupHelper(mUserId));
        addHelper(PERMISSION_HELPER, new PermissionBackupHelper(mUserId));
        addHelper(USAGE_STATS_HELPER, new UsageStatsBackupHelper(this));
        addHelper(SHORTCUT_MANAGER_HELPER, new ShortcutBackupHelper());
        addHelper(ACCOUNT_MANAGER_HELPER, new AccountManagerBackupHelper());
        addHelper(SLICES_HELPER, new SliceBackupHelper(this));
        addHelper(PEOPLE_HELPER, new PeopleBackupHelper(mUserId));
    }

    @Override
    public void onFullBackup(FullBackupDataOutput data) throws IOException {
        // At present we don't back up anything
    }

    @Override
    public void onRestore(BackupDataInput data, int appVersionCode, ParcelFileDescriptor newState)
            throws IOException {
        // Slot in a restore helper for the older wallpaper backup schema to support restore
        // from devices still generating data in that format.
        //TODO(b/147732386): Add multi-display support for wallpaper backup.
        addHelper(WALLPAPER_HELPER, new WallpaperBackupHelper(this,
                new String[] { WALLPAPER_IMAGE_KEY}));

        // On restore, we also support a long-ago wallpaper data schema "system_files"
        addHelper("system_files", new WallpaperBackupHelper(this,
                new String[] { WALLPAPER_IMAGE_KEY} ));

        super.onRestore(data, appVersionCode, newState);
    }

    @Override
    public void addHelper(String keyPrefix, BackupHelper helper) {
        if (mUserId != UserHandle.USER_SYSTEM && !sEligibleForMultiUser.contains(keyPrefix)) {
            return;
        }

        super.addHelper(keyPrefix, helper);
    }

    /**
     * Support for 'adb restore' of legacy archives
     */
    @Override
    public void onRestoreFile(ParcelFileDescriptor data, long size,
            int type, String domain, String path, long mode, long mtime)
            throws IOException {
        Slog.i(TAG, "Restoring file domain=" + domain + " path=" + path);

        // Bits to indicate postprocessing we may need to perform
        boolean restoredWallpaper = false;

        File outFile = null;
        // Various domain+files we understand a priori
        if (domain.equals(FullBackup.ROOT_TREE_TOKEN)) {
            if (path.equals(WALLPAPER_INFO_FILENAME)) {
                outFile = new File(WALLPAPER_INFO);
                restoredWallpaper = true;
            } else if (path.equals(WALLPAPER_IMAGE_FILENAME)) {
                outFile = new File(WALLPAPER_IMAGE);
                restoredWallpaper = true;
            }
        }

        try {
            if (outFile == null) {
                Slog.w(TAG, "Skipping unrecognized system file: [ " + domain + " : " + path + " ]");
            }
            FullBackup.restoreFile(data, size, type, mode, mtime, outFile);

            if (restoredWallpaper) {
                IWallpaperManager wallpaper =
                        (IWallpaperManager)ServiceManager.getService(
                                Context.WALLPAPER_SERVICE);
                if (wallpaper != null) {
                    try {
                        wallpaper.settingsRestored();
                    } catch (RemoteException re) {
                        Slog.e(TAG, "Couldn't restore settings\n" + re);
                    }
                }
            }
        } catch (IOException e) {
            if (restoredWallpaper) {
                // Make sure we wind up in a good state
                (new File(WALLPAPER_IMAGE)).delete();
                (new File(WALLPAPER_INFO)).delete();
            }
        }
    }
}
