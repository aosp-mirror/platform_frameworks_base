/*
 * Copyright (C) 2021 The Android Open Source Project
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

import static android.app.ActivityTaskManager.INVALID_TASK_ID;
import static android.view.RemoteAnimationTarget.MODE_CLOSING;
import static android.view.RemoteAnimationTarget.MODE_OPENING;
import static android.view.WindowManager.LayoutParams.TYPE_BASE_APPLICATION;
import static android.view.WindowManager.TRANSIT_CLOSE;
import static android.view.WindowManager.TRANSIT_OLD_NONE;
import static android.view.WindowManager.TRANSIT_TO_BACK;

import static com.android.internal.protolog.ProtoLogGroup.WM_DEBUG_BACK_PREVIEW;
import static com.android.server.wm.BackNavigationProto.ANIMATION_IN_PROGRESS;
import static com.android.server.wm.BackNavigationProto.ANIMATION_RUNNING;
import static com.android.server.wm.BackNavigationProto.LAST_BACK_TYPE;
import static com.android.server.wm.BackNavigationProto.MAIN_OPEN_ACTIVITY;
import static com.android.server.wm.BackNavigationProto.SHOW_WALLPAPER;
import static com.android.server.wm.SurfaceAnimator.ANIMATION_TYPE_PREDICT_BACK;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.content.Context;
import android.content.res.ResourceId;
import android.graphics.Point;
import android.graphics.Rect;
import android.os.Bundle;
import android.os.RemoteCallback;
import android.os.RemoteException;
import android.os.SystemProperties;
import android.text.TextUtils;
import android.util.ArraySet;
import android.util.Slog;
import android.util.proto.ProtoOutputStream;
import android.view.RemoteAnimationTarget;
import android.view.SurfaceControl;
import android.view.WindowInsets;
import android.window.BackAnimationAdapter;
import android.window.BackNavigationInfo;
import android.window.IBackAnimationFinishedCallback;
import android.window.IWindowlessStartingSurfaceCallback;
import android.window.OnBackInvokedCallbackInfo;
import android.window.TaskSnapshot;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.policy.TransitionAnimation;
import com.android.internal.protolog.common.ProtoLog;
import com.android.server.wm.utils.InsetUtils;

import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Objects;

/**
 * Controller to handle actions related to the back gesture on the server side.
 */
class BackNavigationController {
    private static final String TAG = "CoreBackPreview";
    private WindowManagerService mWindowManagerService;
    private boolean mBackAnimationInProgress;
    private @BackNavigationInfo.BackTargetType int mLastBackType;
    private boolean mShowWallpaper;
    private Runnable mPendingAnimation;
    private final NavigationMonitor mNavigationMonitor = new NavigationMonitor();

    private AnimationHandler mAnimationHandler;

    /**
     * The transition who match the back navigation targets,
     * release animation after this transition finish.
     */
    private Transition mWaitTransitionFinish;
    private final ArrayList<WindowContainer> mTmpOpenApps = new ArrayList<>();
    private final ArrayList<WindowContainer> mTmpCloseApps = new ArrayList<>();

    // This will be set if the back navigation is in progress and the current transition is still
    // running. The pending animation builder will do the animation stuff includes creating leashes,
    // re-parenting leashes and set launch behind, etc. Will be handled when transition finished.
    private AnimationHandler.ScheduleAnimationBuilder mPendingAnimationBuilder;

    private static int sDefaultAnimationResId;

    /**
     * true if the back predictability feature is enabled
     */
    static final boolean sPredictBackEnable =
            SystemProperties.getBoolean("persist.wm.debug.predictive_back", true);

    static boolean isScreenshotEnabled() {
        return SystemProperties.getInt("persist.wm.debug.predictive_back_screenshot", 0) != 0;
    }

    // Notify focus window changed
    void onFocusChanged(WindowState newFocus) {
        mNavigationMonitor.onFocusWindowChanged(newFocus);
    }

