/*
 * Copyright (C) 2018 The Android Open Source Project
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

import static android.app.WindowConfiguration.ACTIVITY_TYPE_DREAM;
import static android.view.WindowManager.TRANSIT_CHANGE;
import static android.view.WindowManager.TRANSIT_CLOSE;
import static android.view.WindowManager.TRANSIT_FLAG_APP_CRASHED;
import static android.view.WindowManager.TRANSIT_FLAG_OPEN_BEHIND;
import static android.view.WindowManager.TRANSIT_KEYGUARD_GOING_AWAY;
import static android.view.WindowManager.TRANSIT_KEYGUARD_OCCLUDE;
import static android.view.WindowManager.TRANSIT_KEYGUARD_UNOCCLUDE;
import static android.view.WindowManager.TRANSIT_NONE;
import static android.view.WindowManager.TRANSIT_OLD_ACTIVITY_CLOSE;
import static android.view.WindowManager.TRANSIT_OLD_ACTIVITY_OPEN;
import static android.view.WindowManager.TRANSIT_OLD_ACTIVITY_RELAUNCH;
import static android.view.WindowManager.TRANSIT_OLD_CRASHING_ACTIVITY_CLOSE;
import static android.view.WindowManager.TRANSIT_OLD_DREAM_ACTIVITY_CLOSE;
import static android.view.WindowManager.TRANSIT_OLD_DREAM_ACTIVITY_OPEN;
import static android.view.WindowManager.TRANSIT_OLD_KEYGUARD_GOING_AWAY;
import static android.view.WindowManager.TRANSIT_OLD_KEYGUARD_GOING_AWAY_ON_WALLPAPER;
import static android.view.WindowManager.TRANSIT_OLD_KEYGUARD_OCCLUDE;
import static android.view.WindowManager.TRANSIT_OLD_KEYGUARD_OCCLUDE_BY_DREAM;
import static android.view.WindowManager.TRANSIT_OLD_KEYGUARD_UNOCCLUDE;
import static android.view.WindowManager.TRANSIT_OLD_NONE;
import static android.view.WindowManager.TRANSIT_OLD_TASK_CHANGE_WINDOWING_MODE;
import static android.view.WindowManager.TRANSIT_OLD_TASK_CLOSE;
import static android.view.WindowManager.TRANSIT_OLD_TASK_FRAGMENT_CHANGE;
import static android.view.WindowManager.TRANSIT_OLD_TASK_FRAGMENT_CLOSE;
import static android.view.WindowManager.TRANSIT_OLD_TASK_FRAGMENT_OPEN;
import static android.view.WindowManager.TRANSIT_OLD_TASK_OPEN;
import static android.view.WindowManager.TRANSIT_OLD_TASK_OPEN_BEHIND;
import static android.view.WindowManager.TRANSIT_OLD_TASK_TO_BACK;
import static android.view.WindowManager.TRANSIT_OLD_TASK_TO_FRONT;
import static android.view.WindowManager.TRANSIT_OLD_TRANSLUCENT_ACTIVITY_CLOSE;
import static android.view.WindowManager.TRANSIT_OLD_TRANSLUCENT_ACTIVITY_OPEN;
import static android.view.WindowManager.TRANSIT_OLD_WALLPAPER_CLOSE;
import static android.view.WindowManager.TRANSIT_OLD_WALLPAPER_INTRA_CLOSE;
import static android.view.WindowManager.TRANSIT_OLD_WALLPAPER_INTRA_OPEN;
import static android.view.WindowManager.TRANSIT_OLD_WALLPAPER_OPEN;
import static android.view.WindowManager.TRANSIT_OPEN;
import static android.view.WindowManager.TRANSIT_RELAUNCH;
import static android.view.WindowManager.TRANSIT_TO_BACK;
import static android.view.WindowManager.TRANSIT_TO_FRONT;

import static com.android.internal.protolog.WmProtoLogGroups.WM_DEBUG_APP_TRANSITIONS;
import static com.android.internal.protolog.WmProtoLogGroups.WM_DEBUG_APP_TRANSITIONS_ANIM;
import static com.android.server.policy.WindowManagerPolicy.FINISH_LAYOUT_REDO_CONFIG;
import static com.android.server.policy.WindowManagerPolicy.FINISH_LAYOUT_REDO_LAYOUT;
import static com.android.server.wm.ActivityTaskManagerInternal.APP_TRANSITION_SNAPSHOT;
import static com.android.server.wm.ActivityTaskManagerInternal.APP_TRANSITION_SPLASH_SCREEN;
import static com.android.server.wm.ActivityTaskManagerInternal.APP_TRANSITION_WINDOWS_DRAWN;
import static com.android.server.wm.AppTransition.isNormalTransit;
import static com.android.server.wm.NonAppWindowAnimationAdapter.shouldAttachNavBarToApp;
import static com.android.server.wm.NonAppWindowAnimationAdapter.shouldStartNonAppWindowAnimationsForKeyguardExit;
import static com.android.server.wm.SurfaceAnimator.ANIMATION_TYPE_APP_TRANSITION;
import static com.android.server.wm.WallpaperAnimationAdapter.shouldStartWallpaperAnimation;
import static com.android.server.wm.WindowContainer.AnimationFlags.PARENTS;
import static com.android.server.wm.WindowManagerDebugConfig.TAG_WITH_CLASS_NAME;
import static com.android.server.wm.WindowManagerDebugConfig.TAG_WM;

import android.annotation.IntDef;
import android.annotation.Nullable;
import android.graphics.Rect;
import android.os.Trace;
import android.util.ArrayMap;
import android.util.ArraySet;
import android.util.Pair;
import android.view.Display;
import android.view.RemoteAnimationAdapter;
import android.view.RemoteAnimationDefinition;
import android.view.WindowManager;
import android.view.WindowManager.LayoutParams;
import android.view.WindowManager.TransitionFlags;
import android.view.WindowManager.TransitionOldType;
import android.view.WindowManager.TransitionType;
import android.window.ITaskFragmentOrganizer;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.protolog.ProtoLog;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.function.Consumer;
import java.util.function.Predicate;

/**
 * Checks for app transition readiness, resolves animation attributes and performs visibility
 * change for apps that animate as part of an app transition.
 */
public class AppTransitionController {
    private static final String TAG = TAG_WITH_CLASS_NAME ? "AppTransitionController" : TAG_WM;
    private final WindowManagerService mService;
    private final DisplayContent mDisplayContent;
    private final WallpaperController mWallpaperControllerLocked;
    private RemoteAnimationDefinition mRemoteAnimationDefinition = null;

    private static final int TYPE_NONE = 0;
    private static final int TYPE_ACTIVITY = 1;
    private static final int TYPE_TASK_FRAGMENT = 2;
    private static final int TYPE_TASK = 3;

    @IntDef(prefix = { "TYPE_" }, value = {
            TYPE_NONE,
            TYPE_ACTIVITY,
            TYPE_TASK_FRAGMENT,
            TYPE_TASK
    })
    @Retention(RetentionPolicy.SOURCE)
    @interface TransitContainerType {}

    private final ArrayMap<WindowContainer, Integer> mTempTransitionReasons = new ArrayMap<>();
    private final ArrayList<WindowContainer> mTempTransitionWindows = new ArrayList<>();

    AppTransitionController(WindowManagerService service, DisplayContent displayContent) {
        mService = service;
        mDisplayContent = displayContent;
        mWallpaperControllerLocked = mDisplayContent.mWallpaperController;
    }

    void registerRemoteAnimations(RemoteAnimationDefinition definition) {
        mRemoteAnimationDefinition = definition;
    }

