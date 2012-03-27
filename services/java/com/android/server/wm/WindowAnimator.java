// Copyright 2012 Google Inc. All Rights Reserved.

package com.android.server.wm;

import static android.view.WindowManager.LayoutParams.FLAG_SHOW_WALLPAPER;

import android.content.Context;
import android.os.SystemClock;
import android.util.Log;
import android.util.Slog;
import android.view.Surface;
import android.view.WindowManager;
import android.view.WindowManager.LayoutParams;
import android.view.WindowManagerPolicy;
import android.view.animation.Animation;
import android.view.animation.AnimationUtils;

import com.android.internal.policy.impl.PhoneWindowManager;

/**
 * @author cmautner@google.com (Craig Mautner)
 * Singleton class that carries out the animations and Surface operations in a separate task
 * on behalf of WindowManagerService.
 */
public class WindowAnimator {
    private static final String TAG = "WindowAnimator";

    final WindowManagerService mService;
    final Context mContext;
    final WindowManagerPolicy mPolicy;

    boolean mAnimating;
    boolean mUpdateRotation;
    boolean mTokenMayBeDrawn;
    boolean mForceHiding;
    WindowState mWindowAnimationBackground;
    int mWindowAnimationBackgroundColor;
    int mAdjResult;

    int mPendingLayoutChanges;

    /** Overall window dimensions */
    int mDw, mDh;

    /** Interior window dimensions */
    int mInnerDw, mInnerDh;

    /** Time of current animation step. Reset on each iteration */
    long mCurrentTime;

    /** Skip repeated AppWindowTokens initialization. Note that AppWindowsToken's version of this
     * is a long initialized to Long.MIN_VALUE so that it doesn't match this value on startup. */
    private int mTransactionSequence;

    /** The one and only screen rotation if one is happening */
    ScreenRotationAnimation mScreenRotationAnimation = null;

    WindowAnimator(final WindowManagerService service, final Context context,
            final WindowManagerPolicy policy) {
        mService = service;
        mContext = context;
        mPolicy = policy;
    }

    private void updateWindowsAppsAndRotationAnimationsLocked() {
        int i;
        final int NAT = mService.mAppTokens.size();
        for (i=0; i<NAT; i++) {
            final AppWindowToken appToken = mService.mAppTokens.get(i);
            final boolean wasAnimating = appToken.animation != null
                    && appToken.animation != WindowManagerService.sDummyAnimation;
            if (appToken.stepAnimationLocked(mCurrentTime, mInnerDw, mInnerDh)) {
                mAnimating = true;
            } else if (wasAnimating) {
                // stopped animating, do one more pass through the layout
                mPendingLayoutChanges |= WindowManagerPolicy.FINISH_LAYOUT_REDO_WALLPAPER;
                if (WindowManagerService.DEBUG_LAYOUT_REPEATS) {
                    mService.debugLayoutRepeats("appToken " + appToken + " done");
                }
            }
        }

        final int NEAT = mService.mExitingAppTokens.size();
        for (i=0; i<NEAT; i++) {
            final AppWindowToken appToken = mService.mExitingAppTokens.get(i);
            final boolean wasAnimating = appToken.animation != null
                    && appToken.animation != WindowManagerService.sDummyAnimation;
            if (appToken.stepAnimationLocked(mCurrentTime, mInnerDw, mInnerDh)) {
                mAnimating = true;
            } else if (wasAnimating) {
                // stopped animating, do one more pass through the layout
                mPendingLayoutChanges |= WindowManagerPolicy.FINISH_LAYOUT_REDO_WALLPAPER;
                if (WindowManagerService.DEBUG_LAYOUT_REPEATS) {
                    mService.debugLayoutRepeats("exiting appToken " + appToken + " done");
                }
            }
        }

        if (mScreenRotationAnimation != null &&
                (mScreenRotationAnimation.isAnimating() ||
                        mScreenRotationAnimation.mFinishAnimReady)) {
            if (mScreenRotationAnimation.stepAnimationLocked(mCurrentTime)) {
                mUpdateRotation = false;
                mAnimating = true;
            } else {
                mUpdateRotation = true;
                mScreenRotationAnimation.kill();
                mScreenRotationAnimation = null;
            }
        }
    }

