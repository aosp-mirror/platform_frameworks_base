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
import static android.app.ActivityManager.StackId.PINNED_STACK_ID;
import static android.view.Display.DEFAULT_DISPLAY;
import static android.view.WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED;
import static android.view.WindowManager.LayoutParams.FLAG_SCALED;
import static android.view.WindowManager.LayoutParams.TYPE_APPLICATION_STARTING;
import static android.view.WindowManager.LayoutParams.TYPE_INPUT_METHOD;
import static com.android.server.wm.AppWindowAnimator.sDummyAnimation;
import static com.android.server.wm.DragResizeMode.DRAG_RESIZE_MODE_FREEFORM;
import static com.android.server.wm.WindowManagerDebugConfig.DEBUG_ADD_REMOVE;
import static com.android.server.wm.WindowManagerDebugConfig.DEBUG_ANIM;
import static com.android.server.wm.WindowManagerDebugConfig.DEBUG_LAYERS;
import static com.android.server.wm.WindowManagerDebugConfig.DEBUG_LAYOUT_REPEATS;
import static com.android.server.wm.WindowManagerDebugConfig.DEBUG_ORIENTATION;
import static com.android.server.wm.WindowManagerDebugConfig.DEBUG_STARTING_WINDOW;
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
import android.os.RemoteException;
import android.os.Trace;
import android.util.Slog;
import android.view.DisplayInfo;
import android.view.MagnificationSpec;
import android.view.Surface;
import android.view.Surface.OutOfResourcesException;
import android.view.SurfaceControl;
import android.view.WindowManager;
import android.view.WindowManager.LayoutParams;
import android.view.WindowManagerPolicy;
import android.view.animation.Animation;
import android.view.animation.AnimationSet;
import android.view.animation.AnimationUtils;
import android.view.animation.Transformation;

import com.android.server.wm.WindowManagerService.H;

