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
 * limitations under the License.
 */

package com.android.server.wm;

import static android.app.ActivityManager.START_TASK_TO_FRONT;
import static android.app.WindowConfiguration.ACTIVITY_TYPE_HOME;
import static android.app.WindowConfiguration.ACTIVITY_TYPE_RECENTS;
import static android.app.WindowConfiguration.WINDOWING_MODE_UNDEFINED;
import static android.content.Intent.FLAG_ACTIVITY_NEW_TASK;
import static android.content.Intent.FLAG_ACTIVITY_NO_ANIMATION;
import static android.os.Trace.TRACE_TAG_WINDOW_MANAGER;

import static com.android.internal.protolog.ProtoLogGroup.WM_DEBUG_RECENTS_ANIMATIONS;
import static com.android.server.wm.ActivityTaskSupervisor.PRESERVE_WINDOWS;
import static com.android.server.wm.RecentsAnimationController.REORDER_KEEP_IN_PLACE;
import static com.android.server.wm.RecentsAnimationController.REORDER_MOVE_TO_ORIGINAL_POSITION;
import static com.android.server.wm.RecentsAnimationController.REORDER_MOVE_TO_TOP;
import static com.android.server.wm.TaskDisplayArea.getRootTaskAbove;

import android.annotation.Nullable;
import android.app.ActivityOptions;
import android.content.ComponentName;
import android.content.Intent;
import android.os.RemoteException;
import android.os.Trace;
import android.util.Slog;
import android.view.IRecentsAnimationRunner;

import com.android.internal.protolog.common.ProtoLog;
import com.android.internal.util.function.pooled.PooledLambda;
import com.android.internal.util.function.pooled.PooledPredicate;
import com.android.server.wm.ActivityMetricsLogger.LaunchingState;
import com.android.server.wm.RecentsAnimationController.RecentsAnimationCallbacks;
import com.android.server.wm.TaskDisplayArea.OnRootTaskOrderChangedListener;

/**
 * Manages the recents animation, including the reordering of the root tasks for the transition and
 * cleanup. See {@link com.android.server.wm.RecentsAnimationController}.
 */
class RecentsAnimation implements RecentsAnimationCallbacks, OnRootTaskOrderChangedListener {
    private static final String TAG = RecentsAnimation.class.getSimpleName();

    private final ActivityTaskManagerService mService;
    private final ActivityTaskSupervisor mTaskSupervisor;
    private final ActivityStartController mActivityStartController;
    private final WindowManagerService mWindowManager;
    private final TaskDisplayArea mDefaultTaskDisplayArea;
    private final Intent mTargetIntent;
    private final ComponentName mRecentsComponent;
    private final @Nullable String mRecentsFeatureId;
    private final int mRecentsUid;
    private final @Nullable WindowProcessController mCaller;
    private final int mUserId;
    private final int mTargetActivityType;

    /**
     * The activity which has been launched behind. We need to remember the activity because the
     * target root task may have other activities, then we are able to restore the launch-behind
     * state for the exact activity.
     */
    private ActivityRecord mLaunchedTargetActivity;

    // The root task to restore the target root task behind when the animation is finished
    private Task mRestoreTargetBehindRootTask;

    RecentsAnimation(ActivityTaskManagerService atm, ActivityTaskSupervisor taskSupervisor,
            ActivityStartController activityStartController, WindowManagerService wm,
            Intent targetIntent, ComponentName recentsComponent, @Nullable String recentsFeatureId,
            int recentsUid, @Nullable WindowProcessController caller) {
        mService = atm;
        mTaskSupervisor = taskSupervisor;
        mDefaultTaskDisplayArea = mService.mRootWindowContainer.getDefaultTaskDisplayArea();
        mActivityStartController = activityStartController;
        mWindowManager = wm;
        mTargetIntent = targetIntent;
        mRecentsComponent = recentsComponent;
        mRecentsFeatureId = recentsFeatureId;
        mRecentsUid = recentsUid;
        mCaller = caller;
        mUserId = atm.getCurrentUserId();
        mTargetActivityType = targetIntent.getComponent() != null
                && recentsComponent.equals(targetIntent.getComponent())
                        ? ACTIVITY_TYPE_RECENTS
                        : ACTIVITY_TYPE_HOME;
    }

