/*
 * Copyright (C) 2014 The Android Open Source Project
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

import static android.app.ActivityManager.StackId;
import static android.view.Display.DEFAULT_DISPLAY;
import static android.view.WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED;
import static android.view.WindowManager.LayoutParams.FLAG_SCALED;
import static android.view.WindowManager.LayoutParams.PRIVATE_FLAG_IS_ROUNDED_CORNERS_OVERLAY;
import static android.view.WindowManager.LayoutParams.TYPE_APPLICATION_STARTING;
import static android.view.WindowManager.LayoutParams.TYPE_INPUT_METHOD;
import static android.view.WindowManager.LayoutParams.TYPE_WALLPAPER;
import static com.android.server.wm.AppWindowAnimator.sDummyAnimation;
import static com.android.server.wm.DragResizeMode.DRAG_RESIZE_MODE_FREEFORM;
import static com.android.server.wm.WindowManagerDebugConfig.DEBUG_ANIM;
import static com.android.server.wm.WindowManagerDebugConfig.DEBUG_LAYERS;
import static com.android.server.wm.WindowManagerDebugConfig.DEBUG_LAYOUT_REPEATS;
import static com.android.server.wm.WindowManagerDebugConfig.DEBUG_ORIENTATION;
import static com.android.server.wm.WindowManagerDebugConfig.DEBUG_STARTING_WINDOW;
import static com.android.server.wm.WindowManagerDebugConfig.DEBUG_STARTING_WINDOW_VERBOSE;
import static com.android.server.wm.WindowManagerDebugConfig.DEBUG_SURFACE_TRACE;
import static com.android.server.wm.WindowManagerDebugConfig.DEBUG_VISIBILITY;
import static com.android.server.wm.WindowManagerDebugConfig.DEBUG_WALLPAPER;
import static com.android.server.wm.WindowManagerDebugConfig.DEBUG_WINDOW_CROP;
import static com.android.server.wm.WindowManagerDebugConfig.SHOW_LIGHT_TRANSACTIONS;
import static com.android.server.wm.WindowManagerDebugConfig.SHOW_SURFACE_ALLOC;
import static com.android.server.wm.WindowManagerDebugConfig.SHOW_TRANSACTIONS;
import static com.android.server.wm.WindowManagerDebugConfig.TAG_WITH_CLASS_NAME;
import static com.android.server.wm.WindowManagerDebugConfig.TAG_WM;
import static com.android.server.wm.WindowManagerService.TYPE_LAYER_MULTIPLIER;
import static com.android.server.wm.WindowManagerService.localLOGV;
import static com.android.server.wm.WindowManagerService.logWithStack;
import static com.android.server.wm.WindowSurfacePlacer.SET_ORIENTATION_CHANGE_COMPLETE;
import static com.android.server.wm.WindowSurfacePlacer.SET_TURN_ON_SCREEN;

import android.content.Context;
import android.graphics.Matrix;
import android.graphics.PixelFormat;
import android.graphics.Point;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Region;
import android.os.Debug;
import android.os.Trace;
import android.util.Slog;
import android.view.DisplayInfo;
import android.view.MagnificationSpec;
import android.view.Surface.OutOfResourcesException;
import android.view.SurfaceControl;
import android.view.WindowManager;
import android.view.WindowManager.LayoutParams;
import android.view.WindowManagerPolicy;
import android.view.animation.Animation;
import android.view.animation.AnimationSet;
import android.view.animation.AnimationUtils;
import android.view.animation.Transformation;

import java.io.PrintWriter;
import java.io.FileDescriptor;

/**
 * Keep track of animations and surface operations for a single WindowState.
 **/
class WindowStateAnimator {
    static final String TAG = TAG_WITH_CLASS_NAME ? "WindowStateAnimator" : TAG_WM;
    static final int WINDOW_FREEZE_LAYER = TYPE_LAYER_MULTIPLIER * 200;

    /**
     * Mode how the window gets clipped by the stack bounds during an animation: The clipping should
     * be applied after applying the animation transformation, i.e. the stack bounds don't move
     * during the animation.
     */
    static final int STACK_CLIP_AFTER_ANIM = 0;

    /**
     * Mode how the window gets clipped by the stack bounds: The clipping should be applied before
     * applying the animation transformation, i.e. the stack bounds move with the window.
     */
    static final int STACK_CLIP_BEFORE_ANIM = 1;

    /**
     * Mode how window gets clipped by the stack bounds during an animation: Don't clip the window
     * by the stack bounds.
     */
    static final int STACK_CLIP_NONE = 2;

    // Unchanging local convenience fields.
    final WindowManagerService mService;
    final WindowState mWin;
    private final WindowStateAnimator mParentWinAnimator;
    final WindowAnimator mAnimator;
    AppWindowAnimator mAppAnimator;
    final Session mSession;
    final WindowManagerPolicy mPolicy;
    final Context mContext;
    final boolean mIsWallpaper;
    private final WallpaperController mWallpaperControllerLocked;

    // Currently running animation.
    boolean mAnimating;
    boolean mLocalAnimating;
    Animation mAnimation;
    boolean mAnimationIsEntrance;
    boolean mHasTransformation;
    boolean mHasLocalTransformation;
    final Transformation mTransformation = new Transformation();
    boolean mWasAnimating;      // Were we animating going into the most recent animation step?
    int mAnimLayer;
    int mLastLayer;
    long mAnimationStartTime;
    long mLastAnimationTime;
    int mStackClip = STACK_CLIP_BEFORE_ANIM;

    /**
     * Set when we have changed the size of the surface, to know that
     * we must tell them application to resize (and thus redraw itself).
     */
    boolean mSurfaceResized;
    /**
     * Whether we should inform the client on next relayoutWindow that
     * the surface has been resized since last time.
     */
    boolean mReportSurfaceResized;
    WindowSurfaceController mSurfaceController;
    private WindowSurfaceController mPendingDestroySurface;

    /**
     * Set if the client has asked that the destroy of its surface be delayed
     * until it explicitly says it is okay.
     */
    boolean mSurfaceDestroyDeferred;

    private boolean mDestroyPreservedSurfaceUponRedraw;
    float mShownAlpha = 0;
    float mAlpha = 0;
    float mLastAlpha = 0;

    boolean mHasClipRect;
    Rect mClipRect = new Rect();
    Rect mTmpClipRect = new Rect();
    Rect mTmpFinalClipRect = new Rect();
    Rect mLastClipRect = new Rect();
    Rect mLastFinalClipRect = new Rect();
    Rect mTmpStackBounds = new Rect();
    private Rect mTmpAnimatingBounds = new Rect();
    private Rect mTmpSourceBounds = new Rect();

    /**
     * This is rectangle of the window's surface that is not covered by
     * system decorations.
     */
    private final Rect mSystemDecorRect = new Rect();
    private final Rect mLastSystemDecorRect = new Rect();

    // Used to save animation distances between the time they are calculated and when they are used.
    private int mAnimDx;
    private int mAnimDy;

    /** Is the next animation to be started a window move animation? */
    private boolean mAnimateMove = false;

    float mDsDx=1, mDtDx=0, mDsDy=0, mDtDy=1;
    private float mLastDsDx=1, mLastDtDx=0, mLastDsDy=0, mLastDtDy=1;

    boolean mHaveMatrix;

    // Set to true if, when the window gets displayed, it should perform
    // an enter animation.
    boolean mEnterAnimationPending;

    /** Used to indicate that this window is undergoing an enter animation. Used for system
     * windows to make the callback to View.dispatchOnWindowShownCallback(). Set when the
     * window is first added or shown, cleared when the callback has been made. */
    boolean mEnteringAnimation;

    private boolean mAnimationStartDelayed;

    /** The pixel format of the underlying SurfaceControl */
    int mSurfaceFormat;

    /** This is set when there is no Surface */
    static final int NO_SURFACE = 0;
    /** This is set after the Surface has been created but before the window has been drawn. During
     * this time the surface is hidden. */
    static final int DRAW_PENDING = 1;
    /** This is set after the window has finished drawing for the first time but before its surface
     * is shown.  The surface will be displayed when the next layout is run. */
    static final int COMMIT_DRAW_PENDING = 2;
    /** This is set during the time after the window's drawing has been committed, and before its
     * surface is actually shown.  It is used to delay showing the surface until all windows in a
     * token are ready to be shown. */
    static final int READY_TO_SHOW = 3;
    /** Set when the window has been shown in the screen the first time. */
    static final int HAS_DRAWN = 4;

    String drawStateToString() {
        switch (mDrawState) {
            case NO_SURFACE: return "NO_SURFACE";
            case DRAW_PENDING: return "DRAW_PENDING";
            case COMMIT_DRAW_PENDING: return "COMMIT_DRAW_PENDING";
            case READY_TO_SHOW: return "READY_TO_SHOW";
            case HAS_DRAWN: return "HAS_DRAWN";
            default: return Integer.toString(mDrawState);
        }
    }
    int mDrawState;

    /** Was this window last hidden? */
    boolean mLastHidden;

    int mAttrType;

    static final long PENDING_TRANSACTION_FINISH_WAIT_TIME = 100;

    boolean mForceScaleUntilResize;

    // WindowState.mHScale and WindowState.mVScale contain the
    // scale according to client specified layout parameters (e.g.
    // one layout size, with another surface size, creates such scaling).
    // Here we track an additional scaling factor used to follow stack
    // scaling (as in the case of the Pinned stack animation).
    float mExtraHScale = (float) 1.0;
    float mExtraVScale = (float) 1.0;

    private final Rect mTmpSize = new Rect();

    WindowStateAnimator(final WindowState win) {
        final WindowManagerService service = win.mService;

        mService = service;
        mAnimator = service.mAnimator;
        mPolicy = service.mPolicy;
        mContext = service.mContext;
        final DisplayContent displayContent = win.getDisplayContent();
        if (displayContent != null) {
            final DisplayInfo displayInfo = displayContent.getDisplayInfo();
            mAnimDx = displayInfo.appWidth;
            mAnimDy = displayInfo.appHeight;
        } else {
            Slog.w(TAG, "WindowStateAnimator ctor: Display has been removed");
            // This is checked on return and dealt with.
        }

        mWin = win;
        mParentWinAnimator = !win.isChildWindow() ? null : win.getParentWindow().mWinAnimator;
        mAppAnimator = win.mAppToken == null ? null : win.mAppToken.mAppAnimator;
        mSession = win.mSession;
        mAttrType = win.mAttrs.type;
        mIsWallpaper = win.mIsWallpaper;
        mWallpaperControllerLocked = mService.mRoot.mWallpaperController;
    }