    /**
     * Set up the necessary leashes and build a {@link BackNavigationInfo} instance for an upcoming
     * back gesture animation.
     *
     * @return a {@link BackNavigationInfo} instance containing the required leashes and metadata
     * for the animation, or null if we don't know how to animate the current window and need to
     * fallback on dispatching the key event.
     */
    @VisibleForTesting
    @Nullable
    BackNavigationInfo startBackNavigation(@NonNull RemoteCallback navigationObserver,
            BackAnimationAdapter adapter) {
        if (!sPredictBackEnable) {
            return null;
        }
        final WindowManagerService wmService = mWindowManagerService;

        int backType = BackNavigationInfo.TYPE_UNDEFINED;

        // The currently visible activity (if any).
        ActivityRecord currentActivity = null;

        // The currently visible task (if any).
        Task currentTask = null;

        // The previous task we're going back to. Can be the same as currentTask, if there are
        // multiple Activities in the Stack.
        Task prevTask = null;

        WindowContainer<?> removedWindowContainer = null;
        WindowState window;

        BackNavigationInfo.Builder infoBuilder = new BackNavigationInfo.Builder();
        synchronized (wmService.mGlobalLock) {
            if (isMonitoringTransition()) {
                Slog.w(TAG, "Previous animation hasn't finish, status: " + mAnimationHandler);
                // Don't start any animation for it.
                return null;
            }

            window = wmService.getFocusedWindowLocked();

            if (window == null) {
                // We don't have any focused window, fallback ont the top currentTask of the focused
                // display.
                ProtoLog.w(WM_DEBUG_BACK_PREVIEW,
                        "No focused window, defaulting to top current task's window");
                currentTask = wmService.mAtmService.getTopDisplayFocusedRootTask();
                window = currentTask != null
                        ? currentTask.getWindow(WindowState::isFocused) : null;
            }

            if (window == null) {
                Slog.e(TAG, "Window is null, returning null.");
                return null;
            }

            // This is needed to bridge the old and new back behavior with recents.  While in
            // Overview with live tile enabled, the previous app is technically focused but we
            // add an input consumer to capture all input that would otherwise go to the apps
            // being controlled by the animation. This means that the window resolved is not
            // the right window to consume back while in overview, so we need to route it to
            // launcher and use the legacy behavior of injecting KEYCODE_BACK since the existing
            // compat callback in VRI only works when the window is focused.
            // This symptom also happen while shell transition enabled, we can check that by
            // isTransientLaunch to know whether the focus window is point to live tile.
            final RecentsAnimationController recentsAnimationController =
                    wmService.getRecentsAnimationController();
            final ActivityRecord tmpAR = window.mActivityRecord;
            if ((tmpAR != null && tmpAR.isActivityTypeHomeOrRecents()
                    && tmpAR.mTransitionController.isTransientLaunch(tmpAR))
                    || (recentsAnimationController != null
                    && recentsAnimationController.shouldApplyInputConsumer(tmpAR))) {
                ProtoLog.d(WM_DEBUG_BACK_PREVIEW, "Current focused window being animated by "
                        + "recents. Overriding back callback to recents controller callback.");
                return null;
            }

            if (!window.isDrawn()) {
                ProtoLog.d(WM_DEBUG_BACK_PREVIEW,
                        "Focused window didn't have a valid surface drawn.");
                return null;
            }

            currentActivity = window.mActivityRecord;
            currentTask = window.getTask();
            if ((currentTask != null && !currentTask.isVisibleRequested())
                    || (currentActivity != null && !currentActivity.isVisibleRequested())) {
                // Closing transition is happening on focus window and should be update soon,
                // don't drive back navigation with it.
                ProtoLog.d(WM_DEBUG_BACK_PREVIEW, "Focus window is closing.");
                return null;
            }
            // Now let's find if this window has a callback from the client side.
            final OnBackInvokedCallbackInfo callbackInfo = window.getOnBackInvokedCallbackInfo();
            if (callbackInfo == null) {
                Slog.e(TAG, "No callback registered, returning null.");
                return null;
            }
            if (!callbackInfo.isSystemCallback()) {
                backType = BackNavigationInfo.TYPE_CALLBACK;
            }
            infoBuilder.setOnBackInvokedCallback(callbackInfo.getCallback());
            infoBuilder.setAnimationCallback(callbackInfo.isAnimationCallback());
            mNavigationMonitor.startMonitor(window, navigationObserver);

            ProtoLog.d(WM_DEBUG_BACK_PREVIEW, "startBackNavigation currentTask=%s, "
                            + "topRunningActivity=%s, callbackInfo=%s, currentFocus=%s",
                    currentTask, currentActivity, callbackInfo, window);

            // If we don't need to set up the animation, we return early. This is the case when
            // - We have an application callback.
            // - We don't have any ActivityRecord or Task to animate.
            // - The IME is opened, and we just need to close it.
            // - The home activity is the focused activity & it's not TYPE_BASE_APPLICATION
            // - The current activity will do shared element transition when exiting.
            if (backType == BackNavigationInfo.TYPE_CALLBACK
                    || currentActivity == null
                    || currentTask == null
                    || (currentActivity.isActivityTypeHome()
                            && window.mAttrs.type == TYPE_BASE_APPLICATION)
                    || currentActivity.mHasSceneTransition) {
                infoBuilder.setType(BackNavigationInfo.TYPE_CALLBACK);
                infoBuilder.setOnBackNavigationDone(new RemoteCallback(result ->
                        onBackNavigationDone(result, BackNavigationInfo.TYPE_CALLBACK)));
                mLastBackType = BackNavigationInfo.TYPE_CALLBACK;
                return infoBuilder.build();
            }

            // The previous activity we're going back to. This can be either a child of currentTask
            // if there are more than one Activity in currentTask, or a child of prevTask, if
            // currentActivity is the last child of currentTask.
            // We don't have an application callback, let's find the destination of the back gesture
            // The search logic should align with ActivityClientController#finishActivity
            final ArrayList<ActivityRecord> prevActivities = new ArrayList<>();
            final boolean canAnimate = getAnimatablePrevActivities(currentTask, currentActivity,
                    prevActivities);
            final boolean isOccluded = isKeyguardOccluded(window);
            if (!canAnimate) {
                backType = BackNavigationInfo.TYPE_CALLBACK;
            } else if ((window.getParent().getChildCount() > 1
                    && window.getParent().getChildAt(0) != window)) {
                // TODO Dialog window does not need to attach on activity, check
                // window.mAttrs.type != TYPE_BASE_APPLICATION
                // Are we the top window of our parent? If not, we are a window on top of the
                // activity, we won't close the activity.
                backType = BackNavigationInfo.TYPE_DIALOG_CLOSE;
                removedWindowContainer = window;
            } else if (hasTranslucentActivity(currentActivity, prevActivities)) {
                // skip if one of participant activity is translucent
                backType = BackNavigationInfo.TYPE_CALLBACK;
            } else if (prevActivities.size() > 0) {
                if (!isOccluded || isAllActivitiesCanShowWhenLocked(prevActivities)) {
                    // We have another Activity in the same currentTask to go to
                    final WindowContainer parent = currentActivity.getParent();
                    final boolean canCustomize = parent != null
                            && (parent.asTask() != null
                            || (parent.asTaskFragment() != null
                            && parent.canCustomizeAppTransition()));
                    if (canCustomize) {
                        if (isCustomizeExitAnimation(window)) {
                            infoBuilder.setWindowAnimations(
                                    window.mAttrs.packageName, window.mAttrs.windowAnimations);
                        }
                        final ActivityRecord.CustomAppTransition customAppTransition =
                                currentActivity.getCustomAnimation(false/* open */);
                        if (customAppTransition != null) {
                            infoBuilder.setCustomAnimation(currentActivity.packageName,
                                    customAppTransition.mEnterAnim,
                                    customAppTransition.mExitAnim,
                                    customAppTransition.mBackgroundColor);
                        }
                    }
                    removedWindowContainer = currentActivity;
                    prevTask = prevActivities.get(0).getTask();
                    backType = BackNavigationInfo.TYPE_CROSS_ACTIVITY;
                } else {
                    // keyguard locked and activities are unable to show when locked.
                    backType = BackNavigationInfo.TYPE_CALLBACK;
                }
            } else if (currentTask.mAtmService.getLockTaskController().isTaskLocked(currentTask)) {
                // Do not predict if current task is in task locked.
                backType = BackNavigationInfo.TYPE_CALLBACK;
            } else {
                // Check back-to-home or cross-task
                prevTask = currentTask.mRootWindowContainer.getTask(t -> {
                    if (t.showToCurrentUser() && !t.mChildren.isEmpty()) {
                        final ActivityRecord ar = t.getTopNonFinishingActivity();
                        return ar != null && ar.showToCurrentUser();
                    }
                    return false;
                }, currentTask, false /*includeBoundary*/, true /*traverseTopToBottom*/);
                final ActivityRecord tmpPre = prevTask != null
                        ? prevTask.getTopNonFinishingActivity() : null;
                if (tmpPre != null) {
                    prevActivities.add(tmpPre);
                    findAdjacentActivityIfExist(tmpPre, prevActivities);
                }
                if (prevTask == null || prevActivities.isEmpty()
                        || (isOccluded && !isAllActivitiesCanShowWhenLocked(prevActivities))) {
                    backType = BackNavigationInfo.TYPE_CALLBACK;
                } else if (prevTask.isActivityTypeHome()) {
                    removedWindowContainer = currentTask;
                    backType = BackNavigationInfo.TYPE_RETURN_TO_HOME;
                    mShowWallpaper = true;
                } else {
                    // If it reaches the top activity, we will check the below task from parent.
                    // If it's null or multi-window and has different parent task, fallback the type
                    // to TYPE_CALLBACK. Or set the type to proper value when it's return to home or
                    // another task.
                    final Task prevParent = prevTask.getParent().asTask();
                    final Task currParent = currentTask.getParent().asTask();
                    if (prevTask.inMultiWindowMode() && prevParent != currParent) {
                        backType = BackNavigationInfo.TYPE_CALLBACK;
                    } else {
                        removedWindowContainer = prevTask;
                        backType = BackNavigationInfo.TYPE_CROSS_TASK;
                    }
                }
            }
            infoBuilder.setType(backType);

            ProtoLog.d(WM_DEBUG_BACK_PREVIEW, "Previous Destination is Activity:%s Task:%s "
                            + "removedContainer:%s, backType=%s",
                    prevActivities.size() > 0 ? TextUtils.join(";", prevActivities.stream()
                            .map(r -> r.mActivityComponent).toArray()) : null,
                    prevTask != null ? prevTask.getName() : null,
                    removedWindowContainer,
                    BackNavigationInfo.typeToString(backType));

            // For now, we only animate when going home, cross task or cross-activity.
            boolean prepareAnimation =
                    (backType == BackNavigationInfo.TYPE_RETURN_TO_HOME
                                    || backType == BackNavigationInfo.TYPE_CROSS_TASK
                                    || backType == BackNavigationInfo.TYPE_CROSS_ACTIVITY
                                    || backType == BackNavigationInfo.TYPE_DIALOG_CLOSE)
                            && adapter != null;

            if (prepareAnimation) {
                final AnimationHandler.ScheduleAnimationBuilder builder =
                        mAnimationHandler.prepareAnimation(
                                backType,
                                adapter,
                                currentTask,
                                prevTask,
                                currentActivity,
                                prevActivities,
                                removedWindowContainer);
                mBackAnimationInProgress = builder != null;
                if (mBackAnimationInProgress) {
                    if (removedWindowContainer.hasCommittedReparentToAnimationLeash()
                            || removedWindowContainer.mTransitionController.inTransition()
                            || mWindowManagerService.mSyncEngine.hasPendingSyncSets()) {
                        ProtoLog.w(WM_DEBUG_BACK_PREVIEW,
                                "Pending back animation due to another animation is running");
                        mPendingAnimationBuilder = builder;
                        // Current transition is still running, we have to defer the hiding to the
                        // client process to prevent the unexpected relayout when handling the back
                        // animation.
                        for (int i = prevActivities.size() - 1; i >= 0; --i) {
                            prevActivities.get(i).setDeferHidingClient(true);
                        }
                    } else {
                        scheduleAnimation(builder);
                    }
                }
            }
            infoBuilder.setPrepareRemoteAnimation(prepareAnimation);

            if (removedWindowContainer != null) {
                final int finalBackType = backType;
                final RemoteCallback onBackNavigationDone = new RemoteCallback(result ->
                        onBackNavigationDone(result, finalBackType));
                infoBuilder.setOnBackNavigationDone(onBackNavigationDone);
            } else {
                mNavigationMonitor.stopMonitorForRemote();
            }
            mLastBackType = backType;
            return infoBuilder.build();
        }
    }

