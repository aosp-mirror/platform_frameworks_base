// Copyright 2012 Google Inc. All Rights Reserved.

package com.android.server.wm;

import android.util.Slog;
import android.view.WindowManager;
import android.view.WindowManagerPolicy;
import android.view.animation.Animation;
import android.view.animation.Transformation;

import com.android.server.wm.WindowManagerService.H;

import java.io.PrintWriter;

/**
 * @author cmautner@google.com (Craig Mautner)
 *
 */
class WindowStateAnimator {

    final WindowManagerService mService;
    final WindowState mWin;
    final WindowState mAttachedWindow;
    final WindowAnimator mAnimator;

    // Currently running animation.
    boolean mAnimating;
    boolean mLocalAnimating;
    Animation mAnimation;
    boolean mAnimationIsEntrance;
    boolean mHasTransformation;
    boolean mHasLocalTransformation;
    final Transformation mTransformation = new Transformation();
    boolean mWasAnimating;      // Were we animating going into the most recent animation step?

    public WindowStateAnimator(final WindowManagerService service, final WindowState win,
                               final WindowState attachedWindow) {
        mService = service;
        mWin = win;
        mAttachedWindow = attachedWindow;
        mAnimator = mService.mAnimator;
    }

    public void setAnimation(Animation anim) {
        if (WindowManagerService.localLOGV) Slog.v(
            WindowManagerService.TAG, "Setting animation in " + this + ": " + anim);
        mAnimating = false;
        mLocalAnimating = false;
        mAnimation = anim;
        mAnimation.restrictDuration(WindowManagerService.MAX_ANIMATION_DURATION);
        mAnimation.scaleCurrentDuration(mService.mWindowAnimationScale);
        // Start out animation gone if window is gone, or visible if window is visible.
        mTransformation.clear();
        mTransformation.setAlpha(mWin.mLastHidden ? 0 : 1);
        mHasLocalTransformation = true;
    }

    public void clearAnimation() {
        if (mAnimation != null) {
            mAnimating = true;
            mLocalAnimating = false;
            mAnimation.cancel();
            mAnimation = null;
        }
    }

    /** Is the window or its container currently animating? */
    boolean isAnimating() {
        final WindowState attached = mAttachedWindow;
        final AppWindowToken atoken = mWin.mAppToken;
        return mAnimation != null
                || (attached != null && attached.mWinAnimator.mAnimation != null)
                || (atoken != null &&
                        (atoken.animation != null
                                || atoken.inPendingTransaction));
    }

    /** Is this window currently animating? */
    boolean isWindowAnimating() {
        return mAnimation != null;
    }

    // TODO: Fix and call finishExit() instead of cancelExitAnimationForNextAnimationLocked()
    // for avoiding the code duplication.
    void cancelExitAnimationForNextAnimationLocked() {
        if (!mWin.mExiting) return;
        if (mAnimation != null) {
            mAnimation.cancel();
            mAnimation = null;
            mWin.destroySurfaceLocked();
        }
        mWin.mExiting = false;
    }

    private boolean stepAnimation(long currentTime) {
        if ((mAnimation == null) || !mLocalAnimating) {
            return false;
        }
        mTransformation.clear();
        final boolean more = mAnimation.getTransformation(currentTime, mTransformation);
        if (WindowManagerService.DEBUG_ANIM) Slog.v(
            WindowManagerService.TAG, "Stepped animation in " + this +
            ": more=" + more + ", xform=" + mTransformation);
        return more;
    }

