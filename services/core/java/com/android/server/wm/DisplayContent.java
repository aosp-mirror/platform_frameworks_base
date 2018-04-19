/*
 * Copyright (C) 2012 The Android Open Source Project
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

package com.android.server.wm;

import static android.app.ActivityManager.SPLIT_SCREEN_CREATE_MODE_TOP_OR_LEFT;
import static android.app.WindowConfiguration.ACTIVITY_TYPE_HOME;
import static android.app.WindowConfiguration.ACTIVITY_TYPE_UNDEFINED;
import static android.app.WindowConfiguration.WINDOWING_MODE_FREEFORM;
import static android.app.WindowConfiguration.WINDOWING_MODE_FULLSCREEN;
import static android.app.WindowConfiguration.WINDOWING_MODE_PINNED;
import static android.app.WindowConfiguration.WINDOWING_MODE_SPLIT_SCREEN_PRIMARY;
import static android.content.pm.ActivityInfo.SCREEN_ORIENTATION_BEHIND;
import static android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSET;
import static android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED;
import static android.content.res.Configuration.ORIENTATION_LANDSCAPE;
import static android.content.res.Configuration.ORIENTATION_PORTRAIT;
import static android.view.Display.DEFAULT_DISPLAY;
import static android.view.Display.FLAG_PRIVATE;
import static android.view.Surface.ROTATION_0;
import static android.view.Surface.ROTATION_180;
import static android.view.Surface.ROTATION_270;
import static android.view.Surface.ROTATION_90;
import static android.view.View.GONE;
import static android.view.WindowManager.DOCKED_BOTTOM;
import static android.view.WindowManager.DOCKED_INVALID;
import static android.view.WindowManager.DOCKED_TOP;
import static android.view.WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE;
import static android.view.WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
import static android.view.WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL;
import static android.view.WindowManager.LayoutParams.FLAG_SECURE;
import static android.view.WindowManager.LayoutParams.FLAG_SHOW_WALLPAPER;
import static android.view.WindowManager.LayoutParams.NEEDS_MENU_SET_TRUE;
import static android.view.WindowManager.LayoutParams.NEEDS_MENU_UNSET;
import static android.view.WindowManager.LayoutParams.PRIVATE_FLAG_KEYGUARD;
import static android.view.WindowManager.LayoutParams.TYPE_APPLICATION;
import static android.view.WindowManager.LayoutParams.TYPE_APPLICATION_STARTING;
import static android.view.WindowManager.LayoutParams.TYPE_BOOT_PROGRESS;
import static android.view.WindowManager.LayoutParams.TYPE_DOCK_DIVIDER;
import static android.view.WindowManager.LayoutParams.TYPE_DRAWN_APPLICATION;
import static android.view.WindowManager.LayoutParams.TYPE_DREAM;
import static android.view.WindowManager.LayoutParams.TYPE_INPUT_METHOD;
import static android.view.WindowManager.LayoutParams.TYPE_INPUT_METHOD_DIALOG;
import static android.view.WindowManager.LayoutParams.TYPE_STATUS_BAR;
import static android.view.WindowManager.LayoutParams.TYPE_SYSTEM_DIALOG;
import static android.view.WindowManager.LayoutParams.TYPE_SYSTEM_ERROR;
import static android.view.WindowManager.LayoutParams.TYPE_TOAST;
import static android.view.WindowManager.LayoutParams.TYPE_WALLPAPER;
import static com.android.server.policy.WindowManagerPolicy.FINISH_LAYOUT_REDO_ANIM;
import static com.android.server.policy.WindowManagerPolicy.FINISH_LAYOUT_REDO_CONFIG;
import static com.android.server.policy.WindowManagerPolicy.FINISH_LAYOUT_REDO_LAYOUT;
import static com.android.server.policy.WindowManagerPolicy.FINISH_LAYOUT_REDO_WALLPAPER;
import static com.android.server.wm.utils.CoordinateTransforms.transformPhysicalToLogicalCoordinates;
import static android.view.WindowManager.TRANSIT_KEYGUARD_UNOCCLUDE;
import static com.android.server.wm.WindowManagerDebugConfig.DEBUG_ADD_REMOVE;
import static com.android.server.wm.WindowManagerDebugConfig.DEBUG_BOOT;
import static com.android.server.wm.WindowManagerDebugConfig.DEBUG_DISPLAY;
import static com.android.server.wm.WindowManagerDebugConfig.DEBUG_FOCUS;
import static com.android.server.wm.WindowManagerDebugConfig.DEBUG_FOCUS_LIGHT;
import static com.android.server.wm.WindowManagerDebugConfig.DEBUG_INPUT_METHOD;
import static com.android.server.wm.WindowManagerDebugConfig.DEBUG_LAYOUT;
import static com.android.server.wm.WindowManagerDebugConfig.DEBUG_LAYOUT_REPEATS;
import static com.android.server.wm.WindowManagerDebugConfig.DEBUG_ORIENTATION;
import static com.android.server.wm.WindowManagerDebugConfig.DEBUG_SCREENSHOT;
import static com.android.server.wm.WindowManagerDebugConfig.DEBUG_SCREEN_ON;
import static com.android.server.wm.WindowManagerDebugConfig.DEBUG_STACK;
import static com.android.server.wm.WindowManagerDebugConfig.DEBUG_TOKEN_MOVEMENT;
import static com.android.server.wm.WindowManagerDebugConfig.DEBUG_WALLPAPER;
import static com.android.server.wm.WindowManagerDebugConfig.DEBUG_WALLPAPER_LIGHT;
import static com.android.server.wm.WindowManagerDebugConfig.SHOW_STACK_CRAWLS;
import static com.android.server.wm.WindowManagerDebugConfig.SHOW_TRANSACTIONS;
import static com.android.server.wm.WindowManagerDebugConfig.TAG_WITH_CLASS_NAME;
import static com.android.server.wm.WindowManagerDebugConfig.TAG_WM;
import static com.android.server.wm.WindowManagerService.CUSTOM_SCREEN_ROTATION;
import static com.android.server.wm.WindowManagerService.H.SEND_NEW_CONFIGURATION;
import static com.android.server.wm.WindowManagerService.H.UPDATE_DOCKED_STACK_DIVIDER;
import static com.android.server.wm.WindowManagerService.H.WINDOW_HIDE_TIMEOUT;
import static com.android.server.wm.WindowManagerService.LAYOUT_REPEAT_THRESHOLD;
import static com.android.server.wm.WindowManagerService.MAX_ANIMATION_DURATION;
import static com.android.server.wm.WindowManagerService.SEAMLESS_ROTATION_TIMEOUT_DURATION;
import static com.android.server.wm.WindowManagerService.UPDATE_FOCUS_WILL_PLACE_SURFACES;
import static com.android.server.wm.WindowManagerService.WINDOWS_FREEZING_SCREENS_ACTIVE;
import static com.android.server.wm.WindowManagerService.WINDOWS_FREEZING_SCREENS_TIMEOUT;
import static com.android.server.wm.WindowManagerService.WINDOW_FREEZE_TIMEOUT_DURATION;
import static com.android.server.wm.WindowManagerService.dipToPixel;
import static com.android.server.wm.WindowManagerService.logSurface;
import static com.android.server.wm.WindowState.RESIZE_HANDLE_WIDTH_IN_DP;
import static com.android.server.wm.WindowStateAnimator.DRAW_PENDING;
import static com.android.server.wm.WindowStateAnimator.READY_TO_SHOW;
import static com.android.server.wm.WindowSurfacePlacer.SET_WALLPAPER_MAY_CHANGE;
import static com.android.server.wm.DisplayProto.ABOVE_APP_WINDOWS;
import static com.android.server.wm.DisplayProto.BELOW_APP_WINDOWS;
import static com.android.server.wm.DisplayProto.DISPLAY_FRAMES;
import static com.android.server.wm.DisplayProto.DISPLAY_INFO;
import static com.android.server.wm.DisplayProto.DOCKED_STACK_DIVIDER_CONTROLLER;
import static com.android.server.wm.DisplayProto.DPI;
import static com.android.server.wm.DisplayProto.ID;
import static com.android.server.wm.DisplayProto.IME_WINDOWS;
import static com.android.server.wm.DisplayProto.PINNED_STACK_CONTROLLER;
import static com.android.server.wm.DisplayProto.ROTATION;
import static com.android.server.wm.DisplayProto.SCREEN_ROTATION_ANIMATION;
import static com.android.server.wm.DisplayProto.STACKS;
import static com.android.server.wm.DisplayProto.WINDOW_CONTAINER;

import android.annotation.CallSuper;
import android.annotation.NonNull;
import android.content.pm.PackageManager;
import android.content.res.CompatibilityInfo;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.Matrix;
import android.graphics.Path;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Region;
import android.graphics.Region.Op;
import android.hardware.display.DisplayManagerInternal;
import android.os.Debug;
import android.os.Handler;
import android.os.IBinder;
import android.os.RemoteException;
import android.os.SystemClock;
import android.os.Trace;
import android.util.ArraySet;
import android.util.DisplayMetrics;
import android.util.Slog;
import android.util.proto.ProtoOutputStream;
import android.view.Display;
import android.view.DisplayCutout;
import android.view.DisplayInfo;
import android.view.InputDevice;
import android.view.MagnificationSpec;
import android.view.Surface;
import android.view.SurfaceControl;
import android.view.SurfaceControl.Transaction;
import android.view.SurfaceSession;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.util.ToBooleanFunction;
import com.android.internal.view.IInputMethodClient;
import com.android.server.policy.WindowManagerPolicy;
import com.android.server.wm.utils.RotationCache;
import com.android.server.wm.utils.WmDisplayCutout;

import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.Predicate;

/**
 * Utility class for keeping track of the WindowStates and other pertinent contents of a
 * particular Display.
 *
 * IMPORTANT: No method from this class should ever be used without holding
 * WindowManagerService.mWindowMap.
 */
class DisplayContent extends WindowContainer<DisplayContent.DisplayChildWindowContainer> {
    private static final String TAG = TAG_WITH_CLASS_NAME ? "DisplayContent" : TAG_WM;

    /** Unique identifier of this stack. */
    private final int mDisplayId;

    /** The containers below are the only child containers the display can have. */
    // Contains all window containers that are related to apps (Activities)
    private final TaskStackContainers mTaskStackContainers = new TaskStackContainers(mService);
    // Contains all non-app window containers that should be displayed above the app containers
    // (e.g. Status bar)
    private final AboveAppWindowContainers mAboveAppWindowsContainers =
            new AboveAppWindowContainers("mAboveAppWindowsContainers", mService);
    // Contains all non-app window containers that should be displayed below the app containers
    // (e.g. Wallpaper).
    private final NonAppWindowContainers mBelowAppWindowsContainers =
            new NonAppWindowContainers("mBelowAppWindowsContainers", mService);
    // Contains all IME window containers. Note that the z-ordering of the IME windows will depend
    // on the IME target. We mainly have this container grouping so we can keep track of all the IME
    // window containers together and move them in-sync if/when needed. We use a subclass of
    // WindowContainer which is omitted from screen magnification, as the IME is never magnified.
    private final NonMagnifiableWindowContainers mImeWindowsContainers =
            new NonMagnifiableWindowContainers("mImeWindowsContainers", mService);

    private WindowState mTmpWindow;
    private WindowState mTmpWindow2;
    private WindowAnimator mTmpWindowAnimator;
    private boolean mTmpRecoveringMemory;
    private boolean mUpdateImeTarget;
    private boolean mTmpInitial;
    private int mMaxUiWidth;

    // Mapping from a token IBinder to a WindowToken object on this display.
    private final HashMap<IBinder, WindowToken> mTokenMap = new HashMap();

    // Initial display metrics.
    int mInitialDisplayWidth = 0;
    int mInitialDisplayHeight = 0;
    int mInitialDisplayDensity = 0;

    DisplayCutout mInitialDisplayCutout;
    private final RotationCache<DisplayCutout, WmDisplayCutout> mDisplayCutoutCache
            = new RotationCache<>(this::calculateDisplayCutoutForRotationUncached);

    /**
     * Overridden display size. Initialized with {@link #mInitialDisplayWidth}
     * and {@link #mInitialDisplayHeight}, but can be set via shell command "adb shell wm size".
     * @see WindowManagerService#setForcedDisplaySize(int, int, int)
     */
    int mBaseDisplayWidth = 0;
    int mBaseDisplayHeight = 0;
    /**
     * Overridden display density for current user. Initialized with {@link #mInitialDisplayDensity}
     * but can be set from Settings or via shell command "adb shell wm density".
     * @see WindowManagerService#setForcedDisplayDensityForUser(int, int, int)
     */
    int mBaseDisplayDensity = 0;
    boolean mDisplayScalingDisabled;
    private final DisplayInfo mDisplayInfo = new DisplayInfo();
    private final Display mDisplay;
    private final DisplayMetrics mDisplayMetrics = new DisplayMetrics();
    DisplayFrames mDisplayFrames;

    /**
     * For default display it contains real metrics, empty for others.
     * @see WindowManagerService#createWatermarkInTransaction()
     */
    final DisplayMetrics mRealDisplayMetrics = new DisplayMetrics();
    /** @see #computeCompatSmallestWidth(boolean, int, int, int, int) */
    private final DisplayMetrics mTmpDisplayMetrics = new DisplayMetrics();

    /**
     * Compat metrics computed based on {@link #mDisplayMetrics}.
     * @see #updateDisplayAndOrientation(int)
     */
    private final DisplayMetrics mCompatDisplayMetrics = new DisplayMetrics();

    /** The desired scaling factor for compatible apps. */
    float mCompatibleScreenScale;

    /**
     * Current rotation of the display.
     * Constants as per {@link android.view.Surface.Rotation}.
     *
     * @see #updateRotationUnchecked()
     */
    private int mRotation = 0;

    /**
     * Last applied orientation of the display.
     * Constants as per {@link android.content.pm.ActivityInfo.ScreenOrientation}.
     *
     * @see WindowManagerService#updateOrientationFromAppTokensLocked(boolean, int)
     */
    private int mLastOrientation = SCREEN_ORIENTATION_UNSPECIFIED;

    /**
     * Flag indicating that the application is receiving an orientation that has different metrics
     * than it expected. E.g. Portrait instead of Landscape.
     *
     * @see #updateRotationUnchecked()
     */
    private boolean mAltOrientation = false;

    /**
     * Orientation forced by some window. If there is no visible window that specifies orientation
     * it is set to {@link android.content.pm.ActivityInfo#SCREEN_ORIENTATION_UNSPECIFIED}.
     *
     * @see NonAppWindowContainers#getOrientation()
     */
    private int mLastWindowForcedOrientation = SCREEN_ORIENTATION_UNSPECIFIED;

    /**
     * Last orientation forced by the keyguard. It is applied when keyguard is shown and is not
     * occluded.
     *
     * @see NonAppWindowContainers#getOrientation()
     */
    private int mLastKeyguardForcedOrientation = SCREEN_ORIENTATION_UNSPECIFIED;

    /**
     * Keep track of wallpaper visibility to notify changes.
     */
    private boolean mLastWallpaperVisible = false;

    private Rect mBaseDisplayRect = new Rect();

    // Accessed directly by all users.
    private boolean mLayoutNeeded;
    int pendingLayoutChanges;
    // TODO(multi-display): remove some of the usages.
    boolean isDefaultDisplay;
    /**
     * Flag indicating whether WindowManager should override info for this display in
     * DisplayManager.
     */
    boolean mShouldOverrideDisplayConfiguration = true;

    /** Window tokens that are in the process of exiting, but still on screen for animations. */
    final ArrayList<WindowToken> mExitingTokens = new ArrayList<>();

    /** Detect user tapping outside of current focused task bounds .*/
    TaskTapPointerEventListener mTapDetector;

    /** Detect user tapping outside of current focused stack bounds .*/
    private Region mTouchExcludeRegion = new Region();

    /** Save allocating when calculating rects */
    private final Rect mTmpRect = new Rect();
    private final Rect mTmpRect2 = new Rect();
    private final RectF mTmpRectF = new RectF();
    private final Matrix mTmpMatrix = new Matrix();
    private final Region mTmpRegion = new Region();

    /** Used for handing back size of display */
    private final Rect mTmpBounds = new Rect();

    /** Remove this display when animation on it has completed. */
    private boolean mDeferredRemoval;

    final DockedStackDividerController mDividerControllerLocked;
    final PinnedStackController mPinnedStackControllerLocked;

    final ArrayList<WindowState> mTapExcludedWindows = new ArrayList<>();
    /** A collection of windows that provide tap exclude regions inside of them. */
    final ArraySet<WindowState> mTapExcludeProvidingWindows = new ArraySet<>();

    private boolean mHaveBootMsg = false;
    private boolean mHaveApp = false;
    private boolean mHaveWallpaper = false;
    private boolean mHaveKeyguard = true;

    private final LinkedList<AppWindowToken> mTmpUpdateAllDrawn = new LinkedList();

    private final TaskForResizePointSearchResult mTmpTaskForResizePointSearchResult =
            new TaskForResizePointSearchResult();
    private final ApplySurfaceChangesTransactionState mTmpApplySurfaceChangesTransactionState =
            new ApplySurfaceChangesTransactionState();

    // True if this display is in the process of being removed. Used to determine if the removal of
    // the display's direct children should be allowed.
    private boolean mRemovingDisplay = false;

    // {@code false} if this display is in the processing of being created.
    private boolean mDisplayReady = false;

    WallpaperController mWallpaperController;

    private final SurfaceSession mSession = new SurfaceSession();

    /**
     * We organize all top-level Surfaces in to the following layers.
     * mOverlayLayer contains a few Surfaces which are always on top of others
     * and omitted from Screen-Magnification, for example the strict mode flash or
     * the magnification overlay itself.
     * {@link #mWindowingLayer} contains everything else.
     */
    private SurfaceControl mOverlayLayer;

    /**
     * See {@link #mOverlayLayer}
     */
    private SurfaceControl mWindowingLayer;

    /**
     * Specifies the size of the surfaces in {@link #mOverlayLayer} and {@link #mWindowingLayer}.
     * <p>
     * For these surfaces currently we use a surface based on the larger of width or height so we
     * don't have to resize when rotating the display.
     */
    private int mSurfaceSize;

    /**
     * Sequence number for the current layout pass.
     */
    int mLayoutSeq = 0;

    /** Temporary float array to retrieve 3x3 matrix values. */
    private final float[] mTmpFloats = new float[9];

    private final Consumer<WindowState> mUpdateWindowsForAnimator = w -> {
        WindowStateAnimator winAnimator = w.mWinAnimator;
        final AppWindowToken atoken = w.mAppToken;
        if (winAnimator.mDrawState == READY_TO_SHOW) {
            if (atoken == null || atoken.allDrawn) {
                if (w.performShowLocked()) {
                    pendingLayoutChanges |= FINISH_LAYOUT_REDO_ANIM;
                    if (DEBUG_LAYOUT_REPEATS) {
                        mService.mWindowPlacerLocked.debugLayoutRepeats(
                                "updateWindowsAndWallpaperLocked 5", pendingLayoutChanges);
                    }
                }
            }
        }
    };

    private final Consumer<WindowState> mUpdateWallpaperForAnimator = w -> {
        final WindowStateAnimator winAnimator = w.mWinAnimator;
        if (winAnimator.mSurfaceController == null || !winAnimator.hasSurface()) {
            return;
        }

        final int flags = w.mAttrs.flags;

        // If this window is animating, make a note that we have an animating window and take
        // care of a request to run a detached wallpaper animation.
        if (winAnimator.isAnimationSet()) {
            final AnimationAdapter anim = w.getAnimation();
            if (anim != null) {
                if ((flags & FLAG_SHOW_WALLPAPER) != 0 && anim.getDetachWallpaper()) {
                    mTmpWindow = w;
                }
                final int color = anim.getBackgroundColor();
                if (color != 0) {
                    final TaskStack stack = w.getStack();
                    if (stack != null) {
                        stack.setAnimationBackground(winAnimator, color);
                    }
                }
            }
        }

        // If this window's app token is running a detached wallpaper animation, make a note so
        // we can ensure the wallpaper is displayed behind it.
        final AppWindowToken atoken = winAnimator.mWin.mAppToken;
        final AnimationAdapter animation = atoken != null ? atoken.getAnimation() : null;
        if (animation != null) {
            if ((flags & FLAG_SHOW_WALLPAPER) != 0 && animation.getDetachWallpaper()) {
                mTmpWindow = w;
            }

            final int color = animation.getBackgroundColor();
            if (color != 0) {
                final TaskStack stack = w.getStack();
                if (stack != null) {
                    stack.setAnimationBackground(winAnimator, color);
                }
            }
        }
    };

