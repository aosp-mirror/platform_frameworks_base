/*
 * Copyright (C) 2019 The Android Open Source Project
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

import static com.android.server.wm.ActivityStack.TAG_ADD_REMOVE;
import static com.android.server.wm.ActivityStack.TAG_TASKS;
import static com.android.server.wm.ActivityTaskManagerDebugConfig.DEBUG_ADD_REMOVE;
import static com.android.server.wm.ActivityTaskManagerDebugConfig.DEBUG_TASKS;

import android.app.ActivityOptions;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.os.Debug;
import android.util.Slog;

import com.android.internal.util.function.pooled.PooledConsumer;
import com.android.internal.util.function.pooled.PooledFunction;
import com.android.internal.util.function.pooled.PooledLambda;

import java.util.ArrayList;

/** Helper class for processing the reset of a task. */
class ResetTargetTaskHelper {
    private Task mTask;
    private Task mTargetTask;
    private ActivityStack mTargetStack;
    private ActivityRecord mRoot;
    private boolean mForceReset;
    private boolean mCanMoveOptions;
    private boolean mTargetTaskFound;
    private int mActivityReparentPosition;
    private ActivityOptions mTopOptions;
    private ArrayList<ActivityRecord> mResultActivities = new ArrayList<>();
    private ArrayList<ActivityRecord> mAllActivities = new ArrayList<>();
    private ArrayList<ActivityRecord> mPendingReparentActivities = new ArrayList<>();

    private void reset(Task task) {
        mTask = task;
        mRoot = null;
        mCanMoveOptions = true;
        mTopOptions = null;
        mResultActivities.clear();
        mAllActivities.clear();
    }

    ActivityOptions process(Task targetTask, boolean forceReset) {
        mForceReset = forceReset;
        mTargetTask = targetTask;
        mTargetTaskFound = false;
        mTargetStack = targetTask.getStack();
        mActivityReparentPosition = -1;

        final PooledConsumer c = PooledLambda.obtainConsumer(
                ResetTargetTaskHelper::processTask, this, PooledLambda.__(Task.class));
        targetTask.mWmService.mRoot.forAllLeafTasks(c, true /*traverseTopToBottom*/);
        c.recycle();

        processPendingReparentActivities();
        reset(null);
        return mTopOptions;
    }

    private void processTask(Task task) {
        reset(task);
        mRoot = task.getRootActivity(true);
        if (mRoot == null) return;

        final boolean isTargetTask = task == mTargetTask;
        if (isTargetTask) mTargetTaskFound = true;

        final PooledFunction f = PooledLambda.obtainFunction(
                ResetTargetTaskHelper::processActivity, this,
                PooledLambda.__(ActivityRecord.class), isTargetTask);
        task.forAllActivities(f);
        f.recycle();
    }

    private boolean processActivity(ActivityRecord r, boolean isTargetTask) {
        // End processing if we have reached the root.
        if (r == mRoot) return true;

        mAllActivities.add(r);
        final int flags = r.info.flags;
        final boolean finishOnTaskLaunch =
                (flags & ActivityInfo.FLAG_FINISH_ON_TASK_LAUNCH) != 0;
        final boolean allowTaskReparenting =
                (flags & ActivityInfo.FLAG_ALLOW_TASK_REPARENTING) != 0;
        final boolean clearWhenTaskReset =
                (r.intent.getFlags() & Intent.FLAG_ACTIVITY_CLEAR_WHEN_TASK_RESET) != 0;

        if (isTargetTask) {
            if (!finishOnTaskLaunch && !clearWhenTaskReset) {
                if (r.resultTo != null) {
                    // If this activity is sending a reply to a previous activity, we can't do
                    // anything with it now until we reach the start of the reply chain.
                    // NOTE: that we are assuming the result is always to the previous activity,
                    // which is almost always the case but we really shouldn't count on.
                    mResultActivities.add(r);
                    return false;
                }
                if (allowTaskReparenting && r.taskAffinity != null
                        && !r.taskAffinity.equals(mTask.affinity)) {
                    // If this activity has an affinity for another task, then we need to move
                    // it out of here. We will move it as far out of the way as possible, to the
                    // bottom of the activity stack. This also keeps it correctly ordered with
                    // any activities we previously moved.

                    // Handle this activity after we have done traversing the hierarchy.
                    mPendingReparentActivities.add(r);
                    return false;
                }
            }
            if (mForceReset || finishOnTaskLaunch || clearWhenTaskReset) {
                // If the activity should just be removed either because it asks for it, or the
                // task should be cleared, then finish it and anything that is part of its reply
                // chain.
                if (clearWhenTaskReset) {
                    // In this case, we want to finish this activity and everything above it,
                    // so be sneaky and pretend like these are all in the reply chain.
                    finishActivities(mAllActivities, "clearWhenTaskReset");
                } else {
                    mResultActivities.add(r);
                    finishActivities(mResultActivities, "reset-task");
                }

                mResultActivities.clear();
                return false;
            } else {
                // If we were in the middle of a chain, well the activity that started it all
                // doesn't want anything special, so leave it all as-is.
                mResultActivities.clear();
            }

            return false;

        } else {
            mResultActivities.add(r);
            if (r.resultTo != null) {
                // If this activity is sending a reply to a previous activity, we can't do
                // anything with it now until we reach the start of the reply chain.
                // NOTE: that we are assuming the result is always to the previous activity,
                // which is almost always the case but we really shouldn't count on.
                return false;
            } else if (mTargetTaskFound && allowTaskReparenting && mTargetTask.affinity != null
                    && mTargetTask.affinity.equals(r.taskAffinity)) {
                // This activity has an affinity for our task. Either remove it if we are
                // clearing or move it over to our task. Note that we currently punt on the case
                // where we are resetting a task that is not at the top but who has activities
                // above with an affinity to it... this is really not a normal case, and we will
                // need to later pull that task to the front and usually at that point we will
                // do the reset and pick up those remaining activities. (This only happens if
                // someone starts an activity in a new task from an activity in a task that is
                // not currently on top.)
                if (mForceReset || finishOnTaskLaunch) {
                    finishActivities(mResultActivities, "move-affinity");
                    return false;
                }
                if (mActivityReparentPosition == -1) {
                    mActivityReparentPosition = mTargetTask.getChildCount();
                }

                processResultActivities(
                        r, mTargetTask, mActivityReparentPosition, false, false);

                // Now we've moved it in to place...but what if this is a singleTop activity and
                // we have put it on top of another instance of the same activity? Then we drop
                // the instance below so it remains singleTop.
                if (r.info.launchMode == ActivityInfo.LAUNCH_SINGLE_TOP) {
                    final ActivityRecord p = mTargetTask.getActivityBelow(r);
                    if (p != null) {
                        if (p.intent.getComponent().equals(r.intent.getComponent())) {
                            p.finishIfPossible("replace", false /* oomAdj */);
                        }
                    }
                }
            }
            return false;
        }
    }

