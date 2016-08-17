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

import android.app.ActivityManager;
import android.app.AppOpsManager;
import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Matrix;
import android.graphics.PixelFormat;
import android.graphics.Point;
import android.graphics.Rect;
import android.graphics.Region;
import android.os.IBinder;
import android.os.PowerManager;
import android.os.RemoteCallbackList;
import android.os.RemoteException;
import android.os.SystemClock;
import android.os.Trace;
import android.os.UserHandle;
import android.os.WorkSource;
import android.util.DisplayMetrics;
import android.util.Slog;
import android.util.TimeUtils;
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
import android.view.View;
import android.view.ViewTreeObserver;
import android.view.WindowManager;
import android.view.WindowManagerPolicy;

import com.android.server.input.InputWindowHandle;

import java.io.PrintWriter;
import java.util.ArrayList;

import static android.app.ActivityManager.StackId;
import static android.app.ActivityManager.StackId.DOCKED_STACK_ID;
import static android.app.ActivityManager.StackId.INVALID_STACK_ID;
import static android.os.Trace.TRACE_TAG_WINDOW_MANAGER;
import static android.view.ViewTreeObserver.InternalInsetsInfo.TOUCHABLE_INSETS_CONTENT;
import static android.view.ViewTreeObserver.InternalInsetsInfo.TOUCHABLE_INSETS_FRAME;
import static android.view.ViewTreeObserver.InternalInsetsInfo.TOUCHABLE_INSETS_REGION;
import static android.view.ViewTreeObserver.InternalInsetsInfo.TOUCHABLE_INSETS_VISIBLE;
import static android.view.WindowManager.LayoutParams.FIRST_SUB_WINDOW;
import static android.view.WindowManager.LayoutParams.FLAG_ALLOW_LOCK_WHILE_SCREEN_ON;
import static android.view.WindowManager.LayoutParams.FLAG_DIM_BEHIND;
import static android.view.WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD;
import static android.view.WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS;
import static android.view.WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE;
import static android.view.WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL;
import static android.view.WindowManager.LayoutParams.FLAG_SCALED;
import static android.view.WindowManager.LayoutParams.FLAG_SECURE;
import static android.view.WindowManager.LayoutParams.FLAG_SHOW_WALLPAPER;
import static android.view.WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED;
import static android.view.WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON;
import static android.view.WindowManager.LayoutParams.LAST_SUB_WINDOW;
import static android.view.WindowManager.LayoutParams.MATCH_PARENT;
import static android.view.WindowManager.LayoutParams.PRIVATE_FLAG_COMPATIBLE_WINDOW;
import static android.view.WindowManager.LayoutParams.PRIVATE_FLAG_KEYGUARD;
import static android.view.WindowManager.LayoutParams.PRIVATE_FLAG_LAYOUT_CHILD_WINDOW_IN_PARENT_FRAME;
import static android.view.WindowManager.LayoutParams.PRIVATE_FLAG_WILL_NOT_REPLACE_ON_RELAUNCH;
import static android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE;
import static android.view.WindowManager.LayoutParams.SOFT_INPUT_MASK_ADJUST;
import static android.view.WindowManager.LayoutParams.TYPE_APPLICATION;
import static android.view.WindowManager.LayoutParams.TYPE_APPLICATION_STARTING;
import static android.view.WindowManager.LayoutParams.TYPE_BASE_APPLICATION;
import static android.view.WindowManager.LayoutParams.TYPE_DRAWN_APPLICATION;
import static android.view.WindowManager.LayoutParams.TYPE_DOCK_DIVIDER;
import static android.view.WindowManager.LayoutParams.TYPE_INPUT_METHOD;
import static android.view.WindowManager.LayoutParams.TYPE_INPUT_METHOD_DIALOG;
import static android.view.WindowManager.LayoutParams.TYPE_WALLPAPER;
import static android.view.WindowManagerPolicy.FINISH_LAYOUT_REDO_WALLPAPER;
import static com.android.server.wm.DragResizeMode.DRAG_RESIZE_MODE_DOCKED_DIVIDER;
import static com.android.server.wm.DragResizeMode.DRAG_RESIZE_MODE_FREEFORM;
import static com.android.server.wm.WindowManagerDebugConfig.DEBUG_ADD_REMOVE;
import static com.android.server.wm.WindowManagerDebugConfig.DEBUG_ANIM;
import static com.android.server.wm.WindowManagerDebugConfig.DEBUG_APP_TRANSITIONS;
import static com.android.server.wm.WindowManagerDebugConfig.DEBUG_CONFIGURATION;
import static com.android.server.wm.WindowManagerDebugConfig.DEBUG_FOCUS_LIGHT;
import static com.android.server.wm.WindowManagerDebugConfig.DEBUG_LAYOUT;
import static com.android.server.wm.WindowManagerDebugConfig.DEBUG_ORIENTATION;
import static com.android.server.wm.WindowManagerDebugConfig.DEBUG_POWER;
import static com.android.server.wm.WindowManagerDebugConfig.DEBUG_RESIZE;
import static com.android.server.wm.WindowManagerDebugConfig.DEBUG_VISIBILITY;
import static com.android.server.wm.WindowManagerDebugConfig.TAG_WITH_CLASS_NAME;
import static com.android.server.wm.WindowManagerDebugConfig.TAG_WM;

class WindowList extends ArrayList<WindowState> {
    WindowList() {}
    WindowList(WindowList windowList) {
        super(windowList);
    }
}

/**
 * A window in the window manager.
 */
final class WindowState implements WindowManagerPolicy.WindowState {
    static final String TAG = TAG_WITH_CLASS_NAME ? "WindowState" : TAG_WM;

    // The minimal size of a window within the usable area of the freeform stack.
    // TODO(multi-window): fix the min sizes when we have mininum width/height support,
    //                     use hard-coded min sizes for now.
    static final int MINIMUM_VISIBLE_WIDTH_IN_DP = 48;
    static final int MINIMUM_VISIBLE_HEIGHT_IN_DP = 32;

    // The thickness of a window resize handle outside the window bounds on the free form workspace
    // to capture touch events in that area.
    static final int RESIZE_HANDLE_WIDTH_IN_DP = 30;

    static final boolean DEBUG_DISABLE_SAVING_SURFACES = false;

    final WindowManagerService mService;
    final WindowManagerPolicy mPolicy;
    final Context mContext;
    final Session mSession;
    final IWindow mClient;
    final int mAppOp;
    // UserId and appId of the owner. Don't display windows of non-current user.
    final int mOwnerUid;
    final IWindowId mWindowId;
    WindowToken mToken;
    WindowToken mRootToken;
    AppWindowToken mAppToken;
    AppWindowToken mTargetAppToken;

    // mAttrs.flags is tested in animation without being locked. If the bits tested are ever
    // modified they will need to be locked.
    final WindowManager.LayoutParams mAttrs = new WindowManager.LayoutParams();
    final DeathRecipient mDeathRecipient;
    final WindowState mAttachedWindow;
    final WindowList mChildWindows = new WindowList();
    final int mBaseLayer;
    final int mSubLayer;
    final boolean mLayoutAttached;
    final boolean mIsImWindow;
    final boolean mIsWallpaper;
    final boolean mIsFloatingLayer;
    int mSeq;
    boolean mEnforceSizeCompat;
    int mViewVisibility;
    int mSystemUiVisibility;
    boolean mPolicyVisibility = true;
    boolean mPolicyVisibilityAfterAnim = true;
    boolean mAppOpVisibility = true;
    boolean mAppFreezing;
    boolean mAttachedHidden;    // is our parent window hidden?
    boolean mWallpaperVisible;  // for wallpaper, what was last vis report?
    boolean mDragResizing;
    boolean mDragResizingChangeReported;
    int mResizeMode;

    RemoteCallbackList<IWindowFocusObserver> mFocusCallbacks;

    /**
     * The window size that was requested by the application.  These are in
     * the application's coordinate space (without compatibility scale applied).
     */
    int mRequestedWidth;
    int mRequestedHeight;
    int mLastRequestedWidth;
    int mLastRequestedHeight;

    int mLayer;
    boolean mHaveFrame;
    boolean mObscured;
    boolean mTurnOnScreen;

    int mLayoutSeq = -1;

    private final Configuration mTmpConfig = new Configuration();
    // Represents the changes from our override configuration applied
    // to the global configuration. This is the only form of configuration
    // which is suitable for delivery to the client.
    private Configuration mMergedConfiguration = new Configuration();
    // Sticky answer to isConfigChanged(), remains true until new Configuration is assigned.
    // Used only on {@link #TYPE_KEYGUARD}.
    private boolean mConfigHasChanged;

    /**
     * Actual position of the surface shown on-screen (may be modified by animation). These are
     * in the screen's coordinate space (WITH the compatibility scale applied).
     */
    final Point mShownPosition = new Point();

    /**
     * Insets that determine the actually visible area.  These are in the application's
     * coordinate space (without compatibility scale applied).
     */
    final Rect mVisibleInsets = new Rect();
    final Rect mLastVisibleInsets = new Rect();
    boolean mVisibleInsetsChanged;

    /**
     * Insets that are covered by system windows (such as the status bar) and
     * transient docking windows (such as the IME).  These are in the application's
     * coordinate space (without compatibility scale applied).
     */
    final Rect mContentInsets = new Rect();
    final Rect mLastContentInsets = new Rect();
    boolean mContentInsetsChanged;

    /**
     * Insets that determine the area covered by the display overscan region.  These are in the
     * application's coordinate space (without compatibility scale applied).
     */
    final Rect mOverscanInsets = new Rect();
    final Rect mLastOverscanInsets = new Rect();
    boolean mOverscanInsetsChanged;

