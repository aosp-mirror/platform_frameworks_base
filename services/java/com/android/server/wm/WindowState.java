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

import static android.view.WindowManager.LayoutParams.FIRST_SUB_WINDOW;
import static android.view.WindowManager.LayoutParams.FLAG_COMPATIBLE_WINDOW;
import static android.view.WindowManager.LayoutParams.LAST_SUB_WINDOW;
import static android.view.WindowManager.LayoutParams.TYPE_APPLICATION_STARTING;
import static android.view.WindowManager.LayoutParams.TYPE_INPUT_METHOD;
import static android.view.WindowManager.LayoutParams.TYPE_INPUT_METHOD_DIALOG;
import static android.view.WindowManager.LayoutParams.TYPE_WALLPAPER;

import com.android.server.wm.WindowManagerService.H;

import android.content.res.Configuration;
import android.graphics.Matrix;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Region;
import android.os.IBinder;
import android.os.RemoteException;
import android.util.Slog;
import android.view.Gravity;
import android.view.IApplicationToken;
import android.view.IWindow;
import android.view.InputChannel;
import android.view.Surface;
import android.view.View;
import android.view.ViewTreeObserver;
import android.view.WindowManager;
import android.view.WindowManagerPolicy;
import android.view.WindowManager.LayoutParams;
import android.view.animation.Animation;
import android.view.animation.Transformation;

import java.io.PrintWriter;
import java.util.ArrayList;

/**
 * A window in the window manager.
 */
final class WindowState implements WindowManagerPolicy.WindowState {
    static final boolean DEBUG_VISIBILITY = WindowManagerService.DEBUG_VISIBILITY;
    static final boolean SHOW_TRANSACTIONS = WindowManagerService.SHOW_TRANSACTIONS;
    static final boolean SHOW_SURFACE_ALLOC = WindowManagerService.SHOW_SURFACE_ALLOC;

    final WindowManagerService mService;
    final Session mSession;
    final IWindow mClient;
    WindowToken mToken;
    WindowToken mRootToken;
    AppWindowToken mAppToken;
    AppWindowToken mTargetAppToken;
    final WindowManager.LayoutParams mAttrs = new WindowManager.LayoutParams();
    final DeathRecipient mDeathRecipient;
    final WindowState mAttachedWindow;
    final ArrayList<WindowState> mChildWindows = new ArrayList<WindowState>();
    final int mBaseLayer;
    final int mSubLayer;
    final boolean mLayoutAttached;
    final boolean mIsImWindow;
    final boolean mIsWallpaper;
    final boolean mIsFloatingLayer;
    boolean mEnforceSizeCompat;
    int mViewVisibility;
    boolean mPolicyVisibility = true;
    boolean mPolicyVisibilityAfterAnim = true;
    boolean mAppFreezing;
    Surface mSurface;
    boolean mReportDestroySurface;
    boolean mSurfacePendingDestroy;
    boolean mAttachedHidden;    // is our parent window hidden?
    boolean mLastHidden;        // was this window last hidden?
    boolean mWallpaperVisible;  // for wallpaper, what was last vis report?

    /**
     * The window size that was requested by the application.  These are in
     * the application's coordinate space (without compatibility scale applied).
     */
    int mRequestedWidth;
    int mRequestedHeight;

    int mLayer;
    int mAnimLayer;
    int mLastLayer;
    boolean mHaveFrame;
    boolean mObscured;
    boolean mTurnOnScreen;

    int mLayoutSeq = -1;
    
    Configuration mConfiguration = null;
    
    /**
     * Actual frame shown on-screen (may be modified by animation).  These
     * are in the screen's coordinate space (WITH the compatibility scale
     * applied).
     */
    final RectF mShownFrame = new RectF();

    /**
     * Set when we have changed the size of the surface, to know that
     * we must tell them application to resize (and thus redraw itself).
     */
    boolean mSurfaceResized;
    
    /**
     * Insets that determine the actually visible area.  These are in the application's
     * coordinate space (without compatibility scale applied).
     */
    final Rect mVisibleInsets = new Rect();
    final Rect mLastVisibleInsets = new Rect();
    boolean mVisibleInsetsChanged;

    /**
     * Insets that are covered by system windows.  These are in the application's
     * coordinate space (without compatibility scale applied).
     */
    final Rect mContentInsets = new Rect();
    final Rect mLastContentInsets = new Rect();
    boolean mContentInsetsChanged;

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
    boolean mHaveMatrix;
    float mGlobalScale=1;
    float mInvGlobalScale=1;
    float mDsDx=1, mDtDx=0, mDsDy=0, mDtDy=1;
    float mLastDsDx=1, mLastDtDx=0, mLastDsDy=0, mLastDtDy=1;
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
    final Rect mDisplayFrame = new Rect();
    final Rect mContentFrame = new Rect();
    final Rect mParentFrame = new Rect();
    final Rect mVisibleFrame = new Rect();

    boolean mContentChanged;

    float mShownAlpha = 1;
    float mAlpha = 1;
    float mLastAlpha = 1;

    // Set to true if, when the window gets displayed, it should perform
    // an enter animation.
    boolean mEnterAnimationPending;

    // Currently running animation.
    boolean mAnimating;
    boolean mLocalAnimating;
    Animation mAnimation;
    boolean mAnimationIsEntrance;
    boolean mHasTransformation;
    boolean mHasLocalTransformation;
    final Transformation mTransformation = new Transformation();

    // If a window showing a wallpaper: the requested offset for the
    // wallpaper; if a wallpaper window: the currently applied offset.
    float mWallpaperX = -1;
    float mWallpaperY = -1;

    // If a window showing a wallpaper: what fraction of the offset
    // range corresponds to a full virtual screen.
    float mWallpaperXStep = -1;
    float mWallpaperYStep = -1;

    // Wallpaper windows: pixels offset based on above variables.
    int mXOffset;
    int mYOffset;

    // This is set after IWindowSession.relayout() has been called at
    // least once for the window.  It allows us to detect the situation
    // where we don't yet have a surface, but should have one soon, so
    // we can give the window focus before waiting for the relayout.
    boolean mRelayoutCalled;

    // This is set after the Surface has been created but before the
    // window has been drawn.  During this time the surface is hidden.
    boolean mDrawPending;

    // This is set after the window has finished drawing for the first
    // time but before its surface is shown.  The surface will be
    // displayed when the next layout is run.
    boolean mCommitDrawPending;

    // This is set during the time after the window's drawing has been
    // committed, and before its surface is actually shown.  It is used
    // to delay showing the surface until all windows in a token are ready
    // to be shown.
    boolean mReadyToShow;

    // Set when the window has been shown in the screen the first time.
    boolean mHasDrawn;

    // Currently running an exit animation?
    boolean mExiting;