    /**
     * Gets previous activities from currentActivity.
     *
     * @return false if unable to predict what will happen
     */
    @VisibleForTesting
    static boolean getAnimatablePrevActivities(@NonNull Task currentTask,
            @NonNull ActivityRecord currentActivity,
            @NonNull ArrayList<ActivityRecord> outPrevActivities) {
        if (currentActivity.mAtmService
                .mTaskOrganizerController.shouldInterceptBackPressedOnRootTask(
                        currentTask.getRootTask())) {
            // The task organizer will handle back pressed, don't play animation.
            return false;
        }
        final ActivityRecord root = currentTask.getRootActivity(false /*ignoreRelinquishIdentity*/,
                true /*setToBottomIfNone*/);
        if (root != null && ActivityClientController.shouldMoveTaskToBack(currentActivity, root)) {
            return true;
        }

        // Searching previous
        final ActivityRecord prevActivity = currentTask.getActivity((below) -> !below.finishing,
                currentActivity, false /*includeBoundary*/, true /*traverseTopToBottom*/);

        final TaskFragment currTF = currentActivity.getTaskFragment();
        if (currTF != null && currTF.asTask() == null) {
            // The currentActivity is embedded, search for the candidate previous activities.
            if (prevActivity != null && currTF.hasChild(prevActivity)) {
                // PrevActivity is under the same task fragment, that's it.
                outPrevActivities.add(prevActivity);
                return true;
            }
            if (currTF.getAdjacentTaskFragment() != null) {
                // The two TFs are adjacent (visually displayed side-by-side), search if any
                // activity below the lowest one
                // If companion, those two TF will be closed together.
                if (currTF.getCompanionTaskFragment() != null) {
                    final WindowContainer commonParent = currTF.getParent();
                    final TaskFragment adjacentTF = currTF.getAdjacentTaskFragment();
                    final TaskFragment lowerTF = commonParent.mChildren.indexOf(currTF)
                            < commonParent.mChildren.indexOf(adjacentTF)
                            ? currTF : adjacentTF;
                    final ActivityRecord lowerActivity = lowerTF.getTopNonFinishingActivity();
                    // TODO (b/274997067) close currTF + companionTF, open next activities if any.
                    // Allow to predict next task if no more activity in task. Or return previous
                    // activities for cross-activity animation.
                    return currentTask.getActivity((below) -> !below.finishing, lowerActivity,
                            false /*includeBoundary*/, true /*traverseTopToBottom*/) == null;
                }
                // Unable to predict if no companion, it can only close current activity and make
                // prev Activity full screened.
                return false;
            } else if (currTF.getCompanionTaskFragment() != null) {
                // TF is isStacked, search bottom activity from companion TF.
                //
                // Sample hierarchy: search for underPrevious if any.
                //     Current TF
                //     Companion TF (bottomActivityInCompanion)
                //     Bottom Activity not inside companion TF (underPrevious)
                final TaskFragment companionTF = currTF.getCompanionTaskFragment();
                // find bottom activity in Companion TF.
                final ActivityRecord bottomActivityInCompanion = companionTF.getActivity(
                        (below) -> !below.finishing, false /* traverseTopToBottom */);
                final ActivityRecord underPrevious = currentTask.getActivity(
                        (below) -> !below.finishing, bottomActivityInCompanion,
                        false /*includeBoundary*/, true /*traverseTopToBottom*/);
                if (underPrevious != null) {
                    outPrevActivities.add(underPrevious);
                    addPreviousAdjacentActivityIfExist(underPrevious, outPrevActivities);
                }
                return true;
            }
        }

        if (prevActivity == null) {
            // No previous activity in this Task nor TaskFragment, it can still predict if previous
            // task exists.
            return true;
        }
        // Add possible adjacent activity if prevActivity is embedded
        addPreviousAdjacentActivityIfExist(prevActivity, outPrevActivities);
        outPrevActivities.add(prevActivity);
        return true;
    }

    private static void addPreviousAdjacentActivityIfExist(@NonNull ActivityRecord prevActivity,
            @NonNull ArrayList<ActivityRecord> outPrevActivities) {
        final TaskFragment prevTF = prevActivity.getTaskFragment();
        if (prevTF == null || prevTF.asTask() != null) {
            return;
        }

        final TaskFragment prevTFAdjacent = prevTF.getAdjacentTaskFragment();
        if (prevTFAdjacent == null || prevTFAdjacent.asTask() != null) {
            return;
        }
        final ActivityRecord prevActivityAdjacent =
                prevTFAdjacent.getTopNonFinishingActivity();
        if (prevActivityAdjacent != null) {
            outPrevActivities.add(prevActivityAdjacent);
        }
    }

    private static void findAdjacentActivityIfExist(@NonNull ActivityRecord mainActivity,
            @NonNull ArrayList<ActivityRecord> outList) {
        final TaskFragment mainTF = mainActivity.getTaskFragment();
        if (mainTF == null || mainTF.getAdjacentTaskFragment() == null) {
            return;
        }
        final TaskFragment adjacentTF = mainTF.getAdjacentTaskFragment();
        final ActivityRecord topActivity = adjacentTF.getTopNonFinishingActivity();
        if (topActivity == null) {
            return;
        }
        outList.add(topActivity);
    }

    private static boolean hasTranslucentActivity(@NonNull ActivityRecord currentActivity,
            @NonNull ArrayList<ActivityRecord> prevActivities) {
        if (!currentActivity.occludesParent() || currentActivity.showWallpaper()) {
            return true;
        }
        for (int i = prevActivities.size() - 1; i >= 0; --i) {
            final ActivityRecord test = prevActivities.get(i);
            if (!test.occludesParent() || test.showWallpaper()) {
                return true;
            }
        }
        return false;
    }

    private static boolean isAllActivitiesCanShowWhenLocked(
            @NonNull ArrayList<ActivityRecord> prevActivities) {
        for (int i = prevActivities.size() - 1; i >= 0; --i) {
            if (!prevActivities.get(i).canShowWhenLocked()) {
                return false;
            }
        }
        return !prevActivities.isEmpty();
    }

    boolean isMonitoringTransition() {
        return mAnimationHandler.mComposed || mNavigationMonitor.isMonitorForRemote();
    }

    private void scheduleAnimation(@NonNull AnimationHandler.ScheduleAnimationBuilder builder) {
        mPendingAnimation = builder.build();
        mWindowManagerService.mWindowPlacerLocked.requestTraversal();
        if (mShowWallpaper) {
            mWindowManagerService.getDefaultDisplayContentLocked().mWallpaperController
                    .adjustWallpaperWindows();
        }
    }

    private boolean isWaitBackTransition() {
        return mAnimationHandler.mComposed && mAnimationHandler.mWaitTransition;
    }

    boolean isKeyguardOccluded(WindowState focusWindow) {
        final KeyguardController kc = mWindowManagerService.mAtmService.mKeyguardController;
        final int displayId = focusWindow.getDisplayId();
        return kc.isKeyguardOccluded(displayId);
    }

    /**
     * There are two ways to customize activity exit animation, one is to provide the
     * windowAnimationStyle by Activity#setTheme, another one is to set resId by
     * Window#setWindowAnimations.
     * Not all run-time customization methods can be checked from here, such as
     * overridePendingTransition, which the animation resource will be set just before the
     * transition is about to happen.
     */
    private static boolean isCustomizeExitAnimation(WindowState window) {
        // The default animation ResId is loaded from system package, so the result must match.
        if (Objects.equals(window.mAttrs.packageName, "android")) {
            return false;
        }
        if (window.mAttrs.windowAnimations != 0) {
            final TransitionAnimation transitionAnimation = window.getDisplayContent()
                    .mAppTransition.mTransitionAnimation;
            final int attr = com.android.internal.R.styleable
                    .WindowAnimation_activityCloseExitAnimation;
            final int appResId = transitionAnimation.getAnimationResId(
                    window.mAttrs, attr, TRANSIT_OLD_NONE);
            if (ResourceId.isValid(appResId)) {
                if (sDefaultAnimationResId == 0) {
                    sDefaultAnimationResId = transitionAnimation.getDefaultAnimationResId(attr,
                            TRANSIT_OLD_NONE);
                }
                return sDefaultAnimationResId != appResId;
            }
        }
        return false;
    }

    // For legacy transition.
    /**
     *  Once we find the transition targets match back animation targets, remove the target from
     *  list, so that transition won't count them in since the close animation was finished.
     *
     *  @return {@code true} if the participants of this transition was animated by back gesture
     *  animations, and shouldn't join next transition.
     */
    boolean removeIfContainsBackAnimationTargets(ArraySet<ActivityRecord> openApps,
            ArraySet<ActivityRecord> closeApps) {
        if (!isMonitoringTransition()) {
            return false;
        }
        mTmpCloseApps.addAll(closeApps);
        final boolean matchAnimationTargets = removeIfWaitForBackTransition(openApps, closeApps);
        if (!matchAnimationTargets) {
            mNavigationMonitor.onTransitionReadyWhileNavigate(mTmpOpenApps, mTmpCloseApps);
        }
        mTmpCloseApps.clear();
        return matchAnimationTargets;
    }

