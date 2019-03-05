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
import static android.app.AppOpsManager.OP_ASSIST_STRUCTURE;
import static android.app.AppOpsManager.OP_NONE;
import static android.app.WindowConfiguration.ACTIVITY_TYPE_HOME;
import static android.app.WindowConfiguration.ACTIVITY_TYPE_RECENTS;
import static android.app.WindowConfiguration.WINDOWING_MODE_UNDEFINED;
import static android.content.Intent.FLAG_ACTIVITY_NEW_TASK;
import static android.content.Intent.FLAG_ACTIVITY_NO_ANIMATION;
import static android.os.Trace.TRACE_TAG_ACTIVITY_MANAGER;
import static android.view.WindowManager.TRANSIT_NONE;

import static com.android.server.wm.ActivityStackSupervisor.PRESERVE_WINDOWS;
import static com.android.server.wm.RecentsAnimationController.REORDER_KEEP_IN_PLACE;
import static com.android.server.wm.RecentsAnimationController.REORDER_MOVE_TO_ORIGINAL_POSITION;
import static com.android.server.wm.RecentsAnimationController.REORDER_MOVE_TO_TOP;
import static com.android.server.wm.WindowManagerDebugConfig.DEBUG_RECENTS_ANIMATIONS;

import android.app.ActivityOptions;
import android.app.AppOpsManager;
import android.app.IAssistDataReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.IBinder;
import android.os.RemoteException;
import android.os.Trace;
import android.util.Slog;
import android.view.IRecentsAnimationRunner;

import com.android.server.LocalServices;
import com.android.server.am.AssistDataRequester;
import com.android.server.contentcapture.ContentCaptureManagerInternal;
import com.android.server.wm.RecentsAnimationController.RecentsAnimationCallbacks;

import java.util.List;

/**
 * Manages the recents animation, including the reordering of the stacks for the transition and
 * cleanup. See {@link com.android.server.wm.RecentsAnimationController}.
 */
