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

import static android.view.WindowManager.LayoutParams.FLAG_SHOW_WALLPAPER;
import static android.view.WindowManager.TRANSIT_ACTIVITY_CLOSE;
import static android.view.WindowManager.TRANSIT_ACTIVITY_OPEN;
import static android.view.WindowManager.TRANSIT_ACTIVITY_RELAUNCH;
import static android.view.WindowManager.TRANSIT_CRASHING_ACTIVITY_CLOSE;
import static android.view.WindowManager.TRANSIT_DOCK_TASK_FROM_RECENTS;
import static android.view.WindowManager.TRANSIT_FLAG_KEYGUARD_GOING_AWAY_NO_ANIMATION;
import static android.view.WindowManager.TRANSIT_FLAG_KEYGUARD_GOING_AWAY_SUBTLE_ANIMATION;
import static android.view.WindowManager.TRANSIT_FLAG_KEYGUARD_GOING_AWAY_TO_SHADE;
import static android.view.WindowManager.TRANSIT_FLAG_KEYGUARD_GOING_AWAY_WITH_WALLPAPER;
import static android.view.WindowManager.TRANSIT_KEYGUARD_GOING_AWAY;
import static android.view.WindowManager.TRANSIT_KEYGUARD_GOING_AWAY_ON_WALLPAPER;
import static android.view.WindowManager.TRANSIT_NONE;
import static android.view.WindowManager.TRANSIT_SHOW_SINGLE_TASK_DISPLAY;
import static android.view.WindowManager.TRANSIT_TASK_CLOSE;
import static android.view.WindowManager.TRANSIT_TASK_OPEN;
import static android.view.WindowManager.TRANSIT_TASK_TO_BACK;
import static android.view.WindowManager.TRANSIT_TASK_TO_FRONT;
import static android.view.WindowManager.TRANSIT_TRANSLUCENT_ACTIVITY_CLOSE;
import static android.view.WindowManager.TRANSIT_TRANSLUCENT_ACTIVITY_OPEN;
import static android.view.WindowManager.TRANSIT_WALLPAPER_CLOSE;
import static android.view.WindowManager.TRANSIT_WALLPAPER_INTRA_CLOSE;
import static android.view.WindowManager.TRANSIT_WALLPAPER_INTRA_OPEN;
import static android.view.WindowManager.TRANSIT_WALLPAPER_OPEN;

import static com.android.server.policy.WindowManagerPolicy.FINISH_LAYOUT_REDO_CONFIG;
import static com.android.server.policy.WindowManagerPolicy.FINISH_LAYOUT_REDO_LAYOUT;
import static com.android.server.wm.ActivityTaskManagerInternal.APP_TRANSITION_SNAPSHOT;
import static com.android.server.wm.ActivityTaskManagerInternal.APP_TRANSITION_SPLASH_SCREEN;
import static com.android.server.wm.ActivityTaskManagerInternal.APP_TRANSITION_WINDOWS_DRAWN;
import static com.android.server.wm.AppTransition.isKeyguardGoingAwayTransit;
import static com.android.server.wm.ProtoLogGroup.WM_DEBUG_APP_TRANSITIONS;
import static com.android.server.wm.ProtoLogGroup.WM_DEBUG_APP_TRANSITIONS_ANIM;
import static com.android.server.wm.WindowContainer.AnimationFlags.CHILDREN;
import static com.android.server.wm.WindowContainer.AnimationFlags.PARENTS;
import static com.android.server.wm.WindowManagerDebugConfig.SHOW_LIGHT_TRANSACTIONS;
import static com.android.server.wm.WindowManagerDebugConfig.TAG_WITH_CLASS_NAME;
import static com.android.server.wm.WindowManagerDebugConfig.TAG_WM;