    boolean removeIfWaitForBackTransition(ArraySet<ActivityRecord> openApps,
            ArraySet<ActivityRecord> closeApps) {
        if (!isWaitBackTransition()) {
            return false;
        }
        // Note: TmpOpenApps is empty. Unlike shell transition, the open apps will be removed from
        // mOpeningApps if there is no visibility change.
        if (mAnimationHandler.containsBackAnimationTargets(mTmpOpenApps, mTmpCloseApps)) {
            // remove close target from close list, open target from open list;
            // but the open target can be in close list.
            for (int i = openApps.size() - 1; i >= 0; --i) {
                final ActivityRecord ar = openApps.valueAt(i);
                if (mAnimationHandler.isTarget(ar, true /* open */)) {
                    openApps.removeAt(i);
                    mAnimationHandler.markStartingSurfaceMatch();
                }
            }
            for (int i = closeApps.size() - 1; i >= 0; --i) {
                final ActivityRecord ar = closeApps.valueAt(i);
                if (mAnimationHandler.isTarget(ar, false /* open */)) {
                    closeApps.removeAt(i);
                }
            }
            return true;
        }
        return false;
    }

    private class NavigationMonitor {
        // The window which triggering the back navigation.
        private WindowState mNavigatingWindow;
        private RemoteCallback mObserver;

        void startMonitor(@NonNull WindowState window, @NonNull RemoteCallback observer) {
            mNavigatingWindow = window;
            mObserver = observer;
        }

        void stopMonitorForRemote() {
            mObserver = null;
        }

        void stopMonitorTransition() {
            mNavigatingWindow = null;
        }

        boolean isMonitorForRemote() {
            return mNavigatingWindow != null && mObserver != null;
        }

        boolean isMonitorAnimationOrTransition() {
            return mNavigatingWindow != null
                    && (mAnimationHandler.mComposed || mAnimationHandler.mWaitTransition);
        }

        /**
         * Notify focus window changed during back navigation. This will cancel the gesture for
         * scenarios like: a system window popup, or when an activity add a new window.
         *
         * This method should only be used to check window-level change, otherwise it may cause
         * misjudgment in multi-window mode. For example: in split-screen, when user is
         * navigating on the top task, bottom task can start a new task, which will gain focus for
         * a short time, but we should not cancel the navigation.
         */
        private void onFocusWindowChanged(WindowState newFocus) {
            if (!atSameDisplay(newFocus)
                    || !(isMonitorForRemote() || isMonitorAnimationOrTransition())) {
                return;
            }
            // Keep navigating if either new focus == navigating window or null.
            if (newFocus != null && newFocus != mNavigatingWindow
                    && (newFocus.mActivityRecord == null
                    || (newFocus.mActivityRecord == mNavigatingWindow.mActivityRecord))) {
                cancelBackNavigating("focusWindowChanged");
            }
        }

        /**
         * Notify an unexpected transition has happened during back navigation.
         */
        private void onTransitionReadyWhileNavigate(ArrayList<WindowContainer> opening,
                ArrayList<WindowContainer> closing) {
            if (!isMonitorForRemote() && !isMonitorAnimationOrTransition()) {
                return;
            }
            final ArrayList<WindowContainer> all = new ArrayList<>(opening);
            all.addAll(closing);
            for (int i = all.size() - 1; i >= 0; --i) {
                if (all.get(i).hasChild(mNavigatingWindow)) {
                    cancelBackNavigating("transitionHappens");
                    break;
                }
            }
        }

        private boolean atSameDisplay(WindowState newFocus) {
            if (mNavigatingWindow == null) {
                return false;
            }
            final int navigatingDisplayId = mNavigatingWindow.getDisplayId();
            return newFocus == null || newFocus.getDisplayId() == navigatingDisplayId;
        }

        private void cancelBackNavigating(String reason) {
            EventLogTags.writeWmBackNaviCanceled(reason);
            if (isMonitorForRemote()) {
                mObserver.sendResult(null /* result */);
            }
            if (isMonitorAnimationOrTransition()) {
                clearBackAnimations();
            }
            cancelPendingAnimation();
        }
    }

    // For shell transition
    /**
     * Check whether the transition targets was animated by back gesture animation.
     * Because the opening target could request to do other stuff at onResume, so it could become
     * close target for a transition. So the condition here is
     * The closing target should only exist in close list, but the opening target can be either in
     * open or close list.
     */
    void onTransactionReady(Transition transition, ArrayList<Transition.ChangeInfo> targets,
            SurfaceControl.Transaction startTransaction) {
        if (!isMonitoringTransition() || targets.isEmpty()) {
            return;
        }
        for (int i = targets.size() - 1; i >= 0; --i) {
            final WindowContainer wc = targets.get(i).mContainer;
            if (wc.asActivityRecord() == null && wc.asTask() == null
                    && wc.asTaskFragment() == null) {
                continue;
            }
            // WC can be visible due to setLaunchBehind
            if (wc.isVisibleRequested()) {
                mTmpOpenApps.add(wc);
            } else {
                mTmpCloseApps.add(wc);
            }
        }
        final boolean matchAnimationTargets = isWaitBackTransition()
                && (transition.mType == TRANSIT_CLOSE || transition.mType == TRANSIT_TO_BACK)
                && mAnimationHandler.containsBackAnimationTargets(mTmpOpenApps, mTmpCloseApps);
        ProtoLog.d(WM_DEBUG_BACK_PREVIEW,
                "onTransactionReady, opening: %s, closing: %s, animating: %s, match: %b",
                mTmpOpenApps, mTmpCloseApps, mAnimationHandler, matchAnimationTargets);
        if (!matchAnimationTargets) {
            mNavigationMonitor.onTransitionReadyWhileNavigate(mTmpOpenApps, mTmpCloseApps);
        } else {
            if (mWaitTransitionFinish != null) {
                Slog.e(TAG, "Gesture animation is applied on another transition?");
            }
            mWaitTransitionFinish = transition;
            // Flag target matches to defer remove the splash screen.
            for (int i = mTmpOpenApps.size() - 1; i >= 0; --i) {
                final WindowContainer wc = mTmpOpenApps.get(i);
                if (mAnimationHandler.isTarget(wc, true /* open */)) {
                    mAnimationHandler.markStartingSurfaceMatch();
                    break;
                }
            }
            // Because the target will reparent to transition root, so it cannot be controlled by
            // animation leash. Hide the close target when transition starts.
            startTransaction.hide(mAnimationHandler.mCloseAdaptor.mTarget.getSurfaceControl());
        }
        mTmpOpenApps.clear();
        mTmpCloseApps.clear();
    }

    boolean isMonitorTransitionTarget(WindowContainer wc) {
        if (!isWaitBackTransition() || mWaitTransitionFinish == null) {
            return false;
        }
        return mAnimationHandler.isTarget(wc, wc.isVisibleRequested() /* open */);
    }

    /**
     * Cleanup animation, this can either happen when legacy transition ready, or when the Shell
     * transition finish.
     */
    void clearBackAnimations() {
        mAnimationHandler.clearBackAnimateTarget();
        mNavigationMonitor.stopMonitorTransition();
        mWaitTransitionFinish = null;
    }

    /**
     * Called when a transition finished.
     * Handle the pending animation when the running transition finished.
     * @param targets The final animation targets derived in transition.
     * @param finishedTransition The finished transition target.
    */
    boolean onTransitionFinish(ArrayList<Transition.ChangeInfo> targets,
            @NonNull Transition finishedTransition) {
        if (finishedTransition == mWaitTransitionFinish) {
            clearBackAnimations();
        }

        if (!mBackAnimationInProgress || mPendingAnimationBuilder == null) {
            return false;
        }
        ProtoLog.d(WM_DEBUG_BACK_PREVIEW,
                "Handling the deferred animation after transition finished");

        // Find the participated container collected by transition when :
        // Open transition -> the open target in back navigation, the close target in transition.
        // Close transition -> the close target in back navigation, the open target in transition.
        boolean hasTarget = false;
        for (int i = 0; i < finishedTransition.mParticipants.size(); i++) {
            final WindowContainer wc = finishedTransition.mParticipants.valueAt(i);
            if (wc.asActivityRecord() == null && wc.asTask() == null
                    && wc.asTaskFragment() == null) {
                continue;
            }

            if (mPendingAnimationBuilder.containTarget(wc)) {
                hasTarget = true;
                break;
            }
        }

        if (!hasTarget) {
            // Skip if no target participated in current finished transition.
            Slog.w(TAG, "Finished transition didn't include the targets"
                    + " open: " + Arrays.toString(mPendingAnimationBuilder.mOpenTargets)
                    + " close: " + mPendingAnimationBuilder.mCloseTarget);
            cancelPendingAnimation();
            return false;
        }

        // Ensure the final animation targets which hidden by transition could be visible.
        for (int i = 0; i < targets.size(); i++) {
            final WindowContainer wc = targets.get(i).mContainer;
            wc.prepareSurfaces();
        }

        scheduleAnimation(mPendingAnimationBuilder);
        mPendingAnimationBuilder = null;
        return true;
    }

