
package com.android.server.wm;

import static android.app.ActivityManager.StackId.INVALID_STACK_ID;
import static android.app.ActivityManagerInternal.APP_TRANSITION_SAVED_SURFACE;
import static android.app.ActivityManagerInternal.APP_TRANSITION_SNAPSHOT;
import static android.app.ActivityManagerInternal.APP_TRANSITION_SPLASH_SCREEN;
import static android.app.ActivityManagerInternal.APP_TRANSITION_WINDOWS_DRAWN;
import static android.view.WindowManagerPolicy.FINISH_LAYOUT_REDO_CONFIG;
import static android.view.WindowManagerPolicy.FINISH_LAYOUT_REDO_LAYOUT;
import static com.android.server.wm.AppTransition.TRANSIT_ACTIVITY_CLOSE;
import static com.android.server.wm.AppTransition.TRANSIT_ACTIVITY_OPEN;
import static com.android.server.wm.AppTransition.TRANSIT_FLAG_KEYGUARD_GOING_AWAY_NO_ANIMATION;
import static com.android.server.wm.AppTransition.TRANSIT_FLAG_KEYGUARD_GOING_AWAY_TO_SHADE;
import static com.android.server.wm.AppTransition.TRANSIT_FLAG_KEYGUARD_GOING_AWAY_WITH_WALLPAPER;
import static com.android.server.wm.AppTransition.TRANSIT_KEYGUARD_GOING_AWAY;
import static com.android.server.wm.AppTransition.TRANSIT_KEYGUARD_GOING_AWAY_ON_WALLPAPER;
import static com.android.server.wm.AppTransition.TRANSIT_NONE;
import static com.android.server.wm.AppTransition.TRANSIT_TASK_CLOSE;
import static com.android.server.wm.AppTransition.TRANSIT_TASK_IN_PLACE;
import static com.android.server.wm.AppTransition.TRANSIT_TASK_OPEN;
import static com.android.server.wm.AppTransition.TRANSIT_TASK_TO_BACK;
import static com.android.server.wm.AppTransition.TRANSIT_TASK_TO_FRONT;
import static com.android.server.wm.AppTransition.TRANSIT_WALLPAPER_CLOSE;
import static com.android.server.wm.AppTransition.TRANSIT_WALLPAPER_INTRA_CLOSE;
import static com.android.server.wm.AppTransition.TRANSIT_WALLPAPER_INTRA_OPEN;
import static com.android.server.wm.AppTransition.TRANSIT_WALLPAPER_OPEN;
import static com.android.server.wm.AppTransition.isKeyguardGoingAwayTransit;
import static com.android.server.wm.WindowManagerDebugConfig.DEBUG;
import static com.android.server.wm.WindowManagerDebugConfig.DEBUG_APP_TRANSITIONS;
import static com.android.server.wm.WindowManagerDebugConfig.SHOW_LIGHT_TRANSACTIONS;
import static com.android.server.wm.WindowManagerDebugConfig.SHOW_TRANSACTIONS;
import static com.android.server.wm.WindowManagerDebugConfig.TAG_WITH_CLASS_NAME;
import static com.android.server.wm.WindowManagerDebugConfig.TAG_WM;
import static com.android.server.wm.WindowManagerService.H.NOTIFY_APP_TRANSITION_STARTING;
import static com.android.server.wm.WindowManagerService.H.REPORT_WINDOWS_CHANGE;
import static com.android.server.wm.WindowManagerService.LAYOUT_REPEAT_THRESHOLD;
import static com.android.server.wm.WindowManagerService.MAX_ANIMATION_DURATION;
import static com.android.server.wm.WindowManagerService.UPDATE_FOCUS_PLACING_SURFACES;

