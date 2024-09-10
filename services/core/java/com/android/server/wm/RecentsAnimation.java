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

import static android.app.ActivityManager.PROCESS_STATE_CACHED_ACTIVITY;
import static android.app.WindowConfiguration.ACTIVITY_TYPE_HOME;
import static android.app.WindowConfiguration.ACTIVITY_TYPE_RECENTS;
import static android.app.WindowConfiguration.WINDOWING_MODE_UNDEFINED;
import static android.content.Intent.FLAG_ACTIVITY_NEW_TASK;
import static android.content.Intent.FLAG_ACTIVITY_NO_ANIMATION;

import static com.android.internal.protolog.ProtoLogGroup.WM_DEBUG_RECENTS_ANIMATIONS;
import static com.android.server.wm.ActivityRecord.State.STOPPED;
import static com.android.server.wm.ActivityRecord.State.STOPPING;

import android.annotation.Nullable;
import android.app.ActivityOptions;
import android.content.ComponentName;
import android.content.Intent;
import android.util.Slog;

import com.android.internal.protolog.ProtoLog;
import com.android.internal.util.function.pooled.PooledLambda;
import com.android.internal.util.function.pooled.PooledPredicate;

/**
 * Manages the recents animation, including the reordering of the root tasks for the transition and
 * cleanup. See {@link com.android.server.wm.RecentsAnimationController}.
 */
class RecentsAnimation {
    private static final String TAG = RecentsAnimation.class.getSimpleName();

    private final ActivityTaskSupervisor mTaskSupervisor;
    private final ActivityStartController mActivityStartController;
    private final TaskDisplayArea mDefaultTaskDisplayArea;
    private final Intent mTargetIntent;
    private final ComponentName mRecentsComponent;
    private final @Nullable String mRecentsFeatureId;
    private final int mRecentsUid;
    private final int mUserId;
    private final int mTargetActivityType;

    RecentsAnimation(ActivityTaskManagerService atm, ActivityTaskSupervisor taskSupervisor,
            ActivityStartController activityStartController,
            Intent targetIntent, ComponentName recentsComponent, @Nullable String recentsFeatureId,
            int recentsUid) {
        mTaskSupervisor = taskSupervisor;
        mDefaultTaskDisplayArea = atm.mRootWindowContainer.getDefaultTaskDisplayArea();
        mActivityStartController = activityStartController;
        mTargetIntent = targetIntent;
        mRecentsComponent = recentsComponent;
        mRecentsFeatureId = recentsFeatureId;
        mRecentsUid = recentsUid;
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
            if (targetActivity.attachedToProcess()) {
                if (targetActivity.isVisibleRequested() || targetActivity.isTopRunningActivity()) {
                    // The activity is ready.
                    return;
                }
                if (targetActivity.app.getCurrentProcState() >= PROCESS_STATE_CACHED_ACTIVITY) {
                    Slog.v(TAG, "Skip preload recents for cached proc " + targetActivity.app);
                    // The process may be frozen that cannot receive binder call.
                    return;
                }
                // The activity may be relaunched if it cannot handle the current configuration
                // changes. The activity will be paused state if it is relaunched, otherwise it
                // keeps the original stopped state.
                targetActivity.ensureActivityConfiguration(true /* ignoreVisibility */);
                ProtoLog.d(WM_DEBUG_RECENTS_ANIMATIONS, "Updated config=%s",
                        targetActivity.getConfiguration());
            }
        } else if (mDefaultTaskDisplayArea.getActivity(
                ActivityRecord::occludesParent, false /* traverseTopToBottom */) == null) {
            // Skip because none of above activities can occlude the target activity. The preload
            // should be done silently in background without being visible.
            return;
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
        if (!targetActivity.finishing && targetActivity.isAttached()
                && !targetActivity.isState(STOPPING, STOPPED)) {
            // Add to stopping instead of stop immediately. So the client has the chance to perform
            // traversal in non-stopped state (ViewRootImpl.mStopped) that would initialize more
            // things (e.g. the measure can be done earlier). The actual stop will be performed when
            // it reports idle.
            targetActivity.addToStopping(true /* scheduleIdle */, true /* idleDelayed */,
                    "preloadRecents");
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
        return task.getNonFinishingActivityCount() > 0 && task.mUserId == mUserId
                && task.getBaseIntent().getComponent().equals(mTargetIntent.getComponent());
    }
}