    /**
     * Starts the recents activity in background without animation if the record doesn't exist or
     * the client isn't launched. If the recents activity is already alive, ensure its configuration
     * is updated to the current one.
     */
    void preloadRecentsActivity() {
        ProtoLog.d(WM_DEBUG_RECENTS_ANIMATIONS, "Preload recents with %s",
                mTargetIntent);
        Task targetRootTask = mDefaultTaskDisplayArea.getRootTask(WINDOWING_MODE_UNDEFINED,
                mTargetActivityType);
        ActivityRecord targetActivity = getTargetActivity(targetRootTask);
        if (targetActivity != null) {
            if (targetActivity.mVisibleRequested || targetActivity.isTopRunningActivity()) {
                // The activity is ready.
                return;
            }
            if (targetActivity.attachedToProcess()) {
                // The activity may be relaunched if it cannot handle the current configuration
                // changes. The activity will be paused state if it is relaunched, otherwise it
                // keeps the original stopped state.
                targetActivity.ensureActivityConfiguration(0 /* globalChanges */,
                        false /* preserveWindow */, true /* ignoreVisibility */);
                ProtoLog.d(WM_DEBUG_RECENTS_ANIMATIONS, "Updated config=%s",
                        targetActivity.getConfiguration());
            }
        } else {
            // Create the activity record. Because the activity is invisible, this doesn't really
            // start the client.
            startRecentsActivityInBackground("preloadRecents");
            targetRootTask = mDefaultTaskDisplayArea.getRootTask(WINDOWING_MODE_UNDEFINED,
                    mTargetActivityType);
            targetActivity = getTargetActivity(targetRootTask);
            if (targetActivity == null) {
                Slog.w(TAG, "Cannot start " + mTargetIntent);
                return;
            }
        }

        if (!targetActivity.attachedToProcess()) {
            ProtoLog.d(WM_DEBUG_RECENTS_ANIMATIONS, "Real start recents");
            mTaskSupervisor.startSpecificActivity(targetActivity, false /* andResume */,
                    false /* checkConfig */);
            // Make sure the activity won't be involved in transition.
            if (targetActivity.getDisplayContent() != null) {
                targetActivity.getDisplayContent().mUnknownAppVisibilityController
                        .appRemovedOrHidden(targetActivity);
            }
        }

        // Invisible activity should be stopped. If the recents activity is alive and its doesn't
        // need to relaunch by current configuration, then it may be already in stopped state.
        if (!targetActivity.isState(Task.ActivityState.STOPPING,
                Task.ActivityState.STOPPED)) {
            // Add to stopping instead of stop immediately. So the client has the chance to perform
            // traversal in non-stopped state (ViewRootImpl.mStopped) that would initialize more
            // things (e.g. the measure can be done earlier). The actual stop will be performed when
            // it reports idle.
            targetActivity.addToStopping(true /* scheduleIdle */, true /* idleDelayed */,
                    "preloadRecents");
        }
    }

