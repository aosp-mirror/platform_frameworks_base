/*
 * Copyright (C) 2017 The Android Open Source Project
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
 * limitations under the License
 */

package com.android.server.wm;

import static android.app.ActivityManagerInternal.APP_TRANSITION_SNAPSHOT;
import static android.app.ActivityManagerInternal.APP_TRANSITION_SPLASH_SCREEN;
import static android.app.ActivityManagerInternal.APP_TRANSITION_WINDOWS_DRAWN;

import static android.view.WindowManager.TRANSIT_CRASHING_ACTIVITY_CLOSE;
import static android.view.WindowManager.TRANSIT_DOCK_TASK_FROM_RECENTS;
import static android.view.WindowManager.TRANSIT_TRANSLUCENT_ACTIVITY_CLOSE;
import static android.view.WindowManager.TRANSIT_TRANSLUCENT_ACTIVITY_OPEN;
import static com.android.server.policy.WindowManagerPolicy.FINISH_LAYOUT_REDO_CONFIG;
import static com.android.server.policy.WindowManagerPolicy.FINISH_LAYOUT_REDO_LAYOUT;
import static android.view.WindowManager.TRANSIT_ACTIVITY_CLOSE;
import static android.view.WindowManager.TRANSIT_ACTIVITY_OPEN;
import static android.view.WindowManager.TRANSIT_FLAG_KEYGUARD_GOING_AWAY_NO_ANIMATION;
import static android.view.WindowManager.TRANSIT_FLAG_KEYGUARD_GOING_AWAY_TO_SHADE;
import static android.view.WindowManager.TRANSIT_FLAG_KEYGUARD_GOING_AWAY_WITH_WALLPAPER;
import static android.view.WindowManager.TRANSIT_KEYGUARD_GOING_AWAY;
import static android.view.WindowManager.TRANSIT_KEYGUARD_GOING_AWAY_ON_WALLPAPER;
import static android.view.WindowManager.TRANSIT_NONE;
import static android.view.WindowManager.TRANSIT_TASK_CLOSE;
import static android.view.WindowManager.TRANSIT_TASK_IN_PLACE;
import static android.view.WindowManager.TRANSIT_TASK_OPEN;
import static android.view.WindowManager.TRANSIT_TASK_TO_BACK;
import static android.view.WindowManager.TRANSIT_TASK_TO_FRONT;
import static android.view.WindowManager.TRANSIT_WALLPAPER_CLOSE;
import static android.view.WindowManager.TRANSIT_WALLPAPER_INTRA_CLOSE;
import static android.view.WindowManager.TRANSIT_WALLPAPER_INTRA_OPEN;
import static android.view.WindowManager.TRANSIT_WALLPAPER_OPEN;
import static com.android.server.wm.AppTransition.isKeyguardGoingAwayTransit;
import static com.android.server.wm.AppTransition.isTaskTransit;
import static com.android.server.wm.WindowManagerDebugConfig.DEBUG;
import static com.android.server.wm.WindowManagerDebugConfig.DEBUG_APP_TRANSITIONS;
import static com.android.server.wm.WindowManagerDebugConfig.SHOW_LIGHT_TRANSACTIONS;
import static com.android.server.wm.WindowManagerDebugConfig.TAG_WITH_CLASS_NAME;
import static com.android.server.wm.WindowManagerDebugConfig.TAG_WM;
import static com.android.server.wm.WindowManagerService.H.NOTIFY_APP_TRANSITION_STARTING;
import static com.android.server.wm.WindowManagerService.H.REPORT_WINDOWS_CHANGE;
import static com.android.server.wm.WindowManagerService.LAYOUT_REPEAT_THRESHOLD;
import static com.android.server.wm.WindowManagerService.UPDATE_FOCUS_PLACING_SURFACES;

import android.app.WindowConfiguration;
import android.os.Debug;
import android.os.Trace;
import android.util.ArraySet;
import android.util.Slog;
import android.util.SparseIntArray;
import android.view.Display;
import android.view.RemoteAnimationAdapter;
import android.view.RemoteAnimationDefinition;
import android.view.WindowManager;
import android.view.WindowManager.LayoutParams;
import android.view.WindowManager.TransitionType;
import android.view.animation.Animation;

import com.android.internal.annotations.VisibleForTesting;
import com.android.server.wm.WindowManagerService.H;

