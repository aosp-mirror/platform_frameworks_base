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

import static android.view.WindowManager.LayoutParams.FLAG_SHOW_WALLPAPER;
import static android.view.WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED;

import static android.view.WindowManager.LayoutParams.PRIVATE_FLAG_KEYGUARD;
import static com.android.server.wm.WindowManagerService.LayoutFields.SET_UPDATE_ROTATION;
import static com.android.server.wm.WindowManagerService.LayoutFields.SET_WALLPAPER_MAY_CHANGE;
import static com.android.server.wm.WindowManagerService.LayoutFields.SET_FORCE_HIDING_CHANGED;
import static com.android.server.wm.WindowManagerService.LayoutFields.SET_ORIENTATION_CHANGE_COMPLETE;
import static com.android.server.wm.WindowManagerService.LayoutFields.SET_WALLPAPER_ACTION_PENDING;

import android.content.Context;
import android.os.Debug;
import android.os.SystemClock;
import android.util.Log;
import android.util.Slog;
import android.util.SparseArray;
import android.util.SparseIntArray;
import android.util.TimeUtils;
import android.view.Display;
import android.view.SurfaceControl;
import android.view.WindowManagerPolicy;
import android.view.animation.AlphaAnimation;
import android.view.animation.Animation;

import com.android.server.wm.WindowManagerService.LayoutFields;

import java.io.PrintWriter;
import java.util.ArrayList;

/**
 * Singleton class that carries out the animations and Surface operations in a separate task
 * on behalf of WindowManagerService.
 */
public class WindowAnimator {
    private static final String TAG = "WindowAnimator";

    /** How long to give statusbar to clear the private keyguard flag when animating out */
    private static final long KEYGUARD_ANIM_TIMEOUT_MS = 1000;

    final WindowManagerService mService;
    final Context mContext;
    final WindowManagerPolicy mPolicy;

    boolean mAnimating;

    final Runnable mAnimationRunnable;

    /** Time of current animation step. Reset on each iteration */
    long mCurrentTime;

    /** Skip repeated AppWindowTokens initialization. Note that AppWindowsToken's version of this
     * is a long initialized to Long.MIN_VALUE so that it doesn't match this value on startup. */
    private int mAnimTransactionSequence;

    /** Window currently running an animation that has requested it be detached
     * from the wallpaper.  This means we need to ensure the wallpaper is
     * visible behind it in case it animates in a way that would allow it to be
     * seen. If multiple windows satisfy this, use the lowest window. */
    WindowState mWindowDetachedWallpaper = null;

    WindowStateAnimator mUniverseBackground = null;
    int mAboveUniverseLayer = 0;

    int mBulkUpdateParams = 0;
    Object mLastWindowFreezeSource;

    SparseArray<DisplayContentsAnimator> mDisplayContentsAnimators =
            new SparseArray<DisplayContentsAnimator>(2);

    boolean mInitialized = false;

    boolean mKeyguardGoingAway;
    boolean mKeyguardGoingAwayToNotificationShade;
    boolean mKeyguardGoingAwayDisableWindowAnimations;

    // forceHiding states.
    static final int KEYGUARD_NOT_SHOWN     = 0;
    static final int KEYGUARD_ANIMATING_IN  = 1;
    static final int KEYGUARD_SHOWN         = 2;
    static final int KEYGUARD_ANIMATING_OUT = 3;
    int mForceHiding = KEYGUARD_NOT_SHOWN;

    private String forceHidingToString() {
        switch (mForceHiding) {
            case KEYGUARD_NOT_SHOWN:    return "KEYGUARD_NOT_SHOWN";
            case KEYGUARD_ANIMATING_IN: return "KEYGUARD_ANIMATING_IN";
            case KEYGUARD_SHOWN:        return "KEYGUARD_SHOWN";
            case KEYGUARD_ANIMATING_OUT:return "KEYGUARD_ANIMATING_OUT";
            default: return "KEYGUARD STATE UNKNOWN " + mForceHiding;
        }
    }

    WindowAnimator(final WindowManagerService service) {
        mService = service;
        mContext = service.mContext;
        mPolicy = service.mPolicy;

        mAnimationRunnable = new Runnable() {
            @Override
            public void run() {
                synchronized (mService.mWindowMap) {
                    mService.mAnimationScheduled = false;
                    animateLocked();
                }
            }
        };
    }

