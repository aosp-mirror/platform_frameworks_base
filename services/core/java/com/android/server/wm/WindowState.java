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

import static com.android.server.wm.WindowManagerService.DEBUG_CONFIGURATION;
import static com.android.server.wm.WindowManagerService.DEBUG_LAYOUT;
import static com.android.server.wm.WindowManagerService.DEBUG_ORIENTATION;
import static com.android.server.wm.WindowManagerService.DEBUG_RESIZE;
import static com.android.server.wm.WindowManagerService.DEBUG_VISIBILITY;

import static android.view.WindowManager.LayoutParams.FIRST_SUB_WINDOW;
import static android.view.WindowManager.LayoutParams.PRIVATE_FLAG_KEYGUARD;
import static android.view.WindowManager.LayoutParams.PRIVATE_FLAG_COMPATIBLE_WINDOW;
import static android.view.WindowManager.LayoutParams.LAST_SUB_WINDOW;
import static android.view.WindowManager.LayoutParams.PRIVATE_FLAG_NO_MOVE_ANIMATION;
import static android.view.WindowManager.LayoutParams.TYPE_INPUT_METHOD;
import static android.view.WindowManager.LayoutParams.TYPE_INPUT_METHOD_DIALOG;
import static android.view.WindowManager.LayoutParams.TYPE_APPLICATION_STARTING;
import static android.view.WindowManager.LayoutParams.TYPE_WALLPAPER;

import android.app.AppOpsManager;
import android.os.Debug;
import android.os.RemoteCallbackList;
import android.os.SystemClock;
import android.util.TimeUtils;
import android.view.Display;
import android.view.IWindowFocusObserver;
import android.view.IWindowId;
import com.android.server.input.InputWindowHandle;

import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Matrix;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Region;
import android.os.IBinder;
import android.os.RemoteException;
import android.os.UserHandle;
import android.util.Slog;
import android.view.DisplayInfo;
import android.view.Gravity;
import android.view.IApplicationToken;
import android.view.IWindow;
import android.view.InputChannel;
import android.view.View;
import android.view.ViewTreeObserver;
import android.view.WindowManager;
import android.view.WindowManagerPolicy;

import java.io.PrintWriter;
import java.util.ArrayList;

class WindowList extends ArrayList<WindowState> {
}

/**
 * A window in the window manager.
 */
final class WindowState implements WindowManagerPolicy.WindowState {
    static final String TAG = "WindowState";

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

    Configuration mConfiguration = null;
    // Sticky answer to isConfigChanged(), remains true until new Configuration is assigned.
    // Used only on {@link #TYPE_KEYGUARD}.
    private boolean mConfigHasChanged;

    /**
     * Actual frame shown on-screen (may be modified by animation).  These
     * are in the screen's coordinate space (WITH the compatibility scale
     * applied).
     */
    final RectF mShownFrame = new RectF();

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

    /**
     * This is rectangle of the window's surface that is not covered by
     * system decorations.
     */
    final Rect mSystemDecorRect = new Rect();
    final Rect mLastSystemDecorRect = new Rect();

    // Current transformation being applied.
    float mGlobalScale=1;
    float mInvGlobalScale=1;
    float mHScale=1, mVScale=1;
    float mLastHScale=1, mLastVScale=1;
    final Matrix mTmpMatrix = new Matrix();

    // "Real" frame that the application sees, in display coordinate space.
    final Rect mFrame = new Rect();
    final Rect mLastFrame = new Rect();
    // Frame that is scaled to the application's coordinate space when in
    // screen size compatibility mode.
    final Rect mCompatFrame = new Rect();

    final Rect mContainingFrame = new Rect();

    final Rect mParentFrame = new Rect();

    // The entire screen area of the device.
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

    /**
     * If the application has called relayout() with changes that can
     * impact its window's size, we need to perform a layout pass on it
     * even if it is not currently visible for layout.  This is set
     * when in that case until the layout is done.
     */
    boolean mLayoutNeeded;

    /** Currently running an exit animation? */
    boolean mExiting;

    /** Currently on the mDestroySurface list? */
    boolean mDestroying;

    /** Completely remove from window manager after exit animation? */
    boolean mRemoveOnExit;

    /**
     * Set when the orientation is changing and this window has not yet
     * been updated for the new orientation.
     */
    boolean mOrientationChanging;

    /**
     * How long we last kept the screen frozen.
     */
    int mLastFreezeDuration;

