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

import static android.Manifest.permission.CONFIGURE_DISPLAY_COLOR_MODE;

import android.annotation.IntDef;
import android.annotation.Nullable;
import android.annotation.RequiresPermission;
import android.annotation.TestApi;
import android.annotation.UnsupportedAppUsage;
import android.app.KeyguardManager;
import android.content.res.CompatibilityInfo;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.graphics.ColorSpace;
import android.graphics.PixelFormat;
import android.graphics.Point;
import android.graphics.Rect;
import android.hardware.display.DisplayManager;
import android.hardware.display.DisplayManagerGlobal;
import android.os.Build;
import android.os.Parcel;
import android.os.Parcelable;
import android.os.Process;
import android.os.SystemClock;
import android.util.DisplayMetrics;
import android.util.Log;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.Arrays;

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
 * using (adb shell wm size).  Use the following methods to query the
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
    private final DisplayAddress mAddress;
    private final int mOwnerUid;
    private final String mOwnerPackageName;
    private final Resources mResources;
    private DisplayAdjustments mDisplayAdjustments;

    @UnsupportedAppUsage
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
     * Invalid display id.
     */
    public static final int INVALID_DISPLAY = -1;

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
     * Display flag: Indicates that the display is private.  Only the application that
     * owns the display and apps that are already on the display can create windows on it.
     *
     * @see #getFlags
     */
    public static final int FLAG_PRIVATE = 1 << 2;

    /**
     * Display flag: Indicates that the display is a presentation display.
     * <p>
     * This flag identifies secondary displays that are suitable for
     * use as presentation displays such as HDMI or Wireless displays.  Applications
     * may automatically project their content to presentation displays to provide
     * richer second screen experiences.
     * </p>
     *
     * @see #getFlags
     */
    public static final int FLAG_PRESENTATION = 1 << 3;

    /**
     * Display flag: Indicates that the display has a round shape.
     * <p>
     * This flag identifies displays that are circular, elliptical or otherwise
     * do not permit the user to see all the way to the logical corners of the display.
     * </p>
     *
     * @see #getFlags
     */
    public static final int FLAG_ROUND = 1 << 4;

    /**
     * Display flag: Indicates that the display can show its content when non-secure keyguard is
     * shown.
     * <p>
     * This flag identifies secondary displays that will continue showing content if keyguard can be
     * dismissed without entering credentials.
     * </p><p>
     * An example of usage is a virtual display which content is displayed on external hardware
     * display that is not visible to the system directly.
     * </p>
     *
     * @see DisplayManager#VIRTUAL_DISPLAY_FLAG_CAN_SHOW_WITH_INSECURE_KEYGUARD
     * @see KeyguardManager#isDeviceSecure()
     * @see KeyguardManager#isDeviceLocked()
     * @see #getFlags
     * @hide
     */
    // TODO (b/114338689): Remove the flag and use IWindowManager#shouldShowWithInsecureKeyguard
    public static final int FLAG_CAN_SHOW_WITH_INSECURE_KEYGUARD = 1 << 5;

    /**
     * Display flag: Indicates that the display should show system decorations.
     * <p>
     * This flag identifies secondary displays that should show system decorations, such as status
     * bar, navigation bar, home activity or IME.
     * </p>
     *
     * @see #supportsSystemDecorations
     * @hide
     */
    // TODO (b/114338689): Remove the flag and use IWindowManager#setShouldShowSystemDecors
    public static final int FLAG_SHOULD_SHOW_SYSTEM_DECORATIONS = 1 << 6;

    /**
     * Display flag: Indicates that the contents of the display should not be scaled
     * to fit the physical screen dimensions.  Used for development only to emulate
     * devices with smaller physicals screens while preserving density.
     *
     * @hide
     */
    public static final int FLAG_SCALING_DISABLED = 1 << 30;

    /**
     * Display type: Unknown display type.
     * @hide
     */
    @UnsupportedAppUsage
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
    @UnsupportedAppUsage
    public static final int TYPE_HDMI = 2;

    /**
     * Display type: WiFi display.
     * @hide
     */
    @UnsupportedAppUsage
    public static final int TYPE_WIFI = 3;

    /**
     * Display type: Overlay display.
     * @hide
     */
    public static final int TYPE_OVERLAY = 4;

    /**
     * Display type: Virtual display.
     * @hide
     */
    @UnsupportedAppUsage
    public static final int TYPE_VIRTUAL = 5;

    /**
     * Display state: The display state is unknown.
     *
     * @see #getState
     */
    public static final int STATE_UNKNOWN = ViewProtoEnums.DISPLAY_STATE_UNKNOWN; // 0

    /**
     * Display state: The display is off.
     *
     * @see #getState
     */
    public static final int STATE_OFF = ViewProtoEnums.DISPLAY_STATE_OFF; // 1

    /**
     * Display state: The display is on.
     *
     * @see #getState
     */
    public static final int STATE_ON = ViewProtoEnums.DISPLAY_STATE_ON; // 2

    /**
     * Display state: The display is dozing in a low power state; it is still
     * on but is optimized for showing system-provided content while the
     * device is non-interactive.
     *
     * @see #getState
     * @see android.os.PowerManager#isInteractive
     */
    public static final int STATE_DOZE = ViewProtoEnums.DISPLAY_STATE_DOZE; // 3

    /**
     * Display state: The display is dozing in a suspended low power state; it is still
     * on but the CPU is not updating it. This may be used in one of two ways: to show
     * static system-provided content while the device is non-interactive, or to allow
     * a "Sidekick" compute resource to update the display. For this reason, the
     * CPU must not control the display in this mode.
     *
     * @see #getState
     * @see android.os.PowerManager#isInteractive
     */
    public static final int STATE_DOZE_SUSPEND = ViewProtoEnums.DISPLAY_STATE_DOZE_SUSPEND; // 4

    /**
     * Display state: The display is on and optimized for VR mode.
     *
     * @see #getState
     * @see android.os.PowerManager#isInteractive
     */
    public static final int STATE_VR = ViewProtoEnums.DISPLAY_STATE_VR; // 5

    /**
     * Display state: The display is in a suspended full power state; it is still
     * on but the CPU is not updating it. This may be used in one of two ways: to show
     * static system-provided content while the device is non-interactive, or to allow
     * a "Sidekick" compute resource to update the display. For this reason, the
     * CPU must not control the display in this mode.
     *
     * @see #getState
     * @see android.os.PowerManager#isInteractive
     */
    public static final int STATE_ON_SUSPEND = ViewProtoEnums.DISPLAY_STATE_ON_SUSPEND; // 6

    /* The color mode constants defined below must be kept in sync with the ones in
     * system/core/include/system/graphics-base.h */

    /**
     * Display color mode: The current color mode is unknown or invalid.
     * @hide
     */
    public static final int COLOR_MODE_INVALID = -1;

    /**
     * Display color mode: The default or native gamut of the display.
     * @hide
     */
    public static final int COLOR_MODE_DEFAULT = 0;

    /** @hide */
    public static final int COLOR_MODE_BT601_625 = 1;
    /** @hide */
    public static final int COLOR_MODE_BT601_625_UNADJUSTED = 2;
    /** @hide */
    public static final int COLOR_MODE_BT601_525 = 3;
    /** @hide */
    public static final int COLOR_MODE_BT601_525_UNADJUSTED = 4;
    /** @hide */
    public static final int COLOR_MODE_BT709 = 5;
    /** @hide */
    public static final int COLOR_MODE_DCI_P3 = 6;
    /** @hide */
    public static final int COLOR_MODE_SRGB = 7;
    /** @hide */
    public static final int COLOR_MODE_ADOBE_RGB = 8;
    /** @hide */
    public static final int COLOR_MODE_DISPLAY_P3 = 9;

    /**
     * Indicates that when display is removed, all its activities will be moved to the primary
     * display and the topmost activity should become focused.
     *
     * @hide
     */
    // TODO (b/114338689): Remove the flag and use WindowManager#REMOVE_CONTENT_MODE_MOVE_TO_PRIMARY
    public static final int REMOVE_MODE_MOVE_CONTENT_TO_PRIMARY = 0;
    /**
     * Indicates that when display is removed, all its stacks and tasks will be removed, all
     * activities will be destroyed according to the usual lifecycle.
     *
     * @hide
     */
    // TODO (b/114338689): Remove the flag and use WindowManager#REMOVE_CONTENT_MODE_DESTROY
    public static final int REMOVE_MODE_DESTROY_CONTENT = 1;

    /**
     * Internal method to create a display.
     * The display created with this method will have a static {@link DisplayAdjustments} applied.
     * Applications should use {@link android.view.WindowManager#getDefaultDisplay()}
     * or {@link android.hardware.display.DisplayManager#getDisplay}
     * to get a display object.
     *
     * @hide
     */
    public Display(DisplayManagerGlobal global, int displayId, /*@NotNull*/ DisplayInfo displayInfo,
            DisplayAdjustments daj) {
        this(global, displayId, displayInfo, daj, null /*res*/);
    }

    /**
     * Internal method to create a display.
     * The display created with this method will be adjusted based on the adjustments in the
     * supplied {@link Resources}.
     *
     * @hide
     */
    public Display(DisplayManagerGlobal global, int displayId, /*@NotNull*/ DisplayInfo displayInfo,
            Resources res) {
        this(global, displayId, displayInfo, null /*daj*/, res);
    }

    private Display(DisplayManagerGlobal global, int displayId,
            /*@NotNull*/ DisplayInfo displayInfo, DisplayAdjustments daj, Resources res) {
        mGlobal = global;
        mDisplayId = displayId;
        mDisplayInfo = displayInfo;
        mResources = res;
        mDisplayAdjustments = mResources != null
            ? new DisplayAdjustments(mResources.getConfiguration())
            : daj != null ? new DisplayAdjustments(daj) : null;
        mIsValid = true;

        // Cache properties that cannot change as long as the display is valid.
        mLayerStack = displayInfo.layerStack;
        mFlags = displayInfo.flags;
        mType = displayInfo.type;
        mAddress = displayInfo.address;
        mOwnerUid = displayInfo.ownerUid;
        mOwnerPackageName = displayInfo.ownerPackageName;
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
     * Gets the display unique id.
     * <p>
     * Unique id is different from display id because physical displays have stable unique id across
     * reboots.
     *
     * @see com.android.service.display.DisplayDevice#hasStableUniqueId().
     * @hide
     */
    public String getUniqueId() {
        return mDisplayInfo.uniqueId;
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
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.P)
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
     * @see #FLAG_PRIVATE
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
     * @see #TYPE_VIRTUAL
     * @hide
     */
    @UnsupportedAppUsage
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
    @UnsupportedAppUsage
    public DisplayAddress getAddress() {
        return mAddress;
    }

    /**
     * Gets the UID of the application that owns this display, or zero if it is
     * owned by the system.
     * <p>
     * If the display is private, then only the owner can use it.
     * </p>
     *
     * @hide
     */
    public int getOwnerUid() {
        return mOwnerUid;
    }

    /**
     * Gets the package name of the application that owns this display, or null if it is
     * owned by the system.
     * <p>
     * If the display is private, then only the owner can use it.
     * </p>
     *
     * @hide
     */
    @UnsupportedAppUsage
    public String getOwnerPackageName() {
        return mOwnerPackageName;
    }

    /**
     * Gets the compatibility info used by this display instance.
     *
     * @return The display adjustments holder, or null if none is required.
     * @hide
     */
    @UnsupportedAppUsage
    public DisplayAdjustments getDisplayAdjustments() {
        if (mResources != null) {
            final DisplayAdjustments currentAdjustements = mResources.getDisplayAdjustments();
            if (!mDisplayAdjustments.equals(currentAdjustements)) {
                mDisplayAdjustments = new DisplayAdjustments(currentAdjustements);
            }
        }

        return mDisplayAdjustments;
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
     * Value returned by this method does not necessarily represent the actual raw size
     * (native resolution) of the display.
     * <p>
     * 1. The returned size may be adjusted to exclude certain system decor elements
     * that are always visible.
     * </p><p>
     * 2. It may be scaled to provide compatibility with older applications that
     * were originally designed for smaller displays.
     * </p><p>
     * 3. It can be different depending on the WindowManager to which the display belongs.
     * </p><p>
     * - If requested from non-Activity context (e.g. Application context via
     * {@code (WindowManager) getApplicationContext().getSystemService(Context.WINDOW_SERVICE)})
     * it will report the size of the entire display based on current rotation and with subtracted
     * system decoration areas.
     * </p><p>
     * - If requested from activity (either using {@code getWindowManager()} or
     * {@code (WindowManager) getSystemService(Context.WINDOW_SERVICE)}) resulting size will
     * correspond to current app window size. In this case it can be smaller than physical size in
     * multi-window mode.
     * </p><p>
     * Typically for the purposes of layout apps should make a request from activity context
     * to obtain size available for the app content.
     * </p>
     *
     * @param outSize A {@link Point} object to receive the size information.
     */
    public void getSize(Point outSize) {
        synchronized (this) {
            updateDisplayInfoLocked();
            mDisplayInfo.getAppMetrics(mTempMetrics, getDisplayAdjustments());
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
            mDisplayInfo.getAppMetrics(mTempMetrics, getDisplayAdjustments());
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
    @UnsupportedAppUsage
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
    @Surface.Rotation
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
    @Surface.Rotation
    public int getOrientation() {
        return getRotation();
    }


    /**
     * Returns the {@link DisplayCutout}, or {@code null} if there is none.
     *
     * @see DisplayCutout
     */
    @Nullable
    public DisplayCutout getCutout() {
        synchronized (this) {
            updateDisplayInfoLocked();
            return mDisplayInfo.displayCutout;
        }
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
            return mDisplayInfo.getMode().getRefreshRate();
        }
    }

    /**
     * Get the supported refresh rates of this display in frames per second.
     * <p>
     * This method only returns refresh rates for the display's default modes. For more options, use
     * {@link #getSupportedModes()}.
     *
     * @deprecated use {@link #getSupportedModes()} instead
     */
    @Deprecated
    public float[] getSupportedRefreshRates() {
        synchronized (this) {
            updateDisplayInfoLocked();
            return mDisplayInfo.getDefaultRefreshRates();
        }
    }

    /**
     * Returns the active mode of the display.
     */
    public Mode getMode() {
        synchronized (this) {
            updateDisplayInfoLocked();
            return mDisplayInfo.getMode();
        }
    }

    /**
     * Gets the supported modes of this display.
     */
    public Mode[] getSupportedModes() {
        synchronized (this) {
            updateDisplayInfoLocked();
            final Display.Mode[] modes = mDisplayInfo.supportedModes;
            return Arrays.copyOf(modes, modes.length);
        }
    }

    /**
     * Request the display applies a color mode.
     * @hide
     */
    @RequiresPermission(CONFIGURE_DISPLAY_COLOR_MODE)
    public void requestColorMode(int colorMode) {
        mGlobal.requestColorMode(mDisplayId, colorMode);
    }

    /**
     * Returns the active color mode of this display
     * @hide
     */
    public int getColorMode() {
        synchronized (this) {
            updateDisplayInfoLocked();
            return mDisplayInfo.colorMode;
        }
    }

    /**
     * @hide
     * Get current remove mode of the display - what actions should be performed with the display's
     * content when it is removed. Default behavior for public displays in this case is to move all
     * activities to the primary display and make it focused. For private display - destroy all
     * activities.
     *
     * @see #REMOVE_MODE_MOVE_CONTENT_TO_PRIMARY
     * @see #REMOVE_MODE_DESTROY_CONTENT
     */
    // TODO (b/114338689): Remove the method and use IWindowManager#getRemoveContentMode
    public int getRemoveMode() {
        return mDisplayInfo.removeMode;
    }

    /**
     * Returns whether this display should support showing system decorations.
     *
     * @see #FLAG_SHOULD_SHOW_SYSTEM_DECORATIONS
     * @hide
     */
    @TestApi
    // TODO (b/114338689): Remove the method and use IWindowManager#shouldShowSystemDecors
    public boolean supportsSystemDecorations() {
        return (mDisplayInfo.flags & FLAG_SHOULD_SHOW_SYSTEM_DECORATIONS) != 0;
    }

    /**
     * Returns the display's HDR capabilities.
     *
     * @see #isHdr()
     */
    public HdrCapabilities getHdrCapabilities() {
        synchronized (this) {
            updateDisplayInfoLocked();
            return mDisplayInfo.hdrCapabilities;
        }
    }

    /**
     * Returns whether this display supports any HDR type.
     *
     * @see #getHdrCapabilities()
     * @see HdrCapabilities#getSupportedHdrTypes()
     */
    public boolean isHdr() {
        synchronized (this) {
            updateDisplayInfoLocked();
            return mDisplayInfo.isHdr();
        }
    }

    /**
     * Returns whether this display can be used to display wide color gamut content.
     * This does not necessarily mean the device itself can render wide color gamut
     * content. To ensure wide color gamut content can be produced, refer to
     * {@link Configuration#isScreenWideColorGamut()}.
     */
    public boolean isWideColorGamut() {
        synchronized (this) {
            updateDisplayInfoLocked();
            return mDisplayInfo.isWideColorGamut();
        }
    }

    /**
     * Returns the preferred wide color space of the Display.
     * The returned wide gamut color space is based on hardware capability and
     * is preferred by the composition pipeline.
     * Returns null if the display doesn't support wide color gamut.
     * {@link Display#isWideColorGamut()}.
     */
    @Nullable
    public ColorSpace getPreferredWideGamutColorSpace() {
        synchronized (this) {
            updateDisplayInfoLocked();
            if (mDisplayInfo.isWideColorGamut()) {
                return mGlobal.getPreferredWideGamutColorSpace();
            }
            return null;
        }
    }

    /**
     * Gets the supported color modes of this device.
     * @hide
     */
    public int[] getSupportedColorModes() {
        synchronized (this) {
            updateDisplayInfoLocked();
            int[] colorModes = mDisplayInfo.supportedColorModes;
            return Arrays.copyOf(colorModes, colorModes.length);
        }
    }

    /**
     * Gets the app VSYNC offset, in nanoseconds.  This is a positive value indicating
     * the phase offset of the VSYNC events provided by Choreographer relative to the
     * display refresh.  For example, if Choreographer reports that the refresh occurred
     * at time N, it actually occurred at (N - appVsyncOffset).
     * <p>
     * Apps generally do not need to be aware of this.  It's only useful for fine-grained
     * A/V synchronization.
     */
    public long getAppVsyncOffsetNanos() {
        synchronized (this) {
            updateDisplayInfoLocked();
            return mDisplayInfo.appVsyncOffsetNanos;
        }
    }

    /**
     * This is how far in advance a buffer must be queued for presentation at
     * a given time.  If you want a buffer to appear on the screen at
     * time N, you must submit the buffer before (N - presentationDeadline).
     * <p>
     * The desired presentation time for GLES rendering may be set with
     * {@link android.opengl.EGLExt#eglPresentationTimeANDROID}.  For video decoding, use
     * {@link android.media.MediaCodec#releaseOutputBuffer(int, long)}.  Times are
     * expressed in nanoseconds, using the system monotonic clock
     * ({@link System#nanoTime}).
     */
    public long getPresentationDeadlineNanos() {
        synchronized (this) {
            updateDisplayInfoLocked();
            return mDisplayInfo.presentationDeadlineNanos;
        }
    }

    /**
     * Gets display metrics that describe the size and density of this display.
     * The size returned by this method does not necessarily represent the
     * actual raw size (native resolution) of the display.
     * <p>
     * 1. The returned size may be adjusted to exclude certain system decor elements
     * that are always visible.
     * </p><p>
     * 2. It may be scaled to provide compatibility with older applications that
     * were originally designed for smaller displays.
     * </p><p>
     * 3. It can be different depending on the WindowManager to which the display belongs.
     * </p><p>
     * - If requested from non-Activity context (e.g. Application context via
     * {@code (WindowManager) getApplicationContext().getSystemService(Context.WINDOW_SERVICE)})
     * metrics will report the size of the entire display based on current rotation and with
     * subtracted system decoration areas.
     * </p><p>
     * - If requested from activity (either using {@code getWindowManager()} or
     * {@code (WindowManager) getSystemService(Context.WINDOW_SERVICE)}) resulting metrics will
     * correspond to current app window metrics. In this case the size can be smaller than physical
     * size in multi-window mode.
     * </p>
     *
     * @param outMetrics A {@link DisplayMetrics} object to receive the metrics.
     */
    public void getMetrics(DisplayMetrics outMetrics) {
        synchronized (this) {
            updateDisplayInfoLocked();
            mDisplayInfo.getAppMetrics(outMetrics, getDisplayAdjustments());
        }
    }

    /**
     * Gets the real size of the display without subtracting any window decor or
     * applying any compatibility scale factors.
     * <p>
     * The size is adjusted based on the current rotation of the display.
     * </p><p>
     * The real size may be smaller than the physical size of the screen when the
     * window manager is emulating a smaller display (using adb shell wm size).
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
     * window manager is emulating a smaller display (using adb shell wm size).
     * </p>
     *
     * @param outMetrics A {@link DisplayMetrics} object to receive the metrics.
     */
    public void getRealMetrics(DisplayMetrics outMetrics) {
        synchronized (this) {
            updateDisplayInfoLocked();
            mDisplayInfo.getLogicalMetrics(outMetrics,
                    CompatibilityInfo.DEFAULT_COMPATIBILITY_INFO, null);
        }
    }

    /**
     * Gets the state of the display, such as whether it is on or off.
     *
     * @return The state of the display: one of {@link #STATE_OFF}, {@link #STATE_ON},
     * {@link #STATE_DOZE}, {@link #STATE_DOZE_SUSPEND}, {@link #STATE_ON_SUSPEND}, or
     * {@link #STATE_UNKNOWN}.
     */
    public int getState() {
        synchronized (this) {
            updateDisplayInfoLocked();
            return mIsValid ? mDisplayInfo.state : STATE_UNKNOWN;
        }
    }

    /**
     * Returns true if the specified UID has access to this display.
     * @hide
     */
    public boolean hasAccess(int uid) {
        return Display.hasAccess(uid, mFlags, mOwnerUid);
    }

    /** @hide */
    public static boolean hasAccess(int uid, int flags, int ownerUid) {
        return (flags & Display.FLAG_PRIVATE) == 0
                || uid == ownerUid
                || uid == Process.SYSTEM_UID
                || uid == 0;
    }

    /**
     * Returns true if the display is a public presentation display.
     * @hide
     */
    public boolean isPublicPresentation() {
        return (mFlags & (Display.FLAG_PRIVATE | Display.FLAG_PRESENTATION)) ==
                Display.FLAG_PRESENTATION;
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
            mDisplayInfo.getAppMetrics(mTempMetrics, getDisplayAdjustments());
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
            mDisplayInfo.getAppMetrics(mTempMetrics, getDisplayAdjustments());
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
            case TYPE_VIRTUAL:
                return "VIRTUAL";
            default:
                return Integer.toString(type);
        }
    }

    /**
     * @hide
     */
    public static String stateToString(int state) {
        switch (state) {
            case STATE_UNKNOWN:
                return "UNKNOWN";
            case STATE_OFF:
                return "OFF";
            case STATE_ON:
                return "ON";
            case STATE_DOZE:
                return "DOZE";
            case STATE_DOZE_SUSPEND:
                return "DOZE_SUSPEND";
            case STATE_VR:
                return "VR";
            case STATE_ON_SUSPEND:
                return "ON_SUSPEND";
            default:
                return Integer.toString(state);
        }
    }

    /**
     * Returns true if display updates may be suspended while in the specified
     * display power state. In SUSPEND states, updates are absolutely forbidden.
     * @hide
     */
    public static boolean isSuspendedState(int state) {
        return state == STATE_OFF || state == STATE_DOZE_SUSPEND || state == STATE_ON_SUSPEND;
    }

    /**
     * Returns true if the display may be in a reduced operating mode while in the
     * specified display power state.
     * @hide
     */
    public static boolean isDozeState(int state) {
        return state == STATE_DOZE || state == STATE_DOZE_SUSPEND;
    }

    /**
     * A mode supported by a given display.
     *
     * @see Display#getSupportedModes()
     */
    public static final class Mode implements Parcelable {
        /**
         * @hide
         */
        public static final Mode[] EMPTY_ARRAY = new Mode[0];

        private final int mModeId;
        private final int mWidth;
        private final int mHeight;
        private final float mRefreshRate;

        /**
         * @hide
         */
        @UnsupportedAppUsage
        public Mode(int modeId, int width, int height, float refreshRate) {
            mModeId = modeId;
            mWidth = width;
            mHeight = height;
            mRefreshRate = refreshRate;
        }

        /**
         * Returns this mode's id.
         */
        public int getModeId() {
            return mModeId;
        }

        /**
         * Returns the physical width of the display in pixels when configured in this mode's
         * resolution.
         * <p>
         * Note that due to application UI scaling, the number of pixels made available to
         * applications when the mode is active (as reported by {@link Display#getWidth()} may
         * differ from the mode's actual resolution (as reported by this function).
         * <p>
         * For example, applications running on a 4K display may have their UI laid out and rendered
         * in 1080p and then scaled up. Applications can take advantage of the extra resolution by
         * rendering content through a {@link android.view.SurfaceView} using full size buffers.
         */
        public int getPhysicalWidth() {
            return mWidth;
        }

        /**
         * Returns the physical height of the display in pixels when configured in this mode's
         * resolution.
         * <p>
         * Note that due to application UI scaling, the number of pixels made available to
         * applications when the mode is active (as reported by {@link Display#getHeight()} may
         * differ from the mode's actual resolution (as reported by this function).
         * <p>
         * For example, applications running on a 4K display may have their UI laid out and rendered
         * in 1080p and then scaled up. Applications can take advantage of the extra resolution by
         * rendering content through a {@link android.view.SurfaceView} using full size buffers.
         */
        public int getPhysicalHeight() {
            return mHeight;
        }

        /**
         * Returns the refresh rate in frames per second.
         */
        public float getRefreshRate() {
            return mRefreshRate;
        }

        /**
         * Returns {@code true} if this mode matches the given parameters.
         *
         * @hide
         */
        public boolean matches(int width, int height, float refreshRate) {
            return mWidth == width &&
                    mHeight == height &&
                    Float.floatToIntBits(mRefreshRate) == Float.floatToIntBits(refreshRate);
        }

        @Override
        public boolean equals(Object other) {
            if (this == other) {
                return true;
            }
            if (!(other instanceof Mode)) {
                return false;
            }
            Mode that = (Mode) other;
            return mModeId == that.mModeId && matches(that.mWidth, that.mHeight, that.mRefreshRate);
        }

        @Override
        public int hashCode() {
            int hash = 1;
            hash = hash * 17 + mModeId;
            hash = hash * 17 + mWidth;
            hash = hash * 17 + mHeight;
            hash = hash * 17 + Float.floatToIntBits(mRefreshRate);
            return hash;
        }

        @Override
        public String toString() {
            return new StringBuilder("{")
                    .append("id=").append(mModeId)
                    .append(", width=").append(mWidth)
                    .append(", height=").append(mHeight)
                    .append(", fps=").append(mRefreshRate)
                    .append("}")
                    .toString();
        }

        @Override
        public int describeContents() {
            return 0;
        }

        private Mode(Parcel in) {
            this(in.readInt(), in.readInt(), in.readInt(), in.readFloat());
        }

        @Override
        public void writeToParcel(Parcel out, int parcelableFlags) {
            out.writeInt(mModeId);
            out.writeInt(mWidth);
            out.writeInt(mHeight);
            out.writeFloat(mRefreshRate);
        }

        @SuppressWarnings("hiding")
        public static final Parcelable.Creator<Mode> CREATOR
                = new Parcelable.Creator<Mode>() {
            @Override
            public Mode createFromParcel(Parcel in) {
                return new Mode(in);
            }

            @Override
            public Mode[] newArray(int size) {
                return new Mode[size];
            }
        };
    }

    /**
     * Encapsulates the HDR capabilities of a given display.
     * For example, what HDR types it supports and details about the desired luminance data.
     * <p>You can get an instance for a given {@link Display} object with
     * {@link Display#getHdrCapabilities getHdrCapabilities()}.
     */
    public static final class HdrCapabilities implements Parcelable {
        /**
         * Invalid luminance value.
         */
        public static final float INVALID_LUMINANCE = -1;
        /**
         * Dolby Vision high dynamic range (HDR) display.
         */
        public static final int HDR_TYPE_DOLBY_VISION = 1;
        /**
         * HDR10 display.
         */
        public static final int HDR_TYPE_HDR10 = 2;
        /**
         * Hybrid Log-Gamma HDR display.
         */
        public static final int HDR_TYPE_HLG = 3;

        /**
         * HDR10+ display.
         */
        public static final int HDR_TYPE_HDR10_PLUS = 4;

        /** @hide */
        @IntDef(prefix = { "HDR_TYPE_" }, value = {
                HDR_TYPE_DOLBY_VISION,
                HDR_TYPE_HDR10,
                HDR_TYPE_HLG,
                HDR_TYPE_HDR10_PLUS,
        })
        @Retention(RetentionPolicy.SOURCE)
        public @interface HdrType {}

        private @HdrType int[] mSupportedHdrTypes = new int[0];
        private float mMaxLuminance = INVALID_LUMINANCE;
        private float mMaxAverageLuminance = INVALID_LUMINANCE;
        private float mMinLuminance = INVALID_LUMINANCE;

        /**
         * @hide
         */
        public HdrCapabilities() {
        }

        /**
         * @hide
         */
        @UnsupportedAppUsage
        public HdrCapabilities(int[] supportedHdrTypes, float maxLuminance,
                float maxAverageLuminance, float minLuminance) {
            mSupportedHdrTypes = supportedHdrTypes;
            mMaxLuminance = maxLuminance;
            mMaxAverageLuminance = maxAverageLuminance;
            mMinLuminance = minLuminance;
        }

        /**
         * Gets the supported HDR types of this display.
         * Returns empty array if HDR is not supported by the display.
         */
        public @HdrType int[] getSupportedHdrTypes() {
            return mSupportedHdrTypes;
        }
        /**
         * Returns the desired content max luminance data in cd/m2 for this display.
         */
        public float getDesiredMaxLuminance() {
            return mMaxLuminance;
        }
        /**
         * Returns the desired content max frame-average luminance data in cd/m2 for this display.
         */
        public float getDesiredMaxAverageLuminance() {
            return mMaxAverageLuminance;
        }
        /**
         * Returns the desired content min luminance data in cd/m2 for this display.
         */
        public float getDesiredMinLuminance() {
            return mMinLuminance;
        }

        @Override
        public boolean equals(Object other) {
            if (this == other) {
                return true;
            }

            if (!(other instanceof HdrCapabilities)) {
                return false;
            }
            HdrCapabilities that = (HdrCapabilities) other;

            return Arrays.equals(mSupportedHdrTypes, that.mSupportedHdrTypes)
                && mMaxLuminance == that.mMaxLuminance
                && mMaxAverageLuminance == that.mMaxAverageLuminance
                && mMinLuminance == that.mMinLuminance;
        }

        @Override
        public int hashCode() {
            int hash = 23;
            hash = hash * 17 + Arrays.hashCode(mSupportedHdrTypes);
            hash = hash * 17 + Float.floatToIntBits(mMaxLuminance);
            hash = hash * 17 + Float.floatToIntBits(mMaxAverageLuminance);
            hash = hash * 17 + Float.floatToIntBits(mMinLuminance);
            return hash;
        }

        public static final Creator<HdrCapabilities> CREATOR = new Creator<HdrCapabilities>() {
            @Override
            public HdrCapabilities createFromParcel(Parcel source) {
                return new HdrCapabilities(source);
            }

            @Override
            public HdrCapabilities[] newArray(int size) {
                return new HdrCapabilities[size];
            }
        };

        private HdrCapabilities(Parcel source) {
            readFromParcel(source);
        }

        /**
         * @hide
         */
        public void readFromParcel(Parcel source) {
            int types = source.readInt();
            mSupportedHdrTypes = new int[types];
            for (int i = 0; i < types; ++i) {
                mSupportedHdrTypes[i] = source.readInt();
            }
            mMaxLuminance = source.readFloat();
            mMaxAverageLuminance = source.readFloat();
            mMinLuminance = source.readFloat();
        }

        @Override
        public void writeToParcel(Parcel dest, int flags) {
            dest.writeInt(mSupportedHdrTypes.length);
            for (int i = 0; i < mSupportedHdrTypes.length; ++i) {
                dest.writeInt(mSupportedHdrTypes[i]);
            }
            dest.writeFloat(mMaxLuminance);
            dest.writeFloat(mMaxAverageLuminance);
            dest.writeFloat(mMinLuminance);
        }

        @Override
        public int describeContents() {
            return 0;
        }
    }
}