    private void finishActivities(ArrayList<ActivityRecord> activities, String reason) {
        boolean noOptions = mCanMoveOptions;

        while (!activities.isEmpty()) {
            final ActivityRecord p = activities.remove(0);
            if (p.finishing) continue;

            noOptions = takeOption(p, noOptions);

            if (DEBUG_TASKS) Slog.w(TAG_TASKS,
                    "resetTaskIntendedTask: calling finishActivity on " + p);
            p.finishIfPossible(reason, false /* oomAdj */);
        }
    }

    private void processResultActivities(ActivityRecord target, Task targetTask, int position,
            boolean ignoreFinishing, boolean takeOptions) {
        boolean noOptions = mCanMoveOptions;

        while (!mResultActivities.isEmpty()) {
            final ActivityRecord p = mResultActivities.remove(0);
            if (ignoreFinishing&& p.finishing) continue;

            if (takeOptions) {
                noOptions = takeOption(p, noOptions);
            }
            if (DEBUG_ADD_REMOVE) Slog.i(TAG_ADD_REMOVE, "Removing activity " + p + " from task="
                    + mTask + " adding to task=" + targetTask + " Callers=" + Debug.getCallers(4));
            if (DEBUG_TASKS) Slog.v(TAG_TASKS,
                    "Pushing next activity " + p + " out to target's task " + target);
            p.reparent(targetTask, position, "resetTargetTaskIfNeeded");
        }
    }

    private void processPendingReparentActivities() {
        if (mPendingReparentActivities.isEmpty()) {
            return;
        }

        final ActivityTaskManagerService atmService = mTargetStack.mAtmService;
        TaskDisplayArea taskDisplayArea = mTargetStack.getDisplayArea();
        final boolean singleTaskInstanceDisplay =
                taskDisplayArea.mDisplayContent.isSingleTaskInstance();
        if (singleTaskInstanceDisplay) {
            taskDisplayArea = atmService.mRootWindowContainer.getDefaultTaskDisplayArea();
        }

        final int windowingMode = mTargetStack.getWindowingMode();
        final int activityType = mTargetStack.getActivityType();

        while (!mPendingReparentActivities.isEmpty()) {
            final ActivityRecord r = mPendingReparentActivities.remove(0);
            final boolean alwaysCreateTask = DisplayContent.alwaysCreateStack(windowingMode,
                    activityType);
            final Task task = alwaysCreateTask
                    ? taskDisplayArea.getBottomMostTask() : mTargetStack.getBottomMostTask();
            Task targetTask = null;
            if (task != null && r.taskAffinity.equals(task.affinity)) {
                // If the activity currently at the bottom has the same task affinity as
                // the one we are moving, then merge it into the same task.
                targetTask = task;
                if (DEBUG_TASKS) Slog.v(TAG_TASKS, "Start pushing activity "
                        + r + " out to bottom task " + targetTask);
            }
            if (targetTask == null) {
                if (alwaysCreateTask) {
                    targetTask = taskDisplayArea.getOrCreateStack(windowingMode,
                            activityType, false /* onTop */);
                } else {
                    targetTask = mTargetStack.reuseOrCreateTask(r.info, null /*intent*/,
                            false /*toTop*/);
                }
                targetTask.affinityIntent = r.intent;
            }
            r.reparent(targetTask, 0 /* position */, "resetTargetTaskIfNeeded");
            atmService.mStackSupervisor.mRecentTasks.add(targetTask);
        }
    }

    private boolean takeOption(ActivityRecord p, boolean noOptions) {
        mCanMoveOptions = false;
        if (noOptions && mTopOptions == null) {
            mTopOptions = p.takeOptionsLocked(false /* fromClient */);
            if (mTopOptions != null) {
                noOptions = false;
            }
        }
        return noOptions;
    }
}
