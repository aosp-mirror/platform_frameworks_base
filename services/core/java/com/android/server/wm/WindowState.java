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
import static android.content.pm.ActivityInfo.CONFIG_WINDOW_CONFIGURATION;
import static android.content.res.Configuration.ORIENTATION_LANDSCAPE;
import static android.graphics.GraphicsProtos.dumpPointProto;
import static android.os.InputConstants.DEFAULT_DISPATCHING_TIMEOUT_MILLIS;
import static android.os.PowerManager.DRAW_WAKE_LOCK;
import static android.os.Trace.TRACE_TAG_WINDOW_MANAGER;
import static android.view.SurfaceControl.Transaction;
import static android.view.SurfaceControl.getGlobalTransaction;
import static android.view.ViewRootImpl.LOCAL_LAYOUT;
import static android.view.ViewTreeObserver.InternalInsetsInfo.TOUCHABLE_INSETS_CONTENT;
import static android.view.ViewTreeObserver.InternalInsetsInfo.TOUCHABLE_INSETS_FRAME;
import static android.view.ViewTreeObserver.InternalInsetsInfo.TOUCHABLE_INSETS_REGION;
import static android.view.ViewTreeObserver.InternalInsetsInfo.TOUCHABLE_INSETS_VISIBLE;
import static android.view.WindowInsets.Type.navigationBars;
import static android.view.WindowInsets.Type.systemBars;
import static android.view.WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE;
import static android.view.WindowLayout.UNSPECIFIED_LENGTH;
import static android.view.WindowManager.LayoutParams.FIRST_SUB_WINDOW;
import static android.view.WindowManager.LayoutParams.FIRST_SYSTEM_WINDOW;
import static android.view.WindowManager.LayoutParams.FLAG_ALLOW_LOCK_WHILE_SCREEN_ON;
import static android.view.WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM;
import static android.view.WindowManager.LayoutParams.FLAG_BLUR_BEHIND;
import static android.view.WindowManager.LayoutParams.FLAG_DIM_BEHIND;
import static android.view.WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD;
import static android.view.WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE;
import static android.view.WindowManager.LayoutParams.FLAG_SCALED;
import static android.view.WindowManager.LayoutParams.FLAG_SHOW_WALLPAPER;
import static android.view.WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED;
import static android.view.WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON;
import static android.view.WindowManager.LayoutParams.LAST_SUB_WINDOW;
import static android.view.WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS;
import static android.view.WindowManager.LayoutParams.MATCH_PARENT;
import static android.view.WindowManager.LayoutParams.PRIVATE_FLAG_COMPATIBLE_WINDOW;
import static android.view.WindowManager.LayoutParams.PRIVATE_FLAG_NOT_MAGNIFIABLE;
import static android.view.WindowManager.LayoutParams.PRIVATE_FLAG_NO_MOVE_ANIMATION;
import static android.view.WindowManager.LayoutParams.PRIVATE_FLAG_SYSTEM_APPLICATION_OVERLAY;
import static android.view.WindowManager.LayoutParams.PRIVATE_FLAG_TRUSTED_OVERLAY;
import static android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE;
import static android.view.WindowManager.LayoutParams.SOFT_INPUT_MASK_ADJUST;
import static android.view.WindowManager.LayoutParams.SYSTEM_FLAG_HIDE_NON_SYSTEM_OVERLAY_WINDOWS;
import static android.view.WindowManager.LayoutParams.SYSTEM_FLAG_SHOW_FOR_ALL_USERS;
import static android.view.WindowManager.LayoutParams.TYPE_ACCESSIBILITY_MAGNIFICATION_OVERLAY;
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
import static android.view.WindowManagerGlobal.RELAYOUT_RES_FIRST_TIME;
import static android.view.WindowManagerPolicyConstants.TYPE_LAYER_MULTIPLIER;
import static android.view.WindowManagerPolicyConstants.TYPE_LAYER_OFFSET;

import static com.android.internal.protolog.ProtoLogGroup.WM_DEBUG_ADD_REMOVE;
import static com.android.internal.protolog.ProtoLogGroup.WM_DEBUG_ANIM;
import static com.android.internal.protolog.ProtoLogGroup.WM_DEBUG_APP_TRANSITIONS;
import static com.android.internal.protolog.ProtoLogGroup.WM_DEBUG_BACK_PREVIEW;
import static com.android.internal.protolog.ProtoLogGroup.WM_DEBUG_FOCUS;
import static com.android.internal.protolog.ProtoLogGroup.WM_DEBUG_FOCUS_LIGHT;
import static com.android.internal.protolog.ProtoLogGroup.WM_DEBUG_ORIENTATION;
import static com.android.internal.protolog.ProtoLogGroup.WM_DEBUG_RESIZE;
import static com.android.internal.protolog.ProtoLogGroup.WM_DEBUG_STARTING_WINDOW;
import static com.android.internal.protolog.ProtoLogGroup.WM_DEBUG_SYNC_ENGINE;
import static com.android.internal.protolog.ProtoLogGroup.WM_DEBUG_WINDOW_INSETS;
import static com.android.server.policy.WindowManagerPolicy.FINISH_LAYOUT_REDO_WALLPAPER;
import static com.android.server.policy.WindowManagerPolicy.TRANSIT_ENTER;
import static com.android.server.policy.WindowManagerPolicy.TRANSIT_EXIT;
import static com.android.server.policy.WindowManagerPolicy.TRANSIT_PREVIEW_DONE;
import static com.android.server.wm.AnimationSpecProto.MOVE;
import static com.android.server.wm.DisplayContent.IME_TARGET_LAYERING;
import static com.android.server.wm.DisplayContent.logsGestureExclusionRestrictions;
import static com.android.server.wm.IdentifierProto.HASH_CODE;
import static com.android.server.wm.IdentifierProto.TITLE;
import static com.android.server.wm.IdentifierProto.USER_ID;
import static com.android.server.wm.MoveAnimationSpecProto.DURATION_MS;
import static com.android.server.wm.MoveAnimationSpecProto.FROM;
import static com.android.server.wm.MoveAnimationSpecProto.TO;
import static com.android.server.wm.SurfaceAnimator.ANIMATION_TYPE_ALL;
import static com.android.server.wm.SurfaceAnimator.ANIMATION_TYPE_APP_TRANSITION;
import static com.android.server.wm.SurfaceAnimator.ANIMATION_TYPE_RECENTS;
import static com.android.server.wm.SurfaceAnimator.ANIMATION_TYPE_STARTING_REVEAL;
import static com.android.server.wm.SurfaceAnimator.ANIMATION_TYPE_WINDOW_ANIMATION;
import static com.android.server.wm.WindowContainer.AnimationFlags.PARENTS;
import static com.android.server.wm.WindowContainer.AnimationFlags.TRANSITION;
import static com.android.server.wm.WindowContainerChildProto.WINDOW;
import static com.android.server.wm.WindowManagerDebugConfig.DEBUG;
import static com.android.server.wm.WindowManagerDebugConfig.DEBUG_CONFIGURATION;
import static com.android.server.wm.WindowManagerDebugConfig.DEBUG_INPUT_METHOD;
import static com.android.server.wm.WindowManagerDebugConfig.DEBUG_POWER;
import static com.android.server.wm.WindowManagerDebugConfig.DEBUG_STARTING_WINDOW_VERBOSE;
import static com.android.server.wm.WindowManagerDebugConfig.DEBUG_VISIBILITY;
import static com.android.server.wm.WindowManagerDebugConfig.TAG_WITH_CLASS_NAME;
import static com.android.server.wm.WindowManagerDebugConfig.TAG_WM;
import static com.android.server.wm.WindowManagerService.H.WINDOW_STATE_BLAST_SYNC_TIMEOUT;
import static com.android.server.wm.WindowManagerService.MAX_ANIMATION_DURATION;
import static com.android.server.wm.WindowManagerService.MY_PID;
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
import static com.android.server.wm.WindowStateProto.FORCE_SEAMLESS_ROTATION;
import static com.android.server.wm.WindowStateProto.GIVEN_CONTENT_INSETS;
import static com.android.server.wm.WindowStateProto.GLOBAL_SCALE;
import static com.android.server.wm.WindowStateProto.HAS_COMPAT_SCALE;
import static com.android.server.wm.WindowStateProto.HAS_SURFACE;
import static com.android.server.wm.WindowStateProto.IS_ON_SCREEN;
import static com.android.server.wm.WindowStateProto.IS_READY_FOR_DISPLAY;
import static com.android.server.wm.WindowStateProto.IS_VISIBLE;
import static com.android.server.wm.WindowStateProto.KEEP_CLEAR_AREAS;
import static com.android.server.wm.WindowStateProto.MERGED_LOCAL_INSETS_SOURCES;
import static com.android.server.wm.WindowStateProto.PENDING_SEAMLESS_ROTATION;
import static com.android.server.wm.WindowStateProto.REMOVED;
import static com.android.server.wm.WindowStateProto.REMOVE_ON_EXIT;
import static com.android.server.wm.WindowStateProto.REQUESTED_HEIGHT;
import static com.android.server.wm.WindowStateProto.REQUESTED_WIDTH;
import static com.android.server.wm.WindowStateProto.STACK_ID;
import static com.android.server.wm.WindowStateProto.SURFACE_INSETS;
import static com.android.server.wm.WindowStateProto.SURFACE_POSITION;
import static com.android.server.wm.WindowStateProto.UNRESTRICTED_KEEP_CLEAR_AREAS;
import static com.android.server.wm.WindowStateProto.VIEW_VISIBILITY;
import static com.android.server.wm.WindowStateProto.WINDOW_CONTAINER;
import static com.android.server.wm.WindowStateProto.WINDOW_FRAMES;

import android.annotation.CallSuper;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.app.ActivityTaskManager;
import android.app.AppOpsManager;
import android.app.admin.DevicePolicyCache;
import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Matrix;
import android.graphics.PixelFormat;
import android.graphics.Point;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Region;
import android.gui.TouchOcclusionMode;
import android.os.Binder;
import android.os.Build;
import android.os.Debug;
import android.os.IBinder;
import android.os.PowerManager;
import android.os.PowerManager.WakeReason;
import android.os.RemoteCallbackList;
import android.os.RemoteException;
import android.os.SystemClock;
import android.os.Trace;
import android.os.WorkSource;
import android.provider.Settings;
import android.util.ArraySet;
import android.util.DisplayMetrics;
import android.util.MergedConfiguration;
import android.util.Slog;
import android.util.SparseArray;
import android.util.TimeUtils;
import android.util.proto.ProtoOutputStream;
import android.view.Display;
import android.view.DisplayInfo;
import android.view.IWindow;
import android.view.IWindowFocusObserver;
import android.view.IWindowId;
import android.view.InputChannel;
import android.view.InputWindowHandle;
import android.view.InsetsSource;
import android.view.InsetsState;
import android.view.Surface;
import android.view.Surface.Rotation;
import android.view.SurfaceControl;
import android.view.SurfaceSession;
import android.view.View;
import android.view.ViewDebug;
import android.view.ViewTreeObserver;
import android.view.WindowInfo;
import android.view.WindowInsets;
import android.view.WindowInsets.Type.InsetsType;
import android.view.WindowManager;
import android.view.animation.Animation;
import android.view.animation.AnimationUtils;
import android.view.animation.Interpolator;
import android.view.inputmethod.ImeTracker;
import android.window.ClientWindowFrames;
import android.window.OnBackInvokedCallbackInfo;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.policy.KeyInterceptionInfo;
import com.android.internal.protolog.ProtoLogImpl;
import com.android.internal.protolog.common.ProtoLog;
import com.android.internal.util.FrameworkStatsLog;
import com.android.internal.util.ToBooleanFunction;
import com.android.server.policy.WindowManagerPolicy;
import com.android.server.wm.LocalAnimationAdapter.AnimationSpec;
import com.android.server.wm.RefreshRatePolicy.FrameRateVote;
import com.android.server.wm.SurfaceAnimator.AnimationType;

import dalvik.annotation.optimization.NeverCompile;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Predicate;

