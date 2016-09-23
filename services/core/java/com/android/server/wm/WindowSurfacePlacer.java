
package com.android.server.wm;

import static android.app.ActivityManagerInternal.APP_TRANSITION_SAVED_SURFACE;
import static android.app.ActivityManagerInternal.APP_TRANSITION_STARTING_WINDOW;
import static android.app.ActivityManagerInternal.APP_TRANSITION_TIMEOUT;
import static android.app.ActivityManagerInternal.APP_TRANSITION_WINDOWS_DRAWN;
import static android.view.WindowManager.LayoutParams.PRIVATE_FLAG_KEYGUARD;
import static android.view.WindowManager.LayoutParams.TYPE_DREAM;
import static android.view.WindowManagerPolicy.FINISH_LAYOUT_REDO_CONFIG;
import static android.view.WindowManagerPolicy.FINISH_LAYOUT_REDO_LAYOUT;
import static android.view.WindowManagerPolicy.FINISH_LAYOUT_REDO_WALLPAPER;
import static com.android.server.wm.WindowManagerDebugConfig.DEBUG;
import static com.android.server.wm.WindowManagerDebugConfig.DEBUG_APP_TRANSITIONS;
import static com.android.server.wm.WindowManagerDebugConfig.DEBUG_LAYOUT;
import static com.android.server.wm.WindowManagerDebugConfig.SHOW_LIGHT_TRANSACTIONS;
import static com.android.server.wm.WindowManagerDebugConfig.SHOW_TRANSACTIONS;
import static com.android.server.wm.WindowManagerDebugConfig.TAG_WITH_CLASS_NAME;
import static com.android.server.wm.WindowManagerDebugConfig.TAG_WM;
import static com.android.server.wm.WindowManagerService.H.DO_TRAVERSAL;
import static com.android.server.wm.WindowManagerService.H.NOTIFY_APP_TRANSITION_STARTING;
import static com.android.server.wm.WindowManagerService.H.REPORT_WINDOWS_CHANGE;
import static com.android.server.wm.WindowManagerService.H.UPDATE_DOCKED_STACK_DIVIDER;
import static com.android.server.wm.WindowManagerService.LAYOUT_REPEAT_THRESHOLD;
import static com.android.server.wm.WindowManagerService.MAX_ANIMATION_DURATION;
import static com.android.server.wm.WindowManagerService.UPDATE_FOCUS_PLACING_SURFACES;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.os.Debug;
import android.os.Trace;
import android.util.ArraySet;
import android.util.Slog;
import android.view.Display;
import android.view.DisplayInfo;
import android.view.Surface;
import android.view.SurfaceControl;
import android.view.View;
import android.view.WindowManager.LayoutParams;
import android.view.animation.Animation;

import com.android.server.wm.WindowManagerService.H;

import java.io.PrintWriter;
import java.util.ArrayList;

/**
 * Positions windows and their surfaces.
 *
 * It sets positions of windows by calculating their frames and then applies this by positioning
 * surfaces according to these frames. Z layer is still assigned withing WindowManagerService.
 */
class WindowSurfacePlacer {
    private static final String TAG = TAG_WITH_CLASS_NAME ? "WindowSurfacePlacer" : TAG_WM;
    private final WindowManagerService mService;
    private final WallpaperController mWallpaperControllerLocked;

    private boolean mInLayout = false;

    /** Only do a maximum of 6 repeated layouts. After that quit */
    private int mLayoutRepeatCount;

    static final int SET_UPDATE_ROTATION                = 1 << 0;
    static final int SET_WALLPAPER_MAY_CHANGE           = 1 << 1;
    static final int SET_FORCE_HIDING_CHANGED           = 1 << 2;
    static final int SET_ORIENTATION_CHANGE_COMPLETE    = 1 << 3;
    static final int SET_TURN_ON_SCREEN                 = 1 << 4;
    static final int SET_WALLPAPER_ACTION_PENDING       = 1 << 5;

    private final Rect mTmpStartRect = new Rect();
    private final Rect mTmpContentRect = new Rect();

    private boolean mTraversalScheduled;
    private int mDeferDepth = 0;

    private static final class LayerAndToken {
        public int layer;
        public AppWindowToken token;
    }
    private final LayerAndToken mTmpLayerAndToken = new LayerAndToken();

    private final ArrayList<SurfaceControl> mPendingDestroyingSurfaces = new ArrayList<>();

    public WindowSurfacePlacer(WindowManagerService service) {
        mService = service;
        mWallpaperControllerLocked = mService.mWallpaperControllerLocked;
    }

    /**
     * See {@link WindowManagerService#deferSurfaceLayout()}
     */
    void deferLayout() {
        mDeferDepth++;
    }

    /**
     * See {@link WindowManagerService#continueSurfaceLayout()}
     */
    void continueLayout() {
        mDeferDepth--;
        if (mDeferDepth <= 0) {
            performSurfacePlacement();
        }
    }