    public void setAnimation(Animation anim, long startTime, int stackClip) {
        if (localLOGV) Slog.v(TAG, "Setting animation in " + this + ": " + anim);
        mAnimating = false;
        mLocalAnimating = false;
        mAnimation = anim;
        mAnimation.restrictDuration(WindowManagerService.MAX_ANIMATION_DURATION);
        mAnimation.scaleCurrentDuration(mService.getWindowAnimationScaleLocked());
        // Start out animation gone if window is gone, or visible if window is visible.
        mTransformation.clear();
        mTransformation.setAlpha(mLastHidden ? 0 : 1);
        mHasLocalTransformation = true;
        mAnimationStartTime = startTime;
        mStackClip = stackClip;
    }

    public void setAnimation(Animation anim, int stackClip) {
        setAnimation(anim, -1, stackClip);
    }

    public void setAnimation(Animation anim) {
        setAnimation(anim, -1, STACK_CLIP_AFTER_ANIM);
    }

    public void clearAnimation() {
        if (mAnimation != null) {
            mAnimating = true;
            mLocalAnimating = false;
            mAnimation.cancel();
            mAnimation = null;
            mStackClip = STACK_CLIP_BEFORE_ANIM;
        }
    }

    /**
     * Is the window or its container currently set to animate or currently animating?
     */
    boolean isAnimationSet() {
        return mAnimation != null
                || (mParentWinAnimator != null && mParentWinAnimator.mAnimation != null)
                || (mAppAnimator != null && mAppAnimator.isAnimating());
    }

    /**
     * @return whether an animation is about to start, i.e. the animation is set already but we
     *         haven't processed the first frame yet.
     */
    boolean isAnimationStarting() {
        return isAnimationSet() && !mAnimating;
    }

    /** Is the window animating the DummyAnimation? */
    boolean isDummyAnimation() {
        return mAppAnimator != null
                && mAppAnimator.animation == sDummyAnimation;
    }

    /**
     * Is this window currently set to animate or currently animating?
     */
    boolean isWindowAnimationSet() {
        return mAnimation != null;
    }

    /**
     * Is this window currently waiting to run an opening animation?
     */
    boolean isWaitingForOpening() {
        return mService.mAppTransition.isTransitionSet() && isDummyAnimation()
                && mService.mOpeningApps.contains(mWin.mAppToken);
    }

    void cancelExitAnimationForNextAnimationLocked() {
        if (DEBUG_ANIM) Slog.d(TAG,
                "cancelExitAnimationForNextAnimationLocked: " + mWin);

        if (mAnimation != null) {
            mAnimation.cancel();
            mAnimation = null;
            mLocalAnimating = false;
            mWin.destroyOrSaveSurfaceUnchecked();
        }
    }

    private boolean stepAnimation(long currentTime) {
        if ((mAnimation == null) || !mLocalAnimating) {
            return false;
        }
        currentTime = getAnimationFrameTime(mAnimation, currentTime);
        mTransformation.clear();
        final boolean more = mAnimation.getTransformation(currentTime, mTransformation);
        if (mAnimationStartDelayed && mAnimationIsEntrance) {
            mTransformation.setAlpha(0f);
        }
        if (false && DEBUG_ANIM) Slog.v(TAG, "Stepped animation in " + this + ": more=" + more
                + ", xform=" + mTransformation);
        return more;
    }

    // This must be called while inside a transaction.  Returns true if
    // there is more animation to run.
    boolean stepAnimationLocked(long currentTime) {
        // Save the animation state as it was before this step so WindowManagerService can tell if
        // we just started or just stopped animating by comparing mWasAnimating with isAnimationSet().
        mWasAnimating = mAnimating;
        final DisplayContent displayContent = mWin.getDisplayContent();
        if (mWin.mToken.okToAnimate()) {
            // We will run animations as long as the display isn't frozen.

            if (mWin.isDrawnLw() && mAnimation != null) {
                mHasTransformation = true;
                mHasLocalTransformation = true;
                if (!mLocalAnimating) {
                    if (DEBUG_ANIM) Slog.v(
                        TAG, "Starting animation in " + this +
                        " @ " + currentTime + ": ww=" + mWin.mFrame.width() +
                        " wh=" + mWin.mFrame.height() +
                        " dx=" + mAnimDx + " dy=" + mAnimDy +
                        " scale=" + mService.getWindowAnimationScaleLocked());
                    final DisplayInfo displayInfo = displayContent.getDisplayInfo();
                    if (mAnimateMove) {
                        mAnimateMove = false;
                        mAnimation.initialize(mWin.mFrame.width(), mWin.mFrame.height(),
                                mAnimDx, mAnimDy);
                    } else {
                        mAnimation.initialize(mWin.mFrame.width(), mWin.mFrame.height(),
                                displayInfo.appWidth, displayInfo.appHeight);
                    }
                    mAnimDx = displayInfo.appWidth;
                    mAnimDy = displayInfo.appHeight;
                    mAnimation.setStartTime(mAnimationStartTime != -1
                            ? mAnimationStartTime
                            : currentTime);
                    mLocalAnimating = true;
                    mAnimating = true;
                }
                if ((mAnimation != null) && mLocalAnimating) {
                    mLastAnimationTime = currentTime;
                    if (stepAnimation(currentTime)) {
                        return true;
                    }
                }
                if (DEBUG_ANIM) Slog.v(
                    TAG, "Finished animation in " + this +
                    " @ " + currentTime);
                //WindowManagerService.this.dump();
            }
            mHasLocalTransformation = false;
            if ((!mLocalAnimating || mAnimationIsEntrance) && mAppAnimator != null
                    && mAppAnimator.animation != null) {
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
            } else if (isAnimationSet()) {
                mAnimating = true;
            }
        } else if (mAnimation != null) {
            // If the display is frozen, and there is a pending animation,
            // clear it and make sure we run the cleanup code.
            mAnimating = true;
        }

        if (!mAnimating && !mLocalAnimating) {
            return false;
        }

        // Done animating, clean up.
        if (DEBUG_ANIM) Slog.v(
            TAG, "Animation done in " + this + ": exiting=" + mWin.mAnimatingExit
            + ", reportedVisible="
            + (mWin.mAppToken != null ? mWin.mAppToken.reportedVisible : false));

        mAnimating = false;
        mLocalAnimating = false;
        if (mAnimation != null) {
            mAnimation.cancel();
            mAnimation = null;
        }
        if (mAnimator.mWindowDetachedWallpaper == mWin) {
            mAnimator.mWindowDetachedWallpaper = null;
        }
        mAnimLayer = mWin.getSpecialWindowAnimLayerAdjustment();
        if (DEBUG_LAYERS) Slog.v(TAG, "Stepping win " + this + " anim layer: " + mAnimLayer);
        mHasTransformation = false;
        mHasLocalTransformation = false;
        mStackClip = STACK_CLIP_BEFORE_ANIM;
        mWin.checkPolicyVisibilityChange();
        mTransformation.clear();
        if (mAttrType == LayoutParams.TYPE_STATUS_BAR && mWin.mPolicyVisibility) {
            // Upon completion of a not-visible to visible status bar animation a relayout is
            // required.
            if (displayContent != null) {
                displayContent.setLayoutNeeded();
            }
        }

        mWin.onExitAnimationDone();
        final int displayId = mWin.getDisplayId();
        mAnimator.setPendingLayoutChanges(displayId, WindowManagerPolicy.FINISH_LAYOUT_REDO_ANIM);
        if (DEBUG_LAYOUT_REPEATS)
            mService.mWindowPlacerLocked.debugLayoutRepeats(
                    "WindowStateAnimator", mAnimator.getPendingLayoutChanges(displayId));

        if (mWin.mAppToken != null) {
            mWin.mAppToken.updateReportedVisibilityLocked();
        }

        return false;
    }

    void hide(String reason) {
        if (!mLastHidden) {
            //dump();
            mLastHidden = true;
            if (mSurfaceController != null) {
                mSurfaceController.hideInTransaction(reason);
            }
        }
    }

    boolean finishDrawingLocked() {
        final boolean startingWindow =
                mWin.mAttrs.type == WindowManager.LayoutParams.TYPE_APPLICATION_STARTING;
        if (DEBUG_STARTING_WINDOW && startingWindow) {
            Slog.v(TAG, "Finishing drawing window " + mWin + ": mDrawState="
                    + drawStateToString());
        }

        boolean layoutNeeded = mWin.clearAnimatingWithSavedSurface();

        if (mDrawState == DRAW_PENDING) {
            if (DEBUG_SURFACE_TRACE || DEBUG_ANIM || SHOW_TRANSACTIONS || DEBUG_ORIENTATION)
                Slog.v(TAG, "finishDrawingLocked: mDrawState=COMMIT_DRAW_PENDING " + mWin + " in "
                        + mSurfaceController);
            if (DEBUG_STARTING_WINDOW && startingWindow) {
                Slog.v(TAG, "Draw state now committed in " + mWin);
            }
            mDrawState = COMMIT_DRAW_PENDING;
            layoutNeeded = true;
        }

        return layoutNeeded;
    }

    // This must be called while inside a transaction.
    boolean commitFinishDrawingLocked() {
        if (DEBUG_STARTING_WINDOW_VERBOSE &&
                mWin.mAttrs.type == WindowManager.LayoutParams.TYPE_APPLICATION_STARTING) {
            Slog.i(TAG, "commitFinishDrawingLocked: " + mWin + " cur mDrawState="
                    + drawStateToString());
        }
        if (mDrawState != COMMIT_DRAW_PENDING && mDrawState != READY_TO_SHOW) {
            return false;
        }
        if (DEBUG_SURFACE_TRACE || DEBUG_ANIM) {
            Slog.i(TAG, "commitFinishDrawingLocked: mDrawState=READY_TO_SHOW " + mSurfaceController);
        }
        mDrawState = READY_TO_SHOW;
        boolean result = false;
        final AppWindowToken atoken = mWin.mAppToken;
        if (atoken == null || atoken.allDrawn || mWin.mAttrs.type == TYPE_APPLICATION_STARTING) {
            result = mWin.performShowLocked();
        }
        return result;
    }

    void preserveSurfaceLocked() {
        if (mDestroyPreservedSurfaceUponRedraw) {
            // This could happen when switching the surface mode very fast. For example,
            // we preserved a surface when dragResizing changed to true. Then before the
            // preserved surface is removed, dragResizing changed to false again.
            // In this case, we need to leave the preserved surface alone, and destroy
            // the actual surface, so that the createSurface call could create a surface
            // of the proper size. The preserved surface will still be removed when client
            // finishes drawing to the new surface.
            mSurfaceDestroyDeferred = false;
            destroySurfaceLocked();
            mSurfaceDestroyDeferred = true;
            return;
        }
        if (SHOW_TRANSACTIONS) WindowManagerService.logSurface(mWin, "SET FREEZE LAYER", false);
        if (mSurfaceController != null) {
            mSurfaceController.setLayer(mAnimLayer + 1);
        }
        mDestroyPreservedSurfaceUponRedraw = true;
        mSurfaceDestroyDeferred = true;
        destroySurfaceLocked();
    }