    void addDisplayLocked(final int displayId) {
        // Create the DisplayContentsAnimator object by retrieving it.
        getDisplayContentsAnimatorLocked(displayId);
        if (displayId == Display.DEFAULT_DISPLAY) {
            mInitialized = true;
        }
    }

    void removeDisplayLocked(final int displayId) {
        final DisplayContentsAnimator displayAnimator = mDisplayContentsAnimators.get(displayId);
        if (displayAnimator != null) {
            if (displayAnimator.mScreenRotationAnimation != null) {
                displayAnimator.mScreenRotationAnimation.kill();
                displayAnimator.mScreenRotationAnimation = null;
            }
        }

        mDisplayContentsAnimators.delete(displayId);
    }

    void hideWallpapersLocked(final WindowState w) {
        final WindowState wallpaperTarget = mService.mWallpaperTarget;
        final WindowState lowerWallpaperTarget = mService.mLowerWallpaperTarget;
        final ArrayList<WindowToken> wallpaperTokens = mService.mWallpaperTokens;

        if ((wallpaperTarget == w && lowerWallpaperTarget == null) || wallpaperTarget == null) {
            final int numTokens = wallpaperTokens.size();
            for (int i = numTokens - 1; i >= 0; i--) {
                final WindowToken token = wallpaperTokens.get(i);
                final int numWindows = token.windows.size();
                for (int j = numWindows - 1; j >= 0; j--) {
                    final WindowState wallpaper = token.windows.get(j);
                    final WindowStateAnimator winAnimator = wallpaper.mWinAnimator;
                    if (!winAnimator.mLastHidden) {
                        winAnimator.hide();
                        mService.dispatchWallpaperVisibility(wallpaper, false);
                        setPendingLayoutChanges(Display.DEFAULT_DISPLAY,
                                WindowManagerPolicy.FINISH_LAYOUT_REDO_WALLPAPER);
                    }
                }
                if (WindowManagerService.DEBUG_WALLPAPER_LIGHT && !token.hidden) Slog.d(TAG,
                        "Hiding wallpaper " + token + " from " + w
                        + " target=" + wallpaperTarget + " lower=" + lowerWallpaperTarget
                        + "\n" + Debug.getCallers(5, "  "));
                token.hidden = true;
            }
        }
    }

    private void updateAppWindowsLocked(int displayId) {
        ArrayList<TaskStack> stacks = mService.getDisplayContentLocked(displayId).getStacks();
        for (int stackNdx = stacks.size() - 1; stackNdx >= 0; --stackNdx) {
            final TaskStack stack = stacks.get(stackNdx);
            final ArrayList<Task> tasks = stack.getTasks();
            for (int taskNdx = tasks.size() - 1; taskNdx >= 0; --taskNdx) {
                final AppTokenList tokens = tasks.get(taskNdx).mAppTokens;
                for (int tokenNdx = tokens.size() - 1; tokenNdx >= 0; --tokenNdx) {
                    final AppWindowAnimator appAnimator = tokens.get(tokenNdx).mAppAnimator;
                    final boolean wasAnimating = appAnimator.animation != null
                            && appAnimator.animation != AppWindowAnimator.sDummyAnimation;
                    if (appAnimator.stepAnimationLocked(mCurrentTime)) {
                        mAnimating = true;
                    } else if (wasAnimating) {
                        // stopped animating, do one more pass through the layout
                        setAppLayoutChanges(appAnimator,
                                WindowManagerPolicy.FINISH_LAYOUT_REDO_WALLPAPER,
                                "appToken " + appAnimator.mAppToken + " done");
                        if (WindowManagerService.DEBUG_ANIM) Slog.v(TAG,
                                "updateWindowsApps...: done animating " + appAnimator.mAppToken);
                    }
                }
            }

            final AppTokenList exitingAppTokens = stack.mExitingAppTokens;
            final int NEAT = exitingAppTokens.size();
            for (int i = 0; i < NEAT; i++) {
                final AppWindowAnimator appAnimator = exitingAppTokens.get(i).mAppAnimator;
                final boolean wasAnimating = appAnimator.animation != null
                        && appAnimator.animation != AppWindowAnimator.sDummyAnimation;
                if (appAnimator.stepAnimationLocked(mCurrentTime)) {
                    mAnimating = true;
                } else if (wasAnimating) {
                    // stopped animating, do one more pass through the layout
                    setAppLayoutChanges(appAnimator, WindowManagerPolicy.FINISH_LAYOUT_REDO_WALLPAPER,
                        "exiting appToken " + appAnimator.mAppToken + " done");
                    if (WindowManagerService.DEBUG_ANIM) Slog.v(TAG,
                            "updateWindowsApps...: done animating exiting " + appAnimator.mAppToken);
                }
            }
        }
    }