import java.io.PrintWriter;
import java.util.function.Predicate;

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
    static final int SET_WALLPAPER_ACTION_PENDING       = 1 << 4;

    private boolean mTraversalScheduled;
    private int mDeferDepth = 0;

    private static final class LayerAndToken {
        public int layer;
        public AppWindowToken token;
    }
    private final LayerAndToken mTmpLayerAndToken = new LayerAndToken();

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
            transit = WindowManager.TRANSIT_UNSET;
        }
        mService.mSkipAppTransitionAnimation = false;
        mService.mNoAnimationNotifyOnTransitionFinished.clear();

        mService.mH.removeMessages(H.APP_TRANSITION_TIMEOUT);

        final DisplayContent displayContent = mService.getDefaultDisplayContentLocked();

        mService.mRoot.mWallpaperMayChange = false;

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

        // Determine if closing and opening app token sets are wallpaper targets, in which case
        // special animations are needed.
        final boolean hasWallpaperTarget = mWallpaperControllerLocked.getWallpaperTarget() != null;
        final boolean openingAppHasWallpaper = canBeWallpaperTarget(mService.mOpeningApps)
                && hasWallpaperTarget;
        final boolean closingAppHasWallpaper = canBeWallpaperTarget(mService.mClosingApps)
                && hasWallpaperTarget;

        transit = maybeUpdateTransitToTranslucentAnim(transit);
        transit = maybeUpdateTransitToWallpaper(transit, openingAppHasWallpaper,
                closingAppHasWallpaper);

        // Find the layout params of the top-most application window in the tokens, which is
        // what will control the animation theme. If all closing windows are obscured, then there is
        // no need to do an animation. This is the case, for example, when this transition is being
        // done behind a dream window.
        final ArraySet<Integer> activityTypes = collectActivityTypes(mService.mOpeningApps,
                mService.mClosingApps);
        final AppWindowToken animLpToken = mService.mPolicy.allowAppAnimationsLw()
                ? findAnimLayoutParamsToken(transit, activityTypes)
                : null;

        final LayoutParams animLp = getAnimLp(animLpToken);
        overrideWithRemoteAnimationIfSet(animLpToken, transit, activityTypes);

        final boolean voiceInteraction = containsVoiceInteraction(mService.mOpeningApps)
                || containsVoiceInteraction(mService.mOpeningApps);

        final int layoutRedo;
        mService.mSurfaceAnimationRunner.deferStartingAnimations();
        try {
            processApplicationsAnimatingInPlace(transit);

            mTmpLayerAndToken.token = null;
            handleClosingApps(transit, animLp, voiceInteraction, mTmpLayerAndToken);
            final AppWindowToken topClosingApp = mTmpLayerAndToken.token;
            final AppWindowToken topOpeningApp = handleOpeningApps(transit, animLp,
                    voiceInteraction);

            mService.mAppTransition.setLastAppTransition(transit, topOpeningApp, topClosingApp);

            final int flags = mService.mAppTransition.getTransitFlags();
            layoutRedo = mService.mAppTransition.goodToGo(transit, topOpeningApp,
                    topClosingApp, mService.mOpeningApps, mService.mClosingApps);
            handleNonAppWindowsInTransition(transit, flags);
            mService.mAppTransition.postAnimationCallback();
            mService.mAppTransition.clear();
        } finally {
            mService.mSurfaceAnimationRunner.continueStartingAnimations();
        }

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

    private static LayoutParams getAnimLp(AppWindowToken wtoken) {
        final WindowState mainWindow = wtoken != null ? wtoken.findMainWindow() : null;
        return mainWindow != null ? mainWindow.mAttrs : null;
    }

    /**
     * Overrides the pending transition with the remote animation defined for the transition in the
     * set of defined remote animations in the app window token.
     */
    private void overrideWithRemoteAnimationIfSet(AppWindowToken animLpToken, int transit,
            ArraySet<Integer> activityTypes) {
        if (transit == TRANSIT_CRASHING_ACTIVITY_CLOSE) {
            // The crash transition has higher priority than any involved remote animations.
            return;
        }
        if (animLpToken == null) {
            return;
        }
        final RemoteAnimationDefinition definition = animLpToken.getRemoteAnimationDefinition();
        if (definition != null) {
            final RemoteAnimationAdapter adapter = definition.getAdapter(transit, activityTypes);
            if (adapter != null) {
                mService.mAppTransition.overridePendingAppTransitionRemote(adapter);
            }
        }
    }

    /**
     * @return The window token that determines the animation theme.
     */
    private AppWindowToken findAnimLayoutParamsToken(@TransitionType int transit,
            ArraySet<Integer> activityTypes) {
        AppWindowToken result;

        // Remote animations always win, but fullscreen tokens override non-fullscreen tokens.
        result = lookForHighestTokenWithFilter(mService.mClosingApps, mService.mOpeningApps,
                w -> w.getRemoteAnimationDefinition() != null
                        && w.getRemoteAnimationDefinition().hasTransition(transit, activityTypes));
        if (result != null) {
            return result;
        }
        result = lookForHighestTokenWithFilter(mService.mClosingApps, mService.mOpeningApps,
                w -> w.fillsParent() && w.findMainWindow() != null);
        if (result != null) {
            return result;
        }
        return lookForHighestTokenWithFilter(mService.mClosingApps, mService.mOpeningApps,
                w -> w.findMainWindow() != null);
    }

    /**
     * @return The set of {@link WindowConfiguration.ActivityType}s contained in the set of apps in
     *         {@code array1} and {@code array2}.
     */
    private ArraySet<Integer> collectActivityTypes(ArraySet<AppWindowToken> array1,
            ArraySet<AppWindowToken> array2) {
        final ArraySet<Integer> result = new ArraySet<>();
        for (int i = array1.size() - 1; i >= 0; i--) {
            result.add(array1.valueAt(i).getActivityType());
        }
        for (int i = array2.size() - 1; i >= 0; i--) {
            result.add(array2.valueAt(i).getActivityType());
        }
        return result;
    }

    private AppWindowToken lookForHighestTokenWithFilter(ArraySet<AppWindowToken> array1,
            ArraySet<AppWindowToken> array2, Predicate<AppWindowToken> filter) {
        final int array1count = array1.size();
        final int count = array1count + array2.size();
        int bestPrefixOrderIndex = Integer.MIN_VALUE;
        AppWindowToken bestToken = null;
        for (int i = 0; i < count; i++) {
            final AppWindowToken wtoken = i < array1count
                    ? array1.valueAt(i)
                    : array2.valueAt(i - array1count);
            final int prefixOrderIndex = wtoken.getPrefixOrderIndex();
            if (filter.test(wtoken) && prefixOrderIndex > bestPrefixOrderIndex) {
                bestPrefixOrderIndex = prefixOrderIndex;
                bestToken = wtoken;
            }
        }
        return bestToken;
    }

    private boolean containsVoiceInteraction(ArraySet<AppWindowToken> apps) {
        for (int i = apps.size() - 1; i >= 0; i--) {
            if (apps.valueAt(i).mVoiceInteraction) {
                return true;
            }
        }
        return false;
    }

    private AppWindowToken handleOpeningApps(int transit, LayoutParams animLp,
            boolean voiceInteraction) {
        AppWindowToken topOpeningApp = null;
        int topOpeningLayer = Integer.MIN_VALUE;
        final int appsCount = mService.mOpeningApps.size();
        for (int i = 0; i < appsCount; i++) {
            AppWindowToken wtoken = mService.mOpeningApps.valueAt(i);
            if (DEBUG_APP_TRANSITIONS) Slog.v(TAG, "Now opening app" + wtoken);

            if (!wtoken.setVisibility(animLp, true, transit, false, voiceInteraction)) {
                // This token isn't going to be animating. Add it to the list of tokens to
                // be notified of app transition complete since the notification will not be
                // sent be the app window animator.
                mService.mNoAnimationNotifyOnTransitionFinished.add(wtoken.token);
            }
            wtoken.updateReportedVisibilityLocked();
            wtoken.waitingToShow = false;
            if (SHOW_LIGHT_TRANSACTIONS) Slog.i(TAG,
                    ">>> OPEN TRANSACTION handleAppTransitionReadyLocked()");
            mService.openSurfaceTransaction();
            try {
                wtoken.showAllWindowsLocked();
            } finally {
                mService.closeSurfaceTransaction("handleAppTransitionReadyLocked");
                if (SHOW_LIGHT_TRANSACTIONS) Slog.i(TAG,
                        "<<< CLOSE TRANSACTION handleAppTransitionReadyLocked()");
            }

            if (animLp != null) {
                final int layer = wtoken.getHighestAnimLayer();
                if (topOpeningApp == null || layer > topOpeningLayer) {
                    topOpeningApp = wtoken;
                    topOpeningLayer = layer;
                }
            }
            if (mService.mAppTransition.isNextAppTransitionThumbnailUp()) {
                wtoken.attachThumbnailAnimation();
            } else if (mService.mAppTransition.isNextAppTransitionOpenCrossProfileApps()) {
                wtoken.attachCrossProfileAppsThumbnailAnimation();
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

            if (DEBUG_APP_TRANSITIONS) Slog.v(TAG, "Now closing app " + wtoken);
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

            if (animLp != null) {
                int layer = wtoken.getHighestAnimLayer();
                if (layerAndToken.token == null || layer > layerAndToken.layer) {
                    layerAndToken.token = wtoken;
                    layerAndToken.layer = layer;
                }
            }
            if (mService.mAppTransition.isNextAppTransitionThumbnailDown()) {
                wtoken.attachThumbnailAnimation();
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
                        + wtoken.isRelaunching() + " startingWindow="
                        + wtoken.startingWindow);


                final boolean allDrawn = wtoken.allDrawn && !wtoken.isRelaunching();
                if (!allDrawn && !wtoken.startingDisplayed && !wtoken.startingMoved) {
                    return false;
                }
                final int windowingMode = wtoken.getWindowingMode();
                if (allDrawn) {
                    outReasons.put(windowingMode,  APP_TRANSITION_WINDOWS_DRAWN);
                } else {
                    outReasons.put(windowingMode,
                            wtoken.startingData instanceof SplashScreenStartingData
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
        // Given no app transition pass it through instead of a wallpaper transition.
        // Never convert the crashing transition.
        // Never update the transition for the wallpaper if we are just docking from recents
        if (transit == TRANSIT_NONE || transit == TRANSIT_CRASHING_ACTIVITY_CLOSE
                || transit == TRANSIT_DOCK_TASK_FROM_RECENTS) {
            return transit;
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
            } else if (wallpaperTarget != null && wallpaperTarget.isVisibleLw()
                    && openingApps.contains(wallpaperTarget.mAppToken)
                    && transit != TRANSIT_TRANSLUCENT_ACTIVITY_CLOSE) {
                // We are transitioning from an activity without
                // a wallpaper to now showing the wallpaper
                transit = TRANSIT_WALLPAPER_OPEN;
                if (DEBUG_APP_TRANSITIONS) Slog.v(TAG, "New transit into wallpaper: "
                        + AppTransition.appTransitionToString(transit));
            }
        }
        return transit;
    }

    /**
     * There are cases where we open/close a new task/activity, but in reality only a translucent
     * activity on top of existing activities is opening/closing. For that one, we have a different
     * animation because non of the task/activity animations actually work well with translucent
     * apps.
     *
     * @param transit The current transition type.
     * @return The current transition type or
     *         {@link WindowManager#TRANSIT_TRANSLUCENT_ACTIVITY_CLOSE}/
     *         {@link WindowManager#TRANSIT_TRANSLUCENT_ACTIVITY_OPEN} if appropriate for the
     *         situation.
     */
    @VisibleForTesting
    int maybeUpdateTransitToTranslucentAnim(int transit) {
        final boolean taskOrActivity = AppTransition.isTaskTransit(transit)
                || AppTransition.isActivityTransit(transit);
        boolean allOpeningVisible = true;
        boolean allTranslucentOpeningApps = !mService.mOpeningApps.isEmpty();
        for (int i = mService.mOpeningApps.size() - 1; i >= 0; i--) {
            final AppWindowToken token = mService.mOpeningApps.valueAt(i);
            if (!token.isVisible()) {
                allOpeningVisible = false;
                if (token.fillsParent()) {
                    allTranslucentOpeningApps = false;
                }
            }
        }
        boolean allTranslucentClosingApps = !mService.mClosingApps.isEmpty();
        for (int i = mService.mClosingApps.size() - 1; i >= 0; i--) {
            if (mService.mClosingApps.valueAt(i).fillsParent()) {
                allTranslucentClosingApps = false;
                break;
            }
        }

        if (taskOrActivity && allTranslucentClosingApps && allOpeningVisible) {
            return TRANSIT_TRANSLUCENT_ACTIVITY_CLOSE;
        }
        if (taskOrActivity && allTranslucentOpeningApps && mService.mClosingApps.isEmpty()) {
            return TRANSIT_TRANSLUCENT_ACTIVITY_OPEN;
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
                if (DEBUG_APP_TRANSITIONS)
                    Slog.v(TAG, "Now animating app in place " + wtoken);
                wtoken.cancelAnimation();
                wtoken.applyAnimationLocked(null, transit, false, false);
                wtoken.updateReportedVisibilityLocked();
                wtoken.showAllWindowsLocked();
            }
        }
    }

    void requestTraversal() {
        if (!mTraversalScheduled) {
            mTraversalScheduled = true;
            mService.mAnimationHandler.post(mPerformSurfacePlacement);
        }
    }

    public void dump(PrintWriter pw, String prefix) {
        pw.println(prefix + "mTraversalScheduled=" + mTraversalScheduled);
        pw.println(prefix + "mHoldScreenWindow=" + mService.mRoot.mHoldScreenWindow);
        pw.println(prefix + "mObscuringWindow=" + mService.mRoot.mObscuringWindow);
    }
}
