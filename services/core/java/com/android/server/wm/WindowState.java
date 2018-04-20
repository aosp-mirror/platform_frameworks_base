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

import static android.app.ActivityManager.StackId.INVALID_STACK_ID;
import static android.app.AppOpsManager.MODE_ALLOWED;
import static android.app.AppOpsManager.MODE_DEFAULT;
import static android.app.AppOpsManager.OP_NONE;
import static android.app.AppOpsManager.OP_SYSTEM_ALERT_WINDOW;
import static android.app.AppOpsManager.OP_TOAST_WINDOW;
import static android.os.PowerManager.DRAW_WAKE_LOCK;
import static android.os.Trace.TRACE_TAG_WINDOW_MANAGER;
import static android.view.Display.DEFAULT_DISPLAY;
import static android.view.SurfaceControl.Transaction;
import static android.view.ViewTreeObserver.InternalInsetsInfo.TOUCHABLE_INSETS_CONTENT;
import static android.view.ViewTreeObserver.InternalInsetsInfo.TOUCHABLE_INSETS_FRAME;
import static android.view.ViewTreeObserver.InternalInsetsInfo.TOUCHABLE_INSETS_REGION;
import static android.view.ViewTreeObserver.InternalInsetsInfo.TOUCHABLE_INSETS_VISIBLE;
import static android.view.WindowManager.LayoutParams.FIRST_SUB_WINDOW;
import static android.view.WindowManager.LayoutParams.FIRST_SYSTEM_WINDOW;
import static android.view.WindowManager.LayoutParams.FLAG_ALLOW_LOCK_WHILE_SCREEN_ON;
import static android.view.WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM;
import static android.view.WindowManager.LayoutParams.FLAG_DIM_BEHIND;
import static android.view.WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD;
import static android.view.WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS;
import static android.view.WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE;
import static android.view.WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL;
import static android.view.WindowManager.LayoutParams.FLAG_SCALED;
import static android.view.WindowManager.LayoutParams.FLAG_SHOW_WALLPAPER;
import static android.view.WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED;
import static android.view.WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON;
import static android.view.WindowManager.LayoutParams.FORMAT_CHANGED;
import static android.view.WindowManager.LayoutParams.LAST_SUB_WINDOW;
import static android.view.WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS;
import static android.view.WindowManager.LayoutParams.MATCH_PARENT;
import static android.view.WindowManager.LayoutParams.PRIVATE_FLAG_COMPATIBLE_WINDOW;
import static android.view.WindowManager.LayoutParams.PRIVATE_FLAG_HIDE_NON_SYSTEM_OVERLAY_WINDOWS;
import static android.view.WindowManager.LayoutParams.PRIVATE_FLAG_LAYOUT_CHILD_WINDOW_IN_PARENT_FRAME;
import static android.view.WindowManager.LayoutParams.PRIVATE_FLAG_NO_MOVE_ANIMATION;
import static android.view.WindowManager.LayoutParams.PRIVATE_FLAG_WILL_NOT_REPLACE_ON_RELAUNCH;
import static android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE;
import static android.view.WindowManager.LayoutParams.SOFT_INPUT_MASK_ADJUST;
import static android.view.WindowManager.LayoutParams.TYPE_APPLICATION;
import static android.view.WindowManager.LayoutParams.TYPE_APPLICATION_MEDIA;
import static android.view.WindowManager.LayoutParams.TYPE_APPLICATION_MEDIA_OVERLAY;
import static android.view.WindowManager.LayoutParams.TYPE_APPLICATION_STARTING;
import static android.view.WindowManager.LayoutParams.TYPE_BASE_APPLICATION;
import static android.view.WindowManager.LayoutParams.TYPE_DOCK_DIVIDER;
import static android.view.WindowManager.LayoutParams.TYPE_DRAWN_APPLICATION;
import static android.view.WindowManager.LayoutParams.TYPE_INPUT_METHOD;
import static android.view.WindowManager.LayoutParams.TYPE_INPUT_METHOD_DIALOG;
import static android.view.WindowManager.LayoutParams.TYPE_MAGNIFICATION_OVERLAY;
import static android.view.WindowManager.LayoutParams.TYPE_NAVIGATION_BAR;
import static android.view.WindowManager.LayoutParams.TYPE_NAVIGATION_BAR_PANEL;
import static android.view.WindowManager.LayoutParams.TYPE_TOAST;
import static android.view.WindowManager.LayoutParams.TYPE_WALLPAPER;
import static android.view.WindowManager.LayoutParams.isSystemAlertWindowType;
import static android.view.WindowManagerGlobal.RELAYOUT_RES_DRAG_RESIZING_DOCKED;
import static android.view.WindowManagerGlobal.RELAYOUT_RES_DRAG_RESIZING_FREEFORM;
import static android.view.WindowManagerGlobal.RELAYOUT_RES_FIRST_TIME;
import static android.view.WindowManagerGlobal.RELAYOUT_RES_SURFACE_CHANGED;
import static com.android.server.policy.WindowManagerPolicy.FINISH_LAYOUT_REDO_WALLPAPER;
import static com.android.server.policy.WindowManagerPolicy.TRANSIT_ENTER;
import static com.android.server.policy.WindowManagerPolicy.TRANSIT_EXIT;
import static com.android.server.policy.WindowManagerPolicy.TRANSIT_PREVIEW_DONE;
import static com.android.server.wm.DragResizeMode.DRAG_RESIZE_MODE_DOCKED_DIVIDER;
import static com.android.server.wm.DragResizeMode.DRAG_RESIZE_MODE_FREEFORM;
import static com.android.server.wm.WindowManagerDebugConfig.DEBUG_ADD_REMOVE;
import static com.android.server.wm.WindowManagerDebugConfig.DEBUG_ANIM;
import static com.android.server.wm.WindowManagerDebugConfig.DEBUG_APP_TRANSITIONS;
import static com.android.server.wm.WindowManagerDebugConfig.DEBUG_CONFIGURATION;
import static com.android.server.wm.WindowManagerDebugConfig.DEBUG_FOCUS;
import static com.android.server.wm.WindowManagerDebugConfig.DEBUG_FOCUS_LIGHT;
import static com.android.server.wm.WindowManagerDebugConfig.DEBUG_INPUT_METHOD;
import static com.android.server.wm.WindowManagerDebugConfig.DEBUG_LAYOUT;
import static com.android.server.wm.WindowManagerDebugConfig.DEBUG_ORIENTATION;
import static com.android.server.wm.WindowManagerDebugConfig.DEBUG_POWER;
import static com.android.server.wm.WindowManagerDebugConfig.DEBUG_RESIZE;
import static com.android.server.wm.WindowManagerDebugConfig.DEBUG_STARTING_WINDOW;
import static com.android.server.wm.WindowManagerDebugConfig.DEBUG_STARTING_WINDOW_VERBOSE;
import static com.android.server.wm.WindowManagerDebugConfig.DEBUG_VISIBILITY;
import static com.android.server.wm.WindowManagerDebugConfig.DEBUG_WALLPAPER_LIGHT;
import static com.android.server.wm.WindowManagerDebugConfig.TAG_WITH_CLASS_NAME;
import static com.android.server.wm.WindowManagerDebugConfig.TAG_WM;
import static com.android.server.wm.WindowManagerService.H.SEND_NEW_CONFIGURATION;
import static com.android.server.wm.WindowManagerService.MAX_ANIMATION_DURATION;
import static com.android.server.wm.WindowManagerService.TYPE_LAYER_MULTIPLIER;
import static com.android.server.wm.WindowManagerService.TYPE_LAYER_OFFSET;
import static com.android.server.wm.WindowManagerService.UPDATE_FOCUS_NORMAL;
import static com.android.server.wm.WindowManagerService.UPDATE_FOCUS_WILL_PLACE_SURFACES;
import static com.android.server.wm.WindowManagerService.WINDOWS_FREEZING_SCREENS_TIMEOUT;
import static com.android.server.wm.WindowManagerService.localLOGV;
import static com.android.server.wm.WindowStateAnimator.COMMIT_DRAW_PENDING;
import static com.android.server.wm.WindowStateAnimator.DRAW_PENDING;
import static com.android.server.wm.WindowStateAnimator.HAS_DRAWN;
import static com.android.server.wm.WindowStateAnimator.READY_TO_SHOW;
import static com.android.server.wm.IdentifierProto.HASH_CODE;
import static com.android.server.wm.IdentifierProto.TITLE;
import static com.android.server.wm.IdentifierProto.USER_ID;
import static com.android.server.wm.AnimationSpecProto.MOVE;
import static com.android.server.wm.MoveAnimationSpecProto.DURATION_MS;
import static com.android.server.wm.MoveAnimationSpecProto.FROM;
import static com.android.server.wm.MoveAnimationSpecProto.TO;
import static com.android.server.wm.WindowStateProto.ANIMATING_EXIT;
import static com.android.server.wm.WindowStateProto.ANIMATOR;
import static com.android.server.wm.WindowStateProto.ATTRIBUTES;
import static com.android.server.wm.WindowStateProto.CHILD_WINDOWS;
import static com.android.server.wm.WindowStateProto.CONTAINING_FRAME;
import static com.android.server.wm.WindowStateProto.CONTENT_FRAME;
import static com.android.server.wm.WindowStateProto.CONTENT_INSETS;
import static com.android.server.wm.WindowStateProto.CUTOUT;
import static com.android.server.wm.WindowStateProto.DECOR_FRAME;
import static com.android.server.wm.WindowStateProto.DESTROYING;
import static com.android.server.wm.WindowStateProto.DISPLAY_FRAME;
import static com.android.server.wm.WindowStateProto.DISPLAY_ID;
import static com.android.server.wm.WindowStateProto.FRAME;
import static com.android.server.wm.WindowStateProto.GIVEN_CONTENT_INSETS;
import static com.android.server.wm.WindowStateProto.HAS_SURFACE;
import static com.android.server.wm.WindowStateProto.IDENTIFIER;
import static com.android.server.wm.WindowStateProto.IS_ON_SCREEN;
import static com.android.server.wm.WindowStateProto.IS_READY_FOR_DISPLAY;
import static com.android.server.wm.WindowStateProto.IS_VISIBLE;
import static com.android.server.wm.WindowStateProto.OUTSETS;
import static com.android.server.wm.WindowStateProto.OUTSET_FRAME;
import static com.android.server.wm.WindowStateProto.OVERSCAN_FRAME;
import static com.android.server.wm.WindowStateProto.OVERSCAN_INSETS;
import static com.android.server.wm.WindowStateProto.PARENT_FRAME;
import static com.android.server.wm.WindowStateProto.REMOVED;
import static com.android.server.wm.WindowStateProto.REMOVE_ON_EXIT;
import static com.android.server.wm.WindowStateProto.REQUESTED_HEIGHT;
import static com.android.server.wm.WindowStateProto.REQUESTED_WIDTH;
import static com.android.server.wm.WindowStateProto.STABLE_INSETS;
import static com.android.server.wm.WindowStateProto.STACK_ID;
import static com.android.server.wm.WindowStateProto.SURFACE_INSETS;
import static com.android.server.wm.WindowStateProto.SURFACE_POSITION;
import static com.android.server.wm.WindowStateProto.SYSTEM_UI_VISIBILITY;
import static com.android.server.wm.WindowStateProto.VIEW_VISIBILITY;
import static com.android.server.wm.WindowStateProto.VISIBLE_FRAME;
import static com.android.server.wm.WindowStateProto.VISIBLE_INSETS;
import static com.android.server.wm.WindowStateProto.WINDOW_CONTAINER;

import android.annotation.CallSuper;
import android.app.AppOpsManager;
import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Matrix;
import android.graphics.PixelFormat;
import android.graphics.Point;
import android.graphics.Rect;
import android.graphics.Region;
import android.os.Binder;
import android.os.Debug;
import android.os.IBinder;
import android.os.PowerManager;
import android.os.RemoteCallbackList;
import android.os.RemoteException;
import android.os.SystemClock;
import android.os.Trace;
import android.os.UserHandle;
import android.os.WorkSource;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.DisplayMetrics;
import android.util.MergedConfiguration;
import android.util.Slog;
import android.util.TimeUtils;
import android.util.proto.ProtoOutputStream;
import android.view.Display;
import android.view.DisplayCutout;
import android.view.DisplayInfo;
import android.view.Gravity;
import android.view.IApplicationToken;
import android.view.IWindow;
import android.view.IWindowFocusObserver;
import android.view.IWindowId;
import android.view.InputChannel;
import android.view.InputEvent;
import android.view.InputEventReceiver;
import android.view.SurfaceControl;
import android.view.SurfaceSession;
import android.view.View;
import android.view.ViewTreeObserver;
import android.view.WindowInfo;
import android.view.WindowManager;
import android.view.animation.Animation;
import android.view.animation.AnimationUtils;
import android.view.animation.Interpolator;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.util.ToBooleanFunction;
import com.android.server.input.InputWindowHandle;
import com.android.server.policy.WindowManagerPolicy;
import com.android.server.wm.LocalAnimationAdapter.AnimationSpec;
import com.android.server.wm.utils.WmDisplayCutout;

import java.io.PrintWriter;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.function.Predicate;

/** A window in the window manager. */
class WindowState extends WindowContainer<WindowState> implements WindowManagerPolicy.WindowState {
    static final String TAG = TAG_WITH_CLASS_NAME ? "WindowState" : TAG_WM;

    // The minimal size of a window within the usable area of the freeform stack.
    // TODO(multi-window): fix the min sizes when we have mininum width/height support,
    //                     use hard-coded min sizes for now.
    static final int MINIMUM_VISIBLE_WIDTH_IN_DP = 48;
    static final int MINIMUM_VISIBLE_HEIGHT_IN_DP = 32;

    // The thickness of a window resize handle outside the window bounds on the free form workspace
    // to capture touch events in that area.
    static final int RESIZE_HANDLE_WIDTH_IN_DP = 30;

    final WindowManagerPolicy mPolicy;
    final Context mContext;
    final Session mSession;
    final IWindow mClient;
    final int mAppOp;
    // UserId and appId of the owner. Don't display windows of non-current user.
    final int mOwnerUid;
    /** The owner has {@link android.Manifest.permission#INTERNAL_SYSTEM_WINDOW} */
    final boolean mOwnerCanAddInternalSystemWindow;
    final WindowId mWindowId;
    WindowToken mToken;
    // The same object as mToken if this is an app window and null for non-app windows.
    AppWindowToken mAppToken;

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
    int mSeq;
    boolean mEnforceSizeCompat;
    int mViewVisibility;
    int mSystemUiVisibility;
    /**
     * The visibility of the window based on policy like {@link WindowManagerPolicy}.
     * Normally set by calling {@link #showLw} and {@link #hideLw}.
     */
    boolean mPolicyVisibility = true;
    /**
     * What {@link #mPolicyVisibility} should be set to after a transition animation.
     * For example, {@link #mPolicyVisibility} might true during an exit animation to hide it and
     * then set to the value of {@link #mPolicyVisibilityAfterAnim} which is false after the exit
     * animation is done.
     */
    boolean mPolicyVisibilityAfterAnim = true;
    // overlay window is hidden because the owning app is suspended
    private boolean mHiddenWhileSuspended;
    private boolean mAppOpVisibility = true;
    boolean mPermanentlyHidden; // the window should never be shown again
    // This is a non-system overlay window that is currently force hidden.
    private boolean mForceHideNonSystemOverlayWindow;
    boolean mAppFreezing;
    boolean mHidden = true;    // Used to determine if to show child windows.
    boolean mWallpaperVisible;  // for wallpaper, what was last vis report?
    private boolean mDragResizing;
    private boolean mDragResizingChangeReported = true;
    private int mResizeMode;

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

    /**
     * Used to store last reported to client configuration and check if we have newer available.
     * We'll send configuration to client only if it is different from the last applied one and
     * client won't perform unnecessary updates.
     */
    private final MergedConfiguration mLastReportedConfiguration = new MergedConfiguration();

    /**
     * Insets that determine the actually visible area.  These are in the application's
     * coordinate space (without compatibility scale applied).
     */
    final Rect mVisibleInsets = new Rect();
    private final Rect mLastVisibleInsets = new Rect();
    private boolean mVisibleInsetsChanged;

    /**
     * Insets that are covered by system windows (such as the status bar) and
     * transient docking windows (such as the IME).  These are in the application's
     * coordinate space (without compatibility scale applied).
     */
    final Rect mContentInsets = new Rect();
    final Rect mLastContentInsets = new Rect();

    /**
     * The last content insets returned to the client in relayout. We use
     * these in the bounds animation to ensure we only observe inset changes
     * at the same time that a client resizes it's surface so that we may use
     * the geometryAppliesWithResize synchronization mechanism to keep
     * the contents in place.
     */
    final Rect mLastRelayoutContentInsets = new Rect();

    private boolean mContentInsetsChanged;

    /**
     * Insets that determine the area covered by the display overscan region.  These are in the
     * application's coordinate space (without compatibility scale applied).
     */
    final Rect mOverscanInsets = new Rect();
    private final Rect mLastOverscanInsets = new Rect();
    private boolean mOverscanInsetsChanged;

    /**
     * Insets that determine the area covered by the stable system windows.  These are in the
     * application's coordinate space (without compatibility scale applied).
     */
    final Rect mStableInsets = new Rect();
    private final Rect mLastStableInsets = new Rect();
    private boolean mStableInsetsChanged;

    /**
     * Outsets determine the area outside of the surface where we want to pretend that it's possible
     * to draw anyway.
     */
    final Rect mOutsets = new Rect();
    private final Rect mLastOutsets = new Rect();
    private boolean mOutsetsChanged = false;