    private void updateWindowsAndWallpaperLocked() {
        ++mTransactionSequence;

        for (int i = mService.mWindows.size() - 1; i >= 0; i--) {
            WindowState w = mService.mWindows.get(i);
            WindowStateAnimator winAnimator = w.mWinAnimator;

            final WindowManager.LayoutParams attrs = w.mAttrs;

            if (w.mSurface != null) {
                // Take care of the window being ready to display.
                if (w.commitFinishDrawingLocked(mCurrentTime)) {
                    if ((w.mAttrs.flags
                            & WindowManager.LayoutParams.FLAG_SHOW_WALLPAPER) != 0) {
                        if (WindowManagerService.DEBUG_WALLPAPER) Slog.v(TAG,
                                "First draw done in potential wallpaper target " + w);
                        mService.mInnerFields.mWallpaperMayChange = true;
                        mPendingLayoutChanges |= WindowManagerPolicy.FINISH_LAYOUT_REDO_WALLPAPER;
                        if (WindowManagerService.DEBUG_LAYOUT_REPEATS) {
                            mService.debugLayoutRepeats("updateWindowsAndWallpaperLocked 1");
                        }
                    }
                }

                // If the window has moved due to its containing
                // content frame changing, then we'd like to animate
                // it.  The checks here are ordered by what is least
                // likely to be true first.
                if (w.shouldAnimateMove()) {
                    // Frame has moved, containing content frame
                    // has also moved, and we're not currently animating...
                    // let's do something.
                    Animation a = AnimationUtils.loadAnimation(mContext,
                            com.android.internal.R.anim.window_move_from_decor);
                    winAnimator.setAnimation(a);
                    w.mAnimDw = w.mLastFrame.left - w.mFrame.left;
                    w.mAnimDh = w.mLastFrame.top - w.mFrame.top;
                } else {
                    w.mAnimDw = mInnerDw;
                    w.mAnimDh = mInnerDh;
                }

                final boolean wasAnimating = winAnimator.mWasAnimating;
                final boolean nowAnimating = winAnimator.stepAnimationLocked(mCurrentTime);

                if (WindowManagerService.DEBUG_WALLPAPER) {
                    Slog.v(TAG, w + ": wasAnimating=" + wasAnimating +
                            ", nowAnimating=" + nowAnimating);
                }

                // If this window is animating, make a note that we have
                // an animating window and take care of a request to run
                // a detached wallpaper animation.
                if (nowAnimating) {
                    if (winAnimator.mAnimation != null) {
                        if ((w.mAttrs.flags&FLAG_SHOW_WALLPAPER) != 0
                                && winAnimator.mAnimation.getDetachWallpaper()) {
                            mService.mInnerFields.mDetachedWallpaper = w;
                        }
                        if (winAnimator.mAnimation.getBackgroundColor() != 0) {
                            if (mWindowAnimationBackground == null
                                    || (w.mAnimLayer < mWindowAnimationBackground.mAnimLayer)) {
                                mWindowAnimationBackground = w;
                                mWindowAnimationBackgroundColor =
                                        winAnimator.mAnimation.getBackgroundColor();
                            }
                        }
                    }
                    mAnimating = true;
                }

                // If this window's app token is running a detached wallpaper
                // animation, make a note so we can ensure the wallpaper is
                // displayed behind it.
                if (w.mAppToken != null && w.mAppToken.animation != null
                        && w.mAppToken.animating) {
                    if ((w.mAttrs.flags&FLAG_SHOW_WALLPAPER) != 0
                            && w.mAppToken.animation.getDetachWallpaper()) {
                        mService.mInnerFields.mDetachedWallpaper = w;
                    }
                    if (w.mAppToken.animation.getBackgroundColor() != 0) {
                        if (mWindowAnimationBackground == null
                                || (w.mAnimLayer <
                                        mWindowAnimationBackground.mAnimLayer)) {
                            mWindowAnimationBackground = w;
                            mWindowAnimationBackgroundColor =
                                    w.mAppToken.animation.getBackgroundColor();
                        }
                    }
                }

                if (wasAnimating && !winAnimator.mAnimating && mService.mWallpaperTarget == w) {
                    mService.mInnerFields.mWallpaperMayChange = true;
                    mPendingLayoutChanges |= WindowManagerPolicy.FINISH_LAYOUT_REDO_WALLPAPER;
                    if (WindowManagerService.DEBUG_LAYOUT_REPEATS) {
                        mService.debugLayoutRepeats("updateWindowsAndWallpaperLocked 2");
                    }
                }

                if (mPolicy.doesForceHide(w, attrs)) {
                    if (!wasAnimating && nowAnimating) {
                        if (WindowManagerService.DEBUG_VISIBILITY) Slog.v(TAG,
                                "Animation started that could impact force hide: "
                                + w);
                        mService.mInnerFields.mWallpaperForceHidingChanged = true;
                        mPendingLayoutChanges |= WindowManagerPolicy.FINISH_LAYOUT_REDO_WALLPAPER;
                        if (WindowManagerService.DEBUG_LAYOUT_REPEATS) {
                            mService.debugLayoutRepeats("updateWindowsAndWallpaperLocked 3");
                        }
                        mService.mFocusMayChange = true;
                    } else if (w.isReadyForDisplay() && winAnimator.mAnimation == null) {
                        mForceHiding = true;
                    }
                } else if (mPolicy.canBeForceHidden(w, attrs)) {
                    boolean changed;
                    if (mForceHiding) {
                        changed = w.hideLw(false, false);
                        if (WindowManagerService.DEBUG_VISIBILITY && changed) Slog.v(TAG,
                                "Now policy hidden: " + w);
                    } else {
                        changed = w.showLw(false, false);
                        if (WindowManagerService.DEBUG_VISIBILITY && changed) Slog.v(TAG,
                                "Now policy shown: " + w);
                        if (changed) {
                            if (mService.mInnerFields.mWallpaperForceHidingChanged
                                    && w.isVisibleNow() /*w.isReadyForDisplay()*/) {
                                // Assume we will need to animate.  If
                                // we don't (because the wallpaper will
                                // stay with the lock screen), then we will
                                // clean up later.
                                Animation a = mPolicy.createForceHideEnterAnimation();
                                if (a != null) {
                                    winAnimator.setAnimation(a);
                                }
                            }
                            if (mCurrentFocus == null || mCurrentFocus.mLayer < w.mLayer) {
                                // We are showing on to of the current
                                // focus, so re-evaluate focus to make
                                // sure it is correct.
                                mService.mFocusMayChange = true;
                            }
                        }
                    }
                    if (changed && (attrs.flags
                            & WindowManager.LayoutParams.FLAG_SHOW_WALLPAPER) != 0) {
                        mService.mInnerFields.mWallpaperMayChange = true;
                        mPendingLayoutChanges |= WindowManagerPolicy.FINISH_LAYOUT_REDO_WALLPAPER;
                        if (WindowManagerService.DEBUG_LAYOUT_REPEATS) {
                            mService.debugLayoutRepeats("updateWindowsAndWallpaperLocked 4");
                        }
                    }
                }
            }

            final AppWindowToken atoken = w.mAppToken;
            if (atoken != null && (!atoken.allDrawn || atoken.freezingScreen)) {
                if (atoken.lastTransactionSequence != mTransactionSequence) {
                    atoken.lastTransactionSequence = mTransactionSequence;
                    atoken.numInterestingWindows = atoken.numDrawnWindows = 0;
                    atoken.startingDisplayed = false;
                }
                if ((w.isOnScreen() || w.mAttrs.type
                        == WindowManager.LayoutParams.TYPE_BASE_APPLICATION)
                        && !w.mExiting && !w.mDestroying) {
                    if (WindowManagerService.DEBUG_VISIBILITY ||
                            WindowManagerService.DEBUG_ORIENTATION) {
                        Slog.v(TAG, "Eval win " + w + ": isDrawn="
                                + w.isDrawnLw()
                                + ", isAnimating=" + winAnimator.isAnimating());
                        if (!w.isDrawnLw()) {
                            Slog.v(TAG, "Not displayed: s=" + w.mSurface
                                    + " pv=" + w.mPolicyVisibility
                                    + " dp=" + w.mDrawPending
                                    + " cdp=" + w.mCommitDrawPending
                                    + " ah=" + w.mAttachedHidden
                                    + " th=" + atoken.hiddenRequested
                                    + " a=" + winAnimator.mAnimating);
                        }
                    }
                    if (w != atoken.startingWindow) {
                        if (!atoken.freezingScreen || !w.mAppFreezing) {
                            atoken.numInterestingWindows++;
                            if (w.isDrawnLw()) {
                                atoken.numDrawnWindows++;
                                if (WindowManagerService.DEBUG_VISIBILITY ||
                                        WindowManagerService.DEBUG_ORIENTATION) Slog.v(TAG,
                                        "tokenMayBeDrawn: " + atoken
                                        + " freezingScreen=" + atoken.freezingScreen
                                        + " mAppFreezing=" + w.mAppFreezing);
                                mTokenMayBeDrawn = true;
                            }
                        }
                    } else if (w.isDrawnLw()) {
                        atoken.startingDisplayed = true;
                    }
                }
            } else if (w.mReadyToShow) {
                if (w.performShowLocked()) {
                    mPendingLayoutChanges |= WindowManagerPolicy.FINISH_LAYOUT_REDO_ANIM;
                    if (WindowManagerService.DEBUG_LAYOUT_REPEATS) {
                        mService.debugLayoutRepeats("updateWindowsAndWallpaperLocked 5");
                    }
                }
            }
            if (atoken != null && atoken.thumbnail != null) {
                if (atoken.thumbnailTransactionSeq != mTransactionSequence) {
                    atoken.thumbnailTransactionSeq = mTransactionSequence;
                    atoken.thumbnailLayer = 0;
                }
                if (atoken.thumbnailLayer < w.mAnimLayer) {
                    atoken.thumbnailLayer = w.mAnimLayer;
                }
            }
        } // end forall windows
    }

