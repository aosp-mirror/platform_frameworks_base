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
import static android.Manifest.permission.CONTROL_DISPLAY_BRIGHTNESS;
import static android.hardware.flags.Flags.FLAG_OVERLAYPROPERTIES_CLASS_API;

import android.Manifest;
import android.annotation.FlaggedApi;
import android.annotation.IntDef;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.RequiresPermission;
import android.annotation.SuppressLint;
import android.annotation.TestApi;
import android.app.ActivityThread;
import android.app.KeyguardManager;
import android.app.WindowConfiguration;
import android.compat.annotation.UnsupportedAppUsage;
import android.content.res.CompatibilityInfo;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.graphics.ColorSpace;
import android.graphics.PixelFormat;
import android.graphics.Point;
import android.graphics.Rect;
import android.hardware.OverlayProperties;
import android.hardware.display.BrightnessInfo;
import android.hardware.display.DeviceProductInfo;
import android.hardware.display.DisplayManager;
import android.hardware.display.DisplayManagerGlobal;
import android.hardware.graphics.common.DisplayDecorationSupport;
import android.os.Build;
import android.os.Parcel;
import android.os.Parcelable;
import android.os.Process;
import android.os.SystemClock;
import android.util.ArraySet;
import android.util.DisplayMetrics;
import android.util.Log;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.function.Consumer;

/**
 * Provides information about the size and density of a logical display.
 * <p>
 * The display area is described in two different ways.
 * <ul>
 * <li>The application display area specifies the part of the display that may contain
 * an application window, excluding the system decorations.  The application display area may
 * be smaller than the real display area because the system subtracts the space needed
 * for decor elements such as the status bar.  Use {@link WindowMetrics#getBounds()} to query the
 * application window bounds.</li>
 * <li>The real display area specifies the part of the display that is accessible to an application
 * in the current system state. The real display area may be smaller than the physical size of the
 * display in a few scenarios. Use {@link WindowManager#getCurrentWindowMetrics()} to identify the
 * current size of the activity window. UI-related work, such as choosing UI layouts, should rely
 * upon {@link WindowMetrics#getBounds()}. See {@link #getRealSize} / {@link #getRealMetrics} for
 * details.</li>
 * </ul>
 * </p><p>
 * A logical display does not necessarily represent a particular physical display device
 * such as the internal display or an external display.  The contents of a logical
 * display may be presented on one or more physical displays according to the devices
 * that are currently attached and whether mirroring has been enabled.
 * </p>
 */
@android.ravenwood.annotation.RavenwoodKeepPartialClass
public final class Display {
    private static final String TAG = "Display";
    private static final boolean DEBUG = false;

    private final Object mLock = new Object();
    private final DisplayManagerGlobal mGlobal;
    private final int mDisplayId;
    private final int mFlags;
    private final int mType;
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
    // Applications should now be using WindowMetrics instead.
    private static final int CACHED_APP_SIZE_DURATION_MILLIS = 20;
    private long mLastCachedAppSizeUpdate;
    private int mCachedAppWidthCompat;
    private int mCachedAppHeightCompat;

    private ArrayList<HdrSdrRatioListenerWrapper> mHdrSdrRatioListeners = new ArrayList<>();

    /**
     * The default Display id, which is the id of the primary display assuming there is one.
     */
    public static final int DEFAULT_DISPLAY = 0;

    /**
     * Invalid display id.
     */
    public static final int INVALID_DISPLAY = -1;

    /**
     * Invalid resolution width.
     * @hide
     */
    public static final int INVALID_DISPLAY_WIDTH = -1;

    /**
     * Invalid resolution height.
     * @hide
     */
    public static final int INVALID_DISPLAY_HEIGHT = -1;

    /**
     * Invalid refresh rate.
     * @hide
     */
    public static final float INVALID_DISPLAY_REFRESH_RATE = 0.0f;

    /**
     * The default display group id, which is the display group id of the primary display assuming
     * there is one.
     * @hide
     */
    public static final int DEFAULT_DISPLAY_GROUP = 0;

    /**
     * Invalid display group id.
     * @hide
     */
    public static final int INVALID_DISPLAY_GROUP = -1;

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
     * use as presentation displays such as external or wireless displays.  Applications
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
     * This flag identifies secondary displays that should show system decorations, such as
     * navigation bar, home activity or wallpaper.
     * </p>
     * <p>Note that this flag doesn't work without {@link #FLAG_TRUSTED}</p>
     *
     * @see #getFlags()
     * @hide
     */
    public static final int FLAG_SHOULD_SHOW_SYSTEM_DECORATIONS = 1 << 6;

    /**
     * Flag: The display is trusted to show system decorations and receive inputs without users'
     * touch.
     * @see #FLAG_SHOULD_SHOW_SYSTEM_DECORATIONS
     *
     * @see #getFlags()
     * @hide
     */
    @TestApi
    public static final int FLAG_TRUSTED = 1 << 7;

    /**
     * Flag: Indicates that the display should not be a part of the default DisplayGroup and
     * instead be part of a new DisplayGroup.
     *
     * @hide
     * @see #getFlags()
     */
    public static final int FLAG_OWN_DISPLAY_GROUP = 1 << 8;

    /**
     * Flag: Indicates that the display should always be unlocked. Only valid on virtual displays
     * that aren't in the default display group.
     *
     * @hide
     * @see #getFlags()
     */
    public static final int FLAG_ALWAYS_UNLOCKED = 1 << 9;

    /**
     * Flag: Indicates that the display should not play sound effects or perform haptic feedback
     * when the user touches the screen.
     *
     * @hide
     * @see #getFlags()
     */
    public static final int FLAG_TOUCH_FEEDBACK_DISABLED = 1 << 10;

    /**
     * Flag: Indicates that the display maintains its own focus and touch mode.
     *
     * This flag is similar to {@link com.android.internal.R.bool.config_perDisplayFocusEnabled} in
     * behavior, but only applies to the specific display instead of system-wide to all displays.
     *
     * Note: The display must be trusted in order to have its own focus.
     *
     * @see #FLAG_TRUSTED
     * @hide
     */
    public static final int FLAG_OWN_FOCUS = 1 << 11;

    /**
     * Flag: Indicates that the display should not become the top focused display by stealing the
     * top focus from another display.
     *
     * <p>The result is that only targeted input events (displayId of input event matches the
     * displayId of the display) can reach this display. A display with this flag set can still
     * become the top focused display, if the system consists of only one display or if all
     * displays have this flag set. In both cases the default display becomes the top focused
     * display.
     *
     * <p>Note:  A display only has a focused window if either
     * - the display is the top focused display or
     * - the display manages its own focus (via {@link #FLAG_OWN_FOCUS})
     * - or all the displays manage their own focus (via {@code config_perDisplayFocusEnabled} flag)
     * If a display has no focused window, no input event is dispatched to it. Therefore this
     * flag is only useful together with {@link #FLAG_OWN_FOCUS} and will be
     * ignored if it is not set.
     *
     * <p>Note: The framework only supports IME on the top focused display (b/262520411). Therefore,
     * Enabling this flag on a display implicitly disables showing any IME. This is not intended
     * behavior but cannot be fixed until b/262520411 is implemented. If you need IME on display do
     * not set this flag.
     *
     * @hide
     * @see #getFlags()
     */
    public static final int FLAG_STEAL_TOP_FOCUS_DISABLED = 1 << 12;

    /**
     * Display flag: Indicates that the display is a rear display.
     * <p>
     * This flag identifies complementary displays that are facing away from the user.
     * </p>
     *
     * @hide
     * @see #getFlags()
     */
    public static final int FLAG_REAR = 1 << 13;

    /**
     * Display flag: Indicates that the orientation of this display is not fixed and is coupled to
     * the orientation of its content.
     *
     * @hide
     */
    public static final int FLAG_ROTATES_WITH_CONTENT = 1 << 14;

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
    @TestApi
    public static final int TYPE_UNKNOWN = 0;

    /**
     * Display type: Physical display connected through an internal port.
     * @hide
     */
    @TestApi
    public static final int TYPE_INTERNAL = 1;

