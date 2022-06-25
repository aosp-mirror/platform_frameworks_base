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
import static android.app.WindowConfiguration.ROTATION_UNDEFINED;
import static android.app.WindowConfiguration.WINDOWING_MODE_FREEFORM;
import static android.app.WindowConfiguration.WINDOWING_MODE_FULLSCREEN;
import static android.app.WindowConfiguration.WINDOWING_MODE_MULTI_WINDOW;
import static android.app.WindowConfiguration.WINDOWING_MODE_PINNED;
import static android.app.WindowConfiguration.WINDOWING_MODE_UNDEFINED;
import static android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSET;
import static android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED;
import static android.content.res.Configuration.ORIENTATION_LANDSCAPE;
import static android.content.res.Configuration.ORIENTATION_PORTRAIT;
import static android.content.res.Configuration.ORIENTATION_UNDEFINED;
import static android.os.Build.VERSION_CODES.N;
import static android.os.Process.SYSTEM_UID;
import static android.os.Trace.TRACE_TAG_WINDOW_MANAGER;
import static android.util.DisplayMetrics.DENSITY_DEFAULT;
import static android.util.RotationUtils.deltaRotation;
import static android.view.Display.DEFAULT_DISPLAY;
import static android.view.Display.FLAG_CAN_SHOW_WITH_INSECURE_KEYGUARD;
import static android.view.Display.FLAG_PRIVATE;
import static android.view.Display.FLAG_SHOULD_SHOW_SYSTEM_DECORATIONS;
import static android.view.Display.INVALID_DISPLAY;
import static android.view.Display.REMOVE_MODE_DESTROY_CONTENT;
import static android.view.Display.STATE_UNKNOWN;
import static android.view.Display.isSuspendedState;
import static android.view.InsetsState.ITYPE_IME;
import static android.view.InsetsState.ITYPE_LEFT_GESTURES;
import static android.view.InsetsState.ITYPE_NAVIGATION_BAR;
import static android.view.InsetsState.ITYPE_RIGHT_GESTURES;
import static android.view.Surface.ROTATION_0;
import static android.view.Surface.ROTATION_270;
import static android.view.Surface.ROTATION_90;
import static android.view.View.GONE;
import static android.view.ViewRootImpl.LOCAL_LAYOUT;
import static android.view.WindowInsets.Type.displayCutout;
import static android.view.WindowInsets.Type.ime;
import static android.view.WindowInsets.Type.systemBars;
import static android.view.WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE;
import static android.view.WindowManager.DISPLAY_IME_POLICY_FALLBACK_DISPLAY;
import static android.view.WindowManager.DISPLAY_IME_POLICY_LOCAL;
import static android.view.WindowManager.LayoutParams;
import static android.view.WindowManager.LayoutParams.FIRST_APPLICATION_WINDOW;
import static android.view.WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE;
import static android.view.WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
import static android.view.WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL;
import static android.view.WindowManager.LayoutParams.INVALID_WINDOW_TYPE;
import static android.view.WindowManager.LayoutParams.LAST_APPLICATION_WINDOW;
import static android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN;
import static android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_HIDDEN;
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
import static android.view.WindowManager.TRANSIT_CHANGE;
import static android.view.WindowManager.TRANSIT_NONE;
import static android.view.WindowManager.TRANSIT_OPEN;
import static android.view.WindowManager.TRANSIT_TO_FRONT;
import static android.window.DisplayAreaOrganizer.FEATURE_IME;
import static android.window.DisplayAreaOrganizer.FEATURE_ROOT;
import static android.window.DisplayAreaOrganizer.FEATURE_WINDOWED_MAGNIFICATION;

import static com.android.internal.protolog.ProtoLogGroup.WM_DEBUG_APP_TRANSITIONS;
import static com.android.internal.protolog.ProtoLogGroup.WM_DEBUG_BOOT;
import static com.android.internal.protolog.ProtoLogGroup.WM_DEBUG_CONTENT_RECORDING;
import static com.android.internal.protolog.ProtoLogGroup.WM_DEBUG_FOCUS;
import static com.android.internal.protolog.ProtoLogGroup.WM_DEBUG_FOCUS_LIGHT;
import static com.android.internal.protolog.ProtoLogGroup.WM_DEBUG_IME;
import static com.android.internal.protolog.ProtoLogGroup.WM_DEBUG_ORIENTATION;
import static com.android.internal.protolog.ProtoLogGroup.WM_DEBUG_SCREEN_ON;
import static com.android.internal.protolog.ProtoLogGroup.WM_DEBUG_WALLPAPER;
import static com.android.internal.protolog.ProtoLogGroup.WM_SHOW_TRANSACTIONS;
import static com.android.internal.util.LatencyTracker.ACTION_ROTATE_SCREEN;
import static com.android.server.policy.WindowManagerPolicy.FINISH_LAYOUT_REDO_ANIM;
import static com.android.server.policy.WindowManagerPolicy.FINISH_LAYOUT_REDO_CONFIG;
import static com.android.server.policy.WindowManagerPolicy.FINISH_LAYOUT_REDO_LAYOUT;
import static com.android.server.policy.WindowManagerPolicy.FINISH_LAYOUT_REDO_WALLPAPER;
import static com.android.server.wm.ActivityRecord.State.RESUMED;
import static com.android.server.wm.ActivityTaskManagerService.POWER_MODE_REASON_CHANGE_DISPLAY;
import static com.android.server.wm.DisplayContentProto.APP_TRANSITION;
import static com.android.server.wm.DisplayContentProto.CLOSING_APPS;
import static com.android.server.wm.DisplayContentProto.CURRENT_FOCUS;
import static com.android.server.wm.DisplayContentProto.DISPLAY_FRAMES;
import static com.android.server.wm.DisplayContentProto.DISPLAY_INFO;
import static com.android.server.wm.DisplayContentProto.DISPLAY_READY;
import static com.android.server.wm.DisplayContentProto.DISPLAY_ROTATION;
import static com.android.server.wm.DisplayContentProto.DPI;
import static com.android.server.wm.DisplayContentProto.FOCUSED_APP;
import static com.android.server.wm.DisplayContentProto.FOCUSED_ROOT_TASK_ID;
import static com.android.server.wm.DisplayContentProto.ID;
import static com.android.server.wm.DisplayContentProto.IME_INSETS_SOURCE_PROVIDER;
import static com.android.server.wm.DisplayContentProto.IME_POLICY;
import static com.android.server.wm.DisplayContentProto.INPUT_METHOD_CONTROL_TARGET;
import static com.android.server.wm.DisplayContentProto.INPUT_METHOD_INPUT_TARGET;
import static com.android.server.wm.DisplayContentProto.INPUT_METHOD_TARGET;
import static com.android.server.wm.DisplayContentProto.INSETS_SOURCE_PROVIDERS;
import static com.android.server.wm.DisplayContentProto.IS_SLEEPING;
import static com.android.server.wm.DisplayContentProto.KEEP_CLEAR_AREAS;
import static com.android.server.wm.DisplayContentProto.MIN_SIZE_OF_RESIZEABLE_TASK_DP;
import static com.android.server.wm.DisplayContentProto.OPENING_APPS;
import static com.android.server.wm.DisplayContentProto.RESUMED_ACTIVITY;
import static com.android.server.wm.DisplayContentProto.ROOT_DISPLAY_AREA;
import static com.android.server.wm.DisplayContentProto.SCREEN_ROTATION_ANIMATION;
import static com.android.server.wm.DisplayContentProto.SLEEP_TOKENS;
import static com.android.server.wm.SurfaceAnimator.ANIMATION_TYPE_WINDOW_ANIMATION;
import static com.android.server.wm.WindowContainer.AnimationFlags.PARENTS;
import static com.android.server.wm.WindowContainer.AnimationFlags.TRANSITION;
import static com.android.server.wm.WindowContainerChildProto.DISPLAY_CONTENT;
import static com.android.server.wm.WindowManagerDebugConfig.DEBUG_DISPLAY;
import static com.android.server.wm.WindowManagerDebugConfig.DEBUG_INPUT_METHOD;
import static com.android.server.wm.WindowManagerDebugConfig.DEBUG_LAYOUT;
import static com.android.server.wm.WindowManagerDebugConfig.DEBUG_LAYOUT_REPEATS;
import static com.android.server.wm.WindowManagerDebugConfig.DEBUG_SCREENSHOT;
import static com.android.server.wm.WindowManagerDebugConfig.SHOW_STACK_CRAWLS;
import static com.android.server.wm.WindowManagerDebugConfig.TAG_WITH_CLASS_NAME;
import static com.android.server.wm.WindowManagerDebugConfig.TAG_WM;
import static com.android.server.wm.WindowManagerService.H.REPORT_HARD_KEYBOARD_STATUS_CHANGE;
import static com.android.server.wm.WindowManagerService.H.WINDOW_HIDE_TIMEOUT;
import static com.android.server.wm.WindowManagerService.UPDATE_FOCUS_PLACING_SURFACES;
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
import android.content.Context;
import android.content.pm.ActivityInfo;
import android.content.pm.ActivityInfo.ScreenOrientation;
import android.content.res.CompatibilityInfo;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.ColorSpace;
import android.graphics.Matrix;
import android.graphics.Point;
import android.graphics.Rect;
import android.graphics.Region;
import android.graphics.Region.Op;
import android.hardware.HardwareBuffer;
import android.hardware.display.DisplayManagerInternal;
import android.metrics.LogMaker;
import android.os.Bundle;
import android.os.Debug;
import android.os.Handler;
import android.os.IBinder;
import android.os.Message;
import android.os.RemoteCallbackList;
import android.os.RemoteException;
import android.os.SystemClock;
import android.os.Trace;
import android.os.UserHandle;
import android.provider.Settings;
import android.util.ArraySet;
import android.util.DisplayMetrics;
import android.util.DisplayUtils;
import android.util.IntArray;
import android.util.RotationUtils;
import android.util.Size;
import android.util.Slog;
import android.util.SparseArray;
import android.util.SparseBooleanArray;
import android.util.proto.ProtoOutputStream;
import android.view.ContentRecordingSession;
import android.view.Display;
import android.view.DisplayCutout;
import android.view.DisplayInfo;
import android.view.Gravity;
import android.view.IDisplayWindowInsetsController;
import android.view.ISystemGestureExclusionListener;
import android.view.IWindow;
import android.view.InputChannel;
import android.view.InputDevice;
import android.view.InsetsSource;
import android.view.InsetsState;
import android.view.InsetsState.InternalInsetsType;
import android.view.InsetsVisibilities;
import android.view.MagnificationSpec;
import android.view.PrivacyIndicatorBounds;
import android.view.RemoteAnimationDefinition;
import android.view.RoundedCorners;
import android.view.Surface;
import android.view.Surface.Rotation;
import android.view.SurfaceControl;
import android.view.SurfaceControl.Transaction;
import android.view.SurfaceSession;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.view.WindowManager.DisplayImePolicy;
import android.view.WindowManagerPolicyConstants.PointerEventListener;
import android.window.DisplayWindowPolicyController;
import android.window.IDisplayAreaOrganizer;
import android.window.TransitionRequestInfo;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.logging.MetricsLogger;
import com.android.internal.logging.nano.MetricsProto.MetricsEvent;
import com.android.internal.protolog.common.ProtoLog;
import com.android.internal.util.ToBooleanFunction;
import com.android.internal.util.function.TriConsumer;
import com.android.internal.util.function.pooled.PooledConsumer;
import com.android.internal.util.function.pooled.PooledLambda;
import com.android.internal.util.function.pooled.PooledPredicate;
import com.android.server.inputmethod.InputMethodManagerInternal;
import com.android.server.policy.WindowManagerPolicy;
import com.android.server.wm.utils.RotationCache;
import com.android.server.wm.utils.WmDisplayCutout;

import java.io.PrintWriter;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Predicate;

/**
 * Utility class for keeping track of the WindowStates and other pertinent contents of a
 * particular Display.
 */
class DisplayContent extends RootDisplayArea implements WindowManagerPolicy.DisplayContentInfo {
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

    final ActivityTaskManagerService mAtmService;

    /**
     * Unique logical identifier of this display.
     *
     * @see DisplayInfo#displayId
     */
    final int mDisplayId;

    /**
     * Unique physical identifier of this display. Unlike {@link #mDisplayId} this value can change
     * at runtime if the underlying physical display changes.
     *
     * @see DisplayInfo#uniqueId
     */
    @Nullable
    String mCurrentUniqueDisplayId;

    /**
     * We organize all top-level Surfaces into the following layer.
     * It contains a few Surfaces which are always on top of others, and omitted from
     * Screen-Magnification, for example the strict mode flash or the fullscreen magnification
     * overlay.
     */
    private SurfaceControl mOverlayLayer;

    /**
     * The direct child layer of the display to put all non-overlay windows. This is also used for
     * screen rotation animation so that there is a parent layer to put the animation leash.
     */
    private SurfaceControl mWindowingLayer;

    /**
     * Delegate for handling all logic around content recording; decides if this DisplayContent is
     * recording, and if so, applies necessary updates to SurfaceFlinger.
     */
    @Nullable
    private ContentRecorder mContentRecorder;

    /**
     * The default per Display minimal size of tasks. Calculated at construction.
     */
    int mMinSizeOfResizeableTaskDp = -1;

    // Contains all IME window containers. Note that the z-ordering of the IME windows will depend
    // on the IME target. We mainly have this container grouping so we can keep track of all the IME
    // window containers together and move them in-sync if/when needed. We use a subclass of
    // WindowContainer which is omitted from screen magnification, as the IME is never magnified.
    // TODO(display-area): is "no magnification" in the comment still true?
    private final ImeContainer mImeWindowsContainer = new ImeContainer(mWmService);

    @VisibleForTesting
    DisplayAreaPolicy mDisplayAreaPolicy;

    private WindowState mTmpWindow;
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
    float mInitialPhysicalXDpi = 0.0f;
    float mInitialPhysicalYDpi = 0.0f;

    private Point mPhysicalDisplaySize;

    DisplayCutout mInitialDisplayCutout;
    private final RotationCache<DisplayCutout, WmDisplayCutout> mDisplayCutoutCache
            = new RotationCache<>(this::calculateDisplayCutoutForRotationUncached);
    boolean mIgnoreDisplayCutout;

    RoundedCorners mInitialRoundedCorners;
    private final RotationCache<RoundedCorners, RoundedCorners> mRoundedCornerCache =
            new RotationCache<>(this::calculateRoundedCornersForRotationUncached);

    PrivacyIndicatorBounds mCurrentPrivacyIndicatorBounds = new PrivacyIndicatorBounds();
    private final RotationCache<PrivacyIndicatorBounds, PrivacyIndicatorBounds>
            mPrivacyIndicatorBoundsCache = new
            RotationCache<>(this::calculatePrivacyIndicatorBoundsForRotationUncached);

    /**
     * Overridden display size. Initialized with {@link #mInitialDisplayWidth}
     * and {@link #mInitialDisplayHeight}, but can be set via shell command "adb shell wm size".
     * @see WindowManagerService#setForcedDisplaySize(int, int, int)
     */
    int mBaseDisplayWidth = 0;
    int mBaseDisplayHeight = 0;
    DisplayCutout mBaseDisplayCutout;
    RoundedCorners mBaseRoundedCorners;
    boolean mIsSizeForced = false;

    /**
     * Overridden display size and metrics to activity window bounds. Set via
     * "adb shell wm set-sandbox-display-apis". Default to true, since only disable for debugging.
     * @see WindowManagerService#setSandboxDisplayApis(int, boolean)
     */
    private boolean mSandboxDisplayApis = true;

    /**
     * Overridden display density for current user. Initialized with {@link #mInitialDisplayDensity}
     * but can be set from Settings or via shell command "adb shell wm density".
     * @see WindowManagerService#setForcedDisplayDensityForUser(int, int, int)
     */
    int mBaseDisplayDensity = 0;
    boolean mIsDensityForced = false;

    /**
     * Overridden display physical dpi.
     */
    float mBaseDisplayPhysicalXDpi = 0.0f;
    float mBaseDisplayPhysicalYDpi = 0.0f;

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

    private Set<Rect> mRestrictedKeepClearAreas = new ArraySet<>();
    private Set<Rect> mUnrestrictedKeepClearAreas = new ArraySet<>();

    /**
     * For default display it contains real metrics, empty for others.
     * @see WindowManagerService#createWatermark()
     */
    final DisplayMetrics mRealDisplayMetrics = new DisplayMetrics();

    /** @see #computeCompatSmallestWidth(boolean, int, int, int) */
    private final DisplayMetrics mTmpDisplayMetrics = new DisplayMetrics();

    /**
     * Compat metrics computed based on {@link #mDisplayMetrics}.
     * @see #updateDisplayAndOrientation(int, Configuration)
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
     * Keep track of wallpaper visibility to notify changes.
     */
    private boolean mLastWallpaperVisible = false;

    // Accessed directly by all users.
    private boolean mLayoutNeeded;
    int pendingLayoutChanges;
    boolean mLayoutAndAssignWindowLayersScheduled;

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

    /** Detect user tapping outside of current focused task bounds .*/
    @VisibleForTesting
    final TaskTapPointerEventListener mTapDetector;

    /** Detect user tapping outside of current focused root task bounds .*/
    private Region mTouchExcludeRegion = new Region();

    /** Save allocating when calculating rects */
    private final Rect mTmpRect = new Rect();
    private final Rect mTmpRect2 = new Rect();
    private final Region mTmpRegion = new Region();

    private final Configuration mTmpConfiguration = new Configuration();

    /** Remove this display when animation on it has completed. */
    private boolean mDeferredRemoval;

    final DockedTaskDividerController mDividerControllerLocked;
    final PinnedTaskController mPinnedTaskController;

    final ArrayList<WindowState> mTapExcludedWindows = new ArrayList<>();
    /** A collection of windows that provide tap exclude regions inside of them. */
    final ArraySet<WindowState> mTapExcludeProvidingWindows = new ArraySet<>();

    private final LinkedList<ActivityRecord> mTmpUpdateAllDrawn = new LinkedList();

    private final TaskForResizePointSearchResult mTmpTaskForResizePointSearchResult =
            new TaskForResizePointSearchResult();
    private final ApplySurfaceChangesTransactionState mTmpApplySurfaceChangesTransactionState =
            new ApplySurfaceChangesTransactionState();

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
     * The foreground app of this display. Windows below this app cannot be the focused window. If
     * the user taps on the area outside of the task of the focused app, we will notify AM about the
     * new task the user wants to interact with.
     */
    ActivityRecord mFocusedApp = null;

    /**
     * We only respect the orientation request from apps below this {@link TaskDisplayArea}.
     * It is the last focused {@link TaskDisplayArea} on this display that handles orientation
     * request.
     */
    @Nullable
    private TaskDisplayArea mOrientationRequestingTaskDisplayArea = null;

    /**
     * The launching activity which is using fixed rotation transformation.
     *
     * @see #handleTopActivityLaunchingInDifferentOrientation
     * @see #setFixedRotationLaunchingApp(ActivityRecord, int)
     * @see DisplayRotation#shouldRotateSeamlessly
     */
    private ActivityRecord mFixedRotationLaunchingApp;

    /** The delay to avoid toggling the animation quickly. */
    private static final long FIXED_ROTATION_HIDE_ANIMATION_DEBOUNCE_DELAY_MS = 250;
    private AsyncRotationController mAsyncRotationController;

    final FixedRotationTransitionListener mFixedRotationTransitionListener =
            new FixedRotationTransitionListener();

    private final PhysicalDisplaySwitchTransitionLauncher mDisplaySwitchTransitionLauncher;
    final RemoteDisplayChangeController mRemoteDisplayChangeController;

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
    private boolean mUpdateImeRequestedWhileDeferred;

    private MagnificationSpec mMagnificationSpec;

    private InputMonitor mInputMonitor;

    /** Caches the value whether told display manager that we have content. */
    private boolean mLastHasContent;

    /**
     * The input method window for this display.
     */
    WindowState mInputMethodWindow;

    /**
     * This just indicates the window the input method is on top of, not
     * necessarily the window its input is going to.
     */
    private WindowState mImeLayeringTarget;

    /**
     * The window which receives input from the input method. This is also a candidate of the
     * input method control target.
     */
    private InputTarget mImeInputTarget;

    /**
     * The last ime input target processed from setImeLayeringTargetInner
     * this is to ensure we update the control target in the case when the IME
     * target changes while the IME layering target stays the same, for example
     * the case of the IME moving to a SurfaceControlViewHost backed EmbeddedWindow
     */
    private InputTarget mLastImeInputTarget;

    /**
     * This controls the visibility and animation of the input method window.
     */
    private InsetsControlTarget mImeControlTarget;

    /**
     * Used by {@link #getImeTarget} to return the IME target which the input method window on
     * top of for adjusting input method window surface layer Z-Ordering.
     *
     * @see #mImeLayeringTarget
     */
    static final int IME_TARGET_LAYERING = 0;

    /**
     * Used by {@link #getImeTarget} to return the IME target which controls the IME insets
     * visibility and animation.
     *
     * @see #mImeControlTarget
     */
    static final int IME_TARGET_CONTROL = 2;

    @IntDef(flag = false, prefix = { "IME_TARGET_" }, value = {
            IME_TARGET_LAYERING,
            IME_TARGET_CONTROL,
    })
    @Retention(RetentionPolicy.SOURCE)
    @interface InputMethodTarget {}

    /** The surface parent of the IME container. */
    @VisibleForTesting
    SurfaceControl mInputMethodSurfaceParent;

    private final PointerEventDispatcher mPointerEventDispatcher;

    private final InsetsStateController mInsetsStateController;
    private final InsetsPolicy mInsetsPolicy;

    /** Corner radius that windows should have in order to match the display. */
    private final float mWindowCornerRadius;

    final SparseArray<ShellRoot> mShellRoots = new SparseArray<>();
    RemoteInsetsControlTarget mRemoteInsetsControlTarget = null;
    private final IBinder.DeathRecipient mRemoteInsetsDeath =
            () -> {
                synchronized (mWmService.mGlobalLock) {
                    mRemoteInsetsControlTarget = null;
                }
            };

    private RootWindowContainer mRootWindowContainer;

    /** Array of all UIDs that are present on the display. */
    private IntArray mDisplayAccessUIDs = new IntArray();

    /** All tokens used to put activities on this root task to sleep (including mOffToken) */
    final ArrayList<RootWindowContainer.SleepToken> mAllSleepTokens = new ArrayList<>();
    /** The token acquirer to put root tasks on the display to sleep */
    private final ActivityTaskManagerInternal.SleepTokenAcquirer mOffTokenAcquirer;

    private boolean mSleeping;

    /** We started the process of removing the display from the system. */
    private boolean mRemoving;

    /**
     * The display is removed from the system and we are just waiting for all activities on it to be
     * finished before removing this object.
     */
    private boolean mRemoved;

    /** Set of activities in foreground size compat mode. */
    private Set<ActivityRecord> mActiveSizeCompatActivities = new ArraySet<>();

    // Used in updating the display size
    private Point mTmpDisplaySize = new Point();

    // Used in updating override configurations
    private final Configuration mTempConfig = new Configuration();

    /**
     * Used to prevent recursions when calling
     * {@link #ensureActivitiesVisible(ActivityRecord, int, boolean, boolean)}
     */
    private boolean mInEnsureActivitiesVisible = false;

    // Used to indicate that the movement of child tasks to top will not move the display to top as
    // well and thus won't change the top resumed / focused record
    boolean mDontMoveToTop;

    /**
     * The helper of policy controller.
     *
     * @see DisplayWindowPolicyControllerHelper
     */
    DisplayWindowPolicyControllerHelper mDwpcHelper;

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
        ProtoLog.v(WM_DEBUG_FOCUS, "Looking for focus: %s, flags=%d, canReceive=%b, reason=%s",
                w, w.mAttrs.flags, w.canReceiveKeys(),
                w.canReceiveKeysReason(false /* fromUserTouch */));

