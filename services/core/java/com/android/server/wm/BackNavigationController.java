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
import static com.android.server.wm.BackNavigationProto.LAST_BACK_TYPE;
import static com.android.server.wm.BackNavigationProto.SHOW_WALLPAPER;
import static com.android.server.wm.SurfaceAnimator.ANIMATION_TYPE_PREDICT_BACK;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.content.Context;
import android.content.res.ResourceId;
import android.graphics.Point;
import android.graphics.Rect;
import android.os.Bundle;
import android.os.IBinder;
import android.os.RemoteCallback;
import android.os.RemoteException;
import android.os.SystemProperties;
import android.util.ArraySet;
import android.util.Slog;
import android.util.proto.ProtoOutputStream;
import android.view.RemoteAnimationTarget;
import android.view.SurfaceControl;
import android.view.WindowInsets;
import android.window.BackAnimationAdapter;
import android.window.BackNavigationInfo;
import android.window.IBackAnimationFinishedCallback;
import android.window.OnBackInvokedCallbackInfo;
import android.window.TaskSnapshot;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.policy.TransitionAnimation;
import com.android.internal.protolog.common.ProtoLog;
import com.android.server.LocalServices;
import com.android.server.wm.utils.InsetUtils;

import java.io.PrintWriter;
import java.util.ArrayList;
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

        // The previous activity we're going back to. This can be either a child of currentTask
        // if there are more than one Activity in currentTask, or a child of prevTask, if
        // currentActivity is the last child of currentTask.
        ActivityRecord prevActivity;
        WindowContainer<?> removedWindowContainer = null;
        WindowState window;

        BackNavigationInfo.Builder infoBuilder = new BackNavigationInfo.Builder();
        synchronized (wmService.mGlobalLock) {
            if (isMonitoringTransition()) {
                Slog.w(TAG, "Previous animation hasn't finish, status: " + mAnimationHandler);
                // Don't start any animation for it.
                return null;
            }
            WindowManagerInternal windowManagerInternal =
                    LocalServices.getService(WindowManagerInternal.class);
            IBinder focusedWindowToken = windowManagerInternal.getFocusedWindowToken();

            window = wmService.getFocusedWindowLocked();

            if (window == null) {
                EmbeddedWindowController.EmbeddedWindow embeddedWindow =
                        wmService.mEmbeddedWindowController.getByFocusToken(focusedWindowToken);
                if (embeddedWindow != null) {
                    ProtoLog.d(WM_DEBUG_BACK_PREVIEW,
                            "Current focused window is embeddedWindow. Dispatch KEYCODE_BACK.");
                    return null;
                }
            }

            // Lets first gather the states of things
            //  - What is our current window ?
            //  - Does it has an Activity and a Task ?
            // TODO Temp workaround for Sysui until b/221071505 is fixed
            if (window != null) {
                ProtoLog.d(WM_DEBUG_BACK_PREVIEW,
                        "Focused window found using getFocusedWindowToken");
            }

            if (window != null) {
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
                final ActivityRecord ar = window.mActivityRecord;
                if ((ar != null && ar.isActivityTypeHomeOrRecents()
                        && ar.mTransitionController.isTransientLaunch(ar))
                        || (recentsAnimationController != null
                        && recentsAnimationController.shouldApplyInputConsumer(ar))) {
                    ProtoLog.d(WM_DEBUG_BACK_PREVIEW, "Current focused window being animated by "
                            + "recents. Overriding back callback to recents controller callback.");
                    return null;
                }

                if (!window.isDrawn()) {
                    ProtoLog.d(WM_DEBUG_BACK_PREVIEW,
                            "Focused window didn't have a valid surface drawn.");
                    return null;
                }
            }

            if (window == null) {
                // We don't have any focused window, fallback ont the top currentTask of the focused
                // display.
                ProtoLog.w(WM_DEBUG_BACK_PREVIEW,
                        "No focused window, defaulting to top current task's window");
                currentTask = wmService.mAtmService.getTopDisplayFocusedRootTask();
                window = currentTask.getWindow(WindowState::isFocused);
            }

            // Now let's find if this window has a callback from the client side.
            OnBackInvokedCallbackInfo callbackInfo = null;
            if (window != null) {
                currentActivity = window.mActivityRecord;
                currentTask = window.getTask();
                callbackInfo = window.getOnBackInvokedCallbackInfo();
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
            }

            ProtoLog.d(WM_DEBUG_BACK_PREVIEW, "startBackNavigation currentTask=%s, "
                            + "topRunningActivity=%s, callbackInfo=%s, currentFocus=%s",
                    currentTask, currentActivity, callbackInfo, window);

            if (window == null) {
                Slog.e(TAG, "Window is null, returning null.");
                return null;
            }

            // If we don't need to set up the animation, we return early. This is the case when
            // - We have an application callback.
            // - We don't have any ActivityRecord or Task to animate.
            // - The IME is opened, and we just need to close it.
            // - The home activity is the focused activity.
            // - The current activity will do shared element transition when exiting.
            if (backType == BackNavigationInfo.TYPE_CALLBACK
                    || currentActivity == null
                    || currentTask == null
                    || currentActivity.isActivityTypeHome()
                    || currentActivity.mHasSceneTransition) {
                infoBuilder.setType(BackNavigationInfo.TYPE_CALLBACK);
                infoBuilder.setOnBackNavigationDone(new RemoteCallback(result ->
                        onBackNavigationDone(result, BackNavigationInfo.TYPE_CALLBACK)));
                mLastBackType = BackNavigationInfo.TYPE_CALLBACK;
                return infoBuilder.build();
            }

            // We don't have an application callback, let's find the destination of the back gesture
            // The search logic should align with ActivityClientController#finishActivity
            prevActivity = currentTask.topRunningActivity(currentActivity.token, INVALID_TASK_ID);
            final boolean isOccluded = isKeyguardOccluded(window);
            // TODO Dialog window does not need to attach on activity, check
            // window.mAttrs.type != TYPE_BASE_APPLICATION
            if ((window.getParent().getChildCount() > 1
                    && window.getParent().getChildAt(0) != window)) {
                // Are we the top window of our parent? If not, we are a window on top of the
                // activity, we won't close the activity.
                backType = BackNavigationInfo.TYPE_DIALOG_CLOSE;
                removedWindowContainer = window;
            } else if (prevActivity != null) {
                if (!isOccluded || prevActivity.canShowWhenLocked()) {
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
                    prevTask = prevActivity.getTask();
                    backType = BackNavigationInfo.TYPE_CROSS_ACTIVITY;
                } else {
                    backType = BackNavigationInfo.TYPE_CALLBACK;
                }
            } else if (currentTask.returnsToHomeRootTask()) {
                if (isOccluded) {
                    backType = BackNavigationInfo.TYPE_CALLBACK;
                } else {
                    // Our Task should bring back to home
                    removedWindowContainer = currentTask;
                    prevTask = currentTask.getDisplayArea().getRootHomeTask();
                    backType = BackNavigationInfo.TYPE_RETURN_TO_HOME;
                    mShowWallpaper = true;
                }
            } else if (currentActivity.isRootOfTask()) {
                // TODO(208789724): Create single source of truth for this, maybe in
                //  RootWindowContainer
                prevTask = currentTask.mRootWindowContainer.getTask(Task::showToCurrentUser,
                        currentTask, false /*includeBoundary*/, true /*traverseTopToBottom*/);
                removedWindowContainer = currentTask;
                // If it reaches the top activity, we will check the below task from parent.
                // If it's null or multi-window, fallback the type to TYPE_CALLBACK.
                // or set the type to proper value when it's return to home or another task.
                if (prevTask == null || prevTask.inMultiWindowMode()) {
                    backType = BackNavigationInfo.TYPE_CALLBACK;
                } else {
                    prevActivity = prevTask.getTopNonFinishingActivity();
                    if (prevActivity == null || (isOccluded && !prevActivity.canShowWhenLocked())) {
                        backType = BackNavigationInfo.TYPE_CALLBACK;
                    } else if (prevTask.isActivityTypeHome()) {
                        backType = BackNavigationInfo.TYPE_RETURN_TO_HOME;
                        mShowWallpaper = true;
                    } else {
                        backType = BackNavigationInfo.TYPE_CROSS_TASK;
                    }
                }
            }
            infoBuilder.setType(backType);

            ProtoLog.d(WM_DEBUG_BACK_PREVIEW, "Previous Destination is Activity:%s Task:%s "
                            + "removedContainer:%s, backType=%s",
                    prevActivity != null ? prevActivity.mActivityComponent : null,
                    prevTask != null ? prevTask.getName() : null,
                    removedWindowContainer,
                    BackNavigationInfo.typeToString(backType));

            // For now, we only animate when going home, cross task or cross-activity.
            boolean prepareAnimation =
                    (backType == BackNavigationInfo.TYPE_RETURN_TO_HOME
                            || backType == BackNavigationInfo.TYPE_CROSS_TASK
                            || backType == BackNavigationInfo.TYPE_CROSS_ACTIVITY)
                    && adapter != null;

            if (prepareAnimation) {
                final AnimationHandler.ScheduleAnimationBuilder builder =
                        mAnimationHandler.prepareAnimation(backType, adapter,
                                currentTask, prevTask, currentActivity, prevActivity);
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
                        if (prevActivity != null) {
                            prevActivity.setDeferHidingClient(true);
                        }
                    } else {
                        scheduleAnimation(builder);
                    }
                }
            }
            infoBuilder.setPrepareRemoteAnimation(prepareAnimation);
        } // Release wm Lock

        WindowContainer<?> finalRemovedWindowContainer = removedWindowContainer;
        if (finalRemovedWindowContainer != null) {
            final int finalBackType = backType;
            RemoteCallback onBackNavigationDone = new RemoteCallback(result -> onBackNavigationDone(
                    result, finalBackType));
            infoBuilder.setOnBackNavigationDone(onBackNavigationDone);
        }
        mLastBackType = backType;
        return infoBuilder.build();
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
        return kc.isKeyguardLocked(displayId) && kc.isDisplayOccluded(displayId);
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
                    mAnimationHandler.mOpenTransitionTargetMatch = true;
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
    void onTransactionReady(Transition transition, ArrayList<Transition.ChangeInfo> targets) {
        if (!isMonitoringTransition()) {
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
                    + " open: " + mPendingAnimationBuilder.mOpenTarget
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
        private BackWindowAnimationAdaptor mOpenAdaptor;
        private boolean mComposed;
        private boolean mWaitTransition;
        private int mSwitchType = UNKNOWN;

        // This will be set before transition happen, to know whether the real opening target
        // exactly match animating target. When target match, reparent the starting surface to
        // the opening target like starting window do.
        private boolean mOpenTransitionTargetMatch;
        // The starting surface task Id. Used to clear the starting surface if the animation has
        // request one during animating.
        private int mRequestedStartingSurfaceTaskId;
        private SurfaceControl mStartingSurface;
        private ActivityRecord mOpenActivity;

        AnimationHandler(WindowManagerService wms) {
            mWindowManagerService = wms;
            final Context context = wms.mContext;
            mShowWindowlessSurface = context.getResources().getBoolean(
                    com.android.internal.R.bool.config_predictShowStartingSurface);
        }
        private static final int UNKNOWN = 0;
        private static final int TASK_SWITCH = 1;
        private static final int ACTIVITY_SWITCH = 2;

        private static boolean isActivitySwitch(WindowContainer close, WindowContainer open) {
            if (close.asActivityRecord() == null || open.asActivityRecord() == null
                    || (close.asActivityRecord().getTask()
                    != open.asActivityRecord().getTask())) {
                return false;
            }
            return true;
        }

        private static boolean isTaskSwitch(WindowContainer close, WindowContainer open) {
            if (close.asTask() == null || open.asTask() == null
                    || (close.asTask() == open.asTask())) {
                return false;
            }
            return true;
        }

        private void initiate(WindowContainer close, WindowContainer open,
                ActivityRecord openActivity)  {
            WindowContainer closeTarget;
            if (isActivitySwitch(close, open)) {
                mSwitchType = ACTIVITY_SWITCH;
                closeTarget = close.asActivityRecord();
            } else if (isTaskSwitch(close, open)) {
                mSwitchType = TASK_SWITCH;
                closeTarget = close.asTask().getTopNonFinishingActivity();
            } else {
                mSwitchType = UNKNOWN;
                return;
            }

            mCloseAdaptor = createAdaptor(closeTarget, false /* isOpen */);
            mOpenAdaptor = createAdaptor(open, true /* isOpen */);
            mOpenActivity = openActivity;
            if (mCloseAdaptor.mAnimationTarget == null || mOpenAdaptor.mAnimationTarget == null) {
                Slog.w(TAG, "composeNewAnimations fail, skip");
                clearBackAnimateTarget();
            }
        }

        boolean composeAnimations(@NonNull WindowContainer close, @NonNull WindowContainer open,
                ActivityRecord openActivity) {
            if (mComposed || mWaitTransition) {
                Slog.e(TAG, "Previous animation is running " + this);
                return false;
            }
            clearBackAnimateTarget();
            if (close == null || open == null || openActivity == null) {
                Slog.e(TAG, "reset animation with null target close: "
                        + close + " open: " + open);
                return false;
            }
            initiate(close, open, openActivity);
            if (mSwitchType == UNKNOWN) {
                return false;
            }
            mComposed = true;
            mWaitTransition = false;
            return true;
        }

        RemoteAnimationTarget[] getAnimationTargets() {
            return mComposed ? new RemoteAnimationTarget[] {
                    mCloseAdaptor.mAnimationTarget, mOpenAdaptor.mAnimationTarget} : null;
        }

        boolean isSupportWindowlessSurface() {
            return mWindowManagerService.mAtmService.mTaskOrganizerController
                    .isSupportWindowlessStartingSurface();
        }

        void createStartingSurface(TaskSnapshot snapshot) {
            if (!mComposed) {
                return;
            }

            final ActivityRecord topActivity = getTopOpenActivity();
            if (topActivity == null) {
                Slog.e(TAG, "createStartingSurface fail, no open activity: " + this);
                return;
            }
            // TODO (b/257857570) draw snapshot by starting surface.
        }

        private ActivityRecord getTopOpenActivity() {
            if (mSwitchType == ACTIVITY_SWITCH) {
                return mOpenAdaptor.mTarget.asActivityRecord();
            } else if (mSwitchType == TASK_SWITCH) {
                return mOpenAdaptor.mTarget.asTask().getTopNonFinishingActivity();
            }
            return null;
        }

        boolean containTarget(ArrayList<WindowContainer> wcs, boolean open) {
            for (int i = wcs.size() - 1; i >= 0; --i) {
                if (isTarget(wcs.get(i), open)) {
                    return true;
                }
            }
            return wcs.isEmpty();
        }

        boolean isTarget(WindowContainer wc, boolean open) {
            if (!mComposed) {
                return false;
            }

            // WC must be ActivityRecord in legacy transition, but it also can be Task or
            // TaskFragment when using Shell transition.
            // Open target: Can be Task or ActivityRecord or TaskFragment
            // Close target: Limit to the top activity for now, to reduce the chance of misjudgment.
            final WindowContainer target = open ? mOpenAdaptor.mTarget : mCloseAdaptor.mTarget;
            if (mSwitchType == TASK_SWITCH) {
                return  wc == target
                        || (wc.asTask() != null && wc.hasChild(target))
                        || (wc.asActivityRecord() != null && target.hasChild(wc));
            } else if (mSwitchType == ACTIVITY_SWITCH) {
                return wc == target || (wc.asTaskFragment() != null && wc.hasChild(target));
            }
            return false;
        }

        void finishPresentAnimations() {
            if (!mComposed) {
                return;
            }
            cleanUpWindowlessSurface();

            if (mCloseAdaptor != null) {
                mCloseAdaptor.mTarget.cancelAnimation();
                mCloseAdaptor = null;
            }
            if (mOpenAdaptor != null) {
                mOpenAdaptor.mTarget.cancelAnimation();
                mOpenAdaptor = null;
            }
            if (mOpenActivity != null && mOpenActivity.mLaunchTaskBehind) {
                restoreLaunchBehind(mOpenActivity);
            }
        }

        private void cleanUpWindowlessSurface() {
            final ActivityRecord ar = getTopOpenActivity();
            if (ar == null) {
                Slog.w(TAG, "finishPresentAnimations without top activity: " + this);
            }
            final SurfaceControl.Transaction pendingT = ar != null ? ar.getPendingTransaction()
                    : mOpenAdaptor.mTarget.getPendingTransaction();
            // ensure open target is visible before cancel animation.
            mOpenTransitionTargetMatch &= ar != null;
            if (mOpenTransitionTargetMatch) {
                pendingT.show(ar.getSurfaceControl());
            }
            if (mRequestedStartingSurfaceTaskId != 0) {
                // If open target match, reparent to open activity
                if (mStartingSurface != null && mOpenTransitionTargetMatch) {
                    pendingT.reparent(mStartingSurface, ar.getSurfaceControl());
                }
                // remove starting surface.
                mStartingSurface = null;
                // TODO (b/257857570) draw snapshot by starting surface.
                mRequestedStartingSurfaceTaskId = 0;
            }
        }

        void clearBackAnimateTarget() {
            finishPresentAnimations();
            mComposed = false;
            mWaitTransition = false;
            mOpenTransitionTargetMatch = false;
            mRequestedStartingSurfaceTaskId = 0;
            mSwitchType = UNKNOWN;
            mOpenActivity = null;
        }

        // The close target must in close list
        // The open target can either in close or open list
        boolean containsBackAnimationTargets(ArrayList<WindowContainer> openApps,
                ArrayList<WindowContainer> closeApps) {
            return containTarget(closeApps, false /* open */)
                    && (containTarget(openApps, true /* open */)
                    || containTarget(openApps, false /* open */));
        }

        @Override
        public String toString() {
            return "AnimationTargets{"
                    + " openTarget= "
                    + (mOpenAdaptor != null ? mOpenAdaptor.mTarget : "null")
                    + " closeTarget= "
                    + (mCloseAdaptor != null ? mCloseAdaptor.mTarget : "null")
                    + " mSwitchType= "
                    + mSwitchType
                    + " mComposed= "
                    + mComposed
                    + " mWaitTransition= "
                    + mWaitTransition
                    + '}';
        }

        private static BackWindowAnimationAdaptor createAdaptor(
                WindowContainer target, boolean isOpen) {
            final BackWindowAnimationAdaptor adaptor =
                    new BackWindowAnimationAdaptor(target, isOpen);
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

        private static class BackWindowAnimationAdaptor implements AnimationAdapter {
            SurfaceControl mCapturedLeash;
            private final Rect mBounds = new Rect();
            private final WindowContainer mTarget;
            private final boolean mIsOpen;
            private RemoteAnimationTarget mAnimationTarget;

            BackWindowAnimationAdaptor(WindowContainer closeTarget, boolean isOpen) {
                mBounds.set(closeTarget.getBounds());
                mTarget = closeTarget;
                mIsOpen = isOpen;
            }
            @Override
            public boolean getShowWallpaper() {
                return false;
            }

            @Override
            public void startAnimation(SurfaceControl animationLeash, SurfaceControl.Transaction t,
                    int type, SurfaceAnimator.OnAnimationFinishedCallback finishCallback) {
                mCapturedLeash = animationLeash;
                createRemoteAnimationTarget(mIsOpen);
            }

            @Override
            public void onAnimationCancelled(SurfaceControl animationLeash) {
                if (mCapturedLeash == animationLeash) {
                    mCapturedLeash = null;
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

            RemoteAnimationTarget createRemoteAnimationTarget(boolean isOpen) {
                if (mAnimationTarget != null) {
                    return mAnimationTarget;
                }
                Task t = mTarget.asTask();
                final ActivityRecord r = t != null ? t.getTopNonFinishingActivity()
                        : mTarget.asActivityRecord();
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
                            mBounds, WindowInsets.Type.systemBars(),
                            false /* ignoreVisibility */).toRect();
                    InsetUtils.addInsets(insets, mainWindow.mActivityRecord.getLetterboxInsets());
                } else {
                    insets = new Rect();
                }
                final int mode = isOpen ? MODE_OPENING : MODE_CLOSING;
                mAnimationTarget = new RemoteAnimationTarget(t.mTaskId, mode, mCapturedLeash,
                        !r.fillsParent(), new Rect(),
                        insets, r.getPrefixOrderIndex(), new Point(mBounds.left, mBounds.top),
                        mBounds, mBounds, t.getWindowConfiguration(),
                        true /* isNotInRecents */, null, null, t.getTaskInfo(),
                        r.checkEnterPictureInPictureAppOpsState());
                return mAnimationTarget;
            }
        }

        ScheduleAnimationBuilder prepareAnimation(int backType, BackAnimationAdapter adapter,
                Task currentTask, Task previousTask, ActivityRecord currentActivity,
                ActivityRecord previousActivity) {
            switch (backType) {
                case BackNavigationInfo.TYPE_RETURN_TO_HOME:
                    return new ScheduleAnimationBuilder(backType, adapter)
                            .setIsLaunchBehind(true)
                            .setComposeTarget(currentTask, previousTask);
                case BackNavigationInfo.TYPE_CROSS_ACTIVITY:
                    return new ScheduleAnimationBuilder(backType, adapter)
                            .setComposeTarget(currentActivity, previousActivity)
                            .setIsLaunchBehind(false);
                case BackNavigationInfo.TYPE_CROSS_TASK:
                    return new ScheduleAnimationBuilder(backType, adapter)
                            .setComposeTarget(currentTask, previousTask)
                            .setIsLaunchBehind(false);
            }
            return null;
        }

        class ScheduleAnimationBuilder {
            final int mType;
            final BackAnimationAdapter mBackAnimationAdapter;
            WindowContainer mCloseTarget;
            WindowContainer mOpenTarget;
            boolean mIsLaunchBehind;

            ScheduleAnimationBuilder(int type, BackAnimationAdapter backAnimationAdapter) {
                mType = type;
                mBackAnimationAdapter = backAnimationAdapter;
            }

            ScheduleAnimationBuilder setComposeTarget(WindowContainer close, WindowContainer open) {
                mCloseTarget = close;
                mOpenTarget = open;
                return this;
            }

            ScheduleAnimationBuilder setIsLaunchBehind(boolean launchBehind) {
                mIsLaunchBehind = launchBehind;
                return this;
            }

            boolean containTarget(@NonNull WindowContainer wc) {
                return wc == mOpenTarget || wc == mCloseTarget
                        || mOpenTarget.hasChild(wc) || mCloseTarget.hasChild(wc);
            }

            /**
             * Apply preview strategy on the opening target
             * @param open The opening target.
             * @param visibleOpenActivity  The visible activity in opening target.
             * @return If the preview strategy is launch behind, returns the Activity that has
             *         launchBehind set, or null otherwise.
             */
            private void applyPreviewStrategy(WindowContainer open,
                    ActivityRecord visibleOpenActivity) {
                if (isSupportWindowlessSurface() && mShowWindowlessSurface && !mIsLaunchBehind) {
                    createStartingSurface(getSnapshot(open));
                    return;
                }
                setLaunchBehind(visibleOpenActivity);
            }

            Runnable build() {
                if (mOpenTarget == null || mCloseTarget == null) {
                    return null;
                }
                final ActivityRecord openActivity = mOpenTarget.asTask() != null
                                ? mOpenTarget.asTask().getTopNonFinishingActivity()
                                : mOpenTarget.asActivityRecord() != null
                                        ? mOpenTarget.asActivityRecord() : null;
                if (openActivity == null) {
                    Slog.e(TAG, "No opening activity");
                    return null;
                }

                if (!composeAnimations(mCloseTarget, mOpenTarget, openActivity)) {
                    return null;
                }
                applyPreviewStrategy(mOpenTarget, openActivity);

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

    private static void setLaunchBehind(@NonNull ActivityRecord activity) {
        if (!activity.isVisibleRequested()) {
            activity.setVisibility(true);
            // The transition could commit the visibility and in the finishing state, that could
            // skip commitVisibility call in setVisibility cause the activity won't visible here.
            // Call it again to make sure the activity could be visible while handling the pending
            // animation.
            activity.commitVisibility(true, true);
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
                0 /* configChanges */, false /* preserveWindows */, true);
    }

    private static void restoreLaunchBehind(@NonNull ActivityRecord activity) {
        activity.mDisplayContent.continueUpdateOrientationForDiffOrienLaunchingApp();

        // Restore the launch-behind state.
        activity.mTaskSupervisor.scheduleLaunchTaskBehindComplete(activity.token);
        activity.mLaunchTaskBehind = false;
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

        mNavigationMonitor.stopMonitorForRemote();
        mBackAnimationInProgress = false;
        mShowWallpaper = false;
        mPendingAnimationBuilder = null;
    }

    static TaskSnapshot getSnapshot(@NonNull WindowContainer w) {
        if (!isScreenshotEnabled()) {
            return null;
        }
        if (w.asTask() != null) {
            final Task task = w.asTask();
            return  task.mRootWindowContainer.mWindowManager.mTaskSnapshotController.getSnapshot(
                    task.mTaskId, task.mUserId, false /* restoreFromDisk */,
                    false /* isLowResolution */);
        }

        if (w.asActivityRecord() != null) {
            // TODO (b/259497289) return TaskSnapshot when feature complete.
            return null;
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
        proto.end(token);
    }
}