    final void performSurfacePlacement() {
        if (mDeferDepth > 0) {
            return;
        }
        int loopCount = 6;
        do {
            mTraversalScheduled = false;
            performSurfacePlacementLoop();
            mService.mH.removeMessages(DO_TRAVERSAL);
            loopCount--;
        } while (mTraversalScheduled && loopCount > 0);
        mService.mRoot.mWallpaperActionPending = false;
    }

    private void performSurfacePlacementLoop() {
        if (mInLayout) {
            if (DEBUG) {
                throw new RuntimeException("Recursive call!");
            }
            Slog.w(TAG, "performLayoutAndPlaceSurfacesLocked called while in layout. Callers="
                    + Debug.getCallers(3));
            return;
        }

        if (mService.mWaitingForConfig) {
            // Our configuration has changed (most likely rotation), but we
            // don't yet have the complete configuration to report to
            // applications.  Don't do any window layout until we have it.
            return;
        }

        if (!mService.mDisplayReady) {
            // Not yet initialized, nothing to do.
            return;
        }

        Trace.traceBegin(Trace.TRACE_TAG_WINDOW_MANAGER, "wmLayout");
        mInLayout = true;

        boolean recoveringMemory = false;
        if (!mService.mForceRemoves.isEmpty()) {
            recoveringMemory = true;
            // Wait a little bit for things to settle down, and off we go.
            while (!mService.mForceRemoves.isEmpty()) {
                final WindowState ws = mService.mForceRemoves.remove(0);
                Slog.i(TAG, "Force removing: " + ws);
                ws.removeImmediately();
            }
            Slog.w(TAG, "Due to memory failure, waiting a bit for next layout");
            Object tmp = new Object();
            synchronized (tmp) {
                try {
                    tmp.wait(250);
                } catch (InterruptedException e) {
                }
            }
        }

        try {
            mService.mRoot.performSurfacePlacement(recoveringMemory);

            mInLayout = false;

            if (mService.mRoot.layoutNeeded()) {
                if (++mLayoutRepeatCount < 6) {
                    requestTraversal();
                } else {
                    Slog.e(TAG, "Performed 6 layouts in a row. Skipping");
                    mLayoutRepeatCount = 0;
                }
            } else {
                mLayoutRepeatCount = 0;
            }

            if (mService.mWindowsChanged && !mService.mWindowChangeListeners.isEmpty()) {
                mService.mH.removeMessages(REPORT_WINDOWS_CHANGE);
                mService.mH.sendEmptyMessage(REPORT_WINDOWS_CHANGE);
            }
        } catch (RuntimeException e) {
            mInLayout = false;
            Slog.wtf(TAG, "Unhandled exception while laying out windows", e);
        }

        Trace.traceEnd(Trace.TRACE_TAG_WINDOW_MANAGER);
    }

    void debugLayoutRepeats(final String msg, int pendingLayoutChanges) {
        if (mLayoutRepeatCount >= LAYOUT_REPEAT_THRESHOLD) {
            Slog.v(TAG, "Layouts looping: " + msg +
                    ", mPendingLayoutChanges = 0x" + Integer.toHexString(pendingLayoutChanges));
        }
    }

    boolean isInLayout() {
        return mInLayout;
    }