    void startRecentsActivity(IRecentsAnimationRunner recentsAnimationRunner, long eventTime) {
        ProtoLog.d(WM_DEBUG_RECENTS_ANIMATIONS, "startRecentsActivity(): intent=%s", mTargetIntent);
        Trace.traceBegin(TRACE_TAG_WINDOW_MANAGER, "RecentsAnimation#startRecentsActivity");

        // If the activity is associated with the root recents task, then try and get that first
        Task targetRootTask = mDefaultTaskDisplayArea.getRootTask(WINDOWING_MODE_UNDEFINED,
                mTargetActivityType);
        ActivityRecord targetActivity = getTargetActivity(targetRootTask);
        final boolean hasExistingActivity = targetActivity != null;
        if (hasExistingActivity) {
            mRestoreTargetBehindRootTask = getRootTaskAbove(targetRootTask);
            if (mRestoreTargetBehindRootTask == null
                    && targetRootTask.getTopMostTask() == targetActivity.getTask()) {
                notifyAnimationCancelBeforeStart(recentsAnimationRunner);
                ProtoLog.d(WM_DEBUG_RECENTS_ANIMATIONS,
                        "No root task above target root task=%s", targetRootTask);
                return;
            }
        }

        // Send launch hint if we are actually launching the target. If it's already visible
        // (shouldn't happen in general) we don't need to send it.
        if (targetActivity == null || !targetActivity.mVisibleRequested) {
            mService.mRootWindowContainer.startPowerModeLaunchIfNeeded(
                    true /* forceSend */, targetActivity);
        }

        final LaunchingState launchingState =
                mTaskSupervisor.getActivityMetricsLogger().notifyActivityLaunching(mTargetIntent);

        if (mCaller != null) {
            mCaller.setRunningRecentsAnimation(true);
        }

        mService.deferWindowLayout();
        try {
            if (hasExistingActivity) {
                // Move the recents activity into place for the animation if it is not top most
                mDefaultTaskDisplayArea.moveRootTaskBehindBottomMostVisibleRootTask(targetRootTask);
                ProtoLog.d(WM_DEBUG_RECENTS_ANIMATIONS, "Moved rootTask=%s behind rootTask=%s",
                        targetRootTask, getRootTaskAbove(targetRootTask));

                // If there are multiple tasks in the target root task (ie. the root home task,
                // with 3p and default launchers coexisting), then move the task to the top as a
                // part of moving the root task to the front
                final Task task = targetActivity.getTask();
                if (targetRootTask.getTopMostTask() != task) {
                    targetRootTask.positionChildAtTop(task);
                }
            } else {
                // No recents activity, create the new recents activity bottom most
                startRecentsActivityInBackground("startRecentsActivity_noTargetActivity");

                // Move the recents activity into place for the animation
                targetRootTask = mDefaultTaskDisplayArea.getRootTask(WINDOWING_MODE_UNDEFINED,
                        mTargetActivityType);
                targetActivity = getTargetActivity(targetRootTask);
                mDefaultTaskDisplayArea.moveRootTaskBehindBottomMostVisibleRootTask(targetRootTask);
                ProtoLog.d(WM_DEBUG_RECENTS_ANIMATIONS, "Moved rootTask=%s behind rootTask=%s",
                        targetRootTask, getRootTaskAbove(targetRootTask));

                mWindowManager.prepareAppTransitionNone();
                mWindowManager.executeAppTransition();

                // TODO: Maybe wait for app to draw in this particular case?

                ProtoLog.d(WM_DEBUG_RECENTS_ANIMATIONS, "Started intent=%s", mTargetIntent);
            }

            // Mark the target activity as launch-behind to bump its visibility for the
            // duration of the gesture that is driven by the recents component
            targetActivity.mLaunchTaskBehind = true;
            mLaunchedTargetActivity = targetActivity;
            // TODO(b/156772625): Evaluate to send new intents vs. replacing the intent extras.
            targetActivity.intent.replaceExtras(mTargetIntent);

            // Fetch all the surface controls and pass them to the client to get the animation
            // started. Cancel any existing recents animation running synchronously (do not hold the
            // WM lock)
            if (mWindowManager.getRecentsAnimationController() != null) {
                mWindowManager.getRecentsAnimationController().forceCancelAnimation(
                        REORDER_MOVE_TO_ORIGINAL_POSITION, "startRecentsActivity");
            }
            mWindowManager.initializeRecentsAnimation(mTargetActivityType, recentsAnimationRunner,
                    this, mDefaultTaskDisplayArea.getDisplayId(),
                    mTaskSupervisor.mRecentTasks.getRecentTaskIds(), targetActivity);

            // If we updated the launch-behind state, update the visibility of the activities after
            // we fetch the visible tasks to be controlled by the animation
            mService.mRootWindowContainer.ensureActivitiesVisible(null, 0, PRESERVE_WINDOWS);

            ActivityOptions options = null;
            if (eventTime > 0) {
                options = ActivityOptions.makeBasic();
                options.setSourceInfo(ActivityOptions.SourceInfo.TYPE_RECENTS_ANIMATION, eventTime);
            }
            mTaskSupervisor.getActivityMetricsLogger().notifyActivityLaunched(launchingState,
                    START_TASK_TO_FRONT, !hasExistingActivity, targetActivity, options);

            // Register for root task order changes
            mDefaultTaskDisplayArea.registerRootTaskOrderChangedListener(this);
        } catch (Exception e) {
            Slog.e(TAG, "Failed to start recents activity", e);
            throw e;
        } finally {
            mService.continueWindowLayout();
            Trace.traceEnd(TRACE_TAG_WINDOW_MANAGER);
        }
    }