    private final Consumer<WindowState> mScheduleToastTimeout = w -> {
        final int lostFocusUid = mTmpWindow.mOwnerUid;
        final Handler handler = mService.mH;
        if (w.mAttrs.type == TYPE_TOAST && w.mOwnerUid == lostFocusUid) {
            if (!handler.hasMessages(WINDOW_HIDE_TIMEOUT, w)) {
                handler.sendMessageDelayed(handler.obtainMessage(WINDOW_HIDE_TIMEOUT, w),
                        w.mAttrs.hideTimeoutMilliseconds);
            }
        }
    };

    private final ToBooleanFunction<WindowState> mFindFocusedWindow = w -> {
        final AppWindowToken focusedApp = mService.mFocusedApp;
        if (DEBUG_FOCUS) Slog.v(TAG_WM, "Looking for focus: " + w
                + ", flags=" + w.mAttrs.flags + ", canReceive=" + w.canReceiveKeys());

        if (!w.canReceiveKeys()) {
            return false;
        }

        final AppWindowToken wtoken = w.mAppToken;

        // If this window's application has been removed, just skip it.
        if (wtoken != null && (wtoken.removed || wtoken.sendingToBottom)) {
            if (DEBUG_FOCUS) Slog.v(TAG_WM, "Skipping " + wtoken + " because "
                    + (wtoken.removed ? "removed" : "sendingToBottom"));
            return false;
        }

        if (focusedApp == null) {
            if (DEBUG_FOCUS_LIGHT) Slog.v(TAG_WM, "findFocusedWindow: focusedApp=null"
                    + " using new focus @ " + w);
            mTmpWindow = w;
            return true;
        }

        if (!focusedApp.windowsAreFocusable()) {
            // Current focused app windows aren't focusable...
            if (DEBUG_FOCUS_LIGHT) Slog.v(TAG_WM, "findFocusedWindow: focusedApp windows not"
                    + " focusable using new focus @ " + w);
            mTmpWindow = w;
            return true;
        }

        // Descend through all of the app tokens and find the first that either matches
        // win.mAppToken (return win) or mFocusedApp (return null).
        if (wtoken != null && w.mAttrs.type != TYPE_APPLICATION_STARTING) {
            if (focusedApp.compareTo(wtoken) > 0) {
                // App stack below focused app stack. No focus for you!!!
                if (DEBUG_FOCUS_LIGHT) Slog.v(TAG_WM,
                        "findFocusedWindow: Reached focused app=" + focusedApp);
                mTmpWindow = null;
                return true;
            }
        }

        if (DEBUG_FOCUS_LIGHT) Slog.v(TAG_WM, "findFocusedWindow: Found new focus @ " + w);
        mTmpWindow = w;
        return true;
    };

    private final Consumer<WindowState> mPerformLayout = w -> {
        // Don't do layout of a window if it is not visible, or soon won't be visible, to avoid
        // wasting time and funky changes while a window is animating away.
        final boolean gone = (mTmpWindow != null && mService.mPolicy.canBeHiddenByKeyguardLw(w))
                || w.isGoneForLayoutLw();

        if (DEBUG_LAYOUT && !w.mLayoutAttached) {
            Slog.v(TAG, "1ST PASS " + w + ": gone=" + gone + " mHaveFrame=" + w.mHaveFrame
                    + " mLayoutAttached=" + w.mLayoutAttached
                    + " screen changed=" + w.isConfigChanged());
            final AppWindowToken atoken = w.mAppToken;
            if (gone) Slog.v(TAG, "  GONE: mViewVisibility=" + w.mViewVisibility
                    + " mRelayoutCalled=" + w.mRelayoutCalled + " hidden=" + w.mToken.isHidden()
                    + " hiddenRequested=" + (atoken != null && atoken.hiddenRequested)
                    + " parentHidden=" + w.isParentWindowHidden());
            else Slog.v(TAG, "  VIS: mViewVisibility=" + w.mViewVisibility
                    + " mRelayoutCalled=" + w.mRelayoutCalled + " hidden=" + w.mToken.isHidden()
                    + " hiddenRequested=" + (atoken != null && atoken.hiddenRequested)
                    + " parentHidden=" + w.isParentWindowHidden());
        }

        // If this view is GONE, then skip it -- keep the current frame, and let the caller know
        // so they can ignore it if they want.  (We do the normal layout for INVISIBLE windows,
        // since that means "perform layout as normal, just don't display").
        if (!gone || !w.mHaveFrame || w.mLayoutNeeded
                || ((w.isConfigChanged() || w.setReportResizeHints())
                && !w.isGoneForLayoutLw() &&
                ((w.mAttrs.privateFlags & PRIVATE_FLAG_KEYGUARD) != 0 ||
                        (w.mHasSurface && w.mAppToken != null &&
                                w.mAppToken.layoutConfigChanges)))) {
            if (!w.mLayoutAttached) {
                if (mTmpInitial) {
                    //Slog.i(TAG, "Window " + this + " clearing mContentChanged - initial");
                    w.mContentChanged = false;
                }
                if (w.mAttrs.type == TYPE_DREAM) {
                    // Don't layout windows behind a dream, so that if it does stuff like hide
                    // the status bar we won't get a bad transition when it goes away.
                    mTmpWindow = w;
                }
                w.mLayoutNeeded = false;
                w.prelayout();
                final boolean firstLayout = !w.isLaidOut();
                mService.mPolicy.layoutWindowLw(w, null, mDisplayFrames);
                w.mLayoutSeq = mLayoutSeq;

                // If this is the first layout, we need to initialize the last inset values as
                // otherwise we'd immediately cause an unnecessary resize.
                if (firstLayout) {
                    w.updateLastInsetValues();
                }

                if (w.mAppToken != null) {
                    w.mAppToken.layoutLetterbox(w);
                }

                if (DEBUG_LAYOUT) Slog.v(TAG, "  LAYOUT: mFrame=" + w.mFrame
                        + " mContainingFrame=" + w.mContainingFrame
                        + " mDisplayFrame=" + w.mDisplayFrame);
            }
        }
    };

    private final Consumer<WindowState> mPerformLayoutAttached = w -> {
        if (w.mLayoutAttached) {
            if (DEBUG_LAYOUT) Slog.v(TAG, "2ND PASS " + w + " mHaveFrame=" + w.mHaveFrame
                    + " mViewVisibility=" + w.mViewVisibility
                    + " mRelayoutCalled=" + w.mRelayoutCalled);
            // If this view is GONE, then skip it -- keep the current frame, and let the caller
            // know so they can ignore it if they want.  (We do the normal layout for INVISIBLE
            // windows, since that means "perform layout as normal, just don't display").
            if (mTmpWindow != null && mService.mPolicy.canBeHiddenByKeyguardLw(w)) {
                return;
            }
            if ((w.mViewVisibility != GONE && w.mRelayoutCalled) || !w.mHaveFrame
                    || w.mLayoutNeeded) {
                if (mTmpInitial) {
                    //Slog.i(TAG, "Window " + this + " clearing mContentChanged - initial");
                    w.mContentChanged = false;
                }
                w.mLayoutNeeded = false;
                w.prelayout();
                mService.mPolicy.layoutWindowLw(w, w.getParentWindow(), mDisplayFrames);
                w.mLayoutSeq = mLayoutSeq;
                if (DEBUG_LAYOUT) Slog.v(TAG, " LAYOUT: mFrame=" + w.mFrame
                        + " mContainingFrame=" + w.mContainingFrame
                        + " mDisplayFrame=" + w.mDisplayFrame);
            }
        } else if (w.mAttrs.type == TYPE_DREAM) {
            // Don't layout windows behind a dream, so that if it does stuff like hide the
            // status bar we won't get a bad transition when it goes away.
            mTmpWindow = mTmpWindow2;
        }
    };

    private final Predicate<WindowState> mComputeImeTargetPredicate = w -> {
        if (DEBUG_INPUT_METHOD && mUpdateImeTarget) Slog.i(TAG_WM, "Checking window @" + w
                + " fl=0x" + Integer.toHexString(w.mAttrs.flags));
        return w.canBeImeTarget();
    };

    private final Consumer<WindowState> mApplyPostLayoutPolicy =
            w -> mService.mPolicy.applyPostLayoutPolicyLw(w, w.mAttrs, w.getParentWindow(),
                    mService.mInputMethodTarget);

    private final Consumer<WindowState> mApplySurfaceChangesTransaction = w -> {
        final WindowSurfacePlacer surfacePlacer = mService.mWindowPlacerLocked;
        final boolean obscuredChanged = w.mObscured !=
                mTmpApplySurfaceChangesTransactionState.obscured;
        final RootWindowContainer root = mService.mRoot;
        // Only used if default window
        final boolean someoneLosingFocus = !mService.mLosingFocus.isEmpty();

        // Update effect.
        w.mObscured = mTmpApplySurfaceChangesTransactionState.obscured;
        if (!mTmpApplySurfaceChangesTransactionState.obscured) {
            final boolean isDisplayed = w.isDisplayedLw();

            if (isDisplayed && w.isObscuringDisplay()) {
                // This window completely covers everything behind it, so we want to leave all
                // of them as undimmed (for performance reasons).
                root.mObscuringWindow = w;
                mTmpApplySurfaceChangesTransactionState.obscured = true;
            }

            mTmpApplySurfaceChangesTransactionState.displayHasContent |=
                    root.handleNotObscuredLocked(w,
                            mTmpApplySurfaceChangesTransactionState.obscured,
                            mTmpApplySurfaceChangesTransactionState.syswin);

            if (w.mHasSurface && isDisplayed) {
                final int type = w.mAttrs.type;
                if (type == TYPE_SYSTEM_DIALOG || type == TYPE_SYSTEM_ERROR
                        || (w.mAttrs.privateFlags & PRIVATE_FLAG_KEYGUARD) != 0) {
                    mTmpApplySurfaceChangesTransactionState.syswin = true;
                }
                if (mTmpApplySurfaceChangesTransactionState.preferredRefreshRate == 0
                        && w.mAttrs.preferredRefreshRate != 0) {
                    mTmpApplySurfaceChangesTransactionState.preferredRefreshRate
                            = w.mAttrs.preferredRefreshRate;
                }
                if (mTmpApplySurfaceChangesTransactionState.preferredModeId == 0
                        && w.mAttrs.preferredDisplayModeId != 0) {
                    mTmpApplySurfaceChangesTransactionState.preferredModeId
                            = w.mAttrs.preferredDisplayModeId;
                }
            }
        }

        if (isDefaultDisplay && obscuredChanged && w.isVisibleLw()
                && mWallpaperController.isWallpaperTarget(w)) {
            // This is the wallpaper target and its obscured state changed... make sure the
            // current wallpaper's visibility has been updated accordingly.
            mWallpaperController.updateWallpaperVisibility();
        }

        w.handleWindowMovedIfNeeded();

        final WindowStateAnimator winAnimator = w.mWinAnimator;

        //Slog.i(TAG, "Window " + this + " clearing mContentChanged - done placing");
        w.mContentChanged = false;

        // Moved from updateWindowsAndWallpaperLocked().
        if (w.mHasSurface) {
            // Take care of the window being ready to display.
            final boolean committed = winAnimator.commitFinishDrawingLocked();
            if (isDefaultDisplay && committed) {
                if (w.mAttrs.type == TYPE_DREAM) {
                    // HACK: When a dream is shown, it may at that point hide the lock screen.
                    // So we need to redo the layout to let the phone window manager make this
                    // happen.
                    pendingLayoutChanges |= FINISH_LAYOUT_REDO_LAYOUT;
                    if (DEBUG_LAYOUT_REPEATS) {
                        surfacePlacer.debugLayoutRepeats(
                                "dream and commitFinishDrawingLocked true",
                                pendingLayoutChanges);
                    }
                }
                if ((w.mAttrs.flags & FLAG_SHOW_WALLPAPER) != 0) {
                    if (DEBUG_WALLPAPER_LIGHT) Slog.v(TAG,
                            "First draw done in potential wallpaper target " + w);
                    root.mWallpaperMayChange = true;
                    pendingLayoutChanges |= FINISH_LAYOUT_REDO_WALLPAPER;
                    if (DEBUG_LAYOUT_REPEATS) {
                        surfacePlacer.debugLayoutRepeats(
                                "wallpaper and commitFinishDrawingLocked true",
                                pendingLayoutChanges);
                    }
                }
            }
        }

        final AppWindowToken atoken = w.mAppToken;
        if (atoken != null) {
            atoken.updateLetterboxSurface(w);
            final boolean updateAllDrawn = atoken.updateDrawnWindowStates(w);
            if (updateAllDrawn && !mTmpUpdateAllDrawn.contains(atoken)) {
                mTmpUpdateAllDrawn.add(atoken);
            }
        }

        if (isDefaultDisplay && someoneLosingFocus && w == mService.mCurrentFocus
                && w.isDisplayedLw()) {
            mTmpApplySurfaceChangesTransactionState.focusDisplayed = true;
        }

        w.updateResizingWindowIfNeeded();
    };

    /**
     * Create new {@link DisplayContent} instance, add itself to the root window container and
     * initialize direct children.
     * @param display May not be null.
     * @param service You know.
     * @param wallpaperController wallpaper windows controller used to adjust the positioning of the
     *                            wallpaper windows in the window list.
     */
    DisplayContent(Display display, WindowManagerService service,
            WallpaperController wallpaperController, DisplayWindowController controller) {
        super(service);
        setController(controller);
        if (service.mRoot.getDisplayContent(display.getDisplayId()) != null) {
            throw new IllegalArgumentException("Display with ID=" + display.getDisplayId()
                    + " already exists=" + service.mRoot.getDisplayContent(display.getDisplayId())
                    + " new=" + display);
        }

        mDisplay = display;
        mDisplayId = display.getDisplayId();
        mWallpaperController = wallpaperController;
        display.getDisplayInfo(mDisplayInfo);
        display.getMetrics(mDisplayMetrics);
        isDefaultDisplay = mDisplayId == DEFAULT_DISPLAY;
        mDisplayFrames = new DisplayFrames(mDisplayId, mDisplayInfo,
                calculateDisplayCutoutForRotation(mDisplayInfo.rotation));
        initializeDisplayBaseInfo();
        mDividerControllerLocked = new DockedStackDividerController(service, this);
        mPinnedStackControllerLocked = new PinnedStackController(service, this);

        // We use this as our arbitrary surface size for buffer-less parents
        // that don't impose cropping on their children. It may need to be larger
        // than the display size because fullscreen windows can be shifted offscreen
        // due to surfaceInsets. 2 times the largest display dimension feels like an
        // appropriately arbitrary number. Eventually we would like to give SurfaceFlinger
        // layers the ability to match their parent sizes and be able to skip
        // such arbitrary size settings.
        mSurfaceSize = Math.max(mBaseDisplayHeight, mBaseDisplayWidth) * 2;

        final SurfaceControl.Builder b = mService.makeSurfaceBuilder(mSession)
                .setSize(mSurfaceSize, mSurfaceSize)
                .setOpaque(true);
        mWindowingLayer = b.setName("Display Root").build();
        mOverlayLayer = b.setName("Display Overlays").build();

        getPendingTransaction().setLayer(mWindowingLayer, 0)
                .setLayerStack(mWindowingLayer, mDisplayId)
                .show(mWindowingLayer)
                .setLayer(mOverlayLayer, 1)
                .setLayerStack(mOverlayLayer, mDisplayId)
                .show(mOverlayLayer);
        getPendingTransaction().apply();

        // These are the only direct children we should ever have and they are permanent.
        super.addChild(mBelowAppWindowsContainers, null);
        super.addChild(mTaskStackContainers, null);
        super.addChild(mAboveAppWindowsContainers, null);
        super.addChild(mImeWindowsContainers, null);

        // Add itself as a child to the root container.
        mService.mRoot.addChild(this, null);

        // TODO(b/62541591): evaluate whether this is the best spot to declare the
        // {@link DisplayContent} ready for use.
        mDisplayReady = true;
    }

    boolean isReady() {
        // The display is ready when the system and the individual display are both ready.
        return mService.mDisplayReady && mDisplayReady;
    }

    int getDisplayId() {
        return mDisplayId;
    }

    WindowToken getWindowToken(IBinder binder) {
        return mTokenMap.get(binder);
    }

    AppWindowToken getAppWindowToken(IBinder binder) {
        final WindowToken token = getWindowToken(binder);
        if (token == null) {
            return null;
        }
        return token.asAppWindowToken();
    }

    private void addWindowToken(IBinder binder, WindowToken token) {
        final DisplayContent dc = mService.mRoot.getWindowTokenDisplay(token);
        if (dc != null) {
            // We currently don't support adding a window token to the display if the display
            // already has the binder mapped to another token. If there is a use case for supporting
            // this moving forward we will either need to merge the WindowTokens some how or have
            // the binder map to a list of window tokens.
            throw new IllegalArgumentException("Can't map token=" + token + " to display="
                    + getName() + " already mapped to display=" + dc + " tokens=" + dc.mTokenMap);
        }
        if (binder == null) {
            throw new IllegalArgumentException("Can't map token=" + token + " to display="
                    + getName() + " binder is null");
        }
        if (token == null) {
            throw new IllegalArgumentException("Can't map null token to display="
                    + getName() + " binder=" + binder);
        }

        mTokenMap.put(binder, token);

        if (token.asAppWindowToken() == null) {
            // Add non-app token to container hierarchy on the display. App tokens are added through
            // the parent container managing them (e.g. Tasks).
            switch (token.windowType) {
                case TYPE_WALLPAPER:
                    mBelowAppWindowsContainers.addChild(token);
                    break;
                case TYPE_INPUT_METHOD:
                case TYPE_INPUT_METHOD_DIALOG:
                    mImeWindowsContainers.addChild(token);
                    break;
                default:
                    mAboveAppWindowsContainers.addChild(token);
                    break;
            }
        }
    }

    WindowToken removeWindowToken(IBinder binder) {
        final WindowToken token = mTokenMap.remove(binder);
        if (token != null && token.asAppWindowToken() == null) {
            token.setExiting();
        }
        return token;
    }

    /** Changes the display the input window token is housed on to this one. */
    void reParentWindowToken(WindowToken token) {
        final DisplayContent prevDc = token.getDisplayContent();
        if (prevDc == this) {
            return;
        }
        if (prevDc != null && prevDc.mTokenMap.remove(token.token) != null
                && token.asAppWindowToken() == null) {
            // Removed the token from the map, but made sure it's not an app token before removing
            // from parent.
            token.getParent().removeChild(token);
        }

        addWindowToken(token.token, token);
    }

    void removeAppToken(IBinder binder) {
        final WindowToken token = removeWindowToken(binder);
        if (token == null) {
            Slog.w(TAG_WM, "removeAppToken: Attempted to remove non-existing token: " + binder);
            return;
        }

        final AppWindowToken appToken = token.asAppWindowToken();

        if (appToken == null) {
            Slog.w(TAG_WM, "Attempted to remove non-App token: " + binder + " token=" + token);
            return;
        }

        appToken.onRemovedFromDisplay();
    }

    Display getDisplay() {
        return mDisplay;
    }

    DisplayInfo getDisplayInfo() {
        return mDisplayInfo;
    }

    DisplayMetrics getDisplayMetrics() {
        return mDisplayMetrics;
    }

    int getRotation() {
        return mRotation;
    }

    @VisibleForTesting
    void setRotation(int newRotation) {
        mRotation = newRotation;
    }

    int getLastOrientation() {
        return mLastOrientation;
    }

    void setLastOrientation(int orientation) {
        mLastOrientation = orientation;
    }

    boolean getAltOrientation() {
        return mAltOrientation;
    }

    void setAltOrientation(boolean altOrientation) {
        mAltOrientation = altOrientation;
    }

    int getLastWindowForcedOrientation() {
        return mLastWindowForcedOrientation;
    }

