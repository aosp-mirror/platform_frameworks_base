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

import android.app.ActivityThread;
import android.app.WallpaperManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.os.AsyncTask;
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
            if (DEBUG) Slog.d(TAG, "Set customized default_wallpaper.");
            Bitmap blank = Bitmap.createBitmap(1, 1, Bitmap.Config.ALPHA_8);
            // set a blank wallpaper to force a redraw of default_wallpaper
            wallpaperManager.setBitmap(blank);
            wallpaperManager.setResource(com.android.internal.R.drawable.default_wallpaper);
        } catch (Exception e) {
            Slog.w(TAG, "Failed to customize system wallpaper." + e);
        }
    }
}