    // Currently on the mDestroySurface list?
    boolean mDestroying;

    // Completely remove from window manager after exit animation?
    boolean mRemoveOnExit;

    // Set when the orientation is changing and this window has not yet
    // been updated for the new orientation.
    boolean mOrientationChanging;

    // Is this window now (or just being) removed?
    boolean mRemoved;

    // Temp for keeping track of windows that have been removed when
    // rebuilding window list.
    boolean mRebuilding;

    // For debugging, this is the last information given to the surface flinger.
    boolean mSurfaceShown;
    float mSurfaceX, mSurfaceY, mSurfaceW, mSurfaceH;
    int mSurfaceLayer;
    float mSurfaceAlpha;
    
    // Input channel and input window handle used by the input dispatcher.
    final InputWindowHandle mInputWindowHandle;
    InputChannel mInputChannel;
    
    // Used to improve performance of toString()
    String mStringNameCache;
    CharSequence mLastTitle;
    boolean mWasPaused;

    WindowState(WindowManagerService service, Session s, IWindow c, WindowToken token,
           WindowState attachedWindow, WindowManager.LayoutParams a,
           int viewVisibility) {
        mService = service;
        mSession = s;
        mClient = c;
        mToken = token;
        mAttrs.copyFrom(a);
        mViewVisibility = viewVisibility;
        DeathRecipient deathRecipient = new DeathRecipient();
        mAlpha = a.alpha;
        mEnforceSizeCompat = (mAttrs.flags & FLAG_COMPATIBLE_WINDOW) != 0;
        if (WindowManagerService.localLOGV) Slog.v(
            WindowManagerService.TAG, "Window " + this + " client=" + c.asBinder()
            + " token=" + token + " (" + mAttrs.token + ")");
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
            return;
        }
        mDeathRecipient = deathRecipient;

        if ((mAttrs.type >= FIRST_SUB_WINDOW &&
                mAttrs.type <= LAST_SUB_WINDOW)) {
            // The multiplier here is to reserve space for multiple
            // windows in the same type layer.
            mBaseLayer = mService.mPolicy.windowTypeToLayerLw(
                    attachedWindow.mAttrs.type) * WindowManagerService.TYPE_LAYER_MULTIPLIER
                    + WindowManagerService.TYPE_LAYER_OFFSET;
            mSubLayer = mService.mPolicy.subWindowTypeToLayerLw(a.type);
            mAttachedWindow = attachedWindow;
            if (WindowManagerService.DEBUG_ADD_REMOVE) Slog.v(WindowManagerService.TAG, "Adding " + this + " to " + mAttachedWindow);
            mAttachedWindow.mChildWindows.add(this);
            mLayoutAttached = mAttrs.type !=
                    WindowManager.LayoutParams.TYPE_APPLICATION_ATTACHED_DIALOG;
            mIsImWindow = attachedWindow.mAttrs.type == TYPE_INPUT_METHOD
                    || attachedWindow.mAttrs.type == TYPE_INPUT_METHOD_DIALOG;
            mIsWallpaper = attachedWindow.mAttrs.type == TYPE_WALLPAPER;
            mIsFloatingLayer = mIsImWindow || mIsWallpaper;
        } else {
            // The multiplier here is to reserve space for multiple
            // windows in the same type layer.
            mBaseLayer = mService.mPolicy.windowTypeToLayerLw(a.type)
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
            appWin = mAttachedWindow;
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

        mSurface = null;
        mRequestedWidth = 0;
        mRequestedHeight = 0;
        mXOffset = 0;
        mYOffset = 0;
        mLayer = 0;
        mAnimLayer = 0;
        mLastLayer = 0;
        mInputWindowHandle = new InputWindowHandle(
                mAppToken != null ? mAppToken.mInputApplicationHandle : null, this);
    }

    void attach() {
        if (WindowManagerService.localLOGV) Slog.v(
            WindowManagerService.TAG, "Attaching " + this + " token=" + mToken
            + ", list=" + mToken.windows);
        mSession.windowAddedLocked();
    }

    public void computeFrameLw(Rect pf, Rect df, Rect cf, Rect vf) {
        mHaveFrame = true;

        final Rect container = mContainingFrame;
        container.set(pf);

        final Rect display = mDisplayFrame;
        display.set(df);

        final int pw = container.right - container.left;
        final int ph = container.bottom - container.top;

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

        final Rect content = mContentFrame;
        content.set(cf);

        final Rect visible = mVisibleFrame;
        visible.set(vf);

        final Rect frame = mFrame;
        final int fw = frame.width();
        final int fh = frame.height();

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

        Gravity.apply(mAttrs.gravity, w, h, container,
                (int) (x + mAttrs.horizontalMargin * pw),
                (int) (y + mAttrs.verticalMargin * ph), frame);

        //System.out.println("Out: " + mFrame);

        // Now make sure the window fits in the overall display.
        Gravity.applyDisplay(mAttrs.gravity, df, frame);

        // Make sure the content and visible frames are inside of the
        // final window frame.
        if (content.left < frame.left) content.left = frame.left;
        if (content.top < frame.top) content.top = frame.top;
        if (content.right > frame.right) content.right = frame.right;
        if (content.bottom > frame.bottom) content.bottom = frame.bottom;
        if (visible.left < frame.left) visible.left = frame.left;
        if (visible.top < frame.top) visible.top = frame.top;
        if (visible.right > frame.right) visible.right = frame.right;
        if (visible.bottom > frame.bottom) visible.bottom = frame.bottom;

        final Rect contentInsets = mContentInsets;
        contentInsets.left = content.left-frame.left;
        contentInsets.top = content.top-frame.top;
        contentInsets.right = frame.right-content.right;
        contentInsets.bottom = frame.bottom-content.bottom;

        final Rect visibleInsets = mVisibleInsets;
        visibleInsets.left = visible.left-frame.left;
        visibleInsets.top = visible.top-frame.top;
        visibleInsets.right = frame.right-visible.right;
        visibleInsets.bottom = frame.bottom-visible.bottom;

        mCompatFrame.set(frame);
        if (mEnforceSizeCompat) {
            // If there is a size compatibility scale being applied to the
            // window, we need to apply this to its insets so that they are
            // reported to the app in its coordinate space.
            contentInsets.scale(mInvGlobalScale);
            visibleInsets.scale(mInvGlobalScale);

            // Also the scaled frame that we report to the app needs to be
            // adjusted to be in its coordinate space.
            mCompatFrame.scale(mInvGlobalScale);
        }

        if (mIsWallpaper && (fw != frame.width() || fh != frame.height())) {
            mService.updateWallpaperOffsetLocked(this,
                    mService.mAppDisplayWidth, mService.mAppDisplayHeight, false);
        }

        if (WindowManagerService.localLOGV) {
            //if ("com.google.android.youtube".equals(mAttrs.packageName)
            //        && mAttrs.type == WindowManager.LayoutParams.TYPE_APPLICATION_PANEL) {
                Slog.v(WindowManagerService.TAG, "Resolving (mRequestedWidth="
                        + mRequestedWidth + ", mRequestedheight="
                        + mRequestedHeight + ") to" + " (pw=" + pw + ", ph=" + ph
                        + "): frame=" + mFrame.toShortString()
                        + " ci=" + contentInsets.toShortString()
                        + " vi=" + visibleInsets.toShortString());
            //}
        }
    }