    /**
     * Returns the currently visible window that is associated with the wallpaper in case we are
     * transitioning from an activity with a wallpaper to one without.
     */
    @Nullable
    private WindowState getOldWallpaper() {
        final WindowState wallpaperTarget = mWallpaperControllerLocked.getWallpaperTarget();
        final @TransitionType int firstTransit =
                mDisplayContent.mAppTransition.getFirstAppTransition();

        final ArraySet<WindowContainer> openingWcs = getAnimationTargets(
                mDisplayContent.mOpeningApps, mDisplayContent.mClosingApps, true /* visible */);
        final boolean showWallpaper = wallpaperTarget != null
                && (wallpaperTarget.hasWallpaper()
                // Update task open transition to wallpaper transition when wallpaper is visible.
                // (i.e.launching app info activity from recent tasks)
                || ((firstTransit == TRANSIT_OPEN || firstTransit == TRANSIT_TO_FRONT)
                && (!openingWcs.isEmpty() && openingWcs.valueAt(0).asTask() != null)
                && mWallpaperControllerLocked.isWallpaperVisible()));
        // If wallpaper is animating or wallpaperTarget doesn't have SHOW_WALLPAPER flag set,
        // don't consider upgrading to wallpaper transition.
        return (mWallpaperControllerLocked.isWallpaperTargetAnimating() || !showWallpaper)
                ? null : wallpaperTarget;
    }

    /**
     * Handle application transition for given display.
     */
    void handleAppTransitionReady() {
        mTempTransitionReasons.clear();
        if (!transitionGoodToGo(mDisplayContent.mOpeningApps, mTempTransitionReasons)
                || !transitionGoodToGo(mDisplayContent.mChangingContainers, mTempTransitionReasons)
                || !transitionGoodToGoForTaskFragments()) {
            return;
        }

        Trace.traceBegin(Trace.TRACE_TAG_WINDOW_MANAGER, "AppTransitionReady");

        ProtoLog.v(WM_DEBUG_APP_TRANSITIONS, "**** GOOD TO GO");
        // TODO(b/205335975): Remove window which stuck in animatingExit status. Find actual cause.
        mDisplayContent.forAllWindows(WindowState::cleanupAnimatingExitWindow,
                true /* traverseTopToBottom */);
        // TODO(new-app-transition): Remove code using appTransition.getAppTransition()
        final AppTransition appTransition = mDisplayContent.mAppTransition;

        mDisplayContent.mNoAnimationNotifyOnTransitionFinished.clear();

        appTransition.removeAppTransitionTimeoutCallbacks();

        mDisplayContent.mWallpaperMayChange = false;

        int appCount = mDisplayContent.mOpeningApps.size();
        for (int i = 0; i < appCount; ++i) {
            // Clearing the mAnimatingExit flag before entering animation. It's set to true if app
            // window is removed, or window relayout to invisible. This also affects window
            // visibility. We need to clear it *before* maybeUpdateTransitToWallpaper() as the
            // transition selection depends on wallpaper target visibility.
            mDisplayContent.mOpeningApps.valueAtUnchecked(i).clearAnimatingFlags();
        }
        appCount = mDisplayContent.mChangingContainers.size();
        for (int i = 0; i < appCount; ++i) {
            // Clearing for same reason as above.
            final ActivityRecord activity = getAppFromContainer(
                    mDisplayContent.mChangingContainers.valueAtUnchecked(i));
            if (activity != null) {
                activity.clearAnimatingFlags();
            }
        }

        // Adjust wallpaper before we pull the lower/upper target, since pending changes
        // (like the clearAnimatingFlags() above) might affect wallpaper target result.
        // Or, the opening app window should be a wallpaper target.
        mWallpaperControllerLocked.adjustWallpaperWindowsForAppTransitionIfNeeded(
                mDisplayContent.mOpeningApps);

        ArraySet<ActivityRecord> tmpOpenApps = mDisplayContent.mOpeningApps;
        ArraySet<ActivityRecord> tmpCloseApps = mDisplayContent.mClosingApps;
        if (mDisplayContent.mAtmService.mBackNavigationController.isMonitoringFinishTransition()) {
            tmpOpenApps = new ArraySet<>(mDisplayContent.mOpeningApps);
            tmpCloseApps = new ArraySet<>(mDisplayContent.mClosingApps);
            if (mDisplayContent.mAtmService.mBackNavigationController
                    .removeIfContainsBackAnimationTargets(tmpOpenApps, tmpCloseApps)) {
                mDisplayContent.mAtmService.mBackNavigationController
                        .clearBackAnimations(false /* cancel */);
            }
        }

        @TransitionOldType final int transit = getTransitCompatType(
                mDisplayContent.mAppTransition, tmpOpenApps,
                tmpCloseApps, mDisplayContent.mChangingContainers,
                mWallpaperControllerLocked.getWallpaperTarget(), getOldWallpaper(),
                mDisplayContent.mSkipAppTransitionAnimation);
        mDisplayContent.mSkipAppTransitionAnimation = false;

        ProtoLog.v(WM_DEBUG_APP_TRANSITIONS,
                "handleAppTransitionReady: displayId=%d appTransition={%s}"
                + " openingApps=[%s] closingApps=[%s] transit=%s",
                mDisplayContent.mDisplayId, appTransition.toString(), tmpOpenApps,
                tmpCloseApps, AppTransition.appTransitionOldToString(transit));

        // Find the layout params of the top-most application window in the tokens, which is
        // what will control the animation theme. If all closing windows are obscured, then there is
        // no need to do an animation. This is the case, for example, when this transition is being
        // done behind a dream window.
        final ArraySet<Integer> activityTypes = collectActivityTypes(tmpOpenApps,
                tmpCloseApps, mDisplayContent.mChangingContainers);
        final ActivityRecord animLpActivity = findAnimLayoutParamsToken(transit, activityTypes,
                tmpOpenApps, tmpCloseApps, mDisplayContent.mChangingContainers);
        final ActivityRecord topOpeningApp =
                getTopApp(tmpOpenApps, false /* ignoreHidden */);
        final ActivityRecord topClosingApp =
                getTopApp(tmpCloseApps, false /* ignoreHidden */);
        final ActivityRecord topChangingApp =
                getTopApp(mDisplayContent.mChangingContainers, false /* ignoreHidden */);
        final WindowManager.LayoutParams animLp = getAnimLp(animLpActivity);

        // Check if there is any override
        if (!overrideWithTaskFragmentRemoteAnimation(transit, activityTypes)) {
            // Unfreeze the windows that were previously frozen for TaskFragment animation.
            unfreezeEmbeddedChangingWindows();
            overrideWithRemoteAnimationIfSet(animLpActivity, transit, activityTypes);
        }

        final boolean voiceInteraction = containsVoiceInteraction(mDisplayContent.mClosingApps)
                || containsVoiceInteraction(mDisplayContent.mOpeningApps);

        final int layoutRedo;
        mService.mSurfaceAnimationRunner.deferStartingAnimations();
        try {
            applyAnimations(tmpOpenApps, tmpCloseApps, transit, animLp, voiceInteraction);
            handleClosingApps();
            handleOpeningApps();
            handleChangingApps(transit);
            handleClosingChangingContainers();

            appTransition.setLastAppTransition(transit, topOpeningApp,
                    topClosingApp, topChangingApp);

            final int flags = appTransition.getTransitFlags();
            layoutRedo = appTransition.goodToGo(transit, topOpeningApp);
            appTransition.postAnimationCallback();
        } finally {
            appTransition.clear();
            mService.mSurfaceAnimationRunner.continueStartingAnimations();
        }

        mService.mSnapshotController.onTransitionStarting(mDisplayContent);

        mDisplayContent.mOpeningApps.clear();
        mDisplayContent.mClosingApps.clear();
        mDisplayContent.mChangingContainers.clear();
        mDisplayContent.mUnknownAppVisibilityController.clear();
        mDisplayContent.mClosingChangingContainers.clear();

        // This has changed the visibility of windows, so perform
        // a new layout to get them all up-to-date.
        mDisplayContent.setLayoutNeeded();

        mDisplayContent.computeImeTarget(true /* updateImeTarget */);

        mService.mAtmService.mTaskSupervisor.getActivityMetricsLogger().notifyTransitionStarting(
                mTempTransitionReasons);

        Trace.traceEnd(Trace.TRACE_TAG_WINDOW_MANAGER);

        mDisplayContent.pendingLayoutChanges |=
                layoutRedo | FINISH_LAYOUT_REDO_LAYOUT | FINISH_LAYOUT_REDO_CONFIG;
    }

