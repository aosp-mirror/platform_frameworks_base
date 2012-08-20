/*
 * Copyright (C) 2006 The Android Open Source Project
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

package android.view;

import android.graphics.PixelFormat;
import android.graphics.Point;
import android.graphics.Rect;
import android.hardware.display.DisplayManager;
import android.os.SystemClock;
import android.util.DisplayMetrics;
import android.util.Log;

/**
 * Provides information about the size and density of a logical display.
 * <p>
 * The display area is described in two different ways.
 * <ul>
 * <li>The application display area specifies the part of the display that may contain
 * an application window, excluding the system decorations.  The application display area may
 * be smaller than the real display area because the system subtracts the space needed
 * for decor elements such as the status bar.  Use the following methods to query the
 * application display area: {@link #getSize}, {@link #getRectSize} and {@link #getMetrics}.</li>
 * <li>The real display area specifies the part of the display that contains content
 * including the system decorations.  Even so, the real display area may be smaller than the
 * physical size of the display if the window manager is emulating a smaller display
 * using (adb shell am display-size).  Use the following methods to query the
 * real display area: {@link #getRealSize}, {@link #getRealMetrics}.</li>
 * </ul>
 * </p><p>
 * A logical display does not necessarily represent a particular physical display device
 * such as the built-in screen or an external monitor.  The contents of a logical
 * display may be presented on one or more physical displays according to the devices
 * that are currently attached and whether mirroring has been enabled.
 * </p>
 */
public final class Display {
    private static final String TAG = "Display";

    private final int mDisplayId;
    private final CompatibilityInfoHolder mCompatibilityInfo;
    private final DisplayInfo mDisplayInfo = new DisplayInfo();

    // Temporary display metrics structure used for compatibility mode.
    private final DisplayMetrics mTempMetrics = new DisplayMetrics();

    // We cache the app width and height properties briefly between calls
    // to getHeight() and getWidth() to ensure that applications perceive
    // consistent results when the size changes (most of the time).
    // Applications should now be using getSize() instead.
    private static final int CACHED_APP_SIZE_DURATION_MILLIS = 20;
    private long mLastCachedAppSizeUpdate;
    private int mCachedAppWidthCompat;
    private int mCachedAppHeightCompat;

    /**
     * The default Display id, which is the id of the built-in primary display
     * assuming there is one.
     */
    public static final int DEFAULT_DISPLAY = 0;

    /**
     * Internal method to create a display.
     * Applications should use {@link android.view.WindowManager#getDefaultDisplay()}
     * or {@link android.hardware.display.DisplayManager#getDisplay}
     * to get a display object.
     *
     * @hide
     */
    public Display(int displayId, CompatibilityInfoHolder compatibilityInfo) {
        mDisplayId = displayId;
        mCompatibilityInfo = compatibilityInfo;
    }

    /**
     * Gets the display id.
     * <p>
     * Each logical display has a unique id.
     * The default display has id {@link #DEFAULT_DISPLAY}.
     * </p>
     */
    public int getDisplayId() {
        return mDisplayId;
    }

    /**
     * Gets a full copy of the display information.
     *
     * @param outDisplayInfo The object to receive the copy of the display information.
     * @hide
     */
    public void getDisplayInfo(DisplayInfo outDisplayInfo) {
        synchronized (this) {
            updateDisplayInfoLocked();
            outDisplayInfo.copyFrom(mDisplayInfo);
        }
    }

    /**
     * Gets the display's layer stack.
     *
     * Each display has its own independent layer stack upon which surfaces
     * are placed to be managed by surface flinger.
     *
     * @return The layer stack number.
     * @hide
     */
    public int getLayerStack() {
        // Note: This is the current convention but there is no requirement that
        // the display id and layer stack id be the same.
        return mDisplayId;
    }

    /**
     * Gets the compatibility info used by this display instance.
     *
     * @return The compatibility info holder, or null if none is required.
     * @hide
     */
    public CompatibilityInfoHolder getCompatibilityInfo() {
        return mCompatibilityInfo;
    }

