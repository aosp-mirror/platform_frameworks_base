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

import android.backup.AbsoluteFileBackupHelper;
import android.backup.BackupDataInput;
import android.backup.BackupDataInputStream;
import android.backup.BackupDataOutput;
import android.backup.BackupHelper;
import android.backup.BackupHelperAgent;
import android.content.Context;
import android.os.ParcelFileDescriptor;
import android.os.ServiceManager;
import android.os.SystemService;
import android.util.Log;

import java.io.File;
import java.io.IOException;

/**
 * Backup agent for various system-managed data
 */
public class SystemBackupAgent extends BackupHelperAgent {
    private static final String TAG = "SystemBackupAgent";

    private static final String WALLPAPER_IMAGE = "/data/data/com.android.settings/files/wallpaper";
    private static final String WALLPAPER_INFO = "/data/system/wallpaper_info.xml";

    @Override
    public void onCreate() {
        addHelper("wallpaper", new AbsoluteFileBackupHelper(SystemBackupAgent.this,
                new String[] { WALLPAPER_IMAGE, WALLPAPER_INFO }));
    }

    @Override
    public void onRestore(BackupDataInput data, int appVersionCode, ParcelFileDescriptor newState)
            throws IOException {
        boolean success = false;
        try {
            super.onRestore(data, appVersionCode, newState);

            WallpaperService wallpaper = (WallpaperService)ServiceManager.getService(
                    Context.WALLPAPER_SERVICE);
            wallpaper.settingsRestored();
        } catch (IOException ex) {
            // If there was a failure, delete everything for the wallpaper, this is too aggresive,
            // but this is hopefully a rare failure.
            Log.d(TAG, "restore failed", ex);
            (new File(WALLPAPER_IMAGE)).delete();
            (new File(WALLPAPER_INFO)).delete();
        }
    }
}