    /**
     * Get old transit type based on the current transit requests.
     *
     * @param appTransition {@link AppTransition} for managing app transition state.
     * @param openingApps {@link ActivityRecord}s which are becoming visible.
     * @param closingApps {@link ActivityRecord}s which are becoming invisible.
     * @param changingContainers {@link WindowContainer}s which are changed in configuration.
     * @param wallpaperTarget If non-null, this is the currently visible window that is associated
     *                        with the wallpaper.
     * @param oldWallpaper The currently visible window that is associated with the wallpaper in
     *                     case we are transitioning from an activity with a wallpaper to one
     *                     without. Otherwise null.
     */
    @TransitionOldType static int getTransitCompatType(AppTransition appTransition,
            ArraySet<ActivityRecord> openingApps, ArraySet<ActivityRecord> closingApps,
            ArraySet<WindowContainer> changingContainers, @Nullable WindowState wallpaperTarget,
            @Nullable WindowState oldWallpaper, boolean skipAppTransitionAnimation) {

        final ActivityRecord topOpeningApp = getTopApp(openingApps, false /* ignoreHidden */);
        final ActivityRecord topClosingApp = getTopApp(closingApps, true /* ignoreHidden */);

        // Determine if closing and opening app token sets are wallpaper targets, in which case
        // special animations are needed.
        final boolean openingAppHasWallpaper = canBeWallpaperTarget(openingApps)
                && wallpaperTarget != null;
        final boolean closingAppHasWallpaper = canBeWallpaperTarget(closingApps)
                && wallpaperTarget != null;

        // Keyguard transit has high priority.
        switch (appTransition.getKeyguardTransition()) {
            case TRANSIT_KEYGUARD_GOING_AWAY:
                return openingAppHasWallpaper ? TRANSIT_OLD_KEYGUARD_GOING_AWAY_ON_WALLPAPER
                        : TRANSIT_OLD_KEYGUARD_GOING_AWAY;
            case TRANSIT_KEYGUARD_OCCLUDE:
                // When there is a closing app, the keyguard has already been occluded by an
                // activity, and another activity has started on top of that activity, so normal
                // app transition animation should be used.
                if (!closingApps.isEmpty()) {
                    return TRANSIT_OLD_ACTIVITY_OPEN;
                }
                if (!openingApps.isEmpty() && openingApps.valueAt(0).getActivityType()
                        == ACTIVITY_TYPE_DREAM) {
                    return TRANSIT_OLD_KEYGUARD_OCCLUDE_BY_DREAM;
                }
                return TRANSIT_OLD_KEYGUARD_OCCLUDE;
            case TRANSIT_KEYGUARD_UNOCCLUDE:
                return TRANSIT_OLD_KEYGUARD_UNOCCLUDE;
        }

        // Determine whether the top opening and closing activity is a dream activity. If so, this
        // has higher priority than others except keyguard transit.
        if (topOpeningApp != null && topOpeningApp.getActivityType() == ACTIVITY_TYPE_DREAM) {
            return TRANSIT_OLD_DREAM_ACTIVITY_OPEN;
        } else if (topClosingApp != null
                && topClosingApp.getActivityType() == ACTIVITY_TYPE_DREAM) {
            return TRANSIT_OLD_DREAM_ACTIVITY_CLOSE;
        }

        // This is not keyguard transition and one of the app has request to skip app transition.
        if (skipAppTransitionAnimation) {
            return WindowManager.TRANSIT_OLD_UNSET;
        }
        @TransitionFlags final int flags = appTransition.getTransitFlags();
        @TransitionType final int firstTransit = appTransition.getFirstAppTransition();

        // Special transitions
        // TODO(new-app-transitions): Revisit if those can be rewritten by using flags.
        if (appTransition.containsTransitRequest(TRANSIT_CHANGE) && !changingContainers.isEmpty()) {
            @TransitContainerType int changingType =
                    getTransitContainerType(changingContainers.valueAt(0));
            switch (changingType) {
                case TYPE_TASK:
                    return TRANSIT_OLD_TASK_CHANGE_WINDOWING_MODE;
                case TYPE_TASK_FRAGMENT:
                    return TRANSIT_OLD_TASK_FRAGMENT_CHANGE;
                default:
                    throw new IllegalStateException(
                            "TRANSIT_CHANGE with unrecognized changing type=" + changingType);
            }
        }
        if ((flags & TRANSIT_FLAG_APP_CRASHED) != 0) {
            return TRANSIT_OLD_CRASHING_ACTIVITY_CLOSE;
        }
        if (firstTransit == TRANSIT_NONE) {
            return TRANSIT_OLD_NONE;
        }

        /*
         * There are cases where we open/close a new task/activity, but in reality only a
         * translucent activity on top of existing activities is opening/closing. For that one, we
         * have a different animation because non of the task/activity animations actually work well
         * with translucent apps.
         */
        if (isNormalTransit(firstTransit)) {
            boolean allOpeningVisible = true;
            boolean allTranslucentOpeningApps = !openingApps.isEmpty();
            for (int i = openingApps.size() - 1; i >= 0; i--) {
                final ActivityRecord activity = openingApps.valueAt(i);
                if (!activity.isVisible()) {
                    allOpeningVisible = false;
                    if (activity.fillsParent()) {
                        allTranslucentOpeningApps = false;
                    }
                }
            }
            boolean allTranslucentClosingApps = !closingApps.isEmpty();
            for (int i = closingApps.size() - 1; i >= 0; i--) {
                if (closingApps.valueAt(i).fillsParent()) {
                    allTranslucentClosingApps = false;
                    break;
                }
            }

            if (allTranslucentClosingApps && allOpeningVisible) {
                return TRANSIT_OLD_TRANSLUCENT_ACTIVITY_CLOSE;
            }
            if (allTranslucentOpeningApps && closingApps.isEmpty()) {
                return TRANSIT_OLD_TRANSLUCENT_ACTIVITY_OPEN;
            }
        }

        if (closingAppHasWallpaper && openingAppHasWallpaper) {
            ProtoLog.v(WM_DEBUG_APP_TRANSITIONS, "Wallpaper animation!");
            switch (firstTransit) {
                case TRANSIT_OPEN:
                case TRANSIT_TO_FRONT:
                    return TRANSIT_OLD_WALLPAPER_INTRA_OPEN;
                case TRANSIT_CLOSE:
                case TRANSIT_TO_BACK:
                    return TRANSIT_OLD_WALLPAPER_INTRA_CLOSE;
            }
        } else if (oldWallpaper != null && !openingApps.isEmpty()
                && !openingApps.contains(oldWallpaper.mActivityRecord)
                && closingApps.contains(oldWallpaper.mActivityRecord)
                && topClosingApp == oldWallpaper.mActivityRecord) {
            // We are transitioning from an activity with a wallpaper to one without.
            return TRANSIT_OLD_WALLPAPER_CLOSE;
        } else if (wallpaperTarget != null && wallpaperTarget.isVisible()
                && openingApps.contains(wallpaperTarget.mActivityRecord)
                && topOpeningApp == wallpaperTarget.mActivityRecord
                /* && transit != TRANSIT_TRANSLUCENT_ACTIVITY_CLOSE */) {
            // We are transitioning from an activity without
            // a wallpaper to now showing the wallpaper
            return TRANSIT_OLD_WALLPAPER_OPEN;
        }

        final ArraySet<WindowContainer> openingWcs = getAnimationTargets(
                openingApps, closingApps, true /* visible */);
        final ArraySet<WindowContainer> closingWcs = getAnimationTargets(
                openingApps, closingApps, false /* visible */);
        final WindowContainer<?> openingContainer = !openingWcs.isEmpty()
                ? openingWcs.valueAt(0) : null;
        final WindowContainer<?> closingContainer = !closingWcs.isEmpty()
                ? closingWcs.valueAt(0) : null;
        @TransitContainerType int openingType = getTransitContainerType(openingContainer);
        @TransitContainerType int closingType = getTransitContainerType(closingContainer);
        if (appTransition.containsTransitRequest(TRANSIT_TO_FRONT) && openingType == TYPE_TASK) {
            if (topOpeningApp != null && topOpeningApp.isActivityTypeHome()) {
                // If we are opening the home task, we want to play an animation as if
                // the task on top is being brought to back.
                return TRANSIT_OLD_TASK_TO_BACK;
            }
            return TRANSIT_OLD_TASK_TO_FRONT;
        }
        if (appTransition.containsTransitRequest(TRANSIT_TO_BACK) && closingType == TYPE_TASK) {
            return TRANSIT_OLD_TASK_TO_BACK;
        }
        if (appTransition.containsTransitRequest(TRANSIT_OPEN)) {
            if (openingType == TYPE_TASK) {
                return (appTransition.getTransitFlags() & TRANSIT_FLAG_OPEN_BEHIND) != 0
                        ? TRANSIT_OLD_TASK_OPEN_BEHIND : TRANSIT_OLD_TASK_OPEN;
            }
            if (openingType == TYPE_ACTIVITY) {
                return TRANSIT_OLD_ACTIVITY_OPEN;
            }
            if (openingType == TYPE_TASK_FRAGMENT) {
                return TRANSIT_OLD_TASK_FRAGMENT_OPEN;
            }
        }
        if (appTransition.containsTransitRequest(TRANSIT_CLOSE)) {
            if (closingType == TYPE_TASK) {
                return TRANSIT_OLD_TASK_CLOSE;
            }
            if (closingType == TYPE_TASK_FRAGMENT) {
                return TRANSIT_OLD_TASK_FRAGMENT_CLOSE;
            }
            if (closingType == TYPE_ACTIVITY) {
                for (int i = closingApps.size() - 1; i >= 0; i--) {
                    if (closingApps.valueAt(i).visibleIgnoringKeyguard) {
                        return TRANSIT_OLD_ACTIVITY_CLOSE;
                    }
                }
                // Skip close activity transition since no closing app can be visible
                return WindowManager.TRANSIT_OLD_UNSET;
            }
        }
        if (appTransition.containsTransitRequest(TRANSIT_RELAUNCH)
                && !openingWcs.isEmpty() && !openingApps.isEmpty()) {
            return TRANSIT_OLD_ACTIVITY_RELAUNCH;
        }
        return TRANSIT_OLD_NONE;
    }