    /**
     * Insets that determine the area covered by the stable system windows.  These are in the
     * application's coordinate space (without compatibility scale applied).
     */
    final Rect mStableInsets = new Rect();
    final Rect mLastStableInsets = new Rect();
    boolean mStableInsetsChanged;

    /**
     * Outsets determine the area outside of the surface where we want to pretend that it's possible
     * to draw anyway.
     */
    final Rect mOutsets = new Rect();
    final Rect mLastOutsets = new Rect();
    boolean mOutsetsChanged = false;

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
    boolean mFrameSizeChanged = false;
    // Frame that is scaled to the application's coordinate space when in
    // screen size compatibility mode.
    final Rect mCompatFrame = new Rect();

    final Rect mContainingFrame = new Rect();

    final Rect mParentFrame = new Rect();

    // The entire screen area of the {@link TaskStack} this window is in. Usually equal to the
    // screen area of the device.
    final Rect mDisplayFrame = new Rect();

    // The region of the display frame that the display type supports displaying content on. This
    // is mostly a special case for TV where some displays don’t have the entire display usable.
    // {@link WindowManager.LayoutParams#FLAG_LAYOUT_IN_OVERSCAN} flag can be used to allow
    // window display contents to extend into the overscan region.
    final Rect mOverscanFrame = new Rect();

    // The display frame minus the stable insets. This value is always constant regardless of if
    // the status bar or navigation bar is visible.
    final Rect mStableFrame = new Rect();

    // The area not occupied by the status and navigation bars. So, if both status and navigation
    // bars are visible, the decor frame is equal to the stable frame.
    final Rect mDecorFrame = new Rect();

    // Equal to the decor frame if the IME (e.g. keyboard) is not present. Equal to the decor frame
    // minus the area occupied by the IME if the IME is present.
    final Rect mContentFrame = new Rect();

    // Legacy stuff. Generally equal to the content frame expect when the IME for older apps
    // displays hint text.
    final Rect mVisibleFrame = new Rect();

    // Frame that includes dead area outside of the surface but where we want to pretend that it's
    // possible to draw.
    final Rect mOutsetFrame = new Rect();

    /**
     * Usually empty. Set to the task's tempInsetFrame. See
     *{@link android.app.IActivityManager#resizeDockedStack}.
     */
    final Rect mInsetFrame = new Rect();

    private static final Rect sTmpRect = new Rect();

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

    // Wallpaper windows: pixels offset based on above variables.
    int mXOffset;
    int mYOffset;

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
    boolean mOrientationChanging;

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

    /**
     * Temp for keeping track of windows that have been removed when
     * rebuilding window list.
     */
    boolean mRebuilding;

    // Input channel and input window handle used by the input dispatcher.
    final InputWindowHandle mInputWindowHandle;
    InputChannel mInputChannel;
    InputChannel mClientChannel;

    // Used to improve performance of toString()
    String mStringNameCache;
    CharSequence mLastTitle;
    boolean mWasExiting;

    final WindowStateAnimator mWinAnimator;

    boolean mHasSurface = false;

    boolean mNotOnAppsDisplay = false;
    DisplayContent  mDisplayContent;

    /** When true this window can be displayed on screens owther than mOwnerUid's */
    private boolean mShowToOwnerOnly;

    // Whether the window has a saved surface from last pause, which can be
    // used to start an entering animation earlier.
    private boolean mSurfaceSaved = false;

    // Whether we're performing an entering animation with a saved surface. This flag is
    // true during the time we're showing a window with a previously saved surface. It's
    // cleared when surface is destroyed, saved, or re-drawn by the app.
    private boolean mAnimatingWithSavedSurface;

    // Whether the window was visible when we set the app to invisible last time. WM uses
    // this as a hint to restore the surface (if available) for early animation next time
    // the app is brought visible.
    boolean mWasVisibleBeforeClientHidden;

    // This window will be replaced due to relaunch. This allows window manager
    // to differentiate between simple removal of a window and replacement. In the latter case it
    // will preserve the old window until the new one is drawn.
    boolean mWillReplaceWindow = false;
    // If true, the replaced window was already requested to be removed.
    boolean mReplacingRemoveRequested = false;
    // Whether the replacement of the window should trigger app transition animation.
    boolean mAnimateReplacingWindow = false;
    // If not null, the window that will be used to replace the old one. This is being set when
    // the window is added and unset when this window reports its first draw.
    WindowState mReplacingWindow = null;
    // For the new window in the replacement transition, if we have
    // requested to replace without animation, then we should
    // make sure we also don't apply an enter animation for
    // the new window.
    boolean mSkipEnterAnimationForSeamlessReplacement = false;
    // Whether this window is being moved via the resize API
    boolean mMovedByResize;

    /**
     * Wake lock for drawing.
     * Even though it's slightly more expensive to do so, we will use a separate wake lock
     * for each app that is requesting to draw while dozing so that we can accurately track
     * who is preventing the system from suspending.
     * This lock is only acquired on first use.
     */
    PowerManager.WakeLock mDrawLock;

    final private Rect mTmpRect = new Rect();

    /**
     * See {@link #notifyMovedInStack}.
     */
    private boolean mJustMovedInStack;

    /**
     * Whether the window was resized by us while it was gone for layout.
     */
    boolean mResizedWhileGone = false;

    /** @see #isResizedWhileNotDragResizing(). */
    private boolean mResizedWhileNotDragResizing;

    /** @see #isResizedWhileNotDragResizingReported(). */
    private boolean mResizedWhileNotDragResizingReported;

    /**
     * During seamless rotation we have two phases, first the old window contents
     * are rotated to look as if they didn't move in the new coordinate system. Then we
     * have to freeze updates to this layer (to preserve the transformation) until
     * the resize actually occurs. This is true from when the transformation is set
     * and false until the transaction to resize is sent.
     */
    boolean mSeamlesslyRotated = false;