    final void performLayoutLockedInner(final DisplayContent displayContent,
            boolean initial, boolean updateInputWindows) {
        if (!displayContent.layoutNeeded) {
            return;
        }
        displayContent.layoutNeeded = false;
        WindowList windows = displayContent.getWindowList();
        boolean isDefaultDisplay = displayContent.isDefaultDisplay;

        DisplayInfo displayInfo = displayContent.getDisplayInfo();
        final int dw = displayInfo.logicalWidth;
        final int dh = displayInfo.logicalHeight;

        if (mService.mInputConsumer != null) {
            mService.mInputConsumer.layout(dw, dh);
        }

        if (mService.mWallpaperInputConsumer != null) {
            mService.mWallpaperInputConsumer.layout(dw, dh);
        }

        final int N = windows.size();
        int i;

        if (DEBUG_LAYOUT) {
            Slog.v(TAG, "-------------------------------------");
            Slog.v(TAG, "performLayout: needed="
                    + displayContent.layoutNeeded + " dw=" + dw + " dh=" + dh);
        }

        mService.mPolicy.beginLayoutLw(isDefaultDisplay, dw, dh, mService.mRotation,
                mService.mGlobalConfiguration.uiMode);
        if (isDefaultDisplay) {
            // Not needed on non-default displays.
            mService.mSystemDecorLayer = mService.mPolicy.getSystemDecorLayerLw();
            mService.mScreenRect.set(0, 0, dw, dh);
        }

        mService.mPolicy.getContentRectLw(mTmpContentRect);
        displayContent.resize(mTmpContentRect);

        int seq = mService.mLayoutSeq+1;
        if (seq < 0) seq = 0;
        mService.mLayoutSeq = seq;

        boolean behindDream = false;

        // First perform layout of any root windows (not attached
        // to another window).
        int topAttached = -1;
        for (i = N-1; i >= 0; i--) {
            final WindowState win = windows.get(i);

            // Don't do layout of a window if it is not visible, or
            // soon won't be visible, to avoid wasting time and funky
            // changes while a window is animating away.
            final boolean gone = (behindDream && mService.mPolicy.canBeForceHidden(win, win.mAttrs))
                    || win.isGoneForLayoutLw();

            if (DEBUG_LAYOUT && !win.mLayoutAttached) {
                Slog.v(TAG, "1ST PASS " + win
                        + ": gone=" + gone + " mHaveFrame=" + win.mHaveFrame
                        + " mLayoutAttached=" + win.mLayoutAttached
                        + " screen changed=" + win.isConfigChanged());
                final AppWindowToken atoken = win.mAppToken;
                if (gone) Slog.v(TAG, "  GONE: mViewVisibility="
                        + win.mViewVisibility + " mRelayoutCalled="
                        + win.mRelayoutCalled + " hidden="
                        + win.mToken.hidden + " hiddenRequested="
                        + (atoken != null && atoken.hiddenRequested)
                        + " parentHidden=" + win.isParentWindowHidden());
                else Slog.v(TAG, "  VIS: mViewVisibility="
                        + win.mViewVisibility + " mRelayoutCalled="
                        + win.mRelayoutCalled + " hidden="
                        + win.mToken.hidden + " hiddenRequested="
                        + (atoken != null && atoken.hiddenRequested)
                        + " parentHidden=" + win.isParentWindowHidden());
            }

            // If this view is GONE, then skip it -- keep the current
            // frame, and let the caller know so they can ignore it
            // if they want.  (We do the normal layout for INVISIBLE
            // windows, since that means "perform layout as normal,
            // just don't display").
            if (!gone || !win.mHaveFrame || win.mLayoutNeeded
                    || ((win.isConfigChanged() || win.setReportResizeHints())
                            && !win.isGoneForLayoutLw() &&
                            ((win.mAttrs.privateFlags & PRIVATE_FLAG_KEYGUARD) != 0 ||
                            (win.mHasSurface && win.mAppToken != null &&
                            win.mAppToken.layoutConfigChanges)))) {
                if (!win.mLayoutAttached) {
                    if (initial) {
                        //Slog.i(TAG, "Window " + this + " clearing mContentChanged - initial");
                        win.mContentChanged = false;
                    }
                    if (win.mAttrs.type == TYPE_DREAM) {
                        // Don't layout windows behind a dream, so that if it
                        // does stuff like hide the status bar we won't get a
                        // bad transition when it goes away.
                        behindDream = true;
                    }
                    win.mLayoutNeeded = false;
                    win.prelayout();
                    mService.mPolicy.layoutWindowLw(win, null);
                    win.mLayoutSeq = seq;

                    // Window frames may have changed. Update dim layer with the new bounds.
                    final Task task = win.getTask();
                    if (task != null) {
                        displayContent.mDimLayerController.updateDimLayer(task);
                    }

                    if (DEBUG_LAYOUT) Slog.v(TAG,
                            "  LAYOUT: mFrame="
                            + win.mFrame + " mContainingFrame="
                            + win.mContainingFrame + " mDisplayFrame="
                            + win.mDisplayFrame);
                } else {
                    if (topAttached < 0) topAttached = i;
                }
            }
        }

        boolean attachedBehindDream = false;

        // Now perform layout of attached windows, which usually
        // depend on the position of the window they are attached to.
        // XXX does not deal with windows that are attached to windows
        // that are themselves attached.
        for (i = topAttached; i >= 0; i--) {
            final WindowState win = windows.get(i);

            if (win.mLayoutAttached) {
                if (DEBUG_LAYOUT) Slog.v(TAG,
                        "2ND PASS " + win + " mHaveFrame=" + win.mHaveFrame + " mViewVisibility="
                        + win.mViewVisibility + " mRelayoutCalled=" + win.mRelayoutCalled);
                // If this view is GONE, then skip it -- keep the current
                // frame, and let the caller know so they can ignore it
                // if they want.  (We do the normal layout for INVISIBLE
                // windows, since that means "perform layout as normal,
                // just don't display").
                if (attachedBehindDream && mService.mPolicy.canBeForceHidden(win, win.mAttrs)) {
                    continue;
                }
                if ((win.mViewVisibility != View.GONE && win.mRelayoutCalled)
                        || !win.mHaveFrame || win.mLayoutNeeded) {
                    if (initial) {
                        //Slog.i(TAG, "Window " + this + " clearing mContentChanged - initial");
                        win.mContentChanged = false;
                    }
                    win.mLayoutNeeded = false;
                    win.prelayout();
                    mService.mPolicy.layoutWindowLw(win, win.getParentWindow());
                    win.mLayoutSeq = seq;
                    if (DEBUG_LAYOUT) Slog.v(TAG,
                            "  LAYOUT: mFrame=" + win.mFrame + " mContainingFrame="
                            + win.mContainingFrame + " mDisplayFrame=" + win.mDisplayFrame);
                }
            } else if (win.mAttrs.type == TYPE_DREAM) {
                // Don't layout windows behind a dream, so that if it
                // does stuff like hide the status bar we won't get a
                // bad transition when it goes away.
                attachedBehindDream = behindDream;
            }
        }

        // Window frames may have changed. Tell the input dispatcher about it.
        mService.mInputMonitor.setUpdateInputWindowsNeededLw();
        if (updateInputWindows) {
            mService.mInputMonitor.updateInputWindowsLw(false /*force*/);
        }

        mService.mPolicy.finishLayoutLw();
        mService.mH.sendEmptyMessage(UPDATE_DOCKED_STACK_DIVIDER);
    }