    /**
     * Update rotation of the display.
     *
     * Returns true if the rotation has been changed.  In this case YOU MUST CALL
     * {@link WindowManagerService#sendNewConfiguration(int)} TO UNFREEZE THE SCREEN.
     */
    boolean updateRotationUnchecked() {
        if (mService.mDeferredRotationPauseCount > 0) {
            // Rotation updates have been paused temporarily.  Defer the update until
            // updates have been resumed.
            if (DEBUG_ORIENTATION) Slog.v(TAG_WM, "Deferring rotation, rotation is paused.");
            return false;
        }

        ScreenRotationAnimation screenRotationAnimation =
                mService.mAnimator.getScreenRotationAnimationLocked(mDisplayId);
        if (screenRotationAnimation != null && screenRotationAnimation.isAnimating()) {
            // Rotation updates cannot be performed while the previous rotation change
            // animation is still in progress.  Skip this update.  We will try updating
            // again after the animation is finished and the display is unfrozen.
            if (DEBUG_ORIENTATION) Slog.v(TAG_WM, "Deferring rotation, animation in progress.");
            return false;
        }
        if (mService.mDisplayFrozen) {
            // Even if the screen rotation animation has finished (e.g. isAnimating
            // returns false), there is still some time where we haven't yet unfrozen
            // the display. We also need to abort rotation here.
            if (DEBUG_ORIENTATION) Slog.v(TAG_WM,
                    "Deferring rotation, still finishing previous rotation");
            return false;
        }

        if (!mService.mDisplayEnabled) {
            // No point choosing a rotation if the display is not enabled.
            if (DEBUG_ORIENTATION) Slog.v(TAG_WM, "Deferring rotation, display is not enabled.");
            return false;
        }

        final int oldRotation = mRotation;
        final int lastOrientation = mLastOrientation;
        final boolean oldAltOrientation = mAltOrientation;
        final int rotation = mService.mPolicy.rotationForOrientationLw(lastOrientation, oldRotation,
                isDefaultDisplay);
        if (DEBUG_ORIENTATION) Slog.v(TAG_WM, "Computed rotation=" + rotation + " for display id="
                + mDisplayId + " based on lastOrientation=" + lastOrientation
                + " and oldRotation=" + oldRotation);
        boolean mayRotateSeamlessly = mService.mPolicy.shouldRotateSeamlessly(oldRotation,
                rotation);

        if (mayRotateSeamlessly) {
            final WindowState seamlessRotated = getWindow((w) -> w.mSeamlesslyRotated);
            if (seamlessRotated != null) {
                // We can't rotate (seamlessly or not) while waiting for the last seamless rotation
                // to complete (that is, waiting for windows to redraw). It's tempting to check
                // w.mSeamlessRotationCount but that could be incorrect in the case of
                // window-removal.
                return false;
            }

            // In the presence of the PINNED stack or System Alert
            // windows we unfortunately can not seamlessly rotate.
            if (hasPinnedStack()) {
                mayRotateSeamlessly = false;
            }
            for (int i = 0; i < mService.mSessions.size(); i++) {
                if (mService.mSessions.valueAt(i).hasAlertWindowSurfaces()) {
                    mayRotateSeamlessly = false;
                    break;
                }
            }
        }
        final boolean rotateSeamlessly = mayRotateSeamlessly;

        // TODO: Implement forced rotation changes.
        //       Set mAltOrientation to indicate that the application is receiving
        //       an orientation that has different metrics than it expected.
        //       eg. Portrait instead of Landscape.

        final boolean altOrientation = !mService.mPolicy.rotationHasCompatibleMetricsLw(
                lastOrientation, rotation);

        if (DEBUG_ORIENTATION) Slog.v(TAG_WM, "Display id=" + mDisplayId
                + " selected orientation " + lastOrientation
                + ", got rotation " + rotation + " which has "
                + (altOrientation ? "incompatible" : "compatible") + " metrics");

        if (oldRotation == rotation && oldAltOrientation == altOrientation) {
            // No change.
            return false;
        }

        if (DEBUG_ORIENTATION) Slog.v(TAG_WM, "Display id=" + mDisplayId
                + " rotation changed to " + rotation
                + (altOrientation ? " (alt)" : "") + " from " + oldRotation
                + (oldAltOrientation ? " (alt)" : "") + ", lastOrientation=" + lastOrientation);

        if (DisplayContent.deltaRotation(rotation, oldRotation) != 2) {
            mService.mWaitingForConfig = true;
        }

        mRotation = rotation;
        mAltOrientation = altOrientation;
        if (isDefaultDisplay) {
            mService.mPolicy.setRotationLw(rotation);
        }

        mService.mWindowsFreezingScreen = WINDOWS_FREEZING_SCREENS_ACTIVE;
        mService.mH.removeMessages(WindowManagerService.H.WINDOW_FREEZE_TIMEOUT);
        mService.mH.sendEmptyMessageDelayed(WindowManagerService.H.WINDOW_FREEZE_TIMEOUT,
                WINDOW_FREEZE_TIMEOUT_DURATION);

        setLayoutNeeded();
        final int[] anim = new int[2];
        mService.mPolicy.selectRotationAnimationLw(anim);

        if (!rotateSeamlessly) {
            mService.startFreezingDisplayLocked(anim[0], anim[1], this);
            // startFreezingDisplayLocked can reset the ScreenRotationAnimation.
            screenRotationAnimation = mService.mAnimator.getScreenRotationAnimationLocked(
                    mDisplayId);
        } else {
            // The screen rotation animation uses a screenshot to freeze the screen
            // while windows resize underneath.
            // When we are rotating seamlessly, we allow the elements to transition
            // to their rotated state independently and without a freeze required.
            screenRotationAnimation = null;

            mService.startSeamlessRotation();
        }

        // We need to update our screen size information to match the new rotation. If the rotation
        // has actually changed then this method will return true and, according to the comment at
        // the top of the method, the caller is obligated to call computeNewConfigurationLocked().
        // By updating the Display info here it will be available to
        // #computeScreenConfiguration() later.
        updateDisplayAndOrientation(getConfiguration().uiMode);

        // NOTE: We disable the rotation in the emulator because
        //       it doesn't support hardware OpenGL emulation yet.
        if (CUSTOM_SCREEN_ROTATION && screenRotationAnimation != null
                && screenRotationAnimation.hasScreenshot()) {
            if (screenRotationAnimation.setRotation(getPendingTransaction(), rotation,
                    MAX_ANIMATION_DURATION, mService.getTransitionAnimationScaleLocked(),
                    mDisplayInfo.logicalWidth, mDisplayInfo.logicalHeight)) {
                mService.scheduleAnimationLocked();
            }
        }

        if (rotateSeamlessly) {
            forAllWindows(w -> {
                    w.mWinAnimator.seamlesslyRotateWindow(getPendingTransaction(),
                            oldRotation, rotation);
            }, true /* traverseTopToBottom */);
        }

        mService.mDisplayManagerInternal.performTraversal(getPendingTransaction());
        scheduleAnimation();

        forAllWindows(w -> {
            if (w.mHasSurface && !rotateSeamlessly) {
                if (DEBUG_ORIENTATION) Slog.v(TAG_WM, "Set mOrientationChanging of " + w);
                w.setOrientationChanging(true);
                mService.mRoot.mOrientationChangeComplete = false;
                w.mLastFreezeDuration = 0;
            }
            w.mReportOrientationChanged = true;
        }, true /* traverseTopToBottom */);

        if (rotateSeamlessly) {
            mService.mH.removeMessages(WindowManagerService.H.SEAMLESS_ROTATION_TIMEOUT);
            mService.mH.sendEmptyMessageDelayed(WindowManagerService.H.SEAMLESS_ROTATION_TIMEOUT,
                    SEAMLESS_ROTATION_TIMEOUT_DURATION);
        }

        for (int i = mService.mRotationWatchers.size() - 1; i >= 0; i--) {
            final WindowManagerService.RotationWatcher rotationWatcher
                    = mService.mRotationWatchers.get(i);
            if (rotationWatcher.mDisplayId == mDisplayId) {
                try {
                    rotationWatcher.mWatcher.onRotationChanged(rotation);
                } catch (RemoteException e) {
                    // Ignore
                }
            }
        }

        // TODO (multi-display): Magnification is supported only for the default display.
        // Announce rotation only if we will not animate as we already have the
        // windows in final state. Otherwise, we make this call at the rotation end.
        if (screenRotationAnimation == null && mService.mAccessibilityController != null
                && isDefaultDisplay) {
            mService.mAccessibilityController.onRotationChangedLocked(this);
        }

        return true;
    }

    void configureDisplayPolicy() {
        mService.mPolicy.setInitialDisplaySize(getDisplay(),
                mBaseDisplayWidth, mBaseDisplayHeight, mBaseDisplayDensity);

        mDisplayFrames.onDisplayInfoUpdated(mDisplayInfo,
                calculateDisplayCutoutForRotation(mDisplayInfo.rotation));
    }

    /**
     * Update {@link #mDisplayInfo} and other internal variables when display is rotated or config
     * changed.
     * Do not call if {@link WindowManagerService#mDisplayReady} == false.
     */
    private DisplayInfo updateDisplayAndOrientation(int uiMode) {
        // Use the effective "visual" dimensions based on current rotation
        final boolean rotated = (mRotation == ROTATION_90 || mRotation == ROTATION_270);
        final int realdw = rotated ? mBaseDisplayHeight : mBaseDisplayWidth;
        final int realdh = rotated ? mBaseDisplayWidth : mBaseDisplayHeight;
        int dw = realdw;
        int dh = realdh;

        if (mAltOrientation) {
            if (realdw > realdh) {
                // Turn landscape into portrait.
                int maxw = (int)(realdh/1.3f);
                if (maxw < realdw) {
                    dw = maxw;
                }
            } else {
                // Turn portrait into landscape.
                int maxh = (int)(realdw/1.3f);
                if (maxh < realdh) {
                    dh = maxh;
                }
            }
        }

        // Update application display metrics.
        final WmDisplayCutout wmDisplayCutout = calculateDisplayCutoutForRotation(mRotation);
        final DisplayCutout displayCutout = wmDisplayCutout.getDisplayCutout();

        final int appWidth = mService.mPolicy.getNonDecorDisplayWidth(dw, dh, mRotation, uiMode,
                mDisplayId, displayCutout);
        final int appHeight = mService.mPolicy.getNonDecorDisplayHeight(dw, dh, mRotation, uiMode,
                mDisplayId, displayCutout);
        mDisplayInfo.rotation = mRotation;
        mDisplayInfo.logicalWidth = dw;
        mDisplayInfo.logicalHeight = dh;
        mDisplayInfo.logicalDensityDpi = mBaseDisplayDensity;
        mDisplayInfo.appWidth = appWidth;
        mDisplayInfo.appHeight = appHeight;
        if (isDefaultDisplay) {
            mDisplayInfo.getLogicalMetrics(mRealDisplayMetrics,
                    CompatibilityInfo.DEFAULT_COMPATIBILITY_INFO, null);
        }
        mDisplayInfo.displayCutout = displayCutout.isEmpty() ? null : displayCutout;
        mDisplayInfo.getAppMetrics(mDisplayMetrics);
        if (mDisplayScalingDisabled) {
            mDisplayInfo.flags |= Display.FLAG_SCALING_DISABLED;
        } else {
            mDisplayInfo.flags &= ~Display.FLAG_SCALING_DISABLED;
        }

        // We usually set the override info in DisplayManager so that we get consistent display
        // metrics values when displays are changing and don't send out new values until WM is aware
        // of them. However, we don't do this for displays that serve as containers for ActivityView
        // because we don't want letter-/pillar-boxing during resize.
        final DisplayInfo overrideDisplayInfo = mShouldOverrideDisplayConfiguration
                ? mDisplayInfo : null;
        mService.mDisplayManagerInternal.setDisplayInfoOverrideFromWindowManager(mDisplayId,
                overrideDisplayInfo);

        mBaseDisplayRect.set(0, 0, dw, dh);

        if (isDefaultDisplay) {
            mCompatibleScreenScale = CompatibilityInfo.computeCompatibleScaling(mDisplayMetrics,
                    mCompatDisplayMetrics);
        }

        updateBounds();
        return mDisplayInfo;
    }

    WmDisplayCutout calculateDisplayCutoutForRotation(int rotation) {
        return mDisplayCutoutCache.getOrCompute(mInitialDisplayCutout, rotation);
    }

    private WmDisplayCutout calculateDisplayCutoutForRotationUncached(
            DisplayCutout cutout, int rotation) {
        if (cutout == null || cutout == DisplayCutout.NO_CUTOUT) {
            return WmDisplayCutout.NO_CUTOUT;
        }
        if (rotation == ROTATION_0) {
            return WmDisplayCutout.computeSafeInsets(
                    cutout, mInitialDisplayWidth, mInitialDisplayHeight);
        }
        final boolean rotated = (rotation == ROTATION_90 || rotation == ROTATION_270);
        final Path bounds = cutout.getBounds().getBoundaryPath();
        transformPhysicalToLogicalCoordinates(rotation, mInitialDisplayWidth, mInitialDisplayHeight,
                mTmpMatrix);
        bounds.transform(mTmpMatrix);
        return WmDisplayCutout.computeSafeInsets(DisplayCutout.fromBounds(bounds),
                rotated ? mInitialDisplayHeight : mInitialDisplayWidth,
                rotated ? mInitialDisplayWidth : mInitialDisplayHeight);
    }

    /**
     * Compute display configuration based on display properties and policy settings.
     * Do not call if mDisplayReady == false.
     */
    void computeScreenConfiguration(Configuration config) {
        final DisplayInfo displayInfo = updateDisplayAndOrientation(config.uiMode);

        final int dw = displayInfo.logicalWidth;
        final int dh = displayInfo.logicalHeight;
        config.orientation = (dw <= dh) ? ORIENTATION_PORTRAIT : ORIENTATION_LANDSCAPE;
        // TODO: Probably best to set this based on some setting in the display content object,
        // so the display can be configured for things like fullscreen.
        config.windowConfiguration.setWindowingMode(WINDOWING_MODE_FULLSCREEN);

        final float density = mDisplayMetrics.density;
        config.screenWidthDp =
                (int)(mService.mPolicy.getConfigDisplayWidth(dw, dh, displayInfo.rotation,
                        config.uiMode, mDisplayId, displayInfo.displayCutout) / density);
        config.screenHeightDp =
                (int)(mService.mPolicy.getConfigDisplayHeight(dw, dh, displayInfo.rotation,
                        config.uiMode, mDisplayId, displayInfo.displayCutout) / density);

        mService.mPolicy.getNonDecorInsetsLw(displayInfo.rotation, dw, dh,
                displayInfo.displayCutout, mTmpRect);
        final int leftInset = mTmpRect.left;
        final int topInset = mTmpRect.top;
        // appBounds at the root level should mirror the app screen size.
        config.windowConfiguration.setAppBounds(leftInset /* left */, topInset /* top */,
                leftInset + displayInfo.appWidth /* right */,
                topInset + displayInfo.appHeight /* bottom */);
        final boolean rotated = (displayInfo.rotation == Surface.ROTATION_90
                || displayInfo.rotation == Surface.ROTATION_270);

        computeSizeRangesAndScreenLayout(displayInfo, mDisplayId, rotated, config.uiMode, dw, dh,
                density, config);

        config.screenLayout = (config.screenLayout & ~Configuration.SCREENLAYOUT_ROUND_MASK)
                | ((displayInfo.flags & Display.FLAG_ROUND) != 0
                ? Configuration.SCREENLAYOUT_ROUND_YES
                : Configuration.SCREENLAYOUT_ROUND_NO);

        config.compatScreenWidthDp = (int)(config.screenWidthDp / mCompatibleScreenScale);
        config.compatScreenHeightDp = (int)(config.screenHeightDp / mCompatibleScreenScale);
        config.compatSmallestScreenWidthDp = computeCompatSmallestWidth(rotated, config.uiMode, dw,
                dh, mDisplayId);
        config.densityDpi = displayInfo.logicalDensityDpi;

        config.colorMode =
                (displayInfo.isHdr()
                        ? Configuration.COLOR_MODE_HDR_YES
                        : Configuration.COLOR_MODE_HDR_NO)
                        | (displayInfo.isWideColorGamut() && mService.hasWideColorGamutSupport()
                        ? Configuration.COLOR_MODE_WIDE_COLOR_GAMUT_YES
                        : Configuration.COLOR_MODE_WIDE_COLOR_GAMUT_NO);

        // Update the configuration based on available input devices, lid switch,
        // and platform configuration.
        config.touchscreen = Configuration.TOUCHSCREEN_NOTOUCH;
        config.keyboard = Configuration.KEYBOARD_NOKEYS;
        config.navigation = Configuration.NAVIGATION_NONAV;

        int keyboardPresence = 0;
        int navigationPresence = 0;
        final InputDevice[] devices = mService.mInputManager.getInputDevices();
        final int len = devices != null ? devices.length : 0;
        for (int i = 0; i < len; i++) {
            InputDevice device = devices[i];
            if (!device.isVirtual()) {
                final int sources = device.getSources();
                final int presenceFlag = device.isExternal() ?
                        WindowManagerPolicy.PRESENCE_EXTERNAL :
                        WindowManagerPolicy.PRESENCE_INTERNAL;

                // TODO(multi-display): Configure on per-display basis.
                if (mService.mIsTouchDevice) {
                    if ((sources & InputDevice.SOURCE_TOUCHSCREEN) ==
                            InputDevice.SOURCE_TOUCHSCREEN) {
                        config.touchscreen = Configuration.TOUCHSCREEN_FINGER;
                    }
                } else {
                    config.touchscreen = Configuration.TOUCHSCREEN_NOTOUCH;
                }

                if ((sources & InputDevice.SOURCE_TRACKBALL) == InputDevice.SOURCE_TRACKBALL) {
                    config.navigation = Configuration.NAVIGATION_TRACKBALL;
                    navigationPresence |= presenceFlag;
                } else if ((sources & InputDevice.SOURCE_DPAD) == InputDevice.SOURCE_DPAD
                        && config.navigation == Configuration.NAVIGATION_NONAV) {
                    config.navigation = Configuration.NAVIGATION_DPAD;
                    navigationPresence |= presenceFlag;
                }

                if (device.getKeyboardType() == InputDevice.KEYBOARD_TYPE_ALPHABETIC) {
                    config.keyboard = Configuration.KEYBOARD_QWERTY;
                    keyboardPresence |= presenceFlag;
                }
            }
        }

        if (config.navigation == Configuration.NAVIGATION_NONAV && mService.mHasPermanentDpad) {
            config.navigation = Configuration.NAVIGATION_DPAD;
            navigationPresence |= WindowManagerPolicy.PRESENCE_INTERNAL;
        }

        // Determine whether a hard keyboard is available and enabled.
        // TODO(multi-display): Should the hardware keyboard be tied to a display or to a device?
        boolean hardKeyboardAvailable = config.keyboard != Configuration.KEYBOARD_NOKEYS;
        if (hardKeyboardAvailable != mService.mHardKeyboardAvailable) {
            mService.mHardKeyboardAvailable = hardKeyboardAvailable;
            mService.mH.removeMessages(WindowManagerService.H.REPORT_HARD_KEYBOARD_STATUS_CHANGE);
            mService.mH.sendEmptyMessage(WindowManagerService.H.REPORT_HARD_KEYBOARD_STATUS_CHANGE);
        }

        // Let the policy update hidden states.
        config.keyboardHidden = Configuration.KEYBOARDHIDDEN_NO;
        config.hardKeyboardHidden = Configuration.HARDKEYBOARDHIDDEN_NO;
        config.navigationHidden = Configuration.NAVIGATIONHIDDEN_NO;
        mService.mPolicy.adjustConfigurationLw(config, keyboardPresence, navigationPresence);
    }