    private void cancelPendingAnimation() {
        if (mPendingAnimationBuilder == null) {
            return;
        }
        try {
            mPendingAnimationBuilder.mBackAnimationAdapter.getRunner().onAnimationCancelled();
        } catch (RemoteException e) {
            Slog.e(TAG, "Remote animation gone", e);
        }
        mPendingAnimationBuilder = null;
    }

    /**
     * Create and handling animations status for an open/close animation targets.
     */
    static class AnimationHandler {
        private final boolean mShowWindowlessSurface;
        private final WindowManagerService mWindowManagerService;
        private BackWindowAnimationAdaptor mCloseAdaptor;
        private BackWindowAnimationAdaptorWrapper mOpenAnimAdaptor;
        private boolean mComposed;
        private boolean mWaitTransition;
        private int mSwitchType = UNKNOWN;

        // This will be set before transition happen, to know whether the real opening target
        // exactly match animating target. When target match, reparent the starting surface to
        // the opening target like starting window do.
        private boolean mStartingSurfaceTargetMatch;
        private ActivityRecord[] mOpenActivities;

        AnimationHandler(WindowManagerService wms) {
            mWindowManagerService = wms;
            final Context context = wms.mContext;
            mShowWindowlessSurface = context.getResources().getBoolean(
                    com.android.internal.R.bool.config_predictShowStartingSurface);
        }
        private static final int UNKNOWN = 0;
        private static final int TASK_SWITCH = 1;
        private static final int ACTIVITY_SWITCH = 2;
        private static final int DIALOG_CLOSE = 3;

        private static boolean isActivitySwitch(@NonNull WindowContainer close,
                @NonNull WindowContainer[] open) {
            if (open == null || open.length == 0 || close.asActivityRecord() == null) {
                return false;
            }
            final Task closeTask = close.asActivityRecord().getTask();
            for (int i = open.length - 1; i >= 0; --i) {
                if (open[i].asActivityRecord() == null
                        || (closeTask != open[i].asActivityRecord().getTask())) {
                    return false;
                }
            }
            return true;
        }

        private static boolean isTaskSwitch(@NonNull WindowContainer close,
                @NonNull WindowContainer[] open) {
            if (open == null || open.length != 1 || close.asTask() == null) {
                return false;
            }
            return open[0].asTask() != null && (close.asTask() != open[0].asTask());
        }

        private static boolean isDialogClose(WindowContainer close) {
            return close.asWindowState() != null;
        }

        private void initiate(@NonNull WindowContainer close, @NonNull WindowContainer[] open,
                @NonNull ActivityRecord[] openingActivities)  {
            if (isActivitySwitch(close, open)) {
                mSwitchType = ACTIVITY_SWITCH;
            } else if (isTaskSwitch(close, open)) {
                mSwitchType = TASK_SWITCH;
            } else if (isDialogClose(close)) {
                mSwitchType = DIALOG_CLOSE;
            } else {
                mSwitchType = UNKNOWN;
                return;
            }

            mCloseAdaptor = createAdaptor(close, false, mSwitchType);
            if (mCloseAdaptor.mAnimationTarget == null) {
                Slog.w(TAG, "composeNewAnimations fail, skip");
                clearBackAnimateTarget();
                return;
            }

            mOpenAnimAdaptor = new BackWindowAnimationAdaptorWrapper(true, mSwitchType, open);
            if (!mOpenAnimAdaptor.isValid()) {
                Slog.w(TAG, "compose animations fail, skip");
                clearBackAnimateTarget();
                return;
            }
            mOpenActivities = openingActivities;
        }

        private boolean composeAnimations(@NonNull WindowContainer close,
                @NonNull WindowContainer[] open, @NonNull ActivityRecord[] openingActivities) {
            if (mComposed || mWaitTransition) {
                Slog.e(TAG, "Previous animation is running " + this);
                return false;
            }
            clearBackAnimateTarget();
            if (close == null || open == null || open.length == 0 || open.length > 2) {
                Slog.e(TAG, "reset animation with null target close: "
                        + close + " open: " + Arrays.toString(open));
                return false;
            }
            initiate(close, open, openingActivities);
            if (mSwitchType == UNKNOWN) {
                return false;
            }
            mComposed = true;
            mWaitTransition = false;
            return true;
        }

        @Nullable RemoteAnimationTarget[] getAnimationTargets() {
            if (!mComposed) {
                return null;
            }
            final RemoteAnimationTarget[] targets = new RemoteAnimationTarget[2];
            targets[0] = mCloseAdaptor.mAnimationTarget;
            targets[1] = mOpenAnimAdaptor.getOrCreateAnimationTarget();
            return targets;
        }

        boolean isSupportWindowlessSurface() {
            return mWindowManagerService.mAtmService.mTaskOrganizerController
                    .isSupportWindowlessStartingSurface();
        }

        boolean containTarget(@NonNull ArrayList<WindowContainer> wcs, boolean open) {
            for (int i = wcs.size() - 1; i >= 0; --i) {
                if (isTarget(wcs.get(i), open)) {
                    return true;
                }
            }
            return wcs.isEmpty();
        }

        boolean isTarget(@NonNull WindowContainer wc, boolean open) {
            if (!mComposed) {
                return false;
            }
            if (open) {
                for (int i = mOpenAnimAdaptor.mAdaptors.length - 1; i >= 0; --i) {
                    if (isAnimateTarget(wc, mOpenAnimAdaptor.mAdaptors[i].mTarget, mSwitchType)) {
                        return true;
                    }
                }
                return false;
            }
            return isAnimateTarget(wc, mCloseAdaptor.mTarget, mSwitchType);
        }

        private static boolean isAnimateTarget(@NonNull WindowContainer window,
                @NonNull WindowContainer animationTarget, int switchType) {
            if (switchType == TASK_SWITCH) {
                // simplify home search for multiple hierarchy
                if (window.isActivityTypeHome() && animationTarget.isActivityTypeHome()) {
                    return true;
                }
                return  window == animationTarget
                        ||  (animationTarget.asTask() != null && animationTarget.hasChild(window))
                        || (animationTarget.asActivityRecord() != null
                        && window.hasChild(animationTarget));
            } else if (switchType == ACTIVITY_SWITCH) {
                return window == animationTarget
                        || (window.asTaskFragment() != null && window.hasChild(animationTarget));
            }
            return false;
        }

        void finishPresentAnimations() {
            if (!mComposed) {
                return;
            }

            if (mCloseAdaptor != null) {
                mCloseAdaptor.mTarget.cancelAnimation();
                mCloseAdaptor = null;
            }
            if (mOpenAnimAdaptor != null) {
                mOpenAnimAdaptor.cleanUp(mStartingSurfaceTargetMatch);
                mOpenAnimAdaptor = null;
            }

            if (mOpenActivities != null) {
                for (int i = mOpenActivities.length - 1; i >= 0; --i) {
                    if (mOpenActivities[i].mLaunchTaskBehind) {
                        restoreLaunchBehind(mOpenActivities[i]);
                    }
                }
            }
        }

        void markStartingSurfaceMatch() {
            mStartingSurfaceTargetMatch = true;
            for (int i = mOpenAnimAdaptor.mAdaptors.length - 1; i >= 0; --i) {
                mOpenAnimAdaptor.mAdaptors[i].reparentWindowlessSurfaceToTarget();
            }
        }

        void clearBackAnimateTarget() {
            finishPresentAnimations();
            mComposed = false;
            mWaitTransition = false;
            mStartingSurfaceTargetMatch = false;
            mSwitchType = UNKNOWN;
            mOpenActivities = null;
        }

        // The close target must in close list
        // The open target can either in close or open list
        boolean containsBackAnimationTargets(@NonNull ArrayList<WindowContainer> openApps,
                @NonNull ArrayList<WindowContainer> closeApps) {
            return containTarget(closeApps, false /* open */)
                    && (containTarget(openApps, true /* open */)
                    || containTarget(openApps, false /* open */));
        }

        @Override
        public String toString() {
            return "AnimationTargets{"
                    + " openTarget= "
                    + (mOpenAnimAdaptor != null ? dumpOpenAnimTargetsToString() : null)
                    + " closeTarget= "
                    + (mCloseAdaptor != null ? mCloseAdaptor.mTarget : null)
                    + " mSwitchType= "
                    + mSwitchType
                    + " mComposed= "
                    + mComposed
                    + " mWaitTransition= "
                    + mWaitTransition
                    + '}';
        }