    /**
     * @param windows List of windows on default display.
     * @return bitmap indicating if another pass through layout must be made.
     */
    int handleAppTransitionReadyLocked(WindowList windows) {
        int appsCount = mService.mOpeningApps.size();
        if (!transitionGoodToGo(appsCount)) {
            return 0;
        }
        Trace.traceBegin(Trace.TRACE_TAG_WINDOW_MANAGER, "AppTransitionReady");

        if (DEBUG_APP_TRANSITIONS) Slog.v(TAG, "**** GOOD TO GO");
        int transit = mService.mAppTransition.getAppTransition();
        if (mService.mSkipAppTransitionAnimation) {
            transit = AppTransition.TRANSIT_UNSET;
        }
        mService.mSkipAppTransitionAnimation = false;
        mService.mNoAnimationNotifyOnTransitionFinished.clear();

        mService.mH.removeMessages(H.APP_TRANSITION_TIMEOUT);

        final DisplayContent displayContent = mService.getDefaultDisplayContentLocked();
        displayContent.rebuildAppWindowList();

        mService.mRoot.mWallpaperMayChange = false;

        // The top-most window will supply the layout params,
        // and we will determine it below.
        LayoutParams animLp = null;
        int bestAnimLayer = -1;
        boolean fullscreenAnim = false;
        boolean voiceInteraction = false;

        int i;
        for (i = 0; i < appsCount; i++) {
            final AppWindowToken wtoken = mService.mOpeningApps.valueAt(i);
            // Clearing the mAnimatingExit flag before entering animation. It's set to
            // true if app window is removed, or window relayout to invisible.
            // This also affects window visibility. We need to clear it *before*
            // maybeUpdateTransitToWallpaper() as the transition selection depends on
            // wallpaper target visibility.
            wtoken.clearAnimatingFlags();

        }
        // Adjust wallpaper before we pull the lower/upper target, since pending changes
        // (like the clearAnimatingFlags() above) might affect wallpaper target result.
        if ((displayContent.pendingLayoutChanges & FINISH_LAYOUT_REDO_WALLPAPER) != 0 &&
                mWallpaperControllerLocked.adjustWallpaperWindows()) {
            mService.mLayersController.assignLayersLocked(windows);
            displayContent.layoutNeeded = true;
        }

        final WindowState lowerWallpaperTarget =
                mWallpaperControllerLocked.getLowerWallpaperTarget();
        final WindowState upperWallpaperTarget =
                mWallpaperControllerLocked.getUpperWallpaperTarget();

        boolean openingAppHasWallpaper = false;
        boolean closingAppHasWallpaper = false;
        final AppWindowToken lowerWallpaperAppToken;
        final AppWindowToken upperWallpaperAppToken;
        if (lowerWallpaperTarget == null) {
            lowerWallpaperAppToken = upperWallpaperAppToken = null;
        } else {
            lowerWallpaperAppToken = lowerWallpaperTarget.mAppToken;
            upperWallpaperAppToken = upperWallpaperTarget.mAppToken;
        }

        // Do a first pass through the tokens for two
        // things:
        // (1) Determine if both the closing and opening
        // app token sets are wallpaper targets, in which
        // case special animations are needed
        // (since the wallpaper needs to stay static
        // behind them).
        // (2) Find the layout params of the top-most
        // application window in the tokens, which is
        // what will control the animation theme.
        final int closingAppsCount = mService.mClosingApps.size();
        appsCount = closingAppsCount + mService.mOpeningApps.size();
        for (i = 0; i < appsCount; i++) {
            final AppWindowToken wtoken;
            if (i < closingAppsCount) {
                wtoken = mService.mClosingApps.valueAt(i);
                if (wtoken == lowerWallpaperAppToken || wtoken == upperWallpaperAppToken) {
                    closingAppHasWallpaper = true;
                }
            } else {
                wtoken = mService.mOpeningApps.valueAt(i - closingAppsCount);
                if (wtoken == lowerWallpaperAppToken || wtoken == upperWallpaperAppToken) {
                    openingAppHasWallpaper = true;
                }
            }

            voiceInteraction |= wtoken.voiceInteraction;

            if (wtoken.fillsParent()) {
                WindowState ws = wtoken.findMainWindow();
                if (ws != null) {
                    animLp = ws.mAttrs;
                    bestAnimLayer = ws.mLayer;
                    fullscreenAnim = true;
                }
            } else if (!fullscreenAnim) {
                WindowState ws = wtoken.findMainWindow();
                if (ws != null) {
                    if (ws.mLayer > bestAnimLayer) {
                        animLp = ws.mAttrs;
                        bestAnimLayer = ws.mLayer;
                    }
                }
            }
        }

        transit = maybeUpdateTransitToWallpaper(transit, openingAppHasWallpaper,
                closingAppHasWallpaper, lowerWallpaperTarget, upperWallpaperTarget);

        // If all closing windows are obscured, then there is
        // no need to do an animation.  This is the case, for
        // example, when this transition is being done behind
        // the lock screen.
        if (!mService.mPolicy.allowAppAnimationsLw()) {
            if (DEBUG_APP_TRANSITIONS) Slog.v(TAG,
                    "Animations disallowed by keyguard or dream.");
            animLp = null;
        }

        processApplicationsAnimatingInPlace(transit);

        mTmpLayerAndToken.token = null;
        handleClosingApps(transit, animLp, voiceInteraction, mTmpLayerAndToken);
        final AppWindowToken topClosingApp = mTmpLayerAndToken.token;
        final int topClosingLayer = mTmpLayerAndToken.layer;

        final AppWindowToken topOpeningApp = handleOpeningApps(transit,
                animLp, voiceInteraction, topClosingLayer);

        mService.mAppTransition.setLastAppTransition(transit, topOpeningApp, topClosingApp);

        final AppWindowAnimator openingAppAnimator = (topOpeningApp == null) ?  null :
                topOpeningApp.mAppAnimator;
        final AppWindowAnimator closingAppAnimator = (topClosingApp == null) ? null :
                topClosingApp.mAppAnimator;

        mService.mAppTransition.goodToGo(openingAppAnimator, closingAppAnimator,
                mService.mOpeningApps, mService.mClosingApps);
        mService.mAppTransition.postAnimationCallback();
        mService.mAppTransition.clear();

        mService.mOpeningApps.clear();
        mService.mClosingApps.clear();

        // This has changed the visibility of windows, so perform
        // a new layout to get them all up-to-date.
        displayContent.layoutNeeded = true;

        // TODO(multidisplay): IMEs are only supported on the default display.
        if (windows == mService.getDefaultWindowListLocked()
                && !mService.moveInputMethodWindowsIfNeededLocked(true)) {
            mService.mLayersController.assignLayersLocked(windows);
        }
        mService.updateFocusedWindowLocked(UPDATE_FOCUS_PLACING_SURFACES,
                true /*updateInputWindows*/);
        mService.mFocusMayChange = false;
        mService.notifyActivityDrawnForKeyguard();

        Trace.traceEnd(Trace.TRACE_TAG_WINDOW_MANAGER);

        return FINISH_LAYOUT_REDO_LAYOUT | FINISH_LAYOUT_REDO_CONFIG;
    }