    private int computeCompatSmallestWidth(boolean rotated, int uiMode, int dw, int dh,
            int displayId) {
        mTmpDisplayMetrics.setTo(mDisplayMetrics);
        final DisplayMetrics tmpDm = mTmpDisplayMetrics;
        final int unrotDw, unrotDh;
        if (rotated) {
            unrotDw = dh;
            unrotDh = dw;
        } else {
            unrotDw = dw;
            unrotDh = dh;
        }
        int sw = reduceCompatConfigWidthSize(0, Surface.ROTATION_0, uiMode, tmpDm, unrotDw, unrotDh,
                displayId);
        sw = reduceCompatConfigWidthSize(sw, Surface.ROTATION_90, uiMode, tmpDm, unrotDh, unrotDw,
                displayId);
        sw = reduceCompatConfigWidthSize(sw, Surface.ROTATION_180, uiMode, tmpDm, unrotDw, unrotDh,
                displayId);
        sw = reduceCompatConfigWidthSize(sw, Surface.ROTATION_270, uiMode, tmpDm, unrotDh, unrotDw,
                displayId);
        return sw;
    }

    private int reduceCompatConfigWidthSize(int curSize, int rotation, int uiMode,
            DisplayMetrics dm, int dw, int dh, int displayId) {
        dm.noncompatWidthPixels = mService.mPolicy.getNonDecorDisplayWidth(dw, dh, rotation, uiMode,
                displayId, mDisplayInfo.displayCutout);
        dm.noncompatHeightPixels = mService.mPolicy.getNonDecorDisplayHeight(dw, dh, rotation,
                uiMode, displayId, mDisplayInfo.displayCutout);
        float scale = CompatibilityInfo.computeCompatibleScaling(dm, null);
        int size = (int)(((dm.noncompatWidthPixels / scale) / dm.density) + .5f);
        if (curSize == 0 || size < curSize) {
            curSize = size;
        }
        return curSize;
    }

    private void computeSizeRangesAndScreenLayout(DisplayInfo displayInfo, int displayId,
            boolean rotated, int uiMode, int dw, int dh, float density, Configuration outConfig) {

        // We need to determine the smallest width that will occur under normal
        // operation.  To this, start with the base screen size and compute the
        // width under the different possible rotations.  We need to un-rotate
        // the current screen dimensions before doing this.
        int unrotDw, unrotDh;
        if (rotated) {
            unrotDw = dh;
            unrotDh = dw;
        } else {
            unrotDw = dw;
            unrotDh = dh;
        }
        displayInfo.smallestNominalAppWidth = 1<<30;
        displayInfo.smallestNominalAppHeight = 1<<30;
        displayInfo.largestNominalAppWidth = 0;
        displayInfo.largestNominalAppHeight = 0;
        adjustDisplaySizeRanges(displayInfo, displayId, Surface.ROTATION_0, uiMode, unrotDw,
                unrotDh);
        adjustDisplaySizeRanges(displayInfo, displayId, Surface.ROTATION_90, uiMode, unrotDh,
                unrotDw);
        adjustDisplaySizeRanges(displayInfo, displayId, Surface.ROTATION_180, uiMode, unrotDw,
                unrotDh);
        adjustDisplaySizeRanges(displayInfo, displayId, Surface.ROTATION_270, uiMode, unrotDh,
                unrotDw);
        int sl = Configuration.resetScreenLayout(outConfig.screenLayout);
        sl = reduceConfigLayout(sl, Surface.ROTATION_0, density, unrotDw, unrotDh, uiMode,
                displayId);
        sl = reduceConfigLayout(sl, Surface.ROTATION_90, density, unrotDh, unrotDw, uiMode,
                displayId);
        sl = reduceConfigLayout(sl, Surface.ROTATION_180, density, unrotDw, unrotDh, uiMode,
                displayId);
        sl = reduceConfigLayout(sl, Surface.ROTATION_270, density, unrotDh, unrotDw, uiMode,
                displayId);
        outConfig.smallestScreenWidthDp = (int)(displayInfo.smallestNominalAppWidth / density);
        outConfig.screenLayout = sl;
    }

    private int reduceConfigLayout(int curLayout, int rotation, float density, int dw, int dh,
            int uiMode, int displayId) {
        // Get the app screen size at this rotation.
        int w = mService.mPolicy.getNonDecorDisplayWidth(dw, dh, rotation, uiMode, displayId,
                mDisplayInfo.displayCutout);
        int h = mService.mPolicy.getNonDecorDisplayHeight(dw, dh, rotation, uiMode, displayId,
                mDisplayInfo.displayCutout);

        // Compute the screen layout size class for this rotation.
        int longSize = w;
        int shortSize = h;
        if (longSize < shortSize) {
            int tmp = longSize;
            longSize = shortSize;
            shortSize = tmp;
        }
        longSize = (int)(longSize/density);
        shortSize = (int)(shortSize/density);
        return Configuration.reduceScreenLayout(curLayout, longSize, shortSize);
    }

    private void adjustDisplaySizeRanges(DisplayInfo displayInfo, int displayId, int rotation,
            int uiMode, int dw, int dh) {
        final DisplayCutout displayCutout = calculateDisplayCutoutForRotation(
                rotation).getDisplayCutout();
        final int width = mService.mPolicy.getConfigDisplayWidth(dw, dh, rotation, uiMode,
                displayId, displayCutout);
        if (width < displayInfo.smallestNominalAppWidth) {
            displayInfo.smallestNominalAppWidth = width;
        }
        if (width > displayInfo.largestNominalAppWidth) {
            displayInfo.largestNominalAppWidth = width;
        }
        final int height = mService.mPolicy.getConfigDisplayHeight(dw, dh, rotation, uiMode,
                displayId, displayCutout);
        if (height < displayInfo.smallestNominalAppHeight) {
            displayInfo.smallestNominalAppHeight = height;
        }
        if (height > displayInfo.largestNominalAppHeight) {
            displayInfo.largestNominalAppHeight = height;
        }
    }

    DockedStackDividerController getDockedDividerController() {
        return mDividerControllerLocked;
    }

    PinnedStackController getPinnedStackController() {
        return mPinnedStackControllerLocked;
    }

    /**
     * Returns true if the specified UID has access to this display.
     */
    boolean hasAccess(int uid) {
        return mDisplay.hasAccess(uid);
    }

    boolean isPrivate() {
        return (mDisplay.getFlags() & FLAG_PRIVATE) != 0;
    }

    TaskStack getHomeStack() {
        return mTaskStackContainers.getHomeStack();
    }

    /**
     * @return The primary split-screen stack, but only if it is visible, and {@code null} otherwise.
     */
    TaskStack getSplitScreenPrimaryStack() {
        TaskStack stack = mTaskStackContainers.getSplitScreenPrimaryStack();
        return (stack != null && stack.isVisible()) ? stack : null;
    }

    /**
     * Like {@link #getSplitScreenPrimaryStack}, but also returns the stack if it's currently
     * not visible.
     */
    TaskStack getSplitScreenPrimaryStackIgnoringVisibility() {
        return mTaskStackContainers.getSplitScreenPrimaryStack();
    }

    TaskStack getPinnedStack() {
        return mTaskStackContainers.getPinnedStack();
    }

    private boolean hasPinnedStack() {
        return mTaskStackContainers.getPinnedStack() != null;
    }

    /**
     * Returns the topmost stack on the display that is compatible with the input windowing mode.
     * Null is no compatible stack on the display.
     */
    TaskStack getTopStackInWindowingMode(int windowingMode) {
        return getStack(windowingMode, ACTIVITY_TYPE_UNDEFINED);
    }

    /**
     * Returns the topmost stack on the display that is compatible with the input windowing mode and
     * activity type. Null is no compatible stack on the display.
     */
    TaskStack getStack(int windowingMode, int activityType) {
        return mTaskStackContainers.getStack(windowingMode, activityType);
    }

    @VisibleForTesting
    TaskStack getTopStack() {
        return mTaskStackContainers.getTopStack();
    }

    ArrayList<Task> getVisibleTasks() {
        return mTaskStackContainers.getVisibleTasks();
    }

    void onStackWindowingModeChanged(TaskStack stack) {
        mTaskStackContainers.onStackWindowingModeChanged(stack);
    }

    @Override
    public void onConfigurationChanged(Configuration newParentConfig) {
        super.onConfigurationChanged(newParentConfig);

        // The display size information is heavily dependent on the resources in the current
        // configuration, so we need to reconfigure it every time the configuration changes.
        // See {@link PhoneWindowManager#setInitialDisplaySize}...sigh...
        mService.reconfigureDisplayLocked(this);

        final DockedStackDividerController dividerController = getDockedDividerController();

        if (dividerController != null) {
            getDockedDividerController().onConfigurationChanged();
        }

        final PinnedStackController pinnedStackController = getPinnedStackController();

        if (pinnedStackController != null) {
            getPinnedStackController().onConfigurationChanged();
        }
    }

    /**
     * Callback used to trigger bounds update after configuration change and get ids of stacks whose
     * bounds were updated.
     */
    void updateStackBoundsAfterConfigChange(@NonNull List<TaskStack> changedStackList) {
        for (int i = mTaskStackContainers.getChildCount() - 1; i >= 0; --i) {
            final TaskStack stack = mTaskStackContainers.getChildAt(i);
            if (stack.updateBoundsAfterConfigChange()) {
                changedStackList.add(stack);
            }
        }

        // If there was no pinned stack, we still need to notify the controller of the display info
        // update as a result of the config change.  We do this here to consolidate the flow between
        // changes when there is and is not a stack.
        if (!hasPinnedStack()) {
            mPinnedStackControllerLocked.onDisplayInfoChanged();
        }
    }

    @Override
    boolean fillsParent() {
        return true;
    }

    @Override
    boolean isVisible() {
        return true;
    }

    @Override
    void onAppTransitionDone() {
        super.onAppTransitionDone();
        mService.mWindowsChanged = true;
    }

    @Override
    boolean forAllWindows(ToBooleanFunction<WindowState> callback, boolean traverseTopToBottom) {
        // Special handling so we can process IME windows with #forAllImeWindows above their IME
        // target, or here in order if there isn't an IME target.
        if (traverseTopToBottom) {
            for (int i = mChildren.size() - 1; i >= 0; --i) {
                final DisplayChildWindowContainer child = mChildren.get(i);
                if (child == mImeWindowsContainers && mService.mInputMethodTarget != null) {
                    // In this case the Ime windows will be processed above their target so we skip
                    // here.
                    continue;
                }
                if (child.forAllWindows(callback, traverseTopToBottom)) {
                    return true;
                }
            }
        } else {
            final int count = mChildren.size();
            for (int i = 0; i < count; i++) {
                final DisplayChildWindowContainer child = mChildren.get(i);
                if (child == mImeWindowsContainers && mService.mInputMethodTarget != null) {
                    // In this case the Ime windows will be processed above their target so we skip
                    // here.
                    continue;
                }
                if (child.forAllWindows(callback, traverseTopToBottom)) {
                    return true;
                }
            }
        }
        return false;
    }

    boolean forAllImeWindows(ToBooleanFunction<WindowState> callback, boolean traverseTopToBottom) {
        return mImeWindowsContainers.forAllWindows(callback, traverseTopToBottom);
    }

    @Override
    int getOrientation() {
        final WindowManagerPolicy policy = mService.mPolicy;

        if (mService.mDisplayFrozen) {
            if (mLastWindowForcedOrientation != SCREEN_ORIENTATION_UNSPECIFIED) {
                if (DEBUG_ORIENTATION) Slog.v(TAG_WM, "Display id=" + mDisplayId
                        + " is frozen, return " + mLastWindowForcedOrientation);
                // If the display is frozen, some activities may be in the middle of restarting, and
                // thus have removed their old window. If the window has the flag to hide the lock
                // screen, then the lock screen can re-appear and inflict its own orientation on us.
                // Keep the orientation stable until this all settles down.
                return mLastWindowForcedOrientation;
            } else if (policy.isKeyguardLocked()) {
                // Use the last orientation the while the display is frozen with the keyguard
                // locked. This could be the keyguard forced orientation or from a SHOW_WHEN_LOCKED
                // window. We don't want to check the show when locked window directly though as
                // things aren't stable while the display is frozen, for example the window could be
                // momentarily unavailable due to activity relaunch.
                if (DEBUG_ORIENTATION) Slog.v(TAG_WM, "Display id=" + mDisplayId
                        + " is frozen while keyguard locked, return " + mLastOrientation);
                return mLastOrientation;
            }
        } else {
            final int orientation = mAboveAppWindowsContainers.getOrientation();
            if (orientation != SCREEN_ORIENTATION_UNSET) {
                return orientation;
            }
        }

        // Top system windows are not requesting an orientation. Start searching from apps.
        return mTaskStackContainers.getOrientation();
    }

    void updateDisplayInfo() {
        // Check if display metrics changed and update base values if needed.
        updateBaseDisplayMetricsIfNeeded();

        mDisplay.getDisplayInfo(mDisplayInfo);
        mDisplay.getMetrics(mDisplayMetrics);

        for (int i = mTaskStackContainers.getChildCount() - 1; i >= 0; --i) {
            mTaskStackContainers.getChildAt(i).updateDisplayInfo(null);
        }
    }

    void initializeDisplayBaseInfo() {
        final DisplayManagerInternal displayManagerInternal = mService.mDisplayManagerInternal;
        if (displayManagerInternal != null) {
            // Bootstrap the default logical display from the display manager.
            final DisplayInfo newDisplayInfo = displayManagerInternal.getDisplayInfo(mDisplayId);
            if (newDisplayInfo != null) {
                mDisplayInfo.copyFrom(newDisplayInfo);
            }
        }

        updateBaseDisplayMetrics(mDisplayInfo.logicalWidth, mDisplayInfo.logicalHeight,
                mDisplayInfo.logicalDensityDpi);
        mInitialDisplayWidth = mDisplayInfo.logicalWidth;
        mInitialDisplayHeight = mDisplayInfo.logicalHeight;
        mInitialDisplayDensity = mDisplayInfo.logicalDensityDpi;
        mInitialDisplayCutout = mDisplayInfo.displayCutout;
    }

    /**
     * If display metrics changed, overrides are not set and it's not just a rotation - update base
     * values.
     */
    private void updateBaseDisplayMetricsIfNeeded() {
        // Get real display metrics without overrides from WM.
        mService.mDisplayManagerInternal.getNonOverrideDisplayInfo(mDisplayId, mDisplayInfo);
        final int orientation = mDisplayInfo.rotation;
        final boolean rotated = (orientation == ROTATION_90 || orientation == ROTATION_270);
        final int newWidth = rotated ? mDisplayInfo.logicalHeight : mDisplayInfo.logicalWidth;
        final int newHeight = rotated ? mDisplayInfo.logicalWidth : mDisplayInfo.logicalHeight;
        final int newDensity = mDisplayInfo.logicalDensityDpi;
        final DisplayCutout newCutout = mDisplayInfo.displayCutout;

        final boolean displayMetricsChanged = mInitialDisplayWidth != newWidth
                || mInitialDisplayHeight != newHeight
                || mInitialDisplayDensity != mDisplayInfo.logicalDensityDpi
                || !Objects.equals(mInitialDisplayCutout, newCutout);

        if (displayMetricsChanged) {
            // Check if display size or density is forced.
            final boolean isDisplaySizeForced = mBaseDisplayWidth != mInitialDisplayWidth
                    || mBaseDisplayHeight != mInitialDisplayHeight;
            final boolean isDisplayDensityForced = mBaseDisplayDensity != mInitialDisplayDensity;

            // If there is an override set for base values - use it, otherwise use new values.
            updateBaseDisplayMetrics(isDisplaySizeForced ? mBaseDisplayWidth : newWidth,
                    isDisplaySizeForced ? mBaseDisplayHeight : newHeight,
                    isDisplayDensityForced ? mBaseDisplayDensity : newDensity);

            // Real display metrics changed, so we should also update initial values.
            mInitialDisplayWidth = newWidth;
            mInitialDisplayHeight = newHeight;
            mInitialDisplayDensity = newDensity;
            mInitialDisplayCutout = newCutout;
            mService.reconfigureDisplayLocked(this);
        }
    }

    /** Sets the maximum width the screen resolution can be */
    void setMaxUiWidth(int width) {
        if (DEBUG_DISPLAY) {
            Slog.v(TAG_WM, "Setting max ui width:" + width + " on display:" + getDisplayId());
        }

        mMaxUiWidth = width;

        // Update existing metrics.
        updateBaseDisplayMetrics(mBaseDisplayWidth, mBaseDisplayHeight, mBaseDisplayDensity);
    }

    /** Update base (override) display metrics. */
    void updateBaseDisplayMetrics(int baseWidth, int baseHeight, int baseDensity) {
        mBaseDisplayWidth = baseWidth;
        mBaseDisplayHeight = baseHeight;
        mBaseDisplayDensity = baseDensity;

        if (mMaxUiWidth > 0 && mBaseDisplayWidth > mMaxUiWidth) {
            mBaseDisplayHeight = (mMaxUiWidth * mBaseDisplayHeight) / mBaseDisplayWidth;
            mBaseDisplayDensity = (mMaxUiWidth * mBaseDisplayDensity) / mBaseDisplayWidth;
            mBaseDisplayWidth = mMaxUiWidth;

            if (DEBUG_DISPLAY) {
                Slog.v(TAG_WM, "Applying config restraints:" + mBaseDisplayWidth + "x"
                        + mBaseDisplayHeight + " at density:" + mBaseDisplayDensity
                        + " on display:" + getDisplayId());
            }
        }

        mBaseDisplayRect.set(0, 0, mBaseDisplayWidth, mBaseDisplayHeight);

        updateBounds();
    }

    void getStableRect(Rect out) {
        out.set(mDisplayFrames.mStable);
    }

    TaskStack createStack(int stackId, boolean onTop, StackWindowController controller) {
        if (DEBUG_STACK) Slog.d(TAG_WM, "Create new stackId=" + stackId + " on displayId="
                + mDisplayId);

        final TaskStack stack = new TaskStack(mService, stackId, controller);
        mTaskStackContainers.addStackToDisplay(stack, onTop);
        return stack;
    }

    void moveStackToDisplay(TaskStack stack, boolean onTop) {
        final DisplayContent prevDc = stack.getDisplayContent();
        if (prevDc == null) {
            throw new IllegalStateException("Trying to move stackId=" + stack.mStackId
                    + " which is not currently attached to any display");
        }
        if (prevDc.getDisplayId() == mDisplayId) {
            throw new IllegalArgumentException("Trying to move stackId=" + stack.mStackId
                    + " to its current displayId=" + mDisplayId);
        }

        prevDc.mTaskStackContainers.removeChild(stack);
        mTaskStackContainers.addStackToDisplay(stack, onTop);
    }

    @Override
    protected void addChild(DisplayChildWindowContainer child,
            Comparator<DisplayChildWindowContainer> comparator) {
        throw new UnsupportedOperationException("See DisplayChildWindowContainer");
    }

    @Override
    protected void addChild(DisplayChildWindowContainer child, int index) {
        throw new UnsupportedOperationException("See DisplayChildWindowContainer");
    }

    @Override
    protected void removeChild(DisplayChildWindowContainer child) {
        // Only allow removal of direct children from this display if the display is in the process
        // of been removed.
        if (mRemovingDisplay) {
            super.removeChild(child);
            return;
        }
        throw new UnsupportedOperationException("See DisplayChildWindowContainer");
    }

    @Override
    void positionChildAt(int position, DisplayChildWindowContainer child, boolean includingParents) {
        // Children of the display are statically ordered, so the real intention here is to perform
        // the operation on the display and not the static direct children.
        getParent().positionChildAt(position, this, includingParents);
    }

    void positionStackAt(int position, TaskStack child) {
        mTaskStackContainers.positionChildAt(position, child, false /* includingParents */);
        layoutAndAssignWindowLayersIfNeeded();
    }

    int taskIdFromPoint(int x, int y) {
        for (int stackNdx = mTaskStackContainers.getChildCount() - 1; stackNdx >= 0; --stackNdx) {
            final TaskStack stack = mTaskStackContainers.getChildAt(stackNdx);
            final int taskId = stack.taskIdFromPoint(x, y);
            if (taskId != -1) {
                return taskId;
            }
        }
        return -1;
    }