    public Rect getFrameLw() {
        return mFrame;
    }

    public RectF getShownFrameLw() {
        return mShownFrame;
    }

    public Rect getDisplayFrameLw() {
        return mDisplayFrame;
    }

    public Rect getContentFrameLw() {
        return mContentFrame;
    }

    public Rect getVisibleFrameLw() {
        return mVisibleFrame;
    }

    public boolean getGivenInsetsPendingLw() {
        return mGivenInsetsPending;
    }

    public Rect getGivenContentInsetsLw() {
        return mGivenContentInsets;
    }

    public Rect getGivenVisibleInsetsLw() {
        return mGivenVisibleInsets;
    }

    public WindowManager.LayoutParams getAttrs() {
        return mAttrs;
    }

    public int getSurfaceLayer() {
        return mLayer;
    }

    public IApplicationToken getAppToken() {
        return mAppToken != null ? mAppToken.appToken : null;
    }
    
    public long getInputDispatchingTimeoutNanos() {
        return mAppToken != null
                ? mAppToken.inputDispatchingTimeoutNanos
                : WindowManagerService.DEFAULT_INPUT_DISPATCHING_TIMEOUT_NANOS;
    }

    public boolean hasAppShownWindows() {
        return mAppToken != null ? mAppToken.firstWindowDrawn : false;
    }

    public void setAnimation(Animation anim) {
        if (WindowManagerService.localLOGV) Slog.v(
            WindowManagerService.TAG, "Setting animation in " + this + ": " + anim);
        mAnimating = false;
        mLocalAnimating = false;
        mAnimation = anim;
        mAnimation.restrictDuration(WindowManagerService.MAX_ANIMATION_DURATION);
        mAnimation.scaleCurrentDuration(mService.mWindowAnimationScale);
    }

    public void clearAnimation() {
        if (mAnimation != null) {
            mAnimating = true;
            mLocalAnimating = false;
            mAnimation.cancel();
            mAnimation = null;
        }
    }

    Surface createSurfaceLocked() {
        if (mSurface == null) {
            mReportDestroySurface = false;
            mSurfacePendingDestroy = false;
            mDrawPending = true;
            mCommitDrawPending = false;
            mReadyToShow = false;
            if (mAppToken != null) {
                mAppToken.allDrawn = false;
            }

            int flags = 0;

            if ((mAttrs.flags&WindowManager.LayoutParams.FLAG_SECURE) != 0) {
                flags |= Surface.SECURE;
            }
            if (DEBUG_VISIBILITY) Slog.v(
                WindowManagerService.TAG, "Creating surface in session "
                + mSession.mSurfaceSession + " window " + this
                + " w=" + mCompatFrame.width()
                + " h=" + mCompatFrame.height() + " format="
                + mAttrs.format + " flags=" + flags);

            int w = mCompatFrame.width();
            int h = mCompatFrame.height();
            if ((mAttrs.flags & LayoutParams.FLAG_SCALED) != 0) {
                // for a scaled surface, we always want the requested
                // size.
                w = mRequestedWidth;
                h = mRequestedHeight;
            }

            // Something is wrong and SurfaceFlinger will not like this,
            // try to revert to sane values
            if (w <= 0) w = 1;
            if (h <= 0) h = 1;

            mSurfaceShown = false;
            mSurfaceLayer = 0;
            mSurfaceAlpha = 1;
            mSurfaceX = 0;
            mSurfaceY = 0;
            mSurfaceW = w;
            mSurfaceH = h;
            try {
                final boolean isHwAccelerated = (mAttrs.flags &
                        WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED) != 0;
                final int format = isHwAccelerated ? PixelFormat.TRANSLUCENT : mAttrs.format;
                if (isHwAccelerated && mAttrs.format == PixelFormat.OPAQUE) {
                    flags |= Surface.OPAQUE;
                }
                mSurface = new Surface(
                        mSession.mSurfaceSession, mSession.mPid,
                        mAttrs.getTitle().toString(),
                        0, w, h, format, flags);
                if (SHOW_TRANSACTIONS || SHOW_SURFACE_ALLOC) Slog.i(WindowManagerService.TAG,
                        "  CREATE SURFACE "
                        + mSurface + " IN SESSION "
                        + mSession.mSurfaceSession
                        + ": pid=" + mSession.mPid + " format="
                        + mAttrs.format + " flags=0x"
                        + Integer.toHexString(flags)
                        + " / " + this);
            } catch (Surface.OutOfResourcesException e) {
                Slog.w(WindowManagerService.TAG, "OutOfResourcesException creating surface");
                mService.reclaimSomeSurfaceMemoryLocked(this, "create", true);
                return null;
            } catch (Exception e) {
                Slog.e(WindowManagerService.TAG, "Exception creating surface", e);
                return null;
            }

            if (WindowManagerService.localLOGV) Slog.v(
                WindowManagerService.TAG, "Got surface: " + mSurface
                + ", set left=" + mFrame.left + " top=" + mFrame.top
                + ", animLayer=" + mAnimLayer);
            if (SHOW_TRANSACTIONS) {
                Slog.i(WindowManagerService.TAG, ">>> OPEN TRANSACTION createSurfaceLocked");
                WindowManagerService.logSurface(this, "CREATE pos=(" + mFrame.left
                        + "," + mFrame.top + ") (" +
                        mCompatFrame.width() + "x" + mCompatFrame.height() + "), layer=" +
                        mAnimLayer + " HIDE", null);
            }
            Surface.openTransaction();
            try {
                try {
                    mSurfaceX = mFrame.left + mXOffset;
                    mSurfaceY = mFrame.top + mYOffset;
                    mSurface.setPosition(mSurfaceX, mSurfaceY);
                    mSurfaceLayer = mAnimLayer;
                    mSurface.setLayer(mAnimLayer);
                    mSurfaceShown = false;
                    mSurface.hide();
                    if ((mAttrs.flags&WindowManager.LayoutParams.FLAG_DITHER) != 0) {
                        if (SHOW_TRANSACTIONS) WindowManagerService.logSurface(this, "DITHER", null);
                        mSurface.setFlags(Surface.SURFACE_DITHER,
                                Surface.SURFACE_DITHER);
                    }
                } catch (RuntimeException e) {
                    Slog.w(WindowManagerService.TAG, "Error creating surface in " + w, e);
                    mService.reclaimSomeSurfaceMemoryLocked(this, "create-init", true);
                }
                mLastHidden = true;
            } finally {
                Surface.closeTransaction();
                if (SHOW_TRANSACTIONS) Slog.i(WindowManagerService.TAG, "<<< CLOSE TRANSACTION createSurfaceLocked");
            }
            if (WindowManagerService.localLOGV) Slog.v(
                    WindowManagerService.TAG, "Created surface " + this);
        }
        return mSurface;
    }