    void destroyPreservedSurfaceLocked() {
        if (!mDestroyPreservedSurfaceUponRedraw) {
            return;
        }
        if (mSurfaceController != null) {
            if (mPendingDestroySurface != null) {
                // If we are preserving a surface but we aren't relaunching that means
                // we are just doing an in-place switch. In that case any SurfaceFlinger side
                // child layers need to be reparented to the new surface to make this
                // transparent to the app.
                if (mWin.mAppToken == null || mWin.mAppToken.isRelaunching() == false) {
                    SurfaceControl.openTransaction();
                    mPendingDestroySurface.reparentChildrenInTransaction(mSurfaceController);
                    SurfaceControl.closeTransaction();
                }
            }
        }

        destroyDeferredSurfaceLocked();
        mDestroyPreservedSurfaceUponRedraw = false;
    }

    void markPreservedSurfaceForDestroy() {
        if (mDestroyPreservedSurfaceUponRedraw
                && !mService.mDestroyPreservedSurface.contains(mWin)) {
            mService.mDestroyPreservedSurface.add(mWin);
        }
    }

    private int getLayerStack() {
        return mWin.getDisplayContent().getDisplay().getLayerStack();
    }

    void updateLayerStackInTransaction() {
        if (mSurfaceController != null) {
            mSurfaceController.setLayerStackInTransaction(
                    getLayerStack());
        }
    }

    void resetDrawState() {
        mDrawState = DRAW_PENDING;

        if (mWin.mAppToken == null) {
            return;
        }

        if (mWin.mAppToken.mAppAnimator.animation == null) {
            mWin.mAppToken.clearAllDrawn();
        } else {
            // Currently animating, persist current state of allDrawn until animation
            // is complete.
            mWin.mAppToken.deferClearAllDrawn = true;
        }
    }

    WindowSurfaceController createSurfaceLocked(int windowType, int ownerUid) {
        final WindowState w = mWin;
        if (w.restoreSavedSurface()) {
            if (DEBUG_ANIM) Slog.i(TAG,
                    "createSurface: " + this + ": called when we had a saved surface");
            return mSurfaceController;
        }

        if (mSurfaceController != null) {
            return mSurfaceController;
        }

        if ((mWin.mAttrs.privateFlags & PRIVATE_FLAG_IS_ROUNDED_CORNERS_OVERLAY) != 0) {
            windowType = SurfaceControl.WINDOW_TYPE_DONT_SCREENSHOT;
        }

        w.setHasSurface(false);

        if (DEBUG_ANIM || DEBUG_ORIENTATION) Slog.i(TAG,
                "createSurface " + this + ": mDrawState=DRAW_PENDING");

        resetDrawState();

        mService.makeWindowFreezingScreenIfNeededLocked(w);

        int flags = SurfaceControl.HIDDEN;
        final WindowManager.LayoutParams attrs = w.mAttrs;

        if (mService.isSecureLocked(w)) {
            flags |= SurfaceControl.SECURE;
        }

        mTmpSize.set(w.mFrame.left + w.mXOffset, w.mFrame.top + w.mYOffset, 0, 0);
        calculateSurfaceBounds(w, attrs);
        final int width = mTmpSize.width();
        final int height = mTmpSize.height();

        if (DEBUG_VISIBILITY) {
            Slog.v(TAG, "Creating surface in session "
                    + mSession.mSurfaceSession + " window " + this
                    + " w=" + width + " h=" + height
                    + " x=" + mTmpSize.left + " y=" + mTmpSize.top
                    + " format=" + attrs.format + " flags=" + flags);
        }

        // We may abort, so initialize to defaults.
        mLastSystemDecorRect.set(0, 0, 0, 0);
        mHasClipRect = false;
        mClipRect.set(0, 0, 0, 0);
        mLastClipRect.set(0, 0, 0, 0);

        // Set up surface control with initial size.
        try {

            final boolean isHwAccelerated = (attrs.flags & FLAG_HARDWARE_ACCELERATED) != 0;
            final int format = isHwAccelerated ? PixelFormat.TRANSLUCENT : attrs.format;
            if (!PixelFormat.formatHasAlpha(attrs.format)
                    // Don't make surface with surfaceInsets opaque as they display a
                    // translucent shadow.
                    && attrs.surfaceInsets.left == 0
                    && attrs.surfaceInsets.top == 0
                    && attrs.surfaceInsets.right == 0
                    && attrs.surfaceInsets.bottom == 0
                    // Don't make surface opaque when resizing to reduce the amount of
                    // artifacts shown in areas the app isn't drawing content to.
                    && !w.isDragResizing()) {
                flags |= SurfaceControl.OPAQUE;
            }

            mSurfaceController = new WindowSurfaceController(mSession.mSurfaceSession,
                    attrs.getTitle().toString(),
                    width, height, format, flags, this, windowType, ownerUid);
            mSurfaceFormat = format;

            w.setHasSurface(true);

            if (SHOW_TRANSACTIONS || SHOW_SURFACE_ALLOC) {
                Slog.i(TAG, "  CREATE SURFACE "
                        + mSurfaceController + " IN SESSION "
                        + mSession.mSurfaceSession
                        + ": pid=" + mSession.mPid + " format="
                        + attrs.format + " flags=0x"
                        + Integer.toHexString(flags)
                        + " / " + this);
            }
        } catch (OutOfResourcesException e) {
            Slog.w(TAG, "OutOfResourcesException creating surface");
            mService.mRoot.reclaimSomeSurfaceMemory(this, "create", true);
            mDrawState = NO_SURFACE;
            return null;
        } catch (Exception e) {
            Slog.e(TAG, "Exception creating surface", e);
            mDrawState = NO_SURFACE;
            return null;
        }

        if (WindowManagerService.localLOGV) Slog.v(TAG, "Got surface: " + mSurfaceController
                + ", set left=" + w.mFrame.left + " top=" + w.mFrame.top
                + ", animLayer=" + mAnimLayer);

        if (SHOW_LIGHT_TRANSACTIONS) {
            Slog.i(TAG, ">>> OPEN TRANSACTION createSurfaceLocked");
            WindowManagerService.logSurface(w, "CREATE pos=("
                    + w.mFrame.left + "," + w.mFrame.top + ") ("
                    + width + "x" + height + "), layer=" + mAnimLayer + " HIDE", false);
        }

        // Start a new transaction and apply position & offset.

        mService.openSurfaceTransaction();
        try {
            mSurfaceController.setPositionInTransaction(mTmpSize.left, mTmpSize.top, false);
            mSurfaceController.setLayerStackInTransaction(getLayerStack());
            mSurfaceController.setLayer(mAnimLayer);
        } finally {
            mService.closeSurfaceTransaction();
        }

        mLastHidden = true;

        if (WindowManagerService.localLOGV) Slog.v(TAG, "Created surface " + this);
        return mSurfaceController;
    }

    private void calculateSurfaceBounds(WindowState w, LayoutParams attrs) {
        if ((attrs.flags & FLAG_SCALED) != 0) {
            // For a scaled surface, we always want the requested size.
            mTmpSize.right = mTmpSize.left + w.mRequestedWidth;
            mTmpSize.bottom = mTmpSize.top + w.mRequestedHeight;
        } else {
            // When we're doing a drag-resizing, request a surface that's fullscreen size,
            // so that we don't need to reallocate during the process. This also prevents
            // buffer drops due to size mismatch.
            if (w.isDragResizing()) {
                if (w.getResizeMode() == DRAG_RESIZE_MODE_FREEFORM) {
                    mTmpSize.left = 0;
                    mTmpSize.top = 0;
                }
                final DisplayInfo displayInfo = w.getDisplayInfo();
                mTmpSize.right = mTmpSize.left + displayInfo.logicalWidth;
                mTmpSize.bottom = mTmpSize.top + displayInfo.logicalHeight;
            } else {
                mTmpSize.right = mTmpSize.left + w.mCompatFrame.width();
                mTmpSize.bottom = mTmpSize.top + w.mCompatFrame.height();
            }
        }

        // Something is wrong and SurfaceFlinger will not like this, try to revert to sane values.
        // This doesn't necessarily mean that there is an error in the system. The sizes might be
        // incorrect, because it is before the first layout or draw.
        if (mTmpSize.width() < 1) {
            mTmpSize.right = mTmpSize.left + 1;
        }
        if (mTmpSize.height() < 1) {
            mTmpSize.bottom = mTmpSize.top + 1;
        }

        // Adjust for surface insets.
        mTmpSize.left -= attrs.surfaceInsets.left;
        mTmpSize.top -= attrs.surfaceInsets.top;
        mTmpSize.right += attrs.surfaceInsets.right;
        mTmpSize.bottom += attrs.surfaceInsets.bottom;
    }

    boolean hasSurface() {
        return !mWin.hasSavedSurface()
                && mSurfaceController != null && mSurfaceController.hasSurface();
    }

    void destroySurfaceLocked() {
        final AppWindowToken wtoken = mWin.mAppToken;
        if (wtoken != null) {
            if (mWin == wtoken.startingWindow) {
                wtoken.startingDisplayed = false;
            }
        }

        mWin.clearHasSavedSurface();

        if (mSurfaceController == null) {
            return;
        }

        // When destroying a surface we want to make sure child windows are hidden. If we are
        // preserving the surface until redraw though we intend to swap it out with another surface
        // for resizing. In this case the window always remains visible to the user and the child
        // windows should likewise remain visible.
        if (!mDestroyPreservedSurfaceUponRedraw) {
            mWin.mHidden = true;
        }

        try {
            if (DEBUG_VISIBILITY) logWithStack(TAG, "Window " + this + " destroying surface "
                    + mSurfaceController + ", session " + mSession);
            if (mSurfaceDestroyDeferred) {
                if (mSurfaceController != null && mPendingDestroySurface != mSurfaceController) {
                    if (mPendingDestroySurface != null) {
                        if (SHOW_TRANSACTIONS || SHOW_SURFACE_ALLOC) {
                            WindowManagerService.logSurface(mWin, "DESTROY PENDING", true);
                        }
                        mPendingDestroySurface.destroyInTransaction();
                    }
                    mPendingDestroySurface = mSurfaceController;
                }
            } else {
                if (SHOW_TRANSACTIONS || SHOW_SURFACE_ALLOC) {
                    WindowManagerService.logSurface(mWin, "DESTROY", true);
                }
                destroySurface();
            }
            // Don't hide wallpaper if we're deferring the surface destroy
            // because of a surface change.
            if (!mDestroyPreservedSurfaceUponRedraw) {
                mWallpaperControllerLocked.hideWallpapers(mWin);
            }
        } catch (RuntimeException e) {
            Slog.w(TAG, "Exception thrown when destroying Window " + this
                + " surface " + mSurfaceController + " session " + mSession + ": " + e.toString());
        }

        // Whether the surface was preserved (and copied to mPendingDestroySurface) or not, it
        // needs to be cleared to match the WindowState.mHasSurface state. It is also necessary
        // so it can be recreated successfully in mPendingDestroySurface case.
        mWin.setHasSurface(false);
        if (mSurfaceController != null) {
            mSurfaceController.setShown(false);
        }
        mSurfaceController = null;
        mDrawState = NO_SURFACE;
    }