    /**
     * Display type: Physical display connected through an external port.
     * @hide
     */
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.R, trackingBug = 170729553)
    @TestApi
    public static final int TYPE_EXTERNAL = 2;

    /**
     * Display type: WiFi display.
     * @hide
     */
    @UnsupportedAppUsage
    @TestApi
    public static final int TYPE_WIFI = 3;

    /**
     * Display type: Overlay display.
     * @hide
     */
    @TestApi
    public static final int TYPE_OVERLAY = 4;

    /**
     * Display type: Virtual display.
     * @hide
     */
    @UnsupportedAppUsage
    @TestApi
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

    /**
     * The cause of the display state change is unknown.
     *
     * @hide
     */
    public static final int STATE_REASON_UNKNOWN = ViewProtoEnums.DISPLAY_STATE_REASON_UNKNOWN;

    /**
     * The default power and display policy caused the display state.
     *
     * @hide
     */
    public static final int STATE_REASON_DEFAULT_POLICY =
            ViewProtoEnums.DISPLAY_STATE_REASON_DEFAULT_POLICY;

    /**
     * The display state was changed due to acquiring a draw wake lock.
     *
     * @hide
     */
    public static final int STATE_REASON_DRAW_WAKE_LOCK =
            ViewProtoEnums.DISPLAY_STATE_REASON_DRAW_WAKE_LOCK;

    /**
     * The display state was changed due to display offloading.
     *
     * @hide
     */
    public static final int STATE_REASON_OFFLOAD = ViewProtoEnums.DISPLAY_STATE_REASON_OFFLOAD;

    /**
     * The display state was changed due to a tilt event.
     *
     * @hide
     */
    public static final int STATE_REASON_TILT = ViewProtoEnums.DISPLAY_STATE_REASON_TILT;

    /**
     * The display state was changed due to the dream manager.
     *
     * @hide
     */
    public static final int STATE_REASON_DREAM_MANAGER =
            ViewProtoEnums.DISPLAY_STATE_REASON_DREAM_MANAGER;

    /**
     * The display state was changed due to a {@link KeyEvent}.
     *
     * @hide
     */
    public static final int STATE_REASON_KEY = ViewProtoEnums.DISPLAY_STATE_REASON_KEY;

    /**
     * The display state was changed due to a {@link MotionEvent}.
     *
     * @hide
     */
    public static final int STATE_REASON_MOTION = ViewProtoEnums.DISPLAY_STATE_REASON_MOTION;

    /** @hide */
    @IntDef(prefix = {"STATE_REASON_"}, value = {
        STATE_REASON_UNKNOWN,
        STATE_REASON_DEFAULT_POLICY,
        STATE_REASON_DRAW_WAKE_LOCK,
        STATE_REASON_OFFLOAD,
        STATE_REASON_TILT,
        STATE_REASON_DREAM_MANAGER,
        STATE_REASON_KEY,
        STATE_REASON_MOTION,
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface StateReason {}

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

    /** @hide **/
    @IntDef(prefix = {"COLOR_MODE_"}, value = {
            COLOR_MODE_INVALID,
            COLOR_MODE_DEFAULT,
            COLOR_MODE_BT601_625,
            COLOR_MODE_BT601_625_UNADJUSTED,
            COLOR_MODE_BT601_525,
            COLOR_MODE_BT601_525_UNADJUSTED,
            COLOR_MODE_BT709,
            COLOR_MODE_DCI_P3,
            COLOR_MODE_SRGB,
            COLOR_MODE_ADOBE_RGB,
            COLOR_MODE_DISPLAY_P3
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface ColorMode {}

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

    /** @hide */
    public static final int DISPLAY_MODE_ID_FOR_FRAME_RATE_OVERRIDE = 0xFF;

    /**
     * Internal method to create a display.
     * The display created with this method will have a static {@link DisplayAdjustments} applied.
     * Applications should use {@link android.content.Context#getDisplay} with
     * {@link android.app.Activity} or a context associated with a {@link Display} via
     * {@link android.content.Context#createDisplayContext(Display)}
     * to get a display object associated with a {@link android.app.Context}, or
     * {@link android.hardware.display.DisplayManager#getDisplay} to get a display object by id.
     *
     * @see android.content.Context#getDisplay()
     * @see android.content.Context#createDisplayContext(Display)
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
            : daj != null ? new DisplayAdjustments(daj) : new DisplayAdjustments();
        mIsValid = true;

        // Cache properties that cannot change as long as the display is valid.
        mFlags = displayInfo.flags;
        mType = displayInfo.type;
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
    @TestApi
    public @Nullable String getUniqueId() {
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
        synchronized (mLock) {
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
        synchronized (mLock) {
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
        synchronized (mLock) {
            updateDisplayInfoLocked();
            return mDisplayInfo.layerStack;
        }
    }

    /**
     * Returns a combination of flags that describe the capabilities of the display.
     *
     * @return The display flags.
     *
     * @see #FLAG_SUPPORTS_PROTECTED_BUFFERS
     * @see #FLAG_SECURE
     * @see #FLAG_PRIVATE
     * @see #FLAG_ROUND
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
     * @see #TYPE_INTERNAL
     * @see #TYPE_EXTERNAL
     * @see #TYPE_WIFI
     * @see #TYPE_OVERLAY
     * @see #TYPE_VIRTUAL
     * @hide
     */
    @UnsupportedAppUsage
    @TestApi
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
    public DisplayAddress getAddress() {
        synchronized (mLock) {
            updateDisplayInfoLocked();
            return mDisplayInfo.address;
        }
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
            final DisplayAdjustments currentAdjustments = mResources.getDisplayAdjustments();
            if (!mDisplayAdjustments.equals(currentAdjustments)) {
                mDisplayAdjustments = new DisplayAdjustments(currentAdjustments);
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
        synchronized (mLock) {
            updateDisplayInfoLocked();
            return mDisplayInfo.name;
        }
    }

    /**
     * Gets the default brightness configured for the display.
     *
     * @return Default brightness between 0.0-1.0
     * @hide
     */
    public float getBrightnessDefault() {
        synchronized (mLock) {
            updateDisplayInfoLocked();
            return mDisplayInfo.brightnessDefault;
        }
    }

    /**
     * @return Brightness information about the display.
     * @hide
     */
    @RequiresPermission(CONTROL_DISPLAY_BRIGHTNESS)
    public @Nullable BrightnessInfo getBrightnessInfo() {
        return mGlobal.getBrightnessInfo(mDisplayId);
    }

    /**
     * Gets the size of the display in pixels.
     *
     * <p>The return value does not necessarily represent the actual size (native resolution) of the
     * display. The returned size might be adjusted to exclude certain system decor elements that
     * are always visible, or the size might be scaled to provide compatibility with older
     * applications that were originally designed for smaller displays.
     *
     * <p>The returned size can also be different depending on the WindowManager bound to the
     * display:
     * <ul>
     *     <li>If size is requested from an activity (either using a WindowManager accessed by
     *         {@code getWindowManager()} or {@code getSystemService(Context.WINDOW_SERVICE)}), the
     *         size of the current app window is returned. As a result, in multi-window mode, the
     *         returned size can be smaller than the size of the device screen.
     *     <li>If size is requested from a non-activity context (for example, the application
     *         context, where the WindowManager is accessed by
     *         {@code getApplicationContext().getSystemService(Context.WINDOW_SERVICE)}), the
     *         returned size can vary depending on API level:
     *         <ul>
     *             <li>API level 29 and below &mdash; The size of the entire display (based on
     *                 current rotation) minus system decoration areas is returned.
     *             <li>API level 30 and above &mdash; The size of the top running activity in the
     *                 current process is returned. If the current process has no running
     *                 activities, the size of the device default display, including system
     *                 decoration areas, is returned.
     *         </ul>
     * </ul>
     *
     * <p>For layout purposes, apps should make a request from an activity context to obtain the
     * size of the display area available for app content.
     *
     * @param outSize A {@link Point} object which receives the display size information.
     *
     * @deprecated Use {@link WindowMetrics} instead. Obtain a {@code WindowMetrics} instance by
     *     calling {@link WindowManager#getCurrentWindowMetrics()}, then call
     *     {@link WindowMetrics#getBounds()} to get the dimensions of the application window.
     */
    @Deprecated
    public void getSize(Point outSize) {
        synchronized (mLock) {
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
     *
     * @deprecated Use {@link WindowMetrics#getBounds()} to get the dimensions of the application
     * window.
     */
    @Deprecated
    public void getRectSize(Rect outSize) {
        synchronized (mLock) {
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
        synchronized (mLock) {
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
        synchronized (mLock) {
            updateDisplayInfoLocked();
            return Math.max(mDisplayInfo.logicalWidth, mDisplayInfo.logicalHeight);
        }
    }

    /**
     * @deprecated Use {@link WindowMetrics#getBounds#width()} instead.
     */
    @Deprecated
    public int getWidth() {
        synchronized (mLock) {
            updateCachedAppSizeIfNeededLocked();
            return mCachedAppWidthCompat;
        }
    }

    /**
     * @deprecated Use {@link WindowMetrics#getBounds()#height()} instead.
     */
    @Deprecated
    public int getHeight() {
        synchronized (mLock) {
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
     *
     * This rotation value will match the results of {@link #getMetrics}: this means that the
     * rotation value will correspond to the activity if accessed through the activity.
     */
    @Surface.Rotation
    public int getRotation() {
        synchronized (mLock) {
            updateDisplayInfoLocked();
            return getLocalRotation();
        }
    }

    /**
     * Returns the install orientation of the display.
     * @hide
     */
    @Surface.Rotation
    public int getInstallOrientation() {
        synchronized (mLock) {
            updateDisplayInfoLocked();
            return mDisplayInfo.installOrientation;
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
        synchronized (mLock) {
            updateDisplayInfoLocked();
            if (mResources == null) return mDisplayInfo.displayCutout;
            final DisplayCutout localCutout = mDisplayInfo.displayCutout;
            if (localCutout == null) return null;
            int rotation = getLocalRotation();
            if (rotation != mDisplayInfo.rotation) {
                return localCutout.getRotated(mDisplayInfo.logicalWidth, mDisplayInfo.logicalHeight,
                        mDisplayInfo.rotation, rotation);
            }
            return localCutout;
        }
    }

    /**
     * Returns the {@link RoundedCorner} of the given position if there is one.
     *
     * @param position the position of the rounded corner on the display.
     *
     * @return the rounded corner of the given position. Returns {@code null} if there is none.
     */
    @SuppressLint("VisiblySynchronized")
    @Nullable
    public RoundedCorner getRoundedCorner(@RoundedCorner.Position int position) {
        synchronized (mLock) {
            updateDisplayInfoLocked();
            final RoundedCorners roundedCorners = mDisplayInfo.roundedCorners;
            final @Surface.Rotation int rotation = getLocalRotation();
            if (roundedCorners != null && rotation != mDisplayInfo.rotation) {
                roundedCorners.rotate(rotation,
                        mDisplayInfo.logicalWidth, mDisplayInfo.logicalHeight);
            }
            return roundedCorners == null ? null : roundedCorners.getRoundedCorner(position);
        }
    }

    /**
     * Returns the {@link DisplayShape} which is based on display coordinates.
     *
     * To get the {@link DisplayShape} based on the window frame, use
     * {@link WindowInsets#getDisplayShape()} instead.
     *
     * @see DisplayShape
     */
    @SuppressLint("VisiblySynchronized")
    @NonNull
    public DisplayShape getShape() {
        synchronized (mLock) {
            updateDisplayInfoLocked();
            final DisplayShape displayShape = mDisplayInfo.displayShape;
            final @Surface.Rotation int rotation = getLocalRotation();
            if (displayShape != null && rotation != mDisplayInfo.rotation) {
                return displayShape.setRotation(rotation);
            }
            return displayShape;
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
        synchronized (mLock) {
            updateDisplayInfoLocked();
            return mDisplayInfo.getRefreshRate();
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
        synchronized (mLock) {
            updateDisplayInfoLocked();
            return mDisplayInfo.getDefaultRefreshRates();
        }
    }

    /**
     * Returns the active mode of the display.
     */
    public Mode getMode() {
        synchronized (mLock) {
            updateDisplayInfoLocked();
            return mDisplayInfo.getMode();
        }
    }

    /**
     * Returns the default mode of the display.
     * @hide
     */
    @TestApi
    public @NonNull Mode getDefaultMode() {
        synchronized (mLock) {
            updateDisplayInfoLocked();
            return mDisplayInfo.getDefaultMode();
        }
    }

    /**
     * Gets the supported modes of this display.
     */
    public Mode[] getSupportedModes() {
        synchronized (mLock) {
            updateDisplayInfoLocked();
            final Display.Mode[] modes = mDisplayInfo.supportedModes;
            return Arrays.copyOf(modes, modes.length);
        }
    }

    /**
     * <p> Returns true if the connected display can be switched into a mode with minimal
     * post processing. </p>
     *
     * <p> If the Display sink is connected via HDMI, this method will return true if the
     * display supports either Auto Low Latency Mode or Game Content Type.
     *
     * <p> If the Display sink has an internal connection or uses some other protocol than
     * HDMI, this method will return true if the sink can be switched into an
     * implementation-defined low latency image processing mode. </p>
     *
     * <p> The ability to switch to a mode with minimal post processing may be disabled
     * by a user setting in the system settings menu. In that case, this method returns
     * false. </p>
     *
     * @see android.view.Window#setPreferMinimalPostProcessing
     */
    @SuppressLint("VisiblySynchronized")
    public boolean isMinimalPostProcessingSupported() {
        synchronized (mLock) {
            updateDisplayInfoLocked();
            return mDisplayInfo.minimalPostProcessingSupported;
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
        synchronized (mLock) {
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
    @SuppressLint("UnflaggedApi") // @TestApi without associated feature.
    @TestApi
    public int getRemoveMode() {
        return mDisplayInfo.removeMode;
    }

    /**
     * Returns the system's preferred display mode. This mode will be used when the user has not
     * specified a display-mode preference. This returns null if the boot display mode feature is
     * not supported by system.
     *
     * @hide
     */
    @TestApi
    @Nullable
    public Display.Mode getSystemPreferredDisplayMode() {
        return mGlobal.getSystemPreferredDisplayMode(getDisplayId());
    }

    /**
     * Returns the current display mode's HDR capabilities.
     *
     * @see #isHdr()
     */
    public HdrCapabilities getHdrCapabilities() {
        synchronized (mLock) {
            updateDisplayInfoLocked();
            if (mDisplayInfo.hdrCapabilities == null) {
                return null;
            }
            int[] supportedHdrTypes;
            if (mDisplayInfo.userDisabledHdrTypes.length == 0) {
                int[] modeSupportedHdrTypes = getMode().getSupportedHdrTypes();
                supportedHdrTypes = Arrays.copyOf(modeSupportedHdrTypes,
                        modeSupportedHdrTypes.length);
            } else {
                ArraySet<Integer> enabledTypesSet = new ArraySet<>();
                for (int supportedType : getMode().getSupportedHdrTypes()) {
                    if (!contains(mDisplayInfo.userDisabledHdrTypes, supportedType)) {
                        enabledTypesSet.add(supportedType);
                    }
                }

                supportedHdrTypes = new int[enabledTypesSet.size()];
                int index = 0;
                for (int enabledType : enabledTypesSet) {
                    supportedHdrTypes[index++] = enabledType;
                }
            }
            return new HdrCapabilities(supportedHdrTypes,
                    mDisplayInfo.hdrCapabilities.mMaxLuminance,
                    mDisplayInfo.hdrCapabilities.mMaxAverageLuminance,
                    mDisplayInfo.hdrCapabilities.mMinLuminance);
        }
    }

    private boolean contains(int[] disabledHdrTypes, int hdrType) {
        for (Integer disabledHdrFormat : disabledHdrTypes) {
            if (disabledHdrFormat == hdrType) {
                return true;
            }
        }
        return false;
    }

    /**
     * @hide
     * Returns the current mode's supported HDR types.
     *
     * @see #isHdr()
     * @see Mode#getSupportedHdrTypes()
     */
    @TestApi
    @NonNull
    public int[] getReportedHdrTypes() {
        synchronized (mLock) {
            updateDisplayInfoLocked();
            return mDisplayInfo.getMode().getSupportedHdrTypes();
        }
    }

    /**
     * Returns whether this display supports any HDR type.
     *
     * @see #getHdrCapabilities()
     * @see HdrCapabilities#getSupportedHdrTypes()
     */
    public boolean isHdr() {
        synchronized (mLock) {
            updateDisplayInfoLocked();
            HdrCapabilities hdrCapabilities = getHdrCapabilities();
            if (hdrCapabilities == null) {
                return false;
            }
            return !(hdrCapabilities.getSupportedHdrTypes().length == 0);
        }
    }

    /**
     * @return Whether the display supports reporting an hdr/sdr ratio. If this is false,
     *         {@link #getHdrSdrRatio()} will always be 1.0f
     */
    public boolean isHdrSdrRatioAvailable() {
        synchronized (mLock) {
            updateDisplayInfoLocked();
            return !Float.isNaN(mDisplayInfo.hdrSdrRatio);
        }
    }

    /**
     * @return The current hdr/sdr ratio expressed as the ratio of targetHdrPeakBrightnessInNits /
     *         targetSdrWhitePointInNits. If {@link #isHdrSdrRatioAvailable()} is false, this
     *         always returns 1.0f.
     */
    public float getHdrSdrRatio() {
        synchronized (mLock) {
            updateDisplayInfoLocked();
            return Float.isNaN(mDisplayInfo.hdrSdrRatio)
                    ? 1.0f : mDisplayInfo.hdrSdrRatio;
        }
    }

    private int findHdrSdrRatioListenerLocked(Consumer<Display> listener) {
        for (int i = 0; i < mHdrSdrRatioListeners.size(); i++) {
            final HdrSdrRatioListenerWrapper wrapper = mHdrSdrRatioListeners.get(i);
            if (wrapper.mListener == listener) {
                return i;
            }
        }
        return -1;
    }

    /**
     * Registers a listener that will be invoked whenever the display's hdr/sdr ratio has changed.
     * After receiving the callback on the specified Executor, call {@link #getHdrSdrRatio()} to
     * get the updated value.
     * If {@link #isHdrSdrRatioAvailable()} is false, then an IllegalStateException will be thrown
     *
     * @see #unregisterHdrSdrRatioChangedListener(Consumer)
     * @param executor The executor to invoke the listener on
     * @param listener The listener to invoke when the HDR/SDR ratio changes
     * @throws IllegalStateException if {@link #isHdrSdrRatioAvailable()} is false
     */
    public void registerHdrSdrRatioChangedListener(@NonNull Executor executor,
            @NonNull Consumer<Display> listener) {
        if (!isHdrSdrRatioAvailable()) {
            throw new IllegalStateException("HDR/SDR ratio changed not available");
        }
        HdrSdrRatioListenerWrapper toRegister = null;
        synchronized (mLock) {
            if (findHdrSdrRatioListenerLocked(listener) == -1) {
                toRegister = new HdrSdrRatioListenerWrapper(listener);
                mHdrSdrRatioListeners.add(toRegister);
            } // else already listening, don't do anything
        }
        if (toRegister != null) {
            // Although we only care about the HDR/SDR ratio changing, that can also come in the
            // form of the larger DISPLAY_CHANGED event
            mGlobal.registerDisplayListener(toRegister, executor,
                    DisplayManager.EVENT_FLAG_HDR_SDR_RATIO_CHANGED
                            | DisplayManagerGlobal.EVENT_DISPLAY_CHANGED,
                    ActivityThread.currentPackageName());
        }

    }

    /**
     * @param listener  The previously
     *                  {@link #registerHdrSdrRatioChangedListener(Executor, Consumer) registered}
     *                  hdr/sdr ratio listener to remove.
     *
     * @see #registerHdrSdrRatioChangedListener(Executor, Consumer)
     */
    public void unregisterHdrSdrRatioChangedListener(@NonNull Consumer<Display> listener) {
        HdrSdrRatioListenerWrapper toRemove = null;
        synchronized (mLock) {
            int index = findHdrSdrRatioListenerLocked(listener);
            if (index != -1) {
                toRemove = mHdrSdrRatioListeners.remove(index);
            }
        }
        if (toRemove != null) {
            mGlobal.unregisterDisplayListener(toRemove);
        }
    }

    /**
     * Sets the default {@link Display.Mode} to use for the display.  The display mode includes
     * preference for resolution and refresh rate.
     * If the mode specified is not supported by the display, then no mode change occurs.
     *
     * @param mode The {@link Display.Mode} to set, which can include resolution and/or
     * refresh-rate. It is created using {@link Display.Mode.Builder}.
     *`
     * @hide
     */
    @TestApi
    @RequiresPermission(Manifest.permission.MODIFY_USER_PREFERRED_DISPLAY_MODE)
    public void setUserPreferredDisplayMode(@NonNull Display.Mode mode) {
        // Create a new object containing default values for the unused fields like mode ID and
        // alternative refresh rates.
        Display.Mode preferredMode = new Display.Mode(mode.getPhysicalWidth(),
                mode.getPhysicalHeight(), mode.getRefreshRate());
        mGlobal.setUserPreferredDisplayMode(mDisplayId, preferredMode);
    }

    /**
     * Removes the display's user preferred display mode.
     *
     * @hide
     */
    @TestApi
    @RequiresPermission(Manifest.permission.MODIFY_USER_PREFERRED_DISPLAY_MODE)
    public void clearUserPreferredDisplayMode() {
        mGlobal.setUserPreferredDisplayMode(mDisplayId, null);
    }

    /**
     * Returns the display's user preferred display mode.
     *
     * @hide
     */
    @TestApi
    @Nullable
    public Display.Mode getUserPreferredDisplayMode() {
        return mGlobal.getUserPreferredDisplayMode(mDisplayId);
    }


    /**
     * Returns whether this display can be used to display wide color gamut content.
     * This does not necessarily mean the device itself can render wide color gamut
     * content. To ensure wide color gamut content can be produced, refer to
     * {@link Configuration#isScreenWideColorGamut()}.
     */
    public boolean isWideColorGamut() {
        synchronized (mLock) {
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
        synchronized (mLock) {
            updateDisplayInfoLocked();
            if (mDisplayInfo.isWideColorGamut()) {
                return mGlobal.getPreferredWideGamutColorSpace();
            }
            return null;
        }
    }

    /**
     * Returns the {@link OverlayProperties} of the display.
     */
    @FlaggedApi(FLAG_OVERLAYPROPERTIES_CLASS_API)
    @NonNull
    public OverlayProperties getOverlaySupport() {
        synchronized (mLock) {
            updateDisplayInfoLocked();
            if (mDisplayInfo.type == TYPE_INTERNAL
                    || mDisplayInfo.type == TYPE_EXTERNAL) {
                return mGlobal.getOverlaySupport();
            }
            return OverlayProperties.getDefault();
        }
    }

    /**
     * Gets the supported color modes of this device.
     * @hide
     */
    public int[] getSupportedColorModes() {
        synchronized (mLock) {
            updateDisplayInfoLocked();
            int[] colorModes = mDisplayInfo.supportedColorModes;
            return Arrays.copyOf(colorModes, colorModes.length);
        }
    }

    /**
     * Gets the supported wide color gamuts of this device.
     *
     * @return Supported WCG color spaces.
     * @hide
     */
    @SuppressLint("VisiblySynchronized")
    @NonNull
    @TestApi
    public @ColorMode ColorSpace[] getSupportedWideColorGamut() {
        synchronized (mLock) {
            final ColorSpace[] defaultColorSpaces = new ColorSpace[0];
            updateDisplayInfoLocked();
            if (!isWideColorGamut()) {
                return defaultColorSpaces;
            }

            final int[] colorModes = getSupportedColorModes();
            final List<ColorSpace> colorSpaces = new ArrayList<>();
            for (int colorMode : colorModes) {
                // Refer to DisplayInfo#isWideColorGamut.
                switch (colorMode) {
                    case COLOR_MODE_DCI_P3:
                        colorSpaces.add(ColorSpace.get(ColorSpace.Named.DCI_P3));
                        break;
                    case COLOR_MODE_DISPLAY_P3:
                        colorSpaces.add(ColorSpace.get(ColorSpace.Named.DISPLAY_P3));
                        break;
                }
            }
            return colorSpaces.toArray(defaultColorSpaces);
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
        synchronized (mLock) {
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
        synchronized (mLock) {
            updateDisplayInfoLocked();
            return mDisplayInfo.presentationDeadlineNanos;
        }
    }

    /**
     * Returns the product-specific information about the display or the directly connected
     * device on the display chain.
     * For example, if the display is transitively connected, this field may contain product
     * information about the intermediate device.
     * Returns {@code null} if product information is not available.
     */
    @Nullable
    public DeviceProductInfo getDeviceProductInfo() {
        synchronized (mLock) {
            updateDisplayInfoLocked();
            return mDisplayInfo.deviceProductInfo;
        }
    }

    /**
     * Gets the size and density of this display.
     *
     * <p>The size returned does not necessarily represent the actual size (native resolution) of
     * the display. The returned size might be adjusted to exclude certain system decor elements
     * that are always visible, or the size might be scaled to provide compatibility with older
     * applications that were originally designed for smaller displays.
     *
     * <p>The returned size can also be different depending on the WindowManager associated with the
     * display:
     * <ul>
     *     <li>If metrics are requested from an activity (either using a WindowManager accessed by
     *         {@code getWindowManager()} or {@code getSystemService(Context.WINDOW_SERVICE)}), the
     *         returned metrics provide the size of the current app window. As a result, in
     *         multi-window mode, the returned size can be smaller than the size of the device
     *         screen.
     *     <li>If metrics are requested from a non-activity context (for example, the application
     *         context, where the WindowManager is accessed by
     *         {@code getApplicationContext().getSystemService(Context.WINDOW_SERVICE)}), the
     *         returned size can vary depending on API level:
     *         <ul>
     *             <li>API level 29 and below &mdash; The returned metrics provide the size of the
     *                 entire display (based on current rotation) minus system decoration areas.
     *             <li>API level 30 and above &mdash; The returned metrics provide the size of the
     *                 top running activity in the current process. If the current process has no
     *                 running activities, the metrics provide the size of the default display of
     *                 the device, including system decoration areas.
     *         </ul>
     * </ul>
     *
     * @param outMetrics A {@link DisplayMetrics} object which receives the display metrics.
     *
     * @deprecated Use {@link WindowMetrics#getBounds()} to get the dimensions of the application
     *     window. Use {@link WindowMetrics#getDensity()} to get the density of the application
     *     window.
     */
    @Deprecated
    public void getMetrics(DisplayMetrics outMetrics) {
        synchronized (mLock) {
            updateDisplayInfoLocked();
            mDisplayInfo.getAppMetrics(outMetrics, getDisplayAdjustments());
        }
    }

    /**
     * Gets the size of the largest region of the display accessible to an app in the current system
     * state, without subtracting any window decor or applying scaling factors.
     * <p>
     * The size is adjusted based on the current rotation of the display.
     * <p></p>
     * The returned size will fall into one of these scenarios:
     * <ol>
     * <li>The device has no partitions on the display. The returned value is the largest region
     * of the display accessible to an app in the current system state, regardless of windowing
     * mode.</li>
     * <li>The device divides a single display into multiple partitions. An application is
     * restricted to a portion of the display. This is common in devices where the display changes
     * size, such as foldables or large screens. The returned size will match the portion of
     * the display the application is restricted to.</li>
     * <li>The window manager is emulating a different display size, using {@code adb shell wm
     * size}. The returned size will match the emulated display size.</li>
     * </ol>
     * </p><p>
     * The returned value is <b>unsuitable to use when sizing and placing UI elements</b>, since it
     * does not reflect the application window size in any of these scenarios.
     * {@link WindowManager#getCurrentWindowMetrics()} is an alternative that returns the size
     * of the current application window, even if the window is on a device with a partitioned
     * display. This helps prevent UI bugs where UI elements are misaligned or placed beyond the
     * bounds of the window.
     * <p></p>
     * Handling multi-window mode correctly is necessary since applications are not always
     * fullscreen. A user on a large screen device, such as a tablet or ChromeOS devices, is more
     * likely to use multi-window modes.
     * <p></p>
     * For example, consider a device with a display partitioned into two halves. The user may have
     * a fullscreen application open on the first partition. They may have two applications open in
     * split screen (an example of multi-window mode) on the second partition, with each application
     * consuming half of the partition. In this case,
     * {@link WindowManager#getCurrentWindowMetrics()} reports the fullscreen window is half of the
     * screen in size, and each split screen window is a quarter of the screen in size. On the other
     * hand, {@link #getRealSize} reports half of the screen size for all windows, since the
     * application windows are all restricted to their respective partitions.
     * </p>
     *
     * @param outSize Set to the real size of the display.
     * @deprecated Use {@link WindowManager#getCurrentWindowMetrics()} to identify the current size
     * of the activity window. UI-related work, such as choosing UI layouts, should rely
     * upon {@link WindowMetrics#getBounds()}.
     */
    @Deprecated
    public void getRealSize(Point outSize) {
        synchronized (mLock) {
            updateDisplayInfoLocked();
            if (shouldReportMaxBounds()) {
                final Rect bounds = mResources.getConfiguration()
                        .windowConfiguration.getMaxBounds();
                outSize.x = bounds.width();
                outSize.y = bounds.height();
                if (DEBUG) {
                    Log.d(TAG, "getRealSize determined from max bounds: " + outSize);
                }
                // Skip adjusting by fixed rotation, since if it is necessary, the configuration
                // should already reflect the expected rotation.
                return;
            }
            outSize.x = mDisplayInfo.logicalWidth;
            outSize.y = mDisplayInfo.logicalHeight;
            final @Surface.Rotation int rotation = getLocalRotation();
            if (rotation != mDisplayInfo.rotation) {
                adjustSize(outSize, mDisplayInfo.rotation, rotation);
            }
        }
    }

    /**
     * Gets the size of the largest region of the display accessible to an app in the current system
     * state, without subtracting any window decor or applying scaling factors.
     * <p>
     * The size is adjusted based on the current rotation of the display.
     * <p></p>
     * The returned size will fall into one of these scenarios:
     * <ol>
     * <li>The device has no partitions on the display. The returned value is the largest region
     * of the display accessible to an app in the current system state, regardless of windowing
     * mode.</li>
     * <li>The device divides a single display into multiple partitions. An application is
     * restricted to a portion of the display. This is common in devices where the display changes
     * size, such as foldables or large screens. The returned size will match the portion of
     * the display the application is restricted to.</li>
     * <li>The window manager is emulating a different display size, using {@code adb shell wm
     * size}. The returned size will match the emulated display size.</li>
     * </ol>
     * </p><p>
     * The returned value is <b>unsuitable to use when sizing and placing UI elements</b>, since it
     * does not reflect the application window size in any of these scenarios.
     * {@link WindowManager#getCurrentWindowMetrics()} is an alternative that returns the size
     * of the current application window, even if the window is on a device with a partitioned
     * display. This helps prevent UI bugs where UI elements are misaligned or placed beyond the
     * bounds of the window.
     * <p></p>
     * Handling multi-window mode correctly is necessary since applications are not always
     * fullscreen. A user on a large screen device, such as a tablet or ChromeOS devices, is more
     * likely to use multi-window modes.
     * <p></p>
     * For example, consider a device with a display partitioned into two halves. The user may have
     * a fullscreen application open on the first partition. They may have two applications open in
     * split screen (an example of multi-window mode) on the second partition, with each application
     * consuming half of the partition. In this case,
     * {@link WindowManager#getCurrentWindowMetrics()} reports the fullscreen window is half of the
     * screen in size, and each split screen window is a quarter of the screen in size. On the other
     * hand, {@link #getRealMetrics} reports half of the screen size for all windows, since the
     * application windows are all restricted to their respective partitions.
     * </p>
     *
     * @param outMetrics A {@link DisplayMetrics} object to receive the metrics.
     * @deprecated Use {@link WindowManager#getCurrentWindowMetrics()} to identify the current size
     * of the activity window. UI-related work, such as choosing UI layouts, should rely
     * upon {@link WindowMetrics#getBounds()}. Use {@link Configuration#densityDpi} to
     * get the current density.
     */
    @Deprecated
    public void getRealMetrics(DisplayMetrics outMetrics) {
        synchronized (mLock) {
            updateDisplayInfoLocked();
            if (shouldReportMaxBounds()) {
                mDisplayInfo.getMaxBoundsMetrics(outMetrics,
                        CompatibilityInfo.DEFAULT_COMPATIBILITY_INFO,
                        mResources.getConfiguration());
                if (DEBUG) {
                    Log.d(TAG, "getRealMetrics determined from max bounds: " + outMetrics);
                }
                // Skip adjusting by fixed rotation, since if it is necessary, the configuration
                // should already reflect the expected rotation.
                return;
            }
            mDisplayInfo.getLogicalMetrics(outMetrics,
                    CompatibilityInfo.DEFAULT_COMPATIBILITY_INFO, null);
            final @Surface.Rotation int rotation = getLocalRotation();
            if (rotation != mDisplayInfo.rotation) {
                adjustMetrics(outMetrics, mDisplayInfo.rotation, rotation);
            }
        }
    }

    /**
     * Determines if {@link WindowConfiguration#getMaxBounds()} should be reported as the
     * display dimensions. The max bounds field may be smaller than the logical dimensions
     * when apps need to be sandboxed.
     *
     * Depends upon {@link WindowConfiguration#getMaxBounds()} being set in
     * {@link com.android.server.wm.ConfigurationContainer#providesMaxBounds()}. In most cases, this
     * value reflects the size of the current DisplayArea.
     * @return {@code true} when max bounds should be applied.
     */
    private boolean shouldReportMaxBounds() {
        if (mResources == null) {
            return false;
        }
        final Configuration config = mResources.getConfiguration();
        return config != null && !config.windowConfiguration.getMaxBounds().isEmpty();
    }

    /**
     * Gets the state of the display, such as whether it is on or off.
     *
     * @return The state of the display: one of {@link #STATE_OFF}, {@link #STATE_ON},
     * {@link #STATE_DOZE}, {@link #STATE_DOZE_SUSPEND}, {@link #STATE_ON_SUSPEND}, or
     * {@link #STATE_UNKNOWN}.
     */
    public int getState() {
        synchronized (mLock) {
            updateDisplayInfoLocked();
            return mIsValid ? mDisplayInfo.state : STATE_UNKNOWN;
        }
    }

    /**
     * Returns the committed state of the display.
     *
     * @return The latest committed display state, such as {@link #STATE_ON}. The display state
     * {@link Display#getState()} is set as committed only after power state changes finish.
     *
     * @hide
     */
    public int getCommittedState() {
        synchronized (mLock) {
            updateDisplayInfoLocked();
            return mIsValid ? mDisplayInfo.committedState : STATE_UNKNOWN;
        }
    }

    /**
     * Returns true if the specified UID has access to this display.
     * @hide
     */
    @TestApi
    public boolean hasAccess(int uid) {
        return hasAccess(uid, mFlags, mOwnerUid, mDisplayId);
    }

    /** @hide */
    public static boolean hasAccess(int uid, int flags, int ownerUid, int displayId) {
        return (flags & Display.FLAG_PRIVATE) == 0
                || uid == ownerUid
                || uid == Process.SYSTEM_UID
                || uid == 0
                // Check if the UID is present on given display.
                || DisplayManagerGlobal.getInstance().isUidPresentOnDisplay(uid, displayId);
    }

    /**
     * Returns true if the display is a public presentation display.
     * @hide
     */
    public boolean isPublicPresentation() {
        return (mFlags & (Display.FLAG_PRIVATE | Display.FLAG_PRESENTATION)) ==
                Display.FLAG_PRESENTATION;
    }

    /**
     * @return {@code true} if the display is a trusted display.
     *
     * @see #FLAG_TRUSTED
     * @hide
     */
    public boolean isTrusted() {
        return (mFlags & FLAG_TRUSTED) == FLAG_TRUSTED;
    }

    /**
     * @return {@code true} if the display can steal the top focus from another display.
     *
     * @see #FLAG_STEAL_TOP_FOCUS_DISABLED
     * @hide
     */
    public boolean canStealTopFocus() {
        return (mFlags & FLAG_STEAL_TOP_FOCUS_DISABLED) == 0;
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

    /** Returns {@code false} if the width and height of display should swap. */
    private static boolean noFlip(@Surface.Rotation int realRotation,
            @Surface.Rotation int localRotation) {
        // Check if the delta is rotated by 90 degrees.
        return (realRotation - localRotation + 4) % 2 == 0;
    }

    /**
     * Adjusts the given size by a rotation offset if necessary.
     * @hide
     */
    private void adjustSize(@NonNull Point size, @Surface.Rotation int realRotation,
            @Surface.Rotation int localRotation) {
        if (noFlip(realRotation, localRotation)) return;
        final int w = size.x;
        size.x = size.y;
        size.y = w;
    }

    /**
     * Adjusts the given metrics by a rotation offset if necessary.
     * @hide
     */
    private void adjustMetrics(@NonNull DisplayMetrics metrics,
            @Surface.Rotation int realRotation, @Surface.Rotation int localRotation) {
        if (noFlip(realRotation, localRotation)) return;
        int w = metrics.widthPixels;
        metrics.widthPixels = metrics.heightPixels;
        metrics.heightPixels = w;

        w = metrics.noncompatWidthPixels;
        metrics.noncompatWidthPixels = metrics.noncompatHeightPixels;
        metrics.noncompatHeightPixels = w;
    }

    private @Surface.Rotation int getLocalRotation() {
        if (mResources == null) return mDisplayInfo.rotation;
        final @Surface.Rotation int localRotation =
                mResources.getConfiguration().windowConfiguration.getDisplayRotation();
        if (localRotation != WindowConfiguration.ROTATION_UNDEFINED) return localRotation;
        return mDisplayInfo.rotation;
    }

    // For debugging purposes
    @Override
    public String toString() {
        synchronized (mLock) {
            updateDisplayInfoLocked();
            final DisplayAdjustments adjustments = getDisplayAdjustments();
            mDisplayInfo.getAppMetrics(mTempMetrics, adjustments);
            return "Display id " + mDisplayId + ": " + mDisplayInfo + ", "
                    + mTempMetrics + ", isValid=" + mIsValid;
        }
    }

    /**
     * @hide
     */
    public static String typeToString(int type) {
        switch (type) {
            case TYPE_UNKNOWN:
                return "UNKNOWN";
            case TYPE_INTERNAL:
                return "INTERNAL";
            case TYPE_EXTERNAL:
                return "EXTERNAL";
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

    /** @hide */
    public static String stateReasonToString(@StateReason int reason) {
        switch (reason) {
            case STATE_REASON_UNKNOWN:
                return "UNKNOWN";
            case STATE_REASON_DEFAULT_POLICY:
                return "DEFAULT_POLICY";
            case STATE_REASON_DRAW_WAKE_LOCK:
                return "DRAW_WAKE_LOCK";
            case STATE_REASON_OFFLOAD:
                return "OFFLOAD";
            case STATE_REASON_TILT:
                return "TILT";
            case STATE_REASON_DREAM_MANAGER:
                return "DREAM_MANAGER";
            case STATE_REASON_KEY:
                return "KEY";
            case STATE_REASON_MOTION:
                return "MOTION";
            default:
                return Integer.toString(reason);
        }
    }

    /**
     * Returns true if display updates may be suspended while in the specified
     * display power state. In SUSPEND states, updates are absolutely forbidden.
     * @hide
     */
    @android.ravenwood.annotation.RavenwoodKeep
    public static boolean isSuspendedState(int state) {
        return state == STATE_OFF || state == STATE_DOZE_SUSPEND || state == STATE_ON_SUSPEND;
    }

    /**
     * Returns true if the display may be in a reduced operating mode while in the
     * specified display power state.
     * @hide
     */
    @android.ravenwood.annotation.RavenwoodKeep
    public static boolean isDozeState(int state) {
        return state == STATE_DOZE || state == STATE_DOZE_SUSPEND;
    }

    /**
     * Returns true if the display is in active state such as {@link #STATE_ON}
     * or {@link #STATE_VR}.
     * @hide
     */
    @android.ravenwood.annotation.RavenwoodKeep
    public static boolean isActiveState(int state) {
        return state == STATE_ON || state == STATE_VR;
    }

    /**
     * Returns true if the display is in an off state such as {@link #STATE_OFF}.
     * @hide
     */
    @android.ravenwood.annotation.RavenwoodKeep
    public static boolean isOffState(int state) {
        return state == STATE_OFF;
    }

    /**
     * Returns true if the display is in an on state such as {@link #STATE_ON}
     * or {@link #STATE_VR} or {@link #STATE_ON_SUSPEND}.
     * @hide
     */
    @android.ravenwood.annotation.RavenwoodKeep
    public static boolean isOnState(int state) {
        return state == STATE_ON || state == STATE_VR || state == STATE_ON_SUSPEND;
    }

    /**
     * Returns true if the specified width is valid.
     * @hide
     */
    public static boolean isWidthValid(int width) {
        return width > 0;
    }

    /**
     * Returns true if the specified height is valid.
     * @hide
     */
    public static boolean isHeightValid(int height) {
        return height > 0;
    }

    /**
     * Returns true if the specified refresh-rate is valid.
     * @hide
     */
    public static boolean isRefreshRateValid(float refreshRate) {
        return refreshRate > 0.0f;
    }

    /**
     * Returns whether/how the specified display supports DISPLAY_DECORATION.
     *
     * Composition.DISPLAY_DECORATION is a special layer type which is used to
     * render the screen decorations (i.e. antialiased rounded corners and
     * cutouts) while taking advantage of specific hardware.
     *
     * @hide
     */
    public DisplayDecorationSupport getDisplayDecorationSupport() {
        return mGlobal.getDisplayDecorationSupport(mDisplayId);
    }

    /**
     * A mode supported by a given display.
     *
     * @see Display#getSupportedModes()
     */
    @android.ravenwood.annotation.RavenwoodKeepWholeClass
    public static final class Mode implements Parcelable {
        /**
         * @hide
         */
        public static final Mode[] EMPTY_ARRAY = new Mode[0];

        /**
         * @hide
         */
        public static final int INVALID_MODE_ID = -1;

        private final int mModeId;
        private final int mWidth;
        private final int mHeight;
        private final float mPeakRefreshRate;
        private final float mVsyncRate;
        @NonNull
        private final float[] mAlternativeRefreshRates;
        @NonNull
        @HdrCapabilities.HdrType
        private final int[] mSupportedHdrTypes;

        /**
         * @hide
         */
        @TestApi
        public Mode(int width, int height, float refreshRate) {
            this(INVALID_MODE_ID, width, height, refreshRate, refreshRate, new float[0],
                    new int[0]);
        }

        /**
         * @hide
         */
        public Mode(int width, int height, float refreshRate, float vsyncRate) {
            this(INVALID_MODE_ID, width, height, refreshRate, vsyncRate, new float[0], new int[0]);
        }

        /**
         * @hide
         */
        @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.R, trackingBug = 170729553)
        public Mode(int modeId, int width, int height, float refreshRate) {
            this(modeId, width, height, refreshRate, refreshRate, new float[0], new int[0]);
        }

        /**
         * @hide
         */
        public Mode(int modeId, int width, int height, float refreshRate,
                    float[] alternativeRefreshRates,
                    @HdrCapabilities.HdrType int[] supportedHdrTypes) {
            this(modeId, width, height, refreshRate, refreshRate, alternativeRefreshRates,
                    supportedHdrTypes);
        }

        /**
         * @hide
         */
        public Mode(int modeId, int width, int height, float refreshRate, float vsyncRate,
                float[] alternativeRefreshRates, @HdrCapabilities.HdrType int[] supportedHdrTypes) {
            mModeId = modeId;
            mWidth = width;
            mHeight = height;
            mPeakRefreshRate = refreshRate;
            mVsyncRate = vsyncRate;
            mAlternativeRefreshRates =
                    Arrays.copyOf(alternativeRefreshRates, alternativeRefreshRates.length);
            Arrays.sort(mAlternativeRefreshRates);
            mSupportedHdrTypes = Arrays.copyOf(supportedHdrTypes, supportedHdrTypes.length);
            Arrays.sort(mSupportedHdrTypes);
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
            return mPeakRefreshRate;
        }

        /**
         * Returns the vsync rate in frames per second.
         * The physical vsync rate may be higher than the refresh rate, as the refresh rate may be
         * constrained by the system.
         * @hide
         */
        public float getVsyncRate() {
            return mVsyncRate;
        }

        /**
         * Returns an array of refresh rates which can be switched to seamlessly.
         * <p>
         * A seamless switch is one without visual interruptions, such as a black screen for
         * a second or two.
         * <p>
         * Presence in this list does not guarantee a switch will occur to the desired
         * refresh rate, but rather, if a switch does occur to a refresh rate in this list,
         * it is guaranteed to be seamless.
         * <p>
         * The binary relation "refresh rate X is alternative to Y" is non-reflexive,
         * symmetric and transitive. For example the mode 1920x1080 60Hz, will never have an
         * alternative refresh rate of 60Hz. If 1920x1080 60Hz has an alternative of 50Hz
         * then 1920x1080 50Hz will have alternative refresh rate of 60Hz. If 1920x1080 60Hz
         * has an alternative of 50Hz and 1920x1080 50Hz has an alternative of 24Hz, then 1920x1080
         * 60Hz will also have an alternative of 24Hz.
         *
         * @see Surface#setFrameRate
         * @see SurfaceControl.Transaction#setFrameRate
         */
        @NonNull
        public float[] getAlternativeRefreshRates() {
            return Arrays.copyOf(mAlternativeRefreshRates, mAlternativeRefreshRates.length);
        }

        /**
         * Returns the supported {@link HdrCapabilities} HDR_TYPE_* for this specific mode
         */
        @NonNull
        @HdrCapabilities.HdrType
        public int[] getSupportedHdrTypes() {
            return Arrays.copyOf(mSupportedHdrTypes, mSupportedHdrTypes.length);
        }

        /**
         * Returns {@code true} if this mode matches the given parameters.
         *
         * @hide
         */
        @TestApi
        public boolean matches(int width, int height, float refreshRate) {
            return mWidth == width &&
                    mHeight == height &&
                    Float.floatToIntBits(mPeakRefreshRate) == Float.floatToIntBits(refreshRate);
        }

        /**
         * Returns {@code true} if this mode matches the given parameters, if those parameters are
         * valid.<p>
         * If resolution (width and height) is valid and refresh-rate is not, the method matches
         * only resolution.
         * If refresh-rate is valid and resolution (width and height) is not, the method matches
         * only refresh-rate.</p>
         *
         * @hide
         */
        public boolean matchesIfValid(int width, int height, float peakRefreshRate) {
            if (!isWidthValid(width) && !isHeightValid(height)
                    && !isRefreshRateValid(peakRefreshRate)) {
                return false;
            }
            if (isWidthValid(width) != isHeightValid(height)) {
                return false;
            }
            return (!isWidthValid(width) || mWidth == width)
                    && (!isHeightValid(height) || mHeight == height)
                    && (!isRefreshRateValid(peakRefreshRate)
                    || Float.floatToIntBits(mPeakRefreshRate)
                            == Float.floatToIntBits(peakRefreshRate));
        }

        /**
         * Returns {@code true} if this mode equals to the other mode in all parameters except
         * the refresh rate.
         *
         * @hide
         */
        public boolean equalsExceptRefreshRate(@Nullable Display.Mode other) {
            return mWidth == other.mWidth && mHeight == other.mHeight;
        }

        /**
         * Returns {@code true} if refresh-rate is set for a display mode
         *
         * @hide
         */
        public boolean isRefreshRateSet() {
            return mPeakRefreshRate != INVALID_DISPLAY_REFRESH_RATE;
        }

        /**
         * Returns {@code true} if refresh-rate is set for a display mode
         *
         * @hide
         */
        public boolean isResolutionSet() {
            return mWidth != INVALID_DISPLAY_WIDTH && mHeight != INVALID_DISPLAY_HEIGHT;
        }

        @Override
        public boolean equals(@Nullable Object other) {
            if (this == other) {
                return true;
            }
            if (!(other instanceof Mode)) {
                return false;
            }
            Mode that = (Mode) other;
            return mModeId == that.mModeId
                    && matches(that.mWidth, that.mHeight, that.mPeakRefreshRate)
                    && Arrays.equals(mAlternativeRefreshRates, that.mAlternativeRefreshRates)
                    && Arrays.equals(mSupportedHdrTypes, that.mSupportedHdrTypes);
        }

        @Override
        public int hashCode() {
            int hash = 1;
            hash = hash * 17 + mModeId;
            hash = hash * 17 + mWidth;
            hash = hash * 17 + mHeight;
            hash = hash * 17 + Float.floatToIntBits(mPeakRefreshRate);
            hash = hash * 17 + Float.floatToIntBits(mVsyncRate);
            hash = hash * 17 + Arrays.hashCode(mAlternativeRefreshRates);
            hash = hash * 17 + Arrays.hashCode(mSupportedHdrTypes);
            return hash;
        }

        @Override
        public String toString() {
            return new StringBuilder("{")
                    .append("id=").append(mModeId)
                    .append(", width=").append(mWidth)
                    .append(", height=").append(mHeight)
                    .append(", fps=").append(mPeakRefreshRate)
                    .append(", vsync=").append(mVsyncRate)
                    .append(", alternativeRefreshRates=")
                    .append(Arrays.toString(mAlternativeRefreshRates))
                    .append(", supportedHdrTypes=")
                    .append(Arrays.toString(mSupportedHdrTypes))
                    .append("}")
                    .toString();
        }

        @Override
        public int describeContents() {
            return 0;
        }

        private Mode(Parcel in) {
            this(in.readInt(), in.readInt(), in.readInt(), in.readFloat(), in.readFloat(),
                    in.createFloatArray(), in.createIntArray());
        }

        @Override
        public void writeToParcel(Parcel out, int parcelableFlags) {
            out.writeInt(mModeId);
            out.writeInt(mWidth);
            out.writeInt(mHeight);
            out.writeFloat(mPeakRefreshRate);
            out.writeFloat(mVsyncRate);
            out.writeFloatArray(mAlternativeRefreshRates);
            out.writeIntArray(mSupportedHdrTypes);
        }

        @SuppressWarnings("hiding")
        public static final @android.annotation.NonNull Parcelable.Creator<Mode> CREATOR
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

        /**
         * Builder is used to create {@link Display.Mode} objects
         *
         * @hide
         */
        @TestApi
        public static final class Builder {
            private int mWidth;
            private int mHeight;
            private float mRefreshRate;

            public Builder() {
                mWidth = Display.INVALID_DISPLAY_WIDTH;
                mHeight = Display.INVALID_DISPLAY_HEIGHT;
                mRefreshRate = Display.INVALID_DISPLAY_REFRESH_RATE;
            }

            /**
             * Sets the resolution (width and height) of a {@link Display.Mode}
             *
             * @return Instance of {@link Builder}
             */
            @NonNull
            public Builder setResolution(int width, int height) {
                if (width > 0 && height > 0) {
                    mWidth = width;
                    mHeight = height;
                }
                return this;
            }

            /**
             * Sets the refresh rate of a {@link Display.Mode}
             *
             * @return Instance of {@link Builder}
             */
            @NonNull
            public Builder setRefreshRate(float refreshRate) {
                if (refreshRate > 0.0f) {
                    mRefreshRate = refreshRate;
                }
                return this;
            }

            /**
             * Creates the {@link Display.Mode} object.
             *
             * <p>
             * If resolution needs to be set, but refresh-rate doesn't matter, create a mode with
             * Builder and call setResolution.
             * {@code
             * Display.Mode mode =
             *      new Display.Mode.Builder()
             *      .setResolution(width, height)
             *      .build();
             * }
             * </p><p>
             * If refresh-rate needs to be set, but resolution doesn't matter, create a mode with
             * Builder and call setRefreshRate.
             * {@code
             * Display.Mode mode =
             *      new Display.Mode.Builder()
             *      .setRefreshRate(refreshRate)
             *      .build();
             * }
             * </p>
             */
            @NonNull
            public Mode build() {
                Display.Mode mode = new Mode(mWidth, mHeight, mRefreshRate);
                return mode;
            }
        }
    }

    /**
     * Encapsulates the HDR capabilities of a given display.
     * For example, what HDR types it supports and details about the desired luminance data.
     * <p>You can get an instance for a given {@link Display} object with
     * {@link Display#getHdrCapabilities getHdrCapabilities()}.
     */
    @android.ravenwood.annotation.RavenwoodKeepWholeClass
    public static final class HdrCapabilities implements Parcelable {
        /**
         * Invalid luminance value.
         */
        public static final float INVALID_LUMINANCE = -1;
        /**
         * Invalid HDR type value.
         */
        public static final int HDR_TYPE_INVALID = -1;
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
        public static final int[] HDR_TYPES = {
                HDR_TYPE_DOLBY_VISION,
                HDR_TYPE_HDR10,
                HDR_TYPE_HLG,
                HDR_TYPE_HDR10_PLUS
        };

        /** @hide */
        @IntDef(prefix = { "HDR_TYPE_" }, value = {
                HDR_TYPE_INVALID,
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
        @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.R, trackingBug = 170729553)
        public HdrCapabilities(int[] supportedHdrTypes, float maxLuminance,
                float maxAverageLuminance, float minLuminance) {
            mSupportedHdrTypes = supportedHdrTypes;
            Arrays.sort(mSupportedHdrTypes);
            mMaxLuminance = maxLuminance;
            mMaxAverageLuminance = maxAverageLuminance;
            mMinLuminance = minLuminance;
        }

        /**
         * Gets the supported HDR types of this display.
         * Returns empty array if HDR is not supported by the display.
         *
         * @deprecated use {@link Display#getMode()}
         * and {@link Mode#getSupportedHdrTypes()} instead
         */
        @Deprecated
        @HdrType
        public int[] getSupportedHdrTypes() {
            return Arrays.copyOf(mSupportedHdrTypes, mSupportedHdrTypes.length);
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
        public boolean equals(@Nullable Object other) {
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

        public static final @android.annotation.NonNull Creator<HdrCapabilities> CREATOR = new Creator<HdrCapabilities>() {
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

        @Override
        public String toString() {
            return "HdrCapabilities{"
                    + "mSupportedHdrTypes=" + Arrays.toString(mSupportedHdrTypes)
                    + ", mMaxLuminance=" + mMaxLuminance
                    + ", mMaxAverageLuminance=" + mMaxAverageLuminance
                    + ", mMinLuminance=" + mMinLuminance + '}';
        }

        /**
         * @hide
         */
        @NonNull
        public static String hdrTypeToString(int hdrType) {
            switch (hdrType) {
                case HDR_TYPE_DOLBY_VISION:
                    return "HDR_TYPE_DOLBY_VISION";
                case HDR_TYPE_HDR10:
                    return "HDR_TYPE_HDR10";
                case HDR_TYPE_HLG:
                    return "HDR_TYPE_HLG";
                case HDR_TYPE_HDR10_PLUS:
                    return "HDR_TYPE_HDR10_PLUS";
                default:
                    return "HDR_TYPE_INVALID";
            }
        }
    }

    private class HdrSdrRatioListenerWrapper implements DisplayManager.DisplayListener {
        Consumer<Display> mListener;
        float mLastReportedRatio = 1.f;

        private HdrSdrRatioListenerWrapper(Consumer<Display> listener) {
            mListener = listener;
        }

        @Override
        public void onDisplayAdded(int displayId) {
            // don't care
        }

        @Override
        public void onDisplayRemoved(int displayId) {
            // don't care
        }

        @Override
        public void onDisplayChanged(int displayId) {
            if (displayId == getDisplayId()) {
                float newRatio = getHdrSdrRatio();
                if (newRatio != mLastReportedRatio) {
                    mLastReportedRatio = newRatio;
                    mListener.accept(Display.this);
                }
            }
        }
    }
}
