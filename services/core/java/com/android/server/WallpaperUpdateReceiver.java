/*
 * Copyright (C) 2018 The Android Open Source Project
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

import android.annotation.RequiresPermission;
import android.app.ActivityThread;
import android.app.WallpaperInfo;
import android.app.WallpaperManager;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.os.AsyncTask;
import android.os.ParcelFileDescriptor;
import android.util.Slog;

/**
 * Receiver responsible for updating the wallpaper when the device
 * configuration has changed.
 *
 * @hide
 */
public class WallpaperUpdateReceiver extends BroadcastReceiver {

    private static final String TAG = "WallpaperUpdateReceiver";
    private static final boolean DEBUG = false;

    @Override
    public void onReceive(final Context context, final Intent intent) {
        if (DEBUG) Slog.d(TAG, "onReceive: " + intent);

        if (intent != null && Intent.ACTION_DEVICE_CUSTOMIZATION_READY.equals(intent.getAction())) {
            AsyncTask.execute(this::updateWallpaper);
        }
    }

    private void updateWallpaper() {
        try {
            ActivityThread currentActivityThread = ActivityThread.currentActivityThread();
            Context uiContext = currentActivityThread.getSystemUiContext();
            WallpaperManager wallpaperManager = WallpaperManager.getInstance(uiContext);
            if (isUserSetWallpaper(wallpaperManager, uiContext)) {
                Slog.i(TAG, "User has set wallpaper, skip to resetting");
                return;
            }
            if (DEBUG) Slog.d(TAG, "Set customized default_wallpaper.");
            // Check if it is not a live wallpaper set
            if (wallpaperManager.getWallpaperInfo() == null) {
                wallpaperManager.clearWallpaper();
            }
        } catch (Exception e) {
            Slog.w(TAG, "Failed to customize system wallpaper." + e);
        }
    }

    /**
     * A function to validate if users have set customized (live)wallpaper
     * <p>
     * return true if users have customized their wallpaper
     **/
    @RequiresPermission(android.Manifest.permission.READ_WALLPAPER_INTERNAL)
    private boolean isUserSetWallpaper(WallpaperManager wm, Context context) {
        WallpaperInfo info = wm.getWallpaperInfo();
        if (info == null) {
            //Image Wallpaper
            ParcelFileDescriptor sysWallpaper =
                    wm.getWallpaperFile(WallpaperManager.FLAG_SYSTEM);
            ParcelFileDescriptor lockWallpaper =
                    wm.getWallpaperFile(WallpaperManager.FLAG_LOCK);
            if (sysWallpaper != null || lockWallpaper != null) {
                return true;
            }
        } else {
            //live wallpaper
            ComponentName currCN = info.getComponent();
            ComponentName defaultCN = WallpaperManager.getCmfDefaultWallpaperComponent(context);
            if (!currCN.equals(defaultCN)) {
                return true;
            }
        }
        return false;
    }
}