    /**
     * Find the task whose outside touch area (for resizing) (x, y) falls within.
     * Returns null if the touch doesn't fall into a resizing area.
     */
    Task findTaskForResizePoint(int x, int y) {
        final int delta = dipToPixel(RESIZE_HANDLE_WIDTH_IN_DP, mDisplayMetrics);
        mTmpTaskForResizePointSearchResult.reset();
        for (int stackNdx = mTaskStackContainers.getChildCount() - 1; stackNdx >= 0; --stackNdx) {
            final TaskStack stack = mTaskStackContainers.getChildAt(stackNdx);
            if (!stack.getWindowConfiguration().canResizeTask()) {
                return null;
            }

            stack.findTaskForResizePoint(x, y, delta, mTmpTaskForResizePointSearchResult);
            if (mTmpTaskForResizePointSearchResult.searchDone) {
                return mTmpTaskForResizePointSearchResult.taskForResize;
            }
        }
        return null;
    }

    void setTouchExcludeRegion(Task focusedTask) {
        // The provided task is the task on this display with focus, so if WindowManagerService's
        // focused app is not on this display, focusedTask will be null.
        if (focusedTask == null) {
            mTouchExcludeRegion.setEmpty();
        } else {
            mTouchExcludeRegion.set(mBaseDisplayRect);
            final int delta = dipToPixel(RESIZE_HANDLE_WIDTH_IN_DP, mDisplayMetrics);
            mTmpRect2.setEmpty();
            for (int stackNdx = mTaskStackContainers.getChildCount() - 1; stackNdx >= 0; --stackNdx) {
                final TaskStack stack = mTaskStackContainers.getChildAt(stackNdx);
                stack.setTouchExcludeRegion(focusedTask, delta, mTouchExcludeRegion,
                        mDisplayFrames.mContent, mTmpRect2);
            }
            // If we removed the focused task above, add it back and only leave its
            // outside touch area in the exclusion. TapDectector is not interested in
            // any touch inside the focused task itself.
            if (!mTmpRect2.isEmpty()) {
                mTouchExcludeRegion.op(mTmpRect2, Region.Op.UNION);
            }
        }
        final WindowState inputMethod = mService.mInputMethodWindow;
        if (inputMethod != null && inputMethod.isVisibleLw()) {
            // If the input method is visible and the user is typing, we don't want these touch
            // events to be intercepted and used to change focus. This would likely cause a
            // disappearance of the input method.
            inputMethod.getTouchableRegion(mTmpRegion);
            if (inputMethod.getDisplayId() == mDisplayId) {
                mTouchExcludeRegion.op(mTmpRegion, Op.UNION);
            } else {
                // IME is on a different display, so we need to update its tap detector.
                // TODO(multidisplay): Remove when IME will always appear on same display.
                inputMethod.getDisplayContent().setTouchExcludeRegion(null /* focusedTask */);
            }
        }
        for (int i = mTapExcludedWindows.size() - 1; i >= 0; i--) {
            final WindowState win = mTapExcludedWindows.get(i);
            win.getTouchableRegion(mTmpRegion);
            mTouchExcludeRegion.op(mTmpRegion, Region.Op.UNION);
        }
        for (int i = mTapExcludeProvidingWindows.size() - 1; i >= 0; i--) {
            final WindowState win = mTapExcludeProvidingWindows.valueAt(i);
            win.amendTapExcludeRegion(mTouchExcludeRegion);
        }
        // TODO(multi-display): Support docked stacks on secondary displays.
        if (mDisplayId == DEFAULT_DISPLAY && getSplitScreenPrimaryStack() != null) {
            mDividerControllerLocked.getTouchRegion(mTmpRect);
            mTmpRegion.set(mTmpRect);
            mTouchExcludeRegion.op(mTmpRegion, Op.UNION);
        }
        if (mTapDetector != null) {
            mTapDetector.setTouchExcludeRegion(mTouchExcludeRegion);
        }
    }

    @Override
    void switchUser() {
        super.switchUser();
        mService.mWindowsChanged = true;
    }

    private void resetAnimationBackgroundAnimator() {
        for (int stackNdx = mTaskStackContainers.getChildCount() - 1; stackNdx >= 0; --stackNdx) {
            mTaskStackContainers.getChildAt(stackNdx).resetAnimationBackgroundAnimator();
        }
    }

    @Override
    void removeIfPossible() {
        if (isAnimating()) {
            mDeferredRemoval = true;
            return;
        }
        removeImmediately();
    }

    @Override
    void removeImmediately() {
        mRemovingDisplay = true;
        try {
            super.removeImmediately();
            if (DEBUG_DISPLAY) Slog.v(TAG_WM, "Removing display=" + this);
            if (mService.canDispatchPointerEvents()) {
                if (mTapDetector != null) {
                    mService.unregisterPointerEventListener(mTapDetector);
                }
                if (mDisplayId == DEFAULT_DISPLAY && mService.mMousePositionTracker != null) {
                    mService.unregisterPointerEventListener(mService.mMousePositionTracker);
                }
            }
            mService.mAnimator.removeDisplayLocked(mDisplayId);
        } finally {
            mRemovingDisplay = false;
        }

        mService.onDisplayRemoved(mDisplayId);
    }

    /** Returns true if a removal action is still being deferred. */
    @Override
    boolean checkCompleteDeferredRemoval() {
        final boolean stillDeferringRemoval = super.checkCompleteDeferredRemoval();

        if (!stillDeferringRemoval && mDeferredRemoval) {
            removeImmediately();
            return false;
        }
        return true;
    }

    /** @return 'true' if removal of this display content is deferred due to active animation. */
    boolean isRemovalDeferred() {
        return mDeferredRemoval;
    }

    boolean animateForIme(float interpolatedValue, float animationTarget,
            float dividerAnimationTarget) {
        boolean updated = false;

        for (int i = mTaskStackContainers.getChildCount() - 1; i >= 0; --i) {
            final TaskStack stack = mTaskStackContainers.getChildAt(i);
            if (stack == null || !stack.isAdjustedForIme()) {
                continue;
            }

            if (interpolatedValue >= 1f && animationTarget == 0f && dividerAnimationTarget == 0f) {
                stack.resetAdjustedForIme(true /* adjustBoundsNow */);
                updated = true;
            } else {
                mDividerControllerLocked.mLastAnimationProgress =
                        mDividerControllerLocked.getInterpolatedAnimationValue(interpolatedValue);
                mDividerControllerLocked.mLastDividerProgress =
                        mDividerControllerLocked.getInterpolatedDividerValue(interpolatedValue);
                updated |= stack.updateAdjustForIme(
                        mDividerControllerLocked.mLastAnimationProgress,
                        mDividerControllerLocked.mLastDividerProgress,
                        false /* force */);
            }
            if (interpolatedValue >= 1f) {
                stack.endImeAdjustAnimation();
            }
        }

        return updated;
    }

    boolean clearImeAdjustAnimation() {
        boolean changed = false;
        for (int i = mTaskStackContainers.getChildCount() - 1; i >= 0; --i) {
            final TaskStack stack = mTaskStackContainers.getChildAt(i);
            if (stack != null && stack.isAdjustedForIme()) {
                stack.resetAdjustedForIme(true /* adjustBoundsNow */);
                changed  = true;
            }
        }
        return changed;
    }

    void beginImeAdjustAnimation() {
        for (int i = mTaskStackContainers.getChildCount() - 1; i >= 0; --i) {
            final TaskStack stack = mTaskStackContainers.getChildAt(i);
            if (stack.isVisible() && stack.isAdjustedForIme()) {
                stack.beginImeAdjustAnimation();
            }
        }
    }

    void adjustForImeIfNeeded() {
        final WindowState imeWin = mService.mInputMethodWindow;
        final boolean imeVisible = imeWin != null && imeWin.isVisibleLw() && imeWin.isDisplayedLw()
                && !mDividerControllerLocked.isImeHideRequested();
        final boolean dockVisible = isStackVisible(WINDOWING_MODE_SPLIT_SCREEN_PRIMARY);
        final TaskStack imeTargetStack = mService.getImeFocusStackLocked();
        final int imeDockSide = (dockVisible && imeTargetStack != null) ?
                imeTargetStack.getDockSide() : DOCKED_INVALID;
        final boolean imeOnTop = (imeDockSide == DOCKED_TOP);
        final boolean imeOnBottom = (imeDockSide == DOCKED_BOTTOM);
        final boolean dockMinimized = mDividerControllerLocked.isMinimizedDock();
        final int imeHeight = mDisplayFrames.getInputMethodWindowVisibleHeight();
        final boolean imeHeightChanged = imeVisible &&
                imeHeight != mDividerControllerLocked.getImeHeightAdjustedFor();

        // The divider could be adjusted for IME position, or be thinner than usual,
        // or both. There are three possible cases:
        // - If IME is visible, and focus is on top, divider is not moved for IME but thinner.
        // - If IME is visible, and focus is on bottom, divider is moved for IME and thinner.
        // - If IME is not visible, divider is not moved and is normal width.

        if (imeVisible && dockVisible && (imeOnTop || imeOnBottom) && !dockMinimized) {
            for (int i = mTaskStackContainers.getChildCount() - 1; i >= 0; --i) {
                final TaskStack stack = mTaskStackContainers.getChildAt(i);
                final boolean isDockedOnBottom = stack.getDockSide() == DOCKED_BOTTOM;
                if (stack.isVisible() && (imeOnBottom || isDockedOnBottom)
                        && stack.inSplitScreenWindowingMode()) {
                    stack.setAdjustedForIme(imeWin, imeOnBottom && imeHeightChanged);
                } else {
                    stack.resetAdjustedForIme(false);
                }
            }
            mDividerControllerLocked.setAdjustedForIme(
                    imeOnBottom /*ime*/, true /*divider*/, true /*animate*/, imeWin, imeHeight);
        } else {
            for (int i = mTaskStackContainers.getChildCount() - 1; i >= 0; --i) {
                final TaskStack stack = mTaskStackContainers.getChildAt(i);
                stack.resetAdjustedForIme(!dockVisible);
            }
            mDividerControllerLocked.setAdjustedForIme(
                    false /*ime*/, false /*divider*/, dockVisible /*animate*/, imeWin, imeHeight);
        }
        mPinnedStackControllerLocked.setAdjustedForIme(imeVisible, imeHeight);
    }

    /**
     * If a window that has an animation specifying a colored background and the current wallpaper
     * is visible, then the color goes *below* the wallpaper so we don't cause the wallpaper to
     * suddenly disappear.
     */
    int getLayerForAnimationBackground(WindowStateAnimator winAnimator) {
        final WindowState visibleWallpaper = mBelowAppWindowsContainers.getWindow(
                w -> w.mIsWallpaper && w.isVisibleNow());

        if (visibleWallpaper != null) {
            return visibleWallpaper.mWinAnimator.mAnimLayer;
        }
        return winAnimator.mAnimLayer;
    }

    void prepareFreezingTaskBounds() {
        for (int stackNdx = mTaskStackContainers.getChildCount() - 1; stackNdx >= 0; --stackNdx) {
            final TaskStack stack = mTaskStackContainers.getChildAt(stackNdx);
            stack.prepareFreezingTaskBounds();
        }
    }

    void rotateBounds(int oldRotation, int newRotation, Rect bounds) {
        getBounds(mTmpRect, newRotation);

        // Compute a transform matrix to undo the coordinate space transformation,
        // and present the window at the same physical position it previously occupied.
        final int deltaRotation = deltaRotation(newRotation, oldRotation);
        createRotationMatrix(deltaRotation, mTmpRect.width(), mTmpRect.height(), mTmpMatrix);

        mTmpRectF.set(bounds);
        mTmpMatrix.mapRect(mTmpRectF);
        mTmpRectF.round(bounds);
    }

    static int deltaRotation(int oldRotation, int newRotation) {
        int delta = newRotation - oldRotation;
        if (delta < 0) delta += 4;
        return delta;
    }

    private static void createRotationMatrix(int rotation, float displayWidth, float displayHeight,
            Matrix outMatrix) {
        // For rotations without Z-ordering we don't need the target rectangle's position.
        createRotationMatrix(rotation, 0 /* rectLeft */, 0 /* rectTop */, displayWidth,
                displayHeight, outMatrix);
    }

    static void createRotationMatrix(int rotation, float rectLeft, float rectTop,
            float displayWidth, float displayHeight, Matrix outMatrix) {
        switch (rotation) {
            case ROTATION_0:
                outMatrix.reset();
                break;
            case ROTATION_270:
                outMatrix.setRotate(270, 0, 0);
                outMatrix.postTranslate(0, displayHeight);
                outMatrix.postTranslate(rectTop, 0);
                break;
            case ROTATION_180:
                outMatrix.reset();
                break;
            case ROTATION_90:
                outMatrix.setRotate(90, 0, 0);
                outMatrix.postTranslate(displayWidth, 0);
                outMatrix.postTranslate(-rectTop, rectLeft);
                break;
        }
    }

    @CallSuper
    @Override
    public void writeToProto(ProtoOutputStream proto, long fieldId, boolean trim) {
        final long token = proto.start(fieldId);
        super.writeToProto(proto, WINDOW_CONTAINER, trim);
        proto.write(ID, mDisplayId);
        for (int stackNdx = mTaskStackContainers.getChildCount() - 1; stackNdx >= 0; --stackNdx) {
            final TaskStack stack = mTaskStackContainers.getChildAt(stackNdx);
            stack.writeToProto(proto, STACKS, trim);
        }
        mDividerControllerLocked.writeToProto(proto, DOCKED_STACK_DIVIDER_CONTROLLER);
        mPinnedStackControllerLocked.writeToProto(proto, PINNED_STACK_CONTROLLER);
        for (int i = mAboveAppWindowsContainers.getChildCount() - 1; i >= 0; --i) {
            final WindowToken windowToken = mAboveAppWindowsContainers.getChildAt(i);
            windowToken.writeToProto(proto, ABOVE_APP_WINDOWS, trim);
        }
        for (int i = mBelowAppWindowsContainers.getChildCount() - 1; i >= 0; --i) {
            final WindowToken windowToken = mBelowAppWindowsContainers.getChildAt(i);
            windowToken.writeToProto(proto, BELOW_APP_WINDOWS, trim);
        }
        for (int i = mImeWindowsContainers.getChildCount() - 1; i >= 0; --i) {
            final WindowToken windowToken = mImeWindowsContainers.getChildAt(i);
            windowToken.writeToProto(proto, IME_WINDOWS, trim);
        }
        proto.write(DPI, mBaseDisplayDensity);
        mDisplayInfo.writeToProto(proto, DISPLAY_INFO);
        proto.write(ROTATION, mRotation);
        final ScreenRotationAnimation screenRotationAnimation =
                mService.mAnimator.getScreenRotationAnimationLocked(mDisplayId);
        if (screenRotationAnimation != null) {
            screenRotationAnimation.writeToProto(proto, SCREEN_ROTATION_ANIMATION);
        }
        mDisplayFrames.writeToProto(proto, DISPLAY_FRAMES);
        proto.end(token);
    }

    @Override
    public void dump(PrintWriter pw, String prefix, boolean dumpAll) {
        super.dump(pw, prefix, dumpAll);
        pw.print(prefix); pw.print("Display: mDisplayId="); pw.println(mDisplayId);
        final String subPrefix = "  " + prefix;
        pw.print(subPrefix); pw.print("init="); pw.print(mInitialDisplayWidth); pw.print("x");
            pw.print(mInitialDisplayHeight); pw.print(" "); pw.print(mInitialDisplayDensity);
            pw.print("dpi");
            if (mInitialDisplayWidth != mBaseDisplayWidth
                    || mInitialDisplayHeight != mBaseDisplayHeight
                    || mInitialDisplayDensity != mBaseDisplayDensity) {
                pw.print(" base=");
                pw.print(mBaseDisplayWidth); pw.print("x"); pw.print(mBaseDisplayHeight);
                pw.print(" "); pw.print(mBaseDisplayDensity); pw.print("dpi");
            }
            if (mDisplayScalingDisabled) {
                pw.println(" noscale");
            }
            pw.print(" cur=");
            pw.print(mDisplayInfo.logicalWidth);
            pw.print("x"); pw.print(mDisplayInfo.logicalHeight);
            pw.print(" app=");
            pw.print(mDisplayInfo.appWidth);
            pw.print("x"); pw.print(mDisplayInfo.appHeight);
            pw.print(" rng="); pw.print(mDisplayInfo.smallestNominalAppWidth);
            pw.print("x"); pw.print(mDisplayInfo.smallestNominalAppHeight);
            pw.print("-"); pw.print(mDisplayInfo.largestNominalAppWidth);
            pw.print("x"); pw.println(mDisplayInfo.largestNominalAppHeight);
            pw.print(subPrefix + "deferred=" + mDeferredRemoval
                    + " mLayoutNeeded=" + mLayoutNeeded);
            pw.println(" mTouchExcludeRegion=" + mTouchExcludeRegion);

        pw.println();
        pw.print(prefix); pw.print("mLayoutSeq="); pw.println(mLayoutSeq);

        pw.println();
        pw.println(prefix + "Application tokens in top down Z order:");
        for (int stackNdx = mTaskStackContainers.getChildCount() - 1; stackNdx >= 0; --stackNdx) {
            final TaskStack stack = mTaskStackContainers.getChildAt(stackNdx);
            stack.dump(pw, prefix + "  ", dumpAll);
        }

        pw.println();
        if (!mExitingTokens.isEmpty()) {
            pw.println();
            pw.println("  Exiting tokens:");
            for (int i = mExitingTokens.size() - 1; i >= 0; i--) {
                final WindowToken token = mExitingTokens.get(i);
                pw.print("  Exiting #"); pw.print(i);
                pw.print(' '); pw.print(token);
                pw.println(':');
                token.dump(pw, "    ", dumpAll);
            }
        }

        pw.println();

        // Dump stack references
        final TaskStack homeStack = getHomeStack();
        if (homeStack != null) {
            pw.println(prefix + "homeStack=" + homeStack.getName());
        }
        final TaskStack pinnedStack = getPinnedStack();
        if (pinnedStack != null) {
            pw.println(prefix + "pinnedStack=" + pinnedStack.getName());
        }
        final TaskStack splitScreenPrimaryStack = getSplitScreenPrimaryStack();
        if (splitScreenPrimaryStack != null) {
            pw.println(prefix + "splitScreenPrimaryStack=" + splitScreenPrimaryStack.getName());
        }

        pw.println();
        mDividerControllerLocked.dump(prefix, pw);
        pw.println();
        mPinnedStackControllerLocked.dump(prefix, pw);

        pw.println();
        mDisplayFrames.dump(prefix, pw);
    }

    @Override
    public String toString() {
        return "Display " + mDisplayId + " info=" + mDisplayInfo + " stacks=" + mChildren;
    }

    String getName() {
        return "Display " + mDisplayId + " name=\"" + mDisplayInfo.name + "\"";
    }

    /** Returns true if the stack in the windowing mode is visible. */
    boolean isStackVisible(int windowingMode) {
        final TaskStack stack = getTopStackInWindowingMode(windowingMode);
        return stack != null && stack.isVisible();
    }

    /** Find the visible, touch-deliverable window under the given point */
    WindowState getTouchableWinAtPointLocked(float xf, float yf) {
        final int x = (int) xf;
        final int y = (int) yf;
        final WindowState touchedWin = getWindow(w -> {
            final int flags = w.mAttrs.flags;
            if (!w.isVisibleLw()) {
                return false;
            }
            if ((flags & FLAG_NOT_TOUCHABLE) != 0) {
                return false;
            }

            w.getVisibleBounds(mTmpRect);
            if (!mTmpRect.contains(x, y)) {
                return false;
            }

            w.getTouchableRegion(mTmpRegion);

            final int touchFlags = flags & (FLAG_NOT_FOCUSABLE | FLAG_NOT_TOUCH_MODAL);
            return mTmpRegion.contains(x, y) || touchFlags == 0;
        });

        return touchedWin;
    }

    boolean canAddToastWindowForUid(int uid) {
        // We allow one toast window per UID being shown at a time.
        // Also if the app is focused adding more than one toast at
        // a time for better backwards compatibility.
        final WindowState focusedWindowForUid = getWindow(w ->
                w.mOwnerUid == uid && w.isFocused());
        if (focusedWindowForUid != null) {
            return true;
        }
        final WindowState win = getWindow(w ->
                w.mAttrs.type == TYPE_TOAST && w.mOwnerUid == uid && !w.mPermanentlyHidden
                && !w.mWindowRemovalAllowed);
        return win == null;
    }