        private String dumpOpenAnimTargetsToString() {
            final StringBuilder sb = new StringBuilder();
            sb.append("{");
            for (int i = 0; i < mOpenAnimAdaptor.mAdaptors.length; i++) {
                if (i > 0) {
                    sb.append(',');
                }
                sb.append(mOpenAnimAdaptor.mAdaptors[i].mTarget);
            }
            sb.append("}");
            return sb.toString();
        }

        @NonNull private static BackWindowAnimationAdaptor createAdaptor(
                @NonNull WindowContainer target, boolean isOpen, int switchType) {
            final BackWindowAnimationAdaptor adaptor =
                    new BackWindowAnimationAdaptor(target, isOpen, switchType);
            final SurfaceControl.Transaction pt = target.getPendingTransaction();
            target.startAnimation(pt, adaptor, false /* hidden */, ANIMATION_TYPE_PREDICT_BACK);
            // Workaround to show TaskFragment which can be hide in Transitions and won't show
            // during isAnimating.
            if (isOpen && target.asActivityRecord() != null) {
                final TaskFragment fragment = target.asActivityRecord().getTaskFragment();
                if (fragment != null) {
                    pt.show(fragment.mSurfaceControl);
                }
            }
            return adaptor;
        }

        private static class BackWindowAnimationAdaptorWrapper {
            final BackWindowAnimationAdaptor[] mAdaptors;
            SurfaceControl.Transaction mCloseTransaction;

            BackWindowAnimationAdaptorWrapper(boolean isOpen, int switchType,
                    @NonNull WindowContainer... targets) {
                mAdaptors = new BackWindowAnimationAdaptor[targets.length];
                for (int i = targets.length - 1; i >= 0; --i) {
                    mAdaptors[i] = createAdaptor(targets[i], isOpen, switchType);
                }
            }

            boolean isValid() {
                for (int i = mAdaptors.length - 1; i >= 0; --i) {
                    if (mAdaptors[i].mAnimationTarget == null) {
                        return false;
                    }
                }
                return true;
            }

            void cleanUp(boolean startingSurfaceMatch) {
                for (int i = mAdaptors.length - 1; i >= 0; --i) {
                    mAdaptors[i].cleanUpWindowlessSurface(startingSurfaceMatch);
                    mAdaptors[i].mTarget.cancelAnimation();
                }
                if (mCloseTransaction != null) {
                    mCloseTransaction.apply();
                    mCloseTransaction = null;
                }
            }

            void onAnimationFinish() {
                final SurfaceControl.Transaction pt = mAdaptors[0].mTarget.getPendingTransaction();
                if (mCloseTransaction != null) {
                    pt.merge(mCloseTransaction);
                    mCloseTransaction = null;
                }
                if (mAdaptors.length > 1) {
                    for (int i = mAdaptors.length - 1; i >= 0; --i) {
                        final WindowContainer wc = mAdaptors[i].mTarget;
                        final WindowContainer parent = wc.getParent();
                        if (parent != null) {
                            pt.reparent(wc.getSurfaceControl(),
                                    parent.getSurfaceControl());
                        }
                    }
                }
            }

            @NonNull RemoteAnimationTarget getOrCreateAnimationTarget() {
                // Special handle for opening two activities together.
                // If we animate both activities separately, the animation area and rounded corner
                // would also being handled separately. To make them seem like "open" together, wrap
                // their leash with another animation leash.
                if (mAdaptors.length > 1 && mCloseTransaction == null) {
                    final Rect unionBounds = new Rect();
                    for (int i = mAdaptors.length - 1; i >= 0; --i) {
                        unionBounds.union(mAdaptors[i].mAnimationTarget.localBounds);
                    }
                    final WindowContainer wc = mAdaptors[0].mTarget;
                    final Task task = wc.asActivityRecord() != null
                            ? wc.asActivityRecord().getTask() : wc.asTask();
                    final RemoteAnimationTarget represent = mAdaptors[0].mAnimationTarget;
                    final SurfaceControl leashSurface = new SurfaceControl.Builder()
                            .setName("cross-animation-leash")
                            .setContainerLayer()
                            .setHidden(false)
                            .setParent(task.getSurfaceControl())
                            .build();
                    final SurfaceControl.Transaction pt = wc.getPendingTransaction();
                    pt.setLayer(leashSurface, wc.getParent().getLastLayer());
                    mCloseTransaction = new SurfaceControl.Transaction();
                    mCloseTransaction.reparent(leashSurface, null);
                    for (int i = mAdaptors.length - 1; i >= 0; --i) {
                        BackWindowAnimationAdaptor adaptor = mAdaptors[i];
                        pt.reparent(adaptor.mAnimationTarget.leash, leashSurface);
                        pt.setPosition(adaptor.mAnimationTarget.leash,
                                adaptor.mAnimationTarget.localBounds.left,
                                adaptor.mAnimationTarget.localBounds.top);
                    }
                    return new RemoteAnimationTarget(represent.taskId, represent.mode, leashSurface,
                            represent.isTranslucent, represent.clipRect, represent.contentInsets,
                            represent.prefixOrderIndex,
                            new Point(unionBounds.left, unionBounds.top),
                            unionBounds, unionBounds, represent.windowConfiguration,
                            true /* isNotInRecents */, null, null, represent.taskInfo,
                            represent.allowEnterPip);
                } else {
                    return mAdaptors[0].mAnimationTarget;
                }
            }
        }

        private static class BackWindowAnimationAdaptor implements AnimationAdapter {
            SurfaceControl mCapturedLeash;
            private final Rect mBounds = new Rect();
            private final WindowContainer mTarget;
            private final boolean mIsOpen;
            private RemoteAnimationTarget mAnimationTarget;
            private final int mSwitchType;

            // The starting surface task Id. Used to clear the starting surface if the animation has
            // requested one during animating.
            private int mRequestedStartingSurfaceId = INVALID_TASK_ID;
            private SurfaceControl mStartingSurface;

            BackWindowAnimationAdaptor(@NonNull WindowContainer target, boolean isOpen,
                    int switchType) {
                mBounds.set(target.getBounds());
                mTarget = target;
                mIsOpen = isOpen;
                mSwitchType = switchType;
            }

            @Override
            public boolean getShowWallpaper() {
                return false;
            }

            @Override
            public void startAnimation(SurfaceControl animationLeash, SurfaceControl.Transaction t,
                    int type, SurfaceAnimator.OnAnimationFinishedCallback finishCallback) {
                mCapturedLeash = animationLeash;
                createRemoteAnimationTarget();
                final WindowState win = mTarget.asWindowState();
                if (win != null && mSwitchType == DIALOG_CLOSE) {
                    final Rect frame = win.getFrame();
                    final Point position = new Point();
                    win.transformFrameToSurfacePosition(frame.left, frame.top, position);
                    t.setPosition(mCapturedLeash, position.x, position.y);
                }
            }

            @Override
            public void onAnimationCancelled(SurfaceControl animationLeash) {
                if (mCapturedLeash == animationLeash) {
                    mCapturedLeash = null;
                    mRequestedStartingSurfaceId = INVALID_TASK_ID;
                    mStartingSurface = null;
                }
            }

            @Override
            public long getDurationHint() {
                return 0;
            }

            @Override
            public long getStatusBarTransitionsStartTime() {
                return 0;
            }

            @Override
            public void dump(PrintWriter pw, String prefix) {
                pw.print(prefix + "BackWindowAnimationAdaptor mCapturedLeash=");
                pw.print(mCapturedLeash);
                pw.println();
            }

            @Override
            public void dumpDebug(ProtoOutputStream proto) {

            }