    void destroyDeferredSurfaceLocked() {
        try {
            if (mPendingDestroySurface != null) {
                if (SHOW_TRANSACTIONS || SHOW_SURFACE_ALLOC) {
                    WindowManagerService.logSurface(mWin, "DESTROY PENDING", true);
                }
                mPendingDestroySurface.destroyInTransaction();
                // Don't hide wallpaper if we're destroying a deferred surface
                // after a surface mode change.
                if (!mDestroyPreservedSurfaceUponRedraw) {
                    mWallpaperControllerLocked.hideWallpapers(mWin);
                }
            }
        } catch (RuntimeException e) {
            Slog.w(TAG, "Exception thrown when destroying Window "
                    + this + " surface " + mPendingDestroySurface
                    + " session " + mSession + ": " + e.toString());
        }
        mSurfaceDestroyDeferred = false;
        mPendingDestroySurface = null;
    }

    void applyMagnificationSpec(MagnificationSpec spec, Matrix transform) {
        final int surfaceInsetLeft = mWin.mAttrs.surfaceInsets.left;
        final int surfaceInsetTop = mWin.mAttrs.surfaceInsets.top;

        if (spec != null && !spec.isNop()) {
            float scale = spec.scale;
            transform.postScale(scale, scale);
            transform.postTranslate(spec.offsetX, spec.offsetY);

            // As we are scaling the whole surface, to keep the content
            // in the same position we will also have to scale the surfaceInsets.
            transform.postTranslate(-(surfaceInsetLeft*scale - surfaceInsetLeft),
                    -(surfaceInsetTop*scale - surfaceInsetTop));
        }
    }

    void computeShownFrameLocked() {
        final boolean selfTransformation = mHasLocalTransformation;
        Transformation attachedTransformation =
                (mParentWinAnimator != null && mParentWinAnimator.mHasLocalTransformation)
                ? mParentWinAnimator.mTransformation : null;
        Transformation appTransformation = (mAppAnimator != null && mAppAnimator.hasTransformation)
                ? mAppAnimator.transformation : null;

        // Wallpapers are animated based on the "real" window they
        // are currently targeting.
        final WindowState wallpaperTarget = mWallpaperControllerLocked.getWallpaperTarget();
        if (mIsWallpaper && wallpaperTarget != null && mService.mAnimateWallpaperWithTarget) {
            final WindowStateAnimator wallpaperAnimator = wallpaperTarget.mWinAnimator;
            if (wallpaperAnimator.mHasLocalTransformation &&
                    wallpaperAnimator.mAnimation != null &&
                    !wallpaperAnimator.mAnimation.getDetachWallpaper()) {
                attachedTransformation = wallpaperAnimator.mTransformation;
                if (DEBUG_WALLPAPER && attachedTransformation != null) {
                    Slog.v(TAG, "WP target attached xform: " + attachedTransformation);
                }
            }
            final AppWindowAnimator wpAppAnimator = wallpaperTarget.mAppToken == null ?
                    null : wallpaperTarget.mAppToken.mAppAnimator;
                if (wpAppAnimator != null && wpAppAnimator.hasTransformation
                    && wpAppAnimator.animation != null
                    && !wpAppAnimator.animation.getDetachWallpaper()) {
                appTransformation = wpAppAnimator.transformation;
                if (DEBUG_WALLPAPER && appTransformation != null) {
                    Slog.v(TAG, "WP target app xform: " + appTransformation);
                }
            }
        }

        final int displayId = mWin.getDisplayId();
        final ScreenRotationAnimation screenRotationAnimation =
                mAnimator.getScreenRotationAnimationLocked(displayId);
        final boolean screenAnimation =
                screenRotationAnimation != null && screenRotationAnimation.isAnimating();

        mHasClipRect = false;
        if (selfTransformation || attachedTransformation != null
                || appTransformation != null || screenAnimation) {
            // cache often used attributes locally
            final Rect frame = mWin.mFrame;
            final float tmpFloats[] = mService.mTmpFloats;
            final Matrix tmpMatrix = mWin.mTmpMatrix;

            // Compute the desired transformation.
            if (screenAnimation && screenRotationAnimation.isRotating()) {
                // If we are doing a screen animation, the global rotation
                // applied to windows can result in windows that are carefully
                // aligned with each other to slightly separate, allowing you
                // to see what is behind them.  An unsightly mess.  This...
                // thing...  magically makes it call good: scale each window
                // slightly (two pixels larger in each dimension, from the
                // window's center).
                final float w = frame.width();
                final float h = frame.height();
                if (w>=1 && h>=1) {
                    tmpMatrix.setScale(1 + 2/w, 1 + 2/h, w/2, h/2);
                } else {
                    tmpMatrix.reset();
                }
            } else {
                tmpMatrix.reset();
            }
            tmpMatrix.postScale(mWin.mGlobalScale, mWin.mGlobalScale);
            if (selfTransformation) {
                tmpMatrix.postConcat(mTransformation.getMatrix());
            }
            if (attachedTransformation != null) {
                tmpMatrix.postConcat(attachedTransformation.getMatrix());
            }
            if (appTransformation != null) {
                tmpMatrix.postConcat(appTransformation.getMatrix());
            }

            // The translation that applies the position of the window needs to be applied at the
            // end in case that other translations include scaling. Otherwise the scaling will
            // affect this translation. But it needs to be set before the screen rotation animation
            // so the pivot point is at the center of the screen for all windows.
            tmpMatrix.postTranslate(frame.left + mWin.mXOffset, frame.top + mWin.mYOffset);
            if (screenAnimation) {
                tmpMatrix.postConcat(screenRotationAnimation.getEnterTransformation().getMatrix());
            }

            MagnificationSpec spec = getMagnificationSpec();
            if (spec != null) {
                applyMagnificationSpec(spec, tmpMatrix);
            }

            // "convert" it into SurfaceFlinger's format
            // (a 2x2 matrix + an offset)
            // Here we must not transform the position of the surface
            // since it is already included in the transformation.
            //Slog.i(TAG_WM, "Transform: " + matrix);

            mHaveMatrix = true;
            tmpMatrix.getValues(tmpFloats);
            mDsDx = tmpFloats[Matrix.MSCALE_X];
            mDtDx = tmpFloats[Matrix.MSKEW_Y];
            mDtDy = tmpFloats[Matrix.MSKEW_X];
            mDsDy = tmpFloats[Matrix.MSCALE_Y];
            float x = tmpFloats[Matrix.MTRANS_X];
            float y = tmpFloats[Matrix.MTRANS_Y];
            mWin.mShownPosition.set(Math.round(x), Math.round(y));

            // Now set the alpha...  but because our current hardware
            // can't do alpha transformation on a non-opaque surface,
            // turn it off if we are running an animation that is also
            // transforming since it is more important to have that
            // animation be smooth.
            mShownAlpha = mAlpha;
            if (!mService.mLimitedAlphaCompositing
                    || (!PixelFormat.formatHasAlpha(mWin.mAttrs.format)
                    || (mWin.isIdentityMatrix(mDsDx, mDtDx, mDtDy, mDsDy)
                            && x == frame.left && y == frame.top))) {
                //Slog.i(TAG_WM, "Applying alpha transform");
                if (selfTransformation) {
                    mShownAlpha *= mTransformation.getAlpha();
                }
                if (attachedTransformation != null) {
                    mShownAlpha *= attachedTransformation.getAlpha();
                }
                if (appTransformation != null) {
                    mShownAlpha *= appTransformation.getAlpha();
                    if (appTransformation.hasClipRect()) {
                        mClipRect.set(appTransformation.getClipRect());
                        mHasClipRect = true;
                        // The app transformation clip will be in the coordinate space of the main
                        // activity window, which the animation correctly assumes will be placed at
                        // (0,0)+(insets) relative to the containing frame. This isn't necessarily
                        // true for child windows though which can have an arbitrary frame position
                        // relative to their containing frame. We need to offset the difference
                        // between the containing frame as used to calculate the crop and our
                        // bounds to compensate for this.
                        if (mWin.layoutInParentFrame()) {
                            mClipRect.offset( (mWin.mContainingFrame.left - mWin.mFrame.left),
                                    mWin.mContainingFrame.top - mWin.mFrame.top );
                        }
                    }
                }
                if (screenAnimation) {
                    mShownAlpha *= screenRotationAnimation.getEnterTransformation().getAlpha();
                }
            } else {
                //Slog.i(TAG_WM, "Not applying alpha transform");
            }

            if ((DEBUG_SURFACE_TRACE || WindowManagerService.localLOGV)
                    && (mShownAlpha == 1.0 || mShownAlpha == 0.0)) Slog.v(
                    TAG, "computeShownFrameLocked: Animating " + this + " mAlpha=" + mAlpha
                    + " self=" + (selfTransformation ? mTransformation.getAlpha() : "null")
                    + " attached=" + (attachedTransformation == null ?
                            "null" : attachedTransformation.getAlpha())
                    + " app=" + (appTransformation == null ? "null" : appTransformation.getAlpha())
                    + " screen=" + (screenAnimation ?
                            screenRotationAnimation.getEnterTransformation().getAlpha() : "null"));
            return;
        } else if (mIsWallpaper && mService.mRoot.mWallpaperActionPending) {
            return;
        } else if (mWin.isDragResizeChanged()) {
            // This window is awaiting a relayout because user just started (or ended)
            // drag-resizing. The shown frame (which affects surface size and pos)
            // should not be updated until we get next finished draw with the new surface.
            // Otherwise one or two frames rendered with old settings would be displayed
            // with new geometry.
            return;
        }

        if (WindowManagerService.localLOGV) Slog.v(
                TAG, "computeShownFrameLocked: " + this +
                " not attached, mAlpha=" + mAlpha);

        MagnificationSpec spec = getMagnificationSpec();
        if (spec != null) {
            final Rect frame = mWin.mFrame;
            final float tmpFloats[] = mService.mTmpFloats;
            final Matrix tmpMatrix = mWin.mTmpMatrix;

            tmpMatrix.setScale(mWin.mGlobalScale, mWin.mGlobalScale);
            tmpMatrix.postTranslate(frame.left + mWin.mXOffset, frame.top + mWin.mYOffset);

            applyMagnificationSpec(spec, tmpMatrix);

            tmpMatrix.getValues(tmpFloats);

            mHaveMatrix = true;
            mDsDx = tmpFloats[Matrix.MSCALE_X];
            mDtDx = tmpFloats[Matrix.MSKEW_Y];
            mDtDy = tmpFloats[Matrix.MSKEW_X];
            mDsDy = tmpFloats[Matrix.MSCALE_Y];
            float x = tmpFloats[Matrix.MTRANS_X];
            float y = tmpFloats[Matrix.MTRANS_Y];
            mWin.mShownPosition.set(Math.round(x), Math.round(y));

            mShownAlpha = mAlpha;
        } else {
            mWin.mShownPosition.set(mWin.mFrame.left, mWin.mFrame.top);
            if (mWin.mXOffset != 0 || mWin.mYOffset != 0) {
                mWin.mShownPosition.offset(mWin.mXOffset, mWin.mYOffset);
            }
            mShownAlpha = mAlpha;
            mHaveMatrix = false;
            mDsDx = mWin.mGlobalScale;
            mDtDx = 0;
            mDtDy = 0;
            mDsDy = mWin.mGlobalScale;
        }
    }