    private void testTokenMayBeDrawnLocked() {
        // See if any windows have been drawn, so they (and others
        // associated with them) can now be shown.
        final int NT = mService.mAppTokens.size();
        for (int i=0; i<NT; i++) {
            AppWindowToken wtoken = mService.mAppTokens.get(i);
            if (wtoken.freezingScreen) {
                int numInteresting = wtoken.numInterestingWindows;
                if (numInteresting > 0 && wtoken.numDrawnWindows >= numInteresting) {
                    if (WindowManagerService.DEBUG_VISIBILITY) Slog.v(TAG,
                            "allDrawn: " + wtoken
                            + " interesting=" + numInteresting
                            + " drawn=" + wtoken.numDrawnWindows);
                    wtoken.showAllWindowsLocked();
                    mService.unsetAppFreezingScreenLocked(wtoken, false, true);
                    if (WindowManagerService.DEBUG_ORIENTATION) Slog.i(TAG,
                            "Setting mOrientationChangeComplete=true because wtoken "
                            + wtoken + " numInteresting=" + numInteresting
                            + " numDrawn=" + wtoken.numDrawnWindows);
                    mService.mInnerFields.mOrientationChangeComplete = true;
                }
            } else if (!wtoken.allDrawn) {
                int numInteresting = wtoken.numInterestingWindows;
                if (numInteresting > 0 && wtoken.numDrawnWindows >= numInteresting) {
                    if (WindowManagerService.DEBUG_VISIBILITY) Slog.v(TAG,
                            "allDrawn: " + wtoken
                            + " interesting=" + numInteresting
                            + " drawn=" + wtoken.numDrawnWindows);
                    wtoken.allDrawn = true;
                    mPendingLayoutChanges |= PhoneWindowManager.FINISH_LAYOUT_REDO_ANIM;
                    if (WindowManagerService.DEBUG_LAYOUT_REPEATS) {
                        mService.debugLayoutRepeats("testTokenMayBeDrawnLocked");
                    }

                    // We can now show all of the drawn windows!
                    if (!mService.mOpeningApps.contains(wtoken)) {
                        mAnimating |= wtoken.showAllWindowsLocked();
                    }
                }
            }
        }
    }