    // This must be called while inside a transaction.  Returns true if
    // there is more animation to run.
    boolean stepAnimationLocked(long currentTime) {
        // Save the animation state as it was before this step so WindowManagerService can tell if
        // we just started or just stopped animating by comparing mWasAnimating with isAnimating().
        mWasAnimating = mAnimating;
        if (mService.okToDisplay()) {
            // We will run animations as long as the display isn't frozen.

            if (mWin.isDrawnLw() && mAnimation != null) {
                mHasTransformation = true;
                mHasLocalTransformation = true;
                if (!mLocalAnimating) {
                    if (WindowManagerService.DEBUG_ANIM) Slog.v(
                        WindowManagerService.TAG, "Starting animation in " + this +
                        " @ " + currentTime + ": ww=" + mWin.mFrame.width() +
                        " wh=" + mWin.mFrame.height() +
                        " dw=" + mWin.mAnimDw + " dh=" + mWin.mAnimDh +
                        " scale=" + mService.mWindowAnimationScale);
                    mAnimation.initialize(mWin.mFrame.width(), mWin.mFrame.height(), mWin.mAnimDw,
                        mWin.mAnimDh);
                    mAnimation.setStartTime(currentTime);
                    mLocalAnimating = true;
                    mAnimating = true;
                }
                if ((mAnimation != null) && mLocalAnimating) {
                    if (stepAnimation(currentTime)) {
                        return true;
                    }
                }
                if (WindowManagerService.DEBUG_ANIM) Slog.v(
                    WindowManagerService.TAG, "Finished animation in " + this +
                    " @ " + currentTime);
                //WindowManagerService.this.dump();
            }
            mHasLocalTransformation = false;
            if ((!mLocalAnimating || mAnimationIsEntrance) && mWin.mAppToken != null
                    && mWin.mAppToken.animation != null) {
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
            WindowManagerService.TAG, "Animation done in " + this + ": exiting=" + mWin.mExiting
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
        mWin.mAnimLayer = mWin.mLayer;
        if (mWin.mIsImWindow) {
            mWin.mAnimLayer += mService.mInputMethodAnimLayerAdjustment;
        } else if (mWin.mIsWallpaper) {
            mWin.mAnimLayer += mService.mWallpaperAnimLayerAdjustment;
        }
        if (WindowManagerService.DEBUG_LAYERS) Slog.v(WindowManagerService.TAG, "Stepping win " + this
                + " anim layer: " + mWin.mAnimLayer);
        mHasTransformation = false;
        mHasLocalTransformation = false;
        if (mWin.mPolicyVisibility != mWin.mPolicyVisibilityAfterAnim) {
            if (WindowState.DEBUG_VISIBILITY) {
                Slog.v(WindowManagerService.TAG, "Policy visibility changing after anim in " + this + ": "
                        + mWin.mPolicyVisibilityAfterAnim);
            }
            mWin.mPolicyVisibility = mWin.mPolicyVisibilityAfterAnim;
            mService.mLayoutNeeded = true;
            if (!mWin.mPolicyVisibility) {
                if (mService.mCurrentFocus == mWin) {
                    mService.mFocusMayChange = true;
                }
                // Window is no longer visible -- make sure if we were waiting
                // for it to be displayed before enabling the display, that
                // we allow the display to be enabled now.
                mService.enableScreenIfNeededLocked();
            }
        }
        mTransformation.clear();
        if (mWin.mHasDrawn
                && mWin.mAttrs.type == WindowManager.LayoutParams.TYPE_APPLICATION_STARTING
                && mWin.mAppToken != null
                && mWin.mAppToken.firstWindowDrawn
                && mWin.mAppToken.startingData != null) {
            if (WindowManagerService.DEBUG_STARTING_WINDOW) Slog.v(WindowManagerService.TAG, "Finish starting "
                    + mWin.mToken + ": first real window done animating");
            mService.mFinishedStarting.add(mWin.mAppToken);
            mService.mH.sendEmptyMessage(H.FINISHED_STARTING);
        }

        finishExit();
        mService.mPendingLayoutChanges |= WindowManagerPolicy.FINISH_LAYOUT_REDO_ANIM;
        if (WindowManagerService.DEBUG_LAYOUT_REPEATS) mService.debugLayoutRepeats("WindowState");

        if (mWin.mAppToken != null) {
            mWin.mAppToken.updateReportedVisibilityLocked();
        }

        return false;
    }

    void finishExit() {
        if (WindowManagerService.DEBUG_ANIM) Slog.v(
                WindowManagerService.TAG, "finishExit in " + this
                + ": exiting=" + mWin.mExiting
                + " remove=" + mWin.mRemoveOnExit
                + " windowAnimating=" + isWindowAnimating());

        final int N = mWin.mChildWindows.size();
        for (int i=0; i<N; i++) {
            mWin.mChildWindows.get(i).mWinAnimator.finishExit();
        }

        if (!mWin.mExiting) {
            return;
        }

        if (isWindowAnimating()) {
            return;
        }

        if (WindowManagerService.localLOGV) Slog.v(
                WindowManagerService.TAG, "Exit animation finished in " + this
                + ": remove=" + mWin.mRemoveOnExit);
        if (mWin.mSurface != null) {
            mService.mDestroySurface.add(mWin);
            mWin.mDestroying = true;
            if (WindowState.SHOW_TRANSACTIONS) WindowManagerService.logSurface(
                mWin, "HIDE (finishExit)", null);
            mWin.mSurfaceShown = false;
            try {
                mWin.mSurface.hide();
            } catch (RuntimeException e) {
                Slog.w(WindowManagerService.TAG, "Error hiding surface in " + this, e);
            }
            mWin.mLastHidden = true;
        }
        mWin.mExiting = false;
        if (mWin.mRemoveOnExit) {
            mService.mPendingRemove.add(mWin);
            mWin.mRemoveOnExit = false;
        }
    }

    public void dump(PrintWriter pw, String prefix, boolean dumpAll) {
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
    }

}