    private void updateWindowsLocked(final int displayId) {
        ++mAnimTransactionSequence;

        final WindowList windows = mService.getWindowListLocked(displayId);
        ArrayList<WindowStateAnimator> unForceHiding = null;
        boolean wallpaperInUnForceHiding = false;
        WindowState wallpaper = null;

        if (mKeyguardGoingAway) {
            for (int i = windows.size() - 1; i >= 0; i--) {
                WindowState win = windows.get(i);
                if (!mPolicy.isKeyguardHostWindow(win.mAttrs)) {
                    continue;
                }
                final WindowStateAnimator winAnimator = win.mWinAnimator;
                if ((win.mAttrs.privateFlags & PRIVATE_FLAG_KEYGUARD) != 0) {
                    if (!winAnimator.mAnimating) {
                        // Create a new animation to delay until keyguard is gone on its own.
                        winAnimator.mAnimation = new AlphaAnimation(1.0f, 1.0f);
                        winAnimator.mAnimation.setDuration(KEYGUARD_ANIM_TIMEOUT_MS);
                        winAnimator.mAnimationIsEntrance = false;
                    }
                } else {
                    mKeyguardGoingAway = false;
                    winAnimator.clearAnimation();
                }
                break;
            }
        }

        mForceHiding = KEYGUARD_NOT_SHOWN;

        final WindowState imeTarget = mService.mInputMethodTarget;
        final boolean showImeOverKeyguard = imeTarget != null && imeTarget.isVisibleNow() &&
                (imeTarget.getAttrs().flags & FLAG_SHOW_WHEN_LOCKED) != 0;

        final WindowState winShowWhenLocked = (WindowState) mPolicy.getWinShowWhenLockedLw();
        final AppWindowToken appShowWhenLocked = winShowWhenLocked == null ?
                null : winShowWhenLocked.mAppToken;

        for (int i = windows.size() - 1; i >= 0; i--) {
            WindowState win = windows.get(i);
            WindowStateAnimator winAnimator = win.mWinAnimator;
            final int flags = win.mAttrs.flags;

            if (winAnimator.mSurfaceControl != null) {
                final boolean wasAnimating = winAnimator.mWasAnimating;
                final boolean nowAnimating = winAnimator.stepAnimationLocked(mCurrentTime);

                if (WindowManagerService.DEBUG_WALLPAPER) {
                    Slog.v(TAG, win + ": wasAnimating=" + wasAnimating +
                            ", nowAnimating=" + nowAnimating);
                }

                if (wasAnimating && !winAnimator.mAnimating && mService.mWallpaperTarget == win) {
                    mBulkUpdateParams |= SET_WALLPAPER_MAY_CHANGE;
                    setPendingLayoutChanges(Display.DEFAULT_DISPLAY,
                            WindowManagerPolicy.FINISH_LAYOUT_REDO_WALLPAPER);
                    if (WindowManagerService.DEBUG_LAYOUT_REPEATS) {
                        mService.debugLayoutRepeats("updateWindowsAndWallpaperLocked 2",
                                getPendingLayoutChanges(Display.DEFAULT_DISPLAY));
                    }
                }

                if (mPolicy.isForceHiding(win.mAttrs)) {
                    if (!wasAnimating && nowAnimating) {
                        if (WindowManagerService.DEBUG_ANIM ||
                                WindowManagerService.DEBUG_VISIBILITY) Slog.v(TAG,
                                "Animation started that could impact force hide: " + win);
                        mBulkUpdateParams |= SET_FORCE_HIDING_CHANGED;
                        setPendingLayoutChanges(displayId,
                                WindowManagerPolicy.FINISH_LAYOUT_REDO_WALLPAPER);
                        if (WindowManagerService.DEBUG_LAYOUT_REPEATS) {
                            mService.debugLayoutRepeats("updateWindowsAndWallpaperLocked 3",
                                    getPendingLayoutChanges(displayId));
                        }
                        mService.mFocusMayChange = true;
                    } else if (mKeyguardGoingAway && !nowAnimating) {
                        // Timeout!!
                        Slog.e(TAG, "Timeout waiting for animation to startup");
                        mPolicy.startKeyguardExitAnimation(0, 0);
                        mKeyguardGoingAway = false;
                    }
                    if (win.isReadyForDisplay()) {
                        if (nowAnimating) {
                            if (winAnimator.mAnimationIsEntrance) {
                                mForceHiding = KEYGUARD_ANIMATING_IN;
                            } else {
                                mForceHiding = KEYGUARD_ANIMATING_OUT;
                            }
                        } else {
                            mForceHiding = win.isDrawnLw() ? KEYGUARD_SHOWN : KEYGUARD_NOT_SHOWN;
                        }
                    }
                    if (WindowManagerService.DEBUG_VISIBILITY) Slog.v(TAG,
                            "Force hide " + forceHidingToString()
                            + " hasSurface=" + win.mHasSurface
                            + " policyVis=" + win.mPolicyVisibility
                            + " destroying=" + win.mDestroying
                            + " attHidden=" + win.mAttachedHidden
                            + " vis=" + win.mViewVisibility
                            + " hidden=" + win.mRootToken.hidden
                            + " anim=" + win.mWinAnimator.mAnimation);
                } else if (mPolicy.canBeForceHidden(win, win.mAttrs)) {
                    final boolean hideWhenLocked = !((win.mIsImWindow && showImeOverKeyguard) ||
                            (appShowWhenLocked != null && appShowWhenLocked == win.mAppToken));
                    final boolean changed;
                    if (((mForceHiding == KEYGUARD_ANIMATING_IN)
                                && (!winAnimator.isAnimating() || hideWhenLocked))
                            || ((mForceHiding == KEYGUARD_SHOWN) && hideWhenLocked)) {
                        changed = win.hideLw(false, false);
                        if (WindowManagerService.DEBUG_VISIBILITY && changed) Slog.v(TAG,
                                "Now policy hidden: " + win);
                    } else {
                        changed = win.showLw(false, false);
                        if (WindowManagerService.DEBUG_VISIBILITY && changed) Slog.v(TAG,
                                "Now policy shown: " + win);
                        if (changed) {
                            if ((mBulkUpdateParams & SET_FORCE_HIDING_CHANGED) != 0
                                    && win.isVisibleNow() /*w.isReadyForDisplay()*/) {
                                if (unForceHiding == null) {
                                    unForceHiding = new ArrayList<WindowStateAnimator>();
                                }
                                unForceHiding.add(winAnimator);
                                if ((flags & FLAG_SHOW_WALLPAPER) != 0) {
                                    wallpaperInUnForceHiding = true;
                                }
                            }
                            final WindowState currentFocus = mService.mCurrentFocus;
                            if (currentFocus == null || currentFocus.mLayer < win.mLayer) {
                                // We are showing on to of the current
                                // focus, so re-evaluate focus to make
                                // sure it is correct.
                                if (WindowManagerService.DEBUG_FOCUS_LIGHT) Slog.v(TAG,
                                        "updateWindowsLocked: setting mFocusMayChange true");
                                mService.mFocusMayChange = true;
                            }
                        }
                    }
                    if (changed && (flags & FLAG_SHOW_WALLPAPER) != 0) {
                        mBulkUpdateParams |= SET_WALLPAPER_MAY_CHANGE;
                        setPendingLayoutChanges(Display.DEFAULT_DISPLAY,
                                WindowManagerPolicy.FINISH_LAYOUT_REDO_WALLPAPER);
                        if (WindowManagerService.DEBUG_LAYOUT_REPEATS) {
                            mService.debugLayoutRepeats("updateWindowsAndWallpaperLocked 4",
                                    getPendingLayoutChanges(Display.DEFAULT_DISPLAY));
                        }
                    }
                }
            }

            final AppWindowToken atoken = win.mAppToken;
            if (winAnimator.mDrawState == WindowStateAnimator.READY_TO_SHOW) {
                if (atoken == null || atoken.allDrawn) {
                    if (winAnimator.performShowLocked()) {
                        setPendingLayoutChanges(displayId,
                                WindowManagerPolicy.FINISH_LAYOUT_REDO_ANIM);
                        if (WindowManagerService.DEBUG_LAYOUT_REPEATS) {
                            mService.debugLayoutRepeats("updateWindowsAndWallpaperLocked 5",
                                    getPendingLayoutChanges(displayId));
                        }
                    }
                }
            }
            final AppWindowAnimator appAnimator = winAnimator.mAppAnimator;
            if (appAnimator != null && appAnimator.thumbnail != null) {
                if (appAnimator.thumbnailTransactionSeq != mAnimTransactionSequence) {
                    appAnimator.thumbnailTransactionSeq = mAnimTransactionSequence;
                    appAnimator.thumbnailLayer = 0;
                }
                if (appAnimator.thumbnailLayer < winAnimator.mAnimLayer) {
                    appAnimator.thumbnailLayer = winAnimator.mAnimLayer;
                }
            }
            if (win.mIsWallpaper) {
                wallpaper = win;
            }
        } // end forall windows

        // If we have windows that are being show due to them no longer
        // being force-hidden, apply the appropriate animation to them.
        if (unForceHiding != null) {
            boolean startKeyguardExit = true;
            for (int i=unForceHiding.size()-1; i>=0; i--) {
                Animation a = null;
                if (!mKeyguardGoingAwayDisableWindowAnimations) {
                    a = mPolicy.createForceHideEnterAnimation(wallpaperInUnForceHiding,
                            mKeyguardGoingAwayToNotificationShade);
                }
                if (a != null) {
                    final WindowStateAnimator winAnimator = unForceHiding.get(i);
                    winAnimator.setAnimation(a);
                    winAnimator.keyguardGoingAwayAnimation = true;
                    if (startKeyguardExit && mKeyguardGoingAway) {
                        // Do one time only.
                        mPolicy.startKeyguardExitAnimation(mCurrentTime + a.getStartOffset(),
                                a.getDuration());
                        mKeyguardGoingAway = false;
                        startKeyguardExit = false;
                    }
                }
            }

            // Wallpaper is going away in un-force-hide motion, animate it as well.
            if (!wallpaperInUnForceHiding && wallpaper != null
                    && !mKeyguardGoingAwayDisableWindowAnimations) {
                Animation a = mPolicy.createForceHideWallpaperExitAnimation(
                        mKeyguardGoingAwayToNotificationShade);
                if (a != null) {
                    WindowStateAnimator animator = wallpaper.mWinAnimator;
                    animator.setAnimation(a);
                }
            }
        }
    }