    private void performAnimationsLocked() {
        if (WindowManagerService.DEBUG_APP_TRANSITIONS) Slog.v(TAG, "*** ANIM STEP: seq="
                + mTransactionSequence + " mAnimating="
                + mAnimating);

        mTokenMayBeDrawn = false;
        mService.mInnerFields.mWallpaperMayChange = false;
        mForceHiding = false;
        mService.mInnerFields.mDetachedWallpaper = null;
        mWindowAnimationBackground = null;
        mWindowAnimationBackgroundColor = 0;

        updateWindowsAndWallpaperLocked();

        if (mTokenMayBeDrawn) {
            testTokenMayBeDrawnLocked();
        }

        if (WindowManagerService.DEBUG_APP_TRANSITIONS) Slog.v(TAG, "*** ANIM STEP: changes=0x"
                + Integer.toHexString(mPendingLayoutChanges));
    }

    public void prepareSurfaceLocked(final WindowState w, final boolean recoveringMemory) {
        if (w.mSurface == null) {
            if (w.mOrientationChanging) {
                if (WindowManagerService.DEBUG_ORIENTATION) {
                    Slog.v(TAG, "Orientation change skips hidden " + w);
                }
                w.mOrientationChanging = false;
            }
            return;
        }

        boolean displayed = false;

        w.computeShownFrameLocked();

        int width, height;
        if ((w.mAttrs.flags & LayoutParams.FLAG_SCALED) != 0) {
            // for a scaled surface, we just want to use
            // the requested size.
            width  = w.mRequestedWidth;
            height = w.mRequestedHeight;
        } else {
            width = w.mCompatFrame.width();
            height = w.mCompatFrame.height();
        }

        if (width < 1) {
            width = 1;
        }
        if (height < 1) {
            height = 1;
        }
        final boolean surfaceResized = w.mSurfaceW != width || w.mSurfaceH != height;
        if (surfaceResized) {
            w.mSurfaceW = width;
            w.mSurfaceH = height;
        }

        if (w.mSurfaceX != w.mShownFrame.left
                || w.mSurfaceY != w.mShownFrame.top) {
            try {
                if (WindowManagerService.SHOW_TRANSACTIONS) WindowManagerService.logSurface(w,
                        "POS " + w.mShownFrame.left
                        + ", " + w.mShownFrame.top, null);
                w.mSurfaceX = w.mShownFrame.left;
                w.mSurfaceY = w.mShownFrame.top;
                w.mSurface.setPosition(w.mShownFrame.left, w.mShownFrame.top);
            } catch (RuntimeException e) {
                Slog.w(TAG, "Error positioning surface of " + w
                        + " pos=(" + w.mShownFrame.left
                        + "," + w.mShownFrame.top + ")", e);
                if (!recoveringMemory) {
                    mService.reclaimSomeSurfaceMemoryLocked(w, "position", true);
                }
            }
        }

        if (surfaceResized) {
            try {
                if (WindowManagerService.SHOW_TRANSACTIONS) WindowManagerService.logSurface(w,
                        "SIZE " + width + "x" + height, null);
                w.mSurfaceResized = true;
                w.mSurface.setSize(width, height);
            } catch (RuntimeException e) {
                // If something goes wrong with the surface (such
                // as running out of memory), don't take down the
                // entire system.
                Slog.e(TAG, "Error resizing surface of " + w
                        + " size=(" + width + "x" + height + ")", e);
                if (!recoveringMemory) {
                    mService.reclaimSomeSurfaceMemoryLocked(w, "size", true);
                }
            }
        }

        if (w.mAttachedHidden || !w.isReadyForDisplay()) {
            if (!w.mLastHidden) {
                //dump();
                w.mLastHidden = true;
                if (WindowManagerService.SHOW_TRANSACTIONS) WindowManagerService.logSurface(w,
                        "HIDE (performLayout)", null);
                if (w.mSurface != null) {
                    w.mSurfaceShown = false;
                    try {
                        w.mSurface.hide();
                    } catch (RuntimeException e) {
                        Slog.w(TAG, "Exception hiding surface in " + w);
                    }
                }
            }
            // If we are waiting for this window to handle an
            // orientation change, well, it is hidden, so
            // doesn't really matter.  Note that this does
            // introduce a potential glitch if the window
            // becomes unhidden before it has drawn for the
            // new orientation.
            if (w.mOrientationChanging) {
                w.mOrientationChanging = false;
                if (WindowManagerService.DEBUG_ORIENTATION) Slog.v(TAG,
                        "Orientation change skips hidden " + w);
            }
        } else if (w.mLastLayer != w.mAnimLayer
                || w.mLastAlpha != w.mShownAlpha
                || w.mLastDsDx != w.mDsDx
                || w.mLastDtDx != w.mDtDx
                || w.mLastDsDy != w.mDsDy
                || w.mLastDtDy != w.mDtDy
                || w.mLastHScale != w.mHScale
                || w.mLastVScale != w.mVScale
                || w.mLastHidden) {
            displayed = true;
            w.mLastAlpha = w.mShownAlpha;
            w.mLastLayer = w.mAnimLayer;
            w.mLastDsDx = w.mDsDx;
            w.mLastDtDx = w.mDtDx;
            w.mLastDsDy = w.mDsDy;
            w.mLastDtDy = w.mDtDy;
            w.mLastHScale = w.mHScale;
            w.mLastVScale = w.mVScale;
            if (WindowManagerService.SHOW_TRANSACTIONS) WindowManagerService.logSurface(w,
                    "alpha=" + w.mShownAlpha + " layer=" + w.mAnimLayer
                    + " matrix=[" + (w.mDsDx*w.mHScale)
                    + "," + (w.mDtDx*w.mVScale)
                    + "][" + (w.mDsDy*w.mHScale)
                    + "," + (w.mDtDy*w.mVScale) + "]", null);
            if (w.mSurface != null) {
                try {
                    w.mSurfaceAlpha = w.mShownAlpha;
                    w.mSurface.setAlpha(w.mShownAlpha);
                    w.mSurfaceLayer = w.mAnimLayer;
                    w.mSurface.setLayer(w.mAnimLayer);
                    w.mSurface.setMatrix(
                            w.mDsDx*w.mHScale, w.mDtDx*w.mVScale,
                            w.mDsDy*w.mHScale, w.mDtDy*w.mVScale);
                } catch (RuntimeException e) {
                    Slog.w(TAG, "Error updating surface in " + w, e);
                    if (!recoveringMemory) {
                        mService.reclaimSomeSurfaceMemoryLocked(w, "update", true);
                    }
                }
            }

            if (w.mLastHidden && w.isDrawnLw()
                    && !w.mReadyToShow) {
                if (WindowManagerService.SHOW_TRANSACTIONS) WindowManagerService.logSurface(w,
                        "SHOW (performLayout)", null);
                if (WindowManagerService.DEBUG_VISIBILITY) Slog.v(TAG, "Showing " + w
                        + " during relayout");
                if (mService.showSurfaceRobustlyLocked(w)) {
                    w.mHasDrawn = true;
                    w.mLastHidden = false;
                } else {
                    w.mOrientationChanging = false;
                }
            }
            if (w.mSurface != null) {
                w.mToken.hasVisible = true;
            }
        } else {
            displayed = true;
        }

        if (displayed) {
            if (w.mOrientationChanging) {
                if (!w.isDrawnLw()) {
                    mService.mInnerFields.mOrientationChangeComplete = false;
                    if (WindowManagerService.DEBUG_ORIENTATION) Slog.v(TAG,
                            "Orientation continue waiting for draw in " + w);
                } else {
                    w.mOrientationChanging = false;
                    if (WindowManagerService.DEBUG_ORIENTATION) Slog.v(TAG,
                            "Orientation change complete in " + w);
                }
            }
            w.mToken.hasVisible = true;
        }
    }