    private MagnificationSpec getMagnificationSpec() {
        //TODO (multidisplay): Magnification is supported only for the default display.
        if (mService.mAccessibilityController != null && mWin.getDisplayId() == DEFAULT_DISPLAY) {
            return mService.mAccessibilityController.getMagnificationSpecForWindowLocked(mWin);
        }
        return null;
    }

    /**
     * In some scenarios we use a screen space clip rect (so called, final clip rect)
     * to crop to stack bounds. Generally because it's easier to deal with while
     * animating.
     *
     * @return True in scenarios where we use the final clip rect for stack clipping.
     */
    private boolean useFinalClipRect() {
        return (isAnimationSet() && resolveStackClip() == STACK_CLIP_AFTER_ANIM)
                || mDestroyPreservedSurfaceUponRedraw || mWin.inPinnedWorkspace();
    }

    /**
     * Calculate the screen-space crop rect and fill finalClipRect.
     * @return true if finalClipRect has been filled, otherwise,
     * no screen space crop should be applied.
     */
    private boolean calculateFinalCrop(Rect finalClipRect) {
        final WindowState w = mWin;
        final DisplayContent displayContent = w.getDisplayContent();
        finalClipRect.setEmpty();

        if (displayContent == null) {
            return false;
        }

        if (!shouldCropToStackBounds() || !useFinalClipRect()) {
            return false;
        }

        // Task is non-null per shouldCropToStackBounds
        final TaskStack stack = w.getTask().mStack;
        stack.getDimBounds(finalClipRect);

        if (StackId.tasksAreFloating(stack.mStackId)) {
            w.expandForSurfaceInsets(finalClipRect);
        }

        // We may be applying a magnification spec to all windows,
        // simulating a transformation in screen space, in which case
        // we need to transform all other screen space values...including
        // the final crop. This is kind of messed up and we should look
        // in to actually transforming screen-space via a parent-layer.
        // b/38322835
        MagnificationSpec spec = getMagnificationSpec();
        if (spec != null && !spec.isNop()) {
            Matrix transform = mWin.mTmpMatrix;
            RectF finalCrop = mService.mTmpRectF;
            transform.reset();
            transform.postScale(spec.scale, spec.scale);
            transform.postTranslate(-spec.offsetX, -spec.offsetY);
            transform.mapRect(finalCrop);
            finalClipRect.top = (int)finalCrop.top;
            finalClipRect.left = (int)finalCrop.left;
            finalClipRect.right = (int)finalClipRect.right;
            finalClipRect.bottom = (int)finalClipRect.bottom;
        }

        return true;
    }

    /**
     * Calculate the window-space crop rect and fill clipRect.
     * @return true if clipRect has been filled otherwise, no window space crop should be applied.
     */
    private boolean calculateCrop(Rect clipRect) {
        final WindowState w = mWin;
        final DisplayContent displayContent = w.getDisplayContent();
        clipRect.setEmpty();

        if (displayContent == null) {
            return false;
        }

        if (w.inPinnedWorkspace()) {
            return false;
        }

        // If we're animating, the wallpaper should only
        // be updated at the end of the animation.
        if (w.mAttrs.type == TYPE_WALLPAPER) {
            return false;
        }

        if (DEBUG_WINDOW_CROP) Slog.d(TAG,
                "Updating crop win=" + w + " mLastCrop=" + mLastClipRect);

        w.calculatePolicyCrop(mSystemDecorRect);

        if (DEBUG_WINDOW_CROP) Slog.d(TAG, "Applying decor to crop win=" + w + " mDecorFrame="
                + w.mDecorFrame + " mSystemDecorRect=" + mSystemDecorRect);

        final Task task = w.getTask();
        final boolean fullscreen = w.fillsDisplay() || (task != null && task.isFullscreen());
        final boolean isFreeformResizing =
                w.isDragResizing() && w.getResizeMode() == DRAG_RESIZE_MODE_FREEFORM;

        // We use the clip rect as provided by the tranformation for non-fullscreen windows to
        // avoid premature clipping with the system decor rect.
        clipRect.set((mHasClipRect && !fullscreen) ? mClipRect : mSystemDecorRect);
        if (DEBUG_WINDOW_CROP) Slog.d(TAG, "win=" + w + " Initial clip rect: " + clipRect
                + " mHasClipRect=" + mHasClipRect + " fullscreen=" + fullscreen);

        if (isFreeformResizing && !w.isChildWindow()) {
            // For freeform resizing non child windows, we are using the big surface positioned
            // at 0,0. Thus we must express the crop in that coordinate space.
            clipRect.offset(w.mShownPosition.x, w.mShownPosition.y);
        }

        w.expandForSurfaceInsets(clipRect);

        if (mHasClipRect && fullscreen) {
            // We intersect the clip rect specified by the transformation with the expanded system
            // decor rect to prevent artifacts from drawing during animation if the transformation
            // clip rect extends outside the system decor rect.
            clipRect.intersect(mClipRect);
        }
        // The clip rect was generated assuming (0,0) as the window origin,
        // so we need to translate to match the actual surface coordinates.
        clipRect.offset(w.mAttrs.surfaceInsets.left, w.mAttrs.surfaceInsets.top);

        if (!useFinalClipRect()) {
            adjustCropToStackBounds(clipRect, isFreeformResizing);
        }
        if (DEBUG_WINDOW_CROP) Slog.d(TAG,
                "win=" + w + " Clip rect after stack adjustment=" + clipRect);

        w.transformClipRectFromScreenToSurfaceSpace(clipRect);

        return true;
    }

    private void applyCrop(Rect clipRect, Rect finalClipRect, boolean recoveringMemory) {
        if (DEBUG_WINDOW_CROP) Slog.d(TAG, "applyCrop: win=" + mWin
                + " clipRect=" + clipRect + " finalClipRect=" + finalClipRect);
        if (clipRect != null) {
            if (!clipRect.equals(mLastClipRect)) {
                mLastClipRect.set(clipRect);
                mSurfaceController.setCropInTransaction(clipRect, recoveringMemory);
            }
        } else {
            mSurfaceController.clearCropInTransaction(recoveringMemory);
        }

        if (finalClipRect == null) {
            finalClipRect = mService.mTmpRect;
            finalClipRect.setEmpty();
        }
        if (!finalClipRect.equals(mLastFinalClipRect)) {
            mLastFinalClipRect.set(finalClipRect);
            mSurfaceController.setFinalCropInTransaction(finalClipRect);
            if (mDestroyPreservedSurfaceUponRedraw && mPendingDestroySurface != null) {
                mPendingDestroySurface.setFinalCropInTransaction(finalClipRect);
            }
        }
    }

    private int resolveStackClip() {
        // App animation overrides window animation stack clip mode.
        if (mAppAnimator != null && mAppAnimator.animation != null) {
            return mAppAnimator.getStackClip();
        } else {
            return mStackClip;
        }
    }

    private boolean shouldCropToStackBounds() {
        final WindowState w = mWin;
        final DisplayContent displayContent = w.getDisplayContent();
        if (displayContent != null && !displayContent.isDefaultDisplay) {
            // There are some windows that live on other displays while their app and main window
            // live on the default display (e.g. casting...). We don't want to crop this windows
            // to the stack bounds which is only currently supported on the default display.
            // TODO(multi-display): Need to support cropping to stack bounds on other displays
            // when we have stacks on other displays.
            return false;
        }

        final Task task = w.getTask();
        if (task == null || !task.cropWindowsToStackBounds()) {
            return false;
        }

        final int stackClip = resolveStackClip();

        // It's animating and we don't want to clip it to stack bounds during animation - abort.
        if (isAnimationSet() && stackClip == STACK_CLIP_NONE) {
            return false;
        }
        return true;
    }

    private void adjustCropToStackBounds(Rect clipRect,
            boolean isFreeformResizing) {
        final WindowState w = mWin;

        if (!shouldCropToStackBounds()) {
            return;
        }

        final TaskStack stack = w.getTask().mStack;
        stack.getDimBounds(mTmpStackBounds);
        final Rect surfaceInsets = w.getAttrs().surfaceInsets;
        // When we resize we use the big surface approach, which means we can't trust the
        // window frame bounds anymore. Instead, the window will be placed at 0, 0, but to avoid
        // hardcoding it, we use surface coordinates.
        final int frameX = isFreeformResizing ? (int) mSurfaceController.getX() :
                w.mFrame.left + mWin.mXOffset - surfaceInsets.left;
        final int frameY = isFreeformResizing ? (int) mSurfaceController.getY() :
                w.mFrame.top + mWin.mYOffset - surfaceInsets.top;

        // We need to do some acrobatics with surface position, because their clip region is
        // relative to the inside of the surface, but the stack bounds aren't.
        if (StackId.hasWindowShadow(stack.mStackId)
                && !StackId.isTaskResizeAllowed(stack.mStackId)) {
                // The windows in this stack display drop shadows and the fill the entire stack
                // area. Adjust the stack bounds we will use to cropping take into account the
                // offsets we use to display the drop shadow so it doesn't get cropped.
                mTmpStackBounds.inset(-surfaceInsets.left, -surfaceInsets.top,
                        -surfaceInsets.right, -surfaceInsets.bottom);
        }

        clipRect.left = Math.max(0,
                Math.max(mTmpStackBounds.left, frameX + clipRect.left) - frameX);
        clipRect.top = Math.max(0,
                Math.max(mTmpStackBounds.top, frameY + clipRect.top) - frameY);
        clipRect.right = Math.max(0,
                Math.min(mTmpStackBounds.right, frameX + clipRect.right) - frameX);
        clipRect.bottom = Math.max(0,
                Math.min(mTmpStackBounds.bottom, frameY + clipRect.bottom) - frameY);
    }