    /** Part of the display that has been cut away. See {@link DisplayCutout}. */
    WmDisplayCutout mDisplayCutout = WmDisplayCutout.NO_CUTOUT;
    private WmDisplayCutout mLastDisplayCutout = WmDisplayCutout.NO_CUTOUT;
    private boolean mDisplayCutoutChanged;

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
    float mInvGlobalScale=1;
    float mHScale=1, mVScale=1;
    float mLastHScale=1, mLastVScale=1;
    final Matrix mTmpMatrix = new Matrix();

    // "Real" frame that the application sees, in display coordinate space.
    final Rect mFrame = new Rect();
    final Rect mLastFrame = new Rect();
    private boolean mFrameSizeChanged = false;
    // Frame that is scaled to the application's coordinate space when in
    // screen size compatibility mode.
    final Rect mCompatFrame = new Rect();

    final Rect mContainingFrame = new Rect();

    private final Rect mParentFrame = new Rect();

    /** Whether the parent frame would have been different if there was no display cutout. */
    private boolean mParentFrameWasClippedByDisplayCutout;

    // The entire screen area of the {@link TaskStack} this window is in. Usually equal to the
    // screen area of the device.
    final Rect mDisplayFrame = new Rect();

    // The region of the display frame that the display type supports displaying content on. This
    // is mostly a special case for TV where some displays don’t have the entire display usable.
    // {@link WindowManager.LayoutParams#FLAG_LAYOUT_IN_OVERSCAN} flag can be used to allow
    // window display contents to extend into the overscan region.
    private final Rect mOverscanFrame = new Rect();

    // The display frame minus the stable insets. This value is always constant regardless of if
    // the status bar or navigation bar is visible.
    private final Rect mStableFrame = new Rect();

    // The area not occupied by the status and navigation bars. So, if both status and navigation
    // bars are visible, the decor frame is equal to the stable frame.
    final Rect mDecorFrame = new Rect();

    // Equal to the decor frame if the IME (e.g. keyboard) is not present. Equal to the decor frame
    // minus the area occupied by the IME if the IME is present.
    private final Rect mContentFrame = new Rect();

    // Legacy stuff. Generally equal to the content frame expect when the IME for older apps
    // displays hint text.
    final Rect mVisibleFrame = new Rect();

    // Frame that includes dead area outside of the surface but where we want to pretend that it's
    // possible to draw.
    private final Rect mOutsetFrame = new Rect();

    /**
     * Usually empty. Set to the task's tempInsetFrame. See
     *{@link android.app.IActivityManager#resizeDockedStack}.
     */
    private final Rect mInsetFrame = new Rect();

    boolean mContentChanged;

    // If a window showing a wallpaper: the requested offset for the
    // wallpaper; if a wallpaper window: the currently applied offset.
    float mWallpaperX = -1;
    float mWallpaperY = -1;

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
    final InputWindowHandle mInputWindowHandle;
    InputChannel mInputChannel;
    private InputChannel mClientChannel;

    // Used to improve performance of toString()
    private String mStringNameCache;
    private CharSequence mLastTitle;
    private boolean mWasExiting;

    final WindowStateAnimator mWinAnimator;

    boolean mHasSurface = false;

    /** When true this window can be displayed on screens owther than mOwnerUid's */
    private boolean mShowToOwnerOnly;

    // Whether the window was visible when we set the app to invisible last time. WM uses
    // this as a hint to restore the surface (if available) for early animation next time
    // the app is brought visible.
    private boolean mWasVisibleBeforeClientHidden;

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

    final private Rect mTmpRect = new Rect();

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
     * A region inside of this window to be excluded from touch-related focus switches.
     */
    private TapExcludeRegionHolder mTapExcludeRegionHolder;

    /**
     * Used for testing because the real PowerManager is final.
     */
    private PowerManagerWrapper mPowerManagerWrapper;

    /**
     * A frame number in which changes requested in this layout will be rendered.
     */
    private long mFrameNumber = -1;

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

    private static final float DEFAULT_DIM_AMOUNT_DEAD_WINDOW = 0.5f;

    interface PowerManagerWrapper {
        void wakeUp(long time, String reason);

        boolean isInteractive();

    }

    WindowState(WindowManagerService service, Session s, IWindow c, WindowToken token,
            WindowState parentWindow, int appOp, int seq, WindowManager.LayoutParams a,
            int viewVisibility, int ownerId, boolean ownerCanAddInternalSystemWindow) {
        this(service, s, c, token, parentWindow, appOp, seq, a, viewVisibility, ownerId,
                ownerCanAddInternalSystemWindow, new PowerManagerWrapper() {
                    @Override
                    public void wakeUp(long time, String reason) {
                        service.mPowerManager.wakeUp(time, reason);
                    }

                    @Override
                    public boolean isInteractive() {
                        return service.mPowerManager.isInteractive();
                    }
                });
    }