    void animate() {
        mPendingLayoutChanges = 0;
        mCurrentTime = SystemClock.uptimeMillis();

        // Update animations of all applications, including those
        // associated with exiting/removed apps
        Surface.openTransaction();

        try {
            updateWindowsAppsAndRotationAnimationsLocked();
            performAnimationsLocked();

            // THIRD LOOP: Update the surfaces of all windows.

            if (mScreenRotationAnimation != null) {
                mScreenRotationAnimation.updateSurfaces();
            }

            final int N = mService.mWindows.size();
            for (int i=N-1; i>=0; i--) {
                WindowState w = mService.mWindows.get(i);
                prepareSurfaceLocked(w, true);
            }

            if (mService.mDimAnimator != null && mService.mDimAnimator.mDimShown) {
                mAnimating |= mService.mDimAnimator.updateSurface(mService.mInnerFields.mDimming,
                            mCurrentTime, !mService.okToDisplay());
            }

            if (mService.mBlackFrame != null) {
                if (mScreenRotationAnimation != null) {
                    mService.mBlackFrame.setMatrix(
                            mScreenRotationAnimation.getEnterTransformation().getMatrix());
                } else {
                    mService.mBlackFrame.clearMatrix();
                }
            }
        } catch (RuntimeException e) {
            Log.wtf(TAG, "Unhandled exception in Window Manager", e);
        } finally {
            Surface.closeTransaction();
        }
    }

    WindowState mCurrentFocus;
    void setCurrentFocus(WindowState currentFocus) {
        mCurrentFocus = currentFocus;
    }

    void setDisplayDimensions(final int curWidth, final int curHeight,
                        final int appWidth, final int appHeight) {
        mDw = curWidth;
        mDh = curHeight;
        mInnerDw = appWidth;
        mInnerDh = appHeight;
    }

}