    private void updateWallpaperLocked(int displayId) {
        mService.getDisplayContentLocked(displayId).resetAnimationBackgroundAnimator();

        final WindowList windows = mService.getWindowListLocked(displayId);
        WindowState detachedWallpaper = null;

        for (int i = windows.size() - 1; i >= 0; i--) {
            final WindowState win = windows.get(i);
            WindowStateAnimator winAnimator = win.mWinAnimator;
            if (winAnimator.mSurfaceControl == null) {
                continue;
            }

            final int flags = win.mAttrs.flags;

            // If this window is animating, make a note that we have
            // an animating window and take care of a request to run
            // a detached wallpaper animation.
            if (winAnimator.mAnimating) {
                if (winAnimator.mAnimation != null) {
                    if ((flags & FLAG_SHOW_WALLPAPER) != 0
                            && winAnimator.mAnimation.getDetachWallpaper()) {
                        detachedWallpaper = win;
                    }
                    final int color = winAnimator.mAnimation.getBackgroundColor();
                    if (color != 0) {
                        final TaskStack stack = win.getStack();
                        if (stack != null) {
                            stack.setAnimationBackground(winAnimator, color);
                        }
                    }
                }
                mAnimating = true;
            }

            // If this window's app token is running a detached wallpaper
            // animation, make a note so we can ensure the wallpaper is
            // displayed behind it.
            final AppWindowAnimator appAnimator = winAnimator.mAppAnimator;
            if (appAnimator != null && appAnimator.animation != null
                    && appAnimator.animating) {
                if ((flags & FLAG_SHOW_WALLPAPER) != 0
                        && appAnimator.animation.getDetachWallpaper()) {
                    detachedWallpaper = win;
                }

                final int color = appAnimator.animation.getBackgroundColor();
                if (color != 0) {
                    final TaskStack stack = win.getStack();
                    if (stack != null) {
                        stack.setAnimationBackground(winAnimator, color);
                    }
                }
            }
        } // end forall windows

        if (mWindowDetachedWallpaper != detachedWallpaper) {
            if (WindowManagerService.DEBUG_WALLPAPER) Slog.v(TAG,
                    "Detached wallpaper changed from " + mWindowDetachedWallpaper
                    + " to " + detachedWallpaper);
            mWindowDetachedWallpaper = detachedWallpaper;
            mBulkUpdateParams |= SET_WALLPAPER_MAY_CHANGE;
        }
    }