    WindowState(WindowManagerService service, Session s, IWindow c, WindowToken token,
            WindowState parentWindow, int appOp, int seq, WindowManager.LayoutParams a,
            int viewVisibility, int ownerId, boolean ownerCanAddInternalSystemWindow,
            PowerManagerWrapper powerManagerWrapper) {
        super(service);
        mSession = s;
        mClient = c;
        mAppOp = appOp;
        mToken = token;
        mAppToken = mToken.asAppWindowToken();
        mOwnerUid = ownerId;
        mOwnerCanAddInternalSystemWindow = ownerCanAddInternalSystemWindow;
        mWindowId = new WindowId(this);
        mAttrs.copyFrom(a);
        mLastSurfaceInsets.set(mAttrs.surfaceInsets);
        mViewVisibility = viewVisibility;
        mPolicy = mService.mPolicy;
        mContext = mService.mContext;
        DeathRecipient deathRecipient = new DeathRecipient();
        mSeq = seq;
        mEnforceSizeCompat = (mAttrs.privateFlags & PRIVATE_FLAG_COMPATIBLE_WINDOW) != 0;
        mPowerManagerWrapper = powerManagerWrapper;
        if (localLOGV) Slog.v(
            TAG, "Window " + this + " client=" + c.asBinder()
            + " token=" + token + " (" + mAttrs.token + ")" + " params=" + a);
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
            mInputWindowHandle = null;
            mWinAnimator = null;
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

            if (DEBUG_ADD_REMOVE) Slog.v(TAG, "Adding " + this + " to " + parentWindow);
            parentWindow.addChild(this, sWindowSubLayerComparator);

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

        if (mAppToken != null && mAppToken.mShowForAllUsers) {
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
        mInputWindowHandle = new InputWindowHandle(
                mAppToken != null ? mAppToken.mInputApplicationHandle : null, this, c,
                    getDisplayId());
    }

    void attach() {
        if (localLOGV) Slog.v(TAG, "Attaching " + this + " token=" + mToken);
        mSession.windowAddedLocked(mAttrs.packageName);
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
    void onParentSet() {
        super.onParentSet();
        setDrawnStateEvaluated(false /*evaluated*/);
    }

    @Override
    public int getOwningUid() {
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

    @Override
    public boolean canAcquireSleepToken() {
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

    @Override
    public void computeFrameLw(Rect parentFrame, Rect displayFrame, Rect overscanFrame,
            Rect contentFrame, Rect visibleFrame, Rect decorFrame, Rect stableFrame,
            Rect outsetFrame, WmDisplayCutout displayCutout,
            boolean parentFrameWasClippedByDisplayCutout) {
        if (mWillReplaceWindow && (mAnimatingExit || !mReplacingRemoveRequested)) {
            // This window is being replaced and either already got information that it's being
            // removed or we are still waiting for some information. Because of this we don't
            // want to apply any more changes to it, so it remains in this state until new window
            // appears.
            return;
        }
        mHaveFrame = true;
        mParentFrameWasClippedByDisplayCutout = parentFrameWasClippedByDisplayCutout;

        final Task task = getTask();
        final boolean inFullscreenContainer = inFullscreenContainer();
        final boolean windowsAreFloating = task != null && task.isFloating();
        final DisplayContent dc = getDisplayContent();

        // If the task has temp inset bounds set, we have to make sure all its windows uses
        // the temp inset frame. Otherwise different display frames get applied to the main
        // window and the child window, making them misaligned.
        // Otherwise we need to clear the inset frame, to avoid using a stale frame after leaving
        // multi window mode.
        if (task != null && isInMultiWindowMode()) {
            task.getTempInsetBounds(mInsetFrame);
        } else {
            mInsetFrame.setEmpty();
        }

        // Denotes the actual frame used to calculate the insets and to perform the layout. When
        // resizing in docked mode, we'd like to freeze the layout, so we also need to freeze the
        // insets temporarily. By the notion of a task having a different layout frame, we can
        // achieve that while still moving the task around.
        final Rect layoutContainingFrame;
        final Rect layoutDisplayFrame;

        // The offset from the layout containing frame to the actual containing frame.
        final int layoutXDiff;
        final int layoutYDiff;
        if (inFullscreenContainer || layoutInParentFrame()) {
            // We use the parent frame as the containing frame for fullscreen and child windows
            mContainingFrame.set(parentFrame);
            mDisplayFrame.set(displayFrame);
            layoutDisplayFrame = displayFrame;
            layoutContainingFrame = parentFrame;
            layoutXDiff = 0;
            layoutYDiff = 0;
        } else {
            getBounds(mContainingFrame);
            if (mAppToken != null && !mAppToken.mFrozenBounds.isEmpty()) {

                // If the bounds are frozen, we still want to translate the window freely and only
                // freeze the size.
                Rect frozen = mAppToken.mFrozenBounds.peek();
                mContainingFrame.right = mContainingFrame.left + frozen.width();
                mContainingFrame.bottom = mContainingFrame.top + frozen.height();
            }
            final WindowState imeWin = mService.mInputMethodWindow;
            // IME is up and obscuring this window. Adjust the window position so it is visible.
            if (imeWin != null && imeWin.isVisibleNow() && isInputMethodTarget()) {
                if (inFreeformWindowingMode()
                        && mContainingFrame.bottom > contentFrame.bottom) {
                    // In freeform we want to move the top up directly.
                    // TODO: Investigate why this is contentFrame not parentFrame.
                    mContainingFrame.top -= mContainingFrame.bottom - contentFrame.bottom;
                } else if (!inPinnedWindowingMode()
                        && mContainingFrame.bottom > parentFrame.bottom) {
                    // But in docked we want to behave like fullscreen and behave as if the task
                    // were given smaller bounds for the purposes of layout. Skip adjustments for
                    // the pinned stack, they are handled separately in the PinnedStackController.
                    mContainingFrame.bottom = parentFrame.bottom;
                }
            }

            if (windowsAreFloating) {
                // In floating modes (e.g. freeform, pinned) we have only to set the rectangle
                // if it wasn't set already. No need to intersect it with the (visible)
                // "content frame" since it is allowed to be outside the visible desktop.
                if (mContainingFrame.isEmpty()) {
                    mContainingFrame.set(contentFrame);
                }
            }
            mDisplayFrame.set(mContainingFrame);
            layoutXDiff = !mInsetFrame.isEmpty() ? mInsetFrame.left - mContainingFrame.left : 0;
            layoutYDiff = !mInsetFrame.isEmpty() ? mInsetFrame.top - mContainingFrame.top : 0;
            layoutContainingFrame = !mInsetFrame.isEmpty() ? mInsetFrame : mContainingFrame;
            mTmpRect.set(0, 0, dc.getDisplayInfo().logicalWidth, dc.getDisplayInfo().logicalHeight);
            subtractInsets(mDisplayFrame, layoutContainingFrame, displayFrame, mTmpRect);
            if (!layoutInParentFrame()) {
                subtractInsets(mContainingFrame, layoutContainingFrame, parentFrame, mTmpRect);
                subtractInsets(mInsetFrame, layoutContainingFrame, parentFrame, mTmpRect);
            }
            layoutDisplayFrame = displayFrame;
            layoutDisplayFrame.intersect(layoutContainingFrame);
        }

        final int pw = mContainingFrame.width();
        final int ph = mContainingFrame.height();

        if (!mParentFrame.equals(parentFrame)) {
            //Slog.i(TAG_WM, "Window " + this + " content frame from " + mParentFrame
            //        + " to " + parentFrame);
            mParentFrame.set(parentFrame);
            mContentChanged = true;
        }
        if (mRequestedWidth != mLastRequestedWidth || mRequestedHeight != mLastRequestedHeight) {
            mLastRequestedWidth = mRequestedWidth;
            mLastRequestedHeight = mRequestedHeight;
            mContentChanged = true;
        }

        mOverscanFrame.set(overscanFrame);
        mContentFrame.set(contentFrame);
        mVisibleFrame.set(visibleFrame);
        mDecorFrame.set(decorFrame);
        mStableFrame.set(stableFrame);
        final boolean hasOutsets = outsetFrame != null;
        if (hasOutsets) {
            mOutsetFrame.set(outsetFrame);
        }

        final int fw = mFrame.width();
        final int fh = mFrame.height();

        applyGravityAndUpdateFrame(layoutContainingFrame, layoutDisplayFrame);

        // Calculate the outsets before the content frame gets shrinked to the window frame.
        if (hasOutsets) {
            mOutsets.set(Math.max(mContentFrame.left - mOutsetFrame.left, 0),
                    Math.max(mContentFrame.top - mOutsetFrame.top, 0),
                    Math.max(mOutsetFrame.right - mContentFrame.right, 0),
                    Math.max(mOutsetFrame.bottom - mContentFrame.bottom, 0));
        } else {
            mOutsets.set(0, 0, 0, 0);
        }

        // Make sure the content and visible frames are inside of the
        // final window frame.
        if (windowsAreFloating && !mFrame.isEmpty()) {
            // For pinned workspace the frame isn't limited in any particular
            // way since SystemUI controls the bounds. For freeform however
            // we want to keep things inside the content frame.
            final Rect limitFrame = task.inPinnedWindowingMode() ? mFrame : mContentFrame;
            // Keep the frame out of the blocked system area, limit it in size to the content area
            // and make sure that there is always a minimum visible so that the user can drag it
            // into a usable area..
            final int height = Math.min(mFrame.height(), limitFrame.height());
            final int width = Math.min(limitFrame.width(), mFrame.width());
            final DisplayMetrics displayMetrics = getDisplayContent().getDisplayMetrics();
            final int minVisibleHeight = Math.min(height, WindowManagerService.dipToPixel(
                    MINIMUM_VISIBLE_HEIGHT_IN_DP, displayMetrics));
            final int minVisibleWidth = Math.min(width, WindowManagerService.dipToPixel(
                    MINIMUM_VISIBLE_WIDTH_IN_DP, displayMetrics));
            final int top = Math.max(limitFrame.top,
                    Math.min(mFrame.top, limitFrame.bottom - minVisibleHeight));
            final int left = Math.max(limitFrame.left + minVisibleWidth - width,
                    Math.min(mFrame.left, limitFrame.right - minVisibleWidth));
            mFrame.set(left, top, left + width, top + height);
            mContentFrame.set(mFrame);
            mVisibleFrame.set(mContentFrame);
            mStableFrame.set(mContentFrame);
        } else if (mAttrs.type == TYPE_DOCK_DIVIDER) {
            dc.getDockedDividerController().positionDockedStackedDivider(mFrame);
            mContentFrame.set(mFrame);
            if (!mFrame.equals(mLastFrame)) {
                mMovedByResize = true;
            }
        } else {
            mContentFrame.set(Math.max(mContentFrame.left, mFrame.left),
                    Math.max(mContentFrame.top, mFrame.top),
                    Math.min(mContentFrame.right, mFrame.right),
                    Math.min(mContentFrame.bottom, mFrame.bottom));

            mVisibleFrame.set(Math.max(mVisibleFrame.left, mFrame.left),
                    Math.max(mVisibleFrame.top, mFrame.top),
                    Math.min(mVisibleFrame.right, mFrame.right),
                    Math.min(mVisibleFrame.bottom, mFrame.bottom));

            mStableFrame.set(Math.max(mStableFrame.left, mFrame.left),
                    Math.max(mStableFrame.top, mFrame.top),
                    Math.min(mStableFrame.right, mFrame.right),
                    Math.min(mStableFrame.bottom, mFrame.bottom));
        }

        if (inFullscreenContainer && !windowsAreFloating) {
            // Windows that are not fullscreen can be positioned outside of the display frame,
            // but that is not a reason to provide them with overscan insets.
            mOverscanInsets.set(Math.max(mOverscanFrame.left - layoutContainingFrame.left, 0),
                    Math.max(mOverscanFrame.top - layoutContainingFrame.top, 0),
                    Math.max(layoutContainingFrame.right - mOverscanFrame.right, 0),
                    Math.max(layoutContainingFrame.bottom - mOverscanFrame.bottom, 0));
        }

        if (mAttrs.type == TYPE_DOCK_DIVIDER) {
            // For the docked divider, we calculate the stable insets like a full-screen window
            // so it can use it to calculate the snap positions.
            final WmDisplayCutout c = displayCutout.calculateRelativeTo(mDisplayFrame);
            mTmpRect.set(mDisplayFrame);
            mTmpRect.inset(c.getDisplayCutout().getSafeInsets());
            mTmpRect.intersectUnchecked(mStableFrame);

            mStableInsets.set(Math.max(mTmpRect.left - mDisplayFrame.left, 0),
                    Math.max(mTmpRect.top - mDisplayFrame.top, 0),
                    Math.max(mDisplayFrame.right - mTmpRect.right, 0),
                    Math.max(mDisplayFrame.bottom - mTmpRect.bottom, 0));

            // The divider doesn't care about insets in any case, so set it to empty so we don't
            // trigger a relayout when moving it.
            mContentInsets.setEmpty();
            mVisibleInsets.setEmpty();
            displayCutout = WmDisplayCutout.NO_CUTOUT;
        } else {
            getDisplayContent().getBounds(mTmpRect);
            // Override right and/or bottom insets in case if the frame doesn't fit the screen in
            // non-fullscreen mode.
            boolean overrideRightInset = !windowsAreFloating && !inFullscreenContainer
                    && mFrame.right > mTmpRect.right;
            boolean overrideBottomInset = !windowsAreFloating && !inFullscreenContainer
                    && mFrame.bottom > mTmpRect.bottom;
            mContentInsets.set(mContentFrame.left - mFrame.left,
                    mContentFrame.top - mFrame.top,
                    overrideRightInset ? mTmpRect.right - mContentFrame.right
                            : mFrame.right - mContentFrame.right,
                    overrideBottomInset ? mTmpRect.bottom - mContentFrame.bottom
                            : mFrame.bottom - mContentFrame.bottom);

            mVisibleInsets.set(mVisibleFrame.left - mFrame.left,
                    mVisibleFrame.top - mFrame.top,
                    overrideRightInset ? mTmpRect.right - mVisibleFrame.right
                            : mFrame.right - mVisibleFrame.right,
                    overrideBottomInset ? mTmpRect.bottom - mVisibleFrame.bottom
                            : mFrame.bottom - mVisibleFrame.bottom);

            mStableInsets.set(Math.max(mStableFrame.left - mFrame.left, 0),
                    Math.max(mStableFrame.top - mFrame.top, 0),
                    overrideRightInset ? Math.max(mTmpRect.right - mStableFrame.right, 0)
                            : Math.max(mFrame.right - mStableFrame.right, 0),
                    overrideBottomInset ? Math.max(mTmpRect.bottom - mStableFrame.bottom, 0)
                            :  Math.max(mFrame.bottom - mStableFrame.bottom, 0));
        }

        mDisplayCutout = displayCutout.calculateRelativeTo(mFrame);

        // Offset the actual frame by the amount layout frame is off.
        mFrame.offset(-layoutXDiff, -layoutYDiff);
        mCompatFrame.offset(-layoutXDiff, -layoutYDiff);
        mContentFrame.offset(-layoutXDiff, -layoutYDiff);
        mVisibleFrame.offset(-layoutXDiff, -layoutYDiff);
        mStableFrame.offset(-layoutXDiff, -layoutYDiff);

        mCompatFrame.set(mFrame);
        if (mEnforceSizeCompat) {
            // If there is a size compatibility scale being applied to the
            // window, we need to apply this to its insets so that they are
            // reported to the app in its coordinate space.
            mOverscanInsets.scale(mInvGlobalScale);
            mContentInsets.scale(mInvGlobalScale);
            mVisibleInsets.scale(mInvGlobalScale);
            mStableInsets.scale(mInvGlobalScale);
            mOutsets.scale(mInvGlobalScale);

            // Also the scaled frame that we report to the app needs to be
            // adjusted to be in its coordinate space.
            mCompatFrame.scale(mInvGlobalScale);
        }

        if (mIsWallpaper && (fw != mFrame.width() || fh != mFrame.height())) {
            final DisplayContent displayContent = getDisplayContent();
            if (displayContent != null) {
                final DisplayInfo displayInfo = displayContent.getDisplayInfo();
                getDisplayContent().mWallpaperController.updateWallpaperOffset(
                        this, displayInfo.logicalWidth, displayInfo.logicalHeight, false);
            }
        }

        if (DEBUG_LAYOUT || localLOGV) Slog.v(TAG,
                "Resolving (mRequestedWidth="
                + mRequestedWidth + ", mRequestedheight="
                + mRequestedHeight + ") to" + " (pw=" + pw + ", ph=" + ph
                + "): frame=" + mFrame.toShortString()
                + " ci=" + mContentInsets.toShortString()
                + " vi=" + mVisibleInsets.toShortString()
                + " si=" + mStableInsets.toShortString()
                + " of=" + mOutsets.toShortString());
    }

    // TODO: Look into whether this override is still necessary.
    @Override
    public Rect getBounds() {
        if (isInMultiWindowMode()) {
            return getTask().getBounds();
        } else if (mAppToken != null){
            return mAppToken.getBounds();
        } else {
            return super.getBounds();
        }
    }

    @Override
    public Rect getFrameLw() {
        return mFrame;
    }

    @Override
    public Rect getDisplayFrameLw() {
        return mDisplayFrame;
    }

    @Override
    public Rect getOverscanFrameLw() {
        return mOverscanFrame;
    }

    @Override
    public Rect getContentFrameLw() {
        return mContentFrame;
    }

    @Override
    public Rect getVisibleFrameLw() {
        return mVisibleFrame;
    }

    Rect getStableFrameLw() {
        return mStableFrame;
    }

    @Override
    public boolean getGivenInsetsPendingLw() {
        return mGivenInsetsPending;
    }

    @Override
    public Rect getGivenContentInsetsLw() {
        return mGivenContentInsets;
    }

    @Override
    public Rect getGivenVisibleInsetsLw() {
        return mGivenVisibleInsets;
    }

    @Override
    public WindowManager.LayoutParams getAttrs() {
        return mAttrs;
    }

    @Override
    public boolean getNeedsMenuLw(WindowManagerPolicy.WindowState bottom) {
        return getDisplayContent().getNeedsMenu(this, bottom);
    }

    @Override
    public int getSystemUiVisibility() {
        return mSystemUiVisibility;
    }

    @Override
    public int getSurfaceLayer() {
        return mLayer;
    }

    @Override
    public int getBaseType() {
        return getTopParentWindow().mAttrs.type;
    }

    @Override
    public IApplicationToken getAppToken() {
        return mAppToken != null ? mAppToken.appToken : null;
    }

    @Override
    public boolean isVoiceInteraction() {
        return mAppToken != null && mAppToken.mVoiceInteraction;
    }

    boolean setReportResizeHints() {
        mOverscanInsetsChanged |= !mLastOverscanInsets.equals(mOverscanInsets);
        mContentInsetsChanged |= !mLastContentInsets.equals(mContentInsets);
        mVisibleInsetsChanged |= !mLastVisibleInsets.equals(mVisibleInsets);
        mStableInsetsChanged |= !mLastStableInsets.equals(mStableInsets);
        mOutsetsChanged |= !mLastOutsets.equals(mOutsets);
        mFrameSizeChanged |= (mLastFrame.width() != mFrame.width()) ||
                (mLastFrame.height() != mFrame.height());
        mDisplayCutoutChanged |= !mLastDisplayCutout.equals(mDisplayCutout);
        return mOverscanInsetsChanged || mContentInsetsChanged || mVisibleInsetsChanged
                || mOutsetsChanged || mFrameSizeChanged || mDisplayCutoutChanged;
    }

    /**
     * Adds the window to the resizing list if any of the parameters we use to track the window
     * dimensions or insets have changed.
     */
    void updateResizingWindowIfNeeded() {
        final WindowStateAnimator winAnimator = mWinAnimator;
        if (!mHasSurface || getDisplayContent().mLayoutSeq != mLayoutSeq || isGoneForLayoutLw()) {
            return;
        }

        final Task task = getTask();
        // In the case of stack bound animations, the window frames will update (unlike other
        // animations which just modify various transformation properties). We don't want to
        // notify the client of frame changes in this case. Not only is it a lot of churn, but
        // the frame may not correspond to the surface size or the onscreen area at various
        // phases in the animation, and the client will become sad and confused.
        if (task != null && task.mStack.isAnimatingBounds()) {
            return;
        }

        setReportResizeHints();
        boolean configChanged = isConfigChanged();
        if (DEBUG_CONFIGURATION && configChanged) {
            Slog.v(TAG_WM, "Win " + this + " config changed: " + getConfiguration());
        }

        final boolean dragResizingChanged = isDragResizeChanged()
                && !isDragResizingChangeReported();

        if (localLOGV) Slog.v(TAG_WM, "Resizing " + this + ": configChanged=" + configChanged
                + " dragResizingChanged=" + dragResizingChanged + " last=" + mLastFrame
                + " frame=" + mFrame);

        // We update mLastFrame always rather than in the conditional with the last inset
        // variables, because mFrameSizeChanged only tracks the width and height changing.
        mLastFrame.set(mFrame);

        if (mContentInsetsChanged
                || mVisibleInsetsChanged
                || mStableInsetsChanged
                || winAnimator.mSurfaceResized
                || mOutsetsChanged
                || mFrameSizeChanged
                || mDisplayCutoutChanged
                || configChanged
                || dragResizingChanged
                || mReportOrientationChanged) {
            if (DEBUG_RESIZE || DEBUG_ORIENTATION) {
                Slog.v(TAG_WM, "Resize reasons for w=" + this + ": "
                        + " contentInsetsChanged=" + mContentInsetsChanged
                        + " " + mContentInsets.toShortString()
                        + " visibleInsetsChanged=" + mVisibleInsetsChanged
                        + " " + mVisibleInsets.toShortString()
                        + " stableInsetsChanged=" + mStableInsetsChanged
                        + " " + mStableInsets.toShortString()
                        + " outsetsChanged=" + mOutsetsChanged
                        + " " + mOutsets.toShortString()
                        + " surfaceResized=" + winAnimator.mSurfaceResized
                        + " configChanged=" + configChanged
                        + " dragResizingChanged=" + dragResizingChanged
                        + " reportOrientationChanged=" + mReportOrientationChanged
                        + " displayCutoutChanged=" + mDisplayCutoutChanged);
            }

            // If it's a dead window left on screen, and the configuration changed, there is nothing
            // we can do about it. Remove the window now.
            if (mAppToken != null && mAppDied) {
                mAppToken.removeDeadWindows();
                return;
            }

            updateLastInsetValues();
            mService.makeWindowFreezingScreenIfNeededLocked(this);

            // If the orientation is changing, or we're starting or ending a drag resizing action,
            // then we need to hold off on unfreezing the display until this window has been
            // redrawn; to do that, we need to go through the process of getting informed by the
            // application when it has finished drawing.
            if (getOrientationChanging() || dragResizingChanged) {
                if (DEBUG_ANIM || DEBUG_ORIENTATION || DEBUG_RESIZE) {
                    Slog.v(TAG_WM, "Orientation or resize start waiting for draw"
                            + ", mDrawState=DRAW_PENDING in " + this
                            + ", surfaceController " + winAnimator.mSurfaceController);
                }
                winAnimator.mDrawState = DRAW_PENDING;
                if (mAppToken != null) {
                    mAppToken.clearAllDrawn();
                }
            }
            if (!mService.mResizingWindows.contains(this)) {
                if (DEBUG_RESIZE || DEBUG_ORIENTATION) Slog.v(TAG_WM, "Resizing window " + this);
                mService.mResizingWindows.add(this);
            }
        } else if (getOrientationChanging()) {
            if (isDrawnLw()) {
                if (DEBUG_ORIENTATION) Slog.v(TAG_WM, "Orientation not waiting for draw in "
                        + this + ", surfaceController " + winAnimator.mSurfaceController);
                setOrientationChanging(false);
                mLastFreezeDuration = (int)(SystemClock.elapsedRealtime()
                        - mService.mDisplayFreezeTime);
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
        mOrientationChanging = changing;
        mOrientationChangeTimedOut = false;
    }

    void orientationChangeTimedOut() {
        mOrientationChangeTimedOut = true;
    }

    DisplayContent getDisplayContent() {
        return mToken.getDisplayContent();
    }

    @Override
    void onDisplayChanged(DisplayContent dc) {
        super.onDisplayChanged(dc);
        // Window was not laid out for this display yet, so make sure mLayoutSeq does not match.
        if (dc != null) {
            mLayoutSeq = dc.mLayoutSeq - 1;
            mInputWindowHandle.displayId = dc.getDisplayId();
        }
    }

    DisplayInfo getDisplayInfo() {
        final DisplayContent displayContent = getDisplayContent();
        return displayContent != null ? displayContent.getDisplayInfo() : null;
    }

    @Override
    public int getDisplayId() {
        final DisplayContent displayContent = getDisplayContent();
        if (displayContent == null) {
            return Display.INVALID_DISPLAY;
        }
        return displayContent.getDisplayId();
    }

    Task getTask() {
        return mAppToken != null ? mAppToken.getTask() : null;
    }

    TaskStack getStack() {
        Task task = getTask();
        if (task != null) {
            if (task.mStack != null) {
                return task.mStack;
            }
        }
        // Some system windows (e.g. "Power off" dialog) don't have a task, but we would still
        // associate them with some stack to enable dimming.
        final DisplayContent dc = getDisplayContent();
        return mAttrs.type >= FIRST_SYSTEM_WINDOW && dc != null ? dc.getHomeStack() : null;
    }

    /**
     * Retrieves the visible bounds of the window.
     * @param bounds The rect which gets the bounds.
     */
    void getVisibleBounds(Rect bounds) {
        final Task task = getTask();
        boolean intersectWithStackBounds = task != null && task.cropWindowsToStackBounds();
        bounds.setEmpty();
        mTmpRect.setEmpty();
        if (intersectWithStackBounds) {
            final TaskStack stack = task.mStack;
            if (stack != null) {
                stack.getDimBounds(mTmpRect);
            } else {
                intersectWithStackBounds = false;
            }
        }

        bounds.set(mVisibleFrame);
        if (intersectWithStackBounds) {
            bounds.intersect(mTmpRect);
        }

        if (bounds.isEmpty()) {
            bounds.set(mFrame);
            if (intersectWithStackBounds) {
                bounds.intersect(mTmpRect);
            }
            return;
        }
    }

    public long getInputDispatchingTimeoutNanos() {
        return mAppToken != null
                ? mAppToken.mInputDispatchingTimeoutNanos
                : WindowManagerService.DEFAULT_INPUT_DISPATCHING_TIMEOUT_NANOS;
    }

    @Override
    public boolean hasAppShownWindows() {
        return mAppToken != null && (mAppToken.firstWindowDrawn || mAppToken.startingDisplayed);
    }

    boolean isIdentityMatrix(float dsdx, float dtdx, float dsdy, float dtdy) {
        if (dsdx < .99999f || dsdx > 1.00001f) return false;
        if (dtdy < .99999f || dtdy > 1.00001f) return false;
        if (dtdx < -.000001f || dtdx > .000001f) return false;
        if (dsdy < -.000001f || dsdy > .000001f) return false;
        return true;
    }

    void prelayout() {
        if (mEnforceSizeCompat) {
            mGlobalScale = getDisplayContent().mCompatibleScreenScale;
            mInvGlobalScale = 1 / mGlobalScale;
        } else {
            mGlobalScale = mInvGlobalScale = 1;
        }
    }

    @Override
    boolean hasContentToDisplay() {
        if (!mAppFreezing && isDrawnLw() && (mViewVisibility == View.VISIBLE
                || (mWinAnimator.isAnimationSet() && !mService.mAppTransition.isTransitionSet()))) {
            return true;
        }

        return super.hasContentToDisplay();
    }

    @Override
    boolean isVisible() {
        return wouldBeVisibleIfPolicyIgnored() && mPolicyVisibility;
    }

    /**
     * @return True if the window would be visible if we'd ignore policy visibility, false
     *         otherwise.
     */
    boolean wouldBeVisibleIfPolicyIgnored() {
        return mHasSurface && !isParentWindowHidden()
                && !mAnimatingExit && !mDestroying && (!mIsWallpaper || mWallpaperVisible);
    }

    @Override
    public boolean isVisibleLw() {
        return isVisible();
    }

    /**
     * Is this window visible, ignoring its app token? It is not visible if there is no surface,
     * or we are in the process of running an exit animation that will remove the surface.
     */
    // TODO: Can we consolidate this with #isVisible() or have a more appropriate name for this?
    boolean isWinVisibleLw() {
        return (mAppToken == null || !mAppToken.hiddenRequested || mAppToken.isSelfAnimating())
                && isVisible();
    }

    /**
     * The same as isVisible(), but follows the current hidden state of the associated app token,
     * not the pending requested hidden state.
     */
    boolean isVisibleNow() {
        return (!mToken.isHidden() || mAttrs.type == TYPE_APPLICATION_STARTING)
                && isVisible();
    }

    /**
     * Can this window possibly be a drag/drop target?  The test here is
     * a combination of the above "visible now" with the check that the
     * Input Manager uses when discarding windows from input consideration.
     */
    boolean isPotentialDragTarget() {
        return isVisibleNow() && !mRemoved
                && mInputChannel != null && mInputWindowHandle != null;
    }

    /**
     * Same as isVisible(), but we also count it as visible between the
     * call to IWindowSession.add() and the first relayout().
     */
    boolean isVisibleOrAdding() {
        final AppWindowToken atoken = mAppToken;
        return (mHasSurface || (!mRelayoutCalled && mViewVisibility == View.VISIBLE))
                && mPolicyVisibility && !isParentWindowHidden()
                && (atoken == null || !atoken.hiddenRequested)
                && !mAnimatingExit && !mDestroying;
    }

    /**
     * Is this window currently on-screen?  It is on-screen either if it
     * is visible or it is currently running an animation before no longer
     * being visible.
     */
    boolean isOnScreen() {
        if (!mHasSurface || mDestroying || !mPolicyVisibility) {
            return false;
        }
        final AppWindowToken atoken = mAppToken;
        if (atoken != null) {
            return ((!isParentWindowHidden() && !atoken.hiddenRequested)
                    || mWinAnimator.isAnimationSet());
        }
        return !isParentWindowHidden() || mWinAnimator.isAnimationSet();
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
        return mAppToken != null && !mAppDied
                && (!mAppToken.isFreezingScreen() || !mAppFreezing);
    }

    /**
     * Like isOnScreen(), but we don't return true if the window is part
     * of a transition that has not yet been started.
     */
    boolean isReadyForDisplay() {
        if (mToken.waitingToShow && mService.mAppTransition.isTransitionSet()) {
            return false;
        }
        return mHasSurface && mPolicyVisibility && !mDestroying
                && ((!isParentWindowHidden() && mViewVisibility == View.VISIBLE && !mToken.isHidden())
                        || mWinAnimator.isAnimationSet());
    }

    // TODO: Another visibility method that was added late in the release to minimize risk.
    @Override
    public boolean canAffectSystemUiFlags() {
        final boolean translucent = mAttrs.alpha == 0.0f;
        if (translucent) {
            return false;
        }
        if (mAppToken == null) {
            final boolean shown = mWinAnimator.getShown();
            final boolean exiting = mAnimatingExit || mDestroying;
            return shown && !exiting;
        } else {
            final Task task = getTask();
            final boolean canFromTask = task != null && task.canAffectSystemUiFlags();
            return canFromTask && !mAppToken.isHidden();
        }
    }

    /**
     * Like isOnScreen, but returns false if the surface hasn't yet
     * been drawn.
     */
    @Override
    public boolean isDisplayedLw() {
        final AppWindowToken atoken = mAppToken;
        return isDrawnLw() && mPolicyVisibility
                && ((!isParentWindowHidden() && (atoken == null || !atoken.hiddenRequested))
                        || mWinAnimator.isAnimationSet());
    }

    /**
     * Return true if this window or its app token is currently animating.
     */
    @Override
    public boolean isAnimatingLw() {
        return isAnimating();
    }

    @Override
    public boolean isGoneForLayoutLw() {
        final AppWindowToken atoken = mAppToken;
        return mViewVisibility == View.GONE
                || !mRelayoutCalled
                || (atoken == null && mToken.isHidden())
                || (atoken != null && atoken.hiddenRequested)
                || isParentWindowHidden()
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
     * Returns true if the window has a surface that it has drawn a
     * complete UI in to.
     */
    @Override
    public boolean isDrawnLw() {
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
        return ((!mIsWallpaper && mAttrs.format == PixelFormat.OPAQUE)
                || (mIsWallpaper && mWallpaperVisible))
                && isDrawnLw() && !mWinAnimator.isAnimationSet();
    }

    @Override
    void onMovedByResize() {
        if (DEBUG_RESIZE) Slog.d(TAG, "onMovedByResize: Moving " + this);
        mMovedByResize = true;
        super.onMovedByResize();
    }

    boolean onAppVisibilityChanged(boolean visible, boolean runningAppAnimation) {
        boolean changed = false;

        for (int i = mChildren.size() - 1; i >= 0; --i) {
            final WindowState c = mChildren.get(i);
            changed |= c.onAppVisibilityChanged(visible, runningAppAnimation);
        }

        if (mAttrs.type == TYPE_APPLICATION_STARTING) {
            // Starting window that's exiting will be removed when the animation finishes.
            // Mark all relevant flags for that onExitAnimationDone will proceed all the way
            // to actually remove it.
            if (!visible && isVisibleNow() && mAppToken.isSelfAnimating()) {
                mAnimatingExit = true;
                mRemoveOnExit = true;
                mWindowRemovalAllowed = true;
            }
            return changed;
        }

        final boolean isVisibleNow = isVisibleNow();
        if (visible != isVisibleNow) {
            // Run exit animation if:
            // 1. App visibility and WS visibility are different
            // 2. App is not running an animation
            // 3. WS is currently visible
            if (!runningAppAnimation && isVisibleNow) {
                final AccessibilityController accessibilityController =
                        mService.mAccessibilityController;
                final int winTransit = TRANSIT_EXIT;
                mWinAnimator.applyAnimationLocked(winTransit, false /* isEntrance */);
                //TODO (multidisplay): Magnification is supported only for the default
                if (accessibilityController != null && getDisplayId() == DEFAULT_DISPLAY) {
                    accessibilityController.onWindowTransitionLocked(this, winTransit);
                }
            }
            changed = true;
            setDisplayLayoutNeeded();
        }

        return changed;
    }

    boolean onSetAppExiting() {
        final DisplayContent displayContent = getDisplayContent();
        boolean changed = false;

        if (isVisibleNow()) {
            mWinAnimator.applyAnimationLocked(TRANSIT_EXIT, false);
            //TODO (multidisplay): Magnification is supported only for the default
            if (mService.mAccessibilityController != null && isDefaultDisplay()) {
                mService.mAccessibilityController.onWindowTransitionLocked(this, TRANSIT_EXIT);
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
        final ArrayList<WindowState> resizingWindows = mService.mResizingWindows;
        if (mHasSurface && !resizingWindows.contains(this)) {
            if (DEBUG_RESIZE) Slog.d(TAG, "onResize: Resizing " + this);
            resizingWindows.add(this);
        }
        if (isGoneForLayoutLw()) {
            mResizedWhileGone = true;
        }

        super.onResize();
    }

    void onUnfreezeBounds() {
        for (int i = mChildren.size() - 1; i >= 0; --i) {
            final WindowState c = mChildren.get(i);
            c.onUnfreezeBounds();
        }

        if (!mHasSurface) {
            return;
        }

        mLayoutNeeded = true;
        setDisplayLayoutNeeded();
        if (!mService.mResizingWindows.contains(this)) {
            mService.mResizingWindows.add(this);
        }
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
        final int left = mFrame.left;
        final int top = mFrame.top;
        final Task task = getTask();
        final boolean adjustedForMinimizedDockOrIme = task != null
                && (task.mStack.isAdjustedForMinimizedDockedStack()
                || task.mStack.isAdjustedForIme());
        if (mToken.okToAnimate()
                && (mAttrs.privateFlags & PRIVATE_FLAG_NO_MOVE_ANIMATION) == 0
                && !isDragResizing() && !adjustedForMinimizedDockOrIme
                && getWindowConfiguration().hasMovementAnimations()
                && !mWinAnimator.mLastHidden) {
            startMoveAnimation(left, top);
        }

        //TODO (multidisplay): Accessibility supported only for the default display.
        if (mService.mAccessibilityController != null
                && getDisplayContent().getDisplayId() == DEFAULT_DISPLAY) {
            mService.mAccessibilityController.onSomeWindowResizedOrMovedLocked();
        }

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
        return mHasSurface && (mContentChanged || mMovedByResize)
                && !mAnimatingExit
                && (mFrame.top != mLastFrame.top || mFrame.left != mLastFrame.left)
                && (!mIsChildWindow || !getParentWindow().hasMoved());
    }

    boolean isObscuringDisplay() {
        Task task = getTask();
        if (task != null && task.mStack != null && !task.mStack.fillsParent()) {
            return false;
        }
        return isOpaqueDrawn() && fillsDisplay();
    }

    boolean fillsDisplay() {
        final DisplayInfo displayInfo = getDisplayInfo();
        return mFrame.left <= 0 && mFrame.top <= 0
                && mFrame.right >= displayInfo.appWidth && mFrame.bottom >= displayInfo.appHeight;
    }

    /** Returns true if last applied config was not yet requested by client. */
    boolean isConfigChanged() {
        return !getLastReportedConfiguration().equals(getConfiguration());
    }

    void onWindowReplacementTimeout() {
        if (mWillReplaceWindow) {
            // Since the window already timed out, remove it immediately now.
            // Use WindowState#removeImmediately() instead of WindowState#removeIfPossible(), as the latter
            // delays removal on certain conditions, which will leave the stale window in the
            // stack and marked mWillReplaceWindow=false, so the window will never be removed.
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
    void forceWindowsScaleableInTransaction(boolean force) {
        if (mWinAnimator != null && mWinAnimator.hasSurface()) {
            mWinAnimator.mSurfaceController.forceScaleableInTransaction(force);
        }

        super.forceWindowsScaleableInTransaction(force);
    }

    @Override
    void removeImmediately() {
        super.removeImmediately();

        if (mRemoved) {
            // Nothing to do.
            if (DEBUG_ADD_REMOVE) Slog.v(TAG_WM,
                    "WS.removeImmediately: " + this + " Already removed...");
            return;
        }

        mRemoved = true;

        mWillReplaceWindow = false;
        if (mReplacementWindow != null) {
            mReplacementWindow.mSkipEnterAnimationForSeamlessReplacement = false;
        }

        final DisplayContent dc = getDisplayContent();
        if (isInputMethodTarget()) {
            dc.computeImeTarget(true /* updateImeTarget */);
        }

        final int type = mAttrs.type;
        if (WindowManagerService.excludeWindowTypeFromTapOutTask(type)) {
            dc.mTapExcludedWindows.remove(this);
        }
        if (mTapExcludeRegionHolder != null) {
            // If a tap exclude region container was initialized for this window, then it should've
            // also been registered in display.
            dc.mTapExcludeProvidingWindows.remove(this);
        }
        mPolicy.removeWindowLw(this);

        disposeInputChannel();

        mWinAnimator.destroyDeferredSurfaceLocked();
        mWinAnimator.destroySurfaceLocked();
        mSession.windowRemovedLocked();
        try {
            mClient.asBinder().unlinkToDeath(mDeathRecipient, 0);
        } catch (RuntimeException e) {
            // Ignore if it has already been removed (usually because
            // we are doing this as part of processing a death note.)
        }

        mService.postWindowRemoveCleanupLocked(this);
    }

    @Override
    void removeIfPossible() {
        super.removeIfPossible();
        removeIfPossible(false /*keepVisibleDeadWindow*/);
    }

    private void removeIfPossible(boolean keepVisibleDeadWindow) {
        mWindowRemovalAllowed = true;
        if (DEBUG_ADD_REMOVE) Slog.v(TAG,
                "removeIfPossible: " + this + " callers=" + Debug.getCallers(5));

        final boolean startingWindow = mAttrs.type == TYPE_APPLICATION_STARTING;
        if (startingWindow && DEBUG_STARTING_WINDOW) Slog.d(TAG_WM,
                "Starting window removed " + this);

        if (localLOGV || DEBUG_FOCUS || DEBUG_FOCUS_LIGHT && this == mService.mCurrentFocus)
            Slog.v(TAG_WM, "Remove " + this + " client="
                        + Integer.toHexString(System.identityHashCode(mClient.asBinder()))
                        + ", surfaceController=" + mWinAnimator.mSurfaceController + " Callers="
                        + Debug.getCallers(5));

        final long origId = Binder.clearCallingIdentity();

        try {
            disposeInputChannel();

            if (DEBUG_APP_TRANSITIONS) Slog.v(TAG_WM, "Remove " + this
                    + ": mSurfaceController=" + mWinAnimator.mSurfaceController
                    + " mAnimatingExit=" + mAnimatingExit
                    + " mRemoveOnExit=" + mRemoveOnExit
                    + " mHasSurface=" + mHasSurface
                    + " surfaceShowing=" + mWinAnimator.getShown()
                    + " isAnimationSet=" + mWinAnimator.isAnimationSet()
                    + " app-animation="
                    + (mAppToken != null ? mAppToken.isSelfAnimating() : "false")
                    + " mWillReplaceWindow=" + mWillReplaceWindow
                    + " inPendingTransaction="
                    + (mAppToken != null ? mAppToken.inPendingTransaction : false)
                    + " mDisplayFrozen=" + mService.mDisplayFrozen
                    + " callers=" + Debug.getCallers(6));

            // Visibility of the removed window. Will be used later to update orientation later on.
            boolean wasVisible = false;

            final int displayId = getDisplayId();

            // First, see if we need to run an animation. If we do, we have to hold off on removing the
            // window until the animation is done. If the display is frozen, just remove immediately,
            // since the animation wouldn't be seen.
            if (mHasSurface && mToken.okToAnimate()) {
                if (mWillReplaceWindow) {
                    // This window is going to be replaced. We need to keep it around until the new one
                    // gets added, then we will get rid of this one.
                    if (DEBUG_ADD_REMOVE) Slog.v(TAG_WM,
                            "Preserving " + this + " until the new one is " + "added");
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
                    if (DEBUG_ADD_REMOVE) Slog.v(TAG_WM,
                            "Not removing " + this + " because app died while it's visible");

                    mAppDied = true;
                    setDisplayLayoutNeeded();
                    mService.mWindowPlacerLocked.performSurfacePlacement();

                    // Set up a replacement input channel since the app is now dead.
                    // We need to catch tapping on the dead window to restart the app.
                    openInputChannel(null);
                    mService.mInputMonitor.updateInputWindowsLw(true /*force*/);
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
                        mService.requestTraversal();
                    }
                    //TODO (multidisplay): Magnification is supported only for the default display.
                    if (mService.mAccessibilityController != null && displayId == DEFAULT_DISPLAY) {
                        mService.mAccessibilityController.onWindowTransitionLocked(this, transit);
                    }
                }
                final boolean isAnimating = mWinAnimator.isAnimationSet()
                        && (mAppToken == null || !mAppToken.isWaitingForTransitionStart());
                final boolean lastWindowIsStartingWindow = startingWindow && mAppToken != null
                        && mAppToken.isLastWindow(this);
                // We delay the removal of a window if it has a showing surface that can be used to run
                // exit animation and it is marked as exiting.
                // Also, If isn't the an animating starting window that is the last window in the app.
                // We allow the removal of the non-animating starting window now as there is no
                // additional window or animation that will trigger its removal.
                if (mWinAnimator.getShown() && mAnimatingExit
                        && (!lastWindowIsStartingWindow || isAnimating)) {
                    // The exit animation is running or should run... wait for it!
                    if (DEBUG_ADD_REMOVE) Slog.v(TAG_WM,
                            "Not removing " + this + " due to exit animation ");
                    setupWindowForRemoveOnExit();
                    if (mAppToken != null) {
                        mAppToken.updateReportedVisibilityLocked();
                    }
                    return;
                }
            }

            removeImmediately();
            // Removing a visible window will effect the computed orientation
            // So just update orientation if needed.
            if (wasVisible && mService.updateOrientationFromAppTokensLocked(displayId)) {
                mService.mH.obtainMessage(SEND_NEW_CONFIGURATION, displayId).sendToTarget();
            }
            mService.updateFocusedWindowLocked(UPDATE_FOCUS_NORMAL, true /*updateInputWindows*/);
        } finally {
            Binder.restoreCallingIdentity(origId);
        }
    }

    private void setupWindowForRemoveOnExit() {
        mRemoveOnExit = true;
        setDisplayLayoutNeeded();
        // Request a focus update as this window's input channel is already gone. Otherwise
        // we could have no focused window in input manager.
        final boolean focusChanged = mService.updateFocusedWindowLocked(
                UPDATE_FOCUS_WILL_PLACE_SURFACES, false /*updateInputWindows*/);
        mService.mWindowPlacerLocked.performSurfacePlacement();
        if (focusChanged) {
            mService.mInputMonitor.updateInputWindowsLw(false /*force*/);
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

        final boolean windowsAreFocusable = mAppToken == null || mAppToken.windowsAreFocusable();
        if (!windowsAreFocusable) {
            // This window can't be an IME target if the app's windows should not be focusable.
            return false;
        }

        final int fl = mAttrs.flags & (FLAG_NOT_FOCUSABLE | FLAG_ALT_FOCUSABLE_IM);
        final int type = mAttrs.type;

        // Can only be an IME target if both FLAG_NOT_FOCUSABLE and FLAG_ALT_FOCUSABLE_IM are set or
        // both are cleared...and not a starting window.
        if (fl != 0 && fl != (FLAG_NOT_FOCUSABLE | FLAG_ALT_FOCUSABLE_IM)
                && type != TYPE_APPLICATION_STARTING) {
            return false;
        }

        if (DEBUG_INPUT_METHOD) {
            Slog.i(TAG_WM, "isVisibleOrAdding " + this + ": " + isVisibleOrAdding());
            if (!isVisibleOrAdding()) {
                Slog.i(TAG_WM, "  mSurfaceController=" + mWinAnimator.mSurfaceController
                        + " relayoutCalled=" + mRelayoutCalled
                        + " viewVis=" + mViewVisibility
                        + " policyVis=" + mPolicyVisibility
                        + " policyVisAfterAnim=" + mPolicyVisibilityAfterAnim
                        + " parentHidden=" + isParentWindowHidden()
                        + " exiting=" + mAnimatingExit + " destroying=" + mDestroying);
                if (mAppToken != null) {
                    Slog.i(TAG_WM, "  mAppToken.hiddenRequested=" + mAppToken.hiddenRequested);
                }
            }
        }
        return isVisibleOrAdding();
    }

    private final class DeadWindowEventReceiver extends InputEventReceiver {
        DeadWindowEventReceiver(InputChannel inputChannel) {
            super(inputChannel, mService.mH.getLooper());
        }
        @Override
        public void onInputEvent(InputEvent event) {
            finishInputEvent(event, true);
        }
    }
    /**
     *  Dummy event receiver for windows that died visible.
     */
    private DeadWindowEventReceiver mDeadWindowEventReceiver;

    void openInputChannel(InputChannel outInputChannel) {
        if (mInputChannel != null) {
            throw new IllegalStateException("Window already has an input channel.");
        }
        String name = getName();
        InputChannel[] inputChannels = InputChannel.openInputChannelPair(name);
        mInputChannel = inputChannels[0];
        mClientChannel = inputChannels[1];
        mInputWindowHandle.inputChannel = inputChannels[0];
        if (outInputChannel != null) {
            mClientChannel.transferTo(outInputChannel);
            mClientChannel.dispose();
            mClientChannel = null;
        } else {
            // If the window died visible, we setup a dummy input channel, so that taps
            // can still detected by input monitor channel, and we can relaunch the app.
            // Create dummy event receiver that simply reports all events as handled.
            mDeadWindowEventReceiver = new DeadWindowEventReceiver(mClientChannel);
        }
        mService.mInputManager.registerInputChannel(mInputChannel, mInputWindowHandle);
    }

    void disposeInputChannel() {
        if (mDeadWindowEventReceiver != null) {
            mDeadWindowEventReceiver.dispose();
            mDeadWindowEventReceiver = null;
        }

        // unregister server channel first otherwise it complains about broken channel
        if (mInputChannel != null) {
            mService.mInputManager.unregisterInputChannel(mInputChannel);
            mInputChannel.dispose();
            mInputChannel = null;
        }
        if (mClientChannel != null) {
            mClientChannel.dispose();
            mClientChannel = null;
        }
        mInputWindowHandle.inputChannel = null;
    }

    /** Returns true if the replacement window was removed. */
    boolean removeReplacedWindowIfNeeded(WindowState replacement) {
        if (mWillReplaceWindow && mReplacementWindow == replacement && replacement.hasDrawnLw()) {
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
        if (DEBUG_ADD_REMOVE) Slog.d(TAG, "Removing replaced window: " + this);
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

    void applyAdjustForImeIfNeeded() {
        final Task task = getTask();
        if (task != null && task.mStack != null && task.mStack.isAdjustedForIme()) {
            task.mStack.applyAdjustForImeIfNeeded(task);
        }
    }

    @Override
    void switchUser() {
        super.switchUser();
        if (isHiddenFromUserLocked()) {
            if (DEBUG_VISIBILITY) Slog.w(TAG_WM, "user changing, hiding " + this
                    + ", attrs=" + mAttrs.type + ", belonging to " + mOwnerUid);
            hideLw(false);
        }
    }

    int getTouchableRegion(Region region, int flags) {
        final boolean modal = (flags & (FLAG_NOT_TOUCH_MODAL | FLAG_NOT_FOCUSABLE)) == 0;
        if (modal && mAppToken != null) {
            // Limit the outer touch to the activity stack region.
            flags |= FLAG_NOT_TOUCH_MODAL;
            // If this is a modal window we need to dismiss it if it's not full screen and the
            // touch happens outside of the frame that displays the content. This means we
            // need to intercept touches outside of that window. The dim layer user
            // associated with the window (task or stack) will give us the good bounds, as
            // they would be used to display the dim layer.
            final Task task = getTask();
            if (task != null) {
                task.getDimBounds(mTmpRect);
            } else {
                getStack().getDimBounds(mTmpRect);
            }
            if (inFreeformWindowingMode()) {
                // For freeform windows we the touch region to include the whole surface for the
                // shadows.
                final DisplayMetrics displayMetrics = getDisplayContent().getDisplayMetrics();
                final int delta = WindowManagerService.dipToPixel(
                        RESIZE_HANDLE_WIDTH_IN_DP, displayMetrics);
                mTmpRect.inset(-delta, -delta);
            }
            region.set(mTmpRect);
            cropRegionToStackBoundsIfNeeded(region);
        } else {
            // Not modal or full screen modal
            getTouchableRegion(region);
        }
        return flags;
    }

    void checkPolicyVisibilityChange() {
        if (mPolicyVisibility != mPolicyVisibilityAfterAnim) {
            if (DEBUG_VISIBILITY) {
                Slog.v(TAG, "Policy visibility changing after anim in " +
                        mWinAnimator + ": " + mPolicyVisibilityAfterAnim);
            }
            mPolicyVisibility = mPolicyVisibilityAfterAnim;
            if (!mPolicyVisibility) {
                mWinAnimator.hide("checkPolicyVisibilityChange");
                if (mService.mCurrentFocus == this) {
                    if (DEBUG_FOCUS_LIGHT) Slog.i(TAG,
                            "setAnimationLocked: setting mFocusMayChange true");
                    mService.mFocusMayChange = true;
                    setDisplayLayoutNeeded();
                }
                // Window is no longer visible -- make sure if we were waiting
                // for it to be displayed before enabling the display, that
                // we allow the display to be enabled now.
                mService.enableScreenIfNeededLocked();
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
        boolean hasTurnScreenOnFlag = (mAttrs.flags & FLAG_TURN_SCREEN_ON) != 0;
        boolean allowTheaterMode =
                mService.mAllowTheaterModeWakeFromLayout || Settings.Global.getInt(
                        mService.mContext.getContentResolver(), Settings.Global.THEATER_MODE_ON, 0)
                        == 0;
        boolean canTurnScreenOn = mAppToken == null || mAppToken.canTurnScreenOn();

        // The screen will turn on if the following conditions are met
        // 1. The window has the flag FLAG_TURN_SCREEN_ON
        // 2. The WMS allows theater mode.
        // 3. No AWT or the AWT allows the screen to be turned on. This should only be true once
        // per resume to prevent the screen getting getting turned on for each relayout. Set
        // canTurnScreenOn will be set to false so the window doesn't turn the screen on again
        // during this resume.
        // 4. When the screen is not interactive. This is because when the screen is already
        // interactive, the value may persist until the next animation, which could potentially
        // be occurring while turning off the screen. This would lead to the screen incorrectly
        // turning back on.
        if (hasTurnScreenOnFlag) {
            if (allowTheaterMode && canTurnScreenOn && !mPowerManagerWrapper.isInteractive()) {
                if (DEBUG_VISIBILITY || DEBUG_POWER) {
                    Slog.v(TAG, "Relayout window turning screen on: " + this);
                }
                mPowerManagerWrapper.wakeUp(SystemClock.uptimeMillis(),
                        "android.server.wm:TURN_ON");
            }

            if (mAppToken != null) {
                mAppToken.setCanTurnScreenOn(false);
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

        if (isDrawnLw() && mToken.okToAnimate()) {
            mWinAnimator.applyEnterAnimationLocked();
        }
    }

    void getMergedConfiguration(MergedConfiguration outConfiguration) {
        final Configuration globalConfig = mService.mRoot.getConfiguration();
        final Configuration overrideConfig = getMergedOverrideConfiguration();
        outConfiguration.setConfiguration(globalConfig, overrideConfig);
    }

    void setLastReportedMergedConfiguration(MergedConfiguration config) {
        mLastReportedConfiguration.setTo(config);
    }

    void getLastReportedMergedConfiguration(MergedConfiguration config) {
        config.setTo(mLastReportedConfiguration);
    }

    private Configuration getLastReportedConfiguration() {
        return mLastReportedConfiguration.getMergedConfiguration();
    }

    void adjustStartingWindowFlags() {
        if (mAttrs.type == TYPE_BASE_APPLICATION && mAppToken != null
                && mAppToken.startingWindow != null) {
            // Special handling of starting window over the base
            // window of the app: propagate lock screen flags to it,
            // to provide the correct semantics while starting.
            final int mask = FLAG_SHOW_WHEN_LOCKED | FLAG_DISMISS_KEYGUARD
                    | FLAG_ALLOW_LOCK_WHILE_SCREEN_ON;
            WindowManager.LayoutParams sa = mAppToken.startingWindow.mAttrs;
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
                synchronized(mService.mWindowMap) {
                    final WindowState win = mService.windowForClientLocked(mSession, mClient, false);
                    Slog.i(TAG, "WIN DEATH: " + win);
                    if (win != null) {
                        final DisplayContent dc = getDisplayContent();
                        if (win.mAppToken != null && win.mAppToken.findMainWindow() == win) {
                            mService.mTaskSnapshotController.onAppDied(win.mAppToken);
                        }
                        win.removeIfPossible(shouldKeepVisibleDeadAppWindow());
                        if (win.mAttrs.type == TYPE_DOCK_DIVIDER) {
                            // The owner of the docked divider died :( We reset the docked stack,
                            // just in case they have the divider at an unstable position. Better
                            // also reset drag resizing state, because the owner can't do it
                            // anymore.
                            final TaskStack stack =
                                    dc.getSplitScreenPrimaryStackIgnoringVisibility();
                            if (stack != null) {
                                stack.resetDockedStackToMiddle();
                            }
                            resetSplitScreenResizing = true;
                        }
                    } else if (mHasSurface) {
                        Slog.e(TAG, "!!! LEAK !!! Window removed but surface still valid.");
                        WindowState.this.removeIfPossible();
                    }
                }
                if (resetSplitScreenResizing) {
                    try {
                        // Note: this calls into ActivityManager, so we must *not* hold the window
                        // manager lock while calling this.
                        mService.mActivityManager.setSplitScreenResizing(false);
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
        if (!isWinVisibleLw() || mAppToken == null || mAppToken.isClientHidden()) {
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

    /** @return true if this window desires key events. */
    boolean canReceiveKeys() {
        return isVisibleOrAdding()
                && (mViewVisibility == View.VISIBLE) && !mRemoveOnExit
                && ((mAttrs.flags & WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE) == 0)
                && (mAppToken == null || mAppToken.windowsAreFocusable())
                && !canReceiveTouchInput();
    }

    /** @return true if this window desires touch events. */
    boolean canReceiveTouchInput() {
        return mAppToken != null && mAppToken.getTask() != null
                && mAppToken.getTask().mStack.shouldIgnoreInput();
    }

    @Override
    public boolean hasDrawnLw() {
        return mWinAnimator.mDrawState == WindowStateAnimator.HAS_DRAWN;
    }

    @Override
    public boolean showLw(boolean doAnimation) {
        return showLw(doAnimation, true);
    }

    boolean showLw(boolean doAnimation, boolean requestAnim) {
        if (isHiddenFromUserLocked()) {
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
        if (mPolicyVisibility && mPolicyVisibilityAfterAnim) {
            // Already showing.
            return false;
        }
        if (DEBUG_VISIBILITY) Slog.v(TAG, "Policy visibility true: " + this);
        if (doAnimation) {
            if (DEBUG_VISIBILITY) Slog.v(TAG, "doAnimation: mPolicyVisibility="
                    + mPolicyVisibility + " isAnimationSet=" + mWinAnimator.isAnimationSet());
            if (!mToken.okToAnimate()) {
                doAnimation = false;
            } else if (mPolicyVisibility && !mWinAnimator.isAnimationSet()) {
                // Check for the case where we are currently visible and
                // not animating; we do not want to do animation at such a
                // point to become visible when we already are.
                doAnimation = false;
            }
        }
        mPolicyVisibility = true;
        mPolicyVisibilityAfterAnim = true;
        if (doAnimation) {
            mWinAnimator.applyAnimationLocked(TRANSIT_ENTER, true);
        }
        if (requestAnim) {
            mService.scheduleAnimationLocked();
        }
        if ((mAttrs.flags & FLAG_NOT_FOCUSABLE) == 0) {
            mService.updateFocusedWindowLocked(UPDATE_FOCUS_NORMAL, false /* updateImWindows */);
        }
        return true;
    }

    @Override
    public boolean hideLw(boolean doAnimation) {
        return hideLw(doAnimation, true);
    }

    boolean hideLw(boolean doAnimation, boolean requestAnim) {
        if (doAnimation) {
            if (!mToken.okToAnimate()) {
                doAnimation = false;
            }
        }
        boolean current = doAnimation ? mPolicyVisibilityAfterAnim : mPolicyVisibility;
        if (!current) {
            // Already hiding.
            return false;
        }
        if (doAnimation) {
            mWinAnimator.applyAnimationLocked(TRANSIT_EXIT, false);
            if (!mWinAnimator.isAnimationSet()) {
                doAnimation = false;
            }
        }
        mPolicyVisibilityAfterAnim = false;
        if (!doAnimation) {
            if (DEBUG_VISIBILITY) Slog.v(TAG, "Policy visibility false: " + this);
            mPolicyVisibility = false;
            // Window is no longer visible -- make sure if we were waiting
            // for it to be displayed before enabling the display, that
            // we allow the display to be enabled now.
            mService.enableScreenIfNeededLocked();
            if (mService.mCurrentFocus == this) {
                if (DEBUG_FOCUS_LIGHT) Slog.i(TAG,
                        "WindowState.hideLw: setting mFocusMayChange true");
                mService.mFocusMayChange = true;
            }
        }
        if (requestAnim) {
            mService.scheduleAnimationLocked();
        }
        if (mService.mCurrentFocus == this) {
            mService.updateFocusedWindowLocked(UPDATE_FOCUS_NORMAL, false /* updateImWindows */);
        }
        return true;
    }

    void setForceHideNonSystemOverlayWindowIfNeeded(boolean forceHide) {
        if (mOwnerCanAddInternalSystemWindow
                || (!isSystemAlertWindowType(mAttrs.type) && mAttrs.type != TYPE_TOAST)) {
            return;
        }
        if (mForceHideNonSystemOverlayWindow == forceHide) {
            return;
        }
        mForceHideNonSystemOverlayWindow = forceHide;
        if (forceHide) {
            hideLw(true /* doAnimation */, true /* requestAnim */);
        } else {
            showLw(true /* doAnimation */, true /* requestAnim */);
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
            hideLw(true, true);
        } else {
            showLw(true, true);
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
                showLw(true, true);
            } else {
                hideLw(true, true);
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
        final int mode = mService.mAppOps.startOpNoThrow(mAppOp,
                getOwningUid(), getOwningPackage(), true);
        if (mode != MODE_ALLOWED && mode != MODE_DEFAULT) {
            setAppOpVisibilityLw(false);
        }
    }

    void resetAppOpsState() {
        if (mAppOp != OP_NONE && mAppOpVisibility) {
            mService.mAppOps.finishOp(mAppOp, getOwningUid(), getOwningPackage());
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
            int mode = mService.mAppOps.checkOpNoThrow(mAppOp, uid, packageName);
            if (mode != MODE_ALLOWED && mode != MODE_DEFAULT) {
                mService.mAppOps.finishOp(mAppOp, uid, packageName);
                setAppOpVisibilityLw(false);
            }
        } else {
            final int mode = mService.mAppOps.startOpNoThrow(mAppOp, uid, packageName, true);
            if (mode == MODE_ALLOWED || mode == MODE_DEFAULT) {
                setAppOpVisibilityLw(true);
            }
        }
    }

    public void hidePermanentlyLw() {
        if (!mPermanentlyHidden) {
            mPermanentlyHidden = true;
            hideLw(true, true);
        }
    }

    public void pokeDrawLockLw(long timeout) {
        if (isVisibleOrAdding()) {
            if (mDrawLock == null) {
                // We want the tag name to be somewhat stable so that it is easier to correlate
                // in wake lock statistics.  So in particular, we don't want to include the
                // window's hash code as in toString().
                final CharSequence tag = getWindowTag();
                mDrawLock = mService.mPowerManager.newWakeLock(DRAW_WAKE_LOCK, "Window:" + tag);
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

    @Override
    public boolean isAlive() {
        return mClient.asBinder().isBinderAlive();
    }

    boolean isClosing() {
        return mAnimatingExit || (mService.mClosingApps.contains(mAppToken));
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

        final boolean clientHidden = mAppToken.isClientHidden();
        if (mAttrs.type == TYPE_APPLICATION_STARTING && clientHidden) {
            // Don't hide the starting window.
            return;
        }

        if (clientHidden) {
            // Once we are notifying the client that it's visibility has changed, we need to prevent
            // it from destroying child surfaces until the animation has finished. We do this by
            // detaching any surface control the client added from the client.
            for (int i = mChildren.size() - 1; i >= 0; --i) {
                final WindowState c = mChildren.get(i);
                c.mWinAnimator.detachChildren();
            }

            mWinAnimator.detachChildren();
        }

        try {
            if (DEBUG_VISIBILITY) Slog.v(TAG,
                    "Setting visibility of " + this + ": " + (!clientHidden));
            mClient.dispatchAppVisibility(!clientHidden);
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
                && mService.mWindowsFreezingScreen != WINDOWS_FREEZING_SCREENS_TIMEOUT) {
            if (DEBUG_ORIENTATION) Slog.v(TAG_WM, "set mOrientationChanging of " + this);
            setOrientationChanging(true);
            mService.mRoot.mOrientationChangeComplete = false;
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

        if (appStopped || mWindowRemovalAllowed) {
            mWinAnimator.destroyPreservedSurfaceLocked();
        }

        if (mDestroying) {
            if (DEBUG_ADD_REMOVE) Slog.e(TAG_WM, "win=" + this
                    + " destroySurfaces: appStopped=" + appStopped
                    + " win.mWindowRemovalAllowed=" + mWindowRemovalAllowed
                    + " win.mRemoveOnExit=" + mRemoveOnExit);
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
        }

        return destroyedSomething;
    }

    // Destroy or save the application surface without checking
    // various indicators of whether the client has released the surface.
    // This is in general unsafe, and most callers should use {@link #destroySurface}
    void destroySurfaceUnchecked() {
        mWinAnimator.destroySurfaceLocked();

        // Clear animating flags now, since the surface is now gone. (Note this is true even
        // if the surface is saved, to outside world the surface is still NO_SURFACE.)
        mAnimatingExit = false;
    }

    @Override
    public boolean isDefaultDisplay() {
        final DisplayContent displayContent = getDisplayContent();
        if (displayContent == null) {
            // Only a window that was on a non-default display can be detached from it.
            return false;
        }
        return displayContent.isDefaultDisplay;
    }

    void setShowToOwnerOnlyLocked(boolean showToOwnerOnly) {
        mShowToOwnerOnly = showToOwnerOnly;
    }

    private boolean isHiddenFromUserLocked() {
        // Child windows are evaluated based on their parent window.
        final WindowState win = getTopParentWindow();
        if (win.mAttrs.type < FIRST_SYSTEM_WINDOW
                && win.mAppToken != null && win.mAppToken.mShowForAllUsers) {

            // All window frames that are fullscreen extend above status bar, but some don't extend
            // below navigation bar. Thus, check for display frame for top/left and stable frame for
            // bottom right.
            if (win.mFrame.left <= win.mDisplayFrame.left
                    && win.mFrame.top <= win.mDisplayFrame.top
                    && win.mFrame.right >= win.mStableFrame.right
                    && win.mFrame.bottom >= win.mStableFrame.bottom) {
                // Is a fullscreen window, like the clock alarm. Show to everyone.
                return false;
            }
        }

        return win.mShowToOwnerOnly
                && !mService.isCurrentProfileLocked(UserHandle.getUserId(win.mOwnerUid));
    }

    private static void applyInsets(Region outRegion, Rect frame, Rect inset) {
        outRegion.set(
                frame.left + inset.left, frame.top + inset.top,
                frame.right - inset.right, frame.bottom - inset.bottom);
    }

    void getTouchableRegion(Region outRegion) {
        final Rect frame = mFrame;
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
                outRegion.translate(frame.left, frame.top);
                break;
            }
        }
        cropRegionToStackBoundsIfNeeded(outRegion);
    }

    private void cropRegionToStackBoundsIfNeeded(Region region) {
        final Task task = getTask();
        if (task == null || !task.cropWindowsToStackBounds()) {
            return;
        }

        final TaskStack stack = task.mStack;
        if (stack == null) {
            return;
        }

        stack.getDimBounds(mTmpRect);
        region.op(mTmpRect, Region.Op.INTERSECT);
    }

    /**
     * Report a focus change.  Must be called with no locks held, and consistently
     * from the same serialized thread (such as dispatched from a handler).
     */
    void reportFocusChangedSerialized(boolean focused, boolean inTouchMode) {
        try {
            mClient.windowFocusChanged(focused, inTouchMode);
        } catch (RemoteException e) {
        }
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
        if (mAppToken != null && mAppToken.mFrozenMergedConfig.size() > 0) {
            return mAppToken.mFrozenMergedConfig.peek();
        }

        return super.getConfiguration();
    }

    void reportResized() {
        Trace.traceBegin(TRACE_TAG_WINDOW_MANAGER, "wm.reportResized_" + getWindowTag());
        try {
            if (DEBUG_RESIZE || DEBUG_ORIENTATION) Slog.v(TAG, "Reporting new frame to " + this
                    + ": " + mCompatFrame);
            final MergedConfiguration mergedConfiguration =
                    new MergedConfiguration(mService.mRoot.getConfiguration(),
                    getMergedOverrideConfiguration());

            setLastReportedMergedConfiguration(mergedConfiguration);

            if (DEBUG_ORIENTATION && mWinAnimator.mDrawState == DRAW_PENDING)
                Slog.i(TAG, "Resizing " + this + " WITH DRAW PENDING");

            final Rect frame = mFrame;
            final Rect overscanInsets = mLastOverscanInsets;
            final Rect contentInsets = mLastContentInsets;
            final Rect visibleInsets = mLastVisibleInsets;
            final Rect stableInsets = mLastStableInsets;
            final Rect outsets = mLastOutsets;
            final boolean reportDraw = mWinAnimator.mDrawState == DRAW_PENDING;
            final boolean reportOrientation = mReportOrientationChanged;
            final int displayId = getDisplayId();
            final DisplayCutout displayCutout = mDisplayCutout.getDisplayCutout();
            if (mAttrs.type != WindowManager.LayoutParams.TYPE_APPLICATION_STARTING
                    && mClient instanceof IWindow.Stub) {
                // To prevent deadlock simulate one-way call if win.mClient is a local object.
                mService.mH.post(new Runnable() {
                    @Override
                    public void run() {
                        try {
                            dispatchResized(frame, overscanInsets, contentInsets, visibleInsets,
                                    stableInsets, outsets, reportDraw, mergedConfiguration,
                                    reportOrientation, displayId, displayCutout);
                        } catch (RemoteException e) {
                            // Not a remote call, RemoteException won't be raised.
                        }
                    }
                });
            } else {
                dispatchResized(frame, overscanInsets, contentInsets, visibleInsets, stableInsets,
                        outsets, reportDraw, mergedConfiguration, reportOrientation, displayId,
                        displayCutout);
            }

            //TODO (multidisplay): Accessibility supported only for the default display.
            if (mService.mAccessibilityController != null && getDisplayId() == DEFAULT_DISPLAY) {
                mService.mAccessibilityController.onSomeWindowResizedOrMovedLocked();
            }

            mOverscanInsetsChanged = false;
            mContentInsetsChanged = false;
            mVisibleInsetsChanged = false;
            mStableInsetsChanged = false;
            mOutsetsChanged = false;
            mFrameSizeChanged = false;
            mDisplayCutoutChanged = false;
            mWinAnimator.mSurfaceResized = false;
            mReportOrientationChanged = false;
        } catch (RemoteException e) {
            setOrientationChanging(false);
            mLastFreezeDuration = (int)(SystemClock.elapsedRealtime()
                    - mService.mDisplayFreezeTime);
            // We are assuming the hosting process is dead or in a zombie state.
            Slog.w(TAG, "Failed to report 'resized' to the client of " + this
                    + ", removing this window.");
            mService.mPendingRemove.add(this);
            mService.mWindowPlacerLocked.requestTraversal();
        }
        Trace.traceEnd(TRACE_TAG_WINDOW_MANAGER);
    }

    Rect getBackdropFrame(Rect frame) {
        // When the task is docked, we send fullscreen sized backDropFrame as soon as resizing
        // start even if we haven't received the relayout window, so that the client requests
        // the relayout sooner. When dragging stops, backDropFrame needs to stay fullscreen
        // until the window to small size, otherwise the multithread renderer will shift last
        // one or more frame to wrong offset. So here we send fullscreen backdrop if either
        // isDragResizing() or isDragResizeChanged() is true.
        boolean resizing = isDragResizing() || isDragResizeChanged();
        if (getWindowConfiguration().useWindowFrameForBackdrop() || !resizing) {
            return frame;
        }
        final DisplayInfo displayInfo = getDisplayInfo();
        mTmpRect.set(0, 0, displayInfo.logicalWidth, displayInfo.logicalHeight);
        return mTmpRect;
    }

    private int getStackId() {
        final TaskStack stack = getStack();
        if (stack == null) {
            return INVALID_STACK_ID;
        }
        return stack.mStackId;
    }

    private void dispatchResized(Rect frame, Rect overscanInsets, Rect contentInsets,
            Rect visibleInsets, Rect stableInsets, Rect outsets, boolean reportDraw,
            MergedConfiguration mergedConfiguration, boolean reportOrientation, int displayId,
            DisplayCutout displayCutout)
            throws RemoteException {
        final boolean forceRelayout = isDragResizeChanged() || reportOrientation;

        mClient.resized(frame, overscanInsets, contentInsets, visibleInsets, stableInsets, outsets,
                reportDraw, mergedConfiguration, getBackdropFrame(frame), forceRelayout,
                mPolicy.isNavBarForcedShownLw(this), displayId,
                new DisplayCutout.ParcelableWrapper(displayCutout));
        mDragResizingChangeReported = true;
    }

    public void registerFocusObserver(IWindowFocusObserver observer) {
        synchronized(mService.mWindowMap) {
            if (mFocusCallbacks == null) {
                mFocusCallbacks = new RemoteCallbackList<IWindowFocusObserver>();
            }
            mFocusCallbacks.register(observer);
        }
    }

    public void unregisterFocusObserver(IWindowFocusObserver observer) {
        synchronized(mService.mWindowMap) {
            if (mFocusCallbacks != null) {
                mFocusCallbacks.unregister(observer);
            }
        }
    }

    public boolean isFocused() {
        synchronized(mService.mWindowMap) {
            return mService.mCurrentFocus == this;
        }
    }

    @Override
    public boolean isInMultiWindowMode() {
        final Task task = getTask();
        return task != null && !task.isFullscreen();
    }

    /** Is this window in a container that takes up the entire screen space? */
    private boolean inFullscreenContainer() {
        return mAppToken == null || (mAppToken.matchParentBounds() && !isInMultiWindowMode());
    }

    /** @return true when the window is in fullscreen task, but has non-fullscreen bounds set. */
    boolean isLetterboxedAppWindow() {
        return !isInMultiWindowMode() && mAppToken != null && !mAppToken.matchParentBounds()
                || isLetterboxedForDisplayCutoutLw();
    }

    @Override
    public boolean isLetterboxedForDisplayCutoutLw() {
        if (mAppToken == null) {
            // Only windows with an AppWindowToken are letterboxed.
            return false;
        }
        if (!mParentFrameWasClippedByDisplayCutout) {
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
        mTmpRect.set(mAppToken.getBounds());
        mTmpRect.intersectUnchecked(mFrame);
        return mAppToken.getBounds().equals(mTmpRect);
    }

    @Override
    public boolean isLetterboxedOverlappingWith(Rect rect) {
        return mAppToken != null && mAppToken.isLetterboxOverlappingWith(rect);
    }

    boolean isDragResizeChanged() {
        return mDragResizing != computeDragResizing();
    }

    @Override
    void setWaitingForDrawnIfResizingChanged() {
        if (isDragResizeChanged()) {
            mService.mWaitingForDrawn.add(this);
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
        if (!inSplitScreenWindowingMode() && !inFreeformWindowingMode()) {
            return false;
        }
        if (mAttrs.width != MATCH_PARENT || mAttrs.height != MATCH_PARENT) {
            // Floating windows never enter drag resize mode.
            return false;
        }
        if (task.isDragResizing()) {
            return true;
        }

        // If the bounds are currently frozen, it means that the layout size that the app sees
        // and the bounds we clip this window to might be different. In order to avoid holes, we
        // simulate that we are still resizing so the app fills the hole with the resizing
        // background.
        return (getDisplayContent().mDividerControllerLocked.isResizing()
                        || mAppToken != null && !mAppToken.mFrozenBounds.isEmpty()) &&
                !task.inFreeformWindowingMode() && !isGoneForLayoutLw();

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
    public void writeToProto(ProtoOutputStream proto, long fieldId, boolean trim) {
        final long token = proto.start(fieldId);
        super.writeToProto(proto, WINDOW_CONTAINER, trim);
        writeIdentifierToProto(proto, IDENTIFIER);
        proto.write(DISPLAY_ID, getDisplayId());
        proto.write(STACK_ID, getStackId());
        mAttrs.writeToProto(proto, ATTRIBUTES);
        mGivenContentInsets.writeToProto(proto, GIVEN_CONTENT_INSETS);
        mFrame.writeToProto(proto, FRAME);
        mContainingFrame.writeToProto(proto, CONTAINING_FRAME);
        mParentFrame.writeToProto(proto, PARENT_FRAME);
        mContentFrame.writeToProto(proto, CONTENT_FRAME);
        mContentInsets.writeToProto(proto, CONTENT_INSETS);
        mAttrs.surfaceInsets.writeToProto(proto, SURFACE_INSETS);
        mSurfacePosition.writeToProto(proto, SURFACE_POSITION);
        mWinAnimator.writeToProto(proto, ANIMATOR);
        proto.write(ANIMATING_EXIT, mAnimatingExit);
        for (int i = 0; i < mChildren.size(); i++) {
            mChildren.get(i).writeToProto(proto, CHILD_WINDOWS, trim);
        }
        proto.write(REQUESTED_WIDTH, mRequestedWidth);
        proto.write(REQUESTED_HEIGHT, mRequestedHeight);
        proto.write(VIEW_VISIBILITY, mViewVisibility);
        proto.write(SYSTEM_UI_VISIBILITY, mSystemUiVisibility);
        proto.write(HAS_SURFACE, mHasSurface);
        proto.write(IS_READY_FOR_DISPLAY, isReadyForDisplay());
        mDisplayFrame.writeToProto(proto, DISPLAY_FRAME);
        mOverscanFrame.writeToProto(proto, OVERSCAN_FRAME);
        mVisibleFrame.writeToProto(proto, VISIBLE_FRAME);
        mDecorFrame.writeToProto(proto, DECOR_FRAME);
        mOutsetFrame.writeToProto(proto, OUTSET_FRAME);
        mOverscanInsets.writeToProto(proto, OVERSCAN_INSETS);
        mVisibleInsets.writeToProto(proto, VISIBLE_INSETS);
        mStableInsets.writeToProto(proto, STABLE_INSETS);
        mOutsets.writeToProto(proto, OUTSETS);
        mDisplayCutout.getDisplayCutout().writeToProto(proto, CUTOUT);
        proto.write(REMOVE_ON_EXIT, mRemoveOnExit);
        proto.write(DESTROYING, mDestroying);
        proto.write(REMOVED, mRemoved);
        proto.write(IS_ON_SCREEN, isOnScreen());
        proto.write(IS_VISIBLE, isVisible());
        proto.end(token);
    }

    @Override
    public void writeIdentifierToProto(ProtoOutputStream proto, long fieldId) {
        final long token = proto.start(fieldId);
        proto.write(HASH_CODE, System.identityHashCode(this));
        proto.write(USER_ID, UserHandle.getUserId(mOwnerUid));
        final CharSequence title = getWindowTag();
        if (title != null) {
            proto.write(TITLE, title.toString());
        }
        proto.end(token);
    }

    @Override
    void dump(PrintWriter pw, String prefix, boolean dumpAll) {
        final TaskStack stack = getStack();
        pw.print(prefix); pw.print("mDisplayId="); pw.print(getDisplayId());
                if (stack != null) {
                    pw.print(" stackId="); pw.print(stack.mStackId);
                }
                pw.print(" mSession="); pw.print(mSession);
                pw.print(" mClient="); pw.println(mClient.asBinder());
        pw.print(prefix); pw.print("mOwnerUid="); pw.print(mOwnerUid);
                pw.print(" mShowToOwnerOnly="); pw.print(mShowToOwnerOnly);
                pw.print(" package="); pw.print(mAttrs.packageName);
                pw.print(" appop="); pw.println(AppOpsManager.opToName(mAppOp));
        pw.print(prefix); pw.print("mAttrs="); pw.println(mAttrs.toString(prefix));
        pw.print(prefix); pw.print("Requested w="); pw.print(mRequestedWidth);
                pw.print(" h="); pw.print(mRequestedHeight);
                pw.print(" mLayoutSeq="); pw.println(mLayoutSeq);
        if (mRequestedWidth != mLastRequestedWidth || mRequestedHeight != mLastRequestedHeight) {
            pw.print(prefix); pw.print("LastRequested w="); pw.print(mLastRequestedWidth);
                    pw.print(" h="); pw.println(mLastRequestedHeight);
        }
        if (mIsChildWindow || mLayoutAttached) {
            pw.print(prefix); pw.print("mParentWindow="); pw.print(getParentWindow());
                    pw.print(" mLayoutAttached="); pw.println(mLayoutAttached);
        }
        if (mIsImWindow || mIsWallpaper || mIsFloatingLayer) {
            pw.print(prefix); pw.print("mIsImWindow="); pw.print(mIsImWindow);
                    pw.print(" mIsWallpaper="); pw.print(mIsWallpaper);
                    pw.print(" mIsFloatingLayer="); pw.print(mIsFloatingLayer);
                    pw.print(" mWallpaperVisible="); pw.println(mWallpaperVisible);
        }
        if (dumpAll) {
            pw.print(prefix); pw.print("mBaseLayer="); pw.print(mBaseLayer);
                    pw.print(" mSubLayer="); pw.print(mSubLayer);
                    pw.print(" mAnimLayer="); pw.print(mLayer); pw.print("+");
                    pw.print("="); pw.print(mWinAnimator.mAnimLayer);
                    pw.print(" mLastLayer="); pw.println(mWinAnimator.mLastLayer);
        }
        if (dumpAll) {
            pw.print(prefix); pw.print("mToken="); pw.println(mToken);
            if (mAppToken != null) {
                pw.print(prefix); pw.print("mAppToken="); pw.println(mAppToken);
                pw.print(prefix); pw.print(" isAnimatingWithSavedSurface()=");
                pw.print(" mAppDied=");pw.print(mAppDied);
                pw.print(prefix); pw.print("drawnStateEvaluated=");
                        pw.print(getDrawnStateEvaluated());
                pw.print(prefix); pw.print("mightAffectAllDrawn=");
                        pw.println(mightAffectAllDrawn());
            }
            pw.print(prefix); pw.print("mViewVisibility=0x");
            pw.print(Integer.toHexString(mViewVisibility));
            pw.print(" mHaveFrame="); pw.print(mHaveFrame);
            pw.print(" mObscured="); pw.println(mObscured);
            pw.print(prefix); pw.print("mSeq="); pw.print(mSeq);
            pw.print(" mSystemUiVisibility=0x");
            pw.println(Integer.toHexString(mSystemUiVisibility));
        }
        if (!mPolicyVisibility || !mPolicyVisibilityAfterAnim || !mAppOpVisibility
                || isParentWindowHidden()|| mPermanentlyHidden || mForceHideNonSystemOverlayWindow
                || mHiddenWhileSuspended) {
            pw.print(prefix); pw.print("mPolicyVisibility=");
                    pw.print(mPolicyVisibility);
                    pw.print(" mPolicyVisibilityAfterAnim=");
                    pw.print(mPolicyVisibilityAfterAnim);
                    pw.print(" mAppOpVisibility=");
                    pw.print(mAppOpVisibility);
                    pw.print(" parentHidden="); pw.print(isParentWindowHidden());
                    pw.print(" mPermanentlyHidden="); pw.print(mPermanentlyHidden);
                    pw.print(" mHiddenWhileSuspended="); pw.print(mHiddenWhileSuspended);
                    pw.print(" mForceHideNonSystemOverlayWindow="); pw.println(
                    mForceHideNonSystemOverlayWindow);
        }
        if (!mRelayoutCalled || mLayoutNeeded) {
            pw.print(prefix); pw.print("mRelayoutCalled="); pw.print(mRelayoutCalled);
                    pw.print(" mLayoutNeeded="); pw.println(mLayoutNeeded);
        }
        if (dumpAll) {
            pw.print(prefix); pw.print("mGivenContentInsets=");
                    mGivenContentInsets.printShortString(pw);
                    pw.print(" mGivenVisibleInsets=");
                    mGivenVisibleInsets.printShortString(pw);
                    pw.println();
            if (mTouchableInsets != 0 || mGivenInsetsPending) {
                pw.print(prefix); pw.print("mTouchableInsets="); pw.print(mTouchableInsets);
                        pw.print(" mGivenInsetsPending="); pw.println(mGivenInsetsPending);
                Region region = new Region();
                getTouchableRegion(region);
                pw.print(prefix); pw.print("touchable region="); pw.println(region);
            }
            pw.print(prefix); pw.print("mFullConfiguration="); pw.println(getConfiguration());
            pw.print(prefix); pw.print("mLastReportedConfiguration=");
                    pw.println(getLastReportedConfiguration());
        }
        pw.print(prefix); pw.print("mHasSurface="); pw.print(mHasSurface);
                pw.print(" isReadyForDisplay()="); pw.print(isReadyForDisplay());
                pw.print(" mWindowRemovalAllowed="); pw.println(mWindowRemovalAllowed);
        if (dumpAll) {
            pw.print(prefix); pw.print("mFrame="); mFrame.printShortString(pw);
                    pw.print(" last="); mLastFrame.printShortString(pw);
                    pw.println();
        }
        if (mEnforceSizeCompat) {
            pw.print(prefix); pw.print("mCompatFrame="); mCompatFrame.printShortString(pw);
                    pw.println();
        }
        if (dumpAll) {
            pw.print(prefix); pw.print("Frames: containing=");
                    mContainingFrame.printShortString(pw);
                    pw.print(" parent="); mParentFrame.printShortString(pw);
                    pw.println();
            pw.print(prefix); pw.print("    display="); mDisplayFrame.printShortString(pw);
                    pw.print(" overscan="); mOverscanFrame.printShortString(pw);
                    pw.println();
            pw.print(prefix); pw.print("    content="); mContentFrame.printShortString(pw);
                    pw.print(" visible="); mVisibleFrame.printShortString(pw);
                    pw.println();
            pw.print(prefix); pw.print("    decor="); mDecorFrame.printShortString(pw);
                    pw.println();
            pw.print(prefix); pw.print("    outset="); mOutsetFrame.printShortString(pw);
                    pw.println();
            pw.print(prefix); pw.print("Cur insets: overscan=");
                    mOverscanInsets.printShortString(pw);
                    pw.print(" content="); mContentInsets.printShortString(pw);
                    pw.print(" visible="); mVisibleInsets.printShortString(pw);
                    pw.print(" stable="); mStableInsets.printShortString(pw);
                    pw.print(" surface="); mAttrs.surfaceInsets.printShortString(pw);
                    pw.print(" outsets="); mOutsets.printShortString(pw);
            pw.print(" cutout=" + mDisplayCutout.getDisplayCutout());
                    pw.println();
            pw.print(prefix); pw.print("Lst insets: overscan=");
                    mLastOverscanInsets.printShortString(pw);
                    pw.print(" content="); mLastContentInsets.printShortString(pw);
                    pw.print(" visible="); mLastVisibleInsets.printShortString(pw);
                    pw.print(" stable="); mLastStableInsets.printShortString(pw);
                    pw.print(" physical="); mLastOutsets.printShortString(pw);
                    pw.print(" outset="); mLastOutsets.printShortString(pw);
                    pw.print(" cutout=" + mLastDisplayCutout);
                    pw.println();
        }
        super.dump(pw, prefix, dumpAll);
        pw.print(prefix); pw.print(mWinAnimator); pw.println(":");
        mWinAnimator.dump(pw, prefix + "  ", dumpAll);
        if (mAnimatingExit || mRemoveOnExit || mDestroying || mRemoved) {
            pw.print(prefix); pw.print("mAnimatingExit="); pw.print(mAnimatingExit);
                    pw.print(" mRemoveOnExit="); pw.print(mRemoveOnExit);
                    pw.print(" mDestroying="); pw.print(mDestroying);
                    pw.print(" mRemoved="); pw.println(mRemoved);
        }
        if (getOrientationChanging() || mAppFreezing || mReportOrientationChanged) {
            pw.print(prefix); pw.print("mOrientationChanging=");
                    pw.print(mOrientationChanging);
                    pw.print(" configOrientationChanging=");
                    pw.print(getLastReportedConfiguration().orientation
                            != getConfiguration().orientation);
                    pw.print(" mAppFreezing="); pw.print(mAppFreezing);
                    pw.print(" mReportOrientationChanged="); pw.println(mReportOrientationChanged);
        }
        if (mLastFreezeDuration != 0) {
            pw.print(prefix); pw.print("mLastFreezeDuration=");
                    TimeUtils.formatDuration(mLastFreezeDuration, pw); pw.println();
        }
        if (mHScale != 1 || mVScale != 1) {
            pw.print(prefix); pw.print("mHScale="); pw.print(mHScale);
                    pw.print(" mVScale="); pw.println(mVScale);
        }
        if (mWallpaperX != -1 || mWallpaperY != -1) {
            pw.print(prefix); pw.print("mWallpaperX="); pw.print(mWallpaperX);
                    pw.print(" mWallpaperY="); pw.println(mWallpaperY);
        }
        if (mWallpaperXStep != -1 || mWallpaperYStep != -1) {
            pw.print(prefix); pw.print("mWallpaperXStep="); pw.print(mWallpaperXStep);
                    pw.print(" mWallpaperYStep="); pw.println(mWallpaperYStep);
        }
        if (mWallpaperDisplayOffsetX != Integer.MIN_VALUE
                || mWallpaperDisplayOffsetY != Integer.MIN_VALUE) {
            pw.print(prefix); pw.print("mWallpaperDisplayOffsetX=");
                    pw.print(mWallpaperDisplayOffsetX);
                    pw.print(" mWallpaperDisplayOffsetY=");
                    pw.println(mWallpaperDisplayOffsetY);
        }
        if (mDrawLock != null) {
            pw.print(prefix); pw.println("mDrawLock=" + mDrawLock);
        }
        if (isDragResizing()) {
            pw.print(prefix); pw.println("isDragResizing=" + isDragResizing());
        }
        if (computeDragResizing()) {
            pw.print(prefix); pw.println("computeDragResizing=" + computeDragResizing());
        }
        pw.print(prefix); pw.println("isOnScreen=" + isOnScreen());
        pw.print(prefix); pw.println("isVisible=" + isVisible());
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
                    + " u" + UserHandle.getUserId(mOwnerUid)
                    + " " + mLastTitle + (mAnimatingExit ? " EXITING}" : "}");
        }
        return mStringNameCache;
    }

    void transformClipRectFromScreenToSurfaceSpace(Rect clipRect) {
         if (mHScale >= 0) {
            clipRect.left = (int) (clipRect.left / mHScale);
            clipRect.right = (int) Math.ceil(clipRect.right / mHScale);
        }
        if (mVScale >= 0) {
            clipRect.top = (int) (clipRect.top / mVScale);
            clipRect.bottom = (int) Math.ceil(clipRect.bottom / mVScale);
        }
    }

    void applyGravityAndUpdateFrame(Rect containingFrame, Rect displayFrame) {
        final int pw = containingFrame.width();
        final int ph = containingFrame.height();
        final Task task = getTask();
        final boolean inNonFullscreenContainer = !inFullscreenContainer();
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

        if ((mAttrs.flags & FLAG_SCALED) != 0) {
            if (mAttrs.width < 0) {
                w = pw;
            } else if (mEnforceSizeCompat) {
                w = (int)(mAttrs.width * mGlobalScale + .5f);
            } else {
                w = mAttrs.width;
            }
            if (mAttrs.height < 0) {
                h = ph;
            } else if (mEnforceSizeCompat) {
                h = (int)(mAttrs.height * mGlobalScale + .5f);
            } else {
                h = mAttrs.height;
            }
        } else {
            if (mAttrs.width == MATCH_PARENT) {
                w = pw;
            } else if (mEnforceSizeCompat) {
                w = (int)(mRequestedWidth * mGlobalScale + .5f);
            } else {
                w = mRequestedWidth;
            }
            if (mAttrs.height == MATCH_PARENT) {
                h = ph;
            } else if (mEnforceSizeCompat) {
                h = (int)(mRequestedHeight * mGlobalScale + .5f);
            } else {
                h = mRequestedHeight;
            }
        }

        if (mEnforceSizeCompat) {
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
                (int) (y + mAttrs.verticalMargin * ph), mFrame);

        // Now make sure the window fits in the overall display frame.
        if (fitToDisplay) {
            Gravity.applyDisplay(mAttrs.gravity, displayFrame, mFrame);
        }

        // We need to make sure we update the CompatFrame as it is used for
        // cropping decisions, etc, on systems where we lack a decor layer.
        mCompatFrame.set(mFrame);
        if (mEnforceSizeCompat) {
            // See comparable block in computeFrameLw.
            mCompatFrame.scale(mInvGlobalScale);
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
        return (mAttrs.privateFlags & PRIVATE_FLAG_HIDE_NON_SYSTEM_OVERLAY_WINDOWS) != 0
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
            mService.mWindowPlacerLocked.requestTraversal();
        }

        for (int i = mChildren.size() - 1; i >= 0; i--) {
            final WindowState c = mChildren.get(i);
            c.requestUpdateWallpaperIfNeeded();
        }
    }

    float translateToWindowX(float x) {
        float winX = x - mFrame.left;
        if (mEnforceSizeCompat) {
            winX *= mGlobalScale;
        }
        return winX;
    }

    float translateToWindowY(float y) {
        float winY = y - mFrame.top;
        if (mEnforceSizeCompat) {
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

    @Override
    public int getRotationAnimationHint() {
        if (mAppToken != null) {
            return mAppToken.mRotationAnimationHint;
        } else {
            return -1;
        }
    }

    @Override
    public boolean isInputMethodWindow() {
        return mIsImWindow;
    }

    // This must be called while inside a transaction.
    boolean performShowLocked() {
        if (isHiddenFromUserLocked()) {
            if (DEBUG_VISIBILITY) Slog.w(TAG, "hiding " + this + ", belonging to " + mOwnerUid);
            hideLw(false);
            return false;
        }

        logPerformShow("performShow on ");

        final int drawState = mWinAnimator.mDrawState;
        if ((drawState == HAS_DRAWN || drawState == READY_TO_SHOW)
                && mAttrs.type != TYPE_APPLICATION_STARTING && mAppToken != null) {
            mAppToken.onFirstWindowDrawn(this, mWinAnimator);
        }

        if (mWinAnimator.mDrawState != READY_TO_SHOW || !isReadyForDisplay()) {
            return false;
        }

        logPerformShow("Showing ");

        mService.enableScreenIfNeededLocked();
        mWinAnimator.applyEnterAnimationLocked();

        // Force the show in the next prepareSurfaceLocked() call.
        mWinAnimator.mLastAlpha = -1;
        if (DEBUG_ANIM) Slog.v(TAG,
                "performShowLocked: mDrawState=HAS_DRAWN in " + this);
        mWinAnimator.mDrawState = HAS_DRAWN;
        mService.scheduleAnimationLocked();

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

        if (mAttrs.type == TYPE_INPUT_METHOD) {
            getDisplayContent().mDividerControllerLocked.resetImeHideRequested();
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
                    + " during animation: policyVis=" + mPolicyVisibility
                    + " parentHidden=" + isParentWindowHidden()
                    + " tok.hiddenRequested="
                    + (mAppToken != null && mAppToken.hiddenRequested)
                    + " tok.hidden=" + (mAppToken != null && mAppToken.isHidden())
                    + " animationSet=" + mWinAnimator.isAnimationSet()
                    + " tok animating="
                    + (mAppToken != null && mAppToken.isSelfAnimating())
                    + " Callers=" + Debug.getCallers(4));
        }
    }

    WindowInfo getWindowInfo() {
        WindowInfo windowInfo = WindowInfo.obtain();
        windowInfo.type = mAttrs.type;
        windowInfo.layer = mLayer;
        windowInfo.token = mClient.asBinder();
        if (mAppToken != null) {
            windowInfo.activityToken = mAppToken.appToken.asBinder();
        }
        windowInfo.title = mAttrs.accessibilityTitle;
        // Panel windows have no public way to set the a11y title directly. Use the
        // regular title as a fallback.
        if (TextUtils.isEmpty(windowInfo.title)
                && (mAttrs.type >= WindowManager.LayoutParams.FIRST_SUB_WINDOW)
                && (mAttrs.type <= WindowManager.LayoutParams.LAST_SUB_WINDOW)) {
            windowInfo.title = mAttrs.getTitle();
        }
        windowInfo.accessibilityIdOfAnchor = mAttrs.accessibilityIdOfAnchor;
        windowInfo.focused = isFocused();
        Task task = getTask();
        windowInfo.inPictureInPicture = (task != null) && task.inPinnedWindowingMode();

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

    int getHighestAnimLayer() {
        int highest = mWinAnimator.mAnimLayer;
        for (int i = mChildren.size() - 1; i >= 0; i--) {
            final WindowState c = mChildren.get(i);
            final int childLayer = c.getHighestAnimLayer();
            if (childLayer > highest) {
                highest = childLayer;
            }
        }
        return highest;
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

    private boolean applyInOrderWithImeWindows(ToBooleanFunction<WindowState> callback,
            boolean traverseTopToBottom) {
        if (traverseTopToBottom) {
            if (isInputMethodTarget()) {
                // This window is the current IME target, so we need to process the IME windows
                // directly above it.
                if (getDisplayContent().forAllImeWindows(callback, traverseTopToBottom)) {
                    return true;
                }
            }
            if (callback.apply(this)) {
                return true;
            }
        } else {
            if (callback.apply(this)) {
                return true;
            }
            if (isInputMethodTarget()) {
                // This window is the current IME target, so we need to process the IME windows
                // directly above it.
                if (getDisplayContent().forAllImeWindows(callback, traverseTopToBottom)) {
                    return true;
                }
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
                + " selfAnimating=" + isSelfAnimating());

        if (!mChildren.isEmpty()) {
            // Copying to a different list as multiple children can be removed.
            final ArrayList<WindowState> childWindows = new ArrayList<>(mChildren);
            for (int i = childWindows.size() - 1; i >= 0; i--) {
                childWindows.get(i).onExitAnimationDone();
            }
        }

        if (mWinAnimator.mEnteringAnimation) {
            mWinAnimator.mEnteringAnimation = false;
            mService.requestTraversal();
            // System windows don't have an activity and an app token as a result, but need a way
            // to be informed about their entrance animation end.
            if (mAppToken == null) {
                try {
                    mClient.dispatchWindowShown();
                } catch (RemoteException e) {
                }
            }
        }

        if (isSelfAnimating()) {
            return;
        }

        //TODO (multidisplay): Accessibility is supported only for the default display.
        if (mService.mAccessibilityController != null && getDisplayId() == DEFAULT_DISPLAY) {
            mService.mAccessibilityController.onSomeWindowResizedOrMovedLocked();
        }

        if (!isSelfOrAncestorWindowAnimatingExit()) {
            return;
        }

        if (localLOGV || DEBUG_ADD_REMOVE) Slog.v(TAG,
                "Exit animation finished in " + this + ": remove=" + mRemoveOnExit);

        mDestroying = true;

        final boolean hasSurface = mWinAnimator.hasSurface();

        // Use pendingTransaction here so hide is done the same transaction as the other
        // animations when exiting
        mWinAnimator.hide(getPendingTransaction(), "onExitAnimationDone");

        // If we have an app token, we ask it to destroy the surface for us, so that it can take
        // care to ensure the activity has actually stopped and the surface is not still in use.
        // Otherwise we add the service to mDestroySurface and allow it to be processed in our next
        // transaction.
        if (mAppToken != null) {
            mAppToken.destroySurfaces();
        } else {
            if (hasSurface) {
                mService.mDestroySurface.add(this);
            }
            if (mRemoveOnExit) {
                mService.mPendingRemove.add(this);
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
                mService.mDestroySurface.remove(this);
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

    void hideWallpaperWindow(boolean wasDeferred, String reason) {
        for (int j = mChildren.size() - 1; j >= 0; --j) {
            final WindowState c = mChildren.get(j);
            c.hideWallpaperWindow(wasDeferred, reason);
        }
        if (!mWinAnimator.mLastHidden || wasDeferred) {
            mWinAnimator.hide(reason);
            dispatchWallpaperVisibility(false);
            final DisplayContent displayContent = getDisplayContent();
            if (displayContent != null) {
                displayContent.pendingLayoutChanges |= FINISH_LAYOUT_REDO_WALLPAPER;
            }
        }
    }

    /**
     * Check wallpaper window for visibility change and notify window if so.
     * @param visible Current visibility.
     */
    void dispatchWallpaperVisibility(final boolean visible) {
        final boolean hideAllowed =
                getDisplayContent().mWallpaperController.mDeferredHideWallpaper == null;

        // Only send notification if the visibility actually changed and we are not trying to hide
        // the wallpaper when we are deferring hiding of the wallpaper.
        if (mWallpaperVisible != visible && (hideAllowed || visible)) {
            mWallpaperVisible = visible;
            try {
                if (DEBUG_VISIBILITY || DEBUG_WALLPAPER_LIGHT) Slog.v(TAG,
                        "Updating vis of wallpaper " + this
                                + ": " + visible + " from:\n" + Debug.getCallers(4, "  "));
                mClient.dispatchAppVisibility(visible);
            } catch (RemoteException e) {
            }
        }
    }

    boolean hasVisibleNotDrawnWallpaper() {
        if (mWallpaperVisible && !isDrawnLw()) {
            return true;
        }
        for (int j = mChildren.size() - 1; j >= 0; --j) {
            final WindowState c = mChildren.get(j);
            if (c.hasVisibleNotDrawnWallpaper()) {
                return true;
            }
        }
        return false;
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
            Slog.v(TAG, "Win " + this + ": isDrawn=" + isDrawnLw()
                    + ", isAnimationSet=" + mWinAnimator.isAnimationSet());
            if (!isDrawnLw()) {
                Slog.v(TAG, "Not displayed: s=" + mWinAnimator.mSurfaceController
                        + " pv=" + mPolicyVisibility
                        + " mDrawState=" + mWinAnimator.mDrawState
                        + " ph=" + isParentWindowHidden()
                        + " th=" + (mAppToken != null ? mAppToken.hiddenRequested : false)
                        + " a=" + mWinAnimator.isAnimationSet());
            }
        }

        results.numInteresting++;
        if (isDrawnLw()) {
            results.numDrawn++;
            if (!mWinAnimator.isAnimationSet()) {
                results.numVisible++;
            }
            results.nowGone = false;
        } else if (mWinAnimator.isAnimationSet()) {
            results.nowGone = false;
        }
    }

    /**
     * Calculate the window crop according to system decor policy. In general this is
     * the system decor rect (see #calculateSystemDecorRect), but we also have some
     * special cases. This rectangle is in screen space.
     */
    void calculatePolicyCrop(Rect policyCrop) {
        final DisplayContent displayContent = getDisplayContent();
        final DisplayInfo displayInfo = displayContent.getDisplayInfo();

        if (!isDefaultDisplay()) {
            // On a different display there is no system decor. Crop the window
            // by the screen boundaries.
            // TODO(multi-display)
            policyCrop.set(0, 0, mCompatFrame.width(), mCompatFrame.height());
            policyCrop.intersect(-mCompatFrame.left, -mCompatFrame.top,
                    displayInfo.logicalWidth - mCompatFrame.left,
                    displayInfo.logicalHeight - mCompatFrame.top);
        } else if (mDecorFrame.isEmpty()) {
            // Windows without policy decor aren't cropped.
            policyCrop.set(0, 0, mCompatFrame.width(), mCompatFrame.height());
        } else {
            // Crop to the system decor specified by policy.
            calculateSystemDecorRect(policyCrop);
        }
    }

    /**
     * The system decor rect is the region of the window which is not covered
     * by system decorations.
     */
    private void calculateSystemDecorRect(Rect systemDecorRect) {
        final Rect decorRect = mDecorFrame;
        final int width = mFrame.width();
        final int height = mFrame.height();

        final int left = mFrame.left;
        final int top = mFrame.top;

        // Initialize the decor rect to the entire frame.
        if (isDockedResizing()) {
            // If we are resizing with the divider, the task bounds might be smaller than the
            // stack bounds. The system decor is used to clip to the task bounds, which we don't
            // want in this case in order to avoid holes.
            //
            // We take care to not shrink the width, for surfaces which are larger than
            // the display region. Of course this area will not eventually be visible
            // but if we truncate the width now, we will calculate incorrectly
            // when adjusting to the stack bounds.
            final DisplayInfo displayInfo = getDisplayContent().getDisplayInfo();
            systemDecorRect.set(0, 0,
                    Math.max(width, displayInfo.logicalWidth),
                    Math.max(height, displayInfo.logicalHeight));
        } else {
            systemDecorRect.set(0, 0, width, height);
        }

        // If a freeform window is animating from a position where it would be cutoff, it would be
        // cutoff during the animation. We don't want that, so for the duration of the animation
        // we ignore the decor cropping and depend on layering to position windows correctly.

        // We also ignore cropping when the window is currently being drag resized in split screen
        // to prevent issues with the crop for screenshot.
        final boolean cropToDecor =
                !(inFreeformWindowingMode() && isAnimatingLw()) && !isDockedResizing();
        if (cropToDecor) {
            // Intersect with the decor rect, offsetted by window position.
            systemDecorRect.intersect(decorRect.left - left, decorRect.top - top,
                    decorRect.right - left, decorRect.bottom - top);
        }

        // If size compatibility is being applied to the window, the
        // surface is scaled relative to the screen.  Also apply this
        // scaling to the crop rect.  We aren't using the standard rect
        // scale function because we want to round things to make the crop
        // always round to a larger rect to ensure we don't crop too
        // much and hide part of the window that should be seen.
        if (mEnforceSizeCompat && mInvGlobalScale != 1.0f) {
            final float scale = mInvGlobalScale;
            systemDecorRect.left = (int) (systemDecorRect.left * scale - 0.5f);
            systemDecorRect.top = (int) (systemDecorRect.top * scale - 0.5f);
            systemDecorRect.right = (int) ((systemDecorRect.right + 1) * scale - 0.5f);
            systemDecorRect.bottom = (int) ((systemDecorRect.bottom + 1) * scale - 0.5f);
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

    int relayoutVisibleWindow(int result, int attrChanges, int oldVisibility) {
        final boolean wasVisible = isVisibleLw();

        result |= (!wasVisible || !isDrawnLw()) ? RELAYOUT_RES_FIRST_TIME : 0;

        if (mWinAnimator.mChildrenDetached) {
            // If there are detached children hanging around we need to force
            // the client receiving a new Surface.
            mWinAnimator.preserveSurfaceLocked();
            result |= RELAYOUT_RES_SURFACE_CHANGED
                    | RELAYOUT_RES_FIRST_TIME;
        }

        if (mAnimatingExit) {
            Slog.d(TAG, "relayoutVisibleWindow: " + this + " mAnimatingExit=true, mRemoveOnExit="
                    + mRemoveOnExit + ", mDestroying=" + mDestroying);

            mWinAnimator.cancelExitAnimationForNextAnimationLocked();
            mAnimatingExit = false;
        }
        if (mDestroying) {
            mDestroying = false;
            mService.mDestroySurface.remove(this);
        }
        if (oldVisibility == View.GONE) {
            mWinAnimator.mEnterAnimationPending = true;
        }

        mLastVisibleLayoutRotation = getDisplayContent().getRotation();

        mWinAnimator.mEnteringAnimation = true;

        prepareWindowToDisplayDuringRelayout(wasVisible);

        if ((attrChanges & FORMAT_CHANGED) != 0) {
            // If the format can't be changed in place, preserve the old surface until the app draws
            // on the new one. This prevents blinking when we change elevation of freeform and
            // pinned windows.
            if (!mWinAnimator.tryChangeFormatInPlaceLocked()) {
                mWinAnimator.preserveSurfaceLocked();
                result |= RELAYOUT_RES_SURFACE_CHANGED
                        | RELAYOUT_RES_FIRST_TIME;
            }
        }

        // When we change the Surface size, in scenarios which may require changing
        // the surface position in sync with the resize, we use a preserved surface
        // so we can freeze it while waiting for the client to report draw on the newly
        // sized surface. At the moment this logic is only in place for switching
        // in and out of the big surface for split screen resize.
        if (isDragResizeChanged()) {
            setDragResizing();
            // We can only change top level windows to the full-screen surface when
            // resizing (as we only have one full-screen surface). So there is no need
            // to preserve and destroy windows which are attached to another, they
            // will keep their surface and its size may change over time.
            if (mHasSurface && !isChildWindow()) {
                mWinAnimator.preserveSurfaceLocked();
                result |= RELAYOUT_RES_SURFACE_CHANGED |
                    RELAYOUT_RES_FIRST_TIME;
            }
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
     * Updates the last inset values to the current ones.
     */
    void updateLastInsetValues() {
        mLastOverscanInsets.set(mOverscanInsets);
        mLastContentInsets.set(mContentInsets);
        mLastVisibleInsets.set(mVisibleInsets);
        mLastStableInsets.set(mStableInsets);
        mLastOutsets.set(mOutsets);
        mLastDisplayCutout = mDisplayCutout;
    }

    void startAnimation(Animation anim) {
        final DisplayInfo displayInfo = getDisplayContent().getDisplayInfo();
        anim.initialize(mFrame.width(), mFrame.height(),
                displayInfo.appWidth, displayInfo.appHeight);
        anim.restrictDuration(MAX_ANIMATION_DURATION);
        anim.scaleCurrentDuration(mService.getWindowAnimationScaleLocked());
        final AnimationAdapter adapter = new LocalAnimationAdapter(
                new WindowAnimationSpec(anim, mSurfacePosition, false /* canSkipFirstFrame */),
                mService.mSurfaceAnimationRunner);
        startAnimation(mPendingTransaction, adapter);
        commitPendingTransaction();
    }

    private void startMoveAnimation(int left, int top) {
        if (DEBUG_ANIM) Slog.v(TAG, "Setting move animation on " + this);
        final Point oldPosition = new Point();
        final Point newPosition = new Point();
        transformFrameToSurfacePosition(mLastFrame.left, mLastFrame.top, oldPosition);
        transformFrameToSurfacePosition(left, top, newPosition);
        final AnimationAdapter adapter = new LocalAnimationAdapter(
                new MoveAnimationSpec(oldPosition.x, oldPosition.y, newPosition.x, newPosition.y),
                mService.mSurfaceAnimationRunner);
        startAnimation(getPendingTransaction(), adapter);
    }

    private void startAnimation(Transaction t, AnimationAdapter adapter) {
        startAnimation(t, adapter, mWinAnimator.mLastHidden);
    }

    @Override
    protected void onAnimationFinished() {
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
        int x = mSurfacePosition.x;
        int y = mSurfacePosition.y;

        // If changed, also adjust transformFrameToSurfacePosition
        final WindowContainer parent = getParent();
        if (isChildWindow()) {
            final WindowState parentWindow = getParentWindow();
            x += parentWindow.mFrame.left - parentWindow.mAttrs.surfaceInsets.left;
            y += parentWindow.mFrame.top - parentWindow.mAttrs.surfaceInsets.top;
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

    // TODO: Hack to work around the number of states AppWindowToken needs to access without having
    // access to its windows children. Need to investigate re-writing
    // {@link AppWindowToken#updateReportedVisibilityLocked} so this can be removed.
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
            return outer != null && outer.isFocused();
        }
    }


    @Override
    boolean shouldMagnify() {
        if (mAttrs.type == TYPE_INPUT_METHOD ||
                mAttrs.type == TYPE_INPUT_METHOD_DIALOG ||
                mAttrs.type == TYPE_MAGNIFICATION_OVERLAY ||
                mAttrs.type == TYPE_NAVIGATION_BAR ||
                // It's tempting to wonder: Have we forgotten the rounded corners overlay?
                // worry not: it's a fake TYPE_NAVIGATION_BAR_PANEL
                mAttrs.type == TYPE_NAVIGATION_BAR_PANEL) {
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
        if (mIsImWindow && mService.mInputMethodTarget != null) {
            final AppWindowToken appToken = mService.mInputMethodTarget.mAppToken;
            if (appToken != null) {
                return appToken.needsZBoost();
            }
        }
        return mWillReplaceWindow;
    }

    private void applyDims(Dimmer dimmer) {
        if (!mAnimatingExit && mAppDied) {
            mIsDimming = true;
            dimmer.dimAbove(getPendingTransaction(), this, DEFAULT_DIM_AMOUNT_DEAD_WINDOW);
        } else if ((mAttrs.flags & FLAG_DIM_BEHIND) != 0 && isVisibleNow() && !mHidden) {
            // Only show a dim behind when the following is satisfied:
            // 1. The window has the flag FLAG_DIM_BEHIND
            // 2. The WindowToken is not hidden so dims aren't shown when the window is exiting.
            // 3. The WS is considered visible according to the isVisible() method
            // 4. The WS is not hidden.
            mIsDimming = true;
            dimmer.dimBelow(getPendingTransaction(), this, mAttrs.dimAmount);
        }
    }

    @Override
    void prepareSurfaces() {
        final Dimmer dimmer = getDimmer();
        mIsDimming = false;
        if (dimmer != null) {
            applyDims(dimmer);
        }
        updateSurfacePosition();

        mWinAnimator.prepareSurfaceLocked(true);
        super.prepareSurfaces();
    }

    @Override
    public void onAnimationLeashCreated(Transaction t, SurfaceControl leash) {
        super.onAnimationLeashCreated(t, leash);

        // Leash is now responsible for position, so set our position to 0.
        t.setPosition(mSurfaceControl, 0, 0);
        mLastSurfacePosition.set(0, 0);
    }

    @Override
    public void onAnimationLeashDestroyed(Transaction t) {
        super.onAnimationLeashDestroyed(t);
        updateSurfacePosition(t);
    }

    @Override
    void updateSurfacePosition() {
        updateSurfacePosition(getPendingTransaction());
    }

    private void updateSurfacePosition(Transaction t) {
        if (mSurfaceControl == null) {
            return;
        }

        transformFrameToSurfacePosition(mFrame.left, mFrame.top, mSurfacePosition);

        if (!mSurfaceAnimator.hasLeash() && !mLastSurfacePosition.equals(mSurfacePosition)) {
            t.setPosition(mSurfaceControl, mSurfacePosition.x, mSurfacePosition.y);
            mLastSurfacePosition.set(mSurfacePosition.x, mSurfacePosition.y);
            if (surfaceInsetsChanging() && mWinAnimator.hasSurface()) {
                mLastSurfaceInsets.set(mAttrs.surfaceInsets);
                t.deferTransactionUntil(mSurfaceControl,
                        mWinAnimator.mSurfaceController.mSurfaceControl.getHandle(),
                        getFrameNumber());
            }
        }
    }

    private void transformFrameToSurfacePosition(int left, int top, Point outPoint) {
        outPoint.set(left, top);

        // If changed, also adjust getTransformationMatrix
        final WindowContainer parentWindowContainer = getParent();
        if (isChildWindow()) {
            // TODO: This probably falls apart at some point and we should
            // actually compute relative coordinates.

            // Since the parent was outset by its surface insets, we need to undo the outsetting
            // with insetting by the same amount.
            final WindowState parent = getParentWindow();
            outPoint.offset(-parent.mFrame.left + parent.mAttrs.surfaceInsets.left,
                    -parent.mFrame.top + parent.mAttrs.surfaceInsets.top);
        } else if (parentWindowContainer != null) {
            final Rect parentBounds = parentWindowContainer.getBounds();
            outPoint.offset(-parentBounds.left, -parentBounds.top);
        }

        TaskStack stack = getStack();

        // If we have stack outsets, that means the top-left
        // will be outset, and we need to inset ourselves
        // to account for it. If we actually have shadows we will
        // then un-inset ourselves by the surfaceInsets.
        if (stack != null) {
            final int outset = stack.getStackOutset();
            outPoint.offset(outset, outset);
        }

        // Expand for surface insets. See WindowState.expandForSurfaceInsets.
        outPoint.offset(-mAttrs.surfaceInsets.left, -mAttrs.surfaceInsets.top);
    }

    @Override
    void assignLayer(Transaction t, int layer) {
        // See comment in assignRelativeLayerForImeTargetChild
        if (!isChildWindow()
                || (!getParentWindow().isInputMethodTarget())
                || !inSplitScreenWindowingMode()) {
            super.assignLayer(t, layer);
            return;
        }
        getDisplayContent().assignRelativeLayerForImeTargetChild(t, this);
    }

    @Override
    public boolean isDimming() {
        return mIsDimming;
    }

    // TODO(b/70040778): We should aim to eliminate the last user of TYPE_APPLICATION_MEDIA
    // then we can drop all negative layering on the windowing side and simply inherit
    // the default implementation here.
    public void assignChildLayers(Transaction t) {
        int layer = 1;
        for (int i = 0; i < mChildren.size(); i++) {
            final WindowState w = mChildren.get(i);

            // APPLICATION_MEDIA_OVERLAY needs to go above APPLICATION_MEDIA
            // while they both need to go below the main window. However the
            // relative layering of multiple APPLICATION_MEDIA/OVERLAY has never
            // been defined and so we can use static layers and leave it that way.
            if (w.mAttrs.type == TYPE_APPLICATION_MEDIA) {
                w.assignLayer(t, -2);
            } else if (w.mAttrs.type == TYPE_APPLICATION_MEDIA_OVERLAY) {
                w.assignLayer(t, -1);
            } else {
                w.assignLayer(t, layer);
            }
            w.assignChildLayers(t);
            layer++;
        }
    }

    /**
     * Update a tap exclude region with a rectangular area identified by provided id. The requested
     * area will be clipped to the window bounds.
     */
    void updateTapExcludeRegion(int regionId, int left, int top, int width, int height) {
        final DisplayContent currentDisplay = getDisplayContent();
        if (currentDisplay == null) {
            throw new IllegalStateException("Trying to update window not attached to any display.");
        }

        if (mTapExcludeRegionHolder == null) {
            mTapExcludeRegionHolder = new TapExcludeRegionHolder();

            // Make sure that this window is registered as one that provides a tap exclude region
            // for its containing display.
            currentDisplay.mTapExcludeProvidingWindows.add(this);
        }

        mTapExcludeRegionHolder.updateRegion(regionId, left, top, width, height);
        // Trigger touch exclude region update on current display.
        final boolean isAppFocusedOnDisplay = mService.mFocusedApp != null
                && mService.mFocusedApp.getDisplayContent() == currentDisplay;
        currentDisplay.setTouchExcludeRegion(isAppFocusedOnDisplay ? mService.mFocusedApp.getTask()
                : null);
    }

    /** Union the region with current tap exclude region that this window provides. */
    void amendTapExcludeRegion(Region region) {
        mTapExcludeRegionHolder.amendRegion(region, getBounds());
    }

    @Override
    public boolean isInputMethodTarget() {
        return mService.mInputMethodTarget == this;
    }

    long getFrameNumber() {
        return mFrameNumber;
    }

    void setFrameNumber(long frameNumber) {
        mFrameNumber = frameNumber;
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
                    (anim.computeDurationHint() * mService.getWindowAnimationScaleLocked());
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
            final float fraction = (float) currentPlayTime / getDuration();
            final float v = mInterpolator.getInterpolation(fraction);
            t.setPosition(leash, mFrom.x + (mTo.x - mFrom.x) * v,
                    mFrom.y + (mTo.y - mFrom.y) * v);
        }

        @Override
        public void dump(PrintWriter pw, String prefix) {
            pw.print(prefix); pw.print("from="); pw.print(mFrom);
            pw.print(" to="); pw.print(mTo);
            pw.print(" duration="); pw.println(mDuration);
        }

        @Override
        public void writeToProtoInner(ProtoOutputStream proto) {
            final long token = proto.start(MOVE);
            mFrom.writeToProto(proto, FROM);
            mTo.writeToProto(proto, TO);
            proto.write(DURATION_MS, mDuration);
            proto.end(token);
        }
    }
}
