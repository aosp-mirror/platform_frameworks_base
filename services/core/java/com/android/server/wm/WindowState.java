/*
 * Copyright (C) 2011 The Android Open Source Project
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
import static android.app.AppOpsManager.MODE_ALLOWED;
import static android.app.AppOpsManager.MODE_DEFAULT;
import static android.app.AppOpsManager.OP_NONE;
import static android.app.WindowConfiguration.ACTIVITY_TYPE_DREAM;
import static android.app.WindowConfiguration.ACTIVITY_TYPE_HOME;
import static android.app.WindowConfiguration.isSplitScreenWindowingMode;
import static android.content.res.Configuration.ORIENTATION_LANDSCAPE;
import static android.graphics.GraphicsProtos.dumpPointProto;
import static android.hardware.input.InputManager.BLOCK_UNTRUSTED_TOUCHES;
import static android.os.InputConstants.DEFAULT_DISPATCHING_TIMEOUT_MILLIS;
import static android.os.PowerManager.DRAW_WAKE_LOCK;
import static android.os.Trace.TRACE_TAG_WINDOW_MANAGER;
import static android.view.InsetsState.ITYPE_IME;
import static android.view.InsetsState.ITYPE_NAVIGATION_BAR;
import static android.view.SurfaceControl.Transaction;
import static android.view.SurfaceControl.getGlobalTransaction;
import static android.view.ViewTreeObserver.InternalInsetsInfo.TOUCHABLE_INSETS_CONTENT;
import static android.view.ViewTreeObserver.InternalInsetsInfo.TOUCHABLE_INSETS_FRAME;
import static android.view.ViewTreeObserver.InternalInsetsInfo.TOUCHABLE_INSETS_REGION;
import static android.view.ViewTreeObserver.InternalInsetsInfo.TOUCHABLE_INSETS_VISIBLE;
import static android.view.WindowInsets.Type.displayCutout;
import static android.view.WindowInsets.Type.ime;
import static android.view.WindowInsets.Type.systemBars;
import static android.view.WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE;
import static android.view.WindowManager.LayoutParams.FIRST_SUB_WINDOW;
import static android.view.WindowManager.LayoutParams.FIRST_SYSTEM_WINDOW;
import static android.view.WindowManager.LayoutParams.FLAG_ALLOW_LOCK_WHILE_SCREEN_ON;
import static android.view.WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM;
import static android.view.WindowManager.LayoutParams.FLAG_BLUR_BEHIND;
import static android.view.WindowManager.LayoutParams.FLAG_DIM_BEHIND;
import static android.view.WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD;
import static android.view.WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS;
import static android.view.WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE;
import static android.view.WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL;
import static android.view.WindowManager.LayoutParams.FLAG_SCALED;
import static android.view.WindowManager.LayoutParams.FLAG_SHOW_WALLPAPER;
import static android.view.WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED;
import static android.view.WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON;
import static android.view.WindowManager.LayoutParams.LAST_SUB_WINDOW;
import static android.view.WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS;
import static android.view.WindowManager.LayoutParams.MATCH_PARENT;
import static android.view.WindowManager.LayoutParams.PRIVATE_FLAG_COMPATIBLE_WINDOW;
import static android.view.WindowManager.LayoutParams.PRIVATE_FLAG_LAYOUT_CHILD_WINDOW_IN_PARENT_FRAME;
import static android.view.WindowManager.LayoutParams.PRIVATE_FLAG_NO_MOVE_ANIMATION;
import static android.view.WindowManager.LayoutParams.PRIVATE_FLAG_TRUSTED_OVERLAY;
import static android.view.WindowManager.LayoutParams.PRIVATE_FLAG_WILL_NOT_REPLACE_ON_RELAUNCH;
import static android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE;
import static android.view.WindowManager.LayoutParams.SOFT_INPUT_MASK_ADJUST;
import static android.view.WindowManager.LayoutParams.SYSTEM_FLAG_HIDE_NON_SYSTEM_OVERLAY_WINDOWS;
import static android.view.WindowManager.LayoutParams.SYSTEM_FLAG_SHOW_FOR_ALL_USERS;
import static android.view.WindowManager.LayoutParams.TYPE_ACCESSIBILITY_MAGNIFICATION_OVERLAY;
import static android.view.WindowManager.LayoutParams.TYPE_APPLICATION;
import static android.view.WindowManager.LayoutParams.TYPE_APPLICATION_MEDIA;
import static android.view.WindowManager.LayoutParams.TYPE_APPLICATION_MEDIA_OVERLAY;
import static android.view.WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY;
import static android.view.WindowManager.LayoutParams.TYPE_APPLICATION_STARTING;
import static android.view.WindowManager.LayoutParams.TYPE_BASE_APPLICATION;
import static android.view.WindowManager.LayoutParams.TYPE_BOOT_PROGRESS;
import static android.view.WindowManager.LayoutParams.TYPE_DISPLAY_OVERLAY;
import static android.view.WindowManager.LayoutParams.TYPE_DOCK_DIVIDER;
import static android.view.WindowManager.LayoutParams.TYPE_DRAWN_APPLICATION;
import static android.view.WindowManager.LayoutParams.TYPE_INPUT_CONSUMER;
import static android.view.WindowManager.LayoutParams.TYPE_INPUT_METHOD;
import static android.view.WindowManager.LayoutParams.TYPE_INPUT_METHOD_DIALOG;
import static android.view.WindowManager.LayoutParams.TYPE_KEYGUARD_DIALOG;
import static android.view.WindowManager.LayoutParams.TYPE_MAGNIFICATION_OVERLAY;
import static android.view.WindowManager.LayoutParams.TYPE_NAVIGATION_BAR;
import static android.view.WindowManager.LayoutParams.TYPE_NAVIGATION_BAR_PANEL;
import static android.view.WindowManager.LayoutParams.TYPE_NOTIFICATION_SHADE;
import static android.view.WindowManager.LayoutParams.TYPE_PHONE;
import static android.view.WindowManager.LayoutParams.TYPE_POINTER;
import static android.view.WindowManager.LayoutParams.TYPE_PRESENTATION;
import static android.view.WindowManager.LayoutParams.TYPE_PRIORITY_PHONE;
import static android.view.WindowManager.LayoutParams.TYPE_PRIVATE_PRESENTATION;
import static android.view.WindowManager.LayoutParams.TYPE_SCREENSHOT;
import static android.view.WindowManager.LayoutParams.TYPE_SEARCH_BAR;
import static android.view.WindowManager.LayoutParams.TYPE_STATUS_BAR;
import static android.view.WindowManager.LayoutParams.TYPE_STATUS_BAR_ADDITIONAL;
import static android.view.WindowManager.LayoutParams.TYPE_STATUS_BAR_SUB_PANEL;
import static android.view.WindowManager.LayoutParams.TYPE_SYSTEM_DIALOG;
import static android.view.WindowManager.LayoutParams.TYPE_TOAST;
import static android.view.WindowManager.LayoutParams.TYPE_VOLUME_OVERLAY;
import static android.view.WindowManager.LayoutParams.TYPE_WALLPAPER;
import static android.view.WindowManager.LayoutParams.isSystemAlertWindowType;
import static android.view.WindowManagerGlobal.RELAYOUT_RES_DRAG_RESIZING_DOCKED;
import static android.view.WindowManagerGlobal.RELAYOUT_RES_DRAG_RESIZING_FREEFORM;
import static android.view.WindowManagerGlobal.RELAYOUT_RES_FIRST_TIME;

import static com.android.internal.protolog.ProtoLogGroup.WM_DEBUG_ADD_REMOVE;
import static com.android.internal.protolog.ProtoLogGroup.WM_DEBUG_APP_TRANSITIONS;
import static com.android.internal.protolog.ProtoLogGroup.WM_DEBUG_FOCUS;
import static com.android.internal.protolog.ProtoLogGroup.WM_DEBUG_FOCUS_LIGHT;
import static com.android.internal.protolog.ProtoLogGroup.WM_DEBUG_IME;
import static com.android.internal.protolog.ProtoLogGroup.WM_DEBUG_ORIENTATION;
import static com.android.internal.protolog.ProtoLogGroup.WM_DEBUG_RESIZE;
import static com.android.internal.protolog.ProtoLogGroup.WM_DEBUG_STARTING_WINDOW;
import static com.android.server.am.ActivityManagerService.MY_PID;
import static com.android.server.policy.WindowManagerPolicy.FINISH_LAYOUT_REDO_WALLPAPER;
import static com.android.server.policy.WindowManagerPolicy.TRANSIT_ENTER;
import static com.android.server.policy.WindowManagerPolicy.TRANSIT_EXIT;
import static com.android.server.policy.WindowManagerPolicy.TRANSIT_PREVIEW_DONE;
import static com.android.server.wm.AnimationSpecProto.MOVE;
import static com.android.server.wm.DisplayContent.IME_TARGET_INPUT;
import static com.android.server.wm.DisplayContent.IME_TARGET_LAYERING;
import static com.android.server.wm.DisplayContent.logsGestureExclusionRestrictions;
import static com.android.server.wm.DragResizeMode.DRAG_RESIZE_MODE_DOCKED_DIVIDER;
import static com.android.server.wm.DragResizeMode.DRAG_RESIZE_MODE_FREEFORM;
import static com.android.server.wm.IdentifierProto.HASH_CODE;
import static com.android.server.wm.IdentifierProto.TITLE;
import static com.android.server.wm.IdentifierProto.USER_ID;
import static com.android.server.wm.MoveAnimationSpecProto.DURATION_MS;
import static com.android.server.wm.MoveAnimationSpecProto.FROM;
import static com.android.server.wm.MoveAnimationSpecProto.TO;
import static com.android.server.wm.SurfaceAnimator.ANIMATION_TYPE_ALL;
import static com.android.server.wm.SurfaceAnimator.ANIMATION_TYPE_APP_TRANSITION;
import static com.android.server.wm.SurfaceAnimator.ANIMATION_TYPE_WINDOW_ANIMATION;
import static com.android.server.wm.WindowContainer.AnimationFlags.PARENTS;
import static com.android.server.wm.WindowContainer.AnimationFlags.TRANSITION;
import static com.android.server.wm.WindowContainerChildProto.WINDOW;
import static com.android.server.wm.WindowManagerDebugConfig.DEBUG;
import static com.android.server.wm.WindowManagerDebugConfig.DEBUG_ANIM;
import static com.android.server.wm.WindowManagerDebugConfig.DEBUG_CONFIGURATION;
import static com.android.server.wm.WindowManagerDebugConfig.DEBUG_INPUT_METHOD;
import static com.android.server.wm.WindowManagerDebugConfig.DEBUG_LAYOUT;
import static com.android.server.wm.WindowManagerDebugConfig.DEBUG_POWER;
import static com.android.server.wm.WindowManagerDebugConfig.DEBUG_STARTING_WINDOW_VERBOSE;
import static com.android.server.wm.WindowManagerDebugConfig.DEBUG_VISIBILITY;
import static com.android.server.wm.WindowManagerDebugConfig.TAG_WITH_CLASS_NAME;
import static com.android.server.wm.WindowManagerDebugConfig.TAG_WM;
import static com.android.server.wm.WindowManagerService.H.WINDOW_STATE_BLAST_SYNC_TIMEOUT;
import static com.android.server.wm.WindowManagerService.MAX_ANIMATION_DURATION;
import static com.android.server.wm.WindowManagerService.TYPE_LAYER_MULTIPLIER;
import static com.android.server.wm.WindowManagerService.TYPE_LAYER_OFFSET;
import static com.android.server.wm.WindowManagerService.UPDATE_FOCUS_NORMAL;
import static com.android.server.wm.WindowManagerService.UPDATE_FOCUS_REMOVING_FOCUS;
import static com.android.server.wm.WindowManagerService.UPDATE_FOCUS_WILL_PLACE_SURFACES;
import static com.android.server.wm.WindowManagerService.WINDOWS_FREEZING_SCREENS_TIMEOUT;
import static com.android.server.wm.WindowStateAnimator.COMMIT_DRAW_PENDING;
import static com.android.server.wm.WindowStateAnimator.DRAW_PENDING;
import static com.android.server.wm.WindowStateAnimator.HAS_DRAWN;
import static com.android.server.wm.WindowStateAnimator.PRESERVED_SURFACE_LAYER;
import static com.android.server.wm.WindowStateAnimator.READY_TO_SHOW;
import static com.android.server.wm.WindowStateProto.ANIMATING_EXIT;
import static com.android.server.wm.WindowStateProto.ANIMATOR;
import static com.android.server.wm.WindowStateProto.ATTRIBUTES;
import static com.android.server.wm.WindowStateProto.DESTROYING;
import static com.android.server.wm.WindowStateProto.DISPLAY_ID;
import static com.android.server.wm.WindowStateProto.FINISHED_SEAMLESS_ROTATION_FRAME;
import static com.android.server.wm.WindowStateProto.FORCE_SEAMLESS_ROTATION;
import static com.android.server.wm.WindowStateProto.GIVEN_CONTENT_INSETS;
import static com.android.server.wm.WindowStateProto.GLOBAL_SCALE;
import static com.android.server.wm.WindowStateProto.HAS_COMPAT_SCALE;
import static com.android.server.wm.WindowStateProto.HAS_SURFACE;
import static com.android.server.wm.WindowStateProto.IS_ON_SCREEN;
import static com.android.server.wm.WindowStateProto.IS_READY_FOR_DISPLAY;
import static com.android.server.wm.WindowStateProto.IS_VISIBLE;
import static com.android.server.wm.WindowStateProto.PENDING_SEAMLESS_ROTATION;
import static com.android.server.wm.WindowStateProto.REMOVED;
import static com.android.server.wm.WindowStateProto.REMOVE_ON_EXIT;
import static com.android.server.wm.WindowStateProto.REQUESTED_HEIGHT;
import static com.android.server.wm.WindowStateProto.REQUESTED_WIDTH;
import static com.android.server.wm.WindowStateProto.STACK_ID;
import static com.android.server.wm.WindowStateProto.SURFACE_INSETS;
import static com.android.server.wm.WindowStateProto.SURFACE_POSITION;
import static com.android.server.wm.WindowStateProto.VIEW_VISIBILITY;
import static com.android.server.wm.WindowStateProto.WINDOW_CONTAINER;
import static com.android.server.wm.WindowStateProto.WINDOW_FRAMES;

import android.annotation.CallSuper;
import android.annotation.Nullable;
import android.app.AppOpsManager;
import android.app.admin.DevicePolicyCache;
import android.app.compat.CompatChanges;
import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Matrix;
import android.graphics.PixelFormat;
import android.graphics.Point;
import android.graphics.Rect;
import android.graphics.Region;
import android.os.Binder;
import android.os.Build;
import android.os.Debug;
import android.os.IBinder;
import android.os.PowerManager;
import android.os.PowerManager.WakeReason;
import android.os.RemoteCallbackList;
import android.os.RemoteException;
import android.os.SystemClock;
import android.os.TouchOcclusionMode;
import android.os.Trace;
import android.os.WorkSource;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.ArraySet;
import android.util.DisplayMetrics;
import android.util.MergedConfiguration;
import android.util.Slog;
import android.util.SparseArray;
import android.util.TimeUtils;
import android.util.proto.ProtoOutputStream;
import android.view.Display;
import android.view.DisplayInfo;
import android.view.Gravity;
import android.view.IApplicationToken;
import android.view.IWindow;
import android.view.IWindowFocusObserver;
import android.view.IWindowId;
import android.view.InputChannel;
import android.view.InputEvent;
import android.view.InputEventReceiver;
import android.view.InputWindowHandle;
import android.view.InsetsSource;
import android.view.InsetsState;
import android.view.InsetsState.InternalInsetsType;
import android.view.Surface;
import android.view.Surface.Rotation;
import android.view.SurfaceControl;
import android.view.SurfaceSession;
import android.view.View;
import android.view.ViewDebug;
import android.view.ViewTreeObserver;
import android.view.WindowInfo;
import android.view.WindowInsets.Type.InsetsType;
import android.view.WindowManager;
import android.view.animation.Animation;
import android.view.animation.AnimationUtils;
import android.view.animation.Interpolator;
import android.window.ClientWindowFrames;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.policy.KeyInterceptionInfo;
import com.android.internal.protolog.common.ProtoLog;
import com.android.internal.util.FrameworkStatsLog;
import com.android.internal.util.ToBooleanFunction;
import com.android.server.policy.WindowManagerPolicy;
import com.android.server.wm.LocalAnimationAdapter.AnimationSpec;
import com.android.server.wm.SurfaceAnimator.AnimationType;

import java.io.PrintWriter;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Predicate;

/** A window in the window manager. */
class WindowState extends WindowContainer<WindowState> implements WindowManagerPolicy.WindowState,
        InsetsControlTarget {
    static final String TAG = TAG_WITH_CLASS_NAME ? "WindowState" : TAG_WM;

    // The minimal size of a window within the usable area of the freeform root task.
    // TODO(multi-window): fix the min sizes when we have minimum width/height support,
    //                     use hard-coded min sizes for now.
    static final int MINIMUM_VISIBLE_WIDTH_IN_DP = 48;
    static final int MINIMUM_VISIBLE_HEIGHT_IN_DP = 32;

    // The thickness of a window resize handle outside the window bounds on the free form workspace
    // to capture touch events in that area.
    static final int RESIZE_HANDLE_WIDTH_IN_DP = 30;

    static final int EXCLUSION_LEFT = 0;
    static final int EXCLUSION_RIGHT = 1;

    final WindowManagerPolicy mPolicy;
    final Context mContext;
    final Session mSession;
    final IWindow mClient;
    final int mAppOp;
    // UserId and appId of the owner. Don't display windows of non-current user.
    final int mOwnerUid;
    /**
     * Requested userId, if this is not equals with the userId from mOwnerUid, then this window is
     * created for secondary user.
     * Use this member instead of get userId from mOwnerUid while query for visibility.
     */
    final int mShowUserId;
    /** The owner has {@link android.Manifest.permission#INTERNAL_SYSTEM_WINDOW} */
    final boolean mOwnerCanAddInternalSystemWindow;
    final WindowId mWindowId;
    WindowToken mToken;
    // The same object as mToken if this is an app window and null for non-app windows.
    ActivityRecord mActivityRecord;

    // mAttrs.flags is tested in animation without being locked. If the bits tested are ever
    // modified they will need to be locked.
    final WindowManager.LayoutParams mAttrs = new WindowManager.LayoutParams();
    final DeathRecipient mDeathRecipient;
    private boolean mIsChildWindow;
    final int mBaseLayer;
    final int mSubLayer;
    final boolean mLayoutAttached;
    final boolean mIsImWindow;
    final boolean mIsWallpaper;
    private final boolean mIsFloatingLayer;
    int mViewVisibility;

    /**
     * Flags to disable system UI functions. This can only be set by the one which has the
     * status bar permission.
     *
     * @see View.SystemUiVisibility
     */
    int mDisableFlags;

    /**
     * The visibility flag of the window based on policy like {@link WindowManagerPolicy}.
     * Normally set by calling {@link #show} and {@link #hide}.
     *
     * TODO: b/131253938 This will eventually be split into individual visibility policy flags.
     */
    static final int LEGACY_POLICY_VISIBILITY = 1;
    /**
     * The visibility flag that determines whether this window is visible for the current user.
     */
    private static final int VISIBLE_FOR_USER = 1 << 1;
    private static final int POLICY_VISIBILITY_ALL = VISIBLE_FOR_USER | LEGACY_POLICY_VISIBILITY;
    /**
     * The Bitwise-or of flags that contribute to visibility of the WindowState
     */
    private int mPolicyVisibility = POLICY_VISIBILITY_ALL;

    /**
     * Whether {@link #LEGACY_POLICY_VISIBILITY} flag should be set after a transition animation.
     * For example, {@link #LEGACY_POLICY_VISIBILITY} might be set during an exit animation to hide
     * it and then unset when the value of {@link #mLegacyPolicyVisibilityAfterAnim} is false
     * after the exit animation is done.
     *
     * TODO: b/131253938 Determine whether this can be changed to use a visibility flag instead.
     */
    boolean mLegacyPolicyVisibilityAfterAnim = true;
    // overlay window is hidden because the owning app is suspended
    private boolean mHiddenWhileSuspended;
    private boolean mAppOpVisibility = true;
    boolean mPermanentlyHidden; // the window should never be shown again
    // This is a non-system overlay window that is currently force hidden.
    private boolean mForceHideNonSystemOverlayWindow;
    boolean mAppFreezing;
    boolean mHidden = true;    // Used to determine if to show child windows.
    private boolean mDragResizing;
    private boolean mDragResizingChangeReported = true;
    private int mResizeMode;
    private boolean mRedrawForSyncReported;

    /**
     * Special mode that is intended only for the rounded corner overlay: during rotation
     * transition, we un-rotate the window token such that the window appears as it did before the
     * rotation.
     */
    final boolean mForceSeamlesslyRotate;
    SeamlessRotator mPendingSeamlessRotate;
    long mFinishSeamlessRotateFrameNumber;

    private RemoteCallbackList<IWindowFocusObserver> mFocusCallbacks;

    /**
     * The window size that was requested by the application.  These are in
     * the application's coordinate space (without compatibility scale applied).
     */
    int mRequestedWidth;
    int mRequestedHeight;
    private int mLastRequestedWidth;
    private int mLastRequestedHeight;

    int mLayer;
    boolean mHaveFrame;
    boolean mObscured;

    int mLayoutSeq = -1;

    /** @see #addEmbeddedDisplayContent(DisplayContent dc) */
    private final ArraySet<DisplayContent> mEmbeddedDisplayContents = new ArraySet<>();

    /**
     * Used to store last reported to client configuration and check if we have newer available.
     * We'll send configuration to client only if it is different from the last applied one and
     * client won't perform unnecessary updates.
     */
    private final MergedConfiguration mLastReportedConfiguration = new MergedConfiguration();

    /** @see #isLastConfigReportedToClient() */
    private boolean mLastConfigReportedToClient;

    private final Configuration mTempConfiguration = new Configuration();

    /**
     * Set to true if we are waiting for this window to receive its
     * given internal insets before laying out other windows based on it.
     */
    boolean mGivenInsetsPending;

    /**
     * These are the content insets that were given during layout for
     * this window, to be applied to windows behind it.
     */
    final Rect mGivenContentInsets = new Rect();

    /**
     * These are the visible insets that were given during layout for
     * this window, to be applied to windows behind it.
     */
    final Rect mGivenVisibleInsets = new Rect();

    /**
     * This is the given touchable area relative to the window frame, or null if none.
     */
    final Region mGivenTouchableRegion = new Region();

    /**
     * Flag indicating whether the touchable region should be adjusted by
     * the visible insets; if false the area outside the visible insets is
     * NOT touchable, so we must use those to adjust the frame during hit
     * tests.
     */
    int mTouchableInsets = ViewTreeObserver.InternalInsetsInfo.TOUCHABLE_INSETS_FRAME;

    // Current transformation being applied.
    float mGlobalScale=1;
    float mLastGlobalScale=1;
    float mInvGlobalScale=1;
    float mOverrideScale = 1;
    float mHScale=1, mVScale=1;
    float mLastHScale=1, mLastVScale=1;
    final Matrix mTmpMatrix = new Matrix();
    final float[] mTmpMatrixArray = new float[9];

    private final WindowFrames mWindowFrames = new WindowFrames();

    private final ClientWindowFrames mClientWindowFrames = new ClientWindowFrames();

    /** The frames used to compute a temporal layout appearance. */
    private WindowFrames mSimulatedWindowFrames;

    /**
     * Usually empty. Set to the task's tempInsetFrame. See
     *{@link android.app.IActivityTaskManager#resizePrimarySplitScreen}.
     */
    private final Rect mInsetFrame = new Rect();

    /**
     * List of rects where system gestures should be ignored.
     *
     * Coordinates are relative to the window's position.
     */
    private final List<Rect> mExclusionRects = new ArrayList<>();

    // 0 = left, 1 = right
    private final int[] mLastRequestedExclusionHeight = {0, 0};
    private final int[] mLastGrantedExclusionHeight = {0, 0};
    private final long[] mLastExclusionLogUptimeMillis = {0, 0};

    private boolean mLastShownChangedReported;

    // If a window showing a wallpaper: the requested offset for the
    // wallpaper; if a wallpaper window: the currently applied offset.
    float mWallpaperX = -1;
    float mWallpaperY = -1;

    // If a window showing a wallpaper: the requested zoom out for the
    // wallpaper; if a wallpaper window: the currently applied zoom.
    float mWallpaperZoomOut = -1;

    // If a wallpaper window: whether the wallpaper should be scaled when zoomed, if set
    // to false, mWallpaperZoom will be ignored here and just passed to the WallpaperService.
    boolean mShouldScaleWallpaper;

    // If a window showing a wallpaper: what fraction of the offset
    // range corresponds to a full virtual screen.
    float mWallpaperXStep = -1;
    float mWallpaperYStep = -1;

    // If a window showing a wallpaper: a raw pixel offset to forcibly apply
    // to its window; if a wallpaper window: not used.
    int mWallpaperDisplayOffsetX = Integer.MIN_VALUE;
    int mWallpaperDisplayOffsetY = Integer.MIN_VALUE;

    /**
     * This is set after IWindowSession.relayout() has been called at
     * least once for the window.  It allows us to detect the situation
     * where we don't yet have a surface, but should have one soon, so
     * we can give the window focus before waiting for the relayout.
     */
    boolean mRelayoutCalled;

    boolean mInRelayout;

    /**
     * If the application has called relayout() with changes that can
     * impact its window's size, we need to perform a layout pass on it
     * even if it is not currently visible for layout.  This is set
     * when in that case until the layout is done.
     */
    boolean mLayoutNeeded;

    /** Currently running an exit animation? */
    boolean mAnimatingExit;

    /** Currently on the mDestroySurface list? */
    boolean mDestroying;

    /** Completely remove from window manager after exit animation? */
    boolean mRemoveOnExit;

    /**
     * Whether the app died while it was visible, if true we might need
     * to continue to show it until it's restarted.
     */
    boolean mAppDied;

    /**
     * Set when the orientation is changing and this window has not yet
     * been updated for the new orientation.
     */
    private boolean mOrientationChanging;

    /** The time when the window was last requested to redraw for orientation change. */
    private long mOrientationChangeRedrawRequestTime;

    /**
     * Sometimes in addition to the mOrientationChanging
     * flag we report that the orientation is changing
     * due to a mismatch in current and reported configuration.
     *
     * In the case of timeout we still need to make sure we
     * leave the orientation changing state though, so we
     * use this as a special time out escape hatch.
     */
    private boolean mOrientationChangeTimedOut;

    /**
     * The orientation during the last visible call to relayout. If our
     * current orientation is different, the window can't be ready
     * to be shown.
     */
    int mLastVisibleLayoutRotation = -1;

    /**
     * Set when we need to report the orientation change to client to trigger a relayout.
     */
    boolean mReportOrientationChanged;

    /**
     * How long we last kept the screen frozen.
     */
    int mLastFreezeDuration;

    /** Is this window now (or just being) removed? */
    boolean mRemoved;

    /**
     * It is save to remove the window and destroy the surface because the client requested removal
     * or some other higher level component said so (e.g. activity manager).
     * TODO: We should either have different booleans for the removal reason or use a bit-field.
     */
    boolean mWindowRemovalAllowed;

    // Input channel and input window handle used by the input dispatcher.
    final InputWindowHandleWrapper mInputWindowHandle;
    InputChannel mInputChannel;

    /**
     * The token will be assigned to {@link InputWindowHandle#token} if this window can receive
     * input event. Note that the token of associated input window handle can be cleared if this
     * window becomes unable to receive input, but this field will remain until the input channel
     * is actually disposed.
     */
    IBinder mInputChannelToken;

    // Used to improve performance of toString()
    private String mStringNameCache;
    private CharSequence mLastTitle;
    private boolean mWasExiting;

    final WindowStateAnimator mWinAnimator;

    boolean mHasSurface = false;

    // This window will be replaced due to relaunch. This allows window manager
    // to differentiate between simple removal of a window and replacement. In the latter case it
    // will preserve the old window until the new one is drawn.
    boolean mWillReplaceWindow = false;
    // If true, the replaced window was already requested to be removed.
    private boolean mReplacingRemoveRequested = false;
    // Whether the replacement of the window should trigger app transition animation.
    private boolean mAnimateReplacingWindow = false;
    // If not null, the window that will be used to replace the old one. This is being set when
    // the window is added and unset when this window reports its first draw.
    private WindowState mReplacementWindow = null;
    // For the new window in the replacement transition, if we have
    // requested to replace without animation, then we should
    // make sure we also don't apply an enter animation for
    // the new window.
    boolean mSkipEnterAnimationForSeamlessReplacement = false;
    // Whether this window is being moved via the resize API
    private boolean mMovedByResize;

    /**
     * Wake lock for drawing.
     * Even though it's slightly more expensive to do so, we will use a separate wake lock
     * for each app that is requesting to draw while dozing so that we can accurately track
     * who is preventing the system from suspending.
     * This lock is only acquired on first use.
     */
    private PowerManager.WakeLock mDrawLock;

    private final Rect mTmpRect = new Rect();
    private final Point mTmpPoint = new Point();

    private final Transaction mTmpTransaction;

    /**
     * If a window is on a display which has been re-parented to a view in another window,
     * use this offset to indicate the correct location.
     */
    private final Point mLastReportedDisplayOffset = new Point();

    /**
     * Whether the window was resized by us while it was gone for layout.
     */
    boolean mResizedWhileGone = false;

    /**
     * During seamless rotation we have two phases, first the old window contents
     * are rotated to look as if they didn't move in the new coordinate system. Then we
     * have to freeze updates to this layer (to preserve the transformation) until
     * the resize actually occurs. This is true from when the transformation is set
     * and false until the transaction to resize is sent.
     */
    boolean mSeamlesslyRotated = false;

    /**
     * The insets state of sources provided by windows above the current window.
     */
    final InsetsState mAboveInsetsState = new InsetsState();

    /**
     * The insets sources provided by this window.
     */
    final SparseArray<InsetsSource> mProvidedInsetsSources = new SparseArray<>();

    /**
     * Surface insets from the previous call to relayout(), used to track
     * if we are changing the Surface insets.
     */
    final Rect mLastSurfaceInsets = new Rect();

    /**
     * A flag set by the {@link WindowState} parent to indicate that the parent has examined this
     * {@link WindowState} in its overall drawing context. This book-keeping allows the parent to
     * make sure all children have been considered.
     */
    private boolean mDrawnStateEvaluated;

    private final Point mSurfacePosition = new Point();

    /**
     * A region inside of this window to be excluded from touch.
     */
    private final Region mTapExcludeRegion = new Region();

    /**
     * Used for testing because the real PowerManager is final.
     */
    private PowerManagerWrapper mPowerManagerWrapper;

    /**
     * A frame number in which changes requested in this layout will be rendered.
     */
    private long mFrameNumber = -1;

    private static final StringBuilder sTmpSB = new StringBuilder();

    /**
     * Compares two window sub-layers and returns -1 if the first is lesser than the second in terms
     * of z-order and 1 otherwise.
     */
    private static final Comparator<WindowState> sWindowSubLayerComparator =
            new Comparator<WindowState>() {
                @Override
                public int compare(WindowState w1, WindowState w2) {
                    final int layer1 = w1.mSubLayer;
                    final int layer2 = w2.mSubLayer;
                    if (layer1 < layer2 || (layer1 == layer2 && layer2 < 0 )) {
                        // We insert the child window into the list ordered by
                        // the sub-layer.  For same sub-layers, the negative one
                        // should go below others; the positive one should go
                        // above others.
                        return -1;
                    }
                    return 1;
                };
            };

    /**
     * Indicates whether we have requested a Dim (in the sense of {@link Dimmer}) from our host
     * container.
     */
    private boolean mIsDimming = false;

    private @Nullable InsetsSourceProvider mControllableInsetProvider;
    private final InsetsState mRequestedInsetsState = new InsetsState();

    /**
     * Freeze the insets state in some cases that not necessarily keeps up-to-date to the client.
     * (e.g app exiting transition)
     */
    private InsetsState mFrozenInsetsState;

    @Nullable InsetsSourceProvider mPendingPositionChanged;

    private static final float DEFAULT_DIM_AMOUNT_DEAD_WINDOW = 0.5f;
    private KeyInterceptionInfo mKeyInterceptionInfo;

    /**
     * This information is passed to SurfaceFlinger to decide which window should have a priority
     * when deciding about the refresh rate of the display. All windows have the lowest priority by
     * default. The variable is cached, so we do not send too many updates to SF.
     */
    int mFrameRateSelectionPriority = RefreshRatePolicy.LAYER_PRIORITY_UNSET;

    /**
     * This is the frame rate which is passed to SurfaceFlinger if the window is part of the
     * high refresh rate deny list. The variable is cached, so we do not send too many updates to
     * SF.
     */
    float mDenyListFrameRate = 0f;

    static final int BLAST_TIMEOUT_DURATION = 5000; /* milliseconds */

    private final WindowProcessController mWpcForDisplayAreaConfigChanges;

    /**
     * We split the draw handlers in to a "pending" and "ready" list, in order to solve
     * sequencing problems. Think of it this way, let's say I update a windows orientation
     * (in configuration), and then I call applyWithNextDraw. What I'm hoping for is to
     * apply with the draw that contains the orientation change. However, since the client
     * can call finishDrawing at any time, it could be about to call a previous call to
     * finishDrawing (or maybe its already called it, we just haven't handled it). Since this
     * frame was already completed it had no time to include the orientation change we made.
     * To solve this problem we accumulate draw handlers in mPendingDrawHandlers, and then force
     * the client to call relayout. Only the frame post relayout will contain the configuration
     * change since the window has to relayout), and so in relayout we drain mPendingDrawHandlers
     * into mReadyDrawHandlers. Finally once we get to finishDrawing we know everything in
     * mReadyDrawHandlers corresponds to state which was observed by the client and we can
     * invoke the consumers.
     */
    private final List<Consumer<SurfaceControl.Transaction>> mPendingDrawHandlers
        = new ArrayList<>();
    private final List<Consumer<SurfaceControl.Transaction>> mReadyDrawHandlers
        = new ArrayList<>();

    private final Consumer<SurfaceControl.Transaction> mSeamlessRotationFinishedConsumer = t -> {
        finishSeamlessRotation(t);
        updateSurfacePosition(t);
    };

    /**
     * @see #setSurfaceTranslationY(int)
     */
    private int mSurfaceTranslationY;

    /**
     * Returns the visibility of the given {@link InternalInsetsType type} requested by the client.
     *
     * @param type the given {@link InternalInsetsType type}.
     * @return {@code true} if the type is requested visible.
     */
    @Override
    public boolean getRequestedVisibility(@InternalInsetsType int type) {
        return mRequestedInsetsState.getSourceOrDefaultVisibility(type);
    }

    /**
     * @see #getRequestedVisibility(int)
     */
    void updateRequestedVisibility(InsetsState state) {
        for (int i = 0; i < InsetsState.SIZE; i++) {
            final InsetsSource source = state.peekSource(i);
            if (source == null) continue;
            mRequestedInsetsState.addSource(source);
        }
    }

    /**
     * Set a freeze state for the window to ignore dispatching its insets state to the client.
     *
     * Used to keep the insets state for some use cases. (e.g. app exiting transition)
     */
    void freezeInsetsState() {
        if (mFrozenInsetsState == null) {
            mFrozenInsetsState = new InsetsState(getInsetsState(), true /* copySources */);
        }
    }

    void clearFrozenInsetsState() {
        mFrozenInsetsState = null;
    }

    InsetsState getFrozenInsetsState() {
        return mFrozenInsetsState;
    }

    /**
     * Check if the insets state of the window is ready to dispatch to the client when invoking
     * {@link InsetsStateController#notifyInsetsChanged}.
     */
    boolean isReadyToDispatchInsetsState() {
        return isVisibleRequested() && mFrozenInsetsState == null;
    }

    void seamlesslyRotateIfAllowed(Transaction transaction, @Rotation int oldRotation,
            @Rotation int rotation, boolean requested) {
        // Invisible windows and the wallpaper do not participate in the seamless rotation animation
        if (!isVisibleNow() || mIsWallpaper) {
            return;
        }

        if (mToken.hasFixedRotationTransform()) {
            // The transform of its surface is handled by fixed rotation.
            return;
        }

        if (mPendingSeamlessRotate != null) {
            oldRotation = mPendingSeamlessRotate.getOldRotation();
        }

        // Skip performing seamless rotation when the controlled insets is IME with visible state.
        if (mControllableInsetProvider != null
                && mControllableInsetProvider.getSource().getType() == ITYPE_IME) {
            return;
        }

        if (mForceSeamlesslyRotate || requested) {
            if (mControllableInsetProvider != null) {
                mControllableInsetProvider.startSeamlessRotation();
            }
            mPendingSeamlessRotate = new SeamlessRotator(oldRotation, rotation, getDisplayInfo(),
                    false /* applyFixedTransformationHint */);
            // The surface position is going to be unrotated according to the last position.
            // Make sure the source position is up-to-date.
            mLastSurfacePosition.set(mSurfacePosition.x, mSurfacePosition.y);
            mPendingSeamlessRotate.unrotate(transaction, this);
            getDisplayContent().getDisplayRotation().markForSeamlessRotation(this,
                    true /* seamlesslyRotated */);
            applyWithNextDraw(mSeamlessRotationFinishedConsumer);
        }
    }

    void cancelSeamlessRotation() {
        finishSeamlessRotation(getPendingTransaction());
    }

    void finishSeamlessRotation(SurfaceControl.Transaction t) {
        if (mPendingSeamlessRotate == null) {
            return;
        }

        mPendingSeamlessRotate.finish(t, this);
        mFinishSeamlessRotateFrameNumber = getFrameNumber();
        mPendingSeamlessRotate = null;

        getDisplayContent().getDisplayRotation().markForSeamlessRotation(this,
            false /* seamlesslyRotated */);
        if (mControllableInsetProvider != null) {
            mControllableInsetProvider.finishSeamlessRotation();
        }
    }

    List<Rect> getSystemGestureExclusion() {
        return mExclusionRects;
    }

    /**
     * Sets the system gesture exclusion rects.
     *
     * @return {@code true} if anything changed
     */
    boolean setSystemGestureExclusion(List<Rect> exclusionRects) {
        if (mExclusionRects.equals(exclusionRects)) {
            return false;
        }
        mExclusionRects.clear();
        mExclusionRects.addAll(exclusionRects);
        return true;
    }

    boolean isImplicitlyExcludingAllSystemGestures() {
        final boolean stickyHideNav =
                mAttrs.insetsFlags.behavior == BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                        && !getRequestedVisibility(ITYPE_NAVIGATION_BAR);
        return stickyHideNav && mWmService.mConstants.mSystemGestureExcludedByPreQStickyImmersive
                && mActivityRecord != null && mActivityRecord.mTargetSdk < Build.VERSION_CODES.Q;
    }

    void setLastExclusionHeights(int side, int requested, int granted) {
        boolean changed = mLastGrantedExclusionHeight[side] != granted
                || mLastRequestedExclusionHeight[side] != requested;

        if (changed) {
            if (mLastShownChangedReported) {
                logExclusionRestrictions(side);
            }

            mLastGrantedExclusionHeight[side] = granted;
            mLastRequestedExclusionHeight[side] = requested;
        }
    }

    interface PowerManagerWrapper {
        void wakeUp(long time, @WakeReason int reason, String details);

        boolean isInteractive();

    }

    WindowState(WindowManagerService service, Session s, IWindow c, WindowToken token,
            WindowState parentWindow, int appOp, WindowManager.LayoutParams a, int viewVisibility,
            int ownerId, int showUserId, boolean ownerCanAddInternalSystemWindow) {
        this(service, s, c, token, parentWindow, appOp, a, viewVisibility, ownerId, showUserId,
                ownerCanAddInternalSystemWindow, new PowerManagerWrapper() {
                    @Override
                    public void wakeUp(long time, @WakeReason int reason, String details) {
                        service.mPowerManager.wakeUp(time, reason, details);
                    }

                    @Override
                    public boolean isInteractive() {
                        return service.mPowerManager.isInteractive();
                    }
                });
    }

    WindowState(WindowManagerService service, Session s, IWindow c, WindowToken token,
            WindowState parentWindow, int appOp, WindowManager.LayoutParams a, int viewVisibility,
            int ownerId, int showUserId, boolean ownerCanAddInternalSystemWindow,
            PowerManagerWrapper powerManagerWrapper) {
        super(service);
        mTmpTransaction = service.mTransactionFactory.get();
        mSession = s;
        mClient = c;
        mAppOp = appOp;
        mToken = token;
        mActivityRecord = mToken.asActivityRecord();
        mOwnerUid = ownerId;
        mShowUserId = showUserId;
        mOwnerCanAddInternalSystemWindow = ownerCanAddInternalSystemWindow;
        mWindowId = new WindowId(this);
        mAttrs.copyFrom(a);
        mLastSurfaceInsets.set(mAttrs.surfaceInsets);
        mViewVisibility = viewVisibility;
        mPolicy = mWmService.mPolicy;
        mContext = mWmService.mContext;
        DeathRecipient deathRecipient = new DeathRecipient();
        mPowerManagerWrapper = powerManagerWrapper;
        mForceSeamlesslyRotate = token.mRoundedCornerOverlay;
        mInputWindowHandle = new InputWindowHandleWrapper(new InputWindowHandle(
                mActivityRecord != null
                        ? mActivityRecord.getInputApplicationHandle(false /* update */) : null,
                getDisplayId()));
        mInputWindowHandle.setOwnerPid(s.mPid);
        mInputWindowHandle.setOwnerUid(s.mUid);
        mInputWindowHandle.setName(getName());
        mInputWindowHandle.setPackageName(mAttrs.packageName);
        mInputWindowHandle.setLayoutParamsType(mAttrs.type);
        // Check private trusted overlay flag and window type to set trustedOverlay variable of
        // input window handle.
        mInputWindowHandle.setTrustedOverlay(
                ((mAttrs.privateFlags & PRIVATE_FLAG_TRUSTED_OVERLAY) != 0
                        && mOwnerCanAddInternalSystemWindow)
                        || InputMonitor.isTrustedOverlay(mAttrs.type));
        if (DEBUG) {
            Slog.v(TAG, "Window " + this + " client=" + c.asBinder()
                            + " token=" + token + " (" + mAttrs.token + ")" + " params=" + a);
        }
        try {
            c.asBinder().linkToDeath(deathRecipient, 0);
        } catch (RemoteException e) {
            mDeathRecipient = null;
            mIsChildWindow = false;
            mLayoutAttached = false;
            mIsImWindow = false;
            mIsWallpaper = false;
            mIsFloatingLayer = false;
            mBaseLayer = 0;
            mSubLayer = 0;
            mWinAnimator = null;
            mWpcForDisplayAreaConfigChanges = null;
            return;
        }
        mDeathRecipient = deathRecipient;

        if (mAttrs.type >= FIRST_SUB_WINDOW && mAttrs.type <= LAST_SUB_WINDOW) {
            // The multiplier here is to reserve space for multiple
            // windows in the same type layer.
            mBaseLayer = mPolicy.getWindowLayerLw(parentWindow)
                    * TYPE_LAYER_MULTIPLIER + TYPE_LAYER_OFFSET;
            mSubLayer = mPolicy.getSubWindowLayerFromTypeLw(a.type);
            mIsChildWindow = true;

            mLayoutAttached = mAttrs.type !=
                    WindowManager.LayoutParams.TYPE_APPLICATION_ATTACHED_DIALOG;
            mIsImWindow = parentWindow.mAttrs.type == TYPE_INPUT_METHOD
                    || parentWindow.mAttrs.type == TYPE_INPUT_METHOD_DIALOG;
            mIsWallpaper = parentWindow.mAttrs.type == TYPE_WALLPAPER;
        } else {
            // The multiplier here is to reserve space for multiple
            // windows in the same type layer.
            mBaseLayer = mPolicy.getWindowLayerLw(this)
                    * TYPE_LAYER_MULTIPLIER + TYPE_LAYER_OFFSET;
            mSubLayer = 0;
            mIsChildWindow = false;
            mLayoutAttached = false;
            mIsImWindow = mAttrs.type == TYPE_INPUT_METHOD
                    || mAttrs.type == TYPE_INPUT_METHOD_DIALOG;
            mIsWallpaper = mAttrs.type == TYPE_WALLPAPER;
        }
        mIsFloatingLayer = mIsImWindow || mIsWallpaper;

        if (mActivityRecord != null && mActivityRecord.mShowForAllUsers) {
            // Windows for apps that can show for all users should also show when the device is
            // locked.
            mAttrs.flags |= FLAG_SHOW_WHEN_LOCKED;
        }

        mWinAnimator = new WindowStateAnimator(this);
        mWinAnimator.mAlpha = a.alpha;

        mRequestedWidth = 0;
        mRequestedHeight = 0;
        mLastRequestedWidth = 0;
        mLastRequestedHeight = 0;
        mLayer = 0;
        mOverrideScale = mWmService.mAtmService.mCompatModePackages.getCompatScale(
                mAttrs.packageName, s.mUid);

        // Make sure we initial all fields before adding to parentWindow, to prevent exception
        // during onDisplayChanged.
        if (mIsChildWindow) {
            ProtoLog.v(WM_DEBUG_ADD_REMOVE, "Adding %s to %s", this, parentWindow);
            parentWindow.addChild(this, sWindowSubLayerComparator);
        }

        // System process or invalid process cannot register to display area config change.
        mWpcForDisplayAreaConfigChanges = (s.mPid == MY_PID || s.mPid < 0)
                ? null
                : service.mAtmService.getProcessController(s.mPid, s.mUid);
    }

    int getTouchOcclusionMode() {
        if (!CompatChanges.isChangeEnabled(BLOCK_UNTRUSTED_TOUCHES, mOwnerUid)) {
            return TouchOcclusionMode.ALLOW;
        }
        if (WindowManager.LayoutParams.isSystemAlertWindowType(mAttrs.type)) {
            return TouchOcclusionMode.USE_OPACITY;
        }
        if (isAnimating(PARENTS | TRANSITION, ANIMATION_TYPE_ALL)) {
            return TouchOcclusionMode.USE_OPACITY;
        }
        return TouchOcclusionMode.BLOCK_UNTRUSTED;
    }

    void attach() {
        if (DEBUG) Slog.v(TAG, "Attaching " + this + " token=" + mToken);
        mSession.windowAddedLocked(mAttrs.packageName);
    }

    /**
     * @return {@code true} if the application runs in size compatibility mode or has an app level
     * scaling override set.
     * @see CompatModePackages#getCompatScale
     * @see android.content.res.CompatibilityInfo#supportsScreen
     * @see ActivityRecord#hasSizeCompatBounds()
     */
    boolean hasCompatScale() {
        return mOverrideScale != 1f || hasCompatScale(mAttrs, mActivityRecord);
    }

    /**
     * @return {@code true} if the application runs in size compatibility mode.
     * @see android.content.res.CompatibilityInfo#supportsScreen
     * @see ActivityRecord#hasSizeCompatBounds()
     */
    static boolean hasCompatScale(WindowManager.LayoutParams attrs, WindowToken windowToken) {
        return (attrs.privateFlags & PRIVATE_FLAG_COMPATIBLE_WINDOW) != 0
                || (windowToken != null && windowToken.hasSizeCompatBounds()
                // Exclude starting window because it is not displayed by the application.
                && attrs.type != TYPE_APPLICATION_STARTING);
    }

    /**
     * Returns whether this {@link WindowState} has been considered for drawing by its parent.
     */
    boolean getDrawnStateEvaluated() {
        return mDrawnStateEvaluated;
    }

    /**
     * Sets whether this {@link WindowState} has been considered for drawing by its parent. Should
     * be cleared when detached from parent.
     */
    void setDrawnStateEvaluated(boolean evaluated) {
        mDrawnStateEvaluated = evaluated;
    }

    @Override
    void onParentChanged(ConfigurationContainer newParent, ConfigurationContainer oldParent) {
        super.onParentChanged(newParent, oldParent);
        setDrawnStateEvaluated(false /*evaluated*/);

        getDisplayContent().reapplyMagnificationSpec();
    }

    /** Returns the uid of the app that owns this window. */
    int getOwningUid() {
        return mOwnerUid;
    }

    @Override
    public String getOwningPackage() {
        return mAttrs.packageName;
    }

    @Override
    public boolean canAddInternalSystemWindow() {
        return mOwnerCanAddInternalSystemWindow;
    }

    /**
     * Returns {@code true} if the window owner has the permission to acquire a sleep token when
     * it's visible. That is, they have the permission
     * {@link androidManifest.permission#DEVICE_POWER}.
     */
    boolean canAcquireSleepToken() {
        return mSession.mCanAcquireSleepToken;
    }

    /**
     * Subtracts the insets calculated by intersecting {@param layoutFrame} with {@param insetFrame}
     * from {@param frame}. In other words, it applies the insets that would result if
     * {@param frame} would be shifted to {@param layoutFrame} and then applying the insets from
     * {@param insetFrame}. Also it respects {@param displayFrame} in case window has minimum
     * width/height applied and insets should be overridden.
     */
    private void subtractInsets(Rect frame, Rect layoutFrame, Rect insetFrame, Rect displayFrame) {
        final int left = Math.max(0, insetFrame.left - Math.max(layoutFrame.left, displayFrame.left));
        final int top = Math.max(0, insetFrame.top - Math.max(layoutFrame.top, displayFrame.top));
        final int right = Math.max(0, Math.min(layoutFrame.right, displayFrame.right) - insetFrame.right);
        final int bottom = Math.max(0, Math.min(layoutFrame.bottom, displayFrame.bottom) - insetFrame.bottom);
        frame.inset(left, top, right, bottom);
    }

    void computeFrameAndUpdateSourceFrame() {
        computeFrame();
        // Update the source frame to provide insets to other windows during layout. If the
        // simulated frames exist, then this is not computing a stable result so just skip.
        if (mControllableInsetProvider != null && mSimulatedWindowFrames == null) {
            mControllableInsetProvider.updateSourceFrame();
        }
    }

    /**
     * Perform standard frame computation. The result can be obtained with getFrame() if so desired.
     */
    void computeFrame() {
        if (mWillReplaceWindow && (mAnimatingExit || !mReplacingRemoveRequested)) {
            // This window is being replaced and either already got information that it's being
            // removed or we are still waiting for some information. Because of this we don't
            // want to apply any more changes to it, so it remains in this state until new window
            // appears.
            return;
        }
        mHaveFrame = true;

        final Task task = getTask();
        final boolean isFullscreenAndFillsDisplay = !inMultiWindowMode() && matchesDisplayBounds();
        final boolean windowsAreFloating = task != null && task.isFloating();
        final DisplayContent dc = getDisplayContent();
        final DisplayInfo displayInfo = getDisplayInfo();
        final WindowFrames windowFrames = getLayoutingWindowFrames();

        mInsetFrame.set(getBounds());

        // Denotes the actual frame used to calculate the insets and to perform the layout. When
        // resizing in docked mode, we'd like to freeze the layout, so we also need to freeze the
        // insets temporarily. By the notion of a task having a different layout frame, we can
        // achieve that while still moving the task around.
        final Rect layoutContainingFrame;
        final Rect layoutDisplayFrame;

        // The offset from the layout containing frame to the actual containing frame.
        final int layoutXDiff;
        final int layoutYDiff;
        final WindowState imeWin = mWmService.mRoot.getCurrentInputMethodWindow();
        final InsetsControlTarget imeTarget = dc.getImeTarget(IME_TARGET_LAYERING);
        final boolean isInputMethodAdjustTarget = windowsAreFloating
                ? imeTarget != null && task == imeTarget.getWindow().getTask()
                : isImeLayeringTarget();
        final boolean isImeTarget =
                imeWin != null && imeWin.isVisibleNow() && isInputMethodAdjustTarget;
        if (isFullscreenAndFillsDisplay || layoutInParentFrame()) {
            // We use the parent frame as the containing frame for fullscreen and child windows
            windowFrames.mContainingFrame.set(windowFrames.mParentFrame);
            layoutDisplayFrame = windowFrames.mDisplayFrame;
            layoutContainingFrame = windowFrames.mParentFrame;
            layoutXDiff = 0;
            layoutYDiff = 0;
        } else {
            windowFrames.mContainingFrame.set(getBounds());
            // IME is up and obscuring this window. Adjust the window position so it is visible.
            if (isImeTarget) {
                if (inFreeformWindowingMode()) {
                    // Push the freeform window up to make room for the IME. However, don't push
                    // it up past the bottom of the top bar.
                    final InsetsState state = dc.getInsetsStateController().getRawInsetsState();
                    final Rect visibleFrame = mTmpRect;
                    visibleFrame.set(state.getDisplayFrame());
                    visibleFrame.inset(state.calculateInsets(visibleFrame,
                            systemBars() | ime() | displayCutout(), false /* ignoreVisibility */));
                    final int bottomOverlap =
                            windowFrames.mContainingFrame.bottom - visibleFrame.bottom;
                    if (bottomOverlap > 0) {
                        final int distanceToTop = Math.max(windowFrames.mContainingFrame.top
                                - visibleFrame.top, 0);
                        int offs = Math.min(bottomOverlap, distanceToTop);
                        windowFrames.mContainingFrame.offset(0, -offs);
                        mInsetFrame.offset(0, -offs);
                    }
                } else if (!inPinnedWindowingMode() && windowFrames.mContainingFrame.bottom
                        > windowFrames.mParentFrame.bottom) {
                    // But in docked we want to behave like fullscreen and behave as if the task
                    // were given smaller bounds for the purposes of layout. Skip adjustments for
                    // the root pinned task, they are handled separately in the
                    // PinnedTaskController.
                    windowFrames.mContainingFrame.bottom = windowFrames.mParentFrame.bottom;
                }
            }

            if (windowsAreFloating) {
                // In floating modes (e.g. freeform, pinned) we have only to set the rectangle
                // if it wasn't set already. No need to intersect it with the (visible)
                // "content frame" since it is allowed to be outside the visible desktop.
                if (windowFrames.mContainingFrame.isEmpty()) {
                    windowFrames.mContainingFrame.set(windowFrames.mDisplayFrame);
                }
            }

            layoutDisplayFrame = new Rect(windowFrames.mDisplayFrame);
            windowFrames.mDisplayFrame.set(windowFrames.mContainingFrame);
            layoutXDiff = mInsetFrame.left - windowFrames.mContainingFrame.left;
            layoutYDiff = mInsetFrame.top - windowFrames.mContainingFrame.top;
            layoutContainingFrame = mInsetFrame;
            mTmpRect.set(0, 0, displayInfo.logicalWidth, displayInfo.logicalHeight);
            subtractInsets(windowFrames.mDisplayFrame, layoutContainingFrame, layoutDisplayFrame,
                    mTmpRect);
            if (!layoutInParentFrame()) {
                subtractInsets(windowFrames.mContainingFrame, layoutContainingFrame,
                        windowFrames.mParentFrame, mTmpRect);
                subtractInsets(mInsetFrame, layoutContainingFrame, windowFrames.mParentFrame,
                        mTmpRect);
            }
            layoutDisplayFrame.intersect(layoutContainingFrame);
        }

        final int pw = windowFrames.mContainingFrame.width();
        final int ph = windowFrames.mContainingFrame.height();

        if (mRequestedWidth != mLastRequestedWidth || mRequestedHeight != mLastRequestedHeight) {
            mLastRequestedWidth = mRequestedWidth;
            mLastRequestedHeight = mRequestedHeight;
            windowFrames.setContentChanged(true);
        }

        final int fw = windowFrames.mFrame.width();
        final int fh = windowFrames.mFrame.height();

        applyGravityAndUpdateFrame(windowFrames, layoutContainingFrame, layoutDisplayFrame);

        if (mAttrs.type == TYPE_DOCK_DIVIDER) {
            if (!windowFrames.mFrame.equals(windowFrames.mLastFrame)) {
                mMovedByResize = true;
            }
        }

        // Offset the actual frame by the amount layout frame is off.
        windowFrames.offsetFrames(-layoutXDiff, -layoutYDiff);

        windowFrames.mCompatFrame.set(windowFrames.mFrame);
        if (hasCompatScale()) {
            // Also the scaled frame that we report to the app needs to be
            // adjusted to be in its coordinate space.
            windowFrames.mCompatFrame.scale(mInvGlobalScale);
        }

        if (mIsWallpaper && (fw != windowFrames.mFrame.width()
                || fh != windowFrames.mFrame.height())) {
            dc.mWallpaperController.updateWallpaperOffset(this, false /* sync */);
        }

        // Calculate relative frame
        windowFrames.mRelFrame.set(windowFrames.mFrame);
        WindowContainer parent = getParent();
        int parentLeft = 0;
        int parentTop = 0;
        if (mIsChildWindow) {
            parentLeft = ((WindowState) parent).mWindowFrames.mFrame.left;
            parentTop = ((WindowState) parent).mWindowFrames.mFrame.top;
        } else if (parent != null) {
            final Rect parentBounds = parent.getBounds();
            parentLeft = parentBounds.left;
            parentTop = parentBounds.top;
        }
        windowFrames.mRelFrame.offsetTo(windowFrames.mFrame.left - parentLeft,
                windowFrames.mFrame.top - parentTop);

        if (DEBUG_LAYOUT || DEBUG) {
            Slog.v(TAG, "Resolving (mRequestedWidth="
                            + mRequestedWidth + ", mRequestedheight="
                            + mRequestedHeight + ") to" + " (pw=" + pw + ", ph=" + ph
                            + "): frame=" + windowFrames.mFrame.toShortString()
                            + " " + mAttrs.getTitle());
        }
    }

    // TODO: Look into whether this override is still necessary.
    @Override
    public Rect getBounds() {
        if (mActivityRecord != null) {
            return mActivityRecord.getBounds();
        } else {
            return super.getBounds();
        }
    }

    /** Retrieves the current frame of the window that the application sees. */
    Rect getFrame() {
        return mWindowFrames.mFrame;
    }

    /** Accessor for testing */
    Rect getRelativeFrame() {
        return mWindowFrames.mRelFrame;
    }

    /**
     * Gets the frame that excludes the area of side insets according to the layout parameter from
     * {@link WindowManager.LayoutParams#setFitInsetsSides}.
     */
    Rect getDisplayFrame() {
        return mWindowFrames.mDisplayFrame;
    }

    Rect getParentFrame() {
        return mWindowFrames.mParentFrame;
    }

    Rect getContainingFrame() {
        return mWindowFrames.mContainingFrame;
    }

    void getCompatFrameSize(Rect outFrame) {
        outFrame.set(0, 0, mWindowFrames.mCompatFrame.width(), mWindowFrames.mCompatFrame.height());
    }

    @Override
    public WindowManager.LayoutParams getAttrs() {
        return mAttrs;
    }

    /** Retrieves the flags used to disable system UI functions. */
    int getDisableFlags() {
        return mDisableFlags;
    }

    /** Gets the layer at which this window's surface will be Z-ordered. */
    int getSurfaceLayer() {
        return mLayer;
    }

    @Override
    public int getBaseType() {
        return getTopParentWindow().mAttrs.type;
    }

    @Override
    public IApplicationToken getAppToken() {
        return mActivityRecord != null ? mActivityRecord.appToken : null;
    }

    /** Returns true if this window is participating in voice interaction. */
    boolean isVoiceInteraction() {
        return mActivityRecord != null && mActivityRecord.mVoiceInteraction;
    }

    boolean setReportResizeHints() {
        return mWindowFrames.setReportResizeHints();
    }

    /**
     * Adds the window to the resizing list if any of the parameters we use to track the window
     * dimensions or insets have changed.
     */
    void updateResizingWindowIfNeeded() {
        final WindowStateAnimator winAnimator = mWinAnimator;
        if (!mHasSurface || getDisplayContent().mLayoutSeq != mLayoutSeq || isGoneForLayout()) {
            return;
        }

        boolean didFrameInsetsChange = setReportResizeHints();
        boolean configChanged = !isLastConfigReportedToClient();
        if (DEBUG_CONFIGURATION && configChanged) {
            Slog.v(TAG_WM, "Win " + this + " config changed: " + getConfiguration());
        }

        final boolean dragResizingChanged = isDragResizeChanged()
                && !isDragResizingChangeReported();

        if (DEBUG) {
            Slog.v(TAG_WM, "Resizing " + this + ": configChanged=" + configChanged
                    + " dragResizingChanged=" + dragResizingChanged
                    + " last=" + mWindowFrames.mLastFrame + " frame=" + mWindowFrames.mFrame);
        }

        // We update mLastFrame always rather than in the conditional with the last inset
        // variables, because mFrameSizeChanged only tracks the width and height changing.
        updateLastFrames();

        // Add a window that is using blastSync to the resizing list if it hasn't been reported
        // already. This because the window is waiting on a finishDrawing from the client.
        if (didFrameInsetsChange
                || configChanged
                || dragResizingChanged
                || mReportOrientationChanged
                || shouldSendRedrawForSync()) {
            ProtoLog.v(WM_DEBUG_RESIZE,
                        "Resize reasons for w=%s:  %s configChanged=%b "
                                + "dragResizingChanged=%b reportOrientationChanged=%b",
                        this, mWindowFrames.getInsetsChangedInfo(),
                        configChanged, dragResizingChanged, mReportOrientationChanged);

            // If it's a dead window left on screen, and the configuration changed, there is nothing
            // we can do about it. Remove the window now.
            if (mActivityRecord != null && mAppDied) {
                mActivityRecord.removeDeadWindows();
                return;
            }

            onResizeHandled();
            mWmService.makeWindowFreezingScreenIfNeededLocked(this);

            // If the orientation is changing, or we're starting or ending a drag resizing action,
            // then we need to hold off on unfreezing the display until this window has been
            // redrawn; to do that, we need to go through the process of getting informed by the
            // application when it has finished drawing.
            if (getOrientationChanging() || dragResizingChanged) {
                if (dragResizingChanged) {
                    ProtoLog.v(WM_DEBUG_RESIZE,
                            "Resize start waiting for draw, "
                                    + "mDrawState=DRAW_PENDING in %s, surfaceController %s",
                            this, winAnimator.mSurfaceController);
                }
                winAnimator.mDrawState = DRAW_PENDING;
                if (mActivityRecord != null) {
                    mActivityRecord.clearAllDrawn();
                }
            }
            if (!mWmService.mResizingWindows.contains(this)) {
                ProtoLog.v(WM_DEBUG_RESIZE, "Resizing window %s", this);
                mWmService.mResizingWindows.add(this);
            }
        } else if (getOrientationChanging()) {
            if (isDrawn()) {
                ProtoLog.v(WM_DEBUG_ORIENTATION,
                        "Orientation not waiting for draw in %s, surfaceController %s", this,
                        winAnimator.mSurfaceController);
                setOrientationChanging(false);
                mLastFreezeDuration = (int)(SystemClock.elapsedRealtime()
                        - mWmService.mDisplayFreezeTime);
            }
        }
    }

    boolean getOrientationChanging() {
        // In addition to the local state flag, we must also consider the difference in the last
        // reported configuration vs. the current state. If the client code has not been informed of
        // the change, logic dependent on having finished processing the orientation, such as
        // unfreezing, could be improperly triggered.
        // TODO(b/62846907): Checking against {@link mLastReportedConfiguration} could be flaky as
        //                   this is not necessarily what the client has processed yet. Find a
        //                   better indicator consistent with the client.
        return (mOrientationChanging || (isVisible()
                && getConfiguration().orientation != getLastReportedConfiguration().orientation))
                && !mSeamlesslyRotated
                && !mOrientationChangeTimedOut;
    }

    void setOrientationChanging(boolean changing) {
        mOrientationChangeTimedOut = false;
        if (mOrientationChanging == changing) {
            return;
        }
        mOrientationChanging = changing;
        if (changing) {
            mLastFreezeDuration = 0;
            if (mWmService.mRoot.mOrientationChangeComplete
                    && mDisplayContent.waitForUnfreeze(this)) {
                mWmService.mRoot.mOrientationChangeComplete = false;
            }
        } else {
            // The orientation change is completed. If it was hidden by the animation, reshow it.
            mDisplayContent.finishFadeRotationAnimation(this);
        }
    }

    void orientationChangeTimedOut() {
        mOrientationChangeTimedOut = true;
    }

    @Override
    DisplayContent getDisplayContent() {
        return mToken.getDisplayContent();
    }

    @Override
    void onDisplayChanged(DisplayContent dc) {
        if (dc != null && mDisplayContent != null && dc != mDisplayContent
                && getImeInputTarget() == this) {
            dc.updateImeInputAndControlTarget(getImeInputTarget());
            mDisplayContent.setImeInputTarget(null);
        }
        super.onDisplayChanged(dc);
        // Window was not laid out for this display yet, so make sure mLayoutSeq does not match.
        if (dc != null && mInputWindowHandle.getDisplayId() != dc.getDisplayId()) {
            mLayoutSeq = dc.mLayoutSeq - 1;
            mInputWindowHandle.setDisplayId(dc.getDisplayId());
        }
    }

    /** @return The display frames in use by this window. */
    DisplayFrames getDisplayFrames(DisplayFrames originalFrames) {
        final DisplayFrames diplayFrames = mToken.getFixedRotationTransformDisplayFrames();
        if (diplayFrames != null) {
            return diplayFrames;
        }
        return originalFrames;
    }

    DisplayInfo getDisplayInfo() {
        final DisplayInfo displayInfo = mToken.getFixedRotationTransformDisplayInfo();
        if (displayInfo != null) {
            return displayInfo;
        }
        return getDisplayContent().getDisplayInfo();
    }

    /**
     * Returns the insets state for the window. Its sources may be the copies with visibility
     * modification according to the state of transient bars.
     */
    InsetsState getInsetsState() {
        return getDisplayContent().getInsetsPolicy().getInsetsForWindow(this);
    }

    /**
     * Returns the insets state for the client and scales the frames if the client is in the size
     * compatible mode.
     */
    InsetsState getCompatInsetsState() {
        InsetsState state = getInsetsState();
        if (hasCompatScale()) {
            state = new InsetsState(state, true);
            state.scale(mInvGlobalScale);
        }
        return state;
    }

    /**
     * Returns the insets state for the window and applies the requested visibility.
     */
    InsetsState getInsetsStateWithVisibilityOverride() {
        final InsetsState state = new InsetsState(getInsetsState());
        for (@InternalInsetsType int type = 0; type < InsetsState.SIZE; type++) {
            final boolean requestedVisible = getRequestedVisibility(type);
            InsetsSource source = state.peekSource(type);
            if (source != null && source.isVisible() != requestedVisible) {
                source = new InsetsSource(source);
                source.setVisible(requestedVisible);
                state.addSource(source);
            }
        }
        return state;
    }

    int getDisplayId() {
        final DisplayContent displayContent = getDisplayContent();
        if (displayContent == null) {
            return Display.INVALID_DISPLAY;
        }
        return displayContent.getDisplayId();
    }

    Task getTask() {
        return mActivityRecord != null ? mActivityRecord.getTask() : null;
    }

    @Nullable Task getRootTask() {
        final Task task = getTask();
        if (task != null) {
            return task.getRootTask();
        }
        // Some system windows (e.g. "Power off" dialog) don't have a task, but we would still
        // associate them with some root task to enable dimming.
        final DisplayContent dc = getDisplayContent();
        return mAttrs.type >= FIRST_SYSTEM_WINDOW
                && dc != null ? dc.getDefaultTaskDisplayArea().getRootHomeTask() : null;
    }

    /**
     * This is a form of rectangle "difference". It cut off each dimension of rect by the amount
     * that toRemove is "pushing into" it from the outside. Any dimension that fully contains
     * toRemove won't change.
     */
    private void cutRect(Rect rect, Rect toRemove) {
        if (toRemove.isEmpty()) return;
        if (toRemove.top < rect.bottom && toRemove.bottom > rect.top) {
            if (toRemove.right >= rect.right && toRemove.left >= rect.left) {
                rect.right = toRemove.left;
            } else if (toRemove.left <= rect.left && toRemove.right <= rect.right) {
                rect.left = toRemove.right;
            }
        }
        if (toRemove.left < rect.right && toRemove.right > rect.left) {
            if (toRemove.bottom >= rect.bottom && toRemove.top >= rect.top) {
                rect.bottom = toRemove.top;
            } else if (toRemove.top <= rect.top && toRemove.bottom <= rect.bottom) {
                rect.top = toRemove.bottom;
            }
        }
    }

    /**
     * Retrieves the visible bounds of the window.
     * @param bounds The rect which gets the bounds.
     */
    void getVisibleBounds(Rect bounds) {
        final Task task = getTask();
        boolean intersectWithRootTaskBounds = task != null && task.cropWindowsToRootTaskBounds();
        bounds.setEmpty();
        mTmpRect.setEmpty();
        if (intersectWithRootTaskBounds) {
            final Task rootTask = task.getRootTask();
            if (rootTask != null) {
                rootTask.getDimBounds(mTmpRect);
            } else {
                intersectWithRootTaskBounds = false;
            }
            if (inSplitScreenPrimaryWindowingMode()) {
                // If this is in the primary split and the root home task is the top visible task in
                // the secondary split, it means this is "minimized" and thus must prevent
                // overlapping with home.
                // TODO(b/158242495): get rid of this when drag/drop can use surface bounds.
                final Task rootSecondary =
                        task.getDisplayArea().getRootSplitScreenSecondaryTask();
                if (rootSecondary.isActivityTypeHome() || rootSecondary.isActivityTypeRecents()) {
                    final WindowContainer topTask = rootSecondary.getTopChild();
                    if (topTask.isVisible()) {
                        cutRect(mTmpRect, topTask.getBounds());
                    }
                }
            }
        }

        bounds.set(mWindowFrames.mFrame);
        bounds.inset(getInsetsStateWithVisibilityOverride().calculateVisibleInsets(
                bounds, mAttrs.softInputMode));
        if (intersectWithRootTaskBounds) {
            bounds.intersect(mTmpRect);
        }
    }

    public long getInputDispatchingTimeoutMillis() {
        return mActivityRecord != null
                ? mActivityRecord.mInputDispatchingTimeoutMillis
                : DEFAULT_DISPATCHING_TIMEOUT_MILLIS;
    }

    /**
     * Returns true if, at any point, the application token associated with this window has actually
     * displayed any windows. This is most useful with the "starting up" window to determine if any
     * windows were displayed when it is closed.
     *
     * @return {@code true} if one or more windows have been displayed, else false.
     */
    boolean hasAppShownWindows() {
        return mActivityRecord != null
                && (mActivityRecord.firstWindowDrawn || mActivityRecord.startingDisplayed);
    }

    boolean isIdentityMatrix(float dsdx, float dtdx, float dsdy, float dtdy) {
        if (dsdx < .99999f || dsdx > 1.00001f) return false;
        if (dtdy < .99999f || dtdy > 1.00001f) return false;
        if (dtdx < -.000001f || dtdx > .000001f) return false;
        if (dsdy < -.000001f || dsdy > .000001f) return false;
        return true;
    }

    void prelayout() {
        if (hasCompatScale()) {
            if (mOverrideScale != 1f) {
                mGlobalScale = mToken.hasSizeCompatBounds()
                        ? mToken.getSizeCompatScale() * mOverrideScale
                        : mOverrideScale;
            } else {
                mGlobalScale = mToken.getSizeCompatScale();
            }
            mInvGlobalScale = 1 / mGlobalScale;
        } else {
            mGlobalScale = mInvGlobalScale = 1;
        }
    }

    @Override
    boolean hasContentToDisplay() {
        if (!mAppFreezing && isDrawn() && (mViewVisibility == View.VISIBLE
                || (isAnimating(TRANSITION | PARENTS)
                && !getDisplayContent().mAppTransition.isTransitionSet()))) {
            return true;
        }

        return super.hasContentToDisplay();
    }

    @Override
    boolean isVisible() {
        return wouldBeVisibleIfPolicyIgnored() && isVisibleByPolicy()
                // If we don't have a provider, this window isn't used as a window generating
                // insets, so nobody can hide it over the inset APIs.
                && (mControllableInsetProvider == null
                        || mControllableInsetProvider.isClientVisible());
    }

    @Override
    boolean isVisibleRequested() {
        if (mToken != null && (mActivityRecord != null || mToken.asWallpaperToken() != null)) {
            // Currently only ActivityRecord and WallpaperToken support visibleRequested.
            return isVisible() && mToken.isVisibleRequested();
        }
        return isVisible();
    }

    /**
     * Ensures that all the policy visibility bits are set.
     * @return {@code true} if all flags about visiblity are set
     */
    boolean isVisibleByPolicy() {
        return (mPolicyVisibility & POLICY_VISIBILITY_ALL) == POLICY_VISIBILITY_ALL;
    }

    void clearPolicyVisibilityFlag(int policyVisibilityFlag) {
        mPolicyVisibility &= ~policyVisibilityFlag;
        mWmService.scheduleAnimationLocked();
    }

    void setPolicyVisibilityFlag(int policyVisibilityFlag) {
        mPolicyVisibility |= policyVisibilityFlag;
        mWmService.scheduleAnimationLocked();
    }

    private boolean isLegacyPolicyVisibility() {
        return (mPolicyVisibility & LEGACY_POLICY_VISIBILITY) != 0;
    }

    /**
     * @return {@code true} if the window would be visible if we'd ignore policy visibility,
     *         {@code false} otherwise.
     */
    boolean wouldBeVisibleIfPolicyIgnored() {
        if (!mHasSurface || isParentWindowHidden() || mAnimatingExit || mDestroying) {
            return false;
        }
        final boolean isWallpaper = mToken != null && mToken.asWallpaperToken() != null;
        return !isWallpaper || mToken.isVisible();
    }

    /**
     * Is this window visible, ignoring its app token? It is not visible if there is no surface,
     * or we are in the process of running an exit animation that will remove the surface.
     */
    // TODO: Can we consolidate this with #isVisible() or have a more appropriate name for this?
    boolean isWinVisibleLw() {
        return (mActivityRecord == null || mActivityRecord.mVisibleRequested
                || mActivityRecord.isAnimating(TRANSITION | PARENTS)) && isVisible();
    }

    /**
     * The same as isVisible(), but follows the current hidden state of the associated app token,
     * not the pending requested hidden state.
     */
    boolean isVisibleNow() {
        return (mToken.isVisible() || mAttrs.type == TYPE_APPLICATION_STARTING)
                && isVisible();
    }

    /**
     * Can this window possibly be a drag/drop target?  The test here is
     * a combination of the above "visible now" with the check that the
     * Input Manager uses when discarding windows from input consideration.
     */
    boolean isPotentialDragTarget(boolean targetInterceptsGlobalDrag) {
        return (targetInterceptsGlobalDrag || isVisibleNow()) && !mRemoved
                && mInputChannel != null && mInputWindowHandle != null;
    }

    /**
     * Same as isVisible(), but we also count it as visible between the
     * call to IWindowSession.add() and the first relayout().
     */
    boolean isVisibleOrAdding() {
        final ActivityRecord atoken = mActivityRecord;
        return (mHasSurface || (!mRelayoutCalled && mViewVisibility == View.VISIBLE))
                && isVisibleByPolicy() && !isParentWindowHidden()
                && (atoken == null || atoken.mVisibleRequested)
                && !mAnimatingExit && !mDestroying;
    }

    /**
     * Is this window currently on-screen?  It is on-screen either if it
     * is visible or it is currently running an animation before no longer
     * being visible.
     */
    boolean isOnScreen() {
        if (!mHasSurface || mDestroying || !isVisibleByPolicy()) {
            return false;
        }
        final ActivityRecord atoken = mActivityRecord;
        if (atoken != null) {
            return ((!isParentWindowHidden() && atoken.isVisible())
                    || isAnimating(TRANSITION | PARENTS));
        }
        final WallpaperWindowToken wtoken = mToken.asWallpaperToken();
        if (wtoken != null) {
            return !isParentWindowHidden() && wtoken.isVisible();
        }
        return !isParentWindowHidden() || isAnimating(TRANSITION | PARENTS);
    }

    boolean isDreamWindow() {
        return mActivityRecord != null
               && mActivityRecord.getActivityType() == ACTIVITY_TYPE_DREAM;
    }

    boolean isSecureLocked() {
        if ((mAttrs.flags & WindowManager.LayoutParams.FLAG_SECURE) != 0) {
            return true;
        }
        return !DevicePolicyCache.getInstance().isScreenCaptureAllowed(mShowUserId,
                mOwnerCanAddInternalSystemWindow);
    }

    /**
     * Whether this window's drawn state might affect the drawn states of the app token.
     *
     * @return true if the window should be considered while evaluating allDrawn flags.
     */
    boolean mightAffectAllDrawn() {
        final boolean isAppType = mWinAnimator.mAttrType == TYPE_BASE_APPLICATION
                || mWinAnimator.mAttrType == TYPE_DRAWN_APPLICATION;
        return (isOnScreen() || isAppType) && !mAnimatingExit && !mDestroying;
    }

    /**
     * Whether this window is "interesting" when evaluating allDrawn. If it's interesting,
     * it must be drawn before allDrawn can become true.
     */
    boolean isInteresting() {
        return mActivityRecord != null && !mAppDied
                && (!mActivityRecord.isFreezingScreen() || !mAppFreezing)
                && mViewVisibility == View.VISIBLE;
    }

    /**
     * Like isOnScreen(), but we don't return true if the window is part
     * of a transition that has not yet been started.
     */
    boolean isReadyForDisplay() {
        if (mToken.waitingToShow && getDisplayContent().mAppTransition.isTransitionSet()) {
            return false;
        }
        final boolean parentAndClientVisible = !isParentWindowHidden()
                && mViewVisibility == View.VISIBLE && mToken.isVisible();
        return mHasSurface && isVisibleByPolicy() && !mDestroying
                && (parentAndClientVisible || isAnimating(TRANSITION | PARENTS));
    }

    boolean isFullyTransparent() {
        return mAttrs.alpha == 0f;
    }

    /**
     * @return Whether the window can affect SystemUI flags, meaning that SystemUI (system bars,
     *         for example) will be  affected by the flags specified in this window. This is the
     *         case when the surface is on screen but not exiting.
     */
    boolean canAffectSystemUiFlags() {
        if (isFullyTransparent()) {
            return false;
        }
        if (mActivityRecord == null) {
            final boolean shown = mWinAnimator.getShown();
            final boolean exiting = mAnimatingExit || mDestroying;
            return shown && !exiting;
        } else {
            final Task task = getTask();
            final boolean canFromTask = task != null && task.canAffectSystemUiFlags();
            return canFromTask && mActivityRecord.isVisible();
        }
    }

    /**
     * Like isOnScreen, but returns false if the surface hasn't yet
     * been drawn.
     */
    boolean isDisplayed() {
        final ActivityRecord atoken = mActivityRecord;
        return isDrawn() && isVisibleByPolicy()
                && ((!isParentWindowHidden() && (atoken == null || atoken.mVisibleRequested))
                        || isAnimating(TRANSITION | PARENTS));
    }

    /**
     * Return true if this window or its app token is currently animating.
     */
    @Override
    public boolean isAnimatingLw() {
        return isAnimating(TRANSITION | PARENTS);
    }

    /** Returns {@code true} if this window considered to be gone for purposes of layout. */
    boolean isGoneForLayout() {
        final ActivityRecord atoken = mActivityRecord;
        return mViewVisibility == View.GONE
                || !mRelayoutCalled
                // We can't check isVisible here because it will also check the client visibility
                // for WindowTokens. Even if the client is not visible, we still need to perform
                // a layout since they can request relayout when client visibility is false.
                // TODO (b/157682066) investigate if we can clean up isVisible
                || (atoken == null && !(wouldBeVisibleIfPolicyIgnored() && isVisibleByPolicy()))
                || (atoken != null && !atoken.mVisibleRequested)
                || isParentWindowGoneForLayout()
                || (mAnimatingExit && !isAnimatingLw())
                || mDestroying;
    }

    /**
     * Returns true if the window has a surface that it has drawn a
     * complete UI in to.
     */
    public boolean isDrawFinishedLw() {
        return mHasSurface && !mDestroying &&
                (mWinAnimator.mDrawState == COMMIT_DRAW_PENDING
                || mWinAnimator.mDrawState == READY_TO_SHOW
                || mWinAnimator.mDrawState == HAS_DRAWN);
    }

    /**
     * Returns true if the window has a surface that it has drawn a complete UI in to. Note that
     * this is different from {@link #hasDrawn()} in that it also returns true if the window is
     * READY_TO_SHOW, but was not yet promoted to HAS_DRAWN.
     */
    boolean isDrawn() {
        return mHasSurface && !mDestroying &&
                (mWinAnimator.mDrawState == READY_TO_SHOW || mWinAnimator.mDrawState == HAS_DRAWN);
    }

    /**
     * Return true if the window is opaque and fully drawn.  This indicates
     * it may obscure windows behind it.
     */
    private boolean isOpaqueDrawn() {
        // When there is keyguard, wallpaper could be placed over the secure app
        // window but invisible. We need to check wallpaper visibility explicitly
        // to determine if it's occluding apps.
        final boolean isWallpaper = mToken != null && mToken.asWallpaperToken() != null;
        return ((!isWallpaper && mAttrs.format == PixelFormat.OPAQUE)
                || (isWallpaper && mToken.isVisible()))
                && isDrawn() && !isAnimating(TRANSITION | PARENTS);
    }

    /** @see WindowManagerInternal#waitForAllWindowsDrawn */
    void requestDrawIfNeeded(List<WindowState> outWaitingForDrawn) {
        if (!isVisible()) {
            return;
        }
        if (mActivityRecord != null) {
            if (mActivityRecord.allDrawn) {
                // The allDrawn of activity is reset when the visibility is changed to visible, so
                // the content should be ready if allDrawn is set.
                return;
            }
            if (mAttrs.type == TYPE_APPLICATION_STARTING) {
                if (isDrawn()) {
                    // Unnecessary to redraw a drawn starting window.
                    return;
                }
            } else if (mActivityRecord.mStartingWindow != null) {
                // If the activity has an active starting window, there is no need to wait for the
                // main window.
                return;
            }
        } else if (!mPolicy.isKeyguardHostWindow(mAttrs)) {
            return;
            // Always invalidate keyguard host window to make sure it shows the latest content
            // because its visibility may not be changed.
        }

        mWinAnimator.mDrawState = DRAW_PENDING;
        // Force add to {@link WindowManagerService#mResizingWindows}.
        forceReportingResized();
        outWaitingForDrawn.add(this);
    }

    @Override
    void onMovedByResize() {
        ProtoLog.d(WM_DEBUG_RESIZE, "onMovedByResize: Moving %s", this);
        mMovedByResize = true;
        super.onMovedByResize();
    }

    void onAppVisibilityChanged(boolean visible, boolean runningAppAnimation) {
        for (int i = mChildren.size() - 1; i >= 0; --i) {
            mChildren.get(i).onAppVisibilityChanged(visible, runningAppAnimation);
        }

        final boolean isVisibleNow = isVisibleNow();
        if (mAttrs.type == TYPE_APPLICATION_STARTING) {
            // Starting window that's exiting will be removed when the animation finishes.
            // Mark all relevant flags for that onExitAnimationDone will proceed all the way
            // to actually remove it.
            if (!visible && isVisibleNow && mActivityRecord.isAnimating(PARENTS | TRANSITION)) {
                mAnimatingExit = true;
                mRemoveOnExit = true;
                mWindowRemovalAllowed = true;
            }
        } else if (visible != isVisibleNow) {
            // Run exit animation if:
            // 1. App visibility and WS visibility are different
            // 2. App is not running an animation
            // 3. WS is currently visible
            if (!runningAppAnimation && isVisibleNow) {
                final AccessibilityController accessibilityController =
                        mWmService.mAccessibilityController;
                final int winTransit = TRANSIT_EXIT;
                mWinAnimator.applyAnimationLocked(winTransit, false /* isEntrance */);
                if (accessibilityController != null) {
                    accessibilityController.onWindowTransition(this, winTransit);
                }
            }
            setDisplayLayoutNeeded();
        }
    }

    boolean onSetAppExiting() {
        final DisplayContent displayContent = getDisplayContent();
        boolean changed = false;

        if (isVisibleNow()) {
            mWinAnimator.applyAnimationLocked(TRANSIT_EXIT, false);
            if (mWmService.mAccessibilityController != null) {
                mWmService.mAccessibilityController.onWindowTransition(this, TRANSIT_EXIT);
            }
            changed = true;
            if (displayContent != null) {
                displayContent.setLayoutNeeded();
            }
        }

        for (int i = mChildren.size() - 1; i >= 0; --i) {
            final WindowState c = mChildren.get(i);
            changed |= c.onSetAppExiting();
        }

        return changed;
    }

    @Override
    void onResize() {
        final ArrayList<WindowState> resizingWindows = mWmService.mResizingWindows;
        if (mHasSurface && !isGoneForLayout() && !resizingWindows.contains(this)) {
            ProtoLog.d(WM_DEBUG_RESIZE, "onResize: Resizing %s", this);
            resizingWindows.add(this);
        }
        if (isGoneForLayout()) {
            mResizedWhileGone = true;
        }

        super.onResize();
    }

    /**
     * If the window has moved due to its containing content frame changing, then notify the
     * listeners and optionally animate it. Simply checking a change of position is not enough,
     * because being move due to dock divider is not a trigger for animation.
     */
    void handleWindowMovedIfNeeded() {
        if (!hasMoved()) {
            return;
        }

        // Frame has moved, containing content frame has also moved, and we're not currently
        // animating... let's do something.
        final int left = mWindowFrames.mFrame.left;
        final int top = mWindowFrames.mFrame.top;

        // During the transition from pip to fullscreen, the activity windowing mode is set to
        // fullscreen at the beginning while the task is kept in pinned mode. Skip the move
        // animation in such case since the transition is handled in SysUI.
        final boolean hasMovementAnimation = getTask() == null
                ? getWindowConfiguration().hasMovementAnimations()
                : getTask().getWindowConfiguration().hasMovementAnimations();
        if (mToken.okToAnimate()
                && (mAttrs.privateFlags & PRIVATE_FLAG_NO_MOVE_ANIMATION) == 0
                && !isDragResizing()
                && hasMovementAnimation
                && !mWinAnimator.mLastHidden
                && !mSeamlesslyRotated) {
            startMoveAnimation(left, top);
        }

        if (mWmService.mAccessibilityController != null) {
            mWmService.mAccessibilityController.onSomeWindowResizedOrMoved(getDisplayId());
        }
        updateLocationInParentDisplayIfNeeded();

        try {
            mClient.moved(left, top);
        } catch (RemoteException e) {
        }
        mMovedByResize = false;
    }

    /**
     * Return whether this window has moved. (Only makes
     * sense to call from performLayoutAndPlaceSurfacesLockedInner().)
     */
    private boolean hasMoved() {
        return mHasSurface && (mWindowFrames.hasContentChanged() || mMovedByResize)
                && !mAnimatingExit
                && (mWindowFrames.mRelFrame.top != mWindowFrames.mLastRelFrame.top
                    || mWindowFrames.mRelFrame.left != mWindowFrames.mLastRelFrame.left)
                && (!mIsChildWindow || !getParentWindow().hasMoved())
                && !mWmService.mAtmService.getTransitionController().isCollecting();
    }

    boolean isObscuringDisplay() {
        Task task = getTask();
        if (task != null && task.getRootTask() != null && !task.getRootTask().fillsParent()) {
            return false;
        }
        return isOpaqueDrawn() && fillsDisplay();
    }

    boolean fillsDisplay() {
        final DisplayInfo displayInfo = getDisplayInfo();
        return mWindowFrames.mFrame.left <= 0 && mWindowFrames.mFrame.top <= 0
                && mWindowFrames.mFrame.right >= displayInfo.appWidth
                && mWindowFrames.mFrame.bottom >= displayInfo.appHeight;
    }

    private boolean matchesDisplayBounds() {
        final Rect displayBounds = mToken.getFixedRotationTransformDisplayBounds();
        if (displayBounds != null) {
            // If the rotated display bounds are available, the window bounds are also rotated.
            return displayBounds.equals(getBounds());
        }
        return getDisplayContent().getBounds().equals(getBounds());
    }

    boolean matchesDisplayAreaBounds() {
        final DisplayArea displayArea = getDisplayArea();
        if (displayArea == null) {
            return matchesDisplayBounds();
        }
        return displayArea.getBounds().equals(getBounds());
    }

    /**
     * @return {@code true} if last applied config was reported to the client already, {@code false}
     *         otherwise.
     */
    boolean isLastConfigReportedToClient() {
        return mLastConfigReportedToClient;
    }

    @Override
    void onMergedOverrideConfigurationChanged() {
        super.onMergedOverrideConfigurationChanged();
        mLastConfigReportedToClient = false;
    }

    void onWindowReplacementTimeout() {
        if (mWillReplaceWindow) {
            // Since the window already timed out, remove it immediately now.
            // Use WindowState#removeImmediately() instead of WindowState#removeIfPossible(), as
            // the latter delays removal on certain conditions, which will leave the stale window
            // in the root task and marked mWillReplaceWindow=false, so the window will never be
            // removed.
            //
            // Also removes child windows.
            removeImmediately();
        } else {
            for (int i = mChildren.size() - 1; i >= 0; --i) {
                final WindowState c = mChildren.get(i);
                c.onWindowReplacementTimeout();
            }
        }
    }

    @Override
    void removeImmediately() {
        super.removeImmediately();

        if (mRemoved) {
            // Nothing to do.
            ProtoLog.v(WM_DEBUG_ADD_REMOVE,
                    "WS.removeImmediately: %s Already removed...", this);
            return;
        }

        mRemoved = true;

        mWillReplaceWindow = false;
        if (mReplacementWindow != null) {
            mReplacementWindow.mSkipEnterAnimationForSeamlessReplacement = false;
        }

        final DisplayContent dc = getDisplayContent();
        if (isImeLayeringTarget()) {
            // Remove the IME screenshot surface if the layering target is not animating.
            dc.removeImeScreenshotIfPossible();
            // Make sure to set mImeLayeringTarget as null when the removed window is the
            // IME target, in case computeImeTarget may use the outdated target.
            dc.setImeLayeringTarget(null);
            dc.computeImeTarget(true /* updateImeTarget */);
        }
        if (dc.getImeTarget(IME_TARGET_INPUT) == this) {
            dc.updateImeInputAndControlTarget(null);
        }

        final int type = mAttrs.type;
        if (WindowManagerService.excludeWindowTypeFromTapOutTask(type)) {
            dc.mTapExcludedWindows.remove(this);
        }

        // Remove this window from mTapExcludeProvidingWindows. If it was not registered, this will
        // not do anything.
        dc.mTapExcludeProvidingWindows.remove(this);
        dc.getDisplayPolicy().removeWindowLw(this);

        disposeInputChannel();

        mWinAnimator.destroySurfaceLocked(mTmpTransaction);
        mTmpTransaction.apply();
        mSession.windowRemovedLocked();
        try {
            mClient.asBinder().unlinkToDeath(mDeathRecipient, 0);
        } catch (RuntimeException e) {
            // Ignore if it has already been removed (usually because
            // we are doing this as part of processing a death note.)
        }

        mWmService.postWindowRemoveCleanupLocked(this);
    }

    @Override
    void removeIfPossible() {
        super.removeIfPossible();
        removeIfPossible(false /*keepVisibleDeadWindow*/);
    }

    private void removeIfPossible(boolean keepVisibleDeadWindow) {
        mWindowRemovalAllowed = true;
        ProtoLog.v(WM_DEBUG_ADD_REMOVE,
                "removeIfPossible: %s callers=%s", this, Debug.getCallers(5));

        final boolean startingWindow = mAttrs.type == TYPE_APPLICATION_STARTING;
        if (startingWindow) {
            ProtoLog.d(WM_DEBUG_STARTING_WINDOW, "Starting window removed %s", this);
        }

        ProtoLog.v(WM_DEBUG_FOCUS, "Remove client=%x, surfaceController=%s Callers=%s",
                    System.identityHashCode(mClient.asBinder()),
                    mWinAnimator.mSurfaceController,
                    Debug.getCallers(5));


        final long origId = Binder.clearCallingIdentity();

        try {
            disposeInputChannel();

            ProtoLog.v(WM_DEBUG_APP_TRANSITIONS,
                    "Remove %s: mSurfaceController=%s mAnimatingExit=%b mRemoveOnExit=%b "
                            + "mHasSurface=%b surfaceShowing=%b animating=%b app-animation=%b "
                            + "mWillReplaceWindow=%b mDisplayFrozen=%b callers=%s",
                    this, mWinAnimator.mSurfaceController, mAnimatingExit, mRemoveOnExit,
                    mHasSurface, mWinAnimator.getShown(),
                    isAnimating(TRANSITION | PARENTS),
                    mActivityRecord != null && mActivityRecord.isAnimating(PARENTS | TRANSITION),
                    mWillReplaceWindow,
                    mWmService.mDisplayFrozen, Debug.getCallers(6));

            // Visibility of the removed window. Will be used later to update orientation later on.
            boolean wasVisible = false;

            // First, see if we need to run an animation. If we do, we have to hold off on removing the
            // window until the animation is done. If the display is frozen, just remove immediately,
            // since the animation wouldn't be seen.
            if (mHasSurface && mToken.okToAnimate()) {
                if (mWillReplaceWindow) {
                    // This window is going to be replaced. We need to keep it around until the new one
                    // gets added, then we will get rid of this one.
                    ProtoLog.v(WM_DEBUG_ADD_REMOVE,
                            "Preserving %s until the new one is added", this);
                    // TODO: We are overloading mAnimatingExit flag to prevent the window state from
                    // been removed. We probably need another flag to indicate that window removal
                    // should be deffered vs. overloading the flag that says we are playing an exit
                    // animation.
                    mAnimatingExit = true;
                    mReplacingRemoveRequested = true;
                    return;
                }

                // If we are not currently running the exit animation, we need to see about starting one
                wasVisible = isWinVisibleLw();

                if (keepVisibleDeadWindow) {
                    ProtoLog.v(WM_DEBUG_ADD_REMOVE,
                            "Not removing %s because app died while it's visible", this);

                    mAppDied = true;
                    setDisplayLayoutNeeded();
                    mWmService.mWindowPlacerLocked.performSurfacePlacement();

                    // Set up a replacement input channel since the app is now dead.
                    // We need to catch tapping on the dead window to restart the app.
                    openInputChannel(null);
                    getDisplayContent().getInputMonitor().updateInputWindowsLw(true /*force*/);
                    return;
                }

                if (wasVisible) {
                    final int transit = (!startingWindow) ? TRANSIT_EXIT : TRANSIT_PREVIEW_DONE;

                    // Try starting an animation.
                    if (mWinAnimator.applyAnimationLocked(transit, false)) {
                        mAnimatingExit = true;

                        // mAnimatingExit affects canAffectSystemUiFlags(). Run layout such that
                        // any change from that is performed immediately.
                        setDisplayLayoutNeeded();
                        mWmService.requestTraversal();
                    }
                    if (mWmService.mAccessibilityController != null) {
                        mWmService.mAccessibilityController.onWindowTransition(this, transit);
                    }
                }
                final boolean isAnimating = mAnimatingExit || isAnimating(TRANSITION | PARENTS,
                        ANIMATION_TYPE_APP_TRANSITION | ANIMATION_TYPE_WINDOW_ANIMATION)
                        && (mActivityRecord == null || !mActivityRecord.isWaitingForTransitionStart());
                final boolean lastWindowIsStartingWindow = startingWindow && mActivityRecord != null
                        && mActivityRecord.isLastWindow(this);
                // We delay the removal of a window if it has a showing surface that can be used to run
                // exit animation and it is marked as exiting.
                // Also, If isn't the an animating starting window that is the last window in the app.
                // We allow the removal of the non-animating starting window now as there is no
                // additional window or animation that will trigger its removal.
                if (mWinAnimator.getShown() && !lastWindowIsStartingWindow && isAnimating) {
                    // Make isSelfOrAncestorWindowAnimatingExit return true so onExitAnimationDone
                    // can proceed to remove this window.
                    mAnimatingExit = true;
                    // The exit animation is running or should run... wait for it!
                    ProtoLog.v(WM_DEBUG_ADD_REMOVE,
                            "Not removing %s due to exit animation", this);
                    setupWindowForRemoveOnExit();
                    if (mActivityRecord != null) {
                        mActivityRecord.updateReportedVisibilityLocked();
                    }
                    return;
                }
            }

            removeImmediately();
            // Removing a visible window will effect the computed orientation
            // So just update orientation if needed.
            if (wasVisible) {
                final DisplayContent displayContent = getDisplayContent();
                if (displayContent.updateOrientation()) {
                    displayContent.sendNewConfiguration();
                }
            }
            mWmService.updateFocusedWindowLocked(isFocused()
                            ? UPDATE_FOCUS_REMOVING_FOCUS
                            : UPDATE_FOCUS_NORMAL,
                    true /*updateInputWindows*/);
        } finally {
            Binder.restoreCallingIdentity(origId);
        }
    }

    private void setupWindowForRemoveOnExit() {
        mRemoveOnExit = true;
        setDisplayLayoutNeeded();
        // Request a focus update as this window's input channel is already gone. Otherwise
        // we could have no focused window in input manager.
        final boolean focusChanged = mWmService.updateFocusedWindowLocked(
                UPDATE_FOCUS_WILL_PLACE_SURFACES, false /*updateInputWindows*/);
        mWmService.mWindowPlacerLocked.performSurfacePlacement();
        if (focusChanged) {
            getDisplayContent().getInputMonitor().updateInputWindowsLw(false /*force*/);
        }
    }

    void setHasSurface(boolean hasSurface) {
        mHasSurface = hasSurface;
    }

    /**
     * Checks whether one of the Windows in a Display embedded in this Window can be an IME target.
     */
    private boolean canWindowInEmbeddedDisplayBeImeTarget() {
        final int embeddedDisplayContentsSize = mEmbeddedDisplayContents.size();
        for (int i = embeddedDisplayContentsSize - 1; i >= 0; i--) {
            final DisplayContent edc = mEmbeddedDisplayContents.valueAt(i);
            if (edc.forAllWindows(WindowState::canBeImeTarget, true)) {
                return true;
            }
        }
        return false;
    }

    boolean canBeImeTarget() {
        // If any of the embedded windows can be the IME target, this window will be the final IME
        // target. This is because embedded windows are on a different display in WM so it would
        // cause confusion trying to set the IME to a window on a different display. Instead, just
        // make the host window the IME target.
        if (canWindowInEmbeddedDisplayBeImeTarget()) {
            return true;
        }

        if (mIsImWindow) {
            // IME windows can't be IME targets. IME targets are required to be below the IME
            // windows and that wouldn't be possible if the IME window is its own target...silly.
            return false;
        }

        if (inPinnedWindowingMode()) {
            return false;
        }

        if (mAttrs.type == TYPE_SCREENSHOT) {
            // Disallow screenshot windows from being IME targets
            return false;
        }

        final boolean windowsAreFocusable = mActivityRecord == null || mActivityRecord.windowsAreFocusable();
        if (!windowsAreFocusable) {
            // This window can't be an IME target if the app's windows should not be focusable.
            return false;
        }

        final Task rootTask = getRootTask();
        if (rootTask != null && !rootTask.isFocusable()) {
            // Ignore when the root task shouldn't receive input event.
            // (i.e. the minimized root task in split screen mode.)
            return false;
        }

        if (mAttrs.type == TYPE_APPLICATION_STARTING) {
            // Ignore mayUseInputMethod for starting window for now.
            // TODO(b/159911356): Remove this special casing (originally added in commit e75d872).
        } else {
            // TODO(b/145812508): Clean this up in S, may depend on b/141738570
            //  The current logic lets windows become the "ime target" even though they are
            //  not-focusable and can thus never actually start input.
            //  Ideally, this would reject windows where mayUseInputMethod() == false, but this
            //  also impacts Z-ordering of and delivery of IME insets to child windows, which means
            //  that simply disallowing non-focusable windows would break apps.
            //  See b/159438771, b/144619551.

            final int fl = mAttrs.flags & (FLAG_NOT_FOCUSABLE | FLAG_ALT_FOCUSABLE_IM);

            // Can only be an IME target if both FLAG_NOT_FOCUSABLE and FLAG_ALT_FOCUSABLE_IM are
            // set or both are cleared...and not a starting window.
            if (fl != 0 && fl != (FLAG_NOT_FOCUSABLE | FLAG_ALT_FOCUSABLE_IM)) {
                return false;
            }
        }

        if (DEBUG_INPUT_METHOD) {
            Slog.i(TAG_WM, "isVisibleOrAdding " + this + ": " + isVisibleOrAdding());
            if (!isVisibleOrAdding()) {
                Slog.i(TAG_WM, "  mSurfaceController=" + mWinAnimator.mSurfaceController
                        + " relayoutCalled=" + mRelayoutCalled
                        + " viewVis=" + mViewVisibility
                        + " policyVis=" + isVisibleByPolicy()
                        + " policyVisAfterAnim=" + mLegacyPolicyVisibilityAfterAnim
                        + " parentHidden=" + isParentWindowHidden()
                        + " exiting=" + mAnimatingExit + " destroying=" + mDestroying);
                if (mActivityRecord != null) {
                    Slog.i(TAG_WM, "  mActivityRecord.visibleRequested="
                            + mActivityRecord.mVisibleRequested);
                }
            }
        }
        return isVisibleOrAdding();
    }

    private final class DeadWindowEventReceiver extends InputEventReceiver {
        DeadWindowEventReceiver(InputChannel inputChannel) {
            super(inputChannel, mWmService.mH.getLooper());
        }
        @Override
        public void onInputEvent(InputEvent event) {
            finishInputEvent(event, true);
        }
    }
    /** Fake event receiver for windows that died visible. */
    private DeadWindowEventReceiver mDeadWindowEventReceiver;

    void openInputChannel(InputChannel outInputChannel) {
        if (mInputChannel != null) {
            throw new IllegalStateException("Window already has an input channel.");
        }
        String name = getName();
        mInputChannel = mWmService.mInputManager.createInputChannel(name);
        mInputChannelToken = mInputChannel.getToken();
        mInputWindowHandle.setToken(mInputChannelToken);
        mWmService.mInputToWindowMap.put(mInputChannelToken, this);
        if (outInputChannel != null) {
            mInputChannel.copyTo(outInputChannel);
        } else {
            // If the window died visible, we setup a fake input channel, so that taps
            // can still detected by input monitor channel, and we can relaunch the app.
            // Create fake event receiver that simply reports all events as handled.
            mDeadWindowEventReceiver = new DeadWindowEventReceiver(mInputChannel);
        }
    }

    void disposeInputChannel() {
        if (mDeadWindowEventReceiver != null) {
            mDeadWindowEventReceiver.dispose();
            mDeadWindowEventReceiver = null;
        }
        if (mInputChannelToken != null) {
            // Unregister server channel first otherwise it complains about broken channel.
            mWmService.mInputManager.removeInputChannel(mInputChannelToken);
            mWmService.mKeyInterceptionInfoForToken.remove(mInputChannelToken);
            mWmService.mInputToWindowMap.remove(mInputChannelToken);
            mInputChannelToken = null;
        }

        if (mInputChannel != null) {
            mInputChannel.dispose();
            mInputChannel = null;
        }
        mInputWindowHandle.setToken(null);
    }

    /** Returns true if the replacement window was removed. */
    boolean removeReplacedWindowIfNeeded(WindowState replacement) {
        if (mWillReplaceWindow && mReplacementWindow == replacement && replacement.hasDrawn()) {
            replacement.mSkipEnterAnimationForSeamlessReplacement = false;
            removeReplacedWindow();
            return true;
        }

        for (int i = mChildren.size() - 1; i >= 0; --i) {
            final WindowState c = mChildren.get(i);
            if (c.removeReplacedWindowIfNeeded(replacement)) {
                return true;
            }
        }
        return false;
    }

    private void removeReplacedWindow() {
        ProtoLog.d(WM_DEBUG_ADD_REMOVE, "Removing replaced window: %s", this);
        mWillReplaceWindow = false;
        mAnimateReplacingWindow = false;
        mReplacingRemoveRequested = false;
        mReplacementWindow = null;
        if (mAnimatingExit || !mAnimateReplacingWindow) {
            removeImmediately();
        }
    }

    boolean setReplacementWindowIfNeeded(WindowState replacementCandidate) {
        boolean replacementSet = false;

        if (mWillReplaceWindow && mReplacementWindow == null
                && getWindowTag().toString().equals(replacementCandidate.getWindowTag().toString())) {

            mReplacementWindow = replacementCandidate;
            replacementCandidate.mSkipEnterAnimationForSeamlessReplacement = !mAnimateReplacingWindow;
            replacementSet = true;
        }

        for (int i = mChildren.size() - 1; i >= 0; --i) {
            final WindowState c = mChildren.get(i);
            replacementSet |= c.setReplacementWindowIfNeeded(replacementCandidate);
        }

        return replacementSet;
    }

    void setDisplayLayoutNeeded() {
        final DisplayContent dc = getDisplayContent();
        if (dc != null) {
            dc.setLayoutNeeded();
        }
    }

    @Override
    void switchUser(int userId) {
        super.switchUser(userId);

        if (showToCurrentUser()) {
            setPolicyVisibilityFlag(VISIBLE_FOR_USER);
        } else {
            if (DEBUG_VISIBILITY) Slog.w(TAG_WM, "user changing, hiding " + this
                    + ", attrs=" + mAttrs.type + ", belonging to " + mOwnerUid);
            clearPolicyVisibilityFlag(VISIBLE_FOR_USER);
        }
    }

    int getSurfaceTouchableRegion(Region region, int flags) {
        final boolean modal = (flags & (FLAG_NOT_TOUCH_MODAL | FLAG_NOT_FOCUSABLE)) == 0;
        if (modal) {
            flags |= FLAG_NOT_TOUCH_MODAL;
            if (mActivityRecord != null) {
                // Limit the outer touch to the activity root task region.
                updateRegionForModalActivityWindow(region);
            } else {
                // Give it a large touchable region at first because it was touch modal. The window
                // might be moved on the display, so the touchable region should be large enough to
                // ensure it covers the whole display, no matter where it is moved.
                getDisplayContent().getBounds(mTmpRect);
                final int dw = mTmpRect.width();
                final int dh = mTmpRect.height();
                region.set(-dw, -dh, dw + dw, dh + dh);
            }
            subtractTouchExcludeRegionIfNeeded(region);
        } else {
            // Not modal
            getTouchableRegion(region);
        }

        // Translate to surface based coordinates.
        final Rect frame = mWindowFrames.mFrame;
        if (frame.left != 0 || frame.top != 0) {
            region.translate(-frame.left, -frame.top);
        }

        // TODO(b/139804591): sizecompat layout needs to be reworked. Currently mFrame is post-
        // scaling but the existing logic doesn't expect that. The result is that the already-
        // scaled region ends up getting sent to surfaceflinger which then applies the scale
        // (again). Until this is resolved, apply an inverse-scale here.
        if (mInvGlobalScale != 1.f) {
            region.scale(mInvGlobalScale);
        }

        return flags;
    }

    /**
     * Expands the given rectangle by the region of window resize handle for freeform window.
     * @param inOutRect The rectangle to update.
     */
    private void adjustRegionInFreefromWindowMode(Rect inOutRect) {
        if (!inFreeformWindowingMode()) {
            return;
        }

        // For freeform windows, we need the touch region to include the whole
        // surface for the shadows.
        final DisplayMetrics displayMetrics = getDisplayContent().getDisplayMetrics();
        final int delta = WindowManagerService.dipToPixel(
                RESIZE_HANDLE_WIDTH_IN_DP, displayMetrics);
        inOutRect.inset(-delta, -delta);
    }

    /**
     * Updates the region for a window in an Activity that was a touch modal. This will limit
     * the outer touch to the activity root task region.
     * @param outRegion The region to update.
     */
    private void updateRegionForModalActivityWindow(Region outRegion) {
        // If the inner bounds of letterbox is available, then it will be used as the
        // touchable region so it won't cover the touchable letterbox and the touch
        // events can slip to activity from letterbox.
        mActivityRecord.getLetterboxInnerBounds(mTmpRect);
        if (mTmpRect.isEmpty()) {
            final Rect transformedBounds = mActivityRecord.getFixedRotationTransformDisplayBounds();
            if (transformedBounds != null) {
                // Task is in the same orientation as display, so the rotated bounds should be
                // chosen as the touchable region. Then when the surface layer transforms the
                // region to display space, the orientation will be consistent.
                mTmpRect.set(transformedBounds);
            } else {
                // If this is a modal window we need to dismiss it if it's not full screen
                // and the touch happens outside of the frame that displays the content. This
                // means we need to intercept touches outside of that window. The dim layer
                // user associated with the window (task or root task) will give us the good
                // bounds, as they would be used to display the dim layer.
                final Task task = getTask();
                if (task != null) {
                    task.getDimBounds(mTmpRect);
                } else if (getRootTask() != null) {
                    getRootTask().getDimBounds(mTmpRect);
                }
            }
        }
        adjustRegionInFreefromWindowMode(mTmpRect);
        outRegion.set(mTmpRect);
        cropRegionToRootTaskBoundsIfNeeded(outRegion);
    }

    void checkPolicyVisibilityChange() {
        if (isLegacyPolicyVisibility() != mLegacyPolicyVisibilityAfterAnim) {
            if (DEBUG_VISIBILITY) {
                Slog.v(TAG, "Policy visibility changing after anim in " +
                        mWinAnimator + ": " + mLegacyPolicyVisibilityAfterAnim);
            }
            if (mLegacyPolicyVisibilityAfterAnim) {
                setPolicyVisibilityFlag(LEGACY_POLICY_VISIBILITY);
            } else {
                clearPolicyVisibilityFlag(LEGACY_POLICY_VISIBILITY);
            }
            if (!isVisibleByPolicy()) {
                mWinAnimator.hide(getGlobalTransaction(), "checkPolicyVisibilityChange");
                if (isFocused()) {
                    ProtoLog.i(WM_DEBUG_FOCUS_LIGHT,
                            "setAnimationLocked: setting mFocusMayChange true");
                    mWmService.mFocusMayChange = true;
                }
                setDisplayLayoutNeeded();
                // Window is no longer visible -- make sure if we were waiting
                // for it to be displayed before enabling the display, that
                // we allow the display to be enabled now.
                mWmService.enableScreenIfNeededLocked();
            }
        }
    }

    void setRequestedSize(int requestedWidth, int requestedHeight) {
        if ((mRequestedWidth != requestedWidth || mRequestedHeight != requestedHeight)) {
            mLayoutNeeded = true;
            mRequestedWidth = requestedWidth;
            mRequestedHeight = requestedHeight;
        }
    }

    void prepareWindowToDisplayDuringRelayout(boolean wasVisible) {
        // We need to turn on screen regardless of visibility.
        final boolean hasTurnScreenOnFlag = (mAttrs.flags & FLAG_TURN_SCREEN_ON) != 0
                || (mActivityRecord != null && mActivityRecord.canTurnScreenOn());

        // The screen will turn on if the following conditions are met
        // 1. The window has the flag FLAG_TURN_SCREEN_ON or ActivityRecord#canTurnScreenOn.
        // 2. The WMS allows theater mode.
        // 3. No AWT or the AWT allows the screen to be turned on. This should only be true once
        // per resume to prevent the screen getting getting turned on for each relayout. Set
        // currentLaunchCanTurnScreenOn will be set to false so the window doesn't turn the screen
        // on again during this resume.
        // 4. When the screen is not interactive. This is because when the screen is already
        // interactive, the value may persist until the next animation, which could potentially
        // be occurring while turning off the screen. This would lead to the screen incorrectly
        // turning back on.
        if (hasTurnScreenOnFlag) {
            boolean allowTheaterMode = mWmService.mAllowTheaterModeWakeFromLayout
                    || Settings.Global.getInt(mWmService.mContext.getContentResolver(),
                            Settings.Global.THEATER_MODE_ON, 0) == 0;
            boolean canTurnScreenOn = mActivityRecord == null || mActivityRecord.currentLaunchCanTurnScreenOn();

            if (allowTheaterMode && canTurnScreenOn
                        && (mWmService.mAtmInternal.isDreaming()
                        || !mPowerManagerWrapper.isInteractive())) {
                if (DEBUG_VISIBILITY || DEBUG_POWER) {
                    Slog.v(TAG, "Relayout window turning screen on: " + this);
                }
                mPowerManagerWrapper.wakeUp(SystemClock.uptimeMillis(),
                        PowerManager.WAKE_REASON_APPLICATION, "android.server.wm:SCREEN_ON_FLAG");
            }

            if (mActivityRecord != null) {
                mActivityRecord.setCurrentLaunchCanTurnScreenOn(false);
            }
        }

        // If we were already visible, skip rest of preparation.
        if (wasVisible) {
            if (DEBUG_VISIBILITY) Slog.v(TAG,
                    "Already visible and does not turn on screen, skip preparing: " + this);
            return;
        }

        if ((mAttrs.softInputMode & SOFT_INPUT_MASK_ADJUST)
                == SOFT_INPUT_ADJUST_RESIZE) {
            mLayoutNeeded = true;
        }

        if (isDrawn() && mToken.okToAnimate()) {
            mWinAnimator.applyEnterAnimationLocked();
        }
    }

    private Configuration getProcessGlobalConfiguration() {
        // For child windows we want to use the pid for the parent window in case the the child
        // window was added from another process.
        final WindowState parentWindow = getParentWindow();
        final int pid = parentWindow != null ? parentWindow.mSession.mPid : mSession.mPid;
        final Configuration processConfig =
                mWmService.mAtmService.getGlobalConfigurationForPid(pid);
        return processConfig;
    }

    void getMergedConfiguration(MergedConfiguration outConfiguration) {
        final Configuration globalConfig = getProcessGlobalConfiguration();
        final Configuration overrideConfig = getMergedOverrideConfiguration();
        outConfiguration.setConfiguration(globalConfig, overrideConfig);
    }

    void setLastReportedMergedConfiguration(MergedConfiguration config) {
        mLastReportedConfiguration.setTo(config);
        mLastConfigReportedToClient = true;
    }

    void getLastReportedMergedConfiguration(MergedConfiguration config) {
        config.setTo(mLastReportedConfiguration);
    }

    private Configuration getLastReportedConfiguration() {
        return mLastReportedConfiguration.getMergedConfiguration();
    }

    void adjustStartingWindowFlags() {
        if (mAttrs.type == TYPE_BASE_APPLICATION && mActivityRecord != null
                && mActivityRecord.mStartingWindow != null) {
            // Special handling of starting window over the base
            // window of the app: propagate lock screen flags to it,
            // to provide the correct semantics while starting.
            final int mask = FLAG_SHOW_WHEN_LOCKED | FLAG_DISMISS_KEYGUARD
                    | FLAG_ALLOW_LOCK_WHILE_SCREEN_ON;
            WindowManager.LayoutParams sa = mActivityRecord.mStartingWindow.mAttrs;
            sa.flags = (sa.flags & ~mask) | (mAttrs.flags & mask);
        }
    }

    void setWindowScale(int requestedWidth, int requestedHeight) {
        final boolean scaledWindow = (mAttrs.flags & FLAG_SCALED) != 0;

        if (scaledWindow) {
            // requested{Width|Height} Surface's physical size
            // attrs.{width|height} Size on screen
            // TODO: We don't check if attrs != null here. Is it implicitly checked?
            mHScale = (mAttrs.width  != requestedWidth)  ?
                    (mAttrs.width  / (float)requestedWidth) : 1.0f;
            mVScale = (mAttrs.height != requestedHeight) ?
                    (mAttrs.height / (float)requestedHeight) : 1.0f;
        } else {
            mHScale = mVScale = 1;
        }
    }

    private class DeathRecipient implements IBinder.DeathRecipient {
        @Override
        public void binderDied() {
            try {
                boolean resetSplitScreenResizing = false;
                synchronized (mWmService.mGlobalLock) {
                    final WindowState win = mWmService
                            .windowForClientLocked(mSession, mClient, false);
                    Slog.i(TAG, "WIN DEATH: " + win);
                    if (win != null) {
                        final DisplayContent dc = getDisplayContent();
                        if (win.mActivityRecord != null && win.mActivityRecord.findMainWindow() == win) {
                            mWmService.mTaskSnapshotController.onAppDied(win.mActivityRecord);
                        }
                        win.removeIfPossible(shouldKeepVisibleDeadAppWindow());
                    } else if (mHasSurface) {
                        Slog.e(TAG, "!!! LEAK !!! Window removed but surface still valid.");
                        WindowState.this.removeIfPossible();
                    }
                }
                if (resetSplitScreenResizing) {
                    try {
                        // Note: this calls into ActivityManager, so we must *not* hold the window
                        // manager lock while calling this.
                        mWmService.mActivityTaskManager.setSplitScreenResizing(false);
                    } catch (RemoteException e) {
                        // Local call, shouldn't return RemoteException.
                        throw e.rethrowAsRuntimeException();
                    }
                }
            } catch (IllegalArgumentException ex) {
                // This will happen if the window has already been removed.
            }
        }
    }

    /**
     * Returns true if this window is visible and belongs to a dead app and shouldn't be removed,
     * because we want to preserve its location on screen to be re-activated later when the user
     * interacts with it.
     */
    private boolean shouldKeepVisibleDeadAppWindow() {
        if (!isWinVisibleLw() || mActivityRecord == null || !mActivityRecord.isClientVisible()) {
            // Not a visible app window or the app isn't dead.
            return false;
        }

        if (mAttrs.token != mClient.asBinder()) {
            // The window was add by a client using another client's app token. We don't want to
            // keep the dead window around for this case since this is meant for 'real' apps.
            return false;
        }

        if (mAttrs.type == TYPE_APPLICATION_STARTING) {
            // We don't keep starting windows since they were added by the window manager before
            // the app even launched.
            return false;
        }

        return getWindowConfiguration().keepVisibleDeadAppWindowOnScreen();
    }

    /** Returns {@code true} if this window desires key events. */
    boolean canReceiveKeys() {
        return canReceiveKeys(false /* fromUserTouch */);
    }

    public String canReceiveKeysReason(boolean fromUserTouch) {
        return "fromTouch= " + fromUserTouch
                + " isVisibleOrAdding=" + isVisibleOrAdding()
                + " mViewVisibility=" + mViewVisibility
                + " mRemoveOnExit=" + mRemoveOnExit
                + " flags=" + mAttrs.flags
                + " appWindowsAreFocusable="
                + (mActivityRecord == null || mActivityRecord.windowsAreFocusable(fromUserTouch))
                + " canReceiveTouchInput=" + canReceiveTouchInput()
                + " displayIsOnTop=" + getDisplayContent().isOnTop()
                + " displayIsTrusted=" + getDisplayContent().isTrusted();
    }

    public boolean canReceiveKeys(boolean fromUserTouch) {
        final boolean canReceiveKeys = isVisibleOrAdding()
                && (mViewVisibility == View.VISIBLE) && !mRemoveOnExit
                && ((mAttrs.flags & WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE) == 0)
                && (mActivityRecord == null || mActivityRecord.windowsAreFocusable(fromUserTouch))
                && canReceiveTouchInput();
        if (!canReceiveKeys) {
            return false;
        }
        // Do not allow untrusted virtual display to receive keys unless user intentionally
        // touches the display.
        return fromUserTouch || getDisplayContent().isOnTop()
                || getDisplayContent().isTrusted();
    }

    @Override
    public boolean canShowWhenLocked() {
        final boolean showBecauseOfActivity =
                mActivityRecord != null && mActivityRecord.canShowWhenLocked();
        final boolean showBecauseOfWindow = (getAttrs().flags & FLAG_SHOW_WHEN_LOCKED) != 0;
        return showBecauseOfActivity || showBecauseOfWindow;
    }

    /**
     * @return {@code true} if this window can receive touches based on among other things,
     * windowing state and recents animation state.
     **/
    boolean canReceiveTouchInput() {
        if (mActivityRecord == null  || mActivityRecord.getTask() == null) {
            return true;
        }

        return !mActivityRecord.getTask().getRootTask().shouldIgnoreInput()
                && mActivityRecord.mVisibleRequested
                && !isRecentsAnimationConsumingAppInput();
    }

    /**
     * Returns {@code true} if the window is animating to home as part of the recents animation and
     * it is consuming input from the app.
     */
    private boolean isRecentsAnimationConsumingAppInput() {
        final RecentsAnimationController recentsAnimationController =
                mWmService.getRecentsAnimationController();
        return recentsAnimationController != null
                && recentsAnimationController.shouldApplyInputConsumer(mActivityRecord);
    }

    /**
     * Returns {@code true} if this window has been shown on screen at some time in the past.
     *
     * @deprecated Use {@link #isDrawn} or any of the other drawn/visibility methods.
     */
    @Deprecated
    boolean hasDrawn() {
        return mWinAnimator.mDrawState == WindowStateAnimator.HAS_DRAWN;
    }

    /**
     * Can be called to undo the effect of {@link #hide}, allowing a window to be shown as long
     * as the client would also like it to be shown.
     */
    boolean show(boolean doAnimation, boolean requestAnim) {
        if (isLegacyPolicyVisibility() && mLegacyPolicyVisibilityAfterAnim) {
            // Already showing.
            return false;
        }
        if (!showToCurrentUser()) {
            return false;
        }
        if (!mAppOpVisibility) {
            // Being hidden due to app op request.
            return false;
        }
        if (mPermanentlyHidden) {
            // Permanently hidden until the app exists as apps aren't prepared
            // to handle their windows being removed from under them.
            return false;
        }
        if (mHiddenWhileSuspended) {
            // Being hidden due to owner package being suspended.
            return false;
        }
        if (mForceHideNonSystemOverlayWindow) {
            // This is an alert window that is currently force hidden.
            return false;
        }
        if (DEBUG_VISIBILITY) Slog.v(TAG, "Policy visibility true: " + this);
        if (doAnimation) {
            if (DEBUG_VISIBILITY) Slog.v(TAG, "doAnimation: mPolicyVisibility="
                    + isLegacyPolicyVisibility()
                    + " animating=" + isAnimating(TRANSITION | PARENTS));
            if (!mToken.okToAnimate()) {
                doAnimation = false;
            } else if (isLegacyPolicyVisibility() && !isAnimating(TRANSITION | PARENTS)) {
                // Check for the case where we are currently visible and
                // not animating; we do not want to do animation at such a
                // point to become visible when we already are.
                doAnimation = false;
            }
        }
        setPolicyVisibilityFlag(LEGACY_POLICY_VISIBILITY);
        mLegacyPolicyVisibilityAfterAnim = true;
        if (doAnimation) {
            mWinAnimator.applyAnimationLocked(TRANSIT_ENTER, true);
        }
        if (requestAnim) {
            mWmService.scheduleAnimationLocked();
        }
        if ((mAttrs.flags & FLAG_NOT_FOCUSABLE) == 0) {
            mWmService.updateFocusedWindowLocked(UPDATE_FOCUS_NORMAL, false /* updateImWindows */);
        }
        return true;
    }

    /** Forces the window to be hidden, regardless of whether the client like it shown. */
    boolean hide(boolean doAnimation, boolean requestAnim) {
        if (doAnimation) {
            if (!mToken.okToAnimate()) {
                doAnimation = false;
            }
        }
        boolean current =
                doAnimation ? mLegacyPolicyVisibilityAfterAnim : isLegacyPolicyVisibility();
        if (!current) {
            // Already hiding.
            return false;
        }
        if (doAnimation) {
            mWinAnimator.applyAnimationLocked(TRANSIT_EXIT, false);
            if (!isAnimating(TRANSITION | PARENTS)) {
                doAnimation = false;
            }
        }
        mLegacyPolicyVisibilityAfterAnim = false;
        final boolean isFocused = isFocused();
        if (!doAnimation) {
            if (DEBUG_VISIBILITY) Slog.v(TAG, "Policy visibility false: " + this);
            clearPolicyVisibilityFlag(LEGACY_POLICY_VISIBILITY);
            // Window is no longer visible -- make sure if we were waiting
            // for it to be displayed before enabling the display, that
            // we allow the display to be enabled now.
            mWmService.enableScreenIfNeededLocked();
            if (isFocused) {
                ProtoLog.i(WM_DEBUG_FOCUS_LIGHT,
                        "WindowState.hideLw: setting mFocusMayChange true");
                mWmService.mFocusMayChange = true;
            }
        }
        if (requestAnim) {
            mWmService.scheduleAnimationLocked();
        }
        if (isFocused) {
            mWmService.updateFocusedWindowLocked(UPDATE_FOCUS_NORMAL, false /* updateImWindows */);
        }
        return true;
    }

    void setForceHideNonSystemOverlayWindowIfNeeded(boolean forceHide) {
        if (mSession.mCanAddInternalSystemWindow
                || (!isSystemAlertWindowType(mAttrs.type) && mAttrs.type != TYPE_TOAST)) {
            return;
        }

        if (mAttrs.type == TYPE_APPLICATION_OVERLAY && mAttrs.isSystemApplicationOverlay()
                && mSession.mCanCreateSystemApplicationOverlay) {
            return;
        }

        if (mForceHideNonSystemOverlayWindow == forceHide) {
            return;
        }
        mForceHideNonSystemOverlayWindow = forceHide;
        if (forceHide) {
            hide(true /* doAnimation */, true /* requestAnim */);
        } else {
            show(true /* doAnimation */, true /* requestAnim */);
        }
    }

    void setHiddenWhileSuspended(boolean hide) {
        if (mOwnerCanAddInternalSystemWindow
                || (!isSystemAlertWindowType(mAttrs.type) && mAttrs.type != TYPE_TOAST)) {
            return;
        }
        if (mHiddenWhileSuspended == hide) {
            return;
        }
        mHiddenWhileSuspended = hide;
        if (hide) {
            hide(true /* doAnimation */, true /* requestAnim */);
        } else {
            show(true /* doAnimation */, true /* requestAnim */);
        }
    }

    private void setAppOpVisibilityLw(boolean state) {
        if (mAppOpVisibility != state) {
            mAppOpVisibility = state;
            if (state) {
                // If the policy visibility had last been to hide, then this
                // will incorrectly show at this point since we lost that
                // information.  Not a big deal -- for the windows that have app
                // ops modifies they should only be hidden by policy due to the
                // lock screen, and the user won't be changing this if locked.
                // Plus it will quickly be fixed the next time we do a layout.
                show(true /* doAnimation */, true /* requestAnim */);
            } else {
                hide(true /* doAnimation */, true /* requestAnim */);
            }
        }
    }

    void initAppOpsState() {
        if (mAppOp == OP_NONE || !mAppOpVisibility) {
            return;
        }
        // If the app op was MODE_DEFAULT we would have checked the permission
        // and add the window only if the permission was granted. Therefore, if
        // the mode is MODE_DEFAULT we want the op to succeed as the window is
        // shown.
        final int mode = mWmService.mAppOps.startOpNoThrow(mAppOp, getOwningUid(),
                getOwningPackage(), true /* startIfModeDefault */, null /* featureId */,
                "init-default-visibility");
        if (mode != MODE_ALLOWED && mode != MODE_DEFAULT) {
            setAppOpVisibilityLw(false);
        }
    }

    void resetAppOpsState() {
        if (mAppOp != OP_NONE && mAppOpVisibility) {
            mWmService.mAppOps.finishOp(mAppOp, getOwningUid(), getOwningPackage(),
                    null /* featureId */);
        }
    }

    void updateAppOpsState() {
        if (mAppOp == OP_NONE) {
            return;
        }
        final int uid = getOwningUid();
        final String packageName = getOwningPackage();
        if (mAppOpVisibility) {
            // There is a race between the check and the finish calls but this is fine
            // as this would mean we will get another change callback and will reconcile.
            int mode = mWmService.mAppOps.checkOpNoThrow(mAppOp, uid, packageName);
            if (mode != MODE_ALLOWED && mode != MODE_DEFAULT) {
                mWmService.mAppOps.finishOp(mAppOp, uid, packageName, null /* featureId */);
                setAppOpVisibilityLw(false);
            }
        } else {
            final int mode = mWmService.mAppOps.startOpNoThrow(mAppOp, uid, packageName,
                    true /* startIfModeDefault */, null /* featureId */, "attempt-to-be-visible");
            if (mode == MODE_ALLOWED || mode == MODE_DEFAULT) {
                setAppOpVisibilityLw(true);
            }
        }
    }

    public void hidePermanentlyLw() {
        if (!mPermanentlyHidden) {
            mPermanentlyHidden = true;
            hide(true /* doAnimation */, true /* requestAnim */);
        }
    }

    public void pokeDrawLockLw(long timeout) {
        if (isVisibleOrAdding()) {
            if (mDrawLock == null) {
                // We want the tag name to be somewhat stable so that it is easier to correlate
                // in wake lock statistics.  So in particular, we don't want to include the
                // window's hash code as in toString().
                final CharSequence tag = getWindowTag();
                mDrawLock = mWmService.mPowerManager.newWakeLock(DRAW_WAKE_LOCK, "Window:" + tag);
                mDrawLock.setReferenceCounted(false);
                mDrawLock.setWorkSource(new WorkSource(mOwnerUid, mAttrs.packageName));
            }
            // Each call to acquire resets the timeout.
            if (DEBUG_POWER) {
                Slog.d(TAG, "pokeDrawLock: poking draw lock on behalf of visible window owned by "
                        + mAttrs.packageName);
            }
            mDrawLock.acquire(timeout);
        } else if (DEBUG_POWER) {
            Slog.d(TAG, "pokeDrawLock: suppressed draw lock request for invisible window "
                    + "owned by " + mAttrs.packageName);
        }
    }

    /** Checks whether the process hosting this window is currently alive. */
    boolean isAlive() {
        return mClient.asBinder().isBinderAlive();
    }

    boolean isClosing() {
        return mAnimatingExit || (mActivityRecord != null && mActivityRecord.isClosingOrEnteringPip());
    }

    void addWinAnimatorToList(ArrayList<WindowStateAnimator> animators) {
        animators.add(mWinAnimator);

        for (int i = mChildren.size() - 1; i >= 0; --i) {
            final WindowState c = mChildren.get(i);
            c.addWinAnimatorToList(animators);
        }
    }

    void sendAppVisibilityToClients() {
        super.sendAppVisibilityToClients();

        if (mToken == null) return;

        final boolean clientVisible = mToken.isClientVisible();
        // TODO(shell-transitions): This is currently only applicable to app windows, BUT we
        //                          want to extend the "starting" concept to other windows.
        if (mAttrs.type == TYPE_APPLICATION_STARTING && !clientVisible) {
            // Don't hide the starting window.
            return;
        }

        try {
            if (DEBUG_VISIBILITY) Slog.v(TAG,
                    "Setting visibility of " + this + ": " + clientVisible);
            mClient.dispatchAppVisibility(clientVisible);
        } catch (RemoteException e) {
        }
    }

    void onStartFreezingScreen() {
        mAppFreezing = true;
        for (int i = mChildren.size() - 1; i >= 0; --i) {
            final WindowState c = mChildren.get(i);
            c.onStartFreezingScreen();
        }
    }

    boolean onStopFreezingScreen() {
        boolean unfrozeWindows = false;
        for (int i = mChildren.size() - 1; i >= 0; --i) {
            final WindowState c = mChildren.get(i);
            unfrozeWindows |= c.onStopFreezingScreen();
        }

        if (!mAppFreezing) {
            return unfrozeWindows;
        }

        mAppFreezing = false;

        if (mHasSurface && !getOrientationChanging()
                && mWmService.mWindowsFreezingScreen != WINDOWS_FREEZING_SCREENS_TIMEOUT) {
            ProtoLog.v(WM_DEBUG_ORIENTATION,
                    "set mOrientationChanging of %s", this);
            setOrientationChanging(true);
        }
        mLastFreezeDuration = 0;
        setDisplayLayoutNeeded();
        return true;
    }

    boolean destroySurface(boolean cleanupOnResume, boolean appStopped) {
        boolean destroyedSomething = false;

        // Copying to a different list as multiple children can be removed.
        final ArrayList<WindowState> childWindows = new ArrayList<>(mChildren);
        for (int i = childWindows.size() - 1; i >= 0; --i) {
            final WindowState c = childWindows.get(i);
            destroyedSomething |= c.destroySurface(cleanupOnResume, appStopped);
        }

        if (!(appStopped || mWindowRemovalAllowed || cleanupOnResume)) {
            return destroyedSomething;
        }

        if (mDestroying) {
            ProtoLog.e(WM_DEBUG_ADD_REMOVE, "win=%s"
                    + " destroySurfaces: appStopped=%b"
                    + " win.mWindowRemovalAllowed=%b"
                    + " win.mRemoveOnExit=%b", this, appStopped,
                    mWindowRemovalAllowed, mRemoveOnExit);
            if (!cleanupOnResume || mRemoveOnExit) {
                destroySurfaceUnchecked();
            }
            if (mRemoveOnExit) {
                removeImmediately();
            }
            if (cleanupOnResume) {
                requestUpdateWallpaperIfNeeded();
            }
            mDestroying = false;
            destroyedSomething = true;

            // Since mDestroying will affect ActivityRecord#allDrawn, we need to perform another
            // traversal in case we are waiting on this window to start the transition.
            if (getDisplayContent().mAppTransition.isTransitionSet()
                    && getDisplayContent().mOpeningApps.contains(mActivityRecord)) {
                mWmService.mWindowPlacerLocked.requestTraversal();
            }
        }

        return destroyedSomething;
    }

    // Destroy or save the application surface without checking
    // various indicators of whether the client has released the surface.
    // This is in general unsafe, and most callers should use {@link #destroySurface}
    void destroySurfaceUnchecked() {
        mWinAnimator.destroySurfaceLocked(mTmpTransaction);
        mTmpTransaction.apply();

        // Clear animating flags now, since the surface is now gone. (Note this is true even
        // if the surface is saved, to outside world the surface is still NO_SURFACE.)
        mAnimatingExit = false;
    }

    void onSurfaceShownChanged(boolean shown) {
        if (mLastShownChangedReported == shown) {
            return;
        }
        mLastShownChangedReported = shown;

        if (shown) {
            initExclusionRestrictions();
        } else {
            logExclusionRestrictions(EXCLUSION_LEFT);
            logExclusionRestrictions(EXCLUSION_RIGHT);
        }
        // Exclude toast because legacy apps may show toast window by themselves, so the misused
        // apps won't always be considered as foreground state.
        if (mAttrs.type >= FIRST_SYSTEM_WINDOW && mAttrs.type != TYPE_TOAST) {
            mWmService.mAtmService.mActiveUids.onNonAppSurfaceVisibilityChanged(mOwnerUid, shown);
        }
    }

    private void logExclusionRestrictions(int side) {
        if (!logsGestureExclusionRestrictions(this)
                || SystemClock.uptimeMillis() < mLastExclusionLogUptimeMillis[side]
                + mWmService.mConstants.mSystemGestureExclusionLogDebounceTimeoutMillis) {
            // Drop the log if we have just logged; this is okay, because what we would have logged
            // was true only for a short duration.
            return;
        }

        final long now = SystemClock.uptimeMillis();
        final long duration = now - mLastExclusionLogUptimeMillis[side];
        mLastExclusionLogUptimeMillis[side] = now;

        final int requested = mLastRequestedExclusionHeight[side];
        final int granted = mLastGrantedExclusionHeight[side];

        FrameworkStatsLog.write(FrameworkStatsLog.EXCLUSION_RECT_STATE_CHANGED,
                mAttrs.packageName, requested, requested - granted /* rejected */,
                side + 1 /* Sides are 1-indexed in atoms.proto */,
                (getConfiguration().orientation == ORIENTATION_LANDSCAPE),
                isSplitScreenWindowingMode(getWindowingMode()), (int) duration);
    }

    private void initExclusionRestrictions() {
        final long now = SystemClock.uptimeMillis();
        mLastExclusionLogUptimeMillis[EXCLUSION_LEFT] = now;
        mLastExclusionLogUptimeMillis[EXCLUSION_RIGHT] = now;
    }

    /** @return {@code true} if this window can be shown to all users. */
    boolean showForAllUsers() {

        // If this switch statement is modified, modify the comment in the declarations of
        // the type in {@link WindowManager.LayoutParams} as well.
        switch (mAttrs.type) {
            default:
                // These are the windows that by default are shown only to the user that created
                // them. If this needs to be overridden, set
                // {@link WindowManager.LayoutParams.SYSTEM_FLAG_SHOW_FOR_ALL_USERS} in
                // {@link WindowManager.LayoutParams}. Note that permission
                // {@link android.Manifest.permission.INTERNAL_SYSTEM_WINDOW} is required as well.
                if ((mAttrs.privateFlags & SYSTEM_FLAG_SHOW_FOR_ALL_USERS) == 0) {
                    return false;
                }
                break;

            // These are the windows that by default are shown to all users. However, to
            // protect against spoofing, check permissions below.
            case TYPE_ACCESSIBILITY_MAGNIFICATION_OVERLAY:
            case TYPE_APPLICATION_STARTING:
            case TYPE_BOOT_PROGRESS:
            case TYPE_DISPLAY_OVERLAY:
            case TYPE_INPUT_CONSUMER:
            case TYPE_KEYGUARD_DIALOG:
            case TYPE_MAGNIFICATION_OVERLAY:
            case TYPE_NAVIGATION_BAR:
            case TYPE_NAVIGATION_BAR_PANEL:
            case TYPE_PHONE:
            case TYPE_POINTER:
            case TYPE_PRIORITY_PHONE:
            case TYPE_SEARCH_BAR:
            case TYPE_STATUS_BAR:
            case TYPE_NOTIFICATION_SHADE:
            case TYPE_STATUS_BAR_ADDITIONAL:
            case TYPE_STATUS_BAR_SUB_PANEL:
            case TYPE_SYSTEM_DIALOG:
            case TYPE_VOLUME_OVERLAY:
            case TYPE_PRESENTATION:
            case TYPE_PRIVATE_PRESENTATION:
            case TYPE_DOCK_DIVIDER:
                break;
        }

        // Only the system can show free windows to all users.
        return mOwnerCanAddInternalSystemWindow;

    }

    @Override
    boolean showToCurrentUser() {
        // Child windows are evaluated based on their parent window.
        final WindowState win = getTopParentWindow();
        if (win.mAttrs.type < FIRST_SYSTEM_WINDOW
                && win.mActivityRecord != null && win.mActivityRecord.mShowForAllUsers) {

            // All window frames that are fullscreen extend above status bar, but some don't extend
            // below navigation bar. Thus, check for display frame for top/left and stable frame for
            // bottom right.
            if (win.getFrame().left <= win.getDisplayFrame().left
                    && win.getFrame().top <= win.getDisplayFrame().top
                    && win.getFrame().right >= win.getDisplayFrame().right
                    && win.getFrame().bottom >= win.getDisplayFrame().bottom) {
                // Is a fullscreen window, like the clock alarm. Show to everyone.
                return true;
            }
        }

        return win.showForAllUsers()
                || mWmService.isCurrentProfile(win.mShowUserId);
    }

    private static void applyInsets(Region outRegion, Rect frame, Rect inset) {
        outRegion.set(
                frame.left + inset.left, frame.top + inset.top,
                frame.right - inset.right, frame.bottom - inset.bottom);
    }

    /** Get the touchable region in global coordinates. */
    void getTouchableRegion(Region outRegion) {
        final Rect frame = mWindowFrames.mFrame;
        switch (mTouchableInsets) {
            default:
            case TOUCHABLE_INSETS_FRAME:
                outRegion.set(frame);
                break;
            case TOUCHABLE_INSETS_CONTENT:
                applyInsets(outRegion, frame, mGivenContentInsets);
                break;
            case TOUCHABLE_INSETS_VISIBLE:
                applyInsets(outRegion, frame, mGivenVisibleInsets);
                break;
            case TOUCHABLE_INSETS_REGION: {
                outRegion.set(mGivenTouchableRegion);
                if (frame.left != 0 || frame.top != 0) {
                    outRegion.translate(frame.left, frame.top);
                }
                break;
            }
        }
        cropRegionToRootTaskBoundsIfNeeded(outRegion);
        subtractTouchExcludeRegionIfNeeded(outRegion);
    }

    /**
     * Get the effective touchable region in global coordinates.
     *
     * In contrast to {@link #getTouchableRegion}, this takes into account
     * {@link WindowManager.LayoutParams#FLAG_NOT_TOUCH_MODAL touch modality.}
     */
    void getEffectiveTouchableRegion(Region outRegion) {
        final boolean modal = (mAttrs.flags & (FLAG_NOT_TOUCH_MODAL | FLAG_NOT_FOCUSABLE)) == 0;
        final DisplayContent dc = getDisplayContent();

        if (modal && dc != null) {
            outRegion.set(dc.getBounds());
            cropRegionToRootTaskBoundsIfNeeded(outRegion);
            subtractTouchExcludeRegionIfNeeded(outRegion);
        } else {
            getTouchableRegion(outRegion);
        }
    }

    private void cropRegionToRootTaskBoundsIfNeeded(Region region) {
        final Task task = getTask();
        if (task == null || !task.cropWindowsToRootTaskBounds()) {
            return;
        }

        final Task rootTask = task.getRootTask();
        if (rootTask == null || rootTask.mCreatedByOrganizer) {
            return;
        }

        rootTask.getDimBounds(mTmpRect);
        adjustRegionInFreefromWindowMode(mTmpRect);
        region.op(mTmpRect, Region.Op.INTERSECT);
    }

    /**
     * If this window has areas that cannot be touched, we subtract those areas from its touchable
     * region.
     */
    private void subtractTouchExcludeRegionIfNeeded(Region touchableRegion) {
        if (mTapExcludeRegion.isEmpty()) {
            return;
        }
        final Region touchExcludeRegion = Region.obtain();
        getTapExcludeRegion(touchExcludeRegion);
        if (!touchExcludeRegion.isEmpty()) {
            touchableRegion.op(touchExcludeRegion, Region.Op.DIFFERENCE);
        }
        touchExcludeRegion.recycle();
    }

    /**
     * Report a focus change.  Must be called with no locks held, and consistently
     * from the same serialized thread (such as dispatched from a handler).
     */
    void reportFocusChangedSerialized(boolean focused) {
        if (mFocusCallbacks != null) {
            final int N = mFocusCallbacks.beginBroadcast();
            for (int i=0; i<N; i++) {
                IWindowFocusObserver obs = mFocusCallbacks.getBroadcastItem(i);
                try {
                    if (focused) {
                        obs.focusGained(mWindowId.asBinder());
                    } else {
                        obs.focusLost(mWindowId.asBinder());
                    }
                } catch (RemoteException e) {
                }
            }
            mFocusCallbacks.finishBroadcast();
        }
    }

    @Override
    public Configuration getConfiguration() {
        // If the process has not registered to any display area to listen to the configuration
        // change, we can simply return the mFullConfiguration as default.
        if (!registeredForDisplayAreaConfigChanges()) {
            return super.getConfiguration();
        }

        // We use the process config this window is associated with as the based global config since
        // the process can override its config, but isn't part of the window hierarchy.
        mTempConfiguration.setTo(getProcessGlobalConfiguration());
        mTempConfiguration.updateFrom(getMergedOverrideConfiguration());
        return mTempConfiguration;
    }

    /** @return {@code true} if the process registered to a display area as a config listener. */
    private boolean registeredForDisplayAreaConfigChanges() {
        final WindowState parentWindow = getParentWindow();
        final WindowProcessController wpc = parentWindow != null
                ? parentWindow.mWpcForDisplayAreaConfigChanges
                : mWpcForDisplayAreaConfigChanges;
        return wpc != null && wpc.registeredForDisplayAreaConfigChanges();
    }

    void fillClientWindowFrames(ClientWindowFrames outFrames) {
        outFrames.frame.set(mWindowFrames.mCompatFrame);
        outFrames.displayFrame.set(mWindowFrames.mDisplayFrame);
        if (mInvGlobalScale != 1.0f && hasCompatScale()) {
            outFrames.displayFrame.scale(mInvGlobalScale);
        }

        final Rect backdropFrame = outFrames.backdropFrame;
        // When the task is docked, we send fullscreen sized backdropFrame as soon as resizing
        // start even if we haven't received the relayout window, so that the client requests
        // the relayout sooner. When dragging stops, backdropFrame needs to stay fullscreen
        // until the window to small size, otherwise the multithread renderer will shift last
        // one or more frame to wrong offset. So here we send fullscreen backdrop if either
        // isDragResizing() or isDragResizeChanged() is true.
        final boolean resizing = isDragResizing() || isDragResizeChanged();
        if (!resizing || getWindowConfiguration().useWindowFrameForBackdrop()) {
            // Surface position is now inherited from parent, and BackdropFrameRenderer uses
            // backdrop frame to position content. Thus we just keep the size of backdrop frame,
            // and remove the offset to avoid double offset from display origin.
            backdropFrame.set(outFrames.frame);
            backdropFrame.offsetTo(0, 0);
        } else {
            final DisplayInfo displayInfo = getDisplayInfo();
            backdropFrame.set(0, 0, displayInfo.logicalWidth, displayInfo.logicalHeight);
        }
    }

    void reportResized() {
        // If the activity is scheduled to relaunch, skip sending the resized to ViewRootImpl now
        // since it will be destroyed anyway. This also prevents the client from receiving
        // windowing mode change before it is destroyed.
        if (mActivityRecord != null && mActivityRecord.isRelaunching()) {
            return;
        }
        // If this is an activity or wallpaper and is invisible or going invisible, don't report
        // either since it is going away. This is likely during a transition so we want to preserve
        // the original state.
        if ((mActivityRecord != null || mToken.asWallpaperToken() != null)
                && !mToken.isVisibleRequested()) {
            return;
        }

        if (Trace.isTagEnabled(TRACE_TAG_WINDOW_MANAGER)) {
            Trace.traceBegin(TRACE_TAG_WINDOW_MANAGER, "wm.reportResized_" + getWindowTag());
        }

        ProtoLog.v(WM_DEBUG_RESIZE, "Reporting new frame to %s: %s", this,
                mWindowFrames.mCompatFrame);
        final boolean drawPending = mWinAnimator.mDrawState == DRAW_PENDING;
        if (drawPending) {
            ProtoLog.i(WM_DEBUG_ORIENTATION, "Resizing %s WITH DRAW PENDING", this);
        }

        getMergedConfiguration(mLastReportedConfiguration);
        mLastConfigReportedToClient = true;

        final boolean reportOrientation = mReportOrientationChanged;
        // Always reset these states first, so if {@link IWindow#resized} fails, this
        // window won't be added to {@link WindowManagerService#mResizingWindows} and set
        // {@link #mOrientationChanging} to true again by {@link #updateResizingWindowIfNeeded}
        // that may cause WINDOW_FREEZE_TIMEOUT because resizing the client keeps failing.
        mReportOrientationChanged = false;
        mDragResizingChangeReported = true;
        mWindowFrames.clearReportResizeHints();

        final MergedConfiguration mergedConfiguration = mLastReportedConfiguration;
        final boolean reportDraw = drawPending || useBLASTSync() || !mRedrawForSyncReported;
        final boolean forceRelayout = reportOrientation || isDragResizeChanged() || !mRedrawForSyncReported;
        final int displayId = getDisplayId();
        fillClientWindowFrames(mClientWindowFrames);

        mRedrawForSyncReported = true;

        try {
            mClient.resized(mClientWindowFrames, reportDraw, mergedConfiguration, forceRelayout,
                    getDisplayContent().getDisplayPolicy().areSystemBarsForcedShownLw(this),
                    displayId);
            if (drawPending && reportOrientation && mOrientationChanging) {
                mOrientationChangeRedrawRequestTime = SystemClock.elapsedRealtime();
                ProtoLog.v(WM_DEBUG_ORIENTATION,
                        "Requested redraw for orientation change: %s", this);
            }

            if (mWmService.mAccessibilityController != null) {
                mWmService.mAccessibilityController.onSomeWindowResizedOrMoved(displayId);
            }
            updateLocationInParentDisplayIfNeeded();
        } catch (RemoteException e) {
            // Cancel orientation change of this window to avoid blocking unfreeze display.
            setOrientationChanging(false);
            mLastFreezeDuration = (int)(SystemClock.elapsedRealtime()
                    - mWmService.mDisplayFreezeTime);
            Slog.w(TAG, "Failed to report 'resized' to " + this + " due to " + e);
        }
        Trace.traceEnd(TRACE_TAG_WINDOW_MANAGER);
    }

    boolean isClientLocal() {
        return mClient instanceof IWindow.Stub;
    }

    void updateLocationInParentDisplayIfNeeded() {
        final int embeddedDisplayContentsSize = mEmbeddedDisplayContents.size();
        // If there is any embedded display which is re-parented to this window, we need to
        // notify all windows in the embedded display about the location change.
        if (embeddedDisplayContentsSize != 0) {
            for (int i = embeddedDisplayContentsSize - 1; i >= 0; i--) {
                final DisplayContent edc = mEmbeddedDisplayContents.valueAt(i);
                edc.notifyLocationInParentDisplayChanged();
            }
        }
        // If this window is in a embedded display which is re-parented to another window,
        // we may need to update its correct on-screen location.
        final DisplayContent dc = getDisplayContent();
        if (dc.getParentWindow() == null) {
            return;
        }

        final Point offset = dc.getLocationInParentDisplay();
        if (mLastReportedDisplayOffset.equals(offset)) {
            return;
        }

        mLastReportedDisplayOffset.set(offset.x, offset.y);
        try {
            mClient.locationInParentDisplayChanged(mLastReportedDisplayOffset);
        } catch (RemoteException e) {
            Slog.e(TAG, "Failed to update offset from DisplayContent", e);
        }
    }

    /**
     * Called when the insets state changed.
     */
    void notifyInsetsChanged() {
        ProtoLog.d(WM_DEBUG_IME, "notifyInsetsChanged for %s ", this);
        try {
            mClient.insetsChanged(getCompatInsetsState());
        } catch (RemoteException e) {
            Slog.w(TAG, "Failed to deliver inset state change w=" + this, e);
        }
    }

    @Override
    public void notifyInsetsControlChanged() {
        ProtoLog.d(WM_DEBUG_IME, "notifyInsetsControlChanged for %s ", this);
        if (mAppDied || mRemoved) {
            return;
        }
        final InsetsStateController stateController =
                getDisplayContent().getInsetsStateController();
        try {
            mClient.insetsControlChanged(getCompatInsetsState(),
                    stateController.getControlsForDispatch(this));
        } catch (RemoteException e) {
            Slog.w(TAG, "Failed to deliver inset state change to w=" + this, e);
        }
    }

    @Override
    public WindowState getWindow() {
        return this;
    }

    @Override
    public void showInsets(@InsetsType int types, boolean fromIme) {
        try {
            mClient.showInsets(types, fromIme);
        } catch (RemoteException e) {
            Slog.w(TAG, "Failed to deliver showInsets", e);
        }
    }

    @Override
    public void hideInsets(@InsetsType int types, boolean fromIme) {
        try {
            mClient.hideInsets(types, fromIme);
        } catch (RemoteException e) {
            Slog.w(TAG, "Failed to deliver showInsets", e);
        }
    }

    @Override
    public boolean canShowTransient() {
        return (mAttrs.insetsFlags.behavior & BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE) != 0;
    }

    private int getRootTaskId() {
        final Task rootTask = getRootTask();
        if (rootTask == null) {
            return INVALID_TASK_ID;
        }
        return rootTask.mTaskId;
    }

    public void registerFocusObserver(IWindowFocusObserver observer) {
        synchronized (mWmService.mGlobalLock) {
            if (mFocusCallbacks == null) {
                mFocusCallbacks = new RemoteCallbackList<IWindowFocusObserver>();
            }
            mFocusCallbacks.register(observer);
        }
    }

    public void unregisterFocusObserver(IWindowFocusObserver observer) {
        synchronized (mWmService.mGlobalLock) {
            if (mFocusCallbacks != null) {
                mFocusCallbacks.unregister(observer);
            }
        }
    }

    boolean isFocused() {
        return getDisplayContent().mCurrentFocus == this;
    }

    /** Is this window in a container that takes up the entire screen space? */
    private boolean inAppWindowThatMatchesParentBounds() {
        return mActivityRecord == null || (mActivityRecord.matchParentBounds() && !inMultiWindowMode());
    }

    /** @return true when the window should be letterboxed. */
    boolean isLetterboxedAppWindow() {
        // Fullscreen mode but doesn't fill display area.
        if (!inMultiWindowMode() && !matchesDisplayAreaBounds()) {
            return true;
        }
        if (mActivityRecord != null) {
            // Activity in size compat.
            if (mActivityRecord.inSizeCompatMode()) {
                return true;
            }
            // Letterbox for fixed orientation.
            if (mActivityRecord.isLetterboxedForFixedOrientationAndAspectRatio()) {
                return true;
            }
        }
        // Letterboxed for display cutout.
        return isLetterboxedForDisplayCutout();
    }

    /** Returns {@code true} if the window is letterboxed for the display cutout. */
    boolean isLetterboxedForDisplayCutout() {
        if (mActivityRecord == null) {
            // Only windows with an ActivityRecord are letterboxed.
            return false;
        }
        if (!mWindowFrames.parentFrameWasClippedByDisplayCutout()) {
            // Cutout didn't make a difference, no letterbox
            return false;
        }
        if (mAttrs.layoutInDisplayCutoutMode == LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS) {
            // Layout in cutout, no letterbox.
            return false;
        }
        if (!mAttrs.isFullscreen()) {
            // Not filling the parent frame, no letterbox
            return false;
        }
        // Otherwise we need a letterbox if the layout was smaller than the app window token allowed
        // it to be.
        return !frameCoversEntireAppTokenBounds();
    }

    /**
     * @return true if this window covers the entire bounds of its app window token
     * @throws NullPointerException if there is no app window token for this window
     */
    private boolean frameCoversEntireAppTokenBounds() {
        mTmpRect.set(mActivityRecord.getBounds());
        mTmpRect.intersectUnchecked(mWindowFrames.mFrame);
        return mActivityRecord.getBounds().equals(mTmpRect);
    }

    /**
     * @return {@code true} if bar shown within a given frame is allowed to be fully transparent
     *     when the current window is displayed.
     */
    boolean isFullyTransparentBarAllowed(Rect frame) {
        return mActivityRecord == null || mActivityRecord.isFullyTransparentBarAllowed(frame);
    }

    public boolean isLetterboxedOverlappingWith(Rect rect) {
        return mActivityRecord != null && mActivityRecord.isLetterboxOverlappingWith(rect);
    }

    boolean isDragResizeChanged() {
        return mDragResizing != computeDragResizing();
    }

    @Override
    void setWaitingForDrawnIfResizingChanged() {
        if (isDragResizeChanged()) {
            mWmService.mRoot.mWaitingForDrawn.add(this);
        }
        super.setWaitingForDrawnIfResizingChanged();
    }

    /**
     * @return Whether we reported a drag resize change to the application or not already.
     */
    private boolean isDragResizingChangeReported() {
        return mDragResizingChangeReported;
    }

    /**
     * Resets the state whether we reported a drag resize change to the app.
     */
    @Override
    void resetDragResizingChangeReported() {
        mDragResizingChangeReported = false;
        super.resetDragResizingChangeReported();
    }

    int getResizeMode() {
        return mResizeMode;
    }

    private boolean computeDragResizing() {
        final Task task = getTask();
        if (task == null) {
            return false;
        }
        if (!inSplitScreenWindowingMode() && !inFreeformWindowingMode()
                && !task.getRootTask().mCreatedByOrganizer) {
            return false;
        }
        // TODO(157912944): formalize drag-resizing so that exceptions aren't hardcoded like this
        if (task.getActivityType() == ACTIVITY_TYPE_HOME) {
            // The current sys-ui implementations never live-resize home, so to prevent WSA from
            // creating/destroying surfaces (which messes up sync-transactions), skip HOME tasks.
            return false;
        }
        if (mAttrs.width != MATCH_PARENT || mAttrs.height != MATCH_PARENT) {
            // Floating windows never enter drag resize mode.
            return false;
        }
        if (task.isDragResizing()) {
            return true;
        }

        return getDisplayContent().mDividerControllerLocked.isResizing()
                && !task.inFreeformWindowingMode() && !isGoneForLayout();
    }

    void setDragResizing() {
        final boolean resizing = computeDragResizing();
        if (resizing == mDragResizing) {
            return;
        }
        mDragResizing = resizing;
        final Task task = getTask();
        if (task != null && task.isDragResizing()) {
            mResizeMode = task.getDragResizeMode();
        } else {
            mResizeMode = mDragResizing && getDisplayContent().mDividerControllerLocked.isResizing()
                    ? DRAG_RESIZE_MODE_DOCKED_DIVIDER
                    : DRAG_RESIZE_MODE_FREEFORM;
        }
    }

    boolean isDragResizing() {
        return mDragResizing;
    }

    boolean isDockedResizing() {
        return (mDragResizing && getResizeMode() == DRAG_RESIZE_MODE_DOCKED_DIVIDER)
                || (isChildWindow() && getParentWindow().isDockedResizing());
    }

    @CallSuper
    @Override
    public void dumpDebug(ProtoOutputStream proto, long fieldId,
            @WindowTraceLogLevel int logLevel) {
        boolean isVisible = isVisible();
        if (logLevel == WindowTraceLogLevel.CRITICAL && !isVisible) {
            return;
        }

        final long token = proto.start(fieldId);
        super.dumpDebug(proto, WINDOW_CONTAINER, logLevel);
        proto.write(DISPLAY_ID, getDisplayId());
        proto.write(STACK_ID, getRootTaskId());
        mAttrs.dumpDebug(proto, ATTRIBUTES);
        mGivenContentInsets.dumpDebug(proto, GIVEN_CONTENT_INSETS);
        mWindowFrames.dumpDebug(proto, WINDOW_FRAMES);
        mAttrs.surfaceInsets.dumpDebug(proto, SURFACE_INSETS);
        dumpPointProto(mSurfacePosition, proto, SURFACE_POSITION);
        mWinAnimator.dumpDebug(proto, ANIMATOR);
        proto.write(ANIMATING_EXIT, mAnimatingExit);
        proto.write(REQUESTED_WIDTH, mRequestedWidth);
        proto.write(REQUESTED_HEIGHT, mRequestedHeight);
        proto.write(VIEW_VISIBILITY, mViewVisibility);
        proto.write(HAS_SURFACE, mHasSurface);
        proto.write(IS_READY_FOR_DISPLAY, isReadyForDisplay());
        proto.write(REMOVE_ON_EXIT, mRemoveOnExit);
        proto.write(DESTROYING, mDestroying);
        proto.write(REMOVED, mRemoved);
        proto.write(IS_ON_SCREEN, isOnScreen());
        proto.write(IS_VISIBLE, isVisible);
        proto.write(PENDING_SEAMLESS_ROTATION, mPendingSeamlessRotate != null);
        proto.write(FINISHED_SEAMLESS_ROTATION_FRAME, mFinishSeamlessRotateFrameNumber);
        proto.write(FORCE_SEAMLESS_ROTATION, mForceSeamlesslyRotate);
        proto.write(HAS_COMPAT_SCALE, hasCompatScale());
        proto.write(GLOBAL_SCALE, mGlobalScale);
        proto.end(token);
    }

    @Override
    long getProtoFieldId() {
        return WINDOW;
    }

    @Override
    public void writeIdentifierToProto(ProtoOutputStream proto, long fieldId) {
        final long token = proto.start(fieldId);
        proto.write(HASH_CODE, System.identityHashCode(this));
        proto.write(USER_ID, mShowUserId);
        final CharSequence title = getWindowTag();
        if (title != null) {
            proto.write(TITLE, title.toString());
        }
        proto.end(token);
    }

    @Override
    void dump(PrintWriter pw, String prefix, boolean dumpAll) {
        pw.print(prefix + "mDisplayId=" + getDisplayId());
        if (getRootTask() != null) {
            pw.print(" rootTaskId=" + getRootTaskId());
        }
        pw.println(" mSession=" + mSession
                + " mClient=" + mClient.asBinder());
        pw.println(prefix + "mOwnerUid=" + mOwnerUid
                + " showForAllUsers=" + showForAllUsers()
                + " package=" + mAttrs.packageName
                + " appop=" + AppOpsManager.opToName(mAppOp));
        pw.println(prefix + "mAttrs=" + mAttrs.toString(prefix));
        pw.println(prefix + "Requested w=" + mRequestedWidth
                + " h=" + mRequestedHeight
                + " mLayoutSeq=" + mLayoutSeq);
        if (mRequestedWidth != mLastRequestedWidth || mRequestedHeight != mLastRequestedHeight) {
            pw.println(prefix + "LastRequested w=" + mLastRequestedWidth
                    + " h=" + mLastRequestedHeight);
        }
        if (mIsChildWindow || mLayoutAttached) {
            pw.println(prefix + "mParentWindow=" + getParentWindow()
                    + " mLayoutAttached=" + mLayoutAttached);
        }
        if (mIsImWindow || mIsWallpaper || mIsFloatingLayer) {
            pw.println(prefix + "mIsImWindow=" + mIsImWindow
                    + " mIsWallpaper=" + mIsWallpaper
                    + " mIsFloatingLayer=" + mIsFloatingLayer);
        }
        if (dumpAll) {
            pw.print(prefix); pw.print("mBaseLayer="); pw.print(mBaseLayer);
                    pw.print(" mSubLayer="); pw.print(mSubLayer);
        }
        if (dumpAll) {
            pw.println(prefix + "mToken=" + mToken);
            if (mActivityRecord != null) {
                pw.println(prefix + "mActivityRecord=" + mActivityRecord);
                pw.print(prefix + "mAppDied=" + mAppDied);
                pw.print(prefix + "drawnStateEvaluated=" + getDrawnStateEvaluated());
                pw.println(prefix + "mightAffectAllDrawn=" + mightAffectAllDrawn());
            }
            pw.println(prefix + "mViewVisibility=0x" + Integer.toHexString(mViewVisibility)
                    + " mHaveFrame=" + mHaveFrame
                    + " mObscured=" + mObscured);
            if (mDisableFlags != 0) {
                pw.println(prefix + "mDisableFlags=" + ViewDebug.flagsToString(
                        View.class, "mSystemUiVisibility", mDisableFlags));
            }
        }
        if (!isVisibleByPolicy() || !mLegacyPolicyVisibilityAfterAnim || !mAppOpVisibility
                || isParentWindowHidden() || mPermanentlyHidden || mForceHideNonSystemOverlayWindow
                || mHiddenWhileSuspended) {
            pw.println(prefix + "mPolicyVisibility=" + isVisibleByPolicy()
                    + " mLegacyPolicyVisibilityAfterAnim=" + mLegacyPolicyVisibilityAfterAnim
                    + " mAppOpVisibility=" + mAppOpVisibility
                    + " parentHidden=" + isParentWindowHidden()
                    + " mPermanentlyHidden=" + mPermanentlyHidden
                    + " mHiddenWhileSuspended=" + mHiddenWhileSuspended
                    + " mForceHideNonSystemOverlayWindow=" + mForceHideNonSystemOverlayWindow);
        }
        if (!mRelayoutCalled || mLayoutNeeded) {
            pw.println(prefix + "mRelayoutCalled=" + mRelayoutCalled
                    + " mLayoutNeeded=" + mLayoutNeeded);
        }
        if (dumpAll) {
            pw.println(prefix + "mGivenContentInsets=" + mGivenContentInsets.toShortString(sTmpSB)
                    + " mGivenVisibleInsets=" + mGivenVisibleInsets.toShortString(sTmpSB));
            if (mTouchableInsets != 0 || mGivenInsetsPending) {
                pw.println(prefix + "mTouchableInsets=" + mTouchableInsets
                        + " mGivenInsetsPending=" + mGivenInsetsPending);
                Region region = new Region();
                getTouchableRegion(region);
                pw.println(prefix + "touchable region=" + region);
            }
            pw.println(prefix + "mFullConfiguration=" + getConfiguration());
            pw.println(prefix + "mLastReportedConfiguration=" + getLastReportedConfiguration());
        }
        pw.println(prefix + "mHasSurface=" + mHasSurface
                + " isReadyForDisplay()=" + isReadyForDisplay()
                + " mWindowRemovalAllowed=" + mWindowRemovalAllowed);
        if (hasCompatScale()) {
            pw.println(prefix + "mCompatFrame=" + mWindowFrames.mCompatFrame.toShortString(sTmpSB));
        }
        if (dumpAll) {
            mWindowFrames.dump(pw, prefix);
            pw.println(prefix + " surface=" + mAttrs.surfaceInsets.toShortString(sTmpSB));
        }
        super.dump(pw, prefix, dumpAll);
        pw.println(prefix + mWinAnimator + ":");
        mWinAnimator.dump(pw, prefix + "  ", dumpAll);
        if (mAnimatingExit || mRemoveOnExit || mDestroying || mRemoved) {
            pw.println(prefix + "mAnimatingExit=" + mAnimatingExit
                    + " mRemoveOnExit=" + mRemoveOnExit
                    + " mDestroying=" + mDestroying
                    + " mRemoved=" + mRemoved);
        }
        if (getOrientationChanging() || mAppFreezing || mReportOrientationChanged) {
            pw.println(prefix + "mOrientationChanging=" + mOrientationChanging
                    + " configOrientationChanging="
                    + (getLastReportedConfiguration().orientation != getConfiguration().orientation)
                    + " mAppFreezing=" + mAppFreezing
                    + " mReportOrientationChanged=" + mReportOrientationChanged);
        }
        if (mLastFreezeDuration != 0) {
            pw.print(prefix + "mLastFreezeDuration=");
            TimeUtils.formatDuration(mLastFreezeDuration, pw);
            pw.println();
        }
        pw.print(prefix + "mForceSeamlesslyRotate=" + mForceSeamlesslyRotate
                + " seamlesslyRotate: pending=");
        if (mPendingSeamlessRotate != null) {
            mPendingSeamlessRotate.dump(pw);
        } else {
            pw.print("null");
        }
        pw.println(" finishedFrameNumber=" + mFinishSeamlessRotateFrameNumber);

        if (mHScale != 1 || mVScale != 1) {
            pw.println(prefix + "mHScale=" + mHScale
                    + " mVScale=" + mVScale);
        }
        if (mWallpaperX != -1 || mWallpaperY != -1) {
            pw.println(prefix + "mWallpaperX=" + mWallpaperX
                    + " mWallpaperY=" + mWallpaperY);
        }
        if (mWallpaperXStep != -1 || mWallpaperYStep != -1) {
            pw.println(prefix + "mWallpaperXStep=" + mWallpaperXStep
                    + " mWallpaperYStep=" + mWallpaperYStep);
        }
        if (mWallpaperZoomOut != -1) {
            pw.println(prefix + "mWallpaperZoomOut=" + mWallpaperZoomOut);
        }
        if (mWallpaperDisplayOffsetX != Integer.MIN_VALUE
                || mWallpaperDisplayOffsetY != Integer.MIN_VALUE) {
            pw.println(prefix + "mWallpaperDisplayOffsetX=" + mWallpaperDisplayOffsetX
                    + " mWallpaperDisplayOffsetY=" + mWallpaperDisplayOffsetY);
        }
        if (mDrawLock != null) {
            pw.println(prefix + "mDrawLock=" + mDrawLock);
        }
        if (isDragResizing()) {
            pw.println(prefix + "isDragResizing=" + isDragResizing());
        }
        if (computeDragResizing()) {
            pw.println(prefix + "computeDragResizing=" + computeDragResizing());
        }
        pw.println(prefix + "isOnScreen=" + isOnScreen());
        pw.println(prefix + "isVisible=" + isVisible());
        if (!mEmbeddedDisplayContents.isEmpty()) {
            pw.println(prefix + "mEmbeddedDisplayContents=" + mEmbeddedDisplayContents);
        }
        if (dumpAll) {
            pw.println(prefix + "mRequestedInsetsState: " + mRequestedInsetsState);
        }
    }

    @Override
    String getName() {
        return Integer.toHexString(System.identityHashCode(this))
                + " " + getWindowTag();
    }

    CharSequence getWindowTag() {
        CharSequence tag = mAttrs.getTitle();
        if (tag == null || tag.length() <= 0) {
            tag = mAttrs.packageName;
        }
        return tag;
    }

    @Override
    public String toString() {
        final CharSequence title = getWindowTag();
        if (mStringNameCache == null || mLastTitle != title || mWasExiting != mAnimatingExit) {
            mLastTitle = title;
            mWasExiting = mAnimatingExit;
            mStringNameCache = "Window{" + Integer.toHexString(System.identityHashCode(this))
                    + " u" + mShowUserId
                    + " " + mLastTitle + (mAnimatingExit ? " EXITING}" : "}");
        }
        return mStringNameCache;
    }

    private void applyGravityAndUpdateFrame(WindowFrames windowFrames, Rect containingFrame,
            Rect displayFrame) {
        final int pw = containingFrame.width();
        final int ph = containingFrame.height();
        final Task task = getTask();
        final boolean inNonFullscreenContainer = !inAppWindowThatMatchesParentBounds();
        final boolean noLimits = (mAttrs.flags & FLAG_LAYOUT_NO_LIMITS) != 0;

        // We need to fit it to the display if either
        // a) The window is in a fullscreen container, or we don't have a task (we assume fullscreen
        // for the taskless windows)
        // b) If it's a secondary app window, we also need to fit it to the display unless
        // FLAG_LAYOUT_NO_LIMITS is set. This is so we place Popups, dialogs, and similar windows on
        // screen, but SurfaceViews want to be always at a specific location so we don't fit it to
        // the display.
        final boolean fitToDisplay = (task == null || !inNonFullscreenContainer)
                || ((mAttrs.type != TYPE_BASE_APPLICATION) && !noLimits);
        float x, y;
        int w,h;

        final boolean hasCompatScale = hasCompatScale();
        if ((mAttrs.flags & FLAG_SCALED) != 0) {
            if (mAttrs.width < 0) {
                w = pw;
            } else if (hasCompatScale) {
                w = (int)(mAttrs.width * mGlobalScale + .5f);
            } else {
                w = mAttrs.width;
            }
            if (mAttrs.height < 0) {
                h = ph;
            } else if (hasCompatScale) {
                h = (int)(mAttrs.height * mGlobalScale + .5f);
            } else {
                h = mAttrs.height;
            }
        } else {
            if (mAttrs.width == MATCH_PARENT) {
                w = pw;
            } else if (hasCompatScale) {
                w = (int)(mRequestedWidth * mGlobalScale + .5f);
            } else {
                w = mRequestedWidth;
            }
            if (mAttrs.height == MATCH_PARENT) {
                h = ph;
            } else if (hasCompatScale) {
                h = (int)(mRequestedHeight * mGlobalScale + .5f);
            } else {
                h = mRequestedHeight;
            }
        }

        if (hasCompatScale) {
            x = mAttrs.x * mGlobalScale;
            y = mAttrs.y * mGlobalScale;
        } else {
            x = mAttrs.x;
            y = mAttrs.y;
        }

        if (inNonFullscreenContainer && !layoutInParentFrame()) {
            // Make sure window fits in containing frame since it is in a non-fullscreen task as
            // required by {@link Gravity#apply} call.
            w = Math.min(w, pw);
            h = Math.min(h, ph);
        }

        // Set mFrame
        Gravity.apply(mAttrs.gravity, w, h, containingFrame,
                (int) (x + mAttrs.horizontalMargin * pw),
                (int) (y + mAttrs.verticalMargin * ph), windowFrames.mFrame);

        // Now make sure the window fits in the overall display frame.
        if (fitToDisplay) {
            Gravity.applyDisplay(mAttrs.gravity, displayFrame, windowFrames.mFrame);
        }

        // We need to make sure we update the CompatFrame as it is used for
        // cropping decisions, etc, on systems where we lack a decor layer.
        windowFrames.mCompatFrame.set(windowFrames.mFrame);
        if (hasCompatScale) {
            // See comparable block in computeFrameLw.
            windowFrames.mCompatFrame.scale(mInvGlobalScale);
        }
    }

    boolean isChildWindow() {
        return mIsChildWindow;
    }

    boolean layoutInParentFrame() {
        return mIsChildWindow
                && (mAttrs.privateFlags & PRIVATE_FLAG_LAYOUT_CHILD_WINDOW_IN_PARENT_FRAME) != 0;
    }

    /**
     * Returns true if any window added by an application process that if of type
     * {@link android.view.WindowManager.LayoutParams#TYPE_TOAST} or that requires that requires
     * {@link android.app.AppOpsManager#OP_SYSTEM_ALERT_WINDOW} permission should be hidden when
     * this window is visible.
     */
    boolean hideNonSystemOverlayWindowsWhenVisible() {
        return (mAttrs.privateFlags & SYSTEM_FLAG_HIDE_NON_SYSTEM_OVERLAY_WINDOWS) != 0
                && mSession.mCanHideNonSystemOverlayWindows;
    }

    /** Returns the parent window if this is a child of another window, else null. */
    WindowState getParentWindow() {
        // NOTE: We are not calling getParent() directly as the WindowState might be a child of a
        // WindowContainer that isn't a WindowState.
        return (mIsChildWindow) ? ((WindowState) super.getParent()) : null;
    }

    /** Returns the topmost parent window if this is a child of another window, else this. */
    WindowState getTopParentWindow() {
        WindowState current = this;
        WindowState topParent = current;
        while (current != null && current.mIsChildWindow) {
            current = current.getParentWindow();
            // Parent window can be null if the child is detached from it's parent already, but
            // someone still has a reference to access it. So, we return the top parent value we
            // already have instead of null.
            if (current != null) {
                topParent = current;
            }
        }
        return topParent;
    }

    boolean isParentWindowHidden() {
        final WindowState parent = getParentWindow();
        return parent != null && parent.mHidden;
    }

    private boolean isParentWindowGoneForLayout() {
        final WindowState parent = getParentWindow();
        return parent != null && parent.isGoneForLayout();
    }

    void setWillReplaceWindow(boolean animate) {
        for (int i = mChildren.size() - 1; i >= 0; i--) {
            final WindowState c = mChildren.get(i);
            c.setWillReplaceWindow(animate);
        }

        if ((mAttrs.privateFlags & PRIVATE_FLAG_WILL_NOT_REPLACE_ON_RELAUNCH) != 0
                || mAttrs.type == TYPE_APPLICATION_STARTING) {
            // We don't set replacing on starting windows since they are added by window manager and
            // not the client so won't be replaced by the client.
            return;
        }

        mWillReplaceWindow = true;
        mReplacementWindow = null;
        mAnimateReplacingWindow = animate;
    }

    void clearWillReplaceWindow() {
        mWillReplaceWindow = false;
        mReplacementWindow = null;
        mAnimateReplacingWindow = false;

        for (int i = mChildren.size() - 1; i >= 0; i--) {
            final WindowState c = mChildren.get(i);
            c.clearWillReplaceWindow();
        }
    }

    boolean waitingForReplacement() {
        if (mWillReplaceWindow) {
            return true;
        }

        for (int i = mChildren.size() - 1; i >= 0; i--) {
            final WindowState c = mChildren.get(i);
            if (c.waitingForReplacement()) {
                return true;
            }
        }
        return false;
    }

    void requestUpdateWallpaperIfNeeded() {
        final DisplayContent dc = getDisplayContent();
        if (dc != null && (mAttrs.flags & FLAG_SHOW_WALLPAPER) != 0) {
            dc.pendingLayoutChanges |= FINISH_LAYOUT_REDO_WALLPAPER;
            dc.setLayoutNeeded();
            mWmService.mWindowPlacerLocked.requestTraversal();
        }

        for (int i = mChildren.size() - 1; i >= 0; i--) {
            final WindowState c = mChildren.get(i);
            c.requestUpdateWallpaperIfNeeded();
        }
    }

    float translateToWindowX(float x) {
        float winX = x - mWindowFrames.mFrame.left;
        if (hasCompatScale()) {
            winX *= mGlobalScale;
        }
        return winX;
    }

    float translateToWindowY(float y) {
        float winY = y - mWindowFrames.mFrame.top;
        if (hasCompatScale()) {
            winY *= mGlobalScale;
        }
        return winY;
    }

    // During activity relaunch due to resize, we sometimes use window replacement
    // for only child windows (as the main window is handled by window preservation)
    // and the big surface.
    //
    // Though windows of TYPE_APPLICATION or TYPE_DRAWN_APPLICATION (as opposed to
    // TYPE_BASE_APPLICATION) are not children in the sense of an attached window,
    // we also want to replace them at such phases, as they won't be covered by window
    // preservation, and in general we expect them to return following relaunch.
    boolean shouldBeReplacedWithChildren() {
        return mIsChildWindow || mAttrs.type == TYPE_APPLICATION
                || mAttrs.type == TYPE_DRAWN_APPLICATION;
    }

    void setWillReplaceChildWindows() {
        if (shouldBeReplacedWithChildren()) {
            setWillReplaceWindow(false /* animate */);
        }
        for (int i = mChildren.size() - 1; i >= 0; i--) {
            final WindowState c = mChildren.get(i);
            c.setWillReplaceChildWindows();
        }
    }

    WindowState getReplacingWindow() {
        if (mAnimatingExit && mWillReplaceWindow && mAnimateReplacingWindow) {
            return this;
        }
        for (int i = mChildren.size() - 1; i >= 0; i--) {
            final WindowState c = mChildren.get(i);
            final WindowState replacing = c.getReplacingWindow();
            if (replacing != null) {
                return replacing;
            }
        }
        return null;
    }

    int getRotationAnimationHint() {
        if (mActivityRecord != null) {
            return mActivityRecord.mRotationAnimationHint;
        } else {
            return -1;
        }
    }

    // This must be called while inside a transaction.
    boolean performShowLocked() {
        if (!showToCurrentUser()) {
            if (DEBUG_VISIBILITY) Slog.w(TAG, "hiding " + this + ", belonging to " + mOwnerUid);
            clearPolicyVisibilityFlag(VISIBLE_FOR_USER);
            return false;
        }

        logPerformShow("performShow on ");

        final int drawState = mWinAnimator.mDrawState;
        if ((drawState == HAS_DRAWN || drawState == READY_TO_SHOW) && mActivityRecord != null) {
            if (mAttrs.type != TYPE_APPLICATION_STARTING) {
                mActivityRecord.onFirstWindowDrawn(this, mWinAnimator);
            } else {
                mActivityRecord.onStartingWindowDrawn();
            }
        }

        if (mWinAnimator.mDrawState != READY_TO_SHOW || !isReadyForDisplay()) {
            return false;
        }

        logPerformShow("Showing ");

        mWmService.enableScreenIfNeededLocked();
        mWinAnimator.applyEnterAnimationLocked();

        // Force the show in the next prepareSurfaceLocked() call.
        mWinAnimator.mLastAlpha = -1;
        if (DEBUG_ANIM) Slog.v(TAG,
                "performShowLocked: mDrawState=HAS_DRAWN in " + this);
        mWinAnimator.mDrawState = HAS_DRAWN;
        mWmService.scheduleAnimationLocked();

        if (mHidden) {
            mHidden = false;
            final DisplayContent displayContent = getDisplayContent();

            for (int i = mChildren.size() - 1; i >= 0; --i) {
                final WindowState c = mChildren.get(i);
                if (c.mWinAnimator.mSurfaceController != null) {
                    c.performShowLocked();
                    // It hadn't been shown, which means layout not performed on it, so now we
                    // want to make sure to do a layout.  If called from within the transaction
                    // loop, this will cause it to restart with a new layout.
                    if (displayContent != null) {
                        displayContent.setLayoutNeeded();
                    }
                }
            }
        }

        return true;
    }

    private void logPerformShow(String prefix) {
        if (DEBUG_VISIBILITY
                || (DEBUG_STARTING_WINDOW_VERBOSE && mAttrs.type == TYPE_APPLICATION_STARTING)) {
            Slog.v(TAG, prefix + this
                    + ": mDrawState=" + mWinAnimator.drawStateToString()
                    + " readyForDisplay=" + isReadyForDisplay()
                    + " starting=" + (mAttrs.type == TYPE_APPLICATION_STARTING)
                    + " during animation: policyVis=" + isVisibleByPolicy()
                    + " parentHidden=" + isParentWindowHidden()
                    + " tok.visibleRequested="
                    + (mActivityRecord != null && mActivityRecord.mVisibleRequested)
                    + " tok.visible=" + (mActivityRecord != null && mActivityRecord.isVisible())
                    + " animating=" + isAnimating(TRANSITION | PARENTS)
                    + " tok animating="
                    + (mActivityRecord != null && mActivityRecord.isAnimating(TRANSITION | PARENTS))
                    + " Callers=" + Debug.getCallers(4));
        }
    }

    WindowInfo getWindowInfo() {
        WindowInfo windowInfo = WindowInfo.obtain();
        windowInfo.displayId = getDisplayId();
        windowInfo.type = mAttrs.type;
        windowInfo.layer = mLayer;
        windowInfo.token = mClient.asBinder();
        if (mActivityRecord != null) {
            windowInfo.activityToken = mActivityRecord.appToken.asBinder();
        }
        windowInfo.title = mAttrs.accessibilityTitle;
        // Panel windows have no public way to set the a11y title directly. Use the
        // regular title as a fallback.
        final boolean isPanelWindow = (mAttrs.type >= WindowManager.LayoutParams.FIRST_SUB_WINDOW)
                && (mAttrs.type <= WindowManager.LayoutParams.LAST_SUB_WINDOW);
        // Accessibility overlays should have titles that work for accessibility, and can't set
        // the a11y title themselves.
        final boolean isAccessibilityOverlay =
                windowInfo.type == WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY;
        if (TextUtils.isEmpty(windowInfo.title) && (isPanelWindow || isAccessibilityOverlay)) {
            final CharSequence title = mAttrs.getTitle();
            windowInfo.title = TextUtils.isEmpty(title) ? null : title;
        }
        windowInfo.accessibilityIdOfAnchor = mAttrs.accessibilityIdOfAnchor;
        windowInfo.focused = isFocused();
        Task task = getTask();
        windowInfo.inPictureInPicture = (task != null) && task.inPinnedWindowingMode();
        windowInfo.hasFlagWatchOutsideTouch =
                (mAttrs.flags & WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH) != 0;

        if (mIsChildWindow) {
            windowInfo.parentToken = getParentWindow().mClient.asBinder();
        }

        final int childCount = mChildren.size();
        if (childCount > 0) {
            if (windowInfo.childTokens == null) {
                windowInfo.childTokens = new ArrayList(childCount);
            }
            for (int j = 0; j < childCount; j++) {
                final WindowState child = mChildren.get(j);
                windowInfo.childTokens.add(child.mClient.asBinder());
            }
        }
        return windowInfo;
    }

    @Override
    boolean forAllWindows(ToBooleanFunction<WindowState> callback, boolean traverseTopToBottom) {
        if (mChildren.isEmpty()) {
            // The window has no children so we just return it.
            return applyInOrderWithImeWindows(callback, traverseTopToBottom);
        }

        if (traverseTopToBottom) {
            return forAllWindowTopToBottom(callback);
        } else {
            return forAllWindowBottomToTop(callback);
        }
    }

    private boolean forAllWindowBottomToTop(ToBooleanFunction<WindowState> callback) {
        // We want to consume the negative sublayer children first because they need to appear
        // below the parent, then this window (the parent), and then the positive sublayer children
        // because they need to appear above the parent.
        int i = 0;
        final int count = mChildren.size();
        WindowState child = mChildren.get(i);

        while (i < count && child.mSubLayer < 0) {
            if (child.applyInOrderWithImeWindows(callback, false /* traverseTopToBottom */)) {
                return true;
            }
            i++;
            if (i >= count) {
                break;
            }
            child = mChildren.get(i);
        }

        if (applyInOrderWithImeWindows(callback, false /* traverseTopToBottom */)) {
            return true;
        }

        while (i < count) {
            if (child.applyInOrderWithImeWindows(callback, false /* traverseTopToBottom */)) {
                return true;
            }
            i++;
            if (i >= count) {
                break;
            }
            child = mChildren.get(i);
        }

        return false;
    }

    private boolean forAllWindowTopToBottom(ToBooleanFunction<WindowState> callback) {
        // We want to consume the positive sublayer children first because they need to appear
        // above the parent, then this window (the parent), and then the negative sublayer children
        // because they need to appear above the parent.
        int i = mChildren.size() - 1;
        WindowState child = mChildren.get(i);

        while (i >= 0 && child.mSubLayer >= 0) {
            if (child.applyInOrderWithImeWindows(callback, true /* traverseTopToBottom */)) {
                return true;
            }
            --i;
            if (i < 0) {
                break;
            }
            child = mChildren.get(i);
        }

        if (applyInOrderWithImeWindows(callback, true /* traverseTopToBottom */)) {
            return true;
        }

        while (i >= 0) {
            if (child.applyInOrderWithImeWindows(callback, true /* traverseTopToBottom */)) {
                return true;
            }
            --i;
            if (i < 0) {
                break;
            }
            child = mChildren.get(i);
        }

        return false;
    }

    private boolean applyImeWindowsIfNeeded(ToBooleanFunction<WindowState> callback,
            boolean traverseTopToBottom) {
        // If this window is the current IME target, so we need to process the IME windows
        // directly above it. The exception is if we are in split screen
        // in which case we process the IME at the DisplayContent level to
        // ensure it is above the docked divider.
        if (isImeLayeringTarget() && !inSplitScreenWindowingMode()) {
            if (getDisplayContent().forAllImeWindows(callback, traverseTopToBottom)) {
                return true;
            }
        }
        return false;
    }

    private boolean applyInOrderWithImeWindows(ToBooleanFunction<WindowState> callback,
            boolean traverseTopToBottom) {
        if (traverseTopToBottom) {
            if (applyImeWindowsIfNeeded(callback, traverseTopToBottom)
                    || callback.apply(this)) {
                return true;
            }
        } else {
            if (callback.apply(this)
                    || applyImeWindowsIfNeeded(callback, traverseTopToBottom)) {
                return true;
            }
        }
        return false;
    }

    WindowState getWindow(Predicate<WindowState> callback) {
        if (mChildren.isEmpty()) {
            return callback.test(this) ? this : null;
        }

        // We want to consume the positive sublayer children first because they need to appear
        // above the parent, then this window (the parent), and then the negative sublayer children
        // because they need to appear above the parent.
        int i = mChildren.size() - 1;
        WindowState child = mChildren.get(i);

        while (i >= 0 && child.mSubLayer >= 0) {
            if (callback.test(child)) {
                return child;
            }
            --i;
            if (i < 0) {
                break;
            }
            child = mChildren.get(i);
        }

        if (callback.test(this)) {
            return this;
        }

        while (i >= 0) {
            if (callback.test(child)) {
                return child;
            }
            --i;
            if (i < 0) {
                break;
            }
            child = mChildren.get(i);
        }

        return null;
    }

    /**
     * @return True if we our one of our ancestors has {@link #mAnimatingExit} set to true, false
     *         otherwise.
     */
    @VisibleForTesting
    boolean isSelfOrAncestorWindowAnimatingExit() {
        WindowState window = this;
        do {
            if (window.mAnimatingExit) {
                return true;
            }
            window = window.getParentWindow();
        } while (window != null);
        return false;
    }

    void onExitAnimationDone() {
        if (DEBUG_ANIM) Slog.v(TAG, "onExitAnimationDone in " + this
                + ": exiting=" + mAnimatingExit + " remove=" + mRemoveOnExit
                + " selfAnimating=" + isAnimating());

        if (!mChildren.isEmpty()) {
            // Copying to a different list as multiple children can be removed.
            final ArrayList<WindowState> childWindows = new ArrayList<>(mChildren);
            for (int i = childWindows.size() - 1; i >= 0; i--) {
                childWindows.get(i).onExitAnimationDone();
            }
        }

        if (mWinAnimator.mEnteringAnimation) {
            mWinAnimator.mEnteringAnimation = false;
            mWmService.requestTraversal();
            // System windows don't have an activity and an app token as a result, but need a way
            // to be informed about their entrance animation end.
            if (mActivityRecord == null) {
                try {
                    mClient.dispatchWindowShown();
                } catch (RemoteException e) {
                }
            }
        }

        if (isAnimating()) {
            return;
        }
        if (mWmService.mAccessibilityController != null) {
            mWmService.mAccessibilityController.onSomeWindowResizedOrMoved(getDisplayId());
        }

        if (!isSelfOrAncestorWindowAnimatingExit()) {
            return;
        }

        ProtoLog.v(WM_DEBUG_ADD_REMOVE, "Exit animation finished in %s: remove=%b",
                this, mRemoveOnExit);

        mDestroying = true;

        final boolean hasSurface = mWinAnimator.hasSurface();

        // Use pendingTransaction here so hide is done the same transaction as the other
        // animations when exiting
        mWinAnimator.hide(getPendingTransaction(), "onExitAnimationDone");

        // If we have an app token, we ask it to destroy the surface for us, so that it can take
        // care to ensure the activity has actually stopped and the surface is not still in use.
        // Otherwise we add the service to mDestroySurface and allow it to be processed in our next
        // transaction.
        if (mActivityRecord != null) {
            mActivityRecord.destroySurfaces();
        } else {
            if (hasSurface) {
                mWmService.mDestroySurface.add(this);
            }
            if (mRemoveOnExit) {
                mWmService.mPendingRemove.add(this);
                mRemoveOnExit = false;
            }
        }
        mAnimatingExit = false;
        getDisplayContent().mWallpaperController.hideWallpapers(this);
    }

    boolean clearAnimatingFlags() {
        boolean didSomething = false;
        // We don't want to clear it out for windows that get replaced, because the
        // animation depends on the flag to remove the replaced window.
        //
        // We also don't clear the mAnimatingExit flag for windows which have the
        // mRemoveOnExit flag. This indicates an explicit remove request has been issued
        // by the client. We should let animation proceed and not clear this flag or
        // they won't eventually be removed by WindowStateAnimator#finishExit.
        if (!mWillReplaceWindow && !mRemoveOnExit) {
            // Clear mAnimating flag together with mAnimatingExit. When animation
            // changes from exiting to entering, we need to clear this flag until the
            // new animation gets applied, so that isAnimationStarting() becomes true
            // until then.
            // Otherwise applySurfaceChangesTransaction will fail to skip surface
            // placement for this window during this period, one or more frame will
            // show up with wrong position or scale.
            if (mAnimatingExit) {
                mAnimatingExit = false;
                didSomething = true;
            }
            if (mDestroying) {
                mDestroying = false;
                mWmService.mDestroySurface.remove(this);
                didSomething = true;
            }
        }

        for (int i = mChildren.size() - 1; i >= 0; --i) {
            didSomething |= (mChildren.get(i)).clearAnimatingFlags();
        }

        return didSomething;
    }

    public boolean isRtl() {
        return getConfiguration().getLayoutDirection() == View.LAYOUT_DIRECTION_RTL;
    }

    void updateReportedVisibility(UpdateReportedVisibilityResults results) {
        for (int i = mChildren.size() - 1; i >= 0; --i) {
            final WindowState c = mChildren.get(i);
            c.updateReportedVisibility(results);
        }

        if (mAppFreezing || mViewVisibility != View.VISIBLE
                || mAttrs.type == TYPE_APPLICATION_STARTING
                || mDestroying) {
            return;
        }
        if (DEBUG_VISIBILITY) {
            Slog.v(TAG, "Win " + this + ": isDrawn=" + isDrawn()
                    + ", animating=" + isAnimating(TRANSITION | PARENTS));
            if (!isDrawn()) {
                Slog.v(TAG, "Not displayed: s=" + mWinAnimator.mSurfaceController
                        + " pv=" + isVisibleByPolicy()
                        + " mDrawState=" + mWinAnimator.mDrawState
                        + " ph=" + isParentWindowHidden()
                        + " th=" + (mActivityRecord != null && mActivityRecord.mVisibleRequested)
                        + " a=" + isAnimating(TRANSITION | PARENTS));
            }
        }

        results.numInteresting++;
        if (isDrawn()) {
            results.numDrawn++;
            if (!isAnimating(TRANSITION | PARENTS)) {
                results.numVisible++;
            }
            results.nowGone = false;
        } else if (isAnimating(TRANSITION | PARENTS)) {
            results.nowGone = false;
        }
    }

    /**
     * Expand the given rectangle by this windows surface insets. This
     * takes you from the 'window size' to the 'surface size'.
     * The surface insets are positive in each direction, so we inset by
     * the inverse.
     */
    void expandForSurfaceInsets(Rect r) {
        r.inset(-mAttrs.surfaceInsets.left,
                -mAttrs.surfaceInsets.top,
                -mAttrs.surfaceInsets.right,
                -mAttrs.surfaceInsets.bottom);
    }

    boolean surfaceInsetsChanging() {
        return !mLastSurfaceInsets.equals(mAttrs.surfaceInsets);
    }

    int relayoutVisibleWindow(int result) {
        final boolean wasVisible = isVisible();

        result |= (!wasVisible || !isDrawn()) ? RELAYOUT_RES_FIRST_TIME : 0;

        if (mAnimatingExit) {
            Slog.d(TAG, "relayoutVisibleWindow: " + this + " mAnimatingExit=true, mRemoveOnExit="
                    + mRemoveOnExit + ", mDestroying=" + mDestroying);

            // Cancel the existing exit animation for the next enter animation.
            if (isAnimating()) {
                cancelAnimation();
                destroySurfaceUnchecked();
            }
            mAnimatingExit = false;
        }
        if (mDestroying) {
            mDestroying = false;
            mWmService.mDestroySurface.remove(this);
        }
        if (!wasVisible) {
            mWinAnimator.mEnterAnimationPending = true;
        }

        mLastVisibleLayoutRotation = getDisplayContent().getRotation();

        mWinAnimator.mEnteringAnimation = true;

        Trace.traceBegin(TRACE_TAG_WINDOW_MANAGER, "prepareToDisplay");
        try {
            prepareWindowToDisplayDuringRelayout(wasVisible);
        } finally {
            Trace.traceEnd(TRACE_TAG_WINDOW_MANAGER);
        }

        if (isDragResizeChanged()) {
            setDragResizing();
        }
        final boolean freeformResizing = isDragResizing()
                && getResizeMode() == DRAG_RESIZE_MODE_FREEFORM;
        final boolean dockedResizing = isDragResizing()
                && getResizeMode() == DRAG_RESIZE_MODE_DOCKED_DIVIDER;
        result |= freeformResizing ? RELAYOUT_RES_DRAG_RESIZING_FREEFORM : 0;
        result |= dockedResizing ? RELAYOUT_RES_DRAG_RESIZING_DOCKED : 0;
        return result;
    }

    /**
     * @return True if this window has been laid out at least once; false otherwise.
     */
    boolean isLaidOut() {
        return mLayoutSeq != -1;
    }

    /**
     * Add the DisplayContent of the embedded display which is re-parented to this window to
     * the list of embedded displays.
     *
     * @param dc DisplayContent of the re-parented embedded display.
     * @return {@code true} if the giving DisplayContent is added, {@code false} otherwise.
     */
    boolean addEmbeddedDisplayContent(DisplayContent dc) {
        return mEmbeddedDisplayContents.add(dc);
    }

    /**
     * Remove the DisplayContent of the embedded display which is re-parented to this window from
     * the list of embedded displays.
     *
     * @param dc DisplayContent of the re-parented embedded display.
     * @return {@code true} if the giving DisplayContent is removed, {@code false} otherwise.
     */
    boolean removeEmbeddedDisplayContent(DisplayContent dc) {
        return mEmbeddedDisplayContents.remove(dc);
    }

    /** Updates the last frames and relative frames to the current ones. */
    void updateLastFrames() {
        mWindowFrames.mLastFrame.set(mWindowFrames.mFrame);
        mWindowFrames.mLastRelFrame.set(mWindowFrames.mRelFrame);
    }

    /**
     * Clears factors that would cause report-resize.
     */
    void onResizeHandled() {
        mWindowFrames.onResizeHandled();
    }

    @Override
    protected boolean isSelfAnimating(int flags, int typesToCheck) {
        if (mControllableInsetProvider != null) {
            return false;
        }
        return super.isSelfAnimating(flags, typesToCheck);
    }

    void startAnimation(Animation anim) {

        // If we are an inset provider, all our animations are driven by the inset client.
        if (mControllableInsetProvider != null) {
            return;
        }

        final DisplayInfo displayInfo = getDisplayInfo();
        anim.initialize(mWindowFrames.mFrame.width(), mWindowFrames.mFrame.height(),
                displayInfo.appWidth, displayInfo.appHeight);
        anim.restrictDuration(MAX_ANIMATION_DURATION);
        anim.scaleCurrentDuration(mWmService.getWindowAnimationScaleLocked());
        final AnimationAdapter adapter = new LocalAnimationAdapter(
                new WindowAnimationSpec(anim, mSurfacePosition, false /* canSkipFirstFrame */,
                        0 /* windowCornerRadius */),
                mWmService.mSurfaceAnimationRunner);
        startAnimation(getPendingTransaction(), adapter);
        commitPendingTransaction();
    }

    private void startMoveAnimation(int left, int top) {

        // If we are an inset provider, all our animations are driven by the inset client.
        if (mControllableInsetProvider != null) {
            return;
        }

        if (DEBUG_ANIM) Slog.v(TAG, "Setting move animation on " + this);
        final Point oldPosition = new Point();
        final Point newPosition = new Point();
        transformFrameToSurfacePosition(mWindowFrames.mLastFrame.left, mWindowFrames.mLastFrame.top,
                oldPosition);
        transformFrameToSurfacePosition(left, top, newPosition);
        final AnimationAdapter adapter = new LocalAnimationAdapter(
                new MoveAnimationSpec(oldPosition.x, oldPosition.y, newPosition.x, newPosition.y),
                mWmService.mSurfaceAnimationRunner);
        startAnimation(getPendingTransaction(), adapter);
    }

    private void startAnimation(Transaction t, AnimationAdapter adapter) {
        startAnimation(t, adapter, mWinAnimator.mLastHidden, ANIMATION_TYPE_WINDOW_ANIMATION);
    }

    @Override
    protected void onAnimationFinished(@AnimationType int type, AnimationAdapter anim) {
        super.onAnimationFinished(type, anim);
        mWinAnimator.onAnimationFinished();
    }

    /**
     * Retrieves the current transformation matrix of the window, relative to the display.
     *
     * @param float9 A temporary array of 9 floats.
     * @param outMatrix Matrix to fill in the transformation.
     */
    void getTransformationMatrix(float[] float9, Matrix outMatrix) {
        float9[Matrix.MSCALE_X] = mWinAnimator.mDsDx;
        float9[Matrix.MSKEW_Y] = mWinAnimator.mDtDx;
        float9[Matrix.MSKEW_X] = mWinAnimator.mDtDy;
        float9[Matrix.MSCALE_Y] = mWinAnimator.mDsDy;
        transformSurfaceInsetsPosition(mTmpPoint, mAttrs.surfaceInsets);
        int x = mSurfacePosition.x + mTmpPoint.x;
        int y = mSurfacePosition.y + mTmpPoint.y;

        // We might be on a display which has been re-parented to a view in another window, so here
        // computes the global location of our display.
        DisplayContent dc = getDisplayContent();
        while (dc != null && dc.getParentWindow() != null) {
            final WindowState displayParent = dc.getParentWindow();
            x += displayParent.mWindowFrames.mFrame.left
                    + (dc.getLocationInParentWindow().x * displayParent.mGlobalScale + 0.5f);
            y += displayParent.mWindowFrames.mFrame.top
                    + (dc.getLocationInParentWindow().y * displayParent.mGlobalScale + 0.5f);
            dc = displayParent.getDisplayContent();
        }

        // If changed, also adjust transformFrameToSurfacePosition
        final WindowContainer parent = getParent();
        if (isChildWindow()) {
            final WindowState parentWindow = getParentWindow();
            x += parentWindow.mWindowFrames.mFrame.left - parentWindow.mAttrs.surfaceInsets.left;
            y += parentWindow.mWindowFrames.mFrame.top - parentWindow.mAttrs.surfaceInsets.top;
        } else if (parent != null) {
            final Rect parentBounds = parent.getBounds();
            x += parentBounds.left;
            y += parentBounds.top;
        }
        float9[Matrix.MTRANS_X] = x;
        float9[Matrix.MTRANS_Y] = y;
        float9[Matrix.MPERSP_0] = 0;
        float9[Matrix.MPERSP_1] = 0;
        float9[Matrix.MPERSP_2] = 1;
        outMatrix.setValues(float9);
    }

    // TODO: Hack to work around the number of states ActivityRecord needs to access without having
    // access to its windows children. Need to investigate re-writing
    // {@link ActivityRecord#updateReportedVisibilityLocked} so this can be removed.
    static final class UpdateReportedVisibilityResults {
        int numInteresting;
        int numVisible;
        int numDrawn;
        boolean nowGone = true;

        void reset() {
            numInteresting = 0;
            numVisible = 0;
            numDrawn = 0;
            nowGone = true;
        }
    }

    private static final class WindowId extends IWindowId.Stub {
        private final WeakReference<WindowState> mOuter;

        private WindowId(WindowState outer) {

            // Use a weak reference for the outer class. This is important to prevent the following
            // leak: Since we send this class to the client process, binder will keep it alive as
            // long as the client keeps it alive. Now, if the window is removed, we need to clear
            // out our reference so even though this class is kept alive we don't leak WindowState,
            // which can keep a whole lot of classes alive.
            mOuter = new WeakReference<>(outer);
        }

        @Override
        public void registerFocusObserver(IWindowFocusObserver observer) {
            final WindowState outer = mOuter.get();
            if (outer != null) {
                outer.registerFocusObserver(observer);
            }
        }
        @Override
        public void unregisterFocusObserver(IWindowFocusObserver observer) {
            final WindowState outer = mOuter.get();
            if (outer != null) {
                outer.unregisterFocusObserver(observer);
            }
        }
        @Override
        public boolean isFocused() {
            final WindowState outer = mOuter.get();
            if (outer != null) {
                synchronized (outer.mWmService.mGlobalLock) {
                    return outer.isFocused();
                }
            }
            return false;
        }
    }


    @Override
    boolean shouldMagnify() {
        if (mAttrs.type == TYPE_ACCESSIBILITY_MAGNIFICATION_OVERLAY
                || mAttrs.type == TYPE_INPUT_METHOD
                || mAttrs.type == TYPE_INPUT_METHOD_DIALOG
                || mAttrs.type == TYPE_MAGNIFICATION_OVERLAY
                || mAttrs.type == TYPE_NAVIGATION_BAR
                // It's tempting to wonder: Have we forgotten the rounded corners overlay?
                // worry not: it's a fake TYPE_NAVIGATION_BAR_PANEL
                || mAttrs.type == TYPE_NAVIGATION_BAR_PANEL) {
            return false;
        }
        return true;
    }

    @Override
    SurfaceSession getSession() {
        if (mSession.mSurfaceSession != null) {
            return mSession.mSurfaceSession;
        } else {
            return getParent().getSession();
        }
    }

    @Override
    boolean needsZBoost() {
        final InsetsControlTarget target = getDisplayContent().getImeTarget(IME_TARGET_LAYERING);
        if (mIsImWindow && target != null) {
            final ActivityRecord activity = target.getWindow().mActivityRecord;
            if (activity != null) {
                return activity.needsZBoost();
            }
        }
        return mWillReplaceWindow;
    }

    private void applyDims() {
        if (!mAnimatingExit && mAppDied) {
            mIsDimming = true;
            getDimmer().dimAbove(getSyncTransaction(), this, DEFAULT_DIM_AMOUNT_DEAD_WINDOW);
        } else if (((mAttrs.flags & FLAG_DIM_BEHIND) != 0 || shouldDrawBlurBehind())
                   && isVisibleNow() && !mHidden) {
            // Only show the Dimmer when the following is satisfied:
            // 1. The window has the flag FLAG_DIM_BEHIND or blur behind is requested
            // 2. The WindowToken is not hidden so dims aren't shown when the window is exiting.
            // 3. The WS is considered visible according to the isVisible() method
            // 4. The WS is not hidden.
            mIsDimming = true;
            final float dimAmount = (mAttrs.flags & FLAG_DIM_BEHIND) != 0 ? mAttrs.dimAmount : 0;
            final int blurRadius = shouldDrawBlurBehind() ? mAttrs.getBlurBehindRadius() : 0;
            getDimmer().dimBelow(getSyncTransaction(), this, dimAmount, blurRadius);
        }
    }

    private boolean shouldDrawBlurBehind() {
        return (mAttrs.flags & FLAG_BLUR_BEHIND) != 0 && mWmService.mBlurController.mBlurEnabled;
    }

    /**
     * Notifies SF about the priority of the window, if it changed. SF then uses this information
     * to decide which window's desired rendering rate should have a priority when deciding about
     * the refresh rate of the screen. Priority
     * {@link RefreshRatePolicy#LAYER_PRIORITY_FOCUSED_WITH_MODE} is considered the highest.
     */
    @VisibleForTesting
    void updateFrameRateSelectionPriorityIfNeeded() {
        RefreshRatePolicy refreshRatePolicy =
                getDisplayContent().getDisplayPolicy().getRefreshRatePolicy();
        final int priority = refreshRatePolicy.calculatePriority(this);
        if (mFrameRateSelectionPriority != priority) {
            mFrameRateSelectionPriority = priority;
            getPendingTransaction().setFrameRateSelectionPriority(mSurfaceControl,
                    mFrameRateSelectionPriority);
        }

        final float refreshRate = refreshRatePolicy.getPreferredRefreshRate(this);
        if (mDenyListFrameRate != refreshRate) {
            mDenyListFrameRate = refreshRate;
            getPendingTransaction().setFrameRate(
                    mSurfaceControl, mDenyListFrameRate, Surface.FRAME_RATE_COMPATIBILITY_EXACT);
        }
    }

    private void updateGlobalScaleIfNeeded() {
        if (mLastGlobalScale != mGlobalScale) {
            getPendingTransaction().setMatrix(getSurfaceControl(),
                mGlobalScale, 0, 0, mGlobalScale);
            mLastGlobalScale = mGlobalScale;
        }
    }

    @Override
    void prepareSurfaces() {
        mIsDimming = false;
        applyDims();
        updateSurfacePositionNonOrganized();
        // Send information to SufaceFlinger about the priority of the current window.
        updateFrameRateSelectionPriorityIfNeeded();
        if (isVisibleRequested()) updateGlobalScaleIfNeeded();

        mWinAnimator.prepareSurfaceLocked(getSyncTransaction());
        super.prepareSurfaces();
    }

    @Override
    @VisibleForTesting
    void updateSurfacePosition(Transaction t) {
        if (mSurfaceControl == null) {
            return;
        }
        if (mWmService.mWindowPlacerLocked.isLayoutDeferred() || isGoneForLayout()) {
            // Since this relies on mWindowFrames, changes made while layout is deferred are
            // likely to be invalid. Similarly, if it's goneForLayout, mWindowFrames may not be
            // up-to-date and thus can't be relied on.
            return;
        }

        transformFrameToSurfacePosition(mWindowFrames.mFrame.left, mWindowFrames.mFrame.top,
                mSurfacePosition);

        // Freeze position while we're unrotated, so the surface remains at the position it was
        // prior to the rotation.
        if (!mSurfaceAnimator.hasLeash() && mPendingSeamlessRotate == null
                && !mLastSurfacePosition.equals(mSurfacePosition)) {
            t.setPosition(mSurfaceControl, mSurfacePosition.x, mSurfacePosition.y);
            mLastSurfacePosition.set(mSurfacePosition.x, mSurfacePosition.y);
            if (surfaceInsetsChanging() && mWinAnimator.hasSurface()) {
                mLastSurfaceInsets.set(mAttrs.surfaceInsets);
                t.deferTransactionUntil(mSurfaceControl,
                        mWinAnimator.mSurfaceController.mSurfaceControl,
                        getFrameNumber());
            }
        }
    }

    void transformFrameToSurfacePosition(int left, int top, Point outPoint) {
        outPoint.set(left, top);

        // If changed, also adjust getTransformationMatrix
        final WindowContainer parentWindowContainer = getParent();
        if (isChildWindow()) {
            // TODO: This probably falls apart at some point and we should
            // actually compute relative coordinates.

            // Since the parent was outset by its surface insets, we need to undo the outsetting
            // with insetting by the same amount.
            final WindowState parent = getParentWindow();
            transformSurfaceInsetsPosition(mTmpPoint, parent.mAttrs.surfaceInsets);
            outPoint.offset(-parent.mWindowFrames.mFrame.left + mTmpPoint.x,
                    -parent.mWindowFrames.mFrame.top + mTmpPoint.y);
        } else if (parentWindowContainer != null) {
            final Rect parentBounds = parentWindowContainer.getBounds();
            outPoint.offset(-parentBounds.left, -parentBounds.top);
        }

        Task rootTask = getRootTask();

        // If we have root task outsets, that means the top-left
        // will be outset, and we need to inset ourselves
        // to account for it. If we actually have shadows we will
        // then un-inset ourselves by the surfaceInsets.
        if (rootTask != null) {
            final int outset = rootTask.getTaskOutset();
            outPoint.offset(outset, outset);
        }

        // Expand for surface insets. See WindowState.expandForSurfaceInsets.
        transformSurfaceInsetsPosition(mTmpPoint, mAttrs.surfaceInsets);
        outPoint.offset(-mTmpPoint.x, -mTmpPoint.y);

        outPoint.y += mSurfaceTranslationY;
    }

    /**
     * The surface insets from layout parameter are in application coordinate. If the window is
     * scaled, the insets also need to be scaled for surface position in global coordinate.
     */
    private void transformSurfaceInsetsPosition(Point outPos, Rect surfaceInsets) {
        if (!hasCompatScale()) {
            outPos.x = surfaceInsets.left;
            outPos.y = surfaceInsets.top;
            return;
        }
        outPos.x = (int) (surfaceInsets.left * mGlobalScale + 0.5f);
        outPos.y = (int) (surfaceInsets.top * mGlobalScale + 0.5f);
    }

    boolean needsRelativeLayeringToIme() {
        // We only use the relative layering mode in split screen, as part of elevating the IME
        // and windows above it's target above the docked divider.
        if (!inSplitScreenWindowingMode()) {
            return false;
        }

        if (isChildWindow()) {
            // If we are a child of the input method target we need this promotion.
            if (getParentWindow().isImeLayeringTarget()) {
                return true;
            }
        } else if (mActivityRecord != null) {
            // Likewise if we share a token with the Input method target and are ordered
            // above it but not necessarily a child (e.g. a Dialog) then we also need
            // this promotion.
            final WindowState imeTarget = getImeLayeringTarget();
            boolean inTokenWithAndAboveImeTarget = imeTarget != null && imeTarget != this
                    && imeTarget.mToken == mToken
                    && mAttrs.type != TYPE_APPLICATION_STARTING
                    && getParent() != null
                    && imeTarget.compareTo(this) <= 0;
            return inTokenWithAndAboveImeTarget;
        }
        return false;
    }

    /**
     * Get IME target that should host IME when this window's display has a parent.
     * Note: IME is never hosted by a display that has a parent.
     * When window calling
     * {@link android.view.inputmethod.InputMethodManager#showSoftInput(View, int)} is unknown,
     * use {@link DisplayContent#getImeControlTarget()} instead.
     *
     * @return {@link InsetsControlTarget} of host that controls the IME.
     *         When window is doesn't have a parent, it is returned as-is.
     */
    InsetsControlTarget getImeControlTarget() {
        final DisplayContent dc = getDisplayContent();
        final WindowState parentWindow = dc.getParentWindow();

        // If target's display has a parent, IME is displayed in the parent display.
        return dc.getImeHostOrFallback(parentWindow != null ? parentWindow : this);
    }

    @Override
    void assignLayer(Transaction t, int layer) {
        // See comment in assignRelativeLayerForImeTargetChild
        if (needsRelativeLayeringToIme()) {
            getDisplayContent().assignRelativeLayerForImeTargetChild(t, this);
            return;
        }
        super.assignLayer(t, layer);
    }

    boolean isDimming() {
        return mIsDimming;
    }

    // TODO(b/70040778): We should aim to eliminate the last user of TYPE_APPLICATION_MEDIA
    // then we can drop all negative layering on the windowing side and simply inherit
    // the default implementation here.
    public void assignChildLayers(Transaction t) {
        // The surface of the main window might be preserved. So the child window on top of the main
        // window should be also on top of the preserved surface.
        int layer = PRESERVED_SURFACE_LAYER + 1;
        for (int i = 0; i < mChildren.size(); i++) {
            final WindowState w = mChildren.get(i);

            // APPLICATION_MEDIA_OVERLAY needs to go above APPLICATION_MEDIA
            // while they both need to go below the main window. However the
            // relative layering of multiple APPLICATION_MEDIA/OVERLAY has never
            // been defined and so we can use static layers and leave it that way.
            if (w.mAttrs.type == TYPE_APPLICATION_MEDIA) {
                if (mWinAnimator.hasSurface()) {
                    w.assignRelativeLayer(t, mWinAnimator.mSurfaceController.mSurfaceControl, -2);
                } else {
                    w.assignLayer(t, -2);
                }
            } else if (w.mAttrs.type == TYPE_APPLICATION_MEDIA_OVERLAY) {
                if (mWinAnimator.hasSurface()) {
                    w.assignRelativeLayer(t, mWinAnimator.mSurfaceController.mSurfaceControl, -1);
                } else {
                    w.assignLayer(t, -1);
                }
            } else {
                w.assignLayer(t, layer);
            }
            w.assignChildLayers(t);
            layer++;
        }
    }

    /**
     * Update a tap exclude region identified by provided id. The requested area will be clipped to
     * the window bounds.
     */
    void updateTapExcludeRegion(Region region) {
        final DisplayContent currentDisplay = getDisplayContent();
        if (currentDisplay == null) {
            throw new IllegalStateException("Trying to update window not attached to any display.");
        }

        // Clear the tap excluded region if the region passed in is null or empty.
        if (region == null || region.isEmpty()) {
            mTapExcludeRegion.setEmpty();
            // Remove this window from mTapExcludeProvidingWindows since it won't be providing
            // tap exclude regions.
            currentDisplay.mTapExcludeProvidingWindows.remove(this);
        } else {
            mTapExcludeRegion.set(region);
            // Make sure that this window is registered as one that provides a tap exclude region
            // for its containing display.
            currentDisplay.mTapExcludeProvidingWindows.add(this);
        }

        // Trigger touch exclude region update on current display.
        currentDisplay.updateTouchExcludeRegion();
        // Trigger touchable region update for this window.
        currentDisplay.getInputMonitor().updateInputWindowsLw(true /* force */);
    }

    /**
     * Get the tap excluded region for this window in screen coordinates.
     *
     * @param outRegion The returned tap excluded region. It is on the screen coordinates.
     */
    void getTapExcludeRegion(Region outRegion) {
        mTmpRect.set(mWindowFrames.mFrame);
        mTmpRect.offsetTo(0, 0);

        outRegion.set(mTapExcludeRegion);
        outRegion.op(mTmpRect, Region.Op.INTERSECT);

        // The region is on the window coordinates, so it needs to  be translated into screen
        // coordinates. There's no need to scale since that will be done by native code.
        outRegion.translate(mWindowFrames.mFrame.left, mWindowFrames.mFrame.top);
    }

    boolean hasTapExcludeRegion() {
        return !mTapExcludeRegion.isEmpty();
    }

    boolean isImeLayeringTarget() {
        return getDisplayContent().getImeTarget(IME_TARGET_LAYERING) == this;
    }

    WindowState getImeLayeringTarget() {
        final InsetsControlTarget target = getDisplayContent().getImeTarget(IME_TARGET_LAYERING);
        return target != null ? target.getWindow() : null;
    }

    WindowState getImeInputTarget() {
        final InsetsControlTarget target = mDisplayContent.getImeTarget(IME_TARGET_INPUT);
        return target != null ? target.getWindow() : null;
    }

    long getFrameNumber() {
        // Return the frame number in which changes requested in this layout will be rendered or
        // -1 if we do not expect the frame to be rendered.
        return getFrame().isEmpty() ? -1 : mFrameNumber;
    }

    void setFrameNumber(long frameNumber) {
        mFrameNumber = frameNumber;
    }

    void forceReportingResized() {
        mWindowFrames.forceReportingResized();
    }

    /** Returns the {@link WindowFrames} associated with this {@link WindowState}. */
    WindowFrames getWindowFrames() {
        return mWindowFrames;
    }

    /**
     * If the simulated frame is set, the computed result won't be used in real layout. So this
     * frames must be cleared when the simulated computation is done.
     */
    void setSimulatedWindowFrames(WindowFrames windowFrames) {
        mSimulatedWindowFrames = windowFrames;
    }

    /**
     * Use this method only when the simulated frames may be set, so it is clearer that the calling
     * path may be used to simulate layout.
     */
    WindowFrames getLayoutingWindowFrames() {
        return mSimulatedWindowFrames != null ? mSimulatedWindowFrames : mWindowFrames;
    }

    void resetContentChanged() {
        mWindowFrames.setContentChanged(false);
    }

    /**
     * Set's an {@link InsetsSourceProvider} to be associated with this window, but only if the
     * provider itself is controllable, as one window can be the provider of more than one inset
     * type (i.e. gesture insets). If this window is controllable, all its animations must be
     * controlled by its control target, and the visibility of this window should be taken account
     * into the state of the control target.
     *
     * @param insetProvider the provider which should not be visible to the client.
     * @see InsetsStateController#getInsetsForWindow(WindowState)
     */
    void setControllableInsetProvider(InsetsSourceProvider insetProvider) {
        mControllableInsetProvider = insetProvider;
    }

    InsetsSourceProvider getControllableInsetProvider() {
        return mControllableInsetProvider;
    }

    private final class MoveAnimationSpec implements AnimationSpec {

        private final long mDuration;
        private Interpolator mInterpolator;
        private Point mFrom = new Point();
        private Point mTo = new Point();

        private MoveAnimationSpec(int fromX, int fromY, int toX, int toY) {
            final Animation anim = AnimationUtils.loadAnimation(mContext,
                    com.android.internal.R.anim.window_move_from_decor);
            mDuration = (long)
                    (anim.computeDurationHint() * mWmService.getWindowAnimationScaleLocked());
            mInterpolator = anim.getInterpolator();
            mFrom.set(fromX, fromY);
            mTo.set(toX, toY);
        }

        @Override
        public long getDuration() {
            return mDuration;
        }

        @Override
        public void apply(Transaction t, SurfaceControl leash, long currentPlayTime) {
            final float fraction = getFraction(currentPlayTime);
            final float v = mInterpolator.getInterpolation(fraction);
            t.setPosition(leash, mFrom.x + (mTo.x - mFrom.x) * v,
                    mFrom.y + (mTo.y - mFrom.y) * v);
        }

        @Override
        public void dump(PrintWriter pw, String prefix) {
            pw.println(prefix + "from=" + mFrom
                    + " to=" + mTo
                    + " duration=" + mDuration);
        }

        @Override
        public void dumpDebugInner(ProtoOutputStream proto) {
            final long token = proto.start(MOVE);
            dumpPointProto(mFrom, proto, FROM);
            dumpPointProto(mTo, proto, TO);
            proto.write(DURATION_MS, mDuration);
            proto.end(token);
        }
    }

    KeyInterceptionInfo getKeyInterceptionInfo() {
        if (mKeyInterceptionInfo == null
                || mKeyInterceptionInfo.layoutParamsPrivateFlags != getAttrs().privateFlags
                || mKeyInterceptionInfo.layoutParamsType != getAttrs().type
                || mKeyInterceptionInfo.windowTitle != getWindowTag()) {
            mKeyInterceptionInfo = new KeyInterceptionInfo(getAttrs().type, getAttrs().privateFlags,
                    getWindowTag().toString());
        }
        return mKeyInterceptionInfo;
    }

    @Override
    void getAnimationFrames(Rect outFrame, Rect outInsets, Rect outStableInsets,
            Rect outSurfaceInsets) {
        // Containing frame will usually cover the whole screen, including dialog windows.
        // For freeform workspace windows it will not cover the whole screen and it also
        // won't exactly match the final freeform window frame (e.g. when overlapping with
        // the status bar). In that case we need to use the final frame.
        if (inFreeformWindowingMode()) {
            outFrame.set(getFrame());
        } else if (isLetterboxedAppWindow() || mToken.isFixedRotationTransforming()) {
            // 1. The letterbox surfaces should be animated with the owner activity, so use task
            //    bounds to include them.
            // 2. If the activity has fixed rotation transform, its windows are rotated in activity
            //    level. Because the animation runs before display is rotated, task bounds should
            //    represent the frames in display space coordinates.
            outFrame.set(getTask().getBounds());
        } else if (isDockedResizing()) {
            // If we are animating while docked resizing, then use the root task bounds as the
            // animation target (which will be different than the task bounds)
            outFrame.set(getTask().getParent().getBounds());
        } else {
            outFrame.set(getContainingFrame());
        }
        outSurfaceInsets.set(getAttrs().surfaceInsets);
        final InsetsState state = getInsetsStateWithVisibilityOverride();
        outInsets.set(state.calculateInsets(outFrame, systemBars(),
                false /* ignoreVisibility */));
        outStableInsets.set(state.calculateInsets(outFrame, systemBars(),
                true /* ignoreVisibility */));
    }

    void setViewVisibility(int viewVisibility) {
        mViewVisibility = viewVisibility;
    }

    SurfaceControl getClientViewRootSurface() {
        return mWinAnimator.getSurfaceControl();
    }

    @Override
    boolean prepareSync() {
        if (!super.prepareSync()) {
            return false;
        }
        // In the WindowContainer implementation we immediately mark ready
        // since a generic WindowContainer only needs to wait for its
        // children to finish and is immediately ready from its own
        // perspective but at the WindowState level we need to wait for ourselves
        // to draw even if the children draw first our don't need to sync, so we start
        // in WAITING state rather than READY.
        mSyncState = SYNC_STATE_WAITING_FOR_DRAW;
        requestRedrawForSync();

        mWmService.mH.removeMessages(WINDOW_STATE_BLAST_SYNC_TIMEOUT, this);
        mWmService.mH.sendNewMessageDelayed(WINDOW_STATE_BLAST_SYNC_TIMEOUT, this,
            BLAST_TIMEOUT_DURATION);
        return true;
    }

    boolean finishDrawing(SurfaceControl.Transaction postDrawTransaction) {
        if (mOrientationChangeRedrawRequestTime > 0) {
            final long duration =
                    SystemClock.elapsedRealtime() - mOrientationChangeRedrawRequestTime;
            Slog.i(TAG, "finishDrawing of orientation change: " + this + " " + duration + "ms");
            mOrientationChangeRedrawRequestTime = 0;
        } else if (mActivityRecord != null && mActivityRecord.mRelaunchStartTime != 0
                && mActivityRecord.findMainWindow() == this) {
            final long duration =
                    SystemClock.elapsedRealtime() - mActivityRecord.mRelaunchStartTime;
            Slog.i(TAG, "finishDrawing of relaunch: " + this + " " + duration + "ms");
            mActivityRecord.mRelaunchStartTime = 0;
        }

        executeDrawHandlers(postDrawTransaction);
        if (!onSyncFinishedDrawing()) {
            return mWinAnimator.finishDrawingLocked(postDrawTransaction);
        }

        if (postDrawTransaction != null) {
            mSyncTransaction.merge(postDrawTransaction);
        }

        mWinAnimator.finishDrawingLocked(null);
        // We always want to force a traversal after a finish draw for blast sync.
        return true;
    }

    void immediatelyNotifyBlastSync() {
        prepareDrawHandlers();
        finishDrawing(null);
        mWmService.mH.removeMessages(WINDOW_STATE_BLAST_SYNC_TIMEOUT, this);
        if (!useBLASTSync()) return;

        final Task task = getTask();
        if (task != null) {
            final SurfaceControl.Transaction t = task.getMainWindowSizeChangeTransaction();
            if (t != null) {
                mSyncTransaction.merge(t);
            }
            task.setMainWindowSizeChangeTransaction(null);
        }
    }

    @Override
    boolean fillsParent() {
        return mAttrs.type == TYPE_APPLICATION_STARTING;
    }

    @Override
    boolean showWallpaper() {
        if (!isVisibleRequested()
                // in multi-window mode, wallpaper is always visible at the back and not tied to
                // the app (there is no wallpaper target).
                || inMultiWindowMode()) {
            return false;
        }
        return (mAttrs.flags & FLAG_SHOW_WALLPAPER) != 0;
    }

    /**
     * When using the two WindowOrganizer sync-primitives (BoundsChangeTransaction, BLASTSync)
     * it can be a little difficult to predict whether your change will actually trigger redrawing
     * on the client side. To ease the burden on shell developers, we force send MSG_RESIZED
     * for Windows involved in these Syncs
     */
    private boolean shouldSendRedrawForSync() {
        final Task task = getTask();
        if (task != null && task.getMainWindowSizeChangeTransaction() != null)
            return !mRedrawForSyncReported;
        return useBLASTSync() && !mRedrawForSyncReported;
    }

    void requestRedrawForSync() {
        mRedrawForSyncReported = false;
    }

    void calculateSurfaceBounds(WindowManager.LayoutParams attrs, Rect outSize) {
        outSize.setEmpty();
        if ((attrs.flags & FLAG_SCALED) != 0) {
            // For a scaled surface, we always want the requested size.
            outSize.right = mRequestedWidth;
            outSize.bottom = mRequestedHeight;
        } else {
            // When we're doing a drag-resizing, request a surface that's fullscreen size,
            // so that we don't need to reallocate during the process. This also prevents
            // buffer drops due to size mismatch.
            if (isDragResizing()) {
                final DisplayInfo displayInfo = getDisplayInfo();
                outSize.right = displayInfo.logicalWidth;
                outSize.bottom = displayInfo.logicalHeight;
            } else {
                getCompatFrameSize(outSize);
            }
        }

        // This doesn't necessarily mean that there is an error in the system. The sizes might be
        // incorrect, because it is before the first layout or draw.
        if (outSize.width() < 1) {
            outSize.right = 1;
        }
        if (outSize.height() < 1) {
            outSize.bottom = 1;
        }

        // Adjust for surface insets.
        outSize.inset(-attrs.surfaceInsets.left, -attrs.surfaceInsets.top,
                -attrs.surfaceInsets.right, -attrs.surfaceInsets.bottom);
    }

    /**
     * This method is used to control whether we return the BLAST_SYNC flag
     * from relayoutWindow calls on this window (triggering the client to redirect
     * it's next draw in to a transaction). If we have pending draw handlers, we are
     * looking for the client to sync.
     *
     * See {@link WindowState#mPendingDrawHandlers}
     */
    @Override
    boolean useBLASTSync() {
        return super.useBLASTSync() || (mPendingDrawHandlers.size() != 0);
    }

    /**
     * Apply the transaction with the next window redraw. A full relayout/finishDrawing
     * cycle must occur before completion. This means if you call the function while
     * "in relayout", the results may be undefined but at all other times the function
     * should sort of transparently work like this:
     *    1. Make changes to WM hierarchy (say change app configuration)
     *    2. Call apply with next draw.
     *    3. After finishDrawing, our consumer will be passed the Transaction
     *    containing the buffer, and we can merge in additional operations.
     * See {@link WindowState#mPendingDrawHandlers}
     */
    void applyWithNextDraw(Consumer<SurfaceControl.Transaction> consumer) {
        mPendingDrawHandlers.add(consumer);
        requestRedrawForSync();

        mWmService.mH.sendNewMessageDelayed(WINDOW_STATE_BLAST_SYNC_TIMEOUT, this,
            BLAST_TIMEOUT_DURATION);
    }

    /**
     * Called from relayout, to indicate the next "finishDrawing" will contain
     * all changes applied by the time mPendingDrawHandlers was populated.
     *
     * See {@link WindowState#mPendingDrawHandlers}
     */
    void prepareDrawHandlers() {
        mReadyDrawHandlers.addAll(mPendingDrawHandlers);
        mPendingDrawHandlers.clear();
    }

    /**
     * Drain the draw handlers, called from finishDrawing()
     * See {@link WindowState#mPendingDrawHandlers}
     */
    boolean executeDrawHandlers(SurfaceControl.Transaction t) {
        if (t == null) t = mTmpTransaction;
        boolean hadHandlers = false;
        for (int i = 0; i < mReadyDrawHandlers.size(); i++) {
            mReadyDrawHandlers.get(i).accept(t);
            hadHandlers = true;
        }
        mReadyDrawHandlers.clear();
        mWmService.mH.removeMessages(WINDOW_STATE_BLAST_SYNC_TIMEOUT, this);

        t.apply();

        return hadHandlers;
    }

    /**
     * Adds an additional translation offset to be applied when positioning the surface. Used to
     * correct offsets in specific reparenting situations, e.g. the navigation bar window attached
     * on the lower split-screen app.
     */
    void setSurfaceTranslationY(int translationY) {
        mSurfaceTranslationY = translationY;
    }

    @Override
    @WindowManager.LayoutParams.WindowType int getWindowType() {
        return mAttrs.type;
    }
}
