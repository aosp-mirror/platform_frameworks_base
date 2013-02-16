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
import android.hardware.display.DisplayManagerGlobal;
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
    private static final boolean DEBUG = false;

    private final DisplayManagerGlobal mGlobal;
    private final int mDisplayId;
    private final int mLayerStack;
    private final int mFlags;
    private final int mType;
    private final String mAddress;
    private final CompatibilityInfoHolder mCompatibilityInfo;

    private DisplayInfo mDisplayInfo; // never null
    private boolean mIsValid;

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
     * Display flag: Indicates that the display supports compositing content
     * that is stored in protected graphics buffers.
     * <p>
     * If this flag is set then the display device supports compositing protected buffers.
     * </p><p>
     * If this flag is not set then the display device may not support compositing
     * protected buffers; the user may see a blank region on the screen instead of
     * the protected content.
     * </p><p>
     * Secure (DRM) video decoders may allocate protected graphics buffers to request that
     * a hardware-protected path be provided between the video decoder and the external
     * display sink.  If a hardware-protected path is not available, then content stored
     * in protected graphics buffers may not be composited.
     * </p><p>
     * An application can use the absence of this flag as a hint that it should not use protected
     * buffers for this display because the content may not be visible.  For example,
     * if the flag is not set then the application may choose not to show content on this
     * display, show an informative error message, select an alternate content stream
     * or adopt a different strategy for decoding content that does not rely on
     * protected buffers.
     * </p>
     *
     * @see #getFlags
     */
    public static final int FLAG_SUPPORTS_PROTECTED_BUFFERS = 1 << 0;

    /**
     * Display flag: Indicates that the display has a secure video output and
     * supports compositing secure surfaces.
     * <p>
     * If this flag is set then the display device has a secure video output
     * and is capable of showing secure surfaces.  It may also be capable of
     * showing {@link #FLAG_SUPPORTS_PROTECTED_BUFFERS protected buffers}.
     * </p><p>
     * If this flag is not set then the display device may not have a secure video
     * output; the user may see a blank region on the screen instead of
     * the contents of secure surfaces or protected buffers.
     * </p><p>
     * Secure surfaces are used to prevent content rendered into those surfaces
     * by applications from appearing in screenshots or from being viewed
     * on non-secure displays.  Protected buffers are used by secure video decoders
     * for a similar purpose.
     * </p><p>
     * An application creates a window with a secure surface by specifying the
     * {@link WindowManager.LayoutParams#FLAG_SECURE} window flag.
     * Likewise, an application creates a {@link SurfaceView} with a secure surface
     * by calling {@link SurfaceView#setSecure} before attaching the secure view to
     * its containing window.
     * </p><p>
     * An application can use the absence of this flag as a hint that it should not create
     * secure surfaces or protected buffers on this display because the content may
     * not be visible.  For example, if the flag is not set then the application may
     * choose not to show content on this display, show an informative error message,
     * select an alternate content stream or adopt a different strategy for decoding
     * content that does not rely on secure surfaces or protected buffers.
     * </p>
     *
     * @see #getFlags
     */
    public static final int FLAG_SECURE = 1 << 1;

    /**
     * Display type: Unknown display type.
     * @hide
     */
    public static final int TYPE_UNKNOWN = 0;

    /**
     * Display type: Built-in display.
     * @hide
     */
    public static final int TYPE_BUILT_IN = 1;

    /**
     * Display type: HDMI display.
     * @hide
     */
    public static final int TYPE_HDMI = 2;

    /**
     * Display type: WiFi display.
     * @hide
     */
    public static final int TYPE_WIFI = 3;

    /**
     * Display type: Overlay display.
     * @hide
     */
    public static final int TYPE_OVERLAY = 4;

    /**
     * Internal method to create a display.
     * Applications should use {@link android.view.WindowManager#getDefaultDisplay()}
     * or {@link android.hardware.display.DisplayManager#getDisplay}
     * to get a display object.
     *
     * @hide
     */
    public Display(DisplayManagerGlobal global,
            int displayId, DisplayInfo displayInfo /*not null*/,
            CompatibilityInfoHolder compatibilityInfo) {
        mGlobal = global;
        mDisplayId = displayId;
        mDisplayInfo = displayInfo;
        mCompatibilityInfo = compatibilityInfo;
        mIsValid = true;

        // Cache properties that cannot change as long as the display is valid.
        mLayerStack = displayInfo.layerStack;
        mFlags = displayInfo.flags;
        mType = displayInfo.type;
        mAddress = displayInfo.address;
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
     * Returns true if this display is still valid, false if the display has been removed.
     *
     * If the display is invalid, then the methods of this class will
     * continue to report the most recently observed display information.
     * However, it is unwise (and rather fruitless) to continue using a
     * {@link Display} object after the display's demise.
     *
     * It's possible for a display that was previously invalid to become
     * valid again if a display with the same id is reconnected.
     *
     * @return True if the display is still valid.
     */
    public boolean isValid() {
        synchronized (this) {
            updateDisplayInfoLocked();
            return mIsValid;
        }
    }

    /**
     * Gets a full copy of the display information.
     *
     * @param outDisplayInfo The object to receive the copy of the display information.
     * @return True if the display is still valid.
     * @hide
     */
    public boolean getDisplayInfo(DisplayInfo outDisplayInfo) {
        synchronized (this) {
            updateDisplayInfoLocked();
            outDisplayInfo.copyFrom(mDisplayInfo);
            return mIsValid;
        }
    }

    /**
     * Gets the display's layer stack.
     *
     * Each display has its own independent layer stack upon which surfaces
     * are placed to be managed by surface flinger.
     *
     * @return The display's layer stack number.
     * @hide
     */
    public int getLayerStack() {
        return mLayerStack;
    }

    /**
     * Returns a combination of flags that describe the capabilities of the display.
     *
     * @return The display flags.
     *
     * @see #FLAG_SUPPORTS_PROTECTED_BUFFERS
     * @see #FLAG_SECURE
     */
    public int getFlags() {
        return mFlags;
    }

    /**
     * Gets the display type.
     *
     * @return The display type.
     *
     * @see #TYPE_UNKNOWN
     * @see #TYPE_BUILT_IN
     * @see #TYPE_HDMI
     * @see #TYPE_WIFI
     * @see #TYPE_OVERLAY
     * @hide
     */
    public int getType() {
        return mType;
    }

    /**
     * Gets the display address, or null if none.
     * Interpretation varies by display type.
     *
     * @return The display address.
     * @hide
     */
    public String getAddress() {
        return mAddress;
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
     * Gets the name of the display.
     * <p>
     * Note that some displays may be renamed by the user.
     * </p>
     *
     * @return The display's name.
     */
    public String getName() {
        synchronized (this) {
            updateDisplayInfoLocked();
            return mDisplayInfo.name;
        }
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
     * @hide
     * Return a rectangle defining the insets of the overscan region of the display.
     * Each field of the rectangle is the number of pixels the overscan area extends
     * into the display on that side.
     */
    public void getOverscanInsets(Rect outRect) {
        synchronized (this) {
            updateDisplayInfoLocked();
            outRect.set(mDisplayInfo.overscanLeft, mDisplayInfo.overscanTop,
                    mDisplayInfo.overscanRight, mDisplayInfo.overscanBottom);
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
        // Note: The display manager caches display info objects on our behalf.
        DisplayInfo newInfo = mGlobal.getDisplayInfo(mDisplayId);
        if (newInfo == null) {
            // Preserve the old mDisplayInfo after the display is removed.
            if (mIsValid) {
                mIsValid = false;
                if (DEBUG) {
                    Log.d(TAG, "Logical display " + mDisplayId + " was removed.");
                }
            }
        } else {
            // Use the new display info.  (It might be the same object if nothing changed.)
            mDisplayInfo = newInfo;
            if (!mIsValid) {
                mIsValid = true;
                if (DEBUG) {
                    Log.d(TAG, "Logical display " + mDisplayId + " was recreated.");
                }
            }
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

    // For debugging purposes
    @Override
    public String toString() {
        synchronized (this) {
            updateDisplayInfoLocked();
            mDisplayInfo.getAppMetrics(mTempMetrics, mCompatibilityInfo);
            return "Display id " + mDisplayId + ": " + mDisplayInfo
                    + ", " + mTempMetrics + ", isValid=" + mIsValid;
        }
    }

    /**
     * @hide
     */
    public static String typeToString(int type) {
        switch (type) {
            case TYPE_UNKNOWN:
                return "UNKNOWN";
            case TYPE_BUILT_IN:
                return "BUILT_IN";
            case TYPE_HDMI:
                return "HDMI";
            case TYPE_WIFI:
                return "WIFI";
            case TYPE_OVERLAY:
                return "OVERLAY";
            default:
                return Integer.toString(type);
        }
    }
}