    private AppWindowToken handleOpeningApps(int transit, LayoutParams animLp,
            boolean voiceInteraction, int topClosingLayer) {
        AppWindowToken topOpeningApp = null;
        final int appsCount = mService.mOpeningApps.size();
        for (int i = 0; i < appsCount; i++) {
            AppWindowToken wtoken = mService.mOpeningApps.valueAt(i);
            final AppWindowAnimator appAnimator = wtoken.mAppAnimator;
            if (DEBUG_APP_TRANSITIONS) Slog.v(TAG, "Now opening app" + wtoken);

            if (!appAnimator.usingTransferredAnimation) {
                appAnimator.clearThumbnail();
                appAnimator.setNullAnimation();
            }

            if (!wtoken.setVisibility(animLp, true, transit, false, voiceInteraction)){
                // This token isn't going to be animating. Add it to the list of tokens to
                // be notified of app transition complete since the notification will not be
                // sent be the app window animator.
                mService.mNoAnimationNotifyOnTransitionFinished.add(wtoken.token);
            }
            wtoken.updateReportedVisibilityLocked();
            wtoken.waitingToShow = false;
            wtoken.setAllAppWinAnimators();

            if (SHOW_LIGHT_TRANSACTIONS) Slog.i(TAG,
                    ">>> OPEN TRANSACTION handleAppTransitionReadyLocked()");
            mService.openSurfaceTransaction();
            try {
                mService.mAnimator.orAnimating(appAnimator.showAllWindowsLocked());
            } finally {
                mService.closeSurfaceTransaction();
                if (SHOW_LIGHT_TRANSACTIONS) Slog.i(TAG,
                        "<<< CLOSE TRANSACTION handleAppTransitionReadyLocked()");
            }
            mService.mAnimator.mAppWindowAnimating |= appAnimator.isAnimating();

            int topOpeningLayer = 0;
            if (animLp != null) {
                final int layer = wtoken.getHighestAnimLayer();
                if (topOpeningApp == null || layer > topOpeningLayer) {
                    topOpeningApp = wtoken;
                    topOpeningLayer = layer;
                }
            }
            if (mService.mAppTransition.isNextAppTransitionThumbnailUp()) {
                createThumbnailAppAnimator(transit, wtoken, topOpeningLayer, topClosingLayer);
            }
        }
        return topOpeningApp;
    }

