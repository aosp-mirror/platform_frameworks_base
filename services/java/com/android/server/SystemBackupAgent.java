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

package com.android.server;

import android.app.backup.AbsoluteFileBackupHelper;
import android.app.backup.BackupDataInput;
import android.app.backup.BackupDataInputStream;
import android.app.backup.BackupDataOutput;
import android.app.backup.BackupHelper;
import android.app.backup.BackupAgentHelper;
import android.content.Context;
import android.os.ParcelFileDescriptor;
import android.os.ServiceManager;
import android.os.SystemService;
import android.util.Slog;

import java.io.File;
import java.io.IOException;

/**
 * Backup agent for various system-managed data, currently just the system wallpaper
 */
public class SystemBackupAgent extends BackupAgentHelper {
    private static final String TAG = "SystemBackupAgent";

    // These paths must match what the WallpaperManagerService uses
    private static final String WALLPAPER_IMAGE = "/data/data/com.android.settings/files/wallpaper";
    private static final String WALLPAPER_INFO = "/data/system/wallpaper_info.xml";

    @Override
    public void onBackup(ParcelFileDescriptor oldState, BackupDataOutput data,
            ParcelFileDescriptor newState) throws IOException {
        // We only back up the data under the current "wallpaper" schema with metadata
        WallpaperManagerService wallpaper = (WallpaperManagerService)ServiceManager.getService(
                Context.WALLPAPER_SERVICE);
        String[] files = new String[] { WALLPAPER_IMAGE, WALLPAPER_INFO };
        if (wallpaper != null && wallpaper.mName != null && wallpaper.mName.length() > 0) {
            // When the wallpaper has a name, back up the info by itself.
            // TODO: Don't rely on the innards of the service object like this!
            // TODO: Send a delete for any stored wallpaper image in this case?
            files = new String[] { WALLPAPER_INFO };
        }
        addHelper("wallpaper", new AbsoluteFileBackupHelper(SystemBackupAgent.this, files));
        super.onBackup(oldState, data, newState);
    }

    @Override
    public void onRestore(BackupDataInput data, int appVersionCode, ParcelFileDescriptor newState)
            throws IOException {
        // On restore, we also support a previous data schema "system_files"
        addHelper("wallpaper", new AbsoluteFileBackupHelper(SystemBackupAgent.this,
                new String[] { WALLPAPER_IMAGE, WALLPAPER_INFO }));
        addHelper("system_files", new AbsoluteFileBackupHelper(SystemBackupAgent.this,
                new String[] { WALLPAPER_IMAGE }));

        boolean success = false;
        try {
            super.onRestore(data, appVersionCode, newState);

            WallpaperManagerService wallpaper = (WallpaperManagerService)ServiceManager.getService(
                    Context.WALLPAPER_SERVICE);
            wallpaper.settingsRestored();
        } catch (IOException ex) {
            // If there was a failure, delete everything for the wallpaper, this is too aggresive,
            // but this is hopefully a rare failure.
            Slog.d(TAG, "restore failed", ex);
            (new File(WALLPAPER_IMAGE)).delete();
            (new File(WALLPAPER_INFO)).delete();
        }
    }
}