    /** See if any windows have been drawn, so they (and others associated with them) can now be
     *  shown. */
    private void testTokenMayBeDrawnLocked(int displayId) {
        // See if any windows have been drawn, so they (and others
        // associated with them) can now be shown.
        final ArrayList<Task> tasks = mService.getDisplayContentLocked(displayId).getTasks();
        final int numTasks = tasks.size();
        for (int taskNdx = 0; taskNdx < numTasks; ++taskNdx) {
            final AppTokenList tokens = tasks.get(taskNdx).mAppTokens;
            final int numTokens = tokens.size();
            for (int tokenNdx = 0; tokenNdx < numTokens; ++tokenNdx) {
                final AppWindowToken wtoken = tokens.get(tokenNdx);
                AppWindowAnimator appAnimator = wtoken.mAppAnimator;
                final boolean allDrawn = wtoken.allDrawn;
                if (allDrawn != appAnimator.allDrawn) {
                    appAnimator.allDrawn = allDrawn;
                    if (allDrawn) {
                        // The token has now changed state to having all
                        // windows shown...  what to do, what to do?
                        if (appAnimator.freezingScreen) {
                            appAnimator.showAllWindowsLocked();
                            mService.unsetAppFreezingScreenLocked(wtoken, false, true);
                            if (WindowManagerService.DEBUG_ORIENTATION) Slog.i(TAG,
                                    "Setting mOrientationChangeComplete=true because wtoken "
                                    + wtoken + " numInteresting=" + wtoken.numInterestingWindows
                                    + " numDrawn=" + wtoken.numDrawnWindows);
                            // This will set mOrientationChangeComplete and cause a pass through layout.
                            setAppLayoutChanges(appAnimator,
                                    WindowManagerPolicy.FINISH_LAYOUT_REDO_WALLPAPER,
                                    "testTokenMayBeDrawnLocked: freezingScreen");
                        } else {
                            setAppLayoutChanges(appAnimator,
                                    WindowManagerPolicy.FINISH_LAYOUT_REDO_ANIM,
                                    "testTokenMayBeDrawnLocked");

                            // We can now show all of the drawn windows!
                            if (!mService.mOpeningApps.contains(wtoken)) {
                                mAnimating |= appAnimator.showAllWindowsLocked();
                            }
                        }
                    }
                }
            }
        }
    }