    @TransitContainerType
    private static int getTransitContainerType(@Nullable WindowContainer<?> container) {
        if (container == null) {
            return TYPE_NONE;
        }
        if (container.asTask() != null) {
            return TYPE_TASK;
        }
        if (container.asTaskFragment() != null) {
            return TYPE_TASK_FRAGMENT;
        }
        if (container.asActivityRecord() != null) {
            return TYPE_ACTIVITY;
        }
        return TYPE_NONE;
    }

    @Nullable
    private static WindowManager.LayoutParams getAnimLp(ActivityRecord activity) {
        final WindowState mainWindow = activity != null ? activity.findMainWindow() : null;
        return mainWindow != null ? mainWindow.mAttrs : null;
    }

    RemoteAnimationAdapter getRemoteAnimationOverride(@Nullable WindowContainer container,
            @TransitionOldType int transit, ArraySet<Integer> activityTypes) {
        if (container != null) {
            final RemoteAnimationDefinition definition = container.getRemoteAnimationDefinition();
            if (definition != null) {
                final RemoteAnimationAdapter adapter = definition.getAdapter(transit,
                        activityTypes);
                if (adapter != null) {
                    return adapter;
                }
            }
        }
        return mRemoteAnimationDefinition != null
                ? mRemoteAnimationDefinition.getAdapter(transit, activityTypes)
                : null;
    }

    private void unfreezeEmbeddedChangingWindows() {
        final ArraySet<WindowContainer> changingContainers = mDisplayContent.mChangingContainers;
        for (int i = changingContainers.size() - 1; i >= 0; i--) {
            final WindowContainer wc = changingContainers.valueAt(i);
            if (wc.isEmbedded()) {
                wc.mSurfaceFreezer.unfreeze(wc.getSyncTransaction());
            }
        }
    }

    private boolean transitionMayContainNonAppWindows(@TransitionOldType int transit) {
        // We don't want to have the client to animate any non-app windows.
        // Having {@code transit} of those types doesn't mean it will contain non-app windows, but
        // non-app windows will only be included with those transition types. And we don't currently
        // have any use case of those for TaskFragment transition.
        return shouldStartNonAppWindowAnimationsForKeyguardExit(transit)
                || shouldAttachNavBarToApp(mService, mDisplayContent, transit)
                || shouldStartWallpaperAnimation(mDisplayContent);
    }

    /**
     * Whether the transition contains any embedded {@link TaskFragment} that does not fill the
     * parent {@link Task} before or after the transition.
     */
    private boolean transitionContainsTaskFragmentWithBoundsOverride() {
        for (int i = mDisplayContent.mChangingContainers.size() - 1; i >= 0; i--) {
            final WindowContainer wc = mDisplayContent.mChangingContainers.valueAt(i);
            if (wc.isEmbedded()) {
                // Contains embedded TaskFragment with bounds changed.
                return true;
            }
        }
        mTempTransitionWindows.clear();
        mTempTransitionWindows.addAll(mDisplayContent.mClosingApps);
        mTempTransitionWindows.addAll(mDisplayContent.mOpeningApps);
        boolean containsTaskFragmentWithBoundsOverride = false;
        for (int i = mTempTransitionWindows.size() - 1; i >= 0; i--) {
            final ActivityRecord r = mTempTransitionWindows.get(i).asActivityRecord();
            final TaskFragment tf = r.getTaskFragment();
            if (tf != null && tf.isEmbeddedWithBoundsOverride()) {
                containsTaskFragmentWithBoundsOverride = true;
                break;
            }
        }
        mTempTransitionWindows.clear();
        return containsTaskFragmentWithBoundsOverride;
    }

    /**
     * Finds the common parent {@link Task} that is parent of all embedded app windows in the
     * current transition.
     * @return {@code null} if app windows in the transition are not children of the same Task, or
     *         if none of the app windows is embedded.
     */
    @Nullable
    private Task findParentTaskForAllEmbeddedWindows() {
        mTempTransitionWindows.clear();
        mTempTransitionWindows.addAll(mDisplayContent.mClosingApps);
        mTempTransitionWindows.addAll(mDisplayContent.mOpeningApps);
        mTempTransitionWindows.addAll(mDisplayContent.mChangingContainers);

        // It should only animated by the organizer if all windows are below the same leaf Task.
        Task leafTask = null;
        for (int i = mTempTransitionWindows.size() - 1; i >= 0; i--) {
            final ActivityRecord r = getAppFromContainer(mTempTransitionWindows.get(i));
            if (r == null) {
                leafTask = null;
                break;
            }
            // There are also cases where the Task contains non-embedded activity, such as launching
            // split TaskFragments from a non-embedded activity.
            // The hierarchy may looks like this:
            // - Task
            //    - Activity
            //    - TaskFragment
            //       - Activity
            //    - TaskFragment
            //       - Activity
            // We also want to have the organizer handle the transition for such case.
            final Task task = r.getTask();
            // We don't support embedding in PiP, leave the animation to the PipTaskOrganizer.
            if (task == null || task.inPinnedWindowingMode()) {
                leafTask = null;
                break;
            }
            // We don't want the organizer to handle transition of other non-embedded Task.
            if (leafTask != null && leafTask != task) {
                leafTask = null;
                break;
            }
            final ActivityRecord rootActivity = task.getRootActivity();
            // We don't want the organizer to handle transition when the whole app is closing.
            if (rootActivity == null) {
                leafTask = null;
                break;
            }
            // We don't want the organizer to handle transition of non-embedded activity of other
            // app.
            if (r.getUid() != task.effectiveUid && !r.isEmbedded()) {
                leafTask = null;
                break;
            }
            leafTask = task;
        }
        mTempTransitionWindows.clear();
        return leafTask;
    }

