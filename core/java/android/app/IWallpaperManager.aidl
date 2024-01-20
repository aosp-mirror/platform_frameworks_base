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

import android.graphics.Point;
import android.graphics.Rect;
import android.graphics.RectF;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import android.app.IWallpaperManagerCallback;
import android.app.ILocalWallpaperColorConsumer;
import android.app.WallpaperInfo;
import android.content.ComponentName;
import android.app.WallpaperColors;

import java.util.List;

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
     * 'screenOrientations' and 'crops' define how the wallpaper will be positioned for
     * different screen orientations. If some screen orientations are missing, crops for these
     * orientations will be added by the system.
     *
     * If 'screenOrientations' is null, 'crops' can be null or a singleton list. The system will
     * fit the provided crop (or the whole image, if 'crops' is 'null') for the current device
     * orientation, and add crops for the missing orientations.
     *
     * The completion callback's "onWallpaperChanged()" method is invoked when the
     * new wallpaper content is ready to display.
     */
    @JavaPassthrough(annotation="@android.annotation.RequiresPermission(android.Manifest.permission.SET_WALLPAPER)")
    ParcelFileDescriptor setWallpaper(String name, in String callingPackage,
            in int[] screenOrientations, in List<Rect> crops, boolean allowBackup,
            out Bundle extras, int which, IWallpaperManagerCallback completion, int userId);

    /**
     * Set the live wallpaper.
     */
    void setWallpaperComponentChecked(in ComponentName name, in String callingPackage, int which,
            int userId);

    /**
     * Set the live wallpaper. This only affects the system wallpaper.
     */
    @UnsupportedAppUsage
    void setWallpaperComponent(in ComponentName name);


    /**
     * @deprecated Use {@link #getWallpaperWithFeature(String, IWallpaperManagerCallback, int,
     * Bundle, int)}
     */
    @UnsupportedAppUsage
    ParcelFileDescriptor getWallpaper(String callingPkg, IWallpaperManagerCallback cb, int which,
            out Bundle outParams, int userId);

    /**
     * Get the wallpaper for a given user.
     */
    ParcelFileDescriptor getWallpaperWithFeature(String callingPkg, String callingFeatureId,
            IWallpaperManagerCallback cb, int which, out Bundle outParams, int userId,
            boolean getCropped);

    /**
     * For a given user and a list of display sizes, get a list of Rect representing the
     * area of the current wallpaper that is displayed for each display size.
     */
    @JavaPassthrough(annotation="@android.annotation.RequiresPermission(android.Manifest.permission.READ_WALLPAPER_INTERNAL)")
    @SuppressWarnings(value={"untyped-collection"})
    List getBitmapCrops(in List<Point> displaySizes, int which, boolean originalBitmap, int userId);

    /**
     * Return how a bitmap of a given size would be cropped for a given list of display sizes when
     * set with the given suggested crops.
     * @hide
     */
    @SuppressWarnings(value={"untyped-collection"})
    List getFutureBitmapCrops(in Point bitmapSize, in List<Point> displaySizes,
            in int[] screenOrientations, in List<Rect> crops);

    /**
     * Return how a bitmap of a given size would be cropped when set with the given suggested crops.
     * @hide
     */
    @SuppressWarnings(value={"untyped-collection"})
    Rect getBitmapCrop(in Point bitmapSize, in int[] screenOrientations, in List<Rect> crops);

    /**
     * Retrieve the given user's current wallpaper ID of the given kind.
     */
    int getWallpaperIdForUser(int which, int userId);

    /**
     * If the current system wallpaper is a live wallpaper component, return the
     * information about that wallpaper.  Otherwise, if it is a static image,
     * simply return null.
     */
    @UnsupportedAppUsage(maxTargetSdk = 30, trackingBug = 170729553)
    WallpaperInfo getWallpaperInfo(int userId);

    /**
     * If the current wallpaper for destination `which` is a live wallpaper component, return the
     * information about that wallpaper.  Otherwise, if it is a static image, simply return null.
     */
    WallpaperInfo getWallpaperInfoWithFlags(int which, int userId);

    /**
     * Return a file descriptor for the file that contains metadata about the given user's
     * wallpaper.
     */
    ParcelFileDescriptor getWallpaperInfoFile(int userId);

    /**
     * Clear the system wallpaper.
     */
    void clearWallpaper(in String callingPackage, int which, int userId);

    /**
     * Return whether the current system wallpaper has the given name.
     */
    @UnsupportedAppUsage
    boolean hasNamedWallpaper(String name);

    /**
     * Sets the dimension hint for the wallpaper. These hints indicate the desired
     * minimum width and height for the wallpaper in a particular display.
     */
    void setDimensionHints(in int width, in int height, in String callingPackage, int displayId);

    /**
     * Returns the desired minimum width for the wallpaper in a particular display.
     */
    @UnsupportedAppUsage
    int getWidthHint(int displayId);

    /**
     * Returns the desired minimum height for the wallpaper in a particular display.
     */
    @UnsupportedAppUsage
    int getHeightHint(int displayId);

    /**
     * Sets extra padding that we would like the wallpaper to have outside of the display.
     */
    void setDisplayPadding(in Rect padding, in String callingPackage, int displayId);

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

    /**
     * Returns the colors used by the lock screen or system wallpaper.
     *
     * @param which either {@link WallpaperManager#FLAG_LOCK}
     * or {@link WallpaperManager#FLAG_SYSTEM}
     * @param displayId Which display is interested
     * @return colors of chosen wallpaper
     */
    WallpaperColors getWallpaperColors(int which, int userId, int displayId);

    /**
    * @hide
    */
    void removeOnLocalColorsChangedListener(
            in ILocalWallpaperColorConsumer callback, in List<RectF> area,
            int which, int userId, int displayId);

    /**
    * @hide
    */
    void addOnLocalColorsChangedListener(in ILocalWallpaperColorConsumer callback,
                                    in List<RectF> regions, int which, int userId, int displayId);

    /**
     * Register a callback to receive color updates from a display
     */
    void registerWallpaperColorsCallback(IWallpaperManagerCallback cb, int userId, int displayId);

    /**
     * Unregister a callback that was receiving color updates from a display
     */
    void unregisterWallpaperColorsCallback(IWallpaperManagerCallback cb, int userId, int displayId);

    /**
     * Called from SystemUI when it shows the AoD UI.
     */
    oneway void setInAmbientMode(boolean inAmbientMode, long animationDuration);

    /**
     * Called from SystemUI when the device is waking up.
     *
     * @hide
     */
    oneway void notifyWakingUp(int x, int y, in Bundle extras);

    /**
     * Called from SystemUI when the device is going to sleep.
     *
     * @hide
     */
    void notifyGoingToSleep(int x, int y, in Bundle extras);

    /**
     * Sets the wallpaper dim amount between [0f, 1f] which would be blended with the system default
     * dimming. 0f doesn't add any additional dimming and 1f makes the wallpaper fully black.
     *
     * @hide
     */
    @JavaPassthrough(annotation="@android.annotation.RequiresPermission(android.Manifest.permission.SET_WALLPAPER_DIM_AMOUNT)")
    oneway void setWallpaperDimAmount(float dimAmount);

    /**
     * Gets the current additional dim amount set on the wallpaper. 0f means no application has
     * added any dimming on top of the system default dim amount.
     *
     * @hide
     */
    @JavaPassthrough(annotation="@android.annotation.RequiresPermission(android.Manifest.permission.SET_WALLPAPER_DIM_AMOUNT)")
    float getWallpaperDimAmount();

    /**
     * Whether the lock screen wallpaper is different from the system wallpaper.
     *
     * @hide
     */
    boolean lockScreenWallpaperExists();

    /**
     * Return true if there is a static wallpaper on the specified screen. With which=FLAG_LOCK,
     * always return false if the lock screen doesn't run its own wallpaper engine.
     *
     * @hide
     */
    boolean isStaticWallpaper(int which);
}