    private void handleClosingApps(int transit, LayoutParams animLp, boolean voiceInteraction,
            LayerAndToken layerAndToken) {
        final int appsCount;
        appsCount = mService.mClosingApps.size();
        for (int i = 0; i < appsCount; i++) {
            AppWindowToken wtoken = mService.mClosingApps.valueAt(i);

            // If we still have some windows animating with saved surfaces that's
            // either invisible or already removed, mark them exiting so that they
            // are disposed of after the exit animation. These are not supposed to
            // be shown, or are delayed removal until app is actually drawn (in which
            // case the window will be removed after the animation).
            wtoken.markSavedSurfaceExiting();

            final AppWindowAnimator appAnimator = wtoken.mAppAnimator;
            if (DEBUG_APP_TRANSITIONS) Slog.v(TAG, "Now closing app " + wtoken);
            appAnimator.clearThumbnail();
            appAnimator.setNullAnimation();
            wtoken.setVisibility(animLp, false, transit, false, voiceInteraction);
            wtoken.updateReportedVisibilityLocked();
            // Force the allDrawn flag, because we want to start
            // this guy's animations regardless of whether it's
            // gotten drawn.
            wtoken.allDrawn = true;
            wtoken.deferClearAllDrawn = false;
            // Ensure that apps that are mid-starting are also scheduled to have their
            // starting windows removed after the animation is complete
            if (wtoken.startingWindow != null && !wtoken.startingWindow.mAnimatingExit) {
                mService.scheduleRemoveStartingWindowLocked(wtoken);
            }
            mService.mAnimator.mAppWindowAnimating |= appAnimator.isAnimating();

            if (animLp != null) {
                int layer = wtoken.getHighestAnimLayer();
                if (layerAndToken.token == null || layer > layerAndToken.layer) {
                    layerAndToken.token = wtoken;
                    layerAndToken.layer = layer;
                }
            }
            if (mService.mAppTransition.isNextAppTransitionThumbnailDown()) {
                createThumbnailAppAnimator(transit, wtoken, 0, layerAndToken.layer);
            }
        }
    }

    private boolean transitionGoodToGo(int appsCount) {
        if (DEBUG_APP_TRANSITIONS) Slog.v(TAG,
                "Checking " + appsCount + " opening apps (frozen="
                        + mService.mDisplayFrozen + " timeout="
                        + mService.mAppTransition.isTimeout() + ")...");
        final ScreenRotationAnimation screenRotationAnimation =
            mService.mAnimator.getScreenRotationAnimationLocked(
                    Display.DEFAULT_DISPLAY);

        int reason = APP_TRANSITION_TIMEOUT;
        if (!mService.mAppTransition.isTimeout()) {
            // Imagine the case where we are changing orientation due to an app transition, but a previous
            // orientation change is still in progress. We won't process the orientation change
            // for our transition because we need to wait for the rotation animation to finish.
            // If we start the app transition at this point, we will interrupt it halfway with a new rotation
            // animation after the old one finally finishes. It's better to defer the
            // app transition.
            if (screenRotationAnimation != null && screenRotationAnimation.isAnimating() &&
                    mService.rotationNeedsUpdateLocked()) {
                if (DEBUG_APP_TRANSITIONS) {
                    Slog.v(TAG, "Delaying app transition for screen rotation animation to finish");
                }
                return false;
            }
            for (int i = 0; i < appsCount; i++) {
                AppWindowToken wtoken = mService.mOpeningApps.valueAt(i);
                if (DEBUG_APP_TRANSITIONS) Slog.v(TAG,
                        "Check opening app=" + wtoken + ": allDrawn="
                        + wtoken.allDrawn + " startingDisplayed="
                        + wtoken.startingDisplayed + " startingMoved="
                        + wtoken.startingMoved + " isRelaunching()="
                        + wtoken.isRelaunching());

                if (wtoken.isRelaunching()) {
                    return false;
                }

                final boolean drawnBeforeRestoring = wtoken.allDrawn;
                wtoken.restoreSavedSurfaceForInterestingWindows();

                if (!wtoken.allDrawn && !wtoken.startingDisplayed && !wtoken.startingMoved) {
                    return false;
                }
                if (wtoken.allDrawn) {
                    reason = drawnBeforeRestoring ? APP_TRANSITION_WINDOWS_DRAWN
                            : APP_TRANSITION_SAVED_SURFACE;
                } else {
                    reason = APP_TRANSITION_STARTING_WINDOW;
                }
            }

            // We also need to wait for the specs to be fetched, if needed.
            if (mService.mAppTransition.isFetchingAppTransitionsSpecs()) {
                if (DEBUG_APP_TRANSITIONS) Slog.v(TAG, "isFetchingAppTransitionSpecs=true");
                return false;
            }

            // If the wallpaper is visible, we need to check it's ready too.
            boolean wallpaperReady = !mWallpaperControllerLocked.isWallpaperVisible() ||
                    mWallpaperControllerLocked.wallpaperTransitionReady();
            if (wallpaperReady) {
                mService.mH.obtainMessage(NOTIFY_APP_TRANSITION_STARTING, reason, 0).sendToTarget();
                return true;
            }
            return false;
        }
        mService.mH.obtainMessage(NOTIFY_APP_TRANSITION_STARTING, reason, 0).sendToTarget();
        return true;
    }