            RemoteAnimationTarget createRemoteAnimationTarget() {
                if (mAnimationTarget != null) {
                    return mAnimationTarget;
                }

                WindowState w = mTarget.asWindowState();
                ActivityRecord r = w != null ? w.getActivityRecord() : null;
                Task t = r != null ? r.getTask() : mTarget.asTask();
                if (t == null && mTarget.asTaskFragment() != null) {
                    t = mTarget.asTaskFragment().getTask();
                    r = mTarget.asTaskFragment().getTopNonFinishingActivity();
                }
                if (r == null) {
                    r = t != null ? t.getTopNonFinishingActivity()
                            : mTarget.asActivityRecord();
                }
                if (t == null && r != null) {
                    t = r.getTask();
                }
                if (t == null || r == null) {
                    Slog.e(TAG, "createRemoteAnimationTarget fail " + mTarget);
                    return null;
                }
                final WindowState mainWindow = r.findMainWindow();
                Rect insets;
                if (mainWindow != null) {
                    insets = mainWindow.getInsetsStateWithVisibilityOverride().calculateInsets(
                            mBounds, WindowInsets.Type.tappableElement(),
                            false /* ignoreVisibility */).toRect();
                    InsetUtils.addInsets(insets, mainWindow.mActivityRecord.getLetterboxInsets());
                } else {
                    insets = new Rect();
                }
                final int mode = mIsOpen ? MODE_OPENING : MODE_CLOSING;
                mAnimationTarget = new RemoteAnimationTarget(t.mTaskId, mode, mCapturedLeash,
                        !r.fillsParent(), new Rect(),
                        insets, r.getPrefixOrderIndex(), new Point(mBounds.left, mBounds.top),
                        mBounds, mBounds, t.getWindowConfiguration(),
                        true /* isNotInRecents */, null, null, t.getTaskInfo(),
                        r.checkEnterPictureInPictureAppOpsState());
                return mAnimationTarget;
            }

            void createStartingSurface(@NonNull WindowContainer closeWindow,
                    @NonNull ActivityRecord[] visibleOpenActivities) {
                if (!mIsOpen) {
                    return;
                }
                if (mSwitchType == DIALOG_CLOSE) {
                    return;
                }
                final Task openTask = mSwitchType == TASK_SWITCH
                        ? mTarget.asTask() : mSwitchType == ACTIVITY_SWITCH
                        ? mTarget.asActivityRecord().getTask() : null;
                if (openTask == null) {
                    return;
                }
                final ActivityRecord mainActivity = mSwitchType == ACTIVITY_SWITCH
                        ? mTarget.asActivityRecord()
                        : openTask.getTopNonFinishingActivity();
                if (mainActivity == null) {
                    return;
                }
                final TaskSnapshot snapshot = getSnapshot(mTarget, visibleOpenActivities);
                mRequestedStartingSurfaceId = openTask.mAtmService.mTaskOrganizerController
                        .addWindowlessStartingSurface(openTask, mainActivity,
                                // Choose configuration from closeWindow, because the configuration
                                // of opening target may not update before resume, so the starting
                                // surface should occlude it entirely.
                                mAnimationTarget.leash, snapshot, closeWindow.getConfiguration(),
                                new IWindowlessStartingSurfaceCallback.Stub() {
                            // Once the starting surface has been created in shell, it will call
                            // onSurfaceAdded to pass the created surface to core, so if a
                            // transition is triggered by the back gesture, there doesn't need to
                            // create another starting surface for the opening target, just reparent
                            // the starting surface to the opening target.
                            // Note if cleanUpWindowlessSurface happen prior than onSurfaceAdded
                            // called, there won't be able to reparent the starting surface on
                            // opening target. But if that happens and transition target is matched,
                            // the app window should already draw.
                                    @Override
                                    public void onSurfaceAdded(SurfaceControl sc) {
                                        synchronized (mTarget.mWmService.mGlobalLock) {
                                            if (mRequestedStartingSurfaceId != INVALID_TASK_ID) {
                                                mStartingSurface = sc;
                                            }
                                        }
                                    }
                                });
            }

            // When back gesture has triggered and transition target matches navigation target,
            // reparent the starting surface to the opening target as it's starting window.
            void reparentWindowlessSurfaceToTarget() {
                if (mRequestedStartingSurfaceId == INVALID_TASK_ID) {
                    return;
                }
                // If open target matches, reparent to open activity or task
                if (mStartingSurface != null && mStartingSurface.isValid()) {
                    mTarget.getPendingTransaction()
                            .reparent(mStartingSurface, mTarget.getSurfaceControl());
                    // remove starting surface.
                    mStartingSurface = null;
                }
            }

            /**
             * Ask shell to clear the starting surface.
             * @param openTransitionMatch if true, shell will play the remove starting window
             *                            animation, otherwise remove it directly.
             */
            void cleanUpWindowlessSurface(boolean openTransitionMatch) {
                if (mRequestedStartingSurfaceId == INVALID_TASK_ID) {
                    return;
                }
                mTarget.mWmService.mAtmService.mTaskOrganizerController
                        .removeWindowlessStartingSurface(mRequestedStartingSurfaceId,
                                !openTransitionMatch);
                mRequestedStartingSurfaceId = INVALID_TASK_ID;
            }
        }

        ScheduleAnimationBuilder prepareAnimation(
                int backType,
                BackAnimationAdapter adapter,
                Task currentTask,
                Task previousTask,
                ActivityRecord currentActivity,
                ArrayList<ActivityRecord> previousActivity,
                WindowContainer removedWindowContainer) {
            switch (backType) {
                case BackNavigationInfo.TYPE_RETURN_TO_HOME:
                    return new ScheduleAnimationBuilder(backType, adapter)
                            .setIsLaunchBehind(true)
                            .setComposeTarget(currentTask, previousTask);
                case BackNavigationInfo.TYPE_CROSS_ACTIVITY:
                    ActivityRecord[] prevActs = new ActivityRecord[previousActivity.size()];
                    prevActs = previousActivity.toArray(prevActs);
                    return new ScheduleAnimationBuilder(backType, adapter)
                            .setComposeTarget(currentActivity, prevActs)
                            .setIsLaunchBehind(false);
                case BackNavigationInfo.TYPE_CROSS_TASK:
                    return new ScheduleAnimationBuilder(backType, adapter)
                            .setComposeTarget(currentTask, previousTask)
                            .setIsLaunchBehind(false);
                case BackNavigationInfo.TYPE_DIALOG_CLOSE:
                    return new ScheduleAnimationBuilder(backType, adapter)
                            .setComposeTarget(removedWindowContainer, currentActivity)
                            .setIsLaunchBehind(false);
            }
            return null;
        }

        class ScheduleAnimationBuilder {
            final int mType;
            final BackAnimationAdapter mBackAnimationAdapter;
            WindowContainer mCloseTarget;
            WindowContainer[] mOpenTargets;
            boolean mIsLaunchBehind;

            ScheduleAnimationBuilder(int type, BackAnimationAdapter backAnimationAdapter) {
                mType = type;
                mBackAnimationAdapter = backAnimationAdapter;
            }

            ScheduleAnimationBuilder setComposeTarget(@NonNull WindowContainer close,
                    @NonNull WindowContainer... open) {
                mCloseTarget = close;
                mOpenTargets = open;
                return this;
            }

            ScheduleAnimationBuilder setIsLaunchBehind(boolean launchBehind) {
                mIsLaunchBehind = launchBehind;
                return this;
            }

            boolean containTarget(@NonNull WindowContainer wc) {
                if (mOpenTargets != null) {
                    for (int i = mOpenTargets.length - 1; i >= 0; --i) {
                        if (wc == mOpenTargets[i] || mOpenTargets[i].hasChild(wc)) {
                            return true;
                        }
                    }
                }
                return wc == mCloseTarget || mCloseTarget.hasChild(wc);
            }

            /**
             * Apply preview strategy on the opening target
             * @param closeWindow The close window, where it's configuration should cover all
             *                    open target(s).
             * @param openAnimationAdaptor The animator who can create starting surface.
             * @param visibleOpenActivities  The visible activities in opening targets.
             */
            private void applyPreviewStrategy(@NonNull WindowContainer closeWindow,
                    @NonNull BackWindowAnimationAdaptor[] openAnimationAdaptor,
                    @NonNull ActivityRecord[] visibleOpenActivities) {
                if (isSupportWindowlessSurface() && mShowWindowlessSurface && !mIsLaunchBehind
                        // TODO (b/274997067) Draw two snapshot in a single starting surface.
                        // We are using TaskId as the key of
                        // StartingSurfaceDrawer#StartingWindowRecordManager, so we cannot create
                        // two activity snapshot with WindowlessStartingWindow.
                        // Try to draw two snapshot within a WindowlessStartingWindow, or find
                        // another key for StartingWindowRecordManager.
                        && openAnimationAdaptor.length == 1) {
                    openAnimationAdaptor[0].createStartingSurface(closeWindow,
                            visibleOpenActivities);
                } else {
                    for (int i = visibleOpenActivities.length - 1; i >= 0; --i) {
                        setLaunchBehind(visibleOpenActivities[i]);
                    }
                }
            }