    /**
     * Finds the common {@link android.window.TaskFragmentOrganizer} that organizes all embedded
     * {@link TaskFragment} belong to the given {@link Task}.
     * @return {@code null} if there is no such organizer, or if there are more than one.
     */
    @Nullable
    private ITaskFragmentOrganizer findTaskFragmentOrganizer(@Nullable Task task) {
        if (task == null) {
            return null;
        }
        // We don't support remote animation for Task with multiple TaskFragmentOrganizers.
        final ITaskFragmentOrganizer[] organizer = new ITaskFragmentOrganizer[1];
        final boolean hasMultipleOrganizers = task.forAllLeafTaskFragments(taskFragment -> {
            final ITaskFragmentOrganizer tfOrganizer = taskFragment.getTaskFragmentOrganizer();
            if (tfOrganizer == null) {
                return false;
            }
            if (organizer[0] != null && !organizer[0].asBinder().equals(tfOrganizer.asBinder())) {
                return true;
            }
            organizer[0] = tfOrganizer;
            return false;
        });
        if (hasMultipleOrganizers) {
            ProtoLog.e(WM_DEBUG_APP_TRANSITIONS, "We don't support remote animation for"
                    + " Task with multiple TaskFragmentOrganizers.");
            return null;
        }
        return organizer[0];
    }

    /**
     * Overrides the pending transition with the remote animation defined by the
     * {@link ITaskFragmentOrganizer} if all windows in the transition are children of
     * {@link TaskFragment} that are organized by the same organizer.
     *
     * @return {@code true} if the transition is overridden.
     */
    private boolean overrideWithTaskFragmentRemoteAnimation(@TransitionOldType int transit,
            ArraySet<Integer> activityTypes) {
        if (transitionMayContainNonAppWindows(transit)) {
            return false;
        }
        if (!transitionContainsTaskFragmentWithBoundsOverride()) {
            // No need to play TaskFragment remote animation if all embedded TaskFragment in the
            // transition fill the Task.
            return false;
        }

        final Task task = findParentTaskForAllEmbeddedWindows();
        final ITaskFragmentOrganizer organizer = findTaskFragmentOrganizer(task);
        final RemoteAnimationDefinition definition = organizer != null
                ? mDisplayContent.mAtmService.mTaskFragmentOrganizerController
                    .getRemoteAnimationDefinition(organizer)
                : null;
        final RemoteAnimationAdapter adapter = definition != null
                ? definition.getAdapter(transit, activityTypes)
                : null;
        if (adapter == null) {
            return false;
        }
        mDisplayContent.mAppTransition.overridePendingAppTransitionRemote(
                adapter, false /* sync */, true /*isActivityEmbedding*/);
        ProtoLog.v(WM_DEBUG_APP_TRANSITIONS,
                "Override with TaskFragment remote animation for transit=%s",
                AppTransition.appTransitionOldToString(transit));

        final int organizerUid = mDisplayContent.mAtmService.mTaskFragmentOrganizerController
                .getTaskFragmentOrganizerUid(organizer);
        final boolean shouldDisableInputForRemoteAnimation = !task.isFullyTrustedEmbedding(
                organizerUid);
        final RemoteAnimationController remoteAnimationController =
                mDisplayContent.mAppTransition.getRemoteAnimationController();
        if (shouldDisableInputForRemoteAnimation && remoteAnimationController != null) {
            // We are going to use client-driven animation, Disable all input on activity windows
            // during the animation (unless it is fully trusted) to ensure it is safe to allow
            // client to animate the surfaces.
            // This is needed for all activity windows in the animation Task.
            remoteAnimationController.setOnRemoteAnimationReady(() -> {
                final Consumer<ActivityRecord> updateActivities =
                        activity -> activity.setDropInputForAnimation(true);
                task.forAllActivities(updateActivities);
            });
            ProtoLog.d(WM_DEBUG_APP_TRANSITIONS, "Task=%d contains embedded TaskFragment."
                    + " Disabled all input during TaskFragment remote animation.", task.mTaskId);
        }
        return true;
    }

    /**
     * Overrides the pending transition with the remote animation defined for the transition in the
     * set of defined remote animations in the app window token.
     */
    private void overrideWithRemoteAnimationIfSet(@Nullable ActivityRecord animLpActivity,
            @TransitionOldType int transit, ArraySet<Integer> activityTypes) {
        RemoteAnimationAdapter adapter = null;
        if (transit == TRANSIT_OLD_CRASHING_ACTIVITY_CLOSE) {
            // The crash transition has higher priority than any involved remote animations.
        } else if (AppTransition.isKeyguardGoingAwayTransitOld(transit)) {
            adapter = mRemoteAnimationDefinition != null
                    ? mRemoteAnimationDefinition.getAdapter(transit, activityTypes)
                    : null;
        } else if (mDisplayContent.mAppTransition.getRemoteAnimationController() == null) {
            adapter = getRemoteAnimationOverride(animLpActivity, transit, activityTypes);
        }
        if (adapter != null) {
            mDisplayContent.mAppTransition.overridePendingAppTransitionRemote(adapter);
        }
    }

    @Nullable
    static Task findRootTaskFromContainer(WindowContainer wc) {
        return wc.asTaskFragment() != null ? wc.asTaskFragment().getRootTask()
                : wc.asActivityRecord().getRootTask();
    }

    @Nullable
    static ActivityRecord getAppFromContainer(WindowContainer wc) {
        return wc.asTaskFragment() != null ? wc.asTaskFragment().getTopNonFinishingActivity()
                : wc.asActivityRecord();
    }

    /**
     * @return The window token that determines the animation theme.
     */
    @Nullable
    private ActivityRecord findAnimLayoutParamsToken(@TransitionOldType int transit,
            ArraySet<Integer> activityTypes, ArraySet<ActivityRecord> openingApps,
            ArraySet<ActivityRecord> closingApps, ArraySet<WindowContainer> changingApps) {
        ActivityRecord result;

        // Remote animations always win, but fullscreen tokens override non-fullscreen tokens.
        result = lookForHighestTokenWithFilter(closingApps, openingApps, changingApps,
                w -> w.getRemoteAnimationDefinition() != null
                        && w.getRemoteAnimationDefinition().hasTransition(transit, activityTypes));
        if (result != null) {
            return result;
        }
        result = lookForHighestTokenWithFilter(closingApps, openingApps, changingApps,
                w -> w.fillsParent() && w.findMainWindow() != null);
        if (result != null) {
            return result;
        }
        return lookForHighestTokenWithFilter(closingApps, openingApps, changingApps,
                w -> w.findMainWindow() != null);
    }

    /**
     * @return The set of {@link android.app.WindowConfiguration.ActivityType}s contained in the set
     *         of apps in {@code array1}, {@code array2}, and {@code array3}.
     */
    private static ArraySet<Integer> collectActivityTypes(ArraySet<ActivityRecord> array1,
            ArraySet<ActivityRecord> array2, ArraySet<WindowContainer> array3) {
        final ArraySet<Integer> result = new ArraySet<>();
        for (int i = array1.size() - 1; i >= 0; i--) {
            result.add(array1.valueAt(i).getActivityType());
        }
        for (int i = array2.size() - 1; i >= 0; i--) {
            result.add(array2.valueAt(i).getActivityType());
        }
        for (int i = array3.size() - 1; i >= 0; i--) {
            result.add(array3.valueAt(i).getActivityType());
        }
        return result;
    }