import android.content.res.Configuration;
import android.graphics.GraphicBuffer;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.os.Binder;
import android.os.Debug;
import android.os.Trace;
import android.util.ArraySet;
import android.util.Slog;
import android.util.SparseIntArray;
import android.view.Display;
import android.view.DisplayInfo;
import android.view.Surface;
import android.view.SurfaceControl;
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
    private final SparseIntArray mTempTransitionReasons = new SparseIntArray();

    private final Runnable mPerformSurfacePlacement;

    public WindowSurfacePlacer(WindowManagerService service) {
        mService = service;
        mWallpaperControllerLocked = mService.mRoot.mWallpaperController;
        mPerformSurfacePlacement = () -> {
            synchronized (mService.mWindowMap) {
                performSurfacePlacement();
            }
        };
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

    boolean isLayoutDeferred() {
        return mDeferDepth > 0;
    }

    final void performSurfacePlacement() {
        performSurfacePlacement(false /* force */);
    }

    final void performSurfacePlacement(boolean force) {
        if (mDeferDepth > 0 && !force) {
            return;
        }
        int loopCount = 6;
        do {
            mTraversalScheduled = false;
            performSurfacePlacementLoop();
            mService.mAnimationHandler.removeCallbacks(mPerformSurfacePlacement);
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

            if (mService.mRoot.isLayoutNeeded()) {
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

    /**
     * @return bitmap indicating if another pass through layout must be made.
     */
    int handleAppTransitionReadyLocked() {
        int appsCount = mService.mOpeningApps.size();
        if (!transitionGoodToGo(appsCount, mTempTransitionReasons)) {
            return 0;
        }
        Trace.traceBegin(Trace.TRACE_TAG_WINDOW_MANAGER, "AppTransitionReady");

        if (DEBUG_APP_TRANSITIONS) Slog.v(TAG, "**** GOOD TO GO");
        int transit = mService.mAppTransition.getAppTransition();
        if (mService.mSkipAppTransitionAnimation && !isKeyguardGoingAwayTransit(transit)) {
            transit = AppTransition.TRANSIT_UNSET;
        }
        mService.mSkipAppTransitionAnimation = false;
        mService.mNoAnimationNotifyOnTransitionFinished.clear();

        mService.mH.removeMessages(H.APP_TRANSITION_TIMEOUT);

        final DisplayContent displayContent = mService.getDefaultDisplayContentLocked();
        // TODO: Don't believe this is really needed...
        //mService.mWindowsChanged = true;

        mService.mRoot.mWallpaperMayChange = false;

        // The top-most window will supply the layout params, and we will determine it below.
        LayoutParams animLp = null;
        int bestAnimLayer = -1;
        boolean fullscreenAnim = false;
        boolean voiceInteraction = false;

        int i;
        for (i = 0; i < appsCount; i++) {
            final AppWindowToken wtoken = mService.mOpeningApps.valueAt(i);
            // Clearing the mAnimatingExit flag before entering animation. It's set to true if app
            // window is removed, or window relayout to invisible. This also affects window
            // visibility. We need to clear it *before* maybeUpdateTransitToWallpaper() as the
            // transition selection depends on wallpaper target visibility.
            wtoken.clearAnimatingFlags();

        }

        // Adjust wallpaper before we pull the lower/upper target, since pending changes
        // (like the clearAnimatingFlags() above) might affect wallpaper target result.
        // Or, the opening app window should be a wallpaper target.
        mWallpaperControllerLocked.adjustWallpaperWindowsForAppTransitionIfNeeded(displayContent,
                mService.mOpeningApps);

        final WindowState wallpaperTarget = mWallpaperControllerLocked.getWallpaperTarget();
        boolean openingAppHasWallpaper = false;
        boolean closingAppHasWallpaper = false;

        // Do a first pass through the tokens for two things:
        // (1) Determine if both the closing and opening app token sets are wallpaper targets, in
        // which case special animations are needed (since the wallpaper needs to stay static behind
        // them).
        // (2) Find the layout params of the top-most application window in the tokens, which is
        // what will control the animation theme.
        final int closingAppsCount = mService.mClosingApps.size();
        appsCount = closingAppsCount + mService.mOpeningApps.size();
        for (i = 0; i < appsCount; i++) {
            final AppWindowToken wtoken;
            if (i < closingAppsCount) {
                wtoken = mService.mClosingApps.valueAt(i);
                if (wallpaperTarget != null && wtoken.windowsCanBeWallpaperTarget()) {
                    closingAppHasWallpaper = true;
                }
            } else {
                wtoken = mService.mOpeningApps.valueAt(i - closingAppsCount);
                if (wallpaperTarget != null && wtoken.windowsCanBeWallpaperTarget()) {
                    openingAppHasWallpaper = true;
                }
            }

            voiceInteraction |= wtoken.mVoiceInteraction;

            if (wtoken.fillsParent()) {
                final WindowState ws = wtoken.findMainWindow();
                if (ws != null) {
                    animLp = ws.mAttrs;
                    bestAnimLayer = ws.mLayer;
                    fullscreenAnim = true;
                }
            } else if (!fullscreenAnim) {
                final WindowState ws = wtoken.findMainWindow();
                if (ws != null) {
                    if (ws.mLayer > bestAnimLayer) {
                        animLp = ws.mAttrs;
                        bestAnimLayer = ws.mLayer;
                    }
                }
            }
        }

        transit = maybeUpdateTransitToWallpaper(transit, openingAppHasWallpaper,
                closingAppHasWallpaper);

        // If all closing windows are obscured, then there is no need to do an animation. This is
        // the case, for example, when this transition is being done behind the lock screen.
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

        final int flags = mService.mAppTransition.getTransitFlags();
        int layoutRedo = mService.mAppTransition.goodToGo(transit, openingAppAnimator,
                closingAppAnimator, mService.mOpeningApps, mService.mClosingApps);
        handleNonAppWindowsInTransition(transit, flags);
        mService.mAppTransition.postAnimationCallback();
        mService.mAppTransition.clear();

        mService.mTaskSnapshotController.onTransitionStarting();

        mService.mOpeningApps.clear();
        mService.mClosingApps.clear();
        mService.mUnknownAppVisibilityController.clear();

        // This has changed the visibility of windows, so perform
        // a new layout to get them all up-to-date.
        displayContent.setLayoutNeeded();

        // TODO(multidisplay): IMEs are only supported on the default display.
        final DisplayContent dc = mService.getDefaultDisplayContentLocked();
        dc.computeImeTarget(true /* updateImeTarget */);
        mService.updateFocusedWindowLocked(UPDATE_FOCUS_PLACING_SURFACES,
                true /*updateInputWindows*/);
        mService.mFocusMayChange = false;

        mService.mH.obtainMessage(NOTIFY_APP_TRANSITION_STARTING,
                mTempTransitionReasons.clone()).sendToTarget();

        Trace.traceEnd(Trace.TRACE_TAG_WINDOW_MANAGER);

        return layoutRedo | FINISH_LAYOUT_REDO_LAYOUT | FINISH_LAYOUT_REDO_CONFIG;
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
            // TODO: Do we need to add to mNoAnimationNotifyOnTransitionFinished like above if not
            //       animating?
            wtoken.setVisibility(animLp, false, transit, false, voiceInteraction);
            wtoken.updateReportedVisibilityLocked();
            // Force the allDrawn flag, because we want to start
            // this guy's animations regardless of whether it's
            // gotten drawn.
            wtoken.allDrawn = true;
            wtoken.deferClearAllDrawn = false;
            // Ensure that apps that are mid-starting are also scheduled to have their
            // starting windows removed after the animation is complete
            if (wtoken.startingWindow != null && !wtoken.startingWindow.mAnimatingExit
                    && wtoken.getController() != null) {
                wtoken.getController().removeStartingWindow();
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

    private void handleNonAppWindowsInTransition(int transit, int flags) {
        if (transit == TRANSIT_KEYGUARD_GOING_AWAY) {
            if ((flags & TRANSIT_FLAG_KEYGUARD_GOING_AWAY_WITH_WALLPAPER) != 0
                    && (flags & TRANSIT_FLAG_KEYGUARD_GOING_AWAY_NO_ANIMATION) == 0) {
                Animation anim = mService.mPolicy.createKeyguardWallpaperExit(
                        (flags & TRANSIT_FLAG_KEYGUARD_GOING_AWAY_TO_SHADE) != 0);
                if (anim != null) {
                    mService.getDefaultDisplayContentLocked().mWallpaperController
                            .startWallpaperAnimation(anim);
                }
            }
        }
        if (transit == TRANSIT_KEYGUARD_GOING_AWAY
                || transit == TRANSIT_KEYGUARD_GOING_AWAY_ON_WALLPAPER) {
            mService.getDefaultDisplayContentLocked().startKeyguardExitOnNonAppWindows(
                    transit == TRANSIT_KEYGUARD_GOING_AWAY_ON_WALLPAPER,
                    (flags & TRANSIT_FLAG_KEYGUARD_GOING_AWAY_TO_SHADE) != 0);
        }
    }

    private boolean transitionGoodToGo(int appsCount, SparseIntArray outReasons) {
        if (DEBUG_APP_TRANSITIONS) Slog.v(TAG,
                "Checking " + appsCount + " opening apps (frozen="
                        + mService.mDisplayFrozen + " timeout="
                        + mService.mAppTransition.isTimeout() + ")...");
        final ScreenRotationAnimation screenRotationAnimation =
            mService.mAnimator.getScreenRotationAnimationLocked(
                    Display.DEFAULT_DISPLAY);

        outReasons.clear();
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

                final boolean drawnBeforeRestoring = wtoken.allDrawn;
                wtoken.restoreSavedSurfaceForInterestingWindows();

                final boolean allDrawn = wtoken.allDrawn && !wtoken.isRelaunching();
                if (!allDrawn && !wtoken.startingDisplayed && !wtoken.startingMoved) {
                    return false;
                }
                final TaskStack stack = wtoken.getStack();
                final int stackId = stack != null ? stack.mStackId : INVALID_STACK_ID;
                if (allDrawn) {
                    outReasons.put(stackId, drawnBeforeRestoring ? APP_TRANSITION_WINDOWS_DRAWN
                            : APP_TRANSITION_SAVED_SURFACE);
                } else {
                    outReasons.put(stackId, wtoken.startingData instanceof SplashScreenStartingData
                            ? APP_TRANSITION_SPLASH_SCREEN
                            : APP_TRANSITION_SNAPSHOT);
                }
            }

            // We also need to wait for the specs to be fetched, if needed.
            if (mService.mAppTransition.isFetchingAppTransitionsSpecs()) {
                if (DEBUG_APP_TRANSITIONS) Slog.v(TAG, "isFetchingAppTransitionSpecs=true");
                return false;
            }

            if (!mService.mUnknownAppVisibilityController.allResolved()) {
                if (DEBUG_APP_TRANSITIONS) {
                    Slog.v(TAG, "unknownApps is not empty: "
                            + mService.mUnknownAppVisibilityController.getDebugMessage());
                }
                return false;
            }

            // If the wallpaper is visible, we need to check it's ready too.
            boolean wallpaperReady = !mWallpaperControllerLocked.isWallpaperVisible() ||
                    mWallpaperControllerLocked.wallpaperTransitionReady();
            if (wallpaperReady) {
                return true;
            }
            return false;
        }
        return true;
    }

    private int maybeUpdateTransitToWallpaper(int transit, boolean openingAppHasWallpaper,
            boolean closingAppHasWallpaper) {
        // Given no app transition pass it through instead of a wallpaper transition
        if (transit == TRANSIT_NONE) {
            return TRANSIT_NONE;
        }

        // if wallpaper is animating in or out set oldWallpaper to null else to wallpaper
        final WindowState wallpaperTarget = mWallpaperControllerLocked.getWallpaperTarget();
        final WindowState oldWallpaper = mWallpaperControllerLocked.isWallpaperTargetAnimating()
                ? null : wallpaperTarget;
        final ArraySet<AppWindowToken> openingApps = mService.mOpeningApps;
        final ArraySet<AppWindowToken> closingApps = mService.mClosingApps;
        boolean openingCanBeWallpaperTarget = canBeWallpaperTarget(openingApps);
        if (DEBUG_APP_TRANSITIONS) Slog.v(TAG,
                "New wallpaper target=" + wallpaperTarget
                        + ", oldWallpaper=" + oldWallpaper
                        + ", openingApps=" + openingApps
                        + ", closingApps=" + closingApps);
        mService.mAnimateWallpaperWithTarget = false;
        if (openingCanBeWallpaperTarget && transit == TRANSIT_KEYGUARD_GOING_AWAY) {
            transit = TRANSIT_KEYGUARD_GOING_AWAY_ON_WALLPAPER;
            if (DEBUG_APP_TRANSITIONS) Slog.v(TAG,
                    "New transit: " + AppTransition.appTransitionToString(transit));
        }
        // We never want to change from a Keyguard transit to a non-Keyguard transit, as our logic
        // relies on the fact that we always execute a Keyguard transition after preparing one.
        else if (!isKeyguardGoingAwayTransit(transit)) {
            if (closingAppHasWallpaper && openingAppHasWallpaper) {
                if (DEBUG_APP_TRANSITIONS) Slog.v(TAG, "Wallpaper animation!");
                switch (transit) {
                    case TRANSIT_ACTIVITY_OPEN:
                    case TRANSIT_TASK_OPEN:
                    case TRANSIT_TASK_TO_FRONT:
                        transit = TRANSIT_WALLPAPER_INTRA_OPEN;
                        break;
                    case TRANSIT_ACTIVITY_CLOSE:
                    case TRANSIT_TASK_CLOSE:
                    case TRANSIT_TASK_TO_BACK:
                        transit = TRANSIT_WALLPAPER_INTRA_CLOSE;
                        break;
                }
                if (DEBUG_APP_TRANSITIONS) Slog.v(TAG,
                        "New transit: " + AppTransition.appTransitionToString(transit));
            } else if (oldWallpaper != null && !mService.mOpeningApps.isEmpty()
                    && !openingApps.contains(oldWallpaper.mAppToken)
                    && closingApps.contains(oldWallpaper.mAppToken)) {
                // We are transitioning from an activity with a wallpaper to one without.
                transit = TRANSIT_WALLPAPER_CLOSE;
                if (DEBUG_APP_TRANSITIONS) Slog.v(TAG, "New transit away from wallpaper: "
                        + AppTransition.appTransitionToString(transit));
            } else if (wallpaperTarget != null && wallpaperTarget.isVisibleLw() &&
                    openingApps.contains(wallpaperTarget.mAppToken)) {
                // We are transitioning from an activity without
                // a wallpaper to now showing the wallpaper
                transit = TRANSIT_WALLPAPER_OPEN;
                if (DEBUG_APP_TRANSITIONS) Slog.v(TAG, "New transit into wallpaper: "
                        + AppTransition.appTransitionToString(transit));
            } else {
                mService.mAnimateWallpaperWithTarget = true;
            }
        }
        return transit;
    }

    private boolean canBeWallpaperTarget(ArraySet<AppWindowToken> apps) {
        for (int i = apps.size() - 1; i >= 0; i--) {
            if (apps.valueAt(i).windowsCanBeWallpaperTarget()) {
                return true;
            }
        }
        return false;
    }

    private void processApplicationsAnimatingInPlace(int transit) {
        if (transit == TRANSIT_TASK_IN_PLACE) {
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
        final int taskId = appToken.getTask().mTaskId;
        final GraphicBuffer thumbnailHeader =
                mService.mAppTransition.getAppTransitionThumbnailHeader(taskId);
        if (thumbnailHeader == null) {
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
            WindowState window = appToken.findMainWindow();
            SurfaceControl surfaceControl = new SurfaceControl(mService.mFxSession,
                    "thumbnail anim", dirty.width(), dirty.height(),
                    PixelFormat.TRANSLUCENT, SurfaceControl.HIDDEN,
                    appToken.windowType,
                    window != null ? window.mOwnerUid : Binder.getCallingUid());
            surfaceControl.setLayerStack(display.getLayerStack());
            if (SHOW_TRANSACTIONS) {
                Slog.i(TAG, "  THUMBNAIL " + surfaceControl + ": CREATE");
            }

            // Transfer the thumbnail to the surface
            Surface drawSurface = new Surface();
            drawSurface.copyFrom(surfaceControl);
            drawSurface.attachAndQueueBuffer(thumbnailHeader);
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
                final Configuration displayConfig = displayContent.getConfiguration();
                // For the new aspect-scaled transition, we want it to always show
                // above the animating opening/closing window, and we want to
                // synchronize its thumbnail surface with the surface for the
                // open/close animation (only on the way down)
                anim = mService.mAppTransition.createThumbnailAspectScaleAnimationLocked(appRect,
                        insets, thumbnailHeader, taskId, displayConfig.uiMode,
                        displayConfig.orientation);
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
            mService.mAnimationHandler.post(mPerformSurfacePlacement);
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
        pw.println(prefix + "mObscuringWindow=" + mService.mRoot.mObscuringWindow);
    }
}
