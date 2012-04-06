// Copyright 2012 Google Inc. All Rights Reserved.

package com.android.server.wm;

import static android.view.WindowManager.LayoutParams.FLAG_SHOW_WALLPAPER;

import static com.android.server.wm.WindowManagerService.LayoutFields.SET_UPDATE_ROTATION;
import static com.android.server.wm.WindowManagerService.LayoutFields.SET_WALLPAPER_MAY_CHANGE;
import static com.android.server.wm.WindowManagerService.LayoutFields.SET_FORCE_HIDING_CHANGED;

import static com.android.server.wm.WindowManagerService.H.SET_DIM_PARAMETERS;

import android.content.Context;
import android.os.SystemClock;
import android.util.Log;
import android.util.Slog;
import android.view.Surface;
import android.view.WindowManager;
import android.view.WindowManagerPolicy;
import android.view.animation.Animation;

import com.android.internal.policy.impl.PhoneWindowManager;

import java.io.PrintWriter;

/**
 * Singleton class that carries out the animations and Surface operations in a separate task
 * on behalf of WindowManagerService.
 */
public class WindowAnimator {
    private static final String TAG = "WindowAnimator";

    final WindowManagerService mService;
    final Context mContext;
    final WindowManagerPolicy mPolicy;

    boolean mAnimating;
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

    // Window currently running an animation that has requested it be detached
    // from the wallpaper.  This means we need to ensure the wallpaper is
    // visible behind it in case it animates in a way that would allow it to be
    // seen.
    WindowState mWindowDetachedWallpaper = null;
    WindowState mDetachedWallpaper = null;
    DimSurface mWindowAnimationBackgroundSurface = null;

    int mBulkUpdateParams = 0;

    DimAnimator mDimAnimator = null;
    DimAnimator.Parameters mDimParams = null;

    WindowAnimator(final WindowManagerService service, final Context context,
            final WindowManagerPolicy policy) {
        mService = service;
        mContext = context;
        mPolicy = policy;
    }

    private void testWallpaperAndBackgroundLocked() {
        if (mWindowDetachedWallpaper != mDetachedWallpaper) {
            if (WindowManagerService.DEBUG_WALLPAPER) Slog.v(TAG,
                    "Detached wallpaper changed from " + mWindowDetachedWallpaper
                    + " to " + mDetachedWallpaper);
            mWindowDetachedWallpaper = mDetachedWallpaper;
            mBulkUpdateParams |= SET_WALLPAPER_MAY_CHANGE;
        }

        if (mWindowAnimationBackgroundColor != 0) {
            // If the window that wants black is the current wallpaper
            // target, then the black goes *below* the wallpaper so we
            // don't cause the wallpaper to suddenly disappear.
            WindowState target = mWindowAnimationBackground;
            if (mService.mWallpaperTarget == target
                    || mService.mLowerWallpaperTarget == target
                    || mService.mUpperWallpaperTarget == target) {
                final int N = mService.mWindows.size();
                for (int i = 0; i < N; i++) {
                    WindowState w = mService.mWindows.get(i);
                    if (w.mIsWallpaper) {
                        target = w;
                        break;
                    }
                }
            }
            if (mWindowAnimationBackgroundSurface == null) {
                mWindowAnimationBackgroundSurface = new DimSurface(mService.mFxSession);
            }
            final int dw = mDw;
            final int dh = mDh;
            mWindowAnimationBackgroundSurface.show(dw, dh,
                    target.mWinAnimator.mAnimLayer - WindowManagerService.LAYER_OFFSET_DIM,
                    mWindowAnimationBackgroundColor);
        } else if (mWindowAnimationBackgroundSurface != null) {
            mWindowAnimationBackgroundSurface.hide();
        }
    }