    private static ActivityRecord lookForHighestTokenWithFilter(ArraySet<ActivityRecord> array1,
            ArraySet<ActivityRecord> array2, ArraySet<WindowContainer> array3,
            Predicate<ActivityRecord> filter) {
        final int array2base = array1.size();
        final int array3base = array2.size() + array2base;
        final int count = array3base + array3.size();
        int bestPrefixOrderIndex = Integer.MIN_VALUE;
        ActivityRecord bestToken = null;
        for (int i = 0; i < count; i++) {
            final WindowContainer wtoken = i < array2base
                    ? array1.valueAt(i)
                    : (i < array3base
                            ? array2.valueAt(i - array2base)
                            : array3.valueAt(i - array3base));
            final int prefixOrderIndex = wtoken.getPrefixOrderIndex();
            final ActivityRecord r = getAppFromContainer(wtoken);
            if (r != null && filter.test(r) && prefixOrderIndex > bestPrefixOrderIndex) {
                bestPrefixOrderIndex = prefixOrderIndex;
                bestToken = r;
            }
        }
        return bestToken;
    }

    private boolean containsVoiceInteraction(ArraySet<ActivityRecord> apps) {
        for (int i = apps.size() - 1; i >= 0; i--) {
            if (apps.valueAt(i).mVoiceInteraction) {
                return true;
            }
        }
        return false;
    }

    /**
     * Apply animation to the set of window containers.
     *
     * @param wcs The list of {@link WindowContainer}s to which an app transition animation applies.
     * @param apps The list of {@link ActivityRecord}s being transitioning.
     * @param transit The current transition type.
     * @param visible {@code true} if the apps becomes visible, {@code false} if the apps becomes
     *                invisible.
     * @param animLp Layout parameters in which an app transition animation runs.
     * @param voiceInteraction {@code true} if one of the apps in this transition belongs to a voice
     *                         interaction session driving task.
     */
    private void applyAnimations(ArraySet<WindowContainer> wcs, ArraySet<ActivityRecord> apps,
            @TransitionOldType int transit, boolean visible, LayoutParams animLp,
            boolean voiceInteraction) {
        final int wcsCount = wcs.size();
        for (int i = 0; i < wcsCount; i++) {
            final WindowContainer wc = wcs.valueAt(i);
            // If app transition animation target is promoted to higher level, SurfaceAnimator
            // triggers WC#onAnimationFinished only on the promoted target. So we need to take care
            // of triggering AR#onAnimationFinished on each ActivityRecord which is a part of the
            // app transition.
            final ArrayList<ActivityRecord> transitioningDescendants = new ArrayList<>();
            for (int j = 0; j < apps.size(); ++j) {
                final ActivityRecord app = apps.valueAt(j);
                if (app.isDescendantOf(wc)) {
                    transitioningDescendants.add(app);
                }
            }
            wc.applyAnimation(animLp, transit, visible, voiceInteraction, transitioningDescendants);
        }
    }

    /**
     * Returns {@code true} if a given {@link WindowContainer} is an embedded Task in
     * {@link TaskView}.
     *
     * Note that this is a short term workaround to support Android Auto until it migrate to
     * ShellTransition. This should only be used by {@link #getAnimationTargets}.
     *
     * TODO(b/213312721): Remove this predicate and its callers once ShellTransition is enabled.
     */
    static boolean isTaskViewTask(WindowContainer wc) {
        // Use Task#mRemoveWithTaskOrganizer to identify an embedded Task, but this is a hack and
        // it is not guaranteed to work this logic in the future version.
        boolean isTaskViewTask =  wc instanceof Task && ((Task) wc).mRemoveWithTaskOrganizer;
        if (isTaskViewTask) {
            return true;
        }

        WindowContainer parent = wc.getParent();
        boolean isParentATaskViewTask = parent != null
                && parent instanceof Task
                && ((Task) parent).mRemoveWithTaskOrganizer;
        return isParentATaskViewTask;
    }

    /**
     * Find WindowContainers to be animated from a set of opening and closing apps. We will promote
     * animation targets to higher level in the window hierarchy if possible.
     *
     * @param visible {@code true} to get animation targets for opening apps, {@code false} to get
     *                            animation targets for closing apps.
     * @return {@link WindowContainer}s to be animated.
     */
    @VisibleForTesting
    static ArraySet<WindowContainer> getAnimationTargets(
            ArraySet<ActivityRecord> openingApps, ArraySet<ActivityRecord> closingApps,
            boolean visible) {

        // The candidates of animation targets, which might be able to promote to higher level.
        final ArrayDeque<WindowContainer> candidates = new ArrayDeque<>();
        final ArraySet<ActivityRecord> apps = visible ? openingApps : closingApps;
        for (int i = 0; i < apps.size(); ++i) {
            final ActivityRecord app = apps.valueAt(i);
            if (app.shouldApplyAnimation(visible)) {
                candidates.add(app);
                ProtoLog.v(WM_DEBUG_APP_TRANSITIONS,
                        "Changing app %s visible=%b performLayout=%b",
                        app, app.isVisible(), false);
            }
        }

        final ArraySet<ActivityRecord> otherApps = visible ? closingApps : openingApps;
        // Ancestors of closing apps while finding animation targets for opening apps, or ancestors
        // of opening apps while finding animation targets for closing apps.
        final ArraySet<WindowContainer> otherAncestors = new ArraySet<>();
        for (int i = 0; i < otherApps.size(); ++i) {
            for (WindowContainer wc = otherApps.valueAt(i); wc != null; wc = wc.getParent()) {
                otherAncestors.add(wc);
            }
        }

        // The final animation targets which cannot promote to higher level anymore.
        final ArraySet<WindowContainer> targets = new ArraySet<>();
        final ArrayList<WindowContainer> siblings = new ArrayList<>();
        while (!candidates.isEmpty()) {
            final WindowContainer current = candidates.removeFirst();
            final WindowContainer parent = current.getParent();
            siblings.clear();
            siblings.add(current);
            boolean canPromote = true;

            if (isTaskViewTask(current)) {
                // Don't animate an embedded Task in app transition. This is a short term workaround
                // to prevent conflict of surface hierarchy changes between legacy app transition
                // and TaskView (b/205189147).
                // TODO(b/213312721): Remove this once ShellTransition is enabled.
                continue;
            } else if (parent == null || !parent.canCreateRemoteAnimationTarget()
                    // We cannot promote the animation on Task's parent when the task is in
                    // clearing task in case the animating get stuck when performing the opening
                    // task that behind it.
                    || (current.asTask() != null && current.asTask().mInRemoveTask)
                    // We cannot promote the animation to changing window. This may happen when an
                    // activity is open in a TaskFragment that is resizing, while the existing
                    // activity in the TaskFragment is reparented to another TaskFragment.
                    || parent.isChangingAppTransition()) {
                canPromote = false;
            } else {
                // In case a descendant of the parent belongs to the other group, we cannot promote
                // the animation target from "current" to the parent.
                //
                // Example: Imagine we're checking if we can animate a Task instead of a set of
                // ActivityRecords. In case an activity starts a new activity within a same Task,
                // an ActivityRecord of an existing activity belongs to the opening apps, at the
                // same time, the other ActivityRecord of a new activity belongs to the closing
                // apps. In this case, we cannot promote the animation target to Task level, but
                // need to animate each individual activity.
                //
                // [Task] +- [ActivityRecord1] (in opening apps)
                //        +- [ActivityRecord2] (in closing apps)
                if (otherAncestors.contains(parent)) {
                    canPromote = false;
                }

                // If the current window container is a task with adjacent task set, the both
                // adjacent tasks will be opened or closed together. To get their opening or
                // closing animation target independently, skip promoting their animation targets.
                if (current.asTask() != null
                        && current.asTask().getAdjacentTask() != null) {
                    canPromote = false;
                }

                // Find all siblings of the current WindowContainer in "candidates", move them into
                // a separate list "siblings", and checks if an animation target can be promoted
                // to its parent.
                //
                // We can promote an animation target to its parent if and only if all visible
                // siblings will be animating.
                //
                // Example: Imagine that a Task contains two visible activity record, but only one
                // of them is included in the opening apps and the other belongs to neither opening
                // or closing apps. This happens when an activity launches another translucent
                // activity in the same Task. In this case, we cannot animate Task, but have to
                // animate each activity, otherwise an activity behind the translucent activity also
                // animates.
                //
                // [Task] +- [ActivityRecord1] (visible, in opening apps)
                //        +- [ActivityRecord2] (visible, not in opening apps)
                for (int j = 0; j < parent.getChildCount(); ++j) {
                    final WindowContainer sibling = parent.getChildAt(j);
                    if (candidates.remove(sibling)) {
                        if (!isTaskViewTask(sibling)) {
                            // Don't animate an embedded Task in app transition. This is a short
                            // term workaround to prevent conflict of surface hierarchy changes
                            // between legacy app transition and TaskView (b/205189147).
                            // TODO(b/213312721): Remove this once ShellTransition is enabled.
                            siblings.add(sibling);
                        }
                    } else if (sibling != current && sibling.isVisible()) {
                        canPromote = false;
                    }
                }
            }

            if (canPromote) {
                candidates.add(parent);
            } else {
                targets.addAll(siblings);
            }
        }
        ProtoLog.v(WM_DEBUG_APP_TRANSITIONS_ANIM, "getAnimationTarget in=%s, out=%s",
                apps, targets);
        return targets;
    }