    WindowState(WindowManagerService service, Session s, IWindow c, WindowToken token,
           WindowState attachedWindow, int appOp, int seq, WindowManager.LayoutParams a,
           int viewVisibility, final DisplayContent displayContent) {
        mService = service;
        mSession = s;
        mClient = c;
        mAppOp = appOp;
        mToken = token;
        mOwnerUid = s.mUid;
        mWindowId = new IWindowId.Stub() {
            @Override
            public void registerFocusObserver(IWindowFocusObserver observer) {
                WindowState.this.registerFocusObserver(observer);
            }
            @Override
            public void unregisterFocusObserver(IWindowFocusObserver observer) {
                WindowState.this.unregisterFocusObserver(observer);
            }
            @Override
            public boolean isFocused() {
                return WindowState.this.isFocused();
            }
        };
        mAttrs.copyFrom(a);
        mViewVisibility = viewVisibility;
        mDisplayContent = displayContent;
        mPolicy = mService.mPolicy;
        mContext = mService.mContext;
        DeathRecipient deathRecipient = new DeathRecipient();
        mSeq = seq;
        mEnforceSizeCompat = (mAttrs.privateFlags & PRIVATE_FLAG_COMPATIBLE_WINDOW) != 0;
        if (WindowManagerService.localLOGV) Slog.v(
            TAG, "Window " + this + " client=" + c.asBinder()
            + " token=" + token + " (" + mAttrs.token + ")" + " params=" + a);
        try {
            c.asBinder().linkToDeath(deathRecipient, 0);
        } catch (RemoteException e) {
            mDeathRecipient = null;
            mAttachedWindow = null;
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

        if ((mAttrs.type >= FIRST_SUB_WINDOW &&
                mAttrs.type <= LAST_SUB_WINDOW)) {
            // The multiplier here is to reserve space for multiple
            // windows in the same type layer.
            mBaseLayer = mPolicy.windowTypeToLayerLw(
                    attachedWindow.mAttrs.type) * WindowManagerService.TYPE_LAYER_MULTIPLIER
                    + WindowManagerService.TYPE_LAYER_OFFSET;
            mSubLayer = mPolicy.subWindowTypeToLayerLw(a.type);
            mAttachedWindow = attachedWindow;
            if (DEBUG_ADD_REMOVE) Slog.v(TAG, "Adding " + this + " to " + mAttachedWindow);

            final WindowList childWindows = mAttachedWindow.mChildWindows;
            final int numChildWindows = childWindows.size();
            if (numChildWindows == 0) {
                childWindows.add(this);
            } else {
                boolean added = false;
                for (int i = 0; i < numChildWindows; i++) {
                    final int childSubLayer = childWindows.get(i).mSubLayer;
                    if (mSubLayer < childSubLayer
                            || (mSubLayer == childSubLayer && childSubLayer < 0)) {
                        // We insert the child window into the list ordered by the sub-layer. For
                        // same sub-layers, the negative one should go below others; the positive
                        // one should go above others.
                        childWindows.add(i, this);
                        added = true;
                        break;
                    }
                }
                if (!added) {
                    childWindows.add(this);
                }
            }

            mLayoutAttached = mAttrs.type !=
                    WindowManager.LayoutParams.TYPE_APPLICATION_ATTACHED_DIALOG;
            mIsImWindow = attachedWindow.mAttrs.type == TYPE_INPUT_METHOD
                    || attachedWindow.mAttrs.type == TYPE_INPUT_METHOD_DIALOG;
            mIsWallpaper = attachedWindow.mAttrs.type == TYPE_WALLPAPER;
            mIsFloatingLayer = mIsImWindow || mIsWallpaper;
        } else {
            // The multiplier here is to reserve space for multiple
            // windows in the same type layer.
            mBaseLayer = mPolicy.windowTypeToLayerLw(a.type)
                    * WindowManagerService.TYPE_LAYER_MULTIPLIER
                    + WindowManagerService.TYPE_LAYER_OFFSET;
            mSubLayer = 0;
            mAttachedWindow = null;
            mLayoutAttached = false;
            mIsImWindow = mAttrs.type == TYPE_INPUT_METHOD
                    || mAttrs.type == TYPE_INPUT_METHOD_DIALOG;
            mIsWallpaper = mAttrs.type == TYPE_WALLPAPER;
            mIsFloatingLayer = mIsImWindow || mIsWallpaper;
        }

        WindowState appWin = this;
        while (appWin.isChildWindow()) {
            appWin = appWin.mAttachedWindow;
        }
        WindowToken appToken = appWin.mToken;
        while (appToken.appWindowToken == null) {
            WindowToken parent = mService.mTokenMap.get(appToken.token);
            if (parent == null || appToken == parent) {
                break;
            }
            appToken = parent;
        }
        mRootToken = appToken;
        mAppToken = appToken.appWindowToken;
        if (mAppToken != null) {
            final DisplayContent appDisplay = getDisplayContent();
            mNotOnAppsDisplay = displayContent != appDisplay;

            if (mAppToken.showForAllUsers) {
                // Windows for apps that can show for all users should also show when the
                // device is locked.
                mAttrs.flags |= FLAG_SHOW_WHEN_LOCKED;
            }
        }

        mWinAnimator = new WindowStateAnimator(this);
        mWinAnimator.mAlpha = a.alpha;

        mRequestedWidth = 0;
        mRequestedHeight = 0;
        mLastRequestedWidth = 0;
        mLastRequestedHeight = 0;
        mXOffset = 0;
        mYOffset = 0;
        mLayer = 0;
        mInputWindowHandle = new InputWindowHandle(
                mAppToken != null ? mAppToken.mInputApplicationHandle : null, this,
                displayContent.getDisplayId());
    }

    void attach() {
        if (WindowManagerService.localLOGV) Slog.v(
            TAG, "Attaching " + this + " token=" + mToken
            + ", list=" + mToken.windows);
        mSession.windowAddedLocked();
    }

    @Override
    public int getOwningUid() {
        return mOwnerUid;
    }

    @Override
    public String getOwningPackage() {
        return mAttrs.packageName;
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
    public void computeFrameLw(Rect pf, Rect df, Rect of, Rect cf, Rect vf, Rect dcf, Rect sf,
            Rect osf) {
        if (mWillReplaceWindow && (mAnimatingExit || !mReplacingRemoveRequested)) {
            // This window is being replaced and either already got information that it's being
            // removed or we are still waiting for some information. Because of this we don't
            // want to apply any more changes to it, so it remains in this state until new window
            // appears.
            return;
        }
        mHaveFrame = true;

        final Task task = getTask();
        final boolean fullscreenTask = !isInMultiWindowMode();
        final boolean windowsAreFloating = task != null && task.isFloating();

        // If the task has temp inset bounds set, we have to make sure all its windows uses
        // the temp inset frame. Otherwise different display frames get applied to the main
        // window and the child window, making them misaligned.
        if (fullscreenTask) {
            mInsetFrame.setEmpty();
        } else {
            task.getTempInsetBounds(mInsetFrame);
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
        if (fullscreenTask || layoutInParentFrame()) {
            // We use the parent frame as the containing frame for fullscreen and child windows
            mContainingFrame.set(pf);
            mDisplayFrame.set(df);
            layoutDisplayFrame = df;
            layoutContainingFrame = pf;
            layoutXDiff = 0;
            layoutYDiff = 0;
        } else {
            task.getBounds(mContainingFrame);
            if (mAppToken != null && !mAppToken.mFrozenBounds.isEmpty()) {

                // If the bounds are frozen, we still want to translate the window freely and only
                // freeze the size.
                Rect frozen = mAppToken.mFrozenBounds.peek();
                mContainingFrame.right = mContainingFrame.left + frozen.width();
                mContainingFrame.bottom = mContainingFrame.top + frozen.height();
            }
            final WindowState imeWin = mService.mInputMethodWindow;
            // IME is up and obscuring this window. Adjust the window position so it is visible.
            if (imeWin != null && imeWin.isVisibleNow() && mService.mInputMethodTarget == this) {
                    if (windowsAreFloating && mContainingFrame.bottom > cf.bottom) {
                        // In freeform we want to move the top up directly.
                        // TODO: Investigate why this is cf not pf.
                        mContainingFrame.top -= mContainingFrame.bottom - cf.bottom;
                    } else if (mContainingFrame.bottom > pf.bottom) {
                        // But in docked we want to behave like fullscreen
                        // and behave as if the task were given smaller bounds
                        // for the purposes of layout.
                        mContainingFrame.bottom = pf.bottom;
                    }
            }

            if (windowsAreFloating) {
                // In floating modes (e.g. freeform, pinned) we have only to set the rectangle
                // if it wasn't set already. No need to intersect it with the (visible)
                // "content frame" since it is allowed to be outside the visible desktop.
                if (mContainingFrame.isEmpty()) {
                    mContainingFrame.set(cf);
                }
            }
            mDisplayFrame.set(mContainingFrame);
            layoutXDiff = !mInsetFrame.isEmpty() ? mInsetFrame.left - mContainingFrame.left : 0;
            layoutYDiff = !mInsetFrame.isEmpty() ? mInsetFrame.top - mContainingFrame.top : 0;
            layoutContainingFrame = !mInsetFrame.isEmpty() ? mInsetFrame : mContainingFrame;
            mTmpRect.set(0, 0, mDisplayContent.getDisplayInfo().logicalWidth,
                    mDisplayContent.getDisplayInfo().logicalHeight);
            subtractInsets(mDisplayFrame, layoutContainingFrame, df, mTmpRect);
            if (!layoutInParentFrame()) {
                subtractInsets(mContainingFrame, layoutContainingFrame, pf, mTmpRect);
                subtractInsets(mInsetFrame, layoutContainingFrame, pf, mTmpRect);
            }
            layoutDisplayFrame = df;
            layoutDisplayFrame.intersect(layoutContainingFrame);
        }

        final int pw = mContainingFrame.width();
        final int ph = mContainingFrame.height();

        if (!mParentFrame.equals(pf)) {
            //Slog.i(TAG_WM, "Window " + this + " content frame from " + mParentFrame
            //        + " to " + pf);
            mParentFrame.set(pf);
            mContentChanged = true;
        }
        if (mRequestedWidth != mLastRequestedWidth || mRequestedHeight != mLastRequestedHeight) {
            mLastRequestedWidth = mRequestedWidth;
            mLastRequestedHeight = mRequestedHeight;
            mContentChanged = true;
        }

        mOverscanFrame.set(of);
        mContentFrame.set(cf);
        mVisibleFrame.set(vf);
        mDecorFrame.set(dcf);
        mStableFrame.set(sf);
        final boolean hasOutsets = osf != null;
        if (hasOutsets) {
            mOutsetFrame.set(osf);
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
            // Keep the frame out of the blocked system area, limit it in size to the content area
            // and make sure that there is always a minimum visible so that the user can drag it
            // into a usable area..
            final int height = Math.min(mFrame.height(), mContentFrame.height());
            final int width = Math.min(mContentFrame.width(), mFrame.width());
            final DisplayMetrics displayMetrics = getDisplayContent().getDisplayMetrics();
            final int minVisibleHeight = Math.min(height, WindowManagerService.dipToPixel(
                    MINIMUM_VISIBLE_HEIGHT_IN_DP, displayMetrics));
            final int minVisibleWidth = Math.min(width, WindowManagerService.dipToPixel(
                    MINIMUM_VISIBLE_WIDTH_IN_DP, displayMetrics));
            final int top = Math.max(mContentFrame.top,
                    Math.min(mFrame.top, mContentFrame.bottom - minVisibleHeight));
            final int left = Math.max(mContentFrame.left + minVisibleWidth - width,
                    Math.min(mFrame.left, mContentFrame.right - minVisibleWidth));
            mFrame.set(left, top, left + width, top + height);
            mContentFrame.set(mFrame);
            mVisibleFrame.set(mContentFrame);
            mStableFrame.set(mContentFrame);
        } else if (mAttrs.type == TYPE_DOCK_DIVIDER) {
            mDisplayContent.getDockedDividerController().positionDockedStackedDivider(mFrame);
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

        if (fullscreenTask && !windowsAreFloating) {
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
            mStableInsets.set(Math.max(mStableFrame.left - mDisplayFrame.left, 0),
                    Math.max(mStableFrame.top - mDisplayFrame.top, 0),
                    Math.max(mDisplayFrame.right - mStableFrame.right, 0),
                    Math.max(mDisplayFrame.bottom - mStableFrame.bottom, 0));

            // The divider doesn't care about insets in any case, so set it to empty so we don't
            // trigger a relayout when moving it.
            mContentInsets.setEmpty();
            mVisibleInsets.setEmpty();
        } else {
            getDisplayContent().getLogicalDisplayRect(mTmpRect);
            // Override right and/or bottom insets in case if the frame doesn't fit the screen in
            // non-fullscreen mode.
            boolean overrideRightInset = !fullscreenTask && mFrame.right > mTmpRect.right;
            boolean overrideBottomInset = !fullscreenTask && mFrame.bottom > mTmpRect.bottom;
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
                mService.mWallpaperControllerLocked.updateWallpaperOffset(
                        this, displayInfo.logicalWidth, displayInfo.logicalHeight, false);
            }
        }

        if (DEBUG_LAYOUT || WindowManagerService.localLOGV) Slog.v(TAG,
                "Resolving (mRequestedWidth="
                + mRequestedWidth + ", mRequestedheight="
                + mRequestedHeight + ") to" + " (pw=" + pw + ", ph=" + ph
                + "): frame=" + mFrame.toShortString()
                + " ci=" + mContentInsets.toShortString()
                + " vi=" + mVisibleInsets.toShortString()
                + " si=" + mStableInsets.toShortString()
                + " of=" + mOutsets.toShortString());
    }

    @Override
    public Rect getFrameLw() {
        return mFrame;
    }

    @Override
    public Point getShownPositionLw() {
        return mShownPosition;
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
        int index = -1;
        WindowState ws = this;
        WindowList windows = getWindowList();
        while (true) {
            if (ws.mAttrs.needsMenuKey != WindowManager.LayoutParams.NEEDS_MENU_UNSET) {
                return ws.mAttrs.needsMenuKey == WindowManager.LayoutParams.NEEDS_MENU_SET_TRUE;
            }
            // If we reached the bottom of the range of windows we are considering,
            // assume no menu is needed.
            if (ws == bottom) {
                return false;
            }
            // The current window hasn't specified whether menu key is needed;
            // look behind it.
            // First, we may need to determine the starting position.
            if (index < 0) {
                index = windows.indexOf(ws);
            }
            index--;
            if (index < 0) {
                return false;
            }
            ws = windows.get(index);
        }
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
        WindowState win = this;
        while (win.isChildWindow()) {
            win = win.mAttachedWindow;
        }
        return win.mAttrs.type;
    }

    @Override
    public IApplicationToken getAppToken() {
        return mAppToken != null ? mAppToken.appToken : null;
    }

    @Override
    public boolean isVoiceInteraction() {
        return mAppToken != null && mAppToken.voiceInteraction;
    }

    boolean setReportResizeHints() {
        mOverscanInsetsChanged |= !mLastOverscanInsets.equals(mOverscanInsets);
        mContentInsetsChanged |= !mLastContentInsets.equals(mContentInsets);
        mVisibleInsetsChanged |= !mLastVisibleInsets.equals(mVisibleInsets);
        mStableInsetsChanged |= !mLastStableInsets.equals(mStableInsets);
        mOutsetsChanged |= !mLastOutsets.equals(mOutsets);
        mFrameSizeChanged |= (mLastFrame.width() != mFrame.width()) ||
                (mLastFrame.height() != mFrame.height());
        return mOverscanInsetsChanged || mContentInsetsChanged || mVisibleInsetsChanged
                || mOutsetsChanged || mFrameSizeChanged;
    }

    public DisplayContent getDisplayContent() {
        if (mAppToken == null || mNotOnAppsDisplay) {
            return mDisplayContent;
        }
        final TaskStack stack = getStack();
        return stack == null ? mDisplayContent : stack.getDisplayContent();
    }

    public DisplayInfo getDisplayInfo() {
        final DisplayContent displayContent = getDisplayContent();
        return displayContent != null ? displayContent.getDisplayInfo() : null;
    }

    public int getDisplayId() {
        final DisplayContent displayContent = getDisplayContent();
        if (displayContent == null) {
            return -1;
        }
        return displayContent.getDisplayId();
    }

    Task getTask() {
        return mAppToken != null ? mAppToken.mTask : null;
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
        return mAttrs.type >= WindowManager.LayoutParams.FIRST_SYSTEM_WINDOW
                && mDisplayContent != null ? mDisplayContent.getHomeStack() : null;
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
                ? mAppToken.inputDispatchingTimeoutNanos
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
            mGlobalScale = mService.mCompatibleScreenScale;
            mInvGlobalScale = 1/mGlobalScale;
        } else {
            mGlobalScale = mInvGlobalScale = 1;
        }
    }

    /**
     * Does the minimal check for visibility. Callers generally want to use one of the public
     * methods as they perform additional checks on the app token.
     * TODO: See if there are other places we can use this check below instead of duplicating...
     */
    private boolean isVisibleUnchecked() {
        return mHasSurface && mPolicyVisibility && !mAttachedHidden
                && !mAnimatingExit && !mDestroying && (!mIsWallpaper || mWallpaperVisible);
    }

    /**
     * Is this window visible?  It is not visible if there is no surface, or we are in the process
     * of running an exit animation that will remove the surface, or its app token has been hidden.
     */
    @Override
    public boolean isVisibleLw() {
        return (mAppToken == null || !mAppToken.hiddenRequested) && isVisibleUnchecked();
    }

    /**
     * Like {@link #isVisibleLw}, but also counts a window that is currently "hidden" behind the
     * keyguard as visible.  This allows us to apply things like window flags that impact the
     * keyguard. XXX I am starting to think we need to have ANOTHER visibility flag for this
     * "hidden behind keyguard" state rather than overloading mPolicyVisibility.  Ungh.
     */
    @Override
    public boolean isVisibleOrBehindKeyguardLw() {
        if (mRootToken.waitingToShow && mService.mAppTransition.isTransitionSet()) {
            return false;
        }
        final AppWindowToken atoken = mAppToken;
        final boolean animating = atoken != null && atoken.mAppAnimator.animation != null;
        return mHasSurface && !mDestroying && !mAnimatingExit
                && (atoken == null ? mPolicyVisibility : !atoken.hiddenRequested)
                && ((!mAttachedHidden && mViewVisibility == View.VISIBLE && !mRootToken.hidden)
                        || mWinAnimator.mAnimation != null || animating);
    }

    /**
     * Is this window visible, ignoring its app token? It is not visible if there is no surface,
     * or we are in the process of running an exit animation that will remove the surface.
     */
    public boolean isWinVisibleLw() {
        return (mAppToken == null || !mAppToken.hiddenRequested || mAppToken.mAppAnimator.animating)
                && isVisibleUnchecked();
    }

    /**
     * The same as isVisible(), but follows the current hidden state of the associated app token,
     * not the pending requested hidden state.
     */
    boolean isVisibleNow() {
        return (!mRootToken.hidden || mAttrs.type == TYPE_APPLICATION_STARTING)
                && isVisibleUnchecked();
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
                && mPolicyVisibility && !mAttachedHidden
                && (atoken == null || !atoken.hiddenRequested)
                && !mAnimatingExit && !mDestroying;
    }

    /**
     * Is this window currently on-screen?  It is on-screen either if it
     * is visible or it is currently running an animation before no longer
     * being visible.
     */
    boolean isOnScreen() {
        return mPolicyVisibility && isOnScreenIgnoringKeyguard();
    }

    /**
     * Like isOnScreen(), but ignores any force hiding of the window due
     * to the keyguard.
     */
    boolean isOnScreenIgnoringKeyguard() {
        if (!mHasSurface || mDestroying) {
            return false;
        }
        final AppWindowToken atoken = mAppToken;
        if (atoken != null) {
            return ((!mAttachedHidden && !atoken.hiddenRequested)
                    || mWinAnimator.mAnimation != null || atoken.mAppAnimator.animation != null);
        }
        return !mAttachedHidden || mWinAnimator.mAnimation != null;
    }

    /**
     * Whether this window's drawn state might affect the drawn states of the app token.
     *
     * @param visibleOnly Whether we should consider only the windows that's currently
     *                    visible in layout. If true, windows that has not relayout to VISIBLE
     *                    would always return false.
     *
     * @return true if the window should be considered while evaluating allDrawn flags.
     */
    boolean mightAffectAllDrawn(boolean visibleOnly) {
        final boolean isViewVisible = (mAppToken == null || !mAppToken.clientHidden)
                && (mViewVisibility == View.VISIBLE) && !mWindowRemovalAllowed;
        return (isOnScreenIgnoringKeyguard() && (!visibleOnly || isViewVisible)
                || mWinAnimator.mAttrType == TYPE_BASE_APPLICATION
                || mWinAnimator.mAttrType == TYPE_DRAWN_APPLICATION)
                && !mAnimatingExit && !mDestroying;
    }

    /**
     * Whether this window is "interesting" when evaluating allDrawn. If it's interesting,
     * it must be drawn before allDrawn can become true.
     */
    boolean isInteresting() {
        return mAppToken != null && !mAppDied
                && (!mAppToken.mAppAnimator.freezingScreen || !mAppFreezing);
    }

    /**
     * Like isOnScreen(), but we don't return true if the window is part
     * of a transition that has not yet been started.
     */
    boolean isReadyForDisplay() {
        if (mRootToken.waitingToShow && mService.mAppTransition.isTransitionSet()) {
            return false;
        }
        return mHasSurface && mPolicyVisibility && !mDestroying
                && ((!mAttachedHidden && mViewVisibility == View.VISIBLE && !mRootToken.hidden)
                        || mWinAnimator.mAnimation != null
                        || ((mAppToken != null) && (mAppToken.mAppAnimator.animation != null)));
    }

    /**
     * Like isReadyForDisplay(), but ignores any force hiding of the window due
     * to the keyguard.
     */
    boolean isReadyForDisplayIgnoringKeyguard() {
        if (mRootToken.waitingToShow && mService.mAppTransition.isTransitionSet()) {
            return false;
        }
        final AppWindowToken atoken = mAppToken;
        if (atoken == null && !mPolicyVisibility) {
            // If this is not an app window, and the policy has asked to force
            // hide, then we really do want to hide.
            return false;
        }
        return mHasSurface && !mDestroying
                && ((!mAttachedHidden && mViewVisibility == View.VISIBLE && !mRootToken.hidden)
                        || mWinAnimator.mAnimation != null
                        || ((atoken != null) && (atoken.mAppAnimator.animation != null)
                                && !mWinAnimator.isDummyAnimation())
                        || isAnimatingWithSavedSurface());
    }

    /**
     * Like isOnScreen, but returns false if the surface hasn't yet
     * been drawn.
     */
    @Override
    public boolean isDisplayedLw() {
        final AppWindowToken atoken = mAppToken;
        return isDrawnLw() && mPolicyVisibility
            && ((!mAttachedHidden &&
                    (atoken == null || !atoken.hiddenRequested))
                        || mWinAnimator.mAnimating
                        || (atoken != null && atoken.mAppAnimator.animation != null));
    }

    /**
     * Return true if this window or its app token is currently animating.
     */
    @Override
    public boolean isAnimatingLw() {
        return mWinAnimator.mAnimation != null
                || (mAppToken != null && mAppToken.mAppAnimator.animation != null);
    }

    @Override
    public boolean isGoneForLayoutLw() {
        final AppWindowToken atoken = mAppToken;
        return mViewVisibility == View.GONE
                || !mRelayoutCalled
                || (atoken == null && mRootToken.hidden)
                || (atoken != null && atoken.hiddenRequested)
                || mAttachedHidden
                || (mAnimatingExit && !isAnimatingLw())
                || mDestroying;
    }

    /**
     * Returns true if the window has a surface that it has drawn a
     * complete UI in to.
     */
    public boolean isDrawFinishedLw() {
        return mHasSurface && !mDestroying &&
                (mWinAnimator.mDrawState == WindowStateAnimator.COMMIT_DRAW_PENDING
                || mWinAnimator.mDrawState == WindowStateAnimator.READY_TO_SHOW
                || mWinAnimator.mDrawState == WindowStateAnimator.HAS_DRAWN);
    }

    /**
     * Returns true if the window has a surface that it has drawn a
     * complete UI in to.
     */
    @Override
    public boolean isDrawnLw() {
        return mHasSurface && !mDestroying &&
                (mWinAnimator.mDrawState == WindowStateAnimator.READY_TO_SHOW
                || mWinAnimator.mDrawState == WindowStateAnimator.HAS_DRAWN);
    }

    /**
     * Return true if the window is opaque and fully drawn.  This indicates
     * it may obscure windows behind it.
     */
    boolean isOpaqueDrawn() {
        // When there is keyguard, wallpaper could be placed over the secure app
        // window but invisible. We need to check wallpaper visibility explicitly
        // to determine if it's occluding apps.
        return ((!mIsWallpaper && mAttrs.format == PixelFormat.OPAQUE)
                || (mIsWallpaper && mWallpaperVisible))
                && isDrawnLw() && mWinAnimator.mAnimation == null
                && (mAppToken == null || mAppToken.mAppAnimator.animation == null);
    }

    /**
     * Return whether this window has moved. (Only makes
     * sense to call from performLayoutAndPlaceSurfacesLockedInner().)
     */
    boolean hasMoved() {
        return mHasSurface && (mContentChanged || mMovedByResize)
                && !mAnimatingExit && mService.okToDisplay()
                && (mFrame.top != mLastFrame.top || mFrame.left != mLastFrame.left)
                && (mAttachedWindow == null || !mAttachedWindow.hasMoved());
    }

    boolean isObscuringFullscreen(final DisplayInfo displayInfo) {
        Task task = getTask();
        if (task != null && task.mStack != null && !task.mStack.isFullscreen()) {
            return false;
        }
        if (!isOpaqueDrawn() || !isFrameFullscreen(displayInfo)) {
            return false;
        }
        return true;
    }

    boolean isFrameFullscreen(final DisplayInfo displayInfo) {
        return mFrame.left <= 0 && mFrame.top <= 0
                && mFrame.right >= displayInfo.appWidth && mFrame.bottom >= displayInfo.appHeight;
    }

    boolean isConfigChanged() {
        getMergedConfig(mTmpConfig);

        // If the merged configuration is still empty, it means that we haven't issues the
        // configuration to the client yet and we need to return true so the configuration updates.
        boolean configChanged = mMergedConfiguration.equals(Configuration.EMPTY)
                || mTmpConfig.diff(mMergedConfiguration) != 0;

        if ((mAttrs.privateFlags & PRIVATE_FLAG_KEYGUARD) != 0) {
            // Retain configuration changed status until resetConfiguration called.
            mConfigHasChanged |= configChanged;
            configChanged = mConfigHasChanged;
        }

        return configChanged;
    }

    boolean isAdjustedForMinimizedDock() {
        return mAppToken != null && mAppToken.mTask != null
                && mAppToken.mTask.mStack.isAdjustedForMinimizedDock();
    }

    void removeLocked() {
        disposeInputChannel();

        if (isChildWindow()) {
            if (DEBUG_ADD_REMOVE) Slog.v(TAG, "Removing " + this + " from " + mAttachedWindow);
            mAttachedWindow.mChildWindows.remove(this);
        }
        mWinAnimator.destroyDeferredSurfaceLocked();
        mWinAnimator.destroySurfaceLocked();
        mSession.windowRemovedLocked();
        try {
            mClient.asBinder().unlinkToDeath(mDeathRecipient, 0);
        } catch (RuntimeException e) {
            // Ignore if it has already been removed (usually because
            // we are doing this as part of processing a death note.)
        }
    }

    void setHasSurface(boolean hasSurface) {
        mHasSurface = hasSurface;
    }

    int getAnimLayerAdjustment() {
        if (mTargetAppToken != null) {
            return mTargetAppToken.mAppAnimator.animLayerAdjustment;
        } else if (mAppToken != null) {
            return mAppToken.mAppAnimator.animLayerAdjustment;
        } else {
            // Nothing is animating, so there is no animation adjustment.
            return 0;
        }
    }

    void scheduleAnimationIfDimming() {
        if (mDisplayContent == null) {
            return;
        }
        final DimLayer.DimLayerUser dimLayerUser = getDimLayerUser();
        if (dimLayerUser != null && mDisplayContent.mDimLayerController.isDimming(
                dimLayerUser, mWinAnimator)) {
            // Force an animation pass just to update the mDimLayer layer.
            mService.scheduleAnimationLocked();
        }
    }

    /**
     * Notifies this window that the corresponding task has just moved in the stack.
     * <p>
     * This is used to fix the following: If we moved in the stack, and if the last clip rect was
     * empty, meaning that our task was completely offscreen, we need to keep it invisible because
     * the actual app transition that updates the visibility is delayed by a few transactions.
     * Instead of messing around with the ordering and timing how transitions and transactions are
     * executed, we introduce this little hack which prevents this window of getting visible again
     * with the wrong bounds until the app transitions has started.
     * <p>
     * This method notifies the window about that we just moved in the stack so we can apply this
     * logic in {@link WindowStateAnimator#updateSurfaceWindowCrop}
     */
    void notifyMovedInStack() {
        mJustMovedInStack = true;
    }

    /**
     * See {@link #notifyMovedInStack}.
     *
     * @return Whether we just got moved in the corresponding stack.
     */
    boolean hasJustMovedInStack() {
        return mJustMovedInStack;
    }

    /**
     * Resets that we just moved in the corresponding stack. See {@link #notifyMovedInStack}.
     */
    void resetJustMovedInStack() {
        mJustMovedInStack = false;
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
        String name = makeInputChannelName();
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

    void applyDimLayerIfNeeded() {
        // When the app is terminated (eg. from Recents), the task might have already been
        // removed with the window pending removal. Don't apply dim in such cases, as there
        // will be no more updateDimLayer() calls, which leaves the dimlayer invalid.
        final AppWindowToken token = mAppToken;
        if (token != null && token.removed) {
            return;
        }

        if (!mAnimatingExit && mAppDied) {
            // If app died visible, apply a dim over the window to indicate that it's inactive
            mDisplayContent.mDimLayerController.applyDimAbove(getDimLayerUser(), mWinAnimator);
        } else if ((mAttrs.flags & FLAG_DIM_BEHIND) != 0
                && mDisplayContent != null && !mAnimatingExit && isVisibleUnchecked()) {
            mDisplayContent.mDimLayerController.applyDimBehind(getDimLayerUser(), mWinAnimator);
        }
    }

    DimLayer.DimLayerUser getDimLayerUser() {
        Task task = getTask();
        if (task != null) {
            return task;
        }
        return getStack();
    }

    void maybeRemoveReplacedWindow() {
        if (mAppToken == null) {
            return;
        }
        for (int i = mAppToken.allAppWindows.size() - 1; i >= 0; i--) {
            final WindowState win = mAppToken.allAppWindows.get(i);
            if (win.mWillReplaceWindow && win.mReplacingWindow == this && hasDrawnLw()) {
                if (DEBUG_ADD_REMOVE) Slog.d(TAG, "Removing replaced window: " + win);
                if (win.isDimming()) {
                    win.transferDimToReplacement();
                }
                win.mWillReplaceWindow = false;
                final boolean animateReplacingWindow = win.mAnimateReplacingWindow;
                win.mAnimateReplacingWindow = false;
                win.mReplacingRemoveRequested = false;
                win.mReplacingWindow = null;
                mSkipEnterAnimationForSeamlessReplacement = false;
                if (win.mAnimatingExit || !animateReplacingWindow) {
                    mService.removeWindowInnerLocked(win);
                }
            }
        }
    }

    void setDisplayLayoutNeeded() {
        if (mDisplayContent != null) {
            mDisplayContent.layoutNeeded = true;
        }
    }

    boolean inDockedWorkspace() {
        final Task task = getTask();
        return task != null && task.inDockedWorkspace();
    }

    // TODO: Strange usage of word workspace here and above.
    boolean inPinnedWorkspace() {
        final Task task = getTask();
        return task != null && task.inPinnedWorkspace();
    }

    boolean isDockedInEffect() {
        final Task task = getTask();
        return task != null && task.isDockedInEffect();
    }

    void applyScrollIfNeeded() {
        final Task task = getTask();
        if (task != null) {
            task.applyScrollToWindowIfNeeded(this);
        }
    }

    void applyAdjustForImeIfNeeded() {
        final Task task = getTask();
        if (task != null && task.mStack != null && task.mStack.isAdjustedForIme()) {
            task.mStack.applyAdjustForImeIfNeeded(task);
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
            final DimLayer.DimLayerUser dimLayerUser = getDimLayerUser();
            if (dimLayerUser != null) {
                dimLayerUser.getDimBounds(mTmpRect);
            } else {
                getVisibleBounds(mTmpRect);
            }
            if (inFreeformWorkspace()) {
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
            setDisplayLayoutNeeded();
            if (!mPolicyVisibility) {
                if (mService.mCurrentFocus == this) {
                    if (DEBUG_FOCUS_LIGHT) Slog.i(TAG,
                            "setAnimationLocked: setting mFocusMayChange true");
                    mService.mFocusMayChange = true;
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

    void prepareWindowToDisplayDuringRelayout(Configuration outConfig) {
        if ((mAttrs.softInputMode & SOFT_INPUT_MASK_ADJUST)
                == SOFT_INPUT_ADJUST_RESIZE) {
            mLayoutNeeded = true;
        }
        if (isDrawnLw() && mService.okToDisplay()) {
            mWinAnimator.applyEnterAnimationLocked();
        }
        if ((mAttrs.flags & FLAG_TURN_SCREEN_ON) != 0) {
            if (DEBUG_VISIBILITY) Slog.v(TAG, "Relayout window turning screen on: " + this);
            mTurnOnScreen = true;
        }
        if (isConfigChanged()) {
            final Configuration newConfig = updateConfiguration();
            if (DEBUG_CONFIGURATION) Slog.i(TAG, "Window " + this + " visible with new config: "
                    + newConfig);
            outConfig.setTo(newConfig);
        }
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
                synchronized(mService.mWindowMap) {
                    WindowState win = mService.windowForClientLocked(mSession, mClient, false);
                    Slog.i(TAG, "WIN DEATH: " + win);
                    if (win != null) {
                        mService.removeWindowLocked(win, shouldKeepVisibleDeadAppWindow());
                        if (win.mAttrs.type == TYPE_DOCK_DIVIDER) {
                            // The owner of the docked divider died :( We reset the docked stack,
                            // just in case they have the divider at an unstable position. Better
                            // also reset drag resizing state, because the owner can't do it
                            // anymore.
                            final TaskStack stack = mService.mStackIdToStack.get(DOCKED_STACK_ID);
                            if (stack != null) {
                                stack.resetDockedStackToMiddle();
                            }
                            mService.setDockedStackResizing(false);
                        }
                    } else if (mHasSurface) {
                        Slog.e(TAG, "!!! LEAK !!! Window removed but surface still valid.");
                        mService.removeWindowLocked(WindowState.this);
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
    boolean shouldKeepVisibleDeadAppWindow() {
        if (!isWinVisibleLw() || mAppToken == null || mAppToken.clientHidden) {
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

        final TaskStack stack = getStack();
        return stack != null && StackId.keepVisibleDeadAppWindowOnScreen(stack.mStackId);
    }

    /** @return true if this window desires key events. */
    boolean canReceiveKeys() {
        return isVisibleOrAdding()
                && (mViewVisibility == View.VISIBLE) && !mRemoveOnExit
                && ((mAttrs.flags & WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE) == 0)
                && (mAppToken == null || mAppToken.windowsAreFocusable())
                && !isAdjustedForMinimizedDock();
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
        if (mPolicyVisibility && mPolicyVisibilityAfterAnim) {
            // Already showing.
            return false;
        }
        if (DEBUG_VISIBILITY) Slog.v(TAG, "Policy visibility true: " + this);
        if (doAnimation) {
            if (DEBUG_VISIBILITY) Slog.v(TAG, "doAnimation: mPolicyVisibility="
                    + mPolicyVisibility + " mAnimation=" + mWinAnimator.mAnimation);
            if (!mService.okToDisplay()) {
                doAnimation = false;
            } else if (mPolicyVisibility && mWinAnimator.mAnimation == null) {
                // Check for the case where we are currently visible and
                // not animating; we do not want to do animation at such a
                // point to become visible when we already are.
                doAnimation = false;
            }
        }
        mPolicyVisibility = true;
        mPolicyVisibilityAfterAnim = true;
        if (doAnimation) {
            mWinAnimator.applyAnimationLocked(WindowManagerPolicy.TRANSIT_ENTER, true);
        }
        if (requestAnim) {
            mService.scheduleAnimationLocked();
        }
        return true;
    }

    @Override
    public boolean hideLw(boolean doAnimation) {
        return hideLw(doAnimation, true);
    }

    boolean hideLw(boolean doAnimation, boolean requestAnim) {
        if (doAnimation) {
            if (!mService.okToDisplay()) {
                doAnimation = false;
            }
        }
        boolean current = doAnimation ? mPolicyVisibilityAfterAnim
                : mPolicyVisibility;
        if (!current) {
            // Already hiding.
            return false;
        }
        if (doAnimation) {
            mWinAnimator.applyAnimationLocked(WindowManagerPolicy.TRANSIT_EXIT, false);
            if (mWinAnimator.mAnimation == null) {
                doAnimation = false;
            }
        }
        if (doAnimation) {
            mPolicyVisibilityAfterAnim = false;
        } else {
            if (DEBUG_VISIBILITY) Slog.v(TAG, "Policy visibility false: " + this);
            mPolicyVisibilityAfterAnim = false;
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
        return true;
    }

    public void setAppOpVisibilityLw(boolean state) {
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

    public void pokeDrawLockLw(long timeout) {
        if (isVisibleOrAdding()) {
            if (mDrawLock == null) {
                // We want the tag name to be somewhat stable so that it is easier to correlate
                // in wake lock statistics.  So in particular, we don't want to include the
                // window's hash code as in toString().
                final CharSequence tag = getWindowTag();
                mDrawLock = mService.mPowerManager.newWakeLock(
                        PowerManager.DRAW_WAKE_LOCK, "Window:" + tag);
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

    boolean isAnimatingWithSavedSurface() {
        return mAnimatingWithSavedSurface;
    }

    boolean isAnimatingInvisibleWithSavedSurface() {
        return mAnimatingWithSavedSurface
                && (mViewVisibility != View.VISIBLE || mWindowRemovalAllowed);
    }

    public void setVisibleBeforeClientHidden() {
        mWasVisibleBeforeClientHidden |=
                (mViewVisibility == View.VISIBLE || mAnimatingWithSavedSurface);
    }

    public void clearVisibleBeforeClientHidden() {
        mWasVisibleBeforeClientHidden = false;
    }

    public boolean wasVisibleBeforeClientHidden() {
        return mWasVisibleBeforeClientHidden;
    }

    private boolean shouldSaveSurface() {
        if (mWinAnimator.mSurfaceController == null) {
            // Don't bother if the surface controller is gone for any reason.
            return false;
        }

        if (!mWasVisibleBeforeClientHidden) {
            return false;
        }

        if ((mAttrs.flags & FLAG_SECURE) != 0) {
            // We don't save secure surfaces since their content shouldn't be shown while the app
            // isn't on screen and content might leak through during the transition animation with
            // saved surface.
            return false;
        }

        if (ActivityManager.isLowRamDeviceStatic()) {
            // Don't save surfaces on Svelte devices.
            return false;
        }

        Task task = getTask();
        if (task == null || task.inHomeStack()) {
            // Don't save surfaces for home stack apps. These usually resume and draw
            // first frame very fast. Saving surfaces are mostly a waste of memory.
            return false;
        }

        final AppWindowToken taskTop = task.getTopVisibleAppToken();
        if (taskTop != null && taskTop != mAppToken) {
            // Don't save if the window is not the topmost window.
            return false;
        }

        if (mResizedWhileGone) {
            // Somebody resized our window while we were gone for layout, which means that the
            // client got an old size, so we have an outdated surface here.
            return false;
        }

        if (DEBUG_DISABLE_SAVING_SURFACES) {
            return false;
        }

        return mAppToken.shouldSaveSurface();
    }

    static final Region sEmptyRegion = new Region();

    void destroyOrSaveSurface() {
        mSurfaceSaved = shouldSaveSurface();
        if (mSurfaceSaved) {
            if (DEBUG_APP_TRANSITIONS || DEBUG_ANIM) {
                Slog.v(TAG, "Saving surface: " + this);
            }
            // Previous user of the surface may have set a transparent region signaling a portion
            // doesn't need to be composited, so reset to default empty state.
            mSession.setTransparentRegion(mClient, sEmptyRegion);

            mWinAnimator.hide("saved surface");
            mWinAnimator.mDrawState = WindowStateAnimator.NO_SURFACE;
            setHasSurface(false);
            // The client should have disconnected at this point, but if it doesn't,
            // we need to make sure it's disconnected. Otherwise when we reuse the surface
            // the client can't reconnect to the buffer queue, and rendering will fail.
            if (mWinAnimator.mSurfaceController != null) {
                mWinAnimator.mSurfaceController.disconnectInTransaction();
            }
            mAnimatingWithSavedSurface = false;
        } else {
            mWinAnimator.destroySurfaceLocked();
        }
        // Clear animating flags now, since the surface is now gone. (Note this is true even
        // if the surface is saved, to outside world the surface is still NO_SURFACE.)
        mAnimatingExit = false;
    }

    void destroySavedSurface() {
        if (mSurfaceSaved) {
            if (DEBUG_APP_TRANSITIONS || DEBUG_ANIM) {
                Slog.v(TAG, "Destroying saved surface: " + this);
            }
            mWinAnimator.destroySurfaceLocked();
            mSurfaceSaved = false;
        }
        mWasVisibleBeforeClientHidden = false;
    }

    void restoreSavedSurface() {
        if (!mSurfaceSaved) {
            return;
        }

        // Sometimes we save surfaces due to layout invisible
        // directly after rotation occurs. However this means
        // the surface was never laid out in the new orientation.
        // We can only restore to the last rotation we were
        // laid out as visible in.
        if (mLastVisibleLayoutRotation != mService.mRotation) {
            destroySavedSurface();
            return;
        }
        mSurfaceSaved = false;

        if (mWinAnimator.mSurfaceController != null) {
            setHasSurface(true);
            mWinAnimator.mDrawState = WindowStateAnimator.READY_TO_SHOW;
            mAnimatingWithSavedSurface = true;

            if (DEBUG_APP_TRANSITIONS || DEBUG_ANIM) {
                Slog.v(TAG, "Restoring saved surface: " + this);
            }
        } else {
            // mSurfaceController shouldn't be null if mSurfaceSaved was still true at
            // this point. Even if we destroyed the saved surface because of rotation
            // or resize, mSurfaceSaved flag should have been cleared. So this is a wtf.
            Slog.wtf(TAG, "Failed to restore saved surface: surface gone! " + this);
        }
    }

    boolean canRestoreSurface() {
        return mWasVisibleBeforeClientHidden && mSurfaceSaved;
    }

    boolean hasSavedSurface() {
        return mSurfaceSaved;
    }

    void clearHasSavedSurface() {
        mSurfaceSaved = false;
        mAnimatingWithSavedSurface = false;
        if (mWasVisibleBeforeClientHidden) {
            mAppToken.destroySavedSurfaces();
        }
    }

    boolean clearAnimatingWithSavedSurface() {
        if (mAnimatingWithSavedSurface) {
            // App has drawn something to its windows, we're no longer animating with
            // the saved surfaces.
            if (DEBUG_ANIM) Slog.d(TAG,
                    "clearAnimatingWithSavedSurface(): win=" + this);
            mAnimatingWithSavedSurface = false;
            return true;
        }
        return false;
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

    @Override
    public boolean isDimming() {
        final DimLayer.DimLayerUser dimLayerUser = getDimLayerUser();
        return dimLayerUser != null && mDisplayContent != null &&
                mDisplayContent.mDimLayerController.isDimming(dimLayerUser, mWinAnimator);
    }

    public void setShowToOwnerOnlyLocked(boolean showToOwnerOnly) {
        mShowToOwnerOnly = showToOwnerOnly;
    }

    boolean isHiddenFromUserLocked() {
        // Attached windows are evaluated based on the window that they are attached to.
        WindowState win = this;
        while (win.isChildWindow()) {
            win = win.mAttachedWindow;
        }
        if (win.mAttrs.type < WindowManager.LayoutParams.FIRST_SYSTEM_WINDOW
                && win.mAppToken != null && win.mAppToken.showForAllUsers) {

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
                final Region givenTouchableRegion = mGivenTouchableRegion;
                outRegion.set(givenTouchableRegion);
                outRegion.translate(frame.left, frame.top);
                break;
            }
        }
        cropRegionToStackBoundsIfNeeded(outRegion);
    }

    void cropRegionToStackBoundsIfNeeded(Region region) {
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

    WindowList getWindowList() {
        final DisplayContent displayContent = getDisplayContent();
        return displayContent == null ? null : displayContent.getWindowList();
    }

    /**
     * Report a focus change.  Must be called with no locks held, and consistently
     * from the same serialized thread (such as dispatched from a handler).
     */
    public void reportFocusChangedSerialized(boolean focused, boolean inTouchMode) {
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

    /**
     * Update our current configurations, based on task configuration.
     *
     * @return A configuration suitable for sending to the client.
     */
    private Configuration updateConfiguration() {
        final boolean configChanged = isConfigChanged();
        getMergedConfig(mMergedConfiguration);
        mConfigHasChanged = false;
        if ((DEBUG_RESIZE || DEBUG_ORIENTATION || DEBUG_CONFIGURATION) && configChanged) {
            Slog.i(TAG, "Sending new config to window " + this + ": " +
                    " / mergedConfig=" + mMergedConfiguration);
        }
        return mMergedConfiguration;
    }

    private void getMergedConfig(Configuration outConfig) {
        if (mAppToken != null && mAppToken.mFrozenMergedConfig.size() > 0) {
            outConfig.setTo(mAppToken.mFrozenMergedConfig.peek());
            return;
        }
        final Task task = getTask();
        final Configuration overrideConfig = task != null
                ? task.mOverrideConfig
                : Configuration.EMPTY;
        final Configuration serviceConfig = mService.mCurConfiguration;
        outConfig.setTo(serviceConfig);
        if (overrideConfig != Configuration.EMPTY) {
            outConfig.updateFrom(overrideConfig);
        }
    }

    void reportResized() {
        Trace.traceBegin(TRACE_TAG_WINDOW_MANAGER, "wm.reportResized_" + getWindowTag());
        try {
            if (DEBUG_RESIZE || DEBUG_ORIENTATION) Slog.v(TAG, "Reporting new frame to " + this
                    + ": " + mCompatFrame);
            final Configuration newConfig = isConfigChanged() ? updateConfiguration() : null;
            if (DEBUG_ORIENTATION && mWinAnimator.mDrawState == WindowStateAnimator.DRAW_PENDING)
                Slog.i(TAG, "Resizing " + this + " WITH DRAW PENDING");

            final Rect frame = mFrame;
            final Rect overscanInsets = mLastOverscanInsets;
            final Rect contentInsets = mLastContentInsets;
            final Rect visibleInsets = mLastVisibleInsets;
            final Rect stableInsets = mLastStableInsets;
            final Rect outsets = mLastOutsets;
            final boolean reportDraw = mWinAnimator.mDrawState == WindowStateAnimator.DRAW_PENDING;
            if (mAttrs.type != WindowManager.LayoutParams.TYPE_APPLICATION_STARTING
                    && mClient instanceof IWindow.Stub) {
                // To prevent deadlock simulate one-way call if win.mClient is a local object.
                mService.mH.post(new Runnable() {
                    @Override
                    public void run() {
                        try {
                            dispatchResized(frame, overscanInsets, contentInsets, visibleInsets,
                                    stableInsets, outsets, reportDraw, newConfig);
                        } catch (RemoteException e) {
                            // Not a remote call, RemoteException won't be raised.
                        }
                    }
                });
            } else {
                dispatchResized(frame, overscanInsets, contentInsets, visibleInsets, stableInsets,
                        outsets, reportDraw, newConfig);
            }

            //TODO (multidisplay): Accessibility supported only for the default display.
            if (mService.mAccessibilityController != null
                    && getDisplayId() == Display.DEFAULT_DISPLAY) {
                mService.mAccessibilityController.onSomeWindowResizedOrMovedLocked();
            }

            mOverscanInsetsChanged = false;
            mContentInsetsChanged = false;
            mVisibleInsetsChanged = false;
            mStableInsetsChanged = false;
            mOutsetsChanged = false;
            mFrameSizeChanged = false;
            mResizedWhileNotDragResizingReported = true;
            mWinAnimator.mSurfaceResized = false;
        } catch (RemoteException e) {
            mOrientationChanging = false;
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
        if (StackId.useWindowFrameForBackdrop(getStackId()) || !resizing) {
            return frame;
        }
        DisplayInfo displayInfo = getDisplayInfo();
        mTmpRect.set(0, 0, displayInfo.logicalWidth, displayInfo.logicalHeight);
        return mTmpRect;
    }

    @Override
    public int getStackId() {
        final TaskStack stack = getStack();
        if (stack == null) {
            return INVALID_STACK_ID;
        }
        return stack.mStackId;
    }

    private void dispatchResized(Rect frame, Rect overscanInsets, Rect contentInsets,
            Rect visibleInsets, Rect stableInsets, Rect outsets, boolean reportDraw,
            Configuration newConfig) throws RemoteException {
        final boolean forceRelayout = isDragResizeChanged() || mResizedWhileNotDragResizing;

        mClient.resized(frame, overscanInsets, contentInsets, visibleInsets, stableInsets, outsets,
                reportDraw, newConfig, getBackdropFrame(frame),
                forceRelayout, mPolicy.isNavBarForcedShownLw(this));
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

    boolean inFreeformWorkspace() {
        final Task task = getTask();
        return task != null && task.inFreeformWorkspace();
    }

    @Override
    public boolean isInMultiWindowMode() {
        final Task task = getTask();
        return task != null && !task.isFullscreen();
    }

    boolean isDragResizeChanged() {
        return mDragResizing != computeDragResizing();
    }

    /**
     * @return Whether we reported a drag resize change to the application or not already.
     */
    boolean isDragResizingChangeReported() {
        return mDragResizingChangeReported;
    }

    /**
     * Resets the state whether we reported a drag resize change to the app.
     */
    void resetDragResizingChangeReported() {
        mDragResizingChangeReported = false;
    }

    /**
     * Set whether we got resized but drag resizing flag was false.
     * @see #isResizedWhileNotDragResizing().
     */
    void setResizedWhileNotDragResizing(boolean resizedWhileNotDragResizing) {
        mResizedWhileNotDragResizing = resizedWhileNotDragResizing;
        mResizedWhileNotDragResizingReported = !resizedWhileNotDragResizing;
    }

    /**
     * Indicates whether we got resized but drag resizing flag was false. In this case, we also
     * need to recreate the surface and defer surface bound updates in order to make sure the
     * buffer contents and the positioning/size stay in sync.
     */
    boolean isResizedWhileNotDragResizing() {
        return mResizedWhileNotDragResizing;
    }

    /**
     * @return Whether we reported "resize while not drag resizing" to the application.
     * @see #isResizedWhileNotDragResizing()
     */
    boolean isResizedWhileNotDragResizingReported() {
        return mResizedWhileNotDragResizingReported;
    }

    int getResizeMode() {
        return mResizeMode;
    }

    boolean computeDragResizing() {
        final Task task = getTask();
        if (task == null) {
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
        return (mDisplayContent.mDividerControllerLocked.isResizing()
                        || mAppToken != null && !mAppToken.mFrozenBounds.isEmpty()) &&
                !task.inFreeformWorkspace() && !isGoneForLayoutLw();

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
            mResizeMode = mDragResizing && mDisplayContent.mDividerControllerLocked.isResizing()
                    ? DRAG_RESIZE_MODE_DOCKED_DIVIDER
                    : DRAG_RESIZE_MODE_FREEFORM;
        }
    }

    boolean isDragResizing() {
        return mDragResizing;
    }

    boolean isDockedResizing() {
        return mDragResizing && getResizeMode() == DRAG_RESIZE_MODE_DOCKED_DIVIDER;
    }

    void dump(PrintWriter pw, String prefix, boolean dumpAll) {
        final TaskStack stack = getStack();
        pw.print(prefix); pw.print("mDisplayId="); pw.print(getDisplayId());
                if (stack != null) {
                    pw.print(" stackId="); pw.print(stack.mStackId);
                }
                if (mNotOnAppsDisplay) {
                    pw.print(" mNotOnAppsDisplay="); pw.print(mNotOnAppsDisplay);
                }
                pw.print(" mSession="); pw.print(mSession);
                pw.print(" mClient="); pw.println(mClient.asBinder());
        pw.print(prefix); pw.print("mOwnerUid="); pw.print(mOwnerUid);
                pw.print(" mShowToOwnerOnly="); pw.print(mShowToOwnerOnly);
                pw.print(" package="); pw.print(mAttrs.packageName);
                pw.print(" appop="); pw.println(AppOpsManager.opToName(mAppOp));
        pw.print(prefix); pw.print("mAttrs="); pw.println(mAttrs);
        pw.print(prefix); pw.print("Requested w="); pw.print(mRequestedWidth);
                pw.print(" h="); pw.print(mRequestedHeight);
                pw.print(" mLayoutSeq="); pw.println(mLayoutSeq);
        if (mRequestedWidth != mLastRequestedWidth || mRequestedHeight != mLastRequestedHeight) {
            pw.print(prefix); pw.print("LastRequested w="); pw.print(mLastRequestedWidth);
                    pw.print(" h="); pw.println(mLastRequestedHeight);
        }
        if (isChildWindow() || mLayoutAttached) {
            pw.print(prefix); pw.print("mAttachedWindow="); pw.print(mAttachedWindow);
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
                    pw.print((mTargetAppToken != null ?
                            mTargetAppToken.mAppAnimator.animLayerAdjustment
                          : (mAppToken != null ? mAppToken.mAppAnimator.animLayerAdjustment : 0)));
                    pw.print("="); pw.print(mWinAnimator.mAnimLayer);
                    pw.print(" mLastLayer="); pw.println(mWinAnimator.mLastLayer);
        }
        if (dumpAll) {
            pw.print(prefix); pw.print("mToken="); pw.println(mToken);
            pw.print(prefix); pw.print("mRootToken="); pw.println(mRootToken);
            if (mAppToken != null) {
                pw.print(prefix); pw.print("mAppToken="); pw.println(mAppToken);
                pw.print(prefix); pw.print(" isAnimatingWithSavedSurface()=");
                pw.print(isAnimatingWithSavedSurface());
                pw.print(" mAppDied=");pw.println(mAppDied);
            }
            if (mTargetAppToken != null) {
                pw.print(prefix); pw.print("mTargetAppToken="); pw.println(mTargetAppToken);
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
                || mAttachedHidden) {
            pw.print(prefix); pw.print("mPolicyVisibility=");
                    pw.print(mPolicyVisibility);
                    pw.print(" mPolicyVisibilityAfterAnim=");
                    pw.print(mPolicyVisibilityAfterAnim);
                    pw.print(" mAppOpVisibility=");
                    pw.print(mAppOpVisibility);
                    pw.print(" mAttachedHidden="); pw.println(mAttachedHidden);
        }
        if (!mRelayoutCalled || mLayoutNeeded) {
            pw.print(prefix); pw.print("mRelayoutCalled="); pw.print(mRelayoutCalled);
                    pw.print(" mLayoutNeeded="); pw.println(mLayoutNeeded);
        }
        if (mXOffset != 0 || mYOffset != 0) {
            pw.print(prefix); pw.print("Offsets x="); pw.print(mXOffset);
                    pw.print(" y="); pw.println(mYOffset);
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
            pw.print(prefix); pw.print("mMergedConfiguration="); pw.println(mMergedConfiguration);
        }
        pw.print(prefix); pw.print("mHasSurface="); pw.print(mHasSurface);
                pw.print(" mShownPosition="); mShownPosition.printShortString(pw);
                pw.print(" isReadyForDisplay()="); pw.print(isReadyForDisplay());
                pw.print(" hasSavedSurface()="); pw.print(hasSavedSurface());
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
                    pw.println();
            pw.print(prefix); pw.print("Lst insets: overscan=");
                    mLastOverscanInsets.printShortString(pw);
                    pw.print(" content="); mLastContentInsets.printShortString(pw);
                    pw.print(" visible="); mLastVisibleInsets.printShortString(pw);
                    pw.print(" stable="); mLastStableInsets.printShortString(pw);
                    pw.print(" physical="); mLastOutsets.printShortString(pw);
                    pw.print(" outset="); mLastOutsets.printShortString(pw);
                    pw.println();
        }
        pw.print(prefix); pw.print(mWinAnimator); pw.println(":");
        mWinAnimator.dump(pw, prefix + "  ", dumpAll);
        if (mAnimatingExit || mRemoveOnExit || mDestroying || mRemoved) {
            pw.print(prefix); pw.print("mAnimatingExit="); pw.print(mAnimatingExit);
                    pw.print(" mRemoveOnExit="); pw.print(mRemoveOnExit);
                    pw.print(" mDestroying="); pw.print(mDestroying);
                    pw.print(" mRemoved="); pw.println(mRemoved);
        }
        if (mOrientationChanging || mAppFreezing || mTurnOnScreen) {
            pw.print(prefix); pw.print("mOrientationChanging=");
                    pw.print(mOrientationChanging);
                    pw.print(" mAppFreezing="); pw.print(mAppFreezing);
                    pw.print(" mTurnOnScreen="); pw.println(mTurnOnScreen);
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
    }

    String makeInputChannelName() {
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
                    + " u" + UserHandle.getUserId(mSession.mUid)
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
        final boolean nonFullscreenTask = isInMultiWindowMode();
        final boolean noLimits = (mAttrs.flags & FLAG_LAYOUT_NO_LIMITS) != 0;

        // We need to fit it to the display if either
        // a) The task is fullscreen, or we don't have a task (we assume fullscreen for the taskless
        // windows)
        // b) If it's a child window, we also need to fit it to the display unless
        // FLAG_LAYOUT_NO_LIMITS is set. This is so we place Popup and similar windows on screen,
        // but SurfaceViews want to be always at a specific location so we don't fit it to the
        // display.
        final boolean fitToDisplay = (task == null || !nonFullscreenTask)
                || (isChildWindow() && !noLimits);
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

        if (nonFullscreenTask && !layoutInParentFrame()) {
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
        return mAttachedWindow != null;
    }

    boolean layoutInParentFrame() {
        return isChildWindow() && (mAttrs.privateFlags & PRIVATE_FLAG_LAYOUT_CHILD_WINDOW_IN_PARENT_FRAME) != 0;
    }

    void setReplacing(boolean animate) {
        if ((mAttrs.privateFlags & PRIVATE_FLAG_WILL_NOT_REPLACE_ON_RELAUNCH) != 0
                || mAttrs.type == TYPE_APPLICATION_STARTING) {
            // We don't set replacing on starting windows since they are added by window manager and
            // not the client so won't be replaced by the client.
            return;
        }

        mWillReplaceWindow = true;
        mReplacingWindow = null;
        mAnimateReplacingWindow = animate;
    }

    void resetReplacing() {
        mWillReplaceWindow = false;
        mReplacingWindow = null;
        mAnimateReplacingWindow = false;
    }

    void requestUpdateWallpaperIfNeeded() {
        if (mDisplayContent != null && (mAttrs.flags & FLAG_SHOW_WALLPAPER) != 0) {
            mDisplayContent.pendingLayoutChanges |= FINISH_LAYOUT_REDO_WALLPAPER;
            mDisplayContent.layoutNeeded = true;
            mService.mWindowPlacerLocked.requestTraversal();
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

    void transferDimToReplacement() {
        final DimLayer.DimLayerUser dimLayerUser = getDimLayerUser();
        if (dimLayerUser != null && mDisplayContent != null) {
            mDisplayContent.mDimLayerController.applyDim(dimLayerUser,
                    mReplacingWindow.mWinAnimator,
                    (mAttrs.flags & FLAG_DIM_BEHIND) != 0 ? true : false);
        }
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
        return isChildWindow() || mAttrs.type == TYPE_APPLICATION
                || mAttrs.type == TYPE_DRAWN_APPLICATION;
    }

    public int getRotationAnimationHint() {
        if (mAppToken != null) {
            return mAppToken.mRotationAnimationHint;
        } else {
            return -1;
        }
    }

    public boolean isRtl() {
        return mMergedConfiguration.getLayoutDirection() == View.LAYOUT_DIRECTION_RTL;
    }
}