    private void updateWindowsAppsAndRotationAnimationsLocked() {
        int i;
        final int NAT = mService.mAppTokens.size();
        for (i=0; i<NAT; i++) {
            final AppWindowAnimator appAnimator = mService.mAppTokens.get(i).mAppAnimator;
            final boolean wasAnimating = appAnimator.animation != null
                    && appAnimator.animation != WindowManagerService.sDummyAnimation;
            if (appAnimator.stepAnimationLocked(mCurrentTime, mInnerDw, mInnerDh)) {
                mAnimating = true;
            } else if (wasAnimating) {
                // stopped animating, do one more pass through the layout
                mPendingLayoutChanges |= WindowManagerPolicy.FINISH_LAYOUT_REDO_WALLPAPER;
                if (WindowManagerService.DEBUG_LAYOUT_REPEATS) {
                    mService.debugLayoutRepeats("appToken " + appAnimator.mAppToken + " done",
                        mPendingLayoutChanges);
                }
            }
        }

        final int NEAT = mService.mExitingAppTokens.size();
        for (i=0; i<NEAT; i++) {
            final AppWindowAnimator appAnimator = mService.mExitingAppTokens.get(i).mAppAnimator;
            final boolean wasAnimating = appAnimator.animation != null
                    && appAnimator.animation != WindowManagerService.sDummyAnimation;
            if (appAnimator.stepAnimationLocked(mCurrentTime, mInnerDw, mInnerDh)) {
                mAnimating = true;
            } else if (wasAnimating) {
                // stopped animating, do one more pass through the layout
                mPendingLayoutChanges |= WindowManagerPolicy.FINISH_LAYOUT_REDO_WALLPAPER;
                if (WindowManagerService.DEBUG_LAYOUT_REPEATS) {
                    mService.debugLayoutRepeats("exiting appToken " + appAnimator.mAppToken 
                        + " done", mPendingLayoutChanges);
                }
            }
        }

        if (mScreenRotationAnimation != null &&
                (mScreenRotationAnimation.isAnimating() ||
                        mScreenRotationAnimation.mFinishAnimReady)) {
            if (mScreenRotationAnimation.stepAnimationLocked(mCurrentTime)) {
                mAnimating = true;
            } else {
                mBulkUpdateParams |= SET_UPDATE_ROTATION;
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

            if (winAnimator.mSurface != null) {
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
                        if ((attrs.flags&FLAG_SHOW_WALLPAPER) != 0
                                && winAnimator.mAnimation.getDetachWallpaper()) {
                            mDetachedWallpaper = w;
                        }
                        final int backgroundColor = winAnimator.mAnimation.getBackgroundColor();
                        if (backgroundColor != 0) {
                            if (mWindowAnimationBackground == null
                                    || (winAnimator.mAnimLayer <
                                            mWindowAnimationBackground.mWinAnimator.mAnimLayer)) {
                                mWindowAnimationBackground = w;
                                mWindowAnimationBackgroundColor = backgroundColor;
                            }
                        }
                    }
                    mAnimating = true;
                }

                // If this window's app token is running a detached wallpaper
                // animation, make a note so we can ensure the wallpaper is
                // displayed behind it.
                final AppWindowAnimator appAnimator =
                        w.mAppToken == null ? null : w.mAppToken.mAppAnimator;
                if (appAnimator != null && appAnimator.animation != null
                        && appAnimator.animating) {
                    if ((attrs.flags&FLAG_SHOW_WALLPAPER) != 0
                            && appAnimator.animation.getDetachWallpaper()) {
                        mDetachedWallpaper = w;
                    }
                    final int backgroundColor = appAnimator.animation.getBackgroundColor();
                    if (backgroundColor != 0) {
                        if (mWindowAnimationBackground == null
                                || (winAnimator.mAnimLayer <
                                        mWindowAnimationBackground.mWinAnimator.mAnimLayer)) {
                            mWindowAnimationBackground = w;
                            mWindowAnimationBackgroundColor = backgroundColor;
                        }
                    }
                }

                if (wasAnimating && !winAnimator.mAnimating && mService.mWallpaperTarget == w) {
                    mBulkUpdateParams |= SET_WALLPAPER_MAY_CHANGE;
                    mPendingLayoutChanges |= WindowManagerPolicy.FINISH_LAYOUT_REDO_WALLPAPER;
                    if (WindowManagerService.DEBUG_LAYOUT_REPEATS) {
                        mService.debugLayoutRepeats("updateWindowsAndWallpaperLocked 2",
                            mPendingLayoutChanges);
                    }
                }

                if (mPolicy.doesForceHide(w, attrs)) {
                    if (!wasAnimating && nowAnimating) {
                        if (WindowManagerService.DEBUG_VISIBILITY) Slog.v(TAG,
                                "Animation started that could impact force hide: "
                                + w);
                        mBulkUpdateParams |= SET_FORCE_HIDING_CHANGED;
                        mPendingLayoutChanges |= WindowManagerPolicy.FINISH_LAYOUT_REDO_WALLPAPER;
                        if (WindowManagerService.DEBUG_LAYOUT_REPEATS) {
                            mService.debugLayoutRepeats("updateWindowsAndWallpaperLocked 3",
                                mPendingLayoutChanges);
                        }
                        mService.mFocusMayChange = true;
                    } else if (w.isReadyForDisplay() && winAnimator.mAnimation == null) {
                        mForceHiding = true;
                    }
                } else if (mPolicy.canBeForceHidden(w, attrs)) {
                    final boolean changed;
                    if (mForceHiding) {
                        changed = w.hideLw(false, false);
                        if (WindowManagerService.DEBUG_VISIBILITY && changed) Slog.v(TAG,
                                "Now policy hidden: " + w);
                    } else {
                        changed = w.showLw(false, false);
                        if (WindowManagerService.DEBUG_VISIBILITY && changed) Slog.v(TAG,
                                "Now policy shown: " + w);
                        if (changed) {
                            if ((mBulkUpdateParams & SET_FORCE_HIDING_CHANGED) != 0
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
                        mBulkUpdateParams |= SET_WALLPAPER_MAY_CHANGE;
                        mPendingLayoutChanges |= WindowManagerPolicy.FINISH_LAYOUT_REDO_WALLPAPER;
                        if (WindowManagerService.DEBUG_LAYOUT_REPEATS) {
                            mService.debugLayoutRepeats("updateWindowsAndWallpaperLocked 4",
                                mPendingLayoutChanges);
                        }
                    }
                }
            }

            final AppWindowToken atoken = w.mAppToken;
            if (atoken != null && (!atoken.allDrawn || atoken.mAppAnimator.freezingScreen)) {
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
                            Slog.v(TAG, "Not displayed: s=" + winAnimator.mSurface
                                    + " pv=" + w.mPolicyVisibility
                                    + " mDrawState=" + winAnimator.mDrawState
                                    + " ah=" + w.mAttachedHidden
                                    + " th=" + atoken.hiddenRequested
                                    + " a=" + winAnimator.mAnimating);
                        }
                    }
                    if (w != atoken.startingWindow) {
                        if (!atoken.mAppAnimator.freezingScreen || !w.mAppFreezing) {
                            atoken.numInterestingWindows++;
                            if (w.isDrawnLw()) {
                                atoken.numDrawnWindows++;
                                if (WindowManagerService.DEBUG_VISIBILITY ||
                                        WindowManagerService.DEBUG_ORIENTATION) Slog.v(TAG,
                                        "tokenMayBeDrawn: " + atoken
                                        + " freezingScreen=" + atoken.mAppAnimator.freezingScreen
                                        + " mAppFreezing=" + w.mAppFreezing);
                                mTokenMayBeDrawn = true;
                            }
                        }
                    } else if (w.isDrawnLw()) {
                        atoken.startingDisplayed = true;
                    }
                }
            } else if (winAnimator.mDrawState == WindowStateAnimator.READY_TO_SHOW) {
                if (winAnimator.performShowLocked()) {
                    mPendingLayoutChanges |= WindowManagerPolicy.FINISH_LAYOUT_REDO_ANIM;
                    if (WindowManagerService.DEBUG_LAYOUT_REPEATS) {
                        mService.debugLayoutRepeats("updateWindowsAndWallpaperLocked 5",
                            mPendingLayoutChanges);
                    }
                }
            }
            final AppWindowAnimator appAnimator =
                    atoken == null ? null : atoken.mAppAnimator;
            if (appAnimator != null && appAnimator.thumbnail != null) {
                if (appAnimator.thumbnailTransactionSeq != mTransactionSequence) {
                    appAnimator.thumbnailTransactionSeq = mTransactionSequence;
                    appAnimator.thumbnailLayer = 0;
                }
                if (appAnimator.thumbnailLayer < winAnimator.mAnimLayer) {
                    appAnimator.thumbnailLayer = winAnimator.mAnimLayer;
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
            if (wtoken.mAppAnimator.freezingScreen) {
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
                        mService.debugLayoutRepeats("testTokenMayBeDrawnLocked",
                            mPendingLayoutChanges);
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
        mTokenMayBeDrawn = false;
        mForceHiding = false;
        mDetachedWallpaper = null;
        mWindowAnimationBackground = null;
        mWindowAnimationBackgroundColor = 0;

        updateWindowsAndWallpaperLocked();

        if (mTokenMayBeDrawn) {
            testTokenMayBeDrawnLocked();
        }
    }

    void animate() {
        mPendingLayoutChanges = 0;
        mCurrentTime = SystemClock.uptimeMillis();
        mBulkUpdateParams = 0;

        // Update animations of all applications, including those
        // associated with exiting/removed apps
        Surface.openTransaction();

        try {
            testWallpaperAndBackgroundLocked();
            updateWindowsAppsAndRotationAnimationsLocked();
            performAnimationsLocked();

            // THIRD LOOP: Update the surfaces of all windows.

            if (mScreenRotationAnimation != null) {
                mScreenRotationAnimation.updateSurfaces();
            }

            for (int i = mService.mWindows.size() - 1; i >= 0; i--) {
                WindowState w = mService.mWindows.get(i);
                w.mWinAnimator.prepareSurfaceLocked(true);
            }

            if (mDimParams != null) {
                mDimAnimator.updateParameters(mContext.getResources(), mDimParams, mCurrentTime);
            }
            if (mDimAnimator != null && mDimAnimator.mDimShown) {
                mAnimating |= mDimAnimator.updateSurface(mDimParams != null, mCurrentTime,
                        !mService.okToDisplay());
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

        if (mBulkUpdateParams != 0) {
            mService.bulkSetParameters(mBulkUpdateParams);
        }
    }

    WindowState mCurrentFocus;
    void setCurrentFocus(final WindowState currentFocus) {
        mCurrentFocus = currentFocus;
    }

    void setDisplayDimensions(final int curWidth, final int curHeight,
                        final int appWidth, final int appHeight) {
        mDw = curWidth;
        mDh = curHeight;
        mInnerDw = appWidth;
        mInnerDh = appHeight;
    }

    void startDimming(final WindowStateAnimator winAnimator, final float target,
                      final int width, final int height) {
        if (mDimAnimator == null) {
            mDimAnimator = new DimAnimator(mService.mFxSession);
        }
        mService.mH.sendMessage(mService.mH.obtainMessage(SET_DIM_PARAMETERS,
                new DimAnimator.Parameters(winAnimator, width, height, target)));
    }

    // TODO(cmautner): Move into Handler
    void stopDimming() {
        mService.mH.sendMessage(mService.mH.obtainMessage(SET_DIM_PARAMETERS, null));
    }

    public void dump(PrintWriter pw, String prefix, boolean dumpAll) {
        if (mWindowDetachedWallpaper != null) {
            pw.print("  mWindowDetachedWallpaper="); pw.println(mWindowDetachedWallpaper);
        }
        if (mWindowAnimationBackgroundSurface != null) {
            pw.println("  mWindowAnimationBackgroundSurface:");
            mWindowAnimationBackgroundSurface.printTo("    ", pw);
        }
        if (mDimAnimator != null) {
            pw.println("  mDimAnimator:");
            mDimAnimator.printTo("    ", pw);
        } else {
            pw.println( "  no DimAnimator ");
        }
    }
}