/** A window in the window manager. */
class WindowState extends WindowContainer<WindowState> implements WindowManagerPolicy.WindowState,
        InsetsControlTarget, InputTarget {
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
    @NonNull WindowToken mToken;
    // The same object as mToken if this is an app window and null for non-app windows.
    ActivityRecord mActivityRecord;
    /** Non-null if this is a starting window. */
    StartingData mStartingData;

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
    private boolean mRedrawForSyncReported = true;

    /**
     * Used to assosciate a given set of state changes sent from MSG_RESIZED
     * with a given call to finishDrawing (does this call contain or not contain
     * those state changes). We need to use it to handle cases like this:
     * 1. Server changes some state, calls applyWithNextDraw
     * 2. Client observes state change, begins drawing frame.
     * 3. Server makes another state change, and calls applyWithNextDraw again
     * 4. We receive finishDrawing, and it only contains the first frame
     *    but there was no way for us to know, because we no longer rely
     *    on a synchronous call to relayout before draw.
     * We track this by storing seqIds in each draw handler, and increment
     * this seqId every time we send MSG_RESIZED. The client sends it back
     * with finishDrawing, and this way we can know is the client replying to
     * the latest MSG_RESIZED or an earlier one. For a detailed discussion,
     * examine the git commit message introducing this comment and variable.2
     */
    int mSyncSeqId = 0;

    /** The last syncId associated with a BLAST prepareSync or 0 when no BLAST sync is active. */
    int mPrepareSyncSeqId = 0;

    /**
     * Special mode that is intended only for the rounded corner overlay: during rotation
     * transition, we un-rotate the window token such that the window appears as it did before the
     * rotation.
     */
    final boolean mForceSeamlesslyRotate;
    SeamlessRotator mPendingSeamlessRotate;

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

    int mRelayoutSeq = -1;
    int mLayoutSeq = -1;

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
     * These are the content insets that were given during layout for this window, to be applied to
     * windows behind it.
     * This is only applied to IME windows when corresponding process in DisplayPolicy executed.
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
    float mGlobalScale = 1f;
    float mInvGlobalScale = 1f;
    float mCompatScale = 1f;
    final float mOverrideScale;
    float mHScale = 1f, mVScale = 1f;
    float mLastHScale = 1f, mLastVScale = 1f;

    // An offset in pixel of the surface contents from the window position. Used for Wallpaper
    // to provide the effect of scrolling within a large surface. We just use these values as
    // a cache.
    int mXOffset = 0;
    int mYOffset = 0;

    // A scale factor for the surface contents, that will be applied from the center of the visible
    // region.
    float mWallpaperScale = 1f;

    final Matrix mTmpMatrix = new Matrix();
    final float[] mTmpMatrixArray = new float[9];

    private final WindowFrames mWindowFrames = new WindowFrames();

    private final ClientWindowFrames mClientWindowFrames = new ClientWindowFrames();

    /**
     * List of rects where system gestures should be ignored.
     *
     * Coordinates are relative to the window's position.
     */
    private final List<Rect> mExclusionRects = new ArrayList<>();
    /**
     * List of rects which should ideally not be covered by floating windows like Pip.
     *
     * Coordinates are relative to the window's position.
     */
    private final List<Rect> mKeepClearAreas = new ArrayList<>();

    /**
     * Like mKeepClearAreas, but the unrestricted ones can be trusted to behave nicely.
     * Floating windows (like Pip) will be moved away from them without applying restrictions.
     */
    private final List<Rect> mUnrestrictedKeepClearAreas = new ArrayList<>();

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

    /**
     * If the application is not currently visible but requires a layout,
     * then make sure we call performSurfacePlacement as well. This is set
     * in layout if mLayoutNeeded is set until surface placement is done.
     */
    boolean mSurfacePlacementNeeded;

    /**
     * The animation types that will call {@link #onExitAnimationDone} so {@link #mAnimatingExit}
     * is guaranteed to be cleared.
     */
    static final int EXIT_ANIMATING_TYPES = ANIMATION_TYPE_APP_TRANSITION
            | ANIMATION_TYPE_WINDOW_ANIMATION | ANIMATION_TYPE_RECENTS;

    /** Currently running an exit animation? */
    boolean mAnimatingExit;

    /** Currently on the mDestroySurface list? */
    boolean mDestroying;

    /** Completely remove from window manager after exit animation? */
    boolean mRemoveOnExit;

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
    private final Region mTmpRegion = new Region();

    private final Transaction mTmpTransaction;

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
     * The insets state of sources provided by the overrides set on any parent up the hierarchy.
     */
    SparseArray<InsetsSource> mMergedLocalInsetsSources = null;

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

    private @InsetsType int mRequestedVisibleTypes = WindowInsets.Type.defaultVisible();

    /**
     * Freeze the insets state in some cases that not necessarily keeps up-to-date to the client.
     * (e.g app exiting transition)
     */
    private InsetsState mFrozenInsetsState;

    private KeyInterceptionInfo mKeyInterceptionInfo;

    /**
     * This information is passed to SurfaceFlinger to decide which window should have a priority
     * when deciding about the refresh rate of the display. All windows have the lowest priority by
     * default. The variable is cached, so we do not send too many updates to SF.
     */
    int mFrameRateSelectionPriority = RefreshRatePolicy.LAYER_PRIORITY_UNSET;

    /**
     * This is the frame rate which is passed to SurfaceFlinger if the window set a
     * preferredDisplayModeId or is part of the high refresh rate deny list.
     * The variable is cached, so we do not send too many updates to SF.
     */
    FrameRateVote mFrameRateVote = new FrameRateVote();

    static final int BLAST_TIMEOUT_DURATION = 5000; /* milliseconds */

    private final WindowProcessController mWpcForDisplayAreaConfigChanges;

    class DrawHandler {
        Consumer<SurfaceControl.Transaction> mConsumer;
        int mSeqId;

        DrawHandler(int seqId, Consumer<SurfaceControl.Transaction> consumer) {
            mSeqId = seqId;
            mConsumer = consumer;
        }
    }
    private final List<DrawHandler> mDrawHandlers = new ArrayList<>();

    private final Consumer<SurfaceControl.Transaction> mSeamlessRotationFinishedConsumer = t -> {
        finishSeamlessRotation(t);
        updateSurfacePosition(t);
    };

    private final Consumer<SurfaceControl.Transaction> mSetSurfacePositionConsumer = t -> {
        // Only apply the position to the surface when there's no leash created.
        if (mSurfaceControl != null && mSurfaceControl.isValid() && !mSurfaceAnimator.hasLeash()) {
            t.setPosition(mSurfaceControl, mSurfacePosition.x, mSurfacePosition.y);
        }
    };

    /**
     * @see #setOnBackInvokedCallbackInfo(OnBackInvokedCallbackInfo)
     */
    private OnBackInvokedCallbackInfo mOnBackInvokedCallbackInfo;
    @Override
    WindowState asWindowState() {
        return this;
    }

    /**
     * @see #setSurfaceTranslationY(int)
     */
    private int mSurfaceTranslationY;

    @Override
    public boolean isRequestedVisible(@InsetsType int types) {
        return (mRequestedVisibleTypes & types) != 0;
    }

    /**
     * Returns requested visible types of insets.
     *
     * @return an integer as the requested visible insets types.
     */
    @Override
    public @InsetsType int getRequestedVisibleTypes() {
        return mRequestedVisibleTypes;
    }

    /**
     * @see #getRequestedVisibleTypes()
     */
    void setRequestedVisibleTypes(@InsetsType int requestedVisibleTypes) {
        if (mRequestedVisibleTypes != requestedVisibleTypes) {
            mRequestedVisibleTypes = requestedVisibleTypes;
        }
    }

    @VisibleForTesting
    void setRequestedVisibleTypes(@InsetsType int requestedVisibleTypes, @InsetsType int mask) {
        setRequestedVisibleTypes(mRequestedVisibleTypes & ~mask | requestedVisibleTypes & mask);
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
        final boolean visible = shouldCheckTokenVisibleRequested()
                ? isVisibleRequested() : isVisible();
        return visible && mFrozenInsetsState == null;
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
        final Task task = getTask();
        if (task != null && task.inPinnedWindowingMode()) {
            // It is handled by PinnedTaskController. Note that the windowing mode of activity
            // and windows may still be fullscreen.
            return;
        }

        if (mPendingSeamlessRotate != null) {
            oldRotation = mPendingSeamlessRotate.getOldRotation();
        }

        // Skip performing seamless rotation when the controlled insets is IME with visible state.
        if (mControllableInsetProvider != null
                && mControllableInsetProvider.getSource().getType() == WindowInsets.Type.ime()) {
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
                        && !isRequestedVisible(navigationBars());
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

    /**
     * Collects all restricted and unrestricted keep-clear areas for this window.
     * Keep-clear areas are rects that should ideally not be covered by floating windows like Pip.
     * The system is more careful about restricted ones and may apply restrictions to them, while
     * the unrestricted ones are considered safe.
     *
     * @param outRestricted collection to add restricted keep-clear areas to
     * @param outUnrestricted collection to add unrestricted keep-clear areas to
     */
    void getKeepClearAreas(Collection<Rect> outRestricted, Collection<Rect> outUnrestricted) {
        final Matrix tmpMatrix = new Matrix();
        final float[] tmpFloat9 = new float[9];
        getKeepClearAreas(outRestricted, outUnrestricted, tmpMatrix, tmpFloat9);
    }

    /**
     * Collects all restricted and unrestricted keep-clear areas for this window.
     * Keep-clear areas are rects that should ideally not be covered by floating windows like Pip.
     * The system is more careful about restricted ones and may apply restrictions to them, while
     * the unrestricted ones are considered safe.
     *
     * @param outRestricted collection to add restricted keep-clear areas to
     * @param outUnrestricted collection to add unrestricted keep-clear areas to
     * @param tmpMatrix a temporary matrix to be used for transformations
     * @param float9 a temporary array of 9 floats
     */
    void getKeepClearAreas(Collection<Rect> outRestricted, Collection<Rect> outUnrestricted,
            Matrix tmpMatrix, float[] float9) {
        outRestricted.addAll(getRectsInScreenSpace(mKeepClearAreas, tmpMatrix, float9));
        outUnrestricted.addAll(
                getRectsInScreenSpace(mUnrestrictedKeepClearAreas, tmpMatrix, float9));
    }

    /**
     * Transforms the given rects from window coordinate space to screen space.
     */
    List<Rect> getRectsInScreenSpace(List<Rect> rects, Matrix tmpMatrix, float[] float9) {
        getTransformationMatrix(float9, tmpMatrix);

        final List<Rect> transformedRects = new ArrayList<Rect>();
        final RectF tmpRect = new RectF();
        Rect curr;
        for (Rect r : rects) {
            tmpRect.set(r);
            tmpMatrix.mapRect(tmpRect);
            curr = new Rect();
            tmpRect.roundOut(curr);
            transformedRects.add(curr);
        }
        return transformedRects;
    }

    /**
     * Sets the new keep-clear areas for this window. The rects should be defined in window
     * coordinate space.
     * Keep-clear areas can be restricted or unrestricted, depending on whether the app holds the
     * {@link android.Manifest.permission.SET_UNRESTRICTED_KEEP_CLEAR_AREAS} system permission.
     * Restricted ones will be handled more carefully by the system. Restrictions may be applied.
     * Unrestricted ones are considered safe. The system should move floating windows away from them
     * without applying restrictions.
     *
     * @param restricted the new restricted keep-clear areas for this window
     * @param unrestricted the new unrestricted keep-clear areas for this window
     *
     * @return true if there is a change in the list of keep-clear areas; false otherwise
     */
    boolean setKeepClearAreas(List<Rect> restricted, List<Rect> unrestricted) {
        final boolean newRestrictedAreas = !mKeepClearAreas.equals(restricted);
        final boolean newUnrestrictedAreas = !mUnrestrictedKeepClearAreas.equals(unrestricted);
        if (!newRestrictedAreas && !newUnrestrictedAreas) {
            return false;
        }
        if (newRestrictedAreas) {
            mKeepClearAreas.clear();
            mKeepClearAreas.addAll(restricted);
        }

        if (newUnrestrictedAreas) {
            mUnrestrictedKeepClearAreas.clear();
            mUnrestrictedKeepClearAreas.addAll(unrestricted);
        }
        return true;
    }

    /**
     * Used by {@link android.window.WindowOnBackInvokedDispatcher} to set the callback to be
     * called when a back navigation action is initiated.
     * @see BackNavigationController
     */
    void setOnBackInvokedCallbackInfo(
            @Nullable OnBackInvokedCallbackInfo callbackInfo) {
        ProtoLog.d(WM_DEBUG_BACK_PREVIEW, "%s: Setting back callback %s",
                this, callbackInfo);
        mOnBackInvokedCallbackInfo = callbackInfo;
    }

    @Nullable
    OnBackInvokedCallbackInfo getOnBackInvokedCallbackInfo() {
        return mOnBackInvokedCallbackInfo;
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
        mInputWindowHandle.setFocusable(false);
        mInputWindowHandle.setOwnerPid(s.mPid);
        mInputWindowHandle.setOwnerUid(s.mUid);
        mInputWindowHandle.setName(getName());
        mInputWindowHandle.setPackageName(mAttrs.packageName);
        mInputWindowHandle.setLayoutParamsType(mAttrs.type);
        mInputWindowHandle.setTrustedOverlay(shouldWindowHandleBeTrusted(s));
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
            mOverrideScale = 1f;
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

        mRequestedWidth = UNSPECIFIED_LENGTH;
        mRequestedHeight = UNSPECIFIED_LENGTH;
        mLastRequestedWidth = UNSPECIFIED_LENGTH;
        mLastRequestedHeight = UNSPECIFIED_LENGTH;
        mLayer = 0;
        mOverrideScale = mWmService.mAtmService.mCompatModePackages.getCompatScale(
                mAttrs.packageName, s.mUid);
        updateGlobalScale();

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

    boolean shouldWindowHandleBeTrusted(Session s) {
        return InputMonitor.isTrustedOverlay(mAttrs.type)
                || ((mAttrs.privateFlags & PRIVATE_FLAG_TRUSTED_OVERLAY) != 0
                        && s.mCanAddInternalSystemWindow)
                || ((mAttrs.privateFlags & PRIVATE_FLAG_SYSTEM_APPLICATION_OVERLAY) != 0
                        && s.mCanCreateSystemApplicationOverlay);
    }

    int getTouchOcclusionMode() {
        if (WindowManager.LayoutParams.isSystemAlertWindowType(mAttrs.type)) {
            return TouchOcclusionMode.USE_OPACITY;
        }
        if (isAnimating(PARENTS | TRANSITION, ANIMATION_TYPE_ALL) || inTransition()) {
            return TouchOcclusionMode.USE_OPACITY;
        }
        return TouchOcclusionMode.BLOCK_UNTRUSTED;
    }

    void attach() {
        if (DEBUG) Slog.v(TAG, "Attaching " + this + " token=" + mToken);
        mSession.windowAddedLocked();
    }

    void updateGlobalScale() {
        if (hasCompatScale()) {
            mCompatScale = (mOverrideScale == 1f || mToken.hasSizeCompatBounds())
                    ? mToken.getCompatScale()
                    : 1f;
            mGlobalScale = mCompatScale * mOverrideScale;
            mInvGlobalScale = 1f / mGlobalScale;
            return;
        }

        mGlobalScale = mInvGlobalScale = mCompatScale = 1f;
    }

    float getCompatScaleForClient() {
        // If this window in the size compat mode. The scaling is fully controlled at the server
        // side. The client doesn't need to take it into account.
        return mToken.hasSizeCompatBounds() ? 1f : mCompatScale;
    }

    /**
     * @return {@code true} if the application runs in size compatibility mode or has an app level
     * scaling override set.
     * @see CompatModePackages#getCompatScale
     * @see android.content.res.CompatibilityInfo#supportsScreen
     * @see ActivityRecord#hasSizeCompatBounds()
     */
    boolean hasCompatScale() {
        if ((mAttrs.privateFlags & PRIVATE_FLAG_COMPATIBLE_WINDOW) != 0) {
            return true;
        }
        if (mAttrs.type == TYPE_APPLICATION_STARTING) {
            // Exclude starting window because it is not displayed by the application.
            return false;
        }
        return mActivityRecord != null && mActivityRecord.hasSizeCompatBounds()
                || mOverrideScale != 1f;
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

    boolean skipLayout() {
        // Skip layout of the window when in transition to pip mode.
        return mActivityRecord != null && mActivityRecord.mWaitForEnteringPinnedMode;
    }

    void setFrames(ClientWindowFrames clientWindowFrames, int requestedWidth, int requestedHeight) {
        final WindowFrames windowFrames = mWindowFrames;
        mTmpRect.set(windowFrames.mParentFrame);

        windowFrames.mDisplayFrame.set(clientWindowFrames.displayFrame);
        windowFrames.mParentFrame.set(clientWindowFrames.parentFrame);
        windowFrames.mFrame.set(clientWindowFrames.frame);

        windowFrames.mCompatFrame.set(windowFrames.mFrame);
        if (mInvGlobalScale != 1f) {
            // Also, the scaled frame that we report to the app needs to be adjusted to be in
            // its coordinate space.
            windowFrames.mCompatFrame.scale(mInvGlobalScale);
        }
        windowFrames.setParentFrameWasClippedByDisplayCutout(
                clientWindowFrames.isParentFrameClippedByDisplayCutout);

        // Calculate relative frame
        windowFrames.mRelFrame.set(windowFrames.mFrame);
        WindowContainer<?> parent = getParent();
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

        if (requestedWidth != mLastRequestedWidth || requestedHeight != mLastRequestedHeight
                || !mTmpRect.equals(windowFrames.mParentFrame)) {
            mLastRequestedWidth = requestedWidth;
            mLastRequestedHeight = requestedHeight;
            windowFrames.setContentChanged(true);
        }

        if (mAttrs.type == TYPE_DOCK_DIVIDER) {
            if (!windowFrames.mFrame.equals(windowFrames.mLastFrame)) {
                mMovedByResize = true;
            }
        }

        if (mIsWallpaper) {
            final Rect lastFrame = windowFrames.mLastFrame;
            final Rect frame = windowFrames.mFrame;
            if (lastFrame.width() != frame.width() || lastFrame.height() != frame.height()) {
                mDisplayContent.mWallpaperController.updateWallpaperOffset(this, false /* sync */);
            }
        }

        updateSourceFrame(windowFrames.mFrame);

        if (mActivityRecord != null && !mIsChildWindow) {
            mActivityRecord.layoutLetterbox(this);
        }
        mSurfacePlacementNeeded = true;
        mHaveFrame = true;
    }

    void updateSourceFrame(Rect winFrame) {
        if (!hasInsetsSourceProvider()) {
            // This window doesn't provide any insets.
            return;
        }
        if (mGivenInsetsPending) {
            // The given insets are pending, and they are not reliable for now. The source frame
            // should be updated after the new given insets are sent to window manager.
            return;
        }
        final SparseArray<InsetsSourceProvider> providers = getInsetsSourceProviders();
        for (int i = providers.size() - 1; i >= 0; i--) {
            providers.valueAt(i).updateSourceFrame(winFrame);
        }
    }

    @Override
    public Rect getBounds() {
        // The window bounds are used for layout in screen coordinates. If the token has bounds for
        // size compatibility mode, its configuration bounds are app based coordinates which should
        // not be used for layout.
        return mToken.hasSizeCompatBounds() ? mToken.getBounds() : super.getBounds();
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

    WindowManager.LayoutParams getAttrs() {
        return mAttrs;
    }

    /** Retrieves the flags used to disable system UI functions. */
    int getDisableFlags() {
        return mDisableFlags;
    }

    @Override
    public int getBaseType() {
        return getTopParentWindow().mAttrs.type;
    }

    boolean setReportResizeHints() {
        return mWindowFrames.setReportResizeHints();
    }

    /**
     * Adds the window to the resizing list if any of the parameters we use to track the window
     * dimensions or insets have changed.
     */
    void updateResizingWindowIfNeeded() {
        final boolean insetsChanged = mWindowFrames.hasInsetsChanged();
        if ((!mHasSurface || getDisplayContent().mLayoutSeq != mLayoutSeq || isGoneForLayout())
                && !insetsChanged) {
            return;
        }

        final WindowStateAnimator winAnimator = mWinAnimator;
        final boolean didFrameInsetsChange = setReportResizeHints();
        // The latest configuration will be returned by the out parameter of relayout, so it is
        // unnecessary to report resize if this window is running relayout.
        final boolean configChanged = !mInRelayout && !isLastConfigReportedToClient();
        if (DEBUG_CONFIGURATION && configChanged) {
            Slog.v(TAG_WM, "Win " + this + " config changed: " + getConfiguration());
        }

        final boolean dragResizingChanged = !mDragResizingChangeReported && isDragResizeChanged();

        final boolean attachedFrameChanged = LOCAL_LAYOUT
                && mLayoutAttached && getParentWindow().frameChanged();

        if (DEBUG) {
            Slog.v(TAG_WM, "Resizing " + this + ": configChanged=" + configChanged
                    + " last=" + mWindowFrames.mLastFrame + " frame=" + mWindowFrames.mFrame);
        }

        // Add a window that is using blastSync to the resizing list if it hasn't been reported
        // already. This because the window is waiting on a finishDrawing from the client.
        if (didFrameInsetsChange
                || configChanged
                || insetsChanged
                || dragResizingChanged
                || shouldSendRedrawForSync()
                || attachedFrameChanged) {
            ProtoLog.v(WM_DEBUG_RESIZE,
                        "Resize reasons for w=%s:  %s configChanged=%b didFrameInsetsChange=%b",
                        this, mWindowFrames.getInsetsChangedInfo(),
                        configChanged, didFrameInsetsChange);

            if (insetsChanged) {
                mWindowFrames.setInsetsChanged(false);
                mWmService.mWindowsInsetsChanged--;
                if (mWmService.mWindowsInsetsChanged == 0) {
                    mWmService.mH.removeMessages(WindowManagerService.H.INSETS_CHANGED);
                }
            }

            onResizeHandled();
            mWmService.makeWindowFreezingScreenIfNeededLocked(this);

            // Reset the drawn state if the window need to redraw for the change, so the transition
            // can wait until it has finished drawing to start.
            if ((configChanged || getOrientationChanging() || dragResizingChanged)
                    && isVisibleRequested()) {
                winAnimator.mDrawState = DRAW_PENDING;
                if (mActivityRecord != null) {
                    mActivityRecord.clearAllDrawn();
                    if (mAttrs.type == TYPE_APPLICATION_STARTING
                            && mActivityRecord.mStartingData != null) {
                        mActivityRecord.mStartingData.mIsDisplayed = false;
                    }
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

    private boolean frameChanged() {
        return !mWindowFrames.mFrame.equals(mWindowFrames.mLastFrame);
    }

    boolean getOrientationChanging() {
        if (mTransitionController.isShellTransitionsEnabled()) {
            // Shell transition doesn't use the methods for display frozen state.
            return false;
        }
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
                    && mDisplayContent.shouldSyncRotationChange(this)) {
                mWmService.mRoot.mOrientationChangeComplete = false;
            }
        } else {
            // The orientation change is completed. If it was hidden by the animation, reshow it.
            mDisplayContent.finishAsyncRotation(mToken);
        }
    }

    void orientationChangeTimedOut() {
        mOrientationChangeTimedOut = true;
    }

    @Override
    public DisplayContent getDisplayContent() {
        return mToken.getDisplayContent();
    }

    @Override
    void onDisplayChanged(DisplayContent dc) {
        if (dc != null && mDisplayContent != null && dc != mDisplayContent
                && mDisplayContent.getImeInputTarget() == this) {
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
        final DisplayFrames displayFrames = mToken.getFixedRotationTransformDisplayFrames();
        if (displayFrames != null) {
            return displayFrames;
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

    @Override
    public Rect getMaxBounds() {
        final Rect maxBounds = mToken.getFixedRotationTransformMaxBounds();
        if (maxBounds != null) {
            return maxBounds;
        }
        return super.getMaxBounds();
    }

    /**
     * See {@link WindowState#getInsetsState(boolean)}
     */
    InsetsState getInsetsState() {
        return getInsetsState(false);
    }

    /**
     * Returns the insets state for the window. Its sources may be the copies with visibility
     * modification according to the state of transient bars.
     * This is to get the insets for a window layout on the screen. If the window is not there, use
     * the {@link InsetsPolicy#getInsetsForWindowMetrics} to get insets instead.
     * @param includeTransient whether or not the transient types should be included in the
     *                         insets state.
     */
    InsetsState getInsetsState(boolean includeTransient) {
        final InsetsState rotatedState = mToken.getFixedRotationTransformInsetsState();
        final InsetsPolicy insetsPolicy = getDisplayContent().getInsetsPolicy();
        if (rotatedState != null) {
            return insetsPolicy.adjustInsetsForWindow(this, rotatedState);
        }
        final InsetsState rawInsetsState =
                mFrozenInsetsState != null ? mFrozenInsetsState : getMergedInsetsState();
        final InsetsState insetsStateForWindow = insetsPolicy.enforceInsetsPolicyForTarget(
                mAttrs, getWindowingMode(), isAlwaysOnTop(), rawInsetsState);
        return insetsPolicy.adjustInsetsForWindow(this, insetsStateForWindow,
                includeTransient);
    }

    private InsetsState getMergedInsetsState() {
        final InsetsState globalInsetsState = mAttrs.receiveInsetsIgnoringZOrder
                ? getDisplayContent().getInsetsStateController().getRawInsetsState()
                : mAboveInsetsState;
        if (mMergedLocalInsetsSources == null) {
            return globalInsetsState;
        }

        final InsetsState mergedInsetsState = new InsetsState(globalInsetsState);
        for (int i = 0; i < mMergedLocalInsetsSources.size(); i++) {
            mergedInsetsState.addSource(mMergedLocalInsetsSources.valueAt(i));
        }
        return mergedInsetsState;
    }

    /**
     * Returns the insets state for the client and scales the frames if the client is in the size
     * compatible mode.
     */
    InsetsState getCompatInsetsState() {
        InsetsState state = getInsetsState();
        if (mInvGlobalScale != 1f) {
            state = new InsetsState(state, true);
            state.scale(mInvGlobalScale);
        }
        return state;
    }

    /**
     * Returns the insets state for the window and applies the requested visibility.
     */
    InsetsState getInsetsStateWithVisibilityOverride() {
        final InsetsState state = new InsetsState(getInsetsState(), true /* copySources */);
        for (int i = state.sourceSize() - 1; i >= 0; i--) {
            final InsetsSource source = state.sourceAt(i);
            final boolean requestedVisible = isRequestedVisible(source.getType());
            if (source.isVisible() != requestedVisible) {
                source.setVisible(requestedVisible);
            }
        }
        return state;
    }

    @Override
    public int getDisplayId() {
        final DisplayContent displayContent = getDisplayContent();
        if (displayContent == null) {
            return Display.INVALID_DISPLAY;
        }
        return displayContent.getDisplayId();
    }

    @Override
    public WindowState getWindowState() {
        return this;
    }

    @Override
    public IWindow getIWindow() {
        return mClient;
    }

    @Override
    public int getPid() {
        return mSession.mPid;
    }

    @Override
    public int getUid() {
        return mSession.mUid;
    }

    Task getTask() {
        return mActivityRecord != null ? mActivityRecord.getTask() : null;
    }

    @Nullable TaskFragment getTaskFragment() {
        return mActivityRecord != null ? mActivityRecord.getTaskFragment() : null;
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
        }

        bounds.set(mWindowFrames.mFrame);
        bounds.inset(getInsetsStateWithVisibilityOverride().calculateVisibleInsets(
                bounds, mAttrs.type, getWindowingMode(), mAttrs.softInputMode, mAttrs.flags));
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
        return mActivityRecord != null && (mActivityRecord.firstWindowDrawn
                || mActivityRecord.isStartingWindowDisplayed());
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

    private boolean isVisibleByPolicyOrInsets() {
        return isVisibleByPolicy()
                // If we don't have a provider, this window isn't used as a window generating
                // insets, so nobody can hide it over the inset APIs.
                && (mControllableInsetProvider == null
                        || mControllableInsetProvider.isClientVisible());
    }

    @Override
    boolean isVisible() {
        return wouldBeVisibleIfPolicyIgnored() && isVisibleByPolicyOrInsets();
    }

    @Override
    boolean isVisibleRequested() {
        final boolean localVisibleRequested =
                wouldBeVisibleRequestedIfPolicyIgnored() && isVisibleByPolicyOrInsets();
        if (localVisibleRequested && shouldCheckTokenVisibleRequested()) {
            return mToken.isVisibleRequested();
        }
        return localVisibleRequested;
    }

    /**
     * Returns {@code true} if {@link WindowToken#isVisibleRequested()} should be considered
     * before dispatching the latest configuration. Currently only {@link
     * ActivityRecord#isVisibleRequested()} and {@link WallpaperWindowToken#isVisibleRequested()}
     * implement explicit visible-requested.
     */
    boolean shouldCheckTokenVisibleRequested() {
        return mActivityRecord != null || mToken.asWallpaperToken() != null;
    }

    /**
     * Ensures that all the policy visibility bits are set.
     * @return {@code true} if all flags about visiblity are set
     */
    boolean isVisibleByPolicy() {
        return (mPolicyVisibility & POLICY_VISIBILITY_ALL) == POLICY_VISIBILITY_ALL;
    }

    boolean providesDisplayDecorInsets() {
        if (mInsetsSourceProviders == null) {
            return false;
        }
        for (int i = mInsetsSourceProviders.size() - 1; i >= 0; i--) {
            final InsetsSource source = mInsetsSourceProviders.valueAt(i).getSource();
            if ((source.getType() & DisplayPolicy.DecorInsets.CONFIG_TYPES) != 0) {
                return true;
            }
        }
        return false;
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
        final boolean isWallpaper = mToken.asWallpaperToken() != null;
        return !isWallpaper || mToken.isVisible();
    }

    private boolean wouldBeVisibleRequestedIfPolicyIgnored() {
        final WindowState parent = getParentWindow();
        final boolean isParentHiddenRequested = parent != null && !parent.isVisibleRequested();
        if (isParentHiddenRequested || mAnimatingExit || mDestroying) {
            return false;
        }
        final boolean isWallpaper = mToken.asWallpaperToken() != null;
        return !isWallpaper || mToken.isVisibleRequested();
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
     * Is this window capable of being visible (policy and content), in a visible part of the
     * hierarchy, and, if an activity window, the activity is visible-requested. Note, this means
     * if the activity is going-away, this will be {@code false} even when the window is visible.
     *
     * The 'adding' part refers to the period of time between IWindowSession.add() and the first
     * relayout() -- which, for activities, is the same as visibleRequested.
     *
     * TODO(b/206005136): This is very similar to isVisibleRequested(). Investigate merging them.
     */
    boolean isVisibleRequestedOrAdding() {
        final ActivityRecord atoken = mActivityRecord;
        return (mHasSurface || (!mRelayoutCalled && mViewVisibility == View.VISIBLE))
                && isVisibleByPolicy() && !isParentWindowHidden()
                && (atoken == null || atoken.isVisibleRequested())
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
                    || isAnimationRunningSelfOrParent());
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
        return !DevicePolicyCache.getInstance().isScreenCaptureAllowed(mShowUserId);
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
        final RecentsAnimationController recentsAnimationController =
                mWmService.getRecentsAnimationController();
        return mActivityRecord != null
                && (!mActivityRecord.isFreezingScreen() || !mAppFreezing)
                && mViewVisibility == View.VISIBLE
                && (recentsAnimationController == null
                         || recentsAnimationController.isInterestingForAllDrawn(this));
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
            return mActivityRecord.canAffectSystemUiFlags()
                    // Do not let snapshot window control the bar
                    && (mAttrs.type != TYPE_APPLICATION_STARTING
                            || !(mStartingData instanceof SnapshotStartingData));
        }
    }

    /**
     * Like isOnScreen, but returns false if the surface hasn't yet
     * been drawn.
     */
    boolean isDisplayed() {
        final ActivityRecord atoken = mActivityRecord;
        return isDrawn() && isVisibleByPolicy()
                && ((!isParentWindowHidden() && (atoken == null || atoken.isVisibleRequested()))
                        || isAnimationRunningSelfOrParent());
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
                || (atoken != null && !atoken.isVisibleRequested())
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
        final boolean isWallpaper = mToken.asWallpaperToken() != null;
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
            if (!mActivityRecord.isVisibleRequested()) return;
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
                ProtoLog.d(WM_DEBUG_ANIM,
                        "Set animatingExit: reason=onAppVisibilityChanged win=%s", this);
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
                if (accessibilityController.hasCallbacks()) {
                    accessibilityController.onWindowTransition(this, winTransit);
                }
            }
            setDisplayLayoutNeeded();
        }
    }

    boolean onSetAppExiting(boolean animateExit) {
        final DisplayContent displayContent = getDisplayContent();
        boolean changed = false;

        if (!animateExit) {
            // Hide the window permanently if no window exist animation is performed, so we can
            // avoid the window surface becoming visible again unexpectedly during the next
            // relayout.
            mPermanentlyHidden = true;
            hide(false /* doAnimation */, false /* requestAnim */);
        }
        if (isVisibleNow() && animateExit) {
            mWinAnimator.applyAnimationLocked(TRANSIT_EXIT, false);
            if (mWmService.mAccessibilityController.hasCallbacks()) {
                mWmService.mAccessibilityController.onWindowTransition(this, TRANSIT_EXIT);
            }
            changed = true;
            if (displayContent != null) {
                displayContent.setLayoutNeeded();
            }
        }

        for (int i = mChildren.size() - 1; i >= 0; --i) {
            final WindowState c = mChildren.get(i);
            changed |= c.onSetAppExiting(animateExit);
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

        if (canPlayMoveAnimation()) {
            startMoveAnimation(left, top);
        }

        if (mWmService.mAccessibilityController.hasCallbacks()) {
            mWmService.mAccessibilityController.onSomeWindowResizedOrMoved(getDisplayId());
        }

        try {
            mClient.moved(left, top);
        } catch (RemoteException e) {
        }
        mMovedByResize = false;
    }

    private boolean canPlayMoveAnimation() {

        // During the transition from pip to fullscreen, the activity windowing mode is set to
        // fullscreen at the beginning while the task is kept in pinned mode. Skip the move
        // animation in such case since the transition is handled in SysUI.
        final boolean hasMovementAnimation = getTask() == null
                ? getWindowConfiguration().hasMovementAnimations()
                : getTask().getWindowConfiguration().hasMovementAnimations();
        return mToken.okToAnimate()
                && (mAttrs.privateFlags & PRIVATE_FLAG_NO_MOVE_ANIMATION) == 0
                && !isDragResizing()
                && hasMovementAnimation
                && !mWinAnimator.mLastHidden
                && !mSeamlesslyRotated;
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
                && !mTransitionController.isCollecting();
    }

    boolean isObscuringDisplay() {
        Task task = getTask();
        if (task != null && !task.fillsParent()) {
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

    boolean matchesDisplayAreaBounds() {
        final Rect rotatedDisplayBounds = mToken.getFixedRotationTransformDisplayBounds();
        if (rotatedDisplayBounds != null) {
            // If the rotated display bounds are available, the window bounds are also rotated.
            return rotatedDisplayBounds.equals(getBounds());
        }
        final DisplayArea displayArea = getDisplayArea();
        if (displayArea == null) {
            return getDisplayContent().getBounds().equals(getBounds());
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
    public void onConfigurationChanged(Configuration newParentConfig) {
        // Get from super to avoid using the updated global config from the override method.
        final Configuration selfConfiguration = super.getConfiguration();
        mTempConfiguration.setTo(selfConfiguration);
        super.onConfigurationChanged(newParentConfig);
        final int diff = selfConfiguration.diff(mTempConfiguration);
        if (diff != 0) {
            mLastConfigReportedToClient = false;
        }

        if (getDisplayContent().getImeInputTarget() != this && !isImeLayeringTarget()) {
            return;
        }
        // When the window configuration changed, we need to update the IME control target in
        // case the app may lose the IME inets control when exiting from split-screen mode, or the
        // IME parent may failed to attach to the app during rotating the screen.
        // See DisplayContent#shouldImeAttachedToApp, DisplayContent#isImeControlledByApp
        if ((diff & CONFIG_WINDOW_CONFIGURATION) != 0) {
            // If the window was the IME layering target, updates the IME surface parent in case
            // the IME surface may be wrongly positioned when the window configuration affects the
            // IME surface association. (e.g. Attach IME surface on the display instead of the
            // app when the app bounds being letterboxed.)
            mDisplayContent.updateImeControlTarget(isImeLayeringTarget() /* updateImeParent */);
        }
    }

    @Override
    void removeImmediately() {
        if (mRemoved) {
            // Nothing to do.
            ProtoLog.v(WM_DEBUG_ADD_REMOVE,
                    "WS.removeImmediately: %s Already removed...", this);
            return;
        }

        mRemoved = true;
        // Destroy surface before super call. The general pattern is that the children need
        // to be removed before the parent (so that the sync-engine tracking works). Since
        // WindowStateAnimator is a "virtual" child, we have to do it manually here.
        mWinAnimator.destroySurfaceLocked(getSyncTransaction());
        if (!mDrawHandlers.isEmpty()) {
            mWmService.mH.removeMessages(WINDOW_STATE_BLAST_SYNC_TIMEOUT, this);
        }
        super.removeImmediately();

        if (isImeOverlayLayeringTarget()) {
            mWmService.dispatchImeTargetOverlayVisibilityChanged(mClient.asBinder(), mAttrs.type,
                    false /* visible */, true /* removed */);
        }
        final DisplayContent dc = getDisplayContent();
        if (isImeLayeringTarget()) {
            // Remove the attached IME screenshot surface.
            dc.removeImeSurfaceByTarget(this);
            // Make sure to set mImeLayeringTarget as null when the removed window is the
            // IME target, in case computeImeTarget may use the outdated target.
            dc.setImeLayeringTarget(null);
            dc.computeImeTarget(true /* updateImeTarget */);
        }
        if (dc.getImeInputTarget() == this && !inRelaunchingActivity()) {
            mWmService.dispatchImeInputTargetVisibilityChanged(mClient.asBinder(),
                    false /* visible */, true /* removed */);
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
        mOnBackInvokedCallbackInfo = null;

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
        mWindowRemovalAllowed = true;
        ProtoLog.v(WM_DEBUG_ADD_REMOVE,
                "removeIfPossible: %s callers=%s", this, Debug.getCallers(5));

        final boolean startingWindow = mAttrs.type == TYPE_APPLICATION_STARTING;
        if (startingWindow) {
            ProtoLog.d(WM_DEBUG_STARTING_WINDOW, "Starting window removed %s", this);
            // Cancel the remove starting window animation on shell. The main window might changed
            // during animating, checking for all windows would be safer.
            if (mActivityRecord != null) {
                mActivityRecord.forAllWindows(w -> {
                    if (w.isSelfAnimating(0, ANIMATION_TYPE_STARTING_REVEAL)) {
                        w.cancelAnimation();
                        return true;
                    }
                    return false;
                }, true);
            }
        } else if (mAttrs.type == TYPE_BASE_APPLICATION
                && isSelfAnimating(0, ANIMATION_TYPE_STARTING_REVEAL)) {
            // Cancel the remove starting window animation in case the binder dead before remove
            // splash window.
            cancelAnimation();
        }

        ProtoLog.v(WM_DEBUG_FOCUS, "Remove client=%x, surfaceController=%s Callers=%s",
                    System.identityHashCode(mClient.asBinder()),
                    mWinAnimator.mSurfaceController,
                    Debug.getCallers(5));

        final DisplayContent displayContent = getDisplayContent();
        final long origId = Binder.clearCallingIdentity();

        try {
            disposeInputChannel();
            mOnBackInvokedCallbackInfo = null;

            ProtoLog.v(WM_DEBUG_APP_TRANSITIONS,
                    "Remove %s: mSurfaceController=%s mAnimatingExit=%b mRemoveOnExit=%b "
                            + "mHasSurface=%b surfaceShowing=%b animating=%b app-animation=%b "
                            + "mDisplayFrozen=%b callers=%s",
                    this, mWinAnimator.mSurfaceController, mAnimatingExit, mRemoveOnExit,
                    mHasSurface, mWinAnimator.getShown(),
                    isAnimating(TRANSITION | PARENTS),
                    mActivityRecord != null && mActivityRecord.isAnimating(PARENTS | TRANSITION),
                    mWmService.mDisplayFrozen, Debug.getCallers(6));

            // Visibility of the removed window. Will be used later to update orientation later on.
            boolean wasVisible = false;

            // First, see if we need to run an animation. If we do, we have to hold off on removing the
            // window until the animation is done. If the display is frozen, just remove immediately,
            // since the animation wouldn't be seen.
            if (mHasSurface && mToken.okToAnimate()) {
                // If we are not currently running the exit animation, we need to see about starting one
                wasVisible = isVisible();

                // Remove immediately if there is display transition because the animation is
                // usually unnoticeable (e.g. covered by rotation animation) and the animation
                // bounds could be inconsistent, such as depending on when the window applies
                // its draw transaction with new rotation.
                final boolean allowExitAnimation = !displayContent.inTransition()
                        // There will be a new window so the exit animation may not be visible or
                        // look weird if its orientation is changed.
                        && !inRelaunchingActivity();

                if (wasVisible && isDisplayed()) {
                    final int transit = (!startingWindow) ? TRANSIT_EXIT : TRANSIT_PREVIEW_DONE;

                    // Try starting an animation.
                    if (allowExitAnimation && mWinAnimator.applyAnimationLocked(transit, false)) {
                        ProtoLog.v(WM_DEBUG_ANIM,
                                "Set animatingExit: reason=remove/applyAnimation win=%s", this);
                        mAnimatingExit = true;

                        // mAnimatingExit affects canAffectSystemUiFlags(). Run layout such that
                        // any change from that is performed immediately.
                        setDisplayLayoutNeeded();
                        mWmService.requestTraversal();
                    }
                    if (mWmService.mAccessibilityController.hasCallbacks()) {
                        mWmService.mAccessibilityController.onWindowTransition(this, transit);
                    }
                }
                final boolean isAnimating = allowExitAnimation
                        && (mAnimatingExit || isAnimationRunningSelfOrParent());
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
                    ProtoLog.v(WM_DEBUG_ANIM, "Set animatingExit: reason=remove/isAnimating win=%s",
                            this);
                    setupWindowForRemoveOnExit();
                    if (mActivityRecord != null) {
                        mActivityRecord.updateReportedVisibilityLocked();
                    }
                    return;
                }
            }

            // Check if window provides non decor insets before clearing its provided insets.
            final boolean windowProvidesDisplayDecorInsets = providesDisplayDecorInsets();

            removeImmediately();
            // Removing a visible window may affect the display orientation so just update it if
            // needed. Also recompute configuration if it provides screen decor insets.
            boolean needToSendNewConfiguration = wasVisible && displayContent.updateOrientation();
            if (windowProvidesDisplayDecorInsets) {
                needToSendNewConfiguration |=
                        displayContent.getDisplayPolicy().updateDecorInsetsInfo();
            }

            if (needToSendNewConfiguration) {
                displayContent.sendNewConfiguration();
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
        getDisplayContent().getDisplayPolicy().removeWindowLw(this);
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

    boolean canBeImeTarget() {
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

        // Don't allow transient-launch activities to take IME.
        if (rootTask != null && mActivityRecord != null
                && mTransitionController.isTransientLaunch(mActivityRecord)) {
            return false;
        }

        if (DEBUG_INPUT_METHOD) {
            Slog.i(TAG_WM, "isVisibleRequestedOrAdding " + this + ": "
                    + isVisibleRequestedOrAdding() + " isVisible: " + (isVisible()
                    && mActivityRecord != null && mActivityRecord.isVisible()));
            if (!isVisibleRequestedOrAdding()) {
                Slog.i(TAG_WM, "  mSurfaceController=" + mWinAnimator.mSurfaceController
                        + " relayoutCalled=" + mRelayoutCalled
                        + " viewVis=" + mViewVisibility
                        + " policyVis=" + isVisibleByPolicy()
                        + " policyVisAfterAnim=" + mLegacyPolicyVisibilityAfterAnim
                        + " parentHidden=" + isParentWindowHidden()
                        + " exiting=" + mAnimatingExit + " destroying=" + mDestroying);
                if (mActivityRecord != null) {
                    Slog.i(TAG_WM, "  mActivityRecord.visibleRequested="
                            + mActivityRecord.isVisibleRequested());
                }
            }
        }
        return isVisibleRequestedOrAdding()
                || (isVisible() && mActivityRecord != null && mActivityRecord.isVisible());
    }

    void openInputChannel(@NonNull InputChannel outInputChannel) {
        if (mInputChannel != null) {
            throw new IllegalStateException("Window already has an input channel.");
        }
        String name = getName();
        mInputChannel = mWmService.mInputManager.createInputChannel(name);
        mInputChannelToken = mInputChannel.getToken();
        mInputWindowHandle.setToken(mInputChannelToken);
        mWmService.mInputToWindowMap.put(mInputChannelToken, this);
        mInputChannel.copyTo(outInputChannel);
    }

    /**
     * Move the touch gesture from the currently touched window on this display to this window.
     */
    public boolean transferTouch() {
        return mWmService.mInputManager.transferTouch(mInputChannelToken, getDisplayId());
    }

    void disposeInputChannel() {
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

    void getSurfaceTouchableRegion(Region region, WindowManager.LayoutParams attrs) {
        final boolean modal = attrs.isModal();
        if (modal) {
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
        if (modal && mTouchableInsets == TOUCHABLE_INSETS_REGION) {
            // The client gave us a touchable region and so first
            // we calculate the untouchable region, then punch that out of our
            // expanded modal region.
            mTmpRegion.set(0, 0, frame.right, frame.bottom);
            mTmpRegion.op(mGivenTouchableRegion, Region.Op.DIFFERENCE);
            region.op(mTmpRegion, Region.Op.DIFFERENCE);
        }

        // TODO(b/139804591): sizecompat layout needs to be reworked. Currently mFrame is post-
        // scaling but the existing logic doesn't expect that. The result is that the already-
        // scaled region ends up getting sent to surfaceflinger which then applies the scale
        // (again). Until this is resolved, apply an inverse-scale here.
        if (mInvGlobalScale != 1.f) {
            region.scale(mInvGlobalScale);
        }
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
                final TaskFragment taskFragment = getTaskFragment();
                if (taskFragment != null) {
                    final Task task = taskFragment.asTask();
                    if (task != null) {
                        task.getDimBounds(mTmpRect);
                    } else {
                        mTmpRect.set(taskFragment.getBounds());
                    }
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
                        && (mWmService.mAtmService.isDreaming()
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
                synchronized (mWmService.mGlobalLock) {
                    final WindowState win = mWmService
                            .windowForClientLocked(mSession, mClient, false);
                    Slog.i(TAG, "WIN DEATH: " + win);
                    if (win != null) {
                        if (win.mActivityRecord != null
                                && win.mActivityRecord.findMainWindow() == win) {
                            mWmService.mSnapshotController.onAppDied(win.mActivityRecord);
                        }
                        win.removeIfPossible();
                    } else if (mHasSurface) {
                        Slog.e(TAG, "!!! LEAK !!! Window removed but surface still valid.");
                        WindowState.this.removeIfPossible();
                    }
                }
            } catch (IllegalArgumentException ex) {
                // This will happen if the window has already been removed.
            }
        }
    }

    /** Returns {@code true} if this window desires key events. */
    boolean canReceiveKeys() {
        return canReceiveKeys(false /* fromUserTouch */);
    }

    public String canReceiveKeysReason(boolean fromUserTouch) {
        return "fromTouch= " + fromUserTouch
                + " isVisibleRequestedOrAdding=" + isVisibleRequestedOrAdding()
                + " mViewVisibility=" + mViewVisibility
                + " mRemoveOnExit=" + mRemoveOnExit
                + " flags=" + mAttrs.flags
                + " appWindowsAreFocusable="
                + (mActivityRecord == null || mActivityRecord.windowsAreFocusable(fromUserTouch))
                + " canReceiveTouchInput=" + canReceiveTouchInput()
                + " displayIsOnTop=" + getDisplayContent().isOnTop()
                + " displayIsTrusted=" + getDisplayContent().isTrusted()
                + " transitShouldKeepFocus=" + (mActivityRecord != null
                        && mTransitionController.shouldKeepFocus(mActivityRecord));
    }

    public boolean canReceiveKeys(boolean fromUserTouch) {
        if (mActivityRecord != null && mTransitionController.shouldKeepFocus(mActivityRecord)) {
            // During transient launch, the transient-hide windows are not visibleRequested
            // or on-top but are kept focusable and thus can receive keys.
            return true;
        }
        final boolean canReceiveKeys = isVisibleRequestedOrAdding()
                && (mViewVisibility == View.VISIBLE) && !mRemoveOnExit
                && ((mAttrs.flags & WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE) == 0)
                && (mActivityRecord == null || mActivityRecord.windowsAreFocusable(fromUserTouch))
                // can it receive touches
                && (mActivityRecord == null || mActivityRecord.getTask() == null
                        || !mActivityRecord.getTask().getRootTask().shouldIgnoreInput());

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
        if (mActivityRecord != null) {
            // It will also check if its windows contain FLAG_SHOW_WHEN_LOCKED.
            return mActivityRecord.canShowWhenLocked();
        }
        return (mAttrs.flags & FLAG_SHOW_WHEN_LOCKED) != 0;
    }

    /**
     * @return {@code true} if this window can receive touches based on among other things,
     * windowing state and recents animation state.
     **/
    boolean canReceiveTouchInput() {
        if (mActivityRecord == null  || mActivityRecord.getTask() == null) {
            return true;
        }
        // During transient launch, the transient-hide windows are not visibleRequested
        // or on-top but are kept focusable and thus can receive touch input.
        if (mTransitionController.shouldKeepFocus(mActivityRecord)) {
            return true;
        }

        return !mActivityRecord.getTask().getRootTask().shouldIgnoreInput()
                && mActivityRecord.isVisibleRequested();
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
        if (isVisibleRequestedOrAdding()) {
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

    void sendAppVisibilityToClients() {
        super.sendAppVisibilityToClients();

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
            // The remote client fails to process the visibility message. That means it is in a
            // wrong state. E.g. the binder buffer is running out or the binder threads are dead.
            // The window visibility is out-of-sync that may cause blank content or left over, so
            // just kill it. And if it is a window of foreground activity, the activity can be
            // restarted automatically if needed.
            Slog.w(TAG, "Exception thrown during dispatchAppVisibility " + this, e);
            android.os.Process.killProcess(mSession.mPid);
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
        ProtoLog.d(WM_DEBUG_ANIM, "Clear animatingExit: reason=destroySurface win=%s", this);

        if (useBLASTSync()) {
            immediatelyNotifyBlastSync();
        }
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
            getDisplayContent().removeImeSurfaceByTarget(this);
        }
        // Exclude toast because legacy apps may show toast window by themselves, so the misused
        // apps won't always be considered as foreground state.
        // Exclude private presentations as they can only be shown on private virtual displays and
        // shouldn't be the cause of an app be considered foreground.
        // Exclude presentations on virtual displays as they are not actually visible.
        if (mAttrs.type >= FIRST_SYSTEM_WINDOW
                && mAttrs.type != TYPE_TOAST
                && mAttrs.type != TYPE_PRIVATE_PRESENTATION
                && !(mAttrs.type == TYPE_PRESENTATION && isOnVirtualDisplay())
        ) {
            mWmService.mAtmService.mActiveUids.onNonAppSurfaceVisibilityChanged(mOwnerUid, shown);
        }
    }

    private boolean isOnVirtualDisplay() {
        return getDisplayContent().mDisplay.getType() == Display.TYPE_VIRTUAL;
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
                false /* (deprecated param) inSplitscreen */, (int) duration);
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
                || mWmService.isUserVisible(win.mShowUserId);
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
        final DisplayContent dc = getDisplayContent();

        if (mAttrs.isModal() && dc != null) {
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

    WindowProcessController getProcess() {
        return mWpcForDisplayAreaConfigChanges;
    }

    /**
     * Fills the given window frames and merged configuration for the client.
     *
     * @param outFrames The frames that will be sent to the client.
     * @param outMergedConfiguration The configuration that will be sent to the client.
     * @param useLatestConfig Whether to use the latest configuration.
     * @param relayoutVisible Whether to consider visibility to use the latest configuration.
     */
    void fillClientWindowFramesAndConfiguration(ClientWindowFrames outFrames,
            MergedConfiguration outMergedConfiguration, boolean useLatestConfig,
            boolean relayoutVisible) {
        outFrames.frame.set(mWindowFrames.mCompatFrame);
        outFrames.displayFrame.set(mWindowFrames.mDisplayFrame);
        if (mInvGlobalScale != 1f) {
            outFrames.displayFrame.scale(mInvGlobalScale);
        }
        if (mLayoutAttached) {
            if (outFrames.attachedFrame == null) {
                outFrames.attachedFrame = new Rect();
            }
            outFrames.attachedFrame.set(getParentWindow().getFrame());
            if (mInvGlobalScale != 1f) {
                outFrames.attachedFrame.scale(mInvGlobalScale);
            }
        }

        outFrames.compatScale = getCompatScaleForClient();

        // Note: in the cases where the window is tied to an activity, we should not send a
        // configuration update when the window has requested to be hidden. Doing so can lead to
        // the client erroneously accepting a configuration that would have otherwise caused an
        // activity restart. We instead hand back the last reported {@link MergedConfiguration}.
        if (useLatestConfig || (relayoutVisible && (mActivityRecord == null
                || mActivityRecord.isVisibleRequested()))) {
            final Configuration globalConfig = getProcessGlobalConfiguration();
            final Configuration overrideConfig = getMergedOverrideConfiguration();
            outMergedConfiguration.setConfiguration(globalConfig, overrideConfig);
            if (outMergedConfiguration != mLastReportedConfiguration) {
                mLastReportedConfiguration.setTo(outMergedConfiguration);
            }
        } else {
            outMergedConfiguration.setTo(mLastReportedConfiguration);
        }
        mLastConfigReportedToClient = true;
    }

    void reportResized() {
        // If the activity is scheduled to relaunch, skip sending the resized to ViewRootImpl now
        // since it will be destroyed anyway. This also prevents the client from receiving
        // windowing mode change before it is destroyed.
        if (inRelaunchingActivity()) {
            return;
        }
        // If this is an activity or wallpaper and is invisible or going invisible, don't report
        // either since it is going away. This is likely during a transition so we want to preserve
        // the original state.
        if (shouldCheckTokenVisibleRequested() && !mToken.isVisibleRequested()) {
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

        // Always reset these states first, so if {@link IWindow#resized} fails, this
        // window won't be added to {@link WindowManagerService#mResizingWindows} and set
        // {@link #mOrientationChanging} to true again by {@link #updateResizingWindowIfNeeded}
        // that may cause WINDOW_FREEZE_TIMEOUT because resizing the client keeps failing.
        mDragResizingChangeReported = true;
        mWindowFrames.clearReportResizeHints();

        // We update mLastFrame always rather than in the conditional with the last inset
        // variables, because mFrameSizeChanged only tracks the width and height changing.
        updateLastFrames();

        final int prevRotation = mLastReportedConfiguration
                .getMergedConfiguration().windowConfiguration.getRotation();
        fillClientWindowFramesAndConfiguration(mClientWindowFrames, mLastReportedConfiguration,
                true /* useLatestConfig */, false /* relayoutVisible */);
        final boolean syncRedraw = shouldSendRedrawForSync();
        final boolean syncWithBuffers = syncRedraw && shouldSyncWithBuffers();
        final boolean reportDraw = syncRedraw || drawPending;
        final boolean isDragResizeChanged = isDragResizeChanged();
        final boolean forceRelayout = syncWithBuffers || isDragResizeChanged;
        final DisplayContent displayContent = getDisplayContent();
        final boolean alwaysConsumeSystemBars =
                displayContent.getDisplayPolicy().areSystemBarsForcedConsumedLw();
        final int displayId = displayContent.getDisplayId();

        if (isDragResizeChanged) {
            setDragResizing();
        }
        final boolean isDragResizing = isDragResizing();

        markRedrawForSyncReported();

        try {
            mClient.resized(mClientWindowFrames, reportDraw, mLastReportedConfiguration,
                    getCompatInsetsState(), forceRelayout, alwaysConsumeSystemBars, displayId,
                    syncWithBuffers ? mSyncSeqId : -1, isDragResizing);
            if (drawPending && prevRotation >= 0 && prevRotation != mLastReportedConfiguration
                    .getMergedConfiguration().windowConfiguration.getRotation()) {
                mOrientationChangeRedrawRequestTime = SystemClock.elapsedRealtime();
                ProtoLog.v(WM_DEBUG_ORIENTATION,
                        "Requested redraw for orientation change: %s", this);
            }

            if (mWmService.mAccessibilityController.hasCallbacks()) {
                mWmService.mAccessibilityController.onSomeWindowResizedOrMoved(displayId);
            }
        } catch (RemoteException e) {
            // Cancel orientation change of this window to avoid blocking unfreeze display.
            setOrientationChanging(false);
            mLastFreezeDuration = (int)(SystemClock.elapsedRealtime()
                    - mWmService.mDisplayFreezeTime);
            Slog.w(TAG, "Failed to report 'resized' to " + this + " due to " + e);
        }
        Trace.traceEnd(TRACE_TAG_WINDOW_MANAGER);
    }

    boolean inRelaunchingActivity() {
        return mActivityRecord != null && mActivityRecord.isRelaunching();
    }

    boolean isClientLocal() {
        return mClient instanceof IWindow.Stub;
    }

    /**
     * Called when the insets state changed.
     */
    void notifyInsetsChanged() {
        ProtoLog.d(WM_DEBUG_WINDOW_INSETS, "notifyInsetsChanged for %s ", this);
        mWindowFrames.setInsetsChanged(true);

        // If the new InsetsState won't be dispatched before releasing WM lock, the following
        // message will be executed.
        mWmService.mWindowsInsetsChanged++;
        mWmService.mH.removeMessages(WindowManagerService.H.INSETS_CHANGED);
        mWmService.mH.sendEmptyMessage(WindowManagerService.H.INSETS_CHANGED);

        final WindowContainer p = getParent();
        if (p != null) {
            p.updateOverlayInsetsState(this);
        }
    }

    @Override
    public void notifyInsetsControlChanged() {
        ProtoLog.d(WM_DEBUG_WINDOW_INSETS, "notifyInsetsControlChanged for %s ", this);
        if (mRemoved) {
            return;
        }
        final InsetsStateController stateController =
                getDisplayContent().getInsetsStateController();
        try {
            mClient.insetsControlChanged(getCompatInsetsState(),
                    stateController.getControlsForDispatch(this));
        } catch (RemoteException e) {
            Slog.w(TAG, "Failed to deliver inset control state change to w=" + this, e);
        }
    }

    @Override
    public WindowState getWindow() {
        return this;
    }

    @Override
    public void showInsets(@InsetsType int types, boolean fromIme,
            @Nullable ImeTracker.Token statsToken) {
        try {
            ImeTracker.forLogging().onProgress(statsToken,
                    ImeTracker.PHASE_WM_WINDOW_INSETS_CONTROL_TARGET_SHOW_INSETS);
            mClient.showInsets(types, fromIme, statsToken);
        } catch (RemoteException e) {
            Slog.w(TAG, "Failed to deliver showInsets", e);
            ImeTracker.forLogging().onFailed(statsToken,
                    ImeTracker.PHASE_WM_WINDOW_INSETS_CONTROL_TARGET_SHOW_INSETS);
        }
    }

    @Override
    public void hideInsets(@InsetsType int types, boolean fromIme,
            @Nullable ImeTracker.Token statsToken) {
        try {
            ImeTracker.forLogging().onProgress(statsToken,
                    ImeTracker.PHASE_WM_WINDOW_INSETS_CONTROL_TARGET_HIDE_INSETS);
            mClient.hideInsets(types, fromIme, statsToken);
        } catch (RemoteException e) {
            Slog.w(TAG, "Failed to deliver hideInsets", e);
            ImeTracker.forLogging().onFailed(statsToken,
                    ImeTracker.PHASE_WM_WINDOW_INSETS_CONTROL_TARGET_HIDE_INSETS);
        }
    }

    @Override
    public boolean canShowTransient() {
        return (mAttrs.insetsFlags.behavior & BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE) != 0;
    }

    boolean canBeHiddenByKeyguard() {
        // Keyguard visibility of window from activities are determined over activity visibility.
        if (mActivityRecord != null) {
            return false;
        }
        switch (mAttrs.type) {
            case TYPE_NOTIFICATION_SHADE:
            case TYPE_STATUS_BAR:
            case TYPE_NAVIGATION_BAR:
            case TYPE_WALLPAPER:
                return false;
            default:
                // Hide only windows below the keyguard host window.
                return mPolicy.getWindowLayerLw(this)
                        < mPolicy.getWindowLayerFromTypeLw(TYPE_NOTIFICATION_SHADE);
        }
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

    /**
     * Returns {@code true} if activity bounds are letterboxed or letterboxed for display cutout.
     *
     * <p>Note that letterbox UI may not be shown even when this returns {@code true}. See {@link
     * LetterboxUiController#shouldShowLetterboxUi} for more context.
     */
    boolean areAppWindowBoundsLetterboxed() {
        return mActivityRecord != null
                && (mActivityRecord.areBoundsLetterboxed() || isLetterboxedForDisplayCutout());
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
     * Resets the state whether we reported a drag resize change to the app.
     */
    @Override
    void resetDragResizingChangeReported() {
        mDragResizingChangeReported = false;
        super.resetDragResizingChangeReported();
    }

    private boolean computeDragResizing() {
        final Task task = getTask();
        if (task == null) {
            return false;
        }
        if (!inFreeformWindowingMode() && !task.getRootTask().mCreatedByOrganizer) {
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

        return false;
    }

    void setDragResizing() {
        final boolean resizing = computeDragResizing();
        if (resizing == mDragResizing) {
            return;
        }
        mDragResizing = resizing;
    }

    boolean isDragResizing() {
        return mDragResizing;
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
        proto.write(FORCE_SEAMLESS_ROTATION, mForceSeamlesslyRotate);
        proto.write(HAS_COMPAT_SCALE, hasCompatScale());
        proto.write(GLOBAL_SCALE, mGlobalScale);
        for (Rect r : mKeepClearAreas) {
            r.dumpDebug(proto, KEEP_CLEAR_AREAS);
        }
        for (Rect r : mUnrestrictedKeepClearAreas) {
            r.dumpDebug(proto, UNRESTRICTED_KEEP_CLEAR_AREAS);
        }
        if (mMergedLocalInsetsSources != null) {
            for (int i = 0; i < mMergedLocalInsetsSources.size(); ++i) {
                mMergedLocalInsetsSources.valueAt(i).dumpDebug(proto, MERGED_LOCAL_INSETS_SOURCES);
            }
        }
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

    @NeverCompile // Avoid size overhead of debugging code.
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
        if (mInvGlobalScale != 1f) {
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
        if (getOrientationChanging() || mAppFreezing) {
            pw.println(prefix + "mOrientationChanging=" + mOrientationChanging
                    + " configOrientationChanging="
                    + (getLastReportedConfiguration().orientation != getConfiguration().orientation)
                    + " mAppFreezing=" + mAppFreezing);
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

        if (mXOffset != 0 || mYOffset != 0) {
            pw.println(prefix + "mXOffset=" + mXOffset + " mYOffset=" + mYOffset);
        }
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
        pw.println(prefix + "keepClearAreas: restricted=" + mKeepClearAreas
                          + ", unrestricted=" + mUnrestrictedKeepClearAreas);
        if (dumpAll) {
            if (mRequestedVisibleTypes != WindowInsets.Type.defaultVisible()) {
                pw.println(prefix + "Requested non-default-visibility types: "
                        + WindowInsets.Type.toString(
                                mRequestedVisibleTypes ^ WindowInsets.Type.defaultVisible()));
            }
        }

        pw.println(prefix + "mPrepareSyncSeqId=" + mPrepareSyncSeqId);
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

    boolean isChildWindow() {
        return mIsChildWindow;
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

    void requestUpdateWallpaperIfNeeded() {
        final DisplayContent dc = getDisplayContent();
        if (dc != null && ((mIsWallpaper && !mLastConfigReportedToClient) || hasWallpaper())) {
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
        if (mGlobalScale != 1f) {
            winX *= mInvGlobalScale;
        }
        return winX;
    }

    float translateToWindowY(float y) {
        float winY = y - mWindowFrames.mFrame.top;
        if (mGlobalScale != 1f) {
            winY *= mInvGlobalScale;
        }
        return winY;
    }

    int getRotationAnimationHint() {
        if (mActivityRecord != null) {
            return mActivityRecord.mRotationAnimationHint;
        } else {
            return -1;
        }
    }

    /** Makes the surface of drawn window (COMMIT_DRAW_PENDING) to be visible. */
    boolean commitFinishDrawing(SurfaceControl.Transaction t) {
        boolean committed = mWinAnimator.commitFinishDrawingLocked();
        if (committed) {
            // Ensure that the visibility of buffer layer is set.
            mWinAnimator.prepareSurfaceLocked(t);
        }
        for (int i = mChildren.size() - 1; i >= 0; i--) {
            committed |= mChildren.get(i).commitFinishDrawing(t);
        }
        return committed;
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
                mActivityRecord.onFirstWindowDrawn(this);
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
        ProtoLog.v(WM_DEBUG_ANIM, "performShowLocked: mDrawState=HAS_DRAWN in %s", this);
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
                    + (mActivityRecord != null && mActivityRecord.isVisibleRequested())
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
            windowInfo.activityToken = mActivityRecord.token;
        }
        windowInfo.accessibilityIdOfAnchor = mAttrs.accessibilityIdOfAnchor;
        windowInfo.focused = isFocused();
        Task task = getTask();
        windowInfo.inPictureInPicture = (task != null) && task.inPinnedWindowingMode();
        windowInfo.taskId = task == null ? ActivityTaskManager.INVALID_TASK_ID : task.mTaskId;
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

    @Override
    void updateAboveInsetsState(InsetsState aboveInsetsState,
            SparseArray<InsetsSource> localInsetsSourcesFromParent,
            ArraySet<WindowState> insetsChangedWindows) {
        final SparseArray<InsetsSource> mergedLocalInsetsSources =
                createMergedSparseArray(localInsetsSourcesFromParent, mLocalInsetsSources);

        // Insets provided by the IME window can effect all the windows below it and hence it needs
        // to be visited in the correct order. Because of which updateAboveInsetsState() can't be
        // used here and instead forAllWindows() is used.
        forAllWindows(w -> {
            if (!w.mAboveInsetsState.equals(aboveInsetsState)) {
                w.mAboveInsetsState.set(aboveInsetsState);
                insetsChangedWindows.add(w);
            }

            if (!mergedLocalInsetsSources.contentEquals(w.mMergedLocalInsetsSources)) {
                w.mMergedLocalInsetsSources = mergedLocalInsetsSources;
                insetsChangedWindows.add(w);
            }

            final SparseArray<InsetsSourceProvider> providers = w.mInsetsSourceProviders;
            if (providers != null) {
                for (int i = providers.size() - 1; i >= 0; i--) {
                    aboveInsetsState.addSource(providers.valueAt(i).getSource());
                }
            }
        }, true /* traverseTopToBottom */);
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
        // No need to apply to IME window if the window is not the current IME layering target.
        if (!isImeLayeringTarget()) {
            return false;
        }
        // Note that we don't process IME window if the IME input target is not on the screen.
        // In case some unexpected IME visibility cases happen like starting the remote
        // animation on the keyguard but seeing the IME window that originally on the app
        // which behinds the keyguard.
        final WindowState imeInputTarget = getImeInputTarget();
        if (imeInputTarget != null
                && !(imeInputTarget.isDrawn() || imeInputTarget.isVisibleRequested())) {
            return false;
        }
        return mDisplayContent.forAllImeWindows(callback, traverseTopToBottom);
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

    boolean isAnimationRunningSelfOrParent() {
        return inTransitionSelfOrParent()
                || isAnimating(0 /* flags */, ANIMATION_TYPE_WINDOW_ANIMATION);
    }

    private boolean shouldFinishAnimatingExit() {
        // Exit animation might be applied soon.
        if (inTransition()) {
            ProtoLog.d(WM_DEBUG_APP_TRANSITIONS, "shouldWaitAnimatingExit: isTransition: %s",
                    this);
            return false;
        }
        if (!mDisplayContent.okToAnimate()) {
            return true;
        }
        // Exit animation is running.
        if (isAnimationRunningSelfOrParent()) {
            ProtoLog.d(WM_DEBUG_APP_TRANSITIONS, "shouldWaitAnimatingExit: isAnimating: %s",
                    this);
            return false;
        }
        // If the wallpaper is currently behind this app window, we need to change both of
        // them inside of a transaction to avoid artifacts.
        if (mDisplayContent.mWallpaperController.isWallpaperTarget(this)) {
            ProtoLog.d(WM_DEBUG_APP_TRANSITIONS,
                    "shouldWaitAnimatingExit: isWallpaperTarget: %s", this);
            return false;
        }
        return true;
    }

    /**
     * If this is window is stuck in the animatingExit status, resume clean up procedure blocked
     * by the exit animation.
     */
    void cleanupAnimatingExitWindow() {
        // TODO(b/205335975): WindowManagerService#tryStartExitingAnimation starts an exit animation
        // and set #mAnimationExit. After the exit animation finishes, #onExitAnimationDone shall
        // be called, but there seems to be a case that #onExitAnimationDone is not triggered, so
        // a windows stuck in the animatingExit status.
        if (mAnimatingExit && shouldFinishAnimatingExit()) {
            ProtoLog.w(WM_DEBUG_APP_TRANSITIONS, "Clear window stuck on animatingExit status: %s",
                    this);
            onExitAnimationDone();
        }
    }

    void onExitAnimationDone() {
        if (ProtoLogImpl.isEnabled(WM_DEBUG_ANIM)) {
            final AnimationAdapter animationAdapter = mSurfaceAnimator.getAnimation();
            StringWriter sw = new StringWriter();
            if (animationAdapter != null) {
                PrintWriter pw = new PrintWriter(sw);
                animationAdapter.dump(pw, "");
            }
            ProtoLog.v(WM_DEBUG_ANIM, "onExitAnimationDone in %s"
                            + ": exiting=%b remove=%b selfAnimating=%b anim=%s",
                    this, mAnimatingExit, mRemoveOnExit, isAnimating(), sw);
        }

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
            if (mAttrs.type == TYPE_BASE_APPLICATION) {
                mActivityRecord.destroySurfaces();
            } else {
                destroySurface(false /* cleanupOnResume */, mActivityRecord.mAppStopped);
            }
        } else {
            if (hasSurface) {
                mWmService.mDestroySurface.add(this);
            }
        }
        mAnimatingExit = false;
        ProtoLog.d(WM_DEBUG_ANIM, "Clear animatingExit: reason=exitAnimationDone win=%s", this);
        getDisplayContent().mWallpaperController.hideWallpapers(this);
    }

    @Override
    boolean handleCompleteDeferredRemoval() {
        if (mRemoveOnExit && !isSelfAnimating(0 /* flags */, ANIMATION_TYPE_WINDOW_ANIMATION)) {
            mRemoveOnExit = false;
            removeImmediately();
        }
        return super.handleCompleteDeferredRemoval();
    }

    boolean clearAnimatingFlags() {
        boolean didSomething = false;
        // We also don't clear the mAnimatingExit flag for windows which have the
        // mRemoveOnExit flag. This indicates an explicit remove request has been issued
        // by the client. We should let animation proceed and not clear this flag or
        // they won't eventually be removed by WindowStateAnimator#finishExit.
        if (!mRemoveOnExit) {
            // Clear mAnimating flag together with mAnimatingExit. When animation
            // changes from exiting to entering, we need to clear this flag until the
            // new animation gets applied, so that isAnimationStarting() becomes true
            // until then.
            // Otherwise applySurfaceChangesTransaction will fail to skip surface
            // placement for this window during this period, one or more frame will
            // show up with wrong position or scale.
            if (mAnimatingExit) {
                mAnimatingExit = false;
                ProtoLog.d(WM_DEBUG_ANIM, "Clear animatingExit: reason=clearAnimatingFlags win=%s",
                        this);
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
                        + " th=" + (mActivityRecord != null && mActivityRecord.isVisibleRequested())
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
            }
            mAnimatingExit = false;
            ProtoLog.d(WM_DEBUG_ANIM, "Clear animatingExit: reason=relayoutVisibleWindow win=%s",
                    this);
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

        return result;
    }

    /**
     * @return True if this window has been laid out at least once; false otherwise.
     */
    boolean isLaidOut() {
        return mLayoutSeq != -1;
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

        ProtoLog.v(WM_DEBUG_ANIM, "Setting move animation on %s", this);
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
        float9[Matrix.MSCALE_X] = mGlobalScale;
        float9[Matrix.MSKEW_Y] = 0;
        float9[Matrix.MSKEW_X] = 0;
        float9[Matrix.MSCALE_Y] = mGlobalScale;
        transformSurfaceInsetsPosition(mTmpPoint, mAttrs.surfaceInsets);
        int x = mSurfacePosition.x + mTmpPoint.x;
        int y = mSurfacePosition.y + mTmpPoint.y;

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
        if ((mAttrs.privateFlags & PRIVATE_FLAG_NOT_MAGNIFIABLE) != 0) {
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
        return false;
    }

    private boolean isStartingWindowAssociatedToTask() {
        return mStartingData != null && mStartingData.mAssociatedTask != null;
    }

    private void applyDims() {
        if (((mAttrs.flags & FLAG_DIM_BEHIND) != 0 || shouldDrawBlurBehind())
                   && isVisibleNow() && !mHidden && mTransitionController.canApplyDim(getTask())) {
            // Only show the Dimmer when the following is satisfied:
            // 1. The window has the flag FLAG_DIM_BEHIND or blur behind is requested
            // 2. The WindowToken is not hidden so dims aren't shown when the window is exiting.
            // 3. The WS is considered visible according to the isVisible() method
            // 4. The WS is not hidden.
            // 5. The window is not in a transition or is in a transition that allows to dim.
            mIsDimming = true;
            final float dimAmount = (mAttrs.flags & FLAG_DIM_BEHIND) != 0 ? mAttrs.dimAmount : 0;
            final int blurRadius = shouldDrawBlurBehind() ? mAttrs.getBlurBehindRadius() : 0;
            getDimmer().dimBelow(getSyncTransaction(), this, dimAmount, blurRadius);
        }
    }

    private boolean shouldDrawBlurBehind() {
        return (mAttrs.flags & FLAG_BLUR_BEHIND) != 0
            && mWmService.mBlurController.getBlurEnabled();
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

        boolean voteChanged = refreshRatePolicy.updateFrameRateVote(this);
        if (voteChanged) {
            getPendingTransaction().setFrameRate(
                    mSurfaceControl, mFrameRateVote.mRefreshRate,
                    mFrameRateVote.mCompatibility, Surface.CHANGE_FRAME_RATE_ALWAYS);

        }
    }

    private void updateScaleIfNeeded() {
        if (!isVisibleRequested() && !(mIsWallpaper && mToken.isVisible())) {
            // Skip if it is requested to be invisible, but if it is wallpaper, it may be in
            // transition that still needs to update the scale for zoom effect.
            return;
        }
        float globalScale = mGlobalScale;
        final WindowState parent = getParentWindow();
        if (parent != null) {
            // Undo parent's scale because the child surface has inherited scale from parent.
            globalScale *= parent.mInvGlobalScale;
        }
        final float newHScale = mHScale * globalScale * mWallpaperScale;
        final float newVScale = mVScale * globalScale * mWallpaperScale;
        if (mLastHScale != newHScale || mLastVScale != newVScale) {
            getSyncTransaction().setMatrix(mSurfaceControl, newHScale, 0, 0, newVScale);
            mLastHScale = newHScale;
            mLastVScale = newVScale;
        }
    }

    @Override
    void prepareSurfaces() {
        mIsDimming = false;
        if (mHasSurface) {
            applyDims();
            updateSurfacePositionNonOrganized();
            // Send information to SurfaceFlinger about the priority of the current window.
            updateFrameRateSelectionPriorityIfNeeded();
            updateScaleIfNeeded();
            mWinAnimator.prepareSurfaceLocked(getSyncTransaction());
        }
        super.prepareSurfaces();
    }

    @Override
    @VisibleForTesting
    void updateSurfacePosition(Transaction t) {
        if (mSurfaceControl == null) {
            return;
        }

        if ((mWmService.mWindowPlacerLocked.isLayoutDeferred() || isGoneForLayout())
                && !mSurfacePlacementNeeded) {
            // Since this relies on mWindowFrames, changes made while layout is deferred are
            // likely to be invalid. Similarly, if it's goneForLayout, mWindowFrames may not be
            // up-to-date and thus can't be relied on.
            return;
        }

        mSurfacePlacementNeeded = false;
        transformFrameToSurfacePosition(mWindowFrames.mFrame.left, mWindowFrames.mFrame.top,
                mSurfacePosition);

        if (mWallpaperScale != 1f) {
            final Rect bounds = getParentFrame();
            Matrix matrix = mTmpMatrix;
            matrix.setTranslate(mXOffset, mYOffset);
            matrix.postScale(mWallpaperScale, mWallpaperScale, bounds.exactCenterX(),
                    bounds.exactCenterY());
            matrix.getValues(mTmpMatrixArray);
            mSurfacePosition.offset(Math.round(mTmpMatrixArray[Matrix.MTRANS_X]),
                Math.round(mTmpMatrixArray[Matrix.MTRANS_Y]));
        } else {
            mSurfacePosition.offset(mXOffset, mYOffset);
        }

        // Freeze position while we're unrotated, so the surface remains at the position it was
        // prior to the rotation.
        if (!mSurfaceAnimator.hasLeash() && mPendingSeamlessRotate == null
                && !mLastSurfacePosition.equals(mSurfacePosition)) {
            final boolean frameSizeChanged = mWindowFrames.isFrameSizeChangeReported();
            final boolean surfaceInsetsChanged = surfaceInsetsChanging();
            final boolean surfaceSizeChanged = frameSizeChanged || surfaceInsetsChanged;
            mLastSurfacePosition.set(mSurfacePosition.x, mSurfacePosition.y);
            if (surfaceInsetsChanged) {
                mLastSurfaceInsets.set(mAttrs.surfaceInsets);
            }
            final boolean surfaceResizedWithoutMoveAnimation = surfaceSizeChanged
                    && mWinAnimator.getShown() && !canPlayMoveAnimation() && okToDisplay()
                    && mSyncState == SYNC_STATE_NONE;
            final ActivityRecord activityRecord = getActivityRecord();
            // If this window belongs to an activity that is relaunching due to an orientation
            // change then delay the position update until it has redrawn to avoid any flickers.
            final boolean isLetterboxedAndRelaunching = activityRecord != null
                    && activityRecord.areBoundsLetterboxed()
                    && activityRecord.mLetterboxUiController
                        .getIsRelaunchingAfterRequestedOrientationChanged();
            if (surfaceResizedWithoutMoveAnimation || isLetterboxedAndRelaunching) {
                applyWithNextDraw(mSetSurfacePositionConsumer);
            } else {
                mSetSurfacePositionConsumer.accept(t);
            }
        }
    }

    void transformFrameToSurfacePosition(int left, int top, Point outPoint) {
        outPoint.set(left, top);

        // If changed, also adjust getTransformationMatrix
        final WindowContainer parentWindowContainer = getParent();
        if (isChildWindow()) {
            final WindowState parent = getParentWindow();
            outPoint.offset(-parent.mWindowFrames.mFrame.left, -parent.mWindowFrames.mFrame.top);
            // Undo the scale of window position because the relative coordinates for child are
            // based on the scaled parent.
            if (mInvGlobalScale != 1f) {
                outPoint.x = (int) (outPoint.x * mInvGlobalScale + 0.5f);
                outPoint.y = (int) (outPoint.y * mInvGlobalScale + 0.5f);
            }
            // Since the parent was outset by its surface insets, we need to undo the outsetting
            // with insetting by the same amount.
            transformSurfaceInsetsPosition(mTmpPoint, parent.mAttrs.surfaceInsets);
            outPoint.offset(mTmpPoint.x, mTmpPoint.y);
        } else if (parentWindowContainer != null) {
            final Rect parentBounds = isStartingWindowAssociatedToTask()
                    ? mStartingData.mAssociatedTask.getBounds()
                    : parentWindowContainer.getBounds();
            outPoint.offset(-parentBounds.left, -parentBounds.top);
        }

        // The surface size is larger than the window if the window has positive surface insets.
        transformSurfaceInsetsPosition(mTmpPoint, mAttrs.surfaceInsets);
        outPoint.offset(-mTmpPoint.x, -mTmpPoint.y);

        outPoint.y += mSurfaceTranslationY;
    }

    /**
     * The surface insets from layout parameter are in application coordinate. If the window is
     * scaled, the insets also need to be scaled for surface position in global coordinate.
     */
    private void transformSurfaceInsetsPosition(Point outPos, Rect surfaceInsets) {
        // Ignore the scale for child window because its insets have been scaled with the
        // parent surface.
        if (mGlobalScale == 1f || mIsChildWindow) {
            outPos.x = surfaceInsets.left;
            outPos.y = surfaceInsets.top;
            return;
        }
        outPos.x = (int) (surfaceInsets.left * mGlobalScale + 0.5f);
        outPos.y = (int) (surfaceInsets.top * mGlobalScale + 0.5f);
    }

    boolean needsRelativeLayeringToIme() {
        // We use the relative layering when IME isn't attached to the app. Such as part of
        // elevating the IME and windows above it's target above the docked divider in
        // split-screen, or make the popupMenu to be above the IME when the parent window is the
        // IME layering target in bubble/freeform mode.
        if (mDisplayContent.shouldImeAttachedToApp()) {
            return false;
        }

        // We don't need to set the window to be relatively above IME if the IME is not visible.
        // In case seeing the window is animating above the app transition layer because its
        // relative layer is above the IME container on the display area but actually not necessary.
        if (!getDisplayContent().getImeContainer().isVisible()) {
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

        // The condition is for the system dialog not belonging to any Activity.
        // (^FLAG_NOT_FOCUSABLE & FLAG_ALT_FOCUSABLE_IM) means the dialog is still focusable but
        // should be placed above the IME window.
        if ((mAttrs.flags & (FLAG_NOT_FOCUSABLE | FLAG_ALT_FOCUSABLE_IM))
                == FLAG_ALT_FOCUSABLE_IM && isTrustedOverlay() && canAddInternalSystemWindow()) {
            // Check the current IME target so that it does not lift this window above the IME if
            // the Z-order of the current IME layering target is greater than it.
            final WindowState imeTarget = getImeLayeringTarget();
            return imeTarget != null && imeTarget != this && imeTarget.compareTo(this) <= 0;
        }
        return false;
    }

    /**
     * Get IME target that should host IME.
     * Note: IME is never hosted by a display that doesn't support IME/system decorations.
     * When window calling
     * {@link android.view.inputmethod.InputMethodManager#showSoftInput(View, int)} is unknown,
     * use {@link DisplayContent#getImeControlTarget()} instead.
     *
     * @return {@link InsetsControlTarget} of host that controls the IME.
     *         When window is doesn't have a parent, it is returned as-is.
     */
    @Override
    public InsetsControlTarget getImeControlTarget() {
        return getDisplayContent().getImeHostOrFallback(this);
    }

    @Override
    void assignLayer(Transaction t, int layer) {
        if (mStartingData != null) {
            // The starting window should cover the task.
            t.setLayer(mSurfaceControl, Integer.MAX_VALUE);
            return;
        }
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

    @Override
    protected void reparentSurfaceControl(Transaction t, SurfaceControl newParent) {
        if (isStartingWindowAssociatedToTask()) {
            // Its surface is already put in task. Don't reparent when transferring starting window
            // across activities.
            return;
        }
        super.reparentSurfaceControl(t, newParent);
    }

    @Override
    public SurfaceControl getAnimationLeashParent() {
        if (isStartingWindowAssociatedToTask()) {
            return mStartingData.mAssociatedTask.mSurfaceControl;
        }
        return super.getAnimationLeashParent();
    }

    @Override
    public void onAnimationLeashCreated(Transaction t, SurfaceControl leash) {
        super.onAnimationLeashCreated(t, leash);
        if (isStartingWindowAssociatedToTask()) {
            // Make sure the animation leash is still on top of the task.
            t.setLayer(leash, Integer.MAX_VALUE);
        }
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

    /**
     * Whether the window is non-focusable IME overlay layering target.
     */
    boolean isImeOverlayLayeringTarget() {
        return isImeLayeringTarget()
                && (mAttrs.flags & (FLAG_ALT_FOCUSABLE_IM | FLAG_NOT_FOCUSABLE)) != 0;
    }

    WindowState getImeLayeringTarget() {
        final InsetsControlTarget target = getDisplayContent().getImeTarget(IME_TARGET_LAYERING);
        return target != null ? target.getWindow() : null;
    }

    WindowState getImeInputTarget() {
        final InputTarget target = mDisplayContent.getImeInputTarget();
        return target != null ? target.getWindowState() : null;
    }

    void forceReportingResized() {
        mWindowFrames.forceReportingResized();
    }

    /** Returns the {@link WindowFrames} associated with this {@link WindowState}. */
    WindowFrames getWindowFrames() {
        return mWindowFrames;
    }

    void resetContentChanged() {
        mWindowFrames.setContentChanged(false);
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
        } else if (areAppWindowBoundsLetterboxed() || mToken.isFixedRotationTransforming()) {
            // 1. The letterbox surfaces should be animated with the owner activity, so use task
            //    bounds to include them.
            // 2. If the activity has fixed rotation transform, its windows are rotated in activity
            //    level. Because the animation runs before display is rotated, task bounds should
            //    represent the frames in display space coordinates.
            outFrame.set(getTask().getBounds());
        } else {
            outFrame.set(getParentFrame());
        }
        outSurfaceInsets.set(getAttrs().surfaceInsets);
        final InsetsState state = getInsetsStateWithVisibilityOverride();
        outInsets.set(state.calculateInsets(outFrame, systemBars(),
                false /* ignoreVisibility */).toRect());
        outStableInsets.set(state.calculateInsets(outFrame, systemBars(),
                true /* ignoreVisibility */).toRect());
    }

    void setViewVisibility(int viewVisibility) {
        mViewVisibility = viewVisibility;
    }

    SurfaceControl getClientViewRootSurface() {
        return mWinAnimator.getSurfaceControl();
    }

    /** Drops a buffer for this window's view-root from a transaction */
    private void dropBufferFrom(Transaction t) {
        SurfaceControl viewSurface = getClientViewRootSurface();
        if (viewSurface == null) return;
        t.unsetBuffer(viewSurface);
    }

    @Override
    protected boolean shouldUpdateSyncOnReparent() {
        // Keep the sync state in case the client is drawing for the latest conifguration or the
        // configuration is not changed after reparenting. This avoids a redundant redraw request.
        return mSyncState != SYNC_STATE_NONE && !mLastConfigReportedToClient;
    }

    @Override
    boolean prepareSync() {
        if (!mDrawHandlers.isEmpty()) {
            Slog.w(TAG, "prepareSync with mDrawHandlers, " + this + ", " + Debug.getCallers(8));
        }
        if (!super.prepareSync()) {
            return false;
        }
        if (mIsWallpaper) {
            // TODO(b/233286785): Add sync support to wallpaper.
            return false;
        }
        // In the WindowContainer implementation we immediately mark ready
        // since a generic WindowContainer only needs to wait for its
        // children to finish and is immediately ready from its own
        // perspective but at the WindowState level we need to wait for ourselves
        // to draw even if the children draw first or don't need to sync, so we start
        // in WAITING state rather than READY.
        mSyncState = SYNC_STATE_WAITING_FOR_DRAW;

        if (mPrepareSyncSeqId > 0) {
            // another prepareSync during existing sync (eg. reparented), so pre-emptively
            // drop buffer (if exists). If the buffer hasn't been received yet, it will be
            // dropped in finishDrawing.
            ProtoLog.d(WM_DEBUG_SYNC_ENGINE, "Preparing to sync a window that was already in the"
                            + " sync, so try dropping buffer. win=%s", this);
            dropBufferFrom(mSyncTransaction);
        }

        mSyncSeqId++;
        if (getSyncMethod() == BLASTSyncEngine.METHOD_BLAST) {
            mPrepareSyncSeqId = mSyncSeqId;
            requestRedrawForSync();
        } else if (mHasSurface && mWinAnimator.mDrawState != DRAW_PENDING) {
            // Only need to request redraw if the window has reported draw.
            requestRedrawForSync();
        }
        return true;
    }

    @Override
    boolean isSyncFinished(BLASTSyncEngine.SyncGroup group) {
        if (!isVisibleRequested() || isFullyTransparent()) {
            // Don't wait for invisible windows. However, we don't alter the state in case the
            // window becomes visible while the sync group is still active.
            return true;
        }
        if (mSyncState == SYNC_STATE_WAITING_FOR_DRAW && mWinAnimator.mDrawState == HAS_DRAWN
                && !mRedrawForSyncReported && !mWmService.mResizingWindows.contains(this)) {
            // Complete the sync state immediately for a drawn window that doesn't need to redraw.
            onSyncFinishedDrawing();
        }
        return super.isSyncFinished(group);
    }

    @Override
    void finishSync(Transaction outMergedTransaction, BLASTSyncEngine.SyncGroup group,
            boolean cancel) {
        final BLASTSyncEngine.SyncGroup syncGroup = getSyncGroup();
        if (syncGroup != null && group != syncGroup) return;
        mPrepareSyncSeqId = 0;
        if (cancel) {
            // This is leaving sync so any buffers left in the sync have a chance of
            // being applied out-of-order and can also block the buffer queue for this
            // window. To prevent this, drop the buffer.
            dropBufferFrom(mSyncTransaction);
        }
        super.finishSync(outMergedTransaction, group, cancel);
    }

    boolean finishDrawing(SurfaceControl.Transaction postDrawTransaction, int syncSeqId) {
        if (mOrientationChangeRedrawRequestTime > 0) {
            final long duration =
                    SystemClock.elapsedRealtime() - mOrientationChangeRedrawRequestTime;
            Slog.i(TAG, "finishDrawing of orientation change: " + this + " " + duration + "ms");
            mOrientationChangeRedrawRequestTime = 0;
        } else if (mActivityRecord != null && mActivityRecord.mRelaunchStartTime != 0
                && mActivityRecord.findMainWindow(false /* includeStartingApp */) == this) {
            final long duration =
                    SystemClock.elapsedRealtime() - mActivityRecord.mRelaunchStartTime;
            Slog.i(TAG, "finishDrawing of relaunch: " + this + " " + duration + "ms");
            mActivityRecord.finishOrAbortReplacingWindow();
        }
        if (mActivityRecord != null && mAttrs.type == TYPE_APPLICATION_STARTING) {
            mWmService.mAtmService.mTaskSupervisor.getActivityMetricsLogger()
                    .notifyStartingWindowDrawn(mActivityRecord);
        }

        final boolean syncActive = mPrepareSyncSeqId > 0;
        final boolean syncStillPending = syncActive && mPrepareSyncSeqId > syncSeqId;
        if (syncStillPending && postDrawTransaction != null) {
            ProtoLog.d(WM_DEBUG_SYNC_ENGINE, "Got a buffer for request id=%d but latest request is"
                    + " id=%d. Since the buffer is out-of-date, drop it. win=%s", syncSeqId,
                    mPrepareSyncSeqId, this);
            // sync is waiting for a newer seqId, so this buffer is obsolete and can be dropped
            // to free up the buffer queue.
            dropBufferFrom(postDrawTransaction);
        }

        final boolean hasSyncHandlers = executeDrawHandlers(postDrawTransaction, syncSeqId);

        boolean skipLayout = false;
        boolean layoutNeeded = false;
        // Control the timing to switch the appearance of window with different rotations.
        final AsyncRotationController asyncRotationController =
                mDisplayContent.getAsyncRotationController();
        if (asyncRotationController != null
                && asyncRotationController.handleFinishDrawing(this, postDrawTransaction)) {
            // Consume the transaction because the controller will apply it with fade animation.
            // Layout is not needed because the window will be hidden by the fade leash.
            postDrawTransaction = null;
            skipLayout = true;
        } else if (syncActive) {
            // Currently in a Sync that is using BLAST.
            if (!syncStillPending) {
                layoutNeeded = onSyncFinishedDrawing();
            }
            if (postDrawTransaction != null) {
                mSyncTransaction.merge(postDrawTransaction);
                // Consume the transaction because the sync group will merge it.
                postDrawTransaction = null;
            }
        } else if (useBLASTSync()) {
            // Sync that is not using BLAST
            layoutNeeded = onSyncFinishedDrawing();
        }

        layoutNeeded |= mWinAnimator.finishDrawingLocked(postDrawTransaction);
        // We always want to force a traversal after a finish draw for blast sync.
        return !skipLayout && (hasSyncHandlers || layoutNeeded);
    }

    void immediatelyNotifyBlastSync() {
        // We could be more subtle with Integer.MAX_VALUE and track a seqId in the timeout.
        finishDrawing(null, Integer.MAX_VALUE);
        mWmService.mH.removeMessages(WINDOW_STATE_BLAST_SYNC_TIMEOUT, this);
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
        return hasWallpaper();
    }

    boolean hasWallpaper() {
        return (mAttrs.flags & FLAG_SHOW_WALLPAPER) != 0 || hasWallpaperForLetterboxBackground();
    }

    boolean hasWallpaperForLetterboxBackground() {
        return mActivityRecord != null && mActivityRecord.hasWallpaperBackgroundForLetterbox();
    }

    /**
     * When using the two WindowOrganizer sync-primitives (BoundsChangeTransaction, BLASTSync)
     * it can be a little difficult to predict whether your change will actually trigger redrawing
     * on the client side. To ease the burden on shell developers, we force send MSG_RESIZED
     * for Windows involved in these Syncs
     */
    private boolean shouldSendRedrawForSync() {
        if (mRedrawForSyncReported) {
            return false;
        }
        if (mInRelayout && (mPrepareSyncSeqId > 0 || (mViewVisibility == View.VISIBLE
                && mWinAnimator.mDrawState == DRAW_PENDING))) {
            // The client will report draw if it gets the sync seq id from relayout or it is
            // drawing for being visible, then no need to request redraw.
            return false;
        }
        return useBLASTSync();
    }

    int getSyncMethod() {
        final BLASTSyncEngine.SyncGroup syncGroup = getSyncGroup();
        if (syncGroup == null) return BLASTSyncEngine.METHOD_NONE;
        if (mSyncMethodOverride != BLASTSyncEngine.METHOD_UNDEFINED) return mSyncMethodOverride;
        return syncGroup.mSyncMethod;
    }

    boolean shouldSyncWithBuffers() {
        if (!mDrawHandlers.isEmpty()) return true;
        return getSyncMethod() == BLASTSyncEngine.METHOD_BLAST;
    }

    void requestRedrawForSync() {
        mRedrawForSyncReported = false;
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
        return super.useBLASTSync() || (mDrawHandlers.size() != 0);
    }

    /**
     * Apply the transaction with the next window redraw. A full relayout/finishDrawing
     * cycle must occur before completion. This means if you call the function while
     * "in relayout", the results may be undefined but at all other times the function
     * should sort of transparently work like this:
     *    1. Make changes to WM hierarchy (say change app configuration)
     *    2. Call applyWithNextDraw
     *    3. After finishDrawing, our consumer will be passed the Transaction
     *    containing the buffer, and we can merge in additional operations.
     * See {@link WindowState#mDrawHandlers}
     */
    void applyWithNextDraw(Consumer<SurfaceControl.Transaction> consumer) {
        if (mSyncState != SYNC_STATE_NONE) {
            Slog.w(TAG, "applyWithNextDraw with mSyncState=" + mSyncState + ", " + this
                    + ", " + Debug.getCallers(8));
        }
        mSyncSeqId++;
        mDrawHandlers.add(new DrawHandler(mSyncSeqId, consumer));

        requestRedrawForSync();

        mWmService.mH.sendNewMessageDelayed(WINDOW_STATE_BLAST_SYNC_TIMEOUT, this,
            BLAST_TIMEOUT_DURATION);
    }

    /**
     * Drain the draw handlers, called from finishDrawing()
     * See {@link WindowState#mPendingDrawHandlers}
     */
    boolean executeDrawHandlers(SurfaceControl.Transaction t, int seqId) {
        boolean hadHandlers = false;
        boolean applyHere = false;
        if (t == null) {
            t = mTmpTransaction;
            applyHere = true;
        }

        final List<DrawHandler> handlersToRemove = new ArrayList<>();
        // Iterate forwards to ensure we process in the same order
        // we added.
        for (int i = 0; i < mDrawHandlers.size(); i++) {
            final DrawHandler h = mDrawHandlers.get(i);
            if (h.mSeqId <= seqId) {
                h.mConsumer.accept(t);
                handlersToRemove.add(h);
                hadHandlers = true;
            }
        }
        for (int i = 0; i < handlersToRemove.size(); i++) {
            final DrawHandler h = handlersToRemove.get(i);
            mDrawHandlers.remove(h);
        }

        if (hadHandlers) {
            mWmService.mH.removeMessages(WINDOW_STATE_BLAST_SYNC_TIMEOUT, this);
        }

        if (applyHere) {
            t.apply();
        }

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

    void markRedrawForSyncReported() {
       mRedrawForSyncReported = true;
    }

    boolean setWallpaperOffset(int dx, int dy, float scale) {
        if (mXOffset == dx && mYOffset == dy && Float.compare(mWallpaperScale, scale) == 0) {
            return false;
        }
        mXOffset = dx;
        mYOffset = dy;
        mWallpaperScale = scale;
        scheduleAnimation();
        return true;
    }

    boolean isTrustedOverlay() {
        return mInputWindowHandle.isTrustedOverlay();
    }

    public boolean receiveFocusFromTapOutside() {
        return canReceiveKeys(true);
    }

    @Override
    public void handleTapOutsideFocusOutsideSelf() {
        // Nothing to do here since raising the other window will naturally take care of
        // us loosing focus
    }

    @Override
    public void handleTapOutsideFocusInsideSelf() {
        mWmService.moveDisplayToTopInternal(getDisplayId());
        mWmService.handleTaskFocusChange(getTask(), mActivityRecord);
    }

    void clearClientTouchableRegion() {
        mTouchableInsets = ViewTreeObserver.InternalInsetsInfo.TOUCHABLE_INSETS_FRAME;
        mGivenTouchableRegion.setEmpty();
    }

    @Override
    public boolean shouldControlIme() {
        return !inMultiWindowMode();
    }

    @Override
    public boolean canScreenshotIme() {
        return !isSecureLocked();
    }

    @Override
    public ActivityRecord getActivityRecord() {
        return mActivityRecord;
    }

    @Override
    public boolean isInputMethodClientFocus(int uid, int pid) {
        return getDisplayContent().isInputMethodClientFocus(uid, pid);
    }

    @Override
    public void dumpProto(ProtoOutputStream proto, long fieldId,
                          @WindowTraceLogLevel int logLevel) {
        dumpDebug(proto, fieldId, logLevel);
    }

    public boolean cancelAndRedraw() {
        // Cancel any draw requests during a sync.
        return mPrepareSyncSeqId > 0;
    }
}