    void scheduleToastWindowsTimeoutIfNeededLocked(WindowState oldFocus, WindowState newFocus) {
        if (oldFocus == null || (newFocus != null && newFocus.mOwnerUid == oldFocus.mOwnerUid)) {
            return;
        }

        // Used to communicate the old focus to the callback method.
        mTmpWindow = oldFocus;

        forAllWindows(mScheduleToastTimeout, false /* traverseTopToBottom */);
    }

    WindowState findFocusedWindow() {
        mTmpWindow = null;

        forAllWindows(mFindFocusedWindow, true /* traverseTopToBottom */);

        if (mTmpWindow == null) {
            if (DEBUG_FOCUS_LIGHT) Slog.v(TAG_WM, "findFocusedWindow: No focusable windows.");
            return null;
        }
        return mTmpWindow;
    }

    /** Updates the layer assignment of windows on this display. */
    void assignWindowLayers(boolean setLayoutNeeded) {
        Trace.traceBegin(Trace.TRACE_TAG_WINDOW_MANAGER, "assignWindowLayers");
        assignChildLayers(getPendingTransaction());
        if (setLayoutNeeded) {
            setLayoutNeeded();
        }

        // We accumlate the layer changes in-to "getPendingTransaction()" but we defer
        // the application of this transaction until the animation pass triggers
        // prepareSurfaces. This allows us to synchronize Z-ordering changes with
        // the hiding and showing of surfaces.
        scheduleAnimation();
        Trace.traceEnd(Trace.TRACE_TAG_WINDOW_MANAGER);
    }

    // TODO: This should probably be called any time a visual change is made to the hierarchy like
    // moving containers or resizing them. Need to investigate the best way to have it automatically
    // happen so we don't run into issues with programmers forgetting to do it.
    void layoutAndAssignWindowLayersIfNeeded() {
        mService.mWindowsChanged = true;
        setLayoutNeeded();

        if (!mService.updateFocusedWindowLocked(UPDATE_FOCUS_WILL_PLACE_SURFACES,
                false /*updateInputWindows*/)) {
            assignWindowLayers(false /* setLayoutNeeded */);
        }

        mService.mInputMonitor.setUpdateInputWindowsNeededLw();
        mService.mWindowPlacerLocked.performSurfacePlacement();
        mService.mInputMonitor.updateInputWindowsLw(false /*force*/);
    }

    /** Returns true if a leaked surface was destroyed */
    boolean destroyLeakedSurfaces() {
        // Used to indicate that a surface was leaked.
        mTmpWindow = null;
        forAllWindows(w -> {
            final WindowStateAnimator wsa = w.mWinAnimator;
            if (wsa.mSurfaceController == null) {
                return;
            }
            if (!mService.mSessions.contains(wsa.mSession)) {
                Slog.w(TAG_WM, "LEAKED SURFACE (session doesn't exist): "
                        + w + " surface=" + wsa.mSurfaceController
                        + " token=" + w.mToken
                        + " pid=" + w.mSession.mPid
                        + " uid=" + w.mSession.mUid);
                wsa.destroySurface();
                mService.mForceRemoves.add(w);
                mTmpWindow = w;
            } else if (w.mAppToken != null && w.mAppToken.isClientHidden()) {
                Slog.w(TAG_WM, "LEAKED SURFACE (app token hidden): "
                        + w + " surface=" + wsa.mSurfaceController
                        + " token=" + w.mAppToken);
                if (SHOW_TRANSACTIONS) logSurface(w, "LEAK DESTROY", false);
                wsa.destroySurface();
                mTmpWindow = w;
            }
        }, false /* traverseTopToBottom */);

        return mTmpWindow != null;
    }

    /**
     * Determine and return the window that should be the IME target.
     * @param updateImeTarget If true the system IME target will be updated to match what we found.
     * @return The window that should be used as the IME target or null if there isn't any.
     */
    WindowState computeImeTarget(boolean updateImeTarget) {
        if (mService.mInputMethodWindow == null) {
            // There isn't an IME so there shouldn't be a target...That was easy!
            if (updateImeTarget) {
                if (DEBUG_INPUT_METHOD) Slog.w(TAG_WM, "Moving IM target from "
                        + mService.mInputMethodTarget + " to null since mInputMethodWindow is null");
                setInputMethodTarget(null, mService.mInputMethodTargetWaitingAnim);
            }
            return null;
        }

        // TODO(multidisplay): Needs some serious rethought when the target and IME are not on the
        // same display. Or even when the current IME/target are not on the same screen as the next
        // IME/target. For now only look for input windows on the main screen.
        mUpdateImeTarget = updateImeTarget;
        WindowState target = getWindow(mComputeImeTargetPredicate);


        // Yet more tricksyness!  If this window is a "starting" window, we do actually want
        // to be on top of it, but it is not -really- where input will go. So look down below
        // for a real window to target...
        if (target != null && target.mAttrs.type == TYPE_APPLICATION_STARTING) {
            final AppWindowToken token = target.mAppToken;
            if (token != null) {
                final WindowState betterTarget = token.getImeTargetBelowWindow(target);
                if (betterTarget != null) {
                    target = betterTarget;
                }
            }
        }

        if (DEBUG_INPUT_METHOD && updateImeTarget) Slog.v(TAG_WM,
                "Proposed new IME target: " + target);

        // Now, a special case -- if the last target's window is in the process of exiting, and is
        // above the new target, keep on the last target to avoid flicker. Consider for example a
        // Dialog with the IME shown: when the Dialog is dismissed, we want to keep the IME above it
        // until it is completely gone so it doesn't drop behind the dialog or its full-screen
        // scrim.
        final WindowState curTarget = mService.mInputMethodTarget;
        if (curTarget != null && curTarget.isDisplayedLw() && curTarget.isClosing()
                && (target == null
                    || curTarget.mWinAnimator.mAnimLayer > target.mWinAnimator.mAnimLayer)) {
            if (DEBUG_INPUT_METHOD) Slog.v(TAG_WM, "Current target higher, not changing");
            return curTarget;
        }

        if (DEBUG_INPUT_METHOD) Slog.v(TAG_WM, "Desired input method target=" + target
                + " updateImeTarget=" + updateImeTarget);

        if (target == null) {
            if (updateImeTarget) {
                if (DEBUG_INPUT_METHOD) Slog.w(TAG_WM, "Moving IM target from " + curTarget
                        + " to null." + (SHOW_STACK_CRAWLS ? " Callers="
                        + Debug.getCallers(4) : ""));
                setInputMethodTarget(null, mService.mInputMethodTargetWaitingAnim);
            }

            return null;
        }

        if (updateImeTarget) {
            AppWindowToken token = curTarget == null ? null : curTarget.mAppToken;
            if (token != null) {

                // Now some fun for dealing with window animations that modify the Z order. We need
                // to look at all windows below the current target that are in this app, finding the
                // highest visible one in layering.
                WindowState highestTarget = null;
                if (token.isSelfAnimating()) {
                    highestTarget = token.getHighestAnimLayerWindow(curTarget);
                }

                if (highestTarget != null) {
                    final AppTransition appTransition = mService.mAppTransition;
                    if (DEBUG_INPUT_METHOD) Slog.v(TAG_WM, appTransition + " " + highestTarget
                            + " animating=" + highestTarget.mWinAnimator.isAnimationSet()
                            + " layer=" + highestTarget.mWinAnimator.mAnimLayer
                            + " new layer=" + target.mWinAnimator.mAnimLayer);

                    if (appTransition.isTransitionSet()) {
                        // If we are currently setting up for an animation, hold everything until we
                        // can find out what will happen.
                        setInputMethodTarget(highestTarget, true);
                        return highestTarget;
                    } else if (highestTarget.mWinAnimator.isAnimationSet() &&
                            highestTarget.mWinAnimator.mAnimLayer > target.mWinAnimator.mAnimLayer) {
                        // If the window we are currently targeting is involved with an animation,
                        // and it is on top of the next target we will be over, then hold off on
                        // moving until that is done.
                        setInputMethodTarget(highestTarget, true);
                        return highestTarget;
                    }
                }
            }

            if (DEBUG_INPUT_METHOD) Slog.w(TAG_WM, "Moving IM target from " + curTarget + " to "
                    + target + (SHOW_STACK_CRAWLS ? " Callers=" + Debug.getCallers(4) : ""));
            setInputMethodTarget(target, false);
        }

        return target;
    }

    private void setInputMethodTarget(WindowState target, boolean targetWaitingAnim) {
        if (target == mService.mInputMethodTarget
                && mService.mInputMethodTargetWaitingAnim == targetWaitingAnim) {
            return;
        }

        mService.mInputMethodTarget = target;
        mService.mInputMethodTargetWaitingAnim = targetWaitingAnim;
        assignWindowLayers(false /* setLayoutNeeded */);
    }

    boolean getNeedsMenu(WindowState top, WindowManagerPolicy.WindowState bottom) {
        if (top.mAttrs.needsMenuKey != NEEDS_MENU_UNSET) {
            return top.mAttrs.needsMenuKey == NEEDS_MENU_SET_TRUE;
        }

        // Used to indicate we have reached the first window in the range we are interested in.
        mTmpWindow = null;

        // TODO: Figure-out a more efficient way to do this.
        final WindowState candidate = getWindow(w -> {
            if (w == top) {
                // Reached the first window in the range we are interested in.
                mTmpWindow = w;
            }
            if (mTmpWindow == null) {
                return false;
            }

            if (w.mAttrs.needsMenuKey != NEEDS_MENU_UNSET) {
                return true;
            }
            // If we reached the bottom of the range of windows we are considering,
            // assume no menu is needed.
            if (w == bottom) {
                return true;
            }
            return false;
        });

        return candidate != null && candidate.mAttrs.needsMenuKey == NEEDS_MENU_SET_TRUE;
    }

    void setLayoutNeeded() {
        if (DEBUG_LAYOUT) Slog.w(TAG_WM, "setLayoutNeeded: callers=" + Debug.getCallers(3));
        mLayoutNeeded = true;
    }

    private void clearLayoutNeeded() {
        if (DEBUG_LAYOUT) Slog.w(TAG_WM, "clearLayoutNeeded: callers=" + Debug.getCallers(3));
        mLayoutNeeded = false;
    }

    boolean isLayoutNeeded() {
        return mLayoutNeeded;
    }

    void dumpTokens(PrintWriter pw, boolean dumpAll) {
        if (mTokenMap.isEmpty()) {
            return;
        }
        pw.println("  Display #" + mDisplayId);
        final Iterator<WindowToken> it = mTokenMap.values().iterator();
        while (it.hasNext()) {
            final WindowToken token = it.next();
            pw.print("  ");
            pw.print(token);
            if (dumpAll) {
                pw.println(':');
                token.dump(pw, "    ", dumpAll);
            } else {
                pw.println();
            }
        }
    }

    void dumpWindowAnimators(PrintWriter pw, String subPrefix) {
        final int[] index = new int[1];
        forAllWindows(w -> {
            final WindowStateAnimator wAnim = w.mWinAnimator;
            pw.println(subPrefix + "Window #" + index[0] + ": " + wAnim);
            index[0] = index[0] + 1;
        }, false /* traverseTopToBottom */);
    }

    /**
     * Starts the Keyguard exit animation on all windows that don't belong to an app token.
     */
    void startKeyguardExitOnNonAppWindows(boolean onWallpaper, boolean goingToShade) {
        final WindowManagerPolicy policy = mService.mPolicy;
        forAllWindows(w -> {
            if (w.mAppToken == null && policy.canBeHiddenByKeyguardLw(w)
                    && w.wouldBeVisibleIfPolicyIgnored() && !w.isVisible()) {
                w.startAnimation(policy.createHiddenByKeyguardExit(onWallpaper, goingToShade));
            }
        }, true /* traverseTopToBottom */);
    }

    boolean checkWaitingForWindows() {

        mHaveBootMsg = false;
        mHaveApp = false;
        mHaveWallpaper = false;
        mHaveKeyguard = true;

        final WindowState visibleWindow = getWindow(w -> {
            if (w.isVisibleLw() && !w.mObscured && !w.isDrawnLw()) {
                return true;
            }
            if (w.isDrawnLw()) {
                if (w.mAttrs.type == TYPE_BOOT_PROGRESS) {
                    mHaveBootMsg = true;
                } else if (w.mAttrs.type == TYPE_APPLICATION
                        || w.mAttrs.type == TYPE_DRAWN_APPLICATION) {
                    mHaveApp = true;
                } else if (w.mAttrs.type == TYPE_WALLPAPER) {
                    mHaveWallpaper = true;
                } else if (w.mAttrs.type == TYPE_STATUS_BAR) {
                    mHaveKeyguard = mService.mPolicy.isKeyguardDrawnLw();
                }
            }
            return false;
        });

        if (visibleWindow != null) {
            // We have a visible window.
            return true;
        }

        // if the wallpaper service is disabled on the device, we're never going to have
        // wallpaper, don't bother waiting for it
        boolean wallpaperEnabled = mService.mContext.getResources().getBoolean(
                com.android.internal.R.bool.config_enableWallpaperService)
                && mService.mContext.getResources().getBoolean(
                        com.android.internal.R.bool.config_checkWallpaperAtBoot)
                && !mService.mOnlyCore;

        if (DEBUG_SCREEN_ON || DEBUG_BOOT) Slog.i(TAG_WM,
                "******** booted=" + mService.mSystemBooted
                + " msg=" + mService.mShowingBootMessages
                + " haveBoot=" + mHaveBootMsg + " haveApp=" + mHaveApp
                + " haveWall=" + mHaveWallpaper + " wallEnabled=" + wallpaperEnabled
                + " haveKeyguard=" + mHaveKeyguard);

        // If we are turning on the screen to show the boot message, don't do it until the boot
        // message is actually displayed.
        if (!mService.mSystemBooted && !mHaveBootMsg) {
            return true;
        }

        // If we are turning on the screen after the boot is completed normally, don't do so until
        // we have the application and wallpaper.
        if (mService.mSystemBooted
                && ((!mHaveApp && !mHaveKeyguard) || (wallpaperEnabled && !mHaveWallpaper))) {
            return true;
        }

        return false;
    }

    void updateWindowsForAnimator(WindowAnimator animator) {
        mTmpWindowAnimator = animator;
        forAllWindows(mUpdateWindowsForAnimator, true /* traverseTopToBottom */);
    }

    void updateWallpaperForAnimator(WindowAnimator animator) {
        resetAnimationBackgroundAnimator();

        // Used to indicate a detached wallpaper.
        mTmpWindow = null;
        mTmpWindowAnimator = animator;

        forAllWindows(mUpdateWallpaperForAnimator, true /* traverseTopToBottom */);

        if (animator.mWindowDetachedWallpaper != mTmpWindow) {
            if (DEBUG_WALLPAPER) Slog.v(TAG, "Detached wallpaper changed from "
                    + animator.mWindowDetachedWallpaper + " to " + mTmpWindow);
            animator.mWindowDetachedWallpaper = mTmpWindow;
            animator.mBulkUpdateParams |= SET_WALLPAPER_MAY_CHANGE;
        }
    }

    boolean inputMethodClientHasFocus(IInputMethodClient client) {
        final WindowState imFocus = computeImeTarget(false /* updateImeTarget */);
        if (imFocus == null) {
            return false;
        }

        if (DEBUG_INPUT_METHOD) {
            Slog.i(TAG_WM, "Desired input method target: " + imFocus);
            Slog.i(TAG_WM, "Current focus: " + mService.mCurrentFocus);
            Slog.i(TAG_WM, "Last focus: " + mService.mLastFocus);
        }

        final IInputMethodClient imeClient = imFocus.mSession.mClient;

        if (DEBUG_INPUT_METHOD) {
            Slog.i(TAG_WM, "IM target client: " + imeClient);
            if (imeClient != null) {
                Slog.i(TAG_WM, "IM target client binder: " + imeClient.asBinder());
                Slog.i(TAG_WM, "Requesting client binder: " + client.asBinder());
            }
        }

        return imeClient != null && imeClient.asBinder() == client.asBinder();
    }

    boolean hasSecureWindowOnScreen() {
        final WindowState win = getWindow(
                w -> w.isOnScreen() && (w.mAttrs.flags & FLAG_SECURE) != 0);
        return win != null;
    }

    void updateSystemUiVisibility(int visibility, int globalDiff) {
        forAllWindows(w -> {
            try {
                final int curValue = w.mSystemUiVisibility;
                final int diff = (curValue ^ visibility) & globalDiff;
                final int newValue = (curValue & ~diff) | (visibility & diff);
                if (newValue != curValue) {
                    w.mSeq++;
                    w.mSystemUiVisibility = newValue;
                }
                if (newValue != curValue || w.mAttrs.hasSystemUiListeners) {
                    w.mClient.dispatchSystemUiVisibilityChanged(w.mSeq,
                            visibility, newValue, diff);
                }
            } catch (RemoteException e) {
                // so sorry
            }
        }, true /* traverseTopToBottom */);
    }

    void onWindowFreezeTimeout() {
        Slog.w(TAG_WM, "Window freeze timeout expired.");
        mService.mWindowsFreezingScreen = WINDOWS_FREEZING_SCREENS_TIMEOUT;

        forAllWindows(w -> {
            if (!w.getOrientationChanging()) {
                return;
            }
            w.orientationChangeTimedOut();
            w.mLastFreezeDuration = (int)(SystemClock.elapsedRealtime()
                    - mService.mDisplayFreezeTime);
            Slog.w(TAG_WM, "Force clearing orientation change: " + w);
        }, true /* traverseTopToBottom */);
        mService.mWindowPlacerLocked.performSurfacePlacement();
    }

    void waitForAllWindowsDrawn() {
        final WindowManagerPolicy policy = mService.mPolicy;
        forAllWindows(w -> {
            final boolean keyguard = policy.isKeyguardHostWindow(w.mAttrs);
            if (w.isVisibleLw() && (w.mAppToken != null || keyguard)) {
                w.mWinAnimator.mDrawState = DRAW_PENDING;
                // Force add to mResizingWindows.
                w.mLastContentInsets.set(-1, -1, -1, -1);
                mService.mWaitingForDrawn.add(w);
            }
        }, true /* traverseTopToBottom */);
    }

    // TODO: Super crazy long method that should be broken down...
    boolean applySurfaceChangesTransaction(boolean recoveringMemory) {

        final int dw = mDisplayInfo.logicalWidth;
        final int dh = mDisplayInfo.logicalHeight;
        final WindowSurfacePlacer surfacePlacer = mService.mWindowPlacerLocked;

        mTmpUpdateAllDrawn.clear();

        int repeats = 0;
        do {
            repeats++;
            if (repeats > 6) {
                Slog.w(TAG, "Animation repeat aborted after too many iterations");
                clearLayoutNeeded();
                break;
            }

            if (DEBUG_LAYOUT_REPEATS) surfacePlacer.debugLayoutRepeats("On entry to LockedInner",
                    pendingLayoutChanges);

            // TODO(multi-display): For now adjusting wallpaper only on primary display to avoid
            // the wallpaper window jumping across displays.
            // Remove check for default display when there will be support for multiple wallpaper
            // targets (on different displays).
            if (isDefaultDisplay && (pendingLayoutChanges & FINISH_LAYOUT_REDO_WALLPAPER) != 0) {
                mWallpaperController.adjustWallpaperWindows(this);
            }

            if (isDefaultDisplay && (pendingLayoutChanges & FINISH_LAYOUT_REDO_CONFIG) != 0) {
                if (DEBUG_LAYOUT) Slog.v(TAG, "Computing new config from layout");
                if (mService.updateOrientationFromAppTokensLocked(mDisplayId)) {
                    setLayoutNeeded();
                    mService.mH.obtainMessage(SEND_NEW_CONFIGURATION, mDisplayId).sendToTarget();
                }
            }

            if ((pendingLayoutChanges & FINISH_LAYOUT_REDO_LAYOUT) != 0) {
                setLayoutNeeded();
            }

            // FIRST LOOP: Perform a layout, if needed.
            if (repeats < LAYOUT_REPEAT_THRESHOLD) {
                performLayout(repeats == 1, false /* updateInputWindows */);
            } else {
                Slog.w(TAG, "Layout repeat skipped after too many iterations");
            }

            // FIRST AND ONE HALF LOOP: Make WindowManagerPolicy think it is animating.
            pendingLayoutChanges = 0;

            if (isDefaultDisplay) {
                mService.mPolicy.beginPostLayoutPolicyLw(dw, dh);
                forAllWindows(mApplyPostLayoutPolicy, true /* traverseTopToBottom */);
                pendingLayoutChanges |= mService.mPolicy.finishPostLayoutPolicyLw();
                if (DEBUG_LAYOUT_REPEATS) surfacePlacer.debugLayoutRepeats(
                        "after finishPostLayoutPolicyLw", pendingLayoutChanges);
            }
        } while (pendingLayoutChanges != 0);

        mTmpApplySurfaceChangesTransactionState.reset();

        mTmpRecoveringMemory = recoveringMemory;
        forAllWindows(mApplySurfaceChangesTransaction, true /* traverseTopToBottom */);
        prepareSurfaces();

        mService.mDisplayManagerInternal.setDisplayProperties(mDisplayId,
                mTmpApplySurfaceChangesTransactionState.displayHasContent,
                mTmpApplySurfaceChangesTransactionState.preferredRefreshRate,
                mTmpApplySurfaceChangesTransactionState.preferredModeId,
                true /* inTraversal, must call performTraversalInTrans... below */);

        final boolean wallpaperVisible = mWallpaperController.isWallpaperVisible();
        if (wallpaperVisible != mLastWallpaperVisible) {
            mLastWallpaperVisible = wallpaperVisible;
            mService.mWallpaperVisibilityListeners.notifyWallpaperVisibilityChanged(this);
        }

        while (!mTmpUpdateAllDrawn.isEmpty()) {
            final AppWindowToken atoken = mTmpUpdateAllDrawn.removeLast();
            // See if any windows have been drawn, so they (and others associated with them)
            // can now be shown.
            atoken.updateAllDrawn();
        }

        return mTmpApplySurfaceChangesTransactionState.focusDisplayed;
    }