            @Nullable Runnable build() {
                if (mOpenTargets == null || mCloseTarget == null || mOpenTargets.length == 0) {
                    return null;
                }
                final boolean shouldLaunchBehind = mIsLaunchBehind || !isSupportWindowlessSurface();
                final ActivityRecord[] openingActivities = getTopOpenActivities(mOpenTargets);

                if (shouldLaunchBehind && openingActivities == null) {
                    Slog.e(TAG, "No opening activity");
                    return null;
                }

                if (!composeAnimations(mCloseTarget, mOpenTargets, openingActivities)) {
                    return null;
                }
                mCloseTarget.mTransitionController.mSnapshotController
                        .mActivitySnapshotController.clearOnBackPressedActivities();
                applyPreviewStrategy(mCloseTarget, mOpenAnimAdaptor.mAdaptors, openingActivities);

                final IBackAnimationFinishedCallback callback = makeAnimationFinishedCallback();
                final RemoteAnimationTarget[] targets = getAnimationTargets();

                return () -> {
                    try {
                        mBackAnimationAdapter.getRunner().onAnimationStart(
                                targets, null, null, callback);
                    } catch (RemoteException e) {
                        e.printStackTrace();
                    }
                };
            }

            private IBackAnimationFinishedCallback makeAnimationFinishedCallback() {
                return new IBackAnimationFinishedCallback.Stub() {
                    @Override
                    public void onAnimationFinished(boolean triggerBack) {
                        synchronized (mWindowManagerService.mGlobalLock) {
                            if (!mComposed) {
                                // animation was canceled
                                return;
                            }
                            mOpenAnimAdaptor.onAnimationFinish();
                            if (!triggerBack) {
                                clearBackAnimateTarget();
                            } else {
                                mWaitTransition = true;
                            }
                        }
                        // TODO Add timeout monitor if transition didn't happen
                    }
                };
            }
        }
    }

    /**
     * Finds next opening activity(ies) based on open targets, which could be:
     * 1. If the open window is Task, then the open activity can either be an activity, or
     * two activities inside two TaskFragments
     * 2. If the open window is Activity, then the open window can be an activity, or two
     * adjacent TaskFragments below it.
     */
    @Nullable
    private static ActivityRecord[] getTopOpenActivities(
            @NonNull WindowContainer[] openWindows) {
        ActivityRecord[] openActivities = null;
        final WindowContainer mainTarget = openWindows[0];
        if (mainTarget.asTask() != null) {
            final ArrayList<ActivityRecord> inTaskActivities = new ArrayList<>();
            final Task task = mainTarget.asTask();
            final ActivityRecord tmpPreActivity = task.getTopNonFinishingActivity();
            if (tmpPreActivity != null) {
                inTaskActivities.add(tmpPreActivity);
                findAdjacentActivityIfExist(tmpPreActivity, inTaskActivities);
            }

            openActivities = new ActivityRecord[inTaskActivities.size()];
            for (int i = inTaskActivities.size() - 1; i >= 0; --i) {
                openActivities[i] = inTaskActivities.get(i);
            }
        } else if (mainTarget.asActivityRecord() != null) {
            final int size = openWindows.length;
            openActivities = new ActivityRecord[size];
            for (int i = size - 1; i >= 0; --i) {
                openActivities[i] = openWindows[i].asActivityRecord();
            }
        }
        return openActivities;
    }

    private static void setLaunchBehind(@NonNull ActivityRecord activity) {
        if (!activity.isVisibleRequested()) {
            // The transition could commit the visibility and in the finishing state, that could
            // skip commitVisibility call in setVisibility cause the activity won't visible here.
            // Call it again to make sure the activity could be visible while handling the pending
            // animation.
            // Do not performLayout during prepare animation, because it could cause focus window
            // change. Let that happen after the BackNavigationInfo has returned to shell.
            activity.commitVisibility(true, false /* performLayout */);
            activity.mTransitionController.mSnapshotController
                    .mActivitySnapshotController.addOnBackPressedActivity(activity);
        }
        activity.mLaunchTaskBehind = true;

        // Handle fixed rotation launching app.
        final DisplayContent dc = activity.mDisplayContent;
        dc.rotateInDifferentOrientationIfNeeded(activity);
        if (activity.hasFixedRotationTransform()) {
            // Set the record so we can recognize it to continue to update display
            // orientation if the previous activity becomes the top later.
            dc.setFixedRotationLaunchingApp(activity,
                    activity.getWindowConfiguration().getRotation());
        }

        ProtoLog.d(WM_DEBUG_BACK_PREVIEW,
                "Setting Activity.mLauncherTaskBehind to true. Activity=%s", activity);
        activity.mTaskSupervisor.mStoppingActivities.remove(activity);
        activity.getDisplayContent().ensureActivitiesVisible(null /* starting */,
                true /* notifyClients */);
    }

    private static void restoreLaunchBehind(@NonNull ActivityRecord activity) {
        activity.mDisplayContent.continueUpdateOrientationForDiffOrienLaunchingApp();

        // Restore the launch-behind state.
        activity.mTaskSupervisor.scheduleLaunchTaskBehindComplete(activity.token);
        activity.mLaunchTaskBehind = false;
        // Ignore all change
        activity.mTransitionController.mSnapshotController
                .mActivitySnapshotController.clearOnBackPressedActivities();
        ProtoLog.d(WM_DEBUG_BACK_PREVIEW,
                "Setting Activity.mLauncherTaskBehind to false. Activity=%s",
                activity);
    }

    void checkAnimationReady(WallpaperController wallpaperController) {
        if (!mBackAnimationInProgress) {
            return;
        }

        final boolean wallpaperReady = !mShowWallpaper
                || (wallpaperController.getWallpaperTarget() != null
                && wallpaperController.wallpaperTransitionReady());
        if (wallpaperReady && mPendingAnimation != null) {
            startAnimation();
        }
    }

    void startAnimation() {
        if (!mBackAnimationInProgress) {
            // gesture is already finished, do not start animation
            if (mPendingAnimation != null) {
                clearBackAnimations();
                mPendingAnimation = null;
            }
            return;
        }
        if (mPendingAnimation != null) {
            mPendingAnimation.run();
            mPendingAnimation = null;
        }
    }

    private void onBackNavigationDone(Bundle result, int backType) {
        boolean triggerBack = result != null && result.getBoolean(
                BackNavigationInfo.KEY_TRIGGER_BACK);
        ProtoLog.d(WM_DEBUG_BACK_PREVIEW, "onBackNavigationDone backType=%s, "
                + "triggerBack=%b", backType, triggerBack);

        synchronized (mWindowManagerService.mGlobalLock) {
            mNavigationMonitor.stopMonitorForRemote();
            mBackAnimationInProgress = false;
            mShowWallpaper = false;
            // All animation should be done, clear any un-send animation.
            mPendingAnimation = null;
            mPendingAnimationBuilder = null;
        }
    }

    static TaskSnapshot getSnapshot(@NonNull WindowContainer w,
            ActivityRecord[] visibleOpenActivities) {
        if (w.asTask() != null) {
            final Task task = w.asTask();
            return task.mRootWindowContainer.mWindowManager.mTaskSnapshotController.getSnapshot(
                    task.mTaskId, task.mUserId, false /* restoreFromDisk */,
                    false /* isLowResolution */);
        }

        if (w.asActivityRecord() != null) {
            final ActivityRecord ar = w.asActivityRecord();
            return ar.mWmService.mSnapshotController.mActivitySnapshotController
                    .getSnapshot(visibleOpenActivities);
        }
        return null;
    }

    void setWindowManager(WindowManagerService wm) {
        mWindowManagerService = wm;
        mAnimationHandler = new AnimationHandler(wm);
    }

    boolean isWallpaperVisible(WindowState w) {
        return mAnimationHandler.mComposed && mShowWallpaper
                && w.mAttrs.type == TYPE_BASE_APPLICATION && w.mActivityRecord != null
                && mAnimationHandler.isTarget(w.mActivityRecord, true /* open */);
    }

    // Called from WindowManagerService to write to a protocol buffer output stream.
    void dumpDebug(ProtoOutputStream proto, long fieldId) {
        final long token = proto.start(fieldId);
        proto.write(ANIMATION_IN_PROGRESS, mBackAnimationInProgress);
        proto.write(LAST_BACK_TYPE, mLastBackType);
        proto.write(SHOW_WALLPAPER, mShowWallpaper);
        if (mAnimationHandler.mOpenAnimAdaptor != null
                && mAnimationHandler.mOpenAnimAdaptor.mAdaptors.length > 0) {
            mAnimationHandler.mOpenActivities[0].writeNameToProto(
                    proto, MAIN_OPEN_ACTIVITY);
        } else {
            proto.write(MAIN_OPEN_ACTIVITY, "");
        }
        // TODO (b/268563842) Only meaningful after new test added
        proto.write(ANIMATION_RUNNING, mAnimationHandler.mComposed
                || mAnimationHandler.mWaitTransition);
        proto.end(token);
    }
}
