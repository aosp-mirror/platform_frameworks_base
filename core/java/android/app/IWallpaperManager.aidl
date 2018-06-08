/**
 * Copyright (c) 2008, The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package android.app;

import android.graphics.Rect;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import android.app.IWallpaperManagerCallback;
import android.app.WallpaperInfo;
import android.content.ComponentName;
import android.app.WallpaperColors;

/** @hide */
interface IWallpaperManager {

    /**
     * Set the wallpaper for the current user.
     *
     * If 'extras' is non-null, on successful return it will contain:
     *   EXTRA_SET_WALLPAPER_ID : integer ID that the new wallpaper will have
     *
     * 'which' is some combination of:
     *   FLAG_SET_SYSTEM
     *   FLAG_SET_LOCK
     *
     * A 'null' cropHint rectangle is explicitly permitted as a sentinel for "whatever
     * the source image's bounding rect is."
     *
     * The completion callback's "onWallpaperChanged()" method is invoked when the
     * new wallpaper content is ready to display.
     */
    ParcelFileDescriptor setWallpaper(String name, in String callingPackage,
            in Rect cropHint, boolean allowBackup, out Bundle extras, int which,
            IWallpaperManagerCallback completion, int userId);

    /**
     * Set the live wallpaper. This only affects the system wallpaper.
     */
    void setWallpaperComponentChecked(in ComponentName name, in String callingPackage, int userId);

    /**
     * Set the live wallpaper. This only affects the system wallpaper.
     */
    void setWallpaperComponent(in ComponentName name);

    /**
     * Get the wallpaper for a given user.
     */
    ParcelFileDescriptor getWallpaper(String callingPkg, IWallpaperManagerCallback cb, int which,
            out Bundle outParams, int userId);

    /**
     * Retrieve the given user's current wallpaper ID of the given kind.
     */
    int getWallpaperIdForUser(int which, int userId);

    /**
     * If the current system wallpaper is a live wallpaper component, return the
     * information about that wallpaper.  Otherwise, if it is a static image,
     * simply return null.
     */
    WallpaperInfo getWallpaperInfo(int userId);

    /**
     * Clear the system wallpaper.
     */
    void clearWallpaper(in String callingPackage, int which, int userId);

    /**
     * Return whether the current system wallpaper has the given name.
     */
    boolean hasNamedWallpaper(String name);

    /**
     * Sets the dimension hint for the wallpaper. These hints indicate the desired
     * minimum width and height for the wallpaper.
     */
    void setDimensionHints(in int width, in int height, in String callingPackage);

    /**
     * Returns the desired minimum width for the wallpaper.
     */
    int getWidthHint();

    /**
     * Returns the desired minimum height for the wallpaper.
     */
    int getHeightHint();

    /**
     * Sets extra padding that we would like the wallpaper to have outside of the display.
     */
    void setDisplayPadding(in Rect padding, in String callingPackage);

    /**
     * Returns the name of the wallpaper. Private API.
     */
    String getName();

    /**
     * Informs the service that wallpaper settings have been restored. Private API.
     */
    void settingsRestored();

    /**
     * Check whether wallpapers are supported for the calling user.
     */
    boolean isWallpaperSupported(in String callingPackage);
    
    /**
     * Check whether setting of wallpapers are allowed for the calling user.
     */
    boolean isSetWallpaperAllowed(in String callingPackage);

    /*
     * Backup: is the current system wallpaper image eligible for off-device backup?
     */
    boolean isWallpaperBackupEligible(int which, int userId);

    /*
     * Keyguard: register a callback for being notified that lock-state relevant
     * wallpaper content has changed.
     */
    boolean setLockWallpaperCallback(IWallpaperManagerCallback cb);

    /**
     * Returns the colors used by the lock screen or system wallpaper.
     *
     * @param which either {@link WallpaperManager#FLAG_LOCK}
     * or {@link WallpaperManager#FLAG_SYSTEM}
     * @return colors of chosen wallpaper
     */
    WallpaperColors getWallpaperColors(int which, int userId);

    /**
     * Register a callback to receive color updates
     */
    void registerWallpaperColorsCallback(IWallpaperManagerCallback cb, int userId);

    /**
     * Unregister a callback that was receiving color updates
     */
    void unregisterWallpaperColorsCallback(IWallpaperManagerCallback cb, int userId);

    /**
     * Called from SystemUI when it shows the AoD UI.
     */
    oneway void setInAmbientMode(boolean inAmbientMode, boolean animated);
}