    private void updateBounds() {
        calculateBounds(mTmpBounds);
        setBounds(mTmpBounds);
    }

    // Determines the current display bounds based on the current state
    private void calculateBounds(Rect out) {
        // Uses same calculation as in LogicalDisplay#configureDisplayInTransactionLocked.
        final int orientation = mDisplayInfo.rotation;
        boolean rotated = (orientation == ROTATION_90 || orientation == ROTATION_270);
        final int physWidth = rotated ? mBaseDisplayHeight : mBaseDisplayWidth;
        final int physHeight = rotated ? mBaseDisplayWidth : mBaseDisplayHeight;
        int width = mDisplayInfo.logicalWidth;
        int left = (physWidth - width) / 2;
        int height = mDisplayInfo.logicalHeight;
        int top = (physHeight - height) / 2;
        out.set(left, top, left + width, top + height);
    }

    @Override
    public void getBounds(Rect out) {
        calculateBounds(out);
    }

    private void getBounds(Rect out, int orientation) {
        getBounds(out);

        // Rotate the Rect if needed.
        final int currentRotation = mDisplayInfo.rotation;
        final int rotationDelta = deltaRotation(currentRotation, orientation);
        if (rotationDelta == ROTATION_90 || rotationDelta == ROTATION_270) {
            createRotationMatrix(rotationDelta, mBaseDisplayWidth, mBaseDisplayHeight, mTmpMatrix);
            mTmpRectF.set(out);
            mTmpMatrix.mapRect(mTmpRectF);
            mTmpRectF.round(out);
        }
    }

    void performLayout(boolean initial, boolean updateInputWindows) {
        if (!isLayoutNeeded()) {
            return;
        }
        clearLayoutNeeded();

        final int dw = mDisplayInfo.logicalWidth;
        final int dh = mDisplayInfo.logicalHeight;
        if (DEBUG_LAYOUT) {
            Slog.v(TAG, "-------------------------------------");
            Slog.v(TAG, "performLayout: needed=" + isLayoutNeeded() + " dw=" + dw + " dh=" + dh);
        }

        mDisplayFrames.onDisplayInfoUpdated(mDisplayInfo,
                calculateDisplayCutoutForRotation(mDisplayInfo.rotation));
        // TODO: Not sure if we really need to set the rotation here since we are updating from the
        // display info above...
        mDisplayFrames.mRotation = mRotation;
        mService.mPolicy.beginLayoutLw(mDisplayFrames, getConfiguration().uiMode);
        if (isDefaultDisplay) {
            // Not needed on non-default displays.
            mService.mSystemDecorLayer = mService.mPolicy.getSystemDecorLayerLw();
            mService.mScreenRect.set(0, 0, dw, dh);
        }

        int seq = mLayoutSeq + 1;
        if (seq < 0) seq = 0;
        mLayoutSeq = seq;

        // Used to indicate that we have processed the dream window and all additional windows are
        // behind it.
        mTmpWindow = null;
        mTmpInitial = initial;

        // First perform layout of any root windows (not attached to another window).
        forAllWindows(mPerformLayout, true /* traverseTopToBottom */);

        // Used to indicate that we have processed the dream window and all additional attached
        // windows are behind it.
        mTmpWindow2 = mTmpWindow;
        mTmpWindow = null;

        // Now perform layout of attached windows, which usually depend on the position of the
        // window they are attached to. XXX does not deal with windows that are attached to windows
        // that are themselves attached.
        forAllWindows(mPerformLayoutAttached, true /* traverseTopToBottom */);

        // Window frames may have changed. Tell the input dispatcher about it.
        mService.mInputMonitor.layoutInputConsumers(dw, dh);
        mService.mInputMonitor.setUpdateInputWindowsNeededLw();
        if (updateInputWindows) {
            mService.mInputMonitor.updateInputWindowsLw(false /*force*/);
        }

        mService.mH.sendEmptyMessage(UPDATE_DOCKED_STACK_DIVIDER);
    }

    /**
     * Takes a snapshot of the display.  In landscape mode this grabs the whole screen.
     * In portrait mode, it grabs the full screenshot.
     *
     * @param config of the output bitmap
     */
    Bitmap screenshotDisplayLocked(Bitmap.Config config) {
        if (!mService.mPolicy.isScreenOn()) {
            if (DEBUG_SCREENSHOT) {
                Slog.i(TAG_WM, "Attempted to take screenshot while display was off.");
            }
            return null;
        }

        int dw = mDisplayInfo.logicalWidth;
        int dh = mDisplayInfo.logicalHeight;

        if (dw <= 0 || dh <= 0) {
            return null;
        }

        final Rect frame = new Rect(0, 0, dw, dh);

        // The screenshot API does not apply the current screen rotation.
        int rot = mDisplay.getRotation();

        if (rot == ROTATION_90 || rot == ROTATION_270) {
            rot = (rot == ROTATION_90) ? ROTATION_270 : ROTATION_90;
        }

        // SurfaceFlinger is not aware of orientation, so convert our logical
        // crop to SurfaceFlinger's portrait orientation.
        convertCropForSurfaceFlinger(frame, rot, dw, dh);

        final ScreenRotationAnimation screenRotationAnimation =
                mService.mAnimator.getScreenRotationAnimationLocked(DEFAULT_DISPLAY);
        final boolean inRotation = screenRotationAnimation != null &&
                screenRotationAnimation.isAnimating();
        if (DEBUG_SCREENSHOT && inRotation) Slog.v(TAG_WM, "Taking screenshot while rotating");

        // TODO(b/68392460): We should screenshot Task controls directly
        // but it's difficult at the moment as the Task doesn't have the
        // correct size set.
        final Bitmap bitmap = SurfaceControl.screenshot(frame, dw, dh, 0, 1, inRotation, rot);
        if (bitmap == null) {
            Slog.w(TAG_WM, "Failed to take screenshot");
            return null;
        }

        // Create a copy of the screenshot that is immutable and backed in ashmem.
        // This greatly reduces the overhead of passing the bitmap between processes.
        final Bitmap ret = bitmap.createAshmemBitmap(config);
        bitmap.recycle();
        return ret;
    }

    // TODO: Can this use createRotationMatrix()?
    private static void convertCropForSurfaceFlinger(Rect crop, int rot, int dw, int dh) {
        if (rot == Surface.ROTATION_90) {
            final int tmp = crop.top;
            crop.top = dw - crop.right;
            crop.right = crop.bottom;
            crop.bottom = dw - crop.left;
            crop.left = tmp;
        } else if (rot == Surface.ROTATION_180) {
            int tmp = crop.top;
            crop.top = dh - crop.bottom;
            crop.bottom = dh - tmp;
            tmp = crop.right;
            crop.right = dw - crop.left;
            crop.left = dw - tmp;
        } else if (rot == Surface.ROTATION_270) {
            final int tmp = crop.top;
            crop.top = crop.left;
            crop.left = dh - crop.bottom;
            crop.bottom = crop.right;
            crop.right = dh - tmp;
        }
    }

    void onSeamlessRotationTimeout() {
        // Used to indicate the layout is needed.
        mTmpWindow = null;

        forAllWindows(w -> {
            if (!w.mSeamlesslyRotated) {
                return;
            }
            mTmpWindow = w;
            w.setDisplayLayoutNeeded();
            mService.markForSeamlessRotation(w, false);
        }, true /* traverseTopToBottom */);

        if (mTmpWindow != null) {
            mService.mWindowPlacerLocked.performSurfacePlacement();
        }
    }

    void setExitingTokensHasVisible(boolean hasVisible) {
        for (int i = mExitingTokens.size() - 1; i >= 0; i--) {
            mExitingTokens.get(i).hasVisible = hasVisible;
        }

        // Initialize state of exiting applications.
        mTaskStackContainers.setExitingTokensHasVisible(hasVisible);
    }

    void removeExistingTokensIfPossible() {
        for (int i = mExitingTokens.size() - 1; i >= 0; i--) {
            final WindowToken token = mExitingTokens.get(i);
            if (!token.hasVisible) {
                mExitingTokens.remove(i);
            }
        }

        // Time to remove any exiting applications?
        mTaskStackContainers.removeExistingAppTokensIfPossible();
    }

    @Override
    void onDescendantOverrideConfigurationChanged() {
        setLayoutNeeded();
        mService.requestTraversal();
    }

    boolean okToDisplay() {
        if (mDisplayId == DEFAULT_DISPLAY) {
            return !mService.mDisplayFrozen
                    && mService.mDisplayEnabled && mService.mPolicy.isScreenOn();
        }
        return mDisplayInfo.state == Display.STATE_ON;
    }

    boolean okToAnimate() {
        return okToDisplay() &&
                (mDisplayId != DEFAULT_DISPLAY || mService.mPolicy.okToAnimate());
    }

    static final class TaskForResizePointSearchResult {
        boolean searchDone;
        Task taskForResize;

        void reset() {
            searchDone = false;
            taskForResize = null;
        }
    }

    private static final class ApplySurfaceChangesTransactionState {
        boolean displayHasContent;
        boolean obscured;
        boolean syswin;
        boolean focusDisplayed;
        float preferredRefreshRate;
        int preferredModeId;

        void reset() {
            displayHasContent = false;
            obscured = false;
            syswin = false;
            focusDisplayed = false;
            preferredRefreshRate = 0;
            preferredModeId = 0;
        }
    }

    private static final class ScreenshotApplicationState {
        WindowState appWin;
        int maxLayer;
        int minLayer;
        boolean screenshotReady;

        void reset(boolean screenshotReady) {
            appWin = null;
            maxLayer = 0;
            minLayer = 0;
            this.screenshotReady = screenshotReady;
            minLayer = (screenshotReady) ? 0 : Integer.MAX_VALUE;
        }
    }

    /**
     * Base class for any direct child window container of {@link #DisplayContent} need to inherit
     * from. This is mainly a pass through class that allows {@link #DisplayContent} to have
     * homogeneous children type which is currently required by sub-classes of
     * {@link WindowContainer} class.
     */
    static class DisplayChildWindowContainer<E extends WindowContainer> extends WindowContainer<E> {

        DisplayChildWindowContainer(WindowManagerService service) {
            super(service);
        }

        @Override
        boolean fillsParent() {
            return true;
        }

        @Override
        boolean isVisible() {
            return true;
        }
    }

    /**
     * Window container class that contains all containers on this display relating to Apps.
     * I.e Activities.
     */
    private final class TaskStackContainers extends DisplayChildWindowContainer<TaskStack> {
        /**
         * A control placed at the appropriate level for transitions to occur.
         */
        SurfaceControl mAppAnimationLayer = null;
        SurfaceControl mBoostedAppAnimationLayer = null;
        SurfaceControl mHomeAppAnimationLayer = null;

        /**
         * Given that the split-screen divider does not have an AppWindowToken, it
         * will have to live inside of a "NonAppWindowContainer", in particular
         * {@link DisplayContent#mAboveAppWindowsContainers}. However, in visual Z order
         * it will need to be interleaved with some of our children, appearing on top of
         * both docked stacks but underneath any assistant stacks.
         *
         * To solve this problem we have this anchor control, which will always exist so
         * we can always assign it the correct value in our {@link #assignChildLayers}.
         * Likewise since it always exists, {@link AboveAppWindowContainers} can always
         * assign the divider a layer relative to it. This way we prevent linking lifecycle
         * events between the two containers.
         */
        SurfaceControl mSplitScreenDividerAnchor = null;

        // Cached reference to some special stacks we tend to get a lot so we don't need to loop
        // through the list to find them.
        private TaskStack mHomeStack = null;
        private TaskStack mPinnedStack = null;
        private TaskStack mSplitScreenPrimaryStack = null;

        TaskStackContainers(WindowManagerService service) {
            super(service);
        }

        /**
         * Returns the topmost stack on the display that is compatible with the input windowing mode
         * and activity type. Null is no compatible stack on the display.
         */
        TaskStack getStack(int windowingMode, int activityType) {
            if (activityType == ACTIVITY_TYPE_HOME) {
                return mHomeStack;
            }
            if (windowingMode == WINDOWING_MODE_PINNED) {
                return mPinnedStack;
            } else if (windowingMode == WINDOWING_MODE_SPLIT_SCREEN_PRIMARY) {
                return mSplitScreenPrimaryStack;
            }
            for (int i = mTaskStackContainers.getChildCount() - 1; i >= 0; --i) {
                final TaskStack stack = mTaskStackContainers.getChildAt(i);
                if (activityType == ACTIVITY_TYPE_UNDEFINED
                        && windowingMode == stack.getWindowingMode()) {
                    // Passing in undefined type means we want to match the topmost stack with the
                    // windowing mode.
                    return stack;
                }
                if (stack.isCompatible(windowingMode, activityType)) {
                    return stack;
                }
            }
            return null;
        }

        @VisibleForTesting
        TaskStack getTopStack() {
            return mTaskStackContainers.getChildCount() > 0
                    ? mTaskStackContainers.getChildAt(mTaskStackContainers.getChildCount() - 1) : null;
        }

        TaskStack getHomeStack() {
            if (mHomeStack == null && mDisplayId == DEFAULT_DISPLAY) {
                Slog.e(TAG_WM, "getHomeStack: Returning null from this=" + this);
            }
            return mHomeStack;
        }

        TaskStack getPinnedStack() {
            return mPinnedStack;
        }

        TaskStack getSplitScreenPrimaryStack() {
            return mSplitScreenPrimaryStack;
        }

        ArrayList<Task> getVisibleTasks() {
            final ArrayList<Task> visibleTasks = new ArrayList<>();
            forAllTasks(task -> {
                if (task.isVisible()) {
                    visibleTasks.add(task);
                }
            });
            return visibleTasks;
        }

        /**
         * Adds the stack to this container.
         * @see DisplayContent#createStack(int, boolean, StackWindowController)
         */
        void addStackToDisplay(TaskStack stack, boolean onTop) {
            addStackReferenceIfNeeded(stack);
            addChild(stack, onTop);
            stack.onDisplayChanged(DisplayContent.this);
        }

        void onStackWindowingModeChanged(TaskStack stack) {
            removeStackReferenceIfNeeded(stack);
            addStackReferenceIfNeeded(stack);
            if (stack == mPinnedStack && getTopStack() != stack) {
                // Looks like this stack changed windowing mode to pinned. Move it to the top.
                positionChildAt(POSITION_TOP, stack, false /* includingParents */);
            }
        }

        private void addStackReferenceIfNeeded(TaskStack stack) {
            if (stack.isActivityTypeHome()) {
                if (mHomeStack != null) {
                    throw new IllegalArgumentException("addStackReferenceIfNeeded: home stack="
                            + mHomeStack + " already exist on display=" + this + " stack=" + stack);
                }
                mHomeStack = stack;
            }
            final int windowingMode = stack.getWindowingMode();
            if (windowingMode == WINDOWING_MODE_PINNED) {
                if (mPinnedStack != null) {
                    throw new IllegalArgumentException("addStackReferenceIfNeeded: pinned stack="
                            + mPinnedStack + " already exist on display=" + this
                            + " stack=" + stack);
                }
                mPinnedStack = stack;
            } else if (windowingMode == WINDOWING_MODE_SPLIT_SCREEN_PRIMARY) {
                if (mSplitScreenPrimaryStack != null) {
                    throw new IllegalArgumentException("addStackReferenceIfNeeded:"
                            + " split-screen-primary" + " stack=" + mSplitScreenPrimaryStack
                            + " already exist on display=" + this + " stack=" + stack);
                }
                mSplitScreenPrimaryStack = stack;
                mDividerControllerLocked.notifyDockedStackExistsChanged(true);
            }
        }

        private void removeStackReferenceIfNeeded(TaskStack stack) {
            if (stack == mHomeStack) {
                mHomeStack = null;
            } else if (stack == mPinnedStack) {
                mPinnedStack = null;
            } else if (stack == mSplitScreenPrimaryStack) {
                mSplitScreenPrimaryStack = null;
                // Re-set the split-screen create mode whenever the split-screen stack is removed.
                mService.setDockedStackCreateStateLocked(
                        SPLIT_SCREEN_CREATE_MODE_TOP_OR_LEFT, null /* initialBounds */);
                mDividerControllerLocked.notifyDockedStackExistsChanged(false);
            }
        }

        private void addChild(TaskStack stack, boolean toTop) {
            final int addIndex = findPositionForStack(toTop ? mChildren.size() : 0, stack,
                    true /* adding */);
            addChild(stack, addIndex);
            setLayoutNeeded();
        }

        @Override
        protected void removeChild(TaskStack stack) {
            super.removeChild(stack);
            removeStackReferenceIfNeeded(stack);
        }

        @Override
        boolean isOnTop() {
            // Considered always on top
            return true;
        }

        @Override
        void positionChildAt(int position, TaskStack child, boolean includingParents) {
            if (child.getWindowConfiguration().isAlwaysOnTop()
                    && position != POSITION_TOP) {
                // This stack is always-on-top, override the default behavior.
                Slog.w(TAG_WM, "Ignoring move of always-on-top stack=" + this + " to bottom");

                // Moving to its current position, as we must call super but we don't want to
                // perform any meaningful action.
                final int currentPosition = mChildren.indexOf(child);
                super.positionChildAt(currentPosition, child, false /* includingParents */);
                return;
            }

            final int targetPosition = findPositionForStack(position, child, false /* adding */);
            super.positionChildAt(targetPosition, child, includingParents);

            setLayoutNeeded();
        }

        /**
         * When stack is added or repositioned, find a proper position for it.
         * This will make sure that pinned stack always stays on top.
         * @param requestedPosition Position requested by caller.
         * @param stack Stack to be added or positioned.
         * @param adding Flag indicates whether we're adding a new stack or positioning an existing.
         * @return The proper position for the stack.
         */
        private int findPositionForStack(int requestedPosition, TaskStack stack, boolean adding) {
            final int topChildPosition = mChildren.size() - 1;
            boolean toTop = requestedPosition == POSITION_TOP;
            toTop |= adding ? requestedPosition >= topChildPosition + 1
                    : requestedPosition >= topChildPosition;
            int targetPosition = requestedPosition;

            if (toTop && stack.getWindowingMode() != WINDOWING_MODE_PINNED && hasPinnedStack()) {
                // The pinned stack is always the top most stack (always-on-top) when it is present.
                TaskStack topStack = mChildren.get(topChildPosition);
                if (topStack.getWindowingMode() != WINDOWING_MODE_PINNED) {
                    throw new IllegalStateException("Pinned stack isn't top stack??? " + mChildren);
                }

                // So, stack is moved just below the pinned stack.
                // When we're adding a new stack the target is the current pinned stack position.
                // When we're positioning an existing stack the target is the position below pinned
                // stack, because WindowContainer#positionAt() first removes element and then adds
                // it to specified place.
                targetPosition = adding ? topChildPosition : topChildPosition - 1;
            }

            return targetPosition;
        }