class RecentsAnimation implements RecentsAnimationCallbacks,
        ActivityDisplay.OnStackOrderChangedListener {
    private static final String TAG = RecentsAnimation.class.getSimpleName();
    private static final boolean DEBUG = DEBUG_RECENTS_ANIMATIONS;

    private final ActivityTaskManagerService mService;
    private final ActivityStackSupervisor mStackSupervisor;
    private final ActivityStartController mActivityStartController;
    private final WindowManagerService mWindowManager;
    private final ActivityDisplay mDefaultDisplay;
    private final int mCallingPid;

    private int mTargetActivityType;
    private AssistDataRequester mAssistDataRequester;

    // The stack to restore the target stack behind when the animation is finished
    private ActivityStack mRestoreTargetBehindStack;

    RecentsAnimation(ActivityTaskManagerService atm, ActivityStackSupervisor stackSupervisor,
            ActivityStartController activityStartController, WindowManagerService wm,
            int callingPid) {
        mService = atm;
        mStackSupervisor = stackSupervisor;
        mDefaultDisplay = mService.mRootActivityContainer.getDefaultDisplay();
        mActivityStartController = activityStartController;
        mWindowManager = wm;
        mCallingPid = callingPid;
    }

    void startRecentsActivity(Intent intent, IRecentsAnimationRunner recentsAnimationRunner,
            ComponentName recentsComponent, int recentsUid,
            @Deprecated IAssistDataReceiver assistDataReceiver) {
        if (DEBUG) Slog.d(TAG, "startRecentsActivity(): intent=" + intent
                + " assistDataReceiver=" + assistDataReceiver);
        Trace.traceBegin(TRACE_TAG_ACTIVITY_MANAGER, "RecentsAnimation#startRecentsActivity");

        // TODO(multi-display) currently only support recents animation in default display.
        final DisplayContent dc =
                mService.mRootActivityContainer.getDefaultDisplay().mDisplayContent;
        if (!mWindowManager.canStartRecentsAnimation()) {
            notifyAnimationCancelBeforeStart(recentsAnimationRunner);
            if (DEBUG) Slog.d(TAG, "Can't start recents animation, nextAppTransition="
                        + dc.mAppTransition.getAppTransition());
            return;
        }

        // If the activity is associated with the recents stack, then try and get that first
        mTargetActivityType = intent.getComponent() != null
                && recentsComponent.equals(intent.getComponent())
                        ? ACTIVITY_TYPE_RECENTS
                        : ACTIVITY_TYPE_HOME;
        ActivityStack targetStack = mDefaultDisplay.getStack(WINDOWING_MODE_UNDEFINED,
                mTargetActivityType);
        ActivityRecord targetActivity = getTargetActivity(targetStack, intent.getComponent());
        final boolean hasExistingActivity = targetActivity != null;
        if (hasExistingActivity) {
            final ActivityDisplay display = targetActivity.getDisplay();
            mRestoreTargetBehindStack = display.getStackAbove(targetStack);
            if (mRestoreTargetBehindStack == null) {
                notifyAnimationCancelBeforeStart(recentsAnimationRunner);
                if (DEBUG) Slog.d(TAG, "No stack above target stack=" + targetStack);
                return;
            }
        }

        // Send launch hint if we are actually launching the target. If it's already visible
        // (shouldn't happen in general) we don't need to send it.
        if (targetActivity == null || !targetActivity.visible) {
            mService.mRootActivityContainer.sendPowerHintForLaunchStartIfNeeded(
                    true /* forceSend */, targetActivity);
        }

        mStackSupervisor.getActivityMetricsLogger().notifyActivityLaunching(intent);

        mService.mH.post(() -> mService.mAmInternal.setRunningRemoteAnimation(mCallingPid, true));

        mWindowManager.deferSurfaceLayout();
        try {
            // Kick off the assist data request in the background before showing the target activity
            requestAssistData(recentsComponent, recentsUid, assistDataReceiver);

            if (hasExistingActivity) {
                // Move the recents activity into place for the animation if it is not top most
                mDefaultDisplay.moveStackBehindBottomMostVisibleStack(targetStack);
                if (DEBUG) Slog.d(TAG, "Moved stack=" + targetStack + " behind stack="
                            + mDefaultDisplay.getStackAbove(targetStack));

                // If there are multiple tasks in the target stack (ie. the home stack, with 3p
                // and default launchers coexisting), then move the task to the top as a part of
                // moving the stack to the front
                if (targetStack.topTask() != targetActivity.getTaskRecord()) {
                    targetStack.addTask(targetActivity.getTaskRecord(), true /* toTop */,
                            "startRecentsActivity");
                }
            } else {
                // No recents activity, create the new recents activity bottom most
                ActivityOptions options = ActivityOptions.makeBasic();
                options.setLaunchActivityType(mTargetActivityType);
                options.setAvoidMoveToFront();
                intent.addFlags(FLAG_ACTIVITY_NEW_TASK | FLAG_ACTIVITY_NO_ANIMATION);

                mActivityStartController
                        .obtainStarter(intent, "startRecentsActivity_noTargetActivity")
                        .setCallingUid(recentsUid)
                        .setCallingPackage(recentsComponent.getPackageName())
                        .setActivityOptions(SafeActivityOptions.fromBundle(options.toBundle()))
                        .setMayWait(mService.getCurrentUserId())
                        .execute();

                // Move the recents activity into place for the animation
                targetActivity = mDefaultDisplay.getStack(WINDOWING_MODE_UNDEFINED,
                        mTargetActivityType).getTopActivity();
                targetStack = targetActivity.getActivityStack();
                mDefaultDisplay.moveStackBehindBottomMostVisibleStack(targetStack);
                if (DEBUG) {
                    Slog.d(TAG, "Moved stack=" + targetStack + " behind stack="
                            + mDefaultDisplay.getStackAbove(targetStack));
                }

                mWindowManager.prepareAppTransition(TRANSIT_NONE, false);
                mWindowManager.executeAppTransition();


                // TODO: Maybe wait for app to draw in this particular case?

                if (DEBUG) Slog.d(TAG, "Started intent=" + intent);
            }

            // Mark the target activity as launch-behind to bump its visibility for the
            // duration of the gesture that is driven by the recents component
            targetActivity.mLaunchTaskBehind = true;

            // Fetch all the surface controls and pass them to the client to get the animation
            // started. Cancel any existing recents animation running synchronously (do not hold the
            // WM lock)
            mWindowManager.cancelRecentsAnimationSynchronously(REORDER_MOVE_TO_ORIGINAL_POSITION,
                    "startRecentsActivity");
            mWindowManager.initializeRecentsAnimation(mTargetActivityType, recentsAnimationRunner,
                    this, mDefaultDisplay.mDisplayId,
                    mStackSupervisor.mRecentTasks.getRecentTaskIds());

            // If we updated the launch-behind state, update the visibility of the activities after
            // we fetch the visible tasks to be controlled by the animation
            mService.mRootActivityContainer.ensureActivitiesVisible(null, 0, PRESERVE_WINDOWS);

            mStackSupervisor.getActivityMetricsLogger().notifyActivityLaunched(START_TASK_TO_FRONT,
                    targetActivity);

            // Register for stack order changes
            mDefaultDisplay.registerStackOrderChangedListener(this);
        } catch (Exception e) {
            Slog.e(TAG, "Failed to start recents activity", e);
            throw e;
        } finally {
            mWindowManager.continueSurfaceLayout();
            Trace.traceEnd(TRACE_TAG_ACTIVITY_MANAGER);
        }
    }

    /**
     * Requests assist data for the top visible activities.
     */
    private void requestAssistData(ComponentName recentsComponent, int recentsUid,
            @Deprecated IAssistDataReceiver assistDataReceiver) {
        final AppOpsManager appOpsManager = (AppOpsManager)
                mService.mContext.getSystemService(Context.APP_OPS_SERVICE);
        final List<IBinder> topActivities =
                mService.mRootActivityContainer.getTopVisibleActivities();
        final AssistDataRequester.AssistDataRequesterCallbacks assistDataCallbacks;
        if (assistDataReceiver != null) {
            assistDataCallbacks = new AssistDataReceiverProxy(assistDataReceiver,
                    recentsComponent.getPackageName()) {
                @Override
                public void onAssistDataReceivedLocked(Bundle data, int activityIndex,
                        int activityCount) {
                    // Try to notify the intelligence service first
                    final ContentCaptureManagerInternal imService =
                            LocalServices.getService(ContentCaptureManagerInternal.class);
                    final IBinder activityToken = topActivities.get(activityIndex);
                    final ActivityRecord r = ActivityRecord.forTokenLocked(activityToken);
                    if (r != null && (imService == null
                            || !imService.sendActivityAssistData(r.mUserId, activityToken, data))) {
                        // Otherwise, use the provided assist data receiver
                        super.onAssistDataReceivedLocked(data, activityIndex, activityCount);
                    }
                }
            };
        } else {
            final ContentCaptureManagerInternal imService =
                    LocalServices.getService(ContentCaptureManagerInternal.class);
            if (imService == null) {
                // There is no intelligence service, so there is no point requesting assist data
                return;
            }

            assistDataCallbacks = new AssistDataRequester.AssistDataRequesterCallbacks() {
                @Override
                public boolean canHandleReceivedAssistDataLocked() {
                    return true;
                }

                @Override
                public void onAssistDataReceivedLocked(Bundle data, int activityIndex,
                        int activityCount) {
                    // Try to notify the intelligence service
                    final IBinder activityToken = topActivities.get(activityIndex);
                    final ActivityRecord r = ActivityRecord.forTokenLocked(activityToken);
                    if (r != null) {
                        imService.sendActivityAssistData(r.mUserId, activityToken, data);
                    }
                }
            };
        }
        mAssistDataRequester = new AssistDataRequester(mService.mContext, mWindowManager,
                appOpsManager, assistDataCallbacks, this, OP_ASSIST_STRUCTURE, OP_NONE);
        mAssistDataRequester.requestAutofillData(topActivities,
                recentsUid, recentsComponent.getPackageName());
    }

    private void finishAnimation(@RecentsAnimationController.ReorderMode int reorderMode) {
        synchronized (mService.mGlobalLock) {
            if (DEBUG) Slog.d(TAG, "onAnimationFinished(): controller="
                    + mWindowManager.getRecentsAnimationController()
                    + " reorderMode=" + reorderMode);

            // Cancel the associated assistant data request
            if (mAssistDataRequester != null) {
                mAssistDataRequester.cancel();
                mAssistDataRequester = null;
            }

            // Unregister for stack order changes
            mDefaultDisplay.unregisterStackOrderChangedListener(this);

            if (mWindowManager.getRecentsAnimationController() == null) return;

            // Just to be sure end the launch hint in case the target activity was never launched.
            // However, if we're keeping the activity and making it visible, we can leave it on.
            if (reorderMode != REORDER_KEEP_IN_PLACE) {
                mService.mRootActivityContainer.sendPowerHintForLaunchEndIfNeeded();
            }

            mService.mH.post(
                    () -> mService.mAmInternal.setRunningRemoteAnimation(mCallingPid, false));

            mWindowManager.inSurfaceTransaction(() -> {
                Trace.traceBegin(TRACE_TAG_ACTIVITY_MANAGER,
                        "RecentsAnimation#onAnimationFinished_inSurfaceTransaction");
                mWindowManager.deferSurfaceLayout();
                try {
                    mWindowManager.cleanupRecentsAnimation(reorderMode);

                    final ActivityStack targetStack = mDefaultDisplay.getStack(
                            WINDOWING_MODE_UNDEFINED, mTargetActivityType);
                    final ActivityRecord targetActivity = targetStack != null
                            ? targetStack.getTopActivity()
                            : null;
                    if (DEBUG) Slog.d(TAG, "onAnimationFinished(): targetStack=" + targetStack
                            + " targetActivity=" + targetActivity
                            + " mRestoreTargetBehindStack=" + mRestoreTargetBehindStack);
                    if (targetActivity == null) {
                        return;
                    }

                    // Restore the launched-behind state
                    targetActivity.mLaunchTaskBehind = false;

                    if (reorderMode == REORDER_MOVE_TO_TOP) {
                        // Bring the target stack to the front
                        mStackSupervisor.mNoAnimActivities.add(targetActivity);
                        targetStack.moveToFront("RecentsAnimation.onAnimationFinished()");
                        if (DEBUG) {
                            final ActivityStack topStack = getTopNonAlwaysOnTopStack();
                            if (topStack != targetStack) {
                                Slog.w(TAG, "Expected target stack=" + targetStack
                                        + " to be top most but found stack=" + topStack);
                            }
                        }
                    } else if (reorderMode == REORDER_MOVE_TO_ORIGINAL_POSITION){
                        // Restore the target stack to its previous position
                        final ActivityDisplay display = targetActivity.getDisplay();
                        display.moveStackBehindStack(targetStack, mRestoreTargetBehindStack);
                        if (DEBUG) {
                            final ActivityStack aboveTargetStack =
                                    mDefaultDisplay.getStackAbove(targetStack);
                            if (mRestoreTargetBehindStack != null
                                    && aboveTargetStack != mRestoreTargetBehindStack) {
                                Slog.w(TAG, "Expected target stack=" + targetStack
                                        + " to restored behind stack=" + mRestoreTargetBehindStack
                                        + " but it is behind stack=" + aboveTargetStack);
                            }
                        }
                    } else {
                        // Keep target stack in place, nothing changes, so ignore the transition
                        // logic below
                        return;
                    }

                    mWindowManager.prepareAppTransition(TRANSIT_NONE, false);
                    mService.mRootActivityContainer.ensureActivitiesVisible(null, 0, false);
                    mService.mRootActivityContainer.resumeFocusedStacksTopActivities();

                    // No reason to wait for the pausing activity in this case, as the hiding of
                    // surfaces needs to be done immediately.
                    mWindowManager.executeAppTransition();

                    // After reordering the stacks, reset the minimized state. At this point, either
                    // the target activity is now top-most and we will stay minimized (if in
                    // split-screen), or we will have returned to the app, and the minimized state
                    // should be reset
                    mWindowManager.checkSplitScreenMinimizedChanged(true /* animate */);
                } catch (Exception e) {
                    Slog.e(TAG, "Failed to clean up recents activity", e);
                    throw e;
                } finally {
                    mWindowManager.continueSurfaceLayout();
                    Trace.traceEnd(TRACE_TAG_ACTIVITY_MANAGER);
                }
            });
        }
    }

    @Override
    public void onAnimationFinished(@RecentsAnimationController.ReorderMode int reorderMode,
            boolean runSychronously) {
        if (runSychronously) {
            finishAnimation(reorderMode);
        } else {
            mService.mH.post(() -> finishAnimation(reorderMode));
        }
    }

    @Override
    public void onStackOrderChanged(ActivityStack stack) {
        if (DEBUG) Slog.d(TAG, "onStackOrderChanged(): stack=" + stack);
        if (mDefaultDisplay.getIndexOf(stack) == -1 || !stack.shouldBeVisible(null)) {
            // The stack is not visible, so ignore this change
            return;
        }
        final RecentsAnimationController controller =
                mWindowManager.getRecentsAnimationController();

        // Cancel running recents animation and screenshot previous task when the next
        // transition starts in below cases:
        // 1) The next launching task is not in recents animation task.
        // 2) The next task is home activity. (i.e. pressing home key to back home in recents).
        if ((!controller.isAnimatingTask(stack.getTaskStack().getTopChild())
                || controller.isTargetApp(stack.getTopActivity().mAppWindowToken))
                && controller.shouldCancelWithDeferredScreenshot()) {
            controller.cancelOnNextTransitionStart();
        } else {
            // Just cancel directly to unleash from launcher when the next launching task is the
            // current top task.
            mWindowManager.cancelRecentsAnimationSynchronously(REORDER_KEEP_IN_PLACE,
                    "stackOrderChanged");
        }
    }

    /**
     * Called only when the animation should be canceled prior to starting.
     */
    private void notifyAnimationCancelBeforeStart(IRecentsAnimationRunner recentsAnimationRunner) {
        try {
            recentsAnimationRunner.onAnimationCanceled(false /* deferredWithScreenshot */);
        } catch (RemoteException e) {
            Slog.e(TAG, "Failed to cancel recents animation before start", e);
        }
    }

    /**
     * @return The top stack that is not always-on-top.
     */
    private ActivityStack getTopNonAlwaysOnTopStack() {
        for (int i = mDefaultDisplay.getChildCount() - 1; i >= 0; i--) {
            final ActivityStack s = mDefaultDisplay.getChildAt(i);
            if (s.getWindowConfiguration().isAlwaysOnTop()) {
                continue;
            }
            return s;
        }
        return null;
    }

    /**
     * @return the top activity in the {@param targetStack} matching the {@param component}, or just
     * the top activity of the top task if no task matches the component.
     */
    private ActivityRecord getTargetActivity(ActivityStack targetStack, ComponentName component) {
        if (targetStack == null) {
            return null;
        }

        for (int i = targetStack.getChildCount() - 1; i >= 0; i--) {
            final TaskRecord task = targetStack.getChildAt(i);
            if (task.getBaseIntent().getComponent().equals(component)) {
                return task.getTopActivity();
            }
        }
        return targetStack.getTopActivity();
    }
}