    /**
     * Apply an app transition animation based on a set of {@link ActivityRecord}
     *
     * @param openingApps The list of opening apps to which an app transition animation applies.
     * @param closingApps The list of closing apps to which an app transition animation applies.
     * @param transit The current transition type.
     * @param animLp Layout parameters in which an app transition animation runs.
     * @param voiceInteraction {@code true} if one of the apps in this transition belongs to a voice
     *                         interaction session driving task.
     */
    private void applyAnimations(ArraySet<ActivityRecord> openingApps,
            ArraySet<ActivityRecord> closingApps, @TransitionOldType int transit,
            LayoutParams animLp, boolean voiceInteraction) {
        if (transit == WindowManager.TRANSIT_OLD_UNSET
                || (openingApps.isEmpty() && closingApps.isEmpty())) {
            return;
        }

        if (AppTransition.isActivityTransitOld(transit)) {
            final ArrayList<Pair<ActivityRecord, Rect>> closingLetterboxes = new ArrayList();
            for (int i = 0; i < closingApps.size(); ++i) {
                ActivityRecord closingApp = closingApps.valueAt(i);
                if (closingApp.areBoundsLetterboxed()) {
                    final Rect insets = closingApp.getLetterboxInsets();
                    closingLetterboxes.add(new Pair(closingApp, insets));
                }
            }

            for (int i = 0; i < openingApps.size(); ++i) {
                ActivityRecord openingApp = openingApps.valueAt(i);
                if (openingApp.areBoundsLetterboxed()) {
                    final Rect openingInsets = openingApp.getLetterboxInsets();
                    for (Pair<ActivityRecord, Rect> closingLetterbox : closingLetterboxes) {
                        final Rect closingInsets = closingLetterbox.second;
                        if (openingInsets.equals(closingInsets)) {
                            ActivityRecord closingApp = closingLetterbox.first;
                            openingApp.setNeedsLetterboxedAnimation(true);
                            closingApp.setNeedsLetterboxedAnimation(true);
                        }
                    }
                }
            }
        }

        final ArraySet<WindowContainer> openingWcs = getAnimationTargets(
                openingApps, closingApps, true /* visible */);
        final ArraySet<WindowContainer> closingWcs = getAnimationTargets(
                openingApps, closingApps, false /* visible */);
        applyAnimations(openingWcs, openingApps, transit, true /* visible */, animLp,
                voiceInteraction);
        applyAnimations(closingWcs, closingApps, transit, false /* visible */, animLp,
                voiceInteraction);

        for (int i = 0; i < openingApps.size(); ++i) {
            openingApps.valueAtUnchecked(i).mOverrideTaskTransition = false;
        }
        for (int i = 0; i < closingApps.size(); ++i) {
            closingApps.valueAtUnchecked(i).mOverrideTaskTransition = false;
        }

        final AccessibilityController accessibilityController =
                mDisplayContent.mWmService.mAccessibilityController;
        if (accessibilityController.hasCallbacks()) {
            accessibilityController.onAppWindowTransition(mDisplayContent.getDisplayId(), transit);
        }
    }

    private void handleOpeningApps() {
        final ArraySet<ActivityRecord> openingApps = mDisplayContent.mOpeningApps;
        final int appsCount = openingApps.size();

        for (int i = 0; i < appsCount; i++) {
            final ActivityRecord app = openingApps.valueAt(i);
            ProtoLog.v(WM_DEBUG_APP_TRANSITIONS, "Now opening app %s", app);

            app.commitVisibility(true /* visible */, false /* performLayout */);

            // In case a trampoline activity is used, it can happen that a new ActivityRecord is
            // added and a new app transition starts before the previous app transition animation
            // ends. So we cannot simply use app.isAnimating(PARENTS) to determine if the app must
            // to be added to the list of tokens to be notified of app transition complete.
            final WindowContainer wc = app.getAnimatingContainer(PARENTS,
                    ANIMATION_TYPE_APP_TRANSITION);
            if (wc == null || !wc.getAnimationSources().contains(app)) {
                // This token isn't going to be animating. Add it to the list of tokens to
                // be notified of app transition complete since the notification will not be
                // sent be the app window animator.
                mDisplayContent.mNoAnimationNotifyOnTransitionFinished.add(app.token);
            }
            app.updateReportedVisibilityLocked();
            app.showAllWindowsLocked();

            if (mDisplayContent.mAppTransition.isNextAppTransitionThumbnailUp()) {
                app.attachThumbnailAnimation();
            } else if (mDisplayContent.mAppTransition.isNextAppTransitionOpenCrossProfileApps()) {
                app.attachCrossProfileAppsThumbnailAnimation();
            }
        }
    }

    private void handleClosingApps() {
        final ArraySet<ActivityRecord> closingApps = mDisplayContent.mClosingApps;
        final int appsCount = closingApps.size();

        for (int i = 0; i < appsCount; i++) {
            final ActivityRecord app = closingApps.valueAt(i);
            ProtoLog.v(WM_DEBUG_APP_TRANSITIONS, "Now closing app %s", app);

            app.commitVisibility(false /* visible */, false /* performLayout */);
            app.updateReportedVisibilityLocked();
            // Force the allDrawn flag, because we want to start
            // this guy's animations regardless of whether it's
            // gotten drawn.
            app.allDrawn = true;
            // Ensure that apps that are mid-starting are also scheduled to have their
            // starting windows removed after the animation is complete
            if (app.mStartingWindow != null && !app.mStartingWindow.mAnimatingExit) {
                app.removeStartingWindow();
            }

            if (mDisplayContent.mAppTransition.isNextAppTransitionThumbnailDown()) {
                app.attachThumbnailAnimation();
            }
        }
    }

    private void handleClosingChangingContainers() {
        final ArrayMap<WindowContainer, Rect> containers =
                mDisplayContent.mClosingChangingContainers;
        while (!containers.isEmpty()) {
            final WindowContainer container = containers.keyAt(0);
            containers.remove(container);

            // For closing changing windows that are part of the transition, they should have been
            // removed from mClosingChangingContainers in WindowContainer#getAnimationAdapter()
            // If the closing changing TaskFragment is not part of the transition, update its
            // surface after removing it from mClosingChangingContainers.
            final TaskFragment taskFragment = container.asTaskFragment();
            if (taskFragment != null) {
                taskFragment.updateOrganizedTaskFragmentSurface();
            }
        }
    }

    private void handleChangingApps(@TransitionOldType int transit) {
        final ArraySet<WindowContainer> apps = mDisplayContent.mChangingContainers;
        final int appsCount = apps.size();
        for (int i = 0; i < appsCount; i++) {
            WindowContainer wc = apps.valueAt(i);
            ProtoLog.v(WM_DEBUG_APP_TRANSITIONS, "Now changing app %s", wc);
            wc.applyAnimation(null, transit, true, false, null /* sources */);
        }
    }

