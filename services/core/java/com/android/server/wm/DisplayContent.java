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

import static android.app.ActivityTaskManager.SPLIT_SCREEN_CREATE_MODE_TOP_OR_LEFT;
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
import static android.view.Display.INVALID_DISPLAY;
import static android.view.InsetsState.TYPE_IME;
import static android.view.Surface.ROTATION_0;
import static android.view.Surface.ROTATION_180;
import static android.view.Surface.ROTATION_270;
import static android.view.Surface.ROTATION_90;
import static android.view.View.GONE;
import static android.view.ViewRootImpl.NEW_INSETS_MODE_NONE;
import static android.view.WindowManager.DOCKED_BOTTOM;
import static android.view.WindowManager.DOCKED_INVALID;
import static android.view.WindowManager.DOCKED_TOP;
import static android.view.WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE;
import static android.view.WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
import static android.view.WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL;
import static android.view.WindowManager.LayoutParams.FLAG_SECURE;
import static android.view.WindowManager.LayoutParams.FLAG_SHOW_WALLPAPER;
import static android.view.WindowManager.LayoutParams.FLAG_SPLIT_TOUCH;
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
import static android.view.WindowManager.TRANSIT_ACTIVITY_OPEN;
import static android.view.WindowManager.TRANSIT_KEYGUARD_UNOCCLUDE;
import static android.view.WindowManager.TRANSIT_TASK_OPEN;
import static android.view.WindowManager.TRANSIT_TASK_TO_FRONT;

import static com.android.server.policy.WindowManagerPolicy.FINISH_LAYOUT_REDO_ANIM;
import static com.android.server.policy.WindowManagerPolicy.FINISH_LAYOUT_REDO_CONFIG;
import static com.android.server.policy.WindowManagerPolicy.FINISH_LAYOUT_REDO_LAYOUT;
import static com.android.server.policy.WindowManagerPolicy.FINISH_LAYOUT_REDO_WALLPAPER;
import static com.android.server.wm.DisplayContentProto.ABOVE_APP_WINDOWS;
import static com.android.server.wm.DisplayContentProto.APP_TRANSITION;
import static com.android.server.wm.DisplayContentProto.BELOW_APP_WINDOWS;
import static com.android.server.wm.DisplayContentProto.DISPLAY_FRAMES;
import static com.android.server.wm.DisplayContentProto.DISPLAY_INFO;
import static com.android.server.wm.DisplayContentProto.DOCKED_STACK_DIVIDER_CONTROLLER;
import static com.android.server.wm.DisplayContentProto.DPI;
import static com.android.server.wm.DisplayContentProto.FOCUSED_APP;
import static com.android.server.wm.DisplayContentProto.ID;
import static com.android.server.wm.DisplayContentProto.IME_WINDOWS;
import static com.android.server.wm.DisplayContentProto.PINNED_STACK_CONTROLLER;
import static com.android.server.wm.DisplayContentProto.ROTATION;
import static com.android.server.wm.DisplayContentProto.SCREEN_ROTATION_ANIMATION;
import static com.android.server.wm.DisplayContentProto.STACKS;
import static com.android.server.wm.DisplayContentProto.WINDOW_CONTAINER;
import static com.android.server.wm.WindowManagerDebugConfig.DEBUG_ADD_REMOVE;
import static com.android.server.wm.WindowManagerDebugConfig.DEBUG_APP_TRANSITIONS;
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
import static com.android.server.wm.WindowManagerDebugConfig.DEBUG_WALLPAPER_LIGHT;
import static com.android.server.wm.WindowManagerDebugConfig.SHOW_STACK_CRAWLS;
import static com.android.server.wm.WindowManagerDebugConfig.SHOW_TRANSACTIONS;
import static com.android.server.wm.WindowManagerDebugConfig.TAG_WITH_CLASS_NAME;
import static com.android.server.wm.WindowManagerDebugConfig.TAG_WM;
import static com.android.server.wm.WindowManagerService.CUSTOM_SCREEN_ROTATION;
import static com.android.server.wm.WindowManagerService.H.REPORT_FOCUS_CHANGE;
import static com.android.server.wm.WindowManagerService.H.REPORT_HARD_KEYBOARD_STATUS_CHANGE;
import static com.android.server.wm.WindowManagerService.H.REPORT_LOSING_FOCUS;
import static com.android.server.wm.WindowManagerService.H.SEND_NEW_CONFIGURATION;
import static com.android.server.wm.WindowManagerService.H.UPDATE_DOCKED_STACK_DIVIDER;
import static com.android.server.wm.WindowManagerService.H.WINDOW_HIDE_TIMEOUT;
import static com.android.server.wm.WindowManagerService.LAYOUT_REPEAT_THRESHOLD;
import static com.android.server.wm.WindowManagerService.MAX_ANIMATION_DURATION;
import static com.android.server.wm.WindowManagerService.SEAMLESS_ROTATION_TIMEOUT_DURATION;
import static com.android.server.wm.WindowManagerService.UPDATE_FOCUS_PLACING_SURFACES;
import static com.android.server.wm.WindowManagerService.UPDATE_FOCUS_REMOVING_FOCUS;
import static com.android.server.wm.WindowManagerService.UPDATE_FOCUS_WILL_ASSIGN_LAYERS;
import static com.android.server.wm.WindowManagerService.UPDATE_FOCUS_WILL_PLACE_SURFACES;
import static com.android.server.wm.WindowManagerService.WINDOWS_FREEZING_SCREENS_ACTIVE;
import static com.android.server.wm.WindowManagerService.WINDOWS_FREEZING_SCREENS_TIMEOUT;
import static com.android.server.wm.WindowManagerService.WINDOW_FREEZE_TIMEOUT_DURATION;
import static com.android.server.wm.WindowManagerService.dipToPixel;
import static com.android.server.wm.WindowManagerService.logSurface;
import static com.android.server.wm.WindowState.RESIZE_HANDLE_WIDTH_IN_DP;
import static com.android.server.wm.WindowStateAnimator.DRAW_PENDING;
import static com.android.server.wm.WindowStateAnimator.READY_TO_SHOW;

import android.animation.AnimationHandler;
import android.annotation.CallSuper;
import android.annotation.IntDef;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.content.pm.PackageManager;
import android.content.res.CompatibilityInfo;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.Insets;
import android.graphics.Matrix;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Region;
import android.graphics.Region.Op;
import android.hardware.display.DisplayManagerInternal;
import android.os.Binder;
import android.os.Debug;
import android.os.Handler;
import android.os.IBinder;
import android.os.Process;
import android.os.RemoteException;
import android.os.SystemClock;
import android.os.Trace;
import android.os.UserHandle;
import android.util.ArraySet;
import android.util.DisplayMetrics;
import android.util.Slog;
import android.util.proto.ProtoOutputStream;
import android.view.Display;
import android.view.DisplayCutout;
import android.view.DisplayInfo;
import android.view.Gravity;
import android.view.InputChannel;
import android.view.InputDevice;
import android.view.InputWindowHandle;
import android.view.InsetsState.InternalInsetType;
import android.view.MagnificationSpec;
import android.view.RemoteAnimationDefinition;
import android.view.Surface;
import android.view.SurfaceControl;
import android.view.SurfaceControl.Transaction;
import android.view.SurfaceSession;
import android.view.View;
import android.view.ViewRootImpl;
import android.view.WindowManager;
import android.view.WindowManagerPolicyConstants.PointerEventListener;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.util.ToBooleanFunction;
import com.android.internal.util.function.TriConsumer;
import com.android.server.AnimationThread;
import com.android.server.policy.WindowManagerPolicy;
import com.android.server.wm.utils.DisplayRotationUtil;
import com.android.server.wm.utils.RotationCache;
import com.android.server.wm.utils.WmDisplayCutout;

import java.io.PrintWriter;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
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
 */