    /**
     * Gets the size of the display, in pixels.
     * <p>
     * Note that this value should <em>not</em> be used for computing layouts,
     * since a device will typically have screen decoration (such as a status bar)
     * along the edges of the display that reduce the amount of application
     * space available from the size returned here.  Layouts should instead use
     * the window size.
     * </p><p>
     * The size is adjusted based on the current rotation of the display.
     * </p><p>
     * The size returned by this method does not necessarily represent the
     * actual raw size (native resolution) of the display.  The returned size may
     * be adjusted to exclude certain system decoration elements that are always visible.
     * It may also be scaled to provide compatibility with older applications that
     * were originally designed for smaller displays.
     * </p>
     *
     * @param outSize A {@link Point} object to receive the size information.
     */
    public void getSize(Point outSize) {
        synchronized (this) {
            updateDisplayInfoLocked();
            mDisplayInfo.getAppMetrics(mTempMetrics, mCompatibilityInfo);
            outSize.x = mTempMetrics.widthPixels;
            outSize.y = mTempMetrics.heightPixels;
        }
    }

    /**
     * Gets the size of the display as a rectangle, in pixels.
     *
     * @param outSize A {@link Rect} object to receive the size information.
     * @see #getSize(Point)
     */
    public void getRectSize(Rect outSize) {
        synchronized (this) {
            updateDisplayInfoLocked();
            mDisplayInfo.getAppMetrics(mTempMetrics, mCompatibilityInfo);
            outSize.set(0, 0, mTempMetrics.widthPixels, mTempMetrics.heightPixels);
        }
    }

    /**
     * Return the range of display sizes an application can expect to encounter
     * under normal operation, as long as there is no physical change in screen
     * size.  This is basically the sizes you will see as the orientation
     * changes, taking into account whatever screen decoration there is in
     * each rotation.  For example, the status bar is always at the top of the
     * screen, so it will reduce the height both in landscape and portrait, and
     * the smallest height returned here will be the smaller of the two.
     *
     * This is intended for applications to get an idea of the range of sizes
     * they will encounter while going through device rotations, to provide a
     * stable UI through rotation.  The sizes here take into account all standard
     * system decorations that reduce the size actually available to the
     * application: the status bar, navigation bar, system bar, etc.  It does
     * <em>not</em> take into account more transient elements like an IME
     * soft keyboard.
     *
     * @param outSmallestSize Filled in with the smallest width and height
     * that the application will encounter, in pixels (not dp units).  The x
     * (width) dimension here directly corresponds to
     * {@link android.content.res.Configuration#smallestScreenWidthDp
     * Configuration.smallestScreenWidthDp}, except the value here is in raw
     * screen pixels rather than dp units.  Your application may of course
     * still get smaller space yet if, for example, a soft keyboard is
     * being displayed.
     * @param outLargestSize Filled in with the largest width and height
     * that the application will encounter, in pixels (not dp units).  Your
     * application may of course still get larger space than this if,
     * for example, screen decorations like the status bar are being hidden.
     */
    public void getCurrentSizeRange(Point outSmallestSize, Point outLargestSize) {
        synchronized (this) {
            updateDisplayInfoLocked();
            outSmallestSize.x = mDisplayInfo.smallestNominalAppWidth;
            outSmallestSize.y = mDisplayInfo.smallestNominalAppHeight;
            outLargestSize.x = mDisplayInfo.largestNominalAppWidth;
            outLargestSize.y = mDisplayInfo.largestNominalAppHeight;
        }
    }

    /**
     * Return the maximum screen size dimension that will happen.  This is
     * mostly for wallpapers.
     * @hide
     */
    public int getMaximumSizeDimension() {
        synchronized (this) {
            updateDisplayInfoLocked();
            return Math.max(mDisplayInfo.logicalWidth, mDisplayInfo.logicalHeight);
        }
    }

    /**
     * @deprecated Use {@link #getSize(Point)} instead.
     */
    @Deprecated
    public int getWidth() {
        synchronized (this) {
            updateCachedAppSizeIfNeededLocked();
            return mCachedAppWidthCompat;
        }
    }

    /**
     * @deprecated Use {@link #getSize(Point)} instead.
     */
    @Deprecated
    public int getHeight() {
        synchronized (this) {
            updateCachedAppSizeIfNeededLocked();
            return mCachedAppHeightCompat;
        }
    }