    void setSurfaceBoundariesLocked(final boolean recoveringMemory) {
        if (mSurfaceController == null) {
            return;
        }

        final WindowState w = mWin;
        final LayoutParams attrs = mWin.getAttrs();
        final Task task = w.getTask();

        // We got resized, so block all updates until we got the new surface.
        if (w.isResizedWhileNotDragResizing() && !w.isGoneForLayoutLw()) {
            return;
        }

        mTmpSize.set(w.mShownPosition.x, w.mShownPosition.y, 0, 0);
        calculateSurfaceBounds(w, attrs);

        mExtraHScale = (float) 1.0;
        mExtraVScale = (float) 1.0;

        boolean wasForceScaled = mForceScaleUntilResize;
        boolean wasSeamlesslyRotated = w.mSeamlesslyRotated;

        // Once relayout has been called at least once, we need to make sure
        // we only resize the client surface during calls to relayout. For
        // clients which use indeterminate measure specs (MATCH_PARENT),
        // we may try and change their window size without a call to relayout.
        // However, this would be unsafe, as the client may be in the middle
        // of producing a frame at the old size, having just completed layout
        // to find the surface size changed underneath it.
        if (!w.mRelayoutCalled || w.mInRelayout) {
            mSurfaceResized = mSurfaceController.setSizeInTransaction(
                    mTmpSize.width(), mTmpSize.height(), recoveringMemory);
        } else {
            mSurfaceResized = false;
        }
        mForceScaleUntilResize = mForceScaleUntilResize && !mSurfaceResized;
        // If we are undergoing seamless rotation, the surface has already
        // been set up to persist at it's old location. We need to freeze
        // updates until a resize occurs.
        mService.markForSeamlessRotation(w, w.mSeamlesslyRotated && !mSurfaceResized);

        Rect clipRect = null, finalClipRect = null;
        if (calculateCrop(mTmpClipRect)) {
            clipRect = mTmpClipRect;
        }
        if (calculateFinalCrop(mTmpFinalClipRect)) {
            finalClipRect = mTmpFinalClipRect;
        }

        float surfaceWidth = mSurfaceController.getWidth();
        float surfaceHeight = mSurfaceController.getHeight();

        if (isForceScaled()) {
            int hInsets = attrs.surfaceInsets.left + attrs.surfaceInsets.right;
            int vInsets = attrs.surfaceInsets.top + attrs.surfaceInsets.bottom;
            float surfaceContentWidth = surfaceWidth - hInsets;
            float surfaceContentHeight = surfaceHeight - vInsets;
            if (!mForceScaleUntilResize) {
                mSurfaceController.forceScaleableInTransaction(true);
            }

            int posX = mTmpSize.left;
            int posY = mTmpSize.top;
            task.mStack.getDimBounds(mTmpStackBounds);

            boolean allowStretching = false;
            task.mStack.getFinalAnimationSourceHintBounds(mTmpSourceBounds);
            // If we don't have source bounds, we can attempt to use the content insets
            // in the following scenario:
            //    1. We have content insets.
            //    2. We are not transitioning to full screen
            // We have to be careful to check "lastAnimatingBoundsWasToFullscreen" rather than
            // the mBoundsAnimating state, as we may have already left it and only be here
            // because of the force-scale until resize state.
            if (mTmpSourceBounds.isEmpty() && (mWin.mLastRelayoutContentInsets.width() > 0
                    || mWin.mLastRelayoutContentInsets.height() > 0)
                        && !task.mStack.lastAnimatingBoundsWasToFullscreen()) {
                mTmpSourceBounds.set(task.mStack.mPreAnimationBounds);
                mTmpSourceBounds.inset(mWin.mLastRelayoutContentInsets);
                allowStretching = true;
            }
            if (!mTmpSourceBounds.isEmpty()) {
                // Get the final target stack bounds, if we are not animating, this is just the
                // current stack bounds
                task.mStack.getFinalAnimationBounds(mTmpAnimatingBounds);

                // Calculate the current progress and interpolate the difference between the target
                // and source bounds
                float finalWidth = mTmpAnimatingBounds.width();
                float initialWidth = mTmpSourceBounds.width();
                float tw = (surfaceContentWidth - mTmpStackBounds.width())
                        / (surfaceContentWidth - mTmpAnimatingBounds.width());
                float th = tw;
                mExtraHScale = (initialWidth + tw * (finalWidth - initialWidth)) / initialWidth;
                if (allowStretching) {
                    float finalHeight = mTmpAnimatingBounds.height();
                    float initialHeight = mTmpSourceBounds.height();
                    th = (surfaceContentHeight - mTmpStackBounds.height())
                        / (surfaceContentHeight - mTmpAnimatingBounds.height());
                    mExtraVScale = (initialHeight + tw * (finalHeight - initialHeight))
                            / initialHeight;
                } else {
                    mExtraVScale = mExtraHScale;
                }

                // Adjust the position to account for the inset bounds
                posX -= (int) (tw * mExtraHScale * mTmpSourceBounds.left);
                posY -= (int) (th * mExtraVScale * mTmpSourceBounds.top);

                // Always clip to the stack bounds since the surface can be larger with the current
                // scale
                clipRect = null;
                finalClipRect = mTmpStackBounds;
            } else {
                // We want to calculate the scaling based on the content area, not based on
                // the entire surface, so that we scale in sync with windows that don't have insets.
                mExtraHScale = mTmpStackBounds.width() / surfaceContentWidth;
                mExtraVScale = mTmpStackBounds.height() / surfaceContentHeight;

                // Since we are scaled to fit in our previously desired crop, we can now
                // expose the whole window in buffer space, and not risk extending
                // past where the system would have cropped us
                clipRect = null;
                finalClipRect = null;
            }

            // In the case of ForceScaleToStack we scale entire tasks together,
            // and so we need to scale our offsets relative to the task bounds
            // or parent and child windows would fall out of alignment.
            posX -= (int) (attrs.x * (1 - mExtraHScale));
            posY -= (int) (attrs.y * (1 - mExtraVScale));

            // Imagine we are scaling down. As we scale the buffer down, we decrease the
            // distance between the surface top left, and the start of the surface contents
            // (previously it was surfaceInsets.left pixels in screen space but now it
            // will be surfaceInsets.left*mExtraHScale). This means in order to keep the
            // non inset content at the same position, we have to shift the whole window
            // forward. Likewise for scaling up, we've increased this distance, and we need
            // to shift by a negative number to compensate.
            posX += attrs.surfaceInsets.left * (1 - mExtraHScale);
            posY += attrs.surfaceInsets.top * (1 - mExtraVScale);

            mSurfaceController.setPositionInTransaction((float) Math.floor(posX),
                    (float) Math.floor(posY), recoveringMemory);

            // Various surfaces in the scaled stack may resize at different times.
            // We need to ensure for each surface, that we disable transformation matrix
            // scaling in the same transaction which we resize the surface in.
            // As we are in SCALING_MODE_SCALE_TO_WINDOW, SurfaceFlinger will
            // then take over the scaling until the new buffer arrives, and things
            // will be seamless.
            mForceScaleUntilResize = true;
        } else {
            if (!w.mSeamlesslyRotated) {
                mSurfaceController.setPositionInTransaction(mTmpSize.left, mTmpSize.top,
                        recoveringMemory);
            }
        }

        // If we are ending the scaling mode. We switch to SCALING_MODE_FREEZE
        // to prevent further updates until buffer latch.
        // When ending both force scaling, and seamless rotation, we need to freeze
        // the Surface geometry until a buffer comes in at the new size (normally position and crop
        // are unfrozen). setGeometryAppliesWithResizeInTransaction accomplishes this for us.
        if ((wasForceScaled && !mForceScaleUntilResize) ||
                (wasSeamlesslyRotated && !w.mSeamlesslyRotated)) {
            mSurfaceController.setGeometryAppliesWithResizeInTransaction(true);
            mSurfaceController.forceScaleableInTransaction(false);
        }

        if (!w.mSeamlesslyRotated) {
            applyCrop(clipRect, finalClipRect, recoveringMemory);
            mSurfaceController.setMatrixInTransaction(mDsDx * w.mHScale * mExtraHScale,
                    mDtDx * w.mVScale * mExtraVScale,
                    mDtDy * w.mHScale * mExtraHScale,
                    mDsDy * w.mVScale * mExtraVScale, recoveringMemory);
        }

        if (mSurfaceResized) {
            mReportSurfaceResized = true;
            mAnimator.setPendingLayoutChanges(w.getDisplayId(),
                    WindowManagerPolicy.FINISH_LAYOUT_REDO_WALLPAPER);
            w.applyDimLayerIfNeeded();
        }
    }

    /**
     * Get rect of the task this window is currently in. If there is no task, rect will be set to
     * empty.
     */
    void getContainerRect(Rect rect) {
        final Task task = mWin.getTask();
        if (task != null) {
            task.getDimBounds(rect);
        } else {
            rect.left = rect.top = rect.right = rect.bottom = 0;
        }
    }