        @Override
        boolean forAllWindows(ToBooleanFunction<WindowState> callback,
                boolean traverseTopToBottom) {
            if (traverseTopToBottom) {
                if (super.forAllWindows(callback, traverseTopToBottom)) {
                    return true;
                }
                if (forAllExitingAppTokenWindows(callback, traverseTopToBottom)) {
                    return true;
                }
            } else {
                if (forAllExitingAppTokenWindows(callback, traverseTopToBottom)) {
                    return true;
                }
                if (super.forAllWindows(callback, traverseTopToBottom)) {
                    return true;
                }
            }
            return false;
        }

        private boolean forAllExitingAppTokenWindows(ToBooleanFunction<WindowState> callback,
                boolean traverseTopToBottom) {
            // For legacy reasons we process the TaskStack.mExitingAppTokens first here before the
            // app tokens.
            // TODO: Investigate if we need to continue to do this or if we can just process them
            // in-order.
            if (traverseTopToBottom) {
                for (int i = mChildren.size() - 1; i >= 0; --i) {
                    final AppTokenList appTokens = mChildren.get(i).mExitingAppTokens;
                    for (int j = appTokens.size() - 1; j >= 0; --j) {
                        if (appTokens.get(j).forAllWindowsUnchecked(callback,
                                traverseTopToBottom)) {
                            return true;
                        }
                    }
                }
            } else {
                final int count = mChildren.size();
                for (int i = 0; i < count; ++i) {
                    final AppTokenList appTokens = mChildren.get(i).mExitingAppTokens;
                    final int appTokensCount = appTokens.size();
                    for (int j = 0; j < appTokensCount; j++) {
                        if (appTokens.get(j).forAllWindowsUnchecked(callback,
                                traverseTopToBottom)) {
                            return true;
                        }
                    }
                }
            }
            return false;
        }

        void setExitingTokensHasVisible(boolean hasVisible) {
            for (int i = mChildren.size() - 1; i >= 0; --i) {
                final AppTokenList appTokens = mChildren.get(i).mExitingAppTokens;
                for (int j = appTokens.size() - 1; j >= 0; --j) {
                    appTokens.get(j).hasVisible = hasVisible;
                }
            }
        }

        void removeExistingAppTokensIfPossible() {
            for (int i = mChildren.size() - 1; i >= 0; --i) {
                final AppTokenList appTokens = mChildren.get(i).mExitingAppTokens;
                for (int j = appTokens.size() - 1; j >= 0; --j) {
                    final AppWindowToken token = appTokens.get(j);
                    if (!token.hasVisible && !mService.mClosingApps.contains(token)
                            && (!token.mIsExiting || token.isEmpty())) {
                        // Make sure there is no animation running on this token, so any windows
                        // associated with it will be removed as soon as their animations are
                        // complete.
                        cancelAnimation();
                        if (DEBUG_ADD_REMOVE || DEBUG_TOKEN_MOVEMENT) Slog.v(TAG,
                                "performLayout: App token exiting now removed" + token);
                        token.removeIfPossible();
                    }
                }
            }
        }

        @Override
        int getOrientation() {
            if (isStackVisible(WINDOWING_MODE_SPLIT_SCREEN_PRIMARY)
                    || isStackVisible(WINDOWING_MODE_FREEFORM)) {
                // Apps and their containers are not allowed to specify an orientation while the
                // docked or freeform stack is visible...except for the home stack/task if the
                // docked stack is minimized and it actually set something.
                if (mHomeStack != null && mHomeStack.isVisible()
                        && mDividerControllerLocked.isMinimizedDock()) {
                    final int orientation = mHomeStack.getOrientation();
                    if (orientation != SCREEN_ORIENTATION_UNSET) {
                        return orientation;
                    }
                }
                return SCREEN_ORIENTATION_UNSPECIFIED;
            }

            final int orientation = super.getOrientation();
            boolean isCar = mService.mContext.getPackageManager().hasSystemFeature(
                    PackageManager.FEATURE_AUTOMOTIVE);
            if (isCar) {
                // In a car, you cannot physically rotate the screen, so it doesn't make sense to
                // allow anything but the default orientation.
                if (DEBUG_ORIENTATION) Slog.v(TAG_WM,
                        "Forcing UNSPECIFIED orientation in car for display id=" + mDisplayId
                                + ". Ignoring " + orientation);
                return SCREEN_ORIENTATION_UNSPECIFIED;
            }

            if (orientation != SCREEN_ORIENTATION_UNSET
                    && orientation != SCREEN_ORIENTATION_BEHIND) {
                if (DEBUG_ORIENTATION) Slog.v(TAG_WM,
                        "App is requesting an orientation, return " + orientation
                                + " for display id=" + mDisplayId);
                return orientation;
            }

            if (DEBUG_ORIENTATION) Slog.v(TAG_WM,
                    "No app is requesting an orientation, return " + mLastOrientation
                            + " for display id=" + mDisplayId);
            // The next app has not been requested to be visible, so we keep the current orientation
            // to prevent freezing/unfreezing the display too early.
            return mLastOrientation;
        }

        @Override
        void assignChildLayers(SurfaceControl.Transaction t) {
            assignStackOrdering(t);

            for (int i = 0; i < mChildren.size(); i++) {
                final TaskStack s = mChildren.get(i);
                s.assignChildLayers(t);
            }
        }

        void assignStackOrdering(SurfaceControl.Transaction t) {

            final int HOME_STACK_STATE = 0;
            final int NORMAL_STACK_STATE = 1;
            final int ALWAYS_ON_TOP_STATE = 2;

            int layer = 0;
            int layerForAnimationLayer = 0;
            int layerForBoostedAnimationLayer = 0;
            int layerForHomeAnimationLayer = 0;

            for (int state = 0; state <= ALWAYS_ON_TOP_STATE; state++) {
                for (int i = 0; i < mChildren.size(); i++) {
                    final TaskStack s = mChildren.get(i);
                    if (state == HOME_STACK_STATE && !s.isActivityTypeHome()) {
                        continue;
                    } else if (state == NORMAL_STACK_STATE && (s.isActivityTypeHome()
                            || s.isAlwaysOnTop())) {
                        continue;
                    } else if (state == ALWAYS_ON_TOP_STATE && !s.isAlwaysOnTop()) {
                        continue;
                    }
                    s.assignLayer(t, layer++);
                    if (s.inSplitScreenWindowingMode() && mSplitScreenDividerAnchor != null) {
                        t.setLayer(mSplitScreenDividerAnchor, layer++);
                    }
                    if ((s.isTaskAnimating() || s.isAppAnimating())
                            && state != ALWAYS_ON_TOP_STATE) {
                        // Ensure the animation layer ends up above the
                        // highest animating stack and no higher.
                        layerForAnimationLayer = layer++;
                    }
                    if (state != ALWAYS_ON_TOP_STATE) {
                        layerForBoostedAnimationLayer = layer++;
                    }
                }
                if (state == HOME_STACK_STATE) {
                    layerForHomeAnimationLayer = layer++;
                }
            }
            if (mAppAnimationLayer != null) {
                t.setLayer(mAppAnimationLayer, layerForAnimationLayer);
            }
            if (mBoostedAppAnimationLayer != null) {
                t.setLayer(mBoostedAppAnimationLayer, layerForBoostedAnimationLayer);
            }
            if (mHomeAppAnimationLayer != null) {
                t.setLayer(mHomeAppAnimationLayer, layerForHomeAnimationLayer);
            }
        }

        @Override
        SurfaceControl getAppAnimationLayer(@AnimationLayer int animationLayer) {
            switch (animationLayer) {
                case ANIMATION_LAYER_BOOSTED:
                    return mBoostedAppAnimationLayer;
                case ANIMATION_LAYER_HOME:
                    return mHomeAppAnimationLayer;
                case ANIMATION_LAYER_STANDARD:
                default:
                    return mAppAnimationLayer;
            }
        }

        SurfaceControl getSplitScreenDividerAnchor() {
            return mSplitScreenDividerAnchor;
        }

        @Override
        void onParentSet() {
            super.onParentSet();
            if (getParent() != null) {
                mAppAnimationLayer = makeChildSurface(null)
                        .setName("animationLayer")
                        .build();
                mBoostedAppAnimationLayer = makeChildSurface(null)
                        .setName("boostedAnimationLayer")
                        .build();
                mHomeAppAnimationLayer = makeChildSurface(null)
                        .setName("homeAnimationLayer")
                        .build();
                mSplitScreenDividerAnchor = makeChildSurface(null)
                        .setName("splitScreenDividerAnchor")
                        .build();
                getPendingTransaction()
                        .show(mAppAnimationLayer)
                        .show(mBoostedAppAnimationLayer)
                        .show(mHomeAppAnimationLayer)
                        .show(mSplitScreenDividerAnchor);
                scheduleAnimation();
            } else {
                mAppAnimationLayer.destroy();
                mAppAnimationLayer = null;
                mBoostedAppAnimationLayer.destroy();
                mBoostedAppAnimationLayer = null;
                mHomeAppAnimationLayer.destroy();
                mHomeAppAnimationLayer = null;
                mSplitScreenDividerAnchor.destroy();
                mSplitScreenDividerAnchor = null;
            }
        }
    }

    private final class AboveAppWindowContainers extends NonAppWindowContainers {
        AboveAppWindowContainers(String name, WindowManagerService service) {
            super(name, service);
        }

        @Override
        void assignChildLayers(SurfaceControl.Transaction t) {
            assignChildLayers(t, null /* imeContainer */);
        }

        void assignChildLayers(SurfaceControl.Transaction t, WindowContainer imeContainer) {
            boolean needAssignIme = imeContainer != null
                    && imeContainer.getSurfaceControl() != null;
            for (int j = 0; j < mChildren.size(); ++j) {
                final WindowToken wt = mChildren.get(j);

                // See {@link mSplitScreenDividerAnchor}
                if (wt.windowType == TYPE_DOCK_DIVIDER) {
                    wt.assignRelativeLayer(t, mTaskStackContainers.getSplitScreenDividerAnchor(), 1);
                    continue;
                }
                wt.assignLayer(t, j);
                wt.assignChildLayers(t);

                int layer = mService.mPolicy.getWindowLayerFromTypeLw(
                        wt.windowType, wt.mOwnerCanManageAppTokens);

                if (needAssignIme && layer >= mService.mPolicy.getWindowLayerFromTypeLw(
                                TYPE_INPUT_METHOD_DIALOG, true)) {
                    imeContainer.assignRelativeLayer(t, wt.getSurfaceControl(), -1);
                    needAssignIme = false;
                }
            }
            if (needAssignIme) {
                imeContainer.assignRelativeLayer(t, getSurfaceControl(), Integer.MAX_VALUE);
            }
        }
    }

    /**
     * Window container class that contains all containers on this display that are not related to
     * Apps. E.g. status bar.
     */
    private class NonAppWindowContainers extends DisplayChildWindowContainer<WindowToken> {
        /**
         * Compares two child window tokens returns -1 if the first is lesser than the second in
         * terms of z-order and 1 otherwise.
         */
        private final Comparator<WindowToken> mWindowComparator = (token1, token2) ->
                // Tokens with higher base layer are z-ordered on-top.
                mService.mPolicy.getWindowLayerFromTypeLw(token1.windowType,
                        token1.mOwnerCanManageAppTokens)
                < mService.mPolicy.getWindowLayerFromTypeLw(token2.windowType,
                        token2.mOwnerCanManageAppTokens) ? -1 : 1;

        private final Predicate<WindowState> mGetOrientingWindow = w -> {
            if (!w.isVisibleLw() || !w.mPolicyVisibilityAfterAnim) {
                return false;
            }
            final int req = w.mAttrs.screenOrientation;
            if(req == SCREEN_ORIENTATION_UNSPECIFIED || req == SCREEN_ORIENTATION_BEHIND
                    || req == SCREEN_ORIENTATION_UNSET) {
                return false;
            }
            return true;
        };

        private final String mName;
        private final Dimmer mDimmer = new Dimmer(this);
        private final Rect mTmpDimBoundsRect = new Rect();

        NonAppWindowContainers(String name, WindowManagerService service) {
            super(service);
            mName = name;
        }

        void addChild(WindowToken token) {
            addChild(token, mWindowComparator);
        }

        @Override
        int getOrientation() {
            final WindowManagerPolicy policy = mService.mPolicy;
            // Find a window requesting orientation.
            final WindowState win = getWindow(mGetOrientingWindow);

            if (win != null) {
                final int req = win.mAttrs.screenOrientation;
                if (policy.isKeyguardHostWindow(win.mAttrs)) {
                    mLastKeyguardForcedOrientation = req;
                    if (mService.mKeyguardGoingAway) {
                        // Keyguard can't affect the orientation if it is going away...
                        mLastWindowForcedOrientation = SCREEN_ORIENTATION_UNSPECIFIED;
                        return SCREEN_ORIENTATION_UNSET;
                    }
                }
                if (DEBUG_ORIENTATION) Slog.v(TAG_WM, win + " forcing orientation to " + req
                        + " for display id=" + mDisplayId);
                return (mLastWindowForcedOrientation = req);
            }

            mLastWindowForcedOrientation = SCREEN_ORIENTATION_UNSPECIFIED;

            // Only allow force setting the orientation when all unknown visibilities have been
            // resolved, as otherwise we just may be starting another occluding activity.
            final boolean isUnoccluding =
                    mService.mAppTransition.getAppTransition() == TRANSIT_KEYGUARD_UNOCCLUDE
                            && mService.mUnknownAppVisibilityController.allResolved();
            if (policy.isKeyguardShowingAndNotOccluded() || isUnoccluding) {
                return mLastKeyguardForcedOrientation;
            }

            return SCREEN_ORIENTATION_UNSET;
        }

        @Override
        String getName() {
            return mName;
        }

        @Override
        Dimmer getDimmer() {
            return mDimmer;
        }

        @Override
        void prepareSurfaces() {
            mDimmer.resetDimStates();
            super.prepareSurfaces();
            getBounds(mTmpDimBoundsRect);

            if (mDimmer.updateDims(getPendingTransaction(), mTmpDimBoundsRect)) {
                scheduleAnimation();
            }
        }
    }

    private class NonMagnifiableWindowContainers extends NonAppWindowContainers {
        NonMagnifiableWindowContainers(String name, WindowManagerService service) {
            super(name, service);
        }

        @Override
        void applyMagnificationSpec(Transaction t, MagnificationSpec spec) {
        }
    };

    SurfaceControl.Builder makeSurface(SurfaceSession s) {
        return mService.makeSurfaceBuilder(s)
                .setParent(mWindowingLayer);
    }

    @Override
    SurfaceSession getSession() {
        return mSession;
    }

    @Override
    SurfaceControl.Builder makeChildSurface(WindowContainer child) {
        SurfaceSession s = child != null ? child.getSession() : getSession();
        final SurfaceControl.Builder b = mService.makeSurfaceBuilder(s);
        b.setSize(mSurfaceSize, mSurfaceSize);

        if (child == null) {
            return b;
        }

        return b.setName(child.getName())
                .setParent(mWindowingLayer);
    }

    /**
     * The makeSurface variants are for use by the window-container
     * hierarchy. makeOverlay here is a function for various non windowing
     * overlays like the ScreenRotation screenshot, the Strict Mode Flash
     * and other potpourii.
     */
    SurfaceControl.Builder makeOverlay() {
        return mService.makeSurfaceBuilder(mSession)
            .setParent(mOverlayLayer);
    }

    /**
     * Reparents the given surface to mOverlayLayer.
     */
    void reparentToOverlay(Transaction transaction, SurfaceControl surface) {
        transaction.reparent(surface, mOverlayLayer.getHandle());
    }

    void applyMagnificationSpec(MagnificationSpec spec) {
        applyMagnificationSpec(getPendingTransaction(), spec);
        getPendingTransaction().apply();
    }

    @Override
    void onParentSet() {
        // Since we are the top of the SurfaceControl hierarchy here
        // we create the root surfaces explicitly rather than chaining
        // up as the default implementation in onParentSet does. So we
        // explicitly do NOT call super here.
    }

    @Override
    void assignChildLayers(SurfaceControl.Transaction t) {

        // These are layers as children of "mWindowingLayer"
        mBelowAppWindowsContainers.assignLayer(t, 0);
        mTaskStackContainers.assignLayer(t, 1);
        mAboveAppWindowsContainers.assignLayer(t, 2);

        WindowState imeTarget = mService.mInputMethodTarget;
        boolean needAssignIme = true;

        // In the case where we have an IME target that is not in split-screen
        // mode IME assignment is easy. We just need the IME to go directly above
        // the target. This way children of the target will naturally go above the IME
        // and everyone is happy.
        //
        // In the case of split-screen windowing mode, we need to elevate the IME above the
        // docked divider while keeping the app itself below the docked divider, so instead
        // we use relative layering of the IME targets child windows, and place the
        // IME in the non-app layer (see {@link AboveAppWindowContainers#assignChildLayers}).
        //
        // In the case the IME target is animating, the animation Z order may be different
        // than the WindowContainer Z order, so it's difficult to be sure we have the correct
        // IME target. In this case we just layer the IME over all transitions by placing it in the
        // above applications layer.
        //
        // In the case where we have no IME target we assign it where it's base layer would
        // place it in the AboveAppWindowContainers.
        if (imeTarget != null && !(imeTarget.inSplitScreenWindowingMode()
                || imeTarget.mToken.isAppAnimating())
                && (imeTarget.getSurfaceControl() != null)) {
            mImeWindowsContainers.assignRelativeLayer(t, imeTarget.getSurfaceControl(),
                    // TODO: We need to use an extra level on the app surface to ensure
                    // this is always above SurfaceView but always below attached window.
                    1);
            needAssignIme = false;
        }

        // Above we have assigned layers to our children, now we ask them to assign
        // layers to their children.
        mBelowAppWindowsContainers.assignChildLayers(t);
        mTaskStackContainers.assignChildLayers(t);
        mAboveAppWindowsContainers.assignChildLayers(t,
                needAssignIme == true ? mImeWindowsContainers : null);
        mImeWindowsContainers.assignChildLayers(t);
    }

    /**
     * Here we satisfy an unfortunate special case of the IME in split-screen mode. Imagine
     * that the IME target is one of the docked applications. We'd like the docked divider to be
     * above both of the applications, and we'd like the IME to be above the docked divider.
     * However we need child windows of the applications to be above the IME (Text drag handles).
     * This is a non-strictly hierarcical layering and we need to break out of the Z ordering
     * somehow. We do this by relatively ordering children of the target to the IME in cooperation
     * with {@link #WindowState#assignLayer}
     */
    void assignRelativeLayerForImeTargetChild(SurfaceControl.Transaction t, WindowContainer child) {
        t.setRelativeLayer(child.getSurfaceControl(), mImeWindowsContainers.getSurfaceControl(), 1);
    }

    @Override
    void prepareSurfaces() {
        final ScreenRotationAnimation screenRotationAnimation =
                mService.mAnimator.getScreenRotationAnimationLocked(mDisplayId);
        if (screenRotationAnimation != null && screenRotationAnimation.isAnimating()) {
            screenRotationAnimation.getEnterTransformation().getMatrix().getValues(mTmpFloats);
            mPendingTransaction.setMatrix(mWindowingLayer,
                    mTmpFloats[Matrix.MSCALE_X], mTmpFloats[Matrix.MSKEW_Y],
                    mTmpFloats[Matrix.MSKEW_X], mTmpFloats[Matrix.MSCALE_Y]);
            mPendingTransaction.setPosition(mWindowingLayer,
                    mTmpFloats[Matrix.MTRANS_X], mTmpFloats[Matrix.MTRANS_Y]);
            mPendingTransaction.setAlpha(mWindowingLayer,
                    screenRotationAnimation.getEnterTransformation().getAlpha());
        }

        super.prepareSurfaces();
    }

    void assignStackOrdering() {
        mTaskStackContainers.assignStackOrdering(getPendingTransaction());
    }
}