    private int maybeUpdateTransitToWallpaper(int transit, boolean openingAppHasWallpaper,
            boolean closingAppHasWallpaper, WindowState lowerWallpaperTarget,
            WindowState upperWallpaperTarget) {
        // if wallpaper is animating in or out set oldWallpaper to null else to wallpaper
        final WindowState wallpaperTarget = mWallpaperControllerLocked.getWallpaperTarget();
        final WindowState oldWallpaper =
                mWallpaperControllerLocked.isWallpaperTargetAnimating()
                        ? null : wallpaperTarget;
        final ArraySet<AppWindowToken> openingApps = mService.mOpeningApps;
        final ArraySet<AppWindowToken> closingApps = mService.mClosingApps;
        if (DEBUG_APP_TRANSITIONS) Slog.v(TAG,
                "New wallpaper target=" + wallpaperTarget
                        + ", oldWallpaper=" + oldWallpaper
                        + ", lower target=" + lowerWallpaperTarget
                        + ", upper target=" + upperWallpaperTarget
                        + ", openingApps=" + openingApps
                        + ", closingApps=" + closingApps);
        mService.mAnimateWallpaperWithTarget = false;
        if (closingAppHasWallpaper && openingAppHasWallpaper) {
            if (DEBUG_APP_TRANSITIONS)
                Slog.v(TAG, "Wallpaper animation!");
            switch (transit) {
                case AppTransition.TRANSIT_ACTIVITY_OPEN:
                case AppTransition.TRANSIT_TASK_OPEN:
                case AppTransition.TRANSIT_TASK_TO_FRONT:
                    transit = AppTransition.TRANSIT_WALLPAPER_INTRA_OPEN;
                    break;
                case AppTransition.TRANSIT_ACTIVITY_CLOSE:
                case AppTransition.TRANSIT_TASK_CLOSE:
                case AppTransition.TRANSIT_TASK_TO_BACK:
                    transit = AppTransition.TRANSIT_WALLPAPER_INTRA_CLOSE;
                    break;
            }
            if (DEBUG_APP_TRANSITIONS) Slog.v(TAG,
                    "New transit: " + AppTransition.appTransitionToString(transit));
        } else if (oldWallpaper != null && !mService.mOpeningApps.isEmpty()
                && !openingApps.contains(oldWallpaper.mAppToken)
                && closingApps.contains(oldWallpaper.mAppToken)) {
            // We are transitioning from an activity with a wallpaper to one without.
            transit = AppTransition.TRANSIT_WALLPAPER_CLOSE;
            if (DEBUG_APP_TRANSITIONS) Slog.v(TAG,
                    "New transit away from wallpaper: "
                    + AppTransition.appTransitionToString(transit));
        } else if (wallpaperTarget != null && wallpaperTarget.isVisibleLw() &&
                openingApps.contains(wallpaperTarget.mAppToken)) {
            // We are transitioning from an activity without
            // a wallpaper to now showing the wallpaper
            transit = AppTransition.TRANSIT_WALLPAPER_OPEN;
            if (DEBUG_APP_TRANSITIONS) Slog.v(TAG,
                    "New transit into wallpaper: "
                    + AppTransition.appTransitionToString(transit));
        } else {
            mService.mAnimateWallpaperWithTarget = true;
        }
        return transit;
    }

    private void processApplicationsAnimatingInPlace(int transit) {
        if (transit == AppTransition.TRANSIT_TASK_IN_PLACE) {
            // Find the focused window
            final WindowState win = mService.getDefaultDisplayContentLocked().findFocusedWindow();
            if (win != null) {
                final AppWindowToken wtoken = win.mAppToken;
                final AppWindowAnimator appAnimator = wtoken.mAppAnimator;
                if (DEBUG_APP_TRANSITIONS)
                    Slog.v(TAG, "Now animating app in place " + wtoken);
                appAnimator.clearThumbnail();
                appAnimator.setNullAnimation();
                mService.updateTokenInPlaceLocked(wtoken, transit);
                wtoken.updateReportedVisibilityLocked();
                wtoken.setAllAppWinAnimators();
                mService.mAnimator.mAppWindowAnimating |= appAnimator.isAnimating();
                mService.mAnimator.orAnimating(appAnimator.showAllWindowsLocked());
            }
        }
    }

