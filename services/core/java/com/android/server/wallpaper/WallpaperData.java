/*
 * Copyright (C) 2023 The Android Open Source Project
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

package com.android.server.wallpaper;

import android.app.IWallpaperManagerCallback;
import android.app.WallpaperColors;
import android.content.ComponentName;
import android.graphics.Rect;
import android.os.RemoteCallbackList;
import android.util.ArrayMap;

import java.io.File;

/**
 * The main wallpaper data model, used internally by the {@link WallpaperManagerService}. <br>
 * An instance of this class contains all the information about a wallpaper.
 */
class WallpaperData {

    int userId;

    final File wallpaperFile;   // source image
    final File cropFile;        // eventual destination

    /**
     * True while the client is writing a new wallpaper
     */
    boolean imageWallpaperPending;

    /**
     * Which wallpaper is set. Flag values are from
     * {@link android.app.WallpaperManager.SetWallpaperFlags}.
     */
    int mWhich;

    /**
     * Callback once the set + crop is finished
     */
    IWallpaperManagerCallback setComplete;

    /**
     * Is the OS allowed to back up this wallpaper imagery?
     */
    boolean allowBackup;

    /**
     * Resource name if using a picture from the wallpaper gallery
     */
    String name = "";

    /**
     * The component name of the currently set live wallpaper.
     */
    ComponentName wallpaperComponent;

    /**
     * The component name of the wallpaper that should be set next.
     */
    ComponentName nextWallpaperComponent;

    /**
     * The ID of this wallpaper
     */
    int wallpaperId;

    /**
     * Primary colors histogram
     */
    WallpaperColors primaryColors;

    /**
     * If the wallpaper was set from a foreground app (instead of from a background service).
     */
    public boolean fromForegroundApp;

    WallpaperManagerService.WallpaperConnection connection;
    long lastDiedTime;
    boolean wallpaperUpdating;
    WallpaperManagerService.WallpaperObserver wallpaperObserver;

    /**
     * The dim amount to be applied to the wallpaper.
     */
    float mWallpaperDimAmount = 0.0f;

    /**
     * A map to keep track of the dimming set by different applications. The key is the calling
     * UID and the value is the dim amount.
     */
    ArrayMap<Integer, Float> mUidToDimAmount = new ArrayMap<>();

    /**
     * Whether we need to extract the wallpaper colors again to calculate the dark hints
     * after dimming is applied.
     */
    boolean mIsColorExtractedFromDim;

    /**
     * List of callbacks registered they should each be notified when the wallpaper is changed.
     */
    RemoteCallbackList<IWallpaperManagerCallback> callbacks = new RemoteCallbackList<>();

    /**
     * The crop hint supplied for displaying a subset of the source image
     */
    final Rect cropHint = new Rect(0, 0, 0, 0);

    WallpaperData(int userId, File wallpaperDir, String inputFileName, String cropFileName) {
        this.userId = userId;
        wallpaperFile = new File(wallpaperDir, inputFileName);
        cropFile = new File(wallpaperDir, cropFileName);
    }

    // Called during initialization of a given user's wallpaper bookkeeping
    boolean cropExists() {
        return cropFile.exists();
    }

    boolean sourceExists() {
        return wallpaperFile.exists();
    }
}