    /** Is this window now (or just being) removed? */
    boolean mRemoved;

    /**
     * Temp for keeping track of windows that have been removed when
     * rebuilding window list.
     */
    boolean mRebuilding;

    // Input channel and input window handle used by the input dispatcher.
    final InputWindowHandle mInputWindowHandle;
    InputChannel mInputChannel;

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

    /** When true this window is at the top of the screen and should be layed out to extend under
     * the status bar */
    boolean mUnderStatusBar = true;

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
            if (WindowManagerService.DEBUG_ADD_REMOVE) Slog.v(TAG, "Adding " + this + " to " + mAttachedWindow);

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
        while (appWin.mAttachedWindow != null) {
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

    @Override
    public void computeFrameLw(Rect pf, Rect df, Rect of, Rect cf, Rect vf, Rect dcf, Rect sf) {
        mHaveFrame = true;

        TaskStack stack = mAppToken != null ? getStack() : null;
        if (stack != null && !stack.isFullscreen()) {
            getStackBounds(stack, mContainingFrame);
            if (mUnderStatusBar) {
                mContainingFrame.top = pf.top;
            }
        } else {
            mContainingFrame.set(pf);
        }

        mDisplayFrame.set(df);

        final int pw = mContainingFrame.width();
        final int ph = mContainingFrame.height();

        int w,h;
        if ((mAttrs.flags & WindowManager.LayoutParams.FLAG_SCALED) != 0) {
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
            if (mAttrs.width == WindowManager.LayoutParams.MATCH_PARENT) {
                w = pw;
            } else if (mEnforceSizeCompat) {
                w = (int)(mRequestedWidth * mGlobalScale + .5f);
            } else {
                w = mRequestedWidth;
            }
            if (mAttrs.height == WindowManager.LayoutParams.MATCH_PARENT) {
                h = ph;
            } else if (mEnforceSizeCompat) {
                h = (int)(mRequestedHeight * mGlobalScale + .5f);
            } else {
                h = mRequestedHeight;
            }
        }

        if (!mParentFrame.equals(pf)) {
            //Slog.i(TAG, "Window " + this + " content frame from " + mParentFrame
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

        final int fw = mFrame.width();
        final int fh = mFrame.height();

        //System.out.println("In: w=" + w + " h=" + h + " container=" +
        //                   container + " x=" + mAttrs.x + " y=" + mAttrs.y);

        float x, y;
        if (mEnforceSizeCompat) {
            x = mAttrs.x * mGlobalScale;
            y = mAttrs.y * mGlobalScale;
        } else {
            x = mAttrs.x;
            y = mAttrs.y;
        }

        Gravity.apply(mAttrs.gravity, w, h, mContainingFrame,
                (int) (x + mAttrs.horizontalMargin * pw),
                (int) (y + mAttrs.verticalMargin * ph), mFrame);

        //System.out.println("Out: " + mFrame);

        // Now make sure the window fits in the overall display.
        Gravity.applyDisplay(mAttrs.gravity, df, mFrame);

        // Make sure the content and visible frames are inside of the
        // final window frame.
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

        mOverscanInsets.set(Math.max(mOverscanFrame.left - mFrame.left, 0),
                Math.max(mOverscanFrame.top - mFrame.top, 0),
                Math.max(mFrame.right - mOverscanFrame.right, 0),
                Math.max(mFrame.bottom - mOverscanFrame.bottom, 0));

        mContentInsets.set(mContentFrame.left - mFrame.left,
                mContentFrame.top - mFrame.top,
                mFrame.right - mContentFrame.right,
                mFrame.bottom - mContentFrame.bottom);

        mVisibleInsets.set(mVisibleFrame.left - mFrame.left,
                mVisibleFrame.top - mFrame.top,
                mFrame.right - mVisibleFrame.right,
                mFrame.bottom - mVisibleFrame.bottom);

        mStableInsets.set(Math.max(mStableFrame.left - mFrame.left, 0),
                Math.max(mStableFrame.top - mFrame.top, 0),
                Math.max(mFrame.right - mStableFrame.right, 0),
                Math.max(mFrame.bottom - mStableFrame.bottom, 0));

        mCompatFrame.set(mFrame);
        if (mEnforceSizeCompat) {
            // If there is a size compatibility scale being applied to the
            // window, we need to apply this to its insets so that they are
            // reported to the app in its coordinate space.
            mOverscanInsets.scale(mInvGlobalScale);
            mContentInsets.scale(mInvGlobalScale);
            mVisibleInsets.scale(mInvGlobalScale);
            mStableInsets.scale(mInvGlobalScale);

            // Also the scaled frame that we report to the app needs to be
            // adjusted to be in its coordinate space.
            mCompatFrame.scale(mInvGlobalScale);
        }

        if (mIsWallpaper && (fw != mFrame.width() || fh != mFrame.height())) {
            final DisplayContent displayContent = getDisplayContent();
            if (displayContent != null) {
                final DisplayInfo displayInfo = displayContent.getDisplayInfo();
                mService.updateWallpaperOffsetLocked(this,
                        displayInfo.logicalWidth, displayInfo.logicalHeight, false);
            }
        }

        if (DEBUG_LAYOUT || WindowManagerService.localLOGV) Slog.v(TAG,
                "Resolving (mRequestedWidth="
                + mRequestedWidth + ", mRequestedheight="
                + mRequestedHeight + ") to" + " (pw=" + pw + ", ph=" + ph
                + "): frame=" + mFrame.toShortString()
                + " ci=" + mContentInsets.toShortString()
                + " vi=" + mVisibleInsets.toShortString()
                + " vi=" + mStableInsets.toShortString());
    }

    @Override
    public Rect getFrameLw() {
        return mFrame;
    }

    @Override
    public RectF getShownFrameLw() {
        return mShownFrame;
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
    public IApplicationToken getAppToken() {
        return mAppToken != null ? mAppToken.appToken : null;
    }

    @Override
    public boolean isVoiceInteraction() {
        return mAppToken != null ? mAppToken.voiceInteraction : false;
    }

    boolean setInsetsChanged() {
        mOverscanInsetsChanged |= !mLastOverscanInsets.equals(mOverscanInsets);
        mContentInsetsChanged |= !mLastContentInsets.equals(mContentInsets);
        mVisibleInsetsChanged |= !mLastVisibleInsets.equals(mVisibleInsets);
        mStableInsetsChanged |= !mLastStableInsets.equals(mStableInsets);
        return mOverscanInsetsChanged || mContentInsetsChanged || mVisibleInsetsChanged;
    }

    public DisplayContent getDisplayContent() {
        if (mAppToken == null || mNotOnAppsDisplay) {
            return mDisplayContent;
        }
        final TaskStack stack = getStack();
        return stack == null ? mDisplayContent : stack.getDisplayContent();
    }

    public int getDisplayId() {
        final DisplayContent displayContent = getDisplayContent();
        if (displayContent == null) {
            return -1;
        }
        return displayContent.getDisplayId();
    }

    TaskStack getStack() {
        AppWindowToken wtoken = mAppToken == null ? mService.mFocusedApp : mAppToken;
        if (wtoken != null) {
            Task task = mService.mTaskIdToTask.get(wtoken.groupId);
            if (task != null) {
                if (task.mStack != null) {
                    return task.mStack;
                }
                Slog.e(TAG, "getStack: mStack null for task=" + task);
            } else {
                Slog.e(TAG, "getStack: " + this + " couldn't find taskId=" + wtoken.groupId
                    + " Callers=" + Debug.getCallers(4));
            }
        }
        return mDisplayContent.getHomeStack();
    }

    void getStackBounds(Rect bounds) {
        getStackBounds(getStack(), bounds);
    }

    private void getStackBounds(TaskStack stack, Rect bounds) {
        if (stack != null) {
            stack.getBounds(bounds);
            return;
        }
        bounds.set(mFrame);
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
     * Is this window visible?  It is not visible if there is no
     * surface, or we are in the process of running an exit animation
     * that will remove the surface, or its app token has been hidden.
     */
    @Override
    public boolean isVisibleLw() {
        final AppWindowToken atoken = mAppToken;
        return mHasSurface && mPolicyVisibility && !mAttachedHidden
                && (atoken == null || !atoken.hiddenRequested)
                && !mExiting && !mDestroying;
    }

    /**
     * Like {@link #isVisibleLw}, but also counts a window that is currently
     * "hidden" behind the keyguard as visible.  This allows us to apply
     * things like window flags that impact the keyguard.
     * XXX I am starting to think we need to have ANOTHER visibility flag
     * for this "hidden behind keyguard" state rather than overloading
     * mPolicyVisibility.  Ungh.
     */
    @Override
    public boolean isVisibleOrBehindKeyguardLw() {
        if (mRootToken.waitingToShow &&
                mService.mAppTransition.isTransitionSet()) {
            return false;
        }
        final AppWindowToken atoken = mAppToken;
        final boolean animating = atoken != null
                ? (atoken.mAppAnimator.animation != null) : false;
        return mHasSurface && !mDestroying && !mExiting
                && (atoken == null ? mPolicyVisibility : !atoken.hiddenRequested)
                && ((!mAttachedHidden && mViewVisibility == View.VISIBLE
                                && !mRootToken.hidden)
                        || mWinAnimator.mAnimation != null || animating);
    }

    /**
     * Is this window visible, ignoring its app token?  It is not visible
     * if there is no surface, or we are in the process of running an exit animation
     * that will remove the surface.
     */
    public boolean isWinVisibleLw() {
        final AppWindowToken atoken = mAppToken;
        return mHasSurface && mPolicyVisibility && !mAttachedHidden
                && (atoken == null || !atoken.hiddenRequested || atoken.mAppAnimator.animating)
                && !mExiting && !mDestroying;
    }

    /**
     * The same as isVisible(), but follows the current hidden state of
     * the associated app token, not the pending requested hidden state.
     */
    boolean isVisibleNow() {
        return mHasSurface && mPolicyVisibility && !mAttachedHidden
                && (!mRootToken.hidden || mAttrs.type == TYPE_APPLICATION_STARTING)
                && !mExiting && !mDestroying;
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
                && !mExiting && !mDestroying;
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
     * Like isOnScreen(), but we don't return true if the window is part
     * of a transition that has not yet been started.
     */
    boolean isReadyForDisplay() {
        if (mRootToken.waitingToShow &&
                mService.mAppTransition.isTransitionSet()) {
            return false;
        }
        return mHasSurface && mPolicyVisibility && !mDestroying
                && ((!mAttachedHidden && mViewVisibility == View.VISIBLE
                                && !mRootToken.hidden)
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
                && ((!mAttachedHidden && mViewVisibility == View.VISIBLE
                                && !mRootToken.hidden)
                        || mWinAnimator.mAnimation != null
                        || ((atoken != null) && (atoken.mAppAnimator.animation != null)
                                && !mWinAnimator.isDummyAnimation()));
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
                || (atoken != null && (atoken.hiddenRequested || atoken.hidden))
                || mAttachedHidden
                || (mExiting && !isAnimatingLw())
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
        return (mAttrs.format == PixelFormat.OPAQUE
                        || mAttrs.type == TYPE_WALLPAPER)
                && isDrawnLw() && mWinAnimator.mAnimation == null
                && (mAppToken == null || mAppToken.mAppAnimator.animation == null);
    }

    /**
     * Return whether this window is wanting to have a translation
     * animation applied to it for an in-progress move.  (Only makes
     * sense to call from performLayoutAndPlaceSurfacesLockedInner().)
     */
    boolean shouldAnimateMove() {
        return mContentChanged && !mExiting && !mWinAnimator.mLastHidden && mService.okToDisplay()
                && (mFrame.top != mLastFrame.top
                        || mFrame.left != mLastFrame.left)
                && (mAttrs.privateFlags&PRIVATE_FLAG_NO_MOVE_ANIMATION) == 0
                && (mAttachedWindow == null || !mAttachedWindow.shouldAnimateMove());
    }

    boolean isFullscreen(int screenWidth, int screenHeight) {
        return mFrame.left <= 0 && mFrame.top <= 0 &&
                mFrame.right >= screenWidth && mFrame.bottom >= screenHeight;
    }

    boolean isConfigChanged() {
        boolean configChanged = mConfiguration != mService.mCurConfiguration
                && (mConfiguration == null
                        || (mConfiguration.diff(mService.mCurConfiguration) != 0));

        if ((mAttrs.privateFlags & PRIVATE_FLAG_KEYGUARD) != 0) {
            // Retain configuration changed status until resetConfiguration called.
            mConfigHasChanged |= configChanged;
            configChanged = mConfigHasChanged;
        }

        return configChanged;
    }

    void removeLocked() {
        disposeInputChannel();

        if (mAttachedWindow != null) {
            if (WindowManagerService.DEBUG_ADD_REMOVE) Slog.v(TAG, "Removing " + this + " from " + mAttachedWindow);
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

    void setConfiguration(final Configuration newConfig) {
        mConfiguration = newConfig;
        mConfigHasChanged = false;
    }

    void setInputChannel(InputChannel inputChannel) {
        if (mInputChannel != null) {
            throw new IllegalStateException("Window already has an input channel.");
        }

        mInputChannel = inputChannel;
        mInputWindowHandle.inputChannel = inputChannel;
    }

    void disposeInputChannel() {
        if (mInputChannel != null) {
            mService.mInputManager.unregisterInputChannel(mInputChannel);

            mInputChannel.dispose();
            mInputChannel = null;
        }

        mInputWindowHandle.inputChannel = null;
    }

    private class DeathRecipient implements IBinder.DeathRecipient {
        @Override
        public void binderDied() {
            try {
                synchronized(mService.mWindowMap) {
                    WindowState win = mService.windowForClientLocked(mSession, mClient, false);
                    Slog.i(TAG, "WIN DEATH: " + win);
                    if (win != null) {
                        mService.removeWindowLocked(mSession, win);
                    } else if (mHasSurface) {
                        Slog.e(TAG, "!!! LEAK !!! Window removed but surface still valid.");
                        mService.removeWindowLocked(mSession, WindowState.this);
                    }
                }
            } catch (IllegalArgumentException ex) {
                // This will happen if the window has already been
                // removed.
            }
        }
    }

    /**
     * @return true if this window desires key events.
     */
    public final boolean canReceiveKeys() {
        return isVisibleOrAdding()
                && (mViewVisibility == View.VISIBLE)
                && ((mAttrs.flags & WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE) == 0);
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
                if (WindowManagerService.DEBUG_FOCUS_LIGHT) Slog.i(TAG,
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

    @Override
    public boolean isAlive() {
        return mClient.asBinder().isBinderAlive();
    }

    boolean isClosing() {
        return mExiting || (mService.mClosingApps.contains(mAppToken));
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

    public void setShowToOwnerOnlyLocked(boolean showToOwnerOnly) {
        mShowToOwnerOnly = showToOwnerOnly;
    }

    boolean isHiddenFromUserLocked() {
        // Attached windows are evaluated based on the window that they are attached to.
        WindowState win = this;
        while (win.mAttachedWindow != null) {
            win = win.mAttachedWindow;
        }
        if (win.mAttrs.type < WindowManager.LayoutParams.FIRST_SYSTEM_WINDOW
                && win.mAppToken != null && win.mAppToken.showWhenLocked) {
            // Save some cycles by not calling getDisplayInfo unless it is an application
            // window intended for all users.
            final DisplayContent displayContent = win.getDisplayContent();
            if (displayContent == null) {
                return true;
            }
            final DisplayInfo displayInfo = displayContent.getDisplayInfo();
            if (win.mFrame.left <= 0 && win.mFrame.top <= 0
                    && win.mFrame.right >= displayInfo.appWidth
                    && win.mFrame.bottom >= displayInfo.appHeight) {
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

    public void getTouchableRegion(Region outRegion) {
        final Rect frame = mFrame;
        switch (mTouchableInsets) {
            default:
            case ViewTreeObserver.InternalInsetsInfo.TOUCHABLE_INSETS_FRAME:
                outRegion.set(frame);
                break;
            case ViewTreeObserver.InternalInsetsInfo.TOUCHABLE_INSETS_CONTENT:
                applyInsets(outRegion, frame, mGivenContentInsets);
                break;
            case ViewTreeObserver.InternalInsetsInfo.TOUCHABLE_INSETS_VISIBLE:
                applyInsets(outRegion, frame, mGivenVisibleInsets);
                break;
            case ViewTreeObserver.InternalInsetsInfo.TOUCHABLE_INSETS_REGION: {
                final Region givenTouchableRegion = mGivenTouchableRegion;
                outRegion.set(givenTouchableRegion);
                outRegion.translate(frame.left, frame.top);
                break;
            }
        }
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

    void reportResized() {
        try {
            if (DEBUG_RESIZE || DEBUG_ORIENTATION) Slog.v(TAG, "Reporting new frame to " + this
                    + ": " + mCompatFrame);
            boolean configChanged = isConfigChanged();
            if ((DEBUG_RESIZE || DEBUG_ORIENTATION || DEBUG_CONFIGURATION) && configChanged) {
                Slog.i(TAG, "Sending new config to window " + this + ": "
                        + mWinAnimator.mSurfaceW + "x" + mWinAnimator.mSurfaceH
                        + " / " + mService.mCurConfiguration);
            }
            setConfiguration(mService.mCurConfiguration);
            if (DEBUG_ORIENTATION && mWinAnimator.mDrawState == WindowStateAnimator.DRAW_PENDING)
                Slog.i(TAG, "Resizing " + this + " WITH DRAW PENDING");

            final Rect frame = mFrame;
            final Rect overscanInsets = mLastOverscanInsets;
            final Rect contentInsets = mLastContentInsets;
            final Rect visibleInsets = mLastVisibleInsets;
            final Rect stableInsets = mLastStableInsets;
            final boolean reportDraw = mWinAnimator.mDrawState == WindowStateAnimator.DRAW_PENDING;
            final Configuration newConfig = configChanged ? mConfiguration : null;
            if (mAttrs.type != WindowManager.LayoutParams.TYPE_APPLICATION_STARTING
                    && mClient instanceof IWindow.Stub) {
                // To prevent deadlock simulate one-way call if win.mClient is a local object.
                mService.mH.post(new Runnable() {
                    @Override
                    public void run() {
                        try {
                            mClient.resized(frame, overscanInsets, contentInsets,
                                    visibleInsets, stableInsets,  reportDraw, newConfig);
                        } catch (RemoteException e) {
                            // Not a remote call, RemoteException won't be raised.
                        }
                    }
                });
            } else {
                mClient.resized(frame, overscanInsets, contentInsets, visibleInsets, stableInsets,
                        reportDraw, newConfig);
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
            mWinAnimator.mSurfaceResized = false;
        } catch (RemoteException e) {
            mOrientationChanging = false;
            mLastFreezeDuration = (int)(SystemClock.elapsedRealtime()
                    - mService.mDisplayFreezeTime);
        }
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

    void dump(PrintWriter pw, String prefix, boolean dumpAll) {
        pw.print(prefix); pw.print("mDisplayId="); pw.print(getDisplayId());
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
        if (mAttachedWindow != null || mLayoutAttached) {
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
            pw.print(prefix); pw.print("mConfiguration="); pw.println(mConfiguration);
        }
        pw.print(prefix); pw.print("mHasSurface="); pw.print(mHasSurface);
                pw.print(" mShownFrame="); mShownFrame.printShortString(pw);
                pw.print(" isReadyForDisplay()="); pw.println(isReadyForDisplay());
        if (dumpAll) {
            pw.print(prefix); pw.print("mFrame="); mFrame.printShortString(pw);
                    pw.print(" last="); mLastFrame.printShortString(pw);
                    pw.println();
            pw.print(prefix); pw.print("mSystemDecorRect="); mSystemDecorRect.printShortString(pw);
                    pw.print(" last="); mLastSystemDecorRect.printShortString(pw);
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
            pw.print(prefix); pw.print("Cur insets: overscan=");
                    mOverscanInsets.printShortString(pw);
                    pw.print(" content="); mContentInsets.printShortString(pw);
                    pw.print(" visible="); mVisibleInsets.printShortString(pw);
                    pw.print(" stable="); mStableInsets.printShortString(pw);
                    pw.println();
            pw.print(prefix); pw.print("Lst insets: overscan=");
                    mLastOverscanInsets.printShortString(pw);
                    pw.print(" content="); mLastContentInsets.printShortString(pw);
                    pw.print(" visible="); mLastVisibleInsets.printShortString(pw);
                    pw.print(" stable="); mLastStableInsets.printShortString(pw);
                    pw.println();
        }
        pw.print(prefix); pw.print(mWinAnimator); pw.println(":");
        mWinAnimator.dump(pw, prefix + "  ", dumpAll);
        if (mExiting || mRemoveOnExit || mDestroying || mRemoved) {
            pw.print(prefix); pw.print("mExiting="); pw.print(mExiting);
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
    }

    String makeInputChannelName() {
        return Integer.toHexString(System.identityHashCode(this))
            + " " + mAttrs.getTitle();
    }

    @Override
    public String toString() {
        CharSequence title = mAttrs.getTitle();
        if (title == null || title.length() <= 0) {
            title = mAttrs.packageName;
        }
        if (mStringNameCache == null || mLastTitle != title || mWasExiting != mExiting) {
            mLastTitle = title;
            mWasExiting = mExiting;
            mStringNameCache = "Window{" + Integer.toHexString(System.identityHashCode(this))
                    + " u" + UserHandle.getUserId(mSession.mUid)
                    + " " + mLastTitle + (mExiting ? " EXITING}" : "}");
        }
        return mStringNameCache;
    }
}