    private void createThumbnailAppAnimator(int transit, AppWindowToken appToken,
            int openingLayer, int closingLayer) {
        AppWindowAnimator openingAppAnimator = (appToken == null) ? null : appToken.mAppAnimator;
        if (openingAppAnimator == null || openingAppAnimator.animation == null) {
            return;
        }
        final int taskId = appToken.mTask.mTaskId;
        Bitmap thumbnailHeader = mService.mAppTransition.getAppTransitionThumbnailHeader(taskId);
        if (thumbnailHeader == null || thumbnailHeader.getConfig() == Bitmap.Config.ALPHA_8) {
            if (DEBUG_APP_TRANSITIONS) Slog.d(TAG, "No thumbnail header bitmap for: " + taskId);
            return;
        }
        // This thumbnail animation is very special, we need to have
        // an extra surface with the thumbnail included with the animation.
        Rect dirty = new Rect(0, 0, thumbnailHeader.getWidth(), thumbnailHeader.getHeight());
        try {
            // TODO(multi-display): support other displays
            final DisplayContent displayContent = mService.getDefaultDisplayContentLocked();
            final Display display = displayContent.getDisplay();
            final DisplayInfo displayInfo = displayContent.getDisplayInfo();

            // Create a new surface for the thumbnail
            SurfaceControl surfaceControl = new SurfaceControl(mService.mFxSession,
                    "thumbnail anim", dirty.width(), dirty.height(),
                    PixelFormat.TRANSLUCENT, SurfaceControl.HIDDEN);
            surfaceControl.setLayerStack(display.getLayerStack());
            if (SHOW_TRANSACTIONS) {
                Slog.i(TAG, "  THUMBNAIL " + surfaceControl + ": CREATE");
            }

            // Draw the thumbnail onto the surface
            Surface drawSurface = new Surface();
            drawSurface.copyFrom(surfaceControl);
            Canvas c = drawSurface.lockCanvas(dirty);
            c.drawBitmap(thumbnailHeader, 0, 0, null);
            drawSurface.unlockCanvasAndPost(c);
            drawSurface.release();

            // Get the thumbnail animation
            Animation anim;
            if (mService.mAppTransition.isNextThumbnailTransitionAspectScaled()) {
                // If this is a multi-window scenario, we use the windows frame as
                // destination of the thumbnail header animation. If this is a full screen
                // window scenario, we use the whole display as the target.
                WindowState win = appToken.findMainWindow();
                Rect appRect = win != null ? win.getContentFrameLw() :
                        new Rect(0, 0, displayInfo.appWidth, displayInfo.appHeight);
                Rect insets = win != null ? win.mContentInsets : null;
                // For the new aspect-scaled transition, we want it to always show
                // above the animating opening/closing window, and we want to
                // synchronize its thumbnail surface with the surface for the
                // open/close animation (only on the way down)
                anim = mService.mAppTransition.createThumbnailAspectScaleAnimationLocked(appRect,
                        insets, thumbnailHeader, taskId, mService.mGlobalConfiguration.uiMode,
                        mService.mGlobalConfiguration.orientation);
                openingAppAnimator.thumbnailForceAboveLayer = Math.max(openingLayer, closingLayer);
                openingAppAnimator.deferThumbnailDestruction =
                        !mService.mAppTransition.isNextThumbnailTransitionScaleUp();
            } else {
                anim = mService.mAppTransition.createThumbnailScaleAnimationLocked(
                        displayInfo.appWidth, displayInfo.appHeight, transit, thumbnailHeader);
            }
            anim.restrictDuration(MAX_ANIMATION_DURATION);
            anim.scaleCurrentDuration(mService.getTransitionAnimationScaleLocked());

            openingAppAnimator.thumbnail = surfaceControl;
            openingAppAnimator.thumbnailLayer = openingLayer;
            openingAppAnimator.thumbnailAnimation = anim;
            mService.mAppTransition.getNextAppTransitionStartRect(taskId, mTmpStartRect);
        } catch (Surface.OutOfResourcesException e) {
            Slog.e(TAG, "Can't allocate thumbnail/Canvas surface w="
                    + dirty.width() + " h=" + dirty.height(), e);
            openingAppAnimator.clearThumbnail();
        }
    }

    void requestTraversal() {
        if (!mTraversalScheduled) {
            mTraversalScheduled = true;
            mService.mH.sendEmptyMessage(DO_TRAVERSAL);
        }
    }

    /**
     * Puts the {@param surface} into a pending list to be destroyed after the current transaction
     * has been committed.
     */
    void destroyAfterTransaction(SurfaceControl surface) {
        mPendingDestroyingSurfaces.add(surface);
    }

    /**
     * Destroys any surfaces that have been put into the pending list with
     * {@link #destroyAfterTransaction}.
     */
    void destroyPendingSurfaces() {
        for (int i = mPendingDestroyingSurfaces.size() - 1; i >= 0; i--) {
            mPendingDestroyingSurfaces.get(i).destroy();
        }
        mPendingDestroyingSurfaces.clear();
    }

    public void dump(PrintWriter pw, String prefix) {
        pw.println(prefix + "mTraversalScheduled=" + mTraversalScheduled);
        pw.println(prefix + "mHoldScreenWindow=" + mService.mRoot.mHoldScreenWindow);
        pw.println(prefix + "mObsuringWindow=" + mService.mRoot.mObsuringWindow);
    }
}