    /**
     * Returns the rotation of the screen from its "natural" orientation.
     * The returned value may be {@link Surface#ROTATION_0 Surface.ROTATION_0}
     * (no rotation), {@link Surface#ROTATION_90 Surface.ROTATION_90},
     * {@link Surface#ROTATION_180 Surface.ROTATION_180}, or
     * {@link Surface#ROTATION_270 Surface.ROTATION_270}.  For
     * example, if a device has a naturally tall screen, and the user has
     * turned it on its side to go into a landscape orientation, the value
     * returned here may be either {@link Surface#ROTATION_90 Surface.ROTATION_90}
     * or {@link Surface#ROTATION_270 Surface.ROTATION_270} depending on
     * the direction it was turned.  The angle is the rotation of the drawn
     * graphics on the screen, which is the opposite direction of the physical
     * rotation of the device.  For example, if the device is rotated 90
     * degrees counter-clockwise, to compensate rendering will be rotated by
     * 90 degrees clockwise and thus the returned value here will be
     * {@link Surface#ROTATION_90 Surface.ROTATION_90}.
     */
    public int getRotation() {
        synchronized (this) {
            updateDisplayInfoLocked();
            return mDisplayInfo.rotation;
        }
    }

    /**
     * @deprecated use {@link #getRotation}
     * @return orientation of this display.
     */
    @Deprecated
    public int getOrientation() {
        return getRotation();
    }

    /**
     * Gets the pixel format of the display.
     * @return One of the constants defined in {@link android.graphics.PixelFormat}.
     *
     * @deprecated This method is no longer supported.
     * The result is always {@link PixelFormat#RGBA_8888}.
     */
    @Deprecated
    public int getPixelFormat() {
        return PixelFormat.RGBA_8888;
    }

    /**
     * Gets the refresh rate of this display in frames per second.
     */
    public float getRefreshRate() {
        synchronized (this) {
            updateDisplayInfoLocked();
            return mDisplayInfo.refreshRate;
        }
    }

    /**
     * Gets display metrics that describe the size and density of this display.
     * <p>
     * The size is adjusted based on the current rotation of the display.
     * </p><p>
     * The size returned by this method does not necessarily represent the
     * actual raw size (native resolution) of the display.  The returned size may
     * be adjusted to exclude certain system decor elements that are always visible.
     * It may also be scaled to provide compatibility with older applications that
     * were originally designed for smaller displays.
     * </p>
     *
     * @param outMetrics A {@link DisplayMetrics} object to receive the metrics.
     */
    public void getMetrics(DisplayMetrics outMetrics) {
        synchronized (this) {
            updateDisplayInfoLocked();
            mDisplayInfo.getAppMetrics(outMetrics, mCompatibilityInfo);
        }
    }

    /**
     * Gets the real size of the display without subtracting any window decor or
     * applying any compatibility scale factors.
     * <p>
     * The size is adjusted based on the current rotation of the display.
     * </p><p>
     * The real size may be smaller than the physical size of the screen when the
     * window manager is emulating a smaller display (using adb shell am display-size).
     * </p>
     *
     * @param outSize Set to the real size of the display.
     */
    public void getRealSize(Point outSize) {
        synchronized (this) {
            updateDisplayInfoLocked();
            outSize.x = mDisplayInfo.logicalWidth;
            outSize.y = mDisplayInfo.logicalHeight;
        }
    }

    /**
     * Gets display metrics based on the real size of this display.
     * <p>
     * The size is adjusted based on the current rotation of the display.
     * </p><p>
     * The real size may be smaller than the physical size of the screen when the
     * window manager is emulating a smaller display (using adb shell am display-size).
     * </p>
     *
     * @param outMetrics A {@link DisplayMetrics} object to receive the metrics.
     */
    public void getRealMetrics(DisplayMetrics outMetrics) {
        synchronized (this) {
            updateDisplayInfoLocked();
            mDisplayInfo.getLogicalMetrics(outMetrics, null);
        }
    }

    private void updateDisplayInfoLocked() {
        // TODO: only refresh the display information when needed
        if (!DisplayManager.getInstance().getDisplayInfo(mDisplayId, mDisplayInfo)) {
            Log.e(TAG, "Could not get information about logical display " + mDisplayId);
        }
    }

    private void updateCachedAppSizeIfNeededLocked() {
        long now = SystemClock.uptimeMillis();
        if (now > mLastCachedAppSizeUpdate + CACHED_APP_SIZE_DURATION_MILLIS) {
            updateDisplayInfoLocked();
            mDisplayInfo.getAppMetrics(mTempMetrics, mCompatibilityInfo);
            mCachedAppWidthCompat = mTempMetrics.widthPixels;
            mCachedAppHeightCompat = mTempMetrics.heightPixels;
            mLastCachedAppSizeUpdate = now;
        }
    }
}