import android.annotation.NonNull;
import android.os.Trace;
import android.util.ArrayMap;
import android.util.ArraySet;
import android.util.Slog;
import android.view.Display;
import android.view.RemoteAnimationAdapter;
import android.view.RemoteAnimationDefinition;
import android.view.WindowManager;
import android.view.WindowManager.LayoutParams;
import android.view.WindowManager.TransitionType;
import android.view.animation.Animation;

import com.android.internal.annotations.VisibleForTesting;
import com.android.server.protolog.common.ProtoLog;

import java.util.ArrayList;
import java.util.LinkedList;
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

    private final ArrayMap<WindowContainer, Integer> mTempTransitionReasons = new ArrayMap<>();

    AppTransitionController(WindowManagerService service, DisplayContent displayContent) {
        mService = service;
        mDisplayContent = displayContent;
        mWallpaperControllerLocked = mDisplayContent.mWallpaperController;
    }

    void registerRemoteAnimations(RemoteAnimationDefinition definition) {
        mRemoteAnimationDefinition = definition;
    }

    /**
     * Handle application transition for given display.
     */
    void handleAppTransitionReady() {
        mTempTransitionReasons.clear();
        if (!transitionGoodToGo(mDisplayContent.mOpeningApps, mTempTransitionReasons)
                || !transitionGoodToGo(mDisplayContent.mChangingContainers,
                        mTempTransitionReasons)) {
            return;
        }
        Trace.traceBegin(Trace.TRACE_TAG_WINDOW_MANAGER, "AppTransitionReady");

        ProtoLog.v(WM_DEBUG_APP_TRANSITIONS, "**** GOOD TO GO");
        final AppTransition appTransition = mDisplayContent.mAppTransition;
        int transit = appTransition.getAppTransition();
        if (mDisplayContent.mSkipAppTransitionAnimation && !isKeyguardGoingAwayTransit(transit)) {
            transit = WindowManager.TRANSIT_UNSET;
        }
        mDisplayContent.mSkipAppTransitionAnimation = false;
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

        // Determine if closing and opening app token sets are wallpaper targets, in which case
        // special animations are needed.
        final boolean hasWallpaperTarget = mWallpaperControllerLocked.getWallpaperTarget() != null;
        final boolean openingAppHasWallpaper = canBeWallpaperTarget(mDisplayContent.mOpeningApps)
                && hasWallpaperTarget;
        final boolean closingAppHasWallpaper = canBeWallpaperTarget(mDisplayContent.mClosingApps)
                && hasWallpaperTarget;

        transit = maybeUpdateTransitToTranslucentAnim(transit);
        transit = maybeUpdateTransitToWallpaper(transit, openingAppHasWallpaper,
                closingAppHasWallpaper);

        // Find the layout params of the top-most application window in the tokens, which is
        // what will control the animation theme. If all closing windows are obscured, then there is
        // no need to do an animation. This is the case, for example, when this transition is being
        // done behind a dream window.
        final ArraySet<Integer> activityTypes = collectActivityTypes(mDisplayContent.mOpeningApps,
                mDisplayContent.mClosingApps, mDisplayContent.mChangingContainers);
        final boolean allowAnimations = mDisplayContent.getDisplayPolicy().allowAppAnimationsLw();
        final ActivityRecord animLpActivity = allowAnimations
                ? findAnimLayoutParamsToken(transit, activityTypes)
                : null;
        final ActivityRecord topOpeningApp = allowAnimations
                ? getTopApp(mDisplayContent.mOpeningApps, false /* ignoreHidden */)
                : null;
        final ActivityRecord topClosingApp = allowAnimations
                ? getTopApp(mDisplayContent.mClosingApps, false /* ignoreHidden */)
                : null;
        final ActivityRecord topChangingApp = allowAnimations
                ? getTopApp(mDisplayContent.mChangingContainers, false /* ignoreHidden */)
                : null;
        final WindowManager.LayoutParams animLp = getAnimLp(animLpActivity);
        overrideWithRemoteAnimationIfSet(animLpActivity, transit, activityTypes);

        final boolean voiceInteraction = containsVoiceInteraction(mDisplayContent.mOpeningApps)
                || containsVoiceInteraction(mDisplayContent.mOpeningApps);

        final int layoutRedo;
        mService.mSurfaceAnimationRunner.deferStartingAnimations();
        try {
            applyAnimations(mDisplayContent.mOpeningApps, mDisplayContent.mClosingApps, transit,
                    animLp, voiceInteraction);
            handleClosingApps();
            handleOpeningApps();
            handleChangingApps(transit);

            appTransition.setLastAppTransition(transit, topOpeningApp,
                    topClosingApp, topChangingApp);

            final int flags = appTransition.getTransitFlags();
            layoutRedo = appTransition.goodToGo(transit, topOpeningApp,
                    mDisplayContent.mOpeningApps);
            handleNonAppWindowsInTransition(transit, flags);
            appTransition.postAnimationCallback();
            appTransition.clear();
        } finally {
            mService.mSurfaceAnimationRunner.continueStartingAnimations();
        }

        mService.mTaskSnapshotController.onTransitionStarting(mDisplayContent);

        mDisplayContent.mOpeningApps.clear();
        mDisplayContent.mClosingApps.clear();
        mDisplayContent.mChangingContainers.clear();
        mDisplayContent.mUnknownAppVisibilityController.clear();

        // This has changed the visibility of windows, so perform
        // a new layout to get them all up-to-date.
        mDisplayContent.setLayoutNeeded();

        mDisplayContent.computeImeTarget(true /* updateImeTarget */);

        mService.mAtmService.mStackSupervisor.getActivityMetricsLogger().notifyTransitionStarting(
                mTempTransitionReasons);

        if (transit == TRANSIT_SHOW_SINGLE_TASK_DISPLAY) {
            mService.mAnimator.addAfterPrepareSurfacesRunnable(() -> {
                mService.mAtmInternal.notifySingleTaskDisplayDrawn(mDisplayContent.getDisplayId());
            });
        }

        Trace.traceEnd(Trace.TRACE_TAG_WINDOW_MANAGER);

        mDisplayContent.pendingLayoutChanges |=
                layoutRedo | FINISH_LAYOUT_REDO_LAYOUT | FINISH_LAYOUT_REDO_CONFIG;
    }

    private static WindowManager.LayoutParams getAnimLp(ActivityRecord activity) {
        final WindowState mainWindow = activity != null ? activity.findMainWindow() : null;
        return mainWindow != null ? mainWindow.mAttrs : null;
    }

    RemoteAnimationAdapter getRemoteAnimationOverride(@NonNull WindowContainer container,
            @TransitionType int transit, ArraySet<Integer> activityTypes) {
        final RemoteAnimationDefinition definition = container.getRemoteAnimationDefinition();
        if (definition != null) {
            final RemoteAnimationAdapter adapter = definition.getAdapter(transit, activityTypes);
            if (adapter != null) {
                return adapter;
            }
        }
        if (mRemoteAnimationDefinition == null) {
            return null;
        }
        return mRemoteAnimationDefinition.getAdapter(transit, activityTypes);
    }

    /**
     * Overrides the pending transition with the remote animation defined for the transition in the
     * set of defined remote animations in the app window token.
     */
    private void overrideWithRemoteAnimationIfSet(ActivityRecord animLpActivity,
            @TransitionType int transit, ArraySet<Integer> activityTypes) {
        if (transit == TRANSIT_CRASHING_ACTIVITY_CLOSE) {
            // The crash transition has higher priority than any involved remote animations.
            return;
        }
        if (animLpActivity == null) {
            return;
        }
        final RemoteAnimationAdapter adapter =
                getRemoteAnimationOverride(animLpActivity, transit, activityTypes);
        if (adapter != null) {
            animLpActivity.getDisplayContent().mAppTransition.overridePendingAppTransitionRemote(
                    adapter);
        }
    }

    static ActivityRecord getAppFromContainer(WindowContainer wc) {
        return wc.asTask() != null ? wc.asTask().getTopNonFinishingActivity()
                : wc.asActivityRecord();
    }

    /**
     * @return The window token that determines the animation theme.
     */
    private ActivityRecord findAnimLayoutParamsToken(@TransitionType int transit,
            ArraySet<Integer> activityTypes) {
        ActivityRecord result;
        final ArraySet<ActivityRecord> closingApps = mDisplayContent.mClosingApps;
        final ArraySet<ActivityRecord> openingApps = mDisplayContent.mOpeningApps;
        final ArraySet<WindowContainer> changingApps = mDisplayContent.mChangingContainers;

        // Remote animations always win, but fullscreen tokens override non-fullscreen tokens.
        result = lookForHighestTokenWithFilter(closingApps, openingApps, changingApps,
                w -> w.getRemoteAnimationDefinition() != null
                        && w.getRemoteAnimationDefinition().hasTransition(transit, activityTypes));
        if (result != null) {
            return getAppFromContainer(result);
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
            @TransitionType int transit, boolean visible, LayoutParams animLp,
            boolean voiceInteraction) {
        final int wcsCount = wcs.size();
        for (int i = 0; i < wcsCount; i++) {
            final WindowContainer wc = wcs.valueAt(i);
            // If app transition animation target is promoted to higher level, SurfaceAnimator
            // triggers WC#onAnimationFinished only on the promoted target. So we need to take care
            // of triggering AR#onAnimationFinished on each ActivityRecord which is a part of the
            // app transition.
            final ArrayList<ActivityRecord> transitioningDecendants = new ArrayList<>();
            for (int j = 0; j < apps.size(); ++j) {
                final ActivityRecord app = apps.valueAt(j);
                if (app.isDescendantOf(wc)) {
                    transitioningDecendants.add(app);
                }
            }
            wc.applyAnimation(animLp, transit, visible, voiceInteraction, (type, anim) -> {
                for (int j = 0; j < transitioningDecendants.size(); ++j) {
                    transitioningDecendants.get(j).onAnimationFinished(type, anim);
                }
            });
        }
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
        final LinkedList<WindowContainer> candidates = new LinkedList<>();
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

        if (!WindowManagerService.sHierarchicalAnimations) {
            return new ArraySet<>(candidates);
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

            if (parent == null || !parent.canCreateRemoteAnimationTarget()) {
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
                        siblings.add(sibling);
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
            ArraySet<ActivityRecord> closingApps, @TransitionType int transit,
            LayoutParams animLp, boolean voiceInteraction) {
        if (transit == WindowManager.TRANSIT_UNSET
                || (openingApps.isEmpty() && closingApps.isEmpty())) {
            return;
        }

        final ArraySet<WindowContainer> openingWcs = getAnimationTargets(
                openingApps, closingApps, true /* visible */);
        final ArraySet<WindowContainer> closingWcs = getAnimationTargets(
                openingApps, closingApps, false /* visible */);
        applyAnimations(openingWcs, openingApps, transit, true /* visible */, animLp,
                voiceInteraction);
        applyAnimations(closingWcs, closingApps, transit, false /* visible */, animLp,
                voiceInteraction);

        final AccessibilityController accessibilityController =
                mDisplayContent.mWmService.mAccessibilityController;
        if (accessibilityController != null) {
            accessibilityController.onAppWindowTransitionLocked(
                    mDisplayContent.getDisplayId(), transit);
        }
    }

    private void handleOpeningApps() {
        final ArraySet<ActivityRecord> openingApps = mDisplayContent.mOpeningApps;
        final int appsCount = openingApps.size();

        for (int i = 0; i < appsCount; i++) {
            final ActivityRecord app = openingApps.valueAt(i);
            ProtoLog.v(WM_DEBUG_APP_TRANSITIONS, "Now opening app %s", app);

            app.commitVisibility(true /* visible */, false /* performLayout */);
            if (!app.isAnimating(PARENTS | CHILDREN)) {
                // This token isn't going to be animating. Add it to the list of tokens to
                // be notified of app transition complete since the notification will not be
                // sent be the app window animator.
                mDisplayContent.mNoAnimationNotifyOnTransitionFinished.add(app.token);
            }
            app.updateReportedVisibilityLocked();
            app.waitingToShow = false;
            if (SHOW_LIGHT_TRANSACTIONS) Slog.i(TAG,
                    ">>> OPEN TRANSACTION handleAppTransitionReady()");
            mService.openSurfaceTransaction();
            try {
                app.showAllWindowsLocked();
            } finally {
                mService.closeSurfaceTransaction("handleAppTransitionReady");
                if (SHOW_LIGHT_TRANSACTIONS) Slog.i(TAG,
                        "<<< CLOSE TRANSACTION handleAppTransitionReady()");
            }

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
            if (app.startingWindow != null && !app.startingWindow.mAnimatingExit) {
                app.removeStartingWindow();
            }

            if (mDisplayContent.mAppTransition.isNextAppTransitionThumbnailDown()) {
                app.attachThumbnailAnimation();
            }
        }
    }

    private void handleChangingApps(@TransitionType int transit) {
        final ArraySet<WindowContainer> apps = mDisplayContent.mChangingContainers;
        final int appsCount = apps.size();
        for (int i = 0; i < appsCount; i++) {
            WindowContainer wc = apps.valueAt(i);
            ProtoLog.v(WM_DEBUG_APP_TRANSITIONS, "Now changing app %s", wc);
            wc.applyAnimation(null, transit, true, false,
                    null /* animationFinishedCallback */);
        }
    }

    private void handleNonAppWindowsInTransition(@TransitionType int transit, int flags) {
        if (transit == TRANSIT_KEYGUARD_GOING_AWAY) {
            if ((flags & TRANSIT_FLAG_KEYGUARD_GOING_AWAY_WITH_WALLPAPER) != 0
                    && (flags & TRANSIT_FLAG_KEYGUARD_GOING_AWAY_NO_ANIMATION) == 0
                    && (flags & TRANSIT_FLAG_KEYGUARD_GOING_AWAY_SUBTLE_ANIMATION) == 0) {
                Animation anim = mService.mPolicy.createKeyguardWallpaperExit(
                        (flags & TRANSIT_FLAG_KEYGUARD_GOING_AWAY_TO_SHADE) != 0);
                if (anim != null) {
                    anim.scaleCurrentDuration(mService.getTransitionAnimationScaleLocked());
                    mDisplayContent.mWallpaperController.startWallpaperAnimation(anim);
                }
            }
        }
        if (transit == TRANSIT_KEYGUARD_GOING_AWAY
                || transit == TRANSIT_KEYGUARD_GOING_AWAY_ON_WALLPAPER) {
            mDisplayContent.startKeyguardExitOnNonAppWindows(
                    transit == TRANSIT_KEYGUARD_GOING_AWAY_ON_WALLPAPER,
                    (flags & TRANSIT_FLAG_KEYGUARD_GOING_AWAY_TO_SHADE) != 0,
                    (flags & TRANSIT_FLAG_KEYGUARD_GOING_AWAY_SUBTLE_ANIMATION) != 0);
        }
    }

    private boolean transitionGoodToGo(ArraySet<? extends WindowContainer> apps,
            ArrayMap<WindowContainer, Integer> outReasons) {
        ProtoLog.v(WM_DEBUG_APP_TRANSITIONS,
                "Checking %d opening apps (frozen=%b timeout=%b)...", apps.size(),
                mService.mDisplayFrozen, mDisplayContent.mAppTransition.isTimeout());

        final ScreenRotationAnimation screenRotationAnimation = mService.mRoot.getDisplayContent(
                Display.DEFAULT_DISPLAY).getRotationAnimation();

        if (!mDisplayContent.mAppTransition.isTimeout()) {
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
                        activity, activity.allDrawn, activity.startingDisplayed,
                        activity.startingMoved, activity.isRelaunching(),
                        activity.startingWindow);


                final boolean allDrawn = activity.allDrawn && !activity.isRelaunching();
                if (!allDrawn && !activity.startingDisplayed && !activity.startingMoved) {
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
            boolean wallpaperReady = !mWallpaperControllerLocked.isWallpaperVisible() ||
                    mWallpaperControllerLocked.wallpaperTransitionReady();
            if (wallpaperReady) {
                return true;
            }
            return false;
        }
        return true;
    }

    private int maybeUpdateTransitToWallpaper(@TransitionType int transit,
            boolean openingAppHasWallpaper, boolean closingAppHasWallpaper) {
        // Given no app transition pass it through instead of a wallpaper transition.
        // Never convert the crashing transition.
        // Never update the transition for the wallpaper if we are just docking from recents
        // Never convert a change transition since the top activity isn't changing and will likely
        // still be above an opening wallpaper.
        if (transit == TRANSIT_NONE || transit == TRANSIT_CRASHING_ACTIVITY_CLOSE
                || transit == TRANSIT_DOCK_TASK_FROM_RECENTS
                || AppTransition.isChangeTransit(transit)) {
            return transit;
        }

        final WindowState wallpaperTarget = mWallpaperControllerLocked.getWallpaperTarget();
        final boolean showWallpaper = wallpaperTarget != null
                && ((wallpaperTarget.mAttrs.flags & FLAG_SHOW_WALLPAPER) != 0
                // Update task open transition to wallpaper transition when wallpaper is visible.
                // (i.e.launching app info activity from recent tasks)
                || ((transit == TRANSIT_TASK_OPEN || transit == TRANSIT_TASK_TO_FRONT)
                && mWallpaperControllerLocked.isWallpaperVisible()));
        // If wallpaper is animating or wallpaperTarget doesn't have SHOW_WALLPAPER flag set,
        // don't consider upgrading to wallpaper transition.
        final WindowState oldWallpaper =
                (mWallpaperControllerLocked.isWallpaperTargetAnimating() || !showWallpaper)
                        ? null
                        : wallpaperTarget;
        final ArraySet<ActivityRecord> openingApps = mDisplayContent.mOpeningApps;
        final ArraySet<ActivityRecord> closingApps = mDisplayContent.mClosingApps;
        final ActivityRecord topOpeningApp = getTopApp(mDisplayContent.mOpeningApps,
                false /* ignoreHidden */);
        final ActivityRecord topClosingApp = getTopApp(mDisplayContent.mClosingApps,
                true /* ignoreHidden */);

        boolean openingCanBeWallpaperTarget = canBeWallpaperTarget(openingApps);
        ProtoLog.v(WM_DEBUG_APP_TRANSITIONS,
                        "New wallpaper target=%s, oldWallpaper=%s, openingApps=%s, closingApps=%s",
                        wallpaperTarget, oldWallpaper, openingApps, closingApps);

        if (openingCanBeWallpaperTarget && transit == TRANSIT_KEYGUARD_GOING_AWAY) {
            transit = TRANSIT_KEYGUARD_GOING_AWAY_ON_WALLPAPER;
            ProtoLog.v(WM_DEBUG_APP_TRANSITIONS,
                    "New transit: %s", AppTransition.appTransitionToString(transit));
        }
        // We never want to change from a Keyguard transit to a non-Keyguard transit, as our logic
        // relies on the fact that we always execute a Keyguard transition after preparing one.
        else if (!isKeyguardGoingAwayTransit(transit)) {
            if (closingAppHasWallpaper && openingAppHasWallpaper) {
                ProtoLog.v(WM_DEBUG_APP_TRANSITIONS, "Wallpaper animation!");
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
                ProtoLog.v(WM_DEBUG_APP_TRANSITIONS,
                        "New transit: %s", AppTransition.appTransitionToString(transit));
            } else if (oldWallpaper != null && !mDisplayContent.mOpeningApps.isEmpty()
                    && !openingApps.contains(oldWallpaper.mActivityRecord)
                    && closingApps.contains(oldWallpaper.mActivityRecord)
                    && topClosingApp == oldWallpaper.mActivityRecord) {
                // We are transitioning from an activity with a wallpaper to one without.
                transit = TRANSIT_WALLPAPER_CLOSE;
                ProtoLog.v(WM_DEBUG_APP_TRANSITIONS,
                        "New transit away from wallpaper: %s",
                                AppTransition.appTransitionToString(transit));
            } else if (wallpaperTarget != null && wallpaperTarget.isVisibleLw()
                    && openingApps.contains(wallpaperTarget.mActivityRecord)
                    && topOpeningApp == wallpaperTarget.mActivityRecord
                    && transit != TRANSIT_TRANSLUCENT_ACTIVITY_CLOSE) {
                // We are transitioning from an activity without
                // a wallpaper to now showing the wallpaper
                transit = TRANSIT_WALLPAPER_OPEN;
                ProtoLog.v(WM_DEBUG_APP_TRANSITIONS, "New transit into wallpaper: %s",
                        AppTransition.appTransitionToString(transit));
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
    int maybeUpdateTransitToTranslucentAnim(@TransitionType int transit) {
        if (AppTransition.isChangeTransit(transit)) {
            // There's no special animation to handle change animations with translucent apps
            return transit;
        }
        final boolean taskOrActivity = AppTransition.isTaskTransit(transit)
                || AppTransition.isActivityTransit(transit);
        boolean allOpeningVisible = true;
        boolean allTranslucentOpeningApps = !mDisplayContent.mOpeningApps.isEmpty();
        for (int i = mDisplayContent.mOpeningApps.size() - 1; i >= 0; i--) {
            final ActivityRecord activity = mDisplayContent.mOpeningApps.valueAt(i);
            if (!activity.isVisible()) {
                allOpeningVisible = false;
                if (activity.fillsParent()) {
                    allTranslucentOpeningApps = false;
                }
            }
        }
        boolean allTranslucentClosingApps = !mDisplayContent.mClosingApps.isEmpty();
        for (int i = mDisplayContent.mClosingApps.size() - 1; i >= 0; i--) {
            if (mDisplayContent.mClosingApps.valueAt(i).fillsParent()) {
                allTranslucentClosingApps = false;
                break;
            }
        }

        if (taskOrActivity && allTranslucentClosingApps && allOpeningVisible) {
            return TRANSIT_TRANSLUCENT_ACTIVITY_CLOSE;
        }
        if (taskOrActivity && allTranslucentOpeningApps && mDisplayContent.mClosingApps.isEmpty()) {
            return TRANSIT_TRANSLUCENT_ACTIVITY_OPEN;
        }
        return transit;
    }

    /**
     * Identifies whether the current transition occurs within a single task or not. This is used
     * to determine whether animations should be clipped to the task bounds instead of stack bounds.
     */
    @VisibleForTesting
    boolean isTransitWithinTask(@TransitionType int transit, Task task) {
        if (task == null
                || !mDisplayContent.mChangingContainers.isEmpty()) {
            // if there is no task, then we can't constrain to the task.
            // if anything is changing, it can animate outside its task.
            return false;
        }
        if (!(transit == TRANSIT_ACTIVITY_OPEN
                || transit == TRANSIT_ACTIVITY_CLOSE
                || transit == TRANSIT_ACTIVITY_RELAUNCH)) {
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

    private boolean canBeWallpaperTarget(ArraySet<ActivityRecord> apps) {
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
    private ActivityRecord getTopApp(ArraySet<? extends WindowContainer> apps,
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