    /** Locked on mService.mWindowMap. */
    private void animateLocked() {
        if (!mInitialized) {
            return;
        }

        mCurrentTime = SystemClock.uptimeMillis();
        mBulkUpdateParams = SET_ORIENTATION_CHANGE_COMPLETE;
        boolean wasAnimating = mAnimating;
        mAnimating = false;
        if (WindowManagerService.DEBUG_WINDOW_TRACE) {
            Slog.i(TAG, "!!! animate: entry time=" + mCurrentTime);
        }

        if (WindowManagerService.SHOW_TRANSACTIONS) Slog.i(
                TAG, ">>> OPEN TRANSACTION animateLocked");
        SurfaceControl.openTransaction();
        SurfaceControl.setAnimationTransaction();
        try {
            final int numDisplays = mDisplayContentsAnimators.size();
            for (int i = 0; i < numDisplays; i++) {
                final int displayId = mDisplayContentsAnimators.keyAt(i);
                updateAppWindowsLocked(displayId);
                DisplayContentsAnimator displayAnimator = mDisplayContentsAnimators.valueAt(i);

                final ScreenRotationAnimation screenRotationAnimation =
                        displayAnimator.mScreenRotationAnimation;
                if (screenRotationAnimation != null && screenRotationAnimation.isAnimating()) {
                    if (screenRotationAnimation.stepAnimationLocked(mCurrentTime)) {
                        mAnimating = true;
                    } else {
                        mBulkUpdateParams |= SET_UPDATE_ROTATION;
                        screenRotationAnimation.kill();
                        displayAnimator.mScreenRotationAnimation = null;

                        //TODO (multidisplay): Accessibility supported only for the default display.
                        if (mService.mAccessibilityController != null
                                && displayId == Display.DEFAULT_DISPLAY) {
                            // We just finished rotation animation which means we did not
                            // anounce the rotation and waited for it to end, announce now.
                            mService.mAccessibilityController.onRotationChangedLocked(
                                    mService.getDefaultDisplayContentLocked(), mService.mRotation);
                        }
                    }
                }

                // Update animations of all applications, including those
                // associated with exiting/removed apps
                updateWindowsLocked(displayId);
                updateWallpaperLocked(displayId);

                final WindowList windows = mService.getWindowListLocked(displayId);
                final int N = windows.size();
                for (int j = 0; j < N; j++) {
                    windows.get(j).mWinAnimator.prepareSurfaceLocked(true);
                }
            }

            for (int i = 0; i < numDisplays; i++) {
                final int displayId = mDisplayContentsAnimators.keyAt(i);

                testTokenMayBeDrawnLocked(displayId);

                final ScreenRotationAnimation screenRotationAnimation =
                        mDisplayContentsAnimators.valueAt(i).mScreenRotationAnimation;
                if (screenRotationAnimation != null) {
                    screenRotationAnimation.updateSurfacesInTransaction();
                }

                mAnimating |= mService.getDisplayContentLocked(displayId).animateDimLayers();

                //TODO (multidisplay): Magnification is supported only for the default display.
                if (mService.mAccessibilityController != null
                        && displayId == Display.DEFAULT_DISPLAY) {
                    mService.mAccessibilityController.drawMagnifiedRegionBorderIfNeededLocked();
                }
            }

            if (mAnimating) {
                mService.scheduleAnimationLocked();
            }

            mService.setFocusedStackLayer();

            if (mService.mWatermark != null) {
                mService.mWatermark.drawIfNeeded();
            }
        } catch (RuntimeException e) {
            Slog.wtf(TAG, "Unhandled exception in Window Manager", e);
        } finally {
            SurfaceControl.closeTransaction();
            if (WindowManagerService.SHOW_TRANSACTIONS) Slog.i(
                    TAG, "<<< CLOSE TRANSACTION animateLocked");
        }

        boolean hasPendingLayoutChanges = false;
        final int numDisplays = mService.mDisplayContents.size();
        for (int displayNdx = 0; displayNdx < numDisplays; ++displayNdx) {
            final DisplayContent displayContent = mService.mDisplayContents.valueAt(displayNdx);
            final int pendingChanges = getPendingLayoutChanges(displayContent.getDisplayId());
            if ((pendingChanges & WindowManagerPolicy.FINISH_LAYOUT_REDO_WALLPAPER) != 0) {
                mBulkUpdateParams |= SET_WALLPAPER_ACTION_PENDING;
            }
            if (pendingChanges != 0) {
                hasPendingLayoutChanges = true;
            }
        }

        boolean doRequest = false;
        if (mBulkUpdateParams != 0) {
            doRequest = mService.copyAnimToLayoutParamsLocked();
        }

        if (hasPendingLayoutChanges || doRequest) {
            mService.requestTraversalLocked();
        }

        if (!mAnimating && wasAnimating) {
            mService.requestTraversalLocked();
        }
        if (WindowManagerService.DEBUG_WINDOW_TRACE) {
            Slog.i(TAG, "!!! animate: exit mAnimating=" + mAnimating
                + " mBulkUpdateParams=" + Integer.toHexString(mBulkUpdateParams)
                + " mPendingLayoutChanges(DEFAULT_DISPLAY)="
                + Integer.toHexString(getPendingLayoutChanges(Display.DEFAULT_DISPLAY)));
        }
    }