class DisplayContent extends WindowContainer<DisplayContent.DisplayChildWindowContainer>
        implements WindowManagerPolicy.DisplayContentInfo {
    private static final String TAG = TAG_WITH_CLASS_NAME ? "DisplayContent" : TAG_WM;

    /** The default scaling mode that scales content automatically. */
    static final int FORCE_SCALING_MODE_AUTO = 0;
    /** For {@link #setForcedScalingMode} to apply flag {@link Display#FLAG_SCALING_DISABLED}. */
    static final int FORCE_SCALING_MODE_DISABLED = 1;

    @IntDef(prefix = { "FORCE_SCALING_MODE_" }, value = {
            FORCE_SCALING_MODE_AUTO,
            FORCE_SCALING_MODE_DISABLED
    })
    @Retention(RetentionPolicy.SOURCE)
    @interface ForceScalingMode {}

    /** Unique identifier of this stack. */
    private final int mDisplayId;

    // TODO: Remove once unification is complete.
    ActivityDisplay mAcitvityDisplay;

    /** The containers below are the only child containers the display can have. */
    // Contains all window containers that are related to apps (Activities)
    private final TaskStackContainers mTaskStackContainers = new TaskStackContainers(mWmService);
    // Contains all non-app window containers that should be displayed above the app containers
    // (e.g. Status bar)
    private final AboveAppWindowContainers mAboveAppWindowsContainers =
            new AboveAppWindowContainers("mAboveAppWindowsContainers", mWmService);
    // Contains all non-app window containers that should be displayed below the app containers
    // (e.g. Wallpaper).
    private final NonAppWindowContainers mBelowAppWindowsContainers =
            new NonAppWindowContainers("mBelowAppWindowsContainers", mWmService);
    // Contains all IME window containers. Note that the z-ordering of the IME windows will depend
    // on the IME target. We mainly have this container grouping so we can keep track of all the IME
    // window containers together and move them in-sync if/when needed. We use a subclass of
    // WindowContainer which is omitted from screen magnification, as the IME is never magnified.
    private final NonMagnifiableWindowContainers mImeWindowsContainers =
            new NonMagnifiableWindowContainers("mImeWindowsContainers", mWmService);

    private WindowState mTmpWindow;
    private WindowState mTmpWindow2;
    private boolean mTmpRecoveringMemory;
    private boolean mUpdateImeTarget;
    private boolean mTmpInitial;
    private int mMaxUiWidth;

    final AppTransition mAppTransition;
    final AppTransitionController mAppTransitionController;
    boolean mSkipAppTransitionAnimation = false;

    final ArraySet<AppWindowToken> mOpeningApps = new ArraySet<>();
    final ArraySet<AppWindowToken> mClosingApps = new ArraySet<>();
    final ArraySet<AppWindowToken> mChangingApps = new ArraySet<>();
    final UnknownAppVisibilityController mUnknownAppVisibilityController;
    BoundsAnimationController mBoundsAnimationController;

    /**
     * List of clients without a transtiton animation that we notify once we are done
     * transitioning since they won't be notified through the app window animator.
     */
    final List<IBinder> mNoAnimationNotifyOnTransitionFinished = new ArrayList<>();

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

    /**
     * Whether to disable display scaling. This can be set via shell command "adb shell wm scaling".
     * @see WindowManagerService#setForcedDisplayScalingMode(int, int)
     */
    boolean mDisplayScalingDisabled;
    private final DisplayInfo mDisplayInfo = new DisplayInfo();
    private final Display mDisplay;
    private final DisplayMetrics mDisplayMetrics = new DisplayMetrics();
    private final DisplayPolicy mDisplayPolicy;
    private DisplayRotation mDisplayRotation;
    DisplayFrames mDisplayFrames;

    /**
     * For default display it contains real metrics, empty for others.
     * @see WindowManagerService#createWatermarkInTransaction()
     */
    final DisplayMetrics mRealDisplayMetrics = new DisplayMetrics();

    /** @see #computeCompatSmallestWidth(boolean, int, int, int, DisplayCutout) */
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
     * @see #updateOrientationFromAppTokens()
     */
    private int mLastOrientation = SCREEN_ORIENTATION_UNSPECIFIED;

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
    int mDeferredRotationPauseCount;

    /**
     * Used to gate application window layout until we have sent the complete configuration.
     * TODO: There are still scenarios where we may be out of sync with the client. Ideally
     *       we want to replace this flag with a mechanism that will confirm the configuration
     *       applied by the client is the one expected by the system server.
     */
    boolean mWaitingForConfig;

    // TODO(multi-display): remove some of the usages.
    @VisibleForTesting
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

    private final Configuration mTmpConfiguration = new Configuration();

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

    boolean mWallpaperMayChange = false;

    private final SurfaceSession mSession = new SurfaceSession();

    /**
     * Window that is currently interacting with the user. This window is responsible for receiving
     * key events and pointer events from the user.
     */
    WindowState mCurrentFocus = null;

    /**
     * The last focused window that we've notified the client that the focus is changed.
     */
    WindowState mLastFocus = null;

    /**
     * Windows that have lost input focus and are waiting for the new focus window to be displayed
     * before they are told about this.
     */
    ArrayList<WindowState> mLosingFocus = new ArrayList<>();

    /**
     * The foreground app of this display. Windows below this app cannot be the focused window. If
     * the user taps on the area outside of the task of the focused app, we will notify AM about the
     * new task the user wants to interact with.
     */
    AppWindowToken mFocusedApp = null;

    /** Windows added since {@link #mCurrentFocus} was set to null. Used for ANR blaming. */
    final ArrayList<WindowState> mWinAddedSinceNullFocus = new ArrayList<>();

    /** Windows removed since {@link #mCurrentFocus} was set to null. Used for ANR blaming. */
    final ArrayList<WindowState> mWinRemovedSinceNullFocus = new ArrayList<>();

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
     * Sequence number for the current layout pass.
     */
    int mLayoutSeq = 0;

    /**
     * Specifies the count to determine whether to defer updating the IME target until ready.
     */
    private int mDeferUpdateImeTargetCount;

    /** Temporary float array to retrieve 3x3 matrix values. */
    private final float[] mTmpFloats = new float[9];

    private MagnificationSpec mMagnificationSpec;

    private InputMonitor mInputMonitor;

    /** Caches the value whether told display manager that we have content. */
    private boolean mLastHasContent;

    private DisplayRotationUtil mRotationUtil = new DisplayRotationUtil();

    /**
     * The input method window for this display.
     */
    WindowState mInputMethodWindow;

    /**
     * This just indicates the window the input method is on top of, not
     * necessarily the window its input is going to.
     */
    WindowState mInputMethodTarget;

    /** If true hold off on modifying the animation layer of mInputMethodTarget */
    boolean mInputMethodTargetWaitingAnim;

    private final PointerEventDispatcher mPointerEventDispatcher;

    private final InsetsStateController mInsetsStateController;

    private SurfaceControl mParentSurfaceControl;
    private InputWindowHandle mPortalWindowHandle;

    // Last systemUiVisibility we received from status bar.
    private int mLastStatusBarVisibility = 0;
    // Last systemUiVisibility we dispatched to windows.
    private int mLastDispatchedSystemUiVisibility = 0;

    private final Consumer<WindowState> mUpdateWindowsForAnimator = w -> {
        WindowStateAnimator winAnimator = w.mWinAnimator;
        final AppWindowToken atoken = w.mAppToken;
        if (winAnimator.mDrawState == READY_TO_SHOW) {
            if (atoken == null || atoken.canShowWindows()) {
                if (w.performShowLocked()) {
                    pendingLayoutChanges |= FINISH_LAYOUT_REDO_ANIM;
                    if (DEBUG_LAYOUT_REPEATS) {
                        mWmService.mWindowPlacerLocked.debugLayoutRepeats(
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

        // If this window is animating, ensure the animation background is set.
        final AnimationAdapter anim = w.mAppToken != null
                ? w.mAppToken.getAnimation()
                : w.getAnimation();
        if (anim != null) {
            final int color = anim.getBackgroundColor();
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
        final Handler handler = mWmService.mH;
        if (w.mAttrs.type == TYPE_TOAST && w.mOwnerUid == lostFocusUid) {
            if (!handler.hasMessages(WINDOW_HIDE_TIMEOUT, w)) {
                handler.sendMessageDelayed(handler.obtainMessage(WINDOW_HIDE_TIMEOUT, w),
                        w.mAttrs.hideTimeoutMilliseconds);
            }
        }
    };

    private final ToBooleanFunction<WindowState> mFindFocusedWindow = w -> {
        final AppWindowToken focusedApp = mFocusedApp;
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
        final boolean gone = (mTmpWindow != null && mWmService.mPolicy.canBeHiddenByKeyguardLw(w))
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
                    w.resetContentChanged();
                }
                if (w.mAttrs.type == TYPE_DREAM) {
                    // Don't layout windows behind a dream, so that if it does stuff like hide
                    // the status bar we won't get a bad transition when it goes away.
                    mTmpWindow = w;
                }
                w.mLayoutNeeded = false;
                w.prelayout();
                final boolean firstLayout = !w.isLaidOut();
                getDisplayPolicy().layoutWindowLw(w, null, mDisplayFrames);
                w.mLayoutSeq = mLayoutSeq;

                // If this is the first layout, we need to initialize the last inset values as
                // otherwise we'd immediately cause an unnecessary resize.
                if (firstLayout) {
                    w.updateLastInsetValues();
                }

                if (w.mAppToken != null) {
                    w.mAppToken.layoutLetterbox(w);
                }

                if (DEBUG_LAYOUT) Slog.v(TAG, "  LAYOUT: mFrame=" + w.getFrameLw()
                        + " mContainingFrame=" + w.getContainingFrame()
                        + " mDisplayFrame=" + w.getDisplayFrameLw());
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
            if (mTmpWindow != null && mWmService.mPolicy.canBeHiddenByKeyguardLw(w)) {
                return;
            }
            if ((w.mViewVisibility != GONE && w.mRelayoutCalled) || !w.mHaveFrame
                    || w.mLayoutNeeded) {
                if (mTmpInitial) {
                    //Slog.i(TAG, "Window " + this + " clearing mContentChanged - initial");
                    w.resetContentChanged();
                }
                w.mLayoutNeeded = false;
                w.prelayout();
                getDisplayPolicy().layoutWindowLw(w, w.getParentWindow(), mDisplayFrames);
                w.mLayoutSeq = mLayoutSeq;
                if (DEBUG_LAYOUT) Slog.v(TAG, " LAYOUT: mFrame=" + w.getFrameLw()
                        + " mContainingFrame=" + w.getContainingFrame()
                        + " mDisplayFrame=" + w.getDisplayFrameLw());
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
            w -> getDisplayPolicy().applyPostLayoutPolicyLw(w, w.mAttrs, w.getParentWindow(),
                    mInputMethodTarget);

    private final Consumer<WindowState> mApplySurfaceChangesTransaction = w -> {
        final WindowSurfacePlacer surfacePlacer = mWmService.mWindowPlacerLocked;
        final boolean obscuredChanged = w.mObscured !=
                mTmpApplySurfaceChangesTransactionState.obscured;
        final RootWindowContainer root = mWmService.mRoot;

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

        if (obscuredChanged && w.isVisibleLw() && mWallpaperController.isWallpaperTarget(w)) {
            // This is the wallpaper target and its obscured state changed... make sure the
            // current wallpaper's visibility has been updated accordingly.
            mWallpaperController.updateWallpaperVisibility();
        }

        w.handleWindowMovedIfNeeded();

        final WindowStateAnimator winAnimator = w.mWinAnimator;

        //Slog.i(TAG, "Window " + this + " clearing mContentChanged - done placing");
        w.resetContentChanged();

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
                    mWallpaperMayChange = true;
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

        if (!mLosingFocus.isEmpty() && w.isFocused() && w.isDisplayedLw()) {
            mWmService.mH.obtainMessage(REPORT_LOSING_FOCUS, this).sendToTarget();
        }

        w.updateResizingWindowIfNeeded();
    };

    /**
     * Create new {@link DisplayContent} instance, add itself to the root window container and
     * initialize direct children.
     * @param display May not be null.
     * @param service You know.
     * @param activityDisplay The ActivityDisplay for the display container.
     */
    DisplayContent(Display display, WindowManagerService service,
            ActivityDisplay activityDisplay) {
        super(service);
        mAcitvityDisplay = activityDisplay;
        if (service.mRoot.getDisplayContent(display.getDisplayId()) != null) {
            throw new IllegalArgumentException("Display with ID=" + display.getDisplayId()
                    + " already exists=" + service.mRoot.getDisplayContent(display.getDisplayId())
                    + " new=" + display);
        }

        mDisplay = display;
        mDisplayId = display.getDisplayId();
        mWallpaperController = new WallpaperController(mWmService, this);
        display.getDisplayInfo(mDisplayInfo);
        display.getMetrics(mDisplayMetrics);
        isDefaultDisplay = mDisplayId == DEFAULT_DISPLAY;
        mDisplayFrames = new DisplayFrames(mDisplayId, mDisplayInfo,
                calculateDisplayCutoutForRotation(mDisplayInfo.rotation));
        initializeDisplayBaseInfo();

        mAppTransition = new AppTransition(service.mContext, service, this);
        mAppTransition.registerListenerLocked(service.mActivityManagerAppTransitionNotifier);
        mAppTransitionController = new AppTransitionController(service, this);
        mUnknownAppVisibilityController = new UnknownAppVisibilityController(service, this);

        AnimationHandler animationHandler = new AnimationHandler();
        mBoundsAnimationController = new BoundsAnimationController(service.mContext,
                mAppTransition, AnimationThread.getHandler(), animationHandler);

        if (mWmService.mInputManager != null) {
            final InputChannel inputChannel = mWmService.mInputManager.monitorInput("Display "
                    + mDisplayId, mDisplayId);
            mPointerEventDispatcher = inputChannel != null
                    ? new PointerEventDispatcher(inputChannel) : null;
        } else {
            mPointerEventDispatcher = null;
        }
        mDisplayPolicy = new DisplayPolicy(service, this);
        mDisplayRotation = new DisplayRotation(service, this);
        if (isDefaultDisplay) {
            // The policy may be invoked right after here, so it requires the necessary default
            // fields of this display content.
            mWmService.mPolicy.setDefaultDisplay(this);
        }
        if (mWmService.mDisplayReady) {
            mDisplayPolicy.onConfigurationChanged();
        }
        if (mWmService.mSystemReady) {
            mDisplayPolicy.systemReady();
        }
        mDividerControllerLocked = new DockedStackDividerController(service, this);
        mPinnedStackControllerLocked = new PinnedStackController(service, this);

        final SurfaceControl.Builder b = mWmService.makeSurfaceBuilder(mSession).setOpaque(true);
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
        mWmService.mRoot.addChild(this, null);

        // TODO(b/62541591): evaluate whether this is the best spot to declare the
        // {@link DisplayContent} ready for use.
        mDisplayReady = true;

        mWmService.mAnimator.addDisplayLocked(mDisplayId);
        mInputMonitor = new InputMonitor(service, mDisplayId);
        mInsetsStateController = new InsetsStateController(this);
    }

    boolean isReady() {
        // The display is ready when the system and the individual display are both ready.
        return mWmService.mDisplayReady && mDisplayReady;
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
        final DisplayContent dc = mWmService.mRoot.getWindowTokenDisplay(token);
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
        if (prevDc != null) {
            if (prevDc.mTokenMap.remove(token.token) != null && token.asAppWindowToken() == null) {
                // Removed the token from the map, but made sure it's not an app token before
                // removing from parent.
                token.getParent().removeChild(token);
            }
            if (prevDc.mLastFocus == mCurrentFocus) {
                // The window has become the focus of this display, so it should not be notified
                // that it lost focus from the previous display.
                prevDc.mLastFocus = null;
            }
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

    @Override
    public Display getDisplay() {
        return mDisplay;
    }

    DisplayInfo getDisplayInfo() {
        return mDisplayInfo;
    }

    DisplayMetrics getDisplayMetrics() {
        return mDisplayMetrics;
    }

    DisplayPolicy getDisplayPolicy() {
        return mDisplayPolicy;
    }

    @Override
    public DisplayRotation getDisplayRotation() {
        return mDisplayRotation;
    }

    /**
     * Marks a window as providing insets for the rest of the windows in the system.
     *
     * @param type The type of inset this window provides.
     * @param win The window.
     * @param frameProvider Function to compute the frame, or {@code null} if the just the frame of
     *                      the window should be taken.
     */
    void setInsetProvider(@InternalInsetType int type, WindowState win,
            @Nullable TriConsumer<DisplayFrames, WindowState, Rect> frameProvider) {
        mInsetsStateController.getSourceProvider(type).setWindow(win, frameProvider);
    }

    InsetsStateController getInsetsStateController() {
        return mInsetsStateController;
    }

    @VisibleForTesting
    void setDisplayRotation(DisplayRotation displayRotation) {
        mDisplayRotation = displayRotation;
    }

    int getRotation() {
        return mRotation;
    }

    @VisibleForTesting
    void setRotation(int newRotation) {
        mRotation = newRotation;
        mDisplayRotation.setRotation(newRotation);
    }

    int getLastOrientation() {
        return mLastOrientation;
    }

    int getLastWindowForcedOrientation() {
        return mLastWindowForcedOrientation;
    }

    void registerRemoteAnimations(RemoteAnimationDefinition definition) {
        mAppTransitionController.registerRemoteAnimations(definition);
    }

    /**
     * Temporarily pauses rotation changes until resumed.
     *
     * This can be used to prevent rotation changes from occurring while the user is
     * performing certain operations, such as drag and drop.
     *
     * This call nests and must be matched by an equal number of calls to
     * {@link #resumeRotationLocked}.
     */
    void pauseRotationLocked() {
        mDeferredRotationPauseCount++;
    }

    /**
     * Resumes normal rotation changes after being paused.
     */
    void resumeRotationLocked() {
        if (mDeferredRotationPauseCount <= 0) {
            return;
        }

        mDeferredRotationPauseCount--;
        if (mDeferredRotationPauseCount == 0) {
            updateRotationAndSendNewConfigIfNeeded();
        }
    }

    /**
     * If this is true we have updated our desired orientation, but not yet changed the real
     * orientation our applied our screen rotation animation. For example, because a previous
     * screen rotation was in progress.
     *
     * @return {@code true} if the there is an ongoing rotation change.
     */
    boolean rotationNeedsUpdate() {
        final int lastOrientation = getLastOrientation();
        final int oldRotation = getRotation();

        final int rotation = mDisplayRotation.rotationForOrientation(lastOrientation, oldRotation);
        return oldRotation != rotation;
    }

    /**
     * The display content may have configuration set from {@link #DisplayWindowSettings}. This
     * callback let the owner of container know there is existing configuration to prevent the
     * values from being replaced by the initializing {@link #ActivityDisplay}.
     */
    void initializeDisplayOverrideConfiguration() {
        if (mAcitvityDisplay != null) {
            mAcitvityDisplay.onInitializeOverrideConfiguration(getRequestedOverrideConfiguration());
        }
    }

    /** Notify the configuration change of this display. */
    void sendNewConfiguration() {
        mWmService.mH.obtainMessage(SEND_NEW_CONFIGURATION, this).sendToTarget();
    }

    @Override
    boolean onDescendantOrientationChanged(IBinder freezeDisplayToken,
            ConfigurationContainer requestingContainer) {
        final Configuration config = updateOrientationFromAppTokens(
                getRequestedOverrideConfiguration(), freezeDisplayToken, false);
        // If display rotation class tells us that it doesn't consider app requested orientation,
        // this display won't rotate just because of an app changes its requested orientation. Thus
        // it indicates that this display chooses not to handle this request.
        final boolean handled = getDisplayRotation().respectAppRequestedOrientation();
        if (config == null) {
            return handled;
        }

        if (handled && requestingContainer instanceof ActivityRecord) {
            final ActivityRecord activityRecord = (ActivityRecord) requestingContainer;
            final boolean kept = mWmService.mAtmService.updateDisplayOverrideConfigurationLocked(
                    config, activityRecord, false /* deferResume */, getDisplayId());
            activityRecord.frozenBeforeDestroy = true;
            if (!kept) {
                mWmService.mAtmService.mRootActivityContainer.resumeFocusedStacksTopActivities();
            }
        } else {
            // We have a new configuration to push so we need to update ATMS for now.
            // TODO: Clean up display configuration push between ATMS and WMS after unification.
            mWmService.mAtmService.updateDisplayOverrideConfigurationLocked(
                    config, null /* starting */, false /* deferResume */, getDisplayId());
        }
        return handled;
    }

    @Override
    boolean handlesOrientationChangeFromDescendant() {
        return getDisplayRotation().respectAppRequestedOrientation();
    }

    /**
     * Determine the new desired orientation of this display.
     *
     * The orientation is computed from non-application windows first. If none of the
     * non-application windows specify orientation, the orientation is computed from application
     * tokens.
     *
     * @return {@code true} if the orientation is changed.
     */
    boolean updateOrientationFromAppTokens() {
        return updateOrientationFromAppTokens(false /* forceUpdate */);
    }

    /**
     * Update orientation of the target display, returning a non-null new Configuration if it has
     * changed from the current orientation. If a non-null configuration is returned, someone must
     * call {@link WindowManagerService#setNewDisplayOverrideConfiguration(Configuration,
     * DisplayContent)} to tell the window manager it can unfreeze the screen. This will typically
     * be done by calling {@link WindowManagerService#sendNewConfiguration(int)}.
     */
    Configuration updateOrientationFromAppTokens(Configuration currentConfig,
            IBinder freezeDisplayToken, boolean forceUpdate) {
        if (!mDisplayReady) {
            return null;
        }

        Configuration config = null;
        if (updateOrientationFromAppTokens(forceUpdate)) {
            // If we changed the orientation but mOrientationChangeComplete is already true,
            // we used seamless rotation, and we don't need to freeze the screen.
            if (freezeDisplayToken != null && !mWmService.mRoot.mOrientationChangeComplete) {
                final AppWindowToken atoken = getAppWindowToken(freezeDisplayToken);
                if (atoken != null) {
                    atoken.startFreezingScreen();
                }
            }
            config = new Configuration();
            computeScreenConfiguration(config);
        } else if (currentConfig != null) {
            // No obvious action we need to take, but if our current state mismatches the
            // activity manager's, update it, disregarding font scale, which should remain set
            // to the value of the previous configuration.
            // Here we're calling Configuration#unset() instead of setToDefaults() because we
            // need to keep override configs clear of non-empty values (e.g. fontSize).
            mTmpConfiguration.unset();
            mTmpConfiguration.updateFrom(currentConfig);
            computeScreenConfiguration(mTmpConfiguration);
            if (currentConfig.diff(mTmpConfiguration) != 0) {
                mWaitingForConfig = true;
                setLayoutNeeded();
                int[] anim = new int[2];
                getDisplayPolicy().selectRotationAnimationLw(anim);

                mWmService.startFreezingDisplayLocked(anim[0], anim[1], this);
                config = new Configuration(mTmpConfiguration);
            }
        }

        return config;
    }


    private boolean updateOrientationFromAppTokens(boolean forceUpdate) {
        final int req = getOrientation();
        if (req != mLastOrientation || forceUpdate) {
            mLastOrientation = req;
            mDisplayRotation.setCurrentOrientation(req);
            return updateRotationUnchecked(forceUpdate);
        }
        return false;
    }

    /**
     * Update rotation of the display and send configuration if the rotation is changed.
     *
     * @return {@code true} if the rotation has been changed and the new config is sent.
     */
    boolean updateRotationAndSendNewConfigIfNeeded() {
        final boolean changed = updateRotationUnchecked(false /* forceUpdate */);
        if (changed) {
            sendNewConfiguration();
        }
        return changed;
    }

    /**
     * Update rotation of the display.
     *
     * @return {@code true} if the rotation has been changed.  In this case YOU MUST CALL
     *         {@link WindowManagerService#sendNewConfiguration(int)} TO UNFREEZE THE SCREEN.
     */
    boolean updateRotationUnchecked() {
        return updateRotationUnchecked(false /* forceUpdate */);
    }

    /**
     * Update rotation of the DisplayContent with an option to force the update. This updates
     * the container's perception of rotation and, depending on the top activities, will freeze
     * the screen or start seamless rotation. The display itself gets rotated in
     * {@link #applyRotationLocked} during {@link WindowManagerService#sendNewConfiguration}.
     *
     * @param forceUpdate Force the rotation update. Sometimes in WM we might skip updating
     *                    orientation because we're waiting for some rotation to finish or display
     *                    to unfreeze, which results in configuration of the previously visible
     *                    activity being applied to a newly visible one. Forcing the rotation
     *                    update allows to workaround this issue.
     * @return {@code true} if the rotation has been changed.  In this case YOU MUST CALL
     *         {@link WindowManagerService#sendNewConfiguration(int)} TO COMPLETE THE ROTATION AND
     *         UNFREEZE THE SCREEN.
     */
    boolean updateRotationUnchecked(boolean forceUpdate) {
        ScreenRotationAnimation screenRotationAnimation;
        if (!forceUpdate) {
            if (mDeferredRotationPauseCount > 0) {
                // Rotation updates have been paused temporarily.  Defer the update until
                // updates have been resumed.
                if (DEBUG_ORIENTATION) Slog.v(TAG_WM, "Deferring rotation, rotation is paused.");
                return false;
            }

            screenRotationAnimation =
                    mWmService.mAnimator.getScreenRotationAnimationLocked(mDisplayId);
            if (screenRotationAnimation != null && screenRotationAnimation.isAnimating()) {
                // Rotation updates cannot be performed while the previous rotation change
                // animation is still in progress.  Skip this update.  We will try updating
                // again after the animation is finished and the display is unfrozen.
                if (DEBUG_ORIENTATION) Slog.v(TAG_WM, "Deferring rotation, animation in progress.");
                return false;
            }
            if (mWmService.mDisplayFrozen) {
                // Even if the screen rotation animation has finished (e.g. isAnimating
                // returns false), there is still some time where we haven't yet unfrozen
                // the display. We also need to abort rotation here.
                if (DEBUG_ORIENTATION) Slog.v(TAG_WM,
                        "Deferring rotation, still finishing previous rotation");
                return false;
            }
        }

        if (!mWmService.mDisplayEnabled) {
            // No point choosing a rotation if the display is not enabled.
            if (DEBUG_ORIENTATION) Slog.v(TAG_WM, "Deferring rotation, display is not enabled.");
            return false;
        }

        final int oldRotation = mRotation;
        final int lastOrientation = mLastOrientation;
        final int rotation = mDisplayRotation.rotationForOrientation(lastOrientation, oldRotation);
        if (DEBUG_ORIENTATION) Slog.v(TAG_WM, "Computed rotation=" + rotation + " for display id="
                + mDisplayId + " based on lastOrientation=" + lastOrientation
                + " and oldRotation=" + oldRotation);
        boolean mayRotateSeamlessly = mDisplayPolicy.shouldRotateSeamlessly(mDisplayRotation,
                oldRotation, rotation);

        if (mayRotateSeamlessly) {
            final WindowState seamlessRotated = getWindow((w) -> w.mSeamlesslyRotated);
            if (seamlessRotated != null && !forceUpdate) {
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
            for (int i = 0; i < mWmService.mSessions.size(); i++) {
                if (mWmService.mSessions.valueAt(i).hasAlertWindowSurfaces()) {
                    mayRotateSeamlessly = false;
                    break;
                }
            }
        }
        final boolean rotateSeamlessly = mayRotateSeamlessly;

        if (DEBUG_ORIENTATION) Slog.v(TAG_WM, "Display id=" + mDisplayId
                + " selected orientation " + lastOrientation
                + ", got rotation " + rotation);

        if (oldRotation == rotation) {
            // No change.
            return false;
        }

        if (DEBUG_ORIENTATION) Slog.v(TAG_WM, "Display id=" + mDisplayId
                + " rotation changed to " + rotation
                + " from " + oldRotation
                + ", lastOrientation=" + lastOrientation);

        if (DisplayContent.deltaRotation(rotation, oldRotation) != 2) {
            mWaitingForConfig = true;
        }

        mRotation = rotation;

        mWmService.mWindowsFreezingScreen = WINDOWS_FREEZING_SCREENS_ACTIVE;
        mWmService.mH.sendNewMessageDelayed(WindowManagerService.H.WINDOW_FREEZE_TIMEOUT,
                this, WINDOW_FREEZE_TIMEOUT_DURATION);

        setLayoutNeeded();
        final int[] anim = new int[2];
        mDisplayPolicy.selectRotationAnimationLw(anim);

        if (!rotateSeamlessly) {
            mWmService.startFreezingDisplayLocked(anim[0], anim[1], this);
            // startFreezingDisplayLocked can reset the ScreenRotationAnimation.
        } else {
            // The screen rotation animation uses a screenshot to freeze the screen
            // while windows resize underneath.
            // When we are rotating seamlessly, we allow the elements to transition
            // to their rotated state independently and without a freeze required.
            mWmService.startSeamlessRotation();
        }

        return true;
    }

    /**
     * Applies the rotation transaction. This must be called after {@link #updateRotationUnchecked}
     * (if it returned {@code true}) to actually finish the rotation.
     *
     * @param oldRotation the rotation we are coming from.
     * @param rotation the rotation to apply.
     */
    void applyRotationLocked(final int oldRotation, final int rotation) {
        mDisplayRotation.setRotation(rotation);
        final boolean rotateSeamlessly = mWmService.isRotatingSeamlessly();
        ScreenRotationAnimation screenRotationAnimation = rotateSeamlessly
                ? null : mWmService.mAnimator.getScreenRotationAnimationLocked(mDisplayId);
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
                    MAX_ANIMATION_DURATION, mWmService.getTransitionAnimationScaleLocked(),
                    mDisplayInfo.logicalWidth, mDisplayInfo.logicalHeight)) {
                mWmService.scheduleAnimationLocked();
            }
        }

        forAllWindows(w -> {
            w.seamlesslyRotateIfAllowed(getPendingTransaction(), oldRotation, rotation,
                    rotateSeamlessly);
        }, true /* traverseTopToBottom */);

        mWmService.mDisplayManagerInternal.performTraversal(getPendingTransaction());
        scheduleAnimation();

        forAllWindows(w -> {
            if (w.mHasSurface && !rotateSeamlessly) {
                if (DEBUG_ORIENTATION) Slog.v(TAG_WM, "Set mOrientationChanging of " + w);
                w.setOrientationChanging(true);
                mWmService.mRoot.mOrientationChangeComplete = false;
                w.mLastFreezeDuration = 0;
            }
            w.mReportOrientationChanged = true;
        }, true /* traverseTopToBottom */);

        if (rotateSeamlessly) {
            mWmService.mH.sendNewMessageDelayed(WindowManagerService.H.SEAMLESS_ROTATION_TIMEOUT,
                    this, SEAMLESS_ROTATION_TIMEOUT_DURATION);
        }

        for (int i = mWmService.mRotationWatchers.size() - 1; i >= 0; i--) {
            final WindowManagerService.RotationWatcher rotationWatcher
                    = mWmService.mRotationWatchers.get(i);
            if (rotationWatcher.mDisplayId == mDisplayId) {
                try {
                    rotationWatcher.mWatcher.onRotationChanged(rotation);
                } catch (RemoteException e) {
                    // Ignore
                }
            }
        }

        // Announce rotation only if we will not animate as we already have the
        // windows in final state. Otherwise, we make this call at the rotation end.
        if (screenRotationAnimation == null && mWmService.mAccessibilityController != null) {
            mWmService.mAccessibilityController.onRotationChangedLocked(this);
        }
    }

    void configureDisplayPolicy() {
        final int width = mBaseDisplayWidth;
        final int height = mBaseDisplayHeight;
        final int shortSize;
        final int longSize;
        if (width > height) {
            shortSize = height;
            longSize = width;
        } else {
            shortSize = width;
            longSize = height;
        }

        final int shortSizeDp = shortSize * DisplayMetrics.DENSITY_DEFAULT / mBaseDisplayDensity;
        final int longSizeDp = longSize * DisplayMetrics.DENSITY_DEFAULT / mBaseDisplayDensity;

        mDisplayPolicy.configure(width, height, shortSizeDp);
        mDisplayRotation.configure(width, height, shortSizeDp, longSizeDp);

        mDisplayFrames.onDisplayInfoUpdated(mDisplayInfo,
                calculateDisplayCutoutForRotation(mDisplayInfo.rotation));

        // Tap Listeners are supported for:
        // 1. All physical displays (multi-display).
        // 2. VirtualDisplays on VR, AA (and everything else).
        if (mPointerEventDispatcher != null && mTapDetector == null) {
            if (DEBUG_DISPLAY) {
                Slog.d(TAG,
                        "Registering PointerEventListener for DisplayId: " + mDisplayId);
            }
            mTapDetector = new TaskTapPointerEventListener(mWmService, this);
            registerPointerEventListener(mTapDetector);
            registerPointerEventListener(mWmService.mMousePositionTracker);
        }
    }

    /**
     * Update {@link #mDisplayInfo} and other internal variables when display is rotated or config
     * changed.
     * Do not call if {@link WindowManagerService#mDisplayReady} == false.
     */
    private DisplayInfo updateDisplayAndOrientation(int uiMode) {
        // Use the effective "visual" dimensions based on current rotation
        final boolean rotated = (mRotation == ROTATION_90 || mRotation == ROTATION_270);
        final int dw = rotated ? mBaseDisplayHeight : mBaseDisplayWidth;
        final int dh = rotated ? mBaseDisplayWidth : mBaseDisplayHeight;

        // Update application display metrics.
        final WmDisplayCutout wmDisplayCutout = calculateDisplayCutoutForRotation(mRotation);
        final DisplayCutout displayCutout = wmDisplayCutout.getDisplayCutout();

        final int appWidth = mDisplayPolicy.getNonDecorDisplayWidth(dw, dh, mRotation, uiMode,
                displayCutout);
        final int appHeight = mDisplayPolicy.getNonDecorDisplayHeight(dw, dh, mRotation, uiMode,
                displayCutout);
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
        mWmService.mDisplayManagerInternal.setDisplayInfoOverrideFromWindowManager(mDisplayId,
                overrideDisplayInfo);

        mBaseDisplayRect.set(0, 0, dw, dh);

        if (isDefaultDisplay) {
            mCompatibleScreenScale = CompatibilityInfo.computeCompatibleScaling(mDisplayMetrics,
                    mCompatDisplayMetrics);
        }

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
        final Rect[] newBounds = mRotationUtil.getRotatedBounds(
                WmDisplayCutout.computeSafeInsets(
                        cutout, mInitialDisplayWidth, mInitialDisplayHeight)
                        .getDisplayCutout().getBoundingRectsAll(),
                rotation, mInitialDisplayWidth, mInitialDisplayHeight);
        return WmDisplayCutout.computeSafeInsets(DisplayCutout.fromBounds(newBounds),
                rotated ? mInitialDisplayHeight : mInitialDisplayWidth,
                rotated ? mInitialDisplayWidth : mInitialDisplayHeight);
    }

    /**
     * Compute display configuration based on display properties and policy settings.
     * Do not call if mDisplayReady == false.
     */
    void computeScreenConfiguration(Configuration config) {
        final DisplayInfo displayInfo = updateDisplayAndOrientation(config.uiMode);
        calculateBounds(displayInfo, mTmpBounds);
        config.windowConfiguration.setBounds(mTmpBounds);

        final int dw = displayInfo.logicalWidth;
        final int dh = displayInfo.logicalHeight;
        config.orientation = (dw <= dh) ? ORIENTATION_PORTRAIT : ORIENTATION_LANDSCAPE;
        config.windowConfiguration.setWindowingMode(getWindowingMode());
        config.windowConfiguration.setDisplayWindowingMode(getWindowingMode());
        config.windowConfiguration.setRotation(displayInfo.rotation);

        final float density = mDisplayMetrics.density;
        config.screenWidthDp =
                (int)(mDisplayPolicy.getConfigDisplayWidth(dw, dh, displayInfo.rotation,
                        config.uiMode, displayInfo.displayCutout) / density);
        config.screenHeightDp =
                (int)(mDisplayPolicy.getConfigDisplayHeight(dw, dh, displayInfo.rotation,
                        config.uiMode, displayInfo.displayCutout) / density);

        mDisplayPolicy.getNonDecorInsetsLw(displayInfo.rotation, dw, dh,
                displayInfo.displayCutout, mTmpRect);
        final int leftInset = mTmpRect.left;
        final int topInset = mTmpRect.top;
        // appBounds at the root level should mirror the app screen size.
        config.windowConfiguration.setAppBounds(leftInset /* left */, topInset /* top */,
                leftInset + displayInfo.appWidth /* right */,
                topInset + displayInfo.appHeight /* bottom */);
        final boolean rotated = (displayInfo.rotation == Surface.ROTATION_90
                || displayInfo.rotation == Surface.ROTATION_270);

        computeSizeRangesAndScreenLayout(displayInfo, rotated, config.uiMode, dw, dh, density,
                config);

        config.screenLayout = (config.screenLayout & ~Configuration.SCREENLAYOUT_ROUND_MASK)
                | ((displayInfo.flags & Display.FLAG_ROUND) != 0
                ? Configuration.SCREENLAYOUT_ROUND_YES
                : Configuration.SCREENLAYOUT_ROUND_NO);

        config.compatScreenWidthDp = (int)(config.screenWidthDp / mCompatibleScreenScale);
        config.compatScreenHeightDp = (int)(config.screenHeightDp / mCompatibleScreenScale);
        config.compatSmallestScreenWidthDp = computeCompatSmallestWidth(rotated, config.uiMode, dw,
                dh, displayInfo.displayCutout);
        config.densityDpi = displayInfo.logicalDensityDpi;

        config.colorMode =
                ((displayInfo.isHdr() && mWmService.hasHdrSupport())
                        ? Configuration.COLOR_MODE_HDR_YES
                        : Configuration.COLOR_MODE_HDR_NO)
                        | (displayInfo.isWideColorGamut() && mWmService.hasWideColorGamutSupport()
                        ? Configuration.COLOR_MODE_WIDE_COLOR_GAMUT_YES
                        : Configuration.COLOR_MODE_WIDE_COLOR_GAMUT_NO);

        // Update the configuration based on available input devices, lid switch,
        // and platform configuration.
        config.touchscreen = Configuration.TOUCHSCREEN_NOTOUCH;
        config.keyboard = Configuration.KEYBOARD_NOKEYS;
        config.navigation = Configuration.NAVIGATION_NONAV;

        int keyboardPresence = 0;
        int navigationPresence = 0;
        final InputDevice[] devices = mWmService.mInputManager.getInputDevices();
        final int len = devices != null ? devices.length : 0;
        for (int i = 0; i < len; i++) {
            InputDevice device = devices[i];
            // Ignore virtual input device.
            if (device.isVirtual()) {
                continue;
            }

            // Check if input device can dispatch events to current display.
            // If display type is virtual, will follow the default display.
            if (!mWmService.mInputManager.canDispatchToDisplay(device.getId(),
                    displayInfo.type == Display.TYPE_VIRTUAL ? DEFAULT_DISPLAY : mDisplayId)) {
                continue;
            }

            final int sources = device.getSources();
            final int presenceFlag = device.isExternal()
                    ? WindowManagerPolicy.PRESENCE_EXTERNAL : WindowManagerPolicy.PRESENCE_INTERNAL;

            if (mWmService.mIsTouchDevice) {
                if ((sources & InputDevice.SOURCE_TOUCHSCREEN) == InputDevice.SOURCE_TOUCHSCREEN) {
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

        if (config.navigation == Configuration.NAVIGATION_NONAV && mWmService.mHasPermanentDpad) {
            config.navigation = Configuration.NAVIGATION_DPAD;
            navigationPresence |= WindowManagerPolicy.PRESENCE_INTERNAL;
        }

        // Determine whether a hard keyboard is available and enabled.
        // TODO(multi-display): Should the hardware keyboard be tied to a display or to a device?
        boolean hardKeyboardAvailable = config.keyboard != Configuration.KEYBOARD_NOKEYS;
        if (hardKeyboardAvailable != mWmService.mHardKeyboardAvailable) {
            mWmService.mHardKeyboardAvailable = hardKeyboardAvailable;
            mWmService.mH.removeMessages(REPORT_HARD_KEYBOARD_STATUS_CHANGE);
            mWmService.mH.sendEmptyMessage(REPORT_HARD_KEYBOARD_STATUS_CHANGE);
        }

        mDisplayPolicy.updateConfigurationDependentBehaviors();

        // Let the policy update hidden states.
        config.keyboardHidden = Configuration.KEYBOARDHIDDEN_NO;
        config.hardKeyboardHidden = Configuration.HARDKEYBOARDHIDDEN_NO;
        config.navigationHidden = Configuration.NAVIGATIONHIDDEN_NO;
        mWmService.mPolicy.adjustConfigurationLw(config, keyboardPresence, navigationPresence);
    }

    private int computeCompatSmallestWidth(boolean rotated, int uiMode, int dw, int dh,
            DisplayCutout displayCutout) {
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
                displayCutout);
        sw = reduceCompatConfigWidthSize(sw, Surface.ROTATION_90, uiMode, tmpDm, unrotDh, unrotDw,
                displayCutout);
        sw = reduceCompatConfigWidthSize(sw, Surface.ROTATION_180, uiMode, tmpDm, unrotDw, unrotDh,
                displayCutout);
        sw = reduceCompatConfigWidthSize(sw, Surface.ROTATION_270, uiMode, tmpDm, unrotDh, unrotDw,
                displayCutout);
        return sw;
    }

    private int reduceCompatConfigWidthSize(int curSize, int rotation, int uiMode,
            DisplayMetrics dm, int dw, int dh, DisplayCutout displayCutout) {
        dm.noncompatWidthPixels = mDisplayPolicy.getNonDecorDisplayWidth(dw, dh, rotation, uiMode,
                displayCutout);
        dm.noncompatHeightPixels = mDisplayPolicy.getNonDecorDisplayHeight(dw, dh, rotation, uiMode,
                displayCutout);
        float scale = CompatibilityInfo.computeCompatibleScaling(dm, null);
        int size = (int)(((dm.noncompatWidthPixels / scale) / dm.density) + .5f);
        if (curSize == 0 || size < curSize) {
            curSize = size;
        }
        return curSize;
    }

    private void computeSizeRangesAndScreenLayout(DisplayInfo displayInfo, boolean rotated,
            int uiMode, int dw, int dh, float density, Configuration outConfig) {

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
        adjustDisplaySizeRanges(displayInfo, Surface.ROTATION_0, uiMode, unrotDw, unrotDh);
        adjustDisplaySizeRanges(displayInfo, Surface.ROTATION_90, uiMode, unrotDh, unrotDw);
        adjustDisplaySizeRanges(displayInfo, Surface.ROTATION_180, uiMode, unrotDw, unrotDh);
        adjustDisplaySizeRanges(displayInfo, Surface.ROTATION_270, uiMode, unrotDh, unrotDw);
        int sl = Configuration.resetScreenLayout(outConfig.screenLayout);
        sl = reduceConfigLayout(sl, Surface.ROTATION_0, density, unrotDw, unrotDh, uiMode,
                displayInfo.displayCutout);
        sl = reduceConfigLayout(sl, Surface.ROTATION_90, density, unrotDh, unrotDw, uiMode,
                displayInfo.displayCutout);
        sl = reduceConfigLayout(sl, Surface.ROTATION_180, density, unrotDw, unrotDh, uiMode,
                displayInfo.displayCutout);
        sl = reduceConfigLayout(sl, Surface.ROTATION_270, density, unrotDh, unrotDw, uiMode,
                displayInfo.displayCutout);
        outConfig.smallestScreenWidthDp = (int)(displayInfo.smallestNominalAppWidth / density);
        outConfig.screenLayout = sl;
    }

    private int reduceConfigLayout(int curLayout, int rotation, float density, int dw, int dh,
            int uiMode, DisplayCutout displayCutout) {
        // Get the app screen size at this rotation.
        int w = mDisplayPolicy.getNonDecorDisplayWidth(dw, dh, rotation, uiMode, displayCutout);
        int h = mDisplayPolicy.getNonDecorDisplayHeight(dw, dh, rotation, uiMode, displayCutout);

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

    private void adjustDisplaySizeRanges(DisplayInfo displayInfo, int rotation,
            int uiMode, int dw, int dh) {
        final DisplayCutout displayCutout = calculateDisplayCutoutForRotation(
                rotation).getDisplayCutout();
        final int width = mDisplayPolicy.getConfigDisplayWidth(dw, dh, rotation, uiMode,
                displayCutout);
        if (width < displayInfo.smallestNominalAppWidth) {
            displayInfo.smallestNominalAppWidth = width;
        }
        if (width > displayInfo.largestNominalAppWidth) {
            displayInfo.largestNominalAppWidth = width;
        }
        final int height = mDisplayPolicy.getConfigDisplayHeight(dw, dh, rotation, uiMode,
                displayCutout);
        if (height < displayInfo.smallestNominalAppHeight) {
            displayInfo.smallestNominalAppHeight = height;
        }
        if (height > displayInfo.largestNominalAppHeight) {
            displayInfo.largestNominalAppHeight = height;
        }
    }

    /**
     * Apps that use the compact menu panel (as controlled by the panelMenuIsCompact
     * theme attribute) on devices that feature a physical options menu key attempt to position
     * their menu panel window along the edge of the screen nearest the physical menu key.
     * This lowers the travel distance between invoking the menu panel and selecting
     * a menu option.
     *
     * This method helps control where that menu is placed. Its current implementation makes
     * assumptions about the menu key and its relationship to the screen based on whether
     * the device's natural orientation is portrait (width < height) or landscape.
     *
     * The menu key is assumed to be located along the bottom edge of natural-portrait
     * devices and along the right edge of natural-landscape devices. If these assumptions
     * do not hold for the target device, this method should be changed to reflect that.
     *
     * @return A {@link Gravity} value for placing the options menu window.
     */
    int getPreferredOptionsPanelGravity() {
        final int rotation = getRotation();
        if (mInitialDisplayWidth < mInitialDisplayHeight) {
            // On devices with a natural orientation of portrait.
            switch (rotation) {
                default:
                case Surface.ROTATION_0:
                    return Gravity.CENTER_HORIZONTAL | Gravity.BOTTOM;
                case Surface.ROTATION_90:
                    return Gravity.RIGHT | Gravity.BOTTOM;
                case Surface.ROTATION_180:
                    return Gravity.CENTER_HORIZONTAL | Gravity.BOTTOM;
                case Surface.ROTATION_270:
                    return Gravity.START | Gravity.BOTTOM;
            }
        }

        // On devices with a natural orientation of landscape.
        switch (rotation) {
            default:
            case Surface.ROTATION_0:
                return Gravity.RIGHT | Gravity.BOTTOM;
            case Surface.ROTATION_90:
                return Gravity.CENTER_HORIZONTAL | Gravity.BOTTOM;
            case Surface.ROTATION_180:
                return Gravity.START | Gravity.BOTTOM;
            case Surface.ROTATION_270:
                return Gravity.CENTER_HORIZONTAL | Gravity.BOTTOM;
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

    boolean hasSplitScreenPrimaryStack() {
        return getSplitScreenPrimaryStack() != null;
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
    WindowList<TaskStack> getStacks() {
        return mTaskStackContainers.mChildren;
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
        if (mDisplayPolicy != null) {
            mDisplayPolicy.onConfigurationChanged();
        }

        // If there was no pinned stack, we still need to notify the controller of the display info
        // update as a result of the config change.
        if (mPinnedStackControllerLocked != null && !hasPinnedStack()) {
            mPinnedStackControllerLocked.onDisplayInfoChanged(getDisplayInfo());
        }
    }

    /**
     * Updates the resources used by docked/pinned controllers. This needs to be called at the
     * beginning of a configuration update cascade since the metrics from these resources are used
     * for bounds calculations. Since ActivityDisplay initiates the configuration update, this
     * should be called from there instead of DisplayContent's onConfigurationChanged.
     */
    void preOnConfigurationChanged() {
        final DockedStackDividerController dividerController = getDockedDividerController();

        if (dividerController != null) {
            getDockedDividerController().onConfigurationChanged();
        }

        final PinnedStackController pinnedStackController = getPinnedStackController();

        if (pinnedStackController != null) {
            getPinnedStackController().onConfigurationChanged();
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
        mWmService.mWindowsChanged = true;
    }

    @Override
    public void setWindowingMode(int windowingMode) {
        super.setWindowingMode(windowingMode);
        super.setDisplayWindowingMode(windowingMode);
    }

    @Override
    void setDisplayWindowingMode(int windowingMode) {
        setWindowingMode(windowingMode);
    }

    /**
     * In split-screen mode we process the IME containers above the docked divider
     * rather than directly above their target.
     */
    private boolean skipTraverseChild(WindowContainer child) {
        if (child == mImeWindowsContainers && mInputMethodTarget != null
                && !hasSplitScreenPrimaryStack()) {
            return true;
        }
        return false;
    }

    @Override
    boolean forAllWindows(ToBooleanFunction<WindowState> callback, boolean traverseTopToBottom) {
        // Special handling so we can process IME windows with #forAllImeWindows above their IME
        // target, or here in order if there isn't an IME target.
        if (traverseTopToBottom) {
            for (int i = mChildren.size() - 1; i >= 0; --i) {
                final DisplayChildWindowContainer child = mChildren.get(i);
                if (skipTraverseChild(child)) {
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
                if (skipTraverseChild(child)) {
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
        final WindowManagerPolicy policy = mWmService.mPolicy;

        if (mWmService.mDisplayFrozen) {
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

        onDisplayChanged(this);
    }

    void initializeDisplayBaseInfo() {
        final DisplayManagerInternal displayManagerInternal = mWmService.mDisplayManagerInternal;
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
        mWmService.mDisplayManagerInternal.getNonOverrideDisplayInfo(mDisplayId, mDisplayInfo);
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
            mWmService.reconfigureDisplayLocked(this);
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

    /**
     * Forces this display to use the specified density.
     *
     * @param density The density in DPI to use. If the value equals to initial density, the setting
     *                will be cleared.
     * @param userId The target user to apply. Only meaningful when this is default display. If the
     *               user id is {@link UserHandle#USER_CURRENT}, it means to apply current settings
     *               so only need to configure display.
     */
    void setForcedDensity(int density, int userId) {
        final boolean clear = density == mInitialDisplayDensity;
        final boolean updateCurrent = userId == UserHandle.USER_CURRENT;
        if (mWmService.mCurrentUserId == userId || updateCurrent) {
            mBaseDisplayDensity = density;
            mWmService.reconfigureDisplayLocked(this);
        }
        if (updateCurrent) {
            // We are applying existing settings so no need to save it again.
            return;
        }

        if (density == mInitialDisplayDensity) {
            density = 0;
        }
        mWmService.mDisplayWindowSettings.setForcedDensity(this, density, userId);
    }

    /** @param mode {@link #FORCE_SCALING_MODE_AUTO} or {@link #FORCE_SCALING_MODE_DISABLED}. */
    void setForcedScalingMode(@ForceScalingMode int mode) {
        if (mode != FORCE_SCALING_MODE_DISABLED) {
            mode = FORCE_SCALING_MODE_AUTO;
        }

        mDisplayScalingDisabled = (mode != FORCE_SCALING_MODE_AUTO);
        Slog.i(TAG_WM, "Using display scaling mode: " + (mDisplayScalingDisabled ? "off" : "auto"));
        mWmService.reconfigureDisplayLocked(this);

        mWmService.mDisplayWindowSettings.setForcedScalingMode(this, mode);
    }

    /** If the given width and height equal to initial size, the setting will be cleared. */
    void setForcedSize(int width, int height) {
        final boolean clear = mInitialDisplayWidth == width && mInitialDisplayHeight == height;
        if (!clear) {
            // Set some sort of reasonable bounds on the size of the display that we will try
            // to emulate.
            final int minSize = 200;
            final int maxScale = 2;
            width = Math.min(Math.max(width, minSize), mInitialDisplayWidth * maxScale);
            height = Math.min(Math.max(height, minSize), mInitialDisplayHeight * maxScale);
        }

        Slog.i(TAG_WM, "Using new display size: " + width + "x" + height);
        updateBaseDisplayMetrics(width, height, mBaseDisplayDensity);
        mWmService.reconfigureDisplayLocked(this);

        if (clear) {
            width = height = 0;
        }
        mWmService.mDisplayWindowSettings.setForcedSize(this, width, height);
    }

    void getStableRect(Rect out) {
        out.set(mDisplayFrames.mStable);
    }

    void setStackOnDisplay(int stackId, boolean onTop, TaskStack stack) {
        if (DEBUG_STACK) {
            Slog.d(TAG_WM, "Create new stackId=" + stackId + " on displayId=" + mDisplayId);
        }

        mTaskStackContainers.addStackToDisplay(stack, onTop);
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

        // Clean up all pending transitions when stack reparent to another display.
        stack.forAllAppWindows(AppWindowToken::removeFromPendingTransition);

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

    void positionStackAt(int position, TaskStack child, boolean includingParents) {
        mTaskStackContainers.positionChildAt(position, child, includingParents);
        layoutAndAssignWindowLayersIfNeeded();
    }

    /**
     * Used to obtain task ID when user taps on coordinate (x, y) in this display, and outside
     * current task in focus.
     *
     * This returns the task ID of the foremost task at (x, y) if the task is not home. Otherwise it
     * returns -1.
     *
     * @param x horizontal coordinate of the tap position
     * @param y vertical coordinate of the tap position
     * @return the task ID if a non-home task is found; -1 if not
     */
    int taskForTapOutside(int x, int y) {
        for (int stackNdx = mTaskStackContainers.getChildCount() - 1; stackNdx >= 0; --stackNdx) {
            final TaskStack stack = mTaskStackContainers.getChildAt(stackNdx);
            if (stack.isActivityTypeHome() && !stack.inMultiWindowMode()) {
                // We skip not only home stack, but also everything behind home because user can't
                // see them when home stack is isn't in multi-window mode.
                break;
            }
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

    void updateTouchExcludeRegion() {
        final Task focusedTask = (mFocusedApp != null ? mFocusedApp.getTask() : null);
        if (focusedTask == null) {
            mTouchExcludeRegion.setEmpty();
        } else {
            mTouchExcludeRegion.set(mBaseDisplayRect);
            final int delta = dipToPixel(RESIZE_HANDLE_WIDTH_IN_DP, mDisplayMetrics);
            mTmpRect2.setEmpty();
            for (int stackNdx = mTaskStackContainers.getChildCount() - 1; stackNdx >= 0;
                    --stackNdx) {
                final TaskStack stack = mTaskStackContainers.getChildAt(stackNdx);
                stack.setTouchExcludeRegion(focusedTask, delta, mTouchExcludeRegion,
                        mDisplayFrames.mContent, mTmpRect2);
            }
            // If we removed the focused task above, add it back and only leave its
            // outside touch area in the exclusion. TapDetector is not interested in
            // any touch inside the focused task itself.
            if (!mTmpRect2.isEmpty()) {
                mTouchExcludeRegion.op(mTmpRect2, Region.Op.UNION);
            }
        }
        if (mInputMethodWindow != null && mInputMethodWindow.isVisibleLw()) {
            // If the input method is visible and the user is typing, we don't want these touch
            // events to be intercepted and used to change focus. This would likely cause a
            // disappearance of the input method.
            mInputMethodWindow.getTouchableRegion(mTmpRegion);
            mTouchExcludeRegion.op(mTmpRegion, Op.UNION);
        }
        for (int i = mTapExcludedWindows.size() - 1; i >= 0; i--) {
            final WindowState win = mTapExcludedWindows.get(i);
            win.getTouchableRegion(mTmpRegion);
            mTouchExcludeRegion.op(mTmpRegion, Region.Op.UNION);
        }
        amendWindowTapExcludeRegion(mTouchExcludeRegion);
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

    /**
     * Union the region with all the tap exclude region provided by windows on this display.
     *
     * @param inOutRegion The region to be amended.
     */
    void amendWindowTapExcludeRegion(Region inOutRegion) {
        for (int i = mTapExcludeProvidingWindows.size() - 1; i >= 0; i--) {
            final WindowState win = mTapExcludeProvidingWindows.valueAt(i);
            win.amendTapExcludeRegion(inOutRegion);
        }
    }

    @Override
    void switchUser() {
        super.switchUser();
        mWmService.mWindowsChanged = true;
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
            // Clear all transitions & screen frozen states when removing display.
            mOpeningApps.clear();
            mClosingApps.clear();
            mChangingApps.clear();
            mUnknownAppVisibilityController.clear();
            mAppTransition.removeAppTransitionTimeoutCallbacks();
            handleAnimatingStoppedAndTransition();
            mWmService.stopFreezingDisplayLocked();
            super.removeImmediately();
            if (DEBUG_DISPLAY) Slog.v(TAG_WM, "Removing display=" + this);
            if (mPointerEventDispatcher != null && mTapDetector != null) {
                unregisterPointerEventListener(mTapDetector);
                unregisterPointerEventListener(mWmService.mMousePositionTracker);
                mTapDetector = null;
            }
            mWmService.mAnimator.removeDisplayLocked(mDisplayId);
            mWindowingLayer.release();
            mOverlayLayer.release();
            mInputMonitor.onDisplayRemoved();
        } finally {
            mDisplayReady = false;
            mRemovingDisplay = false;
        }

        mDisplayPolicy.onDisplayRemoved();
        mWmService.mWindowPlacerLocked.requestTraversal();
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
        final WindowState imeWin = mInputMethodWindow;
        final boolean imeVisible = imeWin != null && imeWin.isVisibleLw() && imeWin.isDisplayedLw()
                && !mDividerControllerLocked.isImeHideRequested();
        final boolean dockVisible = isStackVisible(WINDOWING_MODE_SPLIT_SCREEN_PRIMARY);
        final TaskStack imeTargetStack = mWmService.getImeFocusStackLocked();
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

    void prepareFreezingTaskBounds() {
        for (int stackNdx = mTaskStackContainers.getChildCount() - 1; stackNdx >= 0; --stackNdx) {
            final TaskStack stack = mTaskStackContainers.getChildAt(stackNdx);
            stack.prepareFreezingTaskBounds();
        }
    }

    void rotateBounds(int oldRotation, int newRotation, Rect bounds) {
        getBounds(mTmpRect, newRotation);
        rotateBounds(mTmpRect, oldRotation, newRotation, bounds);
    }

    void rotateBounds(Rect parentBounds, int oldRotation, int newRotation, Rect bounds) {
        // Compute a transform matrix to undo the coordinate space transformation,
        // and present the window at the same physical position it previously occupied.
        final int deltaRotation = deltaRotation(newRotation, oldRotation);
        createRotationMatrix(
                deltaRotation, parentBounds.width(), parentBounds.height(), mTmpMatrix);

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
    public void writeToProto(ProtoOutputStream proto, long fieldId,
            @WindowTraceLogLevel int logLevel) {
        // Critical log level logs only visible elements to mitigate performance overheard
        if (logLevel == WindowTraceLogLevel.CRITICAL && !isVisible()) {
            return;
        }

        final long token = proto.start(fieldId);
        super.writeToProto(proto, WINDOW_CONTAINER, logLevel);
        proto.write(ID, mDisplayId);
        for (int stackNdx = mTaskStackContainers.getChildCount() - 1; stackNdx >= 0; --stackNdx) {
            final TaskStack stack = mTaskStackContainers.getChildAt(stackNdx);
            stack.writeToProto(proto, STACKS, logLevel);
        }
        mDividerControllerLocked.writeToProto(proto, DOCKED_STACK_DIVIDER_CONTROLLER);
        mPinnedStackControllerLocked.writeToProto(proto, PINNED_STACK_CONTROLLER);
        for (int i = mAboveAppWindowsContainers.getChildCount() - 1; i >= 0; --i) {
            final WindowToken windowToken = mAboveAppWindowsContainers.getChildAt(i);
            windowToken.writeToProto(proto, ABOVE_APP_WINDOWS, logLevel);
        }
        for (int i = mBelowAppWindowsContainers.getChildCount() - 1; i >= 0; --i) {
            final WindowToken windowToken = mBelowAppWindowsContainers.getChildAt(i);
            windowToken.writeToProto(proto, BELOW_APP_WINDOWS, logLevel);
        }
        for (int i = mImeWindowsContainers.getChildCount() - 1; i >= 0; --i) {
            final WindowToken windowToken = mImeWindowsContainers.getChildAt(i);
            windowToken.writeToProto(proto, IME_WINDOWS, logLevel);
        }
        proto.write(DPI, mBaseDisplayDensity);
        mDisplayInfo.writeToProto(proto, DISPLAY_INFO);
        proto.write(ROTATION, mRotation);
        final ScreenRotationAnimation screenRotationAnimation =
                mWmService.mAnimator.getScreenRotationAnimationLocked(mDisplayId);
        if (screenRotationAnimation != null) {
            screenRotationAnimation.writeToProto(proto, SCREEN_ROTATION_ANIMATION);
        }
        mDisplayFrames.writeToProto(proto, DISPLAY_FRAMES);
        mAppTransition.writeToProto(proto, APP_TRANSITION);
        if (mFocusedApp != null) {
            mFocusedApp.writeNameToProto(proto, FOCUSED_APP);
        }
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
        pw.print(prefix);
        pw.print("mDeferredRotationPauseCount="); pw.println(mDeferredRotationPauseCount);

        pw.print("  mCurrentFocus="); pw.println(mCurrentFocus);
        if (mLastFocus != mCurrentFocus) {
            pw.print("  mLastFocus="); pw.println(mLastFocus);
        }
        if (mLosingFocus.size() > 0) {
            pw.println();
            pw.println("  Windows losing focus:");
            for (int i = mLosingFocus.size() - 1; i >= 0; i--) {
                final WindowState w = mLosingFocus.get(i);
                pw.print("  Losing #"); pw.print(i); pw.print(' ');
                pw.print(w);
                if (dumpAll) {
                    pw.println(":");
                    w.dump(pw, "    ", true);
                } else {
                    pw.println();
                }
            }
        }
        pw.print("  mFocusedApp="); pw.println(mFocusedApp);
        if (mLastStatusBarVisibility != 0) {
            pw.print("  mLastStatusBarVisibility=0x");
            pw.println(Integer.toHexString(mLastStatusBarVisibility));
        }

        pw.println();
        mWallpaperController.dump(pw, "  ");

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
        pw.println();
        mDisplayPolicy.dump(prefix, pw);
        pw.println();
        mDisplayRotation.dump(prefix, pw);
        pw.println();
        mInputMonitor.dump(pw, "  ");
        pw.println();
        mInsetsStateController.dump(prefix, pw);
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

    WindowState findFocusedWindowIfNeeded() {
        return (mWmService.mPerDisplayFocusEnabled
                || mWmService.mRoot.mTopFocusedAppByProcess.isEmpty()) ? findFocusedWindow() : null;
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

    /**
     * Update the focused window and make some adjustments if the focus has changed.
     *
     * @param mode Indicates the situation we are in. Possible modes are:
     *             {@link WindowManagerService#UPDATE_FOCUS_NORMAL},
     *             {@link WindowManagerService#UPDATE_FOCUS_PLACING_SURFACES},
     *             {@link WindowManagerService#UPDATE_FOCUS_WILL_PLACE_SURFACES},
     *             {@link WindowManagerService#UPDATE_FOCUS_REMOVING_FOCUS}
     * @param updateInputWindows Whether to sync the window information to the input module.
     * @return {@code true} if the focused window has changed.
     */
    boolean updateFocusedWindowLocked(int mode, boolean updateInputWindows) {
        WindowState newFocus = findFocusedWindowIfNeeded();
        if (mCurrentFocus == newFocus) {
            return false;
        }
        boolean imWindowChanged = false;
        final WindowState imWindow = mInputMethodWindow;
        if (imWindow != null) {
            final WindowState prevTarget = mInputMethodTarget;
            final WindowState newTarget = computeImeTarget(true /* updateImeTarget*/);
            imWindowChanged = prevTarget != newTarget;

            if (mode != UPDATE_FOCUS_WILL_ASSIGN_LAYERS
                    && mode != UPDATE_FOCUS_WILL_PLACE_SURFACES) {
                assignWindowLayers(false /* setLayoutNeeded */);
            }
        }

        if (imWindowChanged) {
            mWmService.mWindowsChanged = true;
            setLayoutNeeded();
            newFocus = findFocusedWindowIfNeeded();
        }
        if (mCurrentFocus != newFocus) {
            mWmService.mH.obtainMessage(REPORT_FOCUS_CHANGE, this).sendToTarget();
        }

        if (DEBUG_FOCUS_LIGHT || mWmService.localLOGV) Slog.v(TAG_WM, "Changing focus from "
                + mCurrentFocus + " to " + newFocus + " displayId=" + getDisplayId()
                + " Callers=" + Debug.getCallers(4));
        final WindowState oldFocus = mCurrentFocus;
        mCurrentFocus = newFocus;
        mLosingFocus.remove(newFocus);

        if (newFocus != null) {
            mWinAddedSinceNullFocus.clear();
            mWinRemovedSinceNullFocus.clear();

            if (newFocus.canReceiveKeys()) {
                // Displaying a window implicitly causes dispatching to be unpaused.
                // This is to protect against bugs if someone pauses dispatching but
                // forgets to resume.
                newFocus.mToken.paused = false;
            }
        }

        int focusChanged = getDisplayPolicy().focusChangedLw(oldFocus, newFocus);

        if (imWindowChanged && oldFocus != mInputMethodWindow) {
            // Focus of the input method window changed. Perform layout if needed.
            if (mode == UPDATE_FOCUS_PLACING_SURFACES) {
                performLayout(true /*initial*/,  updateInputWindows);
                focusChanged &= ~FINISH_LAYOUT_REDO_LAYOUT;
            } else if (mode == UPDATE_FOCUS_WILL_PLACE_SURFACES) {
                // Client will do the layout, but we need to assign layers
                // for handleNewWindowLocked() below.
                assignWindowLayers(false /* setLayoutNeeded */);
            }
        }

        if ((focusChanged & FINISH_LAYOUT_REDO_LAYOUT) != 0) {
            // The change in focus caused us to need to do a layout.  Okay.
            setLayoutNeeded();
            if (mode == UPDATE_FOCUS_PLACING_SURFACES) {
                performLayout(true /*initial*/, updateInputWindows);
            } else if (mode == UPDATE_FOCUS_REMOVING_FOCUS) {
                mWmService.mRoot.performSurfacePlacement(false);
            }
        }

        if (mode != UPDATE_FOCUS_WILL_ASSIGN_LAYERS) {
            // If we defer assigning layers, then the caller is responsible for doing this part.
            getInputMonitor().setInputFocusLw(newFocus, updateInputWindows);
        }

        adjustForImeIfNeeded();

        // We may need to schedule some toast windows to be removed. The toasts for an app that
        // does not have input focus are removed within a timeout to prevent apps to redress
        // other apps' UI.
        scheduleToastWindowsTimeoutIfNeededLocked(oldFocus, newFocus);

        if (mode == UPDATE_FOCUS_PLACING_SURFACES) {
            pendingLayoutChanges |= FINISH_LAYOUT_REDO_ANIM;
        }
        return true;
    }

    /**
     * Set the new focused app to this display.
     *
     * @param newFocus the new focused AppWindowToken.
     * @return true if the focused app is changed.
     */
    boolean setFocusedApp(AppWindowToken newFocus) {
        if (newFocus != null) {
            final DisplayContent appDisplay = newFocus.getDisplayContent();
            if (appDisplay != this) {
                throw new IllegalStateException(newFocus + " is not on " + getName()
                        + " but " + ((appDisplay != null) ? appDisplay.getName() : "none"));
            }
        }
        if (mFocusedApp == newFocus) {
            return false;
        }
        mFocusedApp = newFocus;
        getInputMonitor().setFocusedAppLw(newFocus);
        updateTouchExcludeRegion();
        return true;
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
        mWmService.mWindowsChanged = true;
        setLayoutNeeded();

        if (!mWmService.updateFocusedWindowLocked(UPDATE_FOCUS_WILL_PLACE_SURFACES,
                false /*updateInputWindows*/)) {
            assignWindowLayers(false /* setLayoutNeeded */);
        }

        mInputMonitor.setUpdateInputWindowsNeededLw();
        mWmService.mWindowPlacerLocked.performSurfacePlacement();
        mInputMonitor.updateInputWindowsLw(false /*force*/);
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
            if (!mWmService.mSessions.contains(wsa.mSession)) {
                Slog.w(TAG_WM, "LEAKED SURFACE (session doesn't exist): "
                        + w + " surface=" + wsa.mSurfaceController
                        + " token=" + w.mToken
                        + " pid=" + w.mSession.mPid
                        + " uid=" + w.mSession.mUid);
                wsa.destroySurface();
                mWmService.mForceRemoves.add(w);
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
     * Set input method window for the display.
     * @param win Set when window added or Null when destroyed.
     */
    void setInputMethodWindowLocked(WindowState win) {
        mInputMethodWindow = win;
        // Update display configuration for IME process.
        if (mInputMethodWindow != null) {
            final int imePid = mInputMethodWindow.mSession.mPid;
            mWmService.mAtmInternal.onImeWindowSetOnDisplay(imePid,
                    mInputMethodWindow.getDisplayId());
        }
        computeImeTarget(true /* updateImeTarget */);
        mInsetsStateController.getSourceProvider(TYPE_IME).setWindow(win,
                null /* frameProvider */);
    }

    /**
     * Determine and return the window that should be the IME target.
     * @param updateImeTarget If true the system IME target will be updated to match what we found.
     * @return The window that should be used as the IME target or null if there isn't any.
     */
    WindowState computeImeTarget(boolean updateImeTarget) {
        if (mInputMethodWindow == null) {
            // There isn't an IME so there shouldn't be a target...That was easy!
            if (updateImeTarget) {
                if (DEBUG_INPUT_METHOD) Slog.w(TAG_WM, "Moving IM target from "
                        + mInputMethodTarget + " to null since mInputMethodWindow is null");
                setInputMethodTarget(null, mInputMethodTargetWaitingAnim);
            }
            return null;
        }

        final WindowState curTarget = mInputMethodTarget;
        if (!canUpdateImeTarget()) {
            if (DEBUG_INPUT_METHOD) Slog.w(TAG_WM, "Defer updating IME target");
            return curTarget;
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
                "Proposed new IME target: " + target + " for display: " + getDisplayId());

        // Now, a special case -- if the last target's window is in the process of exiting, but
        // not removed, and the new target is home, keep on the last target to avoid flicker.
        // Home is a special case since its above other stacks in the ordering list, but layed
        // out below the others.
        if (curTarget != null && !curTarget.mRemoved && curTarget.isDisplayedLw()
                && curTarget.isClosing() && (target == null || target.isActivityTypeHome())) {
            if (DEBUG_INPUT_METHOD) Slog.v(TAG_WM, "New target is home while current target is "
                    + "closing, not changing");
            return curTarget;
        }

        if (DEBUG_INPUT_METHOD) Slog.v(TAG_WM, "Desired input method target=" + target
                + " updateImeTarget=" + updateImeTarget);

        if (target == null) {
            if (updateImeTarget) {
                if (DEBUG_INPUT_METHOD) Slog.w(TAG_WM, "Moving IM target from " + curTarget
                        + " to null." + (SHOW_STACK_CRAWLS ? " Callers="
                        + Debug.getCallers(4) : ""));
                setInputMethodTarget(null, mInputMethodTargetWaitingAnim);
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
                    if (DEBUG_INPUT_METHOD) Slog.v(TAG_WM, mAppTransition + " " + highestTarget
                            + " animating=" + highestTarget.isAnimating());

                    if (mAppTransition.isTransitionSet()) {
                        // If we are currently setting up for an animation, hold everything until we
                        // can find out what will happen.
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

    /**
     * Calling {@link #computeImeTarget(boolean)} to update the input method target window in
     * the candidate app window token if needed.
     */
    void computeImeTargetIfNeeded(AppWindowToken candidate) {
        if (mInputMethodTarget != null && mInputMethodTarget.mAppToken == candidate) {
            computeImeTarget(true /* updateImeTarget */);
        }
    }

    private void setInputMethodTarget(WindowState target, boolean targetWaitingAnim) {
        if (target == mInputMethodTarget && mInputMethodTargetWaitingAnim == targetWaitingAnim) {
            return;
        }

        mInputMethodTarget = target;
        mInputMethodTargetWaitingAnim = targetWaitingAnim;
        assignWindowLayers(false /* setLayoutNeeded */);
        mInsetsStateController.onImeTargetChanged(target);
        updateImeParent();
    }

    private void updateImeParent() {
        if (ViewRootImpl.sNewInsetsMode == NEW_INSETS_MODE_NONE) {
            return;
        }
        final SurfaceControl newParent = computeImeParent();
        if (newParent != null) {
            mPendingTransaction.reparent(mImeWindowsContainers.mSurfaceControl, newParent);
            scheduleAnimation();
        }
    }

    /**
     * Computes the window the IME should be attached to.
     */
    @VisibleForTesting
    SurfaceControl computeImeParent() {

        // Attach it to app if the target is part of an app and such app is covering the entire
        // screen. If it's not covering the entire screen the IME might extend beyond the apps
        // bounds.
        if (mInputMethodTarget != null && mInputMethodTarget.mAppToken != null &&
                mInputMethodTarget.getWindowingMode() == WINDOWING_MODE_FULLSCREEN) {
            return mInputMethodTarget.mAppToken.getSurfaceControl();
        }

        // Otherwise, we just attach it to the display.
        return mWindowingLayer;
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

        if (!mOpeningApps.isEmpty() || !mClosingApps.isEmpty() || !mChangingApps.isEmpty()) {
            pw.println();
            if (mOpeningApps.size() > 0) {
                pw.print("  mOpeningApps="); pw.println(mOpeningApps);
            }
            if (mClosingApps.size() > 0) {
                pw.print("  mClosingApps="); pw.println(mClosingApps);
            }
            if (mChangingApps.size() > 0) {
                pw.print("  mChangingApps="); pw.println(mChangingApps);
            }
        }

        mUnknownAppVisibilityController.dump(pw, "  ");
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
        final WindowManagerPolicy policy = mWmService.mPolicy;
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
                    mHaveKeyguard = mWmService.mPolicy.isKeyguardDrawnLw();
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
        boolean wallpaperEnabled = mWmService.mContext.getResources().getBoolean(
                com.android.internal.R.bool.config_enableWallpaperService)
                && mWmService.mContext.getResources().getBoolean(
                        com.android.internal.R.bool.config_checkWallpaperAtBoot)
                && !mWmService.mOnlyCore;

        if (DEBUG_SCREEN_ON || DEBUG_BOOT) Slog.i(TAG_WM,
                "******** booted=" + mWmService.mSystemBooted
                + " msg=" + mWmService.mShowingBootMessages
                + " haveBoot=" + mHaveBootMsg + " haveApp=" + mHaveApp
                + " haveWall=" + mHaveWallpaper + " wallEnabled=" + wallpaperEnabled
                + " haveKeyguard=" + mHaveKeyguard);

        // If we are turning on the screen to show the boot message, don't do it until the boot
        // message is actually displayed.
        if (!mWmService.mSystemBooted && !mHaveBootMsg) {
            return true;
        }

        // If we are turning on the screen after the boot is completed normally, don't do so until
        // we have the application and wallpaper.
        if (mWmService.mSystemBooted
                && ((!mHaveApp && !mHaveKeyguard) || (wallpaperEnabled && !mHaveWallpaper))) {
            return true;
        }

        return false;
    }

    void updateWindowsForAnimator() {
        forAllWindows(mUpdateWindowsForAnimator, true /* traverseTopToBottom */);
    }

    /**
     * Updates the {@link TaskStack#setAnimationBackground} for all windows.
     */
    void updateBackgroundForAnimator() {
        resetAnimationBackgroundAnimator();
        forAllWindows(mUpdateWallpaperForAnimator, true /* traverseTopToBottom */);
    }

    boolean isInputMethodClientFocus(int uid, int pid) {
        final WindowState imFocus = computeImeTarget(false /* updateImeTarget */);
        if (imFocus == null) {
            return false;
        }

        if (DEBUG_INPUT_METHOD) {
            Slog.i(TAG_WM, "Desired input method target: " + imFocus);
            Slog.i(TAG_WM, "Current focus: " + mCurrentFocus + " displayId=" + mDisplayId);
            Slog.i(TAG_WM, "Last focus: " + mLastFocus + " displayId=" + mDisplayId);
        }

        if (DEBUG_INPUT_METHOD) {
            Slog.i(TAG_WM, "IM target uid/pid: " + imFocus.mSession.mUid
                    + "/" + imFocus.mSession.mPid);
            Slog.i(TAG_WM, "Requesting client uid/pid: " + uid + "/" + pid);
        }

        return imFocus.mSession.mUid == uid && imFocus.mSession.mPid == pid;
    }

    boolean hasSecureWindowOnScreen() {
        final WindowState win = getWindow(
                w -> w.isOnScreen() && (w.mAttrs.flags & FLAG_SECURE) != 0);
        return win != null;
    }

    void statusBarVisibilityChanged(int visibility) {
        mLastStatusBarVisibility = visibility;
        visibility = getDisplayPolicy().adjustSystemUiVisibilityLw(visibility);
        updateStatusBarVisibilityLocked(visibility);
    }

    private boolean updateStatusBarVisibilityLocked(int visibility) {
        if (mLastDispatchedSystemUiVisibility == visibility) {
            return false;
        }
        final int globalDiff = (visibility ^ mLastDispatchedSystemUiVisibility)
                // We are only interested in differences of one of the
                // clearable flags...
                & View.SYSTEM_UI_CLEARABLE_FLAGS
                // ...if it has actually been cleared.
                & ~visibility;

        mLastDispatchedSystemUiVisibility = visibility;
        if (isDefaultDisplay) {
            mWmService.mInputManager.setSystemUiVisibility(visibility);
        }
        updateSystemUiVisibility(visibility, globalDiff);
        return true;
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

    void reevaluateStatusBarVisibility() {
        int visibility = getDisplayPolicy().adjustSystemUiVisibilityLw(mLastStatusBarVisibility);
        if (updateStatusBarVisibilityLocked(visibility)) {
            mWmService.mWindowPlacerLocked.requestTraversal();
        }
    }

    void onWindowFreezeTimeout() {
        Slog.w(TAG_WM, "Window freeze timeout expired.");
        mWmService.mWindowsFreezingScreen = WINDOWS_FREEZING_SCREENS_TIMEOUT;

        forAllWindows(w -> {
            if (!w.getOrientationChanging()) {
                return;
            }
            w.orientationChangeTimedOut();
            w.mLastFreezeDuration = (int)(SystemClock.elapsedRealtime()
                    - mWmService.mDisplayFreezeTime);
            Slog.w(TAG_WM, "Force clearing orientation change: " + w);
        }, true /* traverseTopToBottom */);
        mWmService.mWindowPlacerLocked.performSurfacePlacement();
    }

    void waitForAllWindowsDrawn() {
        final WindowManagerPolicy policy = mWmService.mPolicy;
        forAllWindows(w -> {
            final boolean keyguard = policy.isKeyguardHostWindow(w.mAttrs);
            if (w.isVisibleLw() && (w.mAppToken != null || keyguard)) {
                w.mWinAnimator.mDrawState = DRAW_PENDING;
                // Force add to mResizingWindows.
                w.resetLastContentInsets();
                mWmService.mWaitingForDrawn.add(w);
            }
        }, true /* traverseTopToBottom */);
    }

    // TODO: Super crazy long method that should be broken down...
    void applySurfaceChangesTransaction(boolean recoveringMemory) {
        final WindowSurfacePlacer surfacePlacer = mWmService.mWindowPlacerLocked;

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

            if ((pendingLayoutChanges & FINISH_LAYOUT_REDO_WALLPAPER) != 0) {
                mWallpaperController.adjustWallpaperWindows();
            }

            if ((pendingLayoutChanges & FINISH_LAYOUT_REDO_CONFIG) != 0) {
                if (DEBUG_LAYOUT) Slog.v(TAG, "Computing new config from layout");
                if (updateOrientationFromAppTokens()) {
                    setLayoutNeeded();
                    sendNewConfiguration();
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

            mDisplayPolicy.beginPostLayoutPolicyLw();
            forAllWindows(mApplyPostLayoutPolicy, true /* traverseTopToBottom */);
            pendingLayoutChanges |= mDisplayPolicy.finishPostLayoutPolicyLw();
            if (DEBUG_LAYOUT_REPEATS) surfacePlacer.debugLayoutRepeats(
                    "after finishPostLayoutPolicyLw", pendingLayoutChanges);
                mInsetsStateController.onPostLayout();
        } while (pendingLayoutChanges != 0);

        mTmpApplySurfaceChangesTransactionState.reset();

        mTmpRecoveringMemory = recoveringMemory;
        forAllWindows(mApplySurfaceChangesTransaction, true /* traverseTopToBottom */);
        prepareSurfaces();

        mLastHasContent = mTmpApplySurfaceChangesTransactionState.displayHasContent;
        mWmService.mDisplayManagerInternal.setDisplayProperties(mDisplayId,
                mLastHasContent,
                mTmpApplySurfaceChangesTransactionState.preferredRefreshRate,
                mTmpApplySurfaceChangesTransactionState.preferredModeId,
                true /* inTraversal, must call performTraversalInTrans... below */);

        final boolean wallpaperVisible = mWallpaperController.isWallpaperVisible();
        if (wallpaperVisible != mLastWallpaperVisible) {
            mLastWallpaperVisible = wallpaperVisible;
            mWmService.mWallpaperVisibilityListeners.notifyWallpaperVisibilityChanged(this);
        }

        while (!mTmpUpdateAllDrawn.isEmpty()) {
            final AppWindowToken atoken = mTmpUpdateAllDrawn.removeLast();
            // See if any windows have been drawn, so they (and others associated with them)
            // can now be shown.
            atoken.updateAllDrawn();
        }
    }

    private void updateBounds() {
        calculateBounds(mDisplayInfo, mTmpBounds);
        setBounds(mTmpBounds);
        if (mPortalWindowHandle != null && mParentSurfaceControl != null) {
            mPortalWindowHandle.touchableRegion.getBounds(mTmpRect);
            if (!mTmpBounds.equals(mTmpRect)) {
                mPortalWindowHandle.touchableRegion.set(mTmpBounds);
                mPendingTransaction.setInputWindowInfo(mParentSurfaceControl, mPortalWindowHandle);
            }
        }
    }

    // Determines the current display bounds based on the current state
    private void calculateBounds(DisplayInfo displayInfo, Rect out) {
        // Uses same calculation as in LogicalDisplay#configureDisplayInTransactionLocked.
        final int rotation = displayInfo.rotation;
        boolean rotated = (rotation == ROTATION_90 || rotation == ROTATION_270);
        final int physWidth = rotated ? mBaseDisplayHeight : mBaseDisplayWidth;
        final int physHeight = rotated ? mBaseDisplayWidth : mBaseDisplayHeight;
        int width = displayInfo.logicalWidth;
        int left = (physWidth - width) / 2;
        int height = displayInfo.logicalHeight;
        int top = (physHeight - height) / 2;
        out.set(left, top, left + width, top + height);
    }

    @Override
    public void getBounds(Rect out) {
        calculateBounds(mDisplayInfo, out);
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

    /** @returns the orientation of the display when it's rotation is ROTATION_0. */
    int getNaturalOrientation() {
        return mBaseDisplayWidth < mBaseDisplayHeight
                ? ORIENTATION_PORTRAIT : ORIENTATION_LANDSCAPE;
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
        mDisplayPolicy.beginLayoutLw(mDisplayFrames, getConfiguration().uiMode);

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
        mInputMonitor.layoutInputConsumers(dw, dh);
        mInputMonitor.setUpdateInputWindowsNeededLw();
        if (updateInputWindows) {
            mInputMonitor.updateInputWindowsLw(false /*force*/);
        }

        mWmService.mH.sendEmptyMessage(UPDATE_DOCKED_STACK_DIVIDER);
    }

    /**
     * Takes a snapshot of the display.  In landscape mode this grabs the whole screen.
     * In portrait mode, it grabs the full screenshot.
     *
     * @param config of the output bitmap
     */
    Bitmap screenshotDisplayLocked(Bitmap.Config config) {
        if (!mWmService.mPolicy.isScreenOn()) {
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
                mWmService.mAnimator.getScreenRotationAnimationLocked(DEFAULT_DISPLAY);
        final boolean inRotation = screenRotationAnimation != null &&
                screenRotationAnimation.isAnimating();
        if (DEBUG_SCREENSHOT && inRotation) Slog.v(TAG_WM, "Taking screenshot while rotating");

        // TODO(b/68392460): We should screenshot Task controls directly
        // but it's difficult at the moment as the Task doesn't have the
        // correct size set.
        final Bitmap bitmap = SurfaceControl.screenshot(frame, dw, dh, inRotation, rot);
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
            w.finishSeamlessRotation(true /* timeout */);
            mWmService.markForSeamlessRotation(w, false);
        }, true /* traverseTopToBottom */);

        if (mTmpWindow != null) {
            mWmService.mWindowPlacerLocked.performSurfacePlacement();
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
        mWmService.requestTraversal();
    }

    boolean okToDisplay() {
        if (mDisplayId == DEFAULT_DISPLAY) {
            return !mWmService.mDisplayFrozen
                    && mWmService.mDisplayEnabled && mWmService.mPolicy.isScreenOn();
        }
        return mDisplayInfo.state == Display.STATE_ON;
    }

    boolean okToAnimate() {
        return okToDisplay() &&
                (mDisplayId != DEFAULT_DISPLAY || mWmService.mPolicy.okToAnimate());
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
        float preferredRefreshRate;
        int preferredModeId;

        void reset() {
            displayHasContent = false;
            obscured = false;
            syswin = false;
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
                mWmService.setDockedStackCreateStateLocked(
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

            if (includingParents) {
                // We still want to move the display of this stack container to top because even the
                // target position is adjusted to non-top, the intention of the condition is to have
                // higher z-order to gain focus (e.g. moving a task of a fullscreen stack to front
                // in a non-top display which is using picture-in-picture mode).
                final int topChildPosition = getChildCount() - 1;
                if (targetPosition < topChildPosition && position >= topChildPosition) {
                    getParent().positionChildAt(POSITION_TOP, this /* child */,
                            true /* includingParents */);
                }
            }

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
            if (stack.inPinnedWindowingMode()) {
                return POSITION_TOP;
            }

            final int topChildPosition = mChildren.size() - 1;
            int belowAlwaysOnTopPosition = POSITION_BOTTOM;
            for (int i = topChildPosition; i >= 0; --i) {
                if (getStacks().get(i) != stack && !getStacks().get(i).isAlwaysOnTop()) {
                    belowAlwaysOnTopPosition = i;
                    break;
                }
            }

            // The max possible position we can insert the stack at.
            int maxPosition = POSITION_TOP;
            // The min possible position we can insert the stack at.
            int minPosition = POSITION_BOTTOM;

            if (stack.isAlwaysOnTop()) {
                if (hasPinnedStack()) {
                    // Always-on-top stacks go below the pinned stack.
                    maxPosition = getStacks().indexOf(mPinnedStack) - 1;
                }
                // Always-on-top stacks need to be above all other stacks.
                minPosition = belowAlwaysOnTopPosition !=
                        POSITION_BOTTOM ? belowAlwaysOnTopPosition : topChildPosition;
            } else {
                // Other stacks need to be below the always-on-top stacks.
                maxPosition = belowAlwaysOnTopPosition !=
                        POSITION_BOTTOM ? belowAlwaysOnTopPosition : 0;
            }

            int targetPosition = requestedPosition;
            targetPosition = Math.min(targetPosition, maxPosition);
            targetPosition = Math.max(targetPosition, minPosition);

            int prevPosition = getStacks().indexOf(stack);
            // The positions we calculated above (maxPosition, minPosition) do not take into
            // consideration the following edge cases.
            // 1) We need to adjust the position depending on the value "adding".
            // 2) When we are moving a stack to another position, we also need to adjust the
            //    position depending on whether the stack is moving to a higher or lower position.
            if ((targetPosition != requestedPosition) &&
                    (adding || targetPosition < prevPosition)) {
                targetPosition++;
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
                    if (!token.hasVisible && !mClosingApps.contains(token)
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
                // docked or freeform stack is visible...except for the home stack if the docked
                // stack is minimized and it actually set something and the bounds is different from
                // the display.
                if (mHomeStack != null && mHomeStack.isVisible()
                        && mDividerControllerLocked.isMinimizedDock()
                        && !(mDividerControllerLocked.isHomeStackResizable()
                            && mHomeStack.matchParentBounds())) {
                    final int orientation = mHomeStack.getOrientation();
                    if (orientation != SCREEN_ORIENTATION_UNSET) {
                        return orientation;
                    }
                }
                return SCREEN_ORIENTATION_UNSPECIFIED;
            }

            final int orientation = super.getOrientation();
            boolean isCar = mWmService.mContext.getPackageManager().hasSystemFeature(
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
        void onParentChanged() {
            super.onParentChanged();
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
                mAppAnimationLayer.remove();
                mAppAnimationLayer = null;
                mBoostedAppAnimationLayer.remove();
                mBoostedAppAnimationLayer = null;
                mHomeAppAnimationLayer.remove();
                mHomeAppAnimationLayer = null;
                mSplitScreenDividerAnchor.remove();
                mSplitScreenDividerAnchor = null;
            }
        }
    }

    private final class AboveAppWindowContainers extends NonAppWindowContainers {
        AboveAppWindowContainers(String name, WindowManagerService service) {
            super(name, service);
        }

        @Override
        SurfaceControl.Builder makeChildSurface(WindowContainer child) {
            final SurfaceControl.Builder builder = super.makeChildSurface(child);
            if (child instanceof WindowToken && ((WindowToken) child).mRoundedCornerOverlay) {
                // To draw above the ColorFade layer during the screen off transition, the
                // rounded corner overlays need to be at the root of the surface hierarchy.
                // TODO: move the ColorLayer into the display overlay layer such that this is not
                // necessary anymore.
                builder.setParent(null);
            }
            return builder;
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
                if (wt.mRoundedCornerOverlay) {
                    wt.assignLayer(t, WindowManagerPolicy.COLOR_FADE_LAYER + 1);
                    continue;
                }
                wt.assignLayer(t, j);
                wt.assignChildLayers(t);

                int layer = mWmService.mPolicy.getWindowLayerFromTypeLw(
                        wt.windowType, wt.mOwnerCanManageAppTokens);

                if (needAssignIme && layer >= mWmService.mPolicy.getWindowLayerFromTypeLw(
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
                mWmService.mPolicy.getWindowLayerFromTypeLw(token1.windowType,
                        token1.mOwnerCanManageAppTokens)
                < mWmService.mPolicy.getWindowLayerFromTypeLw(token2.windowType,
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
            final WindowManagerPolicy policy = mWmService.mPolicy;
            // Find a window requesting orientation.
            final WindowState win = getWindow(mGetOrientingWindow);

            if (win != null) {
                final int req = win.mAttrs.screenOrientation;
                if (policy.isKeyguardHostWindow(win.mAttrs)) {
                    mLastKeyguardForcedOrientation = req;
                    if (mWmService.mKeyguardGoingAway) {
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
                    mAppTransition.getAppTransition() == TRANSIT_KEYGUARD_UNOCCLUDE
                            && mUnknownAppVisibilityController.allResolved();
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
        return mWmService.makeSurfaceBuilder(s)
                .setParent(mWindowingLayer);
    }

    @Override
    SurfaceSession getSession() {
        return mSession;
    }

    @Override
    SurfaceControl.Builder makeChildSurface(WindowContainer child) {
        SurfaceSession s = child != null ? child.getSession() : getSession();
        final SurfaceControl.Builder b = mWmService.makeSurfaceBuilder(s);
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
        return mWmService.makeSurfaceBuilder(mSession)
            .setParent(mOverlayLayer);
    }

    /**
     * Reparents the given surface to mOverlayLayer.
     */
    void reparentToOverlay(Transaction transaction, SurfaceControl surface) {
        transaction.reparent(surface, mOverlayLayer);
    }

    void applyMagnificationSpec(MagnificationSpec spec) {
        if (spec.scale != 1.0) {
            mMagnificationSpec = spec;
        } else {
            mMagnificationSpec = null;
        }

        applyMagnificationSpec(getPendingTransaction(), spec);
        getPendingTransaction().apply();
    }

    void reapplyMagnificationSpec() {
        if (mMagnificationSpec != null) {
            applyMagnificationSpec(getPendingTransaction(), mMagnificationSpec);
        }
    }

    @Override
    void onParentChanged() {
        // Since we are the top of the SurfaceControl hierarchy here
        // we create the root surfaces explicitly rather than chaining
        // up as the default implementation in onParentChanged does. So we
        // explicitly do NOT call super here.
    }

    @Override
    void assignChildLayers(SurfaceControl.Transaction t) {

        // These are layers as children of "mWindowingLayer"
        mBelowAppWindowsContainers.assignLayer(t, 0);
        mTaskStackContainers.assignLayer(t, 1);
        mAboveAppWindowsContainers.assignLayer(t, 2);

        final WindowState imeTarget = mInputMethodTarget;
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
     * with {@link WindowState#assignLayer}
     */
    void assignRelativeLayerForImeTargetChild(SurfaceControl.Transaction t, WindowContainer child) {
        child.assignRelativeLayer(t, mImeWindowsContainers.getSurfaceControl(), 1);
    }

    @Override
    void prepareSurfaces() {
        final ScreenRotationAnimation screenRotationAnimation =
                mWmService.mAnimator.getScreenRotationAnimationLocked(mDisplayId);
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

    /**
     * Increment the deferral count to determine whether to update the IME target.
     */
    void deferUpdateImeTarget() {
        mDeferUpdateImeTargetCount++;
    }

    /**
     * Decrement the deferral count to determine whether to update the IME target. If the count
     * reaches 0, a new ime target will get computed.
     */
    void continueUpdateImeTarget() {
        if (mDeferUpdateImeTargetCount == 0) {
            return;
        }

        mDeferUpdateImeTargetCount--;
        if (mDeferUpdateImeTargetCount == 0) {
            computeImeTarget(true /* updateImeTarget */);
        }
    }

    /**
     * @return Whether a new IME target should be computed.
     */
    private boolean canUpdateImeTarget() {
        return mDeferUpdateImeTargetCount == 0;
    }

    InputMonitor getInputMonitor() {
        return mInputMonitor;
    }

    /**
     * @return Cached value whether we told display manager that we have content.
     */
    boolean getLastHasContent() {
        return mLastHasContent;
    }

    void registerPointerEventListener(@NonNull PointerEventListener listener) {
        if (mPointerEventDispatcher != null) {
            mPointerEventDispatcher.registerInputEventListener(listener);
        }
    }

    void unregisterPointerEventListener(@NonNull PointerEventListener listener) {
        if (mPointerEventDispatcher != null) {
            mPointerEventDispatcher.unregisterInputEventListener(listener);
        }
    }

    void prepareAppTransition(@WindowManager.TransitionType int transit,
            boolean alwaysKeepCurrent) {
        prepareAppTransition(transit, alwaysKeepCurrent, 0 /* flags */, false /* forceOverride */);
    }

    void prepareAppTransition(@WindowManager.TransitionType int transit,
            boolean alwaysKeepCurrent, @WindowManager.TransitionFlags int flags,
            boolean forceOverride) {
        final boolean prepared = mAppTransition.prepareAppTransitionLocked(
                transit, alwaysKeepCurrent, flags, forceOverride);
        if (prepared && okToAnimate()) {
            mSkipAppTransitionAnimation = false;
        }
    }

    void executeAppTransition() {
        if (mAppTransition.isTransitionSet()) {
            if (DEBUG_APP_TRANSITIONS) {
                Slog.w(TAG_WM, "Execute app transition: " + mAppTransition + ", displayId: "
                        + mDisplayId + " Callers=" + Debug.getCallers(5));
            }
            mAppTransition.setReady();
            mWmService.mWindowPlacerLocked.requestTraversal();
        }
    }

    /**
     * Update pendingLayoutChanges after app transition has finished.
     */
    void handleAnimatingStoppedAndTransition() {
        int changes = 0;

        mAppTransition.setIdle();

        for (int i = mNoAnimationNotifyOnTransitionFinished.size() - 1; i >= 0; i--) {
            final IBinder token = mNoAnimationNotifyOnTransitionFinished.get(i);
            mAppTransition.notifyAppTransitionFinishedLocked(token);
        }
        mNoAnimationNotifyOnTransitionFinished.clear();

        mWallpaperController.hideDeferredWallpapersIfNeeded();

        onAppTransitionDone();

        changes |= FINISH_LAYOUT_REDO_LAYOUT;
        if (DEBUG_WALLPAPER_LIGHT) {
            Slog.v(TAG_WM, "Wallpaper layer changed: assigning layers + relayout");
        }
        computeImeTarget(true /* updateImeTarget */);
        mWallpaperMayChange = true;
        // Since the window list has been rebuilt, focus might have to be recomputed since the
        // actual order of windows might have changed again.
        mWmService.mFocusMayChange = true;

        pendingLayoutChanges |= changes;
    }

    /** Check if pending app transition is for activity / task launch. */
    boolean isNextTransitionForward() {
        final int transit = mAppTransition.getAppTransition();
        return transit == TRANSIT_ACTIVITY_OPEN
                || transit == TRANSIT_TASK_OPEN
                || transit == TRANSIT_TASK_TO_FRONT;
    }

    /**
     * @see Display#FLAG_SHOULD_SHOW_SYSTEM_DECORATIONS
     */
    boolean supportsSystemDecorations() {
        // TODO(b/114338689): Read the setting from DisplaySettings.
        return mDisplay.supportsSystemDecorations()
                // TODO (b/111363427): Remove this and set the new FLAG_SHOULD_SHOW_LAUNCHER flag
                // (b/114338689) whenever vr 2d display id is set.
                || mDisplayId == mWmService.mVr2dDisplayId
                || mWmService.mForceDesktopModeOnExternalDisplays;
    }

    /**
     * Re-parent the DisplayContent's top surfaces, {@link #mWindowingLayer} and
     * {@link #mOverlayLayer} to the specified surfaceControl.
     *
     * @param sc The new SurfaceControl, where the DisplayContent's surfaces will be re-parented to.
     */
    void reparentDisplayContent(SurfaceControl sc) {
        mParentSurfaceControl = sc;
        if (mPortalWindowHandle == null) {
            mPortalWindowHandle = createPortalWindowHandle(sc.toString());
        }
        mPendingTransaction.setInputWindowInfo(sc, mPortalWindowHandle)
                .reparent(mWindowingLayer, sc).reparent(mOverlayLayer, sc);
    }

    @VisibleForTesting
    SurfaceControl getWindowingLayer() {
        return mWindowingLayer;
    }

    /**
     * Create a portal window handle for input. This window transports any touch to the display
     * indicated by {@link InputWindowHandle#portalToDisplayId} if the touch hits this window.
     *
     * @param name The name of the portal window handle.
     * @return the new portal window handle.
     */
    private InputWindowHandle createPortalWindowHandle(String name) {
        // Let surface flinger to set the display ID of this input window handle because we don't
        // know which display the parent surface control is on.
        final InputWindowHandle portalWindowHandle = new InputWindowHandle(
                null /* inputApplicationHandle */, null /* clientWindow */, INVALID_DISPLAY);
        portalWindowHandle.name = name;
        portalWindowHandle.token = new Binder();
        portalWindowHandle.layoutParamsFlags =
                FLAG_SPLIT_TOUCH | FLAG_NOT_FOCUSABLE | FLAG_NOT_TOUCH_MODAL;
        getBounds(mTmpBounds);
        portalWindowHandle.touchableRegion.set(mTmpBounds);
        portalWindowHandle.scaleFactor = 1f;
        portalWindowHandle.ownerPid = Process.myPid();
        portalWindowHandle.ownerUid = Process.myUid();
        portalWindowHandle.portalToDisplayId = mDisplayId;
        return portalWindowHandle;
    }

    /**
     * @see IWindowManager#setForwardedInsets
     */
    public void setForwardedInsets(Insets insets) {
        if (insets == null) {
            insets = Insets.NONE;
        }
        if (mDisplayPolicy.getForwardedInsets().equals(insets)) {
            return;
        }
        mDisplayPolicy.setForwardedInsets(insets);
        setLayoutNeeded();
        mWmService.mWindowPlacerLocked.requestTraversal();
    }
}
