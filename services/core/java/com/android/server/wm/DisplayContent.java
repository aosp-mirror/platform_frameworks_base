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

import static android.app.ActivityTaskManager.INVALID_TASK_ID;
import static android.app.WindowConfiguration.ACTIVITY_TYPE_DREAM;
import static android.app.WindowConfiguration.ACTIVITY_TYPE_HOME;
import static android.app.WindowConfiguration.ACTIVITY_TYPE_RECENTS;
import static android.app.WindowConfiguration.ACTIVITY_TYPE_STANDARD;
import static android.app.WindowConfiguration.ACTIVITY_TYPE_UNDEFINED;
import static android.app.WindowConfiguration.ROTATION_UNDEFINED;
import static android.app.WindowConfiguration.WINDOWING_MODE_FREEFORM;
import static android.app.WindowConfiguration.WINDOWING_MODE_FULLSCREEN;
import static android.app.WindowConfiguration.WINDOWING_MODE_FULLSCREEN_OR_SPLIT_SCREEN_SECONDARY;
import static android.app.WindowConfiguration.WINDOWING_MODE_MULTI_WINDOW;
import static android.app.WindowConfiguration.WINDOWING_MODE_PINNED;
import static android.app.WindowConfiguration.WINDOWING_MODE_SPLIT_SCREEN_PRIMARY;
import static android.app.WindowConfiguration.WINDOWING_MODE_SPLIT_SCREEN_SECONDARY;
import static android.app.WindowConfiguration.WINDOWING_MODE_UNDEFINED;
import static android.app.WindowConfiguration.isSplitScreenWindowingMode;
import static android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSET;
import static android.content.pm.ActivityInfo.SCREEN_ORIENTATION_USER;
import static android.content.res.Configuration.ORIENTATION_LANDSCAPE;
import static android.content.res.Configuration.ORIENTATION_PORTRAIT;
import static android.os.Build.VERSION_CODES.N;
import static android.os.Trace.TRACE_TAG_WINDOW_MANAGER;
import static android.util.DisplayMetrics.DENSITY_DEFAULT;
import static android.view.Display.DEFAULT_DISPLAY;
import static android.view.Display.FLAG_PRIVATE;
import static android.view.Display.FLAG_SHOULD_SHOW_SYSTEM_DECORATIONS;
import static android.view.Display.INVALID_DISPLAY;
import static android.view.Display.REMOVE_MODE_DESTROY_CONTENT;
import static android.view.InsetsState.ITYPE_IME;
import static android.view.InsetsState.ITYPE_LEFT_GESTURES;
import static android.view.InsetsState.ITYPE_RIGHT_GESTURES;
import static android.view.Surface.ROTATION_0;
import static android.view.Surface.ROTATION_180;
import static android.view.Surface.ROTATION_270;
import static android.view.Surface.ROTATION_90;
import static android.view.View.GONE;
import static android.view.View.SYSTEM_UI_FLAG_HIDE_NAVIGATION;
import static android.view.View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY;
import static android.view.WindowManager.LayoutParams.FIRST_APPLICATION_WINDOW;
import static android.view.WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE;
import static android.view.WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
import static android.view.WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL;
import static android.view.WindowManager.LayoutParams.FLAG_SECURE;
import static android.view.WindowManager.LayoutParams.FLAG_SHOW_WALLPAPER;
import static android.view.WindowManager.LayoutParams.FLAG_SPLIT_TOUCH;
import static android.view.WindowManager.LayoutParams.LAST_APPLICATION_WINDOW;
import static android.view.WindowManager.LayoutParams.TYPE_ACCESSIBILITY_MAGNIFICATION_OVERLAY;
import static android.view.WindowManager.LayoutParams.TYPE_APPLICATION_STARTING;
import static android.view.WindowManager.LayoutParams.TYPE_BASE_APPLICATION;
import static android.view.WindowManager.LayoutParams.TYPE_BOOT_PROGRESS;
import static android.view.WindowManager.LayoutParams.TYPE_INPUT_METHOD;
import static android.view.WindowManager.LayoutParams.TYPE_INPUT_METHOD_DIALOG;
import static android.view.WindowManager.LayoutParams.TYPE_NAVIGATION_BAR;
import static android.view.WindowManager.LayoutParams.TYPE_NOTIFICATION_SHADE;
import static android.view.WindowManager.LayoutParams.TYPE_SYSTEM_DIALOG;
import static android.view.WindowManager.LayoutParams.TYPE_SYSTEM_ERROR;
import static android.view.WindowManager.LayoutParams.TYPE_TOAST;
import static android.view.WindowManager.LayoutParams.TYPE_WALLPAPER;
import static android.view.WindowManager.TRANSIT_ACTIVITY_OPEN;
import static android.view.WindowManager.TRANSIT_TASK_OPEN;
import static android.view.WindowManager.TRANSIT_TASK_TO_FRONT;

import static com.android.server.policy.WindowManagerPolicy.FINISH_LAYOUT_REDO_ANIM;
import static com.android.server.policy.WindowManagerPolicy.FINISH_LAYOUT_REDO_CONFIG;
import static com.android.server.policy.WindowManagerPolicy.FINISH_LAYOUT_REDO_LAYOUT;
import static com.android.server.policy.WindowManagerPolicy.FINISH_LAYOUT_REDO_WALLPAPER;
import static com.android.server.wm.ActivityStack.ActivityState.RESUMED;
import static com.android.server.wm.ActivityStack.STACK_VISIBILITY_VISIBLE;
import static com.android.server.wm.ActivityStackSupervisor.TAG_TASKS;
import static com.android.server.wm.ActivityTaskManagerDebugConfig.DEBUG_STATES;
import static com.android.server.wm.ActivityTaskManagerDebugConfig.DEBUG_TASKS;
import static com.android.server.wm.ActivityTaskManagerDebugConfig.POSTFIX_STACK;
import static com.android.server.wm.DisplayContentProto.APP_TRANSITION;
import static com.android.server.wm.DisplayContentProto.CLOSING_APPS;
import static com.android.server.wm.DisplayContentProto.DISPLAY_FRAMES;
import static com.android.server.wm.DisplayContentProto.DISPLAY_INFO;
import static com.android.server.wm.DisplayContentProto.DISPLAY_READY;
import static com.android.server.wm.DisplayContentProto.DPI;
import static com.android.server.wm.DisplayContentProto.FOCUSED_APP;
import static com.android.server.wm.DisplayContentProto.FOCUSED_ROOT_TASK_ID;
import static com.android.server.wm.DisplayContentProto.ID;
import static com.android.server.wm.DisplayContentProto.OPENING_APPS;
import static com.android.server.wm.DisplayContentProto.OVERLAY_WINDOWS;
import static com.android.server.wm.DisplayContentProto.RESUMED_ACTIVITY;
import static com.android.server.wm.DisplayContentProto.ROOT_DISPLAY_AREA;
import static com.android.server.wm.DisplayContentProto.ROTATION;
import static com.android.server.wm.DisplayContentProto.SCREEN_ROTATION_ANIMATION;
import static com.android.server.wm.DisplayContentProto.SINGLE_TASK_INSTANCE;
import static com.android.server.wm.DisplayContentProto.WINDOW_CONTAINER;
import static com.android.server.wm.ProtoLogGroup.WM_DEBUG_APP_TRANSITIONS;
import static com.android.server.wm.ProtoLogGroup.WM_DEBUG_FOCUS;
import static com.android.server.wm.ProtoLogGroup.WM_DEBUG_FOCUS_LIGHT;
import static com.android.server.wm.ProtoLogGroup.WM_DEBUG_ORIENTATION;
import static com.android.server.wm.ProtoLogGroup.WM_DEBUG_SCREEN_ON;
import static com.android.server.wm.ProtoLogGroup.WM_SHOW_TRANSACTIONS;
import static com.android.server.wm.RootWindowContainer.TAG_STATES;
import static com.android.server.wm.WindowContainer.AnimationFlags.PARENTS;
import static com.android.server.wm.WindowContainer.AnimationFlags.TRANSITION;
import static com.android.server.wm.WindowContainerChildProto.DISPLAY_CONTENT;
import static com.android.server.wm.WindowManagerDebugConfig.DEBUG_DISPLAY;
import static com.android.server.wm.WindowManagerDebugConfig.DEBUG_INPUT_METHOD;
import static com.android.server.wm.WindowManagerDebugConfig.DEBUG_LAYOUT;
import static com.android.server.wm.WindowManagerDebugConfig.DEBUG_LAYOUT_REPEATS;
import static com.android.server.wm.WindowManagerDebugConfig.DEBUG_SCREENSHOT;
import static com.android.server.wm.WindowManagerDebugConfig.DEBUG_STACK;
import static com.android.server.wm.WindowManagerDebugConfig.DEBUG_WALLPAPER_LIGHT;
import static com.android.server.wm.WindowManagerDebugConfig.SHOW_STACK_CRAWLS;
import static com.android.server.wm.WindowManagerDebugConfig.TAG_WITH_CLASS_NAME;
import static com.android.server.wm.WindowManagerDebugConfig.TAG_WM;
import static com.android.server.wm.WindowManagerService.H.REPORT_FOCUS_CHANGE;
import static com.android.server.wm.WindowManagerService.H.REPORT_HARD_KEYBOARD_STATUS_CHANGE;
import static com.android.server.wm.WindowManagerService.H.REPORT_LOSING_FOCUS;
import static com.android.server.wm.WindowManagerService.H.UPDATE_MULTI_WINDOW_STACKS;
import static com.android.server.wm.WindowManagerService.H.WINDOW_HIDE_TIMEOUT;
import static com.android.server.wm.WindowManagerService.LAYOUT_REPEAT_THRESHOLD;
import static com.android.server.wm.WindowManagerService.SEAMLESS_ROTATION_TIMEOUT_DURATION;
import static com.android.server.wm.WindowManagerService.UPDATE_FOCUS_NORMAL;
import static com.android.server.wm.WindowManagerService.UPDATE_FOCUS_PLACING_SURFACES;
import static com.android.server.wm.WindowManagerService.UPDATE_FOCUS_REMOVING_FOCUS;
import static com.android.server.wm.WindowManagerService.UPDATE_FOCUS_WILL_ASSIGN_LAYERS;
import static com.android.server.wm.WindowManagerService.UPDATE_FOCUS_WILL_PLACE_SURFACES;
import static com.android.server.wm.WindowManagerService.WINDOWS_FREEZING_SCREENS_TIMEOUT;
import static com.android.server.wm.WindowManagerService.dipToPixel;
import static com.android.server.wm.WindowState.EXCLUSION_LEFT;
import static com.android.server.wm.WindowState.EXCLUSION_RIGHT;
import static com.android.server.wm.WindowState.RESIZE_HANDLE_WIDTH_IN_DP;
import static com.android.server.wm.WindowStateAnimator.READY_TO_SHOW;
import static com.android.server.wm.utils.RegionUtils.forEachRectReverse;
import static com.android.server.wm.utils.RegionUtils.rectListToRegion;