    private boolean transitionGoodToGo(ArraySet<? extends WindowContainer> apps,
            ArrayMap<WindowContainer, Integer> outReasons) {
        ProtoLog.v(WM_DEBUG_APP_TRANSITIONS,
                "Checking %d opening apps (frozen=%b timeout=%b)...", apps.size(),
                mService.mDisplayFrozen, mDisplayContent.mAppTransition.isTimeout());
        if (mDisplayContent.mAppTransition.isTimeout()) {
            return true;
        }
        final ScreenRotationAnimation screenRotationAnimation = mService.mRoot.getDisplayContent(
                Display.DEFAULT_DISPLAY).getRotationAnimation();

        // Imagine the case where we are changing orientation due to an app transition, but a
        // previous orientation change is still in progress. We won't process the orientation
        // change for our transition because we need to wait for the rotation animation to
        // finish.
        // If we start the app transition at this point, we will interrupt it halfway with a
        // new rotation animation after the old one finally finishes. It's better to defer the
        // app transition.
        if (screenRotationAnimation != null && screenRotationAnimation.isAnimating()
                && mDisplayContent.getDisplayRotation().needsUpdate()) {
            ProtoLog.v(WM_DEBUG_APP_TRANSITIONS,
                    "Delaying app transition for screen rotation animation to finish");
            return false;
        }
        for (int i = 0; i < apps.size(); i++) {
            WindowContainer wc = apps.valueAt(i);
            final ActivityRecord activity = getAppFromContainer(wc);
            if (activity == null) {
                continue;
            }
            ProtoLog.v(WM_DEBUG_APP_TRANSITIONS,
                    "Check opening app=%s: allDrawn=%b startingDisplayed=%b "
                            + "startingMoved=%b isRelaunching()=%b startingWindow=%s",
                    activity, activity.allDrawn, activity.isStartingWindowDisplayed(),
                    activity.startingMoved, activity.isRelaunching(),
                    activity.mStartingWindow);
            final boolean allDrawn = activity.allDrawn && !activity.isRelaunching();
            if (!allDrawn && !activity.isStartingWindowDisplayed() && !activity.startingMoved) {
                return false;
            }
            if (allDrawn) {
                outReasons.put(activity, APP_TRANSITION_WINDOWS_DRAWN);
            } else {
                outReasons.put(activity,
                        activity.mStartingData instanceof SplashScreenStartingData
                                ? APP_TRANSITION_SPLASH_SCREEN
                                : APP_TRANSITION_SNAPSHOT);
            }
        }

        // We also need to wait for the specs to be fetched, if needed.
        if (mDisplayContent.mAppTransition.isFetchingAppTransitionsSpecs()) {
            ProtoLog.v(WM_DEBUG_APP_TRANSITIONS, "isFetchingAppTransitionSpecs=true");
            return false;
        }

        if (!mDisplayContent.mUnknownAppVisibilityController.allResolved()) {
            ProtoLog.v(WM_DEBUG_APP_TRANSITIONS, "unknownApps is not empty: %s",
                    mDisplayContent.mUnknownAppVisibilityController.getDebugMessage());
            return false;
        }

        // If the wallpaper is visible, we need to check it's ready too.
        return !mWallpaperControllerLocked.isWallpaperVisible()
                || mWallpaperControllerLocked.wallpaperTransitionReady();
    }

    private boolean transitionGoodToGoForTaskFragments() {
        if (mDisplayContent.mAppTransition.isTimeout()) {
            return true;
        }

        // Check all Tasks in this transition. This is needed because new TaskFragment created for
        // launching activity may not be in the tracking lists, but we still want to wait for the
        // activity launch to start the transition.
        final ArraySet<Task> rootTasks = new ArraySet<>();
        for (int i = mDisplayContent.mOpeningApps.size() - 1; i >= 0; i--) {
            rootTasks.add(mDisplayContent.mOpeningApps.valueAt(i).getRootTask());
        }
        for (int i = mDisplayContent.mClosingApps.size() - 1; i >= 0; i--) {
            rootTasks.add(mDisplayContent.mClosingApps.valueAt(i).getRootTask());
        }
        for (int i = mDisplayContent.mChangingContainers.size() - 1; i >= 0; i--) {
            rootTasks.add(
                    findRootTaskFromContainer(mDisplayContent.mChangingContainers.valueAt(i)));
        }

        // Organized TaskFragment can be empty for two situations:
        // 1. New created and is waiting for Activity launch. In this case, we want to wait for
        //    the Activity launch to trigger the transition.
        // 2. Last Activity is just removed. In this case, we want to wait for organizer to
        //    remove the TaskFragment because it may also want to change other TaskFragments in
        //    the same transition.
        for (int i = rootTasks.size() - 1; i >= 0; i--) {
            final Task rootTask = rootTasks.valueAt(i);
            if (rootTask == null) {
                // It is possible that one activity may have been removed from the hierarchy. No
                // need to check for this case.
                continue;
            }
            final boolean notReady = rootTask.forAllLeafTaskFragments(taskFragment -> {
                if (!taskFragment.isReadyToTransit()) {
                    ProtoLog.v(WM_DEBUG_APP_TRANSITIONS, "Organized TaskFragment is not ready= %s",
                            taskFragment);
                    return true;
                }
                return false;
            });
            if (notReady) {
                return false;
            }
        }
        return true;
    }

    /**
     * Identifies whether the current transition occurs within a single task or not. This is used
     * to determine whether animations should be clipped to the task bounds instead of root task
     * bounds.
     */
    @VisibleForTesting
    boolean isTransitWithinTask(@TransitionOldType int transit, Task task) {
        if (task == null
                || !mDisplayContent.mChangingContainers.isEmpty()) {
            // if there is no task, then we can't constrain to the task.
            // if anything is changing, it can animate outside its task.
            return false;
        }
        if (!(transit == TRANSIT_OLD_ACTIVITY_OPEN
                || transit == TRANSIT_OLD_ACTIVITY_CLOSE
                || transit == TRANSIT_OLD_ACTIVITY_RELAUNCH)) {
            // only activity-level transitions will be within-task.
            return false;
        }
        // check that all components are in the task.
        for (ActivityRecord activity : mDisplayContent.mOpeningApps) {
            Task activityTask = activity.getTask();
            if (activityTask != task) {
                return false;
            }
        }
        for (ActivityRecord activity : mDisplayContent.mClosingApps) {
            if (activity.getTask() != task) {
                return false;
            }
        }
        return true;
    }

    private static boolean canBeWallpaperTarget(ArraySet<ActivityRecord> apps) {
        for (int i = apps.size() - 1; i >= 0; i--) {
            if (apps.valueAt(i).windowsCanBeWallpaperTarget()) {
                return true;
            }
        }
        return false;
    }

    /**
     * Finds the top app in a list of apps, using its {@link ActivityRecord#getPrefixOrderIndex} to
     * compare z-order.
     *
     * @param apps The list of apps to search.
     * @param ignoreInvisible If set to true, ignores apps that are not
     *                        {@link ActivityRecord#isVisible}.
     * @return The top {@link ActivityRecord}.
     */
    private static ActivityRecord getTopApp(ArraySet<? extends WindowContainer> apps,
            boolean ignoreInvisible) {
        int topPrefixOrderIndex = Integer.MIN_VALUE;
        ActivityRecord topApp = null;
        for (int i = apps.size() - 1; i >= 0; i--) {
            final ActivityRecord app = getAppFromContainer(apps.valueAt(i));
            if (app == null || ignoreInvisible && !app.isVisible()) {
                continue;
            }
            final int prefixOrderIndex = app.getPrefixOrderIndex();
            if (prefixOrderIndex > topPrefixOrderIndex) {
                topPrefixOrderIndex = prefixOrderIndex;
                topApp = app;
            }
        }
        return topApp;
    }
}
