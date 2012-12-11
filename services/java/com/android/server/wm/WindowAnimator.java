// Copyright 2012 Google Inc. All Rights Reserved.

package com.android.server.wm;

import static android.view.WindowManager.LayoutParams.FLAG_SHOW_WALLPAPER;
import static android.view.WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED;

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
import android.view.Surface;
import android.view.WindowManagerPolicy;
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

    final WindowManagerService mService;
    final Context mContext;
    final WindowManagerPolicy mPolicy;

    boolean mAnimating;

    final Runnable mAnimationRunnable;

    int mAdjResult;

    // Layout changes for individual Displays. Indexed by displayId.
    SparseIntArray mPendingLayoutChanges = new SparseIntArray();

    /** Time of current animation step. Reset on each iteration */
    long mCurrentTime;

    /** Skip repeated AppWindowTokens initialization. Note that AppWindowsToken's version of this
     * is a long initialized to Long.MIN_VALUE so that it doesn't match this value on startup. */
    private int mAnimTransactionSequence;

    // Window currently running an animation that has requested it be detached
    // from the wallpaper.  This means we need to ensure the wallpaper is
    // visible behind it in case it animates in a way that would allow it to be
    // seen. If multiple windows satisfy this, use the lowest window.
    WindowState mWindowDetachedWallpaper = null;

    WindowStateAnimator mUniverseBackground = null;
    int mAboveUniverseLayer = 0;

    int mBulkUpdateParams = 0;

    SparseArray<DisplayContentsAnimator> mDisplayContentsAnimators =
            new SparseArray<WindowAnimator.DisplayContentsAnimator>();

    boolean mInitialized = false;

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
            if (displayAnimator.mWindowAnimationBackgroundSurface != null) {
                displayAnimator.mWindowAnimationBackgroundSurface.kill();
                displayAnimator.mWindowAnimationBackgroundSurface = null;
            }
            if (displayAnimator.mScreenRotationAnimation != null) {
                displayAnimator.mScreenRotationAnimation.kill();
                displayAnimator.mScreenRotationAnimation = null;
            }
            if (displayAnimator.mDimAnimator != null) {
                displayAnimator.mDimAnimator.kill();
                displayAnimator.mDimAnimator = null;
            }
        }

        mDisplayContentsAnimators.delete(displayId);
        mPendingLayoutChanges.delete(displayId);
    }

    AppWindowAnimator getWallpaperAppAnimator() {
        return mService.mWallpaperTarget == null
                ? null : mService.mWallpaperTarget.mAppToken == null
                        ? null : mService.mWallpaperTarget.mAppToken.mAppAnimator;
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

    private void updateAppWindowsLocked() {
        int i;
        final ArrayList<AppWindowToken> appTokens = mService.mAnimatingAppTokens;
        final int NAT = appTokens.size();
        for (i=0; i<NAT; i++) {
            final AppWindowAnimator appAnimator = appTokens.get(i).mAppAnimator;
            final boolean wasAnimating = appAnimator.animation != null
                    && appAnimator.animation != AppWindowAnimator.sDummyAnimation;
            if (appAnimator.stepAnimationLocked(mCurrentTime)) {
                mAnimating = true;
            } else if (wasAnimating) {
                // stopped animating, do one more pass through the layout
                setAppLayoutChanges(appAnimator, WindowManagerPolicy.FINISH_LAYOUT_REDO_WALLPAPER,
                        "appToken " + appAnimator.mAppToken + " done");
                if (WindowManagerService.DEBUG_ANIM) Slog.v(TAG,
                        "updateWindowsApps...: done animating " + appAnimator.mAppToken);
            }
        }

        final int NEAT = mService.mExitingAppTokens.size();
        for (i=0; i<NEAT; i++) {
            final AppWindowAnimator appAnimator = mService.mExitingAppTokens.get(i).mAppAnimator;
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

    private void updateWindowsLocked(final int displayId) {
        ++mAnimTransactionSequence;

        final WindowList windows = mService.getWindowListLocked(displayId);
        ArrayList<WindowStateAnimator> unForceHiding = null;
        boolean wallpaperInUnForceHiding = false;
        mForceHiding = KEYGUARD_NOT_SHOWN;

        for (int i = windows.size() - 1; i >= 0; i--) {
            WindowState win = windows.get(i);
            WindowStateAnimator winAnimator = win.mWinAnimator;
            final int flags = winAnimator.mAttrFlags;

            if (winAnimator.mSurface != null) {
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
                            mPendingLayoutChanges.get(Display.DEFAULT_DISPLAY));
                    }
                }

                if (mPolicy.doesForceHide(win, win.mAttrs)) {
                    if (!wasAnimating && nowAnimating) {
                        if (WindowManagerService.DEBUG_ANIM ||
                                WindowManagerService.DEBUG_VISIBILITY) Slog.v(TAG,
                                "Animation started that could impact force hide: " + win);
                        mBulkUpdateParams |= SET_FORCE_HIDING_CHANGED;
                        setPendingLayoutChanges(displayId,
                                WindowManagerPolicy.FINISH_LAYOUT_REDO_WALLPAPER);
                        if (WindowManagerService.DEBUG_LAYOUT_REPEATS) {
                            mService.debugLayoutRepeats("updateWindowsAndWallpaperLocked 3",
                                mPendingLayoutChanges.get(displayId));
                        }
                        mService.mFocusMayChange = true;
                    }
                    if (win.isReadyForDisplay()) {
                        if (nowAnimating) {
                            if (winAnimator.mAnimationIsEntrance) {
                                mForceHiding = KEYGUARD_ANIMATING_IN;
                            } else {
                                mForceHiding = KEYGUARD_ANIMATING_OUT;
                            }
                        } else {
                            mForceHiding = KEYGUARD_SHOWN;
                        }
                    }
                    if (WindowManagerService.DEBUG_VISIBILITY) Slog.v(TAG,
                            "Force hide " + mForceHiding
                            + " hasSurface=" + win.mHasSurface
                            + " policyVis=" + win.mPolicyVisibility
                            + " destroying=" + win.mDestroying
                            + " attHidden=" + win.mAttachedHidden
                            + " vis=" + win.mViewVisibility
                            + " hidden=" + win.mRootToken.hidden
                            + " anim=" + win.mWinAnimator.mAnimation);
                } else if (mPolicy.canBeForceHidden(win, win.mAttrs)) {
                    final boolean hideWhenLocked =
                            (winAnimator.mAttrFlags & FLAG_SHOW_WHEN_LOCKED) == 0;
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
                            if (mCurrentFocus == null || mCurrentFocus.mLayer < win.mLayer) {
                                // We are showing on to of the current
                                // focus, so re-evaluate focus to make
                                // sure it is correct.
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
                                mPendingLayoutChanges.get(Display.DEFAULT_DISPLAY));
                        }
                    }
                }
            }

            final AppWindowToken atoken = win.mAppToken;
            if (winAnimator.mDrawState == WindowStateAnimator.READY_TO_SHOW) {
                if (atoken == null || atoken.allDrawn) {
                    if (winAnimator.performShowLocked()) {
                        mPendingLayoutChanges.put(displayId,
                                WindowManagerPolicy.FINISH_LAYOUT_REDO_ANIM);
                        if (WindowManagerService.DEBUG_LAYOUT_REPEATS) {
                            mService.debugLayoutRepeats("updateWindowsAndWallpaperLocked 5",
                                mPendingLayoutChanges.get(displayId));
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
        } // end forall windows

        // If we have windows that are being show due to them no longer
        // being force-hidden, apply the appropriate animation to them.
        if (unForceHiding != null) {
            for (int i=unForceHiding.size()-1; i>=0; i--) {
                Animation a = mPolicy.createForceHideEnterAnimation(wallpaperInUnForceHiding);
                if (a != null) {
                    final WindowStateAnimator winAnimator = unForceHiding.get(i);
                    winAnimator.setAnimation(a);
                    winAnimator.mAnimationIsEntrance = true;
                }
            }
        }
    }

    private void updateWallpaperLocked(int displayId) {
        final DisplayContentsAnimator displayAnimator =
                getDisplayContentsAnimatorLocked(displayId);
        final WindowList windows = mService.getWindowListLocked(displayId);
        WindowStateAnimator windowAnimationBackground = null;
        int windowAnimationBackgroundColor = 0;
        WindowState detachedWallpaper = null;
        final DimSurface windowAnimationBackgroundSurface =
                displayAnimator.mWindowAnimationBackgroundSurface;

        for (int i = windows.size() - 1; i >= 0; i--) {
            final WindowState win = windows.get(i);
            WindowStateAnimator winAnimator = win.mWinAnimator;
            if (winAnimator.mSurface == null) {
                continue;
            }

            final int flags = winAnimator.mAttrFlags;

            // If this window is animating, make a note that we have
            // an animating window and take care of a request to run
            // a detached wallpaper animation.
            if (winAnimator.mAnimating) {
                if (winAnimator.mAnimation != null) {
                    if ((flags & FLAG_SHOW_WALLPAPER) != 0
                            && winAnimator.mAnimation.getDetachWallpaper()) {
                        detachedWallpaper = win;
                    }
                    final int backgroundColor = winAnimator.mAnimation.getBackgroundColor();
                    if (backgroundColor != 0) {
                        if (windowAnimationBackground == null || (winAnimator.mAnimLayer <
                                windowAnimationBackground.mAnimLayer)) {
                            windowAnimationBackground = winAnimator;
                            windowAnimationBackgroundColor = backgroundColor;
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

                final int backgroundColor = appAnimator.animation.getBackgroundColor();
                if (backgroundColor != 0) {
                    if (windowAnimationBackground == null || (winAnimator.mAnimLayer <
                            windowAnimationBackground.mAnimLayer)) {
                        windowAnimationBackground = winAnimator;
                        windowAnimationBackgroundColor = backgroundColor;
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

        if (windowAnimationBackgroundColor != 0) {
            // If the window that wants black is the current wallpaper
            // target, then the black goes *below* the wallpaper so we
            // don't cause the wallpaper to suddenly disappear.
            int animLayer = windowAnimationBackground.mAnimLayer;
            WindowState win = windowAnimationBackground.mWin;
            if (mService.mWallpaperTarget == win || mService.mLowerWallpaperTarget == win
                    || mService.mUpperWallpaperTarget == win) {
                final int N = windows.size();
                for (int i = 0; i < N; i++) {
                    WindowStateAnimator winAnimator = windows.get(i).mWinAnimator;
                    if (winAnimator.mIsWallpaper) {
                        animLayer = winAnimator.mAnimLayer;
                        break;
                    }
                }
            }

            if (windowAnimationBackgroundSurface != null) {
                windowAnimationBackgroundSurface.show(
                        animLayer - WindowManagerService.LAYER_OFFSET_DIM,
                        windowAnimationBackgroundColor);
            }
        } else {
            if (windowAnimationBackgroundSurface != null) {
                windowAnimationBackgroundSurface.hide();
            }
        }
    }

    /** See if any windows have been drawn, so they (and others associated with them) can now be
     *  shown. */
    private void testTokenMayBeDrawnLocked() {
        // See if any windows have been drawn, so they (and others
        // associated with them) can now be shown.
        final ArrayList<AppWindowToken> appTokens = mService.mAnimatingAppTokens;
        final int NT = appTokens.size();
        for (int i=0; i<NT; i++) {
            AppWindowToken wtoken = appTokens.get(i);
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

    private void performAnimationsLocked(final int displayId) {
        updateWindowsLocked(displayId);
        updateWallpaperLocked(displayId);
    }

    // TODO(cmautner): Change the following comment when no longer locked on mWindowMap */
    /** Locked on mService.mWindowMap and this. */
    private void animateLocked() {
        if (!mInitialized) {
            return;
        }

        mPendingLayoutChanges.clear();
        mCurrentTime = SystemClock.uptimeMillis();
        mBulkUpdateParams = SET_ORIENTATION_CHANGE_COMPLETE;
        boolean wasAnimating = mAnimating;
        mAnimating = false;
        if (WindowManagerService.DEBUG_WINDOW_TRACE) {
            Slog.i(TAG, "!!! animate: entry time=" + mCurrentTime);
        }

        if (WindowManagerService.SHOW_TRANSACTIONS) Slog.i(
                TAG, ">>> OPEN TRANSACTION animateLocked");
        Surface.openTransaction();
        Surface.setAnimationTransaction();
        try {
            updateAppWindowsLocked();

            final int numDisplays = mDisplayContentsAnimators.size();
            for (int i = 0; i < numDisplays; i++) {
                final int displayId = mDisplayContentsAnimators.keyAt(i);
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
                    }
                }

                // Update animations of all applications, including those
                // associated with exiting/removed apps
                performAnimationsLocked(displayId);

                final WindowList windows = mService.getWindowListLocked(displayId);
                final int N = windows.size();
                for (int j = 0; j < N; j++) {
                    windows.get(j).mWinAnimator.prepareSurfaceLocked(true);
                }
            }

            testTokenMayBeDrawnLocked();

            for (int i = 0; i < numDisplays; i++) {
                final int displayId = mDisplayContentsAnimators.keyAt(i);
                DisplayContentsAnimator displayAnimator = mDisplayContentsAnimators.valueAt(i);

                final ScreenRotationAnimation screenRotationAnimation =
                        displayAnimator.mScreenRotationAnimation;
                if (screenRotationAnimation != null) {
                    screenRotationAnimation.updateSurfacesInTransaction();
                }

                final DimAnimator.Parameters dimParams = displayAnimator.mDimParams;
                final DimAnimator dimAnimator = displayAnimator.mDimAnimator;
                if (dimAnimator != null && dimParams != null) {
                    dimAnimator.updateParameters(mContext.getResources(), dimParams, mCurrentTime);
                }
                if (dimAnimator != null && dimAnimator.mDimShown) {
                    mAnimating |= dimAnimator.updateSurface(isDimmingLocked(displayId),
                            mCurrentTime, !mService.okToDisplay());
                }
            }

            if (mService.mWatermark != null) {
                mService.mWatermark.drawIfNeeded();
            }
        } catch (RuntimeException e) {
            Log.wtf(TAG, "Unhandled exception in Window Manager", e);
        } finally {
            Surface.closeTransaction();
            if (WindowManagerService.SHOW_TRANSACTIONS) Slog.i(
                    TAG, "<<< CLOSE TRANSACTION animateLocked");
        }

        for (int i = mPendingLayoutChanges.size() - 1; i >= 0; i--) {
            if ((mPendingLayoutChanges.valueAt(i)
                    & WindowManagerPolicy.FINISH_LAYOUT_REDO_WALLPAPER) != 0) {
                mBulkUpdateParams |= SET_WALLPAPER_ACTION_PENDING;
            }
        }

        if (mBulkUpdateParams != 0 || mPendingLayoutChanges.size() > 0) {
            if (mService.copyAnimToLayoutParamsLocked()) {
                mService.requestTraversalLocked();
            }
        }

        if (mAnimating) {
            mService.scheduleAnimationLocked();
        } else if (wasAnimating) {
            mService.requestTraversalLocked();
        }
        if (WindowManagerService.DEBUG_WINDOW_TRACE) {
            Slog.i(TAG, "!!! animate: exit mAnimating=" + mAnimating
                + " mBulkUpdateParams=" + Integer.toHexString(mBulkUpdateParams)
                + " mPendingLayoutChanges(DEFAULT_DISPLAY)="
                + Integer.toHexString(mPendingLayoutChanges.get(Display.DEFAULT_DISPLAY)));
        }
    }

    WindowState mCurrentFocus;
    void setCurrentFocus(final WindowState currentFocus) {
        mCurrentFocus = currentFocus;
    }

    boolean isDimmingLocked(int displayId) {
        return getDisplayContentsAnimatorLocked(displayId).mDimParams != null;
    }

    boolean isDimmingLocked(final WindowStateAnimator winAnimator) {
        DimAnimator.Parameters dimParams =
                getDisplayContentsAnimatorLocked(winAnimator.mWin.getDisplayId()).mDimParams;
        return dimParams != null && dimParams.mDimWinAnimator == winAnimator;
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
            if (displayAnimator.mWindowAnimationBackgroundSurface != null) {
                if (dumpAll || displayAnimator.mWindowAnimationBackgroundSurface.mDimShown) {
                    pw.print(subPrefix); pw.println("mWindowAnimationBackgroundSurface:");
                    displayAnimator.mWindowAnimationBackgroundSurface.printTo(subSubPrefix, pw);
                }
            }
            if (displayAnimator.mDimAnimator != null) {
                if (dumpAll || displayAnimator.mDimAnimator.mDimShown) {
                    pw.print(subPrefix); pw.println("mDimAnimator:");
                    displayAnimator.mDimAnimator.printTo(subSubPrefix, pw);
                }
            } else if (dumpAll) {
                pw.print(subPrefix); pw.println("no DimAnimator ");
            }
            if (displayAnimator.mDimParams != null) {
                pw.print(subPrefix); pw.println("mDimParams:");
                displayAnimator.mDimParams.printTo(subSubPrefix, pw);
            } else if (dumpAll) {
                pw.print(subPrefix); pw.println("no DimParams ");
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

    void setPendingLayoutChanges(final int displayId, final int changes) {
        mPendingLayoutChanges.put(displayId, mPendingLayoutChanges.get(displayId) | changes);
    }

    void setAppLayoutChanges(final AppWindowAnimator appAnimator, final int changes, String s) {
        // Used to track which displays layout changes have been done.
        SparseIntArray displays = new SparseIntArray();
        WindowList windows = appAnimator.mAppToken.allAppWindows;
        for (int i = windows.size() - 1; i >= 0; i--) {
            final int displayId = windows.get(i).getDisplayId();
            if (displays.indexOfKey(displayId) < 0) {
                setPendingLayoutChanges(displayId, changes);
                if (WindowManagerService.DEBUG_LAYOUT_REPEATS) {
                    mService.debugLayoutRepeats(s, mPendingLayoutChanges.get(displayId));
                }
                // Keep from processing this display again.
                displays.put(displayId, changes);
            }
        }
    }

    void setDimParamsLocked(int displayId, DimAnimator.Parameters dimParams) {
        DisplayContentsAnimator displayAnimator = mDisplayContentsAnimators.get(displayId);
        if (dimParams == null) {
            displayAnimator.mDimParams = null;
        } else {
            final WindowStateAnimator newWinAnimator = dimParams.mDimWinAnimator;

            // Only set dim params on the highest dimmed layer.
            final WindowStateAnimator existingDimWinAnimator =
                    displayAnimator.mDimParams == null ?
                            null : displayAnimator.mDimParams.mDimWinAnimator;
            // Don't turn on for an unshown surface, or for any layer but the highest
            // dimmed layer.
            if (newWinAnimator.mSurfaceShown && (existingDimWinAnimator == null
                    || !existingDimWinAnimator.mSurfaceShown
                    || existingDimWinAnimator.mAnimLayer < newWinAnimator.mAnimLayer)) {
                displayAnimator.mDimParams = new DimAnimator.Parameters(dimParams);
            }
        }
    }

    private DisplayContentsAnimator getDisplayContentsAnimatorLocked(int displayId) {
        DisplayContentsAnimator displayAnimator = mDisplayContentsAnimators.get(displayId);
        if (displayAnimator == null) {
            displayAnimator = new DisplayContentsAnimator(displayId);
            mDisplayContentsAnimators.put(displayId, displayAnimator);
        }
        return displayAnimator;
    }

    void setScreenRotationAnimationLocked(int displayId, ScreenRotationAnimation animation) {
        getDisplayContentsAnimatorLocked(displayId).mScreenRotationAnimation = animation;
    }

    ScreenRotationAnimation getScreenRotationAnimationLocked(int displayId) {
        return getDisplayContentsAnimatorLocked(displayId).mScreenRotationAnimation;
    }

    private class DisplayContentsAnimator {
        DimAnimator mDimAnimator = null;
        DimAnimator.Parameters mDimParams = null;
        DimSurface mWindowAnimationBackgroundSurface = null;
        ScreenRotationAnimation mScreenRotationAnimation = null;

        public DisplayContentsAnimator(int displayId) {
            mDimAnimator = new DimAnimator(mService.mFxSession, displayId);
            mWindowAnimationBackgroundSurface = new DimSurface(mService.mFxSession,
                    mService.getDisplayContentLocked(displayId));
        }
    }
}