    static String bulkUpdateParamsToString(int bulkUpdateParams) {
        StringBuilder builder = new StringBuilder(128);
        if ((bulkUpdateParams & LayoutFields.SET_UPDATE_ROTATION) != 0) {
            builder.append(" UPDATE_ROTATION");
        }
        if ((bulkUpdateParams & LayoutFields.SET_WALLPAPER_MAY_CHANGE) != 0) {
            builder.append(" WALLPAPER_MAY_CHANGE");
        }
        if ((bulkUpdateParams & LayoutFields.SET_FORCE_HIDING_CHANGED) != 0) {
            builder.append(" FORCE_HIDING_CHANGED");
        }
        if ((bulkUpdateParams & LayoutFields.SET_ORIENTATION_CHANGE_COMPLETE) != 0) {
            builder.append(" ORIENTATION_CHANGE_COMPLETE");
        }
        if ((bulkUpdateParams & LayoutFields.SET_TURN_ON_SCREEN) != 0) {
            builder.append(" TURN_ON_SCREEN");
        }
        return builder.toString();
    }

    public void dumpLocked(PrintWriter pw, String prefix, boolean dumpAll) {
        final String subPrefix = "  " + prefix;
        final String subSubPrefix = "  " + subPrefix;

        for (int i = 0; i < mDisplayContentsAnimators.size(); i++) {
            pw.print(prefix); pw.print("DisplayContentsAnimator #");
                    pw.print(mDisplayContentsAnimators.keyAt(i));
                    pw.println(":");
            DisplayContentsAnimator displayAnimator = mDisplayContentsAnimators.valueAt(i);
            final WindowList windows =
                    mService.getWindowListLocked(mDisplayContentsAnimators.keyAt(i));
            final int N = windows.size();
            for (int j = 0; j < N; j++) {
                WindowStateAnimator wanim = windows.get(j).mWinAnimator;
                pw.print(subPrefix); pw.print("Window #"); pw.print(j);
                        pw.print(": "); pw.println(wanim);
            }
            if (displayAnimator.mScreenRotationAnimation != null) {
                pw.print(subPrefix); pw.println("mScreenRotationAnimation:");
                displayAnimator.mScreenRotationAnimation.printTo(subSubPrefix, pw);
            } else if (dumpAll) {
                pw.print(subPrefix); pw.println("no ScreenRotationAnimation ");
            }
        }

        pw.println();

        if (dumpAll) {
            pw.print(prefix); pw.print("mAnimTransactionSequence=");
                    pw.print(mAnimTransactionSequence);
                    pw.print(" mForceHiding="); pw.println(forceHidingToString());
            pw.print(prefix); pw.print("mCurrentTime=");
                    pw.println(TimeUtils.formatUptime(mCurrentTime));
        }
        if (mBulkUpdateParams != 0) {
            pw.print(prefix); pw.print("mBulkUpdateParams=0x");
                    pw.print(Integer.toHexString(mBulkUpdateParams));
                    pw.println(bulkUpdateParamsToString(mBulkUpdateParams));
        }
        if (mWindowDetachedWallpaper != null) {
            pw.print(prefix); pw.print("mWindowDetachedWallpaper=");
                pw.println(mWindowDetachedWallpaper);
        }
        if (mUniverseBackground != null) {
            pw.print(prefix); pw.print("mUniverseBackground="); pw.print(mUniverseBackground);
                    pw.print(" mAboveUniverseLayer="); pw.println(mAboveUniverseLayer);
        }
    }