    void destroySurfaceLocked() {
        if (mAppToken != null && this == mAppToken.startingWindow) {
            mAppToken.startingDisplayed = false;
        }

        if (mSurface != null) {
            mDrawPending = false;
            mCommitDrawPending = false;
            mReadyToShow = false;

            int i = mChildWindows.size();
            while (i > 0) {
                i--;
                WindowState c = mChildWindows.get(i);
                c.mAttachedHidden = true;
            }

            if (mReportDestroySurface) {
                mReportDestroySurface = false;
                mSurfacePendingDestroy = true;
                try {
                    mClient.dispatchGetNewSurface();
                    // We'll really destroy on the next time around.
                    return;
                } catch (RemoteException e) {
                }
            }

            try {
                if (DEBUG_VISIBILITY) {
                    RuntimeException e = null;
                    if (!WindowManagerService.HIDE_STACK_CRAWLS) {
                        e = new RuntimeException();
                        e.fillInStackTrace();
                    }
                    Slog.w(WindowManagerService.TAG, "Window " + this + " destroying surface "
                            + mSurface + ", session " + mSession, e);
                }
                if (SHOW_TRANSACTIONS || SHOW_SURFACE_ALLOC) {
                    RuntimeException e = null;
                    if (!WindowManagerService.HIDE_STACK_CRAWLS) {
                        e = new RuntimeException();
                        e.fillInStackTrace();
                    }
                    WindowManagerService.logSurface(this, "DESTROY", e);
                }
                mSurface.destroy();
            } catch (RuntimeException e) {
                Slog.w(WindowManagerService.TAG, "Exception thrown when destroying Window " + this
                    + " surface " + mSurface + " session " + mSession
                    + ": " + e.toString());
            }

            mSurfaceShown = false;
            mSurface = null;
        }
    }

    boolean finishDrawingLocked() {
        if (mDrawPending) {
            if (SHOW_TRANSACTIONS || WindowManagerService.DEBUG_ORIENTATION) Slog.v(
                WindowManagerService.TAG, "finishDrawingLocked: " + mSurface);
            mCommitDrawPending = true;
            mDrawPending = false;
            return true;
        }
        return false;
    }

    // This must be called while inside a transaction.
    boolean commitFinishDrawingLocked(long currentTime) {
        //Slog.i(TAG, "commitFinishDrawingLocked: " + mSurface);
        if (!mCommitDrawPending) {
            return false;
        }
        mCommitDrawPending = false;
        mReadyToShow = true;
        final boolean starting = mAttrs.type == TYPE_APPLICATION_STARTING;
        final AppWindowToken atoken = mAppToken;
        if (atoken == null || atoken.allDrawn || starting) {
            performShowLocked();
        }
        return true;
    }

    // This must be called while inside a transaction.
    boolean performShowLocked() {
        if (DEBUG_VISIBILITY) {
            RuntimeException e = null;
            if (!WindowManagerService.HIDE_STACK_CRAWLS) {
                e = new RuntimeException();
                e.fillInStackTrace();
            }
            Slog.v(WindowManagerService.TAG, "performShow on " + this
                    + ": readyToShow=" + mReadyToShow + " readyForDisplay=" + isReadyForDisplay()
                    + " starting=" + (mAttrs.type == TYPE_APPLICATION_STARTING), e);
        }
        if (mReadyToShow && isReadyForDisplay()) {
            if (SHOW_TRANSACTIONS || WindowManagerService.DEBUG_ORIENTATION) WindowManagerService.logSurface(this,
                    "SHOW (performShowLocked)", null);
            if (DEBUG_VISIBILITY) Slog.v(WindowManagerService.TAG, "Showing " + this
                    + " during animation: policyVis=" + mPolicyVisibility
                    + " attHidden=" + mAttachedHidden
                    + " tok.hiddenRequested="
                    + (mAppToken != null ? mAppToken.hiddenRequested : false)
                    + " tok.hidden="
                    + (mAppToken != null ? mAppToken.hidden : false)
                    + " animating=" + mAnimating
                    + " tok animating="
                    + (mAppToken != null ? mAppToken.animating : false));
            if (!mService.showSurfaceRobustlyLocked(this)) {
                return false;
            }
            mLastAlpha = -1;
            mHasDrawn = true;
            mLastHidden = false;
            mReadyToShow = false;
            mService.enableScreenIfNeededLocked();

            mService.applyEnterAnimationLocked(this);

            int i = mChildWindows.size();
            while (i > 0) {
                i--;
                WindowState c = mChildWindows.get(i);
                if (c.mAttachedHidden) {
                    c.mAttachedHidden = false;
                    if (c.mSurface != null) {
                        c.performShowLocked();
                        // It hadn't been shown, which means layout not
                        // performed on it, so now we want to make sure to
                        // do a layout.  If called from within the transaction
                        // loop, this will cause it to restart with a new
                        // layout.
                        mService.mLayoutNeeded = true;
                    }
                }
            }

            if (mAttrs.type != TYPE_APPLICATION_STARTING
                    && mAppToken != null) {
                mAppToken.firstWindowDrawn = true;

                if (mAppToken.startingData != null) {
                    if (WindowManagerService.DEBUG_STARTING_WINDOW || WindowManagerService.DEBUG_ANIM) Slog.v(WindowManagerService.TAG,
                            "Finish starting " + mToken
                            + ": first real window is shown, no animation");
                    // If this initial window is animating, stop it -- we
                    // will do an animation to reveal it from behind the
                    // starting window, so there is no need for it to also
                    // be doing its own stuff.
                    if (mAnimation != null) {
                        mAnimation.cancel();
                        mAnimation = null;
                        // Make sure we clean up the animation.
                        mAnimating = true;
                    }
                    mService.mFinishedStarting.add(mAppToken);
                    mService.mH.sendEmptyMessage(H.FINISHED_STARTING);
                }
                mAppToken.updateReportedVisibilityLocked();
            }
        }
        return true;
    }