    private void finishAnimation(@RecentsAnimationController.ReorderMode int reorderMode,
            boolean sendUserLeaveHint) {
        synchronized (mService.mGlobalLock) {
            ProtoLog.d(WM_DEBUG_RECENTS_ANIMATIONS,
                    "onAnimationFinished(): controller=%s reorderMode=%d",
                            mWindowManager.getRecentsAnimationController(), reorderMode);

            // Unregister for root task order changes
            mDefaultTaskDisplayArea.unregisterRootTaskOrderChangedListener(this);

            final RecentsAnimationController controller =
                    mWindowManager.getRecentsAnimationController();
            if (controller == null) return;

            // Just to be sure end the launch hint in case the target activity was never launched.
            // However, if we're keeping the activity and making it visible, we can leave it on.
            if (reorderMode != REORDER_KEEP_IN_PLACE) {
                mService.endLaunchPowerMode(
                        ActivityTaskManagerService.POWER_MODE_REASON_START_ACTIVITY);
            }

            // Once the target is shown, prevent spurious background app switches
            if (reorderMode == REORDER_MOVE_TO_TOP) {
                mService.stopAppSwitches();
            }

            if (mCaller != null) {
                mCaller.setRunningRecentsAnimation(false);
            }

            mWindowManager.inSurfaceTransaction(() -> {
                Trace.traceBegin(TRACE_TAG_WINDOW_MANAGER,
                        "RecentsAnimation#onAnimationFinished_inSurfaceTransaction");
                mService.deferWindowLayout();
                try {
                    mWindowManager.cleanupRecentsAnimation(reorderMode);

                    final Task targetRootTask = mDefaultTaskDisplayArea.getRootTask(
                            WINDOWING_MODE_UNDEFINED, mTargetActivityType);
                    // Prefer to use the original target activity instead of top activity because
                    // we may have moved another task to top (starting 3p launcher).
                    final ActivityRecord targetActivity = targetRootTask != null
                            ? targetRootTask.isInTask(mLaunchedTargetActivity)
                            : null;
                    ProtoLog.d(WM_DEBUG_RECENTS_ANIMATIONS,
                            "onAnimationFinished(): targetRootTask=%s targetActivity=%s "
                                    + "mRestoreTargetBehindRootTask=%s",
                            targetRootTask, targetActivity, mRestoreTargetBehindRootTask);
                    if (targetActivity == null) {
                        return;
                    }

                    // Restore the launched-behind state
                    targetActivity.mLaunchTaskBehind = false;

                    if (reorderMode == REORDER_MOVE_TO_TOP) {
                        // Bring the target root task to the front
                        mTaskSupervisor.mNoAnimActivities.add(targetActivity);

                        if (sendUserLeaveHint) {
                            // Setting this allows the previous app to PiP.
                            mTaskSupervisor.mUserLeaving = true;
                            targetRootTask.moveTaskToFront(targetActivity.getTask(),
                                    true /* noAnimation */, null /* activityOptions */,
                                    targetActivity.appTimeTracker,
                                    "RecentsAnimation.onAnimationFinished()");
                        } else {
                            targetRootTask.moveToFront("RecentsAnimation.onAnimationFinished()");
                        }

                        if (WM_DEBUG_RECENTS_ANIMATIONS.isLogToAny()) {
                            final Task topRootTask = getTopNonAlwaysOnTopRootTask();
                            if (topRootTask != targetRootTask) {
                                ProtoLog.w(WM_DEBUG_RECENTS_ANIMATIONS,
                                        "Expected target rootTask=%s"
                                        + " to be top most but found rootTask=%s",
                                        targetRootTask, topRootTask);
                            }
                        }
                    } else if (reorderMode == REORDER_MOVE_TO_ORIGINAL_POSITION){
                        // Restore the target root task to its previous position
                        final TaskDisplayArea taskDisplayArea = targetActivity.getDisplayArea();
                        taskDisplayArea.moveRootTaskBehindRootTask(targetRootTask,
                                mRestoreTargetBehindRootTask);
                        if (WM_DEBUG_RECENTS_ANIMATIONS.isLogToAny()) {
                            final Task aboveTargetRootTask = getRootTaskAbove(targetRootTask);
                            if (mRestoreTargetBehindRootTask != null
                                    && aboveTargetRootTask != mRestoreTargetBehindRootTask) {
                                ProtoLog.w(WM_DEBUG_RECENTS_ANIMATIONS,
                                        "Expected target rootTask=%s to restored behind "
                                                + "rootTask=%s but it is behind rootTask=%s",
                                        targetRootTask, mRestoreTargetBehindRootTask,
                                        aboveTargetRootTask);
                            }
                        }
                    } else {
                        // If there is no recents screenshot animation, we can update the visibility
                        // of target root task immediately because it is visually invisible and the
                        // launch-behind state is restored. That also prevents the next transition
                        // type being disturbed if the visibility is updated after setting the next
                        // transition (the target activity will be one of closing apps).
                        if (!controller.shouldDeferCancelWithScreenshot()
                                && !targetRootTask.isFocusedRootTaskOnDisplay()) {
                            targetRootTask.ensureActivitiesVisible(null /* starting */,
                                    0 /* starting */, false /* preserveWindows */);
                        }
                        // Keep target root task in place, nothing changes, so ignore the transition
                        // logic below
                        return;
                    }

                    mWindowManager.prepareAppTransitionNone();
                    mService.mRootWindowContainer.ensureActivitiesVisible(null, 0, false);
                    mService.mRootWindowContainer.resumeFocusedTasksTopActivities();

                    // No reason to wait for the pausing activity in this case, as the hiding of
                    // surfaces needs to be done immediately.
                    mWindowManager.executeAppTransition();

                    final Task rootTask = targetRootTask.getRootTask();
                    // Client state may have changed during the recents animation, so force
                    // send task info so the client can synchronize its state.
                    rootTask.dispatchTaskInfoChangedIfNeeded(true /* force */);
                } catch (Exception e) {
                    Slog.e(TAG, "Failed to clean up recents activity", e);
                    throw e;
                } finally {
                    mTaskSupervisor.mUserLeaving = false;
                    mService.continueWindowLayout();
                    // Make sure the surfaces are updated with the latest state. Sometimes the
                    // surface placement may be skipped if display configuration is changed (i.e.
                    // {@link DisplayContent#mWaitingForConfig} is true).
                    if (mWindowManager.mRoot.isLayoutNeeded()) {
                        mWindowManager.mRoot.performSurfacePlacement();
                    }
                    Trace.traceEnd(TRACE_TAG_WINDOW_MANAGER);
                }
            });
        }
    }