import android.annotation.IntDef;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.app.ActivityManager;
import android.app.ActivityManagerInternal;
import android.app.ActivityOptions;
import android.app.WindowConfiguration;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.ActivityInfo.ScreenOrientation;
import android.content.pm.ApplicationInfo;
import android.content.res.CompatibilityInfo;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.Insets;
import android.graphics.Matrix;
import android.graphics.Point;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Region;
import android.graphics.Region.Op;
import android.hardware.display.DisplayManagerInternal;
import android.metrics.LogMaker;
import android.os.Binder;
import android.os.Debug;
import android.os.Handler;
import android.os.IBinder;
import android.os.Message;
import android.os.Process;
import android.os.RemoteCallbackList;
import android.os.RemoteException;
import android.os.SystemClock;
import android.os.Trace;
import android.os.UserHandle;
import android.provider.Settings;
import android.util.ArraySet;
import android.util.DisplayMetrics;
import android.util.IntArray;
import android.util.RotationUtils;
import android.util.Slog;
import android.util.SparseArray;
import android.util.SparseBooleanArray;
import android.util.proto.ProtoOutputStream;
import android.view.Display;
import android.view.DisplayCutout;
import android.view.DisplayInfo;
import android.view.Gravity;
import android.view.IDisplayWindowInsetsController;
import android.view.ISystemGestureExclusionListener;
import android.view.IWindow;
import android.view.InputChannel;
import android.view.InputDevice;
import android.view.InputWindowHandle;
import android.view.InsetsState.InternalInsetsType;
import android.view.MagnificationSpec;
import android.view.RemoteAnimationDefinition;
import android.view.Surface;
import android.view.SurfaceControl;
import android.view.SurfaceControl.Transaction;
import android.view.SurfaceSession;
import android.view.View;
import android.view.ViewRootImpl;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.view.WindowManagerPolicyConstants.PointerEventListener;
import android.window.WindowContainerTransaction;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.logging.MetricsLogger;
import com.android.internal.logging.nano.MetricsProto.MetricsEvent;
import com.android.internal.util.ToBooleanFunction;
import com.android.internal.util.function.TriConsumer;
import com.android.internal.util.function.pooled.PooledConsumer;
import com.android.internal.util.function.pooled.PooledFunction;
import com.android.internal.util.function.pooled.PooledLambda;
import com.android.internal.util.function.pooled.PooledPredicate;
import com.android.server.policy.WindowManagerPolicy;
import com.android.server.protolog.common.ProtoLog;
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
    private static final String TAG_STACK = TAG + POSTFIX_STACK;
    private static final int NO_ROTATION = -1;

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

    ActivityTaskManagerService mAtmService;

    /** Unique identifier of this display. */
    final int mDisplayId;

    /**
     * Most surfaces will be a child of this window. There are some special layers and windows
     * which are always on top of others and omitted from Screen-Magnification, for example the
     * strict mode flash or the magnification overlay itself. Those layers will be children of
     * {@link #mOverlayContainers} where mWindowContainers contains everything else.
     */
    private final WindowContainers mWindowContainers =
            new WindowContainers("mWindowContainers", mWmService);

    // Contains some special windows which are always on top of others and omitted from
    // Screen-Magnification, for example the WindowMagnification windows.
    private final NonAppWindowContainers mOverlayContainers =
            new NonAppWindowContainers("mOverlayContainers", mWmService);

    /** The containers below are the only child containers {@link #mWindowContainers} can have. */
    // Contains all window containers that are related to apps (Activities)
    private final TaskContainers mTaskContainers = new TaskContainers(this, mWmService);

    // Contains all IME window containers. Note that the z-ordering of the IME windows will depend
    // on the IME target. We mainly have this container grouping so we can keep track of all the IME
    // window containers together and move them in-sync if/when needed. We use a subclass of
    // WindowContainer which is omitted from screen magnification, as the IME is never magnified.
    // TODO(display-area): is "no magnification" in the comment still true?
    private final ImeContainer mImeWindowsContainers = new ImeContainer(mWmService);

    private final DisplayArea.Root mRootDisplayArea = new DisplayArea.Root(mWmService);

    private final DisplayAreaPolicy mDisplayAreaPolicy;

    private WindowState mTmpWindow;
    private WindowState mTmpWindow2;
    private boolean mUpdateImeTarget;
    private boolean mTmpInitial;
    private int mMaxUiWidth;

    final AppTransition mAppTransition;
    final AppTransitionController mAppTransitionController;
    boolean mSkipAppTransitionAnimation = false;

    final ArraySet<ActivityRecord> mOpeningApps = new ArraySet<>();
    final ArraySet<ActivityRecord> mClosingApps = new ArraySet<>();
    final ArraySet<WindowContainer> mChangingContainers = new ArraySet<>();
    final UnknownAppVisibilityController mUnknownAppVisibilityController;

    private MetricsLogger mMetricsLogger;

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
    final Display mDisplay;
    private final DisplayInfo mDisplayInfo = new DisplayInfo();
    private final DisplayMetrics mDisplayMetrics = new DisplayMetrics();
    private final DisplayPolicy mDisplayPolicy;
    private final DisplayRotation mDisplayRotation;
    DisplayFrames mDisplayFrames;

    private final RemoteCallbackList<ISystemGestureExclusionListener>
            mSystemGestureExclusionListeners = new RemoteCallbackList<>();
    private final Region mSystemGestureExclusion = new Region();
    private boolean mSystemGestureExclusionWasRestricted = false;
    private final Region mSystemGestureExclusionUnrestricted = new Region();
    private int mSystemGestureExclusionLimit;

    /**
     * For default display it contains real metrics, empty for others.
     * @see WindowManagerService#createWatermark()
     */
    final DisplayMetrics mRealDisplayMetrics = new DisplayMetrics();

    /** @see #computeCompatSmallestWidth(boolean, int, int, int) */
    private final DisplayMetrics mTmpDisplayMetrics = new DisplayMetrics();

    /**
     * Compat metrics computed based on {@link #mDisplayMetrics}.
     * @see #updateDisplayAndOrientation(int)
     */
    private final DisplayMetrics mCompatDisplayMetrics = new DisplayMetrics();

    /** The desired scaling factor for compatible apps. */
    float mCompatibleScreenScale;

    /** @see #getCurrentOverrideConfigurationChanges */
    private int mCurrentOverrideConfigurationChanges;

    /**
     * The maximum aspect ratio (longerSide/shorterSide) that is treated as close-to-square. The
     * orientation requests from apps would be ignored if the display is close-to-square.
     */
    @VisibleForTesting
    final float mCloseToSquareMaxAspectRatio;

    /**
     * If this is true, we would not rotate the display for apps. The rotation would be either the
     * sensor rotation or the user rotation, controlled by
     * {@link WindowManagerPolicy.UserRotationMode}.
     */
    private boolean mIgnoreRotationForApps;

    /**
     * Keep track of wallpaper visibility to notify changes.
     */
    private boolean mLastWallpaperVisible = false;

    private Rect mBaseDisplayRect = new Rect();

    // Accessed directly by all users.
    private boolean mLayoutNeeded;
    int pendingLayoutChanges;

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
    @VisibleForTesting
    final TaskTapPointerEventListener mTapDetector;

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

    private ArrayList<Task> mTmpTasks = new ArrayList<>();

    /** Remove this display when animation on it has completed. */
    private boolean mDeferredRemoval;

    final DockedStackDividerController mDividerControllerLocked;
    final PinnedStackController mPinnedStackControllerLocked;

    final ArrayList<WindowState> mTapExcludedWindows = new ArrayList<>();
    /** A collection of windows that provide tap exclude regions inside of them. */
    final ArraySet<WindowState> mTapExcludeProvidingWindows = new ArraySet<>();

    private final LinkedList<ActivityRecord> mTmpUpdateAllDrawn = new LinkedList();

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
    ActivityRecord mFocusedApp = null;

    /**
     * The launching activity which is using fixed rotation transformation.
     *
     * @see #handleTopActivityLaunchingInDifferentOrientation
     */
    ActivityRecord mFixedRotationLaunchingApp;

    /** Windows added since {@link #mCurrentFocus} was set to null. Used for ANR blaming. */
    final ArrayList<WindowState> mWinAddedSinceNullFocus = new ArrayList<>();

    /** Windows removed since {@link #mCurrentFocus} was set to null. Used for ANR blaming. */
    final ArrayList<WindowState> mWinRemovedSinceNullFocus = new ArrayList<>();

    private ScreenRotationAnimation mScreenRotationAnimation;

    /**
     * Sequence number for the current layout pass.
     */
    int mLayoutSeq = 0;

    /**
     * Specifies the count to determine whether to defer updating the IME target until ready.
     */
    private int mDeferUpdateImeTargetCount;

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

    InsetsControlTarget mInputMethodControlTarget;

    /** If true hold off on modifying the animation layer of mInputMethodTarget */
    boolean mInputMethodTargetWaitingAnim;

    private final PointerEventDispatcher mPointerEventDispatcher;

    private final InsetsStateController mInsetsStateController;
    private final InsetsPolicy mInsetsPolicy;

    /** @see #getParentWindow() */
    private WindowState mParentWindow;

    private Point mLocationInParentWindow = new Point();
    private SurfaceControl mParentSurfaceControl;
    private InputWindowHandle mPortalWindowHandle;

    // Last systemUiVisibility we received from status bar.
    private int mLastStatusBarVisibility = 0;
    // Last systemUiVisibility we dispatched to windows.
    private int mLastDispatchedSystemUiVisibility = 0;

    /** Corner radius that windows should have in order to match the display. */
    private final float mWindowCornerRadius;

    private final SparseArray<ShellRoot> mShellRoots = new SparseArray<>();
    RemoteInsetsControlTarget mRemoteInsetsControlTarget = null;
    private final IBinder.DeathRecipient mRemoteInsetsDeath =
            () -> {
                synchronized (mWmService.mGlobalLock) {
                    mRemoteInsetsControlTarget = null;
                }
            };

    private RootWindowContainer mRootWindowContainer;

    /**
     * All of the stacks on this display. Order matters, topmost stack is in front of all other
     * stacks, bottommost behind. Accessed directly by ActivityManager package classes. Any calls
     * changing the list should also call {@link #onStackOrderChanged()}.
     */
    private ArrayList<OnStackOrderChangedListener> mStackOrderChangedCallbacks = new ArrayList<>();

    /** Array of all UIDs that are present on the display. */
    private IntArray mDisplayAccessUIDs = new IntArray();

    /** All tokens used to put activities on this stack to sleep (including mOffToken) */
    final ArrayList<ActivityTaskManagerInternal.SleepToken> mAllSleepTokens = new ArrayList<>();
    /** The token acquired by ActivityStackSupervisor to put stacks on the display to sleep */
    ActivityTaskManagerInternal.SleepToken mOffToken;

    private boolean mSleeping;

    /** We started the process of removing the display from the system. */
    private boolean mRemoving;

    /**
     * The display is removed from the system and we are just waiting for all activities on it to be
     * finished before removing this object.
     */
    private boolean mRemoved;

    /** The display can only contain one task. */
    private boolean mSingleTaskInstance;

    /**
     * Non-null if the last size compatibility mode activity is using non-native screen
     * configuration. The activity is not able to put in multi-window mode, so it exists only one
     * per display.
     */
    private ActivityRecord mLastCompatModeActivity;

    /**
     * A focusable stack that is purposely to be positioned at the top. Although the stack may not
     * have the topmost index, it is used as a preferred candidate to prevent being unable to resume
     * target stack properly when there are other focusable always-on-top stacks.
     */
    private ActivityStack mPreferredTopFocusableStack;

    /**
     * If this is the same as {@link #getFocusedStack} then the activity on the top of the focused
     * stack has been resumed. If stacks are changing position this will hold the old stack until
     * the new stack becomes resumed after which it will be set to current focused stack.
     */
    private ActivityStack mLastFocusedStack;

    // Used in updating the display size
    private Point mTmpDisplaySize = new Point();

    // Used in updating override configurations
    private final Configuration mTempConfig = new Configuration();

    private final RootWindowContainer.FindTaskResult
            mTmpFindTaskResult = new RootWindowContainer.FindTaskResult();

    // When non-null, new tasks get put into this root task.
    Task mLaunchRootTask = null;

    // Used in performing layout
    private boolean mTmpWindowsBehindIme;

    private final Consumer<WindowState> mUpdateWindowsForAnimator = w -> {
        WindowStateAnimator winAnimator = w.mWinAnimator;
        final ActivityRecord activity = w.mActivityRecord;
        if (winAnimator.mDrawState == READY_TO_SHOW) {
            if (activity == null || activity.canShowWindows()) {
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
        final ActivityRecord focusedApp = mFocusedApp;
        ProtoLog.v(WM_DEBUG_FOCUS, "Looking for focus: %s, flags=%d, canReceive=%b",
                w, w.mAttrs.flags, w.canReceiveKeys());

        if (!w.canReceiveKeys()) {
            return false;
        }

        final ActivityRecord activity = w.mActivityRecord;

        if (focusedApp == null) {
            ProtoLog.v(WM_DEBUG_FOCUS_LIGHT,
                    "findFocusedWindow: focusedApp=null using new focus @ %s", w);
            mTmpWindow = w;
            return true;
        }

        if (!focusedApp.windowsAreFocusable()) {
            // Current focused app windows aren't focusable...
            ProtoLog.v(WM_DEBUG_FOCUS_LIGHT, "findFocusedWindow: focusedApp windows not"
                    + " focusable using new focus @ %s", w);
            mTmpWindow = w;
            return true;
        }

        // Descend through all of the app tokens and find the first that either matches
        // win.mActivityRecord (return win) or mFocusedApp (return null).
        if (activity != null && w.mAttrs.type != TYPE_APPLICATION_STARTING) {
            if (focusedApp.compareTo(activity) > 0) {
                // App stack below focused app stack. No focus for you!!!
                ProtoLog.v(WM_DEBUG_FOCUS_LIGHT,
                        "findFocusedWindow: Reached focused app=%s", focusedApp);
                mTmpWindow = null;
                return true;
            }
        }

        ProtoLog.v(WM_DEBUG_FOCUS_LIGHT, "findFocusedWindow: Found new focus @ %s", w);
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
                    + " config reported=" + w.isLastConfigReportedToClient());
            final ActivityRecord activity = w.mActivityRecord;
            if (gone) Slog.v(TAG, "  GONE: mViewVisibility=" + w.mViewVisibility
                    + " mRelayoutCalled=" + w.mRelayoutCalled + " visible=" + w.mToken.isVisible()
                    + " visibleRequested=" + (activity != null && activity.mVisibleRequested)
                    + " parentHidden=" + w.isParentWindowHidden());
            else Slog.v(TAG, "  VIS: mViewVisibility=" + w.mViewVisibility
                    + " mRelayoutCalled=" + w.mRelayoutCalled + " visible=" + w.mToken.isVisible()
                    + " visibleRequested=" + (activity != null && activity.mVisibleRequested)
                    + " parentHidden=" + w.isParentWindowHidden());
        }

        // Sets mBehindIme for each window. Windows behind IME can get IME insets.
        w.mBehindIme = mTmpWindowsBehindIme;
        if (w == mInputMethodWindow) {
            mTmpWindowsBehindIme = true;
        }

        // If this view is GONE, then skip it -- keep the current frame, and let the caller know
        // so they can ignore it if they want.  (We do the normal layout for INVISIBLE windows,
        // since that means "perform layout as normal, just don't display").
        if ((!gone || !w.mHaveFrame || w.mLayoutNeeded) && !w.mLayoutAttached) {
            if (mTmpInitial) {
                w.resetContentChanged();
            }
            w.mLayoutNeeded = false;
            w.prelayout();
            final boolean firstLayout = !w.isLaidOut();
            getDisplayPolicy().layoutWindowLw(w, null, mDisplayFrames);
            w.mLayoutSeq = mLayoutSeq;

            // If this is the first layout, we need to initialize the last frames and inset values,
            // as otherwise we'd immediately cause an unnecessary resize.
            if (firstLayout) {
                // The client may compute its actual requested size according to the first layout,
                // so we still request the window to resize if the current frame is empty.
                if (!w.getFrameLw().isEmpty()) {
                    w.updateLastFrames();
                }
                w.updateLastInsetValues();
                w.updateLocationInParentDisplayIfNeeded();
            }

            if (w.mActivityRecord != null) {
                w.mActivityRecord.layoutLetterbox(w);
            }

            if (DEBUG_LAYOUT) Slog.v(TAG, "  LAYOUT: mFrame=" + w.getFrameLw()
                    + " mContainingFrame=" + w.getContainingFrame()
                    + " mDisplayFrame=" + w.getDisplayFrameLw());
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

            final boolean displayHasContent = root.handleNotObscuredLocked(w,
                    mTmpApplySurfaceChangesTransactionState.obscured,
                    mTmpApplySurfaceChangesTransactionState.syswin);

            if (!mTmpApplySurfaceChangesTransactionState.displayHasContent
                    && !getDisplayPolicy().isWindowExcludedFromContent(w)) {
                mTmpApplySurfaceChangesTransactionState.displayHasContent |= displayHasContent;
            }

            if (w.mHasSurface && isDisplayed) {
                final int type = w.mAttrs.type;
                if (type == TYPE_SYSTEM_DIALOG || type == TYPE_SYSTEM_ERROR
                        || mWmService.mPolicy.isKeyguardShowing()) {
                    mTmpApplySurfaceChangesTransactionState.syswin = true;
                }
                if (mTmpApplySurfaceChangesTransactionState.preferredRefreshRate == 0
                        && w.mAttrs.preferredRefreshRate != 0) {
                    mTmpApplySurfaceChangesTransactionState.preferredRefreshRate
                            = w.mAttrs.preferredRefreshRate;
                }

                mTmpApplySurfaceChangesTransactionState.preferMinimalPostProcessing
                        |= w.mAttrs.preferMinimalPostProcessing;

                final int preferredModeId = getDisplayPolicy().getRefreshRatePolicy()
                        .getPreferredModeId(w);
                if (mTmpApplySurfaceChangesTransactionState.preferredModeId == 0
                        && preferredModeId != 0) {
                    mTmpApplySurfaceChangesTransactionState.preferredModeId = preferredModeId;
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

        final ActivityRecord activity = w.mActivityRecord;
        if (activity != null) {
            activity.updateLetterboxSurface(w);
            final boolean updateAllDrawn = activity.updateDrawnWindowStates(w);
            if (updateAllDrawn && !mTmpUpdateAllDrawn.contains(activity)) {
                mTmpUpdateAllDrawn.add(activity);
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
     * @param root {@link RootWindowContainer}
     */
    DisplayContent(Display display, RootWindowContainer root) {
        super(root.mWindowManager);
        if (mWmService.mRoot.getDisplayContent(display.getDisplayId()) != null) {
            throw new IllegalArgumentException("Display with ID=" + display.getDisplayId()
                    + " already exists="
                    + mWmService.mRoot.getDisplayContent(display.getDisplayId())
                    + " new=" + display);
        }

        mRootWindowContainer = root;
        mAtmService = mWmService.mAtmService;
        mDisplay = display;
        mDisplayId = display.getDisplayId();
        mWallpaperController = new WallpaperController(mWmService, this);
        display.getDisplayInfo(mDisplayInfo);
        display.getMetrics(mDisplayMetrics);
        mSystemGestureExclusionLimit = mWmService.mConstants.mSystemGestureExclusionLimitDp
                * mDisplayMetrics.densityDpi / DENSITY_DEFAULT;
        isDefaultDisplay = mDisplayId == DEFAULT_DISPLAY;
        mDisplayFrames = new DisplayFrames(mDisplayId, mDisplayInfo,
                calculateDisplayCutoutForRotation(mDisplayInfo.rotation));
        initializeDisplayBaseInfo();

        mAppTransition = new AppTransition(mWmService.mContext, mWmService, this);
        mAppTransition.registerListenerLocked(mWmService.mActivityManagerAppTransitionNotifier);
        mAppTransitionController = new AppTransitionController(mWmService, this);
        mUnknownAppVisibilityController = new UnknownAppVisibilityController(mWmService, this);

        final InputChannel inputChannel = mWmService.mInputManager.monitorInput(
                "PointerEventDispatcher" + mDisplayId, mDisplayId);
        mPointerEventDispatcher = new PointerEventDispatcher(inputChannel);

        // Tap Listeners are supported for:
        // 1. All physical displays (multi-display).
        // 2. VirtualDisplays on VR, AA (and everything else).
        mTapDetector = new TaskTapPointerEventListener(mWmService, this);
        registerPointerEventListener(mTapDetector);
        registerPointerEventListener(mWmService.mMousePositionTracker);
        if (mWmService.mAtmService.getRecentTasks() != null) {
            registerPointerEventListener(
                    mWmService.mAtmService.getRecentTasks().getInputListener());
        }

        mDisplayPolicy = new DisplayPolicy(mWmService, this);
        mDisplayRotation = new DisplayRotation(mWmService, this);
        mCloseToSquareMaxAspectRatio = mWmService.mContext.getResources().getFloat(
                com.android.internal.R.dimen.config_closeToSquareDisplayMaxAspectRatio);
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
        mWindowCornerRadius = mDisplayPolicy.getWindowCornerRadius();
        mDividerControllerLocked = new DockedStackDividerController(this);
        mPinnedStackControllerLocked = new PinnedStackController(mWmService, this);

        final SurfaceControl.Builder b = mWmService.makeSurfaceBuilder(mSession)
                .setOpaque(true)
                .setContainerLayer();
        mSurfaceControl = b.setName("Root").setContainerLayer().build();

        getPendingTransaction()
                .setLayer(mSurfaceControl, 0)
                .setLayerStack(mSurfaceControl, mDisplayId)
                .show(mSurfaceControl);
        getPendingTransaction().apply();

        // These are the only direct children we should ever have and they are permanent.
        super.addChild(mWindowContainers, null);
        super.addChild(mOverlayContainers, null);

        mDisplayAreaPolicy = mWmService.mDisplayAreaPolicyProvider.instantiate(
                mWmService, this, mRootDisplayArea, mImeWindowsContainers, mTaskContainers);
        mWindowContainers.addChildren();

        // Sets the display content for the children.
        onDisplayChanged(this);

        mInputMonitor = new InputMonitor(mWmService, this);
        mInsetsStateController = new InsetsStateController(this);
        mInsetsPolicy = new InsetsPolicy(mInsetsStateController, this);

        if (DEBUG_DISPLAY) Slog.v(TAG_WM, "Creating display=" + display);

        mWmService.mDisplayWindowSettings.applySettingsToDisplayLocked(this);
    }

    boolean isReady() {
        // The display is ready when the system and the individual display are both ready.
        return mWmService.mDisplayReady && mDisplayReady;
    }

    int getDisplayId() {
        return mDisplayId;
    }

    float getWindowCornerRadius() {
        return mWindowCornerRadius;
    }

    WindowToken getWindowToken(IBinder binder) {
        return mTokenMap.get(binder);
    }

    ActivityRecord getActivityRecord(IBinder binder) {
        final WindowToken token = getWindowToken(binder);
        if (token == null) {
            return null;
        }
        return token.asActivityRecord();
    }

    void addWindowToken(IBinder binder, WindowToken token) {
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

        if (token.asActivityRecord() == null) {
            // Set displayContent for non-app token to prevent same token will add twice after
            // onDisplayChanged.
            // TODO: Check if it's fine that super.onDisplayChanged of WindowToken
            //  (WindowsContainer#onDisplayChanged) may skipped when token.mDisplayContent assigned.
            token.mDisplayContent = this;
            // Add non-app token to container hierarchy on the display. App tokens are added through
            // the parent container managing them (e.g. Tasks).
            switch (token.windowType) {
                case TYPE_INPUT_METHOD:
                case TYPE_INPUT_METHOD_DIALOG:
                    mImeWindowsContainers.addChild(token);
                    break;
                case TYPE_ACCESSIBILITY_MAGNIFICATION_OVERLAY:
                    // TODO(display-area): Migrate to DisplayArea
                    mOverlayContainers.addChild(token);
                    break;
                default:
                    mDisplayAreaPolicy.addWindow(token);
                    break;
            }
        }
    }

    WindowToken removeWindowToken(IBinder binder) {
        final WindowToken token = mTokenMap.remove(binder);
        if (token != null && token.asActivityRecord() == null) {
            token.setExiting();
        }
        return token;
    }

    SurfaceControl addShellRoot(@NonNull IWindow client, int windowType) {
        ShellRoot root = mShellRoots.get(windowType);
        if (root != null) {
            if (root.getClient() == client) {
                return root.getSurfaceControl();
            }
            root.clear();
            mShellRoots.remove(windowType);
        }
        root = new ShellRoot(client, this, windowType);
        SurfaceControl rootLeash = root.getSurfaceControl();
        if (rootLeash == null) {
            // Root didn't finish initializing, so don't add it.
            root.clear();
            return null;
        }
        mShellRoots.put(windowType, root);
        SurfaceControl out = new SurfaceControl();
        out.copyFrom(rootLeash);
        return out;
    }

    void removeShellRoot(int windowType) {
        ShellRoot root = mShellRoots.get(windowType);
        if (root == null) {
            return;
        }
        root.clear();
        mShellRoots.remove(windowType);
    }

    void setRemoteInsetsController(IDisplayWindowInsetsController controller) {
        if (mRemoteInsetsControlTarget != null) {
            mRemoteInsetsControlTarget.mRemoteInsetsController.asBinder().unlinkToDeath(
                    mRemoteInsetsDeath, 0);
            mRemoteInsetsControlTarget = null;
        }
        if (controller != null) {
            try {
                controller.asBinder().linkToDeath(mRemoteInsetsDeath, 0);
                mRemoteInsetsControlTarget = new RemoteInsetsControlTarget(controller);
            } catch (RemoteException e) {
                return;
            }
        }
    }

    /** Changes the display the input window token is housed on to this one. */
    void reParentWindowToken(WindowToken token) {
        final DisplayContent prevDc = token.getDisplayContent();
        if (prevDc == this) {
            return;
        }
        if (prevDc != null) {
            if (prevDc.mTokenMap.remove(token.token) != null && token.asActivityRecord() == null) {
                // Removed the token from the map, but made sure it's not an app token before
                // removing from parent.
                token.getParent().removeChild(token);
            }
            if (token.hasChild(prevDc.mLastFocus)) {
                // If the reparent window token contains previous display's last focus window, means
                // it will end up to gain window focus on the target display, so it should not be
                // notified that it lost focus from the previous display.
                prevDc.mLastFocus = null;
            }
        }

        addWindowToken(token.token, token);

        if (mWmService.mAccessibilityController != null) {
            final int prevDisplayId = prevDc != null ? prevDc.getDisplayId() : INVALID_DISPLAY;
            mWmService.mAccessibilityController.onSomeWindowResizedOrMovedLocked(prevDisplayId,
                    getDisplayId());
        }
    }

    void removeAppToken(IBinder binder) {
        final WindowToken token = removeWindowToken(binder);
        if (token == null) {
            Slog.w(TAG_WM, "removeAppToken: Attempted to remove non-existing token: " + binder);
            return;
        }

        final ActivityRecord activity = token.asActivityRecord();

        if (activity == null) {
            Slog.w(TAG_WM, "Attempted to remove non-App token: " + binder + " token=" + token);
            return;
        }

        activity.onRemovedFromDisplay();
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

    void setInsetProvider(@InternalInsetsType int type, WindowState win,
            @Nullable TriConsumer<DisplayFrames, WindowState, Rect> frameProvider){
        setInsetProvider(type, win, frameProvider, null /* imeFrameProvider */);
    }

    /**
     * Marks a window as providing insets for the rest of the windows in the system.
     *
     * @param type The type of inset this window provides.
     * @param win The window.
     * @param frameProvider Function to compute the frame, or {@code null} if the just the frame of
     *                      the window should be taken.
     * @param imeFrameProvider Function to compute the frame when dispatching insets to the IME, or
     *                         {@code null} if the normal frame should be taken.
     */
    void setInsetProvider(@InternalInsetsType int type, WindowState win,
            @Nullable TriConsumer<DisplayFrames, WindowState, Rect> frameProvider,
            @Nullable TriConsumer<DisplayFrames, WindowState, Rect> imeFrameProvider) {
        mInsetsStateController.getSourceProvider(type).setWindow(win, frameProvider,
                imeFrameProvider);
    }

    InsetsStateController getInsetsStateController() {
        return mInsetsStateController;
    }

    InsetsPolicy getInsetsPolicy() {
        return mInsetsPolicy;
    }

    @Surface.Rotation
    int getRotation() {
        return mDisplayRotation.getRotation();
    }

    @ScreenOrientation
    int getLastOrientation() {
        return mDisplayRotation.getLastOrientation();
    }

    void registerRemoteAnimations(RemoteAnimationDefinition definition) {
        mAppTransitionController.registerRemoteAnimations(definition);
    }

    void reconfigureDisplayLocked() {
        if (!isReady()) {
            return;
        }
        configureDisplayPolicy();
        setLayoutNeeded();

        boolean configChanged = updateOrientation();
        final Configuration currentDisplayConfig = getConfiguration();
        mTmpConfiguration.setTo(currentDisplayConfig);
        computeScreenConfiguration(mTmpConfiguration);
        configChanged |= currentDisplayConfig.diff(mTmpConfiguration) != 0;

        if (configChanged) {
            mWaitingForConfig = true;
            mWmService.startFreezingDisplayLocked(0 /* exitAnim */, 0 /* enterAnim */, this);
            sendNewConfiguration();
        }

        mWmService.mWindowPlacerLocked.performSurfacePlacement();
    }

    void sendNewConfiguration() {
        if (!isReady()) {
            return;
        }
        if (mDisplayRotation.isWaitingForRemoteRotation()) {
            return;
        }

        final boolean configUpdated = updateDisplayOverrideConfigurationLocked();
        if (configUpdated) {
            return;
        }
        // Something changed (E.g. device rotation), but no configuration update is needed.
        // E.g. changing device rotation by 180 degrees. Go ahead and perform surface placement to
        // unfreeze the display since we froze it when the rotation was updated in
        // DisplayContent#updateRotationUnchecked.
        if (mWaitingForConfig) {
            mWaitingForConfig = false;
            mWmService.mLastFinishedFreezeSource = "config-unchanged";
            setLayoutNeeded();
            mWmService.mWindowPlacerLocked.performSurfacePlacement();
        }
    }

    @Override
    boolean onDescendantOrientationChanged(IBinder freezeDisplayToken,
            ConfigurationContainer requestingContainer) {
        final Configuration config = updateOrientation(
                getRequestedOverrideConfiguration(), freezeDisplayToken, false /* forceUpdate */);
        // If display rotation class tells us that it doesn't consider app requested orientation,
        // this display won't rotate just because of an app changes its requested orientation. Thus
        // it indicates that this display chooses not to handle this request.
        final boolean handled = getDisplayRotation().respectAppRequestedOrientation();
        if (config == null) {
            return handled;
        }

        if (handled && requestingContainer instanceof ActivityRecord) {
            final ActivityRecord activityRecord = (ActivityRecord) requestingContainer;
            final boolean kept = updateDisplayOverrideConfigurationLocked(config, activityRecord,
                    false /* deferResume */, null /* result */);
            activityRecord.frozenBeforeDestroy = true;
            if (!kept) {
                mRootWindowContainer.resumeFocusedStacksTopActivities();
            }
        } else {
            // We have a new configuration to push so we need to update ATMS for now.
            // TODO: Clean up display configuration push between ATMS and WMS after unification.
            updateDisplayOverrideConfigurationLocked(config, null /* starting */,
                    false /* deferResume */, null);
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
     * @see #getOrientation()
     * @return {@code true} if the orientation is changed and the caller should call
     *         {@link #sendNewConfiguration} if the method returns {@code true}.
     */
    boolean updateOrientation() {
        return updateOrientation(false /* forceUpdate */);
    }

    /**
     * Update orientation of the display, returning a non-null new Configuration if it has
     * changed from the current orientation. If a non-null configuration is returned, someone must
     * call {@link WindowManagerService#setNewDisplayOverrideConfiguration(Configuration,
     * DisplayContent)} to tell the window manager it can unfreeze the screen. This will typically
     * be done by calling {@link #sendNewConfiguration}.
     *
     * @param currentConfig The current requested override configuration (it is usually set from
     *                      the last {@link #sendNewConfiguration}) of the display. It is used to
     *                      check if the configuration container has the latest state.
     * @param freezeDisplayToken Freeze the app window token if the orientation is changed.
     * @param forceUpdate See {@link DisplayRotation#updateRotationUnchecked(boolean)}
     */
    Configuration updateOrientation(Configuration currentConfig, IBinder freezeDisplayToken,
            boolean forceUpdate) {
        if (!mDisplayReady) {
            return null;
        }

        Configuration config = null;
        if (updateOrientation(forceUpdate)) {
            // If we changed the orientation but mOrientationChangeComplete is already true,
            // we used seamless rotation, and we don't need to freeze the screen.
            if (freezeDisplayToken != null && !mWmService.mRoot.mOrientationChangeComplete) {
                final ActivityRecord activity = getActivityRecord(freezeDisplayToken);
                if (activity != null) {
                    activity.startFreezingScreen();
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
                mDisplayRotation.prepareNormalRotationAnimation();
                config = new Configuration(mTmpConfiguration);
            }
        }

        return config;
    }

    private boolean updateOrientation(boolean forceUpdate) {
        final int orientation = getOrientation();
        // The last orientation source is valid only after getOrientation.
        final WindowContainer orientationSource = getLastOrientationSource();
        final ActivityRecord r =
                orientationSource != null ? orientationSource.asActivityRecord() : null;
        // Currently there is no use case from non-activity.
        if (r != null && handleTopActivityLaunchingInDifferentOrientation(r)) {
            mFixedRotationLaunchingApp = r;
            // Display orientation should be deferred until the top fixed rotation is finished.
            return false;
        }
        return mDisplayRotation.updateOrientation(orientation, forceUpdate);
    }

    /** @return a valid rotation if the activity can use different orientation than the display. */
    @Surface.Rotation
    private int rotationForActivityInDifferentOrientation(@NonNull ActivityRecord r) {
        if (!mWmService.mIsFixedRotationTransformEnabled) {
            return NO_ROTATION;
        }
        if (r.inMultiWindowMode()
                || r.getRequestedConfigurationOrientation() == getConfiguration().orientation) {
            return NO_ROTATION;
        }
        final int currentRotation = getRotation();
        final int rotation = mDisplayRotation.rotationForOrientation(r.getRequestedOrientation(),
                currentRotation);
        if (rotation == currentRotation) {
            return NO_ROTATION;
        }
        return rotation;
    }

    /**
     * We need to keep display rotation fixed for a while when the activity in different orientation
     * is launching until the launch animation is done to avoid showing the previous activity
     * inadvertently in a wrong orientation.
     *
     * @return {@code true} if the fixed rotation is started.
     */
    private boolean handleTopActivityLaunchingInDifferentOrientation(@NonNull ActivityRecord r) {
        if (!mWmService.mIsFixedRotationTransformEnabled) {
            return false;
        }
        if (r.isFinishingFixedRotationTransform()) {
            return false;
        }
        if (r.hasFixedRotationTransform()) {
            // It has been set and not yet finished.
            return true;
        }
        if (!mAppTransition.isTransitionSet()) {
            // Apply normal rotation animation in case of the activity set different requested
            // orientation without activity switch.
            return false;
        }
        if (!mOpeningApps.contains(r)
                // Without screen rotation, the rotation behavior of non-top visible activities is
                // undefined. So the fixed rotated activity needs to cover the screen.
                && r.findMainWindow() != mDisplayPolicy.getTopFullscreenOpaqueWindow()) {
            return false;
        }
        final int rotation = rotationForActivityInDifferentOrientation(r);
        if (rotation == NO_ROTATION) {
            return false;
        }
        if (!r.getParent().matchParentBounds()) {
            // Because the fixed rotated configuration applies to activity directly, if its parent
            // has it own policy for bounds, the activity bounds based on parent is unknown.
            return false;
        }

        startFixedRotationTransform(r, rotation);
        mAppTransition.registerListenerLocked(new WindowManagerInternal.AppTransitionListener() {
            void done() {
                r.finishFixedRotationTransform();
                mAppTransition.unregisterListener(this);
            }

            @Override
            public void onAppTransitionFinishedLocked(IBinder token) {
                if (token == r.token) {
                    done();
                }
            }

            @Override
            public void onAppTransitionCancelledLocked(int transit) {
                done();
            }

            @Override
            public void onAppTransitionTimeoutLocked() {
                done();
            }
        });
        return true;
    }

    /** @return {@code true} if the display orientation will be changed. */
    boolean continueUpdateOrientationForDiffOrienLaunchingApp(WindowToken token) {
        if (token != mFixedRotationLaunchingApp) {
            return false;
        }
        // Update directly because the app which will change the orientation of display is ready.
        if (mDisplayRotation.updateOrientation(getOrientation(), false /* forceUpdate */)) {
            sendNewConfiguration();
            return true;
        }
        return false;
    }

    private void startFixedRotationTransform(WindowToken token, int rotation) {
        mTmpConfiguration.unset();
        final DisplayInfo info = computeScreenConfiguration(mTmpConfiguration, rotation);
        final WmDisplayCutout cutout = calculateDisplayCutoutForRotation(rotation);
        final DisplayFrames displayFrames = new DisplayFrames(mDisplayId, info, cutout);
        token.applyFixedRotationTransform(info, displayFrames, mTmpConfiguration);
    }

    /**
     * If the provided {@link ActivityRecord} can be displayed in an orientation different from the
     * display's, it will be rotated to match its requested orientation.
     *
     * @see #rotationForActivityInDifferentOrientation(ActivityRecord).
     * @see WindowToken#applyFixedRotationTransform(DisplayInfo, DisplayFrames, Configuration)
     */
    void rotateInDifferentOrientationIfNeeded(ActivityRecord activityRecord) {
        int rotation = rotationForActivityInDifferentOrientation(activityRecord);
        if (rotation != NO_ROTATION) {
            startFixedRotationTransform(activityRecord, rotation);
        }
    }

    /**
     * Update rotation of the display.
     *
     * @return {@code true} if the rotation has been changed.  In this case YOU MUST CALL
     *         {@link #sendNewConfiguration} TO UNFREEZE THE SCREEN.
     */
    boolean updateRotationUnchecked() {
        return mDisplayRotation.updateRotationUnchecked(false /* forceUpdate */);
    }

    /**
     * Applies the rotation transaction. This must be called after {@link #updateRotationUnchecked}
     * (if it returned {@code true}) to actually finish the rotation.
     *
     * @param oldRotation the rotation we are coming from.
     * @param rotation the rotation to apply.
     */
    private void applyRotation(final int oldRotation, final int rotation) {
        mDisplayRotation.applyCurrentRotation(rotation);
        final boolean rotateSeamlessly = mDisplayRotation.isRotatingSeamlessly();
        final Transaction transaction = getPendingTransaction();
        ScreenRotationAnimation screenRotationAnimation = rotateSeamlessly
                ? null : getRotationAnimation();
        // We need to update our screen size information to match the new rotation. If the rotation
        // has actually changed then this method will return true and, according to the comment at
        // the top of the method, the caller is obligated to call computeNewConfigurationLocked().
        // By updating the Display info here it will be available to
        // #computeScreenConfiguration() later.
        updateDisplayAndOrientation(getConfiguration().uiMode, null /* outConfig */);

        // NOTE: We disable the rotation in the emulator because
        //       it doesn't support hardware OpenGL emulation yet.
        if (screenRotationAnimation != null && screenRotationAnimation.hasScreenshot()) {
            screenRotationAnimation.setRotation(transaction, rotation);
        }

        forAllWindows(w -> {
            w.seamlesslyRotateIfAllowed(transaction, oldRotation, rotation, rotateSeamlessly);
        }, true /* traverseTopToBottom */);

        mWmService.mDisplayManagerInternal.performTraversal(transaction);
        scheduleAnimation();

        forAllWindows(w -> {
            if (w.mHasSurface && !rotateSeamlessly) {
                ProtoLog.v(WM_DEBUG_ORIENTATION, "Set mOrientationChanging of %s", w);
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

        final int shortSizeDp = shortSize * DENSITY_DEFAULT / mBaseDisplayDensity;
        final int longSizeDp = longSize * DENSITY_DEFAULT / mBaseDisplayDensity;

        mDisplayPolicy.updateConfigurationAndScreenSizeDependentBehaviors();
        mDisplayRotation.configure(width, height, shortSizeDp, longSizeDp);

        mDisplayFrames.onDisplayInfoUpdated(mDisplayInfo,
                calculateDisplayCutoutForRotation(mDisplayInfo.rotation));

        // Not much of use to rotate the display for apps since it's close to square.
        mIgnoreRotationForApps = isNonDecorDisplayCloseToSquare(Surface.ROTATION_0, width, height);
    }

    /** @return {@code true} if the orientation requested from application will be ignored. */
    boolean ignoreRotationForApps() {
        return mIgnoreRotationForApps;
    }

    private boolean isNonDecorDisplayCloseToSquare(int rotation, int width, int height) {
        final DisplayCutout displayCutout =
                calculateDisplayCutoutForRotation(rotation).getDisplayCutout();
        final int uiMode = mWmService.mPolicy.getUiMode();
        final int w = mDisplayPolicy.getNonDecorDisplayWidth(
                width, height, rotation, uiMode, displayCutout);
        final int h = mDisplayPolicy.getNonDecorDisplayHeight(
                width, height, rotation, uiMode, displayCutout);
        final float aspectRatio = Math.max(w, h) / (float) Math.min(w, h);
        return aspectRatio <= mCloseToSquareMaxAspectRatio;
    }

    /**
     * Update {@link #mDisplayInfo} and other internal variables when display is rotated or config
     * changed.
     * Do not call if {@link WindowManagerService#mDisplayReady} == false.
     */
    private DisplayInfo updateDisplayAndOrientation(int uiMode, Configuration outConfig) {
        // Use the effective "visual" dimensions based on current rotation
        final int rotation = getRotation();
        final boolean rotated = (rotation == ROTATION_90 || rotation == ROTATION_270);
        final int dw = rotated ? mBaseDisplayHeight : mBaseDisplayWidth;
        final int dh = rotated ? mBaseDisplayWidth : mBaseDisplayHeight;

        // Update application display metrics.
        final WmDisplayCutout wmDisplayCutout = calculateDisplayCutoutForRotation(rotation);
        final DisplayCutout displayCutout = wmDisplayCutout.getDisplayCutout();

        final int appWidth = mDisplayPolicy.getNonDecorDisplayWidth(dw, dh, rotation, uiMode,
                displayCutout);
        final int appHeight = mDisplayPolicy.getNonDecorDisplayHeight(dw, dh, rotation, uiMode,
                displayCutout);
        mDisplayInfo.rotation = rotation;
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

        computeSizeRangesAndScreenLayout(mDisplayInfo, rotated, uiMode, dw, dh,
                mDisplayMetrics.density, outConfig);

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
        final Insets waterfallInsets =
                RotationUtils.rotateInsets(cutout.getWaterfallInsets(), rotation);
        if (rotation == ROTATION_0) {
            return WmDisplayCutout.computeSafeInsets(
                    cutout, mInitialDisplayWidth, mInitialDisplayHeight);
        }
        final boolean rotated = (rotation == ROTATION_90 || rotation == ROTATION_270);
        final Rect[] newBounds = mRotationUtil.getRotatedBounds(
                cutout.getBoundingRectsAll(),
                rotation, mInitialDisplayWidth, mInitialDisplayHeight);
        return WmDisplayCutout.computeSafeInsets(
                DisplayCutout.fromBoundsAndWaterfall(newBounds, waterfallInsets),
                rotated ? mInitialDisplayHeight : mInitialDisplayWidth,
                rotated ? mInitialDisplayWidth : mInitialDisplayHeight);
    }

    /**
     * Compute display info and configuration according to the given rotation without changing
     * current display.
     */
    DisplayInfo computeScreenConfiguration(Configuration outConfig, int rotation) {
        final boolean rotated = (rotation == ROTATION_90 || rotation == ROTATION_270);
        final int dw = rotated ? mBaseDisplayHeight : mBaseDisplayWidth;
        final int dh = rotated ? mBaseDisplayWidth : mBaseDisplayHeight;
        outConfig.windowConfiguration.getBounds().set(0, 0, dw, dh);

        final int uiMode = getConfiguration().uiMode;
        final DisplayCutout displayCutout =
                calculateDisplayCutoutForRotation(rotation).getDisplayCutout();
        computeScreenAppConfiguration(outConfig, dw, dh, rotation, uiMode, displayCutout);

        final DisplayInfo displayInfo = new DisplayInfo(mDisplayInfo);
        displayInfo.rotation = rotation;
        displayInfo.logicalWidth = dw;
        displayInfo.logicalHeight = dh;
        final Rect appBounds = outConfig.windowConfiguration.getAppBounds();
        displayInfo.appWidth = appBounds.width();
        displayInfo.appHeight = appBounds.height();
        displayInfo.displayCutout = displayCutout.isEmpty() ? null : displayCutout;
        computeSizeRangesAndScreenLayout(displayInfo, rotated, uiMode, dw, dh,
                mDisplayMetrics.density, outConfig);
        return displayInfo;
    }

    /** Compute configuration related to application without changing current display. */
    private void computeScreenAppConfiguration(Configuration outConfig, int dw, int dh,
            int rotation, int uiMode, DisplayCutout displayCutout) {
        final int appWidth = mDisplayPolicy.getNonDecorDisplayWidth(dw, dh, rotation, uiMode,
                displayCutout);
        final int appHeight = mDisplayPolicy.getNonDecorDisplayHeight(dw, dh, rotation, uiMode,
                displayCutout);
        mDisplayPolicy.getNonDecorInsetsLw(rotation, dw, dh, displayCutout, mTmpRect);
        final int leftInset = mTmpRect.left;
        final int topInset = mTmpRect.top;
        // AppBounds at the root level should mirror the app screen size.
        outConfig.windowConfiguration.setAppBounds(leftInset /* left */, topInset /* top */,
                leftInset + appWidth /* right */, topInset + appHeight /* bottom */);
        outConfig.windowConfiguration.setRotation(rotation);
        outConfig.orientation = (dw <= dh) ? ORIENTATION_PORTRAIT : ORIENTATION_LANDSCAPE;

        final float density = mDisplayMetrics.density;
        outConfig.screenWidthDp = (int) (mDisplayPolicy.getConfigDisplayWidth(dw, dh, rotation,
                uiMode, displayCutout) / density);
        outConfig.screenHeightDp = (int) (mDisplayPolicy.getConfigDisplayHeight(dw, dh, rotation,
                uiMode, displayCutout) / density);
        outConfig.compatScreenWidthDp = (int) (outConfig.screenWidthDp / mCompatibleScreenScale);
        outConfig.compatScreenHeightDp = (int) (outConfig.screenHeightDp / mCompatibleScreenScale);

        final boolean rotated = (rotation == ROTATION_90 || rotation == ROTATION_270);
        outConfig.compatSmallestScreenWidthDp = computeCompatSmallestWidth(rotated, uiMode, dw,
                dh);
    }

    /**
     * Compute display configuration based on display properties and policy settings.
     * Do not call if mDisplayReady == false.
     */
    void computeScreenConfiguration(Configuration config) {
        final DisplayInfo displayInfo = updateDisplayAndOrientation(config.uiMode, config);
        calculateBounds(displayInfo, mTmpBounds);
        config.windowConfiguration.setBounds(mTmpBounds);
        config.windowConfiguration.setWindowingMode(getWindowingMode());
        config.windowConfiguration.setDisplayWindowingMode(getWindowingMode());

        final int dw = displayInfo.logicalWidth;
        final int dh = displayInfo.logicalHeight;
        computeScreenAppConfiguration(config, dw, dh, displayInfo.rotation, config.uiMode,
                displayInfo.displayCutout);

        config.screenLayout = (config.screenLayout & ~Configuration.SCREENLAYOUT_ROUND_MASK)
                | ((displayInfo.flags & Display.FLAG_ROUND) != 0
                ? Configuration.SCREENLAYOUT_ROUND_YES
                : Configuration.SCREENLAYOUT_ROUND_NO);

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

        mDisplayPolicy.updateConfigurationAndScreenSizeDependentBehaviors();

        // Let the policy update hidden states.
        config.keyboardHidden = Configuration.KEYBOARDHIDDEN_NO;
        config.hardKeyboardHidden = Configuration.HARDKEYBOARDHIDDEN_NO;
        config.navigationHidden = Configuration.NAVIGATIONHIDDEN_NO;
        mWmService.mPolicy.adjustConfigurationLw(config, keyboardPresence, navigationPresence);
    }

    private int computeCompatSmallestWidth(boolean rotated, int uiMode, int dw, int dh) {
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
        int sw = reduceCompatConfigWidthSize(0, Surface.ROTATION_0, uiMode, tmpDm, unrotDw,
                unrotDh);
        sw = reduceCompatConfigWidthSize(sw, Surface.ROTATION_90, uiMode, tmpDm, unrotDh,
                unrotDw);
        sw = reduceCompatConfigWidthSize(sw, Surface.ROTATION_180, uiMode, tmpDm, unrotDw,
                unrotDh);
        sw = reduceCompatConfigWidthSize(sw, Surface.ROTATION_270, uiMode, tmpDm, unrotDh,
                unrotDw);
        return sw;
    }

    private int reduceCompatConfigWidthSize(int curSize, int rotation, int uiMode,
            DisplayMetrics dm, int dw, int dh) {
        final DisplayCutout displayCutout = calculateDisplayCutoutForRotation(
                rotation).getDisplayCutout();
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

        if (outConfig == null) {
            return;
        }
        int sl = Configuration.resetScreenLayout(outConfig.screenLayout);
        sl = reduceConfigLayout(sl, Surface.ROTATION_0, density, unrotDw, unrotDh, uiMode);
        sl = reduceConfigLayout(sl, Surface.ROTATION_90, density, unrotDh, unrotDw, uiMode);
        sl = reduceConfigLayout(sl, Surface.ROTATION_180, density, unrotDw, unrotDh, uiMode);
        sl = reduceConfigLayout(sl, Surface.ROTATION_270, density, unrotDh, unrotDw, uiMode);
        outConfig.smallestScreenWidthDp = (int)(displayInfo.smallestNominalAppWidth / density);
        outConfig.screenLayout = sl;
    }

    private int reduceConfigLayout(int curLayout, int rotation, float density, int dw, int dh,
            int uiMode) {
        // Get the display cutout at this rotation.
        final DisplayCutout displayCutout = calculateDisplayCutoutForRotation(
                rotation).getDisplayCutout();

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

    ActivityStack getRootHomeTask() {
        return mTaskContainers.getRootHomeTask();
    }

    /**
     * Returns the existing home stack or creates and returns a new one if it should exist for the
     * display.
     */
    @Nullable
    ActivityStack getOrCreateRootHomeTask() {
        ActivityStack homeTask = getRootHomeTask();
        if (homeTask == null && supportsSystemDecorations() && !isUntrustedVirtualDisplay()) {
            homeTask = createStack(WINDOWING_MODE_FULLSCREEN, ACTIVITY_TYPE_HOME,
                    false /* onTop */);
        }
        return homeTask;
    }

    /** @return The primary split-screen task, and {@code null} otherwise. */
    @Nullable ActivityStack getRootSplitScreenPrimaryTask() {
        return mTaskContainers.getRootSplitScreenPrimaryTask();
    }

    boolean isSplitScreenModeActivated() {
        Task task = getRootSplitScreenPrimaryTask();
        return task != null && task.hasChild();
    }

    ActivityStack getRootPinnedTask() {
        return mTaskContainers.getRootPinnedTask();
    }

    boolean hasPinnedTask() {
        return mTaskContainers.getRootPinnedTask() != null;
    }

    /**
     * Returns the topmost stack on the display that is compatible with the input windowing mode.
     * Null is no compatible stack on the display.
     */
    ActivityStack getTopStackInWindowingMode(int windowingMode) {
        return getStack(windowingMode, ACTIVITY_TYPE_UNDEFINED);
    }

    /**
     * Returns the topmost stack on the display that is compatible with the input windowing mode and
     * activity type. Null is no compatible stack on the display.
     */
    ActivityStack getStack(int windowingMode, int activityType) {
        return mTaskContainers.getStack(windowingMode, activityType);
    }

    protected int getStackCount() {
        return mTaskContainers.mChildren.size();
    }

    protected ActivityStack getStackAt(int index) {
        return mTaskContainers.mChildren.get(index);
    }

    int getIndexOf(ActivityStack stack) {
        return mTaskContainers.getIndexOf(stack);
    }

    void removeStack(ActivityStack stack) {
        mTaskContainers.removeChild(stack);
    }

    @VisibleForTesting
    WindowList<ActivityStack> getStacks() {
        return mTaskContainers.mChildren;
    }

    @VisibleForTesting
    ActivityStack getTopStack() {
        return mTaskContainers.getTopStack();
    }

    ArrayList<Task> getVisibleTasks() {
        return mTaskContainers.getVisibleTasks();
    }

    SurfaceControl getSplitScreenDividerAnchor() {
        return mTaskContainers.getSplitScreenDividerAnchor();
    }

    void onStackWindowingModeChanged(ActivityStack stack) {
        mTaskContainers.onStackWindowingModeChanged(stack);
    }

    /**
     * The value is only valid in the scope {@link #onRequestedOverrideConfigurationChanged} of the
     * changing hierarchy and the {@link #onConfigurationChanged} of its children.
     *
     * @return The current changes ({@link android.content.pm.ActivityInfo.Config}) of requested
     *         override configuration.
     */
    int getCurrentOverrideConfigurationChanges() {
        return mCurrentOverrideConfigurationChanges;
    }

    @Override
    public void onConfigurationChanged(Configuration newParentConfig) {
        // update resources before cascade so that docked/pinned stacks use the correct info
        preOnConfigurationChanged();
        final int lastOrientation = getConfiguration().orientation;
        super.onConfigurationChanged(newParentConfig);
        if (mDisplayPolicy != null) {
            mDisplayPolicy.onConfigurationChanged();
        }

        if (lastOrientation != getConfiguration().orientation) {
            getMetricsLogger().write(
                    new LogMaker(MetricsEvent.ACTION_PHONE_ORIENTATION_CHANGED)
                            .setSubtype(getConfiguration().orientation)
                            .addTaggedData(MetricsEvent.FIELD_DISPLAY_ID, getDisplayId()));
        }

        if (mPinnedStackControllerLocked != null) {
            mPinnedStackControllerLocked.onDisplayInfoChanged(getDisplayInfo());
        }
    }

    /**
     * Updates the resources used by docked/pinned controllers. This needs to be called at the
     * beginning of a configuration update cascade since the metrics from these resources are used
     * for bounds calculations.
     */
    void preOnConfigurationChanged() {
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

    boolean forAllImeWindows(ToBooleanFunction<WindowState> callback, boolean traverseTopToBottom) {
        return mImeWindowsContainers.forAllWindowForce(callback, traverseTopToBottom);
    }

    /**
     * In the general case, the orientation is computed from the above app windows first. If none of
     * the above app windows specify orientation, the orientation is computed from the child window
     * container, e.g. {@link ActivityRecord#getOrientation(int)}.
     */
    @ScreenOrientation
    @Override
    int getOrientation() {
        mLastOrientationSource = null;

        if (mIgnoreRotationForApps) {
            return SCREEN_ORIENTATION_USER;
        }

        if (mWmService.mDisplayFrozen) {
            if (mWmService.mPolicy.isKeyguardLocked()) {
                // Use the last orientation the while the display is frozen with the keyguard
                // locked. This could be the keyguard forced orientation or from a SHOW_WHEN_LOCKED
                // window. We don't want to check the show when locked window directly though as
                // things aren't stable while the display is frozen, for example the window could be
                // momentarily unavailable due to activity relaunch.
                ProtoLog.v(WM_DEBUG_ORIENTATION,
                        "Display id=%d is frozen while keyguard locked, return %d",
                        mDisplayId, getLastOrientation());
                return getLastOrientation();
            }
        }
        final int rootOrientation = mRootDisplayArea.getOrientation();
        mLastOrientationSource = mRootDisplayArea.getLastOrientationSource();
        return rootOrientation;
    }

    void updateDisplayInfo() {
        // Check if display metrics changed and update base values if needed.
        updateBaseDisplayMetricsIfNeeded();

        mDisplay.getDisplayInfo(mDisplayInfo);
        mDisplay.getMetrics(mDisplayMetrics);

        onDisplayChanged(this);
    }

    @Override
    void onDisplayChanged(DisplayContent dc) {
        super.onDisplayChanged(dc);
        updateSystemGestureExclusionLimit();
    }

    void updateSystemGestureExclusionLimit() {
        mSystemGestureExclusionLimit = mWmService.mConstants.mSystemGestureExclusionLimitDp
                * mDisplayMetrics.densityDpi / DENSITY_DEFAULT;
        updateSystemGestureExclusion();
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
            reconfigureDisplayLocked();
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
        final boolean updateCurrent = userId == UserHandle.USER_CURRENT;
        if (mWmService.mCurrentUserId == userId || updateCurrent) {
            mBaseDisplayDensity = density;
            reconfigureDisplayLocked();
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
        reconfigureDisplayLocked();

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
        reconfigureDisplayLocked();

        if (clear) {
            width = height = 0;
        }
        mWmService.mDisplayWindowSettings.setForcedSize(this, width, height);
    }

    void getStableRect(Rect out) {
        out.set(mDisplayFrames.mStable);
    }

    void setStackOnDisplay(ActivityStack stack, int position) {
        if (DEBUG_STACK) Slog.d(TAG_WM, "Set stack=" + stack + " on displayId=" + mDisplayId);
        mTaskContainers.addChild(stack, position);
    }

    void moveStackToDisplay(ActivityStack stack, boolean onTop) {
        stack.reparent(mTaskContainers, onTop ? POSITION_TOP: POSITION_BOTTOM);
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

    void positionDisplayAt(int position, boolean includingParents) {
        getParent().positionChildAt(position, this, includingParents);
    }

    @Override
    void positionChildAt(int position, DisplayChildWindowContainer child, boolean includingParents) {
        // Children of the display are statically ordered, so the real intention here is to perform
        // the operation on the display and not the static direct children.
        positionDisplayAt(position, includingParents);
    }

    void positionStackAt(int position, ActivityStack child, boolean includingParents) {
        mTaskContainers.positionChildAt(position, child, includingParents);
        layoutAndAssignWindowLayersIfNeeded();
    }

    /**
     * Returns true if the input point is within an app window.
     */
    boolean pointWithinAppWindow(int x, int y) {
        final int[] targetWindowType = {-1};
        final PooledConsumer fn = PooledLambda.obtainConsumer((w, nonArg) -> {
            if (targetWindowType[0] != -1) {
                return;
            }

            if (w.isOnScreen() && w.isVisibleLw() && w.getFrameLw().contains(x, y)) {
                targetWindowType[0] = w.mAttrs.type;
                return;
            }
        }, PooledLambda.__(WindowState.class), mTmpRect);
        forAllWindows(fn, true /* traverseTopToBottom */);
        fn.recycle();
        return FIRST_APPLICATION_WINDOW <= targetWindowType[0]
                && targetWindowType[0] <= LAST_APPLICATION_WINDOW;
    }

    /**
     * Find the task whose outside touch area (for resizing) (x, y) falls within.
     * Returns null if the touch doesn't fall into a resizing area.
     */
    Task findTaskForResizePoint(int x, int y) {
        final int delta = dipToPixel(RESIZE_HANDLE_WIDTH_IN_DP, mDisplayMetrics);
        return mTmpTaskForResizePointSearchResult.process(mTaskContainers, x, y, delta);
    }

    void updateTouchExcludeRegion() {
        final Task focusedTask = (mFocusedApp != null ? mFocusedApp.getTask() : null);
        if (focusedTask == null) {
            mTouchExcludeRegion.setEmpty();
        } else {
            mTouchExcludeRegion.set(mBaseDisplayRect);
            final int delta = dipToPixel(RESIZE_HANDLE_WIDTH_IN_DP, mDisplayMetrics);
            mTmpRect.setEmpty();
            mTmpRect2.setEmpty();

            final PooledConsumer c = PooledLambda.obtainConsumer(
                    DisplayContent::processTaskForTouchExcludeRegion, this,
                    PooledLambda.__(Task.class), focusedTask, delta);
            mTaskContainers.forAllTasks(c);
            c.recycle();

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
        if (mDisplayId == DEFAULT_DISPLAY && isSplitScreenModeActivated()) {
            mDividerControllerLocked.getTouchRegion(mTmpRect);
            mTmpRegion.set(mTmpRect);
            mTouchExcludeRegion.op(mTmpRegion, Op.UNION);
        }
        mTapDetector.setTouchExcludeRegion(mTouchExcludeRegion);
    }

    private void processTaskForTouchExcludeRegion(Task task, Task focusedTask, int delta) {
        final ActivityRecord topVisibleActivity = task.getTopVisibleActivity();

        if (topVisibleActivity == null || !topVisibleActivity.hasContentToDisplay()) {
            return;
        }

        // Exclusion region is the region that TapDetector doesn't care about.
        // Here we want to remove all non-focused tasks from the exclusion region.
        // We also remove the outside touch area for resizing for all freeform
        // tasks (including the focused).
        // We save the focused task region once we find it, and add it back at the end.
        // If the task is home stack and it is resizable and visible (top of its root task), we want
        // to exclude the docked stack from touch so we need the entire screen area and not just a
        // small portion which the home stack currently is resized to.
        if (task.isActivityTypeHome() && task.isVisible() && task.isResizeable()) {
            mDisplayContent.getBounds(mTmpRect);
        } else {
            task.getDimBounds(mTmpRect);
        }

        if (task == focusedTask) {
            // Add the focused task rect back into the exclude region once we are done
            // processing stacks.
            // NOTE: this *looks* like a no-op, but this usage of mTmpRect2 is expected by
            //       updateTouchExcludeRegion.
            mTmpRect2.set(mTmpRect);
        }

        final boolean isFreeformed = task.inFreeformWindowingMode();
        if (task != focusedTask || isFreeformed) {
            if (isFreeformed) {
                // If the task is freeformed, enlarge the area to account for outside
                // touch area for resize.
                mTmpRect.inset(-delta, -delta);
                // Intersect with display content rect. If we have system decor (status bar/
                // navigation bar), we want to exclude that from the tap detection.
                // Otherwise, if the app is partially placed under some system button (eg.
                // Recents, Home), pressing that button would cause a full series of
                // unwanted transfer focus/resume/pause, before we could go home.
                mTmpRect.intersect(mDisplayFrames.mContent);
            }
            mTouchExcludeRegion.op(mTmpRect, Region.Op.DIFFERENCE);
        }
    }

    /**
     * Union the region with all the tap exclude region provided by windows on this display.
     *
     * @param inOutRegion The region to be amended.
     */
    private void amendWindowTapExcludeRegion(Region inOutRegion) {
        final Region region = Region.obtain();
        for (int i = mTapExcludeProvidingWindows.size() - 1; i >= 0; i--) {
            final WindowState win = mTapExcludeProvidingWindows.valueAt(i);
            win.getTapExcludeRegion(region);
            inOutRegion.op(region, Op.UNION);
        }
        region.recycle();
    }

    @Override
    void switchUser(int userId) {
        super.switchUser(userId);
        mWmService.mWindowsChanged = true;
        mDisplayPolicy.switchUser();
    }

    @Override
    void removeIfPossible() {
        if (isAnimating(TRANSITION | PARENTS)) {
            mDeferredRemoval = true;
            return;
        }
        removeImmediately();
    }

    @Override
    void removeImmediately() {
        mRemovingDisplay = true;
        try {
            if (mParentWindow != null) {
                mParentWindow.removeEmbeddedDisplayContent(this);
            }
            // Clear all transitions & screen frozen states when removing display.
            mOpeningApps.clear();
            mClosingApps.clear();
            mChangingContainers.clear();
            mUnknownAppVisibilityController.clear();
            mAppTransition.removeAppTransitionTimeoutCallbacks();
            handleAnimatingStoppedAndTransition();
            mWmService.stopFreezingDisplayLocked();
            super.removeImmediately();
            if (DEBUG_DISPLAY) Slog.v(TAG_WM, "Removing display=" + this);
            mPointerEventDispatcher.dispose();
            setRotationAnimation(null);
            mWmService.mAnimator.removeDisplayLocked(mDisplayId);
            mInputMonitor.onDisplayRemoved();
            mWmService.mDisplayNotificationController.dispatchDisplayRemoved(this);
        } finally {
            mDisplayReady = false;
            mRemovingDisplay = false;
        }

        // Apply the pending transaction here since we may not be able to reach the DisplayContent
        // on the next traversal if it's removed from RootWindowContainer child list.
        getPendingTransaction().apply();
        mWmService.mWindowPlacerLocked.requestTraversal();
    }

    /** Returns true if a removal action is still being deferred. */
    @Override
    boolean checkCompleteDeferredRemoval() {
        boolean stillDeferringRemoval = false;

        for (int i = getChildCount() - 1; i >= 0; --i) {
            final DisplayChildWindowContainer child = getChildAt(i);
            stillDeferringRemoval |= child.checkCompleteDeferredRemoval();
            if (getChildCount() == 0) {
                // If this display is pending to be removed because it contains an activity with
                // {@link ActivityRecord#mIsExiting} is true, this display may be removed when
                // completing the removal of the last activity from
                // {@link ActivityRecord#checkCompleteDeferredRemoval}.
                return false;
            }
        }

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

    void adjustForImeIfNeeded() {
        final WindowState imeWin = mInputMethodWindow;
        final boolean imeVisible = imeWin != null && imeWin.isVisibleLw()
                && imeWin.isDisplayedLw();
        final int imeHeight = mDisplayFrames.getInputMethodWindowVisibleHeight();
        mPinnedStackControllerLocked.setAdjustedForIme(imeVisible, imeHeight);
    }

    void prepareFreezingTaskBounds() {
        for (int stackNdx = mTaskContainers.getChildCount() - 1; stackNdx >= 0; --stackNdx) {
            final ActivityStack stack = mTaskContainers.getChildAt(stackNdx);
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

    public void setRotationAnimation(ScreenRotationAnimation screenRotationAnimation) {
        if (mScreenRotationAnimation != null) {
            mScreenRotationAnimation.kill();
        }
        mScreenRotationAnimation = screenRotationAnimation;
    }

    public ScreenRotationAnimation getRotationAnimation() {
        return mScreenRotationAnimation;
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

    public void dumpDebug(ProtoOutputStream proto, long fieldId,
            @WindowTraceLogLevel int logLevel) {
        // Critical log level logs only visible elements to mitigate performance overheard
        if (logLevel == WindowTraceLogLevel.CRITICAL && !isVisible()) {
            return;
        }

        final long token = proto.start(fieldId);
        super.dumpDebug(proto, WINDOW_CONTAINER, logLevel);

        proto.write(ID, mDisplayId);
        mRootDisplayArea.dumpDebug(proto, ROOT_DISPLAY_AREA, logLevel);
        for (int i = mOverlayContainers.getChildCount() - 1; i >= 0; --i) {
            final WindowToken windowToken = mOverlayContainers.getChildAt(i);
            windowToken.dumpDebug(proto, OVERLAY_WINDOWS, logLevel);
        }
        proto.write(DPI, mBaseDisplayDensity);
        mDisplayInfo.dumpDebug(proto, DISPLAY_INFO);
        proto.write(ROTATION, getRotation());
        final ScreenRotationAnimation screenRotationAnimation = getRotationAnimation();
        if (screenRotationAnimation != null) {
            screenRotationAnimation.dumpDebug(proto, SCREEN_ROTATION_ANIMATION);
        }
        mDisplayFrames.dumpDebug(proto, DISPLAY_FRAMES);
        mAppTransition.dumpDebug(proto, APP_TRANSITION);
        if (mFocusedApp != null) {
            mFocusedApp.writeNameToProto(proto, FOCUSED_APP);
        }
        for (int i = mOpeningApps.size() - 1; i >= 0; i--) {
            mOpeningApps.valueAt(i).writeIdentifierToProto(proto, OPENING_APPS);
        }
        for (int i = mClosingApps.size() - 1; i >= 0; i--) {
            mClosingApps.valueAt(i).writeIdentifierToProto(proto, CLOSING_APPS);
        }

        proto.write(SINGLE_TASK_INSTANCE, mSingleTaskInstance);
        final ActivityStack focusedStack = getFocusedStack();
        if (focusedStack != null) {
            proto.write(FOCUSED_ROOT_TASK_ID, focusedStack.getRootTaskId());
            final ActivityRecord focusedActivity = focusedStack.getDisplay().getResumedActivity();
            if (focusedActivity != null) {
                focusedActivity.writeIdentifierToProto(proto, RESUMED_ACTIVITY);
            }
        } else {
            proto.write(FOCUSED_ROOT_TASK_ID, INVALID_TASK_ID);
        }
        proto.write(DISPLAY_READY, isReady());

        proto.end(token);
    }

    @Override
    long getProtoFieldId() {
        return DISPLAY_CONTENT;
    }

    @Override
    public void dump(PrintWriter pw, String prefix, boolean dumpAll) {
        super.dump(pw, prefix, dumpAll);
        pw.print(prefix);
        pw.println("Display: mDisplayId=" + mDisplayId + " stacks=" + getStackCount() + (
                mSingleTaskInstance ? " mSingleTaskInstance" : ""));
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

        pw.print("  mCurrentFocus="); pw.println(mCurrentFocus);
        if (mLastFocus != mCurrentFocus) {
            pw.print("  mLastFocus="); pw.println(mLastFocus);
        }
        if (mPreferredTopFocusableStack != null) {
            pw.println(prefix + "mPreferredTopFocusableStack=" + mPreferredTopFocusableStack);
        }
        if (mLastFocusedStack != null) {
            pw.println(prefix + "mLastFocusedStack=" + mLastFocusedStack);
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
        pw.print("mSystemGestureExclusion=");
        if (mSystemGestureExclusionListeners.getRegisteredCallbackCount() > 0) {
            pw.println(mSystemGestureExclusion);
        } else {
            pw.println("<no lstnrs>");
        }

        pw.println();
        pw.println(prefix + "Application tokens in top down Z order:");
        for (int stackNdx = mTaskContainers.getChildCount() - 1; stackNdx >= 0; --stackNdx) {
            final ActivityStack stack = mTaskContainers.getChildAt(stackNdx);
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

        final ScreenRotationAnimation rotationAnimation = getRotationAnimation();
        if (rotationAnimation != null) {
            pw.print(subPrefix);
            pw.println("  mScreenRotationAnimation:");
            rotationAnimation.printTo("  ", pw);
        } else if (dumpAll) {
            pw.print(subPrefix);
            pw.println("  no ScreenRotationAnimation ");
        }

        pw.println();

        // Dump stack references
        final ActivityStack homeStack = getRootHomeTask();
        if (homeStack != null) {
            pw.println(prefix + "homeStack=" + homeStack.getName());
        }
        final ActivityStack pinnedStack = getRootPinnedTask();
        if (pinnedStack != null) {
            pw.println(prefix + "pinnedStack=" + pinnedStack.getName());
        }
        final ActivityStack splitScreenPrimaryStack = getRootSplitScreenPrimaryTask();
        if (splitScreenPrimaryStack != null) {
            pw.println(prefix + "splitScreenPrimaryStack=" + splitScreenPrimaryStack.getName());
        }
        final ActivityStack recentsStack =
                getStack(WINDOWING_MODE_UNDEFINED, ACTIVITY_TYPE_RECENTS);
        if (recentsStack != null) {
            pw.println(prefix + "recentsStack=" + recentsStack.getName());
        }
        final ActivityStack dreamStack =
                getStack(WINDOWING_MODE_UNDEFINED, ACTIVITY_TYPE_DREAM);
        if (dreamStack != null) {
            pw.println(prefix + "dreamStack=" + dreamStack.getName());
        }

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
        final ActivityStack stack = getTopStackInWindowingMode(windowingMode);
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

    /**
     * Looking for the focused window on this display if the top focused display hasn't been
     * found yet (topFocusedDisplayId is INVALID_DISPLAY) or per-display focused was allowed.
     *
     * @param topFocusedDisplayId Id of the top focused display.
     * @return The focused window or null if there isn't any or no need to seek.
     */
    WindowState findFocusedWindowIfNeeded(int topFocusedDisplayId) {
        return (mWmService.mPerDisplayFocusEnabled || topFocusedDisplayId == INVALID_DISPLAY)
                ? findFocusedWindow() : null;
    }

    WindowState findFocusedWindow() {
        mTmpWindow = null;

        forAllWindows(mFindFocusedWindow, true /* traverseTopToBottom */);

        if (mTmpWindow == null) {
            ProtoLog.v(WM_DEBUG_FOCUS_LIGHT, "findFocusedWindow: No focusable windows.");
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
     * @param topFocusedDisplayId Display id of current top focused display.
     * @return {@code true} if the focused window has changed.
     */
    boolean updateFocusedWindowLocked(int mode, boolean updateInputWindows,
            int topFocusedDisplayId) {
        WindowState newFocus = findFocusedWindowIfNeeded(topFocusedDisplayId);
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
            newFocus = findFocusedWindowIfNeeded(topFocusedDisplayId);
        }
        if (mCurrentFocus != newFocus) {
            mWmService.mH.obtainMessage(REPORT_FOCUS_CHANGE, this).sendToTarget();
        }

        ProtoLog.v(WM_DEBUG_FOCUS_LIGHT, "Changing focus from %s to %s displayId=%d Callers=%s",
                mCurrentFocus, newFocus, getDisplayId(), Debug.getCallers(4));
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

        onWindowFocusChanged(oldFocus, newFocus);

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
                mWmService.mRoot.performSurfacePlacement();
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

    private static void onWindowFocusChanged(WindowState oldFocus, WindowState newFocus) {
        final Task focusedTask = newFocus != null ? newFocus.getTask() : null;
        final Task unfocusedTask = oldFocus != null ? oldFocus.getTask() : null;
        if (focusedTask == unfocusedTask) {
            return;
        }
        if (focusedTask != null) {
            focusedTask.onWindowFocusChanged(true /* hasFocus */);
        }
        if (unfocusedTask != null) {
            unfocusedTask.onWindowFocusChanged(false /* hasFocus */);
        }
    }

    /**
     * Set the new focused app to this display.
     *
     * @param newFocus the new focused {@link ActivityRecord}.
     * @return true if the focused app is changed.
     */
    boolean setFocusedApp(ActivityRecord newFocus) {
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
        Trace.traceBegin(TRACE_TAG_WINDOW_MANAGER, "assignWindowLayers");
        assignChildLayers(getPendingTransaction());
        if (setLayoutNeeded) {
            setLayoutNeeded();
        }

        // We accumlate the layer changes in-to "getPendingTransaction()" but we defer
        // the application of this transaction until the animation pass triggers
        // prepareSurfaces. This allows us to synchronize Z-ordering changes with
        // the hiding and showing of surfaces.
        scheduleAnimation();
        Trace.traceEnd(TRACE_TAG_WINDOW_MANAGER);
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
            } else if (w.mActivityRecord != null && !w.mActivityRecord.isClientVisible()) {
                Slog.w(TAG_WM, "LEAKED SURFACE (app token hidden): "
                        + w + " surface=" + wsa.mSurfaceController
                        + " token=" + w.mActivityRecord);
                ProtoLog.i(WM_SHOW_TRANSACTIONS, "SURFACE LEAK DESTROY: %s", w);
                wsa.destroySurface();
                mTmpWindow = w;
            }
        }, false /* traverseTopToBottom */);

        return mTmpWindow != null;
    }

    boolean hasAlertWindowSurfaces() {
        for (int i = mWmService.mSessions.size() - 1; i >= 0; --i) {
            if (mWmService.mSessions.valueAt(i).hasAlertWindowSurfaces(this)) {
                return true;
            }
        }
        return false;
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
        mInsetsStateController.getSourceProvider(ITYPE_IME).setWindow(win,
                null /* frameProvider */, null /* imeFrameProvider */);
        computeImeTarget(true /* updateImeTarget */);
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
            final ActivityRecord activity = target.mActivityRecord;
            if (activity != null) {
                final WindowState betterTarget = activity.getImeTargetBelowWindow(target);
                if (betterTarget != null) {
                    target = betterTarget;
                }
            }
        }

        if (DEBUG_INPUT_METHOD && updateImeTarget) Slog.v(TAG_WM,
                "Proposed new IME target: " + target + " for display: " + getDisplayId());

        // Now, a special case -- if the last target's window is in the process of exiting, but
        // not removed, keep on the last target to avoid IME flicker.
        if (curTarget != null && !curTarget.mRemoved && curTarget.isDisplayedLw()
                && curTarget.isClosing()) {
            if (DEBUG_INPUT_METHOD) Slog.v(TAG_WM, "Not changing target till current window is"
                    + " closing and not removed");
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
            ActivityRecord activity = curTarget == null ? null : curTarget.mActivityRecord;
            if (activity != null) {

                // Now some fun for dealing with window animations that modify the Z order. We need
                // to look at all windows below the current target that are in this app, finding the
                // highest visible one in layering.
                WindowState highestTarget = null;
                if (activity.isAnimating(PARENTS | TRANSITION)) {
                    highestTarget = activity.getHighestAnimLayerWindow(curTarget);
                }

                if (highestTarget != null) {
                    if (DEBUG_INPUT_METHOD) {
                        Slog.v(TAG_WM, mAppTransition + " " + highestTarget + " animating="
                                + highestTarget.isAnimating(TRANSITION | PARENTS));
                    }

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
    void computeImeTargetIfNeeded(ActivityRecord candidate) {
        if (mInputMethodTarget != null && mInputMethodTarget.mActivityRecord == candidate) {
            computeImeTarget(true /* updateImeTarget */);
        }
    }

    boolean isImeAttachedToApp() {
        return (mInputMethodTarget != null && mInputMethodTarget.mActivityRecord != null
                && mInputMethodTarget.getWindowingMode() == WINDOWING_MODE_FULLSCREEN
                // An activity with override bounds should be letterboxed inside its parent bounds,
                // so it doesn't fill the screen.
                && mInputMethodTarget.mActivityRecord.matchParentBounds());
    }

    /**
     * Get IME target that should host IME when this display that is reparented to another
     * WindowState.
     * IME is never displayed in a child display.
     * Use {@link WindowState#getImeControlTarget()} when IME target window
     * which originally called
     * {@link android.view.inputmethod.InputMethodManager#showSoftInput(View, int)} is known.
     *
     * @return {@link WindowState} of host that controls IME.
     *         {@code null} when {@param dc} is not a virtual display.
     * @see DisplayContent#reparent
     */
    @Nullable
    WindowState getImeControlTarget() {
        WindowState imeTarget = mInputMethodTarget;
        if (imeTarget != null) {
            return imeTarget.getImeControlTarget();
        }

        return getInsetsStateController().getImeSourceProvider().getControlTarget().getWindow();
    }

    /**
     * Finds the window which can host IME if IME target cannot host it.
     * e.g. IME target cannot host IME when it's display has a parent display OR when display
     * doesn't support IME/system decorations.
     *
     * @param target current IME target.
     * @return {@link WindowState} that can host IME.
     * @see DisplayContent#getImeControlTarget()
     */
    WindowState getImeHostOrFallback(WindowState target) {
        if (target != null && target.getDisplayContent().canShowIme()) {
            return target;
        }
        return getImeFallback();
    }

    WindowState getImeFallback() {

        // host is in non-default display that doesn't support system decor, default to
        // default display's StatusBar to control IME.
        // TODO: (b/148234093)find a better host OR control IME animation/visibility directly
        //  because it won't work when statusbar isn't available.
        return mWmService.getDefaultDisplayContentLocked().getDisplayPolicy().getStatusBar();
    }

    boolean canShowIme() {
        if (isUntrustedVirtualDisplay()) {
            return false;
        }
        return mWmService.mDisplayWindowSettings.shouldShowImeLocked(this)
                || mWmService.mForceDesktopModeOnExternalDisplays;
    }

    private void setInputMethodTarget(WindowState target, boolean targetWaitingAnim) {
        // Always update control target. This is needed to handle rotation.
        updateImeControlTarget(target);
        if (target == mInputMethodTarget && mInputMethodTargetWaitingAnim == targetWaitingAnim) {
            return;
        }

        mInputMethodTarget = target;
        mInputMethodTargetWaitingAnim = targetWaitingAnim;
        assignWindowLayers(false /* setLayoutNeeded */);
        updateImeParent();
    }

    /**
     * IME control target is the window that controls the IME visibility and animation.
     * This window is same as the window on which startInput is called.
     * @param target the window that receives IME control. This is ignored if we aren't attaching
     *               the IME to an app (eg. when in multi-window mode).
     *
     * @see #getImeControlTarget()
     */
    void updateImeControlTarget(InsetsControlTarget target) {
        if (!isImeAttachedToApp() && mRemoteInsetsControlTarget != null) {
            mInputMethodControlTarget = mRemoteInsetsControlTarget;
        } else {
            // Otherwise, we just use the ime target
            mInputMethodControlTarget = target;
        }
        mInsetsStateController.onImeControlTargetChanged(mInputMethodControlTarget);
    }

    private void updateImeParent() {
        // Force attaching IME to the display when magnifying, or it would be magnified with
        // target app together.
        final boolean shouldAttachToDisplay = (mMagnificationSpec != null);
        final SurfaceControl newParent =
                shouldAttachToDisplay ? mWindowContainers.getSurfaceControl() : computeImeParent();
        if (newParent != null) {
            getPendingTransaction().reparent(mImeWindowsContainers.mSurfaceControl, newParent);
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
        if (isImeAttachedToApp()) {
            return mInputMethodTarget.mActivityRecord.getSurfaceControl();
        }

        // Otherwise, we just attach it to the display.
        return mWindowContainers.getSurfaceControl();
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

        if (!mOpeningApps.isEmpty() || !mClosingApps.isEmpty() || !mChangingContainers.isEmpty()) {
            pw.println();
            if (mOpeningApps.size() > 0) {
                pw.print("  mOpeningApps="); pw.println(mOpeningApps);
            }
            if (mClosingApps.size() > 0) {
                pw.print("  mClosingApps="); pw.println(mClosingApps);
            }
            if (mChangingContainers.size() > 0) {
                pw.print("  mChangingApps="); pw.println(mChangingContainers);
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
    void startKeyguardExitOnNonAppWindows(boolean onWallpaper, boolean goingToShade,
            boolean subtle) {
        final WindowManagerPolicy policy = mWmService.mPolicy;
        forAllWindows(w -> {
            if (w.mActivityRecord == null && policy.canBeHiddenByKeyguardLw(w)
                    && w.wouldBeVisibleIfPolicyIgnored() && !w.isVisible()) {
                w.startAnimation(policy.createHiddenByKeyguardExit(
                        onWallpaper, goingToShade, subtle));
            }
        }, true /* traverseTopToBottom */);
    }

    /** @return {@code true} if there is window to wait before enabling the screen. */
    boolean shouldWaitForSystemDecorWindowsOnBoot() {
        if (!isDefaultDisplay && !supportsSystemDecorations()) {
            // Nothing to wait because the secondary display doesn't support system decorations,
            // there is no wallpaper, keyguard (status bar) or application (home) window to show
            // during booting.
            return false;
        }

        final SparseBooleanArray drawnWindowTypes = new SparseBooleanArray();
        // Presuppose keyguard is drawn because if its window isn't attached, we don't know if it
        // wants to be shown or hidden, then it should not delay enabling the screen.
        drawnWindowTypes.put(TYPE_NOTIFICATION_SHADE, true);

        final WindowState visibleNotDrawnWindow = getWindow(w -> {
            if (w.mViewVisibility == View.VISIBLE && !w.mObscured && !w.isDrawnLw()) {
                return true;
            }
            if (w.isDrawnLw()) {
                final int type = w.mAttrs.type;
                if (type == TYPE_BOOT_PROGRESS || type == TYPE_BASE_APPLICATION
                        || type == TYPE_WALLPAPER) {
                    drawnWindowTypes.put(type, true);
                } else if (type == TYPE_NOTIFICATION_SHADE) {
                    drawnWindowTypes.put(TYPE_NOTIFICATION_SHADE,
                            mWmService.mPolicy.isKeyguardDrawnLw());
                }
            }
            return false;
        });

        if (visibleNotDrawnWindow != null) {
            // Wait for the visible window to be drawn.
            return true;
        }

        // if the wallpaper service is disabled on the device, we're never going to have
        // wallpaper, don't bother waiting for it
        boolean wallpaperEnabled = mWmService.mContext.getResources().getBoolean(
                com.android.internal.R.bool.config_enableWallpaperService)
                && mWmService.mContext.getResources().getBoolean(
                com.android.internal.R.bool.config_checkWallpaperAtBoot)
                && !mWmService.mOnlyCore;

        final boolean haveBootMsg = drawnWindowTypes.get(TYPE_BOOT_PROGRESS);
        final boolean haveApp = drawnWindowTypes.get(TYPE_BASE_APPLICATION);
        final boolean haveWallpaper = drawnWindowTypes.get(TYPE_WALLPAPER);
        final boolean haveKeyguard = drawnWindowTypes.get(TYPE_NOTIFICATION_SHADE);

        ProtoLog.i(WM_DEBUG_SCREEN_ON,
                "******** booted=%b msg=%b haveBoot=%b haveApp=%b haveWall=%b "
                        + "wallEnabled=%b haveKeyguard=%b",
                mWmService.mSystemBooted, mWmService.mShowingBootMessages, haveBootMsg,
                haveApp, haveWallpaper, wallpaperEnabled, haveKeyguard);

        // If we are turning on the screen to show the boot message, don't do it until the boot
        // message is actually displayed.
        if (!mWmService.mSystemBooted && !haveBootMsg) {
            return true;
        }

        // If we are turning on the screen after the boot is completed normally, don't do so until
        // we have the application and wallpaper.
        if (mWmService.mSystemBooted
                && ((!haveApp && !haveKeyguard) || (wallpaperEnabled && !haveWallpaper))) {
            return true;
        }

        return false;
    }

    void updateWindowsForAnimator() {
        forAllWindows(mUpdateWindowsForAnimator, true /* traverseTopToBottom */);
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

    void hideTransientBars() {
        // TODO(b/118118435): Remove this after migration
        final int transientFlags = View.STATUS_BAR_TRANSIENT | View.NAVIGATION_BAR_TRANSIENT;
        statusBarVisibilityChanged(mLastStatusBarVisibility & ~transientFlags);

        getInsetsPolicy().hideTransient();
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
                if ((newValue != curValue || w.mAttrs.hasSystemUiListeners)
                        && ViewRootImpl.sNewInsetsMode != ViewRootImpl.NEW_INSETS_MODE_FULL) {
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

    // TODO: Super crazy long method that should be broken down...
    void applySurfaceChangesTransaction() {
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
                if (updateOrientation()) {
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

            Trace.traceBegin(TRACE_TAG_WINDOW_MANAGER, "applyPostLayoutPolicy");
            try {
                mDisplayPolicy.beginPostLayoutPolicyLw();
                forAllWindows(mApplyPostLayoutPolicy, true /* traverseTopToBottom */);
                pendingLayoutChanges |= mDisplayPolicy.finishPostLayoutPolicyLw();
            } finally {
                Trace.traceEnd(TRACE_TAG_WINDOW_MANAGER);
            }
            if (DEBUG_LAYOUT_REPEATS) surfacePlacer.debugLayoutRepeats(
                    "after finishPostLayoutPolicyLw", pendingLayoutChanges);
            mInsetsStateController.onPostLayout();
        } while (pendingLayoutChanges != 0);

        mTmpApplySurfaceChangesTransactionState.reset();

        Trace.traceBegin(TRACE_TAG_WINDOW_MANAGER, "applyWindowSurfaceChanges");
        try {
            forAllWindows(mApplySurfaceChangesTransaction, true /* traverseTopToBottom */);
        } finally {
            Trace.traceEnd(TRACE_TAG_WINDOW_MANAGER);
        }
        prepareSurfaces();

        // This should be called after the insets have been dispatched to clients and we have
        // committed finish drawing windows.
        mInsetsStateController.getImeSourceProvider().checkShowImePostLayout();

        mLastHasContent = mTmpApplySurfaceChangesTransactionState.displayHasContent;
        mWmService.mDisplayManagerInternal.setDisplayProperties(mDisplayId,
                mLastHasContent,
                mTmpApplySurfaceChangesTransactionState.preferredRefreshRate,
                mTmpApplySurfaceChangesTransactionState.preferredModeId,
                mTmpApplySurfaceChangesTransactionState.preferMinimalPostProcessing,
                true /* inTraversal, must call performTraversalInTrans... below */);

        final boolean wallpaperVisible = mWallpaperController.isWallpaperVisible();
        if (wallpaperVisible != mLastWallpaperVisible) {
            mLastWallpaperVisible = wallpaperVisible;
            mWmService.mWallpaperVisibilityListeners.notifyWallpaperVisibilityChanged(this);
        }

        while (!mTmpUpdateAllDrawn.isEmpty()) {
            final ActivityRecord activity = mTmpUpdateAllDrawn.removeLast();
            // See if any windows have been drawn, so they (and others associated with them)
            // can now be shown.
            activity.updateAllDrawn();
        }
    }

    private void updateBounds() {
        calculateBounds(mDisplayInfo, mTmpBounds);
        setBounds(mTmpBounds);
        if (mPortalWindowHandle != null && mParentSurfaceControl != null) {
            mPortalWindowHandle.touchableRegion.getBounds(mTmpRect);
            if (!mTmpBounds.equals(mTmpRect)) {
                mPortalWindowHandle.touchableRegion.set(mTmpBounds);
                getPendingTransaction().setInputWindowInfo(
                        mParentSurfaceControl, mPortalWindowHandle);
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

    /** @return the orientation of the display when it's rotation is ROTATION_0. */
    int getNaturalOrientation() {
        return mBaseDisplayWidth < mBaseDisplayHeight
                ? ORIENTATION_PORTRAIT : ORIENTATION_LANDSCAPE;
    }

    void performLayout(boolean initial, boolean updateInputWindows) {
        Trace.traceBegin(TRACE_TAG_WINDOW_MANAGER, "performLayout");
        try {
            performLayoutNoTrace(initial, updateInputWindows);
        } finally {
            Trace.traceEnd(TRACE_TAG_WINDOW_MANAGER);
        }
    }

    private void performLayoutNoTrace(boolean initial, boolean updateInputWindows) {
        if (!isLayoutNeeded()) {
            return;
        }
        clearLayoutNeeded();

        final int dw = mDisplayInfo.logicalWidth;
        final int dh = mDisplayInfo.logicalHeight;
        if (DEBUG_LAYOUT) {
            Slog.v(TAG, "-------------------------------------");
            Slog.v(TAG, "performLayout: needed=" + isLayoutNeeded() + " dw=" + dw
                    + " dh=" + dh);
        }

        mDisplayFrames.onDisplayInfoUpdated(mDisplayInfo,
                calculateDisplayCutoutForRotation(mDisplayInfo.rotation));
        // TODO: Not sure if we really need to set the rotation here since we are updating from
        // the display info above...
        mDisplayFrames.mRotation = getRotation();
        mDisplayPolicy.beginLayoutLw(mDisplayFrames, getConfiguration().uiMode);

        int seq = mLayoutSeq + 1;
        if (seq < 0) seq = 0;
        mLayoutSeq = seq;

        // Used to indicate that we have processed the dream window and all additional windows are
        // behind it.
        mTmpWindow = null;
        mTmpInitial = initial;

        // Used to indicate that we have processed the IME window.
        mTmpWindowsBehindIme = false;

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

        mWmService.mH.sendEmptyMessage(UPDATE_MULTI_WINDOW_STACKS);
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
                mWmService.mRoot.getDisplayContent(DEFAULT_DISPLAY).getRotationAnimation();
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

    void setExitingTokensHasVisible(boolean hasVisible) {
        for (int i = mExitingTokens.size() - 1; i >= 0; i--) {
            mExitingTokens.get(i).hasVisible = hasVisible;
        }

        // Initialize state of exiting applications.
        mTaskContainers.setExitingTokensHasVisible(hasVisible);
    }

    void removeExistingTokensIfPossible() {
        for (int i = mExitingTokens.size() - 1; i >= 0; i--) {
            final WindowToken token = mExitingTokens.get(i);
            if (!token.hasVisible) {
                mExitingTokens.remove(i);
            }
        }

        // Time to remove any exiting applications?
        mTaskContainers.removeExistingAppTokensIfPossible();
    }

    @Override
    void onDescendantOverrideConfigurationChanged() {
        setLayoutNeeded();
        mWmService.requestTraversal();
    }

    boolean okToDisplay() {
        return okToDisplay(false);
    }

    boolean okToDisplay(boolean ignoreFrozen) {
        if (mDisplayId == DEFAULT_DISPLAY) {
            return (!mWmService.mDisplayFrozen || ignoreFrozen)
                    && mWmService.mDisplayEnabled && mWmService.mPolicy.isScreenOn();
        }
        return mDisplayInfo.state == Display.STATE_ON;
    }

    boolean okToAnimate() {
        return okToAnimate(false);
    }

    boolean okToAnimate(boolean ignoreFrozen) {
        return okToDisplay(ignoreFrozen) &&
                (mDisplayId != DEFAULT_DISPLAY || mWmService.mPolicy.okToAnimate());
    }

    static final class TaskForResizePointSearchResult {
        private Task taskForResize;
        private int x;
        private int y;
        private int delta;
        private Rect mTmpRect = new Rect();

        Task process(WindowContainer root, int x, int y, int delta) {
            taskForResize = null;
            this.x = x;
            this.y = y;
            this.delta = delta;
            mTmpRect.setEmpty();

            final PooledFunction f = PooledLambda.obtainFunction(
                    TaskForResizePointSearchResult::processTask, this, PooledLambda.__(Task.class));
            root.forAllTasks(f);
            f.recycle();

            return taskForResize;
        }

        private boolean processTask(Task task) {
            if (!task.getStack().getWindowConfiguration().canResizeTask()) {
                return true;
            }

            if (task.getWindowingMode() == WINDOWING_MODE_FULLSCREEN) {
                return true;
            }

            // We need to use the task's dim bounds (which is derived from the visible bounds of
            // its apps windows) for any touch-related tests. Can't use the task's original
            // bounds because it might be adjusted to fit the content frame. One example is when
            // the task is put to top-left quadrant, the actual visible area would not start at
            // (0,0) after it's adjusted for the status bar.
            task.getDimBounds(mTmpRect);
            mTmpRect.inset(-delta, -delta);
            if (mTmpRect.contains(x, y)) {
                mTmpRect.inset(delta, delta);

                if (!mTmpRect.contains(x, y)) {
                    taskForResize = task;
                    return true;
                }
                // User touched inside the task. No need to look further,
                // focus transfer will be handled in ACTION_UP.
                return true;
            }

            return false;
        }
    }

    private static final class ApplySurfaceChangesTransactionState {
        boolean displayHasContent;
        boolean obscured;
        boolean syswin;
        boolean preferMinimalPostProcessing;
        float preferredRefreshRate;
        int preferredModeId;

        void reset() {
            displayHasContent = false;
            obscured = false;
            syswin = false;
            preferMinimalPostProcessing = false;
            preferredRefreshRate = 0;
            preferredModeId = 0;
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
            // TODO(display-area): move to ConfigurationContainer?
            mOrientation = SCREEN_ORIENTATION_UNSET;
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

    private class WindowContainers extends DisplayChildWindowContainer<WindowContainer> {
        private final String mName;

        WindowContainers(String name, WindowManagerService service) {
            super(service);
            mName = name;
        }

        @Override
        void assignChildLayers(SurfaceControl.Transaction t) {
            mImeWindowsContainers.setNeedsLayer();

            mRootDisplayArea.assignLayer(t, 0);

            final WindowState imeTarget = mInputMethodTarget;
            // In the case where we have an IME target that is not in split-screen mode IME
            // assignment is easy. We just need the IME to go directly above the target. This way
            // children of the target will naturally go above the IME and everyone is happy.
            //
            // In the case of split-screen windowing mode, we need to elevate the IME above the
            // docked divider while keeping the app itself below the docked divider, so instead
            // we use relative layering of the IME targets child windows, and place the IME in
            // the non-app layer (see {@link AboveAppWindowContainers#assignChildLayers}).
            //
            // In the case the IME target is animating, the animation Z order may be different
            // than the WindowContainer Z order, so it's difficult to be sure we have the correct
            // IME target. In this case we just layer the IME over all transitions by placing it
            // in the above applications layer.
            //
            // In the case where we have no IME target we assign it where its base layer would
            // place it in the AboveAppWindowContainers.
            //
            // Keep IME window in mAboveAppWindowsContainers as long as app's starting window
            // exists so it get's layered above the starting window.
            if (imeTarget != null && !(imeTarget.mActivityRecord != null
                    && imeTarget.mActivityRecord.hasStartingWindow()) && (
                    !(imeTarget.inMultiWindowMode()
                            || imeTarget.mToken.isAppTransitioning()) && (
                            imeTarget.getSurfaceControl() != null))) {
                mImeWindowsContainers.assignRelativeLayer(t, imeTarget.getSurfaceControl(),
                        // TODO: We need to use an extra level on the app surface to ensure
                        // this is always above SurfaceView but always below attached window.
                        1);
            }

            // Above we have assigned layers to our children, now we ask them to assign
            // layers to their children.
            mRootDisplayArea.assignChildLayers(t);
        }

        @Override
        String getName() {
            return mName;
        }

        void addChildren() {
            addChild(mRootDisplayArea, 0);
            mDisplayAreaPolicy.attachDisplayAreas();
        }

        @Override
        void positionChildAt(int position, WindowContainer child, boolean includingParents) {
            // Children of the WindowContainers are statically ordered, so the real intention here
            // is to perform the operation on the display and not the static direct children.
            getParent().positionChildAt(position, this, includingParents);
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

        private final String mName;
        private final Dimmer mDimmer = new Dimmer(this);
        private final Rect mTmpDimBoundsRect = new Rect();

        NonAppWindowContainers(String name, WindowManagerService service) {
            super(service);
            mName = name;
        }

        @Override
        boolean hasActivity() {
            // I am a non-app-window-container :P
            return false;
        }

        void addChild(WindowToken token) {
            addChild(token, mWindowComparator);
        }

        @Override
        int getOrientation(int candidate) {
            ProtoLog.w(WM_DEBUG_ORIENTATION, "NonAppWindowContainer cannot set orientation: %s",
                    this);
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

        @Override
        boolean shouldMagnify() {
            // Omitted from Screen-Magnification
            return false;
        }
    }

    /**
     * Container for IME windows.
     *
     * This has some special behaviors:
     * - layers assignment is ignored except if setNeedsLayer() has been called before (and no
     *   layer has been assigned since), to facilitate assigning the layer from the IME target, or
     *   fall back if there is no target.
     * - the container doesn't always participate in window traversal, according to
     *   {@link #skipImeWindowsDuringTraversal()}
     */
    private static class ImeContainer extends DisplayArea.Tokens {
        boolean mNeedsLayer = false;

        ImeContainer(WindowManagerService wms) {
            super(wms, Type.ABOVE_TASKS, "ImeContainer");
        }

        public void setNeedsLayer() {
            mNeedsLayer = true;
        }

        @Override
        int getOrientation(int candidate) {
            // IME does not participate in orientation.
            return candidate;
        }

        @Override
        boolean forAllWindows(ToBooleanFunction<WindowState> callback,
                boolean traverseTopToBottom) {
            final DisplayContent dc = mDisplayContent;
            if (skipImeWindowsDuringTraversal(dc)) {
                return false;
            }
            return super.forAllWindows(callback, traverseTopToBottom);
        }

        private boolean skipImeWindowsDuringTraversal(DisplayContent dc) {
            // We skip IME windows so they're processed just above their target, except
            // in split-screen mode where we process the IME containers above the docked divider.
            return dc.mInputMethodTarget != null && !dc.isSplitScreenModeActivated();
        }

        /** Like {@link #forAllWindows}, but ignores {@link #skipImeWindowsDuringTraversal} */
        boolean forAllWindowForce(ToBooleanFunction<WindowState> callback,
                boolean traverseTopToBottom) {
            return super.forAllWindows(callback, traverseTopToBottom);
        }

        @Override
        void assignLayer(Transaction t, int layer) {
            if (!mNeedsLayer) {
                return;
            }
            super.assignLayer(t, layer);
            mNeedsLayer = false;
        }

        @Override
        void assignRelativeLayer(Transaction t, SurfaceControl relativeTo, int layer) {
            if (!mNeedsLayer) {
                return;
            }
            super.assignRelativeLayer(t, relativeTo, layer);
            mNeedsLayer = false;
        }
    }

    @Override
    SurfaceSession getSession() {
        return mSession;
    }

    @Override
    SurfaceControl.Builder makeChildSurface(WindowContainer child) {
        SurfaceSession s = child != null ? child.getSession() : getSession();
        final SurfaceControl.Builder b = mWmService.makeSurfaceBuilder(s).setContainerLayer();
        if (child == null) {
            return b;
        }

        return b.setName(child.getName())
                .setParent(mSurfaceControl);
    }

    /**
     * The makeSurface variants are for use by the window-container
     * hierarchy. makeOverlay here is a function for various non windowing
     * overlays like the ScreenRotation screenshot, the Strict Mode Flash
     * and other potpourii.
     */
    SurfaceControl.Builder makeOverlay() {
        return mWmService.makeSurfaceBuilder(mSession)
                .setParent(mOverlayContainers.getSurfaceControl());
    }

    /**
     * Reparents the given surface to {@link #mOverlayContainers}' SurfaceControl.
     */
    void reparentToOverlay(Transaction transaction, SurfaceControl surface) {
        transaction.reparent(surface, mOverlayContainers.getSurfaceControl());
    }

    void applyMagnificationSpec(MagnificationSpec spec) {
        if (spec.scale != 1.0) {
            mMagnificationSpec = spec;
        } else {
            mMagnificationSpec = null;
        }
        // Re-parent IME's SurfaceControl when MagnificationSpec changed.
        updateImeParent();

        if (spec.scale != 1.0) {
            applyMagnificationSpec(getPendingTransaction(), spec);
        } else {
            clearMagnificationSpec(getPendingTransaction());
        }
        getPendingTransaction().apply();
    }

    void reapplyMagnificationSpec() {
        if (mMagnificationSpec != null) {
            applyMagnificationSpec(getPendingTransaction(), mMagnificationSpec);
        }
    }

    @Override
    void onParentChanged(ConfigurationContainer newParent, ConfigurationContainer oldParent) {
        // Since we are the top of the SurfaceControl hierarchy here
        // we create the root surfaces explicitly rather than chaining
        // up as the default implementation in onParentChanged does. So we
        // explicitly do NOT call super here.

        if (!isReady()) {
            // TODO(b/62541591): evaluate whether this is the best spot to declare the
            // {@link DisplayContent} ready for use.
            mDisplayReady = true;

            mWmService.mAnimator.addDisplayLocked(mDisplayId);

            if (mWmService.mDisplayManagerInternal != null) {
                mWmService.mDisplayManagerInternal
                        .setDisplayInfoOverrideFromWindowManager(mDisplayId, getDisplayInfo());
                configureDisplayPolicy();
            }

            reconfigureDisplayLocked();
            onRequestedOverrideConfigurationChanged(getRequestedOverrideConfiguration());
            mWmService.mDisplayNotificationController.dispatchDisplayAdded(this);
        }
    }

    @Override
    void assignChildLayers(SurfaceControl.Transaction t) {
        mWindowContainers.assignLayer(t, 0);
        mOverlayContainers.assignLayer(t, 1);

        mWindowContainers.assignChildLayers(t);
        mOverlayContainers.assignChildLayers(t);
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
        mImeWindowsContainers.setNeedsLayer();
        child.assignRelativeLayer(t, mImeWindowsContainers.getSurfaceControl(), 1);
    }

    @Override
    void prepareSurfaces() {
        Trace.traceBegin(TRACE_TAG_WINDOW_MANAGER, "prepareSurfaces");
        try {
            final Transaction transaction = getPendingTransaction();
            super.prepareSurfaces();

            // TODO: Once we totally eliminate global transaction we will pass transaction in here
            //       rather than merging to global.
            SurfaceControl.mergeToGlobalTransaction(transaction);
        } finally {
            Trace.traceEnd(TRACE_TAG_WINDOW_MANAGER);
        }
    }

    void assignStackOrdering() {
        mTaskContainers.assignStackOrdering(getPendingTransaction());
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
        mPointerEventDispatcher.registerInputEventListener(listener);
    }

    void unregisterPointerEventListener(@NonNull PointerEventListener listener) {
        mPointerEventDispatcher.unregisterInputEventListener(listener);
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
            ProtoLog.w(WM_DEBUG_APP_TRANSITIONS,
                    "Execute app transition: %s, displayId: %d Callers=%s",
                    mAppTransition, mDisplayId, Debug.getCallers(5));
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
        return (mWmService.mDisplayWindowSettings.shouldShowSystemDecorsLocked(this)
                || (mDisplay.getFlags() & FLAG_SHOULD_SHOW_SYSTEM_DECORATIONS) != 0
                || (mWmService.mForceDesktopModeOnExternalDisplays && !isUntrustedVirtualDisplay()))
                // VR virtual display will be used to run and render 2D app within a VR experience.
                && mDisplayId != mWmService.mVr2dDisplayId;
    }

    /**
     * @return {@code true} if the display is non-system created virtual display.
     */
    boolean isUntrustedVirtualDisplay() {
        return mDisplay.getType() == Display.TYPE_VIRTUAL
                && mDisplay.getOwnerUid() != Process.SYSTEM_UID;
    }

    /**
     * Re-parent the DisplayContent's top surface, {@link #mSurfaceControl} to the specified
     * SurfaceControl.
     *
     * @param win The window which owns the SurfaceControl. This indicates the z-order of the
     *            windows of this display against the windows on the parent display.
     * @param sc The new SurfaceControl, where the DisplayContent's surfaces will be re-parented to.
     */
    void reparentDisplayContent(WindowState win, SurfaceControl sc) {
        mParentWindow = win;
        mParentWindow.addEmbeddedDisplayContent(this);
        mParentSurfaceControl = sc;
        if (mPortalWindowHandle == null) {
            mPortalWindowHandle = createPortalWindowHandle(sc.toString());
        }
        getPendingTransaction().setInputWindowInfo(sc, mPortalWindowHandle)
                .reparent(mSurfaceControl, sc);
    }

    /**
     * Get the window which owns the surface that this DisplayContent is re-parented to.
     *
     * @return the parent window.
     */
    WindowState getParentWindow() {
        return mParentWindow;
    }

    /**
     * Update the location of this display in the parent window. This enables windows in this
     * display to compute the global transformation matrix.
     *
     * @param win The parent window of this display.
     * @param x The x coordinate in the parent window.
     * @param y The y coordinate in the parent window.
     */
    void updateLocation(WindowState win, int x, int y) {
        if (mParentWindow != win) {
            throw new IllegalArgumentException(
                    "The given window is not the parent window of this display.");
        }
        if (!mLocationInParentWindow.equals(x, y)) {
            mLocationInParentWindow.set(x, y);
            if (mWmService.mAccessibilityController != null) {
                mWmService.mAccessibilityController.onSomeWindowResizedOrMovedLocked(mDisplayId);
            }
            notifyLocationInParentDisplayChanged();
        }
    }

    Point getLocationInParentWindow() {
        return mLocationInParentWindow;
    }

    Point getLocationInParentDisplay() {
        final Point location = new Point();
        if (mParentWindow != null) {
            // LocationInParentWindow indicates the offset to (0,0) of window, but what we need is
            // the offset to (0,0) of display.
            DisplayContent dc = this;
            do {
                final WindowState displayParent = dc.getParentWindow();
                location.x += displayParent.getFrameLw().left
                        + (dc.getLocationInParentWindow().x * displayParent.mGlobalScale + 0.5f);
                location.y += displayParent.getFrameLw().top
                        + (dc.getLocationInParentWindow().y * displayParent.mGlobalScale + 0.5f);
                dc = displayParent.getDisplayContent();
            } while (dc != null && dc.getParentWindow() != null);
        }
        return location;
    }

    void notifyLocationInParentDisplayChanged() {
        forAllWindows(w -> {
            w.updateLocationInParentDisplayIfNeeded();
        }, false /* traverseTopToBottom */);
    }

    @VisibleForTesting
    SurfaceControl getWindowingLayer() {
        return mWindowContainers.getSurfaceControl();
    }

    SurfaceControl getOverlayLayer() {
        return mOverlayContainers.getSurfaceControl();
    }

    /**
     * Updates the display's system gesture exclusion.
     *
     * @return true, if the exclusion changed.
     */
    boolean updateSystemGestureExclusion() {
        if (mSystemGestureExclusionListeners.getRegisteredCallbackCount() == 0) {
            // No one's interested anyways.
            return false;
        }

        final Region systemGestureExclusion = Region.obtain();
        mSystemGestureExclusionWasRestricted = calculateSystemGestureExclusion(
                systemGestureExclusion, mSystemGestureExclusionUnrestricted);
        try {
            if (mSystemGestureExclusion.equals(systemGestureExclusion)) {
                return false;
            }
            mSystemGestureExclusion.set(systemGestureExclusion);
            final Region unrestrictedOrNull = mSystemGestureExclusionWasRestricted
                    ? mSystemGestureExclusionUnrestricted : null;
            for (int i = mSystemGestureExclusionListeners.beginBroadcast() - 1; i >= 0; --i) {
                try {
                    mSystemGestureExclusionListeners.getBroadcastItem(i)
                            .onSystemGestureExclusionChanged(mDisplayId, systemGestureExclusion,
                                    unrestrictedOrNull);
                } catch (RemoteException e) {
                    Slog.e(TAG, "Failed to notify SystemGestureExclusionListener", e);
                }
            }
            mSystemGestureExclusionListeners.finishBroadcast();
            return true;
        } finally {
            systemGestureExclusion.recycle();
        }
    }

    /**
     * Calculates the system gesture exclusion.
     *
     * @param outExclusion will be set to the gesture exclusion region
     * @param outExclusionUnrestricted will be set to the gesture exclusion region without
     *                                 any restrictions applied.
     * @return whether any restrictions were applied, i.e. outExclusion and outExclusionUnrestricted
     *         differ.
     */
    @VisibleForTesting
    boolean calculateSystemGestureExclusion(Region outExclusion, @Nullable
            Region outExclusionUnrestricted) {
        outExclusion.setEmpty();
        if (outExclusionUnrestricted != null) {
            outExclusionUnrestricted.setEmpty();
        }
        final Region unhandled = Region.obtain();
        unhandled.set(0, 0, mDisplayFrames.mDisplayWidth, mDisplayFrames.mDisplayHeight);

        final Rect leftEdge = mInsetsStateController.getSourceProvider(ITYPE_LEFT_GESTURES)
                .getSource().getFrame();
        final Rect rightEdge = mInsetsStateController.getSourceProvider(ITYPE_RIGHT_GESTURES)
                .getSource().getFrame();

        final Region touchableRegion = Region.obtain();
        final Region local = Region.obtain();
        final int[] remainingLeftRight =
                {mSystemGestureExclusionLimit, mSystemGestureExclusionLimit};

        // Traverse all windows top down to assemble the gesture exclusion rects.
        // For each window, we only take the rects that fall within its touchable region.
        forAllWindows(w -> {
            if (w.cantReceiveTouchInput() || !w.isVisible()
                    || (w.getAttrs().flags & FLAG_NOT_TOUCHABLE) != 0
                    || unhandled.isEmpty()) {
                return;
            }

            // Get the touchable region of the window, and intersect with where the screen is still
            // touchable, i.e. touchable regions on top are not covering it yet.
            w.getEffectiveTouchableRegion(touchableRegion);
            touchableRegion.op(unhandled, Op.INTERSECT);

            if (w.isImplicitlyExcludingAllSystemGestures()) {
                local.set(touchableRegion);
            } else {
                rectListToRegion(w.getSystemGestureExclusion(), local);

                // Transform to display coordinates
                local.scale(w.mGlobalScale);
                final Rect frame = w.getWindowFrames().mFrame;
                local.translate(frame.left, frame.top);

                // A window can only exclude system gestures where it is actually touchable
                local.op(touchableRegion, Op.INTERSECT);
            }

            // Apply restriction if necessary.
            if (needsGestureExclusionRestrictions(w, mLastDispatchedSystemUiVisibility)) {

                // Processes the region along the left edge.
                remainingLeftRight[0] = addToGlobalAndConsumeLimit(local, outExclusion, leftEdge,
                        remainingLeftRight[0], w, EXCLUSION_LEFT);

                // Processes the region along the right edge.
                remainingLeftRight[1] = addToGlobalAndConsumeLimit(local, outExclusion, rightEdge,
                        remainingLeftRight[1], w, EXCLUSION_RIGHT);

                // Adds the middle (unrestricted area)
                final Region middle = Region.obtain(local);
                middle.op(leftEdge, Op.DIFFERENCE);
                middle.op(rightEdge, Op.DIFFERENCE);
                outExclusion.op(middle, Op.UNION);
                middle.recycle();
            } else {
                boolean loggable = needsGestureExclusionRestrictions(w, 0 /* lastSysUiVis */);
                if (loggable) {
                    addToGlobalAndConsumeLimit(local, outExclusion, leftEdge,
                            Integer.MAX_VALUE, w, EXCLUSION_LEFT);
                    addToGlobalAndConsumeLimit(local, outExclusion, rightEdge,
                            Integer.MAX_VALUE, w, EXCLUSION_RIGHT);
                }
                outExclusion.op(local, Op.UNION);
            }
            if (outExclusionUnrestricted != null) {
                outExclusionUnrestricted.op(local, Op.UNION);
            }
            unhandled.op(touchableRegion, Op.DIFFERENCE);
        }, true /* topToBottom */);
        local.recycle();
        touchableRegion.recycle();
        unhandled.recycle();
        return remainingLeftRight[0] < mSystemGestureExclusionLimit
                || remainingLeftRight[1] < mSystemGestureExclusionLimit;
    }

    /**
     * @return Whether gesture exclusion area should be restricted from the window depending on the
     *         current SystemUI visibility flags.
     */
    private static boolean needsGestureExclusionRestrictions(WindowState win, int sysUiVisibility) {
        final int type = win.mAttrs.type;
        final int stickyHideNavFlags =
                SYSTEM_UI_FLAG_HIDE_NAVIGATION | SYSTEM_UI_FLAG_IMMERSIVE_STICKY;
        final boolean stickyHideNav =
                (sysUiVisibility & stickyHideNavFlags) == stickyHideNavFlags;
        return !stickyHideNav && type != TYPE_INPUT_METHOD && type != TYPE_NOTIFICATION_SHADE
                && win.getActivityType() != ACTIVITY_TYPE_HOME;
    }

    /**
     * @return Whether gesture exclusion area should be logged for the given window
     */
    static boolean logsGestureExclusionRestrictions(WindowState win) {
        if (win.mWmService.mConstants.mSystemGestureExclusionLogDebounceTimeoutMillis <= 0) {
            return false;
        }
        final WindowManager.LayoutParams attrs = win.getAttrs();
        final int type = attrs.type;
        return type != TYPE_WALLPAPER
                && type != TYPE_APPLICATION_STARTING
                && type != TYPE_NAVIGATION_BAR
                && (attrs.flags & FLAG_NOT_TOUCHABLE) == 0
                && needsGestureExclusionRestrictions(win, 0 /* sysUiVisibility */)
                && win.getDisplayContent().mDisplayPolicy.hasSideGestures();
    }

    /**
     * Adds a local gesture exclusion area to the global area while applying a limit per edge.
     *
     * @param local The gesture exclusion area to add.
     * @param global The destination.
     * @param edge Only processes the part in that region.
     * @param limit How much limit in pixels we have.
     * @param win The WindowState that is being processed
     * @param side The side that is being processed, either {@link WindowState#EXCLUSION_LEFT} or
     *             {@link WindowState#EXCLUSION_RIGHT}
     * @return How much of the limit is remaining.
     */
    private static int addToGlobalAndConsumeLimit(Region local, Region global, Rect edge,
            int limit, WindowState win, int side) {
        final Region r = Region.obtain(local);
        r.op(edge, Op.INTERSECT);

        final int[] remaining = {limit};
        final int[] requestedExclusion = {0};
        forEachRectReverse(r, rect -> {
            if (remaining[0] <= 0) {
                return;
            }
            final int height = rect.height();
            requestedExclusion[0] += height;
            if (height > remaining[0]) {
                rect.top = rect.bottom - remaining[0];
            }
            remaining[0] -= height;
            global.op(rect, Op.UNION);
        });

        final int grantedExclusion = limit - remaining[0];
        win.setLastExclusionHeights(side, requestedExclusion[0], grantedExclusion);

        r.recycle();
        return remaining[0];
    }

    void registerSystemGestureExclusionListener(ISystemGestureExclusionListener listener) {
        mSystemGestureExclusionListeners.register(listener);
        final boolean changed;
        if (mSystemGestureExclusionListeners.getRegisteredCallbackCount() == 1) {
            changed = updateSystemGestureExclusion();
        } else {
            changed = false;
        }

        if (!changed) {
            final Region unrestrictedOrNull = mSystemGestureExclusionWasRestricted
                    ? mSystemGestureExclusionUnrestricted : null;
            // If updateSystemGestureExclusion changed the exclusion, it will already have
            // notified the listener. Otherwise, we'll do it here.
            try {
                listener.onSystemGestureExclusionChanged(mDisplayId, mSystemGestureExclusion,
                        unrestrictedOrNull);
            } catch (RemoteException e) {
                Slog.e(TAG, "Failed to notify SystemGestureExclusionListener during register", e);
            }
        }
    }

    void unregisterSystemGestureExclusionListener(ISystemGestureExclusionListener listener) {
        mSystemGestureExclusionListeners.unregister(listener);
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
                null /* inputApplicationHandle */, INVALID_DISPLAY);
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

    protected MetricsLogger getMetricsLogger() {
        if (mMetricsLogger == null) {
            mMetricsLogger = new MetricsLogger();
        }
        return mMetricsLogger;
    }

    void onDisplayChanged() {
        mDisplay.getRealSize(mTmpDisplaySize);
        setBounds(0, 0, mTmpDisplaySize.x, mTmpDisplaySize.y);
        updateDisplayInfo();

        // The window policy is responsible for stopping activities on the default display.
        final int displayId = mDisplay.getDisplayId();
        if (displayId != DEFAULT_DISPLAY) {
            final int displayState = mDisplay.getState();
            if (displayState == Display.STATE_OFF && mOffToken == null) {
                mOffToken = mAtmService.acquireSleepToken("Display-off", displayId);
            } else if (displayState == Display.STATE_ON && mOffToken != null) {
                mOffToken.release();
                mOffToken = null;
            }
        }
        mWmService.requestTraversal();
    }

    void addStack(ActivityStack stack, int position) {
        setStackOnDisplay(stack, position);
        positionStackAt(stack, position);
    }

    void addStackReferenceIfNeeded(ActivityStack stack) {
        mTaskContainers.addStackReferenceIfNeeded(stack);
    }

    void removeStackReferenceIfNeeded(ActivityStack stack) {
        mTaskContainers.removeStackReferenceIfNeeded(stack);
    }

    void onStackRemoved(ActivityStack stack) {
        if (ActivityTaskManagerDebugConfig.DEBUG_STACK) {
            Slog.v(TAG_STACK, "removeStack: detaching " + stack + " from displayId=" + mDisplayId);
        }
        if (mPreferredTopFocusableStack == stack) {
            mPreferredTopFocusableStack = null;
        }
        releaseSelfIfNeeded();
        onStackOrderChanged(stack);
    }

    void positionStackAtTop(ActivityStack stack, boolean includingParents) {
        positionStackAtTop(stack, includingParents, null /* updateLastFocusedStackReason */);
    }

    void positionStackAtTop(ActivityStack stack, boolean includingParents,
            String updateLastFocusedStackReason) {
        positionStackAt(stack, getStackCount(), includingParents, updateLastFocusedStackReason);
    }

    void positionStackAtBottom(ActivityStack stack) {
        positionStackAtBottom(stack, null /* updateLastFocusedStackReason */);
    }

    void positionStackAtBottom(ActivityStack stack, String updateLastFocusedStackReason) {
        positionStackAt(stack, 0, false /* includingParents */, updateLastFocusedStackReason);
    }

    private void positionStackAt(ActivityStack stack, int position) {
        positionStackAt(stack, position, false /* includingParents */,
                null /* updateLastFocusedStackReason */);
    }

    private void positionStackAt(ActivityStack stack, int position, boolean includingParents,
            String updateLastFocusedStackReason) {
        // TODO: Keep in sync with WindowContainer.positionChildAt(), once we change that to adjust
        //       the position internally, also update the logic here
        final ActivityStack prevFocusedStack = updateLastFocusedStackReason != null
                ? getFocusedStack() : null;
        final boolean wasContained = getIndexOf(stack) >= 0;
        if (mSingleTaskInstance && getStackCount() == 1 && !wasContained) {
            throw new IllegalStateException(
                    "positionStackAt: Can only have one task on display=" + this);
        }

        final boolean movingToTop = wasContained && position >= getStackCount() - 1;
        // Reset mPreferredTopFocusableStack before positioning to top or {@link
        // ActivityStackSupervisor#updateTopResumedActivityIfNeeded()} won't update the top
        // resumed activity.
        if (movingToTop && stack.isFocusable()) {
            mPreferredTopFocusableStack = null;
        }

        // Since positionChildAt() is called during the creation process of pinned stacks,
        // ActivityStack#getStack() can be null.
        positionStackAt(position, stack, includingParents);

        // The insert position may be adjusted to non-top when there is always-on-top stack. Since
        // the original position is preferred to be top, the stack should have higher priority when
        // we are looking for top focusable stack. The condition {@code wasContained} restricts the
        // preferred stack is set only when moving an existing stack to top instead of adding a new
        // stack that may be too early (e.g. in the middle of launching or reparenting).
        if (movingToTop && stack.isFocusableAndVisible()) {
            mPreferredTopFocusableStack = stack;
        } else if (mPreferredTopFocusableStack == stack) {
            mPreferredTopFocusableStack = null;
        }

        if (updateLastFocusedStackReason != null) {
            final ActivityStack currentFocusedStack = getFocusedStack();
            if (currentFocusedStack != prevFocusedStack) {
                mLastFocusedStack = prevFocusedStack;
                EventLogTags.writeWmFocusedStack(mRootWindowContainer.mCurrentUser, mDisplayId,
                        currentFocusedStack == null ? -1 : currentFocusedStack.getRootTaskId(),
                        mLastFocusedStack == null ? -1 : mLastFocusedStack.getRootTaskId(),
                        updateLastFocusedStackReason);
            }
        }

        onStackOrderChanged(stack);
    }

    ActivityStack getStack(int rootTaskId) {
        for (int i = getStackCount() - 1; i >= 0; --i) {
            final ActivityStack stack = getStackAt(i);
            if (stack.getRootTaskId() == rootTaskId) {
                return stack;
            }
        }
        return null;
    }

    static boolean alwaysCreateStack(int windowingMode, int activityType) {
        // Always create a stack for fullscreen, freeform, and split-screen-secondary windowing
        // modes so that we can manage visual ordering and return types correctly.
        return activityType == ACTIVITY_TYPE_STANDARD
                && (windowingMode == WINDOWING_MODE_FULLSCREEN
                || windowingMode == WINDOWING_MODE_FREEFORM
                || windowingMode == WINDOWING_MODE_SPLIT_SCREEN_SECONDARY
                || windowingMode == WINDOWING_MODE_MULTI_WINDOW);
    }

    /**
     * Returns an existing stack compatible with the windowing mode and activity type or creates one
     * if a compatible stack doesn't exist.
     * @see #getOrCreateStack(int, int, boolean, Intent, Task, boolean)
     */
    ActivityStack getOrCreateStack(int windowingMode, int activityType, boolean onTop) {
        return getOrCreateStack(windowingMode, activityType, onTop, null /* intent */,
                null /* candidateTask */, false /* createdByOrganizer */);
    }

    /**
     * When two level tasks are required for given windowing mode and activity type, returns an
     * existing compatible root task or creates a new one.
     * For one level task, the candidate task would be reused to also be the root task or create
     * a new root task if no candidate task.
     * @see #getStack(int, int)
     * @see #createStack(int, int, boolean)
     */
    ActivityStack getOrCreateStack(int windowingMode, int activityType, boolean onTop,
            Intent intent, Task candidateTask, boolean createdByOrganizer) {
        if (!alwaysCreateStack(windowingMode, activityType)) {
            ActivityStack stack = getStack(windowingMode, activityType);
            if (stack != null) {
                return stack;
            }
        } else if (candidateTask != null) {
            final ActivityStack stack = (ActivityStack) candidateTask;
            final int position = onTop ? POSITION_TOP : POSITION_BOTTOM;
            if (isSplitScreenModeActivated()) {
                final Task splitRootSecondary = getTask(t -> t.mCreatedByOrganizer && t.isRootTask()
                        && t.inSplitScreenSecondaryWindowingMode());
                if (stack.getParent() == null) {
                    splitRootSecondary.addChild(stack, position);
                } else if (stack.getParent() != splitRootSecondary) {
                    stack.reparent(splitRootSecondary, position);
                }
            } else if (stack.getDisplay() != this || !stack.isRootTask()) {
                if (stack.getParent() == null) {
                    addStack(stack, position);
                } else {
                    stack.reparent(this, onTop);
                }
            }
            // Update windowing mode if necessary, e.g. moving a pinned task to fullscreen.
            if (candidateTask.getWindowingMode() != windowingMode) {
                candidateTask.setWindowingMode(windowingMode);
            }
            return stack;
        }
        return createStack(windowingMode, activityType, onTop, null /*info*/, intent,
                createdByOrganizer);
    }

    /**
     * Returns an existing stack compatible with the input params or creates one
     * if a compatible stack doesn't exist.
     * @see #getOrCreateStack(int, int, boolean)
     */
    ActivityStack getOrCreateStack(@Nullable ActivityRecord r,
            @Nullable ActivityOptions options, @Nullable Task candidateTask, int activityType,
            boolean onTop) {
        // First preference is the windowing mode in the activity options if set.
        int windowingMode = (options != null)
                ? options.getLaunchWindowingMode() : WINDOWING_MODE_UNDEFINED;
        // Validate that our desired windowingMode will work under the current conditions.
        // UNDEFINED windowing mode is a valid result and means that the new stack will inherit
        // it's display's windowing mode.
        windowingMode = validateWindowingMode(windowingMode, r, candidateTask, activityType);
        return getOrCreateStack(windowingMode, activityType, onTop, null /* intent */,
                candidateTask, false /* createdByOrganizer */);
    }

    @VisibleForTesting
    int getNextStackId() {
        return mAtmService.mStackSupervisor.getNextTaskIdForUser();
    }

    ActivityStack createStack(int windowingMode, int activityType, boolean onTop) {
        return createStack(windowingMode, activityType, onTop, null /* info */, null /* intent */,
                false /* createdByOrganizer */);
    }

    /**
     * Creates a stack matching the input windowing mode and activity type on this display.
     * @param windowingMode The windowing mode the stack should be created in. If
     *                      {@link WindowConfiguration#WINDOWING_MODE_UNDEFINED} then the stack will
     *                      inherit its parent's windowing mode.
     * @param activityType The activityType the stack should be created in. If
     *                     {@link WindowConfiguration#ACTIVITY_TYPE_UNDEFINED} then the stack will
     *                     be created in {@link WindowConfiguration#ACTIVITY_TYPE_STANDARD}.
     * @param onTop If true the stack will be created at the top of the display, else at the bottom.
     * @param info The started activity info.
     * @param intent The intent that started this task.
     * @param createdByOrganizer @{code true} if this is created by task organizer, @{code false}
     *                          otherwise.
     * @return The newly created stack.
     */
    ActivityStack createStack(int windowingMode, int activityType, boolean onTop, ActivityInfo info,
            Intent intent, boolean createdByOrganizer) {
        if (mSingleTaskInstance && getStackCount() > 0) {
            // Create stack on default display instead since this display can only contain 1 stack.
            // TODO: Kinda a hack, but better that having the decision at each call point. Hoping
            // this goes away once ActivityView is no longer using virtual displays.
            return mRootWindowContainer.getDefaultDisplay().createStack(
                    windowingMode, activityType, onTop, info, intent, createdByOrganizer);
        }

        if (activityType == ACTIVITY_TYPE_UNDEFINED && !createdByOrganizer) {
            // Can't have an undefined stack type yet...so re-map to standard. Anyone that wants
            // anything else should be passing it in anyways...except for the task organizer.
            activityType = ACTIVITY_TYPE_STANDARD;
        }

        if (activityType != ACTIVITY_TYPE_STANDARD && activityType != ACTIVITY_TYPE_UNDEFINED) {
            // For now there can be only one stack of a particular non-standard activity type on a
            // display. So, get that ignoring whatever windowing mode it is currently in.
            ActivityStack stack = getStack(WINDOWING_MODE_UNDEFINED, activityType);
            if (stack != null) {
                throw new IllegalArgumentException("Stack=" + stack + " of activityType="
                        + activityType + " already on display=" + this + ". Can't have multiple.");
            }
        }

        if (!isWindowingModeSupported(windowingMode, mAtmService.mSupportsMultiWindow,
                mAtmService.mSupportsSplitScreenMultiWindow,
                mAtmService.mSupportsFreeformWindowManagement,
                mAtmService.mSupportsPictureInPicture, activityType)) {
            throw new IllegalArgumentException("Can't create stack for unsupported windowingMode="
                    + windowingMode);
        }

        final int stackId = getNextStackId();
        return createStackUnchecked(windowingMode, activityType, stackId, onTop, info, intent,
                createdByOrganizer);
    }

    /** @return the root task to create the next task in. */
    private Task updateLaunchRootTask(int windowingMode) {
        if (!isSplitScreenWindowingMode(windowingMode)) {
            // Only split-screen windowing modes can do this currently...
            return null;
        }
        for (int i = getStackCount() - 1; i >= 0; --i) {
            final Task t = getStackAt(i);
            if (!t.mCreatedByOrganizer || t.getRequestedOverrideWindowingMode() != windowingMode) {
                continue;
            }
            // If not already set, pick a launch root which is not the one we are launching into.
            if (mLaunchRootTask == null) {
                for (int j = 0, n = getStackCount(); j < n; ++j) {
                    final Task tt = getStackAt(j);
                    if (tt.mCreatedByOrganizer && tt != t) {
                        mLaunchRootTask = tt;
                        break;
                    }
                }
            }
            return t;
        }
        return mLaunchRootTask;
    }

    @VisibleForTesting
    ActivityStack createStackUnchecked(int windowingMode, int activityType, int stackId,
            boolean onTop, ActivityInfo info, Intent intent, boolean createdByOrganizer) {
        if (windowingMode == WINDOWING_MODE_PINNED && activityType != ACTIVITY_TYPE_STANDARD) {
            throw new IllegalArgumentException("Stack with windowing mode cannot with non standard "
                    + "activity type.");
        }
        if (info == null) {
            info = new ActivityInfo();
            info.applicationInfo = new ApplicationInfo();
        }

        // Task created by organizer are added as root.
        Task launchRootTask = createdByOrganizer ? null : updateLaunchRootTask(windowingMode);
        if (launchRootTask != null) {
            // Since this stack will be put into a root task, its windowingMode will be inherited.
            windowingMode = WINDOWING_MODE_UNDEFINED;
        }

        final ActivityStack stack = (ActivityStack) Task.create(mAtmService, stackId, activityType,
                info, intent, createdByOrganizer);
        if (launchRootTask != null) {
            launchRootTask.addChild(stack, onTop ? POSITION_TOP : POSITION_BOTTOM);
            if (onTop) {
                positionStackAtTop((ActivityStack) launchRootTask, false /* includingParents */);
            }
        } else {
            addStack(stack, onTop ? POSITION_TOP : POSITION_BOTTOM);
            stack.setWindowingMode(windowingMode, false /* animate */, false /* showRecents */,
                    false /* enteringSplitScreenMode */, false /* deferEnsuringVisibility */,
                    true /* creating */);
        }
        return stack;
    }

    /**
     * Get the preferred focusable stack in priority. If the preferred stack does not exist, find a
     * focusable and visible stack from the top of stacks in this display.
     */
    ActivityStack getFocusedStack() {
        if (mPreferredTopFocusableStack != null) {
            return mPreferredTopFocusableStack;
        }

        for (int i = getStackCount() - 1; i >= 0; --i) {
            final ActivityStack stack = getStackAt(i);
            if (stack.isFocusableAndVisible()) {
                return stack;
            }
        }

        return null;
    }

    ActivityStack getNextFocusableStack(ActivityStack currentFocus, boolean ignoreCurrent) {
        final int currentWindowingMode = currentFocus != null
                ? currentFocus.getWindowingMode() : WINDOWING_MODE_UNDEFINED;

        ActivityStack candidate = null;
        for (int i = getStackCount() - 1; i >= 0; --i) {
            final ActivityStack stack = getStackAt(i);
            if (ignoreCurrent && stack == currentFocus) {
                continue;
            }
            if (!stack.isFocusableAndVisible()) {
                continue;
            }

            if (currentWindowingMode == WINDOWING_MODE_SPLIT_SCREEN_SECONDARY
                    && candidate == null && stack.inSplitScreenPrimaryWindowingMode()) {
                // If the currently focused stack is in split-screen secondary we save off the
                // top primary split-screen stack as a candidate for focus because we might
                // prefer focus to move to an other stack to avoid primary split-screen stack
                // overlapping with a fullscreen stack when a fullscreen stack is higher in z
                // than the next split-screen stack. Assistant stack, I am looking at you...
                // We only move the focus to the primary-split screen stack if there isn't a
                // better alternative.
                candidate = stack;
                continue;
            }
            if (candidate != null && stack.inSplitScreenSecondaryWindowingMode()) {
                // Use the candidate stack since we are now at the secondary split-screen.
                return candidate;
            }
            return stack;
        }
        return candidate;
    }

    ActivityRecord getResumedActivity() {
        final ActivityStack focusedStack = getFocusedStack();
        if (focusedStack == null) {
            return null;
        }
        // TODO(b/111541062): Move this into ActivityStack#getResumedActivity()
        // Check if the focused stack has the resumed activity
        ActivityRecord resumedActivity = focusedStack.getResumedActivity();
        if (resumedActivity == null || resumedActivity.app == null) {
            // If there is no registered resumed activity in the stack or it is not running -
            // try to use previously resumed one.
            resumedActivity = focusedStack.mPausingActivity;
            if (resumedActivity == null || resumedActivity.app == null) {
                // If previously resumed activity doesn't work either - find the topmost running
                // activity that can be focused.
                resumedActivity = focusedStack.topRunningActivity(true /* focusableOnly */);
            }
        }
        return resumedActivity;
    }

    ActivityStack getLastFocusedStack() {
        return mLastFocusedStack;
    }

    boolean allResumedActivitiesComplete() {
        for (int stackNdx = getStackCount() - 1; stackNdx >= 0; --stackNdx) {
            final ActivityRecord r = getStackAt(stackNdx).getResumedActivity();
            if (r != null && !r.isState(RESUMED)) {
                return false;
            }
        }
        final ActivityStack currentFocusedStack = getFocusedStack();
        if (ActivityTaskManagerDebugConfig.DEBUG_STACK) {
            Slog.d(TAG_STACK, "allResumedActivitiesComplete: mLastFocusedStack changing from="
                    + mLastFocusedStack + " to=" + currentFocusedStack);
        }
        mLastFocusedStack = currentFocusedStack;
        return true;
    }

    /**
     * Pause all activities in either all of the stacks or just the back stacks. This is done before
     * resuming a new activity and to make sure that previously active activities are
     * paused in stacks that are no longer visible or in pinned windowing mode. This does not
     * pause activities in visible stacks, so if an activity is launched within the same stack/task,
     * then we should explicitly pause that stack's top activity.
     * @param userLeaving Passed to pauseActivity() to indicate whether to call onUserLeaving().
     * @param resuming The resuming activity.
     * @return {@code true} if any activity was paused as a result of this call.
     */
    boolean pauseBackStacks(boolean userLeaving, ActivityRecord resuming) {
        boolean someActivityPaused = false;
        for (int stackNdx = getStackCount() - 1; stackNdx >= 0; --stackNdx) {
            final ActivityStack stack = getStackAt(stackNdx);
            final ActivityRecord resumedActivity = stack.getResumedActivity();
            if (resumedActivity != null
                    && (stack.getVisibility(resuming) != STACK_VISIBILITY_VISIBLE
                    || !stack.isTopActivityFocusable())) {
                if (DEBUG_STATES) Slog.d(TAG_STATES, "pauseBackStacks: stack=" + stack
                        + " mResumedActivity=" + resumedActivity);
                someActivityPaused |= stack.startPausingLocked(userLeaving, false /* uiSleeping*/,
                        resuming);
            }
        }
        return someActivityPaused;
    }

    /**
     * Find task for putting the Activity in.
     */
    void findTaskLocked(final ActivityRecord r, final boolean isPreferredDisplay,
            RootWindowContainer.FindTaskResult result) {
        mTmpFindTaskResult.clear();
        for (int stackNdx = getStackCount() - 1; stackNdx >= 0; --stackNdx) {
            final ActivityStack stack = getStackAt(stackNdx);
            if (!r.hasCompatibleActivityType(stack) && stack.isLeafTask()) {
                if (DEBUG_TASKS) {
                    Slog.d(TAG_TASKS, "Skipping stack: (mismatch activity/stack) " + stack);
                }
                continue;
            }

            mTmpFindTaskResult.process(r, stack);
            // It is possible to have tasks in multiple stacks with the same root affinity, so
            // we should keep looking after finding an affinity match to see if there is a
            // better match in another stack. Also, task affinity isn't a good enough reason
            // to target a display which isn't the source of the intent, so skip any affinity
            // matches not on the specified display.
            if (mTmpFindTaskResult.mRecord != null) {
                if (mTmpFindTaskResult.mIdealMatch) {
                    result.setTo(mTmpFindTaskResult);
                    return;
                } else if (isPreferredDisplay) {
                    // Note: since the traversing through the stacks is top down, the floating
                    // tasks should always have lower priority than any affinity-matching tasks
                    // in the fullscreen stacks
                    result.setTo(mTmpFindTaskResult);
                }
            }
        }
    }

    /**
     * Removes stacks in the input windowing modes from the system if they are of activity type
     * ACTIVITY_TYPE_STANDARD or ACTIVITY_TYPE_UNDEFINED
     */
    void removeStacksInWindowingModes(int... windowingModes) {
        if (windowingModes == null || windowingModes.length == 0) {
            return;
        }

        // Collect the stacks that are necessary to be removed instead of performing the removal
        // by looping mStacks, so that we don't miss any stacks after the stack size changed or
        // stacks reordered.
        final ArrayList<ActivityStack> stacks = new ArrayList<>();
        for (int j = windowingModes.length - 1; j >= 0; --j) {
            final int windowingMode = windowingModes[j];
            for (int i = getStackCount() - 1; i >= 0; --i) {
                final ActivityStack stack = getStackAt(i);
                if (!stack.isActivityTypeStandardOrUndefined()) {
                    continue;
                }
                if (stack.getWindowingMode() != windowingMode) {
                    continue;
                }
                stacks.add(stack);
            }
        }

        for (int i = stacks.size() - 1; i >= 0; --i) {
            mRootWindowContainer.mStackSupervisor.removeStack(stacks.get(i));
        }
    }

    void removeStacksWithActivityTypes(int... activityTypes) {
        if (activityTypes == null || activityTypes.length == 0) {
            return;
        }

        // Collect the stacks that are necessary to be removed instead of performing the removal
        // by looping mStacks, so that we don't miss any stacks after the stack size changed or
        // stacks reordered.
        final ArrayList<ActivityStack> stacks = new ArrayList<>();
        for (int j = activityTypes.length - 1; j >= 0; --j) {
            final int activityType = activityTypes[j];
            for (int i = getStackCount() - 1; i >= 0; --i) {
                final ActivityStack stack = getStackAt(i);
                // Collect the root tasks that are currently being organized.
                if (stack.isOrganized()) {
                    for (int k = stack.getChildCount() - 1; k >= 0; --k) {
                        final ActivityStack childStack = (ActivityStack) stack.getChildAt(k);
                        if (childStack.getActivityType() == activityType) {
                            stacks.add(childStack);
                        }
                    }
                } else if (stack.getActivityType() == activityType) {
                    stacks.add(stack);
                }
            }
        }

        for (int i = stacks.size() - 1; i >= 0; --i) {
            mRootWindowContainer.mStackSupervisor.removeStack(stacks.get(i));
        }
    }

    void onSplitScreenModeDismissed() {
        mAtmService.deferWindowLayout();
        try {
            mLaunchRootTask = null;
            moveSplitScreenTasksToFullScreen();
        } finally {
            final ActivityStack topFullscreenStack =
                    getTopStackInWindowingMode(WINDOWING_MODE_FULLSCREEN);
            final ActivityStack homeStack = getOrCreateRootHomeTask();
            if (topFullscreenStack != null && homeStack != null && !isTopStack(homeStack)) {
                // Whenever split-screen is dismissed we want the home stack directly behind the
                // current top fullscreen stack so it shows up when the top stack is finished.
                // TODO: Would be better to use ActivityDisplay.positionChildAt() for this, however
                // ActivityDisplay doesn't have a direct controller to WM side yet. We can switch
                // once we have that.
                homeStack.moveToFront("onSplitScreenModeDismissed");
                topFullscreenStack.moveToFront("onSplitScreenModeDismissed");
            }
            mAtmService.continueWindowLayout();
        }
    }

    private void moveSplitScreenTasksToFullScreen() {
        final WindowContainerTransaction wct = new WindowContainerTransaction();
        mTmpTasks.clear();
        forAllTasks(task -> {
            if (task.mCreatedByOrganizer && task.inSplitScreenWindowingMode() && task.hasChild()) {
                mTmpTasks.add(task);
            }
        });

        for (int i = mTmpTasks.size() - 1; i >= 0; i--) {
            final Task root = mTmpTasks.get(i);
            for (int j = 0; j < root.getChildCount(); j++) {
                wct.reparent(root.getChildAt(j).mRemoteToken, null, true /* toTop */);
            }
        }
        mAtmService.mWindowOrganizerController.applyTransaction(wct);
    }

    /**
     * Returns true if the {@param windowingMode} is supported based on other parameters passed in.
     * @param windowingMode The windowing mode we are checking support for.
     * @param supportsMultiWindow If we should consider support for multi-window mode in general.
     * @param supportsSplitScreen If we should consider support for split-screen multi-window.
     * @param supportsFreeform If we should consider support for freeform multi-window.
     * @param supportsPip If we should consider support for picture-in-picture mutli-window.
     * @param activityType The activity type under consideration.
     * @return true if the windowing mode is supported.
     */
    private boolean isWindowingModeSupported(int windowingMode, boolean supportsMultiWindow,
            boolean supportsSplitScreen, boolean supportsFreeform, boolean supportsPip,
            int activityType) {

        if (windowingMode == WINDOWING_MODE_UNDEFINED
                || windowingMode == WINDOWING_MODE_FULLSCREEN) {
            return true;
        }
        if (!supportsMultiWindow) {
            return false;
        }

        if (windowingMode == WINDOWING_MODE_MULTI_WINDOW) {
            return true;
        }

        final int displayWindowingMode = getWindowingMode();
        if (windowingMode == WINDOWING_MODE_SPLIT_SCREEN_PRIMARY
                || windowingMode == WINDOWING_MODE_SPLIT_SCREEN_SECONDARY) {
            return supportsSplitScreen
                    && WindowConfiguration.supportSplitScreenWindowingMode(activityType)
                    // Freeform windows and split-screen windows don't mix well, so prevent
                    // split windowing modes on freeform displays.
                    && displayWindowingMode != WINDOWING_MODE_FREEFORM;
        }

        if (!supportsFreeform && windowingMode == WINDOWING_MODE_FREEFORM) {
            return false;
        }

        if (!supportsPip && windowingMode == WINDOWING_MODE_PINNED) {
            return false;
        }
        return true;
    }

    /**
     * Resolves the windowing mode that an {@link ActivityRecord} would be in if started on this
     * display with the provided parameters.
     *
     * @param r The ActivityRecord in question.
     * @param options Options to start with.
     * @param task The task within-which the activity would start.
     * @param activityType The type of activity to start.
     * @return The resolved (not UNDEFINED) windowing-mode that the activity would be in.
     */
    int resolveWindowingMode(@Nullable ActivityRecord r, @Nullable ActivityOptions options,
            @Nullable Task task, int activityType) {

        // First preference if the windowing mode in the activity options if set.
        int windowingMode = (options != null)
                ? options.getLaunchWindowingMode() : WINDOWING_MODE_UNDEFINED;

        // If windowing mode is unset, then next preference is the candidate task, then the
        // activity record.
        if (windowingMode == WINDOWING_MODE_UNDEFINED) {
            if (task != null) {
                windowingMode = task.getWindowingMode();
            }
            if (windowingMode == WINDOWING_MODE_UNDEFINED && r != null) {
                windowingMode = r.getWindowingMode();
            }
            if (windowingMode == WINDOWING_MODE_UNDEFINED) {
                // Use the display's windowing mode.
                windowingMode = getWindowingMode();
            }
        }
        windowingMode = validateWindowingMode(windowingMode, r, task, activityType);
        return windowingMode != WINDOWING_MODE_UNDEFINED
                ? windowingMode : WINDOWING_MODE_FULLSCREEN;
    }

    /**
     * Check that the requested windowing-mode is appropriate for the specified task and/or activity
     * on this display.
     *
     * @param windowingMode The windowing-mode to validate.
     * @param r The {@link ActivityRecord} to check against.
     * @param task The {@link Task} to check against.
     * @param activityType An activity type.
     * @return The provided windowingMode or the closest valid mode which is appropriate.
     */
    int validateWindowingMode(int windowingMode, @Nullable ActivityRecord r, @Nullable Task task,
            int activityType) {
        // Make sure the windowing mode we are trying to use makes sense for what is supported.
        boolean supportsMultiWindow = mAtmService.mSupportsMultiWindow;
        boolean supportsSplitScreen = mAtmService.mSupportsSplitScreenMultiWindow;
        boolean supportsFreeform = mAtmService.mSupportsFreeformWindowManagement;
        boolean supportsPip = mAtmService.mSupportsPictureInPicture;
        if (supportsMultiWindow) {
            if (task != null) {
                supportsMultiWindow = task.isResizeable();
                supportsSplitScreen = task.supportsSplitScreenWindowingMode();
                // TODO: Do we need to check for freeform and Pip support here?
            } else if (r != null) {
                supportsMultiWindow = r.isResizeable();
                supportsSplitScreen = r.supportsSplitScreenWindowingMode();
                supportsFreeform = r.supportsFreeform();
                supportsPip = r.supportsPictureInPicture();
            }
        }

        final boolean inSplitScreenMode = isSplitScreenModeActivated();
        if (!inSplitScreenMode
                && windowingMode == WINDOWING_MODE_FULLSCREEN_OR_SPLIT_SCREEN_SECONDARY) {
            // Switch to the display's windowing mode if we are not in split-screen mode and we are
            // trying to launch in split-screen secondary.
            windowingMode = WINDOWING_MODE_UNDEFINED;
        } else if (inSplitScreenMode && (windowingMode == WINDOWING_MODE_FULLSCREEN
                || windowingMode == WINDOWING_MODE_UNDEFINED)
                && supportsSplitScreen) {
            windowingMode = WINDOWING_MODE_SPLIT_SCREEN_SECONDARY;
        }

        if (windowingMode != WINDOWING_MODE_UNDEFINED
                && isWindowingModeSupported(windowingMode, supportsMultiWindow, supportsSplitScreen,
                supportsFreeform, supportsPip, activityType)) {
            return windowingMode;
        }
        return WINDOWING_MODE_UNDEFINED;
    }

    boolean isTopStack(ActivityStack stack) {
        return stack == getTopStack();
    }

    boolean isTopNotPinnedStack(ActivityStack stack) {
        for (int i = getStackCount() - 1; i >= 0; --i) {
            final ActivityStack current = getStackAt(i);
            if (!current.inPinnedWindowingMode()) {
                return current == stack;
            }
        }
        return false;
    }

    ActivityRecord topRunningActivity() {
        return topRunningActivity(false /* considerKeyguardState */);
    }

    /**
     * Returns the top running activity in the focused stack. In the case the focused stack has no
     * such activity, the next focusable stack on this display is returned.
     *
     * @param considerKeyguardState Indicates whether the locked state should be considered. if
     *                              {@code true} and the keyguard is locked, only activities that
     *                              can be shown on top of the keyguard will be considered.
     * @return The top running activity. {@code null} if none is available.
     */
    ActivityRecord topRunningActivity(boolean considerKeyguardState) {
        ActivityRecord topRunning = null;
        final ActivityStack focusedStack = getFocusedStack();
        if (focusedStack != null) {
            topRunning = focusedStack.topRunningActivity();
        }

        // Look in other focusable stacks.
        if (topRunning == null) {
            for (int i = getStackCount() - 1; i >= 0; --i) {
                final ActivityStack stack = getStackAt(i);
                // Only consider focusable stacks other than the current focused one.
                if (stack == focusedStack || !stack.isTopActivityFocusable()) {
                    continue;
                }
                topRunning = stack.topRunningActivity();
                if (topRunning != null) {
                    break;
                }
            }
        }

        // This activity can be considered the top running activity if we are not considering
        // the locked state, the keyguard isn't locked, or we can show when locked.
        if (topRunning != null && considerKeyguardState
                && mRootWindowContainer.mStackSupervisor.getKeyguardController()
                        .isKeyguardLocked()
                && !topRunning.canShowWhenLocked()) {
            return null;
        }

        return topRunning;
    }

    boolean updateDisplayOverrideConfigurationLocked() {
        Configuration values = new Configuration();
        computeScreenConfiguration(values);

        mAtmService.mH.sendMessage(PooledLambda.obtainMessage(
                ActivityManagerInternal::updateOomLevelsForDisplay, mAtmService.mAmInternal,
                mDisplayId));

        Settings.System.clearConfiguration(values);
        updateDisplayOverrideConfigurationLocked(values, null /* starting */,
                false /* deferResume */, mAtmService.mTmpUpdateConfigurationResult);
        return mAtmService.mTmpUpdateConfigurationResult.changes != 0;
    }

    /**
     * Updates override configuration specific for the selected display. If no config is provided,
     * new one will be computed in WM based on current display info.
     */
    boolean updateDisplayOverrideConfigurationLocked(Configuration values,
            ActivityRecord starting, boolean deferResume,
            ActivityTaskManagerService.UpdateConfigurationResult result) {

        int changes = 0;
        boolean kept = true;

        mAtmService.deferWindowLayout();
        try {
            if (values != null) {
                if (mDisplayId == DEFAULT_DISPLAY) {
                    // Override configuration of the default display duplicates global config, so
                    // we're calling global config update instead for default display. It will also
                    // apply the correct override config.
                    changes = mAtmService.updateGlobalConfigurationLocked(values,
                            false /* initLocale */, false /* persistent */,
                            UserHandle.USER_NULL /* userId */, deferResume);
                } else {
                    changes = performDisplayOverrideConfigUpdate(values, deferResume);
                }
            }

            kept = mAtmService.ensureConfigAndVisibilityAfterUpdate(starting, changes);
        } finally {
            mAtmService.continueWindowLayout();
        }

        if (result != null) {
            result.changes = changes;
            result.activityRelaunched = !kept;
        }
        return kept;
    }

    int performDisplayOverrideConfigUpdate(Configuration values, boolean deferResume) {
        mTempConfig.setTo(getRequestedOverrideConfiguration());
        final int changes = mTempConfig.updateFrom(values);
        if (changes != 0) {
            Slog.i(TAG, "Override config changes=" + Integer.toHexString(changes) + " "
                    + mTempConfig + " for displayId=" + mDisplayId);
            onRequestedOverrideConfigurationChanged(mTempConfig);

            final boolean isDensityChange = (changes & ActivityInfo.CONFIG_DENSITY) != 0;
            if (isDensityChange && mDisplayId == DEFAULT_DISPLAY) {
                mAtmService.mAppWarnings.onDensityChanged();

                // Post message to start process to avoid possible deadlock of calling into AMS with
                // the ATMS lock held.
                final Message msg = PooledLambda.obtainMessage(
                        ActivityManagerInternal::killAllBackgroundProcessesExcept,
                        mAtmService.mAmInternal, N,
                        ActivityManager.PROCESS_STATE_IMPORTANT_FOREGROUND);
                mAtmService.mH.sendMessage(msg);
            }
            mWmService.mDisplayNotificationController.dispatchDisplayChanged(
                    this, getConfiguration());
        }
        return changes;
    }

    @Override
    public void onRequestedOverrideConfigurationChanged(Configuration overrideConfiguration) {
        final Configuration currOverrideConfig = getRequestedOverrideConfiguration();
        final int currRotation = currOverrideConfig.windowConfiguration.getRotation();
        final int overrideRotation = overrideConfiguration.windowConfiguration.getRotation();
        if (currRotation != ROTATION_UNDEFINED && currRotation != overrideRotation) {
            if (mFixedRotationLaunchingApp != null) {
                mFixedRotationLaunchingApp.clearFixedRotationTransform(
                        () -> applyRotation(currRotation, overrideRotation));
                // Clear the record because the display will sync to current rotation.
                mFixedRotationLaunchingApp = null;
            } else {
                applyRotation(currRotation, overrideRotation);
            }
        }
        mCurrentOverrideConfigurationChanges = currOverrideConfig.diff(overrideConfiguration);
        super.onRequestedOverrideConfigurationChanged(overrideConfiguration);
        mCurrentOverrideConfigurationChanges = 0;
        mWmService.setNewDisplayOverrideConfiguration(overrideConfiguration, this);
        mAtmService.addWindowLayoutReasons(
                ActivityTaskManagerService.LAYOUT_REASON_CONFIG_CHANGED);
    }

    /** Checks whether the given activity is in size compatibility mode and notifies the change. */
    void handleActivitySizeCompatModeIfNeeded(ActivityRecord r) {
        if (!r.isState(RESUMED) || r.getWindowingMode() != WINDOWING_MODE_FULLSCREEN) {
            // The callback is only interested in the foreground changes of fullscreen activity.
            return;
        }
        if (!r.inSizeCompatMode()) {
            if (mLastCompatModeActivity != null) {
                mAtmService.getTaskChangeNotificationController()
                        .notifySizeCompatModeActivityChanged(mDisplayId, null /* activityToken */);
            }
            mLastCompatModeActivity = null;
            return;
        }
        if (mLastCompatModeActivity == r) {
            return;
        }
        mLastCompatModeActivity = r;
        mAtmService.getTaskChangeNotificationController()
                .notifySizeCompatModeActivityChanged(mDisplayId, r.appToken);
    }

    boolean isUidPresent(int uid) {
        final PooledPredicate p = PooledLambda.obtainPredicate(
                ActivityRecord::isUid, PooledLambda.__(ActivityRecord.class), uid);
        final boolean isUidPresent = mDisplayContent.getActivity(p) != null;
        p.recycle();
        return isUidPresent;
    }

    /**
     * @see #mRemoved
     */
    boolean isRemoved() {
        return mRemoved;
    }

    /**
     * @see #mRemoving
     */
    boolean isRemoving() {
        return mRemoving;
    }

    void remove() {
        mRemoving = true;
        final boolean destroyContentOnRemoval = shouldDestroyContentOnRemove();
        ActivityStack lastReparentedStack = null;
        mPreferredTopFocusableStack = null;

        // Stacks could be reparented from the removed display to other display. While
        // reparenting the last stack of the removed display, the remove display is ready to be
        // released (no more ActivityStack). But, we cannot release it at that moment or the
        // related WindowContainer will also be removed. So, we set display as removed after
        // reparenting stack finished.
        final DisplayContent toDisplay = mRootWindowContainer.getDefaultDisplay();
        mRootWindowContainer.mStackSupervisor.beginDeferResume();
        try {
            int numStacks = getStackCount();
            // Keep the order from bottom to top.
            for (int stackNdx = 0; stackNdx < numStacks; stackNdx++) {
                final ActivityStack stack = getStackAt(stackNdx);
                // Always finish non-standard type stacks.
                if (destroyContentOnRemoval || !stack.isActivityTypeStandardOrUndefined()) {
                    stack.finishAllActivitiesImmediately();
                } else {
                    // If default display is in split-window mode, set windowing mode of the stack
                    // to split-screen secondary. Otherwise, set the windowing mode to undefined by
                    // default to let stack inherited the windowing mode from the new display.
                    final int windowingMode = toDisplay.isSplitScreenModeActivated()
                            ? WINDOWING_MODE_SPLIT_SCREEN_SECONDARY
                            : WINDOWING_MODE_UNDEFINED;
                    stack.reparent(toDisplay, true /* onTop */);
                    stack.setWindowingMode(windowingMode);
                    lastReparentedStack = stack;
                }
                // Stacks may be removed from this display. Ensure each stack will be processed and
                // the loop will end.
                stackNdx -= numStacks - getStackCount();
                numStacks = getStackCount();
            }
        } finally {
            mRootWindowContainer.mStackSupervisor.endDeferResume();
        }
        mRemoved = true;

        // Only update focus/visibility for the last one because there may be many stacks are
        // reparented and the intermediate states are unnecessary.
        if (lastReparentedStack != null) {
            lastReparentedStack.postReparent();
        }
        releaseSelfIfNeeded();
        mDisplayPolicy.release();

        if (!mAllSleepTokens.isEmpty()) {
            mRootWindowContainer.mSleepTokens.removeAll(mAllSleepTokens);
            mAllSleepTokens.clear();
            mAtmService.updateSleepIfNeededLocked();
        }
    }

    private void releaseSelfIfNeeded() {
        if (!mRemoved) {
            return;
        }

        final ActivityStack stack = getStackCount() == 1 ? getStackAt(0) : null;
        if (stack != null && stack.isActivityTypeHome() && !stack.hasChild()) {
            // Release this display if an empty home stack is the only thing left.
            // Since it is the last stack, this display will be released along with the stack
            // removal.
            stack.removeIfPossible();
        } else if (getTopStack() == null) {
            removeIfPossible();
            mRootWindowContainer.mStackSupervisor
                    .getKeyguardController().onDisplayRemoved(mDisplayId);
        }
    }

    /** Update and get all UIDs that are present on the display and have access to it. */
    IntArray getPresentUIDs() {
        mDisplayAccessUIDs.clear();
        final PooledConsumer c = PooledLambda.obtainConsumer(DisplayContent::addActivityUid,
                PooledLambda.__(ActivityRecord.class), mDisplayAccessUIDs);
        mDisplayContent.forAllActivities(c);
        c.recycle();
        return mDisplayAccessUIDs;
    }

    private static void addActivityUid(ActivityRecord r, IntArray uids) {
        uids.add(r.getUid());
    }

    @VisibleForTesting
    boolean shouldDestroyContentOnRemove() {
        return mDisplay.getRemoveMode() == REMOVE_MODE_DESTROY_CONTENT;
    }

    boolean shouldSleep() {
        return (getStackCount() == 0 || !mAllSleepTokens.isEmpty())
                && (mAtmService.mRunningVoice == null);
    }

    void setFocusedApp(ActivityRecord r, boolean moveFocusNow) {
        final ActivityRecord newFocus;
        final IBinder token = r.appToken;
        if (token == null) {
            ProtoLog.v(WM_DEBUG_FOCUS_LIGHT, "Clearing focused app, displayId=%d",
                    mDisplayId);
            newFocus = null;
        } else {
            newFocus = mWmService.mRoot.getActivityRecord(token);
            if (newFocus == null) {
                Slog.w(TAG_WM, "Attempted to set focus to non-existing app token: " + token
                        + ", displayId=" + mDisplayId);
            }
            ProtoLog.v(WM_DEBUG_FOCUS_LIGHT,
                    "Set focused app to: %s moveFocusNow=%b displayId=%d", newFocus,
                    moveFocusNow, mDisplayId);
        }

        final boolean changed = setFocusedApp(newFocus);
        if (moveFocusNow && changed) {
            mWmService.updateFocusedWindowLocked(UPDATE_FOCUS_NORMAL,
                    true /*updateInputWindows*/);
        }
    }

    /**
     * @return the stack currently above the {@param stack}.  Can be null if the {@param stack} is
     *         already top-most.
     */
    ActivityStack getStackAbove(ActivityStack stack) {
        final WindowContainer wc = stack.getParent();
        final int index = wc.mChildren.indexOf(stack) + 1;
        return (index < wc.mChildren.size()) ? (ActivityStack) wc.mChildren.get(index) : null;
    }

    /**
     * Adjusts the {@param stack} behind the last visible stack in the display if necessary.
     * Generally used in conjunction with {@link #moveStackBehindStack}.
     */
    // TODO(b/151575894): Remove special stack movement methods.
    void moveStackBehindBottomMostVisibleStack(ActivityStack stack) {
        if (stack.shouldBeVisible(null)) {
            // Skip if the stack is already visible
            return;
        }

        final boolean isRootTask = stack.isRootTask();
        if (isRootTask) {
            // Move the stack to the bottom to not affect the following visibility checks
            positionStackAtBottom(stack);
        } else {
            stack.getParent().positionChildAt(POSITION_BOTTOM, stack, false /* includingParents */);
        }

        // Find the next position where the stack should be placed
        final int numStacks = isRootTask ? getStackCount() : stack.getParent().getChildCount();
        for (int stackNdx = 0; stackNdx < numStacks; stackNdx++) {
            final ActivityStack s = isRootTask ? getStackAt(stackNdx)
                    : (ActivityStack) stack.getParent().getChildAt(stackNdx);
            if (s == stack) {
                continue;
            }
            final int winMode = s.getWindowingMode();
            final boolean isValidWindowingMode = winMode == WINDOWING_MODE_FULLSCREEN
                    || winMode == WINDOWING_MODE_SPLIT_SCREEN_SECONDARY;
            if (s.shouldBeVisible(null) && isValidWindowingMode) {
                // Move the provided stack to behind this stack
                final int position = Math.max(0, stackNdx - 1);
                if (isRootTask) {
                    positionStackAt(stack, position);
                } else {
                    stack.getParent().positionChildAt(position, stack, false /*includingParents */);
                }
                break;
            }
        }
    }

    /**
     * Moves the {@param stack} behind the given {@param behindStack} if possible. If
     * {@param behindStack} is not currently in the display, then then the stack is moved to the
     * back. Generally used in conjunction with {@link #moveStackBehindBottomMostVisibleStack}.
     */
    void moveStackBehindStack(ActivityStack stack, ActivityStack behindStack) {
        if (behindStack == null || behindStack == stack) {
            return;
        }

        final WindowContainer parent = stack.getParent();
        if (parent == null || parent != behindStack.getParent()) {
            return;
        }

        // Note that positionChildAt will first remove the given stack before inserting into the
        // list, so we need to adjust the insertion index to account for the removed index
        // TODO: Remove this logic when WindowContainer.positionChildAt() is updated to adjust the
        //       position internally
        final int stackIndex = parent.mChildren.indexOf(stack);
        final int behindStackIndex = parent.mChildren.indexOf(behindStack);
        final int insertIndex = stackIndex <= behindStackIndex
                ? behindStackIndex - 1 : behindStackIndex;
        final int position = Math.max(0, insertIndex);
        if (stack.isRootTask()) {
            positionStackAt(stack, position);
        } else {
            parent.positionChildAt(position, stack, false /* includingParents */);
        }
    }

    void ensureActivitiesVisible(ActivityRecord starting, int configChanges,
            boolean preserveWindows, boolean notifyClients) {
        for (int stackNdx = getStackCount() - 1; stackNdx >= 0; --stackNdx) {
            final ActivityStack stack = getStackAt(stackNdx);
            stack.ensureActivitiesVisible(starting, configChanges, preserveWindows,
                    notifyClients);
        }
    }

    void moveHomeStackToFront(String reason) {
        final ActivityStack homeStack = getOrCreateRootHomeTask();
        if (homeStack != null) {
            homeStack.moveToFront(reason);
        }
    }

    /**
     * Moves the focusable home activity to top. If there is no such activity, the home stack will
     * still move to top.
     */
    void moveHomeActivityToTop(String reason) {
        final ActivityRecord top = getHomeActivity();
        if (top == null) {
            moveHomeStackToFront(reason);
            return;
        }
        top.moveFocusableActivityToTop(reason);
    }

    @Nullable
    ActivityRecord getHomeActivity() {
        return getHomeActivityForUser(mRootWindowContainer.mCurrentUser);
    }

    @Nullable
    ActivityRecord getHomeActivityForUser(int userId) {
        final ActivityStack homeStack = getRootHomeTask();
        if (homeStack == null) {
            return null;
        }

        final PooledPredicate p = PooledLambda.obtainPredicate(
                DisplayContent::isHomeActivityForUser, PooledLambda.__(ActivityRecord.class),
                userId);
        final ActivityRecord r = homeStack.getActivity(p);
        p.recycle();
        return r;
    }

    private static boolean isHomeActivityForUser(ActivityRecord r, int userId) {
        return r.isActivityTypeHome() && (userId == UserHandle.USER_ALL || r.mUserId == userId);
    }

    boolean isSleeping() {
        return mSleeping;
    }

    void setIsSleeping(boolean asleep) {
        mSleeping = asleep;
    }

    /**
     * Adds a listener to be notified whenever the stack order in the display changes. Currently
     * only used by the {@link RecentsAnimation} to determine whether to interrupt and cancel the
     * current animation when the system state changes.
     */
    void registerStackOrderChangedListener(OnStackOrderChangedListener listener) {
        if (!mStackOrderChangedCallbacks.contains(listener)) {
            mStackOrderChangedCallbacks.add(listener);
        }
    }

    /**
     * Removes a previously registered stack order change listener.
     */
    void unregisterStackOrderChangedListener(OnStackOrderChangedListener listener) {
        mStackOrderChangedCallbacks.remove(listener);
    }

    /**
     * Notifies of a stack order change
     * @param stack The stack which triggered the order change
     */
    private void onStackOrderChanged(ActivityStack stack) {
        for (int i = mStackOrderChangedCallbacks.size() - 1; i >= 0; i--) {
            mStackOrderChangedCallbacks.get(i).onStackOrderChanged(stack);
        }
    }

    void setDisplayToSingleTaskInstance() {
        final int childCount = getStackCount();
        if (childCount > 1) {
            throw new IllegalArgumentException("Display already has multiple stacks. display="
                    + this);
        }
        if (childCount > 0) {
            final ActivityStack stack = getStackAt(0);
            if (stack.getChildCount() > 1) {
                throw new IllegalArgumentException("Display stack already has multiple tasks."
                        + " display=" + this + " stack=" + stack);
            }
        }

        mSingleTaskInstance = true;
    }

    /** Returns true if the display can only contain one task */
    boolean isSingleTaskInstance() {
        return mSingleTaskInstance;
    }

    @VisibleForTesting
    void removeAllTasks() {
        forAllTasks((t) -> { t.getStack().removeChild(t, "removeAllTasks"); });
    }

    /**
     * Callback for when the order of the stacks in the display changes.
     */
    interface OnStackOrderChangedListener {
        void onStackOrderChanged(ActivityStack stack);
    }

    public void dumpStacks(PrintWriter pw) {
        for (int i = getStackCount() - 1; i >= 0; --i) {
            pw.print(getStackAt(i).getRootTaskId());
            if (i > 0) {
                pw.print(",");
            }
        }
    }

    /**
     * Similar to {@link RootWindowContainer#isAnyNonToastWindowVisibleForUid(int)}, but
     * used for pid.
     */
    boolean isAnyNonToastWindowVisibleForPid(int pid) {
        final PooledPredicate p = PooledLambda.obtainPredicate(
                WindowState::isNonToastWindowVisibleForPid,
                PooledLambda.__(WindowState.class), pid);

        final WindowState w = getWindow(p);
        p.recycle();
        return w != null;
    }

    Context getDisplayUiContext() {
        return mDisplayPolicy.getSystemUiContext();
    }

    class RemoteInsetsControlTarget implements InsetsControlTarget {
        private final IDisplayWindowInsetsController mRemoteInsetsController;

        RemoteInsetsControlTarget(IDisplayWindowInsetsController controller) {
            mRemoteInsetsController = controller;
        }

        void notifyInsetsChanged() {
            try {
                mRemoteInsetsController.insetsChanged(
                        getInsetsStateController().getRawInsetsState());
            } catch (RemoteException e) {
                Slog.w(TAG, "Failed to deliver inset state change", e);
            }
        }

        @Override
        public void notifyInsetsControlChanged() {
            final InsetsStateController stateController = getInsetsStateController();
            try {
                mRemoteInsetsController.insetsControlChanged(stateController.getRawInsetsState(),
                        stateController.getControlsForDispatch(this));
            } catch (RemoteException e) {
                Slog.w(TAG, "Failed to deliver inset state change", e);
            }
        }

        @Override
        public void showInsets(@WindowInsets.Type.InsetsType int types, boolean fromIme) {
            try {
                mRemoteInsetsController.showInsets(types, fromIme);
            } catch (RemoteException e) {
                Slog.w(TAG, "Failed to deliver showInsets", e);
            }
        }

        @Override
        public void hideInsets(@WindowInsets.Type.InsetsType int types, boolean fromIme) {
            try {
                mRemoteInsetsController.hideInsets(types, fromIme);
            } catch (RemoteException e) {
                Slog.w(TAG, "Failed to deliver showInsets", e);
            }
        }
    }
}