    // This must be called while inside a transaction.  Returns true if
    // there is more animation to run.
    boolean stepAnimationLocked(long currentTime, int dw, int dh) {
        if (!mService.mDisplayFrozen && mService.mPolicy.isScreenOn()) {
            // We will run animations as long as the display isn't frozen.

            if (!mDrawPending && !mCommitDrawPending && mAnimation != null) {
                mHasTransformation = true;
                mHasLocalTransformation = true;
                if (!mLocalAnimating) {
                    if (WindowManagerService.DEBUG_ANIM) Slog.v(
                        WindowManagerService.TAG, "Starting animation in " + this +
                        " @ " + currentTime + ": ww=" + mFrame.width() +
                        " wh=" + mFrame.height() +
                        " dw=" + dw + " dh=" + dh + " scale=" + mService.mWindowAnimationScale);
                    mAnimation.initialize(mFrame.width(), mFrame.height(), dw, dh);
                    mAnimation.setStartTime(currentTime);
                    mLocalAnimating = true;
                    mAnimating = true;
                }
                mTransformation.clear();
                final boolean more = mAnimation.getTransformation(
                    currentTime, mTransformation);
                if (WindowManagerService.DEBUG_ANIM) Slog.v(
                    WindowManagerService.TAG, "Stepped animation in " + this +
                    ": more=" + more + ", xform=" + mTransformation);
                if (more) {
                    // we're not done!
                    return true;
                }
                if (WindowManagerService.DEBUG_ANIM) Slog.v(
                    WindowManagerService.TAG, "Finished animation in " + this +
                    " @ " + currentTime);

                if (mAnimation != null) {
                    mAnimation.cancel();
                    mAnimation = null;
                }
                //WindowManagerService.this.dump();
            }
            mHasLocalTransformation = false;
            if ((!mLocalAnimating || mAnimationIsEntrance) && mAppToken != null
                    && mAppToken.animation != null) {
                // When our app token is animating, we kind-of pretend like
                // we are as well.  Note the mLocalAnimating mAnimationIsEntrance
                // part of this check means that we will only do this if
                // our window is not currently exiting, or it is not
                // locally animating itself.  The idea being that one that
                // is exiting and doing a local animation should be removed
                // once that animation is done.
                mAnimating = true;
                mHasTransformation = true;
                mTransformation.clear();
                return false;
            } else if (mHasTransformation) {
                // Little trick to get through the path below to act like
                // we have finished an animation.
                mAnimating = true;
            } else if (isAnimating()) {
                mAnimating = true;
            }
        } else if (mAnimation != null) {
            // If the display is frozen, and there is a pending animation,
            // clear it and make sure we run the cleanup code.
            mAnimating = true;
            mLocalAnimating = true;
            mAnimation.cancel();
            mAnimation = null;
        }

        if (!mAnimating && !mLocalAnimating) {
            return false;
        }

        if (WindowManagerService.DEBUG_ANIM) Slog.v(
            WindowManagerService.TAG, "Animation done in " + this + ": exiting=" + mExiting
            + ", reportedVisible="
            + (mAppToken != null ? mAppToken.reportedVisible : false));

        mAnimating = false;
        mLocalAnimating = false;
        if (mAnimation != null) {
            mAnimation.cancel();
            mAnimation = null;
        }
        mAnimLayer = mLayer;
        if (mIsImWindow) {
            mAnimLayer += mService.mInputMethodAnimLayerAdjustment;
        } else if (mIsWallpaper) {
            mAnimLayer += mService.mWallpaperAnimLayerAdjustment;
        }
        if (WindowManagerService.DEBUG_LAYERS) Slog.v(WindowManagerService.TAG, "Stepping win " + this
                + " anim layer: " + mAnimLayer);
        mHasTransformation = false;
        mHasLocalTransformation = false;
        if (mPolicyVisibility != mPolicyVisibilityAfterAnim) {
            if (DEBUG_VISIBILITY) {
                Slog.v(WindowManagerService.TAG, "Policy visibility changing after anim in " + this + ": "
                        + mPolicyVisibilityAfterAnim);
            }
            mPolicyVisibility = mPolicyVisibilityAfterAnim;
            mService.mLayoutNeeded = true;
            if (!mPolicyVisibility) {
                if (mService.mCurrentFocus == this) {
                    mService.mFocusMayChange = true;
                }
                // Window is no longer visible -- make sure if we were waiting
                // for it to be displayed before enabling the display, that
                // we allow the display to be enabled now.
                mService.enableScreenIfNeededLocked();
            }
        }
        mTransformation.clear();
        if (mHasDrawn
                && mAttrs.type == WindowManager.LayoutParams.TYPE_APPLICATION_STARTING
                && mAppToken != null
                && mAppToken.firstWindowDrawn
                && mAppToken.startingData != null) {
            if (WindowManagerService.DEBUG_STARTING_WINDOW) Slog.v(WindowManagerService.TAG, "Finish starting "
                    + mToken + ": first real window done animating");
            mService.mFinishedStarting.add(mAppToken);
            mService.mH.sendEmptyMessage(H.FINISHED_STARTING);
        }

        finishExit();

        if (mAppToken != null) {
            mAppToken.updateReportedVisibilityLocked();
        }

        return false;
    }