import java.io.PrintWriter;

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
    final WindowStateAnimator mAttachedWinAnimator;
    final WindowAnimator mAnimator;
    AppWindowAnimator mAppAnimator;
    final Session mSession;
    final WindowManagerPolicy mPolicy;
    final Context mContext;
    final boolean mIsWallpaper;
    final WallpaperController mWallpaperControllerLocked;

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
    float mLastDsDx=1, mLastDtDx=0, mLastDsDy=0, mLastDtDy=1;

    boolean mHaveMatrix;

    // Set to true if, when the window gets displayed, it should perform
    // an enter animation.
    boolean mEnterAnimationPending;

    /** Used to indicate that this window is undergoing an enter animation. Used for system
     * windows to make the callback to View.dispatchOnWindowShownCallback(). Set when the
     * window is first added or shown, cleared when the callback has been made. */
    boolean mEnteringAnimation;

    private boolean mAnimationStartDelayed;

    boolean mKeyguardGoingAwayAnimation;
    boolean mKeyguardGoingAwayWithWallpaper;

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
        mAttachedWinAnimator = win.mAttachedWindow == null
                ? null : win.mAttachedWindow.mWinAnimator;
        mAppAnimator = win.mAppToken == null ? null : win.mAppToken.mAppAnimator;
        mSession = win.mSession;
        mAttrType = win.mAttrs.type;
        mIsWallpaper = win.mIsWallpaper;
        mWallpaperControllerLocked = mService.mWallpaperControllerLocked;
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
            mKeyguardGoingAwayAnimation = false;
            mKeyguardGoingAwayWithWallpaper = false;
            mStackClip = STACK_CLIP_BEFORE_ANIM;
        }
    }

    /**
     * Is the window or its container currently set to animate or currently animating?
     */
    boolean isAnimationSet() {
        return mAnimation != null
                || (mAttachedWinAnimator != null && mAttachedWinAnimator.mAnimation != null)
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
            mWin.destroyOrSaveSurface();
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
        if (displayContent != null && mService.okToDisplay()) {
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
        mKeyguardGoingAwayAnimation = false;
        mKeyguardGoingAwayWithWallpaper = false;
        mLocalAnimating = false;
        if (mAnimation != null) {
            mAnimation.cancel();
            mAnimation = null;
        }
        if (mAnimator.mWindowDetachedWallpaper == mWin) {
            mAnimator.mWindowDetachedWallpaper = null;
        }
        mAnimLayer = mWin.mLayer
                + mService.mLayersController.getSpecialWindowAnimLayerAdjustment(mWin);
        if (DEBUG_LAYERS) Slog.v(TAG, "Stepping win " + this + " anim layer: " + mAnimLayer);
        mHasTransformation = false;
        mHasLocalTransformation = false;
        mStackClip = STACK_CLIP_BEFORE_ANIM;
        mWin.checkPolicyVisibilityChange();
        mTransformation.clear();
        if (mDrawState == HAS_DRAWN
                && mWin.mAttrs.type == WindowManager.LayoutParams.TYPE_APPLICATION_STARTING
                && mWin.mAppToken != null
                && mWin.mAppToken.firstWindowDrawn
                && mWin.mAppToken.startingData != null) {
            if (DEBUG_STARTING_WINDOW) Slog.v(TAG, "Finish starting "
                    + mWin.mToken + ": first real window done animating");
            mService.mFinishedStarting.add(mWin.mAppToken);
            mService.mH.sendEmptyMessage(H.FINISHED_STARTING);
        } else if (mAttrType == LayoutParams.TYPE_STATUS_BAR && mWin.mPolicyVisibility) {
            // Upon completion of a not-visible to visible status bar animation a relayout is
            // required.
            if (displayContent != null) {
                displayContent.layoutNeeded = true;
            }
        }

        finishExit();
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

    void finishExit() {
        if (DEBUG_ANIM) Slog.v(
                TAG, "finishExit in " + this
                + ": exiting=" + mWin.mAnimatingExit
                + " remove=" + mWin.mRemoveOnExit
                + " windowAnimating=" + isWindowAnimationSet());

        if (!mWin.mChildWindows.isEmpty()) {
            // Copying to a different list as multiple children can be removed.
            final WindowList childWindows = new WindowList(mWin.mChildWindows);
            for (int i = childWindows.size() - 1; i >= 0; i--) {
                childWindows.get(i).mWinAnimator.finishExit();
            }
        }

        if (mEnteringAnimation) {
            mEnteringAnimation = false;
            mService.requestTraversal();
            // System windows don't have an activity and an app token as a result, but need a way
            // to be informed about their entrance animation end.
            if (mWin.mAppToken == null) {
                try {
                    mWin.mClient.dispatchWindowShown();
                } catch (RemoteException e) {
                }
            }
        }

        if (!isWindowAnimationSet()) {
            //TODO (multidisplay): Accessibility is supported only for the default display.
            if (mService.mAccessibilityController != null
                    && mWin.getDisplayId() == DEFAULT_DISPLAY) {
                mService.mAccessibilityController.onSomeWindowResizedOrMovedLocked();
            }
        }

        if (!mWin.mAnimatingExit) {
            return;
        }

        if (isWindowAnimationSet()) {
            return;
        }

        if (WindowManagerService.localLOGV || DEBUG_ADD_REMOVE) Slog.v(TAG,
                "Exit animation finished in " + this + ": remove=" + mWin.mRemoveOnExit);


        mWin.mDestroying = true;

        final boolean hasSurface = hasSurface();
        if (hasSurface) {
            hide("finishExit");
        }

        // If we have an app token, we ask it to destroy the surface for us,
        // so that it can take care to ensure the activity has actually stopped
        // and the surface is not still in use. Otherwise we add the service to
        // mDestroySurface and allow it to be processed in our next transaction.
        if (mWin.mAppToken != null) {
            mWin.mAppToken.destroySurfaces();
        } else {
            if (hasSurface) {
                mService.mDestroySurface.add(mWin);
            }
            if (mWin.mRemoveOnExit) {
                mService.mPendingRemove.add(mWin);
                mWin.mRemoveOnExit = false;
            }
        }
        mWin.mAnimatingExit = false;
        mWallpaperControllerLocked.hideWallpapers(mWin);
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
        if (DEBUG_STARTING_WINDOW &&
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
            result = performShowLocked();
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
        destroyDeferredSurfaceLocked();
        mDestroyPreservedSurfaceUponRedraw = false;
    }

    void markPreservedSurfaceForDestroy() {
        if (mDestroyPreservedSurfaceUponRedraw
                && !mService.mDestroyPreservedSurface.contains(mWin)) {
            mService.mDestroyPreservedSurface.add(mWin);
        }
    }

    WindowSurfaceController createSurfaceLocked() {
        final WindowState w = mWin;
        if (w.hasSavedSurface()) {
            if (DEBUG_ANIM) Slog.i(TAG,
                    "createSurface: " + this + ": called when we had a saved surface");
            w.restoreSavedSurface();
            return mSurfaceController;
        }

        if (mSurfaceController != null) {
            return mSurfaceController;
        }

        w.setHasSurface(false);

        if (DEBUG_ANIM || DEBUG_ORIENTATION) Slog.i(TAG,
                "createSurface " + this + ": mDrawState=DRAW_PENDING");

        mDrawState = DRAW_PENDING;
        if (w.mAppToken != null) {
            if (w.mAppToken.mAppAnimator.animation == null) {
                w.mAppToken.clearAllDrawn();
            } else {
                // Currently animating, persist current state of allDrawn until animation
                // is complete.
                w.mAppToken.deferClearAllDrawn = true;
            }
        }

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
                    width, height, format, flags, this);

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
            mService.reclaimSomeSurfaceMemoryLocked(this, "create", true);
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
        final int layerStack = w.getDisplayContent().getDisplay().getLayerStack();
        mSurfaceController.setPositionAndLayer(mTmpSize.left, mTmpSize.top, layerStack, mAnimLayer);
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

        int i = mWin.mChildWindows.size();
        // When destroying a surface we want to make sure child windows are hidden. If we are
        // preserving the surface until redraw though we intend to swap it out with another surface
        // for resizing. In this case the window always remains visible to the user and the child
        // windows should likewise remain visible.
        while (!mDestroyPreservedSurfaceUponRedraw && i > 0) {
            i--;
            WindowState c = mWin.mChildWindows.get(i);
            c.mAttachedHidden = true;
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
                (mAttachedWinAnimator != null && mAttachedWinAnimator.mHasLocalTransformation)
                ? mAttachedWinAnimator.mTransformation : null;
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
            tmpMatrix.postTranslate(frame.left + mWin.mXOffset, frame.top + mWin.mYOffset);
            if (appTransformation != null) {
                tmpMatrix.postConcat(appTransformation.getMatrix());
            }
            if (screenAnimation) {
                tmpMatrix.postConcat(screenRotationAnimation.getEnterTransformation().getMatrix());
            }

            //TODO (multidisplay): Magnification is supported only for the default display.
            if (mService.mAccessibilityController != null && displayId == DEFAULT_DISPLAY) {
                MagnificationSpec spec = mService.mAccessibilityController
                        .getMagnificationSpecForWindowLocked(mWin);
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
            mDsDy = tmpFloats[Matrix.MSKEW_X];
            mDtDy = tmpFloats[Matrix.MSCALE_Y];
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
                    || (mWin.isIdentityMatrix(mDsDx, mDtDx, mDsDy, mDtDy)
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
        } else if (mIsWallpaper && mService.mWindowPlacerLocked.mWallpaperActionPending) {
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

        MagnificationSpec spec = null;
        //TODO (multidisplay): Magnification is supported only for the default display.
        if (mService.mAccessibilityController != null && displayId == DEFAULT_DISPLAY) {
            spec = mService.mAccessibilityController.getMagnificationSpecForWindowLocked(mWin);
        }
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
            mDsDy = tmpFloats[Matrix.MSKEW_X];
            mDtDy = tmpFloats[Matrix.MSCALE_Y];
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
            mDsDy = 0;
            mDtDy = mWin.mGlobalScale;
        }
    }

    private void calculateSystemDecorRect() {
        final WindowState w = mWin;
        final Rect decorRect = w.mDecorFrame;
        final int width = w.mFrame.width();
        final int height = w.mFrame.height();

        // Compute the offset of the window in relation to the decor rect.
        final int left = w.mXOffset + w.mFrame.left;
        final int top = w.mYOffset + w.mFrame.top;

        // Initialize the decor rect to the entire frame.
        if (w.isDockedResizing() ||
                (w.isChildWindow() && w.mAttachedWindow.isDockedResizing())) {

            // If we are resizing with the divider, the task bounds might be smaller than the
            // stack bounds. The system decor is used to clip to the task bounds, which we don't
            // want in this case in order to avoid holes.
            //
            // We take care to not shrink the width, for surfaces which are larger than
            // the display region. Of course this area will not eventually be visible
            // but if we truncate the width now, we will calculate incorrectly
            // when adjusting to the stack bounds.
            final DisplayInfo displayInfo = w.getDisplayContent().getDisplayInfo();
            mSystemDecorRect.set(0, 0,
                    Math.max(width, displayInfo.logicalWidth),
                    Math.max(height, displayInfo.logicalHeight));
        } else {
            mSystemDecorRect.set(0, 0, width, height);
        }

        // If a freeform window is animating from a position where it would be cutoff, it would be
        // cutoff during the animation. We don't want that, so for the duration of the animation
        // we ignore the decor cropping and depend on layering to position windows correctly.
        final boolean cropToDecor = !(w.inFreeformWorkspace() && w.isAnimatingLw());
        if (cropToDecor) {
            // Intersect with the decor rect, offsetted by window position.
            mSystemDecorRect.intersect(decorRect.left - left, decorRect.top - top,
                    decorRect.right - left, decorRect.bottom - top);
        }

        // If size compatibility is being applied to the window, the
        // surface is scaled relative to the screen.  Also apply this
        // scaling to the crop rect.  We aren't using the standard rect
        // scale function because we want to round things to make the crop
        // always round to a larger rect to ensure we don't crop too
        // much and hide part of the window that should be seen.
        if (w.mEnforceSizeCompat && w.mInvGlobalScale != 1.0f) {
            final float scale = w.mInvGlobalScale;
            mSystemDecorRect.left = (int) (mSystemDecorRect.left * scale - 0.5f);
            mSystemDecorRect.top = (int) (mSystemDecorRect.top * scale - 0.5f);
            mSystemDecorRect.right = (int) ((mSystemDecorRect.right + 1) * scale - 0.5f);
            mSystemDecorRect.bottom = (int) ((mSystemDecorRect.bottom + 1) * scale - 0.5f);
        }
    }

    void calculateSurfaceWindowCrop(Rect clipRect, Rect finalClipRect) {
        final WindowState w = mWin;
        final DisplayContent displayContent = w.getDisplayContent();
        if (displayContent == null) {
            clipRect.setEmpty();
            finalClipRect.setEmpty();
            return;
        }
        final DisplayInfo displayInfo = displayContent.getDisplayInfo();
        if (DEBUG_WINDOW_CROP) Slog.d(TAG,
                "Updating crop win=" + w + " mLastCrop=" + mLastClipRect);

        // Need to recompute a new system decor rect each time.
        if (!w.isDefaultDisplay()) {
            // On a different display there is no system decor.  Crop the window
            // by the screen boundaries.
            mSystemDecorRect.set(0, 0, w.mCompatFrame.width(), w.mCompatFrame.height());
            mSystemDecorRect.intersect(-w.mCompatFrame.left, -w.mCompatFrame.top,
                    displayInfo.logicalWidth - w.mCompatFrame.left,
                    displayInfo.logicalHeight - w.mCompatFrame.top);
        } else if (w.mLayer >= mService.mSystemDecorLayer) {
            // Above the decor layer is easy, just use the entire window.
            mSystemDecorRect.set(0, 0, w.mCompatFrame.width(), w.mCompatFrame.height());
        } else if (w.mDecorFrame.isEmpty()) {
            // Windows without policy decor aren't cropped.
            mSystemDecorRect.set(0, 0, w.mCompatFrame.width(), w.mCompatFrame.height());
        } else if (w.mAttrs.type == LayoutParams.TYPE_WALLPAPER && mAnimator.isAnimating()) {
            // If we're animating, the wallpaper crop should only be updated at the end of the
            // animation.
            mTmpClipRect.set(mSystemDecorRect);
            calculateSystemDecorRect();
            mSystemDecorRect.union(mTmpClipRect);
        } else {
            // Crop to the system decor specified by policy.
            calculateSystemDecorRect();
            if (DEBUG_WINDOW_CROP) Slog.d(TAG, "Applying decor to crop win=" + w + " mDecorFrame="
                    + w.mDecorFrame + " mSystemDecorRect=" + mSystemDecorRect);
        }

        final boolean fullscreen = w.isFrameFullscreen(displayInfo);
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

        // Expand the clip rect for surface insets.
        final WindowManager.LayoutParams attrs = w.mAttrs;
        clipRect.left -= attrs.surfaceInsets.left;
        clipRect.top -= attrs.surfaceInsets.top;
        clipRect.right += attrs.surfaceInsets.right;
        clipRect.bottom += attrs.surfaceInsets.bottom;

        if (mHasClipRect && fullscreen) {
            // We intersect the clip rect specified by the transformation with the expanded system
            // decor rect to prevent artifacts from drawing during animation if the transformation
            // clip rect extends outside the system decor rect.
            clipRect.intersect(mClipRect);
        }
        // The clip rect was generated assuming (0,0) as the window origin,
        // so we need to translate to match the actual surface coordinates.
        clipRect.offset(attrs.surfaceInsets.left, attrs.surfaceInsets.top);

        finalClipRect.setEmpty();
        adjustCropToStackBounds(w, clipRect, finalClipRect, isFreeformResizing);
        if (DEBUG_WINDOW_CROP) Slog.d(TAG,
                "win=" + w + " Clip rect after stack adjustment=" + clipRect);

        w.transformClipRectFromScreenToSurfaceSpace(clipRect);

        // See {@link WindowState#notifyMovedInStack} for why this is necessary.
        if (w.hasJustMovedInStack() && mLastClipRect.isEmpty() && !clipRect.isEmpty()) {
            clipRect.setEmpty();
        }
    }

    void updateSurfaceWindowCrop(Rect clipRect, Rect finalClipRect, boolean recoveringMemory) {
        if (DEBUG_WINDOW_CROP) Slog.d(TAG, "updateSurfaceWindowCrop: win=" + mWin
                + " clipRect=" + clipRect + " finalClipRect=" + finalClipRect);
        if (clipRect != null) {
            if (!clipRect.equals(mLastClipRect)) {
                mLastClipRect.set(clipRect);
                mSurfaceController.setCropInTransaction(clipRect, recoveringMemory);
            }
        } else {
            mSurfaceController.clearCropInTransaction(recoveringMemory);
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
    private void adjustCropToStackBounds(WindowState w, Rect clipRect, Rect finalClipRect,
            boolean isFreeformResizing) {

        final DisplayContent displayContent = w.getDisplayContent();
        if (displayContent != null && !displayContent.isDefaultDisplay) {
            // There are some windows that live on other displays while their app and main window
            // live on the default display (e.g. casting...). We don't want to crop this windows
            // to the stack bounds which is only currently supported on the default display.
            // TODO(multi-display): Need to support cropping to stack bounds on other displays
            // when we have stacks on other displays.
            return;
        }

        final Task task = w.getTask();
        if (task == null || !task.cropWindowsToStackBounds()) {
            return;
        }

        final int stackClip = resolveStackClip();

        // It's animating and we don't want to clip it to stack bounds during animation - abort.
        if (isAnimationSet() && stackClip == STACK_CLIP_NONE) {
            return;
        }

        final WindowState winShowWhenLocked = (WindowState) mPolicy.getWinShowWhenLockedLw();
        if (w == winShowWhenLocked && mPolicy.isKeyguardShowingOrOccluded()) {
            return;
        }

        final TaskStack stack = task.mStack;
        stack.getDimBounds(mTmpStackBounds);
        final Rect surfaceInsets = w.getAttrs().surfaceInsets;
        // When we resize we use the big surface approach, which means we can't trust the
        // window frame bounds anymore. Instead, the window will be placed at 0, 0, but to avoid
        // hardcoding it, we use surface coordinates.
        final int frameX = isFreeformResizing ? (int) mSurfaceController.getX() :
                w.mFrame.left + mWin.mXOffset - surfaceInsets.left;
        final int frameY = isFreeformResizing ? (int) mSurfaceController.getY() :
                w.mFrame.top + mWin.mYOffset - surfaceInsets.top;

        // If we are animating, we either apply the clip before applying all the animation
        // transformation or after all the transformation.
        final boolean useFinalClipRect = isAnimationSet() && stackClip == STACK_CLIP_AFTER_ANIM
                || mDestroyPreservedSurfaceUponRedraw;

        // We need to do some acrobatics with surface position, because their clip region is
        // relative to the inside of the surface, but the stack bounds aren't.
        if (useFinalClipRect) {
            finalClipRect.set(mTmpStackBounds);
        } else {
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
    }

    void setSurfaceBoundariesLocked(final boolean recoveringMemory) {
        final WindowState w = mWin;
        final Task task = w.getTask();

        // We got resized, so block all updates until we got the new surface.
        if (w.isResizedWhileNotDragResizing() && !w.isGoneForLayoutLw()) {
            return;
        }

        mTmpSize.set(w.mShownPosition.x, w.mShownPosition.y, 0, 0);
        calculateSurfaceBounds(w, w.getAttrs());

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

        calculateSurfaceWindowCrop(mTmpClipRect, mTmpFinalClipRect);

        float surfaceWidth = mSurfaceController.getWidth();
        float surfaceHeight = mSurfaceController.getHeight();

        if ((task != null && task.mStack.getForceScaleToCrop()) || mForceScaleUntilResize) {
            int hInsets = w.getAttrs().surfaceInsets.left + w.getAttrs().surfaceInsets.right;
            int vInsets = w.getAttrs().surfaceInsets.top + w.getAttrs().surfaceInsets.bottom;
            if (!mForceScaleUntilResize) {
                mSurfaceController.forceScaleableInTransaction(true);
            }
            // We want to calculate the scaling based on the content area, not based on
            // the entire surface, so that we scale in sync with windows that don't have insets.
            mExtraHScale = (mTmpClipRect.width() - hInsets) / (float)(surfaceWidth - hInsets);
            mExtraVScale = (mTmpClipRect.height() - vInsets) / (float)(surfaceHeight - vInsets);

            // In the case of ForceScaleToCrop we scale entire tasks together,
            // and so we need to scale our offsets relative to the task bounds
            // or parent and child windows would fall out of alignment.
            int posX = (int) (mTmpSize.left - w.mAttrs.x * (1 - mExtraHScale));
            int posY = (int) (mTmpSize.top - w.mAttrs.y * (1 - mExtraVScale));
            // Imagine we are scaling down. As we scale the buffer down, we decrease the
            // distance between the surface top left, and the start of the surface contents
            // (previously it was surfaceInsets.left pixels in screen space but now it
            // will be surfaceInsets.left*mExtraHScale). This means in order to keep the
            // non inset content at the same position, we have to shift the whole window
            // forward. Likewise for scaling up, we've increased this distance, and we need
            // to shift by a negative number to compensate.
            posX += w.getAttrs().surfaceInsets.left * (1 - mExtraHScale);
            posY += w.getAttrs().surfaceInsets.top * (1 - mExtraVScale);

            mSurfaceController.setPositionInTransaction((float)Math.floor(posX),
                    (float)Math.floor(posY), recoveringMemory);

            // Since we are scaled to fit in our previously desired crop, we can now
            // expose the whole window in buffer space, and not risk extending
            // past where the system would have cropped us
            mTmpClipRect.set(0, 0, (int)surfaceWidth, (int)surfaceHeight);
            mTmpFinalClipRect.setEmpty();

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

        Rect clipRect = mTmpClipRect;
        if (w.inPinnedWorkspace()) {
            clipRect = null;
            task.mStack.getDimBounds(mTmpFinalClipRect);
            mTmpFinalClipRect.inset(-w.mAttrs.surfaceInsets.left, -w.mAttrs.surfaceInsets.top,
                    -w.mAttrs.surfaceInsets.right, -w.mAttrs.surfaceInsets.bottom);
        }

        if (!w.mSeamlesslyRotated) {
            updateSurfaceWindowCrop(clipRect, mTmpFinalClipRect, recoveringMemory);
            mSurfaceController.setMatrixInTransaction(mDsDx * w.mHScale * mExtraHScale,
                    mDtDx * w.mVScale * mExtraVScale,
                    mDsDy * w.mHScale * mExtraHScale,
                    mDtDy * w.mVScale * mExtraVScale, recoveringMemory);
        }

        if (mSurfaceResized) {
            mReportSurfaceResized = true;
            mAnimator.setPendingLayoutChanges(w.getDisplayId(),
                    WindowManagerPolicy.FINISH_LAYOUT_REDO_WALLPAPER);
            w.applyDimLayerIfNeeded();
        }

    }

    void prepareSurfaceLocked(final boolean recoveringMemory) {
        final WindowState w = mWin;
        if (!hasSurface()) {
            if (w.mOrientationChanging) {
                if (DEBUG_ORIENTATION) {
                    Slog.v(TAG, "Orientation change skips hidden " + w);
                }
                w.mOrientationChanging = false;
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
        } else if (w.mAttachedHidden || !w.isOnScreen()) {
            hide("prepareSurfaceLocked");
            mWallpaperControllerLocked.hideWallpapers(w);

            // If we are waiting for this window to handle an
            // orientation change, well, it is hidden, so
            // doesn't really matter.  Note that this does
            // introduce a potential glitch if the window
            // becomes unhidden before it has drawn for the
            // new orientation.
            if (w.mOrientationChanging) {
                w.mOrientationChanging = false;
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
                    + "][" + mDsDy + "*" + w.mHScale
                    + "," + mDtDy + "*" + w.mVScale + "]", false);

            boolean prepared =
                mSurfaceController.prepareToShowInTransaction(mShownAlpha, mAnimLayer,
                        mDsDx * w.mHScale * mExtraHScale,
                        mDtDx * w.mVScale * mExtraVScale,
                        mDsDy * w.mHScale * mExtraHScale,
                        mDtDy * w.mVScale * mExtraVScale,
                        recoveringMemory);

            if (prepared && mLastHidden && mDrawState == HAS_DRAWN) {
                if (showSurfaceRobustlyLocked()) {
                    markPreservedSurfaceForDestroy();
                    mAnimator.requestRemovalOfReplacedWindows(w);
                    mLastHidden = false;
                    if (mIsWallpaper) {
                        mWallpaperControllerLocked.dispatchWallpaperVisibility(w, true);
                    }
                    // This draw means the difference between unique content and mirroring.
                    // Run another pass through performLayout to set mHasContent in the
                    // LogicalDisplay.
                    mAnimator.setPendingLayoutChanges(w.getDisplayId(),
                            WindowManagerPolicy.FINISH_LAYOUT_REDO_ANIM);
                } else {
                    w.mOrientationChanging = false;
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

        if (displayed) {
            if (w.mOrientationChanging) {
                if (!w.isDrawnLw()) {
                    mAnimator.mBulkUpdateParams &= ~SET_ORIENTATION_CHANGE_COMPLETE;
                    mAnimator.mLastWindowFreezeSource = w;
                    if (DEBUG_ORIENTATION) Slog.v(TAG,
                            "Orientation continue waiting for draw in " + w);
                } else {
                    w.mOrientationChanging = false;
                    if (DEBUG_ORIENTATION) Slog.v(TAG, "Orientation change complete in " + w);
                }
            }
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
            SurfaceControl.openTransaction();
            mSurfaceController.setPositionInTransaction(mWin.mFrame.left + left,
                    mWin.mFrame.top + top, false);
            calculateSurfaceWindowCrop(mTmpClipRect, mTmpFinalClipRect);
            updateSurfaceWindowCrop(mTmpClipRect, mTmpFinalClipRect, false);
        } catch (RuntimeException e) {
            Slog.w(TAG, "Error positioning surface of " + mWin
                    + " pos=(" + left + "," + top + ")", e);
        } finally {
            SurfaceControl.closeTransaction();
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

    // This must be called while inside a transaction.
    boolean performShowLocked() {
        if (mWin.isHiddenFromUserLocked()) {
            if (DEBUG_VISIBILITY) Slog.w(TAG, "hiding " + mWin + ", belonging to " + mWin.mOwnerUid);
            mWin.hideLw(false);
            return false;
        }
        if (DEBUG_VISIBILITY || (DEBUG_STARTING_WINDOW &&
                mWin.mAttrs.type == WindowManager.LayoutParams.TYPE_APPLICATION_STARTING)) {
            Slog.v(TAG, "performShow on " + this
                    + ": mDrawState=" + drawStateToString() + " readyForDisplay="
                    + mWin.isReadyForDisplayIgnoringKeyguard()
                    + " starting=" + (mWin.mAttrs.type == TYPE_APPLICATION_STARTING)
                    + " during animation: policyVis=" + mWin.mPolicyVisibility
                    + " attHidden=" + mWin.mAttachedHidden
                    + " tok.hiddenRequested="
                    + (mWin.mAppToken != null ? mWin.mAppToken.hiddenRequested : false)
                    + " tok.hidden="
                    + (mWin.mAppToken != null ? mWin.mAppToken.hidden : false)
                    + " animating=" + mAnimating
                    + " tok animating="
                    + (mAppAnimator != null ? mAppAnimator.animating : false) + " Callers="
                    + Debug.getCallers(3));
        }
        if (mDrawState == READY_TO_SHOW && mWin.isReadyForDisplayIgnoringKeyguard()) {
            if (DEBUG_VISIBILITY || (DEBUG_STARTING_WINDOW &&
                    mWin.mAttrs.type == WindowManager.LayoutParams.TYPE_APPLICATION_STARTING)) {
                Slog.v(TAG, "Showing " + this
                        + " during animation: policyVis=" + mWin.mPolicyVisibility
                        + " attHidden=" + mWin.mAttachedHidden
                        + " tok.hiddenRequested="
                        + (mWin.mAppToken != null ? mWin.mAppToken.hiddenRequested : false)
                        + " tok.hidden="
                        + (mWin.mAppToken != null ? mWin.mAppToken.hidden : false)
                        + " animating=" + mAnimating
                        + " tok animating="
                        + (mAppAnimator != null ? mAppAnimator.animating : false));
            }

            mService.enableScreenIfNeededLocked();

            applyEnterAnimationLocked();

            // Force the show in the next prepareSurfaceLocked() call.
            mLastAlpha = -1;
            if (DEBUG_SURFACE_TRACE || DEBUG_ANIM)
                Slog.v(TAG, "performShowLocked: mDrawState=HAS_DRAWN in " + mWin);
            mDrawState = HAS_DRAWN;
            mService.scheduleAnimationLocked();

            int i = mWin.mChildWindows.size();
            while (i > 0) {
                i--;
                WindowState c = mWin.mChildWindows.get(i);
                if (c.mAttachedHidden) {
                    c.mAttachedHidden = false;
                    if (c.mWinAnimator.mSurfaceController != null) {
                        c.mWinAnimator.performShowLocked();
                        // It hadn't been shown, which means layout not
                        // performed on it, so now we want to make sure to
                        // do a layout.  If called from within the transaction
                        // loop, this will cause it to restart with a new
                        // layout.
                        final DisplayContent displayContent = c.getDisplayContent();
                        if (displayContent != null) {
                            displayContent.layoutNeeded = true;
                        }
                    }
                }
            }

            if (mWin.mAttrs.type != TYPE_APPLICATION_STARTING && mWin.mAppToken != null) {
                mWin.mAppToken.onFirstWindowDrawn(mWin, this);
            }

            if (mWin.mAttrs.type == TYPE_INPUT_METHOD) {
                mWin.mDisplayContent.mDividerControllerLocked.resetImeHideRequested();
            }

            return true;
        }
        return false;
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

        if (mWin.mTurnOnScreen) {
            if (DEBUG_VISIBILITY) Slog.v(TAG, "Show surface turning screen on: " + mWin);
            mWin.mTurnOnScreen = false;
            mAnimator.mBulkUpdateParams |= SET_TURN_ON_SCREEN;
        }
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
        if ((mLocalAnimating && mAnimationIsEntrance == isEntrance)
                || mKeyguardGoingAwayAnimation) {
            // If we are trying to apply an animation, but already running
            // an animation of the same type, then just leave that one alone.

            // If we are in a keyguard exit animation, and the window should animate away, modify
            // keyguard exit animation such that it also fades out.
            if (mAnimation != null && mKeyguardGoingAwayAnimation
                    && transit == WindowManagerPolicy.TRANSIT_PREVIEW_DONE) {
                applyFadeoutDuringKeyguardExitAnimation();
            }
            return true;
        }

        // Only apply an animation if the display isn't frozen.  If it is
        // frozen, there is no reason to animate and it can cause strange
        // artifacts when we unfreeze the display if some different animation
        // is running.
        Trace.traceBegin(Trace.TRACE_TAG_WINDOW_MANAGER, "WSA#applyAnimationLocked");
        if (mService.okToDisplay()) {
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
            mService.adjustForImeIfNeeded(mWin.mDisplayContent);
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
                    pw.print(" mDsDy="); pw.print(mDsDy);
                    pw.print(" mDtDy="); pw.println(mDtDy);
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
        mService.reclaimSomeSurfaceMemoryLocked(this, operation, secure);
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
                mWin.mAttachedWindow.mWinAnimator.mSurfaceController.getHandle(),
                frameNumber);
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

        // We have two cases:
        //  1. Windows with NATIVE_WINDOW_TRANSFORM_INVERSE_DISPLAY:
        //     These windows never change buffer size when rotating. Rather the window manager
        //     just updates the scaling factors to fit in the new coordinate system,
        //     and SurfaceFlinger takes care of updating the buffer contents. So in this case
        //     we just need we just need to update the scaling factors and things are seamless
        //     already.
        //  2. Other windows:
        //     In this case, we need to apply a rotation matrix to the window. For example
        //     if we have a portrait window and rotate to landscape, the window is still portrait
        //     and now extends off the bottom of the screen (and only halfway across). Essentially we
        //     apply a transform to display the current buffer at it's old position
        //     (in the new coordinate space). We then freeze layer updates until the resize
        //     occurs, at which point we undo, them.
        if (w.isChildWindow() && mSurfaceController.getTransformToDisplayInverse()) {
            frameRect.set(x, y, x+width, y+height);
            transform.mapRect(frameRect);

            w.mAttrs.x = (int) frameRect.left - w.mAttachedWindow.mFrame.left;
            w.mAttrs.y = (int) frameRect.top - w.mAttachedWindow.mFrame.top;
            w.mAttrs.width = (int) Math.ceil(frameRect.width());
            w.mAttrs.height = (int) Math.ceil(frameRect.height());

            w.setWindowScale(w.mRequestedWidth, w.mRequestedHeight);

            w.applyGravityAndUpdateFrame(w.mContainingFrame, w.mDisplayFrame);
            computeShownFrameLocked();
            setSurfaceBoundariesLocked(false);

            // The stack bounds will not yet be rotated at this point so setSurfaceBoundaries locked
            // will crop us incorrectly. Overwrite the crop, exposing the full surface. By the next
            // transaction this will be corrected.
            cropRect.set(0, 0, w.mRequestedWidth, w.mRequestedWidth + w.mRequestedHeight);
            mSurfaceController.setCropInTransaction(cropRect, false);
        } else {
            mService.markForSeamlessRotation(w, true);
            transform.getValues(mService.mTmpFloats);

            float DsDx = mService.mTmpFloats[Matrix.MSCALE_X];
            float DtDx = mService.mTmpFloats[Matrix.MSKEW_Y];
            float DsDy = mService.mTmpFloats[Matrix.MSKEW_X];
            float DtDy = mService.mTmpFloats[Matrix.MSCALE_Y];
            float nx = mService.mTmpFloats[Matrix.MTRANS_X];
            float ny = mService.mTmpFloats[Matrix.MTRANS_Y];
            mSurfaceController.setPositionInTransaction(nx, ny, false);
            mSurfaceController.setMatrixInTransaction(DsDx * w.mHScale,
                    DtDx * w.mVScale,
                    DsDy * w.mHScale,
                    DtDy * w.mVScale, false);
        }
    }
}