    int getPendingLayoutChanges(final int displayId) {
        if (displayId < 0) {
            return 0;
        }
        return mService.getDisplayContentLocked(displayId).pendingLayoutChanges;
    }

    void setPendingLayoutChanges(final int displayId, final int changes) {
        if (displayId >= 0) {
            mService.getDisplayContentLocked(displayId).pendingLayoutChanges |= changes;
        }
    }

    void setAppLayoutChanges(final AppWindowAnimator appAnimator, final int changes, String s) {
        // Used to track which displays layout changes have been done.
        SparseIntArray displays = new SparseIntArray(2);
        WindowList windows = appAnimator.mAppToken.allAppWindows;
        for (int i = windows.size() - 1; i >= 0; i--) {
            final int displayId = windows.get(i).getDisplayId();
            if (displayId >= 0 && displays.indexOfKey(displayId) < 0) {
                setPendingLayoutChanges(displayId, changes);
                if (WindowManagerService.DEBUG_LAYOUT_REPEATS) {
                    mService.debugLayoutRepeats(s, getPendingLayoutChanges(displayId));
                }
                // Keep from processing this display again.
                displays.put(displayId, changes);
            }
        }
    }

    private DisplayContentsAnimator getDisplayContentsAnimatorLocked(int displayId) {
        DisplayContentsAnimator displayAnimator = mDisplayContentsAnimators.get(displayId);
        if (displayAnimator == null) {
            displayAnimator = new DisplayContentsAnimator();
            mDisplayContentsAnimators.put(displayId, displayAnimator);
        }
        return displayAnimator;
    }

    void setScreenRotationAnimationLocked(int displayId, ScreenRotationAnimation animation) {
        if (displayId >= 0) {
            getDisplayContentsAnimatorLocked(displayId).mScreenRotationAnimation = animation;
        }
    }

    ScreenRotationAnimation getScreenRotationAnimationLocked(int displayId) {
        if (displayId < 0) {
            return null;
        }
        return getDisplayContentsAnimatorLocked(displayId).mScreenRotationAnimation;
    }

    private class DisplayContentsAnimator {
        ScreenRotationAnimation mScreenRotationAnimation = null;
    }
}