    void prepareSurfaceLocked(final boolean recoveringMemory) {
        final WindowState w = mWin;
        if (!hasSurface()) {

            // There is no need to wait for an animation change if our window is gone for layout
            // already as we'll never be visible.
            if (w.getOrientationChanging() && w.isGoneForLayoutLw()) {
                if (DEBUG_ORIENTATION) {
                    Slog.v(TAG, "Orientation change skips hidden " + w);
                }
                w.setOrientationChanging(false);
            }
            return;
        }

        // Do not change surface properties of opening apps if we are waiting for the
        // transition to be ready. transitionGoodToGo could be not ready even after all
        // opening apps are drawn. It's only waiting on isFetchingAppTransitionsSpecs()
        // to get the animation spec. (For example, go into Recents and immediately open
        // the same app again before the app's surface is destroyed or saved, the surface
        // is always ready in the whole process.) If we go ahead here, the opening app
        // will be shown with the full size before the correct animation spec arrives.
        if (isWaitingForOpening()) {
            return;
        }

        boolean displayed = false;

        computeShownFrameLocked();

        setSurfaceBoundariesLocked(recoveringMemory);

        if (mIsWallpaper && !mWin.mWallpaperVisible) {
            // Wallpaper is no longer visible and there is no wp target => hide it.
            hide("prepareSurfaceLocked");
        } else if (w.isParentWindowHidden() || !w.isOnScreen()) {
            hide("prepareSurfaceLocked");
            mWallpaperControllerLocked.hideWallpapers(w);

            // If we are waiting for this window to handle an orientation change. If this window is
            // really hidden (gone for layout), there is no point in still waiting for it.
            // Note that this does introduce a potential glitch if the window becomes unhidden
            // before it has drawn for the new orientation.
            if (w.getOrientationChanging() && w.isGoneForLayoutLw()) {
                w.setOrientationChanging(false);
                if (DEBUG_ORIENTATION) Slog.v(TAG,
                        "Orientation change skips hidden " + w);
            }
        } else if (mLastLayer != mAnimLayer
                || mLastAlpha != mShownAlpha
                || mLastDsDx != mDsDx
                || mLastDtDx != mDtDx
                || mLastDsDy != mDsDy
                || mLastDtDy != mDtDy
                || w.mLastHScale != w.mHScale
                || w.mLastVScale != w.mVScale
                || mLastHidden) {
            displayed = true;
            mLastAlpha = mShownAlpha;
            mLastLayer = mAnimLayer;
            mLastDsDx = mDsDx;
            mLastDtDx = mDtDx;
            mLastDsDy = mDsDy;
            mLastDtDy = mDtDy;
            w.mLastHScale = w.mHScale;
            w.mLastVScale = w.mVScale;
            if (SHOW_TRANSACTIONS) WindowManagerService.logSurface(w,
                    "controller=" + mSurfaceController +
                    "alpha=" + mShownAlpha + " layer=" + mAnimLayer
                    + " matrix=[" + mDsDx + "*" + w.mHScale
                    + "," + mDtDx + "*" + w.mVScale
                    + "][" + mDtDy + "*" + w.mHScale
                    + "," + mDsDy + "*" + w.mVScale + "]", false);

            boolean prepared =
                mSurfaceController.prepareToShowInTransaction(mShownAlpha,
                        mDsDx * w.mHScale * mExtraHScale,
                        mDtDx * w.mVScale * mExtraVScale,
                        mDtDy * w.mHScale * mExtraHScale,
                        mDsDy * w.mVScale * mExtraVScale,
                        recoveringMemory);
            mSurfaceController.setLayer(mAnimLayer);

            if (prepared && mDrawState == HAS_DRAWN) {
                if (mLastHidden) {
                    if (showSurfaceRobustlyLocked()) {
                        markPreservedSurfaceForDestroy();
                        mAnimator.requestRemovalOfReplacedWindows(w);
                        mLastHidden = false;
                        if (mIsWallpaper) {
                            w.dispatchWallpaperVisibility(true);
                        }
                        // This draw means the difference between unique content and mirroring.
                        // Run another pass through performLayout to set mHasContent in the
                        // LogicalDisplay.
                        mAnimator.setPendingLayoutChanges(w.getDisplayId(),
                                WindowManagerPolicy.FINISH_LAYOUT_REDO_ANIM);
                    } else {
                        w.setOrientationChanging(false);
                    }
                }
                // We process mTurnOnScreen even for windows which have already
                // been shown, to handle cases where windows are not necessarily
                // hidden while the screen is turning off.
                // TODO(b/63773439): These cases should be eliminated, though we probably still
                // want to process mTurnOnScreen in this way for clarity.
                if (mWin.mTurnOnScreen &&
                        (mWin.mAppToken == null || mWin.mAppToken.canTurnScreenOn())) {
                    if (DEBUG_VISIBILITY) Slog.v(TAG, "Show surface turning screen on: " + mWin);
                    mWin.mTurnOnScreen = false;

                    // The window should only turn the screen on once per resume, but
                    // prepareSurfaceLocked can be called multiple times. Set canTurnScreenOn to
                    // false so the window doesn't turn the screen on again during this resume.
                    if (mWin.mAppToken != null) {
                        mWin.mAppToken.setCanTurnScreenOn(false);
                    }

                    // We do not add {@code SET_TURN_ON_SCREEN} when the screen is already
                    // interactive as the value may persist until the next animation, which could
                    // potentially occurring while turning off the screen. This would lead to the
                    // screen incorrectly turning back on.
                    if (!mService.mPowerManager.isInteractive()) {
                        mAnimator.mBulkUpdateParams |= SET_TURN_ON_SCREEN;
                    }
                }
            }
            if (hasSurface()) {
                w.mToken.hasVisible = true;
            }
        } else {
            if (DEBUG_ANIM && isAnimationSet()) {
                Slog.v(TAG, "prepareSurface: No changes in animation for " + this);
            }
            displayed = true;
        }

        if (w.getOrientationChanging()) {
            if (!w.isDrawnLw()) {
                mAnimator.mBulkUpdateParams &= ~SET_ORIENTATION_CHANGE_COMPLETE;
                mAnimator.mLastWindowFreezeSource = w;
                if (DEBUG_ORIENTATION) Slog.v(TAG,
                        "Orientation continue waiting for draw in " + w);
            } else {
                w.setOrientationChanging(false);
                if (DEBUG_ORIENTATION) Slog.v(TAG, "Orientation change complete in " + w);
            }
        }

        if (displayed) {
            w.mToken.hasVisible = true;
        }
    }

    void setTransparentRegionHintLocked(final Region region) {
        if (mSurfaceController == null) {
            Slog.w(TAG, "setTransparentRegionHint: null mSurface after mHasSurface true");
            return;
        }
        mSurfaceController.setTransparentRegionHint(region);
    }

    void setWallpaperOffset(Point shownPosition) {
        final LayoutParams attrs = mWin.getAttrs();
        final int left = shownPosition.x - attrs.surfaceInsets.left;
        final int top = shownPosition.y - attrs.surfaceInsets.top;

        try {
            if (SHOW_LIGHT_TRANSACTIONS) Slog.i(TAG, ">>> OPEN TRANSACTION setWallpaperOffset");
            mService.openSurfaceTransaction();
            mSurfaceController.setPositionInTransaction(mWin.mFrame.left + left,
                    mWin.mFrame.top + top, false);
            applyCrop(null, null, false);
        } catch (RuntimeException e) {
            Slog.w(TAG, "Error positioning surface of " + mWin
                    + " pos=(" + left + "," + top + ")", e);
        } finally {
            mService.closeSurfaceTransaction();
            if (SHOW_LIGHT_TRANSACTIONS) Slog.i(TAG,
                    "<<< CLOSE TRANSACTION setWallpaperOffset");
        }
    }

    /**
     * Try to change the pixel format without recreating the surface. This
     * will be common in the case of changing from PixelFormat.OPAQUE to
     * PixelFormat.TRANSLUCENT in the hardware-accelerated case as both
     * requested formats resolve to the same underlying SurfaceControl format
     * @return True if format was succesfully changed, false otherwise
     */
    boolean tryChangeFormatInPlaceLocked() {
        if (mSurfaceController == null) {
            return false;
        }
        final LayoutParams attrs = mWin.getAttrs();
        final boolean isHwAccelerated = (attrs.flags & FLAG_HARDWARE_ACCELERATED) != 0;
        final int format = isHwAccelerated ? PixelFormat.TRANSLUCENT : attrs.format;
        if (format == mSurfaceFormat) {
            setOpaqueLocked(!PixelFormat.formatHasAlpha(attrs.format));
            return true;
        }
        return false;
    }

    void setOpaqueLocked(boolean isOpaque) {
        if (mSurfaceController == null) {
            return;
        }
        mSurfaceController.setOpaque(isOpaque);
    }

    void setSecureLocked(boolean isSecure) {
        if (mSurfaceController == null) {
            return;
        }
        mSurfaceController.setSecure(isSecure);
    }

    /**
     * Have the surface flinger show a surface, robustly dealing with
     * error conditions.  In particular, if there is not enough memory
     * to show the surface, then we will try to get rid of other surfaces
     * in order to succeed.
     *
     * @return Returns true if the surface was successfully shown.
     */
    private boolean showSurfaceRobustlyLocked() {
        final Task task = mWin.getTask();
        if (task != null && StackId.windowsAreScaleable(task.mStack.mStackId)) {
            mSurfaceController.forceScaleableInTransaction(true);
        }

        boolean shown = mSurfaceController.showRobustlyInTransaction();
        if (!shown)
            return false;

        return true;
    }

    void applyEnterAnimationLocked() {
        // If we are the new part of a window replacement transition and we have requested
        // not to animate, we instead want to make it seamless, so we don't want to apply
        // an enter transition.
        if (mWin.mSkipEnterAnimationForSeamlessReplacement) {
            return;
        }
        final int transit;
        if (mEnterAnimationPending) {
            mEnterAnimationPending = false;
            transit = WindowManagerPolicy.TRANSIT_ENTER;
        } else {
            transit = WindowManagerPolicy.TRANSIT_SHOW;
        }
        applyAnimationLocked(transit, true);
        //TODO (multidisplay): Magnification is supported only for the default display.
        if (mService.mAccessibilityController != null
                && mWin.getDisplayId() == DEFAULT_DISPLAY) {
            mService.mAccessibilityController.onWindowTransitionLocked(mWin, transit);
        }
    }

    /**
     * Choose the correct animation and set it to the passed WindowState.
     * @param transit If AppTransition.TRANSIT_PREVIEW_DONE and the app window has been drawn
     *      then the animation will be app_starting_exit. Any other value loads the animation from
     *      the switch statement below.
     * @param isEntrance The animation type the last time this was called. Used to keep from
     *      loading the same animation twice.
     * @return true if an animation has been loaded.
     */
    boolean applyAnimationLocked(int transit, boolean isEntrance) {
        if (mLocalAnimating && mAnimationIsEntrance == isEntrance) {
            // If we are trying to apply an animation, but already running
            // an animation of the same type, then just leave that one alone.
            return true;
        }

        // Only apply an animation if the display isn't frozen.  If it is
        // frozen, there is no reason to animate and it can cause strange
        // artifacts when we unfreeze the display if some different animation
        // is running.
        Trace.traceBegin(Trace.TRACE_TAG_WINDOW_MANAGER, "WSA#applyAnimationLocked");
        if (mWin.mToken.okToAnimate()) {
            int anim = mPolicy.selectAnimationLw(mWin, transit);
            int attr = -1;
            Animation a = null;
            if (anim != 0) {
                a = anim != -1 ? AnimationUtils.loadAnimation(mContext, anim) : null;
            } else {
                switch (transit) {
                    case WindowManagerPolicy.TRANSIT_ENTER:
                        attr = com.android.internal.R.styleable.WindowAnimation_windowEnterAnimation;
                        break;
                    case WindowManagerPolicy.TRANSIT_EXIT:
                        attr = com.android.internal.R.styleable.WindowAnimation_windowExitAnimation;
                        break;
                    case WindowManagerPolicy.TRANSIT_SHOW:
                        attr = com.android.internal.R.styleable.WindowAnimation_windowShowAnimation;
                        break;
                    case WindowManagerPolicy.TRANSIT_HIDE:
                        attr = com.android.internal.R.styleable.WindowAnimation_windowHideAnimation;
                        break;
                }
                if (attr >= 0) {
                    a = mService.mAppTransition.loadAnimationAttr(mWin.mAttrs, attr);
                }
            }
            if (DEBUG_ANIM) Slog.v(TAG,
                    "applyAnimation: win=" + this
                    + " anim=" + anim + " attr=0x" + Integer.toHexString(attr)
                    + " a=" + a
                    + " transit=" + transit
                    + " isEntrance=" + isEntrance + " Callers " + Debug.getCallers(3));
            if (a != null) {
                if (DEBUG_ANIM) logWithStack(TAG, "Loaded animation " + a + " for " + this);
                setAnimation(a);
                mAnimationIsEntrance = isEntrance;
            }
        } else {
            clearAnimation();
        }
        Trace.traceEnd(Trace.TRACE_TAG_WINDOW_MANAGER);

        if (mWin.mAttrs.type == TYPE_INPUT_METHOD) {
            mWin.getDisplayContent().adjustForImeIfNeeded();
            if (isEntrance) {
                mWin.setDisplayLayoutNeeded();
                mService.mWindowPlacerLocked.requestTraversal();
            }
        }
        return mAnimation != null;
    }

