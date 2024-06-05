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

import static android.app.WallpaperManager.FLAG_LOCK;
import static android.app.WallpaperManager.ORIENTATION_UNKNOWN;

import static com.android.server.wallpaper.WallpaperUtils.WALLPAPER;
import static com.android.server.wallpaper.WallpaperUtils.WALLPAPER_CROP;
import static com.android.server.wallpaper.WallpaperUtils.WALLPAPER_LOCK_CROP;
import static com.android.server.wallpaper.WallpaperUtils.WALLPAPER_LOCK_ORIG;
import static com.android.server.wallpaper.WallpaperUtils.getWallpaperDir;

import android.app.IWallpaperManagerCallback;
import android.app.WallpaperColors;
import android.app.WallpaperManager.ScreenOrientation;
import android.app.WallpaperManager.SetWallpaperFlags;
import android.content.ComponentName;
import android.graphics.Rect;
import android.os.RemoteCallbackList;
import android.util.SparseArray;

import java.io.File;

/**
 * The main wallpaper data model, used internally by the {@link WallpaperManagerService}. <br>
 * An instance of this class contains all the information about a wallpaper.
 */
class WallpaperData {

    final int userId;

    /**
     * True while the client is writing a new wallpaper
     */
    boolean imageWallpaperPending;

    /**
     * Which wallpaper is set. Flag values are from {@link SetWallpaperFlags}.
     */
    int mWhich;

    /**
     * True if the system wallpaper was also used for lock screen before this wallpaper was set.
     * This is needed to update state after setting the wallpaper.
     */
    boolean mSystemWasBoth;

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
    SparseArray<Float> mUidToDimAmount = new SparseArray<>();

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
     * Defines which part of the {@link #getWallpaperFile()} image is in the {@link #getCropFile()}.
     */
    final Rect cropHint = new Rect(0, 0, 0, 0);

    /**
     * How much the crop is sub-sampled. A value > 1 means that the image quality was reduced.
     * This is the ratio between the cropHint height and the actual {@link #getCropFile()} height.
     */
    float mSampleSize = 1f;

    // Describes the context of a call to WallpaperManagerService#bindWallpaperComponentLocked
    enum BindSource {
        UNKNOWN,
        CONNECT_LOCKED,
        CONNECTION_TRY_TO_REBIND,
        INITIALIZE_FALLBACK,
        PACKAGE_UPDATE_FINISHED,
        RESTORE_SETTINGS_LIVE_FAILURE,
        RESTORE_SETTINGS_LIVE_SUCCESS,
        RESTORE_SETTINGS_STATIC,
        SET_LIVE,
        SET_LIVE_TO_CLEAR,
        SET_STATIC,
        SWITCH_WALLPAPER_FAILURE,
        SWITCH_WALLPAPER_SWITCH_USER,
        SWITCH_WALLPAPER_UNLOCK_USER,
    }

    // Context in which this wallpaper was bound. Intended for use in resolving b/301073479 but may
    // be useful after the issue is resolved as well.
    BindSource mBindSource = BindSource.UNKNOWN;

    // map of which -> File
    private final SparseArray<File> mWallpaperFiles = new SparseArray<>();
    private final SparseArray<File> mCropFiles = new SparseArray<>();

    /**
     * Mapping of {@link ScreenOrientation} -> crop hint. The crop hints are relative to the
     * original image stored in {@link #getWallpaperFile()}.
     * Only used when multi crop flag is enabled.
     */
    SparseArray<Rect> mCropHints = new SparseArray<>();

    /**
     * The phone orientation when the wallpaper was set. Only relevant for image wallpapers
     */
    int mOrientationWhenSet = ORIENTATION_UNKNOWN;

    WallpaperData(int userId, @SetWallpaperFlags int wallpaperType) {
        this.userId = userId;
        this.mWhich = wallpaperType;
    }

    /**
     * Copies the essential properties of a WallpaperData to a new instance, including the id and
     * WallpaperConnection, usually in preparation for migrating a system+lock wallpaper to system-
     * or lock-only. NB: the source object retains the pointer to the connection and it is the
     * caller's responsibility to set this to null or otherwise be sure the connection is not shared
     * between WallpaperData instances.
     *
     * @param source WallpaperData object to copy
     */
    WallpaperData(WallpaperData source) {
        this.userId = source.userId;
        this.wallpaperComponent = source.wallpaperComponent;
        this.mWhich = source.mWhich;
        this.wallpaperId = source.wallpaperId;
        this.cropHint.set(source.cropHint);
        if (source.mCropHints != null) {
            this.mCropHints = source.mCropHints.clone();
        }
        this.allowBackup = source.allowBackup;
        this.primaryColors = source.primaryColors;
        this.mWallpaperDimAmount = source.mWallpaperDimAmount;
        this.connection = source.connection;
        if (this.connection != null) {
            this.connection.mWallpaper = this;
        }
    }

    File getWallpaperFile() {
        String fileName = mWhich == FLAG_LOCK ? WALLPAPER_LOCK_ORIG : WALLPAPER;
        return getFile(mWallpaperFiles, fileName);
    }

    File getCropFile() {
        String fileName = mWhich == FLAG_LOCK ? WALLPAPER_LOCK_CROP : WALLPAPER_CROP;
        return getFile(mCropFiles, fileName);
    }

    private File getFile(SparseArray<File> map, String fileName) {
        File result = map.get(mWhich);
        if (result == null) {
            result = new File(getWallpaperDir(userId), fileName);
            map.put(userId, result);
        }
        return result;
    }

    @Override
    public String toString() {
        StringBuilder out = new StringBuilder(defaultString(this));
        out.append(", id: ");
        out.append(wallpaperId);
        out.append(", which: ");
        out.append(mWhich);
        out.append(", file mod: ");
        out.append(getWallpaperFile() != null ? getWallpaperFile().lastModified() : "null");
        if (connection == null) {
            out.append(", no connection");
        } else {
            out.append(", info: ");
            out.append(connection.mInfo);
            out.append(", engine(s):");
            connection.forEachDisplayConnector(connector -> {
                if (connector.mEngine != null) {
                    out.append(" ");
                    out.append(defaultString(connector.mEngine));
                } else {
                    out.append(" null");
                }
            });
        }
        return out.toString();
    }

    private static String defaultString(Object o) {
        return o.getClass().getSimpleName() + "@" + Integer.toHexString(o.hashCode());
    }

    // Called during initialization of a given user's wallpaper bookkeeping
    boolean cropExists() {
        return getCropFile().exists();
    }

    boolean sourceExists() {
        return getWallpaperFile().exists();
    }
}