        if (!w.canReceiveKeys()) {
            return false;
        }

        // When switching the app task, we keep the IME window visibility for better
        // transitioning experiences.
        // However, in case IME created a child window or the IME selection dialog without
        // dismissing during the task switching to keep the window focus because IME window has
        // higher window hierarchy, we don't give it focus if the next IME layering target
        // doesn't request IME visible.
        if (w.mIsImWindow && w.isChildWindow() && (mImeLayeringTarget == null
                || !mImeLayeringTarget.getRequestedVisibility(ITYPE_IME))) {
            return false;
        }
        if (w.mAttrs.type == TYPE_INPUT_METHOD_DIALOG && mImeLayeringTarget != null
                && !mImeLayeringTarget.getRequestedVisibility(ITYPE_IME)
                && !mImeLayeringTarget.isVisibleRequested()) {
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
                // App root task below focused app root task. No focus for you!!!
                ProtoLog.v(WM_DEBUG_FOCUS_LIGHT,
                        "findFocusedWindow: Reached focused app=%s", focusedApp);
                mTmpWindow = null;
                return true;
            }

            // If the candidate activity is currently being embedded in the focused task, the
            // activity cannot be focused unless it is on the same TaskFragment as the focusedApp's.
            TaskFragment parent = activity.getTaskFragment();
            if (parent != null && parent.isEmbedded()) {
                Task hostTask = focusedApp.getTask();
                if (hostTask.isEmbedded()) {
                    // Use the hosting task if the current task is embedded.
                    hostTask = hostTask.getParent().asTaskFragment().getTask();
                }
                if (activity.isDescendantOf(hostTask)
                        && activity.getTaskFragment() != focusedApp.getTaskFragment()) {
                    return false;
                }
            }
        }

        ProtoLog.v(WM_DEBUG_FOCUS_LIGHT, "findFocusedWindow: Found new focus @ %s", w);
        mTmpWindow = w;
        return true;
    };

    private final Consumer<WindowState> mPerformLayout = w -> {
        if (w.mLayoutAttached) {
            return;
        }

        // Don't do layout of a window if it is not visible, or soon won't be visible, to avoid
        // wasting time and funky changes while a window is animating away.
        final boolean gone = w.isGoneForLayout();

        if (DEBUG_LAYOUT) {
            Slog.v(TAG, "1ST PASS " + w + ": gone=" + gone + " mHaveFrame=" + w.mHaveFrame
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

        // If this view is GONE, then skip it -- keep the current frame, and let the caller know
        // so they can ignore it if they want.  (We do the normal layout for INVISIBLE windows,
        // since that means "perform layout as normal, just don't display").
        if (!gone || !w.mHaveFrame || w.mLayoutNeeded) {
            if (mTmpInitial) {
                w.resetContentChanged();
            }
            w.mSurfacePlacementNeeded = true;
            w.mLayoutNeeded = false;
            final boolean firstLayout = !w.isLaidOut();
            getDisplayPolicy().layoutWindowLw(w, null, mDisplayFrames);
            w.mLayoutSeq = mLayoutSeq;

            // If this is the first layout, we need to initialize the last frames and inset values,
            // as otherwise we'd immediately cause an unnecessary resize.
            if (firstLayout) {
                // The client may compute its actual requested size according to the first layout,
                // so we still request the window to resize if the current frame is empty.
                if (!w.getFrame().isEmpty()) {
                    w.updateLastFrames();
                }
                w.onResizeHandled();
            }

            if (DEBUG_LAYOUT) Slog.v(TAG, "  LAYOUT: mFrame=" + w.getFrame()
                    + " mParentFrame=" + w.getParentFrame()
                    + " mDisplayFrame=" + w.getDisplayFrame());
        }
    };

    private final Consumer<WindowState> mPerformLayoutAttached = w -> {
        if (!w.mLayoutAttached) {
            return;
        }
        if (DEBUG_LAYOUT) Slog.v(TAG, "2ND PASS " + w + " mHaveFrame=" + w.mHaveFrame
                + " mViewVisibility=" + w.mViewVisibility
                + " mRelayoutCalled=" + w.mRelayoutCalled);
        // If this view is GONE, then skip it -- keep the current frame, and let the caller
        // know so they can ignore it if they want.  (We do the normal layout for INVISIBLE
        // windows, since that means "perform layout as normal, just don't display").
        if ((w.mViewVisibility != GONE && w.mRelayoutCalled) || !w.mHaveFrame
                || w.mLayoutNeeded) {
            if (mTmpInitial) {
                w.resetContentChanged();
            }
            w.mSurfacePlacementNeeded = true;
            w.mLayoutNeeded = false;
            getDisplayPolicy().layoutWindowLw(w, w.getParentWindow(), mDisplayFrames);
            w.mLayoutSeq = mLayoutSeq;
            if (DEBUG_LAYOUT) Slog.v(TAG, " LAYOUT: mFrame=" + w.getFrame()
                    + " mParentFrame=" + w.getParentFrame()
                    + " mDisplayFrame=" + w.getDisplayFrame());
        }
    };

    private final Predicate<WindowState> mComputeImeTargetPredicate = w -> {
        if (DEBUG_INPUT_METHOD && mUpdateImeTarget) Slog.i(TAG_WM, "Checking window @" + w
                + " fl=0x" + Integer.toHexString(w.mAttrs.flags));
        return w.canBeImeTarget();
    };

    private final Consumer<WindowState> mApplyPostLayoutPolicy =
            w -> getDisplayPolicy().applyPostLayoutPolicyLw(w, w.mAttrs, w.getParentWindow(),
                    mImeLayeringTarget);

    private final Consumer<WindowState> mApplySurfaceChangesTransaction = w -> {
        final WindowSurfacePlacer surfacePlacer = mWmService.mWindowPlacerLocked;
        final boolean obscuredChanged = w.mObscured !=
                mTmpApplySurfaceChangesTransactionState.obscured;
        final RootWindowContainer root = mWmService.mRoot;

        // Update effect.
        w.mObscured = mTmpApplySurfaceChangesTransactionState.obscured;

        if (!mTmpApplySurfaceChangesTransactionState.obscured) {
            final boolean isDisplayed = w.isDisplayed();

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
                if (type == TYPE_SYSTEM_DIALOG
                        || type == TYPE_SYSTEM_ERROR
                        || (type == TYPE_NOTIFICATION_SHADE
                            &&  mWmService.mPolicy.isKeyguardShowing())) {
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

                final float preferredMinRefreshRate = getDisplayPolicy().getRefreshRatePolicy()
                        .getPreferredMinRefreshRate(w);
                if (mTmpApplySurfaceChangesTransactionState.preferredMinRefreshRate == 0
                        && preferredMinRefreshRate != 0) {
                    mTmpApplySurfaceChangesTransactionState.preferredMinRefreshRate =
                            preferredMinRefreshRate;
                }

                final float preferredMaxRefreshRate = getDisplayPolicy().getRefreshRatePolicy()
                        .getPreferredMaxRefreshRate(w);
                if (mTmpApplySurfaceChangesTransactionState.preferredMaxRefreshRate == 0
                        && preferredMaxRefreshRate != 0) {
                    mTmpApplySurfaceChangesTransactionState.preferredMaxRefreshRate =
                            preferredMaxRefreshRate;
                }
            }
        }

        if (obscuredChanged && w.isVisible() && mWallpaperController.isWallpaperTarget(w)) {
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
                if (w.hasWallpaper()) {
                    ProtoLog.v(WM_DEBUG_WALLPAPER,
                            "First draw done in potential wallpaper target %s", w);
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
        if (activity != null && activity.isVisibleRequested()) {
            activity.updateLetterboxSurface(w);
            final boolean updateAllDrawn = activity.updateDrawnWindowStates(w);
            if (updateAllDrawn && !mTmpUpdateAllDrawn.contains(activity)) {
                mTmpUpdateAllDrawn.add(activity);
            }
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
        super(root.mWindowManager, "DisplayContent", FEATURE_ROOT);
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
        mCurrentUniqueDisplayId = display.getUniqueId();
        mOffTokenAcquirer = mRootWindowContainer.mDisplayOffTokenAcquirer;
        mWallpaperController = new WallpaperController(mWmService, this);
        display.getDisplayInfo(mDisplayInfo);
        display.getMetrics(mDisplayMetrics);
        mSystemGestureExclusionLimit = mWmService.mConstants.mSystemGestureExclusionLimitDp
                * mDisplayMetrics.densityDpi / DENSITY_DEFAULT;
        isDefaultDisplay = mDisplayId == DEFAULT_DISPLAY;
        mInsetsStateController = new InsetsStateController(this);
        mDisplayFrames = new DisplayFrames(mDisplayId, mInsetsStateController.getRawInsetsState(),
                mDisplayInfo, calculateDisplayCutoutForRotation(mDisplayInfo.rotation),
                calculateRoundedCornersForRotation(mDisplayInfo.rotation),
                calculatePrivacyIndicatorBoundsForRotation(mDisplayInfo.rotation));
        initializeDisplayBaseInfo();

        mAppTransition = new AppTransition(mWmService.mContext, mWmService, this);
        mAppTransition.registerListenerLocked(mWmService.mActivityManagerAppTransitionNotifier);
        mAppTransition.registerListenerLocked(mFixedRotationTransitionListener);
        mAppTransitionController = new AppTransitionController(mWmService, this);
        mTransitionController.registerLegacyListener(mFixedRotationTransitionListener);
        mUnknownAppVisibilityController = new UnknownAppVisibilityController(mWmService, this);
        mDisplaySwitchTransitionLauncher = new PhysicalDisplaySwitchTransitionLauncher(this,
                mTransitionController);
        mRemoteDisplayChangeController = new RemoteDisplayChangeController(mWmService, mDisplayId);

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
        mDividerControllerLocked = new DockedTaskDividerController(this);
        mPinnedTaskController = new PinnedTaskController(mWmService, this);

        final Transaction pendingTransaction = getPendingTransaction();
        configureSurfaces(pendingTransaction);
        pendingTransaction.apply();

        // Sets the display content for the children.
        onDisplayChanged(this);
        updateDisplayAreaOrganizers();

        mInputMonitor = new InputMonitor(mWmService, this);
        mInsetsPolicy = new InsetsPolicy(mInsetsStateController, this);
        mMinSizeOfResizeableTaskDp = getMinimalTaskSizeDp();
        if (DEBUG_DISPLAY) Slog.v(TAG_WM, "Creating display=" + display);

        mWmService.mDisplayWindowSettings.applySettingsToDisplayLocked(this);
    }

    @Override
    void migrateToNewSurfaceControl(Transaction t) {
        t.remove(mSurfaceControl);

        mLastSurfacePosition.set(0, 0);
        mLastDeltaRotation = Surface.ROTATION_0;

        configureSurfaces(t);

        for (int i = 0; i < mChildren.size(); i++)  {
            SurfaceControl sc = mChildren.get(i).getSurfaceControl();
            if (sc != null) {
                t.reparent(sc, mSurfaceControl);
            }
        }

        scheduleAnimation();
    }

    /**
     * Configures the surfaces hierarchy for DisplayContent
     * This method always recreates the main surface control but reparents the children
     * if they are already created.
     * @param transaction as part of which to perform the configuration
     */
    private void configureSurfaces(Transaction transaction) {
        final SurfaceControl.Builder b = mWmService.makeSurfaceBuilder(mSession)
                .setOpaque(true)
                .setContainerLayer()
                .setCallsite("DisplayContent");
        mSurfaceControl = b.setName(getName()).setContainerLayer().build();

        if (mDisplayAreaPolicy == null) {
            // Setup the policy and build the display area hierarchy.
            // Build the hierarchy only after creating the surface so it is reparented correctly
            mDisplayAreaPolicy = mWmService.getDisplayAreaPolicyProvider().instantiate(
                    mWmService, this /* content */, this /* root */,
                    mImeWindowsContainer);
        }

        final List<DisplayArea<? extends WindowContainer>> areas =
                mDisplayAreaPolicy.getDisplayAreas(FEATURE_WINDOWED_MAGNIFICATION);
        final DisplayArea<?> area = areas.size() == 1 ? areas.get(0) : null;

        if (area != null && area.getParent() == this) {
            // The windowed magnification area should contain all non-overlay windows, so just use
            // it as the windowing layer.
            mWindowingLayer = area.mSurfaceControl;
            transaction.reparent(mWindowingLayer, mSurfaceControl);
        } else {
            // Need an additional layer for screen level animation, so move the layer containing
            // the windows to the new root.
            mWindowingLayer = mSurfaceControl;
            mSurfaceControl = b.setName("RootWrapper").build();
            transaction.reparent(mWindowingLayer, mSurfaceControl)
                    .show(mWindowingLayer);
        }

        if (mOverlayLayer == null) {
            mOverlayLayer = b.setName("Display Overlays").setParent(mSurfaceControl).build();
        } else {
            transaction.reparent(mOverlayLayer, mSurfaceControl);
        }

        transaction
                .setLayer(mSurfaceControl, 0)
                .setLayerStack(mSurfaceControl, mDisplayId)
                .show(mSurfaceControl)
                .setLayer(mOverlayLayer, Integer.MAX_VALUE)
                .show(mOverlayLayer);
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
            final DisplayArea.Tokens da = findAreaForToken(token).asTokens();
            da.addChild(token);
        }
    }

    WindowToken removeWindowToken(IBinder binder, boolean animateExit) {
        final WindowToken token = mTokenMap.remove(binder);
        if (token != null && token.asActivityRecord() == null) {
            token.setExiting(animateExit);
        }
        return token;
    }

    SurfaceControl addShellRoot(@NonNull IWindow client,
            @WindowManager.ShellRootLayer int shellRootLayer) {
        ShellRoot root = mShellRoots.get(shellRootLayer);
        if (root != null) {
            if (root.getClient() == client) {
                return root.getSurfaceControl();
            }
            root.clear();
            mShellRoots.remove(shellRootLayer);
        }
        root = new ShellRoot(client, this, shellRootLayer);
        SurfaceControl rootLeash = root.getSurfaceControl();
        if (rootLeash == null) {
            // Root didn't finish initializing, so don't add it.
            root.clear();
            return null;
        }
        mShellRoots.put(shellRootLayer, root);
        SurfaceControl out = new SurfaceControl(rootLeash, "DisplayContent.addShellRoot");
        return out;
    }

    void removeShellRoot(int windowType) {
        synchronized(mWmService.mGlobalLock) {
            ShellRoot root = mShellRoots.get(windowType);
            if (root == null) {
                return;
            }
            root.clear();
            mShellRoots.remove(windowType);
        }
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
        }

        addWindowToken(token.token, token);

        if (mWmService.mAccessibilityController.hasCallbacks()) {
            final int prevDisplayId = prevDc != null ? prevDc.getDisplayId() : INVALID_DISPLAY;
            mWmService.mAccessibilityController.onSomeWindowResizedOrMoved(prevDisplayId,
                    getDisplayId());
        }
    }

    void removeAppToken(IBinder binder) {
        final WindowToken token = removeWindowToken(binder, true /* animateExit */);
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
        if (activity == mFixedRotationLaunchingApp) {
            // Make sure the states of associated tokens are also cleared.
            activity.finishFixedRotationTransform();
            setFixedRotationLaunchingAppUnchecked(null);
        }
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

    void setInsetProvider(@InternalInsetsType int type, WindowContainer win,
            @Nullable TriConsumer<DisplayFrames, WindowContainer, Rect> frameProvider) {
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
    void setInsetProvider(@InternalInsetsType int type, WindowContainer win,
            @Nullable TriConsumer<DisplayFrames, WindowContainer, Rect> frameProvider,
            @Nullable TriConsumer<DisplayFrames, WindowContainer, Rect> imeFrameProvider) {
        mInsetsStateController.getSourceProvider(type).setWindowContainer(win, frameProvider,
                imeFrameProvider);
    }

    InsetsStateController getInsetsStateController() {
        return mInsetsStateController;
    }

    InsetsPolicy getInsetsPolicy() {
        return mInsetsPolicy;
    }

    @Rotation
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
        final int changes = currentDisplayConfig.diff(mTmpConfiguration);
        configChanged |= changes != 0;

        if (configChanged) {
            mWaitingForConfig = true;
            if (mTransitionController.isShellTransitionsEnabled()) {
                requestChangeTransitionIfNeeded(changes, null /* displayChange */);
            } else if (mLastHasContent) {
                mWmService.startFreezingDisplay(0 /* exitAnim */, 0 /* enterAnim */, this);
            }
            sendNewConfiguration();
        }

        mWmService.mWindowPlacerLocked.performSurfacePlacement();
    }

    void sendNewConfiguration() {
        if (!isReady()) {
            return;
        }
        if (mRemoteDisplayChangeController.isWaitingForRemoteDisplayChange()) {
            return;
        }

        final boolean configUpdated = updateDisplayOverrideConfigurationLocked();
        if (configUpdated) {
            return;
        }

        // The display configuration doesn't change. If there is a launching transformed app, that
        // means its request to change display configuration has been discarded, then it should
        // respect to the current configuration of display.
        clearFixedRotationLaunchingApp();

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
    boolean onDescendantOrientationChanged(WindowContainer requestingContainer) {
        final Configuration config = updateOrientation(
                requestingContainer, false /* forceUpdate */);
        // If display rotation class tells us that it doesn't consider app requested orientation,
        // this display won't rotate just because of an app changes its requested orientation. Thus
        // it indicates that this display chooses not to handle this request.
        final boolean handled = handlesOrientationChangeFromDescendant();
        if (config == null) {
            return handled;
        }

        if (handled && requestingContainer instanceof ActivityRecord) {
            final ActivityRecord activityRecord = (ActivityRecord) requestingContainer;
            final boolean kept = updateDisplayOverrideConfigurationLocked(config, activityRecord,
                    false /* deferResume */, null /* result */);
            activityRecord.frozenBeforeDestroy = true;
            if (!kept) {
                mRootWindowContainer.resumeFocusedTasksTopActivities();
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
        return !getIgnoreOrientationRequest()
                && !getDisplayRotation().isFixedToUserRotation();
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
     * @param freezeDisplayWindow Freeze the app window if the orientation is changed.
     * @param forceUpdate See {@link DisplayRotation#updateRotationUnchecked(boolean)}
     */
    Configuration updateOrientation(WindowContainer<?> freezeDisplayWindow, boolean forceUpdate) {
        if (!mDisplayReady) {
            return null;
        }

        Configuration config = null;
        if (updateOrientation(forceUpdate)) {
            // If we changed the orientation but mOrientationChangeComplete is already true,
            // we used seamless rotation, and we don't need to freeze the screen.
            if (freezeDisplayWindow != null && !mWmService.mRoot.mOrientationChangeComplete) {
                final ActivityRecord activity = freezeDisplayWindow.asActivityRecord();
                if (activity != null && activity.mayFreezeScreenLocked()) {
                    activity.startFreezingScreen();
                }
            }
            config = new Configuration();
            computeScreenConfiguration(config);
        } else if (!(mTransitionController.isCollecting(this)
                // If waiting for a remote display change, don't prematurely update configuration.
                || mRemoteDisplayChangeController.isWaitingForRemoteDisplayChange())) {
            // No obvious action we need to take, but if our current state mismatches the
            // activity manager's, update it, disregarding font scale, which should remain set
            // to the value of the previous configuration.
            // Here we're calling Configuration#unset() instead of setToDefaults() because we
            // need to keep override configs clear of non-empty values (e.g. fontSize).
            final Configuration currentConfig = getRequestedOverrideConfiguration();
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

    private int getMinimalTaskSizeDp() {
        final Context displayConfigurationContext =
                mAtmService.mContext.createConfigurationContext(getConfiguration());
        final float minimalSize =
                displayConfigurationContext.getResources().getDimension(
                                com.android.internal.R.dimen.default_minimal_size_resizable_task);
        if (Double.compare(mDisplayMetrics.density, 0.0) == 0) {
            throw new IllegalArgumentException("Display with ID=" + getDisplayId() + "has invalid "
                + "DisplayMetrics.density= 0.0");
        }
        return (int) (minimalSize / mDisplayMetrics.density);
    }

    private boolean updateOrientation(boolean forceUpdate) {
        final int orientation = getOrientation();
        // The last orientation source is valid only after getOrientation.
        final WindowContainer orientationSource = getLastOrientationSource();
        final ActivityRecord r =
                orientationSource != null ? orientationSource.asActivityRecord() : null;
        if (r != null) {
            final Task task = r.getTask();
            if (task != null && orientation != task.mLastReportedRequestedOrientation) {
                task.mLastReportedRequestedOrientation = orientation;
                mAtmService.getTaskChangeNotificationController()
                        .notifyTaskRequestedOrientationChanged(task.mTaskId, orientation);
            }
            // The orientation source may not be the top if it uses SCREEN_ORIENTATION_BEHIND.
            final ActivityRecord topCandidate = !r.mVisibleRequested ? topRunningActivity() : r;
            if (handleTopActivityLaunchingInDifferentOrientation(
                    topCandidate, r, true /* checkOpening */)) {
                // Display orientation should be deferred until the top fixed rotation is finished.
                return false;
            }
        }
        return mDisplayRotation.updateOrientation(orientation, forceUpdate);
    }

    @Override
    boolean isSyncFinished() {
        // Do not consider children because if they are requested to be synced, they should be
        // added to sync group explicitly.
        return !mRemoteDisplayChangeController.isWaitingForRemoteDisplayChange();
    }

    /**
     * Returns a valid rotation if the activity can use different orientation than the display.
     * Otherwise {@link android.app.WindowConfiguration#ROTATION_UNDEFINED}.
     */
    @Rotation
    int rotationForActivityInDifferentOrientation(@NonNull ActivityRecord r) {
        if (mTransitionController.useShellTransitionsRotation()) {
            return ROTATION_UNDEFINED;
        }
        if (!WindowManagerService.ENABLE_FIXED_ROTATION_TRANSFORM) {
            return ROTATION_UNDEFINED;
        }
        if (r.mOrientation == ActivityInfo.SCREEN_ORIENTATION_BEHIND) {
            final ActivityRecord nextCandidate = getActivity(
                    a -> a.mOrientation != SCREEN_ORIENTATION_UNSET
                            && a.mOrientation != ActivityInfo.SCREEN_ORIENTATION_BEHIND,
                    r, false /* includeBoundary */, true /* traverseTopToBottom */);
            if (nextCandidate != null) {
                r = nextCandidate;
            }
        }
        if (r.inMultiWindowMode() || r.getRequestedConfigurationOrientation(true /* forDisplay */)
                == getConfiguration().orientation) {
            return ROTATION_UNDEFINED;
        }
        final int currentRotation = getRotation();
        final int rotation = mDisplayRotation.rotationForOrientation(r.getRequestedOrientation(),
                currentRotation);
        if (rotation == currentRotation) {
            return ROTATION_UNDEFINED;
        }
        return rotation;
    }

    boolean handleTopActivityLaunchingInDifferentOrientation(@NonNull ActivityRecord r,
            boolean checkOpening) {
        return handleTopActivityLaunchingInDifferentOrientation(r, r, checkOpening);
    }

    /**
     * We need to keep display rotation fixed for a while when the activity in different orientation
     * is launching until the launch animation is done to avoid showing the previous activity
     * inadvertently in a wrong orientation.
     *
     * @param r The launching activity which may change display orientation.
     * @param orientationSrc It may be different from {@param r} if the launching activity uses
     *                       "behind" orientation.
     * @param checkOpening Whether to check if the activity is animating by transition. Set to
     *                     {@code true} if the caller is not sure whether the activity is launching.
     * @return {@code true} if the fixed rotation is started.
     */
    private boolean handleTopActivityLaunchingInDifferentOrientation(@NonNull ActivityRecord r,
            @NonNull ActivityRecord orientationSrc, boolean checkOpening) {
        if (!WindowManagerService.ENABLE_FIXED_ROTATION_TRANSFORM) {
            return false;
        }
        if (r.isFinishingFixedRotationTransform()) {
            return false;
        }
        if (r.hasFixedRotationTransform()) {
            // It has been set and not yet finished.
            return true;
        }
        if (!r.occludesParent() || r.isReportedDrawn()) {
            // While entering or leaving a translucent or floating activity (e.g. dialog style),
            // there is a visible activity in the background. Then it still needs rotation animation
            // to cover the activity configuration change.
            return false;
        }
        if (checkOpening) {
            if (mTransitionController.isShellTransitionsEnabled()) {
                if (!mTransitionController.isCollecting(r)) {
                    return false;
                }
            } else {
                if (!mAppTransition.isTransitionSet() || !mOpeningApps.contains(r)) {
                    // Apply normal rotation animation in case of the activity set different
                    // requested orientation without activity switch, or the transition is unset due
                    // to starting window was transferred ({@link #mSkipAppTransitionAnimation}).
                    return false;
                }
            }
            if (r.isState(RESUMED) && !r.getRootTask().mInResumeTopActivity) {
                // If the activity is executing or has done the lifecycle callback, use normal
                // rotation animation so the display info can be updated immediately (see
                // updateDisplayAndOrientation). This prevents a compatibility issue such as
                // calling setRequestedOrientation in Activity#onCreate and then get display info.
                // If fixed rotation is applied, the display rotation will still be the old one,
                // unless the client side gets the rotation again after the adjustments arrive.
                return false;
            }
        } else if (r != topRunningActivity()) {
            // If the transition has not started yet, the activity must be the top.
            return false;
        }
        if (mLastWallpaperVisible && r.windowsCanBeWallpaperTarget()
                && mFixedRotationTransitionListener.mAnimatingRecents == null) {
            // Use normal rotation animation for orientation change of visible wallpaper if recents
            // animation is not running (it may be swiping to home).
            return false;
        }
        final int rotation = rotationForActivityInDifferentOrientation(orientationSrc);
        if (rotation == ROTATION_UNDEFINED) {
            // The display rotation won't be changed by current top activity. The client side
            // adjustments of previous rotated activity should be cleared earlier. Otherwise if
            // the current top is in the same process, it may get the rotated state. The transform
            // will be cleared later with transition callback to ensure smooth animation.
            return false;
        }
        if (!r.getDisplayArea().matchParentBounds()) {
            // Because the fixed rotated configuration applies to activity directly, if its parent
            // has it own policy for bounds, the activity bounds based on parent is unknown.
            return false;
        }

        setFixedRotationLaunchingApp(r, rotation);
        return true;
    }

    /** Returns {@code true} if the IME is possible to show on the launching activity. */
    boolean mayImeShowOnLaunchingActivity(@NonNull ActivityRecord r) {
        final WindowState win = r.findMainWindow();
        if (win == null) {
            return false;
        }
        // See InputMethodManagerService#shouldRestoreImeVisibility that we expecting the IME
        // should be hidden when the window set the hidden softInputMode.
        final int softInputMode = win.mAttrs.softInputMode;
        switch (softInputMode & WindowManager.LayoutParams.SOFT_INPUT_MASK_STATE) {
            case SOFT_INPUT_STATE_ALWAYS_HIDDEN:
            case SOFT_INPUT_STATE_HIDDEN:
                return false;
        }
        return r.mLastImeShown;
    }

    /** Returns {@code true} if the top activity is transformed with the new rotation of display. */
    boolean hasTopFixedRotationLaunchingApp() {
        return mFixedRotationLaunchingApp != null
                // Ignore animating recents because it hasn't really become the top.
                && mFixedRotationLaunchingApp != mFixedRotationTransitionListener.mAnimatingRecents;
    }

    boolean isFixedRotationLaunchingApp(ActivityRecord r) {
        return mFixedRotationLaunchingApp == r;
    }

    @VisibleForTesting
    @Nullable AsyncRotationController getAsyncRotationController() {
        return mAsyncRotationController;
    }

    void setFixedRotationLaunchingAppUnchecked(@Nullable ActivityRecord r) {
        setFixedRotationLaunchingAppUnchecked(r, ROTATION_UNDEFINED);
    }

    void setFixedRotationLaunchingAppUnchecked(@Nullable ActivityRecord r, int rotation) {
        if (mFixedRotationLaunchingApp == null && r != null) {
            mWmService.mDisplayNotificationController.dispatchFixedRotationStarted(this, rotation);
            // Delay the hide animation to avoid blinking by clicking navigation bar that may
            // toggle fixed rotation in a short time.
            final boolean shouldDebounce = r == mFixedRotationTransitionListener.mAnimatingRecents
                    || mTransitionController.isTransientLaunch(r);
            startAsyncRotation(shouldDebounce);
        } else if (mFixedRotationLaunchingApp != null && r == null) {
            mWmService.mDisplayNotificationController.dispatchFixedRotationFinished(this);
            // Keep async rotation controller if the next transition of display is requested.
            if (!mTransitionController.isCollecting(this)) {
                finishAsyncRotationIfPossible();
            }
        }
        mFixedRotationLaunchingApp = r;
    }

    /**
     * Sets the provided record to {@link #mFixedRotationLaunchingApp} if possible to apply fixed
     * rotation transform to it and indicate that the display may be rotated after it is launched.
     */
    void setFixedRotationLaunchingApp(@NonNull ActivityRecord r, @Rotation int rotation) {
        final ActivityRecord prevRotatedLaunchingApp = mFixedRotationLaunchingApp;
        if (prevRotatedLaunchingApp == r
                && r.getWindowConfiguration().getRotation() == rotation) {
            // The given launching app and target rotation are the same as the existing ones.
            return;
        }
        if (prevRotatedLaunchingApp != null
                && prevRotatedLaunchingApp.getWindowConfiguration().getRotation() == rotation
                // It is animating so we can expect there will have a transition callback.
                && (prevRotatedLaunchingApp.isInTransition())) {
            // It may be the case that multiple activities launch consecutively. Because their
            // rotation are the same, the transformed state can be shared to avoid duplicating
            // the heavy operations. This also benefits that the states of multiple activities
            // are handled together.
            r.linkFixedRotationTransform(prevRotatedLaunchingApp);
            if (r != mFixedRotationTransitionListener.mAnimatingRecents) {
                // Only update the record for normal activity so the display orientation can be
                // updated when the transition is done if it becomes the top. And the case of
                // recents can be handled when the recents animation is finished.
                setFixedRotationLaunchingAppUnchecked(r, rotation);
            }
            return;
        }

        if (!r.hasFixedRotationTransform()) {
            startFixedRotationTransform(r, rotation);
        }
        setFixedRotationLaunchingAppUnchecked(r, rotation);
        if (prevRotatedLaunchingApp != null) {
            prevRotatedLaunchingApp.finishFixedRotationTransform();
        }
    }

    /**
     * Continue updating the orientation change of display if it was deferred by a top activity
     * launched in a different orientation.
     */
    void continueUpdateOrientationForDiffOrienLaunchingApp() {
        if (mFixedRotationLaunchingApp == null) {
            return;
        }
        if (mPinnedTaskController.shouldDeferOrientationChange()) {
            // Wait for the PiP animation to finish.
            return;
        }
        // Update directly because the app which will change the orientation of display is ready.
        if (mDisplayRotation.updateOrientation(getOrientation(), false /* forceUpdate */)) {
            sendNewConfiguration();
            return;
        }
        if (mRemoteDisplayChangeController.isWaitingForRemoteDisplayChange()) {
            // There is pending display change to apply.
            return;
        }
        // The orientation of display is not changed.
        clearFixedRotationLaunchingApp();
    }

    /**
     * Clears the {@link #mFixedRotationLaunchingApp} without applying rotation to display. It is
     * used when the display won't rotate (e.g. the orientation from sensor has updated again before
     * applying rotation to display) but the launching app has been transformed. So the record need
     * to be cleared and restored to stop using seamless rotation and rotated configuration.
     */
    private void clearFixedRotationLaunchingApp() {
        if (mFixedRotationLaunchingApp == null) {
            return;
        }
        mFixedRotationLaunchingApp.finishFixedRotationTransform();
        setFixedRotationLaunchingAppUnchecked(null);
    }

    private void startFixedRotationTransform(WindowToken token, int rotation) {
        mTmpConfiguration.unset();
        final DisplayInfo info = computeScreenConfiguration(mTmpConfiguration, rotation);
        final WmDisplayCutout cutout = calculateDisplayCutoutForRotation(rotation);
        final RoundedCorners roundedCorners = calculateRoundedCornersForRotation(rotation);
        final PrivacyIndicatorBounds indicatorBounds =
                calculatePrivacyIndicatorBoundsForRotation(rotation);
        final DisplayFrames displayFrames = new DisplayFrames(mDisplayId, new InsetsState(), info,
                cutout, roundedCorners, indicatorBounds);
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
        if (rotation != ROTATION_UNDEFINED) {
            startFixedRotationTransform(activityRecord, rotation);
        }
    }

    /** Returns {@code true} if the decided new rotation has not applied to configuration yet. */
    boolean isRotationChanging() {
        return mDisplayRotation.getRotation() != getWindowConfiguration().getRotation();
    }

    private void startAsyncRotationIfNeeded() {
        if (isRotationChanging()) {
            startAsyncRotation(false /* shouldDebounce */);
        }
    }

    /**
     * Starts the hide animation for the windows which will be rotated seamlessly.
     *
     * @return {@code true} if the animation is executed right now.
     */
    private boolean startAsyncRotation(boolean shouldDebounce) {
        if (shouldDebounce) {
            mWmService.mH.postDelayed(() -> {
                synchronized (mWmService.mGlobalLock) {
                    if (mFixedRotationLaunchingApp != null
                            && startAsyncRotation(false /* shouldDebounce */)) {
                        // Apply the transaction so the animation leash can take effect immediately.
                        getPendingTransaction().apply();
                    }
                }
            }, FIXED_ROTATION_HIDE_ANIMATION_DEBOUNCE_DELAY_MS);
            return false;
        }
        if (mAsyncRotationController == null) {
            mAsyncRotationController = new AsyncRotationController(this);
            mAsyncRotationController.start();
            return true;
        }
        return false;
    }

    /** Re-show the previously hidden windows if all seamless rotated windows are done. */
    void finishAsyncRotationIfPossible() {
        final AsyncRotationController controller = mAsyncRotationController;
        if (controller != null && !mDisplayRotation.hasSeamlessRotatingWindow()) {
            controller.completeAll();
            mAsyncRotationController = null;
        }
    }

    /** Shows the given window which may be hidden for screen rotation. */
    void finishAsyncRotation(WindowToken windowToken) {
        final AsyncRotationController controller = mAsyncRotationController;
        if (controller != null && controller.completeRotation(windowToken)) {
            mAsyncRotationController = null;
        }
    }

    /** Returns {@code true} if the screen rotation animation needs to wait for the window. */
    boolean shouldSyncRotationChange(WindowState w) {
        final AsyncRotationController controller = mAsyncRotationController;
        return controller == null || !controller.isAsync(w);
    }

    void notifyInsetsChanged(Consumer<WindowState> dispatchInsetsChanged) {
        if (mFixedRotationLaunchingApp != null) {
            // The insets state of fixed rotation app is a rotated copy. Make sure the visibilities
            // of insets sources are consistent with the latest state.
            final InsetsState rotatedState =
                    mFixedRotationLaunchingApp.getFixedRotationTransformInsetsState();
            if (rotatedState != null) {
                final InsetsState state = mInsetsStateController.getRawInsetsState();
                for (int i = 0; i < InsetsState.SIZE; i++) {
                    final InsetsSource source = state.peekSource(i);
                    if (source != null) {
                        rotatedState.setSourceVisible(i, source.isVisible());
                    }
                }
            }
        }
        forAllWindows(dispatchInsetsChanged, true /* traverseTopToBottom */);
        if (mRemoteInsetsControlTarget != null) {
            mRemoteInsetsControlTarget.notifyInsetsChanged();
        }
        // In Accessibility side, we need to know what magnification mode is activated while IME
        // is opened for logging metrics.
        if (mWmService.mAccessibilityController.hasCallbacks()) {
            final boolean isImeShow = mImeControlTarget != null
                    && mImeControlTarget.getRequestedVisibility(ITYPE_IME);
            mWmService.mAccessibilityController.updateImeVisibilityIfNeeded(mDisplayId, isImeShow);
        }
    }

    /**
     * Update rotation of the display.
     *
     * @return {@code true} if the rotation has been changed.  In this case YOU MUST CALL
     *         {@link #sendNewConfiguration} TO UNFREEZE THE SCREEN unless using Shell transitions.
     */
    boolean updateRotationUnchecked() {
        return mDisplayRotation.updateRotationUnchecked(false /* forceUpdate */);
    }

    /**
     * @see DisplayWindowPolicyController#canShowTasksInRecents()
     */
    boolean canShowTasksInRecents() {
        if (mDwpcHelper == null) {
            return true;
        }
        return mDwpcHelper.canShowTasksInRecents();
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
        final boolean shellTransitions = mTransitionController.getTransitionPlayer() != null;
        final boolean rotateSeamlessly =
                mDisplayRotation.isRotatingSeamlessly() && !shellTransitions;
        final Transaction transaction =
                shellTransitions ? getSyncTransaction() : getPendingTransaction();
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

        if (!shellTransitions) {
            forAllWindows(w -> {
                w.seamlesslyRotateIfAllowed(transaction, oldRotation, rotation, rotateSeamlessly);
            }, true /* traverseTopToBottom */);
            mPinnedTaskController.startSeamlessRotationIfNeeded(transaction, oldRotation, rotation);
        }

        mWmService.mDisplayManagerInternal.performTraversal(transaction);
        scheduleAnimation();

        forAllWindows(w -> {
            if (!w.mHasSurface) return;
            if (!rotateSeamlessly) {
                ProtoLog.v(WM_DEBUG_ORIENTATION, "Set mOrientationChanging of %s", w);
                w.setOrientationChanging(true);
            }
        }, true /* traverseTopToBottom */);

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
    }

    void configureDisplayPolicy() {
        mRootWindowContainer.updateDisplayImePolicyCache();
        mDisplayPolicy.updateConfigurationAndScreenSizeDependentBehaviors();
        mDisplayRotation.configure(mBaseDisplayWidth, mBaseDisplayHeight);
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
        final RoundedCorners roundedCorners = calculateRoundedCornersForRotation(rotation);

        final int appWidth = mDisplayPolicy.getNonDecorDisplayWidth(dw, dh, rotation, uiMode,
                displayCutout);
        final int appHeight = mDisplayPolicy.getNonDecorDisplayHeight(dh, rotation,
                displayCutout);
        mDisplayInfo.rotation = rotation;
        mDisplayInfo.logicalWidth = dw;
        mDisplayInfo.logicalHeight = dh;
        mDisplayInfo.logicalDensityDpi = mBaseDisplayDensity;
        mDisplayInfo.physicalXDpi = mBaseDisplayPhysicalXDpi;
        mDisplayInfo.physicalYDpi = mBaseDisplayPhysicalYDpi;
        mDisplayInfo.appWidth = appWidth;
        mDisplayInfo.appHeight = appHeight;
        if (isDefaultDisplay) {
            mDisplayInfo.getLogicalMetrics(mRealDisplayMetrics,
                    CompatibilityInfo.DEFAULT_COMPATIBILITY_INFO, null);
        }
        mDisplayInfo.displayCutout = displayCutout.isEmpty() ? null : displayCutout;
        mDisplayInfo.roundedCorners = roundedCorners;
        mDisplayInfo.getAppMetrics(mDisplayMetrics);
        if (mDisplayScalingDisabled) {
            mDisplayInfo.flags |= Display.FLAG_SCALING_DISABLED;
        } else {
            mDisplayInfo.flags &= ~Display.FLAG_SCALING_DISABLED;
        }

        computeSizeRangesAndScreenLayout(mDisplayInfo, rotated, uiMode, dw, dh,
                mDisplayMetrics.density, outConfig);

        mWmService.mDisplayManagerInternal.setDisplayInfoOverrideFromWindowManager(mDisplayId,
                mDisplayInfo);

        if (isDefaultDisplay) {
            mCompatibleScreenScale = CompatibilityInfo.computeCompatibleScaling(mDisplayMetrics,
                    mCompatDisplayMetrics);
        }

        onDisplayInfoChanged();

        return mDisplayInfo;
    }

    WmDisplayCutout calculateDisplayCutoutForRotation(int rotation) {
        return mDisplayCutoutCache.getOrCompute(
                mIsSizeForced ? mBaseDisplayCutout : mInitialDisplayCutout, rotation);
    }

    static WmDisplayCutout calculateDisplayCutoutForRotationAndDisplaySizeUncached(
            DisplayCutout cutout, int rotation, int displayWidth, int displayHeight) {
        if (cutout == null || cutout == DisplayCutout.NO_CUTOUT) {
            return WmDisplayCutout.NO_CUTOUT;
        }
        if (rotation == ROTATION_0) {
            return WmDisplayCutout.computeSafeInsets(
                    cutout, displayWidth, displayHeight);
        }
        final DisplayCutout rotatedCutout =
                cutout.getRotated(displayWidth, displayHeight, ROTATION_0, rotation);
        final boolean rotated = (rotation == ROTATION_90 || rotation == ROTATION_270);
        return new WmDisplayCutout(rotatedCutout, new Size(
                rotated ? displayHeight : displayWidth,
                rotated ? displayWidth : displayHeight));
    }

    private WmDisplayCutout calculateDisplayCutoutForRotationUncached(
            DisplayCutout cutout, int rotation) {
        return calculateDisplayCutoutForRotationAndDisplaySizeUncached(cutout, rotation,
                mIsSizeForced ? mBaseDisplayWidth : mInitialDisplayWidth,
                mIsSizeForced ? mBaseDisplayHeight : mInitialDisplayHeight);
    }

    RoundedCorners calculateRoundedCornersForRotation(int rotation) {
        return mRoundedCornerCache.getOrCompute(
                mIsSizeForced ? mBaseRoundedCorners : mInitialRoundedCorners, rotation);
    }

    private RoundedCorners calculateRoundedCornersForRotationUncached(
            RoundedCorners roundedCorners, int rotation) {
        if (roundedCorners == null || roundedCorners == RoundedCorners.NO_ROUNDED_CORNERS) {
            return RoundedCorners.NO_ROUNDED_CORNERS;
        }

        if (rotation == ROTATION_0) {
            return roundedCorners;
        }

        return roundedCorners.rotate(
                rotation,
                mIsSizeForced ? mBaseDisplayWidth : mInitialDisplayWidth,
                mIsSizeForced ? mBaseDisplayHeight : mInitialDisplayHeight);
    }

    PrivacyIndicatorBounds calculatePrivacyIndicatorBoundsForRotation(int rotation) {
        return mPrivacyIndicatorBoundsCache.getOrCompute(mCurrentPrivacyIndicatorBounds, rotation);
    }

    private PrivacyIndicatorBounds calculatePrivacyIndicatorBoundsForRotationUncached(
            PrivacyIndicatorBounds bounds, int rotation) {
        if (bounds == null) {
            return new PrivacyIndicatorBounds(new Rect[4], rotation);
        }

        return bounds.rotate(rotation);
    }

    /**
     * Compute display info and configuration according to the given rotation without changing
     * current display.
     */
    DisplayInfo computeScreenConfiguration(Configuration outConfig, int rotation) {
        final boolean rotated = (rotation == ROTATION_90 || rotation == ROTATION_270);
        final int dw = rotated ? mBaseDisplayHeight : mBaseDisplayWidth;
        final int dh = rotated ? mBaseDisplayWidth : mBaseDisplayHeight;
        outConfig.windowConfiguration.setMaxBounds(0, 0, dw, dh);
        outConfig.windowConfiguration.setBounds(outConfig.windowConfiguration.getMaxBounds());

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
        final int appHeight = mDisplayPolicy.getNonDecorDisplayHeight(dh, rotation,
                displayCutout);
        mDisplayPolicy.getNonDecorInsetsLw(rotation, displayCutout, mTmpRect);
        final int leftInset = mTmpRect.left;
        final int topInset = mTmpRect.top;
        // AppBounds at the root level should mirror the app screen size.
        outConfig.windowConfiguration.setAppBounds(leftInset /* left */, topInset /* top */,
                leftInset + appWidth /* right */, topInset + appHeight /* bottom */);
        outConfig.windowConfiguration.setRotation(rotation);
        outConfig.orientation = (dw <= dh) ? ORIENTATION_PORTRAIT : ORIENTATION_LANDSCAPE;

        final float density = mDisplayMetrics.density;
        outConfig.screenWidthDp = (int) (mDisplayPolicy.getConfigDisplayWidth(dw, dh, rotation,
                uiMode, displayCutout) / density + 0.5f);
        outConfig.screenHeightDp = (int) (mDisplayPolicy.getConfigDisplayHeight(dw, dh, rotation,
                uiMode, displayCutout) / density + 0.5f);
        outConfig.compatScreenWidthDp = (int) (outConfig.screenWidthDp / mCompatibleScreenScale);
        outConfig.compatScreenHeightDp = (int) (outConfig.screenHeightDp / mCompatibleScreenScale);

        final boolean rotated = (rotation == ROTATION_90 || rotation == ROTATION_270);
        outConfig.compatSmallestScreenWidthDp = computeCompatSmallestWidth(rotated, uiMode, dw, dh);
        outConfig.windowConfiguration.setDisplayRotation(rotation);
    }

    /**
     * Compute display configuration based on display properties and policy settings.
     * Do not call if mDisplayReady == false.
     */
    void computeScreenConfiguration(Configuration config) {
        final DisplayInfo displayInfo = updateDisplayAndOrientation(config.uiMode, config);
        final int dw = displayInfo.logicalWidth;
        final int dh = displayInfo.logicalHeight;
        mTmpRect.set(0, 0, dw, dh);
        config.windowConfiguration.setBounds(mTmpRect);
        config.windowConfiguration.setMaxBounds(mTmpRect);
        config.windowConfiguration.setWindowingMode(getWindowingMode());
        config.windowConfiguration.setDisplayWindowingMode(getWindowingMode());

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
            if (!mWmService.mInputManager.canDispatchToDisplay(device.getId(), mDisplayId)) {
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
        dm.noncompatHeightPixels = mDisplayPolicy.getNonDecorDisplayHeight(dh, rotation,
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
        outConfig.smallestScreenWidthDp =
                (int) (displayInfo.smallestNominalAppWidth / density + 0.5f);
        outConfig.screenLayout = sl;
    }

    private int reduceConfigLayout(int curLayout, int rotation, float density, int dw, int dh,
            int uiMode) {
        // Get the display cutout at this rotation.
        final DisplayCutout displayCutout = calculateDisplayCutoutForRotation(
                rotation).getDisplayCutout();

        // Get the app screen size at this rotation.
        int w = mDisplayPolicy.getNonDecorDisplayWidth(dw, dh, rotation, uiMode, displayCutout);
        int h = mDisplayPolicy.getNonDecorDisplayHeight(dh, rotation, displayCutout);

        // Compute the screen layout size class for this rotation.
        int longSize = w;
        int shortSize = h;
        if (longSize < shortSize) {
            int tmp = longSize;
            longSize = shortSize;
            shortSize = tmp;
        }
        longSize = (int) (longSize / density + 0.5f);
        shortSize = (int) (shortSize / density + 0.5f);
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

    DockedTaskDividerController getDockedDividerController() {
        return mDividerControllerLocked;
    }

    PinnedTaskController getPinnedTaskController() {
        return mPinnedTaskController;
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

    boolean isTrusted() {
        return mDisplay.isTrusted();
    }

    /**
     * Returns the topmost root task on the display that is compatible with the input windowing
     * mode and activity type. Null is no compatible root task on the display.
     */
    @Nullable
    Task getRootTask(int windowingMode, int activityType) {
        return getItemFromTaskDisplayAreas(taskDisplayArea ->
                taskDisplayArea.getRootTask(windowingMode, activityType));
    }

    @Nullable
    Task getRootTask(int rootTaskId) {
        return getRootTask(rootTask -> rootTask.getRootTaskId() == rootTaskId);
    }

    int getRootTaskCount() {
        final int[] count = new int[1];
        forAllRootTasks(task -> {
            count[0]++;
        });
        return count[0];
    }

    @Nullable
    Task getTopRootTask() {
        return getRootTask(t -> true);
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
        final int lastOrientation = getConfiguration().orientation;
        super.onConfigurationChanged(newParentConfig);
        if (mDisplayPolicy != null) {
            mDisplayPolicy.onConfigurationChanged();
            mPinnedTaskController.onPostDisplayConfigurationChanged();
        }
        // Update IME parent if needed.
        updateImeParent();

        // Update surface for MediaProjection, if this DisplayContent is being used for recording.
        if (mContentRecorder != null) {
            mContentRecorder.onConfigurationChanged(lastOrientation);
        }

        if (lastOrientation != getConfiguration().orientation) {
            getMetricsLogger().write(
                    new LogMaker(MetricsEvent.ACTION_PHONE_ORIENTATION_CHANGED)
                            .setSubtype(getConfiguration().orientation)
                            .addTaggedData(MetricsEvent.FIELD_DISPLAY_ID, getDisplayId()));
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
    boolean isVisibleRequested() {
        return isVisible() && !mRemoved && !mRemoving;
    }

    @Override
    void onAppTransitionDone() {
        super.onAppTransitionDone();
        mWmService.mWindowsChanged = true;
        // If the transition finished callback cannot match the token for some reason, make sure the
        // rotated state is cleared if it is already invisible.
        if (mFixedRotationLaunchingApp != null && !mFixedRotationLaunchingApp.mVisibleRequested
                && !mFixedRotationLaunchingApp.isVisible()
                && !mDisplayRotation.isRotatingSeamlessly()) {
            clearFixedRotationLaunchingApp();
        }
    }

    @Override
    public void setWindowingMode(int windowingMode) {
        // Intentionally call onRequestedOverrideConfigurationChanged() directly to change windowing
        // mode and display windowing mode atomically.
        mTmpConfiguration.setTo(getRequestedOverrideConfiguration());
        mTmpConfiguration.windowConfiguration.setWindowingMode(windowingMode);
        mTmpConfiguration.windowConfiguration.setDisplayWindowingMode(windowingMode);
        onRequestedOverrideConfigurationChanged(mTmpConfiguration);
    }

    @Override
    void setDisplayWindowingMode(int windowingMode) {
        setWindowingMode(windowingMode);
    }

    /**
     * See {@code WindowState#applyImeWindowsIfNeeded} for the details that we won't traverse the
     * IME window in some cases.
     */
    boolean forAllImeWindows(ToBooleanFunction<WindowState> callback, boolean traverseTopToBottom) {
        return mImeWindowsContainer.forAllWindowForce(callback, traverseTopToBottom);
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
        if (!handlesOrientationChangeFromDescendant()) {
            // Return SCREEN_ORIENTATION_UNSPECIFIED so that Display respect sensor rotation
            ProtoLog.v(WM_DEBUG_ORIENTATION,
                    "Display id=%d is ignoring all orientation requests, return %d",
                    mDisplayId, SCREEN_ORIENTATION_UNSPECIFIED);
            return SCREEN_ORIENTATION_UNSPECIFIED;
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

        final int orientation = super.getOrientation();
        if (orientation == SCREEN_ORIENTATION_UNSET) {
            // Return SCREEN_ORIENTATION_UNSPECIFIED so that Display respect sensor rotation
            ProtoLog.v(WM_DEBUG_ORIENTATION,
                    "No app or window is requesting an orientation, return %d for display id=%d",
                    SCREEN_ORIENTATION_UNSPECIFIED, mDisplayId);
            return SCREEN_ORIENTATION_UNSPECIFIED;
        }

        return orientation;
    }

    void updateDisplayInfo() {
        // Check if display metrics changed and update base values if needed.
        updateBaseDisplayMetricsIfNeeded();

        mDisplay.getDisplayInfo(mDisplayInfo);
        mDisplay.getMetrics(mDisplayMetrics);

        onDisplayInfoChanged();
        onDisplayChanged(this);
    }

    void updatePrivacyIndicatorBounds(Rect[] staticBounds) {
        PrivacyIndicatorBounds oldBounds = mCurrentPrivacyIndicatorBounds;
        mCurrentPrivacyIndicatorBounds =
                mCurrentPrivacyIndicatorBounds.updateStaticBounds(staticBounds);
        if (!Objects.equals(oldBounds, mCurrentPrivacyIndicatorBounds)) {
            updateDisplayFrames(false /* insetsSourceMayChange */, true /* notifyInsetsChange */);
        }
    }

    void onDisplayInfoChanged() {
        updateDisplayFrames(LOCAL_LAYOUT, LOCAL_LAYOUT);
        mMinSizeOfResizeableTaskDp = getMinimalTaskSizeDp();
        mInputMonitor.layoutInputConsumers(mDisplayInfo.logicalWidth, mDisplayInfo.logicalHeight);
        mDisplayPolicy.onDisplayInfoChanged(mDisplayInfo);
    }

    private void updateDisplayFrames(boolean insetsSourceMayChange, boolean notifyInsetsChange) {
        if (mDisplayFrames.update(mDisplayInfo,
                calculateDisplayCutoutForRotation(mDisplayInfo.rotation),
                calculateRoundedCornersForRotation(mDisplayInfo.rotation),
                calculatePrivacyIndicatorBoundsForRotation(mDisplayInfo.rotation))) {
            if (insetsSourceMayChange) {
                mDisplayPolicy.updateInsetsSourceFramesExceptIme(mDisplayFrames);
            }
            mInsetsStateController.onDisplayFramesUpdated(notifyInsetsChange);
        }
    }

    @Override
    void onDisplayChanged(DisplayContent dc) {
        super.onDisplayChanged(dc);
        updateSystemGestureExclusionLimit();
        updateKeepClearAreas();
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

            mDwpcHelper = new DisplayWindowPolicyControllerHelper(this);
        }

        updateBaseDisplayMetrics(mDisplayInfo.logicalWidth, mDisplayInfo.logicalHeight,
                mDisplayInfo.logicalDensityDpi, mDisplayInfo.physicalXDpi,
                mDisplayInfo.physicalYDpi);
        mInitialDisplayWidth = mDisplayInfo.logicalWidth;
        mInitialDisplayHeight = mDisplayInfo.logicalHeight;
        mInitialDisplayDensity = mDisplayInfo.logicalDensityDpi;
        mInitialPhysicalXDpi = mDisplayInfo.physicalXDpi;
        mInitialPhysicalYDpi = mDisplayInfo.physicalYDpi;
        mInitialDisplayCutout = mDisplayInfo.displayCutout;
        mInitialRoundedCorners = mDisplayInfo.roundedCorners;
        mCurrentPrivacyIndicatorBounds = new PrivacyIndicatorBounds(new Rect[4],
                mDisplayInfo.rotation);
        final Display.Mode maxDisplayMode =
                DisplayUtils.getMaximumResolutionDisplayMode(mDisplayInfo.supportedModes);
        mPhysicalDisplaySize = new Point(
                maxDisplayMode == null ? mInitialDisplayWidth : maxDisplayMode.getPhysicalWidth(),
                maxDisplayMode == null ? mInitialDisplayHeight : maxDisplayMode.getPhysicalHeight()
        );
    }

    /**
     * If display metrics changed, overrides are not set and it's not just a rotation - update base
     * values.
     */
    private void updateBaseDisplayMetricsIfNeeded() {
        // Get real display metrics without overrides from WM.
        mWmService.mDisplayManagerInternal.getNonOverrideDisplayInfo(mDisplayId, mDisplayInfo);
        final int currentRotation = getRotation();
        final int orientation = mDisplayInfo.rotation;
        final boolean rotated = (orientation == ROTATION_90 || orientation == ROTATION_270);
        final int newWidth = rotated ? mDisplayInfo.logicalHeight : mDisplayInfo.logicalWidth;
        final int newHeight = rotated ? mDisplayInfo.logicalWidth : mDisplayInfo.logicalHeight;
        final int newDensity = mDisplayInfo.logicalDensityDpi;
        final float newXDpi = mDisplayInfo.physicalXDpi;
        final float newYDpi = mDisplayInfo.physicalYDpi;
        final DisplayCutout newCutout = mIgnoreDisplayCutout
                ? DisplayCutout.NO_CUTOUT : mDisplayInfo.displayCutout;
        final String newUniqueId = mDisplayInfo.uniqueId;
        final RoundedCorners newRoundedCorners = mDisplayInfo.roundedCorners;

        final boolean displayMetricsChanged = mInitialDisplayWidth != newWidth
                || mInitialDisplayHeight != newHeight
                || mInitialDisplayDensity != newDensity
                || mInitialPhysicalXDpi != newXDpi
                || mInitialPhysicalYDpi != newYDpi
                || !Objects.equals(mInitialDisplayCutout, newCutout)
                || !Objects.equals(mInitialRoundedCorners, newRoundedCorners);
        final boolean physicalDisplayChanged = !newUniqueId.equals(mCurrentUniqueDisplayId);

        if (displayMetricsChanged || physicalDisplayChanged) {
            if (physicalDisplayChanged) {
                // Reapply the window settings as the underlying physical display has changed.
                // Do not include rotation settings here, postpone them until the display
                // metrics are updated as rotation settings might depend on them
                mWmService.mDisplayWindowSettings.applySettingsToDisplayLocked(this,
                        /* includeRotationSettings */ false);
                mDisplaySwitchTransitionLauncher.requestDisplaySwitchTransitionIfNeeded(mDisplayId,
                        mInitialDisplayWidth, mInitialDisplayHeight, newWidth, newHeight);
            }

            // If there is an override set for base values - use it, otherwise use new values.
            updateBaseDisplayMetrics(mIsSizeForced ? mBaseDisplayWidth : newWidth,
                    mIsSizeForced ? mBaseDisplayHeight : newHeight,
                    mIsDensityForced ? mBaseDisplayDensity : newDensity,
                    mIsSizeForced ? mBaseDisplayPhysicalXDpi : newXDpi,
                    mIsSizeForced ? mBaseDisplayPhysicalYDpi : newYDpi);

            configureDisplayPolicy();

            if (physicalDisplayChanged) {
                // Reapply the rotation window settings, we are doing this after updating
                // the screen size and configuring display policy as the rotation depends
                // on the display size
                mWmService.mDisplayWindowSettings.applyRotationSettingsToDisplayLocked(this);
            }

            // Real display metrics changed, so we should also update initial values.
            mInitialDisplayWidth = newWidth;
            mInitialDisplayHeight = newHeight;
            mInitialDisplayDensity = newDensity;
            mInitialPhysicalXDpi = newXDpi;
            mInitialPhysicalYDpi = newYDpi;
            mInitialDisplayCutout = newCutout;
            mInitialRoundedCorners = newRoundedCorners;
            mCurrentUniqueDisplayId = newUniqueId;
            reconfigureDisplayLocked();

            if (physicalDisplayChanged) {
                mDisplaySwitchTransitionLauncher.onDisplayUpdated(currentRotation, getRotation(),
                        getDisplayAreaInfo());
            }
        }
    }

    /** Sets the maximum width the screen resolution can be */
    void setMaxUiWidth(int width) {
        if (DEBUG_DISPLAY) {
            Slog.v(TAG_WM, "Setting max ui width:" + width + " on display:" + getDisplayId());
        }

        mMaxUiWidth = width;

        // Update existing metrics.
        updateBaseDisplayMetrics(mBaseDisplayWidth, mBaseDisplayHeight, mBaseDisplayDensity,
                mBaseDisplayPhysicalXDpi, mBaseDisplayPhysicalYDpi);
    }

    /** Update base (override) display metrics. */
    void updateBaseDisplayMetrics(int baseWidth, int baseHeight, int baseDensity, float baseXDpi,
            float baseYDpi) {
        mBaseDisplayWidth = baseWidth;
        mBaseDisplayHeight = baseHeight;
        mBaseDisplayDensity = baseDensity;
        mBaseDisplayPhysicalXDpi = baseXDpi;
        mBaseDisplayPhysicalYDpi = baseYDpi;
        if (mIsSizeForced) {
            mBaseDisplayCutout = loadDisplayCutout(baseWidth, baseHeight);
            mBaseRoundedCorners = loadRoundedCorners(baseWidth, baseHeight);
        }

        if (mMaxUiWidth > 0 && mBaseDisplayWidth > mMaxUiWidth) {
            final float ratio = mMaxUiWidth / (float) mBaseDisplayWidth;
            mBaseDisplayHeight = (int) (mBaseDisplayHeight * ratio);
            mBaseDisplayWidth = mMaxUiWidth;
            mBaseDisplayPhysicalXDpi = mBaseDisplayPhysicalXDpi * ratio;
            mBaseDisplayPhysicalYDpi = mBaseDisplayPhysicalYDpi * ratio;
            if (!mIsDensityForced) {
                // Update the density proportionally so the size of the UI elements won't change
                // from the user's perspective.
                mBaseDisplayDensity = (int) (mBaseDisplayDensity * ratio);
            }

            if (DEBUG_DISPLAY) {
                Slog.v(TAG_WM, "Applying config restraints:" + mBaseDisplayWidth + "x"
                        + mBaseDisplayHeight + " on display:" + getDisplayId());
            }
        }
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
        mIsDensityForced = density != mInitialDisplayDensity;
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
        // Can't force size higher than the maximal allowed
        if (mMaxUiWidth > 0 && width > mMaxUiWidth) {
            final float ratio = mMaxUiWidth / (float) width;
            height = (int) (height * ratio);
            width = mMaxUiWidth;
        }

        mIsSizeForced = mInitialDisplayWidth != width || mInitialDisplayHeight != height;
        if (mIsSizeForced) {
            // Set some sort of reasonable bounds on the size of the display that we will try
            // to emulate.
            final int minSize = 200;
            final int maxScale = 3;
            final int maxSize = Math.max(mInitialDisplayWidth, mInitialDisplayHeight) * maxScale;
            width = Math.min(Math.max(width, minSize), maxSize);
            height = Math.min(Math.max(height, minSize), maxSize);
        }

        Slog.i(TAG_WM, "Using new display size: " + width + "x" + height);
        updateBaseDisplayMetrics(width, height, mBaseDisplayDensity, mBaseDisplayPhysicalXDpi,
                mBaseDisplayPhysicalYDpi);
        reconfigureDisplayLocked();

        if (!mIsSizeForced) {
            width = height = 0;
        }
        mWmService.mDisplayWindowSettings.setForcedSize(this, width, height);
    }

    DisplayCutout loadDisplayCutout(int displayWidth, int displayHeight) {
        if (mDisplayPolicy == null || mInitialDisplayCutout == null) {
            return null;
        }
        return DisplayCutout.fromResourcesRectApproximation(
                mDisplayPolicy.getSystemUiContext().getResources(), mDisplayInfo.uniqueId,
                mPhysicalDisplaySize.x, mPhysicalDisplaySize.y, displayWidth, displayHeight);
    }

    RoundedCorners loadRoundedCorners(int displayWidth, int displayHeight) {
        if (mDisplayPolicy == null || mInitialRoundedCorners == null) {
            return null;
        }
        return RoundedCorners.fromResources(
                mDisplayPolicy.getSystemUiContext().getResources(),  mDisplayInfo.uniqueId,
                mPhysicalDisplaySize.x, mPhysicalDisplaySize.y, displayWidth, displayHeight);
    }

    @Override
    void getStableRect(Rect out) {
        final InsetsState state = mDisplayContent.getInsetsStateController().getRawInsetsState();
        out.set(state.getDisplayFrame());
        out.inset(state.calculateInsets(out, systemBars(), true /* ignoreVisibility */));
    }

    /**
     * Get the default display area on the display dedicated to app windows. This one should be used
     * only as a fallback location for activity launches when no target display area is specified,
     * or for cases when multi-instance is not supported yet (like Split-screen, PiP or Recents).
     */
    TaskDisplayArea getDefaultTaskDisplayArea() {
        return mDisplayAreaPolicy.getDefaultTaskDisplayArea();
    }

    /**
     * Checks for all non-organized {@link DisplayArea}s for if there is any existing organizer for
     * their features. If so, registers them with the matched organizer.
     */
    @VisibleForTesting
    void updateDisplayAreaOrganizers() {
        if (!isTrusted()) {
            // No need to update for untrusted display.
            return;
        }
        forAllDisplayAreas(displayArea -> {
            if (displayArea.isOrganized()) {
                return;
            }
            // Check if we have a registered organizer for the DA feature.
            final IDisplayAreaOrganizer organizer =
                    mAtmService.mWindowOrganizerController.mDisplayAreaOrganizerController
                            .getOrganizerByFeature(displayArea.mFeatureId);
            if (organizer != null) {
                displayArea.setOrganizer(organizer);
            }
        });
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

            if (w.isOnScreen() && w.isVisible() && w.getFrame().contains(x, y)) {
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
    @Nullable
    Task findTaskForResizePoint(int x, int y) {
        final int delta = dipToPixel(RESIZE_HANDLE_WIDTH_IN_DP, mDisplayMetrics);
        return getItemFromTaskDisplayAreas(taskDisplayArea ->
                mTmpTaskForResizePointSearchResult.process(taskDisplayArea, x, y, delta));
    }

    void updateTouchExcludeRegion() {
        final Task focusedTask = (mFocusedApp != null ? mFocusedApp.getTask() : null);
        if (focusedTask == null) {
            mTouchExcludeRegion.setEmpty();
        } else {
            mTouchExcludeRegion.set(0, 0, mDisplayInfo.logicalWidth, mDisplayInfo.logicalHeight);
            final int delta = dipToPixel(RESIZE_HANDLE_WIDTH_IN_DP, mDisplayMetrics);
            mTmpRect.setEmpty();
            mTmpRect2.setEmpty();

            final PooledConsumer c = PooledLambda.obtainConsumer(
                    DisplayContent::processTaskForTouchExcludeRegion, this,
                    PooledLambda.__(Task.class), focusedTask, delta);
            forAllTasks(c);
            c.recycle();

            // If we removed the focused task above, add it back and only leave its
            // outside touch area in the exclusion. TapDetector is not interested in
            // any touch inside the focused task itself.
            if (!mTmpRect2.isEmpty()) {
                mTouchExcludeRegion.op(mTmpRect2, Region.Op.UNION);
            }
        }
        if (mInputMethodWindow != null && mInputMethodWindow.isVisible()) {
            // If the input method is visible and the user is typing, we don't want these touch
            // events to be intercepted and used to change focus. This would likely cause a
            // disappearance of the input method.
            mInputMethodWindow.getTouchableRegion(mTmpRegion);
            mTouchExcludeRegion.op(mTmpRegion, Op.UNION);
        }
        for (int i = mTapExcludedWindows.size() - 1; i >= 0; i--) {
            final WindowState win = mTapExcludedWindows.get(i);
            if (!win.isVisible()) {
                continue;
            }
            win.getTouchableRegion(mTmpRegion);
            mTouchExcludeRegion.op(mTmpRegion, Region.Op.UNION);
        }
        amendWindowTapExcludeRegion(mTouchExcludeRegion);
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
        // If the task is root home task and it is resizable and visible (top of its root task),
        // we want to exclude the root docked task from touch so we need the entire screen area
        // and not just a small portion which the root home task currently is resized to.
        if (task.isActivityTypeHome() && task.isVisible() && task.isResizeable()) {
            task.getDisplayArea().getBounds(mTmpRect);
        } else {
            task.getDimBounds(mTmpRect);
        }

        if (task == focusedTask) {
            // Add the focused task rect back into the exclude region once we are done
            // processing root tasks.
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
                // Intersect with display content frame. If we have system decor (status bar/
                // navigation bar), we want to exclude that from the tap detection.
                // Otherwise, if the app is partially placed under some system button (eg.
                // Recents, Home), pressing that button would cause a full series of
                // unwanted transfer focus/resume/pause, before we could go home.
                mTmpRect.inset(getInsetsStateController().getRawInsetsState().calculateInsets(
                        mTmpRect, systemBars() | ime(), false /* ignoreVisibility */));
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

    private boolean shouldDeferRemoval() {
        return isAnimating(TRANSITION | PARENTS)
                // isAnimating is a legacy transition query and will be removed, so also add a
                // check for whether this is in a shell-transition when not using legacy.
                || mTransitionController.isTransitionOnDisplay(this);
    }

    @Override
    void removeIfPossible() {
        if (shouldDeferRemoval()) {
            mDeferredRemoval = true;
            return;
        }
        removeImmediately();
    }

    @Override
    void removeImmediately() {
        mDeferredRemoval = false;
        try {
            // Clear all transitions & screen frozen states when removing display.
            mOpeningApps.clear();
            mClosingApps.clear();
            mChangingContainers.clear();
            mUnknownAppVisibilityController.clear();
            mAppTransition.removeAppTransitionTimeoutCallbacks();
            mTransitionController.unregisterLegacyListener(mFixedRotationTransitionListener);
            handleAnimatingStoppedAndTransition();
            mWmService.stopFreezingDisplayLocked();
            mDisplaySwitchTransitionLauncher.destroy();
            super.removeImmediately();
            if (DEBUG_DISPLAY) Slog.v(TAG_WM, "Removing display=" + this);
            mPointerEventDispatcher.dispose();
            setRotationAnimation(null);
            mWmService.mAnimator.removeDisplayLocked(mDisplayId);
            mOverlayLayer.release();
            mInputMonitor.onDisplayRemoved();
            mWmService.mDisplayNotificationController.dispatchDisplayRemoved(this);
            mWmService.mAccessibilityController.onDisplayRemoved(mDisplayId);
            mRootWindowContainer.mTaskSupervisor
                    .getKeyguardController().onDisplayRemoved(mDisplayId);
        } finally {
            mDisplayReady = false;
        }

        // Apply the pending transaction here since we may not be able to reach the DisplayContent
        // on the next traversal if it's removed from RootWindowContainer child list.
        getPendingTransaction().apply();
        mWmService.mWindowPlacerLocked.requestTraversal();
    }

    /** Returns true if a removal action is still being deferred. */
    @Override
    boolean handleCompleteDeferredRemoval() {
        final boolean stillDeferringRemoval =
                super.handleCompleteDeferredRemoval() || shouldDeferRemoval();

        if (!stillDeferringRemoval && mDeferredRemoval) {
            removeImmediately();
            return false;
        }
        return stillDeferringRemoval;
    }

    void adjustForImeIfNeeded() {
        final WindowState imeWin = mInputMethodWindow;
        final boolean imeVisible = imeWin != null && imeWin.isVisible()
                && imeWin.isDisplayed();
        final int imeHeight = getInputMethodWindowVisibleHeight();
        mPinnedTaskController.setAdjustedForIme(imeVisible, imeHeight);
    }

    int getInputMethodWindowVisibleHeight() {
        final InsetsState state = getInsetsStateController().getRawInsetsState();
        final InsetsSource imeSource = state.peekSource(ITYPE_IME);
        if (imeSource == null || !imeSource.isVisible()) {
            return 0;
        }
        final Rect imeFrame = imeSource.getVisibleFrame() != null
                ? imeSource.getVisibleFrame() : imeSource.getFrame();
        final Rect dockFrame = mTmpRect;
        dockFrame.set(state.getDisplayFrame());
        dockFrame.inset(state.calculateInsets(dockFrame, systemBars() | displayCutout(),
                false /* ignoreVisibility */));
        return dockFrame.bottom - imeFrame.top;
    }

    void rotateBounds(@Rotation int oldRotation, @Rotation int newRotation, Rect inOutBounds) {
        // Get display bounds on oldRotation as parent bounds for the rotation.
        getBounds(mTmpRect, oldRotation);
        RotationUtils.rotateBounds(inOutBounds, mTmpRect, oldRotation, newRotation);
    }

    public void setRotationAnimation(ScreenRotationAnimation screenRotationAnimation) {
        if (mScreenRotationAnimation != null) {
            mScreenRotationAnimation.kill();
        }
        mScreenRotationAnimation = screenRotationAnimation;

        // Hide the windows which are not significant in rotation animation. So that the windows
        // don't need to block the unfreeze time.
        if (screenRotationAnimation != null && screenRotationAnimation.hasScreenshot()) {
            startAsyncRotationIfNeeded();
        }
    }

    public ScreenRotationAnimation getRotationAnimation() {
        return mScreenRotationAnimation;
    }

    /**
     * Requests to start a transition for the display configuration change. The given changes must
     * be non-zero. This method is no-op if the display has been collected.
     */
    void requestChangeTransitionIfNeeded(@ActivityInfo.Config int changes,
            @Nullable TransitionRequestInfo.DisplayChange displayChange) {
        if (!mLastHasContent) return;
        final TransitionController controller = mTransitionController;
        if (controller.isCollecting()) {
            if (displayChange != null) {
                throw new IllegalArgumentException("Provided displayChange for non-new transition");
            }
            if (!controller.isCollecting(this)) {
                controller.collect(this);
                startAsyncRotationIfNeeded();
            }
            return;
        }
        final Transition t = controller.requestTransitionIfNeeded(TRANSIT_CHANGE, 0 /* flags */,
                this, this, null /* remoteTransition */, displayChange);
        if (t != null) {
            mAtmService.startLaunchPowerMode(POWER_MODE_REASON_CHANGE_DISPLAY);
            if (mFixedRotationLaunchingApp != null) {
                // A fixed-rotation transition is done, then continue to start a seamless display
                // transition. And be fore the start transaction is applied, the non-app windows
                // need to keep in previous rotation to avoid showing inconsistent content.
                t.setSeamlessRotation(this);
                if (mAsyncRotationController != null) {
                    mAsyncRotationController.keepAppearanceInPreviousRotation();
                }
            } else if (isRotationChanging()) {
                mWmService.mLatencyTracker.onActionStart(ACTION_ROTATE_SCREEN);
                controller.mTransitionMetricsReporter.associate(t,
                        startTime -> mWmService.mLatencyTracker.onActionEnd(ACTION_ROTATE_SCREEN));
                startAsyncRotation(false /* shouldDebounce */);
            }
            t.setKnownConfigChanges(this, changes);
        }
    }

    /** If the display is in transition, there should be a screenshot covering it. */
    @Override
    boolean inTransition() {
        return mScreenRotationAnimation != null || super.inTransition();
    }

    @Override
    public void dumpDebug(ProtoOutputStream proto, long fieldId,
            @WindowTraceLogLevel int logLevel) {
        // Critical log level logs only visible elements to mitigate performance overheard
        if (logLevel == WindowTraceLogLevel.CRITICAL && !isVisible()) {
            return;
        }

        final long token = proto.start(fieldId);
        super.dumpDebug(proto, ROOT_DISPLAY_AREA, logLevel);

        proto.write(ID, mDisplayId);
        proto.write(DPI, mBaseDisplayDensity);
        mDisplayInfo.dumpDebug(proto, DISPLAY_INFO);
        mDisplayRotation.dumpDebug(proto, DISPLAY_ROTATION);
        final ScreenRotationAnimation screenRotationAnimation = getRotationAnimation();
        if (screenRotationAnimation != null) {
            screenRotationAnimation.dumpDebug(proto, SCREEN_ROTATION_ANIMATION);
        }
        mDisplayFrames.dumpDebug(proto, DISPLAY_FRAMES);
        proto.write(MIN_SIZE_OF_RESIZEABLE_TASK_DP, mMinSizeOfResizeableTaskDp);
        if (mTransitionController.isShellTransitionsEnabled()) {
            mTransitionController.dumpDebugLegacy(proto, APP_TRANSITION);
        } else {
            mAppTransition.dumpDebug(proto, APP_TRANSITION);
        }
        if (mFocusedApp != null) {
            mFocusedApp.writeNameToProto(proto, FOCUSED_APP);
        }
        for (int i = mOpeningApps.size() - 1; i >= 0; i--) {
            mOpeningApps.valueAt(i).writeIdentifierToProto(proto, OPENING_APPS);
        }
        for (int i = mClosingApps.size() - 1; i >= 0; i--) {
            mClosingApps.valueAt(i).writeIdentifierToProto(proto, CLOSING_APPS);
        }

        final Task focusedRootTask = getFocusedRootTask();
        if (focusedRootTask != null) {
            proto.write(FOCUSED_ROOT_TASK_ID, focusedRootTask.getRootTaskId());
            final ActivityRecord focusedActivity = focusedRootTask.getDisplayArea()
                    .getFocusedActivity();
            if (focusedActivity != null) {
                focusedActivity.writeIdentifierToProto(proto, RESUMED_ACTIVITY);
            }
        } else {
            proto.write(FOCUSED_ROOT_TASK_ID, INVALID_TASK_ID);
        }
        proto.write(DISPLAY_READY, isReady());
        proto.write(IS_SLEEPING, isSleeping());
        for (int i = 0; i < mAllSleepTokens.size(); ++i) {
            mAllSleepTokens.get(i).writeTagToProto(proto, SLEEP_TOKENS);
        }

        if (mImeLayeringTarget != null) {
            mImeLayeringTarget.dumpDebug(proto, INPUT_METHOD_TARGET, logLevel);
        }
        if (mImeInputTarget != null) {
            mImeInputTarget.dumpProto(proto, INPUT_METHOD_INPUT_TARGET, logLevel);
        }
        if (mImeControlTarget != null
                && mImeControlTarget.getWindow() != null) {
            mImeControlTarget.getWindow().dumpDebug(proto, INPUT_METHOD_CONTROL_TARGET,
                    logLevel);
        }
        if (mCurrentFocus != null) {
            mCurrentFocus.dumpDebug(proto, CURRENT_FOCUS, logLevel);
        }
        if (mInsetsStateController != null) {
            for (@InternalInsetsType int type = 0; type < InsetsState.SIZE; type++) {
                final WindowContainerInsetsSourceProvider provider = mInsetsStateController
                        .peekSourceProvider(type);
                if (provider != null) {
                    provider.dumpDebug(proto, type == ITYPE_IME ? IME_INSETS_SOURCE_PROVIDER :
                            INSETS_SOURCE_PROVIDERS, logLevel);
                }
            }
        }
        proto.write(IME_POLICY, getImePolicy());
        for (Rect r : getKeepClearAreas()) {
            r.dumpDebug(proto, KEEP_CLEAR_AREAS);
        }
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
        pw.println("Display: mDisplayId=" + mDisplayId + " rootTasks=" + getRootTaskCount());
        final String subPrefix = "  " + prefix;
        pw.print(subPrefix); pw.print("init="); pw.print(mInitialDisplayWidth); pw.print("x");
        pw.print(mInitialDisplayHeight); pw.print(" "); pw.print(mInitialDisplayDensity);
        pw.print("dpi");
        pw.print(" mMinSizeOfResizeableTaskDp="); pw.print(mMinSizeOfResizeableTaskDp);
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
        pw.print("  mFocusedApp="); pw.println(mFocusedApp);
        if (mFixedRotationLaunchingApp != null) {
            pw.println("  mFixedRotationLaunchingApp=" + mFixedRotationLaunchingApp);
        }

        pw.println();
        mWallpaperController.dump(pw, "  ");

        if (mSystemGestureExclusionListeners.getRegisteredCallbackCount() > 0) {
            pw.println();
            pw.print("  mSystemGestureExclusion=");
            pw.println(mSystemGestureExclusion);
        }

        final Set<Rect> keepClearAreas = getKeepClearAreas();
        if (!keepClearAreas.isEmpty()) {
            pw.println();
            pw.print("  keepClearAreas=");
            pw.println(keepClearAreas);
        }

        pw.println();
        pw.println(prefix + "Display areas in top down Z order:");
        dumpChildDisplayArea(pw, subPrefix, dumpAll);

        pw.println();
        pw.println(prefix + "Task display areas in top down Z order:");
        forAllTaskDisplayAreas(taskDisplayArea -> {
            taskDisplayArea.dump(pw, prefix + "  ", dumpAll);
        });

        pw.println();

        final ScreenRotationAnimation rotationAnimation = getRotationAnimation();
        if (rotationAnimation != null) {
            pw.println("  mScreenRotationAnimation:");
            rotationAnimation.printTo(subPrefix, pw);
        } else if (dumpAll) {
            pw.println("  no ScreenRotationAnimation ");
        }

        pw.println();

        // Dump root task references
        final Task rootHomeTask = getDefaultTaskDisplayArea().getRootHomeTask();
        if (rootHomeTask != null) {
            pw.println(prefix + "rootHomeTask=" + rootHomeTask.getName());
        }
        final Task rootPinnedTask = getDefaultTaskDisplayArea().getRootPinnedTask();
        if (rootPinnedTask != null) {
            pw.println(prefix + "rootPinnedTask=" + rootPinnedTask.getName());
        }
        // TODO: Support recents on non-default task containers
        final Task rootRecentsTask = getDefaultTaskDisplayArea().getRootTask(
                WINDOWING_MODE_UNDEFINED, ACTIVITY_TYPE_RECENTS);
        if (rootRecentsTask != null) {
            pw.println(prefix + "rootRecentsTask=" + rootRecentsTask.getName());
        }
        final Task rootDreamTask =
                getRootTask(WINDOWING_MODE_UNDEFINED, ACTIVITY_TYPE_DREAM);
        if (rootDreamTask != null) {
            pw.println(prefix + "rootDreamTask=" + rootDreamTask.getName());
        }

        pw.println();
        mPinnedTaskController.dump(prefix, pw);

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
        mDwpcHelper.dump(prefix, pw);
    }

    @Override
    public String toString() {
        return "Display{#" + mDisplayId + " state=" + Display.stateToString(mDisplayInfo.state)
                + " size=" + mDisplayInfo.logicalWidth + "x" + mDisplayInfo.logicalHeight
                + " " + Surface.rotationToString(mDisplayInfo.rotation) + "}";
    }

    String getName() {
        return "Display " + mDisplayId + " name=\"" + mDisplayInfo.name + "\"";
    }

    /** Find the visible, touch-deliverable window under the given point */
    WindowState getTouchableWinAtPointLocked(float xf, float yf) {
        final int x = (int) xf;
        final int y = (int) yf;
        final WindowState touchedWin = getWindow(w -> {
            final int flags = w.mAttrs.flags;
            if (!w.isVisible()) {
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
            ProtoLog.v(WM_DEBUG_FOCUS_LIGHT, "findFocusedWindow: No focusable windows, display=%d",
                    getDisplayId());
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
            final WindowState prevTarget = mImeLayeringTarget;
            final WindowState newTarget = computeImeTarget(true /* updateImeTarget*/);
            imWindowChanged = prevTarget != newTarget;

            if (mode != UPDATE_FOCUS_WILL_ASSIGN_LAYERS
                    && mode != UPDATE_FOCUS_WILL_PLACE_SURFACES) {
                assignWindowLayers(false /* setLayoutNeeded */);
            }

            if (imWindowChanged) {
                mWmService.mWindowsChanged = true;
                setLayoutNeeded();
                newFocus = findFocusedWindowIfNeeded(topFocusedDisplayId);
            }
        }

        ProtoLog.d(WM_DEBUG_FOCUS_LIGHT, "Changing focus from %s to %s displayId=%d Callers=%s",
                mCurrentFocus, newFocus, getDisplayId(), Debug.getCallers(4));
        final WindowState oldFocus = mCurrentFocus;
        mCurrentFocus = newFocus;

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

        getDisplayPolicy().focusChangedLw(oldFocus, newFocus);

        if (imWindowChanged && oldFocus != mInputMethodWindow) {
            // Focus of the input method window changed. Perform layout if needed.
            if (mode == UPDATE_FOCUS_PLACING_SURFACES) {
                performLayout(true /*initial*/,  updateInputWindows);
            } else if (mode == UPDATE_FOCUS_WILL_PLACE_SURFACES) {
                // Client will do the layout, but we need to assign layers
                // for handleNewWindowLocked() below.
                assignWindowLayers(false /* setLayoutNeeded */);
            }
        }

        if (mode != UPDATE_FOCUS_WILL_ASSIGN_LAYERS) {
            // If we defer assigning layers, then the caller is responsible for doing this part.
            getInputMonitor().setInputFocusLw(newFocus, updateInputWindows);
        }

        adjustForImeIfNeeded();
        updateKeepClearAreas();

        // We may need to schedule some toast windows to be removed. The toasts for an app that
        // does not have input focus are removed within a timeout to prevent apps to redress
        // other apps' UI.
        scheduleToastWindowsTimeoutIfNeededLocked(oldFocus, newFocus);

        if (mode == UPDATE_FOCUS_PLACING_SURFACES) {
            pendingLayoutChanges |= FINISH_LAYOUT_REDO_ANIM;
        }

        // Notify the accessibility manager for the change so it has the windows before the newly
        // focused one starts firing events.
        // TODO(b/151179149) investigate what info accessibility service needs before input can
        // dispatch focus to clients.
        if (mWmService.mAccessibilityController.hasCallbacks()) {
            mWmService.mH.sendMessage(PooledLambda.obtainMessage(
                    this::updateAccessibilityOnWindowFocusChanged,
                    mWmService.mAccessibilityController));
        }

        return true;
    }

    void updateAccessibilityOnWindowFocusChanged(AccessibilityController accessibilityController) {
        accessibilityController.onWindowFocusChangedNot(getDisplayId());
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

            // Called even if the focused app is not changed in case the app is moved to a different
            // TaskDisplayArea.
            onLastFocusedTaskDisplayAreaChanged(newFocus.getDisplayArea());
        }
        if (mFocusedApp == newFocus) {
            return false;
        }
        ProtoLog.i(WM_DEBUG_FOCUS_LIGHT, "setFocusedApp %s displayId=%d Callers=%s",
                newFocus, getDisplayId(), Debug.getCallers(4));
        final Task oldTask = mFocusedApp != null ? mFocusedApp.getTask() : null;
        final Task newTask = newFocus != null ? newFocus.getTask() : null;
        mFocusedApp = newFocus;
        if (oldTask != newTask) {
            if (oldTask != null) oldTask.onAppFocusChanged(false);
            if (newTask != null) newTask.onAppFocusChanged(true);
        }

        getInputMonitor().setFocusedAppLw(newFocus);
        updateTouchExcludeRegion();
        return true;
    }

    /** Update the top activity and the uids of non-finishing activity */
    void onRunningActivityChanged() {
        mDwpcHelper.onRunningActivityChanged();
    }

    /** Called when the focused {@link TaskDisplayArea} on this display may have changed. */
    void onLastFocusedTaskDisplayAreaChanged(@Nullable TaskDisplayArea taskDisplayArea) {
        // Only record the TaskDisplayArea that handles orientation request.
        if (taskDisplayArea != null && taskDisplayArea.handlesOrientationChangeFromDescendant()) {
            mOrientationRequestingTaskDisplayArea = taskDisplayArea;
            return;
        }

        // If the previous TDA no longer handles orientation request, clear it.
        if (mOrientationRequestingTaskDisplayArea != null
                && !mOrientationRequestingTaskDisplayArea
                .handlesOrientationChangeFromDescendant()) {
            mOrientationRequestingTaskDisplayArea = null;
        }
    }

    /**
     * Gets the {@link TaskDisplayArea} that we respect orientation requests from apps below it.
     */
    @Nullable
    TaskDisplayArea getOrientationRequestingTaskDisplayArea() {
        return mOrientationRequestingTaskDisplayArea;
    }

    /** Updates the layer assignment of windows on this display. */
    void assignWindowLayers(boolean setLayoutNeeded) {
        Trace.traceBegin(TRACE_TAG_WINDOW_MANAGER, "assignWindowLayers");
        assignChildLayers(getSyncTransaction());
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
        final Transaction t = mWmService.mTransactionFactory.get();
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
                wsa.destroySurface(t);
                mWmService.mForceRemoves.add(w);
                mTmpWindow = w;
            } else if (w.mActivityRecord != null && !w.mActivityRecord.isClientVisible()) {
                Slog.w(TAG_WM, "LEAKED SURFACE (app token hidden): "
                        + w + " surface=" + wsa.mSurfaceController
                        + " token=" + w.mActivityRecord);
                ProtoLog.i(WM_SHOW_TRANSACTIONS, "SURFACE LEAK DESTROY: %s", w);
                wsa.destroySurface(t);
                mTmpWindow = w;
            }
        }, false /* traverseTopToBottom */);
        t.apply();

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
            mAtmService.onImeWindowSetOnDisplayArea(imePid, mImeWindowsContainer);
        }
        mInsetsStateController.getSourceProvider(ITYPE_IME).setWindowContainer(win,
                mDisplayPolicy.getImeSourceFrameProvider(), null /* imeFrameProvider */);
        computeImeTarget(true /* updateImeTarget */);
        updateImeControlTarget();
    }

    /**
     * Determine and return the window that should be the IME target for layering the IME window.
     * @param updateImeTarget If true the system IME target will be updated to match what we found.
     * @return The window that should be used as the IME target or null if there isn't any.
     */
    WindowState computeImeTarget(boolean updateImeTarget) {
        if (mInputMethodWindow == null) {
            // There isn't an IME so there shouldn't be a target...That was easy!
            if (updateImeTarget) {
                if (DEBUG_INPUT_METHOD) Slog.w(TAG_WM, "Moving IM target from "
                        + mImeLayeringTarget + " to null since mInputMethodWindow is null");
                setImeLayeringTargetInner(null);
            }
            return null;
        }

        final WindowState curTarget = mImeLayeringTarget;
        if (!canUpdateImeTarget()) {
            if (DEBUG_INPUT_METHOD) Slog.w(TAG_WM, "Defer updating IME target");
            mUpdateImeRequestedWhileDeferred = true;
            return curTarget;
        }

        // TODO(multidisplay): Needs some serious rethought when the target and IME are not on the
        // same display. Or even when the current IME/target are not on the same screen as the next
        // IME/target. For now only look for input windows on the main screen.
        mUpdateImeTarget = updateImeTarget;
        WindowState target = getWindow(mComputeImeTargetPredicate);

        if (DEBUG_INPUT_METHOD && updateImeTarget) Slog.v(TAG_WM,
                "Proposed new IME target: " + target + " for display: " + getDisplayId());

        if (DEBUG_INPUT_METHOD) Slog.v(TAG_WM, "Desired input method target=" + target
                + " updateImeTarget=" + updateImeTarget);

        if (target == null) {
            if (updateImeTarget) {
                if (DEBUG_INPUT_METHOD) Slog.w(TAG_WM, "Moving IM target from " + curTarget
                        + " to null." + (SHOW_STACK_CRAWLS ? " Callers="
                        + Debug.getCallers(4) : ""));
                setImeLayeringTargetInner(null);
            }

            return null;
        }

        if (updateImeTarget) {
            if (DEBUG_INPUT_METHOD) Slog.w(TAG_WM, "Moving IM target from " + curTarget + " to "
                    + target + (SHOW_STACK_CRAWLS ? " Callers=" + Debug.getCallers(4) : ""));
            setImeLayeringTargetInner(target);
        }

        return target;
    }

    /**
     * Calling {@link #computeImeTarget(boolean)} to update the input method target window in
     * the candidate app window token if needed.
     */
    void computeImeTargetIfNeeded(ActivityRecord candidate) {
        if (mImeLayeringTarget != null && mImeLayeringTarget.mActivityRecord == candidate) {
            computeImeTarget(true /* updateImeTarget */);
        }
    }

    private boolean isImeControlledByApp() {
        return mImeInputTarget != null && mImeInputTarget.shouldControlIme();
    }

    boolean shouldImeAttachedToApp() {
        if (mImeWindowsContainer.isOrganized()) {
            return false;
        }

        // Force attaching IME to the display when magnifying, or it would be magnified with
        // target app together.
        final boolean allowAttachToApp = (mMagnificationSpec == null);

        return allowAttachToApp && isImeControlledByApp()
                && mImeLayeringTarget != null
                && mImeLayeringTarget.mActivityRecord != null
                && mImeLayeringTarget.getWindowingMode() == WINDOWING_MODE_FULLSCREEN
                // IME is attached to app windows that fill display area. This excludes
                // letterboxed windows.
                && mImeLayeringTarget.matchesDisplayAreaBounds();
    }

    /**
     * Unlike {@link #shouldImeAttachedToApp()}, this method returns {@code @true} only when both
     * the IME layering target is valid to attach the IME surface to the app, and the
     * {@link #mInputMethodSurfaceParent} of the {@link ImeContainer} has actually attached to
     * the app. (i.e. Even if {@link #shouldImeAttachedToApp()} returns {@code true}, calling this
     * method will return {@code false} if the IME surface doesn't actually attach to the app.)
     */
    boolean isImeAttachedToApp() {
        return shouldImeAttachedToApp()
                && mInputMethodSurfaceParent != null
                && mInputMethodSurfaceParent.isSameSurface(
                        mImeLayeringTarget.mActivityRecord.getSurfaceControl());
    }

    /**
     * Finds the window which can host IME if IME target cannot host it.
     * e.g. IME target cannot host IME  when display doesn't support IME/system decorations.
     *
     * @param target current IME target.
     * @return {@link InsetsControlTarget} that can host IME.
     */
    InsetsControlTarget getImeHostOrFallback(WindowState target) {
        if (target != null
                && target.getDisplayContent().getImePolicy() == DISPLAY_IME_POLICY_LOCAL) {
            return target;
        }
        return getImeFallback();
    }

    InsetsControlTarget getImeFallback() {
        // host is in non-default display that doesn't support system decor, default to
        // default display's StatusBar to control IME (when available), else let system control it.
        final DisplayContent defaultDc = mWmService.getDefaultDisplayContentLocked();
        WindowState statusBar = defaultDc.getDisplayPolicy().getStatusBar();
        return statusBar != null ? statusBar : defaultDc.mRemoteInsetsControlTarget;
    }

    /**
     * Returns the corresponding IME insets control target according the IME target type.
     *
     * @param type The type of the IME target.
     * @see #IME_TARGET_LAYERING
     * @see #IME_TARGET_CONTROL
     */
    InsetsControlTarget getImeTarget(@InputMethodTarget int type) {
        switch (type) {
            case IME_TARGET_LAYERING: return mImeLayeringTarget;
            case IME_TARGET_CONTROL: return mImeControlTarget;
            default:
                return null;
        }
    }

    InputTarget getImeInputTarget() {
        return mImeInputTarget;
    }

    // IMPORTANT: When introducing new dependencies in this method, make sure that
    // changes to those result in RootWindowContainer.updateDisplayImePolicyCache()
    // being called.
    @DisplayImePolicy int getImePolicy() {
        if (!isTrusted()) {
            return DISPLAY_IME_POLICY_FALLBACK_DISPLAY;
        }
        final int imePolicy = mWmService.mDisplayWindowSettings.getImePolicyLocked(this);
        if (imePolicy == DISPLAY_IME_POLICY_FALLBACK_DISPLAY && forceDesktopMode()) {
            // If the display has not explicitly requested for the IME to be hidden then it shall
            // show the IME locally.
            return DISPLAY_IME_POLICY_LOCAL;
        }
        return imePolicy;
    }

    boolean forceDesktopMode() {
        return mWmService.mForceDesktopModeOnExternalDisplays && !isDefaultDisplay && !isPrivate();
    }

    /** @see WindowManagerInternal#onToggleImeRequested */
    void onShowImeRequested() {
        if (mImeLayeringTarget == null || mInputMethodWindow == null) {
            return;
        }
        // If IME window will be shown on the rotated activity, share the transformed state to
        // IME window so it can compute rotated frame with rotated configuration.
        if (mImeLayeringTarget.mToken.isFixedRotationTransforming()) {
            mInputMethodWindow.mToken.linkFixedRotationTransform(mImeLayeringTarget.mToken);
            // Hide the window until the rotation is done to avoid intermediate artifacts if the
            // parent surface of IME container is changed.
            if (mAsyncRotationController != null) {
                mAsyncRotationController.hideImmediately(mInputMethodWindow.mToken);
            }
        }
    }

    @VisibleForTesting
    void setImeLayeringTarget(WindowState target) {
        mImeLayeringTarget = target;
    }

    /**
     * Sets the window the IME is on top of.
     * @param target window to place the IME surface on top of. If {@code null}, the IME will be
     *               placed at its parent's surface.
     */
    private void setImeLayeringTargetInner(@Nullable WindowState target) {
        /**
         * This function is also responsible for updating the IME control target
         * and so in the case where the IME layering target does not change
         * but the Input target does (for example, IME moving to a SurfaceControlViewHost
         * we have to continue executing this function, otherwise there is no work
         * to do.
         */
        if (target == mImeLayeringTarget && mLastImeInputTarget == mImeInputTarget) {
            return;
        }
        mLastImeInputTarget = mImeInputTarget;

        // If the IME target is the input target, before it changes, prepare the IME screenshot
        // for the last IME target when its task is applying app transition. This is for the
        // better IME transition to keep IME visibility when transitioning to the next task.
        if (mImeLayeringTarget != null && mImeLayeringTarget == mImeInputTarget) {
            boolean nonAppImeTargetAnimatingExit = mImeLayeringTarget.mAnimatingExit
                    && mImeLayeringTarget.mAttrs.type != TYPE_BASE_APPLICATION
                    && mImeLayeringTarget.isSelfAnimating(0, ANIMATION_TYPE_WINDOW_ANIMATION);
            if (mImeLayeringTarget.inTransitionSelfOrParent() || nonAppImeTargetAnimatingExit) {
                showImeScreenshot();
            }
        }

        ProtoLog.i(WM_DEBUG_IME, "setInputMethodTarget %s", target);
        final boolean layeringTargetChanged = target != mImeLayeringTarget;
        mImeLayeringTarget = target;

        // 1. Reparent the IME container window to the target root DA to get the correct bounds and
        // config. Only happens when the target window is in a different root DA and ImeContainer
        // is not organized (see FEATURE_IME and updateImeParent).
        if (target != null && !mImeWindowsContainer.isOrganized()) {
            RootDisplayArea targetRoot = target.getRootDisplayArea();
            if (targetRoot != null && targetRoot != mImeWindowsContainer.getRootDisplayArea()) {
                // Reposition the IME container to the target root to get the correct bounds and
                // config.
                targetRoot.placeImeContainer(mImeWindowsContainer);
                // Directly hide the IME window so it doesn't flash immediately after reparenting.
                // InsetsController will make IME visible again before animating it.
                if (mInputMethodWindow != null) {
                    mInputMethodWindow.hide(false /* doAnimation */, false /* requestAnim */);
                }
            }
        }
        // 2. Assign window layers based on the IME surface parent to make sure it is on top of the
        // app.
        assignWindowLayers(true /* setLayoutNeeded */);
        // 3. The z-order of IME might have been changed. Update the above insets state.
        mInsetsStateController.updateAboveInsetsState(
                mInsetsStateController.getRawInsetsState().getSourceOrDefaultVisibility(ITYPE_IME));
        // 4. Update the IME control target to apply any inset change and animation.
        // 5. Reparent the IME container surface to either the input target app, or the IME window
        // parent.
        updateImeControlTarget(layeringTargetChanged);
    }

    @VisibleForTesting
    void setImeInputTarget(InputTarget target) {
        mImeInputTarget = target;
        if (refreshImeSecureFlag(getPendingTransaction())) {
            mWmService.requestTraversal();
        }
    }

    /**
     * Re-check the IME target's SECURE flag since it's possible to have changed after the target
     * was set.
     */
    boolean refreshImeSecureFlag(Transaction t) {
        boolean canScreenshot = mImeInputTarget == null || mImeInputTarget.canScreenshotIme();
        return mImeWindowsContainer.setCanScreenshot(t, canScreenshot);
    }

    @VisibleForTesting
    void setImeControlTarget(InsetsControlTarget target) {
        mImeControlTarget = target;
    }

    // ========== Begin of ImeScreenshot stuff ==========
    /** The screenshot IME surface to place on the task while transitioning to the next task. */
    ImeScreenshot mImeScreenshot;

    static final class ImeScreenshot {
        private WindowState mImeTarget;
        private SurfaceControl.Builder mSurfaceBuilder;
        private SurfaceControl mImeSurface;

        ImeScreenshot(SurfaceControl.Builder surfaceBuilder, @NonNull WindowState imeTarget) {
            mSurfaceBuilder = surfaceBuilder;
            mImeTarget = imeTarget;
        }

        WindowState getImeTarget() {
            return mImeTarget;
        }

        private SurfaceControl createImeSurface(SurfaceControl.ScreenshotHardwareBuffer b,
                Transaction t) {
            final HardwareBuffer buffer = b.getHardwareBuffer();
            ProtoLog.i(WM_DEBUG_IME, "create IME snapshot for %s, buff width=%s, height=%s",
                    mImeTarget, buffer.getWidth(), buffer.getHeight());
            final WindowState imeWindow = mImeTarget.getDisplayContent().mInputMethodWindow;
            final ActivityRecord activity = mImeTarget.mActivityRecord;
            final SurfaceControl imeParent = mImeTarget.mAttrs.type == TYPE_BASE_APPLICATION
                    ? activity.getSurfaceControl()
                    : mImeTarget.getSurfaceControl();
            final SurfaceControl imeSurface = mSurfaceBuilder
                    .setName("IME-snapshot-surface")
                    .setBLASTLayer()
                    .setFormat(buffer.getFormat())
                    // Attaching IME snapshot to the associated IME layering target on the
                    // activity when:
                    // - The target is activity main window: attaching on top of the activity.
                    // - The target is non-activity main window (e.g. activity overlay or
                    // dialog-themed activity): attaching on top of the target since the layer has
                    // already above the activity.
                    .setParent(imeParent)
                    .setCallsite("DisplayContent.attachAndShowImeScreenshotOnTarget")
                    .build();
            // Make IME snapshot as trusted overlay
            InputMonitor.setTrustedOverlayInputInfo(imeSurface, t, imeWindow.getDisplayId(),
                    "IME-snapshot-surface");
            t.setBuffer(imeSurface, buffer);
            t.setColorSpace(activity.mSurfaceControl, ColorSpace.get(ColorSpace.Named.SRGB));
            t.setLayer(imeSurface, 1);

            final Point surfacePosition = new Point(imeWindow.getFrame().left,
                    imeWindow.getFrame().top);
            if (imeParent == activity.getSurfaceControl()) {
                t.setPosition(imeSurface, surfacePosition.x, surfacePosition.y);
            } else {
                surfacePosition.offset(-mImeTarget.getFrame().left, -mImeTarget.getFrame().top);
                surfacePosition.offset(mImeTarget.mAttrs.surfaceInsets.left,
                        mImeTarget.mAttrs.surfaceInsets.top);
                t.setPosition(imeSurface, surfacePosition.x, surfacePosition.y);
            }
            ProtoLog.i(WM_DEBUG_IME, "Set IME snapshot position: (%d, %d)", surfacePosition.x,
                    surfacePosition.y);
            return imeSurface;
        }

        private void removeImeSurface(Transaction t) {
            if (mImeSurface != null) {
                ProtoLog.i(WM_DEBUG_IME, "remove IME snapshot, caller=%s", Debug.getCallers(6));
                t.remove(mImeSurface);
                mImeSurface = null;
            }
        }

        void attachAndShow(Transaction t) {
            final DisplayContent dc = mImeTarget.getDisplayContent();
            // Prepare IME screenshot for the target if it allows to attach into.
            final Task task = mImeTarget.getTask();
            // Re-new the IME screenshot when it does not exist or the size changed.
            final boolean renewImeSurface = mImeSurface == null
                    || mImeSurface.getWidth() != dc.mInputMethodWindow.getFrame().width()
                    || mImeSurface.getHeight() != dc.mInputMethodWindow.getFrame().height();
            if (task != null && !task.isActivityTypeHomeOrRecents()) {
                SurfaceControl.ScreenshotHardwareBuffer imeBuffer = renewImeSurface
                        ? dc.mWmService.mTaskSnapshotController.snapshotImeFromAttachedTask(task)
                        : null;
                if (imeBuffer != null) {
                    // Remove the last IME surface when the surface needs to renew.
                    removeImeSurface(t);
                    mImeSurface = createImeSurface(imeBuffer, t);
                }
            }
            final boolean isValidSnapshot = mImeSurface != null && mImeSurface.isValid();
            // Showing the IME screenshot if the target has already in app transition stage.
            // Note that if the current IME insets is not showing, no need to show IME screenshot
            // to reflect the true IME insets visibility and the app task layout as possible.
            if (isValidSnapshot
                    && dc.getInsetsStateController().getImeSourceProvider().isImeShowing()) {
                ProtoLog.i(WM_DEBUG_IME, "show IME snapshot, ime target=%s, callers=%s",
                        mImeTarget, Debug.getCallers(6));
                t.show(mImeSurface);
            } else if (!isValidSnapshot) {
                removeImeSurface(t);
            }
        }

        void detach(Transaction t) {
            removeImeSurface(t);
        }
    }

    private void attachAndShowImeScreenshotOnTarget() {
        // No need to attach screenshot if the IME target not exists or screen is off.
        if (!shouldImeAttachedToApp() || !mWmService.mPolicy.isScreenOn()) {
            return;
        }

        final SurfaceControl.Transaction t = getPendingTransaction();
        // Prepare IME screenshot for the target if it allows to attach into.
        if (mInputMethodWindow != null && mInputMethodWindow.isVisible()) {
            // Remove the obsoleted IME snapshot first in case the new snapshot happens to
            // override the current one before the transition finish and the surface never be
            // removed on the task.
            removeImeSurfaceImmediately();
            mImeScreenshot = new ImeScreenshot(
                    mWmService.mSurfaceControlFactory.apply(null), mImeLayeringTarget);
            mImeScreenshot.attachAndShow(t);
        }
    }

    /**
     * Shows the IME screenshot and attach to the IME target window.
     *
     * Used when the IME target window with IME visible is transitioning to the next target.
     * e.g. App transitioning or swiping this the task of the IME target window to recents app.
     */
    void showImeScreenshot() {
        attachAndShowImeScreenshotOnTarget();
    }

    /**
     * Removes the IME screenshot when necessary.
     *
     * Used when app transition animation finished or obsoleted screenshot surface like size
     * changed by rotation.
     */
    void removeImeScreenshotIfPossible() {
        if (mImeLayeringTarget == null
                || mImeLayeringTarget.mAttrs.type != TYPE_APPLICATION_STARTING
                && !mImeLayeringTarget.inTransitionSelfOrParent()) {
            removeImeSurfaceImmediately();
        }
    }

    /** Removes the IME screenshot immediately. */
    void removeImeSurfaceImmediately() {
        if (mImeScreenshot != null) {
            mImeScreenshot.detach(getSyncTransaction());
            mImeScreenshot = null;
        }
    }
 // ========== End of ImeScreenshot stuff ==========

    /**
     * The IME input target is the window which receives input from IME. It is also a candidate
     * which controls the visibility and animation of the input method window.
     */
    void updateImeInputAndControlTarget(InputTarget target) {
        if (mImeInputTarget != target) {
            ProtoLog.i(WM_DEBUG_IME, "setInputMethodInputTarget %s", target);
            setImeInputTarget(target);
            mInsetsStateController.updateAboveInsetsState(mInsetsStateController
                    .getRawInsetsState().getSourceOrDefaultVisibility(ITYPE_IME));
            // Force updating the IME parent when the IME control target has been updated to the
            // remote target but updateImeParent not happen because ImeLayeringTarget and
            // ImeInputTarget are different. Then later updateImeParent would be ignored when there
            // is no new IME control target to change the IME parent.
            final boolean forceUpdateImeParent = mImeControlTarget == mRemoteInsetsControlTarget
                    && (mInputMethodSurfaceParent != null
                    && !mInputMethodSurfaceParent.isSameSurface(
                            mImeWindowsContainer.getParent().mSurfaceControl));
            updateImeControlTarget(forceUpdateImeParent);
        }
        // Unfreeze IME insets after the new target updated, in case updateAboveInsetsState may
        // deliver unrelated IME insets change to the non-IME requester.
        if (target != null) {
            target.unfreezeInsetsAfterStartInput();
        }
    }

    void updateImeControlTarget() {
        updateImeControlTarget(false /* forceUpdateImeParent */);
    }

    void updateImeControlTarget(boolean forceUpdateImeParent) {
        InsetsControlTarget prevImeControlTarget = mImeControlTarget;
        mImeControlTarget = computeImeControlTarget();
        mInsetsStateController.onImeControlTargetChanged(mImeControlTarget);
        // Update Ime parent when IME insets leash created or the new IME layering target might
        // updated from setImeLayeringTarget, which is the best time that default IME visibility
        // has been settled down after IME control target changed.
        final boolean imeControlChanged = prevImeControlTarget != mImeControlTarget;
        if (imeControlChanged || forceUpdateImeParent) {
            updateImeParent();
        }

        final WindowState win = InsetsControlTarget.asWindowOrNull(mImeControlTarget);
        final IBinder token = win != null ? win.mClient.asBinder() : null;
        // Note: not allowed to call into IMMS with the WM lock held, hence the post.
        mWmService.mH.post(() -> InputMethodManagerInternal.get().reportImeControl(token));
    }

    void updateImeParent() {
        if (mImeWindowsContainer.isOrganized()) {
            if (DEBUG_INPUT_METHOD) {
                Slog.i(TAG_WM, "ImeContainer is organized. Skip updateImeParent.");
            }
            // Leave the ImeContainer where the DisplayAreaPolicy placed it.
            // FEATURE_IME is organized by vendor so they are responible for placing the surface.
            mInputMethodSurfaceParent = null;
            return;
        }

        final SurfaceControl newParent = computeImeParent();
        if (newParent != null && newParent != mInputMethodSurfaceParent) {
            mInputMethodSurfaceParent = newParent;
            getSyncTransaction().reparent(mImeWindowsContainer.mSurfaceControl, newParent);
            // When surface parent is removed, the relative layer will also be removed. We need to
            // do a force update to make sure there is a layer set for the new parent.
            assignRelativeLayerForIme(getSyncTransaction(), true /* forceUpdate */);
            scheduleAnimation();

            mWmService.mH.post(() -> InputMethodManagerInternal.get().onImeParentChanged());
        }
    }

    /**
     * Computes the window where we hand IME control to.
     */
    @VisibleForTesting
    InsetsControlTarget computeImeControlTarget() {
        if (!isImeControlledByApp() && mRemoteInsetsControlTarget != null
                || (mImeInputTarget != null
                        && getImeHostOrFallback(mImeInputTarget.getWindowState())
                               == mRemoteInsetsControlTarget)) {
            return mRemoteInsetsControlTarget;
        } else {
            return mImeInputTarget != null ? mImeInputTarget.getWindowState() : null;
        }
    }

    /**
     * Computes the window the IME should be attached to.
     */
    @VisibleForTesting
    SurfaceControl computeImeParent() {
        if (mImeLayeringTarget != null) {
            // Ensure changing the IME parent when the layering target that may use IME has
            // became to the input target for preventing IME flickers.
            // Note that:
            // 1) For the imeLayeringTarget that may not use IME but requires IME on top
            // of it (e.g. an overlay window with NOT_FOCUSABLE|ALT_FOCUSABLE_IM flags), we allow
            // it to re-parent the IME on top the display to keep the legacy behavior.
            // 2) Even though the starting window won't use IME, the associated activity
            // behind the starting window may request the input. If so, then we should still hold
            // the IME parent change until the activity started the input.
            boolean imeLayeringTargetMayUseIme =
                    LayoutParams.mayUseInputMethod(mImeLayeringTarget.mAttrs.flags)
                    || mImeLayeringTarget.mAttrs.type == TYPE_APPLICATION_STARTING;
            if (imeLayeringTargetMayUseIme && (mImeInputTarget == null
                    || mImeLayeringTarget.mActivityRecord != mImeInputTarget.getActivityRecord())) {
                // Do not change parent if the window hasn't requested IME.
                return null;
            }
        }
        // Attach it to app if the target is part of an app and such app is covering the entire
        // screen. If it's not covering the entire screen the IME might extend beyond the apps
        // bounds.
        if (shouldImeAttachedToApp()) {
            return mImeLayeringTarget.mActivityRecord.getSurfaceControl();
        }
        // Otherwise, we just attach it to where the display area policy put it.
        return mImeWindowsContainer.getParent() != null
                ? mImeWindowsContainer.getParent().getSurfaceControl() : null;
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
            if (w.mActivityRecord == null && w.canBeHiddenByKeyguard()
                    && w.wouldBeVisibleIfPolicyIgnored() && !w.isVisible()) {
                w.startAnimation(policy.createHiddenByKeyguardExit(
                        onWallpaper, goingToShade, subtle));
            }
        }, true /* traverseTopToBottom */);
        for (int i = mShellRoots.size() - 1; i >= 0; --i) {
            mShellRoots.valueAt(i).startAnimation(policy.createHiddenByKeyguardExit(
                    onWallpaper, goingToShade, subtle));
        }
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
            final boolean isVisible = w.isVisible() && !w.mObscured;
            final boolean isDrawn = w.isDrawn();
            if (isVisible && !isDrawn) {
                ProtoLog.d(WM_DEBUG_BOOT,
                        "DisplayContent: boot is waiting for window of type %d to be drawn",
                        w.mAttrs.type);
                return true;
            }
            if (isDrawn) {
                switch (w.mAttrs.type) {
                    case TYPE_BOOT_PROGRESS:
                    case TYPE_BASE_APPLICATION:
                    case TYPE_WALLPAPER:
                        drawnWindowTypes.put(w.mAttrs.type, true);
                        break;
                    case TYPE_NOTIFICATION_SHADE:
                        drawnWindowTypes.put(TYPE_NOTIFICATION_SHADE,
                                mWmService.mPolicy.isKeyguardDrawnLw());
                        break;
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
        }

        if (DEBUG_INPUT_METHOD) {
            Slog.i(TAG_WM, "IM target uid/pid: " + imFocus.mSession.mUid
                    + "/" + imFocus.mSession.mPid);
            Slog.i(TAG_WM, "Requesting client uid/pid: " + uid + "/" + pid);
        }

        return imFocus.mSession.mUid == uid && imFocus.mSession.mPid == pid;
    }

    boolean hasSecureWindowOnScreen() {
        final WindowState win = getWindow(w -> w.isOnScreen() && w.isSecureLocked());
        return win != null;
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

    /**
     * Callbacks when the given type of {@link WindowContainer} animation finished running in the
     * hierarchy.
     */
    void onWindowAnimationFinished(@NonNull WindowContainer wc, int type) {
        if (mImeScreenshot != null) {
            ProtoLog.i(WM_DEBUG_IME,
                    "onWindowAnimationFinished, wc=%s, type=%s, imeSnapshot=%s, target=%s",
                    wc, SurfaceAnimator.animationTypeToString(type), mImeScreenshot,
                    mImeScreenshot.getImeTarget());
        }
        if (mImeScreenshot != null && (wc == mImeScreenshot.getImeTarget()
                || wc.getWindow(w -> w == mImeScreenshot.getImeTarget()) != null)
                && (type & WindowState.EXIT_ANIMATING_TYPES) != 0) {
            removeImeSurfaceImmediately();
        }
    }

    // TODO: Super unexpected long method that should be broken down...
    void applySurfaceChangesTransaction() {
        final WindowSurfacePlacer surfacePlacer = mWmService.mWindowPlacerLocked;

        mTmpUpdateAllDrawn.clear();

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

        // Perform a layout, if needed.
        performLayout(true /* initial */, false /* updateInputWindows */);
        pendingLayoutChanges = 0;

        Trace.traceBegin(TRACE_TAG_WINDOW_MANAGER, "applyPostLayoutPolicy");
        try {
            mDisplayPolicy.beginPostLayoutPolicyLw();
            forAllWindows(mApplyPostLayoutPolicy, true /* traverseTopToBottom */);
            mDisplayPolicy.finishPostLayoutPolicyLw();
        } finally {
            Trace.traceEnd(TRACE_TAG_WINDOW_MANAGER);
        }
        mInsetsStateController.onPostLayout();

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
        if (!mWmService.mDisplayFrozen) {
            mWmService.mDisplayManagerInternal.setDisplayProperties(mDisplayId,
                    mLastHasContent,
                    mTmpApplySurfaceChangesTransactionState.preferredRefreshRate,
                    mTmpApplySurfaceChangesTransactionState.preferredModeId,
                    mTmpApplySurfaceChangesTransactionState.preferredMinRefreshRate,
                    mTmpApplySurfaceChangesTransactionState.preferredMaxRefreshRate,
                    mTmpApplySurfaceChangesTransactionState.preferMinimalPostProcessing,
                    true /* inTraversal, must call performTraversalInTrans... below */);
        }
        // If the display now has content, or no longer has content, update recording.
        updateRecording();

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

    private void getBounds(Rect out, @Rotation int rotation) {
        getBounds(out);

        // Rotate the Rect if needed.
        final int currentRotation = mDisplayInfo.rotation;
        final int rotationDelta = deltaRotation(currentRotation, rotation);
        if (rotationDelta == ROTATION_90 || rotationDelta == ROTATION_270) {
            out.set(0, 0, out.height(), out.width());
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

        if (DEBUG_LAYOUT) {
            Slog.v(TAG, "-------------------------------------");
            Slog.v(TAG, "performLayout: dw=" + mDisplayInfo.logicalWidth
                    + " dh=" + mDisplayInfo.logicalHeight);
        }

        int seq = mLayoutSeq + 1;
        if (seq < 0) seq = 0;
        mLayoutSeq = seq;

        mTmpInitial = initial;


        // First perform layout of any root windows (not attached to another window).
        forAllWindows(mPerformLayout, true /* traverseTopToBottom */);

        // Now perform layout of attached windows, which usually depend on the position of the
        // window they are attached to. XXX does not deal with windows that are attached to windows
        // that are themselves attached.
        forAllWindows(mPerformLayoutAttached, true /* traverseTopToBottom */);

        // Window frames may have changed. Tell the input dispatcher about it.
        mInputMonitor.setUpdateInputWindowsNeededLw();
        if (updateInputWindows) {
            mInputMonitor.updateInputWindowsLw(false /*force*/);
        }
    }

    /**
     * Takes a snapshot of the display.  In landscape mode this grabs the whole screen.
     * In portrait mode, it grabs the full screenshot.
     */
    Bitmap screenshotDisplayLocked() {
        if (!mWmService.mPolicy.isScreenOn()) {
            if (DEBUG_SCREENSHOT) {
                Slog.i(TAG_WM, "Attempted to take screenshot while display was off.");
            }
            return null;
        }

        final ScreenRotationAnimation screenRotationAnimation =
                mWmService.mRoot.getDisplayContent(DEFAULT_DISPLAY).getRotationAnimation();
        final boolean inRotation = screenRotationAnimation != null &&
                screenRotationAnimation.isAnimating();
        if (DEBUG_SCREENSHOT && inRotation) Slog.v(TAG_WM, "Taking screenshot while rotating");

        // Send invalid rect and no width and height since it will screenshot the entire display.
        final IBinder displayToken = SurfaceControl.getInternalDisplayToken();
        final SurfaceControl.DisplayCaptureArgs captureArgs =
                new SurfaceControl.DisplayCaptureArgs.Builder(displayToken)
                        .setUseIdentityTransform(inRotation)
                        .build();
        final SurfaceControl.ScreenshotHardwareBuffer screenshotBuffer =
                SurfaceControl.captureDisplay(captureArgs);
        final Bitmap bitmap = screenshotBuffer == null ? null : screenshotBuffer.asBitmap();
        if (bitmap == null) {
            Slog.w(TAG_WM, "Failed to take screenshot");
            return null;
        }

        // Create a copy of the screenshot that is immutable and backed in ashmem.
        // This greatly reduces the overhead of passing the bitmap between processes.
        final Bitmap ret = bitmap.asShared();
        if (ret != bitmap) {
            bitmap.recycle();
        }
        return ret;
    }

    @Override
    void onDescendantOverrideConfigurationChanged() {
        setLayoutNeeded();
        mWmService.requestTraversal();
    }

    @Override
    boolean okToDisplay() {
        return okToDisplay(false /* ignoreFrozen */, false /* ignoreScreenOn */);
    }

    boolean okToDisplay(boolean ignoreFrozen, boolean ignoreScreenOn) {
        if (mDisplayId == DEFAULT_DISPLAY) {
            return (!mWmService.mDisplayFrozen || ignoreFrozen)
                    && mWmService.mDisplayEnabled
                    && (ignoreScreenOn || mWmService.mPolicy.isScreenOn());
        }
        return mDisplayInfo.state == Display.STATE_ON;
    }

    @Override
    boolean okToAnimate(boolean ignoreFrozen, boolean ignoreScreenOn) {
        return okToDisplay(ignoreFrozen, ignoreScreenOn)
                && (mDisplayId != DEFAULT_DISPLAY
                || mWmService.mPolicy.okToAnimate(ignoreScreenOn))
                && getDisplayPolicy().isScreenOnFully();
    }

    static final class TaskForResizePointSearchResult implements Predicate<Task> {
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
            root.forAllTasks(this);

            return taskForResize;
        }

        @Override
        public boolean test(Task task) {
            if (!task.getRootTask().getWindowConfiguration().canResizeTask()) {
                return true;
            }

            if (task.getWindowingMode() == WINDOWING_MODE_FULLSCREEN) {
                return true;
            }

            if (task.isOrganized()) {
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
        public boolean displayHasContent;
        public boolean obscured;
        public boolean syswin;
        public boolean preferMinimalPostProcessing;
        public float preferredRefreshRate;
        public int preferredModeId;
        public float preferredMinRefreshRate;
        public float preferredMaxRefreshRate;

        void reset() {
            displayHasContent = false;
            obscured = false;
            syswin = false;
            preferMinimalPostProcessing = false;
            preferredRefreshRate = 0;
            preferredModeId = 0;
            preferredMinRefreshRate = 0;
            preferredMaxRefreshRate = 0;
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
            super(wms, Type.ABOVE_TASKS, "ImeContainer", FEATURE_IME);
        }

        public void setNeedsLayer() {
            mNeedsLayer = true;
        }

        @Override
        int getOrientation(int candidate) {
            if (getIgnoreOrientationRequest()) {
                return SCREEN_ORIENTATION_UNSET;
            }

            // IME does not participate in orientation.
            return candidate;
        }

        @Override
        void updateAboveInsetsState(InsetsState aboveInsetsState,
                SparseArray<InsetsSourceProvider> localInsetsSourceProvidersFromParent,
                ArraySet<WindowState> insetsChangedWindows) {
            if (skipImeWindowsDuringTraversal(mDisplayContent)) {
                return;
            }
            super.updateAboveInsetsState(aboveInsetsState, localInsetsSourceProvidersFromParent,
                    insetsChangedWindows);
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

        private static boolean skipImeWindowsDuringTraversal(DisplayContent dc) {
            // We skip IME windows so they're processed just above their target.
            // Note that this method check should align with {@link
            // WindowState#applyImeWindowsIfNeeded} in case of any state mismatch.
            return dc.mImeLayeringTarget != null
                    // Make sure that the IME window won't be skipped to report that it has
                    // completed the orientation change.
                    && !dc.mWmService.mDisplayFrozen;
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
        void assignRelativeLayer(Transaction t, SurfaceControl relativeTo, int layer,
                boolean forceUpdate) {
            if (!mNeedsLayer) {
                return;
            }
            super.assignRelativeLayer(t, relativeTo, layer, forceUpdate);
            mNeedsLayer = false;
        }

        @Override
        void setOrganizer(IDisplayAreaOrganizer organizer, boolean skipDisplayAreaAppeared) {
            super.setOrganizer(organizer, skipDisplayAreaAppeared);
            mDisplayContent.updateImeParent();

            // If the ImeContainer was previously unorganized then the framework might have
            // reparented its surface control under an activity so we need to reparent it back
            // under its parent.
            if (organizer != null) {
                final SurfaceControl imeParentSurfaceControl = getParentSurfaceControl();
                if (mSurfaceControl != null && imeParentSurfaceControl != null) {
                    ProtoLog.i(WM_DEBUG_IME, "ImeContainer just became organized. Reparenting "
                            + "under parent. imeParentSurfaceControl=%s", imeParentSurfaceControl);
                    getPendingTransaction().reparent(mSurfaceControl, imeParentSurfaceControl);
                } else {
                    ProtoLog.e(WM_DEBUG_IME, "ImeContainer just became organized but it doesn't "
                            + "have a parent or the parent doesn't have a surface control."
                            + " mSurfaceControl=%s imeParentSurfaceControl=%s",
                            mSurfaceControl, imeParentSurfaceControl);
                }
            }
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
        return mWmService.makeSurfaceBuilder(mSession).setParent(getOverlayLayer());
    }

    @Override
    public SurfaceControl.Builder makeAnimationLeash() {
        return mWmService.makeSurfaceBuilder(mSession).setParent(mSurfaceControl)
                .setContainerLayer();
    }

    /**
     * Reparents the given surface to {@link #mOverlayLayer} SurfaceControl.
     */
    void reparentToOverlay(Transaction transaction, SurfaceControl surface) {
        transaction.reparent(surface, getOverlayLayer());
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
            // Attach the SystemUiContext to this DisplayContent the get latest configuration.
            // Note that the SystemUiContext will be removed automatically if this DisplayContent
            // is detached.
            mWmService.mWindowContextListenerController.registerWindowContainerListener(
                    getDisplayUiContext().getWindowContextToken(), this, SYSTEM_UID,
                    INVALID_WINDOW_TYPE, null /* options */);
        }
    }

    @Override
    void assignChildLayers(SurfaceControl.Transaction t) {
        assignRelativeLayerForIme(t, false /* forceUpdate */);
        super.assignChildLayers(t);
    }

    private void assignRelativeLayerForIme(SurfaceControl.Transaction t, boolean forceUpdate) {
        if (mImeWindowsContainer.isOrganized()) {
            if (DEBUG_INPUT_METHOD) {
                Slog.i(TAG_WM, "ImeContainer is organized. Skip assignRelativeLayerForIme.");
            }
            // Leave the ImeContainer where the DisplayAreaPolicy placed it.
            // When using FEATURE_IME, Organizer assumes the responsibility for placing the surface.
            return;
        }

        mImeWindowsContainer.setNeedsLayer();
        final WindowState imeTarget = mImeLayeringTarget;
        // In the case where we have an IME target that is not in split-screen mode IME
        // assignment is easy. We just need the IME to go directly above the target. This way
        // children of the target will naturally go above the IME and everyone is happy.
        //
        // In the case of split-screen windowing mode, we need to elevate the IME above the
        // docked divider while keeping the app itself below the docked divider, so instead
        // we will put the docked divider below the IME. @see #assignRelativeLayerForImeTargetChild
        //
        // In the case where we have no IME target we let its window parent to place it.
        //
        // Keep IME window in surface parent as long as app's starting window
        // exists so it get's layered above the starting window.
        if (imeTarget != null && !(imeTarget.mActivityRecord != null
                && imeTarget.mActivityRecord.hasStartingWindow())) {
            final WindowToken imeControlTargetToken =
                    mImeControlTarget != null && mImeControlTarget.getWindow() != null
                            ? mImeControlTarget.getWindow().mToken : null;
            final boolean canImeTargetSetRelativeLayer = imeTarget.getSurfaceControl() != null
                    && imeTarget.mToken == imeControlTargetToken
                    && !imeTarget.inMultiWindowMode();
            if (canImeTargetSetRelativeLayer) {
                mImeWindowsContainer.assignRelativeLayer(t, imeTarget.getSurfaceControl(),
                        // TODO: We need to use an extra level on the app surface to ensure
                        // this is always above SurfaceView but always below attached window.
                        1, forceUpdate);
                return;
            }
        }
        if (mInputMethodSurfaceParent != null) {
            // The IME surface parent may not be its window parent's surface
            // (@see #computeImeParent), so set relative layer here instead of letting the window
            // parent to assign layer.
            mImeWindowsContainer.assignRelativeLayer(t, mInputMethodSurfaceParent, 1, forceUpdate);
        }
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
        child.assignRelativeLayer(t, mImeWindowsContainer.getSurfaceControl(), 1);
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

    /**
     * Increment the deferral count to determine whether to update the IME target.
     */
    void deferUpdateImeTarget() {
        if (mDeferUpdateImeTargetCount == 0) {
            mUpdateImeRequestedWhileDeferred = false;
        }
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
        if (mDeferUpdateImeTargetCount == 0 && mUpdateImeRequestedWhileDeferred) {
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

    @VisibleForTesting
    void setLastHasContent() {
        mLastHasContent = true;
    }

    void registerPointerEventListener(@NonNull PointerEventListener listener) {
        mPointerEventDispatcher.registerInputEventListener(listener);
    }

    void unregisterPointerEventListener(@NonNull PointerEventListener listener) {
        mPointerEventDispatcher.unregisterInputEventListener(listener);
    }

    /**
     * Transfer app transition from other display to this display.
     *
     * @param from Display from where the app transition is transferred.
     *
     * TODO(new-app-transition): Remove this once the shell handles app transition.
     */
    void transferAppTransitionFrom(DisplayContent from) {
        final boolean prepared = mAppTransition.transferFrom(from.mAppTransition);
        if (prepared && okToAnimate()) {
            mSkipAppTransitionAnimation = false;
        }
    }

    /**
     * @deprecated new transition should use {@link #requestTransitionAndLegacyPrepare(int, int)}
     */
    @Deprecated
    void prepareAppTransition(@WindowManager.TransitionType int transit) {
        prepareAppTransition(transit, 0 /* flags */);
    }

    /**
     * @deprecated new transition should use {@link #requestTransitionAndLegacyPrepare(int, int)}
     */
    @Deprecated
    void prepareAppTransition(@WindowManager.TransitionType int transit,
            @WindowManager.TransitionFlags int flags) {
        final boolean prepared = mAppTransition.prepareAppTransition(transit, flags);
        if (prepared && okToAnimate() && transit != TRANSIT_NONE) {
            mSkipAppTransitionAnimation = false;
        }
    }

    /**
     * Helper that both requests a transition (using the new transition system) and prepares
     * the legacy transition system. Use this when both systems have the same start-point.
     *
     * @see TransitionController#requestTransitionIfNeeded(int, int, WindowContainer,
     *      WindowContainer)
     * @see AppTransition#prepareAppTransition
     */
    void requestTransitionAndLegacyPrepare(@WindowManager.TransitionType int transit,
            @WindowManager.TransitionFlags int flags) {
        prepareAppTransition(transit, flags);
        mTransitionController.requestTransitionIfNeeded(transit, flags,
                null /* trigger */, this);
    }

    /** @see #requestTransitionAndLegacyPrepare(int, int) */
    void requestTransitionAndLegacyPrepare(@WindowManager.TransitionType int transit,
            @Nullable WindowContainer trigger) {
        prepareAppTransition(transit);
        mTransitionController.requestTransitionIfNeeded(transit, 0 /* flags */,
                trigger, this);
    }

    void executeAppTransition() {
        mTransitionController.setReady(this);
        if (mAppTransition.isTransitionSet()) {
            ProtoLog.w(WM_DEBUG_APP_TRANSITIONS,
                    "Execute app transition: %s, displayId: %d Callers=%s",
                    mAppTransition, mDisplayId, Debug.getCallers(5));
            mAppTransition.setReady();
            mWmService.mWindowPlacerLocked.requestTraversal();
        }
    }

    void cancelAppTransition() {
        if (!mAppTransition.isTransitionSet() || mAppTransition.isRunning()) return;
        mAppTransition.abort();
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

        mWallpaperController.hideDeferredWallpapersIfNeededLegacy();

        onAppTransitionDone();

        changes |= FINISH_LAYOUT_REDO_LAYOUT;
        ProtoLog.v(WM_DEBUG_WALLPAPER, "Wallpaper layer changed: assigning layers + relayout");
        computeImeTarget(true /* updateImeTarget */);
        mWallpaperMayChange = true;
        // Since the window list has been rebuilt, focus might have to be recomputed since the
        // actual order of windows might have changed again.
        mWmService.mFocusMayChange = true;

        pendingLayoutChanges |= changes;
    }

    /** Check if pending app transition is for activity / task launch. */
    boolean isNextTransitionForward() {
        // TODO(b/191375840): decouple "forwardness" from transition system.
        if (mTransitionController.isShellTransitionsEnabled()) {
            @WindowManager.TransitionType int type =
                    mTransitionController.getCollectingTransitionType();
            return type == TRANSIT_OPEN || type == TRANSIT_TO_FRONT;
        }
        return mAppTransition.containsTransitRequest(TRANSIT_OPEN)
                || mAppTransition.containsTransitRequest(TRANSIT_TO_FRONT);
    }

    /**
     * @see Display#FLAG_SHOULD_SHOW_SYSTEM_DECORATIONS
     */
    boolean supportsSystemDecorations() {
        return (mWmService.mDisplayWindowSettings.shouldShowSystemDecorsLocked(this)
                || (mDisplay.getFlags() & FLAG_SHOULD_SHOW_SYSTEM_DECORATIONS) != 0
                || forceDesktopMode())
                // VR virtual display will be used to run and render 2D app within a VR experience.
                && mDisplayId != mWmService.mVr2dDisplayId
                // Do not show system decorations on untrusted virtual display.
                && isTrusted();
    }

    SurfaceControl getWindowingLayer() {
        return mWindowingLayer;
    }

    DisplayArea.Tokens getImeContainer() {
        return mImeWindowsContainer;
    }

    SurfaceControl getOverlayLayer() {
        return mOverlayLayer;
    }

    SurfaceControl[] findRoundedCornerOverlays() {
        List<SurfaceControl> roundedCornerOverlays = new ArrayList<>();
        for (WindowToken token : mTokenMap.values()) {
            if (token.mRoundedCornerOverlay && token.isVisible()) {
                roundedCornerOverlays.add(token.mSurfaceControl);
            }
        }
        return roundedCornerOverlays.toArray(new SurfaceControl[0]);
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
        final RecentsAnimationController recentsAnimationController =
                mWmService.getRecentsAnimationController();

        // Traverse all windows top down to assemble the gesture exclusion rects.
        // For each window, we only take the rects that fall within its touchable region.
        forAllWindows(w -> {
            final boolean ignoreRecentsAnimationTarget = recentsAnimationController != null
                    && recentsAnimationController.shouldApplyInputConsumer(w.getActivityRecord());
            if (!w.canReceiveTouchInput() || !w.isVisible()
                    || (w.getAttrs().flags & FLAG_NOT_TOUCHABLE) != 0
                    || unhandled.isEmpty()
                    || ignoreRecentsAnimationTarget) {
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
            if (needsGestureExclusionRestrictions(w, false /* ignoreRequest */)) {

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
                boolean loggable = needsGestureExclusionRestrictions(w, true /* ignoreRequest */);
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
     * Returns whether gesture exclusion area should be restricted from the window depending on the
     * window/activity types and the requested navigation bar visibility and the behavior.
     *
     * @param win The target window.
     * @param ignoreRequest If this is {@code true}, only the window/activity types are considered.
     * @return {@code true} if the gesture exclusion restrictions are needed.
     */
    private static boolean needsGestureExclusionRestrictions(WindowState win,
            boolean ignoreRequest) {
        final int type = win.mAttrs.type;
        final boolean stickyHideNav =
                !win.getRequestedVisibility(ITYPE_NAVIGATION_BAR)
                        && win.mAttrs.insetsFlags.behavior == BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE;
        return (!stickyHideNav || ignoreRequest) && type != TYPE_INPUT_METHOD
                && type != TYPE_NOTIFICATION_SHADE && win.getActivityType() != ACTIVITY_TYPE_HOME;
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
                && needsGestureExclusionRestrictions(win, true /* ignoreRequest */)
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

    void updateKeepClearAreas() {
        final Set<Rect> restrictedKeepClearAreas = new ArraySet<>();
        final Set<Rect> unrestrictedKeepClearAreas = new ArraySet<>();
        getKeepClearAreas(restrictedKeepClearAreas, unrestrictedKeepClearAreas);

        if (!mRestrictedKeepClearAreas.equals(restrictedKeepClearAreas)
                || !mUnrestrictedKeepClearAreas.equals(unrestrictedKeepClearAreas)) {
            mRestrictedKeepClearAreas = restrictedKeepClearAreas;
            mUnrestrictedKeepClearAreas = unrestrictedKeepClearAreas;
            mWmService.mDisplayNotificationController.dispatchKeepClearAreasChanged(
                    this, restrictedKeepClearAreas, unrestrictedKeepClearAreas);
        }
    }

    /**
     * Fills {@param outRestricted} with all keep-clear areas from visible, relevant windows
     * on this display, which set restricted keep-clear areas.
     * Fills {@param outUnrestricted} with keep-clear areas from visible, relevant windows on this
     * display, which set unrestricted keep-clear areas.
     *
     * For context on restricted vs unrestricted keep-clear areas, see
     * {@link android.Manifest.permission.SET_UNRESTRICTED_KEEP_CLEAR_AREAS}.
     */
    void getKeepClearAreas(Set<Rect> outRestricted, Set<Rect> outUnrestricted) {
        final Matrix tmpMatrix = new Matrix();
        final float[] tmpFloat9 = new float[9];
        forAllWindows(w -> {
            if (w.isVisible() && !w.inPinnedWindowingMode()) {
                w.getKeepClearAreas(outRestricted, outUnrestricted, tmpMatrix, tmpFloat9);
            }

            // We stop traversing when we reach the base of a fullscreen app.
            return w.getWindowType() == TYPE_BASE_APPLICATION
                    && w.getWindowingMode() == WINDOWING_MODE_FULLSCREEN;
        }, true);
    }

    /**
     * Returns all keep-clear areas from visible, relevant windows on this display.
     */
    Set<Rect> getKeepClearAreas() {
        final Set<Rect> keepClearAreas = new ArraySet<>();
        getKeepClearAreas(keepClearAreas, keepClearAreas);
        return keepClearAreas;
    }

    protected MetricsLogger getMetricsLogger() {
        if (mMetricsLogger == null) {
            mMetricsLogger = new MetricsLogger();
        }
        return mMetricsLogger;
    }

    void onDisplayChanged() {
        final int lastDisplayState = mDisplayInfo.state;
        updateDisplayInfo();

        // The window policy is responsible for stopping activities on the default display.
        final int displayId = mDisplay.getDisplayId();
        final int displayState = mDisplayInfo.state;
        if (displayId != DEFAULT_DISPLAY) {
            if (displayState == Display.STATE_OFF) {
                mOffTokenAcquirer.acquire(mDisplayId);
            } else if (displayState == Display.STATE_ON) {
                mOffTokenAcquirer.release(mDisplayId);
            }
            ProtoLog.v(WM_DEBUG_CONTENT_RECORDING,
                    "Display %d state is now (%d), so update recording?",
                    mDisplayId, displayState);
            if (lastDisplayState != displayState) {
                // If state is on due to surface being added, then start recording.
                // If state is off due to surface being removed, then stop recording.
                updateRecording();
            }
        }
        // Dispatch pending Configuration to WindowContext if the associated display changes to
        // un-suspended state from suspended.
        if (isSuspendedState(lastDisplayState)
                && !isSuspendedState(displayState) && displayState != STATE_UNKNOWN) {
            mWmService.mWindowContextListenerController
                    .dispatchPendingConfigurationIfNeeded(mDisplayId);
        }
        mWmService.requestTraversal();
    }

    static boolean alwaysCreateRootTask(int windowingMode, int activityType) {
        // Always create a root task for fullscreen, freeform, and multi windowing
        // modes so that we can manage visual ordering and return types correctly.
        return activityType == ACTIVITY_TYPE_STANDARD
                && (windowingMode == WINDOWING_MODE_FULLSCREEN
                || windowingMode == WINDOWING_MODE_FREEFORM
                || windowingMode == WINDOWING_MODE_PINNED
                || windowingMode == WINDOWING_MODE_MULTI_WINDOW);
    }

    @Nullable
    Task getFocusedRootTask() {
        return getItemFromTaskDisplayAreas(TaskDisplayArea::getFocusedRootTask);
    }

    /**
     * Removes root tasks in the input windowing modes from the system if they are of activity type
     * ACTIVITY_TYPE_STANDARD or ACTIVITY_TYPE_UNDEFINED
     */
    void removeRootTasksInWindowingModes(int... windowingModes) {
        if (windowingModes == null || windowingModes.length == 0) {
            return;
        }

        // Collect the root tasks that are necessary to be removed instead of performing the removal
        // by looping the children, so that we don't miss any root tasks after the children size
        // changed or reordered.
        final ArrayList<Task> rootTasks = new ArrayList<>();
        forAllRootTasks(rootTask -> {
            for (int windowingMode : windowingModes) {
                if (rootTask.mCreatedByOrganizer
                        || rootTask.getWindowingMode() != windowingMode
                        || !rootTask.isActivityTypeStandardOrUndefined()) {
                    continue;
                }
                rootTasks.add(rootTask);
            }
        });
        for (int i = rootTasks.size() - 1; i >= 0; --i) {
            mRootWindowContainer.mTaskSupervisor.removeRootTask(rootTasks.get(i));
        }
    }

    void removeRootTasksWithActivityTypes(int... activityTypes) {
        if (activityTypes == null || activityTypes.length == 0) {
            return;
        }

        // Collect the root tasks that are necessary to be removed instead of performing the removal
        // by looping the children, so that we don't miss any root tasks after the children size
        // changed or reordered.
        final ArrayList<Task> rootTasks = new ArrayList<>();
        forAllRootTasks(rootTask -> {
            for (int activityType : activityTypes) {
                // Collect the root tasks that are currently being organized.
                if (rootTask.mCreatedByOrganizer) {
                    for (int k = rootTask.getChildCount() - 1; k >= 0; --k) {
                        final Task task = (Task) rootTask.getChildAt(k);
                        if (task.getActivityType() == activityType) {
                            rootTasks.add(task);
                        }
                    }
                } else if (rootTask.getActivityType() == activityType) {
                    rootTasks.add(rootTask);
                }
            }
        });
        for (int i = rootTasks.size() - 1; i >= 0; --i) {
            mRootWindowContainer.mTaskSupervisor.removeRootTask(rootTasks.get(i));
        }
    }

    ActivityRecord topRunningActivity() {
        return topRunningActivity(false /* considerKeyguardState */);
    }

    /**
     * Returns the top running activity in the focused root task. In the case the focused root
     * task has no such activity, the next focusable root task on this display is returned.
     *
     * @param considerKeyguardState Indicates whether the locked state should be considered. if
     *                              {@code true} and the keyguard is locked, only activities that
     *                              can be shown on top of the keyguard will be considered.
     * @return The top running activity. {@code null} if none is available.
     */
    @Nullable
    ActivityRecord topRunningActivity(boolean considerKeyguardState) {
        return getItemFromTaskDisplayAreas(taskDisplayArea ->
                taskDisplayArea.topRunningActivity(considerKeyguardState));
    }

    boolean updateDisplayOverrideConfigurationLocked() {
        // Preemptively cancel the running recents animation -- SysUI can't currently handle this
        // case properly since the signals it receives all happen post-change
        final RecentsAnimationController recentsAnimationController =
                mWmService.getRecentsAnimationController();
        if (recentsAnimationController != null) {
            recentsAnimationController.cancelAnimationForDisplayChange();
        }

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
                            UserHandle.USER_NULL /* userId */);
                } else {
                    changes = performDisplayOverrideConfigUpdate(values);
                }
            }

            if (!deferResume) {
                kept = mAtmService.ensureConfigAndVisibilityAfterUpdate(starting, changes);
            }
        } finally {
            mAtmService.continueWindowLayout();
        }

        if (result != null) {
            result.changes = changes;
            result.activityRelaunched = !kept;
        }
        return kept;
    }

    int performDisplayOverrideConfigUpdate(Configuration values) {
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
            if (isReady() && mTransitionController.isShellTransitionsEnabled()) {
                requestChangeTransitionIfNeeded(changes, null /* displayChange */);
            }
        }
        return changes;
    }

    @Override
    public void onRequestedOverrideConfigurationChanged(Configuration overrideConfiguration) {
        final Configuration currOverrideConfig = getRequestedOverrideConfiguration();
        final int currRotation = currOverrideConfig.windowConfiguration.getRotation();
        final int overrideRotation = overrideConfiguration.windowConfiguration.getRotation();
        if (currRotation != ROTATION_UNDEFINED && overrideRotation != ROTATION_UNDEFINED
                && currRotation != overrideRotation) {
            applyRotationAndFinishFixedRotation(currRotation, overrideRotation);
        }
        mCurrentOverrideConfigurationChanges = currOverrideConfig.diff(overrideConfiguration);
        super.onRequestedOverrideConfigurationChanged(overrideConfiguration);
        mCurrentOverrideConfigurationChanges = 0;
        if (mWaitingForConfig) {
            mWaitingForConfig = false;
            mWmService.mLastFinishedFreezeSource = "new-config";
        }
        mAtmService.addWindowLayoutReasons(
                ActivityTaskManagerService.LAYOUT_REASON_CONFIG_CHANGED);
    }

    @Override
    void onResize() {
        super.onResize();
        if (mWmService.mAccessibilityController.hasCallbacks()) {
            mWmService.mAccessibilityController.onDisplaySizeChanged(this);
        }
    }

    /**
     * If the launching rotated activity ({@link #mFixedRotationLaunchingApp}) is null, it simply
     * applies the rotation to display. Otherwise because the activity has shown as rotated, the
     * fixed rotation transform also needs to be cleared to make sure the rotated activity fits
     * the display naturally.
     */
    private void applyRotationAndFinishFixedRotation(int oldRotation, int newRotation) {
        final WindowToken rotatedLaunchingApp = mFixedRotationLaunchingApp;
        if (rotatedLaunchingApp == null) {
            applyRotation(oldRotation, newRotation);
            return;
        }

        rotatedLaunchingApp.finishFixedRotationTransform(
                () -> applyRotation(oldRotation, newRotation));
        setFixedRotationLaunchingAppUnchecked(null);
    }

    /** Checks whether the given activity is in size compatibility mode and notifies the change. */
    void handleActivitySizeCompatModeIfNeeded(ActivityRecord r) {
        final Task organizedTask = r.getOrganizedTask();
        if (organizedTask == null) {
            mActiveSizeCompatActivities.remove(r);
            return;
        }

        if (r.isState(RESUMED) && r.inSizeCompatMode()) {
            if (mActiveSizeCompatActivities.add(r)) {
                // Trigger task event for new size compat activity.
                organizedTask.onSizeCompatActivityChanged();
            }
            return;
        }

        if (mActiveSizeCompatActivities.remove(r)) {
            // Trigger task event for activity no longer in foreground size compat.
            organizedTask.onSizeCompatActivityChanged();
        }
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
        Task lastReparentedRootTask;

        mRootWindowContainer.mTaskSupervisor.beginDeferResume();
        try {
            lastReparentedRootTask = reduceOnAllTaskDisplayAreas((taskDisplayArea, rootTask) -> {
                final Task lastReparentedRootTaskFromArea = taskDisplayArea.remove();
                if (lastReparentedRootTaskFromArea != null) {
                    return lastReparentedRootTaskFromArea;
                }
                return rootTask;
            }, null /* initValue */, false /* traverseTopToBottom */);
        } finally {
            mRootWindowContainer.mTaskSupervisor.endDeferResume();
        }
        mRemoved = true;

        if (mContentRecorder != null) {
            mContentRecorder.remove();
        }

        // Only update focus/visibility for the last one because there may be many root tasks are
        // reparented and the intermediate states are unnecessary.
        if (lastReparentedRootTask != null) {
            lastReparentedRootTask.resumeNextFocusAfterReparent();
        }
        releaseSelfIfNeeded();
        mDisplayPolicy.release();

        if (!mAllSleepTokens.isEmpty()) {
            mAllSleepTokens.forEach(token ->
                    mRootWindowContainer.mSleepTokens.remove(token.mHashKey));
            mAllSleepTokens.clear();
            mAtmService.updateSleepIfNeededLocked();
        }
    }

    void releaseSelfIfNeeded() {
        if (!mRemoved) {
            return;
        }

        // Check if all task display areas have only the empty home root tasks left.
        boolean hasNonEmptyHomeRootTask = forAllRootTasks(rootTask ->
                !rootTask.isActivityTypeHome() || rootTask.hasChild());
        if (!hasNonEmptyHomeRootTask && getRootTaskCount() > 0) {
            // Release this display if only empty home root task(s) are left. This display will be
            // released along with the root task(s) removal.
            forAllRootTasks(t -> {t.removeIfPossible("releaseSelfIfNeeded");});
        } else if (getTopRootTask() == null) {
            removeIfPossible();
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
        return (getRootTaskCount() == 0 || !mAllSleepTokens.isEmpty())
                && (mAtmService.mRunningVoice == null);
    }


    void ensureActivitiesVisible(ActivityRecord starting, int configChanges,
            boolean preserveWindows, boolean notifyClients) {
        if (mInEnsureActivitiesVisible) {
            // Don't do recursive work.
            return;
        }
        mInEnsureActivitiesVisible = true;
        mAtmService.mTaskSupervisor.beginActivityVisibilityUpdate();
        try {
            forAllRootTasks(rootTask -> {
                rootTask.ensureActivitiesVisible(starting, configChanges, preserveWindows,
                        notifyClients);
            });
            if (mTransitionController.useShellTransitionsRotation()
                    && mTransitionController.isCollecting()
                    && mWallpaperController.getWallpaperTarget() != null) {
                // Also update wallpapers so that their requestedVisibility immediately reflects
                // the changes to activity visibility.
                // TODO(b/206005136): Move visibleRequested logic up to WindowToken.
                mWallpaperController.adjustWallpaperWindows();
            }
        } finally {
            mAtmService.mTaskSupervisor.endActivityVisibilityUpdate();
            mInEnsureActivitiesVisible = false;
        }
    }

    boolean isSleeping() {
        return mSleeping;
    }

    void setIsSleeping(boolean asleep) {
        mSleeping = asleep;
    }

    /**
     * Notifies that some Keyguard flags have changed and the visibilities of the activities may
     * need to be reevaluated.
     */
    void notifyKeyguardFlagsChanged() {
        if (!isKeyguardLocked()) {
            // If keyguard is not locked, the change of flags won't affect activity visibility.
            return;
        }
        // We might change the visibilities here, so prepare an empty app transition which might be
        // overridden later if we actually change visibilities.
        final boolean wasTransitionSet = mAppTransition.isTransitionSet();
        if (!wasTransitionSet) {
            prepareAppTransition(TRANSIT_NONE);
        }
        mRootWindowContainer.ensureActivitiesVisible(null, 0, false /* preserveWindows */);

        // If there was a transition set already we don't want to interfere with it as we might be
        // starting it too early.
        if (!wasTransitionSet) {
            executeAppTransition();
        }
    }

    /**
     * Check if the display has {@link Display#FLAG_CAN_SHOW_WITH_INSECURE_KEYGUARD} applied.
     */
    boolean canShowWithInsecureKeyguard() {
        final int flags = mDisplay.getFlags();
        return (flags & FLAG_CAN_SHOW_WITH_INSECURE_KEYGUARD) != 0;
    }

    /**
     * @return whether keyguard is locked for this display
     */
    boolean isKeyguardLocked() {
        return mRootWindowContainer.mTaskSupervisor
                .getKeyguardController().isKeyguardLocked(mDisplayId);
    }

    /**
     * @return whether keyguard is going away on this display
     */
    boolean isKeyguardGoingAway() {
        return mRootWindowContainer.mTaskSupervisor
                .getKeyguardController().isKeyguardGoingAway(mDisplayId);
    }

    /**
     * @return whether keyguard should always be unlocked for this display
     */
    boolean isKeyguardAlwaysUnlocked() {
        return (mDisplayInfo.flags & Display.FLAG_ALWAYS_UNLOCKED) != 0;
    }

    /**
     * @return whether AOD is showing on this display
     */
    boolean isAodShowing() {
        return mRootWindowContainer.mTaskSupervisor
                .getKeyguardController().isAodShowing(mDisplayId);
    }

    @VisibleForTesting
    void removeAllTasks() {
        forAllTasks((t) -> { t.getRootTask().removeChild(t, "removeAllTasks"); });
    }

    Context getDisplayUiContext() {
        return mDisplayPolicy.getSystemUiContext();
    }

    @Override
    boolean setIgnoreOrientationRequest(boolean ignoreOrientationRequest) {
        if (mSetIgnoreOrientationRequest == ignoreOrientationRequest) return false;
        final boolean rotationChanged = super.setIgnoreOrientationRequest(ignoreOrientationRequest);
        mWmService.mDisplayWindowSettings.setIgnoreOrientationRequest(
                this, mSetIgnoreOrientationRequest);
        return rotationChanged;
    }

    /**
     * Updates orientation if necessary after ignore orientation request override logic in {@link
     * WindowManagerService#isIgnoreOrientationRequestDisabled} changes at runtime.
     */
    void onIsIgnoreOrientationRequestDisabledChanged() {
        if (mFocusedApp != null) {
            // We record the last focused TDA that respects orientation request, check if this
            // change may affect it.
            onLastFocusedTaskDisplayAreaChanged(mFocusedApp.getDisplayArea());
        }
        if (mSetIgnoreOrientationRequest) {
            updateOrientation();
        }
    }

    /**
     * Locates the appropriate target window for scroll capture. The search progresses top to
     * bottom.
     * If {@code searchBehind} is non-null, the search will only consider windows behind this one.
     * If a valid taskId is specified, the target window must belong to the given task.
     *
     * @param searchBehind a window used to filter the search to windows behind it, or null to begin
     *                     the search at the top window of the display
     * @param taskId       specifies the id of a task the result must belong to or
     *                     {@link android.app.ActivityTaskManager#INVALID_TASK_ID INVALID_TASK_ID}
     *                     to match any window
     * @return the located window or null if none could be found matching criteria
     */
    @Nullable
    WindowState findScrollCaptureTargetWindow(@Nullable WindowState searchBehind, int taskId) {
        return getWindow(new Predicate<WindowState>() {
            boolean behindTopWindow = (searchBehind == null); // optional filter
            @Override
            public boolean test(WindowState nextWindow) {
                // Skip through all windows until we pass topWindow (if specified)
                if (!behindTopWindow) {
                    if (nextWindow == searchBehind) {
                        behindTopWindow = true;
                    }
                    return false; /* continue */
                }
                if (taskId == INVALID_TASK_ID) {
                    if (!nextWindow.canReceiveKeys()) {
                        return false; /* continue */
                    }
                } else {
                    Task task = nextWindow.getTask();
                    if (task == null || !task.isTaskId(taskId)) {
                        return false; /* continue */
                    }
                }
                if (nextWindow.isSecureLocked()) {
                    return false; /* continue */
                }
                return true; /* stop, match found */
            }
        });
    }

    @Override
    public boolean providesMaxBounds() {
        return true;
    }

    /**
     * Sets if Display APIs should be sandboxed to the activity window bounds.
     */
    void setSandboxDisplayApis(boolean sandboxDisplayApis) {
        mSandboxDisplayApis = sandboxDisplayApis;
    }

    /**
     * Returns {@code true} is Display APIs should be sandboxed to the activity window bounds,
     * {@code false} otherwise. Default to true, unless set for debugging purposes.
     */
    boolean sandboxDisplayApis() {
        return mSandboxDisplayApis;
    }

    private ContentRecorder getContentRecorder() {
        if (mContentRecorder == null) {
            mContentRecorder = new ContentRecorder(this);
        }
        return mContentRecorder;
    }

    /**
     * Pause the recording session.
     */
    @VisibleForTesting void pauseRecording() {
        if (mContentRecorder != null) {
            mContentRecorder.pauseRecording();
        }
    }

    /**
     * Sets the incoming recording session. Should only be used when starting to record on
     * this display; stopping recording is handled separately when the display is destroyed.
     *
     * @param session the new session indicating recording will begin on this display.
     */
    void setContentRecordingSession(@Nullable ContentRecordingSession session) {
        getContentRecorder().setContentRecordingSession(session);
    }

    /**
     * Start recording if this DisplayContent no longer has content. Stop recording if it now
     * has content or the display is not on.
     */
    @VisibleForTesting void updateRecording() {
        getContentRecorder().updateRecording();
    }

    /**
     * Returns {@code true} if this DisplayContent is currently recording.
     */
    boolean isCurrentlyRecording() {
        return mContentRecorder != null && mContentRecorder.isCurrentlyRecording();
    }

    /** The entry for proceeding to handle {@link #mFixedRotationLaunchingApp}. */
    class FixedRotationTransitionListener extends WindowManagerInternal.AppTransitionListener {

        /**
         * The animating activity which shows the recents task list. It is set between
         * {@link RecentsAnimationController#initialize} and
         * {@link RecentsAnimationController#cleanupAnimation}.
         */
        private ActivityRecord mAnimatingRecents;

        /** Whether {@link #mAnimatingRecents} is going to be the top activity. */
        private boolean mRecentsWillBeTop;

        /**
         * If the recents activity has a fixed orientation which is different from the current top
         * activity, it will be rotated before being shown so we avoid a screen rotation animation
         * when showing the Recents view.
         */
        void onStartRecentsAnimation(@NonNull ActivityRecord r) {
            mAnimatingRecents = r;
            if (r.isVisible() && mFocusedApp != null && !mFocusedApp.occludesParent()) {
                // The recents activity has shown with the orientation determined by the top
                // activity, keep its current orientation to avoid flicking by the configuration
                // change of visible activity.
                return;
            }
            rotateInDifferentOrientationIfNeeded(r);
            if (r.hasFixedRotationTransform()) {
                // Set the record so we can recognize it to continue to update display orientation
                // if the recents activity becomes the top later.
                setFixedRotationLaunchingApp(r, r.getWindowConfiguration().getRotation());
            }
        }

        /**
         * If {@link #mAnimatingRecents} still has fixed rotation, it should be moved to top so we
         * don't clear {@link #mFixedRotationLaunchingApp} that will be handled by transition.
         */
        void onFinishRecentsAnimation() {
            final ActivityRecord animatingRecents = mAnimatingRecents;
            final boolean recentsWillBeTop = mRecentsWillBeTop;
            mAnimatingRecents = null;
            mRecentsWillBeTop = false;
            if (recentsWillBeTop) {
                // The recents activity will be the top, such as staying at recents list or
                // returning to home (if home and recents are the same activity).
                return;
            }

            if (animatingRecents != null && animatingRecents == mFixedRotationLaunchingApp
                    && animatingRecents.isVisible() && animatingRecents != topRunningActivity()) {
                // The recents activity should be going to be invisible (switch to another app or
                // return to original top). Only clear the top launching record without finishing
                // the transform immediately because it won't affect display orientation. And before
                // the visibility is committed, the recents activity may perform relayout which may
                // cause unexpected configuration change if the rotated configuration is restored.
                // The transform will be finished when the transition is done.
                setFixedRotationLaunchingAppUnchecked(null);
            } else {
                // If there is already a launching activity that is not the recents, before its
                // transition is completed, the recents animation may be started. So if the recents
                // activity won't be the top, the display orientation should be updated according
                // to the current top activity.
                continueUpdateOrientationForDiffOrienLaunchingApp();
            }
        }

        void notifyRecentsWillBeTop() {
            mRecentsWillBeTop = true;
        }

        /**
         * Returns {@code true} if the transient launch (e.g. recents animation) requested a fixed
         * orientation, then the rotation change should be deferred.
         */
        boolean shouldDeferRotation() {
            ActivityRecord source = null;
            if (mTransitionController.isShellTransitionsEnabled()) {
                final ActivityRecord r = mFixedRotationLaunchingApp;
                if (r != null && mTransitionController.isTransientLaunch(r)) {
                    source = r;
                }
            } else if (mAnimatingRecents != null && !hasTopFixedRotationLaunchingApp()) {
                source = mAnimatingRecents;
            }
            if (source == null || source.getRequestedConfigurationOrientation(
                    true /* forDisplay */) == ORIENTATION_UNDEFINED) {
                return false;
            }
            // If screen is off or the device is going to sleep, then still allow to update.
            return mWmService.mPolicy.okToAnimate(false /* ignoreScreenOn */);
        }

        @Override
        public void onAppTransitionFinishedLocked(IBinder token) {
            final ActivityRecord r = getActivityRecord(token);
            // Ignore the animating recents so the fixed rotation transform won't be switched twice
            // by finishing the recents animation and moving it to top. That also avoids flickering
            // due to wait for previous activity to be paused if it supports PiP that ignores the
            // effect of resume-while-pausing.
            if (r == null || r == mAnimatingRecents) {
                return;
            }
            if (mAnimatingRecents != null && mRecentsWillBeTop) {
                // The activity is not the recents and it should be moved to back later, so it is
                // better to keep its current appearance for the next transition. Otherwise the
                // display orientation may be updated too early and the layout procedures at the
                // end of finishing recents animation is skipped. That causes flickering because
                // the surface of closing app hasn't updated to invisible.
                return;
            }
            if (mFixedRotationLaunchingApp == null) {
                // In most cases this is a no-op if the activity doesn't have fixed rotation.
                // Otherwise it could be from finishing recents animation while the display has
                // different orientation.
                r.finishFixedRotationTransform();
                return;
            }
            if (mFixedRotationLaunchingApp.hasFixedRotationTransform(r)) {
                if (mFixedRotationLaunchingApp.hasAnimatingFixedRotationTransition()) {
                    // Waiting until all of the associated activities have done animation, or the
                    // orientation would be updated too early and cause flickering.
                    return;
                }
            } else {
                // Handle a corner case that the task of {@link #mFixedRotationLaunchingApp} is no
                // longer animating but the corresponding transition finished event won't notify.
                // E.g. activity A transferred starting window to B, only A will receive transition
                // finished event. A doesn't have fixed rotation but B is the rotated launching app.
                final Task task = r.getTask();
                if (task == null || task != mFixedRotationLaunchingApp.getTask()) {
                    // Different tasks won't be in one activity transition animation.
                    return;
                }
                if (task.getActivity(ActivityRecord::isInTransition) != null) {
                    return;
                    // Continue to update orientation because the transition of the top rotated
                    // launching activity is done.
                }
            }
            continueUpdateOrientationForDiffOrienLaunchingApp();
        }

        @Override
        public void onAppTransitionCancelledLocked(boolean keyguardGoingAway) {
            // It is only needed when freezing display in legacy transition.
            if (mTransitionController.isShellTransitionsEnabled()) return;
            continueUpdateOrientationForDiffOrienLaunchingApp();
        }

        @Override
        public void onAppTransitionTimeoutLocked() {
            continueUpdateOrientationForDiffOrienLaunchingApp();
        }
    }

    class RemoteInsetsControlTarget implements InsetsControlTarget {
        private final IDisplayWindowInsetsController mRemoteInsetsController;
        private final InsetsVisibilities mRequestedVisibilities = new InsetsVisibilities();

        RemoteInsetsControlTarget(IDisplayWindowInsetsController controller) {
            mRemoteInsetsController = controller;
        }

        /**
         * Notifies the remote insets controller that the top focused window has changed.
         *
         * @param packageName The name of the package that is open in the top focused window.
         * @param requestedVisibilities The insets visibilities requested by the focussed window.
         */
        void topFocusedWindowChanged(String packageName, InsetsVisibilities requestedVisibilities) {
            try {
                mRemoteInsetsController.topFocusedWindowChanged(packageName, requestedVisibilities);
            } catch (RemoteException e) {
                Slog.w(TAG, "Failed to deliver package in top focused window change", e);
            }
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

        @Override
        public boolean getRequestedVisibility(@InternalInsetsType int type) {
            if (type == ITYPE_IME) {
                return getInsetsStateController().getImeSourceProvider().isImeShowing();
            }
            return mRequestedVisibilities.getVisibility(type);
        }

        void setRequestedVisibilities(InsetsVisibilities requestedVisibilities) {
            mRequestedVisibilities.set(requestedVisibilities);
        }
    }

    MagnificationSpec getMagnificationSpec() {
        return mMagnificationSpec;
    }

    DisplayArea findAreaForWindowType(int windowType, Bundle options,
            boolean ownerCanManageAppToken, boolean roundedCornerOverlay) {
        if (windowType >= FIRST_APPLICATION_WINDOW && windowType <= LAST_APPLICATION_WINDOW) {
            return mDisplayAreaPolicy.getTaskDisplayArea(options);
        }
        // Return IME container here because it could be in one of sub RootDisplayAreas depending on
        // the focused edit text. Also, the RootDisplayArea choosing strategy is implemented by
        // the server side, but not mSelectRootForWindowFunc customized by OEM.
        if (windowType == TYPE_INPUT_METHOD || windowType == TYPE_INPUT_METHOD_DIALOG) {
            return getImeContainer();
        }
        return mDisplayAreaPolicy.findAreaForWindowType(windowType, options,
                ownerCanManageAppToken, roundedCornerOverlay);
    }

    /**
     * Finds the {@link DisplayArea} for the {@link WindowToken} to attach to.
     * <p>
     * Note that the differences between this API and
     * {@link RootDisplayArea#findAreaForTokenInLayer(WindowToken)} is that this API finds a
     * {@link DisplayArea} in {@link DisplayContent} level, which may find a {@link DisplayArea}
     * from multiple {@link RootDisplayArea RootDisplayAreas} under this {@link DisplayContent}'s
     * hierarchy, while {@link RootDisplayArea#findAreaForTokenInLayer(WindowToken)} finds a
     * {@link DisplayArea.Tokens} from a {@link DisplayArea.Tokens} list mapped to window layers.
     * </p>
     *
     * @see DisplayContent#findAreaForTokenInLayer(WindowToken)
     */
    DisplayArea findAreaForToken(WindowToken windowToken) {
        return findAreaForWindowType(windowToken.getWindowType(), windowToken.mOptions,
                windowToken.mOwnerCanManageAppTokens, windowToken.mRoundedCornerOverlay);
    }

    @Override
    DisplayContent asDisplayContent() {
        return this;
    }
}