    void finishExit() {
        if (WindowManagerService.DEBUG_ANIM) Slog.v(
                WindowManagerService.TAG, "finishExit in " + this
                + ": exiting=" + mExiting
                + " remove=" + mRemoveOnExit
                + " windowAnimating=" + isWindowAnimating());

        final int N = mChildWindows.size();
        for (int i=0; i<N; i++) {
            mChildWindows.get(i).finishExit();
        }

        if (!mExiting) {
            return;
        }

        if (isWindowAnimating()) {
            return;
        }

        if (WindowManagerService.localLOGV) Slog.v(
                WindowManagerService.TAG, "Exit animation finished in " + this
                + ": remove=" + mRemoveOnExit);
        if (mSurface != null) {
            mService.mDestroySurface.add(this);
            mDestroying = true;
            if (SHOW_TRANSACTIONS) WindowManagerService.logSurface(this, "HIDE (finishExit)", null);
            mSurfaceShown = false;
            try {
                mSurface.hide();
            } catch (RuntimeException e) {
                Slog.w(WindowManagerService.TAG, "Error hiding surface in " + this, e);
            }
            mLastHidden = true;
        }
        mExiting = false;
        if (mRemoveOnExit) {
            mService.mPendingRemove.add(this);
            mRemoveOnExit = false;
        }
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

    void computeShownFrameLocked() {
        final boolean selfTransformation = mHasLocalTransformation;
        Transformation attachedTransformation =
                (mAttachedWindow != null && mAttachedWindow.mHasLocalTransformation)
                ? mAttachedWindow.mTransformation : null;
        Transformation appTransformation =
                (mAppToken != null && mAppToken.hasTransformation)
                ? mAppToken.transformation : null;

        // Wallpapers are animated based on the "real" window they
        // are currently targeting.
        if (mAttrs.type == TYPE_WALLPAPER && mService.mLowerWallpaperTarget == null
                && mService.mWallpaperTarget != null) {
            if (mService.mWallpaperTarget.mHasLocalTransformation &&
                    mService.mWallpaperTarget.mAnimation != null &&
                    !mService.mWallpaperTarget.mAnimation.getDetachWallpaper()) {
                attachedTransformation = mService.mWallpaperTarget.mTransformation;
                if (WindowManagerService.DEBUG_WALLPAPER && attachedTransformation != null) {
                    Slog.v(WindowManagerService.TAG, "WP target attached xform: " + attachedTransformation);
                }
            }
            if (mService.mWallpaperTarget.mAppToken != null &&
                    mService.mWallpaperTarget.mAppToken.hasTransformation &&
                    mService.mWallpaperTarget.mAppToken.animation != null &&
                    !mService.mWallpaperTarget.mAppToken.animation.getDetachWallpaper()) {
                appTransformation = mService.mWallpaperTarget.mAppToken.transformation;
                if (WindowManagerService.DEBUG_WALLPAPER && appTransformation != null) {
                    Slog.v(WindowManagerService.TAG, "WP target app xform: " + appTransformation);
                }
            }
        }

        final boolean screenAnimation = mService.mScreenRotationAnimation != null
                && mService.mScreenRotationAnimation.isAnimating();
        if (selfTransformation || attachedTransformation != null
                || appTransformation != null || screenAnimation) {
            // cache often used attributes locally
            final Rect frame = mFrame;
            final float tmpFloats[] = mService.mTmpFloats;
            final Matrix tmpMatrix = mTmpMatrix;

            // Compute the desired transformation.
            tmpMatrix.setTranslate(0, 0);
            tmpMatrix.postScale(mGlobalScale, mGlobalScale);
            if (selfTransformation) {
                tmpMatrix.postConcat(mTransformation.getMatrix());
            }
            tmpMatrix.postTranslate(frame.left + mXOffset, frame.top + mYOffset);
            if (attachedTransformation != null) {
                tmpMatrix.postConcat(attachedTransformation.getMatrix());
            }
            if (appTransformation != null) {
                tmpMatrix.postConcat(appTransformation.getMatrix());
            }
            if (screenAnimation) {
                tmpMatrix.postConcat(
                        mService.mScreenRotationAnimation.getEnterTransformation().getMatrix());
            }

            // "convert" it into SurfaceFlinger's format
            // (a 2x2 matrix + an offset)
            // Here we must not transform the position of the surface
            // since it is already included in the transformation.
            //Slog.i(TAG, "Transform: " + matrix);

            mHaveMatrix = true;
            tmpMatrix.getValues(tmpFloats);
            mDsDx = tmpFloats[Matrix.MSCALE_X];
            mDtDx = tmpFloats[Matrix.MSKEW_Y];
            mDsDy = tmpFloats[Matrix.MSKEW_X];
            mDtDy = tmpFloats[Matrix.MSCALE_Y];
            float x = tmpFloats[Matrix.MTRANS_X];
            float y = tmpFloats[Matrix.MTRANS_Y];
            int w = frame.width();
            int h = frame.height();
            mShownFrame.set(x, y, x+w, y+h);

            // Now set the alpha...  but because our current hardware
            // can't do alpha transformation on a non-opaque surface,
            // turn it off if we are running an animation that is also
            // transforming since it is more important to have that
            // animation be smooth.
            mShownAlpha = mAlpha;
            if (!mService.mLimitedAlphaCompositing
                    || (!PixelFormat.formatHasAlpha(mAttrs.format)
                    || (isIdentityMatrix(mDsDx, mDtDx, mDsDy, mDtDy)
                            && x == frame.left && y == frame.top))) {
                //Slog.i(TAG, "Applying alpha transform");
                if (selfTransformation) {
                    mShownAlpha *= mTransformation.getAlpha();
                }
                if (attachedTransformation != null) {
                    mShownAlpha *= attachedTransformation.getAlpha();
                }
                if (appTransformation != null) {
                    mShownAlpha *= appTransformation.getAlpha();
                }
                if (screenAnimation) {
                    mShownAlpha *=
                        mService.mScreenRotationAnimation.getEnterTransformation().getAlpha();
                }
            } else {
                //Slog.i(TAG, "Not applying alpha transform");
            }

            if (WindowManagerService.localLOGV) Slog.v(
                WindowManagerService.TAG, "Continuing animation in " + this +
                ": " + mShownFrame +
                ", alpha=" + mTransformation.getAlpha());
            return;
        }

        mShownFrame.set(mFrame);
        if (mXOffset != 0 || mYOffset != 0) {
            mShownFrame.offset(mXOffset, mYOffset);
        }
        mShownAlpha = mAlpha;
        mHaveMatrix = false;
        mDsDx = mGlobalScale;
        mDtDx = 0;
        mDsDy = 0;
        mDtDy = mGlobalScale;
    }

    /**
     * Is this window visible?  It is not visible if there is no
     * surface, or we are in the process of running an exit animation
     * that will remove the surface, or its app token has been hidden.
     */
    public boolean isVisibleLw() {
        final AppWindowToken atoken = mAppToken;
        return mSurface != null && mPolicyVisibility && !mAttachedHidden
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
    public boolean isVisibleOrBehindKeyguardLw() {
        final AppWindowToken atoken = mAppToken;
        return mSurface != null && !mAttachedHidden
                && (atoken == null ? mPolicyVisibility : !atoken.hiddenRequested)
                && !mDrawPending && !mCommitDrawPending
                && !mExiting && !mDestroying;
    }

    /**
     * Is this window visible, ignoring its app token?  It is not visible
     * if there is no surface, or we are in the process of running an exit animation
     * that will remove the surface.
     */
    public boolean isWinVisibleLw() {
        final AppWindowToken atoken = mAppToken;
        return mSurface != null && mPolicyVisibility && !mAttachedHidden
                && (atoken == null || !atoken.hiddenRequested || atoken.animating)
                && !mExiting && !mDestroying;
    }

    /**
     * The same as isVisible(), but follows the current hidden state of
     * the associated app token, not the pending requested hidden state.
     */
    boolean isVisibleNow() {
        return mSurface != null && mPolicyVisibility && !mAttachedHidden
                && !mRootToken.hidden && !mExiting && !mDestroying;
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
        return ((mSurface != null && !mReportDestroySurface)
                        || (!mRelayoutCalled && mViewVisibility == View.VISIBLE))
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
        final AppWindowToken atoken = mAppToken;
        if (atoken != null) {
            return mSurface != null && mPolicyVisibility && !mDestroying
                    && ((!mAttachedHidden && !atoken.hiddenRequested)
                            || mAnimation != null || atoken.animation != null);
        } else {
            return mSurface != null && mPolicyVisibility && !mDestroying
                    && (!mAttachedHidden || mAnimation != null);
        }
    }

    /**
     * Like isOnScreen(), but we don't return true if the window is part
     * of a transition that has not yet been started.
     */
    boolean isReadyForDisplay() {
        if (mRootToken.waitingToShow &&
                mService.mNextAppTransition != WindowManagerPolicy.TRANSIT_UNSET) {
            return false;
        }
        final AppWindowToken atoken = mAppToken;
        final boolean animating = atoken != null
                ? (atoken.animation != null) : false;
        return mSurface != null && mPolicyVisibility && !mDestroying
                && ((!mAttachedHidden && mViewVisibility == View.VISIBLE
                                && !mRootToken.hidden)
                        || mAnimation != null || animating);
    }

    /** Is the window or its container currently animating? */
    boolean isAnimating() {
        final WindowState attached = mAttachedWindow;
        final AppWindowToken atoken = mAppToken;
        return mAnimation != null
                || (attached != null && attached.mAnimation != null)
                || (atoken != null &&
                        (atoken.animation != null
                                || atoken.inPendingTransaction));
    }

    /** Is this window currently animating? */
    boolean isWindowAnimating() {
        return mAnimation != null;
    }

    /**
     * Like isOnScreen, but returns false if the surface hasn't yet
     * been drawn.
     */
    public boolean isDisplayedLw() {
        final AppWindowToken atoken = mAppToken;
        return mSurface != null && mPolicyVisibility && !mDestroying
            && !mDrawPending && !mCommitDrawPending
            && ((!mAttachedHidden &&
                    (atoken == null || !atoken.hiddenRequested))
                    || mAnimating);
    }

    /**
     * Returns true if the window has a surface that it has drawn a
     * complete UI in to.
     */
    public boolean isDrawnLw() {
        final AppWindowToken atoken = mAppToken;
        return mSurface != null && !mDestroying
            && !mDrawPending && !mCommitDrawPending;
    }

    /**
     * Return true if the window is opaque and fully drawn.  This indicates
     * it may obscure windows behind it.
     */
    boolean isOpaqueDrawn() {
        return (mAttrs.format == PixelFormat.OPAQUE
                        || mAttrs.type == TYPE_WALLPAPER)
                && mSurface != null && mAnimation == null
                && (mAppToken == null || mAppToken.animation == null)
                && !mDrawPending && !mCommitDrawPending;
    }

    /**
     * Return whether this window is wanting to have a translation
     * animation applied to it for an in-progress move.  (Only makes
     * sense to call from performLayoutAndPlaceSurfacesLockedInner().)
     */
    boolean shouldAnimateMove() {
        return mContentChanged && !mExiting && !mLastHidden && !mService.mDisplayFrozen
                && (mFrame.top != mLastFrame.top
                        || mFrame.left != mLastFrame.left)
                && (mAttachedWindow == null || !mAttachedWindow.shouldAnimateMove())
                && mService.mPolicy.isScreenOn();
    }

    boolean isFullscreen(int screenWidth, int screenHeight) {
        return mFrame.left <= 0 && mFrame.top <= 0 &&
                mFrame.right >= screenWidth && mFrame.bottom >= screenHeight;
    }

    void removeLocked() {
        disposeInputChannel();
        
        if (mAttachedWindow != null) {
            if (WindowManagerService.DEBUG_ADD_REMOVE) Slog.v(WindowManagerService.TAG, "Removing " + this + " from " + mAttachedWindow);
            mAttachedWindow.mChildWindows.remove(this);
        }
        destroySurfaceLocked();
        mSession.windowRemovedLocked();
        try {
            mClient.asBinder().unlinkToDeath(mDeathRecipient, 0);
        } catch (RuntimeException e) {
            // Ignore if it has already been removed (usually because
            // we are doing this as part of processing a death note.)
        }
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
        public void binderDied() {
            try {
                synchronized(mService.mWindowMap) {
                    WindowState win = mService.windowForClientLocked(mSession, mClient, false);
                    Slog.i(WindowManagerService.TAG, "WIN DEATH: " + win);
                    if (win != null) {
                        mService.removeWindowLocked(mSession, win);
                    }
                }
            } catch (IllegalArgumentException ex) {
                // This will happen if the window has already been
                // removed.
            }
        }
    }

    /** Returns true if this window desires key events. */
    public final boolean canReceiveKeys() {
        return     isVisibleOrAdding()
                && (mViewVisibility == View.VISIBLE)
                && ((mAttrs.flags & WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE) == 0);
    }

    public boolean hasDrawnLw() {
        return mHasDrawn;
    }

    public boolean showLw(boolean doAnimation) {
        return showLw(doAnimation, true);
    }

    boolean showLw(boolean doAnimation, boolean requestAnim) {
        if (mPolicyVisibility && mPolicyVisibilityAfterAnim) {
            return false;
        }
        if (DEBUG_VISIBILITY) Slog.v(WindowManagerService.TAG, "Policy visibility true: " + this);
        if (doAnimation) {
            if (DEBUG_VISIBILITY) Slog.v(WindowManagerService.TAG, "doAnimation: mPolicyVisibility="
                    + mPolicyVisibility + " mAnimation=" + mAnimation);
            if (mService.mDisplayFrozen || !mService.mPolicy.isScreenOn()) {
                doAnimation = false;
            } else if (mPolicyVisibility && mAnimation == null) {
                // Check for the case where we are currently visible and
                // not animating; we do not want to do animation at such a
                // point to become visible when we already are.
                doAnimation = false;
            }
        }
        mPolicyVisibility = true;
        mPolicyVisibilityAfterAnim = true;
        if (doAnimation) {
            mService.applyAnimationLocked(this, WindowManagerPolicy.TRANSIT_ENTER, true);
        }
        if (requestAnim) {
            mService.requestAnimationLocked(0);
        }
        return true;
    }

    public boolean hideLw(boolean doAnimation) {
        return hideLw(doAnimation, true);
    }

    boolean hideLw(boolean doAnimation, boolean requestAnim) {
        if (doAnimation) {
            if (mService.mDisplayFrozen || !mService.mPolicy.isScreenOn()) {
                doAnimation = false;
            }
        }
        boolean current = doAnimation ? mPolicyVisibilityAfterAnim
                : mPolicyVisibility;
        if (!current) {
            return false;
        }
        if (doAnimation) {
            mService.applyAnimationLocked(this, WindowManagerPolicy.TRANSIT_EXIT, false);
            if (mAnimation == null) {
                doAnimation = false;
            }
        }
        if (doAnimation) {
            mPolicyVisibilityAfterAnim = false;
        } else {
            if (DEBUG_VISIBILITY) Slog.v(WindowManagerService.TAG, "Policy visibility false: " + this);
            mPolicyVisibilityAfterAnim = false;
            mPolicyVisibility = false;
            // Window is no longer visible -- make sure if we were waiting
            // for it to be displayed before enabling the display, that
            // we allow the display to be enabled now.
            mService.enableScreenIfNeededLocked();
            if (mService.mCurrentFocus == this) {
                mService.mFocusMayChange = true;
            }
        }
        if (requestAnim) {
            mService.requestAnimationLocked(0);
        }
        return true;
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

    void dump(PrintWriter pw, String prefix, boolean dumpAll) {
        pw.print(prefix); pw.print("mSession="); pw.print(mSession);
                pw.print(" mClient="); pw.println(mClient.asBinder());
        pw.print(prefix); pw.print("mAttrs="); pw.println(mAttrs);
        pw.print(prefix); pw.print("Requested w="); pw.print(mRequestedWidth);
                pw.print(" h="); pw.print(mRequestedHeight);
                pw.print(" mLayoutSeq="); pw.println(mLayoutSeq);
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
                    pw.print((mTargetAppToken != null ? mTargetAppToken.animLayerAdjustment
                          : (mAppToken != null ? mAppToken.animLayerAdjustment : 0)));
                    pw.print("="); pw.print(mAnimLayer);
                    pw.print(" mLastLayer="); pw.println(mLastLayer);
        }
        if (mSurface != null) {
            if (dumpAll) {
                pw.print(prefix); pw.print("mSurface="); pw.println(mSurface);
            }
            pw.print(prefix); pw.print("Surface: shown="); pw.print(mSurfaceShown);
                    pw.print(" layer="); pw.print(mSurfaceLayer);
                    pw.print(" alpha="); pw.print(mSurfaceAlpha);
                    pw.print(" rect=("); pw.print(mSurfaceX);
                    pw.print(","); pw.print(mSurfaceY);
                    pw.print(") "); pw.print(mSurfaceW);
                    pw.print(" x "); pw.println(mSurfaceH);
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
            pw.print(" mLastHidden="); pw.print(mLastHidden);
            pw.print(" mHaveFrame="); pw.print(mHaveFrame);
            pw.print(" mObscured="); pw.println(mObscured);
        }
        if (!mPolicyVisibility || !mPolicyVisibilityAfterAnim || mAttachedHidden) {
            pw.print(prefix); pw.print("mPolicyVisibility=");
                    pw.print(mPolicyVisibility);
                    pw.print(" mPolicyVisibilityAfterAnim=");
                    pw.print(mPolicyVisibilityAfterAnim);
                    pw.print(" mAttachedHidden="); pw.println(mAttachedHidden);
        }
        if (!mRelayoutCalled) {
            pw.print(prefix); pw.print("mRelayoutCalled="); pw.println(mRelayoutCalled);
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
            }
            pw.print(prefix); pw.print("mConfiguration="); pw.println(mConfiguration);
        }
        pw.print(prefix); pw.print("mShownFrame=");
                mShownFrame.printShortString(pw); pw.println();
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
            pw.print(prefix); pw.print("mContainingFrame=");
                    mContainingFrame.printShortString(pw);
                    pw.print(" mParentFrame=");
                    mParentFrame.printShortString(pw);
                    pw.print(" mDisplayFrame=");
                    mDisplayFrame.printShortString(pw);
                    pw.println();
            pw.print(prefix); pw.print("mContentFrame="); mContentFrame.printShortString(pw);
                    pw.print(" mVisibleFrame="); mVisibleFrame.printShortString(pw);
                    pw.println();
            pw.print(prefix); pw.print("mContentInsets="); mContentInsets.printShortString(pw);
                    pw.print(" last="); mLastContentInsets.printShortString(pw);
                    pw.print(" mVisibleInsets="); mVisibleInsets.printShortString(pw);
                    pw.print(" last="); mLastVisibleInsets.printShortString(pw);
                    pw.println();
        }
        if (mAnimating || mLocalAnimating || mAnimationIsEntrance
                || mAnimation != null) {
            pw.print(prefix); pw.print("mAnimating="); pw.print(mAnimating);
                    pw.print(" mLocalAnimating="); pw.print(mLocalAnimating);
                    pw.print(" mAnimationIsEntrance="); pw.print(mAnimationIsEntrance);
                    pw.print(" mAnimation="); pw.println(mAnimation);
        }
        if (mHasTransformation || mHasLocalTransformation) {
            pw.print(prefix); pw.print("XForm: has=");
                    pw.print(mHasTransformation);
                    pw.print(" hasLocal="); pw.print(mHasLocalTransformation);
                    pw.print(" "); mTransformation.printShortString(pw);
                    pw.println();
        }
        if (mShownAlpha != 1 || mAlpha != 1 || mLastAlpha != 1) {
            pw.print(prefix); pw.print("mShownAlpha="); pw.print(mShownAlpha);
                    pw.print(" mAlpha="); pw.print(mAlpha);
                    pw.print(" mLastAlpha="); pw.println(mLastAlpha);
        }
        if (mHaveMatrix || mGlobalScale != 1) {
            pw.print(prefix); pw.print("mGlobalScale="); pw.print(mGlobalScale);
                    pw.print(" mDsDx="); pw.print(mDsDx);
                    pw.print(" mDtDx="); pw.print(mDtDx);
                    pw.print(" mDsDy="); pw.print(mDsDy);
                    pw.print(" mDtDy="); pw.println(mDtDy);
        }
        if (dumpAll) {
            pw.print(prefix); pw.print("mDrawPending="); pw.print(mDrawPending);
                    pw.print(" mCommitDrawPending="); pw.print(mCommitDrawPending);
                    pw.print(" mReadyToShow="); pw.print(mReadyToShow);
                    pw.print(" mHasDrawn="); pw.println(mHasDrawn);
        }
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
    }
    
    String makeInputChannelName() {
        return Integer.toHexString(System.identityHashCode(this))
            + " " + mAttrs.getTitle();
    }

    @Override
    public String toString() {
        if (mStringNameCache == null || mLastTitle != mAttrs.getTitle()
                || mWasPaused != mToken.paused) {
            mLastTitle = mAttrs.getTitle();
            mWasPaused = mToken.paused;
            mStringNameCache = "Window{" + Integer.toHexString(System.identityHashCode(this))
                    + " " + mLastTitle + " paused=" + mWasPaused + "}";
        }
        return mStringNameCache;
    }
}