    @Override
    public void onAnimationFinished(@RecentsAnimationController.ReorderMode int reorderMode,
            boolean sendUserLeaveHint) {
        finishAnimation(reorderMode, sendUserLeaveHint);
    }

    @Override
    public void onRootTaskOrderChanged(Task rootTask) {
        ProtoLog.d(WM_DEBUG_RECENTS_ANIMATIONS, "onRootTaskOrderChanged(): rootTask=%s", rootTask);
        if (mDefaultTaskDisplayArea.getRootTask(t -> t == rootTask) == null
                || !rootTask.shouldBeVisible(null)) {
            // The root task is not visible, so ignore this change
            return;
        }
        final RecentsAnimationController controller =
                mWindowManager.getRecentsAnimationController();
        if (controller == null) {
            return;
        }

        // We defer canceling the recents animation until the next app transition in the following
        // cases:
        // 1) The next launching task is not being animated by the recents animation
        // 2) The next task is home activity. (i.e. pressing home key to back home in recents).
        if ((!controller.isAnimatingTask(rootTask.getTopMostTask())
                || controller.isTargetApp(rootTask.getTopNonFinishingActivity()))
                && controller.shouldDeferCancelUntilNextTransition()) {
            // Always prepare an app transition since we rely on the transition callbacks to cleanup
            mWindowManager.prepareAppTransitionNone();
            controller.setCancelOnNextTransitionStart();
        }
    }

    private void startRecentsActivityInBackground(String reason) {
        final ActivityOptions options = ActivityOptions.makeBasic();
        options.setLaunchActivityType(mTargetActivityType);
        options.setAvoidMoveToFront();
        mTargetIntent.addFlags(FLAG_ACTIVITY_NEW_TASK | FLAG_ACTIVITY_NO_ANIMATION);

        mActivityStartController
                .obtainStarter(mTargetIntent, reason)
                .setCallingUid(mRecentsUid)
                .setCallingPackage(mRecentsComponent.getPackageName())
                .setCallingFeatureId(mRecentsFeatureId)
                .setActivityOptions(new SafeActivityOptions(options))
                .setUserId(mUserId)
                .execute();
    }

    /**
     * Called only when the animation should be canceled prior to starting.
     */
    static void notifyAnimationCancelBeforeStart(IRecentsAnimationRunner recentsAnimationRunner) {
        try {
            recentsAnimationRunner.onAnimationCanceled(null /* taskSnapshot */);
        } catch (RemoteException e) {
            Slog.e(TAG, "Failed to cancel recents animation before start", e);
        }
    }

    /**
     * @return The top root task that is not always-on-top.
     */
    private Task getTopNonAlwaysOnTopRootTask() {
        return mDefaultTaskDisplayArea.getRootTask(task ->
                !task.getWindowConfiguration().isAlwaysOnTop());
    }

    /**
     * @return the top activity in the {@param targetRootTask} matching the {@param component},
     * or just the top activity of the top task if no task matches the component.
     */
    private ActivityRecord getTargetActivity(Task targetRootTask) {
        if (targetRootTask == null) {
            return null;
        }

        final PooledPredicate p = PooledLambda.obtainPredicate(RecentsAnimation::matchesTarget,
                this, PooledLambda.__(Task.class));
        final Task task = targetRootTask.getTask(p);
        p.recycle();
        return task != null ? task.getTopNonFinishingActivity() : null;
    }

    private boolean matchesTarget(Task task) {
        return task.mUserId == mUserId
                && task.getBaseIntent().getComponent().equals(mTargetIntent.getComponent());
    }
}