    private void applyFadeoutDuringKeyguardExitAnimation() {
        long startTime = mAnimation.getStartTime();
        long duration = mAnimation.getDuration();
        long elapsed = mLastAnimationTime - startTime;
        long fadeDuration = duration - elapsed;
        if (fadeDuration <= 0) {
            // Never mind, this would be no visible animation, so abort the animation change.
            return;
        }
        AnimationSet newAnimation = new AnimationSet(false /* shareInterpolator */);
        newAnimation.setDuration(duration);
        newAnimation.setStartTime(startTime);
        newAnimation.addAnimation(mAnimation);
        Animation fadeOut = AnimationUtils.loadAnimation(
                mContext, com.android.internal.R.anim.app_starting_exit);
        fadeOut.setDuration(fadeDuration);
        fadeOut.setStartOffset(elapsed);
        newAnimation.addAnimation(fadeOut);
        newAnimation.initialize(mWin.mFrame.width(), mWin.mFrame.height(), mAnimDx, mAnimDy);
        mAnimation = newAnimation;
    }

    public void dump(PrintWriter pw, String prefix, boolean dumpAll) {
        if (mAnimating || mLocalAnimating || mAnimationIsEntrance
                || mAnimation != null) {
            pw.print(prefix); pw.print("mAnimating="); pw.print(mAnimating);
                    pw.print(" mLocalAnimating="); pw.print(mLocalAnimating);
                    pw.print(" mAnimationIsEntrance="); pw.print(mAnimationIsEntrance);
                    pw.print(" mAnimation="); pw.print(mAnimation);
                    pw.print(" mStackClip="); pw.println(mStackClip);
        }
        if (mHasTransformation || mHasLocalTransformation) {
            pw.print(prefix); pw.print("XForm: has=");
                    pw.print(mHasTransformation);
                    pw.print(" hasLocal="); pw.print(mHasLocalTransformation);
                    pw.print(" "); mTransformation.printShortString(pw);
                    pw.println();
        }
        if (mSurfaceController != null) {
            mSurfaceController.dump(pw, prefix, dumpAll);
        }
        if (dumpAll) {
            pw.print(prefix); pw.print("mDrawState="); pw.print(drawStateToString());
            pw.print(prefix); pw.print(" mLastHidden="); pw.println(mLastHidden);
            pw.print(prefix); pw.print("mSystemDecorRect="); mSystemDecorRect.printShortString(pw);
            pw.print(" last="); mLastSystemDecorRect.printShortString(pw);
            pw.print(" mHasClipRect="); pw.print(mHasClipRect);
            pw.print(" mLastClipRect="); mLastClipRect.printShortString(pw);

            if (!mLastFinalClipRect.isEmpty()) {
                pw.print(" mLastFinalClipRect="); mLastFinalClipRect.printShortString(pw);
            }
            pw.println();
        }

        if (mPendingDestroySurface != null) {
            pw.print(prefix); pw.print("mPendingDestroySurface=");
                    pw.println(mPendingDestroySurface);
        }
        if (mSurfaceResized || mSurfaceDestroyDeferred) {
            pw.print(prefix); pw.print("mSurfaceResized="); pw.print(mSurfaceResized);
                    pw.print(" mSurfaceDestroyDeferred="); pw.println(mSurfaceDestroyDeferred);
        }
        if (mShownAlpha != 1 || mAlpha != 1 || mLastAlpha != 1) {
            pw.print(prefix); pw.print("mShownAlpha="); pw.print(mShownAlpha);
                    pw.print(" mAlpha="); pw.print(mAlpha);
                    pw.print(" mLastAlpha="); pw.println(mLastAlpha);
        }
        if (mHaveMatrix || mWin.mGlobalScale != 1) {
            pw.print(prefix); pw.print("mGlobalScale="); pw.print(mWin.mGlobalScale);
                    pw.print(" mDsDx="); pw.print(mDsDx);
                    pw.print(" mDtDx="); pw.print(mDtDx);
                    pw.print(" mDtDy="); pw.print(mDtDy);
                    pw.print(" mDsDy="); pw.println(mDsDy);
        }
        if (mAnimationStartDelayed) {
            pw.print(prefix); pw.print("mAnimationStartDelayed="); pw.print(mAnimationStartDelayed);
        }
    }

    @Override
    public String toString() {
        StringBuffer sb = new StringBuffer("WindowStateAnimator{");
        sb.append(Integer.toHexString(System.identityHashCode(this)));
        sb.append(' ');
        sb.append(mWin.mAttrs.getTitle());
        sb.append('}');
        return sb.toString();
    }

    void reclaimSomeSurfaceMemory(String operation, boolean secure) {
        mService.mRoot.reclaimSomeSurfaceMemory(this, operation, secure);
    }

    boolean getShown() {
        if (mSurfaceController != null) {
            return mSurfaceController.getShown();
        }
        return false;
    }

    void destroySurface() {
        try {
            if (mSurfaceController != null) {
                mSurfaceController.destroyInTransaction();
            }
        } catch (RuntimeException e) {
            Slog.w(TAG, "Exception thrown when destroying surface " + this
                    + " surface " + mSurfaceController + " session " + mSession + ": " + e);
        } finally {
            mWin.setHasSurface(false);
            mSurfaceController = null;
            mDrawState = NO_SURFACE;
        }
    }

    void setMoveAnimation(int left, int top) {
        final Animation a = AnimationUtils.loadAnimation(mContext,
                com.android.internal.R.anim.window_move_from_decor);
        setAnimation(a);
        mAnimDx = mWin.mLastFrame.left - left;
        mAnimDy = mWin.mLastFrame.top - top;
        mAnimateMove = true;
    }

    void deferTransactionUntilParentFrame(long frameNumber) {
        if (!mWin.isChildWindow()) {
            return;
        }
        mSurfaceController.deferTransactionUntil(
                mWin.getParentWindow().mWinAnimator.mSurfaceController.getHandle(), frameNumber);
    }

    /**
     * Sometimes we need to synchronize the first frame of animation with some external event.
     * To achieve this, we prolong the start of the animation and keep producing the first frame of
     * the animation.
     */
    private long getAnimationFrameTime(Animation animation, long currentTime) {
        if (mAnimationStartDelayed) {
            animation.setStartTime(currentTime);
            return currentTime + 1;
        }
        return currentTime;
    }

    void startDelayingAnimationStart() {
        mAnimationStartDelayed = true;
    }

    void endDelayingAnimationStart() {
        mAnimationStartDelayed = false;
    }

    void seamlesslyRotateWindow(int oldRotation, int newRotation) {
        final WindowState w = mWin;
        if (!w.isVisibleNow() || w.mIsWallpaper) {
            return;
        }

        final Rect cropRect = mService.mTmpRect;
        final Rect displayRect = mService.mTmpRect2;
        final RectF frameRect = mService.mTmpRectF;
        final Matrix transform = mService.mTmpTransform;

        final float x = w.mFrame.left;
        final float y = w.mFrame.top;
        final float width = w.mFrame.width();
        final float height = w.mFrame.height();

        mService.getDefaultDisplayContentLocked().getLogicalDisplayRect(displayRect);
        final float displayWidth = displayRect.width();
        final float displayHeight = displayRect.height();

        // Compute a transform matrix to undo the coordinate space transformation,
        // and present the window at the same physical position it previously occupied.
        final int deltaRotation = DisplayContent.deltaRotation(newRotation, oldRotation);
        DisplayContent.createRotationMatrix(deltaRotation, x, y, displayWidth, displayHeight,
                transform);

        // We just need to apply a rotation matrix to the window. For example
        // if we have a portrait window and rotate to landscape, the window is still portrait
        // and now extends off the bottom of the screen (and only halfway across). Essentially we
        // apply a transform to display the current buffer at it's old position
        // (in the new coordinate space). We then freeze layer updates until the resize
        // occurs, at which point we undo, them.
        mService.markForSeamlessRotation(w, true);
        transform.getValues(mService.mTmpFloats);

        float DsDx = mService.mTmpFloats[Matrix.MSCALE_X];
        float DtDx = mService.mTmpFloats[Matrix.MSKEW_Y];
        float DtDy = mService.mTmpFloats[Matrix.MSKEW_X];
        float DsDy = mService.mTmpFloats[Matrix.MSCALE_Y];
        float nx = mService.mTmpFloats[Matrix.MTRANS_X];
        float ny = mService.mTmpFloats[Matrix.MTRANS_Y];
        mSurfaceController.setPositionInTransaction(nx, ny, false);
        mSurfaceController.setMatrixInTransaction(DsDx * w.mHScale,
                DtDx * w.mVScale,
                DtDy * w.mHScale,
                DsDy * w.mVScale, false);
    }

    void enableSurfaceTrace(FileDescriptor fd) {
        if (mSurfaceController != null) {
            mSurfaceController.installRemoteTrace(fd);
        }
    }

    void disableSurfaceTrace() {
        if (mSurfaceController != null) {
            try {
                mSurfaceController.removeRemoteTrace();
            } catch (ClassCastException e) {
                Slog.e(TAG, "Disable surface trace for " + this + " but its not enabled");
            }
        }
    }

    /** The force-scaled state for a given window can persist past
     * the state for it's stack as the windows complete resizing
     * independently of one another.
     */
    boolean isForceScaled() {
        final Task task = mWin.getTask();
        if (task != null && task.mStack.isForceScaled()) {
            return true;
        }
        return mForceScaleUntilResize;
    }

    void detachChildren() {
        if (mSurfaceController != null) {
            mSurfaceController.detachChildren();
        }
    }
}
